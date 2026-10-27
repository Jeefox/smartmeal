import type { Participant, PersonPortion, PersonNutrition } from './household';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, request } from './api';
import { useSession } from './session';
import { invalidateShopping } from './shopping';
import type { Ingredient, Nutrition, Recipe } from './types';
export type ApiMealType = 'BREAKFAST' | 'LUNCH' | 'DINNER' | 'SNACK';
export const mealTypes: ApiMealType[] = ['BREAKFAST', 'LUNCH', 'DINNER', 'SNACK'];
export const mealLabels: Record<ApiMealType, string> = {
  BREAKFAST: 'Завтрак',
  LUNCH: 'Обед',
  DINNER: 'Ужин',
  SNACK: 'Перекус',
};
export type RecipeSnapshot = {
  components?: import('./balanced-meals').MealComponent[];
  recipeId: string;
  name: string;
  description: string | null;
  instructions: string;
  preparationTimeMin: number;
  servings: number;
  ingredients: Omit<Ingredient, 'id' | 'position'>[];
  nutritionTotal: Nutrition;
  nutritionPerServing: Nutrition;
};
export type MealPlanEntry = {
  id: string;
  mealPlanId: string;
  mealType: ApiMealType;
  portions: number;
  position: number;
  recipeSnapshot: RecipeSnapshot;
  nutrition: Nutrition;
  personPortions?: PersonPortion[];
};
export type MealPlan = {
  id: string;
  planDate: string;
  status: 'DRAFT' | 'CONFIRMED' | 'CANCELLED';
  dailyCalorieTargetSnapshot: number | null;
  dailyCalorieTarget: number | null;
  confirmedAt: string | null;
  createdAt: string;
  updatedAt: string;
  entries: MealPlanEntry[];
  nutritionTotal: Nutrition;
  participants?: Participant[];
  people?: PersonNutrition[];
};
export const mealPlanKey = (userId: string, date: string) => ['meal-plan', userId, date] as const;
export const mealPlanApi = {
  find: async (date: string, signal?: AbortSignal): Promise<MealPlan | null> => {
    try {
      return await request<MealPlan>(`/me/meal-plans?${new URLSearchParams({ date })}`, { signal });
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null;
      throw error;
    }
  },
  create: (planDate: string) =>
    request<MealPlan>('/me/meal-plans', {
      method: 'POST',
      body: JSON.stringify({ planDate }),
    }),
  add: (planId: string, recipeId: string, mealType: ApiMealType, portions: number) =>
    request<MealPlanEntry>(`/me/meal-plans/${encodeURIComponent(planId)}/entries`, {
      method: 'POST',
      body: JSON.stringify({ recipeId, mealType, portions }),
    }),
  portions: (planId: string, entryId: string, portions: number) =>
    request<MealPlanEntry>(
      `/me/meal-plans/${encodeURIComponent(planId)}/entries/${encodeURIComponent(entryId)}`,
      { method: 'PUT', body: JSON.stringify({ portions }) },
    ),
  remove: (planId: string, entryId: string) =>
    request<void>(
      `/me/meal-plans/${encodeURIComponent(planId)}/entries/${encodeURIComponent(entryId)}`,
      { method: 'DELETE' },
    ),
  confirm: (planId: string) =>
    request<MealPlan>(`/me/meal-plans/${encodeURIComponent(planId)}/confirm`, {
      method: 'POST',
    }),
};
export function validPortions(value: string) {
  return /^\d{1,4}(\.\d{1,2})?$/.test(value) && Number(value) > 0 && Number(value) <= 9999.99;
}
export function snapshotRecipe(snapshot: RecipeSnapshot): Recipe {
  return { ...snapshot, id: snapshot.recipeId };
}
export function useDayPlan(date: string) {
  const session = useSession();
  const id = session.profile!.userId;
  return useQuery({
    queryKey: mealPlanKey(id, date),
    queryFn: ({ signal }) => mealPlanApi.find(date, signal),
    staleTime: 0,
    refetchOnWindowFocus: true,
  });
}
export function useRefreshPlan() {
  const client = useQueryClient();
  const userId = useSession().profile!.userId;
  return async (date: string) => {
    await invalidateShopping(client, userId);
    await client.invalidateQueries({
      queryKey: mealPlanKey(userId, date),
      refetchType: 'none',
    });
    return client.fetchQuery({
      queryKey: mealPlanKey(userId, date),
      queryFn: ({ signal }) => mealPlanApi.find(date, signal),
      staleTime: 0,
    });
  };
}
// A duplicate draft can be created concurrently on another device. Re-read, never replay the POST.
export async function createOrFindPlan(date: string) {
  try {
    return await mealPlanApi.create(date);
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      const existing = await mealPlanApi.find(date);
      if (existing) return existing;
    }
    throw error;
  }
}
export async function addRecipeToDay(
  date: string,
  recipeId: string,
  meal: ApiMealType,
  portions: number,
) {
  const plan = (await mealPlanApi.find(date)) ?? (await createOrFindPlan(date));
  if (plan.status !== 'DRAFT')
    throw new ApiError(409, 'Меню на выбранный день подтверждено. Добавьте блюдо в другой день.');
  return mealPlanApi.add(plan.id, recipeId, meal, portions);
}
export const planRecipes = (q: string, page: number, signal?: AbortSignal) =>
  api.recipes(
    new URLSearchParams({
      q,
      page: String(page),
      size: '12',
      sort: 'name,asc',
    }),
    signal,
  );
// Re-export the shopping contract for existing clients.
export { readShoppingNeeds } from './shopping';
export type { ShoppingNeeds } from './shopping';
