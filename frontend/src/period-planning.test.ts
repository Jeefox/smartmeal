import { describe, expect, it } from 'vitest';
import { validatePlanningOptions } from './period-planning';
import type { PlanningOptions } from './period-planning';
const options: PlanningOptions = {
  from: '2035-01-01',
  to: '2035-01-07',
  budgetRub: 3000,
  allowedChainIds: [1],
  city: 'Тула',
  purchaseMode: 'SINGLE_CHAIN',
  maxPreparationTimeByMeal: { BREAKFAST: 15, LUNCH: 45, DINNER: 45 },
  maxRecipeOccurrences: 2,
  minRepeatGapDays: 2,
  allowEstimatedPrices: true,
  minSplitSavingRub: 50,
  seed: 0,
};
describe('period planning inputs', () => {
  it('accepts a week and an optional budget', () => {
    expect(validatePlanningOptions(options)).toBeNull();
    expect(validatePlanningOptions({ ...options, budgetRub: null })).toBeNull();
  });
  it('rejects reversed or too long periods', () => {
    expect(validatePlanningOptions({ ...options, to: '2034-12-31' })).not.toBeNull();
    expect(validatePlanningOptions({ ...options, to: '2035-01-15' })).not.toBeNull();
  });
  it('does not send impossible dates', () => {
    expect(validatePlanningOptions({ ...options, from: '2035-02-30' })).not.toBeNull();
  });
  it('requires a chain and does not duplicate it', () => {
    expect(validatePlanningOptions({ ...options, allowedChainIds: [] })).not.toBeNull();
    expect(validatePlanningOptions({ ...options, allowedChainIds: [1, 1] })).not.toBeNull();
  });
  it('validates money and timing', () => {
    for (const budgetRub of [-1, 0, NaN, 10.001])
      expect(validatePlanningOptions({ ...options, budgetRub })).not.toBeNull();
    expect(
      validatePlanningOptions({
        ...options,
        maxPreparationTimeByMeal: { ...options.maxPreparationTimeByMeal, BREAKFAST: NaN },
      }),
    ).not.toBeNull();
  });
  it('rejects invalid repeats', () => {
    expect(validatePlanningOptions({ ...options, maxRecipeOccurrences: 0 })).not.toBeNull();
    expect(validatePlanningOptions({ ...options, minRepeatGapDays: -1 })).not.toBeNull();
  });
});
