import { describe, it, expect } from 'vitest';
import { ApiError } from './api';
import {
  basketSummary,
  budgetSummary,
  errorRecovery,
  warningGroups,
  incompleteSummary,
} from './planning-presentation';
import type { PeriodView } from './period-planning';
const view = (): PeriodView => ({
  id: 'g',
  revision: 0,
  expiresAt: '2099-01-01T00:00:00Z',
  savedPlanIds: [],
  result: {
    options: {
      from: '2035-01-01',
      to: '2035-01-01',
      budgetRub: 4000,
      allowedChainIds: [1],
      city: 'Тула',
      purchaseMode: 'SINGLE_CHAIN',
      maxPreparationTimeByMeal: { BREAKFAST: 15, LUNCH: 45, DINNER: 45 },
      maxRecipeOccurrences: 2,
      minRepeatGapDays: 2,
      allowEstimatedPrices: true,
      minSplitSavingRub: 50,
      seed: 0,
    },
    dailyCalorieTarget: 2000,
    dataAsOf: '2035-01-01T10:00:00Z',
    entries: [],
    days: [],
    status: 'NO_FEASIBLE_FOUND',
    searchStatus: 'FOUND',
    issues: [],
    shopping: {
      items: [],
      knownPurchaseCost: 3800,
      estimatedTotal: 3800,
      consumedIngredientCost: 3000,
      pricedProducts: 3,
      unknownProducts: 0,
      estimatedProducts: 3,
      priceQuality: 'COMPLETE_ESTIMATED',
      budgetStatus: 'WITHIN',
      chains: [{ id: 1, name: 'Чижик' }],
      savingAgainstSingleChain: 0,
    },
  },
});
describe('planning presentation', () => {
  it('never presents unknown costs as a complete total or a budget success', () => {
    const v = view();
    v.result.shopping.estimatedTotal = null;
    v.result.shopping.unknownProducts = 2;
    expect(basketSummary(v)).toContain('Известная часть');
    expect(basketSummary(v)).toContain('без цены: 2');
    expect(budgetSummary(v)).not.toContain('в бюджете');
  });
  it('shows the actual budget excess', () => {
    const v = view();
    v.result.shopping.estimatedTotal = 4350;
    expect(budgetSummary(v)).toContain('350');
  });
  it('counts empty slots instead of asking the user to fill dates manually', () => {
    expect(incompleteSummary(view())).toBe(
      'Не подобрано приёмов пищи: 3. Завтрак — 1, Обед — 1, Ужин — 1.',
    );
  });
  it('uses machine codes, not translated message substrings, for recovery', () => {
    expect(errorRecovery(new ApiError(409, 'любое сообщение', [], 'PANTRY_CHANGED')).action).toBe(
      'refresh',
    );
    expect(
      errorRecovery(new ApiError(409, 'Цены изменились', [], 'REPLACEMENT_COMPARISON_CHANGED'))
        .action,
    ).toBe('refresh');
    expect(errorRecovery(new ApiError(409, 'Запасы изменились')).action).toBe('none');
    expect(errorRecovery(new ApiError(409, 'x', [], 'MEAL_PLAN_DATE_CONFLICT')).action).toBe(
      'calendar',
    );
    expect(errorRecovery(new ApiError(410, 'x')).action).toBe('create');
  });
  it('preserves unknown warnings for explicit acknowledgement', () => {
    const v = view();
    v.result.issues = [
      { code: 'NEW_CODE', message: 'Новая важная причина' },
      { code: 'SEARCH_LIMIT', message: 'Оптимизация ограничена' },
      { code: 'BUDGET_EXCEEDED', message: 'Дороже' },
    ];
    expect(warningGroups(v).flatMap((g) => g.messages)).toHaveLength(3);
  });
});
