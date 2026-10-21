import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
async function fixture(page: Page) {
  const state = {
    target: 2200 as number | null,
    genStatus: 200,
    saveStatus: 201,
    offlineSave: false,
    delay: 0,
    exists: false,
    options: null as any,
    saves: [] as any[],
    generations: [] as any[],
    warnings: ['TARGET_NOT_REACHED', 'RECENT_RECIPES_USED', 'CATALOGUE_SEARCH_LIMIT'],
  };
  const nutrition = { calories: 1900.25, protein: 100.5, fat: 65.1, carbs: 251.2 };
  function plan(date: string) {
    return {
      id: 'saved-plan',
      planDate: date,
      status: 'DRAFT',
      dailyCalorieTarget: 2200,
      dailyCalorieTargetSnapshot: null,
      confirmedAt: null,
      createdAt: '2026-10-20T00:00:00Z',
      updatedAt: '2026-10-20T00:00:00Z',
      nutritionTotal: nutrition,
      entries: (['BREAKFAST', 'LUNCH', 'DINNER'] as const).map((mealType, i) => ({
        id: `entry${i}`,
        mealPlanId: 'saved-plan',
        mealType,
        portions: [0.75, 1.25, 2][i],
        position: 1,
        nutrition: { calories: 633.42, protein: 33.5, fat: 21.7, carbs: 83.73 },
        recipeSnapshot: {
          recipeId: `recipe${i}`,
          name: `Серверное блюдо ${i}`,
          description: '',
          instructions: 'Готовьте',
          preparationTimeMin: 15,
          servings: 2,
          ingredients: [{ productId: 1, productName: 'Рис', quantityG: 150 }],
          nutritionTotal: nutrition,
          nutritionPerServing: nutrition,
        },
      })),
    };
  }
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname;
    if (path === '/api/me/profile')
      return route.fulfill({ json: { userId: 'preview-user', dailyCalorieTarget: state.target } });
    if (path === '/api/auth/csrf')
      return route.fulfill({
        json: { token: 'fresh-token', headerName: 'X-CSRF', parameterName: '_csrf' },
      });
    if (path === '/api/me/meal-plans' && req.method() === 'GET')
      return state.exists
        ? route.fulfill({ json: plan(url.searchParams.get('date')!) })
        : route.fulfill({ status: 404 });
    if (path === '/api/me/meal-plans/preview') {
      const body = req.postDataJSON();
      state.generations.push(body);
      state.options = body;
      const response = {
        planDate: body.planDate,
        dailyCalorieTarget: 2200,
        nutritionTotal: nutrition,
        calorieDeviation: -299.75,
        withinTargetTolerance: false,
        warnings: state.warnings,
        entries: (['BREAKFAST', 'LUNCH', 'DINNER'] as const).map((mealType, i) => ({
          mealType,
          recipeId: `recipe${i}`,
          recipeName: `Серверное блюдо ${i}`,
          portions: [0.75, 1.25, 2][i],
          preparationTimeMin: 15,
          nutrition: { calories: 633.42, protein: 33.5, fat: 21.7, carbs: 83.73 },
          lastPlannedDate: i === 0 ? '2026-10-19' : null,
          recipeFingerprint: String(i).repeat(64),
        })),
      };
      if (state.delay) await new Promise((resolve) => setTimeout(resolve, state.delay));
      return (
        state.genStatus === 200
          ? route.fulfill({ json: response })
          : route.fulfill({
              status: state.genStatus,
              json: { detail: 'Не удалось собрать три разных рецепта' },
            })
      ).catch(() => {});
    }
    if (path === '/api/me/meal-plans/preview/save') {
      state.saves.push({ body: req.postDataJSON(), csrf: req.headers()['x-csrf'] });
      if (state.delay) await new Promise((resolve) => setTimeout(resolve, state.delay));
      if (state.offlineSave) return route.abort('failed');
      if (state.saveStatus !== 201) return route.fulfill({ status: state.saveStatus, body: '' });
      state.exists = true;
      return route.fulfill({ status: 201, json: plan(req.postDataJSON().options.planDate) });
    }
    return route.fulfill({ status: 404 });
  });
  return state;
}
async function open(page: Page) {
  await page.goto('/menu-preview?date=2026-10-21');
  await expect(page.getByLabel('Дата меню', { exact: true })).toHaveValue('2026-10-21');
}
async function generate(page: Page) {
  await page.getByRole('button', { name: /Сформировать/ }).click();
  await expect(page.getByRole('region', { name: 'Предпросмотр меню', exact: true })).toBeVisible();
}
test('предпросмотр без записи, серверные итоги/предупреждения и точное отдельное сохранение', async ({
  page,
}) => {
  const state = await fixture(page);
  await open(page);
  await generate(page);
  expect(state.saves).toHaveLength(0);
  await expect(page.locator('.preview-meal')).toHaveCount(3);
  await expect(page.locator('.preview-day-total')).toContainText('−299,75'.replace('−', '-'));
  await expect(page.getByText('Поиск ограничен частью каталога.', { exact: false })).toBeVisible();
  await expect(page.getByText('Есть блюда из вашей истории', { exact: false })).toBeVisible();
  await expect(page.getByText('Не удалось подобрать калорийность', { exact: false })).toBeVisible();
  state.delay = 300;
  await page.getByRole('button', { name: 'Сохранить меню как черновик', exact: true }).dblclick();
  await expect(page).toHaveURL(/\/\?date=2026-10-21/);
  expect(state.saves).toHaveLength(1);
  expect(state.saves[0]).toEqual({
    csrf: 'fresh-token',
    body: {
      options: {
        planDate: '2026-10-21',
        breakfastMaxPreparationTimeMin: 15,
        lunchMaxPreparationTimeMin: 45,
        dinnerMaxPreparationTimeMin: 45,
      },
      dailyCalorieTarget: 2200,
      entries: (['BREAKFAST', 'LUNCH', 'DINNER'] as const).map((mealType, i) => ({
        mealType,
        recipeId: `recipe${i}`,
        portions: [0.75, 1.25, 2][i],
        recipeFingerprint: String(i).repeat(64),
      })),
    },
  });
  await expect(page.getByText('Серверное блюдо 0', { exact: true })).toBeVisible();
});
test('смена даты/настроек исключает старое сохранение и отбрасывает запоздавший ответ', async ({
  page,
}) => {
  const state = await fixture(page);
  await open(page);
  await generate(page);
  await page.getByLabel('Завтрак · максимум минут', { exact: true }).fill('20');
  await expect(page.locator('.menu-preview-result')).toHaveCount(0);
  state.delay = 800;
  await page.getByRole('button', { name: /Сформировать/ }).click();
  await page.getByLabel('Дата меню', { exact: true }).fill('2026-10-22');
  await page.waitForTimeout(1000);
  await expect(page.locator('.menu-preview-result')).toHaveCount(0);
  expect(state.saves).toHaveLength(0);
  state.delay = 0;
  await generate(page);
  await page.getByRole('button', { name: 'Сохранить меню как черновик', exact: true }).click();
  await expect(page).toHaveURL(/date=2026-10-22/);
  expect(state.saves[0].body.options.breakfastMaxPreparationTimeMin).toBe(20);
  expect(state.saves[0].body.options.planDate).toBe('2026-10-22');
});
test('существующее меню не затирается, missing target предлагает профиль, валидация до POST', async ({
  page,
}) => {
  const state = await fixture(page);
  state.exists = true;
  await open(page);
  await generate(page);
  await expect(
    page.getByRole('button', { name: 'Сохранить меню как черновик', exact: true }),
  ).toBeDisabled();
  await expect(page.getByText('На эту дату уже есть меню.', { exact: false })).toBeVisible();
  expect(state.saves).toHaveLength(0);
  await page.getByLabel('Обед · максимум минут', { exact: true }).fill('-1');
  await page.getByRole('button', { name: /Сформировать/ }).click();
  await expect(page.getByRole('alert')).toContainText('неотрицательное');
  expect(state.generations).toHaveLength(1);
  state.target = null;
  await page.reload();
  await expect(page.getByRole('button', { name: /Сформировать/ })).toBeDisabled();
  await expect(page.getByRole('link', { name: 'Задать цель в профиле' })).toBeVisible();
});
test('409/404 сохранения требуют нового preview, 403 без успеха/повтора, generation409 не фальсифицируется', async ({
  page,
}) => {
  const state = await fixture(page);
  await open(page);
  state.genStatus = 409;
  await page.getByRole('button', { name: /Сформировать/ }).click();
  await expect(page.getByRole('alert')).toContainText('три разных рецепта');
  await expect(page.locator('.menu-preview-result')).toHaveCount(0);
  state.genStatus = 200;
  await generate(page);
  state.saveStatus = 403;
  await page.getByRole('button', { name: 'Сохранить меню как черновик', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('CSRF');
  expect(state.saves).toHaveLength(1);
  await expect(page).toHaveURL(/menu-preview/);
  for (const status of [409, 404]) {
    state.saveStatus = status;
    await page.getByRole('button', { name: 'Сохранить меню как черновик', exact: true }).click();
    await expect(
      page.getByText('Этот вариант больше нельзя сохранить', { exact: false }),
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: 'Сохранить меню как черновик', exact: true }),
    ).toBeDisabled();
    await generate(page);
  }
  expect(state.saves).toHaveLength(3);
});
test('неопределённая сетевая запись не повторяется; offline/401 блокируют действия', async ({
  page,
}) => {
  const state = await fixture(page);
  await open(page);
  await generate(page);
  state.offlineSave = true;
  await page.getByRole('button', { name: 'Сохранить меню как черновик', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('меню могло сохраниться');
  await expect(
    page.getByRole('button', { name: 'Сохранить меню как черновик', exact: true }),
  ).toBeDisabled();
  expect(state.saves).toHaveLength(1);
  await page.context().setOffline(true);
  await expect(page.getByRole('button', { name: /Сформировать/ })).toBeDisabled();
  await page.context().setOffline(false);
  state.offlineSave = false;
  state.genStatus = 401;
  await page.getByRole('button', { name: /Сформировать/ }).click();
  await expect(page).toHaveURL(/login/);
  await expect(page.locator('.menu-preview-result')).toHaveCount(0);
});
test('вход из меню сохраняет дату, мобильные настройки и карточки без переполнения', async ({
  page,
}) => {
  await fixture(page);
  await page.goto('/?date=2026-10-21');
  await page.getByRole('link', { name: 'Подобрать меню', exact: true }).click();
  await expect(page.getByLabel('Дата меню', { exact: true })).toHaveValue('2026-10-21');
  await generate(page);
  for (const width of [360, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBe(true);
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() => page.locator('.sidebar').evaluate((e) => e.getBoundingClientRect().right))
    .toBeLessThanOrEqual(1);
  await page
    .locator('.menu-preview-result')
    .screenshot({ path: 'test-results/menu-preview-mobile.png' });
});
test('демо не обращается к генератору и не притворяется серверным подбором', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"demo"'));
  let calls = 0;
  await page.route('**/api/**', (route) => {
    calls++;
    return route.fulfill({ status: 500 });
  });
  await page.goto('/menu-preview');
  await expect(
    page.getByText('Предпросмотр доступен через SmartMeal API', { exact: true }),
  ).toBeVisible();
  expect(calls).toBe(0);
});
