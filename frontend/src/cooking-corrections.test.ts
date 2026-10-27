import { describe, it, expect } from 'vitest';
import { validCookedWeight } from './cooking-corrections';
describe('cooked weight corrections', () => {
  it('requires a positive finite weight with at most two decimal places', () => {
    for (const value of ['0.01', '400', '999999.99']) expect(validCookedWeight(value)).toBe(true);
    for (const value of ['', '0', '-1', '1e3', '1000000', '10.001', 'NaN'])
      expect(validCookedWeight(value)).toBe(false);
  });
});
