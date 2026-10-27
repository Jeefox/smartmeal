import { describe, it, expect } from 'vitest';
import type { Alternative } from './period-planning';
import { replacementChoices } from './replacement-choices';
const base = {
  recipeId: 'a',
  name: 'Блюдо',
  portions: 1,
  preparationTimeMin: 20,
  dayNutrition: { calories: 2000, protein: 90, fat: 70, carbs: 250 },
  newPurchaseCost: 100,
  deltaPurchaseCost: -10,
  budgetStatus: 'WITHIN',
  priceQuality: 'COMPLETE_OBSERVED',
  goals: ['SAME_PRODUCTS', 'CHEAPER', 'FASTER'],
  impact: {
    addedProducts: [],
    removedProducts: [],
    purchaseProductsBefore: 3,
    purchaseProductsAfter: 3,
    minutesSaved: 5,
    usePantry: true,
    pantryRevision: 1,
    personPortions: [],
  },
} as Alternative;
describe('replacement choices', () => {
  it('ranks cheaper and faster independently without mutating the server response', () => {
    const cheap = { ...base, recipeId: 'cheap', newPurchaseCost: 50, preparationTimeMin: 30 };
    const fast = { ...base, recipeId: 'fast', newPurchaseCost: 120, preparationTimeMin: 5 };
    const items = [fast, cheap];
    expect(replacementChoices(items, 'CHEAPER')[0].recipeId).toBe('cheap');
    expect(replacementChoices(items, 'FASTER')[0].recipeId).toBe('fast');
    expect(items[0]).toBe(fast);
  });
  it('never fabricates a missing category or an unknown saving', () => {
    expect(
      replacementChoices(
        [{ ...base, goals: ['FASTER'], newPurchaseCost: null, deltaPurchaseCost: null }],
        'CHEAPER',
      ),
    ).toEqual([]);
    expect(replacementChoices([{ ...base, goals: ['FASTER'] }], 'SAME_PRODUCTS')).toEqual([]);
  });
  it('limits each group to three and uses deterministic identifiers on ties', () => {
    const items = ['z', 'b', 'a', 'c'].map((recipeId) => ({ ...base, recipeId }));
    expect(replacementChoices(items, 'CHEAPER').map((x) => x.recipeId)).toEqual(['a', 'b', 'c']);
  });
});
