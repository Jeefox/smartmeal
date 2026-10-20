import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
async function fixture(page: Page) {
  const state = {
    status: 200,
    offline: false,
    empty: false,
    allPartial: false,
    tie: false,
    cost: 100,
    delay: 0,
    calls: [] as string[],
  };
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url()),
      path = url.pathname;
    if (path === '/api/me/profile')
      return route.fulfill({ json: { userId: 'comparison-user', dailyCalorieTarget: 2000 } });
    if (path === '/api/retail-chains')
      return route.fulfill({ json: [{ id: 42, code: 'API', name: 'Сеть API' }] });
    if (path === '/api/stores')
      return route.fulfill({
        json: Array.from({ length: 11 }, (_, i) => ({
          id: i + 501,
          chainId: 42,
          chainName: `Магазин ${i + 1}`,
          city: url.searchParams.get('city'),
          address: `Очень длинная улица для проверки мобильного отображения, дом ${i + 1}`,
        })),
      });
    if (path === '/api/me/shopping-list')
      return route.fulfill({
        json: {
          from: url.searchParams.get('from'),
          to: url.searchParams.get('to'),
          containsDrafts: false,
          items: [],
        },
      });
    if (path === '/api/me/shopping-comparison') {
      state.calls.push(url.toString());
      if (state.offline) return route.abort('failed');
      if (state.status !== 200) return route.fulfill({ status: state.status, body: '' });
      const items = url.searchParams
        .get('storeIds')!
        .split(',')
        .map(Number)
        .map((id) => ({
          from: url.searchParams.get('from'),
          to: url.searchParams.get('to'),
          storeId: id,
          containsDrafts: !state.empty,
          complete: state.empty || (!state.allPartial && id !== 503),
          knownPurchaseCost: state.empty
            ? 0
            : id === 503
              ? 1
              : state.tie
                ? state.cost
                : state.cost + (id - 501) * 100,
          items: state.empty
            ? []
            : [
                id === 503 || state.allPartial
                  ? {
                      productId: 1,
                      productName: 'Рис без цены',
                      requiredWeightG: 1250.5,
                      storeProductId: null,
                      storeProductName: null,
                      packageWeightG: null,
                      packageCount: null,
                      packagePrice: null,
                      purchaseCost: null,
                      remainderG: null,
                      priceObservedAt: null,
                      priceSource: null,
                    }
                  : {
                      productId: 1,
                      productName: 'Рис API',
                      requiredWeightG: 1250.5,
                      storeProductId: 90,
                      storeProductName: 'Упаковка риса',
                      packageWeightG: 500,
                      packageCount: 3,
                      packagePrice: 33.33,
                      purchaseCost: state.cost + (id - 501) * 100,
                      remainderG: 249,
                      priceObservedAt: '2020-01-01T10:00:00Z',
                      priceSource: 'MANUAL',
                    },
              ],
        }));
      items.sort(
        (a, b) =>
          Number(b.complete) - Number(a.complete) ||
          a.knownPurchaseCost - b.knownPurchaseCost ||
          a.storeId - b.storeId,
      );
      const response = {
        from: url.searchParams.get('from'),
        to: url.searchParams.get('to'),
        containsDrafts: !state.empty,
        shoppingEstimateResponseList: items,
      };
      if (state.delay) await new Promise((resolve) => setTimeout(resolve, state.delay));
      return route.fulfill({ json: response }).catch(() => {});
    }
    return route.fulfill({ status: 404 });
  });
  return state;
}
async function search(page: Page) {
  await page.goto('/shopping?from=2026-10-20&to=2026-10-21');
  await page.getByRole('button', { name: 'Сравнить магазины', exact: true }).click();
  await page.getByLabel('Город', { exact: true }).fill('Тула');
  await page.getByRole('button', { name: 'Найти магазины' }).click();
  await expect(page.locator('.comparison-panel .store-choice')).toHaveCount(11);
}
async function choose(page: Page, indices = [0, 1, 2]) {
  for (const index of indices)
    await page.locator('.comparison-panel .store-choice').nth(index).click();
  await page.getByRole('button', { name: 'Сравнить выбранные магазины', exact: true }).click();
  await expect(
    page.getByRole('region', { name: 'Результаты сравнения', exact: true }),
  ).toBeVisible();
}
test('полные отдельно от неполных, 1 рубль не победитель, подробности с упаковками и датами', async ({
  page,
}) => {
  const state = await fixture(page);
  await search(page);
  await choose(page);
  const full = page.getByRole('region', { name: 'Полные оценки', exact: true }),
    partial = page.getByRole('region', {
      name: 'Неполные оценки · только известная часть',
      exact: true,
    });
  await expect(full.locator('.comparison-card')).toHaveCount(2);
  await expect(partial.locator('.comparison-card')).toHaveCount(1);
  await expect(full.locator('.comparison-card').first()).toContainText('Магазин 1');
  await expect(full.locator('.comparison-best')).toHaveCount(1);
  await expect(partial.locator('.comparison-best')).toHaveCount(0);
  await expect(partial).toContainText('Известная часть стоимости');
  await expect(partial).toContainText('не являются');
  await expect(page.getByText('В сравнении есть черновые меню.', { exact: false })).toBeVisible();
  await full
    .getByRole('button', { name: /Подробнее/ })
    .first()
    .click();
  const details = page.getByRole('region', { name: 'Подробности выбранного магазина' });
  await expect(details).toContainText('3 уп. × 500 г');
  await expect(details).toContainText('249 г');
  await expect(details).toContainText('2020');
  await partial.getByRole('button', { name: /Подробнее/ }).click();
  await expect(details).toContainText('Стоимость не определена');
  expect(state.calls.some((x) => new URL(x).searchParams.get('storeIds') === '501,502,503')).toBe(
    true,
  );
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain('comparison-user');
});
test('1–10 магазинов, пустой выбор блокируется, фильтры не стирают выбранные адреса', async ({
  page,
}) => {
  const state = await fixture(page);
  await search(page);
  await page.getByRole('button', { name: 'Сравнить выбранные магазины', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('хотя бы один');
  expect(state.calls).toHaveLength(0);
  for (let i = 0; i < 10; i++) await page.locator('.comparison-panel .store-choice').nth(i).click();
  await page.locator('.comparison-panel .store-choice').nth(10).click();
  await expect(page.getByRole('alert')).toContainText('не более 10');
  await page.getByRole('button', { name: 'Сравнить выбранные магазины', exact: true }).click();
  await expect(page.locator('.comparison-card')).toHaveCount(10);
  await page.getByLabel('Торговая сеть').selectOption('42');
  await expect(page.getByRole('list', { name: 'Выбранные магазины' }).locator('li')).toHaveCount(
    10,
  );
  await page.getByRole('button', { name: /Убрать Магазин 1,/ }).click();
  await expect(page.locator('.comparison-results')).toHaveCount(0);
  await page.getByRole('button', { name: 'Сравнить выбранные магазины', exact: true }).click();
  await expect(page.locator('.comparison-card')).toHaveCount(9);
});
test('нет победителя для пустого меню/всех неполных, равные полные отмечаются одинаково', async ({
  page,
}) => {
  const state = await fixture(page);
  state.empty = true;
  await search(page);
  await choose(page);
  await expect(
    page.getByText('В выбранном периоде нет продуктов.', { exact: false }),
  ).toBeVisible();
  await expect(page.locator('.comparison-best')).toHaveCount(0);
  await expect(page.locator('.comparison-card').first()).toContainText('0,00');
  state.empty = false;
  state.allPartial = true;
  await page.getByRole('button', { name: 'Обновить сравнение', exact: true }).click();
  await expect(page.getByText('Полных оценок нет.', { exact: false })).toBeVisible();
  await expect(page.locator('.comparison-best')).toHaveCount(0);
  state.allPartial = false;
  state.tie = true;
  await page.getByRole('button', { name: 'Обновить сравнение', exact: true }).click();
  await expect(page.locator('.comparison-best')).toHaveCount(2);
});
test('ошибки всего сравнения, устаревшие данные, повтор и истечение сессии', async ({ page }) => {
  const state = await fixture(page);
  await search(page);
  state.status = 404;
  await page.locator('.comparison-panel .store-choice').first().click();
  await page.getByRole('button', { name: 'Сравнить выбранные магазины', exact: true }).click();
  await expect(page.locator('.comparison-panel .estimate-error')).toContainText(
    'не выполнено целиком',
  );
  await expect(page.locator('.comparison-card')).toHaveCount(0);
  for (const [status, text] of [
    [400, 'Проверьте параметры'],
    [409, 'больше не активен'],
  ] as const) {
    state.status = status;
    await page.getByRole('button', { name: 'Обновить сравнение', exact: true }).click();
    await expect(page.locator('.comparison-panel .estimate-error')).toContainText(text);
  }
  state.status = 200;
  await page.getByRole('button', { name: 'Обновить сравнение', exact: true }).click();
  await expect(page.locator('.comparison-card')).toHaveCount(1);
  state.offline = true;
  await page.getByRole('button', { name: 'Обновить сравнение', exact: true }).click();
  await expect(page.locator('.comparison-panel .estimate-error')).toContainText('устаревшим');
  await expect(page.locator('.comparison-card')).toHaveCount(1);
  state.offline = false;
  state.cost = 175;
  await page.getByRole('button', { name: 'Обновить сравнение', exact: true }).click();
  await expect(page.locator('.comparison-cost')).toContainText('175,00');
  state.status = 401;
  await page.getByRole('button', { name: 'Обновить сравнение', exact: true }).click();
  await expect(page).toHaveURL(/login/);
  await expect(page.locator('.comparison-card')).toHaveCount(0);
});
test('изменение периода и состава скрывает старый ответ; запоздавший GET не возвращает его', async ({
  page,
}) => {
  const state = await fixture(page);
  await search(page);
  await choose(page, [0]);
  state.delay = 1000;
  await page.getByRole('button', { name: 'Обновить сравнение', exact: true }).click();
  await page.locator('[name=from]').fill('2026-10-22');
  await page.locator('[name=to]').fill('2026-10-23');
  await page.getByRole('button', { name: 'Показать список', exact: true }).click();
  await expect(page.locator('.comparison-results')).toHaveCount(0);
  await page.waitForTimeout(1200);
  await expect(page.locator('.comparison-results')).toHaveCount(0);
  state.delay = 0;
  await page.getByRole('button', { name: 'Сравнить выбранные магазины', exact: true }).click();
  await expect
    .poll(() => state.calls.some((x) => x.includes('from=2026-10-22&to=2026-10-23')))
    .toBe(true);
  await expect(page.locator('.comparison-results')).toBeVisible();
  await page.locator('.comparison-panel .store-choice').nth(1).click();
  await expect(page.locator('.comparison-results')).toHaveCount(0);
  await page.getByRole('button', { name: 'Сравнить выбранные магазины', exact: true }).click();
  await expect(page.locator('.comparison-card')).toHaveCount(2);
  await page.locator('[name=to]').fill('2026-11-23');
  await page.getByRole('button', { name: 'Показать список', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('не более 31');
});
test('сравнение и подробности на 360/390/768/1440 без переполнения', async ({ page }) => {
  await fixture(page);
  await search(page);
  await choose(page);
  await page
    .locator('.comparison-card')
    .first()
    .getByRole('button', { name: /Подробнее/ })
    .click();
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
  await page.screenshot({ path: 'test-results/comparison-mobile.png', fullPage: true });
  await page
    .locator('.comparison-results')
    .screenshot({ path: 'test-results/comparison-results-mobile.png' });
});
