import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
async function fixture(page: Page, configured = false) {
  const state = { configured, ids: configured ? [3] : ([] as number[]), writes: 0, fail: false };
  const available = [
    { id: 1, code: 'PAN', name: 'Сковорода' },
    { id: 2, code: 'POT', name: 'Кастрюля' },
    { id: 3, code: 'OVEN', name: 'Духовка' },
    { id: 4, code: 'MICROWAVE', name: 'Микроволновая печь' },
    { id: 5, code: 'AIR_FRYER', name: 'Аэрогриль' },
    { id: 6, code: 'BLENDER', name: 'Блендер' },
  ];
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/me/profile')
      return route.fulfill({
        json: {
          userId: 'kitchen-user',
          dailyCalorieTarget: 2000,
          equipmentConfigured: state.configured,
        },
      });
    if (path === '/api/auth/csrf')
      return route.fulfill({
        json: { token: 'test', headerName: 'X-CSRF', parameterName: '_csrf' },
      });
    if (path === '/api/me/kitchen') {
      if (route.request().method() === 'PUT') {
        state.writes++;
        if (state.fail)
          return route.fulfill({ status: 500, json: { detail: 'Сохранить не удалось' } });
        state.ids = route.request().postDataJSON().equipmentIds;
        state.configured = true;
      }
      return route.fulfill({
        json: { configured: state.configured, equipmentIds: state.ids, available },
      });
    }
    if (path === '/api/recipes')
      return route.fulfill({
        json: { content: [], page: 0, size: 12, totalPages: 0, totalElements: 0 },
      });
    return route.fulfill({ status: 404, json: { detail: 'Not found' } });
  });
  return state;
}
test('first setup saves appliances once and does not ask again after reload', async ({ page }) => {
  const state = await fixture(page);
  await page.goto('/onboarding');
  await expect(page).toHaveURL(/setup\/kitchen/);
  await page.getByRole('checkbox', { name: 'Духовка', exact: true }).check();
  await page.getByRole('checkbox', { name: /Аэрогриль/ }).check();
  await page.getByRole('button', { name: 'Сохранить и продолжить' }).click();
  await expect(page).toHaveURL(/^[^?]+\/onboarding$/);
  expect(state.ids).toEqual([3, 5]);
  await page.reload();
  await expect(page.getByLabel('Возраст, лет', { exact: true })).toBeVisible();
  expect(state.writes).toBe(1);
});
test('an explicit empty kitchen is a completed setup', async ({ page }) => {
  const state = await fixture(page);
  await page.goto('/setup/kitchen?next=/recipes');
  await page.getByRole('button', { name: 'Сохранить и продолжить' }).click();
  await expect(page).toHaveURL(/^[^?]+\/recipes$/);
  expect(state.configured).toBe(true);
  expect(state.ids).toEqual([]);
  await page.goto('/setup/kitchen?next=/recipes');
  await expect(page).toHaveURL(/^[^?]+\/recipes$/);
});
test('profile edits the saved kitchen without repeating the first-run flow', async ({ page }) => {
  const state = await fixture(page, true);
  await page.goto('/profile');
  await expect(page.locator('.kitchen-summary')).toContainText('Духовка');
  await page.locator('.kitchen-summary').getByRole('link', { name: 'Изменить' }).click();
  await expect(page.getByRole('checkbox', { name: 'Духовка', exact: true })).toBeChecked();
  await page.getByRole('checkbox', { name: 'Духовка', exact: true }).uncheck();
  await page.getByRole('checkbox', { name: /Микроволновая/ }).check();
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click();
  await expect(page).toHaveURL(/\/profile$/);
  expect(state.ids).toEqual([4]);
  await expect(page.locator('.kitchen-summary')).toContainText('Микроволновая');
});
test('failed save preserves choices and mobile setup fits the viewport', async ({ page }) => {
  const state = await fixture(page);
  state.fail = true;
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/setup/kitchen?next=/recipes');
  await page.getByRole('checkbox', { name: /Плита/ }).check();
  await page.getByRole('button', { name: 'Сохранить и продолжить' }).click();
  await expect(page.getByRole('alert')).toContainText('Сохранить не удалось');
  await expect(page.getByRole('checkbox', { name: /Плита/ })).toBeChecked();
  expect(state.configured).toBe(false);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: '/tmp/smartmeal-kitchen-mobile.png', fullPage: true });
});
