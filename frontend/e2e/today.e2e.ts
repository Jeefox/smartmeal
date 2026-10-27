import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
async function fixture(page: Page) {
  await page.clock.install({ time: new Date('2026-10-25T12:00:00+03:00') });
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  const n = { calories: 600, protein: 40, fat: 15, carbs: 70, knownFiber: 5, fiberComplete: true };
  const source = (id: string, name: string) => ({
    recipeId: id,
    name,
    instructions: 'Приготовить по рецепту',
    preparationTimeMin: 10,
    servings: 1,
    ingredients: [{ productId: 1, productName: 'Продукт', quantityG: 100 }],
    nutritionTotal: n,
    nutritionPerServing: n,
    components: [],
  });
  const root = {
    ...source('main', 'Курица + рис'),
    components: [
      {
        id: 'main',
        role: 'PROTEIN_MAIN',
        recipe: source('main', 'Курица'),
        profile: {
          role: 'PROTEIN_MAIN',
          cookedYieldG: 200,
          yieldQuality: 'ESTIMATED',
          source: 'Тест',
          minG: 100,
          maxG: 200,
          stepG: 25,
          containsStarch: false,
          containsVegetables: false,
        },
        amounts: [{ participantId: 'u', multiplier: 1, cookedG: 150 }],
        locked: false,
      },
      {
        id: 'rice',
        role: 'SIDE',
        recipe: source('rice', 'Рис'),
        profile: {
          role: 'SIDE',
          cookedYieldG: 200,
          yieldQuality: 'ESTIMATED',
          source: 'Тест',
          minG: 100,
          maxG: 250,
          stepG: 25,
          containsStarch: true,
          containsVegetables: false,
        },
        amounts: [{ participantId: 'u', multiplier: 1, cookedG: 200 }],
        locked: false,
      },
    ],
  };
  const entry = {
    id: 'entry',
    mealPlanId: 'plan',
    mealType: 'DINNER',
    position: 1,
    portions: 1,
    recipeSnapshot: root,
    nutrition: n,
    personPortions: [{ participantId: 'u', name: 'Вы', portions: 1, nutrition: n }],
  };
  const plan = {
    id: 'plan',
    planDate: '2026-10-25',
    status: 'CONFIRMED',
    dailyCalorieTarget: 2000,
    dailyCalorieTargetSnapshot: 2000,
    confirmedAt: '2026-10-25T10:00:00Z',
    createdAt: '2026-10-25T10:00:00Z',
    updatedAt: '2026-10-25T10:00:00Z',
    nutritionTotal: n,
    entries: [entry],
    participants: [{ id: 'u', name: 'Вы', dailyCalorieTarget: 2000, accountOwner: true }],
    people: [{ participantId: 'u', name: 'Вы', dailyCalorieTarget: 2000, nutrition: n }],
  };
  const candidates = [0, 1].map((i) => ({
    ref: { entryId: i ? 'next' : 'entry', componentId: 'rice' },
    date: `2026-10-${25 + i}`,
    mealType: 'DINNER',
    recipeName: 'Рис',
    fingerprint: 'rice',
    cookedG: 200,
  }));
  const state = {
    view: { revision: 0, today: '2026-10-25', candidates, batches: [] as any[], drafts: [] },
    inventory: 'OPEN',
    calls: [] as { path: string; body: any; key: string | null }[],
    failCook: false,
    plan,
    history: [] as any[],
    loseCorrectionResponse: false,
  };
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      p = new URL(req.url()).pathname;
    if (p === '/api/me/profile')
      return route.fulfill({ json: { userId: 'u', dailyCalorieTarget: 2000 } });
    if (p === '/api/auth/csrf')
      return route.fulfill({
        json: { token: 'test', headerName: 'X-CSRF', parameterName: '_csrf' },
      });
    if (p === '/api/me/meal-plans') return route.fulfill({ json: plan });
    if (p === '/api/me/meal-plans/plan/inventory')
      return route.fulfill({
        json: {
          revision: state.view.revision,
          today: '2026-10-25',
          entries: [{ entryId: 'entry', state: state.inventory, reservedG: {} }],
        },
      });
    if (p === '/api/me/cooking-batches' && req.method() === 'GET') {
      if (state.failCook) return route.fulfill({ status: 503, json: { detail: 'Недоступно' } });
      return route.fulfill({ json: state.view });
    }
    if (p === '/api/me/meal-plan-entries/entry/portion-feedback')
      return route.fulfill({ json: [] });
    if (p.endsWith('/corrections') && req.method() === 'GET')
      return route.fulfill({ json: state.history });
    if (req.method() === 'POST') {
      const body = req.postDataJSON();
      state.calls.push({ path: p, body, key: req.headers()['idempotency-key'] });
      if (p === '/api/me/cooking-batches') {
        state.view.batches = [
          {
            id: 'batch',
            name: 'Рис',
            cookOn: '2026-10-25',
            useBy: body.useBy,
            status: 'PLANNED',
            estimatedYieldG: 400,
            actualYieldG: null,
            remainingG: 0,
            freeG: 0,
            expired: false,
            fingerprint: 'rice',
            recipeId: 'rice',
            instructions: 'Сварить рис',
            ingredients: [{ name: 'Рис сухой', quantityG: 200 }],
            allocations: candidates.map((c, i) => ({
              id: 'a' + i,
              entryId: c.ref.entryId,
              componentId: 'rice',
              date: c.date,
              mealType: 'DINNER',
              cookedG: 200,
              status: 'ACTIVE',
              mealReady: false,
            })),
          },
        ];
        state.view.candidates = [];
      }
      if (p.endsWith('/assign')) {
        state.view.batches[0].allocations.push({
          id: 'a0',
          entryId: 'entry',
          componentId: 'rice',
          date: '2026-10-25',
          mealType: 'DINNER',
          cookedG: 200,
          status: 'ACTIVE',
          mealReady: false,
        });
        state.view.candidates = [];
        state.view.batches[0].freeG = 200;
      }
      if (p.endsWith('/prepare')) {
        state.view.batches[0].status = 'COOKED';
        state.view.batches[0].remainingG = body.actualYieldG;
        state.view.batches[0].actualYieldG = body.actualYieldG;
      }
      if (p.endsWith('/ingredient-usage')) {
        state.inventory = 'DEDUCTED';
        state.view.batches.forEach((b) => (b.allocations[0].mealReady = true));
      }
      if (p.endsWith('/consume')) {
        state.view.batches[0].allocations[0].status = 'CONSUMED';
        state.view.batches[0].remainingG = 200;
      }
      if (p.endsWith('/weight-corrections')) {
        const b = state.view.batches[0],
          old = b.actualYieldG;
        b.actualYieldG = body.actualYieldG;
        b.allocations.forEach((a: any) => (a.cookedG = body.actualYieldG / 2));
        b.remainingG =
          b.allocations[0].status === 'CONSUMED' ? body.actualYieldG / 2 : body.actualYieldG;
        state.history.unshift({
          id: String(state.history.length),
          batchId: b.id,
          entryId: null,
          kind: 'YIELD',
          reason: body.reason,
          createdAt: '2026-10-25T12:00:00Z',
          before: { actualYieldG: old },
          after: { actualYieldG: body.actualYieldG },
        });
      }
      if (p.endsWith('/consumption-reversal')) {
        const b = state.view.batches[0];
        b.allocations[0].status = plan.status === 'CONFIRMED' ? 'ACTIVE' : 'RELEASED';
        b.remainingG = b.actualYieldG;
      }
      state.view.revision++;
      if (p.endsWith('/weight-corrections') && state.loseCorrectionResponse) {
        state.loseCorrectionResponse = false;
        return route.abort('failed');
      }
      return route.fulfill({
        json: { operationId: 'op', revision: state.view.revision, lotIds: [] },
      });
    }
    return route.fulfill({ status: 404, json: { detail: p } });
  });
  return state;
}
test('today shows portions without opening technical details and never opts into batch cooking automatically', async ({
  page,
}) => {
  const s = await fixture(page);
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Сегодня', exact: true })).toBeVisible();
  await expect(page.getByText(/курица ≈ 150 г.*рис ≈ 200 г/)).toBeVisible();
  await expect(page.getByRole('checkbox', { name: 'Готовить с запасом' })).not.toBeChecked();
  await expect(page.getByRole('button', { name: 'Приготовил', exact: true })).toBeVisible();
  expect(s.calls).toHaveLength(0);
  await expect(page.getByRole('button', { name: 'Пропустить', exact: true })).not.toBeVisible();
});
test('plan, prepare, serve and eat a shared component without leaving today', async ({ page }) => {
  const s = await fixture(page);
  await page.goto('/');
  await page.getByRole('checkbox', { name: 'Готовить с запасом' }).check();
  await page.getByRole('button', { name: 'Приготовить рис сразу на 2 дня' }).click();
  const plan = page.getByRole('button', { name: 'Запланировать партию', exact: true });
  await expect(plan).toBeDisabled();
  await page.getByLabel('Хранить до', { exact: true }).fill('2026-10-26');
  await plan.click();
  await expect(page.getByRole('button', { name: 'Приготовил', exact: true })).toBeDisabled();
  await page.getByLabel('Вес готовой партии, г').fill('400');
  await page.getByRole('button', { name: 'Приготовил партию', exact: true }).click();
  await expect(page.getByText('Уже готово: рис · 200 г на всех')).toBeVisible();
  await page.getByRole('button', { name: 'Приготовил остальное', exact: true }).click();
  await page.getByRole('button', { name: 'Съедено', exact: true }).click();
  await expect(page.getByText('Отмечено съеденным')).toBeVisible();
  expect(s.calls.map((c) => c.path)).toEqual([
    '/api/me/cooking-batches',
    '/api/me/cooking-batches/batch/prepare',
    '/api/me/meal-plan-entries/entry/ingredient-usage',
    '/api/me/cooking-batches/entries/entry/consume',
  ]);
  expect(s.calls[0].body.entries).toHaveLength(2);
  expect(s.calls.every((c) => c.key)).toBe(true);
  expect(new URL(page.url()).pathname).toBe('/');
});
test('preference survives reload and turning it off does not cancel existing parties', async ({
  page,
}) => {
  const s = await fixture(page);
  await page.goto('/');
  await page.getByRole('checkbox', { name: 'Готовить с запасом' }).check();
  await page.reload();
  await expect(page.getByRole('checkbox', { name: 'Готовить с запасом' })).toBeChecked();
  await page.getByRole('checkbox', { name: 'Готовить с запасом' }).uncheck();
  expect(s.calls).toHaveLength(0);
});
test('mobile day is within viewport and details are collapsed', async ({ page }) => {
  await fixture(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Сегодня', exact: true })).toBeVisible();
  for (const width of [360, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 800 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: '/tmp/smartmeal-today.png',
    fullPage: true,
    animations: 'disabled',
  });
});
test('cooking data failure is explicit and creates no parties', async ({ page }) => {
  const s = await fixture(page);
  s.failCook = true;
  await page.goto('/');
  await expect(page.getByText(/Не удалось проверить готовые остатки/)).toBeVisible();
  expect(s.calls).toHaveLength(0);
});

test('free cooked food can be assigned directly from the meal card', async ({ page }) => {
  const s = await fixture(page);
  s.view.batches = [
    {
      id: 'leftover',
      name: 'Рис',
      cookOn: '2026-10-25',
      useBy: '2026-10-26',
      status: 'COOKED',
      estimatedYieldG: 400,
      actualYieldG: 400,
      remainingG: 400,
      freeG: 400,
      expired: false,
      fingerprint: 'rice',
      recipeId: 'rice',
      instructions: 'Сварить рис',
      ingredients: [],
      allocations: [],
    },
  ];
  await page.goto('/');
  await page.getByRole('button', { name: 'Использовать готовое' }).click();
  await expect(page.getByText('Уже готово: рис · 200 г на всех')).toBeVisible();
  expect(s.calls.map((c) => c.path)).toEqual(['/api/me/cooking-batches/leftover/assign']);
});

test('Today navigation returns to the current date after viewing another day', async ({ page }) => {
  await fixture(page);
  await page.goto('/?date=2026-10-26');
  await expect(page.getByLabel('День меню')).toHaveValue('2026-10-26');
  await page.getByRole('link', { name: 'Сегодня', exact: true }).first().click();
  await expect(page.getByLabel('День меню')).toHaveValue('2026-10-25');
  await expect(page.getByRole('heading', { name: 'Сегодня', exact: true })).toBeVisible();
});

async function eatenFixture(page: Page) {
  const s = await fixture(page);
  s.inventory = 'DEDUCTED';
  s.view.batches = [
    {
      id: 'batch',
      name: 'Рис',
      cookOn: '2026-10-25',
      useBy: '2026-10-26',
      status: 'COOKED',
      estimatedYieldG: 400,
      actualYieldG: 400,
      remainingG: 200,
      freeG: 0,
      expired: false,
      fingerprint: 'rice',
      recipeId: 'rice',
      instructions: 'Сварить рис',
      ingredients: [],
      allocations: [
        {
          id: 'a',
          entryId: 'entry',
          componentId: 'rice',
          date: '2026-10-25',
          mealType: 'DINNER',
          cookedG: 200,
          status: 'CONSUMED',
          mealReady: true,
        },
      ],
    },
  ];
  await page.goto('/');
  await expect(page.getByText('Отмечено съеденным')).toBeVisible();
  await page.getByText('Другие действия', { exact: true }).click();
  return s;
}
test('mistaken weight and eaten marks can be corrected with explicit confirmation', async ({
  page,
}) => {
  const s = await eatenFixture(page);
  await page.getByRole('button', { name: 'Исправить вес партии', exact: true }).click();
  await page.getByLabel('Правильный вес всей партии, г').fill('600');
  await page.getByRole('button', { name: 'Сохранить правильный вес' }).click();
  await expect(
    page.getByRole('button', { name: 'Исправить вес партии', exact: true }),
  ).toBeVisible();
  await page.getByText('История исправлений', { exact: true }).click();
  await expect(page.getByText('Вес партии: 400 → 600 г')).toBeVisible();
  await page.getByRole('button', { name: 'Ошибочно отметил «съедено»', exact: true }).click();
  expect(s.calls.filter((c) => c.path.endsWith('/consumption-reversal'))).toHaveLength(0);
  await page.getByRole('button', { name: 'Отменить отметку «съедено»', exact: true }).click();
  await expect(page.getByText('Отмечено съеденным')).not.toBeVisible();
  await expect(page.getByRole('button', { name: 'Съедено', exact: true })).toBeVisible();
  expect(s.calls.some((c) => c.path.endsWith('/ingredient-usage'))).toBe(false);
});
test('lost correction response refreshes weight and prevents a second identical write', async ({
  page,
}) => {
  const s = await eatenFixture(page);
  s.loseCorrectionResponse = true;
  await page.getByRole('button', { name: 'Исправить вес партии', exact: true }).click();
  await page.getByLabel('Правильный вес всей партии, г').fill('600');
  await page.getByRole('button', { name: 'Сохранить правильный вес' }).click();
  await expect(page.getByRole('button', { name: 'Сохранить правильный вес' })).toBeDisabled();
  await page.getByText('История исправлений', { exact: true }).click();
  await expect(page.getByText('Вес партии: 400 → 600 г')).toBeVisible();
  expect(s.calls.filter((c) => c.path.endsWith('/weight-corrections'))).toHaveLength(1);
});
test('undo eaten after cancelling a day refreshes its cooked state', async ({ page }) => {
  const s = await fixture(page);
  s.plan.status = 'CANCELLED';
  s.inventory = 'DEDUCTED';
  s.view.batches = [
    {
      id: 'batch',
      name: 'Рис',
      cookOn: '2026-10-25',
      useBy: '2026-10-26',
      status: 'COOKED',
      estimatedYieldG: 400,
      actualYieldG: 400,
      remainingG: 200,
      freeG: 0,
      expired: false,
      fingerprint: 'rice',
      recipeId: 'rice',
      instructions: 'Сварить рис',
      ingredients: [],
      allocations: [
        {
          id: 'a',
          entryId: 'entry',
          componentId: 'rice',
          date: '2026-10-25',
          mealType: 'DINNER',
          cookedG: 200,
          status: 'CONSUMED',
          mealReady: true,
        },
      ],
    },
  ];
  await page.goto('/');
  await expect(page.getByText('Отмечено съеденным')).toBeVisible();
  await page.getByText('Другие действия', { exact: true }).click();
  await page.getByRole('button', { name: 'Ошибочно отметил «съедено»', exact: true }).click();
  await page.getByRole('button', { name: 'Отменить отметку «съедено»', exact: true }).click();
  await expect(page.getByText('Отмечено съеденным')).not.toBeVisible();
  expect(s.view.batches[0].allocations[0].status).toBe('RELEASED');
});
