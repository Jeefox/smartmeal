import { useQuery } from '@tanstack/react-query';
import { cookingApi, cookingGroups } from './cooking';
import type { CookingView, CookingCandidate } from './cooking';
import { useSession } from './session';
import { shiftDate } from './domain';
export type BatchPreference = { enabled: boolean; days: 2 | 3; weekdays: number[] };
export const defaultBatchPreference = (): BatchPreference => ({
  enabled: false,
  days: 2,
  weekdays: [0, 1, 2, 3, 4, 5, 6],
});
const key = (id: string) => `smartmeal.batch-cooking.v1.${id}`;
export function readBatchPreference(id: string): BatchPreference {
  try {
    const v = JSON.parse(localStorage.getItem(key(id)) || 'null');
    if (
      v &&
      typeof v.enabled === 'boolean' &&
      [2, 3].includes(v.days) &&
      Array.isArray(v.weekdays) &&
      v.weekdays.length > 0 &&
      v.weekdays.every(
        (n: unknown) => typeof n === 'number' && Number.isInteger(n) && n >= 0 && n <= 6,
      ) &&
      new Set(v.weekdays).size === v.weekdays.length
    )
      return v;
  } catch {
    /* optional preferences */
  }
  return defaultBatchPreference();
}
export function saveBatchPreference(id: string, p: BatchPreference) {
  try {
    localStorage.setItem(key(id), JSON.stringify(p));
  } catch {
    /* available for this visit */
  }
}
export function useTodayCooking(date: string, enabled = true) {
  const { profile } = useSession();
  const to = shiftDate(date, 6);
  return useQuery({
    queryKey: ['cooking', profile?.userId, date, to],
    queryFn: ({ signal }) => cookingApi.view(date, to, signal),
    enabled: enabled && !!profile,
    staleTime: 15000,
    retry: false,
  });
}
export function batchSuggestions(view: CookingView, date: string, p: BatchPreference) {
  const weekday = new Date(`${date}T12:00:00`).getDay();
  if (!p.enabled || !p.weekdays.includes(weekday) || date < view.today) return [];
  let last = shiftDate(date, p.days - 1);
  for (let i = 1; i < p.days; i++)
    if (p.weekdays.length < 7 && p.weekdays.includes((weekday + i) % 7)) {
      last = shiftDate(date, i - 1);
      break;
    }
  return cookingGroups(view.candidates.filter((c) => c.date >= date && c.date <= last)).filter(
    (g) => new Set(g.entries.map((c) => c.date)).size >= 2,
  );
}
export function readyComponents(view: CookingView | undefined, entryId: string) {
  return (view?.batches || []).flatMap((b) =>
    b.allocations
      .filter((a) => a.entryId === entryId && a.status !== 'RELEASED')
      .map((a) => ({ batch: b, allocation: a })),
  );
}
export function candidateRefs(entries: CookingCandidate[]) {
  return entries.map((e) => e.ref);
}
