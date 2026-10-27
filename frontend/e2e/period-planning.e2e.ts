import { test, expect } from '@playwright/test';
import { shiftDate } from '../src/domain';
import type { Page } from '@playwright/test';
import type { PeriodView, PlanningOptions } from '../src/period-planning';
async function fixture(page: Page) {
  const state = {
    view: null as PeriodView | null,
    views: new Map<string, PeriodView>(),
    full: new Map<string, PeriodView>(),
    commitBodies: [] as { acceptWarnings: boolean; expectedRevision: number }[],
    commitFailure: null as null | 'before' | 'after',
    conflict: null as string | null,
    regenerates: 0,
    reads: 0,
    holdRead: false,
    creates: [] as PlanningOptions[],
    commits: [] as string[],
    partial: false,
    incomplete: false,
    recoverWithRepeats: false,
    searchLimited: false,
    family: false,
    expired: false,
    richAlternatives: false,
    staleReplacement: false,
    replacements: [] as any[],
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
      const dayCount =
        Math.round((Date.parse(options.to) - Date.parse(options.from)) / 86400000) + 1;
      const nutrition = { calories: 2000, protein: 100, fat: 70, carbs: 250 };
      const entries = Array.from({ length: dayCount }, (_, day) =>
        ['BREAKFAST', 'LUNCH', 'DINNER'].map((meal, i) => {
          const date = shiftDate(options.from, day);
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
          days: Array.from({ length: dayCount }, (_, i) => ({
            date: shiftDate(options.from, i),
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
      state.full.set(state.view.id, structuredClone(state.view));
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
      state.views.set(state.view.id, state.view);
      return route.fulfill({ status: 201, json: state.view });
    }
    if (path.includes('/meal-plan-generations/')) {
      const id = path.split('/meal-plan-generations/')[1].split('/')[0];
      const current = state.views.get(id);
      if (req.method() === 'GET' && !path.endsWith('/alternatives')) {
        state.reads++;
        if (state.holdRead) return route.abort('failed');
      }
      if (!current) return route.fulfill({ status: 404 });
      if (path.endsWith('/alternatives') && state.richAlternatives) {
        const impact = {
          addedProducts: [],
          removedProducts: [],
          purchaseProductsBefore: 3,
          purchaseProductsAfter: 3,
          minutesSaved: 0,
          usePantry: true,
          pantryRevision: 1,
          personPortions: [],
        };
        const common = {
          recipeId: 'omelet',
          portions: 1,
          dayNutrition: { calories: 2000, protein: 100, fat: 70, carbs: 250 },
          budgetStatus: 'WITHIN',
          priceQuality: 'COMPLETE_OBSERVED',
          comparisonKey: 'a'.repeat(64),
        };
        return route.fulfill({
          json: [
            {
              ...common,
              name: 'Омлет с овощами',
              preparationTimeMin: 12,
              newPurchaseCost: 120,
              deltaPurchaseCost: 30,
              goals: ['SAME_PRODUCTS'],
              impact,
            },
            {
              ...common,
              recipeId: 'cheap',
              name: 'Чечевица с морковью',
              preparationTimeMin: 20,
              newPurchaseCost: 80,
              deltaPurchaseCost: -10,
              goals: ['CHEAPER'],
              impact: {
                ...impact,
                addedProducts: [{ productId: 5, name: 'Морковь' }],
                purchaseProductsAfter: 4,
                minutesSaved: -8,
              },
            },
            {
              ...common,
              recipeId: 'fast',
              name: 'Творог с яблоком',
              preparationTimeMin: 5,
              newPurchaseCost: 100,
              deltaPurchaseCost: 10,
              goals: ['FASTER'],
              impact: { ...impact, minutesSaved: 7 },
            },
          ],
        });
      }
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
        for (const entry of current.result.entries)
          if (entry.id === entryId) entry.locked = req.postDataJSON().locked;
        current.revision++;
      } else if (path.includes('/entries/') && req.method() === 'PUT') {
        state.replacements.push(req.postDataJSON());
        if (state.staleReplacement)
          return route.fulfill({
            status: 409,
            json: {
              code: 'REPLACEMENT_COMPARISON_CHANGED',
              detail:
                'Цены или запасы изменились. Обновите варианты и проверьте покупки перед подтверждением.',
            },
          });
        const entryId = decodeURIComponent(path.split('/entries/')[1]);
        const entry = current.result.entries.find((e) => e.id === entryId)!;
        entry.recipe.name = 'Омлет с овощами';
        entry.recipe.recipeId = 'omelet';
        current.result.shopping.knownPurchaseCost = 120;
        current.result.shopping.estimatedTotal = 120;
        current.revision++;
      } else if (path.endsWith('/commit')) {
        state.commits.push(req.headers()['idempotency-key']);
        state.commitBodies.push(req.postDataJSON());
        if (state.conflict)
          return route.fulfill({
            status: 409,
            json: { detail: 'Данные изменились', code: state.conflict },
          });
        if (state.commitFailure === 'before') return route.abort('failed');
        current.savedPlanIds = Array.from(
          { length: current.result.days.length },
          (_, i) => 'plan' + i,
        );
        current.revision++;
        if (state.commitFailure === 'after') return route.abort('failed');
      } else if (path.endsWith('/regenerate')) {
        state.regenerates++;
        if (!state.incomplete && current.result.entries.length < current.result.days.length * 3) {
          const full = state.full.get(id)!;
          current.result.entries = structuredClone(full.result.entries);
          current.result.days = structuredClone(full.result.days);
          current.result.issues = current.result.issues.filter(
            (i) => i.code !== 'NO_FEASIBLE_FOUND',
          );
          current.result.status = current.result.issues.length ? 'READY_WITH_WARNINGS' : 'READY';
          current.result.searchStatus = 'FOUND';
        }
        current.revision++;
      } else if (req.method() === 'POST') current.revision++;
      return route.fulfill({ json: current });
    }
    return route.fulfill({ json: [] });
  });
  return state;
}
test('personal restrictions can be saved without SQL', async ({ page }) => {
  await fixture(page);
  let values = {
    allergenIds: [] as number[],
    excludedProductIds: [] as number[],
    equipmentIds: [2] as number[],
  };
  let submitted: unknown;
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
    if (route.request().method() === 'PUT') {
      const body = route.request().postDataJSON();
      submitted = body;
      values = { ...body, equipmentIds: body.equipmentIds ?? values.equipmentIds };
    }
    return route.fulfill({
      json: {
        values,
        excludedProducts: values.excludedProductIds.includes(3) ? [{ id: 3, name: 'Рис' }] : [],
      },
    });
  });
  await page.goto('/planning-preferences');
  await page.getByLabel('Молоко', { exact: true }).check();
  await expect(page.getByLabel('Сковорода', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Кухонная техника →' })).toHaveAttribute(
    'href',
    '/profile/kitchen',
  );
  await page.getByLabel('Рис', { exact: true }).check();
  await page.getByRole('button', { name: 'Сохранить ограничения' }).click();
  await expect(page.getByText('Ограничения сохранены.', { exact: false })).toBeVisible();
  expect(values).toEqual({ allergenIds: [1], excludedProductIds: [3], equipmentIds: [2] });
  expect(submitted).toEqual({ allergenIds: [1], excludedProductIds: [3], equipmentIds: null });
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

const member = '11111111-1111-4111-8111-111111111111';
const saveButton = (page: Page) =>
  page.getByRole('button', { name: 'Сохранить меню и перейти к покупкам', exact: true });
async function generate(page: Page, days = 7) {
  await page.goto('/menu-generation');
  await page.getByLabel('Начало периода', { exact: true }).fill('2035-01-01');
  if (days !== 7)
    await page
      .getByRole('button', { name: `${days} ${days === 3 ? 'дня' : 'дней'}`, exact: true })
      .click();
  const stores = page.getByRole('button', { name: 'Изменить магазины', exact: true });
  if (await stores.isVisible()) await stores.click();
  await page.getByRole('checkbox', { name: 'Чижик', exact: true }).check();
  await page.getByRole('button', { name: 'Составить меню', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Меню на весь период' })).toBeVisible();
}
async function openFirstMeal(page: Page) {
  await page
    .locator('.menu-day')
    .first()
    .getByRole('button', { name: /^Завтрак,/ })
    .click();
}

test('a five day plan saves once without opening any date or extra setup steps', async ({
  page,
}) => {
  const s = await fixture(page);
  await generate(page, 5);
  await expect(page.locator('.menu-day')).toHaveCount(5);
  await expect(page.locator('.menu-dish')).toHaveCount(15);
  expect(s.creates[0].to).toBe('2035-01-05');
  await saveButton(page).click();
  await expect(page.getByRole('heading', { name: 'Что купить', exact: true })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Сохранение меню' })).toContainText(
    'Меню сохранено на 5 дней',
  );
  expect(s.commits).toHaveLength(1);
  expect(s.commitBodies[0].acceptWarnings).toBe(false);
});

test('dish details, alternatives and pins live in a single optional dialog', async ({ page }) => {
  const s = await fixture(page);
  await generate(page);
  await openFirstMeal(page);
  await expect(page.getByRole('dialog')).toContainText('Овсянка');
  await page.getByRole('button', { name: 'Заменить блюдо', exact: true }).click();
  await page.getByRole('button', { name: 'Выбрать вариант 1' }).click();
  await page.getByRole('button', { name: 'Подтвердить замену', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await page
    .locator('.menu-day')
    .first()
    .getByRole('button', { name: /Омлет с овощами/ })
    .click();
  await page.getByRole('button', { name: 'Оставить это блюдо' }).click();
  await expect(page.getByRole('button', { name: 'Открепить блюдо' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Другой вариант', exact: true }).click();
  await expect.poll(() => s.regenerates).toBe(1);
  await expect(page.locator('.menu-day').first()).toContainText('Омлет с овощами');
});

test('warnings are explicit only when saving and cancelling sends no mutation', async ({
  page,
}) => {
  const s = await fixture(page);
  s.partial = true;
  await generate(page);
  await expect(page.getByText('Известная часть —', { exact: false })).toBeVisible();
  await expect(page.getByRole('checkbox', { name: /ознакомился/ })).toHaveCount(0);
  await saveButton(page).click();
  await expect(
    page.getByRole('dialog', { name: 'Проверьте меню перед сохранением' }),
  ).toContainText('Нет цены для одного продукта');
  await page.getByRole('button', { name: 'Вернуться к меню' }).click();
  expect(s.commits).toHaveLength(0);
  await saveButton(page).click();
  await page.getByRole('button', { name: 'Сохранить с предупреждениями', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Что купить', exact: true })).toBeVisible();
  expect(s.commitBodies[0].acceptWarnings).toBe(true);
});

test('partial menus give a recovery action instead of an unexplained disabled save', async ({
  page,
}) => {
  const s = await fixture(page);
  s.incomplete = true;
  await generate(page);
  await expect(saveButton(page)).toHaveCount(0);
  await expect(page.getByRole('region', { name: 'Сохранение меню' })).toContainText(
    'Не подобрано приёмов пищи: 2',
  );
  await page.getByRole('button', { name: 'Разрешить больше повторов' }).click();
  await expect(page.getByRole('dialog')).toContainText(
    'Закрепления текущего варианта не переносятся',
  );
  s.recoverWithRepeats = true;
  await page.getByRole('button', { name: 'Разрешить повторы и пересчитать' }).click();
  await expect(page).toHaveURL(/id=preview-2&tab=menu/);
  expect(s.creates[1]).toEqual({ ...s.creates[0], maxRecipeOccurrences: 7, minRepeatGapDays: 0 });
  await expect(saveButton(page)).toBeVisible();
  expect(s.commits).toHaveLength(0);
});

test('a search limit retries the entire period without silently changing conditions', async ({
  page,
}) => {
  const s = await fixture(page);
  s.incomplete = true;
  s.searchLimited = true;
  await generate(page);
  s.incomplete = false;
  s.searchLimited = false;
  await page.getByRole('button', { name: 'Повторить подбор всего периода', exact: true }).click();
  await expect(saveButton(page)).toBeVisible();
  expect(s.creates).toHaveLength(1);
  await expect.poll(() => s.regenerates).toBe(1);
  expect(s.commits).toHaveLength(0);
});

test('expiry offers fresh conditions and never attempts a commit', async ({ page }) => {
  const s = await fixture(page);
  s.expired = true;
  await generate(page);
  await expect(saveButton(page)).toHaveCount(0);
  await page.getByRole('button', { name: 'Создать новый подбор', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Пересчитать меню', exact: true })).toBeVisible();
  expect(s.commits).toHaveLength(0);
});

test('family shares remain visible in the dish dialog and account target does not block saving', async ({
  page,
}) => {
  const s = await fixture(page);
  s.family = true;
  await page.goto('/menu-generation');
  await page.getByLabel('Начало периода').fill('2035-01-01');
  await page.getByRole('checkbox', { name: 'Анна · 1 500 ккал' }).check();
  await page.getByRole('button', { name: 'Составить меню', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Меню на 7 дн. · 2 чел.' })).toBeVisible();
  await openFirstMeal(page);
  await expect(page.getByRole('region', { name: 'Порции по людям' })).toContainText(
    'Анна: 0,75 порц.',
  );
  await page.keyboard.press('Escape');
  await saveButton(page).click();
  expect(s.creates[0].householdMemberIds).toEqual([member]);
  await expect(page.getByRole('heading', { name: 'Что купить', exact: true })).toBeVisible();
  expect(s.commits).toHaveLength(1);
});

test('editing conditions cannot save the old preview with the new budget', async ({ page }) => {
  const s = await fixture(page);
  await generate(page);
  await page.getByRole('button', { name: 'Изменить условия', exact: true }).click();
  await page.getByLabel('Бюджет на покупки').fill('4000');
  await expect(
    page.getByText('Настройки изменены, меню ещё не пересчитано.', { exact: false }),
  ).toBeVisible();
  await expect(saveButton(page)).toHaveCount(0);
  await page.getByRole('button', { name: 'Отменить изменения', exact: true }).click();
  await saveButton(page).click();
  expect(s.creates).toHaveLength(1);
  await expect(page.getByRole('heading', { name: 'Что купить', exact: true })).toBeVisible();
  expect(s.commitBodies[0].expectedRevision).toBe(0);
});

test('saved shopping links remain tied to their generation after viewing a later draft', async ({
  page,
}) => {
  const s = await fixture(page);
  await generate(page);
  await saveButton(page).click();
  await expect(page.getByRole('link', { name: 'Открыть покупки по меню' })).toHaveAttribute(
    'href',
    '/shopping-lists?source=preview-1',
  );
  await page.goto('/menu-generation');
  await page.getByRole('button', { name: 'Составить меню', exact: true }).click();
  await expect(page).toHaveURL(/id=preview-2/);
  await page.goto('/menu-generation?id=preview-1&tab=shopping');
  await expect(page.getByRole('link', { name: 'Открыть покупки по меню' })).toHaveAttribute(
    'href',
    '/shopping-lists?source=preview-1',
  );
  expect(s.commits).toHaveLength(1);
});

test('lost commit response is reconciled with GET without a second POST', async ({ page }) => {
  const s = await fixture(page);
  s.commitFailure = 'after';
  await generate(page);
  await saveButton(page).click();
  await expect(page.getByRole('heading', { name: 'Что купить', exact: true })).toBeVisible();
  expect(s.commits).toHaveLength(1);
  expect(s.reads).toBeGreaterThan(0);
});

test('manual retry after a reload reuses the original key and does not auto-save', async ({
  page,
}) => {
  const s = await fixture(page);
  s.commitFailure = 'before';
  await generate(page);
  await saveButton(page).click();
  await expect(
    page.getByRole('button', { name: 'Повторить сохранение', exact: true }),
  ).toBeVisible();
  const key = s.commits[0];
  await page.reload();
  await expect(
    page.getByRole('button', { name: 'Повторить сохранение', exact: true }),
  ).toBeVisible();
  expect(s.commits).toHaveLength(1);
  s.commitFailure = null;
  await page.getByRole('button', { name: 'Повторить сохранение', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Что купить', exact: true })).toBeVisible();
  expect(s.commits).toEqual([key, key]);
});

test('typed conflicts offer the matching recovery and never overwrite dates', async ({ page }) => {
  const s = await fixture(page);
  await generate(page);
  s.conflict = 'MEAL_PLAN_DATE_CONFLICT';
  await saveButton(page).click();
  await expect(
    page
      .getByRole('region', { name: 'Сохранение меню' })
      .getByRole('button', { name: 'Открыть календарь' }),
  ).toBeVisible();
  expect(s.commits).toHaveLength(1);
});

test('expired shopping links provide an exit rather than a redirect loop', async ({ page }) => {
  await fixture(page);
  await page.route('**/api/me/meal-plan-generations/expired', (r) =>
    r.fulfill({ status: 410, json: { code: 'GENERATION_EXPIRED', detail: 'Срок истёк' } }),
  );
  await page.goto('/menu-generation?id=expired&tab=shopping');
  await expect(page.getByRole('heading', { name: 'Этот вариант сейчас недоступен' })).toBeVisible();
  await page.getByRole('button', { name: 'Создать новый подбор', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Составить меню', exact: true })).toBeVisible();
});

test('mobile overview and fixed action stay clear of bottom navigation', async ({ page }) => {
  await fixture(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await generate(page, 5);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const bar = await page.getByRole('region', { name: 'Сохранение меню' }).boundingBox(),
    nav = await page.getByRole('navigation', { name: 'Мобильная навигация' }).boundingBox();
  expect(bar!.y + bar!.height).toBeLessThanOrEqual(nav!.y);
  await openFirstMeal(page);
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(
    page
      .locator('.menu-day')
      .first()
      .getByRole('button', { name: /^Завтрак,/ }),
  ).toBeFocused();
  await page.screenshot({ path: '/tmp/smartmeal-menu-ux-mobile.png', fullPage: true });
});

test('revision conflict closes warning consent and requires a fresh review', async ({ page }) => {
  const s = await fixture(page);
  s.partial = true;
  await generate(page);
  await saveButton(page).click();
  s.conflict = 'GENERATION_REVISION_CONFLICT';
  await page.getByRole('button', { name: 'Сохранить с предупреждениями', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(
    page
      .getByRole('region', { name: 'Сохранение меню' })
      .getByRole('button', { name: 'Проверить состояние меню' }),
  ).toBeVisible();
  const current = s.views.get('preview-1')!;
  current.revision++;
  s.conflict = null;
  await page
    .getByRole('region', { name: 'Сохранение меню' })
    .getByRole('button', { name: 'Проверить состояние меню' })
    .click();
  await expect(
    page.getByRole('button', { name: 'Повторить сохранение', exact: true }),
  ).toBeVisible();
  expect(s.commits).toHaveLength(1);
  await page.getByRole('button', { name: 'Повторить сохранение', exact: true }).click();
  await expect(
    page.getByRole('dialog', { name: 'Проверьте меню перед сохранением' }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Сохранить с предупреждениями', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Что купить', exact: true })).toBeVisible();
  expect(s.commitBodies.map((b) => b.expectedRevision)).toEqual([0, 1]);
});

test('unavailable selected people are never silently removed from remembered conditions', async ({
  page,
}) => {
  const s = await fixture(page);
  await page.addInitScript(
    (id) =>
      localStorage.setItem(
        'smartmeal.planning.preferences.v1.period-user',
        JSON.stringify({
          version: 1,
          values: {
            days: 5,
            budget: '4000',
            city: 'Тула',
            chains: [1],
            members: [id],
            times: { BREAKFAST: 15, LUNCH: 45, DINNER: 45 },
            repeat: 2,
            gap: 2,
            pantry: true,
            estimates: true,
            split: false,
            saving: 50,
          },
        }),
      ),
    member,
  );
  await page.goto('/menu-generation');
  await expect(
    page.getByText('Выбранный профиль семьи больше недоступен.', { exact: false }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Составить меню', exact: true })).toBeDisabled();
  expect(s.creates).toHaveLength(0);
  await page.getByRole('button', { name: 'Убрать недоступные профили из выбора' }).click();
  await expect(page.getByRole('button', { name: 'Составить меню', exact: true })).toBeEnabled();
});

test('replacement goals show shopping impact and never write before confirmation', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const s = await fixture(page);
  s.richAlternatives = true;
  await generate(page);
  await openFirstMeal(page);
  await page.getByRole('button', { name: 'Заменить блюдо', exact: true }).click();
  await expect(
    page.getByRole('button', { name: 'Из тех же продуктов', exact: true }),
  ).toHaveAttribute('aria-pressed', 'true');
  await page.getByRole('button', { name: 'Дешевле', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Чечевица с морковью', exact: true }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Выбрать вариант 1', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Подтверждение замены' })).toContainText(
    'Добавятся: Морковь',
  );
  await expect(page.getByRole('region', { name: 'Подтверждение замены' })).toContainText('3 → 4');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: '/tmp/smartmeal-replacement-review.png', fullPage: true });
  expect(s.replacements).toHaveLength(0);
  await page.getByRole('button', { name: 'Назад к вариантам', exact: true }).click();
  await page.getByRole('button', { name: 'Быстрее', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Творог с яблоком', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Выбрать вариант 1', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Подтверждение замены' })).toContainText(
    '7 мин быстрее',
  );
  await page.getByRole('button', { name: 'Подтвердить замену', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(s.replacements).toHaveLength(1);
  expect(s.replacements[0].comparisonKey).toBe('a'.repeat(64));
});

test('stale replacement asks for a refreshed calculation without changing the menu', async ({
  page,
}) => {
  const s = await fixture(page);
  s.richAlternatives = true;
  s.staleReplacement = true;
  await generate(page);
  await openFirstMeal(page);
  await page.getByRole('button', { name: 'Заменить блюдо', exact: true }).click();
  await page.getByRole('button', { name: 'Выбрать вариант 1', exact: true }).click();
  await page.getByRole('button', { name: 'Подтвердить замену', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(
    page.getByRole('alert').getByText('Цены или запасы изменились.', { exact: false }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Обновить расчёт', exact: true }).first(),
  ).toBeVisible();
  expect(s.view!.revision).toBe(0);
  expect(s.commits).toHaveLength(0);
});
