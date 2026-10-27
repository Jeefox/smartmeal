import { test, expect, type Page } from '@playwright/test';
async function fixture(page: Page) {
  await page.clock.install({ time: new Date('2035-01-01T12:00:00+03:00') });
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  const nutrition = { calories: 500, protein: 30, fat: 15, carbs: 50 };
  const snapshot = (id: string, name: string) => ({
    recipeId: id,
    name,
    description: null,
    instructions: 'Приготовить',
    preparationTimeMin: 10,
    servings: 1,
    ingredients: [{ productId: 1, productName: 'Рис сухой', quantityG: 100 }],
    nutritionTotal: nutrition,
    nutritionPerServing: nutrition,
    components: [],
  });
  const old = snapshot('old', 'Гречка с курицей'),
    next = snapshot('new', 'Рис с индейкой');
  const plan = {
    id: 'plan',
    planDate: '2035-01-01',
    status: 'CONFIRMED',
    dailyCalorieTarget: 2000,
    dailyCalorieTargetSnapshot: 2000,
    confirmedAt: '2035-01-01T08:00:00Z',
    createdAt: '2035-01-01T08:00:00Z',
    updatedAt: '2035-01-01T08:00:00Z',
    nutritionTotal: { ...nutrition, calories: 1000 },
    entries: [
      {
        id: 'entry',
        mealPlanId: 'plan',
        mealType: 'BREAKFAST',
        position: 1,
        portions: 1,
        recipeSnapshot: old,
        nutrition,
      },
      {
        id: 'other',
        mealPlanId: 'plan',
        mealType: 'LUNCH',
        position: 1,
        portions: 1,
        recipeSnapshot: snapshot('soup', 'Овощной суп'),
        nutrition,
      },
    ],
  };
  const state = {
    plan,
    revision: 1,
    changed: false,
    protected: false,
    lost: false,
    posts: [] as { path: string; body: any; key: string | null }[],
  };
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      p = url.pathname;
    if (p === '/api/me/profile')
      return route.fulfill({ json: { userId: 'user', dailyCalorieTarget: 2000 } });
    if (p === '/api/auth/csrf')
      return route.fulfill({
        json: { token: 'test', headerName: 'X-CSRF', parameterName: '_csrf' },
      });
    if (p === '/api/me/meal-plans')
      return url.searchParams.get('date') === '2035-01-01'
        ? route.fulfill({ json: plan })
        : route.fulfill({ status: 404, json: { detail: 'Нет дня' } });
    if (p === '/api/me/meal-plans/plan/inventory')
      return route.fulfill({
        json: {
          revision: state.revision,
          today: '2035-01-01',
          entries: plan.entries.map((e) => ({
            entryId: e.id,
            state: e.id === 'entry' && !state.protected ? 'OPEN' : 'DEDUCTED',
            reservedG: {},
          })),
        },
      });
    if (p === '/api/me/cooking-batches')
      return route.fulfill({
        json: {
          revision: state.revision,
          today: '2035-01-01',
          candidates: [],
          batches: [],
          drafts: [],
        },
      });
    if (p.endsWith('/replacements/status'))
      return route.fulfill({
        json: {
          revision: state.revision,
          canReplace: p.includes('/entry/') && !state.protected,
          reason: state.protected ? 'Блюдо связано с общей партией' : null,
          undoId: p.includes('/entry/') && state.changed && !state.protected ? 'change' : null,
          undoName: 'Гречка с курицей',
        },
      });
    if (p.endsWith('/replacements') && req.method() === 'GET')
      return route.fulfill({
        json: {
          revision: state.revision,
          from: '2035-01-01',
          to: '2035-01-07',
          alternatives: [
            {
              recipeId: 'new',
              name: next.name,
              portions: 1,
              preparationTimeMin: 10,
              dayNutrition: { ...nutrition, calories: 2000 },
              newPurchaseCost: 900,
              deltaPurchaseCost: -100,
              budgetStatus: 'NOT_SET',
              priceQuality: 'COMPLETE_OBSERVED',
              candidateKey: 'candidate',
              comparisonKey: 'a'.repeat(64),
              goals: ['SAME_PRODUCTS', 'CHEAPER'],
              proposedRecipe: next,
              impact: {
                addedProducts: [],
                removedProducts: [],
                purchaseProductsBefore: 7,
                purchaseProductsAfter: 7,
                minutesSaved: 0,
                usePantry: true,
                pantryRevision: state.revision,
                personPortions: [],
              },
            },
          ],
        },
      });
    if (req.method() === 'POST') {
      state.posts.push({
        path: p,
        body: req.postDataJSON(),
        key: req.headers()['idempotency-key'],
      });
      if (p.endsWith('/replacements')) {
        state.changed = true;
        plan.entries[0].recipeSnapshot = next;
      }
      if (p.endsWith('/undo')) {
        state.changed = false;
        plan.entries[0].recipeSnapshot = old;
      }
      state.revision++;
      plan.updatedAt = `2035-01-01T08:00:0${state.revision}Z`;
      if (state.lost) {
        state.lost = false;
        return route.abort('failed');
      }
      return route.fulfill({ json: { operationId: 'op', revision: state.revision, lotIds: [] } });
    }
    if (p.endsWith('/portion-feedback') || p.endsWith('/household-members'))
      return route.fulfill({ json: [] });
    return route.fulfill({ status: 404, json: { detail: p } });
  });
  return state;
}
test('saved day replaces only after confirmation and can undo without buying anything', async ({
  page,
}) => {
  const s = await fixture(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await page.getByRole('button', { name: 'Заменить блюдо', exact: true }).click();
  await page.getByRole('button', { name: 'Выбрать вариант 1', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Подтверждение замены' })).toContainText(
    'Оставшиеся покупки периода',
  );
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: '/tmp/smartmeal-saved-replacement.png', fullPage: true });
  expect(s.posts).toHaveLength(0);
  await page.getByRole('button', { name: 'Подтвердить замену', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Рис с индейкой', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Овощной суп', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Отменить последнюю замену', exact: true }).click();
  await page.getByRole('button', { name: 'Вернуть предыдущее блюдо', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Гречка с курицей', exact: true })).toBeVisible();
  expect(s.posts).toHaveLength(2);
  expect(s.posts.every((p) => p.key)).toBe(true);
  expect(s.posts.every((p) => p.path.includes('/replacements'))).toBe(true);
});
test('week view offers the same replacement for an unprepared saved draft', async ({ page }) => {
  const s = await fixture(page);
  s.plan.status = 'DRAFT';
  await page.goto('/week?date=2035-01-01');
  await page.getByRole('button', { name: 'Заменить блюдо', exact: true }).click();
  await expect(
    page.getByRole('button', { name: 'Из тех же продуктов', exact: true }),
  ).toBeVisible();
  expect(s.posts).toHaveLength(0);
});
test('linked cooking protects a saved meal from replacement and undo', async ({ page }) => {
  const s = await fixture(page);
  s.protected = true;
  s.changed = true;
  await page.goto('/');
  await expect(
    page.getByText('Блюдо связано с общей партией', { exact: true }).first(),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Заменить блюдо', exact: true })).toHaveCount(0);
  await expect(
    page.getByRole('button', { name: 'Отменить последнюю замену', exact: true }),
  ).toHaveCount(0);
  expect(s.posts).toHaveLength(0);
});
test('lost response refreshes the saved meal without a second replacement write', async ({
  page,
}) => {
  const s = await fixture(page);
  s.lost = true;
  await page.goto('/');
  await page.getByRole('button', { name: 'Заменить блюдо', exact: true }).click();
  await page.getByRole('button', { name: 'Выбрать вариант 1', exact: true }).click();
  await page.getByRole('button', { name: 'Подтвердить замену', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Рис с индейкой', exact: true })).toBeVisible();
  expect(s.posts).toHaveLength(1);
  await page.keyboard.press('Escape');
  await expect(
    page.locator('.live-meal-card').getByRole('heading', { name: 'Рис с индейкой', exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Отменить последнюю замену', exact: true }),
  ).toBeVisible();
});
