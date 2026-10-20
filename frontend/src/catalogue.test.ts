import { afterEach, expect, it, vi } from 'vitest';
import { QueryClient } from '@tanstack/react-query';
import {
  catalogueApi,
  catalogueKey,
  latestPriceKey,
  localDateTime,
  observationInstant,
  priceErrors,
  packageErrors,
  invalidateRetail,
} from './catalogue';
afterEach(() => vi.unstubAllGlobals());
it('каталог передаёт буквальный поиск, фильтр, страницу, cookie и отмену', async () => {
  const mock = vi
    .fn()
    .mockResolvedValue(
      Response.json({ content: [], page: 2, size: 20, totalElements: 0, totalPages: 0 }),
    );
  vi.stubGlobal('fetch', mock);
  const signal = new AbortController().signal;
  await catalogueApi.list(71, ' Рис%_ ', 2, signal, 5);
  const url = new URL(mock.mock.calls[0][0], 'http://localhost');
  expect(url.pathname).toBe('/api/store-products');
  expect(Object.fromEntries(url.searchParams)).toEqual({
    storeId: '71',
    name: 'Рис%_',
    page: '2',
    size: '20',
    productId: '5',
  });
  expect(mock.mock.calls[0][1]).toMatchObject({
    credentials: 'same-origin',
    cache: 'no-store',
    signal,
  });
});
it('только latest404 превращается в отсутствие цены', async () => {
  const mock = vi
    .fn()
    .mockImplementation(() => Promise.resolve(new Response(null, { status: 404 })));
  vi.stubGlobal('fetch', mock);
  expect(await catalogueApi.latest(90)).toBeNull();
  await expect(catalogueApi.list(71, '', 0)).rejects.toMatchObject({ status: 404 });
});
it.each([400, 401, 403, 409, 500])('latest%s не маскируется отсутствием цены', async (status) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status })));
  await expect(catalogueApi.latest(90)).rejects.toMatchObject({ status });
});
it('записи используют CSRF и точные тела, без source и повторов', async () => {
  const created = { id: 90, storeId: 71, productId: 5, name: 'Рис', packageWeightG: 500 };
  const observation = {
    id: 10,
    storeProductId: 90,
    price: 99.5,
    observedAt: '2020-01-01T10:00:00.000Z',
    promotional: false,
    priceSource: 'MANUAL',
  };
  const mock = vi
    .fn()
    .mockResolvedValueOnce(
      Response.json({ token: 'one', headerName: 'X-CSRF', parameterName: '_csrf' }),
    )
    .mockResolvedValueOnce(Response.json(created, { status: 201 }))
    .mockResolvedValueOnce(
      Response.json({ token: 'two', headerName: 'X-CSRF', parameterName: '_csrf' }),
    )
    .mockResolvedValueOnce(Response.json(observation, { status: 201 }));
  vi.stubGlobal('fetch', mock);
  expect(
    await catalogueApi.create({ storeId: 71, productId: 5, name: 'Рис', packageWeightG: 500 }),
  ).toEqual(created);
  expect(
    await catalogueApi.price(90, {
      price: 99.5,
      observedAt: observation.observedAt,
      promotional: false,
    }),
  ).toEqual(observation);
  expect(JSON.parse(mock.mock.calls[3][1].body)).toEqual({
    price: 99.5,
    observedAt: observation.observedAt,
    promotional: false,
  });
  expect(mock.mock.calls[3][1].headers.get('X-CSRF')).toBe('two');
  expect(mock).toHaveBeenCalledTimes(4);
});
it('403 записи не повторяет POST и не создаёт локального результата', async () => {
  const mock = vi
    .fn()
    .mockResolvedValueOnce(
      Response.json({ token: 'one', headerName: 'X-CSRF', parameterName: '_csrf' }),
    )
    .mockResolvedValueOnce(new Response(null, { status: 403 }));
  vi.stubGlobal('fetch', mock);
  await expect(
    catalogueApi.price(90, { price: 99, observedAt: '2020-01-01T10:00:00Z', promotional: true }),
  ).rejects.toMatchObject({ status: 403 });
  expect(mock).toHaveBeenCalledTimes(2);
});
it('местные дата и время преобразуются в UTC с учётом окружения', () => {
  const local = '2020-06-15T12:34';
  expect(observationInstant(local)).toBe(new Date(2020, 5, 15, 12, 34).toISOString());
  expect(localDateTime(new Date(2020, 5, 15, 12, 34))).toBe(local);
  for (const value of ['', 'bad', '2026-02-30T12:00', '2026-13-01T12:00'])
    expect(observationInstant(value)).toBeNull();
});
it.each(['0', '-1', '1.234', '10000000000.00', '', 'NaN', '1e2'])('цена %s отклоняется', (price) =>
  expect(priceErrors(price, '2020-01-01T10:00')).toHaveProperty('price'),
);
it.each(['0.01', '99,50', '9999999999.99'])('цена %s разрешена', (price) =>
  expect(priceErrors(price, '2020-01-01T10:00')).toEqual({}),
);
it('будущее, пустая упаковка, отсутствие продукта, дробный вес отклоняются', () => {
  expect(priceErrors('1', '2030-01-01T00:00', 0)).toHaveProperty('observedAt');
  expect(packageErrors(null, '', '1.5')).toHaveProperty('productId');
  expect(packageErrors(1, 'a'.repeat(301), '0')).toHaveProperty('name');
  expect(packageErrors(1, 'Рис', '1.5')).toHaveProperty('packageWeightG');
  expect(packageErrors(1, 'Рис', '2147483648')).toHaveProperty('packageWeightG');
  expect(packageErrors(1, 'Рис', '500')).toEqual({});
});
it('инвалидация цены сбрасывает каталог/карточку/оценки/сравнения только аккаунта', async () => {
  const client = new QueryClient();
  const keys = [
    catalogueKey('a', 71, 'рис', 0),
    latestPriceKey('a', 90),
    ['shopping-estimate', 'a', 'date'],
    ['shopping-comparison', 'a', 'date'],
    latestPriceKey('b', 90),
    catalogueKey('a', 72, 'рис', 0),
  ];
  keys.forEach((key) => client.setQueryData(key, {}));
  await invalidateRetail(client, 'a', 71, 90);
  expect(keys.map((key) => client.getQueryState(key)?.isInvalidated)).toEqual([
    true,
    true,
    true,
    true,
    false,
    false,
  ]);
  client.clear();
});
