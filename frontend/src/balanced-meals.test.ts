import { expect, it } from 'vitest';
import { defaultDraft, draftOptions, fromOptions, sameOptions } from './planning-preferences';
it('new menus enable composition while old documents remain legacy unless changed', () => {
  const d = defaultDraft();
  d.chains = [1];
  expect(draftOptions(d).balancedMeals).toBe(true);
  const old = { ...draftOptions(d), balancedMeals: undefined };
  expect(fromOptions(old).balanced).toBe(false);
  expect(sameOptions(draftOptions(d), old)).toBe(false);
  expect(sameOptions(draftOptions(fromOptions(old)), old)).toBe(true);
});

it('new plans default to ten grocery products, old documents keep their original rules', () => {
  const d = defaultDraft();
  d.chains = [1];
  expect(draftOptions(d).maxShoppingProducts).toBe(10);
  const old = { ...draftOptions(d), maxShoppingProducts: undefined };
  expect(fromOptions(old).maxShoppingProducts).toBeNull();
  expect(sameOptions(draftOptions(d), old)).toBe(false);
  expect(sameOptions(draftOptions(fromOptions(old)), old)).toBe(true);
  expect(draftOptions({ ...d, maxShoppingProducts: 7 }).maxShoppingProducts).toBe(7);
});
