import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import type { HouseholdMember } from '../src/household';
async function fixture(page: Page) {
  const state = {
    members: [] as HouseholdMember[],
    requests: [] as { path: string; body: unknown }[],
    fail: false,
  };
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      path = new URL(req.url()).pathname;
    if (path === '/api/me/profile')
      return route.fulfill({ json: { userId: 'owner', dailyCalorieTarget: 2000 } });
    if (path === '/api/auth/csrf')
      return route.fulfill({ json: { token: 't', headerName: 'X-CSRF', parameterName: '_csrf' } });
    if (path === '/api/planning-restrictions')
      return route.fulfill({
        json: {
          allergens: [
            { id: 1, name: 'Молоко' },
            { id: 2, name: 'Орехи' },
          ],
          equipment: [],
        },
      });
    if (path === '/api/retail-chains') return route.fulfill({ json: [{ id: 1, name: 'Чижик' }] });
    if (path === '/api/products')
      return route.fulfill({
        json: { content: [{ id: 1, name: 'Молоко' }], totalPages: 1, totalElements: 1 },
      });
    if (path.startsWith('/api/me/household-members')) {
      if (req.method() === 'GET')
        return state.fail
          ? route.fulfill({ status: 500, json: { detail: 'Ошибка' } })
          : route.fulfill({ json: state.members });
      state.requests.push({ path, body: req.method() === 'DELETE' ? null : req.postDataJSON() });
      if (req.method() === 'DELETE') {
        state.members = [];
        return route.fulfill({ status: 204 });
      }
      const input = req.postDataJSON(),
        m = {
          ...input,
          id: '11111111-1111-4111-8111-111111111111',
          excludedProducts: input.excludedProductIds.length ? [{ id: 1, name: 'Молоко' }] : [],
        };
      state.members = [m];
      return route.fulfill({ json: m });
    }
    return route.fulfill({ json: [] });
  });
  return state;
}
test('creates and edits a person with their own target and allergies, then selects them in common planning', async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto('/household');
  await page.getByRole('button', { name: 'Добавить человека', exact: true }).click();
  await page.getByLabel('Имя', { exact: true }).fill('Анна');
  await page.getByLabel('Суточная норма, ккал').fill('1500');
  await page.getByRole('checkbox', { name: 'Молоко', exact: true }).check();
  await page.getByLabel('Найти продукт для исключения').fill('мо');
  await page
    .getByRole('group', { name: 'Исключённые продукты' })
    .getByRole('checkbox', { name: 'Молоко', exact: true })
    .check();
  await page.getByRole('button', { name: 'Сохранить профиль', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Анна', exact: true })).toBeVisible();
  expect(state.members[0].dailyCalorieTarget).toBe(1500);
  expect(state.members[0].allergenIds).toEqual([1]);
  expect(state.members[0].excludedProductIds).toEqual([1]);
  await page.getByRole('button', { name: 'Изменить профиль Анна' }).click();
  await page.getByLabel('Суточная норма, ккал').fill('1700');
  await page.getByRole('button', { name: 'Сохранить профиль', exact: true }).click();
  await page.getByRole('link', { name: 'Подобрать общее меню' }).click();
  await page.getByRole('checkbox', { name: 'Анна · 1 700 ккал' }).check();
  await expect(page.getByRole('group', { name: 'Для кого готовим' })).toContainText('Анна');
  await expect(page.getByText('Общая сумма на 2 чел.')).toBeVisible();
  expect(state.requests).toHaveLength(2);
});
test('archives a person after confirmation and clearly preserves saved menus', async ({ page }) => {
  const state = await fixture(page);
  state.members = [
    {
      id: 'member',
      name: 'Анна',
      dailyCalorieTarget: 1500,
      allergenIds: [],
      excludedProductIds: [],
    },
  ];
  await page.goto('/household');
  await page.getByRole('button', { name: 'Убрать из семьи: Анна' }).click();
  await expect(page.getByRole('dialog')).toContainText('Сохранённые меню и порции останутся');
  await page.getByRole('button', { name: 'Убрать профиль', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Анна', exact: true })).toHaveCount(0);
  expect(state.requests).toHaveLength(1);
});
test('a failed family request is not presented as an empty household', async ({ page }) => {
  const state = await fixture(page);
  state.fail = true;
  await page.goto('/household');
  await expect(page.getByText('Не удалось загрузить семью.')).toBeVisible();
  await expect(
    page.getByText('Добавьте близких, затем отметьте их в настройках подбора.', { exact: false }),
  ).toHaveCount(0);
});
test('household settings fit on a phone', async ({ page }) => {
  await fixture(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/household');
  await page.getByRole('button', { name: 'Добавить человека', exact: true }).click();
  await expect(page.getByLabel('Имя', { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
