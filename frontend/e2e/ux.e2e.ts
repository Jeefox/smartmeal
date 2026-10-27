import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
async function fixture(page: Page) {
  const calls: string[] = [];
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url());
    calls.push(url.toString());
    if (url.pathname === '/api/me/profile')
      return route.fulfill({ json: { userId: 'ux-user', dailyCalorieTarget: 2000 } });
    if (url.pathname === '/api/retail-chains')
      return route.fulfill({ json: [{ id: 42, name: 'Сеть API', code: 'API' }] });
    if (url.pathname === '/api/stores')
      return route.fulfill({
        json: [
          {
            id: 71,
            chainId: 42,
            chainName: 'Сеть API',
            city: url.searchParams.get('city'),
            address: 'Улица теста, 10',
          },
        ],
      });
    if (url.pathname === '/api/me/shopping-list')
      return route.fulfill({
        json: {
          from: url.searchParams.get('from'),
          to: url.searchParams.get('to'),
          containsDrafts: false,
          items: [],
        },
      });
    if (url.pathname === '/api/me/shopping-estimate')
      return route.fulfill({
        json: {
          from: url.searchParams.get('from'),
          to: url.searchParams.get('to'),
          storeId: 71,
          containsDrafts: false,
          complete: true,
          knownPurchaseCost: 0,
          items: [],
        },
      });
    if (url.pathname === '/api/recipes') {
      const n = Number(url.searchParams.get('page') ?? 0),
        size = Number(url.searchParams.get('size') ?? 12);
      return route.fulfill({
        json: {
          content: Array.from({ length: n === 0 ? 12 : 2 }, (_, i) => ({
            id: i === 0 ? '1eb55deb-2b9e-50be-a46c-e5cbb032c585' : `recipe-${n}-${i}`,
            name: i === 0 ? 'Омлет с сыром на сковороде' : `Блюдо ${n * size + i}`,
            description: 'Простой домашний рецепт',
            preparationTimeMin: 15,
            servings: 2,
          })),
          page: n,
          size,
          totalElements: 14,
          totalPages: 2,
        },
      });
    }
    return route.fulfill({ status: 404 });
  });
  return calls;
}
test('город сохраняется после перезагрузки, повторного ввода нет, его можно изменить', async ({
  page,
}) => {
  const calls = await fixture(page);
  await page.goto('/shopping?view=calendar');
  await page.getByLabel('Город', { exact: true }).fill('Тула');
  await page.getByRole('button', { name: 'Найти магазины', exact: true }).click();
  await expect(page.locator('.location-summary')).toContainText('Тула');
  await page.reload();
  await expect(page.locator('.location-summary')).toContainText('Тула');
  await expect(page.getByLabel('Город', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /Сеть API.*Улица теста/ })).toBeVisible();
  await page.getByRole('button', { name: 'Изменить город', exact: true }).click();
  await page.getByLabel('Город', { exact: true }).fill('Калуга');
  await page.getByRole('button', { name: 'Найти магазины', exact: true }).click();
  await expect(page.locator('.location-summary')).toContainText('Калуга');
  expect(
    calls
      .filter((x) => new URL(x).pathname === '/api/stores')
      .some((x) => new URL(x).searchParams.get('city') === 'Калуга'),
  ).toBe(true);
  expect(await page.evaluate(() => localStorage.getItem('smartmeal.v2.preferences.city'))).toBe(
    '"Калуга"',
  );
});
test('выбранный магазин остаётся при переходе к рецептам и назад, режим в URL', async ({
  page,
}) => {
  await fixture(page);
  await page.goto('/shopping?view=calendar');
  await page.getByLabel('Город', { exact: true }).fill('Тула');
  await page.getByRole('button', { name: 'Найти магазины', exact: true }).click();
  await page.getByRole('button', { name: /Сеть API.*Улица теста/ }).click();
  await expect(page.locator('.estimate-result')).toBeVisible();
  await page.getByRole('link', { name: 'Рецепты', exact: true }).first().click();
  await expect(page.locator('.recipe-grid')).toBeVisible();
  await page
    .getByRole('link', { name: /^Покупки/ })
    .first()
    .click();
  await page.getByRole('link', { name: 'Рассчитать продукты по датам' }).click();
  await expect(page.locator('.estimate-result')).toBeVisible();
  await expect(page.locator('.store-choice.selected')).toContainText('Улица теста');
  await page.getByRole('button', { name: 'Сравнить магазины', exact: true }).click();
  await expect(page).toHaveURL(/view=comparison/);
  await page.reload();
  await expect(
    page.getByRole('button', { name: 'Сравнить магазины', exact: true }),
  ).toHaveAttribute('aria-pressed', 'true');
  const stored = await page.evaluate(() => JSON.stringify(localStorage));
  expect(stored).not.toContain('ux-user');
  expect(stored).not.toContain('Улица теста');
});
test('компактный каталог без неработающих фильтров, фото и пагинация', async ({ page }) => {
  await fixture(page);
  await page.goto('/recipes');
  await expect(page.locator('.recipe-card')).toHaveCount(12);
  await expect(page.locator('.catalog-hero')).toHaveCount(0);
  await expect(page.getByRole('combobox', { name: 'Время приготовления' })).toHaveCount(0);
  const img = page.locator('.recipe-card img').first();
  await expect(img).toHaveAttribute('src', /omelette-960.webp/);
  await expect(img).toHaveAttribute('srcset', /480w/);
  await expect(page.locator('.generated-cover-label').first()).toContainText('Фото подачи');
  await expect(page.locator('.generated-cover-label').first()).toHaveAttribute('title', /CC0/);
  await expect.poll(() => img.evaluate((e: HTMLImageElement) => e.naturalWidth)).toBeGreaterThan(0);
  await page.getByRole('button', { name: 'Следующая страница', exact: true }).click();
  await expect(page).toHaveURL(/page=1/);
  await expect(page.locator('.recipe-card')).toHaveCount(2);
  await expect(page.locator('.catalog-range')).toContainText('13–14 из 14');
  for (const width of [360, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBe(true);
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() => page.locator('.sidebar').evaluate((e) => e.getBoundingClientRect().right))
    .toBeLessThanOrEqual(1);
  await page.screenshot({ path: 'test-results/catalogue-ux-mobile.png', fullPage: true });
});
