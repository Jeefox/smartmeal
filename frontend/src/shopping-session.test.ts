import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import {
  emptyLocal,
  writeLocal,
  readLocal,
  clearShoppingLocal,
  validateLines,
  decimal,
} from './shopping-session';
import type { PurchaseLine } from './shopping-session';
const id = '11111111-1111-4111-8111-111111111111';
const line: PurchaseLine = {
  itemId: id,
  quantityG: '900',
  actualTotalRub: null,
  storeProductId: null,
  receivedOn: '2026-10-25',
  expiresOn: null,
};
beforeEach(() => {
  const map = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    get length() {
      return map.size;
    },
    key: (n: number) => [...map.keys()][n] ?? null,
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => map.set(k, v),
    removeItem: (k: string) => map.delete(k),
    clear: () => map.clear(),
  });
});
afterEach(() => vi.unstubAllGlobals());
it('validates real mass and optional total, keeping zero distinct from missing', () => {
  expect(validateLines([line], '2026-10-25')).toBe('');
  expect(validateLines([{ ...line, actualTotalRub: '0' }], '2026-10-25')).toBe('');
  expect(validateLines([{ ...line, quantityG: '0' }], '2026-10-25')).not.toBe('');
  expect(validateLines([{ ...line, actualTotalRub: '1.111' }], '2026-10-25')).not.toBe('');
  expect(decimal('1e3', true, 4)).toBe(false);
});
it('rejects duplicate items, future purchases and backwards expiry', () => {
  expect(validateLines([line, line], '2026-10-25')).not.toBe('');
  expect(validateLines([line], '2026-10-24')).not.toBe('');
  expect(validateLines([{ ...line, expiresOn: '2026-10-24' }], '2026-10-25')).not.toBe('');
});
it('isolates forms and pending keys by owner and list', () => {
  const x = emptyLocal();
  x.form[id] = { quantity: '900', price: '', received: '2026-10-25', expires: '', sku: '' };
  x.pending = {
    kind: 'purchase',
    key: id,
    body: { expectedRevision: 3, expectedPantryRevision: 2, items: [line] },
  };
  expect(writeLocal('a', 'one', x)).toBe(true);
  expect(readLocal('a', 'one')).toEqual(x);
  expect(readLocal('b', 'one')).toEqual(emptyLocal());
  expect(readLocal('a', 'two')).toEqual(emptyLocal());
});
it('drops malformed stored commands instead of sending them', () => {
  localStorage.setItem(
    'smartmeal.shopping.sessions.v1.a.one',
    JSON.stringify({
      version: 1,
      form: { bad: {} },
      pending: {
        kind: 'purchase',
        key: id,
        body: { expectedRevision: 0, expectedPantryRevision: 0, items: [] },
      },
    }),
  );
  expect(readLocal('a', 'one')).toEqual(emptyLocal());
  localStorage.setItem('smartmeal.shopping.sessions.v1.a.one', '{');
  expect(readLocal('a', 'one')).toEqual(emptyLocal());
});
it('logout clears only this account drafts', () => {
  writeLocal('a', 'one', emptyLocal());
  writeLocal('b', 'one', emptyLocal());
  clearShoppingLocal('a');
  expect(localStorage.getItem('smartmeal.shopping.sessions.v1.a.one')).toBeNull();
  expect(localStorage.getItem('smartmeal.shopping.sessions.v1.b.one')).not.toBeNull();
});
