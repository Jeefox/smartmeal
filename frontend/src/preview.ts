import { request } from './api';
import type { MealPlan } from './mealplans';
import type { Nutrition } from './types';
import { calendarDay } from './shopping';
export const previewMeals = ['BREAKFAST', 'LUNCH', 'DINNER'] as const;
export type PreviewMeal = (typeof previewMeals)[number];
export type PreviewOptions = {
  planDate: string;
  breakfastMaxPreparationTimeMin: number;
  lunchMaxPreparationTimeMin: number;
  dinnerMaxPreparationTimeMin: number;
};
export type MenuPreview = {
  planDate: string;
  dailyCalorieTarget: number;
  nutritionTotal: Nutrition;
  calorieDeviation: number;
  withinTargetTolerance: boolean;
  warnings: string[];
  entries: {
    mealType: PreviewMeal;
    recipeId: string;
    recipeName: string;
    portions: number;
    preparationTimeMin: number;
    nutrition: Nutrition;
    lastPlannedDate: string | null;
    recipeFingerprint: string;
  }[];
};
export const previewApi = {
  generate: (options: PreviewOptions, signal?: AbortSignal) =>
    request<MenuPreview>('/me/meal-plans/preview', {
      method: 'POST',
      body: JSON.stringify(options),
      signal,
    }),
  save: (options: PreviewOptions, preview: MenuPreview) =>
    request<MealPlan>('/me/meal-plans/preview/save', {
      method: 'POST',
      body: JSON.stringify(previewSaveBody(options, preview)),
    }),
};
export function previewSaveBody(options: PreviewOptions, preview: MenuPreview) {
  return {
    options,
    dailyCalorieTarget: preview.dailyCalorieTarget,
    entries: preview.entries.map(({ mealType, recipeId, portions, recipeFingerprint }) => ({
      mealType,
      recipeId,
      portions,
      recipeFingerprint,
    })),
  };
}
export const timeFields = [
  ['breakfastMaxPreparationTimeMin', 'Завтрак', 15],
  ['lunchMaxPreparationTimeMin', 'Обед', 45],
  ['dinnerMaxPreparationTimeMin', 'Ужин', 45],
] as const;
export function previewFormErrors(planDate: string, times: string[]) {
  const errors: Record<string, string> = {};
  if (calendarDay(planDate) == null) errors.planDate = 'Укажите корректную дату меню.';
  times.forEach((value, index) => {
    if (!/^\d+$/.test(value) || Number(value) > 2147483647)
      errors[timeFields[index][0]] = 'Введите целое неотрицательное время в минутах.';
  });
  return errors;
}
export const warningText = (code: string) =>
  ({
    TARGET_NOT_REACHED:
      'Не удалось подобрать калорийность в пределах 10% от цели. Проверьте итог перед сохранением.',
    RECENT_RECIPES_USED:
      'Есть блюда из вашей истории планирования за предыдущий 21 день. Это история меню, а не сведения о съеденной еде.',
    CATALOGUE_ESTIMATES_USED:
      'Часть стоимости рассчитана по каталожным ориентирам. Цены в выбранном магазине не подтверждены.',
    CATALOGUE_SEARCH_LIMIT:
      'Поиск ограничен частью каталога. Другие подходящие рецепты могли не попасть в подбор.',
  })[code] ?? `Предупреждение сервера: ${code}`;
