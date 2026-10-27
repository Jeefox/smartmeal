import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
async function fixture(page: Page) {
  const state = {
    status: 200,
    offline: false,
    empty: false,
    complete: false,
    storesEmpty: false,
    reference: false,
    weighted: false,
    calls: [] as string[],
  };
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url()),
      path = url.pathname;
    if (path === '/api/me/profile')
      return route.fulfill({ json: { userId: 'estimate-user', dailyCalorieTarget: 2000 } });
    if (path === '/api/retail-chains')
      return route.fulfill({ json: [{ id: 42, code: 'test', name: 'Сеть API' }] });
    if (path === '/api/stores') {
      state.calls.push(url.toString());
      return route.fulfill({
        json: state.storesEmpty
          ? []
          : [
              {
                id: 987,
                chainId: 42,
                chainName: 'Сеть API',
                city: 'Тула',
                address: 'Очень длинная улица для проверки переноса, дом 123',
              },
              {
                id: 988,
                chainId: 42,
                chainName: 'Сеть API',
                city: 'Тула',
                address: 'Второй адрес',
              },
            ],
      });
    }
    if (path === '/api/me/shopping-list')
      return route.fulfill({
        json: {
          from: url.searchParams.get('from'),
          to: url.searchParams.get('to'),
          containsDrafts: false,
          items: [{ productId: 1, productName: 'Рис', quantityG: 1250.55 }],
        },
      });
    if (path === '/api/me/shopping-estimate') {
      state.calls.push(url.toString());
      if (state.offline) return route.abort('failed');
      if (state.status !== 200) return route.fulfill({ status: state.status, body: '' });
      const covered = {
        productId: 1,
        productName: 'Рис с длинным названием продукта для проверки мобильного отображения',
        requiredWeightG: 1250.55,
        storeProductId: 700,
        storeProductName: 'Упаковка риса API',
        packageWeightG: 500,
        packageCount: state.weighted ? null : 3,
        packagePrice: 100.25,
        purchaseCost: 300.75,
        remainderG: 249,
        priceObservedAt: '2020-01-05T10:20:00Z',
        priceSource: state.reference ? null : 'MANUAL',
        referencePrice: state.reference,
        referenceSourceName: state.reference ? 'Тестовый каталог' : null,
        referenceSourceScope: state.reference ? 'Офлайн-точка Тулы не подтверждена' : null,
        referenceSourceUrl: state.reference ? 'https://example.invalid/reference' : null,
      };
      return route.fulfill({
        json: {
          from: url.searchParams.get('from'),
          to: url.searchParams.get('to'),
          storeId: Number(url.searchParams.get('storeId')),
          containsDrafts: !state.empty,
          complete: (state.empty || state.complete) && !state.reference,
          knownPurchaseCost: state.empty ? 0 : 300.75,
          items: state.empty
            ? []
            : state.complete
              ? [covered]
              : [
                  covered,
                  {
                    productId: 2,
                    productName: 'Неизвестный продукт',
                    requiredWeightG: 25,
                    storeProductId: null,
                    storeProductName: null,
                    packageWeightG: null,
                    packageCount: null,
                    packagePrice: null,
                    purchaseCost: null,
                    remainderG: null,
                    priceObservedAt: null,
                    priceSource: null,
                  },
                ],
        },
      });
    }
    return route.fulfill({ status: 404 });
  });
  return state;
}
async function choose(page: Page) {
  await page.getByLabel('Город', { exact: true }).fill('Тула');
  await page.getByRole('button', { name: 'Найти магазины' }).click();
  await page.getByRole('button', { name: /Сеть API.*Очень длинная/ }).click();
}
test('конкретная точка, упаковки, неполная сумма, старая цена и отсутствие локальных данных', async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto('/shopping?from=2026-10-20&to=2026-10-21');
  await expect(page.locator('.live-needs-list')).toBeVisible();
  expect(state.calls.some((x) => x.includes('shopping-estimate'))).toBe(false);
  await choose(page);
  await expect(page.locator('.estimate-total')).toContainText('Известная часть стоимости');
  await expect(page.locator('.estimate-total')).toContainText('300,75');
  await expect(page.locator('.estimate-items')).toContainText('3 уп. × 500 г');
  await expect(page.locator('.estimate-items')).toContainText('249 г');
  await expect(page.locator('.estimate-items')).toContainText('2020');
  await expect(page.locator('.estimate-items')).toContainText('ручной ввод');
  await expect(page.locator('.estimate-items li').nth(1)).toContainText('Стоимость не определена');
  expect(await page.locator('.estimate-items li').nth(1).innerText()).not.toContain('₽');
  await expect(page.getByText('Расчёт включает черновые меню.', { exact: false })).toBeVisible();
  expect(state.calls.some((x) => x.includes('from=2026-10-20&to=2026-10-21&storeId=987'))).toBe(
    true,
  );
  const storage = await page.evaluate(() => JSON.stringify(localStorage));
  expect(storage).not.toContain('estimate-user');
  expect(storage).not.toContain('300.75');
  state.complete = true;
  await page.getByRole('button', { name: 'Обновить расчёт', exact: true }).click();
  await expect(page.locator('.estimate-total')).toContainText('Все продукты покрыты');
});
test('смена сети/точки/периода не показывает чужой расчёт, пустые магазины и пустое меню', async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto('/shopping?view=calendar');
  await choose(page);
  await expect(page.locator('.estimate-total')).toBeVisible();
  await page.getByRole('button', { name: /Сеть API.*Второй адрес/ }).click();
  await expect.poll(() => state.calls.some((x) => x.includes('storeId=988'))).toBe(true);
  await page.getByLabel('Торговая сеть').selectOption('42');
  await expect(page.locator('.estimate-result')).toHaveCount(0);
  await expect.poll(() => state.calls.some((x) => x.includes('chainId=42'))).toBe(true);
  state.storesEmpty = true;
  await page.getByRole('button', { name: 'Обновить магазины' }).click();
  await expect(
    page.getByText('В этом городе по выбранному фильтру нет активных магазинов.', { exact: false }),
  ).toBeVisible();
  state.storesEmpty = false;
  await page.getByRole('button', { name: 'Обновить магазины' }).click();
  state.empty = true;
  await page.getByRole('button', { name: /Сеть API.*Второй адрес/ }).click();
  await expect(page.locator('.estimate-total')).toContainText('0,00');
  await expect(page.locator('.estimate-total')).toContainText('Все продукты покрыты');
  await page.locator('[name=from]').fill('2026-10-20');
  await page.locator('[name=to]').fill('2026-10-21');
  await page.getByRole('button', { name: 'Показать список' }).click();
  await expect
    .poll(() => state.calls.some((x) => x.includes('from=2026-10-20&to=2026-10-21&storeId=988')))
    .toBe(true);
});
test('400/404/409 и сетевой сбой не маскируются, 401 завершает сессию', async ({ page }) => {
  const state = await fixture(page);
  await page.goto('/shopping?view=calendar');
  state.status = 400;
  await choose(page);
  await expect(page.locator('.estimate-error')).toContainText('Проверьте параметры');
  for (const [status, message] of [
    [404, 'Расчёт или магазин недоступен'],
    [409, 'Магазин больше не активен'],
  ] as const) {
    state.status = status;
    await page.getByRole('button', { name: 'Обновить расчёт', exact: true }).click();
    await expect(page.locator('.estimate-error')).toContainText(message);
  }
  state.status = 200;
  await page.getByRole('button', { name: 'Обновить расчёт', exact: true }).click();
  await expect(page.locator('.estimate-total')).toBeVisible();
  state.offline = true;
  await page.getByRole('button', { name: 'Обновить расчёт', exact: true }).click();
  await expect(page.locator('.estimate-error')).toContainText('ранее полученный');
  await expect(page.locator('.estimate-total')).toBeVisible();
  state.offline = false;
  state.status = 401;
  await page.getByRole('button', { name: 'Обновить расчёт', exact: true }).click();
  await expect(page).toHaveURL(/login/);
});
test('расчёт на телефоне без горизонтального переполнения', async ({ page }) => {
  await fixture(page);
  await page.goto('/shopping?view=calendar');
  await choose(page);
  await expect(page.locator('.estimate-total')).toBeVisible();
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
  expect(
    await page
      .locator('.estimate-panel')
      .evaluate((e) => e.getBoundingClientRect().right <= innerWidth),
  ).toBe(true);
  await page.screenshot({ path: 'test-results/estimate-mobile.png', fullPage: true });
});

