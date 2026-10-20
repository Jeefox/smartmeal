import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
async function fixture(page: Page) {
  const state = { empty: false, drafts: true, status: 200, offline: false, calls: [] as string[] };
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url());
    if (url.pathname === '/api/me/profile')
      return route.fulfill({
        json: {
          userId: 'shopping-user',
          dailyCalorieTarget: 2200,
          age: 30,
          sex: 'MALE',
          heightCm: 180,
          weightKg: 80,
          activityLevel: 'LOW',
          goal: 'MAINTAIN_WEIGHT',
        },
      });
    if (url.pathname === '/api/me/shopping-list') {
      state.calls.push(req.url());
      if (state.offline) return route.abort('failed');
      if (state.status !== 200)
        return route.fulfill({
          status: state.status,
          json: { detail: 'Период отклонён сервером' },
        });
      return route.fulfill({
        json: {
          from: url.searchParams.get('from'),
          to: url.searchParams.get('to'),
          containsDrafts: state.empty ? false : state.drafts,
          items: state.empty
            ? []
            : [
                {
                  productId: 1,
                  productName:
                    'API-продукт с очень длинным русским названием для проверки переноса текста',
                  quantityG: 1250.5,
                },
                { productId: 2, productName: 'Рис', quantityG: 150 },
                { productId: 3, productName: 'Рис', quantityG: 50 },
              ],
        },
      });
    }
    return route.fulfill({ status: 404, json: { detail: 'Нет метода' } });
  });
  return state;
}
test('показывает граммы и предупреждение, не добавляя цены, упаковки и отметки', async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto('/shopping?from=2026-10-18&to=2026-10-19');
  await expect(page.locator('.live-needs-list li')).toHaveCount(3);
  await expect(page.locator('.needs-quantity').first()).toContainText('1 250,5');
  await expect(page.getByText('В периоде есть черновые меню.', { exact: false })).toBeVisible();
  await expect(page.getByRole('checkbox')).toHaveCount(0);
  expect(await page.locator('.live-shopping-layout').innerText()).not.toContain('₽');
  const stored = await page.evaluate(() => JSON.stringify(localStorage));
  expect(stored).not.toContain('shopping-user');
  expect(stored).not.toContain('API-продукт');
  state.drafts = false;
  await page.getByRole('button', { name: 'Обновить список' }).click();
  await expect(page.getByText('В периоде есть черновые меню.', { exact: false })).toHaveCount(0);
});
test('пустой период — успешная выдача, а не ошибка', async ({ page }) => {
  const state = await fixture(page);
  state.empty = true;
  await page.goto('/shopping');
  await expect(page.getByRole('heading', { name: 'На этот период нет продуктов' })).toBeVisible();
  await expect(page.locator('.error-state')).toHaveCount(0);
  await expect(page.getByText('В периоде есть черновые меню.', { exact: false })).toHaveCount(0);
});
test('31 день отправляется, неверный и 32-дневный периоды блокируются до запроса', async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto('/shopping?from=2026-01-01&to=2026-01-31');
  await expect(page.locator('.live-needs-list')).toBeVisible();
  expect(state.calls.every((url) => url.includes('from=2026-01-01&to=2026-01-31'))).toBe(true);
  await page.locator('[name=to]').fill('2026-02-01');
  await page.getByRole('button', { name: 'Показать список' }).click();
  await expect(page.getByRole('alert')).toContainText('не более 31');
  expect(state.calls.every((url) => url.includes('from=2026-01-01&to=2026-01-31'))).toBe(true);
  await page.locator('[name=to]').fill('2025-12-31');
  await page.getByRole('button', { name: 'Показать список' }).click();
  await expect(page.getByRole('alert')).toContainText('раньше начальной');
  expect(state.calls.every((url) => url.includes('from=2026-01-01&to=2026-01-31'))).toBe(true);
  await page.goto('/shopping?from=bad&to=2026-01-31');
  await expect(
    page.getByText('Период в адресе страницы некорректен.', { exact: false }),
  ).toBeVisible();
  expect(state.calls.every((url) => url.includes('from=2026-01-01&to=2026-01-31'))).toBe(true);
});
test('период применяется явно и переживает перезагрузку/навигацию', async ({ page }) => {
  const state = await fixture(page);
  await page.goto('/shopping?from=2026-10-18&to=2026-10-19');
  await expect(page.locator('.live-needs-list')).toBeVisible();
  await page.locator('[name=from]').fill('2026-10-20');
  await page.locator('[name=to]').fill('2026-10-21');
  expect(state.calls.every((url) => url.includes('from=2026-10-18&to=2026-10-19'))).toBe(true);
  await page.getByRole('button', { name: 'Показать список' }).click();
  await expect(page).toHaveURL(/from=2026-10-20&to=2026-10-21/);
  await expect
    .poll(() => state.calls.some((url) => url.includes('from=2026-10-20&to=2026-10-21')))
    .toBe(true);
  await page.reload();
  await expect(page.locator('[name=from]')).toHaveValue('2026-10-20');
  await expect(page.locator('.live-needs-list')).toBeVisible();
  await page.goBack();
  await expect(page.locator('[name=from]')).toHaveValue('2026-10-18');
});
test('400/сеть не превращаются в пустоту, 401 требует входа', async ({ page }) => {
  const state = await fixture(page);
  state.status = 400;
  await page.goto('/shopping');
  await expect(page.locator('.error-state')).toContainText('Период отклонён сервером');
  await expect(page.getByRole('heading', { name: 'На этот период нет продуктов' })).toHaveCount(0);
  state.status = 200;
  await page.getByRole('button', { name: 'Повторить', exact: true }).click();
  await expect(page.locator('.live-needs-list')).toBeVisible();
  state.offline = true;
  await page.getByRole('button', { name: 'Обновить список' }).click();
  await expect(page.getByText('Не удалось обновить список:', { exact: false })).toBeVisible();
  await expect(page.locator('.live-needs-list li')).toHaveCount(3);
  await expect(page).toHaveURL(/shopping/);
  state.offline = false;
  state.status = 401;
  await page.getByRole('button', { name: 'Обновить список' }).click();
  await expect(page).toHaveURL(/login/);
  await expect(page.locator('.live-needs-list')).toHaveCount(0);
});
test('на 360/390/768/1440 нет переполнения и названия переносятся', async ({ page }) => {
  await fixture(page);
  await page.goto('/shopping?from=2026-10-18&to=2026-10-19');
  await expect(page.locator('.live-needs-list')).toBeVisible();
  for (const width of [360, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await expect
    .poll(() =>
      page.locator('.sidebar').evaluate((element) => element.getBoundingClientRect().right),
    )
    .toBeLessThanOrEqual(1);
  expect(
    await page.evaluate(() =>
      [
        ...document.querySelectorAll(
          'main,.shopping-period-form,.live-shopping-layout,.shopping-source,.needs-information',
        ),
      ].every((element) => {
        const bounds = element.getBoundingClientRect();
        return bounds.left >= 0 && bounds.right <= innerWidth + 1;
      }),
    ),
  ).toBe(true);
  await page.screenshot({ path: 'test-results/live-shopping-mobile.png', fullPage: true });
});
