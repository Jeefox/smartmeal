import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
test.use({ timezoneId: 'Europe/Moscow' });
async function fixture(page: Page) {
  const state = {
    writes: [] as { body: any; csrf?: string }[],
    offline: false,
    status: 201,
    listStatus: 200,
    delay: 0,
    saved: false,
    failRefresh: false,
  };
  const items = [
    {
      productId: 1,
      productName: 'Рис сухой',
      shoppingName: 'Рис',
      recipeCount: 5,
      offers: [
        {
          storeProductId: 90,
          name: 'Рис 900 г',
          packageWeightG: 900,
          price: null,
          observedAt: null,
        },
      ],
    },
    { productId: 2, productName: 'Хлопья', shoppingName: 'Хлопья', recipeCount: 3, offers: [] },
  ];
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname;
    if (path === '/api/me/profile')
      return route.fulfill({ json: { userId: 'quick-price-user', dailyCalorieTarget: 2000 } });
    if (path === '/api/auth/csrf')
      return route.fulfill({
        json: { token: 'fresh-token', headerName: 'X-CSRF', parameterName: '_csrf' },
      });
    if (path === '/api/retail-chains')
      return route.fulfill({ json: [{ id: 1, code: 'API', name: 'Сеть API' }] });
    if (path === '/api/stores')
      return route.fulfill({
        json: [
          { id: 71, chainId: 1, chainName: 'Сеть API', city: 'Тула', address: 'Первый адрес' },
        ],
      });
    if (path === '/api/me/shopping-list')
      return route.fulfill({
        json: { from: '2026-10-20', to: '2026-10-20', containsDrafts: false, items: [] },
      });
    if (path === '/api/store-products')
      return route.fulfill({
        json: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
      });
    if (path === '/api/stores/71/price-entry' && req.method() === 'GET') {
      if (state.listStatus !== 200 || (state.saved && state.failRefresh))
        return route.fulfill({ status: 500, json: { detail: 'Ошибка обновления' } });
      const n = Number(url.searchParams.get('page'));
      const content =
        state.saved && url.searchParams.get('missingOnly') === 'true'
          ? []
          : n === 0
            ? items
            : [
                {
                  productId: 3,
                  productName: 'Макароны',
                  shoppingName: 'Макароны',
                  recipeCount: 1,
                  offers: [
                    {
                      storeProductId: 91,
                      name: 'Макароны 500 г',
                      packageWeightG: 500,
                      price: null,
                      observedAt: null,
                    },
                  ],
                },
              ];
      return route.fulfill({
        json: {
          content,
          page: n,
          size: 20,
          totalElements: state.saved ? 0 : 21,
          totalPages: state.saved ? 0 : 2,
        },
      });
    }
    if (path === '/api/stores/71/price-entry' && req.method() === 'POST') {
      const body = req.postDataJSON();
      state.writes.push({ body, csrf: req.headers()['x-csrf'] });
      if (state.delay) await new Promise((resolve) => setTimeout(resolve, state.delay));
      if (state.offline) return route.abort('failed');
      if (state.status !== 201)
        return route.fulfill({ status: state.status, json: { detail: 'Упаковка недоступна' } });
      state.saved = true;
      return route.fulfill({
        status: 201,
        json: {
          requestId: body.requestId,
          storeId: 71,
          savedCount: body.entries.length,
          entries: body.entries.map((e: any, i: number) => ({
            productId: e.productId,
            storeProductId: e.storeProductId ?? 100 + i,
            priceObservationId: 200 + i,
            createdStoreProduct: !e.storeProductId,
          })),
        },
      });
    }
    return route.fulfill({ status: 404 });
  });
  return state;
}
async function open(page: Page) {
  await page.goto('/shopping?view=catalogue');
  const city = page.getByLabel('Город', { exact: true });
  await expect(city.or(page.locator('.location-summary')).first()).toBeVisible();
  if (await city.count()) {
    await city.fill('Тула');
    await page.getByRole('button', { name: 'Найти магазины', exact: true }).click();
  } else {
    await expect(page.locator('.location-summary')).toContainText('Тула');
  }
  await page.getByRole('button', { name: /Сеть API.*Первый адрес/ }).click();
  await page.getByRole('button', { name: 'Быстро заполнить цены', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Цены для ваших рецептов' })).toBeVisible();
}
test('сохраняет заполненные строки одной операцией с CSRF, без предполагаемых цен и дублей клика', async ({
  page,
}) => {
  const state = await fixture(page);
  await open(page);
  await expect(
    page.getByRole('article', { name: 'Рис сухой' }).getByLabel('Цена упаковки, ₽'),
  ).toHaveValue('');
  await page
    .getByRole('article', { name: 'Рис сухой' })
    .getByLabel('Цена упаковки, ₽')
    .fill('79,90');
  await page.getByRole('article', { name: 'Хлопья' }).getByLabel('Вес упаковки, г').fill('450');
  await page.getByRole('article', { name: 'Хлопья' }).getByLabel('Цена упаковки, ₽').fill('59');
  await page
    .getByRole('article', { name: 'Хлопья' })
    .getByLabel('Акция / специальная цена')
    .check();
  await page.getByLabel('Когда наблюдали цены').fill('2020-01-01T12:00');
  state.delay = 400;
  await page.getByRole('button', { name: 'Сохранить заполненные цены', exact: true }).dblclick();
  await expect(page.getByText('Сохранено цен: 2.', { exact: true })).toBeVisible();
  expect(state.writes).toHaveLength(1);
  expect(state.writes[0].csrf).toBe('fresh-token');
  expect(state.writes[0].body.entries).toEqual([
    { productId: 1, storeProductId: 90, price: 79.9, promotional: false },
    { productId: 2, name: 'Хлопья', packageWeightG: 450, price: 59, promotional: true },
  ]);
  expect(state.writes[0].body.observedAt).toBe('2020-01-01T09:00:00.000Z');
});
test('вставка таблицы заполняет форму, неизвестные строки требуют исправления', async ({
  page,
}) => {
  const state = await fixture(page);
  await open(page);
  await page.getByText('Вставить таблицу из Excel или текст', { exact: true }).click();
  await page.getByLabel('Таблица цен', { exact: true }).fill('Неизвестный;500;10');
  await page.getByRole('button', { name: 'Заполнить из таблицы', exact: true }).click();
  await expect(page.getByText('не найден однозначно', { exact: false })).toBeVisible();
  expect(state.writes).toHaveLength(0);
  await page.getByLabel('Таблица цен', { exact: true }).fill('Рис;900;79,90\nХлопья;450;59');
  await page.getByRole('button', { name: 'Заполнить из таблицы', exact: true }).click();
  await expect(
    page.getByRole('article', { name: 'Хлопья' }).getByLabel('Цена упаковки, ₽'),
  ).toHaveValue('59');
  expect(state.writes).toHaveLength(0);
  await page.getByRole('button', { name: 'Сохранить заполненные цены', exact: true }).click();
  await expect(page.getByText('Сохранено цен: 2.', { exact: true })).toBeVisible();
});
test('потеря ответа сохраняет исходный запрос для безопасного повтора после перезагрузки', async ({
  page,
}) => {
  const state = await fixture(page);
  state.offline = true;
  await open(page);
  await page.getByRole('article', { name: 'Рис сухой' }).getByLabel('Цена упаковки, ₽').fill('79');
  await page.getByRole('button', { name: 'Сохранить заполненные цены', exact: true }).click();
  await expect(
    page.getByRole('button', { name: 'Повторить тот же запрос', exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole('article', { name: 'Рис сухой' }).getByLabel('Цена упаковки, ₽'),
  ).toBeDisabled();
  expect(state.writes).toHaveLength(1);
  await open(page);
  state.offline = false;
  state.status = 403;
  await page.getByRole('button', { name: 'Повторить тот же запрос', exact: true }).click();
  await expect(page.getByText('Сохранение не подтверждено.', { exact: false })).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Повторить тот же запрос', exact: true }),
  ).toBeEnabled();
  state.status = 201;
  await page.getByRole('button', { name: 'Повторить тот же запрос', exact: true }).click();
  await expect(page.getByText('Сохранено цен: 1.', { exact: true })).toBeVisible();
  expect(state.writes).toHaveLength(3);
  expect(state.writes[1].body).toEqual(state.writes[0].body);
  expect(state.writes[2].body).toEqual(state.writes[0].body);
});
test('черновик сохраняет строки при переключении страниц, серверный конфликт не стирает ввод', async ({
  page,
}) => {
  const state = await fixture(page);
  await open(page);
  await page.getByRole('article', { name: 'Рис сухой' }).getByLabel('Цена упаковки, ₽').fill('79');
  await page.getByRole('button', { name: 'Следующие продукты', exact: true }).click();
  await page.getByRole('article', { name: 'Макароны' }).getByLabel('Цена упаковки, ₽').fill('59');
  state.status = 409;
  await page.getByRole('button', { name: 'Сохранить заполненные цены', exact: true }).click();
  await expect(page.getByText('Упаковка недоступна', { exact: true })).toBeVisible();
  expect(state.writes[0].body.entries).toHaveLength(2);
  await page.getByRole('button', { name: 'Предыдущие продукты', exact: true }).click();
  await expect(
    page.getByRole('article', { name: 'Рис сухой' }).getByLabel('Цена упаковки, ₽'),
  ).toHaveValue('79');
});
test('телефон: без горизонтального переполнения, ошибка обновления после записи не отменяет успех', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await fixture(page);
  await open(page);
  await page.getByRole('article', { name: 'Рис сухой' }).getByLabel('Цена упаковки, ₽').fill('79');
  await page.screenshot({ path: '/tmp/smartmeal-quick-prices-mobile.png', fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  state.failRefresh = true;
  await page.getByRole('button', { name: 'Сохранить заполненные цены', exact: true }).click();
  await expect(page.getByText('Сохранено цен: 1.', { exact: true })).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Повторить тот же запрос', exact: true }),
  ).toHaveCount(0);
  expect(state.writes).toHaveLength(1);
});
