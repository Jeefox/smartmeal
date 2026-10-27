import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import type { ShoppingSession } from '../src/shopping-session';
const id = '11111111-1111-4111-8111-111111111111',
  itemId = '22222222-2222-4222-8222-222222222222';
async function fixture(page: Page) {
  const view: ShoppingSession = {
    id,
    generationId: id,
    from: '2026-10-26',
    to: '2026-10-30',
    revision: 0,
    pantryRevision: 0,
    today: '2026-10-25',
    status: 'OPEN',
    budgetRub: 4000,
    participants: 2,
    knownEstimateRub: 69,
    unknownEstimateProducts: 0,
    knownActualTotalRub: 0,
    purchasesWithoutPrice: 0,
    overlappingOpenSessions: false,
    appliedPurchaseKeys: [],
    items: [
      {
        id: itemId,
        snapshot: {
          productId: 1,
          productName: 'Гречка',
          requiredG: 600,
          needToBuyG: 600,
          purchasedG: 900,
          packageWeightG: 900,
          packageCount: 1,
          purchaseCost: 69,
          packagePrice: 69,
          chainId: 1,
          chainName: 'Чижик',
          estimated: true,
          sourceName: 'Каталог',
        },
        requiredToBuyG: 600,
        acquiredG: 0,
        remainingG: 600,
        suggestedPurchaseG: 900,
        packageCount: 1,
        picked: false,
        skipped: false,
      },
    ],
  };
  const state = {
    view,
    opens: 0,
    marks: 0,
    purchases: [] as { key: string; body: any }[],
    failure: null as null | 'before' | 'after',
    conflict: false,
  };
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.context().route('**/api/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname,
      method = req.method();
    if (path === '/api/me/profile')
      return route.fulfill({ json: { userId: 'shopping-user', dailyCalorieTarget: 2000 } });
    if (path === '/api/auth/csrf')
      return route.fulfill({
        json: { token: 'test', headerName: 'X-CSRF', parameterName: '_csrf' },
      });
    if (path === '/api/me/shopping-sessions') {
      if (method === 'POST') {
        state.opens++;
        return route.fulfill({ json: state.view });
      }
      return route.fulfill({
        json: {
          items: [
            { id, from: view.from, to: view.to, status: view.status, revision: view.revision },
          ],
          page: 0,
          hasNext: false,
        },
      });
    }
    if (path === `/api/me/shopping-sessions/${id}`) return route.fulfill({ json: state.view });
    if (path.endsWith(`/items/${itemId}`)) {
      state.marks++;
      const b = req.postDataJSON();
      if (b.expectedRevision !== view.revision)
        return route.fulfill({
          status: 409,
          json: { code: 'SHOPPING_SESSION_STALE', detail: 'Список изменился' },
        });
      view.items[0].picked = b.picked;
      view.items[0].skipped = b.skipped;
      view.revision++;
      view.status = b.skipped ? 'COMPLETED' : 'OPEN';
      return route.fulfill({ json: view });
    }
    if (path.endsWith('/purchases')) {
      const key = req.headers()['idempotency-key'],
        body = req.postDataJSON();
      state.purchases.push({ key, body });
      if (state.conflict) {
        state.conflict = false;
        view.pantryRevision++;
        return route.fulfill({
          status: 409,
          json: { code: 'PANTRY_STALE', detail: 'Запасы изменились' },
        });
      }
      if (state.failure === 'before') return route.abort('failed');
      if (!view.appliedPurchaseKeys.includes(key)) {
        const l = body.items[0],
          i = view.items[0];
        i.acquiredG += Number(l.quantityG);
        i.remainingG = Math.max(600 - i.acquiredG, 0);
        i.suggestedPurchaseG = i.remainingG > 0 ? 900 : 0;
        i.packageCount = i.remainingG > 0 ? 1 : 0;
        i.picked = false;
        view.revision++;
        view.pantryRevision++;
        view.status = i.remainingG === 0 ? 'COMPLETED' : 'OPEN';
        if (l.actualTotalRub === null) view.purchasesWithoutPrice++;
        else view.knownActualTotalRub += Number(l.actualTotalRub);
        view.appliedPurchaseKeys.push(key);
      }
      if (state.failure === 'after') return route.abort('failed');
      return route.fulfill({ json: view });
    }
    return route.fulfill({ status: 404, json: { detail: 'Нет тестовых данных' } });
  });
  return state;
}
async function pick(page: Page) {
  await page.getByRole('checkbox', { name: 'Собрано: Гречка' }).check();
  await expect(page.getByText('Отметка сохранена на сервере.', { exact: true })).toBeVisible();
}
async function confirm(page: Page, quantity = '900') {
  await page.getByRole('button', { name: /Добавить покупку в запасы/ }).click();
  await page.getByLabel('Фактическая масса, г').fill(quantity);
}
test('saved source opens one list and marking survives another page', async ({ page }) => {
  const s = await fixture(page);
  await page.goto(`/shopping-lists?source=${id}`);
  await expect(page).toHaveURL(new RegExp(`/shopping-lists/${id}$`));
  await pick(page);
  const other = await page.context().newPage();
  await other.goto(`/shopping-lists/${id}`);
  await expect(other.getByRole('checkbox', { name: 'Собрано: Гречка' })).toBeChecked();
  expect(s.opens).toBe(1);
  expect(s.purchases).toHaveLength(0);
  await other.close();
});
test('one confirmation adds actual partial weight and preserves missing price', async ({
  page,
}) => {
  const s = await fixture(page);
  await page.goto(`/shopping-lists/${id}`);
  await pick(page);
  await confirm(page, '400');
  await expect(page.getByLabel('Сумма за всю строку, ₽ · необязательно')).toHaveValue('');
  await page.getByRole('button', { name: 'Добавить в запасы', exact: true }).click();
  await expect(page.getByText('Куплено частично · осталось 200 г')).toBeVisible();
  expect(s.purchases[0].body.items[0].actualTotalRub).toBeNull();
  expect(s.view.items[0].acquiredG).toBe(400);
  await page.reload();
  await expect(page.getByText('Куплено частично · осталось 200 г')).toBeVisible();
});
test('response lost after purchase reconciles without another POST', async ({ page }) => {
  const s = await fixture(page);
  s.failure = 'after';
  await page.goto(`/shopping-lists/${id}`);
  await pick(page);
  await confirm(page);
  await page.getByRole('button', { name: 'Добавить в запасы', exact: true }).click();
  await expect(
    page.getByText('Покупка уже добавлена в запасы. Повторять её не нужно.'),
  ).toBeVisible();
  await page.reload();
  await expect(page.getByText('Список завершён: куплено 1, пропущено 0')).toBeVisible();
  expect(s.purchases).toHaveLength(1);
});
test('failure before purchase keeps original key and body across reload', async ({ page }) => {
  const s = await fixture(page);
  s.failure = 'before';
  await page.goto(`/shopping-lists/${id}`);
  await pick(page);
  await confirm(page, '400');
  await page.getByRole('button', { name: 'Добавить в запасы', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Действие ещё не подтверждено' })).toBeVisible();
  await expect(
    page.getByText('Не удалось связаться с сервером. Проверьте сеть и запуск Spring Boot.', {
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Проверить и повторить покупку' })).toBeEnabled();
  expect(s.purchases).toHaveLength(1);
  const original = s.purchases[0];
  await page.reload();
  s.failure = null;
  await page.getByRole('button', { name: 'Проверить и повторить покупку' }).click();
  await expect(page.getByText('Куплено частично · осталось 200 г')).toBeVisible();
  expect(s.purchases).toHaveLength(2);
  expect(s.purchases[1]).toEqual(original);
});
test('pantry conflict preserves weight and requires fresh confirmation', async ({ page }) => {
  const s = await fixture(page);
  s.conflict = true;
  await page.goto(`/shopping-lists/${id}`);
  await pick(page);
  await confirm(page, '400');
  await page.getByRole('button', { name: 'Добавить в запасы', exact: true }).click();
  await expect(page.getByText('Данные обновились.', { exact: false })).toBeVisible();
  expect(s.purchases).toHaveLength(1);
  await page.getByRole('button', { name: /Добавить покупку в запасы/ }).click();
  await expect(page.getByLabel('Фактическая масса, г')).toHaveValue('400');
  await page.getByRole('button', { name: 'Добавить в запасы', exact: true }).click();
  await expect(page.getByText('Куплено частично · осталось 200 г')).toBeVisible();
  expect(s.purchases[1].body.expectedPantryRevision).toBe(1);
  expect(s.purchases[1].key).not.toBe(s.purchases[0].key);
});
test('zero actual total is explicit and cancellation never purchases', async ({ page }) => {
  const s = await fixture(page);
  await page.goto(`/shopping-lists/${id}`);
  await pick(page);
  await confirm(page);
  await page.getByLabel('Сумма за всю строку, ₽ · необязательно').fill('0');
  await page.getByRole('button', { name: 'Отмена', exact: true }).click();
  expect(s.purchases).toHaveLength(0);
  await page.getByRole('button', { name: /Добавить покупку в запасы/ }).click();
  await expect(page.getByLabel('Сумма за всю строку, ₽ · необязательно')).toHaveValue('0');
  await page.getByRole('button', { name: 'Добавить в запасы', exact: true }).click();
  await expect(page.getByText('Список завершён: куплено 1, пропущено 0')).toBeVisible();
  expect(s.purchases[0].body.items[0].actualTotalRub).toBe('0');
});
test('skip and restore never create stock, unknown prices remain explicit', async ({ page }) => {
  const s = await fixture(page);
  s.view.unknownEstimateProducts = 1;
  s.view.knownEstimateRub = 0;
  s.view.items[0].snapshot.purchaseCost = null;
  await page.goto(`/shopping-lists/${id}`);
  await expect(page.getByText('Известная часть оценки покупок:', { exact: false })).toBeVisible();
  await page.getByText('Количество и источник цены').click();
  await page.getByRole('button', { name: 'Не покупать', exact: true }).click();
  await expect(page.getByText('Список завершён: куплено 0, пропущено 1')).toBeVisible();
  await page.getByRole('button', { name: 'Все', exact: true }).click();
  await page.getByText('Количество и источник цены').click();
  await page.getByRole('button', { name: 'Вернуть в список' }).click();
  await expect(page.getByRole('checkbox', { name: 'Собрано: Гречка' })).toBeEnabled();
  expect(s.purchases).toHaveLength(0);
});
test('offline mark is local until explicit retry', async ({ page }) => {
  const s = await fixture(page);
  await page.goto(`/shopping-lists/${id}`);
  await expect(page.getByRole('checkbox', { name: 'Собрано: Гречка' })).toBeVisible();
  await page.context().setOffline(true);
  await page.getByRole('checkbox', { name: 'Собрано: Гречка' }).check();
  await expect(page.getByRole('button', { name: 'Отправить сохранённую отметку' })).toBeDisabled();
  expect(s.marks).toBe(0);
  await page.context().setOffline(false);
  await page.getByRole('button', { name: 'Отправить сохранённую отметку' }).click();
  await expect(page.getByText('Отметка сохранена на сервере.', { exact: true })).toBeVisible();
  expect(s.marks).toBe(1);
});
test('shopping navigation opens server index without creating lists', async ({ page }) => {
  const s = await fixture(page);
  await page.goto('/shopping');
  await expect(page.getByRole('heading', { name: 'Списки покупок' })).toBeVisible();
  await page.getByRole('link', { name: /Продолжить покупки/ }).click();
  await expect(page.getByRole('heading', { name: 'Что купить', exact: true })).toBeVisible();
  expect(s.opens).toBe(0);
});
test('mobile list and dialog fit, action is above navigation', async ({ page }) => {
  await fixture(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto(`/shopping-lists/${id}`);
  await pick(page);
  await expect(page.getByRole('banner')).toContainText('Покупки');
  await expect(
    page
      .getByRole('navigation', { name: 'Мобильная навигация' })
      .getByRole('link', { name: 'Покупки' }),
  ).toHaveAttribute('aria-current', 'page');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const a = await page.getByRole('region', { name: 'Покупка продуктов' }).boundingBox(),
    b = await page.getByRole('navigation', { name: 'Мобильная навигация' }).boundingBox();
  expect(a!.y + a!.height).toBeLessThanOrEqual(b!.y);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: '/tmp/smartmeal-shopping-mobile.png', fullPage: true });
  await confirm(page);
  await expect(page.getByRole('dialog', { name: 'Подтвердить фактическую покупку' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button', { name: /Добавить покупку в запасы/ })).toBeFocused();
});
