import { describe, expect, it } from 'vitest';
import { validGrams } from './pantry';
describe('pantry quantities', () => {
  it('accepts canonical grams without floating-point arithmetic', () => {
    expect(validGrams('0.0001')).toBe(true);
    expect(validGrams('900')).toBe(true);
    expect(validGrams('9999999999.9999')).toBe(true);
  });
  it('zero is allowed only for an actual remaining balance', () => {
    expect(validGrams('0')).toBe(false);
    expect(validGrams('0', true)).toBe(true);
  });
  it.each(['-1', 'NaN', 'Infinity', '0.00001', '1e3', '1,5', '10000000000', ''])(
    'rejects %s',
    (value) => expect(validGrams(value)).toBe(false),
  );
});
