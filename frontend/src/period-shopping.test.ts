import { describe, expect, it } from 'vitest';
import {
  buyAmount,
  missingMeals,
  needToBuy,
  purchaseKey,
  saveBlockReason,
  shoppingText,
  shoppingProductCount,
} from './period-shopping';
import type { BasketItem, PeriodView } from './period-planning';
const item: BasketItem = {
  productId: 1,
  productName: 'Гречка',
  requiredG: 450,
  fromPantryG: 0,
  needToBuyG: 450,
  purchasedG: 900,
  remainderG: 450,
  packageCount: 1,
  packageWeightG: 900,
  packagePrice: 90,
  purchaseCost: 90,
  chainId: 1,
  chainName: 'Чижик',
  offerName: 'Гречка 900 г',
  estimated: true,
  sourceName: 'Каталог',
  sourceScope: 'Тула',
  sourceUrl: null,
  observedAt: '2030-01-01T00:00:00Z',
  expiresAt: null,
  applicability: 'CHAIN_CATALOGUE_ESTIMATE',
};
function view(): PeriodView {
  return {
    id: 'id',
    revision: 0,
    expiresAt: '2099-01-01T00:00:00Z',
    savedPlanIds: [],
    result: {
      options: {
        from: '2030-01-01',
        to: '2030-01-02',
        budgetRub: 500,
        allowedChainIds: [1],
        city: 'Тула',
        purchaseMode: 'SINGLE_CHAIN',
        maxPreparationTimeByMeal: { BREAKFAST: 20, LUNCH: 60, DINNER: 40 },
        maxRecipeOccurrences: 2,
        minRepeatGapDays: 1,
        allowEstimatedPrices: true,
        minSplitSavingRub: 0,
        seed: 1,
      },
      dataAsOf: '2030-01-01T00:00:00Z',
      days: [],
      searchStatus: 'COMPLETED',
      dailyCalorieTarget: 2000,
      entries: ['2030-01-01', '2030-01-02'].flatMap((date) =>
        (['BREAKFAST', 'LUNCH', 'DINNER'] as const).map((mealType) => ({
          date,
          mealType,
          id: date + mealType,
          recipeFingerprint: 'recipe',
          portions: 1,
          locked: false,
          coverImageUrl: null,
          explanations: [],
          recipe: {
            recipeId: 'recipe',
            name: 'Каша',
            description: null,
            instructions: 'Сварить',
            preparationTimeMin: 15,
            servings: 1,
            ingredients: [],
            nutritionTotal: { calories: 400, protein: 10, fat: 10, carbs: 60 },
            nutritionPerServing: { calories: 400, protein: 10, fat: 10, carbs: 60 },
          },
        })),
      ),
      shopping: {
        items: [item],
        estimatedTotal: 90,
        knownPurchaseCost: 90,
        consumedIngredientCost: 45,
        pricedProducts: 1,
        estimatedProducts: 1,
        unknownProducts: 0,
        priceQuality: 'COMPLETE_ESTIMATED',
        budgetStatus: 'WITHIN',
        chains: [{ id: 1, name: 'Чижик' }],
        savingAgainstSingleChain: null,
      },
      status: 'READY_WITH_WARNINGS',
      issues: [{ code: 'ESTIMATED_PRICES', message: 'Цены приблизительные' }],
    },
  };
}
describe('clear shopping quantities', () => {
  it('leads with whole packages, rather than the recipe fraction', () =>
    expect(buyAmount(item)).toBe('1 уп. × 900 г'));
  it('distinguishes a weighed purchase from a missing package', () => {
    expect(buyAmount({ ...item, packageCount: null, purchasedG: 1200 })).toBe('1,2 кг на вес');
    expect(buyAmount({ ...item, packageCount: null, purchasedG: null })).toBe(
      '450 г · уточните упаковку',
    );
  });
  it('keeps fully covered pantry goods out of the shopping need', () => {
    const home = { ...item, needToBuyG: 0, fromPantryG: 450 };
    expect(needToBuy(home)).toBe(0);
    expect(buyAmount(home)).toBe('Есть дома');
    expect(needToBuy({ ...item, needToBuyG: undefined, fromPantryG: 300 })).toBe(150);
  });
  it('keeps the mark on a price-only refresh and resets on changed quantity', () => {
    expect(purchaseKey(item)).toBe(purchaseKey({ ...item, purchaseCost: 100 }));
    expect(purchaseKey(item)).not.toBe(purchaseKey({ ...item, packageCount: 2, purchasedG: 1800 }));
  });
});
describe('save explanation', () => {
  it('reproduces the screenshot: acknowledgement cannot fix missing meals', () => {
    const partial = view();
    partial.result.entries.pop();
    partial.result.status = 'NO_FEASIBLE_FOUND';
    expect(missingMeals(partial)).toEqual([{ date: '2030-01-02', meal: 'DINNER' }]);
    expect(saveBlockReason(partial, 2000)).toContain('не подобрано приёмов пищи — 1');
  });
  it('warns about a missing whole day even when no day object was returned', () => {
    const partial = view();
    partial.result.entries = partial.result.entries.filter((e) => e.date === '2030-01-01');
    expect(missingMeals(partial)).toHaveLength(3);
  });
  it('soft price warnings do not independently block a complete menu', () =>
    expect(saveBlockReason(view(), 2000)).toBeNull());
  it('explains expiry and profile changes', () => {
    const expired = view();
    expired.expiresAt = '2000-01-01T00:00:00Z';
    expect(saveBlockReason(expired, 2000)).toContain('Срок предпросмотра истёк');
    expect(saveBlockReason(view(), 2200)).toContain('Суточная цель изменилась');
  });
  it('saved plans remain readable and do not request confirmation again', () => {
    const saved = view();
    saved.savedPlanIds = ['plan'];
    saved.expiresAt = '2000-01-01T00:00:00Z';
    expect(saveBlockReason(saved, 2200)).toBeNull();
  });
});
describe('household calorie targets', () => {
  it('compares the current account target with its own snapshot, not the household sum', () => {
    const household = view();
    household.result.dailyCalorieTarget = 3500;
    household.result.participants = [
      { id: 'owner', name: 'Вы', dailyCalorieTarget: 2000, accountOwner: true },
      { id: 'person', name: 'Анна', dailyCalorieTarget: 1500, accountOwner: false },
    ];
    expect(saveBlockReason(household, 2000)).toBeNull();
    expect(saveBlockReason(household, 2100)).toContain('Суточная цель изменилась');
  });
});
describe('plain shopping list', () => {
  it('exports a short quantity list with explicit approximate prices', () => {
    const text = shoppingText(view());
    expect(text).toContain('Гречка — 1 уп. × 900 г');
    expect(text).toContain('≈ 90 ₽');
    expect(text).not.toContain('Остаток');
  });
  it('does not export pantry goods as purchases or unknown prices as zero', () => {
    const result = view();
    result.result.shopping.items = [
      { ...item, needToBuyG: 0 },
      { ...item, productId: 2, productName: 'Рис', purchaseCost: null },
    ];
    result.result.shopping.estimatedTotal = null;
    const text = shoppingText(result);
    expect(text).not.toContain('□ Гречка');
    expect(text).toContain('цена неизвестна');
    expect(text).toContain('есть товары без цены');
  });
  it('labels an incomplete draft as a preliminary shopping list', () => {
    const result = view();
    result.result.entries = [];
    expect(shoppingText(result)).toContain('Предварительный список');
  });
});

it('counts buying products once, excludes home stock and blocks exceeding the explicit cap', () => {
  const v = view();
  v.result.options.maxShoppingProducts = 1;
  v.result.shopping.items = [
    item,
    { ...item, packageCount: 3 },
    { ...item, productId: 2, needToBuyG: 0, fromPantryG: 450 },
  ];
  expect(shoppingProductCount(v)).toBe(1);
  expect(saveBlockReason(v, 2000)).toBeNull();
  v.result.shopping.items.push({ ...item, productId: 3, purchaseCost: null });
  expect(shoppingProductCount(v)).toBe(2);
  expect(saveBlockReason(v, 2000)).toMatch(/при пределе 1/);
});
