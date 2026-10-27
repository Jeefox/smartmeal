import type { PlanningOptions, PeriodView } from './period-planning';
import { calendarDay } from './shopping';
import { dateKey, shiftDate } from './domain';
import { readCity } from './store-preferences';
import { validatePlanningOptions } from './period-planning';
export type PlanningDraft = {
  balanced?: boolean;
  maxShoppingProducts?: number | null;
  from: string;
  days: number;
  budget: string;
  city: string;
  chains: number[];
  members: string[];
  times: PlanningOptions['maxPreparationTimeByMeal'];
  repeat: number;
  gap: number;
  pantry: boolean;
  estimates: boolean;
  split: boolean;
  saving: number;
};
const key = (userId: string) => `smartmeal.planning.preferences.v1.${userId}`;
export function defaultDraft(): PlanningDraft {
  return {
    balanced: true,
    maxShoppingProducts: 10,
    from: dateKey(new Date()),
    days: 7,
    budget: '',
    city: readCity() || 'Тула',
    chains: [],
    members: [],
    times: { BREAKFAST: 15, LUNCH: 45, DINNER: 45 },
    repeat: 2,
    gap: 2,
    pantry: true,
    estimates: true,
    split: false,
    saving: 50,
  };
}
export function draftOptions(d: PlanningDraft): PlanningOptions {
  return {
    balancedMeals: d.balanced === true,
    maxShoppingProducts: d.maxShoppingProducts ?? null,
    from: d.from,
    to: shiftDate(d.from, d.days - 1),
    budgetRub: d.budget.trim() ? Number(d.budget) : null,
    city: d.city,
    allowedChainIds: d.chains,
    householdMemberIds: d.members,
    maxPreparationTimeByMeal: d.times,
    maxRecipeOccurrences: d.repeat,
    minRepeatGapDays: d.gap,
    usePantry: d.pantry,
    allowEstimatedPrices: d.estimates,
    purchaseMode: d.split ? 'TWO_CHAINS' : 'SINGLE_CHAIN',
    minSplitSavingRub: d.saving,
    seed: 0,
  };
}
export function fromOptions(o: PlanningOptions): PlanningDraft {
  return {
    balanced: o.balancedMeals === true,
    maxShoppingProducts: o.maxShoppingProducts ?? null,
    from: o.from,
    days: Math.round((calendarDay(o.to)! - calendarDay(o.from)!) / 86400000) + 1,
    budget: o.budgetRub == null ? '' : String(o.budgetRub),
    city: o.city,
    chains: [...o.allowedChainIds],
    members: [...(o.householdMemberIds || [])],
    times: { ...o.maxPreparationTimeByMeal },
    repeat: o.maxRecipeOccurrences,
    gap: o.minRepeatGapDays,
    pantry: o.usePantry !== false,
    estimates: o.allowEstimatedPrices,
    split: o.purchaseMode === 'TWO_CHAINS',
    saving: o.minSplitSavingRub,
  };
}
export function sameOptions(a: PlanningOptions, b: PlanningOptions) {
  const normal = (o: PlanningOptions) => [
    o.balancedMeals === true,
    o.maxShoppingProducts ?? null,
    o.from,
    o.to,
    o.budgetRub,
    o.city,
    [...o.allowedChainIds].sort((a, b) => a - b),
    [...(o.householdMemberIds || [])].sort(),
    o.purchaseMode,
    o.maxPreparationTimeByMeal.BREAKFAST,
    o.maxPreparationTimeByMeal.LUNCH,
    o.maxPreparationTimeByMeal.DINNER,
    o.maxRecipeOccurrences,
    o.minRepeatGapDays,
    o.allowEstimatedPrices,
    o.minSplitSavingRub,
    o.usePantry !== false,
  ];
  return JSON.stringify(normal(a)) === JSON.stringify(normal(b));
}
export function readPlanningPreferences(userId: string): PlanningDraft {
  const fallback = defaultDraft();
  try {
    const stored = JSON.parse(localStorage.getItem(key(userId)) || 'null');
    if (!stored || stored.version !== 1) return fallback;
    const d: PlanningDraft = {
      ...fallback,
      ...stored.values,
      from: fallback.from,
    };
    if (
      !Array.isArray(d.chains) ||
      !d.chains.every((n: unknown) => typeof n === 'number' && Number.isSafeInteger(n) && n > 0) ||
      !Array.isArray(d.members) ||
      !d.members.every((id: unknown) => typeof id === 'string' && /^[0-9a-f-]{36}$/i.test(id)) ||
      !Number.isInteger(d.days) ||
      d.days < 1 ||
      d.days > 14 ||
      typeof d.budget !== 'string' ||
      typeof d.city !== 'string' ||
      (d.balanced !== undefined && typeof d.balanced !== 'boolean') ||
      typeof d.pantry !== 'boolean' ||
      typeof d.estimates !== 'boolean' ||
      typeof d.split !== 'boolean' ||
      !d.times ||
      !['BREAKFAST', 'LUNCH', 'DINNER'].every(
        (m) => typeof d.times[m as keyof typeof d.times] === 'number',
      )
    )
      return fallback;
    if (validatePlanningOptions(draftOptions({ ...d, chains: d.chains.length ? d.chains : [1] })))
      return fallback;
    return d;
  } catch {
    return fallback;
  }
}
export function savePlanningPreferences(userId: string, draft: PlanningDraft) {
  try {
    const { from: _, ...values } = draft;
    localStorage.setItem(key(userId), JSON.stringify({ version: 1, values }));
  } catch {
    /* Preferences are optional. */
  }
}
const pendingKey = (userId: string, id: string) => `smartmeal.planning.commit.${userId}.${id}`;
export function commitKey(userId: string, id: string, fallback: Map<string, string>) {
  const existing = fallback.get(id);
  if (existing) return existing;
  let value: string | null = null;
  try {
    value = sessionStorage.getItem(pendingKey(userId, id));
  } catch {
    /* Memory fallback. */
  }
  if (!value || !/^[0-9a-f-]{36}$/i.test(value)) value = crypto.randomUUID();
  fallback.set(id, value);
  try {
    sessionStorage.setItem(pendingKey(userId, id), value);
  } catch {
    /* Server also prevents duplicate commits. */
  }
  return value;
}
export function clearCommitKey(userId: string, id: string) {
  try {
    sessionStorage.removeItem(pendingKey(userId, id));
  } catch {
    /* Optional. */
  }
}
export function savedPeriod(userId: string): { id: string; from: string; to: string } | null {
  try {
    const value = JSON.parse(localStorage.getItem(`smartmeal.saved-period.${userId}`) || 'null');
    return value &&
      typeof value.id === 'string' &&
      /^[a-z0-9-]{1,80}$/i.test(value.id) &&
      calendarDay(value.from) != null &&
      calendarDay(value.to) != null
      ? value
      : null;
  } catch {
    return null;
  }
}
export function rememberSavedPeriod(userId: string, view: PeriodView) {
  if (!view.savedPlanIds.length) return;
  try {
    localStorage.setItem(
      `smartmeal.saved-period.${userId}`,
      JSON.stringify({
        id: view.id,
        from: view.result.options.from,
        to: view.result.options.to,
      }),
    );
  } catch {
    /* Optional. */
  }
}

export function pendingCommit(userId: string, id: string) {
  try {
    return sessionStorage.getItem(pendingKey(userId, id));
  } catch {
    return null;
  }
}
