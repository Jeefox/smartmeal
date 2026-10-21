import { afterEach, expect, it, vi } from 'vitest';
import { buildPriceBatch, initialPriceDraft, parsePriceTable, priceEntryApi } from './price-entry';
import type { PriceEntryItem } from './price-entry';
const rice: PriceEntryItem = {
  productId: 1,
  productName: 'Рис сухой',
  shoppingName: 'Рис',
  recipeCount: 4,
  offers: [
    {
      storeProductId: 7,
      name: 'Рис 900 г',
      packageWeightG: 900,
      price: 99,
      observedAt: '2026-01-01T00:00:00Z',
    },
  ],
};
const oats: PriceEntryItem = {
  productId: 2,
  productName: 'Хлопья',
  shoppingName: 'Хлопья',
  recipeCount: 2,
  offers: [],
};
afterEach(() => vi.unstubAllGlobals());
it('выбирает единственную упаковку, но не подставляет историческую цену', () => {
  expect(initialPriceDraft(rice)).toMatchObject({ skuId: '7', price: '' });
  expect(initialPriceDraft(oats)).toMatchObject({
    skuId: 'new',
    name: 'Хлопья',
    weight: '',
    price: '',
  });
  expect(
    initialPriceDraft({
      ...rice,
      offers: [rice.offers[0], { ...rice.offers[0], storeProductId: 8 }],
    }).skuId,
  ).toBe('');
});
it('сохраняет заполненные цены и десятичную запятую', () => {
  const result = buildPriceBatch(
    [
      { ...initialPriceDraft(rice), price: '89,90' },
      { ...initialPriceDraft(oats), price: '50', weight: '450', promotional: true },
    ],
    '2020-01-01T12:00',
    'request',
  );
  expect(result.errors).toEqual({});
  expect(result.body.entries).toEqual([
    { productId: 1, storeProductId: 7, price: 89.9, promotional: false },
    { productId: 2, name: 'Хлопья', packageWeightG: 450, price: 50, promotional: true },
  ]);
  expect(
    buildPriceBatch([initialPriceDraft(oats)], '2020-01-01T12:00', 'request').errors.form,
  ).toBeTruthy();
});
it('отклоняет неверные цены, упаковку, массу и дату', () => {
  for (const price of ['0', '-1', '12.345', 'текст'])
    expect(
      buildPriceBatch([{ ...initialPriceDraft(rice), price }], '2020-01-01T12:00', 'r').errors['1'],
    ).toBeTruthy();
  expect(
    buildPriceBatch([{ ...initialPriceDraft(oats), price: '50' }], '2020-01-01T12:00', 'r').errors[
      '2'
    ],
  ).toBeTruthy();
  expect(
    buildPriceBatch(
      [{ ...initialPriceDraft(rice), skuId: '', price: '50' }],
      '2020-01-01T12:00',
      'r',
    ).errors['1'],
  ).toBeTruthy();
  expect(
    buildPriceBatch([{ ...initialPriceDraft(rice), price: '50' }], '2999-01-01T12:00', 'r').errors
      .observedAt,
  ).toBeTruthy();
});
it('таблица сопоставляет только однозначные продукты и массу', () => {
  const parsed = parsePriceTable('Рис\t900\t89,90\nХлопья;450;50', [rice, oats]);
  expect(parsed.errors).toEqual([]);
  expect(parsed.drafts.map((d) => [d.skuId, d.price])).toEqual([
    ['7', '89,90'],
    ['new', '50'],
  ]);
  expect(parsePriceTable('Неизвестный;500;10', [rice]).errors).toHaveLength(1);
  expect(parsePriceTable('Рис;900;10\nРис;900;20', [rice]).errors).toHaveLength(1);
  expect(
    parsePriceTable('Рис;900;10', [rice, { ...oats, shoppingName: 'Рис' }]).errors,
  ).toHaveLength(1);
});
it('API передаёт магазин, свежий CSRF и исходный ключ повтора', async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ content: [] }), {
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    .mockResolvedValueOnce(
      new Response(
        JSON.stringify({ token: 'fresh', headerName: 'X-CSRF', parameterName: '_csrf' }),
        { headers: { 'Content-Type': 'application/json' } },
      ),
    )
    .mockResolvedValueOnce(
      new Response(JSON.stringify({ savedCount: 1 }), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  vi.stubGlobal('fetch', fetch);
  await priceEntryApi.list(11, true, 'Рис', 0);
  expect(String(fetch.mock.calls[0][0])).toContain('/stores/11/price-entry?');
  expect(String(fetch.mock.calls[0][0])).toContain('missingOnly=true');
  const body = buildPriceBatch(
    [{ ...initialPriceDraft(rice), price: '50' }],
    '2020-01-01T12:00',
    'same-key',
  ).body;
  await priceEntryApi.save(11, body);
  expect(fetch.mock.calls[2][1].headers.get('X-CSRF')).toBe('fresh');
  expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual(body);
});
