import { QueryClient } from '@tanstack/react-query';
import { invalidateShopping } from './shopping';
import { afterEach, expect, it, vi } from 'vitest';
import { comparisonKey, comparisonSelectionError, estimateKey, retailApi } from './retail';
afterEach(() => vi.unstubAllGlobals());
it('передаёт город, сеть, сигнал и cookie без выдуманных ID', async () => {
  const mock = vi.fn().mockImplementation(() => Promise.resolve(Response.json([])));
  vi.stubGlobal('fetch', mock);
  const signal = new AbortController().signal;
  await retailApi.chains(signal);
  await retailApi.stores(' Тула ', '', signal);
  await retailApi.stores('Тула', '42', signal);
  expect(mock.mock.calls[0][0]).toBe('/api/retail-chains');
  expect(new URL(mock.mock.calls[1][0], 'http://localhost').searchParams.get('city')).toBe('Тула');
  expect(mock.mock.calls[1][0]).not.toContain('chainId');
  expect(mock.mock.calls[2][0]).toContain('chainId=42');
  expect(mock.mock.calls[2][1]).toMatchObject({
    credentials: 'same-origin',
    cache: 'no-store',
    signal,
  });
});
it('сохраняет null, сумму и дату сервера, отправляет диапазон и точку', async () => {
  const result = {
    from: '2026-10-20',
    to: '2026-10-21',
    storeId: 987,
    containsDrafts: true,
    complete: false,
    knownPurchaseCost: 10.25,
    items: [
      {
        productId: 1,
        requiredWeightG: 1250.55,
        purchaseCost: null,
        packageCount: null,
        priceObservedAt: null,
      },
    ],
  };
  const mock = vi.fn().mockResolvedValue(Response.json(result));
  vi.stubGlobal('fetch', mock);
  expect(await retailApi.estimate(result.from, result.to, 987)).toEqual(result);
  expect(mock.mock.calls[0][0]).toBe(
    '/api/me/shopping-estimate?from=2026-10-20&to=2026-10-21&storeId=987',
  );
  expect(estimateKey('a', result.from, result.to, 987)).not.toEqual(
    estimateKey('b', result.from, result.to, 987),
  );
  expect(estimateKey('a', result.from, result.to, 987)).not.toEqual(
    estimateKey('a', result.from, result.to, 988),
  );
});
it.each([400, 404, 409, 500])('не подменяет ошибку %s нулевой стоимостью', async (status) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status })));
  await expect(retailApi.estimate('2026-10-20', '2026-10-21', 987)).rejects.toMatchObject({
    status,
  });
});

it('после изменения меню сбрасывает все периоды и точки только текущего аккаунта', async () => {
  const client = new QueryClient();
  const keys = [
    ['shopping-needs', 'a', '2026-10-20', '2026-10-21'],
    estimateKey('a', '2026-10-20', '2026-10-21', 987),
    estimateKey('a', '2026-10-22', '2026-10-23', 988),
    comparisonKey('a', '2026-10-20', '2026-10-21', [987, 988]),
    estimateKey('b', '2026-10-20', '2026-10-21', 987),
    comparisonKey('b', '2026-10-20', '2026-10-21', [987, 988]),
  ];
  keys.forEach((key) => client.setQueryData(key, {}));
  await invalidateShopping(client, 'a');
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

it('сравнение использует точное поле DTO, дедупликацию и GET с cookie/signal', async () => {
  const response = {
    from: '2026-10-20',
    to: '2026-10-21',
    containsDrafts: true,
    shoppingEstimateResponseList: [
      { storeId: 988, complete: false, knownPurchaseCost: 5, items: [{ purchaseCost: null }] },
    ],
  };
  const mock = vi.fn().mockResolvedValue(Response.json(response));
  vi.stubGlobal('fetch', mock);
  const signal = new AbortController().signal;
  expect(await retailApi.compare(response.from, response.to, [988, 987, 988], signal)).toEqual(
    response,
  );
  expect(mock.mock.calls[0][0]).toBe(
    '/api/me/shopping-comparison?from=2026-10-20&to=2026-10-21&storeIds=987%2C988',
  );
  expect(mock.mock.calls[0][1]).toMatchObject({
    signal,
    credentials: 'same-origin',
    cache: 'no-store',
  });
  expect(mock).toHaveBeenCalledTimes(1);
  expect(comparisonKey('a', response.from, response.to, [988, 987])).toEqual(
    comparisonKey('a', response.from, response.to, [987, 988, 987]),
  );
  expect(comparisonKey('a', response.from, response.to, [987])).not.toEqual(
    comparisonKey('b', response.from, response.to, [987]),
  );
  expect(comparisonKey('a', response.from, response.to, [987])).not.toEqual(
    comparisonKey('a', response.from, '2026-10-22', [987]),
  );
});
it('выбор 1–10 уникальных положительных целых ID', () => {
  expect(comparisonSelectionError([1])).toBe('');
  expect(comparisonSelectionError(Array.from({ length: 10 }, (_, i) => i + 1))).toBe('');
  expect(comparisonSelectionError([1, 1])).toBe('');
  for (const ids of [[], [0], [-1], [1.5], [NaN], Array.from({ length: 11 }, (_, i) => i + 1)])
    expect(comparisonSelectionError(ids)).not.toBe('');
});
it.each([400, 401, 404, 409, 500])(
  'сравнение %s не превращается в частичный успех',
  async (status) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status })));
    await expect(retailApi.compare('2026-10-20', '2026-10-21', [987, 988])).rejects.toMatchObject({
      status,
    });
  },
);
