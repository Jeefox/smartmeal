import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
test.use({ timezoneId: 'Europe/Moscow' });
async function fixture(page: Page) {
  const state = {
    listStatus: 200,
    references: [] as any[],
    refreshStatus: [] as any[],
    coverage: [] as any[],
    latestStatus: 200,
    writeStatus: 201,
    offlineWrite: false,
    delay: 0,
    latestCalls: [] as string[],
    listCalls: [] as string[],
    writes: [] as { path: string; body: any; csrf: string | undefined }[],
    created: false,
    latest: {
      id: 1,
      storeProductId: 90,
      price: 110,
      observedAt: '2020-01-01T10:00:00Z',
      promotional: true,
      priceSource: 'MANUAL',
      createdAt: '2020-01-01T10:00:00Z',
    },
  };
  const sku = (id = 90, storeId = 71) => ({
    id,
    storeId,
    productId: 5,
    name: id === 92 ? 'Новая упаковка риса' : `Рис API ${id}`,
    packageWeightG: 500,
    active: true,
    createdAt: '2020-01-01T10:00:00Z',
    updatedAt: '2020-01-01T10:00:00Z',
  });
  await page.addInitScript(() => localStorage.setItem('smartmeal.v2.mode', '"api"'));
  await page.route('**/api/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname;
    if (path === '/api/me/profile')
      return route.fulfill({ json: { userId: 'catalogue-user', dailyCalorieTarget: 2000 } });
    if (path === '/api/auth/csrf')
      return route.fulfill({
        json: { token: 'csrf-fresh', headerName: 'X-CSRF', parameterName: '_csrf' },
      });
    if (path === '/api/price-references/status')
      return route.fulfill({ json: state.refreshStatus });
    if (path === '/api/price-references/coverage') return route.fulfill({ json: state.coverage });
    if (path === '/api/price-references') return route.fulfill({ json: state.references });
    if (path === '/api/retail-chains')
      return route.fulfill({ json: [{ id: 42, code: 'API', name: 'Сеть API' }] });
    if (path === '/api/stores')
      return route.fulfill({
        json: [
          { id: 71, chainId: 42, chainName: 'Сеть API', city: 'Тула', address: 'Первый адрес' },
          { id: 72, chainId: 42, chainName: 'Сеть API', city: 'Тула', address: 'Второй адрес' },
        ],
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
    if (req.method() === 'POST') {
      const body = req.postDataJSON();
      state.writes.push({ path, body, csrf: req.headers()['x-csrf'] });
      if (state.delay) await new Promise((resolve) => setTimeout(resolve, state.delay));
      if (state.offlineWrite) return route.abort('failed');
      if (state.writeStatus !== 201)
        return route.fulfill({
          status: state.writeStatus,
          json: {
            detail: 'Запись отклонена сервером',
            errors: [
              { field: path.endsWith('/prices') ? 'price' : 'name', message: 'Ошибка поля API' },
            ],
          },
        });
      if (path === '/api/store-products') {
        state.created = true;
        return route.fulfill({ status: 201, json: sku(92, body.storeId) });
      }
      return route.fulfill({
        status: 201,
        json: {
          id: 2,
          storeProductId: Number(path.split('/')[3]),
          ...body,
          priceSource: 'MANUAL',
          createdAt: new Date().toISOString(),
        },
      });
    }
    if (path === '/api/store-products') {
      state.listCalls.push(url.toString());
      if (state.listStatus !== 200) return route.fulfill({ status: state.listStatus, body: '' });
      const name = url.searchParams.get('name') ?? '',
        storeId = Number(url.searchParams.get('storeId')),
        pageNumber = Number(url.searchParams.get('page'));
      const response = {
        content:
          name === 'нет'
            ? []
            : state.created
              ? [sku(92, storeId)]
              : pageNumber > 0
                ? [sku(91, storeId)]
                : [sku(90, storeId), sku(93, storeId)],
        page: pageNumber,
        size: 20,
        totalElements: name === 'нет' ? 0 : 21,
        totalPages: name === 'нет' ? 0 : 2,
      };
      if (name === 'медленно') await new Promise((resolve) => setTimeout(resolve, 1000));
      return route.fulfill({ json: response }).catch(() => {});
    }
    if (path.endsWith('/prices/latest')) {
      state.latestCalls.push(path);
      if (state.latestStatus !== 200)
        return route.fulfill({ status: state.latestStatus, body: '' });
      return route.fulfill({
        json: { ...state.latest, storeProductId: Number(path.split('/')[3]) },
      });
    }
    if (path === '/api/product-categories')
      return route.fulfill({ json: [{ id: 3, name: 'Крупы', code: 'CEREALS' }] });
    if (path === '/api/products')
      return route.fulfill({
        json: {
          content: [
            {
              id: 5,
              name: 'Рис сухой',
              categoryId: 3,
              shoppingName: 'Рис',
              edibleYield: 1,
              caloriesPer100g: 340,
              proteinPer100g: 7,
              fatPer100g: 1,
              carbsPer100g: 75,
            },
          ],
          page: 0,
          size: 12,
          totalElements: 1,
          totalPages: 1,
        },
      });
    return route.fulfill({ status: 404 });
  });
  return state;
}
async function open(page: Page) {
  await page.goto('/shopping?view=catalogue');
  await page.getByLabel('Город', { exact: true }).fill('Тула');
  await page.getByRole('button', { name: 'Найти магазины', exact: true }).click();
  await page.getByRole('button', { name: /Сеть API.*Первый адрес/ }).click();
  await expect(page.getByRole('region', { name: 'Каталог упаковок магазина' })).toBeVisible();
}
async function select(page: Page) {
  await page
    .getByRole('group', { name: 'Упаковки магазина', exact: true })
    .getByRole('button')
    .first()
    .click();
  await expect(page.getByRole('region', { name: 'Цена выбранной упаковки' })).toBeVisible();
}
test('цены только выбранной карточки, акция/дата, подтверждённая историческая запись не подменяет latest', async ({
  page,
}) => {
  const state = await fixture(page);
  await open(page);
  expect(state.latestCalls).toHaveLength(0);
  await select(page);
  await expect(page.locator('.sku-known-price')).toContainText('110,00');
  await expect(page.locator('.sku-known-price')).toContainText('2020');
  await expect(page.getByText('Последняя запись акционная.', { exact: false })).toBeVisible();
  await page.getByLabel('Цена упаковки, ₽', { exact: true }).fill('99,50');
  await page.getByLabel('Когда наблюдали цену', { exact: true }).fill('2019-01-01T12:30');
  await page.getByLabel('Акционная цена', { exact: true }).check();
  state.delay = 300;
  await page.getByRole('button', { name: 'Сохранить наблюдение', exact: true }).dblclick();
  await expect(page.getByText('Наблюдение добавлено:', { exact: false })).toBeVisible();
  expect(state.writes).toHaveLength(1);
  expect(state.writes[0].csrf).toBe('csrf-fresh');
  expect(state.writes[0].body).toEqual({
    price: 99.5,
    observedAt: await page.evaluate(() => new Date('2019-01-01T12:30').toISOString()),
    promotional: true,
  });
  expect(state.writes[0].body.observedAt).toBe('2019-01-01T09:30:00.000Z');
  await expect(page.locator('.sku-known-price')).toContainText('110,00');
  expect(state.latestCalls.length).toBeGreaterThan(1);
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain('99.5');
});
test('404 latest — нет цены; валидация и 400/403 не становятся успехом, сетевой POST не повторяется', async ({
  page,
}) => {
  const state = await fixture(page);
  state.latestStatus = 404;
  await open(page);
  await select(page);
  await expect(page.getByText('Цена ещё не добавлена.', { exact: false })).toBeVisible();
  await page.getByLabel('Цена упаковки, ₽', { exact: true }).fill('1.234');
  await page.getByRole('button', { name: 'Сохранить наблюдение', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('до 10 цифр');
  expect(state.writes).toHaveLength(0);
  await page.getByLabel('Цена упаковки, ₽', { exact: true }).fill('100');
  await page.getByLabel('Когда наблюдали цену', { exact: true }).fill('2099-01-01T00:00');
  await page.getByRole('button', { name: 'Сохранить наблюдение', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('в будущем');
  expect(state.writes).toHaveLength(0);
  await page.getByLabel('Когда наблюдали цену', { exact: true }).fill('2020-01-01T00:00');
  for (const status of [400, 403]) {
    state.writeStatus = status;
    await page.getByRole('button', { name: 'Сохранить наблюдение', exact: true }).click();
    await expect(page.locator('.sku-price-form .estimate-error')).toContainText('Запись отклонена');
  }
  expect(state.writes).toHaveLength(2);
  await expect(page.getByText('Наблюдение добавлено:', { exact: false })).toHaveCount(0);
  state.writeStatus = 201;
  state.offlineWrite = true;
  await page.getByRole('button', { name: 'Сохранить наблюдение', exact: true }).click();
  await expect(page.locator('.sku-price-form .estimate-error')).toContainText('могло сохраниться');
  expect(state.writes).toHaveLength(3);
});
test('поиск, страницы и смена магазина очищают карточку, пустота и отмена старого ответа', async ({
  page,
}) => {
  const state = await fixture(page);
  await open(page);
  await select(page);
  await page.getByRole('button', { name: 'Следующие упаковки', exact: true }).click();
  await expect(page.locator('.sku-options')).toContainText('Рис API 91');
  await expect(page.locator('.sku-price-card')).toHaveCount(0);
  await page.getByLabel('Поиск упаковок', { exact: true }).fill('медленно');
  await page.getByRole('button', { name: 'Найти упаковки', exact: true }).click();
  await page.getByLabel('Поиск упаковок', { exact: true }).fill('нет');
  await page.getByRole('button', { name: 'Найти упаковки', exact: true }).click();
  await expect(page.getByText('Упаковки не найдены.', { exact: false })).toBeVisible();
  await page.waitForTimeout(1100);
  await expect(page.locator('.sku-options button')).toHaveCount(0);
  await page.getByRole('button', { name: /Сеть API.*Второй адрес/ }).click();
  await expect(page.locator('.store-catalogue')).toContainText('Второй адрес');
  await expect(page.getByLabel('Поиск упаковок', { exact: true })).toHaveValue('');
  expect(state.listCalls.some((x) => x.includes('storeId=72'))).toBe(true);
});
test('создание упаковки из базового продукта, затем цена; без двойной записи', async ({ page }) => {
  const state = await fixture(page);
  await open(page);
  await page.getByRole('button', { name: 'Добавить упаковку', exact: true }).click();
  await page.getByRole('button', { name: 'Создать упаковку', exact: true }).click();
  await expect(
    page.getByText('Выберите базовый продукт из каталога.', { exact: true }),
  ).toBeVisible();
  expect(state.writes).toHaveLength(0);
  await page.getByLabel('Поиск базового продукта', { exact: true }).fill('рис');
  await page.getByRole('button', { name: 'Найти продукт', exact: true }).click();
  await page
    .getByRole('group', { name: 'Выбор базового продукта' })
    .getByRole('button', { name: /Рис сухой/ })
    .click();
  await page.getByLabel('Название упаковки', { exact: true }).fill('Новая упаковка риса');
  await page.getByLabel('Вес упаковки, г', { exact: true }).fill('500');
  state.delay = 200;
  await page.getByRole('button', { name: 'Создать упаковку', exact: true }).dblclick();
  await expect(page.getByText('Упаковка создана сервером.', { exact: false })).toBeVisible();
  expect(state.writes).toHaveLength(1);
  expect(state.writes[0].body).toEqual({
    storeId: 71,
    productId: 5,
    name: 'Новая упаковка риса',
    packageWeightG: 500,
  });
  await expect(page.locator('.sku-price-card')).toContainText('Новая упаковка риса');
});
test('ошибки каталога и записи не создают дубликаты; offline запрещает запись, 401 уводит на вход', async ({
  page,
}) => {
  const state = await fixture(page);
  state.listStatus = 409;
  await open(page);
  await expect(page.locator('.store-catalogue .error-state')).toBeVisible();
  expect(state.latestCalls).toHaveLength(0);
  state.listStatus = 200;
  await page.getByRole('button', { name: 'Обновить каталог', exact: true }).click();
  await expect(page.locator('.sku-options button')).toHaveCount(2);
  await select(page);
  await page.context().setOffline(true);
  await expect(
    page.getByRole('button', { name: 'Сохранить наблюдение', exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByText('Нет сети. Цена может быть устаревшей', { exact: false }),
  ).toBeVisible();
  state.latestStatus = 401;
  await page.context().setOffline(false);
  await expect(page).toHaveURL(/login/);
  await expect(page.locator('.sku-price-card')).toHaveCount(0);
});
test('мобильные каталог, создание и цена без переполнения, часовой пояс корректен', async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await fixture(page);
  await open(page);
  await select(page);
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
  await page.locator('.sku-price-card').screenshot({ path: 'test-results/price-mobile.png' });
  await page.getByRole('button', { name: 'Добавить упаковку', exact: true }).click();
  for (const width of [360, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
      .toBe(true);
  }
});

test('ошибка создания упаковки сохраняет форму, не добавляет локальный SKU и не повторяет POST', async ({
  page,
}) => {
  const state = await fixture(page);
  await open(page);
  await page.getByRole('button', { name: 'Добавить упаковку', exact: true }).click();
  await page
    .getByRole('group', { name: 'Выбор базового продукта' })
    .getByRole('button')
    .first()
    .click();
  await page.getByLabel('Название упаковки', { exact: true }).fill('Новая упаковка риса');
  await page.getByLabel('Вес упаковки, г', { exact: true }).fill('500');
  for (const status of [400, 403, 409]) {
    state.writeStatus = status;
    await page.getByRole('button', { name: 'Создать упаковку', exact: true }).click();
    await expect(page.locator('.sku-create-form .estimate-error')).toContainText(
      'Запись отклонена',
    );
  }
  expect(state.writes).toHaveLength(3);
  expect(state.created).toBe(false);
  await expect(page.locator('.sku-price-card')).toHaveCount(0);
  state.writeStatus = 201;
  state.offlineWrite = true;
  await page.getByRole('button', { name: 'Создать упаковку', exact: true }).click();
  await expect(page.locator('.sku-create-form .estimate-error')).toContainText('могла сохраниться');
  expect(state.writes).toHaveLength(4);
  await expect(page.getByLabel('Название упаковки', { exact: true })).toHaveValue(
    'Новая упаковка риса',
  );
});

test('базовые продукты показывают покупное название и выход без расчёта на клиенте', async ({
  page,
}) => {
  await fixture(page);
  await page.goto('/products');
  await expect(page.locator('.product-grid')).toContainText('Для покупок: Рис');
  await expect(page.locator('.product-grid')).toContainText('Доля съедобной части: 1');
});

test('показывает четыре справочные цены с упаковками и источниками отдельно от товаров магазина', async ({
  page,
}) => {
  const state = await fixture(page);
  state.references = ['Макароны', 'Гречка', 'Картофель', 'Морковь'].map((name, i) => ({
    id: i + 1,
    productId: i + 1,
    productName: name,
    name: name + ' из каталога',
    packageWeightG: 500,
    price: 40 + i,
    sourceName: 'Тестовый каталог',
    sourceScope: 'Регион не подтверждён',
    sourceUrl: 'https://example.invalid/' + i,
    retrievedAt: '2026-10-20T00:00:00Z',
    expiresAt: '2026-10-23T00:00:00Z',
  }));
  await open(page);
  const panel = page.getByRole('region', { name: 'Каталожные ориентиры', exact: true });
  await expect(panel.getByRole('link', { name: 'Источник цены', exact: true })).toHaveCount(4);
  await expect(panel).toContainText('цена и наличие в вашей точке Тулы не подтверждены');
  await expect(panel).toContainText('40,00');
  expect(state.writes).toHaveLength(0);
});

test('автоматическое покрытие каталога и весовая цена показаны без вымышленных упаковок', async ({
  page,
}) => {
  const state = await fixture(page);
  state.refreshStatus = [
    { id: 1, enabled: true, lastSuccessAt: '2026-10-20T10:00:00Z', lastError: null },
  ];
  state.coverage = [
    { productId: 24, productName: 'Морковь', priced: true, lastError: null },
    { productId: 14, productName: 'Тунец', priced: false, lastError: 'NO_SAFE_PRODUCT_MATCH' },
  ];
  state.references = [
    {
      id: 200,
      productId: 24,
      productName: 'Морковь',
      name: 'Морковь мытая',
      packageWeightG: 1000,
      soldByWeight: true,
      price: 95,
      sourceName: 'ВкусВилл',
      sourceUrl: 'https://vkusvill.ru/goods/morkov-mytaya-18735/',
      sourceScope: 'Регион не подтверждён',
      retrievedAt: '2026-10-20T10:00:00Z',
      expiresAt: '2026-10-23T10:00:00Z',
    },
  ];
  await open(page);
  const panel = page.getByRole('region', { name: 'Каталожные ориентиры' });
  await expect(panel).toContainText('1 из 2 продуктов');
  await expect(panel).toContainText('автоматически раз в сутки');
  await expect(panel).toContainText('95,00');
  await expect(panel).toContainText('/ кг');
  await panel.getByText('Для каких продуктов ещё нет цен').click();
  await expect(panel).toContainText('Тунец — пока не найден подходящий товар');
});

test('большой каталог сворачивается, отключённые источники не показывают ложный сбой', async ({
  page,
}) => {
  const state = await fixture(page);
  state.refreshStatus = [
    { id: 1, enabled: true, lastSuccessAt: '2026-10-20T10:00:00Z', lastError: null },
    { id: 2, enabled: false, lastSuccessAt: null, lastError: 'UNSAFE_PRODUCT_MATCH' },
  ];
  state.references = Array.from({ length: 10 }, (_, index) => ({
    id: index + 100,
    productId: index + 100,
    productName: `Продукт ${index}`,
    name: `Товар ${index}`,
    packageWeightG: 500,
    price: 50,
    sourceName: 'Тестовый каталог',
    sourceUrl: 'https://example.invalid/',
    sourceScope: 'Регион не подтверждён',
    retrievedAt: '2026-10-20T10:00:00Z',
    expiresAt: '2026-10-23T10:00:00Z',
  }));
  await open(page);
  const panel = page.getByRole('region', { name: 'Каталожные ориентиры' });
  await expect(panel.locator('.catalogue-reference-list > li')).toHaveCount(6);
  await expect(panel).toContainText('Последние проверки выполнены');
  await expect(panel).not.toContainText('Часть источников сейчас недоступна');
  await panel.getByRole('button', { name: 'Показать все 10 продуктов с ценами' }).click();
  await expect(panel.locator('.catalogue-reference-list > li')).toHaveCount(10);
});
