import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import type { PeriodView, PlanningOptions } from '../src/period-planning';
async function fixture(page: Page) {
  const state = {
    view: null as PeriodView | null,
    creates: [] as PlanningOptions[],
    commits: [] as string[],
    partial: false,
    incomplete: false,
    recoverWithRepeats: false,
    searchLimited: false,
    family: false,
    expired: false,
  };
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      path = new URL(req.url()).pathname;
    if (path === '/api/me/profile')
      return route.fulfill({ json: { userId: 'period-user', dailyCalorieTarget: 2000 } });
    if (path === '/api/auth/csrf')
      return route.fulfill({
        json: { token: 'test', headerName: 'X-CSRF', parameterName: '_csrf' },
      });
    if (path === '/api/me/household-members')
      return route.fulfill({
        json: state.family
          ? [
              {
                id: '11111111-1111-4111-8111-111111111111',
                name: 'Анна',
                dailyCalorieTarget: 1500,
                allergenIds: [],
                excludedProductIds: [],
              },
            ]
          : [],
      });
    if (path === '/api/retail-chains')
      return route.fulfill({
        json: [
          { id: 1, name: 'Чижик' },
          { id: 2, name: 'Магнит' },
        ],
      });
    if (path === '/api/me/meal-plan-generations' && req.method() === 'POST') {
      const options = req.postDataJSON() as PlanningOptions;
      state.creates.push(options);
      const nutrition = { calories: 2000, protein: 100, fat: 70, carbs: 250 };
      const entries = Array.from({ length: 7 }, (_, day) =>
        ['BREAKFAST', 'LUNCH', 'DINNER'].map((meal, i) => {
          const date = `2035-01-${String(day + 1).padStart(2, '0')}`;
          return {
            id: date + '-' + meal,
            date,
            mealType: meal,
            portions: 1,
            locked: false,
            recipeFingerprint: 'hash',
            coverImageUrl: null,
            explanations: ['Подходит по времени'],
            recipe: {
              recipeId: `r-${day}-${meal}`,
              name: ['Овсянка', 'Гречка с курицей', 'Рыба'][i],
              description: null,
              instructions: 'Готовить',
              preparationTimeMin: 10,
              servings: 1,
              ingredients: [],
              nutritionTotal: { ...nutrition, calories: [500, 800, 700][i] },
              nutritionPerServing: { ...nutrition, calories: [500, 800, 700][i] },
            },
          };
        }),
      ).flat();
      state.view = {
        id: `preview-${state.creates.length}`,
        revision: 0,
        expiresAt: '2099-01-01T00:00:00Z',
        savedPlanIds: [],
        result: {
          options,
          dailyCalorieTarget: 2000,
          dataAsOf: '2035-01-01T10:00:00Z',
          entries,
          days: Array.from({ length: 7 }, (_, i) => ({
            date: `2035-01-${String(i + 1).padStart(2, '0')}`,
            nutrition,
            calorieDeviation: 0,
            withinTargetTolerance: true,
            entries: entries.slice(i * 3, i * 3 + 3),
          })),
          shopping: {
            items: [
              {
                productId: 1,
                productName: 'Гречка',
                requiredG: 450,
                purchasedG: 900,
                remainderG: 450,
                packageCount: 1,
                packageWeightG: 900,
                packagePrice: 90,
                purchaseCost: state.partial ? null : 90,
                chainId: 1,
                chainName: 'Чижик',
                offerName: 'Пачка гречки',
                estimated: false,
                sourceName: 'Наблюдение',
                sourceScope: 'Тула',
                sourceUrl: null,
                observedAt: '2035-01-01T00:00:00Z',
                expiresAt: '2035-01-10T00:00:00Z',
                applicability: 'STORE_OBSERVATION',
              },
            ],
            knownPurchaseCost: state.partial ? 0 : 90,
            estimatedTotal: state.partial ? null : 90,
            consumedIngredientCost: 45,
            pricedProducts: state.partial ? 0 : 1,
            estimatedProducts: 0,
            unknownProducts: state.partial ? 1 : 0,
            priceQuality: state.partial ? 'PARTIAL' : 'COMPLETE_OBSERVED',
            budgetStatus:
              options.budgetRub == null ? 'NOT_SET' : state.partial ? 'UNKNOWN' : 'WITHIN',
            chains: [{ id: 1, name: 'Чижик' }],
            savingAgainstSingleChain: 0,
          },
          issues: state.partial
            ? [{ code: 'PRICES_INCOMPLETE', message: 'Нет цены для одного продукта' }]
            : [],
          status: state.partial ? 'READY_WITH_WARNINGS' : 'READY',
          searchStatus: 'FOUND',
        },
      } as PeriodView;
      if (options.householdMemberIds?.length) {
        state.view.result.dailyCalorieTarget = 3500;
        state.view.result.participants = [
          { id: 'period-user', name: 'Вы', dailyCalorieTarget: 2000, accountOwner: true },
          {
            id: options.householdMemberIds[0],
            name: 'Анна',
            dailyCalorieTarget: 1500,
            accountOwner: false,
          },
        ];
        for (const entry of state.view.result.entries) {
          entry.portions = 1.75;
          const n = entry.recipe.nutritionPerServing;
          entry.personPortions = [
            { participantId: 'period-user', name: 'Вы', portions: 1, nutrition: n },
            {
              participantId: options.householdMemberIds[0],
              name: 'Анна',
              portions: 0.75,
              nutrition: {
                calories: n.calories * 0.75,
                protein: n.protein * 0.75,
                fat: n.fat * 0.75,
                carbs: n.carbs * 0.75,
              },
            },
          ];
        }
        for (const day of state.view.result.days) {
          day.nutrition = { calories: 3500, protein: 175, fat: 122.5, carbs: 437.5 };
          day.people = [
            {
              participantId: 'period-user',
              name: 'Вы',
              dailyCalorieTarget: 2000,
              nutrition: { calories: 2000, protein: 100, fat: 70, carbs: 250 },
            },
            {
              participantId: options.householdMemberIds[0],
              name: 'Анна',
              dailyCalorieTarget: 1500,
              nutrition: { calories: 1500, protein: 75, fat: 52.5, carbs: 187.5 },
            },
          ];
        }
      }
      if (
        state.incomplete &&
        !(
          state.recoverWithRepeats &&
          options.maxRecipeOccurrences >= 7 &&
          options.minRepeatGapDays === 0
        )
      ) {
        state.view.result.entries = state.view.result.entries.filter(
          (e) => e.date !== '2035-01-03' || e.mealType === 'BREAKFAST',
        );
        state.view.result.days[2].entries = state.view.result.days[2].entries.filter(
          (e) => e.mealType === 'BREAKFAST',
        );
        state.view.result.days[2].withinTargetTolerance = false;
        state.view.result.status = 'NO_FEASIBLE_FOUND';
        state.view.result.issues.unshift({
          code: 'NO_FEASIBLE_FOUND',
          message: 'Не удалось заполнить все приёмы пищи',
        });
      }
      if (state.searchLimited) state.view.result.searchStatus = 'LIMIT_REACHED';
      if (state.expired) state.view.expiresAt = '2000-01-01T00:00:00Z';
      return route.fulfill({ status: 201, json: state.view });
    }
    if (path.includes('/meal-plan-generations/')) {
      if (!state.view) return route.fulfill({ status: 404 });
      if (path.endsWith('/alternatives'))
        return route.fulfill({
          json: [
            {
              recipeId: 'omelet',
              name: 'Омлет с овощами',
              portions: 1,
              preparationTimeMin: 12,
              dayNutrition: { calories: 2030, protein: 100, fat: 70, carbs: 250 },
              newPurchaseCost: 120,
              deltaPurchaseCost: 30,
              budgetStatus: 'WITHIN',
              priceQuality: 'COMPLETE_OBSERVED',
            },
          ],
        });
      if (path.endsWith('/lock')) {
        const entryId = decodeURIComponent(path.split('/entries/')[1].replace('/lock', ''));
        for (const entry of state.view.result.entries)
          if (entry.id === entryId) entry.locked = req.postDataJSON().locked;
        state.view.revision++;
      } else if (path.includes('/entries/') && req.method() === 'PUT') {
        const entryId = decodeURIComponent(path.split('/entries/')[1]);
        const entry = state.view.result.entries.find((e) => e.id === entryId)!;
        entry.recipe.name = 'Омлет с овощами';
        entry.recipe.recipeId = 'omelet';
        state.view.result.shopping.knownPurchaseCost = 120;
        state.view.result.shopping.estimatedTotal = 120;
        state.view.revision++;
      } else if (path.endsWith('/commit')) {
        state.commits.push(req.headers()['idempotency-key']);
        state.view.savedPlanIds = Array.from({ length: 7 }, (_, i) => 'plan' + i);
        state.view.revision++;
      } else if (req.method() === 'POST') state.view.revision++;
      return route.fulfill({ json: state.view });
    }
    return route.fulfill({ json: [] });
  });
  return state;
}
test('week selection, replacement, pinning, basket and save', async ({ page }, testInfo) => {
  const state = await fixture(page);
  await page.goto('/menu-generation');
  await page.getByLabel('Начало периода').fill('2035-01-01');
  await page.getByLabel('Бюджет на покупки').fill('3000');
  await page.getByRole('button', { name: 'Подобрать меню', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Овсянка', exact: true })).toBeVisible();
  expect(state.creates[0].to).toBe('2035-01-07');
  if (process.env.SMARTMEAL_CAPTURE_UI)
    await page.screenshot({ path: testInfo.outputPath('period-menu.png'), fullPage: true });
  await page.getByRole('button', { name: 'Заменить', exact: true }).first().click();
  await expect(page.getByRole('dialog')).toContainText('Омлет с овощами');
  await page.getByRole('button', { name: 'Выбрать вариант 1' }).click();
  await expect(page.getByRole('heading', { name: 'Омлет с овощами', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Закрепить: Омлет с овощами' }).click();
  await expect(page.getByRole('button', { name: 'Открепить: Омлет с овощами' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await page.getByRole('button', { name: 'Пересобрать незакреплённое' }).click();
  await expect(page.getByRole('button', { name: 'Открепить: Омлет с овощами' })).toBeVisible();
  await page.getByRole('button', { name: 'К покупкам' }).click();
  await expect(page.locator('#period-shopping')).toContainText('1 уп. × 900 г');
  await page.getByRole('button', { name: 'Сохранить меню на весь период' }).click();
  await expect(page.getByText('Сохранено дней: 7.', { exact: false })).toBeVisible();
  expect(state.commits).toHaveLength(1);
});
test('unknown prices stay unknown on a narrow screen', async ({ page }, testInfo) => {
  const state = await fixture(page);
  state.partial = true;
  await page.setViewportSize({ width: 360, height: 900 });
  await page.goto('/menu-generation');
  await page.getByLabel('Начало периода').fill('2035-01-01');
  await page.getByLabel('Бюджет на покупки').fill('3000');
  await page.getByRole('button', { name: 'Подобрать меню', exact: true }).click();
  await expect(page.getByText('Бюджет проверить нельзя', { exact: true })).toBeVisible();
  if (process.env.SMARTMEAL_CAPTURE_UI)
    await page.screenshot({ path: testInfo.outputPath('period-mobile.png'), fullPage: true });
  await page.getByRole('button', { name: 'К покупкам' }).click();
  await expect(page.getByRole('button', { name: 'Сохранить меню на весь период' })).toBeDisabled();
  const sizes = await page.evaluate(() => ({
    width: innerWidth,
    scroll: document.documentElement.scrollWidth,
  }));
  expect(sizes.scroll).toBeLessThanOrEqual(sizes.width);
});

test('personal restrictions can be saved without SQL', async ({ page }) => {
  await fixture(page);
  let values = {
    allergenIds: [] as number[],
    excludedProductIds: [] as number[],
    equipmentIds: [] as number[],
  };
  await page.route('**/api/planning-restrictions', (route) =>
    route.fulfill({
      json: { allergens: [{ id: 1, name: 'Молоко' }], equipment: [{ id: 2, name: 'Сковорода' }] },
    }),
  );
  await page.route('**/api/products?**', (route) =>
    route.fulfill({
      json: {
        content: [{ id: 3, name: 'Рис' }],
        page: 0,
        size: 12,
        totalPages: 1,
        totalElements: 1,
      },
    }),
  );
  await page.route('**/api/me/planning-preferences', async (route) => {
    if (route.request().method() === 'PUT') values = route.request().postDataJSON();
    return route.fulfill({
      json: {
        values,
        excludedProducts: values.excludedProductIds.includes(3) ? [{ id: 3, name: 'Рис' }] : [],
      },
    });
  });
  await page.goto('/planning-preferences');
  await page.getByLabel('Молоко', { exact: true }).check();
  await page.getByLabel('Сковорода', { exact: true }).check();
  await page.getByLabel('Рис', { exact: true }).check();
  await page.getByRole('button', { name: 'Сохранить ограничения' }).click();
  await expect(page.getByText('Ограничения сохранены.', { exact: false })).toBeVisible();
  expect(values).toEqual({ allergenIds: [1], excludedProductIds: [3], equipmentIds: [2] });
});

test('demo settings do not read or write API', async ({ page }) => {
  const calls: string[] = [];
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"demo"'));
  await page.route('**/api/**', (route) => {
    calls.push(route.request().url());
    return route.fulfill({ json: [] });
  });
  await page.goto('/planning-preferences');
  await expect(page.getByText('Ограничения доступны в режиме API', { exact: true })).toBeVisible();
  await page.goto('/recipes/demo/planning');
  await expect(
    page.getByText('Настройки подбора доступны в режиме API', { exact: true }),
  ).toBeVisible();
  expect(calls).toHaveLength(0);
});

async function generateWeek(page: Page) {
  await page.goto('/menu-generation');
  await page.getByLabel('Начало периода').fill('2035-01-01');
  await page.getByRole('button', { name: 'Подобрать меню', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Овсянка', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'К покупкам' }).click();
}
test('partial menu explains disabled save next to the button and opens the missing day', async ({
  page,
}) => {
  const state = await fixture(page);
  state.incomplete = true;
  state.partial = true;
  await generateWeek(page);
  const save = page.getByRole('region', { name: 'Сохранение меню' });
  await expect(save).toContainText('не подобрано приёмов пищи — 2');
  await expect(page.getByRole('button', { name: 'Сохранить меню на весь период' })).toBeDisabled();
  await expect(
    page.getByRole('checkbox', { name: 'Я ознакомился с предупреждениями и сохраняю меню' }),
  ).toHaveCount(0);
  await save.getByRole('button', { name: 'Показать незаполненный день' }).click();
  await expect(page.locator('.period-day-heading')).toContainText('3 января');
  await expect(page.getByRole('heading', { name: 'Блюдо не подобрано', exact: true })).toHaveCount(
    2,
  );
  expect(state.commits).toHaveLength(0);
});
test('complete menu with price warnings saves after acknowledgement then shows success', async ({
  page,
}) => {
  const state = await fixture(page);
  state.partial = true;
  await generateWeek(page);
  const button = page.getByRole('button', { name: 'Сохранить меню на весь период' });
  await expect(button).toBeDisabled();
  await page
    .getByRole('checkbox', { name: 'Я ознакомился с предупреждениями и сохраняю меню' })
    .check();
  await expect(button).toBeEnabled();
  await button.click();
  await expect(page.getByRole('heading', { name: 'Сохранено дней: 7.' })).toBeVisible();
  await expect(button).toHaveCount(0);
  expect(state.commits).toHaveLength(1);
});
test('checklist survives reload and shopping navigation without writing stock or plans', async ({
  page,
}) => {
  const state = await fixture(page);
  await generateWeek(page);
  await page.getByRole('checkbox', { name: 'Взял: Гречка' }).check();
  await expect(page.getByText('Собрано 1 из 1')).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Что купить', exact: true })).toBeVisible();
  await expect(page.getByRole('checkbox', { name: 'Взял: Гречка' })).toBeChecked();
  await page.goto('/shopping');
  await expect(page.getByRole('heading', { name: 'Что купить', exact: true })).toBeVisible();
  await expect(page).toHaveURL(/tab=shopping/);
  expect(state.commits).toHaveLength(0);
  expect(state.creates).toHaveLength(1);
});
test('shopping quantities and save reason fit on a phone without opening item details', async ({
  page,
}) => {
  const state = await fixture(page);
  state.incomplete = true;
  await page.setViewportSize({ width: 360, height: 800 });
  await generateWeek(page);
  await expect(page.getByText('1 уп. × 900 г', { exact: true })).toBeVisible();
  await expect(page.getByText('не подобрано приёмов пищи — 2', { exact: false })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: '/tmp/smartmeal-shopping-ux-mobile.png', fullPage: true });
});
test('expiry has a recovery action rather than an unexplained disabled control', async ({
  page,
}) => {
  const state = await fixture(page);
  state.expired = true;
  await generateWeek(page);
  await expect(page.getByRole('region', { name: 'Сохранение меню' })).toContainText(
    'Срок предпросмотра истёк',
  );
  await expect(page.getByRole('button', { name: 'Создать свежий подбор' })).toBeEnabled();
  await expect(page.getByRole('button', { name: 'Сохранить меню на весь период' })).toBeDisabled();
});

test('whole period is visible and saves in one operation without opening every day', async ({
  page,
}) => {
  const state = await fixture(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/menu-generation');
  await page.getByLabel('Начало периода').fill('2035-01-01');
  await page.getByRole('button', { name: 'Подобрать меню', exact: true }).click();
  const overview = page.getByRole('region', { name: 'Меню на весь период' });
  await expect(overview).toContainText('7 из 7 дней заполнено');
  await expect(overview.locator('.period-overview-day')).toHaveCount(7);
  await expect(overview.locator('.period-overview-day').last()).toContainText('7 января');
  await expect(overview.locator('dd')).toHaveCount(21);
  await expect(overview).toContainText('Сохранять каждый день отдельно не нужно');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('button', { name: 'К сохранению всего периода' }).click();
  await page.getByRole('button', { name: 'Сохранить меню на весь период' }).click();
  await expect(page.getByRole('heading', { name: 'Что купить', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Сохранено дней: 7.' })).toBeVisible();
  expect(state.creates).toHaveLength(1);
  expect(state.commits).toHaveLength(1);
});

test('partial period can retry globally with explicit repeats and preserves the remaining restrictions', async ({
  page,
}) => {
  const state = await fixture(page);
  state.incomplete = true;
  state.recoverWithRepeats = true;
  await page.goto('/menu-generation');
  await page.getByLabel('Начало периода').fill('2035-01-01');
  await page.getByLabel('Бюджет на покупки').fill('3000');
  await page.getByRole('button', { name: 'Подобрать меню', exact: true }).click();
  const overview = page.getByRole('region', { name: 'Меню на весь период' });
  await expect(overview).toContainText('6 из 7 дней заполнено');
  await expect(overview).toContainText('Закреплённые блюда текущего варианта не переносятся');
  await page
    .getByRole('button', { name: 'Подобрать весь период с повторами', exact: true })
    .click();
  await expect(overview).toContainText('7 из 7 дней заполнено');
  await expect(page).toHaveURL(/id=preview-2&tab=menu/);
  expect(state.creates[1]).toEqual({
    ...state.creates[0],
    maxRecipeOccurrences: 7,
    minRepeatGapDays: 0,
  });
  expect(state.commits).toHaveLength(0);
  await page.reload();
  await expect(overview).toContainText('7 из 7 дней заполнено');
});

test('repeat recovery never presents an incomplete response as a complete menu', async ({
  page,
}) => {
  const state = await fixture(page);
  state.incomplete = true;
  await page.goto('/menu-generation');
  await page.getByLabel('Начало периода').fill('2035-01-01');
  await page.getByRole('button', { name: 'Подобрать меню', exact: true }).click();
  await page
    .getByRole('button', { name: 'Подобрать весь период с повторами', exact: true })
    .click();
  await expect(page).toHaveURL(/id=preview-2&tab=menu/);
  await expect(page.getByRole('region', { name: 'Меню на весь период' })).toContainText(
    '6 из 7 дней заполнено',
  );
  await expect(page.getByRole('button', { name: 'Сохранить меню на весь период' })).toBeDisabled();
  expect(state.commits).toHaveLength(0);
});

test('search limit explains early stop and retries whole period without changing repeat rules', async ({
  page,
}) => {
  const state = await fixture(page);
  state.incomplete = true;
  state.searchLimited = true;
  await page.goto('/menu-generation');
  await page.getByLabel('Начало периода').fill('2035-01-01');
  await page.getByRole('button', { name: 'Подобрать меню', exact: true }).click();
  const overview = page.getByRole('region', { name: 'Меню на весь период' });
  await expect(overview).toContainText('Поиск остановился до заполнения всех дней');
  await expect(
    page.getByRole('button', { name: 'Подобрать весь период с повторами', exact: true }),
  ).toHaveCount(0);
  state.incomplete = false;
  state.searchLimited = false;
  await page.getByRole('button', { name: 'Повторить подбор всего периода', exact: true }).click();
  await expect(overview).toContainText('7 из 7 дней заполнено');
  expect(state.creates[1]).toEqual(state.creates[0]);
  expect(state.commits).toHaveLength(0);
});

test("family preview separates account target and shows everyone's portion before saving", async ({
  page,
}) => {
  const state = await fixture(page);
  state.family = true;
  await page.goto('/menu-generation');
  await page.getByLabel('Начало периода').fill('2035-01-01');
  await page.getByRole('checkbox', { name: 'Анна · 1 500 ккал' }).check();
  await page.getByRole('button', { name: 'Подобрать меню', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Меню на 7 дн. · 2 чел.', exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Питание каждого', exact: true })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Порции по людям' }).first()).toContainText(
    'Анна: 0,75 порц.',
  );
  await expect(page.getByRole('button', { name: 'Сохранить меню на весь период' })).toBeEnabled();
  expect(state.creates[0].householdMemberIds).toEqual(['11111111-1111-4111-8111-111111111111']);
  await page.getByRole('button', { name: 'Сохранить меню на весь период' }).click();
  await expect(page.getByRole('heading', { name: 'Сохранено дней: 7.' })).toBeVisible();
  expect(state.commits).toHaveLength(1);
});
