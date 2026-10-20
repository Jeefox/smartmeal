import type { Nutrition, Offer, PlanEntry, ShoppingItem } from './types';
export const zeroNutrition = (): Nutrition => ({ calories: 0, protein: 0, fat: 0, carbs: 0 });
export function planNutrition(entries: PlanEntry[]): Nutrition | null {
  if (entries.some((entry) => !entry.recipe.nutritionTotal && !entry.recipe.nutritionPerServing))
    return null;
  return entries.reduce((total, entry) => {
    const base = entry.recipe.nutritionTotal;
    const serving = entry.recipe.nutritionPerServing!;
    for (const key of Object.keys(total) as (keyof Nutrition)[])
      total[key] += (base ? base[key] / entry.recipe.servings : serving[key]) * entry.portions;
    return total;
  }, zeroNutrition());
}
export function buildShopping(entries: PlanEntry[], offers: Offer[]): ShoppingItem[] {
  const required = new Map<number, { name: string; grams: number }>();
  entries.forEach((entry) =>
    entry.recipe.ingredients.forEach((ingredient) => {
      const existing = required.get(ingredient.productId);
      required.set(ingredient.productId, {
        name: ingredient.productName,
        grams:
          (existing?.grams || 0) + (ingredient.quantityG * entry.portions) / entry.recipe.servings,
      });
    }),
  );
  return [...required].map(([productId, value]) => {
    const offer = offers.find((o) => o.productId === productId) || null;
    const packs =
      offer && offer.packageG > 0
        ? Math.ceil(Number(value.grams.toFixed(6)) / offer.packageG)
        : null;
    const known = offer?.priceCents != null && packs != null;
    const cost = known ? packs! * offer!.priceCents! : null;
    return {
      productId,
      productName: value.name,
      category: 'Продукты для меню',
      requiredG: value.grams,
      packs,
      purchasedG: packs != null ? packs * offer!.packageG : null,
      remainderG: packs != null ? packs * offer!.packageG - value.grams : null,
      costCents: cost,
      consumedCents: known
        ? Math.round((offer!.priceCents! * value.grams) / offer!.packageG)
        : null,
      lowerCents: known
        ? packs! *
          (offer!.quality === 'OBSERVED'
            ? offer!.priceCents!
            : (offer!.lowerCents ?? offer!.priceCents!))
        : null,
      upperCents: known
        ? packs! *
          (offer!.quality === 'OBSERVED'
            ? offer!.priceCents!
            : (offer!.upperCents ?? offer!.priceCents!))
        : null,
      offer,
      status: 'PLANNED',
    };
  });
}
export function quoteTotals(items: ShoppingItem[], budget: number, mode: 'known' | 'estimate') {
  const unknown = items.filter((i) => i.costCents == null).length;
  const estimates = items.filter((i) => i.offer?.quality === 'ESTIMATE').length;
  const stale = items.filter((i) => i.offer?.quality === 'STALE').length;
  const sum = (key: 'costCents' | 'consumedCents' | 'lowerCents' | 'upperCents') =>
    items.reduce((total, item) => total + (item[key] ?? 0), 0);
  const complete =
    items.length > 0 && unknown === 0 && (mode === 'estimate' || estimates + stale === 0);
  const central = sum('costCents');
  const upper = sum('upperCents');
  const status = !complete
    ? 'INCOMPLETE'
    : central > budget * 100
      ? 'OVER_BUDGET'
      : upper > budget * 100
        ? 'RISK'
        : 'WITHIN_SCENARIO';
  return {
    unknown,
    estimates,
    stale,
    observed: items.length - unknown - estimates - stale,
    central,
    consumed: sum('consumedCents'),
    lower: sum('lowerCents'),
    upper,
    complete,
    status,
  };
}
export const number = (value: number, digits = 0) =>
  new Intl.NumberFormat('ru-RU', { maximumFractionDigits: digits }).format(value);
export const money = (cents: number | null) =>
  cents == null
    ? 'Неизвестно'
    : new Intl.NumberFormat('ru-RU', {
        style: 'currency',
        currency: 'RUB',
        maximumFractionDigits: 2,
      }).format(cents / 100);
export function dateKey(date: Date) {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Europe/Moscow',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date);
}
export function shiftDate(key: string, days: number) {
  const date = new Date(`${key}T12:00:00+03:00`);
  date.setUTCDate(date.getUTCDate() + days);
  return dateKey(date);
}
export function weekDates(key: string) {
  const day = new Date(`${key}T12:00:00+03:00`).getUTCDay();
  const monday = shiftDate(key, -((day + 6) % 7));
  return Array.from({ length: 7 }, (_, i) => shiftDate(monday, i));
}
export const labelDate = (
  key: string,
  options: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'long' },
) =>
  new Intl.DateTimeFormat('ru-RU', { ...options, timeZone: 'Europe/Moscow' }).format(
    new Date(`${key}T12:00:00+03:00`),
  );
