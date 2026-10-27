import { test, expect } from '@playwright/test';
const owner = '11111111-1111-4111-8111-111111111111';
const preferences = `smartmeal.planning.preferences.v1.${owner}`;
const shopping = `smartmeal.shopping.sessions.v1.${owner}.22222222-2222-4222-8222-222222222222`;
async function fixture(page: any) {
  let signed = true;
  await page.addInitScript(
    ({ preferences, shopping }: any) => {
      localStorage.setItem('smartmeal.v2.mode', '"api"');
      localStorage.setItem(
        preferences,
        JSON.stringify({
          version: 1,
          values: {
            budget: '4371',
            city: 'Тула',
            members: ['33333333-3333-4333-8333-333333333333'],
          },
        }),
      );
      localStorage.setItem(
        shopping,
        JSON.stringify({ version: 1, form: { privateDraft: { price: '827.42' } }, pending: null }),
      );
    },
    { preferences, shopping },
  );
  await page.route('**/api/**', async (route: any) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/me/profile')
      return signed
        ? route.fulfill({ json: { userId: owner, dailyCalorieTarget: 2000 } })
        : route.fulfill({ status: 401 });
    if (path === '/api/auth/csrf')
      return route.fulfill({
        json: { token: 'audit-csrf', headerName: 'X-CSRF-TOKEN', parameterName: '_csrf' },
      });
    return route.fulfill({ json: { content: [], items: [], totalPages: 0, totalElements: 0 } });
  });
  await page.route('**/logout', async (route: any) => {
    signed = false;
    return route.fulfill({ status: 204 });
  });
  await page.goto('/profile');
  await expect(page.getByRole('button', { name: 'Выйти', exact: true })).toBeVisible();
}
test('logout removes all private preferences and drafts', async ({ page }) => {
  await fixture(page);
  await page.getByRole('button', { name: 'Выйти', exact: true }).click();
  await expect(page).toHaveURL(/login/);
  const result = await page.evaluate(
    ({ preferences, shopping }) => ({
      preferences: !!localStorage.getItem(preferences),
      shopping: !!localStorage.getItem(shopping),
    }),
    { preferences, shopping },
  );
  expect(result).toEqual({ preferences: false, shopping: false });
});
test('expired session removes private drafts', async ({ page }) => {
  await fixture(page);
  await page.evaluate(() => window.dispatchEvent(new Event('smartmeal:unauthorized')));
  await expect(page).toHaveURL(/login/);
  expect(await page.evaluate((key) => !!localStorage.getItem(key), shopping)).toBe(false);
});
test('account switch resets the calorie form', async ({ page }) => {
  let profile = { userId: owner, dailyCalorieTarget: 2345, weightKg: 91 };
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    return path === '/api/me/profile'
      ? route.fulfill({ json: profile })
      : route.fulfill({ json: { content: [], items: [] } });
  });
  await page.goto('/profile');
  await expect(page.getByLabel('Своя суточная цель, ккал')).toHaveValue('2345');
  profile = {
    userId: '44444444-4444-4444-8444-444444444444',
    dailyCalorieTarget: 1678,
    weightKg: 63,
  };
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')));
  await expect(page.locator('.saved-target')).toContainText('1 678');
  await expect(page.locator('.account-details')).toContainText('63 кг');
  await expect(page.getByLabel('Своя суточная цель, ккал')).toHaveValue('1678');
});
test('account switch resets the body questionnaire', async ({ page }) => {
  let current = {
    userId: owner,
    dailyCalorieTarget: 2345,
    weightKg: 91,
    age: 38,
    sex: 'MALE',
    heightCm: 181,
    activityLevel: 'LOW',
    goal: 'MAINTAIN_WEIGHT',
  };
  let returnedUser = '';
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/me/profile') {
      returnedUser = current.userId;
      return route.fulfill({ json: current });
    }
    return route.fulfill({ json: { content: [], items: [] } });
  });
  await page.goto('/onboarding');
  await expect(page.getByLabel('Вес, кг', { exact: true })).toHaveValue('91');
  current = {
    userId: '44444444-4444-4444-8444-444444444444',
    dailyCalorieTarget: 1678,
    weightKg: 63,
    age: 25,
    sex: 'FEMALE',
    heightCm: 163,
    activityLevel: 'HIGH',
    goal: 'GAIN_WEIGHT',
  };
  await page.evaluate(() => window.dispatchEvent(new Event('visibilitychange')));
  await expect.poll(() => returnedUser).toBe(current.userId);
  await expect(page.getByLabel('Вес, кг', { exact: true })).toHaveValue('63');
  await expect(page.getByLabel('Возраст, лет', { exact: true })).toHaveValue('25');
});

test('a session notification from another tab clears the open private form', async ({
  page,
  context,
}) => {
  let profile = { userId: owner, dailyCalorieTarget: 2345 };
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', (route) =>
    new URL(route.request().url()).pathname === '/api/me/profile'
      ? route.fulfill({ json: profile })
      : route.fulfill({ json: { content: [], items: [] } }),
  );
  await page.goto('/profile');
  await expect(page.getByLabel('Своя суточная цель, ккал')).toHaveValue('2345');
  profile = { userId: '44444444-4444-4444-8444-444444444444', dailyCalorieTarget: 1678 };
  const tab = await context.newPage();
  await tab.goto('/login');
  await tab.evaluate(() => {
    const channel = new BroadcastChannel('smartmeal-session');
    channel.postMessage('changed');
    channel.close();
  });
  await expect(page.getByLabel('Своя суточная цель, ккал')).toHaveValue('1678');
  await expect(page.getByLabel('Своя суточная цель, ккал')).not.toHaveValue('2345');
  await tab.close();
});
