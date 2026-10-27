import type { Participant, PersonPortion, PersonNutrition } from './household';
import { request } from './api';
import type { RecipeSnapshot, ApiMealType } from './mealplans';
import type { Nutrition } from './types';
import { calendarDay } from './shopping';

export type PlanningMeal = Exclude<ApiMealType, 'SNACK'>;
export type PlanningOptions = {
  balancedMeals?: boolean;
  maxShoppingProducts?: number | null;
  from: string;
  to: string;
  budgetRub: number | null;
  allowedChainIds: number[];
  city: string;
  purchaseMode: 'SINGLE_CHAIN' | 'TWO_CHAINS';
  maxPreparationTimeByMeal: Record<PlanningMeal, number>;
  maxRecipeOccurrences: number;
  minRepeatGapDays: number;
  allowEstimatedPrices: boolean;
  minSplitSavingRub: number;
  seed: number;
  usePantry?: boolean;
  householdMemberIds?: string[];
};
export type BudgetStatus = 'WITHIN' | 'OVER' | 'UNKNOWN' | 'NOT_SET';
export type PriceQuality = 'COMPLETE_OBSERVED' | 'COMPLETE_ESTIMATED' | 'PARTIAL';
export type Selection = {
  id: string;
  date: string;
  mealType: PlanningMeal;
  recipe: RecipeSnapshot;
  recipeFingerprint: string;
  portions: number;
  locked: boolean;
  coverImageUrl: string | null;
  explanations: string[];
  personPortions?: PersonPortion[];
};
export type BasketItem = {
  productId: number;
  productName: string;
  requiredG: number;
  fromPantryG?: number;
  needToBuyG?: number;
  purchasedG: number | null;
  remainderG: number | null;
  packageCount: number | null;
  packageWeightG: number | null;
  packagePrice: number | null;
  purchaseCost: number | null;
  chainId: number | null;
  chainName: string | null;
  offerName: string | null;
  estimated: boolean;
  sourceName: string | null;
  sourceScope: string | null;
  sourceUrl: string | null;
  observedAt: string | null;
  expiresAt: string | null;
  applicability: string;
};
export type PeriodView = {
  id: string;
  revision: number;
  expiresAt: string;
  savedPlanIds: string[];
  result: {
    options: PlanningOptions;
    dailyCalorieTarget: number;
    participants?: Participant[];
    dataAsOf: string;
    entries: Selection[];
    days: {
      date: string;
      nutrition: Nutrition;
      calorieDeviation: number;
      withinTargetTolerance: boolean;
      entries: Selection[];
      people?: PersonNutrition[];
    }[];
    shopping: {
      items: BasketItem[];
      knownPurchaseCost: number;
      estimatedTotal: number | null;
      consumedIngredientCost: number | null;
      pricedProducts: number;
      estimatedProducts: number;
      unknownProducts: number;
      priceQuality: PriceQuality;
      budgetStatus: BudgetStatus;
      chains: { id: number; name: string }[];
      savingAgainstSingleChain: number | null;
    };
    issues: { code: string; message: string }[];
    status: string;
    searchStatus: string;
  };
};
export type ReplacementGoal = 'SAME_PRODUCTS' | 'CHEAPER' | 'FASTER';
export type Alternative = {
  goals?: ReplacementGoal[];
  comparisonKey?: string;
  impact?: {
    addedProducts: { productId: number; name: string }[];
    removedProducts: { productId: number; name: string }[];
    purchaseProductsBefore: number;
    purchaseProductsAfter: number;
    minutesSaved: number;
    usePantry: boolean;
    pantryRevision: number;
    previousPurchaseCost?: number | null;
    dataAsOf?: string;
    personPortions: import('./household').PersonPortion[];
  };
  candidateKey?: string;
  componentId?: string;
  proposedRecipe?: RecipeSnapshot;
  recipeId: string;
  name: string;
  portions: number;
  preparationTimeMin: number;
  dayNutrition: Nutrition;
  newPurchaseCost: number | null;
  deltaPurchaseCost: number | null;
  budgetStatus: BudgetStatus;
  priceQuality: PriceQuality;
};
const base = '/me/meal-plan-generations';
const body = (method: string, data: unknown, signal?: AbortSignal) => ({
  method,
  body: JSON.stringify(data),
  signal,
});
export const periodApi = {
  create: (options: PlanningOptions, signal?: AbortSignal) =>
    request<PeriodView>(base, body('POST', options, signal)),
  get: (id: string, signal?: AbortSignal) =>
    request<PeriodView>(`${base}/${encodeURIComponent(id)}`, { signal }),
  alternatives: (view: PeriodView, entry: Selection, signal?: AbortSignal, componentId?: string) =>
    request<Alternative[]>(
      `${base}/${view.id}/entries/${encodeURIComponent(entry.id)}/alternatives${componentId ? `?componentId=${encodeURIComponent(componentId)}` : ''}`,
      { signal },
    ),
  lock: (view: PeriodView, entry: Selection, signal?: AbortSignal) =>
    request<PeriodView>(
      `${base}/${view.id}/entries/${encodeURIComponent(entry.id)}/lock`,
      body('PATCH', { expectedRevision: view.revision, locked: !entry.locked }, signal),
    ),
  replace: (view: PeriodView, entry: Selection, alternative: Alternative, signal?: AbortSignal) =>
    request<PeriodView>(
      `${base}/${view.id}/entries/${encodeURIComponent(entry.id)}`,
      body(
        'PUT',
        {
          expectedRevision: view.revision,
          recipeId: alternative.recipeId,
          portions: alternative.portions,
          comparisonKey: alternative.comparisonKey,
          ...(alternative.candidateKey
            ? {
                candidateKey: alternative.candidateKey,
                componentId: alternative.componentId,
              }
            : {}),
        },
        signal,
      ),
    ),
  lockComponent: (
    view: PeriodView,
    entry: Selection,
    componentId: string,
    locked: boolean,
    signal?: AbortSignal,
  ) =>
    request<PeriodView>(
      `${base}/${view.id}/entries/${encodeURIComponent(entry.id)}/components/${encodeURIComponent(componentId)}/lock`,
      body('PATCH', { expectedRevision: view.revision, locked }, signal),
    ),
  regenerate: (view: PeriodView, signal?: AbortSignal) =>
    request<PeriodView>(
      `${base}/${view.id}/regenerate`,
      body('POST', { expectedRevision: view.revision }, signal),
    ),
  refresh: (view: PeriodView, signal?: AbortSignal) =>
    request<PeriodView>(
      `${base}/${view.id}/refresh`,
      body('POST', { expectedRevision: view.revision }, signal),
    ),
  commit: (view: PeriodView, key: string, acceptWarnings: boolean, signal?: AbortSignal) =>
    request<PeriodView>(`${base}/${view.id}/commit`, {
      ...body('POST', { expectedRevision: view.revision, acceptWarnings }, signal),
      headers: { 'Idempotency-Key': key },
    }),
};
export function validatePlanningOptions(value: PlanningOptions): string | null {
  const from = calendarDay(value.from),
    to = calendarDay(value.to);
  if (from == null || to == null || to < from || (to - from) / 86_400_000 >= 14)
    return 'Выберите период от 1 до 14 дней.';
  if (
    !value.allowedChainIds.length ||
    value.allowedChainIds.length > 6 ||
    new Set(value.allowedChainIds).size !== value.allowedChainIds.length
  )
    return 'Выберите от одной до шести разных сетей.';
  if (
    (value.householdMemberIds?.length || 0) > 9 ||
    new Set(value.householdMemberIds || []).size !== (value.householdMemberIds?.length || 0)
  )
    return 'Выберите до 9 разных профилей семьи.';
  if (
    value.maxShoppingProducts != null &&
    (!Number.isInteger(value.maxShoppingProducts) ||
      value.maxShoppingProducts < 1 ||
      value.maxShoppingProducts > 50)
  )
    return 'Предел покупок: целое число от 1 до 50.';
  if (!value.city.trim() || value.city.trim().length > 120) return 'Укажите город.';
  if (
    value.budgetRub != null &&
    (!Number.isFinite(value.budgetRub) ||
      value.budgetRub <= 0 ||
      value.budgetRub > 9999999999.99 ||
      Math.abs(value.budgetRub * 100 - Math.round(value.budgetRub * 100)) > 0.0001)
  )
    return 'Бюджет должен быть положительной суммой с точностью до копеек.';
  if (
    Object.values(value.maxPreparationTimeByMeal).some(
      (n) => !Number.isInteger(n) || n < 0 || n > 240,
    )
  )
    return 'Время готовки: от 0 до 240 минут.';
  if (
    !Number.isInteger(value.maxRecipeOccurrences) ||
    value.maxRecipeOccurrences < 1 ||
    value.maxRecipeOccurrences > 14 ||
    !Number.isInteger(value.minRepeatGapDays) ||
    value.minRepeatGapDays < 0 ||
    value.minRepeatGapDays > 14
  )
    return 'Проверьте настройки повторов.';
  if (!Number.isFinite(value.minSplitSavingRub) || value.minSplitSavingRub < 0)
    return 'Минимальная экономия не может быть отрицательной.';
  return null;
}
export const budgetLabel: Record<BudgetStatus, string> = {
  WITHIN: 'По оценке в бюджете',
  OVER: 'Выше бюджета',
  UNKNOWN: 'Бюджет проверить нельзя',
  NOT_SET: 'Бюджет не задан',
};
