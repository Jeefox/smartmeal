import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
async function fixture(page: Page) {
  const snapshot = {
    recipeId: 'recipe',
    name: 'Сохранённый рис',
    description: 'Снимок',
    preparationTimeMin: 20,
    servings: 2,
    instructions: 'Сварить рис.',
    ingredients: [{ productId: 1, productName: 'Рис', quantityG: 200 }],
    nutritionTotal: { calories: 700, protein: 14, fat: 2, carbs: 156 },
    nutritionPerServing: { calories: 350, protein: 7, fat: 1, carbs: 78 },
  };
  const state = {
    plans: {} as Record<string, any>,
    target: 2200 as number | null,
    conflict: false,
    concurrentCreate: false,
    calls: [] as { path: string; method: string; body: any }[],
  };
  function plan(date: string) {
    return {
      id: 'plan-' + date,
      planDate: date,
      status: 'DRAFT',
      dailyCalorieTarget: state.target,
      dailyCalorieTargetSnapshot: null,
      confirmedAt: null,
      createdAt: '2026-10-18T10:00:00Z',
      updatedAt: '2026-10-18T10:00:00Z',
      entries: [],
      nutritionTotal: { calories: 0, protein: 0, fat: 0, carbs: 0 },
    };
  }
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname,
      method = req.method(),
      body = req.postData() ? req.postDataJSON() : null;
    state.calls.push({ path, method, body });
    const reply = (data: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    if (path === '/api/auth/csrf')
      return reply({ token: 'fresh', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' });
    if (path === '/api/me/profile')
      return reply({
        userId: 'user',
        dailyCalorieTarget: state.target,
        age: 30,
        sex: 'MALE',
        heightCm: 180,
        weightKg: 80,
        activityLevel: 'LOW',
        goal: 'MAINTAIN_WEIGHT',
      });
    if (path === '/api/me/shopping-list')
      return reply({
        from: url.searchParams.get('from'),
        to: url.searchParams.get('to'),
        containsDrafts: false,
        items: [],
      });
    if (path === '/api/recipes')
      return reply({
        content: [
          {
            id: 'recipe',
            name: 'Текущий рис',
            description: 'Из каталога',
            preparationTime: 20,
            servings: 2,
          },
        ],
        page: 0,
        size: 12,
        totalElements: 1,
        totalPages: 1,
      });
    if (path === '/api/recipes/recipe')
      return reply({ ...snapshot, id: 'recipe', name: 'Изменённый текущий рецепт' });
    if (path === '/api/me/meal-plans' && method === 'GET') {
      const date = url.searchParams.get('date')!;
      const p = state.plans[date];
      if (!p) return reply({ detail: 'Меню не найдено' }, 404);
      return reply({
        ...p,
        dailyCalorieTarget: p.status === 'CONFIRMED' ? p.dailyCalorieTargetSnapshot : state.target,
      });
    }
    if (path === '/api/me/meal-plans' && method === 'POST') {
      const date = body.planDate;
      if (state.plans[date]) return reply({ detail: 'Меню существует' }, 409);
      state.plans[date] = plan(date);
      return state.concurrentCreate
        ? reply({ detail: 'Меню создано другим устройством' }, 409)
        : reply(state.plans[date], 201);
    }
    const p = Object.values(state.plans).find((p: any) => path.includes('/' + p.id + '/')) as any;
    if (p) {
      if (state.conflict) {
        p.status = 'CONFIRMED';
        p.dailyCalorieTargetSnapshot = state.target;
        return reply({ detail: 'Меню уже подтверждено' }, 409);
      }
      if (p.status === 'CONFIRMED') return reply({ detail: 'Меню недоступно для изменений' }, 409);
      if (path.endsWith('/confirm')) {
        if (!p.entries.length || !state.target)
          return reply({ detail: 'Пустое меню или нет цели' }, 409);
        p.status = 'CONFIRMED';
        p.dailyCalorieTargetSnapshot = state.target;
        p.confirmedAt = '2026-10-18T10:30:00Z';
        return reply(p);
      }
      if (path.endsWith('/entries') && method === 'POST') {
        const entry = {
          id: 'entry',
          mealPlanId: p.id,
          mealType: body.mealType,
          portions: body.portions,
          position: 1,
          recipeSnapshot: snapshot,
          nutrition: { calories: 333, protein: 10, fat: 4, carbs: 50 },
        };
        p.entries.push(entry);
        p.nutritionTotal = { calories: 1111, protein: 20, fat: 6, carbs: 60 };
        return reply(entry, 201);
      }
      if (method === 'PUT') {
        p.entries[0].portions = body.portions;
        p.entries[0].nutrition.calories = 456;
        p.nutritionTotal.calories = 1777;
        return reply(p.entries[0]);
      }
      if (method === 'DELETE') {
        p.entries = [];
        p.nutritionTotal = { calories: 0, protein: 0, fat: 0, carbs: 0 };
        return route.fulfill({ status: 204, body: '' });
      }
    }
    return reply({ detail: 'Не найдено' }, 404);
  });
  return { state, plan };
}
async function day(page: Page) {
  await page.goto('/');
  await page.locator('input[type=date]').fill('2026-10-18');
  await expect(page.getByRole('heading', { name: 'На этот день ещё нет меню' })).toBeVisible();
}
async function create(page: Page) {
  await day(page);
  await page.getByRole('button', { name: 'Создать меню на день', exact: true }).click();
  await expect(page.locator('.daily-overview')).toBeVisible();
}
async function add(page: Page) {
  await page.getByRole('button', { name: 'Добавить: Обед', exact: true }).click();
  const modal = page.getByRole('dialog');
  await modal.locator('.recipe-picker button').click();
  await modal.getByLabel('Количество порций').fill('0.5');
  await modal.getByRole('button', { name: 'Добавить блюдо', exact: true }).click();
  await expect(modal).toBeHidden();
  await expect(page.getByRole('button', { name: 'Сохранённый рис', exact: true })).toBeVisible();
}
test('создание, добавление, порции и подтверждение с серверными итогами и снимками', async ({
  page,
}) => {
  const { state } = await fixture(page);
  await create(page);
  await add(page);
  await expect(page.locator('.energy-row h2')).toContainText('1 111');
  await page.getByRole('spinbutton', { name: 'Порции: Сохранённый рис' }).fill('1.5');
  await page.getByRole('button', { name: 'Сохранить порции' }).click();
  await expect(page.locator('.energy-row h2')).toContainText('1 777');
  await page.getByRole('button', { name: 'Снимок блюда: Сохранённый рис' }).click();
  await expect(page.getByRole('dialog')).toContainText('Снимок блюда в меню');
  expect(state.calls.filter((c) => c.path === '/api/recipes/recipe')).toHaveLength(0);
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Подтвердить меню', exact: true }).click();
  await page.getByRole('button', { name: 'Подтвердить день', exact: true }).click();
  await expect(page.getByText('Меню подтверждено', { exact: false }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: 'Сохранить порции' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Добавить: Обед' })).toHaveCount(0);
  state.target = 3000;
  await page.reload();
  await expect(page.locator('.energy-row h2')).toContainText('2 200');
  expect(state.plans['2026-10-18'].dailyCalorieTargetSnapshot).toBe(2200);
});
test('удаление 204 заново получает итоги дня', async ({ page }) => {
  const { state } = await fixture(page);
  await create(page);
  await add(page);
  await page.getByRole('button', { name: 'Удалить: Сохранённый рис' }).click();
  await page.getByRole('button', { name: 'Удалить блюдо', exact: true }).click();
  await expect(page.locator('.meal-card')).toHaveCount(0);
  const idx = state.calls.findIndex((c) => c.method === 'DELETE');
  expect(
    state.calls.slice(idx + 1).some((c) => c.path === '/api/me/meal-plans' && c.method === 'GET'),
  ).toBe(true);
});
test('409 другого устройства обновляет подтверждённый статус без повторной записи', async ({
  page,
}) => {
  const { state } = await fixture(page);
  await create(page);
  await add(page);
  state.conflict = true;
  await page.getByRole('spinbutton', { name: 'Порции: Сохранённый рис' }).fill('2');
  await page.getByRole('button', { name: 'Сохранить порции' }).click();
  await expect(page.getByRole('alert')).toContainText('Меню уже подтверждено');
  await expect(page.getByRole('button', { name: 'Сохранить порции' })).toHaveCount(0);
  expect(state.calls.filter((c) => c.method === 'PUT')).toHaveLength(1);
});
test('нет цели и пустое меню не подтверждаются, неделя не создаёт меню сама', async ({ page }) => {
  const { state } = await fixture(page);
  state.target = null;
  await create(page);
  await expect(page.getByRole('button', { name: 'Подтвердить меню', exact: true })).toBeDisabled();
  await add(page);
  await expect(page.getByRole('button', { name: 'Подтвердить меню', exact: true })).toBeDisabled();
  await page.goto('/week');
  await expect(page.locator('.week-strip>button')).toHaveCount(7);
  expect(
    state.calls.filter((c) => c.path === '/api/me/meal-plans' && c.method === 'POST'),
  ).toHaveLength(1);
});
test('карточка рецепта создаёт день только после подтверждения добавления; переход к покупкам', async ({
  page,
}) => {
  const { state } = await fixture(page);
  state.concurrentCreate = true;
  await page.goto('/recipes/recipe');
  await page.getByRole('button', { name: 'Добавить в меню', exact: true }).click();
  const modal = page.getByRole('dialog');
  await modal.locator('input[type=date]').fill('2026-10-18');
  await expect(modal.getByText('Для этого дня ещё нет меню.', { exact: false })).toBeVisible();
  expect(state.calls.filter((c) => c.method === 'POST')).toHaveLength(0);
  await modal.getByRole('button', { name: 'Создать меню и добавить', exact: true }).click();
  await expect(modal).toBeHidden();
  expect(
    state.calls.filter((c) => c.path === '/api/me/meal-plans' && c.method === 'POST'),
  ).toHaveLength(1);
  expect(state.plans['2026-10-18'].entries).toHaveLength(1);
  await page.goto('/shopping?view=calendar');
  await expect(page.locator('main')).toContainText('На этот период нет продуктов');
  expect(state.calls.some((c) => c.path === '/api/me/shopping-list')).toBe(true);
});
test('мобильное меню 360/390/768/1440 без переполнения', async ({ page }) => {
  await fixture(page);
  await create(page);
  await add(page);
  for (const width of [360, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('heading', { name: 'Меню на день' }).click();
  await page.locator('.toast').waitFor({ state: 'hidden' });
  await page.screenshot({ path: 'test-results/live-menu-mobile.png', fullPage: true });
});

test('подтверждение другим устройством закрывает устаревший диалог удаления', async ({ page }) => {
  const { state } = await fixture(page);
  await create(page);
  await add(page);
  await page.getByRole('button', { name: 'Удалить: Сохранённый рис' }).click();
  state.conflict = true;
  await page.getByRole('button', { name: 'Удалить блюдо', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('alert')).toContainText('Меню уже подтверждено');
  expect(state.calls.filter((c) => c.method === 'DELETE')).toHaveLength(1);
  await expect(page.getByRole('button', { name: 'Удалить: Сохранённый рис' })).toHaveCount(0);
});
