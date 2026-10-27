import { request } from './api';
import type { Nutrition } from './types';
export type HouseholdMember = {
  id: string;
  name: string;
  dailyCalorieTarget: number;
  allergenIds: number[];
  excludedProductIds: number[];
  excludedProducts?: { id: number; name: string }[];
};
export type MemberInput = Omit<HouseholdMember, 'id' | 'excludedProducts'>;
export type Participant = {
  id: string;
  name: string;
  dailyCalorieTarget: number;
  accountOwner: boolean;
};
export type PersonPortion = {
  participantId: string;
  name: string;
  portions: number;
  nutrition: Nutrition;
};
export type PersonNutrition = {
  participantId: string;
  name: string;
  dailyCalorieTarget: number;
  proteinTargetG?: number | null;
  fiberTargetG?: number | null;
  nutrition: Nutrition;
};
export const householdApi = {
  list: (signal?: AbortSignal) => request<HouseholdMember[]>('/me/household-members', { signal }),
  save: (input: MemberInput, id?: string) =>
    request<HouseholdMember>(`/me/household-members${id ? '/' + encodeURIComponent(id) : ''}`, {
      method: id ? 'PUT' : 'POST',
      body: JSON.stringify(input),
    }),
  remove: (id: string) =>
    request<void>(`/me/household-members/${encodeURIComponent(id)}`, {
      method: 'DELETE',
    }),
};
