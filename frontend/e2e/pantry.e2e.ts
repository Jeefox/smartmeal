import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
async function fixture(page: Page) {
  const state = {
    revision: 0,
    lots: [] as any[],
    calls: [] as { path: string; key: string; body: any }[],
    lostResponse: false,
    appliedKey: '',
    planState: 'OPEN',
    planStatus: 'CONFIRMED',
  };
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname,
      method = req.method();
    if (path === '/api/me/profile')
      return route.fulfill({ json: { userId: 'pantry-user', dailyCalorieTarget: 2000 } });
    if (path === '/api/auth/csrf')
      return route.fulfill({
        json: { token: 'test', headerName: 'X-CSRF', parameterName: '_csrf' },
      });
    if (path === '/api/products' || path === '/api/products/1') {
      const product = { id: 1, name: 'Гречка', categoryId: 1 };
      return route.fulfill({
        json: path.endsWith('/1')
          ? product
          : { content: [product], page: 0, size: 30, totalElements: 1, totalPages: 1 },
      });
    }
    if (path === '/api/me/pantry' && method === 'GET')
      return route.fulfill({
        json: {
          revision: state.revision,
          today: '2026-10-22',
          lots: state.lots,
          unconfirmedPastEntries: 0,
        },
      });
    if (path === '/api/me/pantry/operations')
      return route.fulfill({ json: { page: 0, hasNext: false, items: [] } });
    if (path === '/api/me/meal-plans' && method === 'GET')
      return route.fulfill({
        json: {
          id: 'plan',
          planDate: '2026-10-22',
          status: state.planStatus,
          dailyCalorieTarget: 2000,
          dailyCalorieTargetSnapshot: 2000,
          confirmedAt: '2026-10-22T10:00:00Z',
          createdAt: '2026-10-22T10:00:00Z',
          updatedAt: '2026-10-22T10:00:00Z',
          nutritionTotal: { calories: 350, protein: 12, fat: 3, carbs: 60 },
          entries: [
            {
              id: 'entry',
              mealPlanId: 'plan',
              mealType: 'LUNCH',
              portions: 1,
              position: 1,
              nutrition: { calories: 350, protein: 12, fat: 3, carbs: 60 },
              recipeSnapshot: {
                recipeId: 'recipe',
                name: 'Гречка на обед',
                description: null,
                instructions: 'Сварить',
                preparationTimeMin: 10,
                servings: 1,
                ingredients: [{ productId: 1, productName: 'Гречка', quantityG: 100 }],
                nutritionTotal: { calories: 350, protein: 12, fat: 3, carbs: 60 },
                nutritionPerServing: { calories: 350, protein: 12, fat: 3, carbs: 60 },
              },
            },
          ],
        },
      });
    if (path === '/api/me/meal-plans/plan/inventory')
      return route.fulfill({
        json: {
          revision: state.revision,
          entries: [{ entryId: 'entry', state: state.planState, reservedG: { 1: 100 } }],
        },
      });
    if (
      (path.startsWith('/api/me/pantry') && method !== 'GET') ||
      path.includes('/ingredient-usage') ||
      path.includes('/inventory-state') ||
      path.endsWith('/cancel') ||
      path.endsWith('/reopen')
    ) {
      const body = req.postDataJSON(),
        key = req.headers()['idempotency-key'];
      state.calls.push({ path, key, body });
      if (path === '/api/me/pantry/lots' || path === '/api/me/pantry/purchases') {
        if (state.appliedKey !== key) {
          const item = body.items?.[0] || body;
          state.lots.push({
            id: 'lot-1',
            productId: 1,
            productName: 'Гречка',
            quantityOnHandG: item.quantityG,
            reservedG: 0,
            freeG: item.quantityG,
            receivedOn: item.receivedOn,
            expiresOn: item.expiresOn,
            source: 'INITIAL',
            expired: false,
          });
          state.revision++;
          state.appliedKey = key;
        }
        if (state.lostResponse) {
          state.lostResponse = false;
          return route.abort('failed');
        }
      } else if (path.endsWith('/cancel')) {
        state.planStatus = 'CANCELLED';
        state.revision++;
      } else if (path.endsWith('/reopen')) {
        state.planStatus = 'DRAFT';
        state.revision++;
      } else if (path.endsWith('/adjustments')) {
        state.lots[0].quantityOnHandG = body.quantityG;
        state.lots[0].freeG = body.quantityG;
        state.revision++;
      } else if (path.endsWith('/ingredient-usage')) {
        state.planState = 'DEDUCTED';
        state.revision++;
      } else if (path.endsWith('/reversal')) {
        state.planState = 'OPEN';
        state.revision++;
      } else if (path.endsWith('/inventory-state')) {
        state.planState = body.state;
        state.revision++;
      }
      return route.fulfill({
        json: { operationId: 'op', revision: state.revision, lotIds: ['lot-1'] },
      });
    }
    return route.fulfill({ status: 404, json: { detail: 'Нет данных' } });
  });
  return state;
}
test('add stock, correct actual balance and see persisted availability', async ({ page }) => {
  const state = await fixture(page);
  await page.goto('/pantry');
  await page.getByRole('combobox', { name: 'Продукт', exact: true }).selectOption('1');
  await page.getByLabel('Масса, г', { exact: true }).fill('300');
  await page.getByRole('button', { name: 'Добавить в запасы' }).click();
  await expect(page.getByRole('heading', { name: 'Гречка', exact: true })).toBeVisible();
  await page.getByText('Сверить остаток или исправить партию').click();
  await page.getByLabel('Сейчас осталось, г').fill('125');
  await page.getByRole('button', { name: 'Установить фактический остаток' }).click();
  await expect.poll(() => state.lots[0].quantityOnHandG).toBe(125);
  expect(state.calls).toHaveLength(2);
  expect(state.calls[1].body.expectedRevision).toBe(1);
});
test('lost response retries the original purchase key and revision', async ({ page }) => {
  const state = await fixture(page);
  state.lostResponse = true;
  await page.goto('/pantry?purchase=1&productId=1&quantityG=900');
  await page.getByRole('button', { name: 'Добавить в запасы' }).click();
  await expect(page.getByText('Не удалось связаться с сервером.', { exact: false })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Гречка', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Добавить в запасы' }).click();
  await expect(page.getByText('Изменение сохранено.')).toBeVisible();
  expect(state.lots).toHaveLength(1);
  expect(state.calls).toHaveLength(2);
  expect(state.calls[1].key).toBe(state.calls[0].key);
  expect(state.calls[1].body.expectedRevision).toBe(0);
});
test('cooking deduction can be reversed and skipped from the menu', async ({ page }) => {
  const state = await fixture(page);
  await page.goto('/?date=2026-10-22');
  await page.getByRole('button', { name: 'Приготовил', exact: true }).click();
  await expect(page.getByText('Ингредиенты списаны', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Отменить ошибочное списание' }).click();
  await expect(page.getByRole('button', { name: 'Приготовил', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Пропустить', exact: true }).click();
  await expect(page.getByText('Блюдо пропущено')).toBeVisible();
  expect(state.calls).toHaveLength(3);
  expect(state.calls[2].body.expectedRevision).toBe(2);
});
test('mobile stock page stays within viewport and supports expiry filter', async ({ page }) => {
  const state = await fixture(page);
  state.lots = [
    {
      id: 'lot-1',
      productId: 1,
      productName: 'Гречка',
      quantityOnHandG: 300,
      reservedG: 0,
      freeG: 0,
      receivedOn: '2026-10-01',
      expiresOn: '2026-10-20',
      source: 'INITIAL',
      expired: true,
    },
  ];
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/pantry');
  await page.getByRole('combobox', { name: 'Показать', exact: true }).selectOption('expired');
  await expect(page.getByText('Не используется в меню', { exact: false })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.screenshot({ path: '/tmp/smartmeal-pantry-mobile.png', fullPage: true });
});
test('demo stock page does not call private API', async ({ page }) => {
  const calls: string[] = [];
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"demo"'));
  page.on('request', (r) => {
    if (r.url().includes('/api/me/pantry')) calls.push(r.url());
  });
  await page.goto('/pantry');
  await expect(page.getByText('Домашние запасы доступны через API')).toBeVisible();
  expect(calls).toEqual([]);
});

test('cancel day releases its workflow and can reopen as a draft', async ({ page }) => {
  const state = await fixture(page);
  await page.goto('/?date=2026-10-22');
  await page.getByRole('button', { name: 'Отменить день', exact: true }).click();
  await page.getByRole('button', { name: 'Подтвердить отмену дня' }).click();
  await expect(page.getByRole('button', { name: 'Восстановить в черновик' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Приготовил', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Восстановить в черновик' }).click();
  await expect(page.getByText('Черновик', { exact: true })).toBeVisible();
  expect(state.calls.map((c) => c.path)).toEqual([
    '/api/me/meal-plans/plan/cancel',
    '/api/me/meal-plans/plan/reopen',
  ]);
});
