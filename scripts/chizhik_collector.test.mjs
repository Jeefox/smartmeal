import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseOverview, cataloguePageError } from './collect_chizhik.mjs';

const product = { plu: 3231481, name: 'Гречка ядрица 900г', uom: 'шт',
  is_available: true, prices: { regular: '39.0', discount: '29.0' }, property_clarification: '900 г' };
const overview = products => ({ name: 'Крупы и макароны', products_count: products.length,
  categories: [{ products }] });

test('deduplicates PLU and keeps regular price instead of promotion', () => {
  const result = parseOverview(overview([product, product]));
  assert.equal(result.length, 1);
  assert.equal(result[0].regularPrice, '39.0');
  assert.equal(result[0].packageLabel, '900 г');
});

test('unavailable products carry no usable price', () => {
  assert.equal(parseOverview(overview([{ ...product, is_available: false, prices: null }]))[0].regularPrice, null);
});

test('rejects conflicting duplicates, zero prices and incomplete categories', () => {
  assert.throws(() => parseOverview(overview([product, { ...product, prices: { regular: '99' } }])));
  assert.throws(() => parseOverview(overview([{ ...product, prices: { regular: '0' } }])));
  assert.throws(() => parseOverview({ ...overview([product]), products_count: 100 }));
});

test('does not mistake HTTP 200 CAPTCHA or blocking pages for the catalogue', () => {
  assert.equal(cataloguePageError(200, 'Мы хотим убедиться, что имеем дело именно с вами, а не с ботом.', 0), 'CAPTCHA_REQUIRED');
  assert.equal(cataloguePageError(200, 'Проблемы со связью. Проверьте настройки интернета и VPN', 0), 'CATALOGUE_ACCESS_BLOCKED');
  assert.equal(cataloguePageError(403, '', 0), 'CATALOGUE_HTTP_403');
  assert.equal(cataloguePageError(200, '', 0), 'CATALOGUE_NOT_LOADED');
  assert.equal(cataloguePageError(200, 'Каталог', 24), null);
});
