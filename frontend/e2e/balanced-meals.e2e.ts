import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
const owner = '11111111-1111-4111-8111-111111111111',
  mom = '22222222-2222-4222-8222-222222222222',
  id = '33333333-3333-4333-8333-333333333333';
const numeric = (calories: number, protein: number, fiber: number, complete = true) => ({
  calories,
  protein,
  fat: 10,
  carbs: 50,
  knownFiber: fiber,
  fiberComplete: complete,
});
function snapshot(rid: string, name: string, calories = 300) {
  return {
    recipeId: rid,
    name,
    description: null,
    instructions: 'Приготовить по рецепту',
    preparationTimeMin: 5,
    servings: 1,
    ingredients: [
      {
        productId: 1,
        productName: 'Продукт',
        quantityG: 100,
        shoppingName: 'Продукт',
        edibleYield: 1,
      },
    ],
    nutritionTotal: numeric(calories, 20, 0),
    nutritionPerServing: numeric(calories, 20, 0),
    components: [] as any[],
  };
}
function document() {
  const recipe = snapshot(id, 'Индейка + гречка + овощи');
  recipe.nutritionTotal = numeric(1413, 75.2, 21);
  recipe.nutritionPerServing = recipe.nutritionTotal;
  recipe.components = [
    ['PROTEIN_MAIN', 'Индейка', 150, 140],
    ['SIDE', 'Гречка', 200, 180],
    ['VEGETABLE', 'Овощи', 150, 140],
  ].map(([role, name, a, b], i) => ({
    id: `component-${i}`,
    role,
    recipe: snapshot(`44444444-4444-4444-8444-44444444444${i}`, String(name)),
    profile: {
      role,
      cookedYieldG: 100,
      yieldQuality: 'ESTIMATED',
      source: 'Плановая оценка выхода',
      minG: 100,
      maxG: 250,
      stepG: 25,
      containsStarch: role === 'SIDE',
      containsVegetables: role === 'VEGETABLE',
    },
    amounts: [
      { participantId: owner, cookedG: a, multiplier: Number(a) / 100 },
      { participantId: mom, cookedG: b, multiplier: Number(b) / 100 },
    ],
    locked: false,
  }));
  const people = [
    { participantId: owner, name: 'Вы', portions: 0.52, nutrition: numeric(735, 39, 11) },
    { participantId: mom, name: 'Мама', portions: 0.48, nutrition: numeric(678, 36.2, 10) },
  ];
  const entries = ['BREAKFAST', 'LUNCH', 'DINNER'].map((mealType) => ({
    id: `2026-10-25-${mealType}`,
    date: '2026-10-25',
    mealType,
    recipe: structuredClone(recipe),
    recipeFingerprint: 'fixture',
    portions: 1,
    locked: false,
    coverImageUrl: null,
    explanations: [],
    personPortions: people,
  }));
  return {
    id,
    revision: 0,
    expiresAt: '2099-01-01T00:00:00Z',
    savedPlanIds: [] as string[],
    result: {
      options: {
        from: '2026-10-25',
        to: '2026-10-25',
        budgetRub: 4000,
        city: 'Тула',
        allowedChainIds: [1],
        householdMemberIds: [mom],
        maxPreparationTimeByMeal: { BREAKFAST: 15, LUNCH: 45, DINNER: 45 },
        maxRecipeOccurrences: 2,
        minRepeatGapDays: 2,
        usePantry: true,
        allowEstimatedPrices: true,
        purchaseMode: 'SINGLE_CHAIN',
        minSplitSavingRub: 50,
        seed: 0,
        balancedMeals: true,
      },
      dailyCalorieTarget: 4200,
      participants: [
        { id: owner, name: 'Вы', dailyCalorieTarget: 2200, accountOwner: true },
        { id: mom, name: 'Мама', dailyCalorieTarget: 2000, accountOwner: false },
      ],
      contextFingerprint: 'fixture',
      dataAsOf: '2026-10-25T09:00:00Z',
      pantryRevision: 0,
      pantryAsOf: '2026-10-25',
      entries,
      days: [
        {
          date: '2026-10-25',
          nutrition: numeric(4239, 225.6, 63),
          calorieDeviation: 39,
          withinTargetTolerance: true,
          entries,
          people: people.map((p, i) => ({
            ...p,
            dailyCalorieTarget: i ? 2000 : 2200,
            proteinTargetG: i ? 70 : 80,
            fiberTargetG: 25,
            nutrition: numeric(
              p.nutrition.calories * 3,
              p.nutrition.protein * 3,
              p.nutrition.knownFiber * 3,
            ),
          })),
        },
      ],
      shopping: {
        items: [],
        knownPurchaseCost: 100,
        estimatedTotal: 100,
        consumedIngredientCost: 50,
        pricedProducts: 1,
        estimatedProducts: 0,
        unknownProducts: 0,
        priceQuality: 'COMPLETE_OBSERVED',
        budgetStatus: 'WITHIN',
        chains: [],
        savingAgainstSingleChain: null,
      },
      issues: [],
      status: 'READY',
      searchStatus: 'FOUND',
      algorithmVersion: 'balanced-composite-v1',
    },
  };
}
async function fixture(page: Page) {
  const view = document();
  const state = {
    view,
    targetWrites: 0,
    suggestions: 0,
    replacement: null as any,
    componentLocks: 0,
    fiber: {
      fiberPer100g: null as number | null,
      source: null as string | null,
      quality: 'UNKNOWN',
    },
    profile: { ...view.result.entries[0].recipe.components[0].profile },
    feedback: [] as any[],
    prepared: true,
    targets: [owner, mom].map((participantId, i) => ({
      participantId,
      name: i ? 'Мама' : 'Вы',
      revision: 0,
      settings: {
        expectedRevision: 0,
        adultConfirmed: false,
        weightKg: null as number | null,
        proteinTargetG: null as number | null,
        fiberTargetG: null as number | null,
        maxMealG: 650,
        targetSource: 'UNSET',
      },
    })),
  };
  await page.clock.install({ time: new Date('2026-10-25T12:00:00+03:00') });
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      u = new URL(req.url()),
      p = u.pathname,
      m = req.method();
    if (p === '/api/me/profile')
      return route.fulfill({
        json: { canEditCatalogue: true, userId: owner, dailyCalorieTarget: 2200 },
      });
    if (p === '/api/auth/csrf')
      return route.fulfill({
        json: { token: 'test', headerName: 'X-CSRF', parameterName: '_csrf' },
      });
    if (p === '/api/retail-chains')
      return route.fulfill({ json: [{ id: 1, name: 'Чижик', active: true }] });
    if (p === '/api/me/household-members')
      return route.fulfill({
        json: [
          {
            id: mom,
            name: 'Мама',
            dailyCalorieTarget: 2000,
            allergenIds: [],
            excludedProductIds: [],
          },
        ],
      });
    if (p === '/api/me/nutrition-targets') return route.fulfill({ json: state.targets });
    if (p.endsWith('/nutrition-targets/suggest')) {
      state.suggestions++;
      return route.fulfill({
        json: {
          ...req.postDataJSON(),
          proteinTargetG: 66.4,
          fiberTargetG: 25,
          targetSource: 'REFERENCE',
        },
      });
    }
    if (p.startsWith('/api/me/nutrition-targets/')) {
      state.targetWrites++;
      const person = state.targets.find((x) => p.endsWith(x.participantId))!;
      person.settings = req.postDataJSON();
      person.revision++;
      person.settings.expectedRevision = person.revision;
      return route.fulfill({ json: person });
    }
    if (p === '/api/planning-restrictions')
      return route.fulfill({ json: { allergens: [], equipment: [] } });
    if (p === `/api/recipes/${id}/planning`)
      return route.fulfill({
        json: {
          minPortions: 1,
          maxPortions: 2,
          portionStep: 1,
          dishFamily: null,
          mealTypes: ['LUNCH'],
          equipmentIds: [],
        },
      });
    if (p === '/api/products/1/planning')
      return route.fulfill({
        json: { allergenDataComplete: false, verificationNote: null, allergenIds: [] },
      });
    if (p === `/api/recipes/${id}/portion-profile`) {
      if (m === 'PUT') state.profile = req.postDataJSON();
      return route.fulfill({ json: state.profile });
    }
    if (p === '/api/products/1/fiber') {
      if (m === 'PUT') state.fiber = req.postDataJSON();
      return route.fulfill({ json: state.fiber });
    }
    if (p === `/api/me/meal-plan-generations/${id}`) return route.fulfill({ json: view });
    if (p.includes('/alternatives')) {
      const e = view.result.entries.find(
        (x) => p.includes(encodeURIComponent(x.id)) || p.includes(x.id),
      )!;
      const r = structuredClone(e.recipe);
      r.name = 'Индейка + рис + овощи';
      r.components[1].recipe.name = 'Рис';
      return route.fulfill({
        json: [
          {
            recipeId: id,
            name: r.name,
            portions: 1,
            preparationTimeMin: 15,
            dayNutrition: view.result.days[0].nutrition,
            newPurchaseCost: 90,
            deltaPurchaseCost: -10,
            budgetStatus: 'WITHIN',
            priceQuality: 'COMPLETE_OBSERVED',
            candidateKey: 'replacement-key',
            comparisonKey: 'b'.repeat(64),
            goals: ['SAME_PRODUCTS', 'CHEAPER'],
            impact: {
              addedProducts: [],
              removedProducts: [],
              purchaseProductsBefore: 3,
              purchaseProductsAfter: 3,
              minutesSaved: 0,
              usePantry: true,
              pantryRevision: 0,
              personPortions: e.personPortions,
            },
            proposedRecipe: r,
          },
        ],
      });
    }
    if (p.includes('/components/') && p.endsWith('/lock')) {
      state.componentLocks++;
      const c = view.result.entries
        .find((x) => p.includes(encodeURIComponent(x.id)) || p.includes(x.id))!
        .recipe.components.find((x) => p.includes(x.id))!;
      c.locked = req.postDataJSON().locked;
      view.revision++;
      return route.fulfill({ json: view });
    }
    if (p.includes('/entries/') && m === 'PUT') {
      state.replacement = req.postDataJSON();
      const e = view.result.entries.find(
        (x) => p.endsWith(encodeURIComponent(x.id)) || p.endsWith(x.id),
      )!;
      e.recipe.name = 'Индейка + рис + овощи';
      e.recipe.components[1].recipe.name = 'Рис';
      view.revision++;
      return route.fulfill({ json: view });
    }
    const entry = view.result.entries[0];
    if (p === '/api/me/meal-plans')
      return route.fulfill({
        json: {
          id: 'plan',
          planDate: '2026-10-25',
          status: 'CONFIRMED',
          dailyCalorieTarget: 4200,
          dailyCalorieTargetSnapshot: 4200,
          confirmedAt: '2026-10-25T09:00:00Z',
          createdAt: '2026-10-25T09:00:00Z',
          updatedAt: '2026-10-25T09:00:00Z',
          entries: [
            {
              id: 'entry',
              mealPlanId: 'plan',
              mealType: 'BREAKFAST',
              portions: 1,
              position: 1,
              recipeSnapshot: entry.recipe,
              nutrition: entry.recipe.nutritionTotal,
              personPortions: entry.personPortions,
            },
          ],
          nutritionTotal: entry.recipe.nutritionTotal,
          people: view.result.days[0].people,
        },
      });
    if (p === '/api/me/meal-plans/plan/inventory')
      return route.fulfill({
        json: {
          revision: 0,
          today: '2026-10-25',
          entries: [
            { entryId: 'entry', state: state.prepared ? 'DEDUCTED' : 'OPEN', reservedG: {} },
          ],
        },
      });
    if (p === '/api/me/pantry')
      return route.fulfill({
        json: { revision: 0, today: '2026-10-25', lots: [], unconfirmedPastEntries: 0 },
      });
    if (p.includes('/portion-feedback')) {
      if (m === 'PUT') {
        const participantId = p.split('/').at(-1)!,
          rating = req.postDataJSON().rating;
        state.feedback = [
          ...state.feedback.filter((x) => x.participantId !== participantId),
          { participantId, rating, revision: 1 },
        ];
        return route.fulfill({ json: state.feedback.at(-1) });
      }
      return route.fulfill({ json: state.feedback });
    }
    return route.fulfill({ status: 404, json: { detail: 'Нет тестовых данных' } });
  });
  return state;
}
async function openMeal(page: Page) {
  await page.goto(`/menu-generation?id=${id}&tab=menu`);
  await page.getByRole('button', { name: /^Обед,/ }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
}
test('components show individual ready weights and nutrition instead of recipe multipliers', async ({
  page,
}) => {
  await fixture(page);
  await openMeal(page);
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('heading', { name: 'Количество для каждого' })).toBeVisible();
  await expect(dialog.getByText('≈ 150 г готового').first()).toBeVisible();
  await expect(dialog.getByText('Выход приблизительный.', { exact: false }).first()).toBeVisible();
  await expect(dialog.getByText('порц. всего', { exact: false })).toHaveCount(0);
  await expect(dialog.getByText('белок', { exact: false }).first()).toBeVisible();
  await expect(dialog.getByText('Клетчатка:', { exact: false }).first()).toBeVisible();
});
test('side replacement sends candidate fingerprint and keeps logical meal', async ({ page }) => {
  const s = await fixture(page);
  await openMeal(page);
  await page.getByRole('button', { name: 'Заменить гарнир', exact: true }).click();
  await page.getByRole('button', { name: 'Выбрать вариант 1' }).click();
  await page.getByText('Порции участников после замены', { exact: true }).click();
  await expect(
    page
      .getByRole('region', { name: 'Подтверждение замены' })
      .getByRole('heading', { name: 'Количество для каждого', exact: true }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Подтвердить замену', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(s.replacement.candidateKey).toBe('replacement-key');
  expect(s.replacement.comparisonKey).toBe('b'.repeat(64));
  expect(s.replacement.componentId).toBe('component-1');
  await expect(page.getByRole('button', { name: /^Обед,.*рис/ })).toBeVisible();
});
test('component lock is independent from locking the entire meal', async ({ page }) => {
  const s = await fixture(page);
  await openMeal(page);
  await page.getByRole('button', { name: 'Оставить компонент', exact: true }).first().click();
  await expect(
    page.getByRole('button', { name: 'Открепить компонент', exact: true }),
  ).toBeVisible();
  expect(s.componentLocks).toBe(1);
  expect(s.view.result.entries.find((x) => x.mealType === 'LUNCH')!.locked).toBe(false);
});
test('reference suggestion is not saved until explicitly confirmed, per person', async ({
  page,
}) => {
  const s = await fixture(page);
  await page.goto('/nutrition-settings');
  await page.getByLabel('Подтверждаю взрослый профиль').check();
  await page.getByLabel('Масса тела, кг · для справочного расчёта').fill('80');
  await page.getByRole('button', { name: 'Предложить справочные ориентиры' }).click();
  await expect(page.getByLabel('Белок в сутки, г · можно не задавать')).toHaveValue('66.4');
  expect(s.targetWrites).toBe(0);
  await page.getByRole('button', { name: 'Сохранить цели' }).click();
  await expect(page.getByText('Цели сохранены.', { exact: false })).toBeVisible();
  expect(s.targetWrites).toBe(1);
  await page.getByRole('combobox', { name: 'Для кого', exact: true }).selectOption(mom);
  await expect(page.getByLabel('Белок в сутки, г · можно не задавать')).toHaveValue('');
});
test('missing fiber stays blank and confirmed zero is distinct', async ({ page }) => {
  const s = await fixture(page);
  await page.goto('/products/1/planning');
  await page.getByText('Данные клетчатки', { exact: true }).click();
  await expect(page.getByLabel('Клетчатка на 100 г съедобного продукта')).toHaveValue('');
  await page.getByLabel('Клетчатка на 100 г съедобного продукта').fill('0');
  await page.getByLabel('Качество данных').selectOption('LABEL');
  await page.getByLabel('Источник или основание данных').fill('Этикетка');
  await page.getByRole('button', { name: 'Сохранить данные порции' }).click();
  await expect(page.getByText('Данные сохранены.', { exact: false })).toBeVisible();
  expect(s.fiber.fiberPer100g).toBe(0);
});
test('ready yield quality is edited explicitly', async ({ page }) => {
  const s = await fixture(page);
  await page.goto(`/recipes/${id}/planning`);
  await page.getByText('Размер порции и роль блюда', { exact: true }).click();
  await page.getByLabel('Выход всего готового рецепта, г').fill('500');
  await page.getByLabel('Как получен выход').selectOption('MEASURED');
  await page.getByLabel('Источник или основание данных').fill('Взвешено после приготовления');
  await page.getByRole('button', { name: 'Сохранить данные порции' }).click();
  await expect(page.getByText('Данные сохранены.', { exact: false })).toBeVisible();
  expect(s.profile.cookedYieldG).toBe(500);
  expect(s.profile.yieldQuality).toBe('MEASURED');
});
test('prepared meal feedback is per person and does not change nutrient targets', async ({
  page,
}) => {
  const s = await fixture(page);
  await page.goto('/');
  await page.getByText('Другие действия', { exact: true }).first().click();
  await page.getByText('Хватило ли еды?', { exact: true }).first().click();
  await page
    .getByRole('group', { name: 'Мама', exact: true })
    .getByRole('button', { name: 'Достаточно', exact: true })
    .click();
  await expect(page.getByText('Оценка сохранена. Цели питания не изменены.')).toBeVisible();
  expect(s.feedback[0].participantId).toBe(mom);
  expect(s.targetWrites).toBe(0);
});
test('mobile composite modal fits at 360px', async ({ page }) => {
  await fixture(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await openMeal(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: '/tmp/smartmeal-composite-mobile.png' });
  await page.keyboard.press('Escape');
  await expect(page.getByRole('button', { name: /^Обед,/ })).toBeFocused();
});

test('legacy preview keeps its old quantities and offers an explicit new composition', async ({
  page,
}) => {
  const s = await fixture(page);
  s.view.result.options.balancedMeals = false;
  for (const e of s.view.result.entries) {
    e.recipe.components = [];
    e.portions = 4;
  }
  await openMeal(page);
  await expect(page.getByText('4 порц. всего', { exact: true })).toBeVisible();
  await page.keyboard.press('Escape');
  await page.getByRole('button', { name: 'Новый подбор с гарнирами' }).click();
  await expect(
    page.getByRole('checkbox', { name: 'Подбирать нормальные количества и сочетания с гарниром' }),
  ).toBeChecked();
});

test('demo nutrient settings never contact the API', async ({ page }) => {
  let calls = 0;
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"demo"'));
  await page.route('**/api/**', (r) => {
    calls++;
    return r.abort();
  });
  await page.goto('/nutrition-settings');
  await expect(
    page.getByRole('heading', { name: 'Цели питания доступны в режиме API' }),
  ).toBeVisible();
  expect(calls).toBe(0);
});

test('short grocery limit is visible and applies to a new plan without rewriting old menus', async ({
  page,
}) => {
  const s = await fixture(page);
  await page.goto(`/menu-generation?id=${id}&tab=menu`);
  await expect(page.getByText(/К покупке:.*разных продуктов/)).toBeVisible();
  await page.getByRole('button', { name: 'Изменить условия', exact: true }).click();
  const select = page.getByRole('combobox', { name: 'Максимум разных продуктов на весь период' });
  await expect(select).toHaveValue('');
  await select.selectOption('7');
  await expect(select).toHaveValue('7');
  expect(s.view.result.options).not.toHaveProperty('maxShoppingProducts');
});

test('failed compact generation explains the bottleneck above the menu', async ({ page }) => {
  const s = await fixture(page);
  s.view.result.issues = [
    {
      code: 'COMPACT_BASKET_SEARCH',
      message: 'При пределе 7 продуктов не найдено полного меню. Разрешите больше повторов блюд.',
    },
  ] as any;
  s.view.result.entries = [];
  s.view.result.status = 'NO_FEASIBLE_FOUND';
  await page.goto(`/menu-generation?id=${id}&tab=menu`);
  await expect(page.getByRole('region', { name: 'Что мешает подбору' })).toContainText(
    'Разрешите больше повторов блюд',
  );
  await page.getByRole('button', { name: 'Изменить условия подбора', exact: true }).click();
  await expect(
    page.getByRole('combobox', { name: 'Максимум разных продуктов на весь период' }),
  ).toBeVisible();
});
