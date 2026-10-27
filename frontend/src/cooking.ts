import { request } from './api';
export type CookingRef = { entryId: string; componentId: string };
export type CookingCandidate = {
  ref: CookingRef;
  date: string;
  mealType: string;
  recipeName: string;
  fingerprint: string;
  cookedG: number;
};
export type CookingBatch = {
  id: string;
  name: string;
  cookOn: string;
  useBy: string;
  status: 'PLANNED' | 'COOKED' | 'CANCELLED' | 'DISCARDED';
  estimatedYieldG: number;
  actualYieldG: number | null;
  remainingG: number;
  freeG: number;
  expired: boolean;
  fingerprint: string;
  recipeId: string;
  instructions: string;
  ingredients: { name: string; quantityG: number }[];
  allocations: {
    id: string;
    entryId: string;
    componentId: string;
    date: string;
    mealType: string;
    cookedG: number;
    status: 'ACTIVE' | 'CONSUMED' | 'RELEASED';
    mealReady: boolean;
  }[];
};
export type CookingView = {
  revision: number;
  today: string;
  candidates: CookingCandidate[];
  batches: CookingBatch[];
  drafts: { id: string; date: string }[];
};
export const cookingApi = {
  view: (from: string, to: string, signal?: AbortSignal) =>
    request<CookingView>(
      `/me/cooking-batches?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
      { signal },
    ),
};
export function cookingGroups(candidates: CookingCandidate[]) {
  const groups = new Map<string, CookingCandidate[]>();
  for (const c of candidates) {
    const group = groups.get(c.fingerprint) || [];
    group.push(c);
    groups.set(c.fingerprint, group);
  }
  return [...groups].map(([fingerprint, entries]) => ({
    fingerprint,
    name: entries[0].recipeName,
    entries: entries.sort(
      (a, b) => a.date.localeCompare(b.date) || a.ref.entryId.localeCompare(b.ref.entryId),
    ),
    cookedG: entries.reduce((sum, c) => sum + c.cookedG, 0),
  }));
}
