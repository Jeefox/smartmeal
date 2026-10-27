import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
async function fixture(page: Page, initial = false) {
  const candidates = [0, 1].map((i) => ({
    ref: { entryId: 'entry' + i, componentId: 'SIDE:rice' },
    date: `2026-10-${25 + i}`,
    mealType: 'DINNER',
    recipeName: 'Рис отварной',
    fingerprint: 'rice',
    cookedG: 200,
  }));
  const batch = {
    id: 'batch',
    name: 'Рис отварной',
    cookOn: '2026-10-25',
    useBy: '2026-10-26',
    status: 'PLANNED',
    estimatedYieldG: 400,
    actualYieldG: null as number | null,
    remainingG: 0,
    freeG: 0,
    expired: false,
    fingerprint: 'rice',
    recipeId: 'rice',
    instructions: 'Сварить рис. Разложить по контейнерам.',
    ingredients: [{ name: 'Рис сухой', quantityG: 200 }],
    allocations: candidates.map((c, i) => ({
      id: 'a' + i,
      entryId: c.ref.entryId,
      componentId: c.ref.componentId,
      date: c.date,
      mealType: c.mealType,
      cookedG: 200,
      status: 'ACTIVE',
    })),
  };
  const state = {
    view: {
      revision: 0,
      today: '2026-10-25',
      candidates: initial ? [] : candidates,
      batches: initial ? [batch] : ([] as (typeof batch)[]),
      drafts: [],
    },
    calls: [] as { path: string; body: any; key: string | null }[],
    lost: false,
  };
  await page.clock.install({ time: new Date('2026-10-25T12:00:00+03:00') });
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      p = new URL(req.url()).pathname;
    if (p === '/api/me/profile')
      return route.fulfill({ json: { userId: 'user', dailyCalorieTarget: 2000 } });
    if (p === '/api/auth/csrf')
      return route.fulfill({
        json: { token: 'token', headerName: 'X-CSRF', parameterName: '_csrf' },
      });
    if (p === '/api/me/cooking-batches' && req.method() === 'GET')
      return route.fulfill({ json: state.view });
    if (p.startsWith('/api/me/cooking-batches') && req.method() === 'POST') {
      const body = req.postDataJSON();
      state.calls.push({ path: p, body, key: req.headers()['idempotency-key'] });
      if (p.endsWith('/prepare')) {
        batch.status = 'COOKED';
        batch.actualYieldG = body.actualYieldG;
        batch.remainingG = body.actualYieldG;
        batch.allocations.forEach((a) => (a.cookedG = body.actualYieldG / 2));
      } else if (p.endsWith('/discard')) {
        batch.status = 'DISCARDED';
        batch.remainingG = 0;
      } else if (p.endsWith('/consume')) {
        batch.allocations[0].status = 'CONSUMED';
        batch.remainingG = 200;
      } else {
        state.view.batches = [batch];
        state.view.candidates = [];
      }
      state.view.revision++;
      if (state.lost) {
        state.lost = false;
        return route.abort();
      }
      return route.fulfill({
        json: { operationId: 'op', revision: state.view.revision, lotIds: [] },
      });
    }
    return route.fulfill({ status: 404, json: { detail: 'unexpected ' + p } });
  });
  return state;
}
test('explicit date plans one shared batch for two days without deducting raw stock', async ({
  page,
}) => {
  const s = await fixture(page);
  await page.goto('/cooking');
  const plan = page.getByRole('button', { name: 'Запланировать общую партию' });
  await expect(plan).toBeDisabled();
  await page.getByLabel('Хранить приготовленную партию до').fill('2026-10-26');
  await plan.click();
  await expect(page.getByText('Рис сухой: 200 г')).toHaveCount(0);
  await expect.poll(() => s.calls.length).toBe(1);
  expect(s.calls[0].body.entries).toHaveLength(2);
  expect(s.calls[0].key).toBeTruthy();
  await expect(
    page.getByRole('button', { name: 'Приготовил партию · списать сырые продукты' }),
  ).toBeVisible();
  await page.getByText('Что и сколько приготовить').click();
  await expect(page.getByText('Рис сухой: 200 г')).toBeVisible();
});
test('actual ready weight is explicit and preparation refreshes cooked balance', async ({
  page,
}) => {
  const s = await fixture(page, true);
  await page.goto('/cooking');
  const cook = page.getByRole('button', { name: 'Приготовил партию · списать сырые продукты' });
  await expect(cook).toBeDisabled();
  await page.getByLabel('Фактический вес готовой партии, г').fill('420');
  await cook.click();
  await expect(page.getByText(/Готово · осталось 420 г/)).toBeVisible();
  expect(s.calls[0].body.actualYieldG).toBe(420);
});
test('expired batch cannot be eaten and discarding requires confirmation', async ({ page }) => {
  const s = await fixture(page, true);
  s.view.batches[0].status = 'COOKED';
  s.view.batches[0].expired = true;
  await page.goto('/cooking');
  await expect(page.getByRole('button', { name: /^Съедено/ })).toHaveCount(0);
  await page.getByRole('button', { name: 'Списать готовый остаток' }).click();
  expect(s.calls).toHaveLength(0);
  await page.getByRole('button', { name: 'Подтвердить', exact: true }).click();
  await expect(page.getByText('Изменение сохранено.')).toBeVisible();
  expect(s.calls[0].path).toContain('/discard');
});
test('lost preparation response reconciles balance without repeating deduction', async ({
  page,
}) => {
  const s = await fixture(page, true);
  s.lost = true;
  await page.goto('/cooking');
  await page.getByLabel('Фактический вес готовой партии, г').fill('400');
  await page.getByRole('button', { name: 'Приготовил партию · списать сырые продукты' }).click();
  await expect(page.getByText(/Готово · осталось 400 г/)).toBeVisible();
  expect(s.calls).toHaveLength(1);
});
test('cooking page fits narrow phone', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await fixture(page, true);
  await page.goto('/cooking');
  await expect(page.getByRole('heading', { name: 'Готовка на несколько дней' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: '/tmp/smartmeal-cooking-mobile.png', fullPage: true });
});
test('demo does not access personal cooking data', async ({ page }) => {
  let calls = 0;
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"demo"'));
  await page.route('**/api/**', (r) => {
    calls++;
    return r.abort();
  });
  await page.goto('/cooking');
  await expect(page.getByRole('heading', { name: 'Готовка доступна через API' })).toBeVisible();
  expect(calls).toBe(0);
});
