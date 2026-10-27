import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
async function fixture(page: Page) {
  const owner = '11111111-1111-4111-8111-111111111111',
    member = '22222222-2222-4222-8222-222222222222';
  const settings = {
    expectedRevision: 0,
    adultConfirmed: true,
    weightKg: 80,
    proteinTargetG: 90,
    fiberTargetG: 25,
    maxMealG: 650,
    targetSource: 'USER_SET',
  };
  const state = {
    people: [
      { participantId: owner, name: 'Вы', revision: 0, settings: { ...settings } },
      {
        participantId: member,
        name: 'Мама',
        revision: 0,
        settings: { ...settings, proteinTargetG: 65, fiberTargetG: 22 },
      },
    ],
    writes: [] as any[],
  };
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname,
      method = route.request().method();
    if (path === '/api/me/profile')
      return route.fulfill({
        json: { userId: owner, dailyCalorieTarget: 2000, equipmentConfigured: true },
      });
    if (path === '/api/me/kitchen')
      return route.fulfill({ json: { configured: true, equipmentIds: [], available: [] } });
    if (path === '/api/auth/csrf')
      return route.fulfill({
        json: { token: 'test', headerName: 'X-CSRF', parameterName: '_csrf' },
      });
    if (path === '/api/me/nutrition-targets') return route.fulfill({ json: state.people });
    if (path.startsWith('/api/me/nutrition-targets/') && method === 'PUT') {
      const person = state.people.find((p) => path.endsWith(p.participantId))!;
      const body = route.request().postDataJSON();
      state.writes.push({ id: person.participantId, body });
      person.revision++;
      person.settings = { ...body, expectedRevision: person.revision };
      return route.fulfill({ json: person });
    }
    return route.fulfill({ status: 404, json: { detail: 'Not found' } });
  });
  return state;
}
test('profile shows saved nutrients compactly and saves edited daily amounts', async ({ page }) => {
  const state = await fixture(page);
  await page.goto('/profile');
  const block = page.locator('#nutrition');
  await expect(block).toContainText('Белок 90 г/сут · Клетчатка 25 г/сут');
  await expect(page.getByLabel('Белок в сутки, г · можно не задавать')).not.toBeVisible();
  await block.getByText('Изменить цели питания', { exact: true }).click();
  await page.getByLabel('Белок в сутки, г · можно не задавать').fill('110');
  await page.getByLabel('Клетчатка в сутки, г · можно не задавать').fill('30');
  await page.getByRole('button', { name: 'Сохранить цели', exact: true }).click();
  await expect(block).toContainText('Белок 110 г/сут · Клетчатка 30 г/сут');
  expect(state.writes).toHaveLength(1);
  expect(state.writes[0].body).toMatchObject({
    proteinTargetG: 110,
    fiberTargetG: 30,
    expectedRevision: 0,
  });
  await page.reload();
  await expect(page.locator('#nutrition')).toContainText('Белок 110 г/сут · Клетчатка 30 г/сут');
});
test('legacy nutrient URL opens the profile section and preserves individual targets', async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto('/nutrition-settings');
  await expect(page).toHaveURL(/\/profile#nutrition$/);
  await page
    .getByRole('combobox', { name: 'Для кого', exact: true })
    .selectOption(state.people[1].participantId);
  await expect(page.getByLabel('Белок в сутки, г · можно не задавать')).toHaveValue('65');
  await page.getByLabel('Белок в сутки, г · можно не задавать').fill('70');
  await page.getByRole('button', { name: 'Сохранить цели', exact: true }).click();
  await expect(page.getByText('Цели сохранены.', { exact: false })).toBeVisible();
  expect(state.people[0].settings.proteinTargetG).toBe(90);
  expect(state.people[1].settings.proteinTargetG).toBe(70);
});
