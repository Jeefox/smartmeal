import { request } from './api';
import type { RecipeSnapshot } from './mealplans';
export type Role = 'COMPLETE_MAIN' | 'PROTEIN_MAIN' | 'SIDE' | 'VEGETABLE' | 'SOUP' | 'BREAKFAST';
export const roleLabels: Record<Role, string> = {
  COMPLETE_MAIN: 'Самостоятельное блюдо',
  PROTEIN_MAIN: 'Основное белковое блюдо',
  SIDE: 'Гарнир',
  VEGETABLE: 'Овощная часть',
  SOUP: 'Суп',
  BREAKFAST: 'Завтрак',
};
export type PortionProfile = {
  role: Role;
  cookedYieldG: number | null;
  yieldQuality: 'UNKNOWN' | 'ESTIMATED' | 'AUTHOR_REPORTED' | 'MEASURED';
  source: string | null;
  minG: number;
  maxG: number;
  stepG: number;
  containsStarch: boolean;
  containsVegetables: boolean;
};
export type MealComponent = {
  id: string;
  role: Role;
  recipe: RecipeSnapshot;
  profile: PortionProfile;
  amounts: { participantId: string; multiplier: number; cookedG: number }[];
  locked: boolean;
};
export type NutrientTargets = {
  expectedRevision: number;
  adultConfirmed: boolean;
  weightKg: number | null;
  proteinTargetG: number | null;
  fiberTargetG: number | null;
  maxMealG: number;
  targetSource: 'UNSET' | 'REFERENCE' | 'USER_SET';
};
export type TargetView = {
  participantId: string;
  name: string;
  revision: number;
  settings: NutrientTargets;
};
export const balancedApi = {
  targets: (signal?: AbortSignal) => request<TargetView[]>('/me/nutrition-targets', { signal }),
  suggest: (r: NutrientTargets) =>
    request<NutrientTargets>('/me/nutrition-targets/suggest', {
      method: 'POST',
      body: JSON.stringify(r),
    }),
  save: (id: string, r: NutrientTargets) =>
    request<TargetView>(`/me/nutrition-targets/${id}`, {
      method: 'PUT',
      body: JSON.stringify(r),
    }),
};
