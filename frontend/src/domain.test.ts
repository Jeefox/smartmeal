import { describe, expect, it } from 'vitest';
import { buildShopping, planNutrition, quoteTotals, shiftDate, weekDates } from './domain';
import type { Offer, PlanEntry, Recipe } from './types';
const recipe: Recipe = {
  id: 'rice',
  name: 'Рис',
  description: '',
  instructions: 'Отварить',
  servings: 2,
  preparationTimeMin: 20,
  ingredients: [{ productId: 1, productName: 'Рис', quantityG: 500 }],
  nutritionTotal: { calories: 1750, protein: 35, fat: 5, carbs: 390 },
};
const entry = (id: string, portions: number): PlanEntry => ({
  id,
  date: '2026-10-16',
  meal: 'Обед',
  recipe,
  portions,
  locked: false,
});
const offer: Offer = {
  productId: 1,
  name: 'Рис',
  packageG: 800,
  priceCents: 10000,
  quality: 'OBSERVED',
  observedAt: '2026-10-16',
  source: 'test',
};
describe('Корзина за период', () => {
  it('агрегирует 1250 г до округления: 2 × 800 г, 200 ₽, остаток 350 г', () => {
    const [line] = buildShopping([entry('1', 2), entry('2', 3)], [offer]);
    expect(line.requiredG).toBe(1250);
    expect(line.packs).toBe(2);
    expect(line.costCents).toBe(20000);
    expect(line.consumedCents).toBe(15625);
    expect(line.remainderG).toBe(350);
  });
  it('бюджет 180 ₽ не соблюдён, хотя стоимость еды ниже', () => {
    const items = buildShopping([entry('1', 5)], [offer]);
    expect(quoteTotals(items, 180, 'known').status).toBe('OVER_BUDGET');
  });
  it('не превращает неизвестную цену в бесплатную упаковку', () => {
    const items = buildShopping(
      [entry('1', 5)],
      [{ ...offer, priceCents: null, quality: 'UNKNOWN' }],
    );
    expect(items[0].costCents).toBeNull();
    expect(quoteTotals(items, 1000, 'estimate')).toMatchObject({
      status: 'INCOMPLETE',
      unknown: 1,
      complete: false,
    });
  });
  it('показывает риск при центральной цене ниже бюджета и верхней выше', () => {
    const items = buildShopping(
      [entry('1', 5)],
      [{ ...offer, quality: 'ESTIMATE', lowerCents: 9000, upperCents: 14000 }],
    );
    expect(quoteTotals(items, 250, 'estimate').status).toBe('RISK');
    expect(quoteTotals(items, 250, 'known').status).toBe('INCOMPLETE');
  });
  it('не скрывает отсутствие упаковки', () => {
    const [line] = buildShopping([entry('1', 1)], []);
    expect(line.packs).toBeNull();
    expect(line.remainderG).toBeNull();
    expect(line.costCents).toBeNull();
  });
  it('не изменяет исходный снимок при расчёте', () => {
    const input = entry('1', 0.5);
    const before = JSON.stringify(input);
    buildShopping([input], [offer]);
    expect(JSON.stringify(input)).toBe(before);
  });
});
describe('Питание и даты', () => {
  it('масштабирует дробную порцию по неокруглённому итогу', () => {
    expect(planNutrition([entry('1', 0.5)])).toEqual({
      calories: 437.5,
      protein: 8.75,
      fat: 1.25,
      carbs: 97.5,
    });
  });
  it('не показывает неполную сумму питания как достоверную', () => {
    expect(
      planNutrition([
        {
          ...entry('1', 1),
          recipe: { ...recipe, nutritionTotal: null, nutritionPerServing: null },
        },
      ]),
    ).toBeNull();
  });
  it('неделя начинается в понедельник и пересекает границу месяца', () => {
    expect(weekDates('2026-11-01')).toEqual([
      '2026-10-26',
      '2026-10-27',
      '2026-10-28',
      '2026-10-29',
      '2026-10-30',
      '2026-10-31',
      '2026-11-01',
    ]);
    expect(shiftDate('2026-12-31', 1)).toBe('2027-01-01');
  });
});