test('каталожный ориентир даёт ненулевую оценку и явно отмечен как неподтверждённый', async ({
  page,
}) => {
  const state = await fixture(page);
  state.reference = true;
  state.complete = true;
  await page.goto('/shopping?view=calendar');
  await choose(page);
  await expect(
    page.getByText('Предварительная стоимость с ориентирами', { exact: true }),
  ).toBeVisible();
  await expect(page.getByText('300,75', { exact: false }).first()).toBeVisible();
  await expect(
    page.getByText('Каталожный ориентир · Тестовый каталог', { exact: false }),
  ).toBeVisible();
  await expect(page.getByRole('link', { name: 'Открыть источник', exact: true })).toHaveAttribute(
    'href',
    'https://example.invalid/reference',
  );
  await expect(page.getByText('Все продукты покрыты', { exact: true })).toHaveCount(0);
});

test('весовой ориентир в смете показывает цену за килограмм вместо упаковок', async ({ page }) => {
  const state = await fixture(page);
  state.reference = true;
  state.weighted = true;
  state.complete = true;
  await page.goto('/shopping?view=calendar');
  await choose(page);
  await expect(page.getByText(/1\s?250,55 г · на вес/)).toBeVisible();
  await expect(page.getByText(/100,25.*\/ кг/)).toBeVisible();
  await expect(page.getByText('3 уп.', { exact: false })).toHaveCount(0);
});
