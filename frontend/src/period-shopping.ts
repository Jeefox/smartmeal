import type { BasketItem, PeriodView, PlanningMeal } from './period-planning';
import { number, labelDate } from './domain';
import { calendarDay } from './shopping';

export const mainMeals: PlanningMeal[] = ['BREAKFAST', 'LUNCH', 'DINNER'];
export type MissingMeal = { date: string; meal: PlanningMeal };
export function missingMeals(view: PeriodView): MissingMeal[] {
  const start = calendarDay(view.result.options.from),
    end = calendarDay(view.result.options.to);
  if (start == null || end == null || end < start || (end - start) / 86400000 >= 14) return [];
  const present = new Set(view.result.entries.map((e) => `${e.date}:${e.mealType}`));
  const missing: MissingMeal[] = [];
  for (let time = start; time <= end; time += 86400000) {
    const date = new Date(time).toISOString().slice(0, 10);
    for (const meal of mainMeals) if (!present.has(`${date}:${meal}`)) missing.push({ date, meal });
  }
  return missing;
}
export function saveBlockReason(
  view: PeriodView,
  target: number | null | undefined,
  now = Date.now(),
): string | null {
  if (view.savedPlanIds.length) return null;
  const missing = missingMeals(view);
  if (missing.length)
    return `Меню не готово: не подобрано приёмов пищи — ${missing.length}. Сначала заполните все дни.`;
  if (view.result.status === 'NO_FEASIBLE_FOUND')
    return 'Меню не удалось собрать полностью. Измените настройки и повторите подбор.';
  if (Date.parse(view.expiresAt) <= now)
    return 'Срок предпросмотра истёк. Создайте свежий подбор перед сохранением.';
  if (
    target !==
    (view.result.participants?.find((p) => p.accountOwner)?.dailyCalorieTarget ??
      view.result.dailyCalorieTarget)
  )
    return 'Суточная цель изменилась. Создайте подбор с актуальной целью.';
  return null;
}
export function needToBuy(item: BasketItem): number {
  return Math.max(0, item.needToBuyG ?? item.requiredG - (item.fromPantryG ?? 0));
}
export function grams(value: number): string {
  return value >= 1000 ? `${number(value / 1000, 2)} кг` : `${number(value, 2)} г`;
}
export function buyAmount(item: BasketItem): string {
  if (needToBuy(item) === 0) return 'Есть дома';
  if (item.packageCount != null && item.packageCount > 0 && item.packageWeightG != null)
    return `${item.packageCount} уп. × ${grams(item.packageWeightG)}`;
  if (item.purchasedG != null && item.purchasedG > 0) return `${grams(item.purchasedG)} на вес`;
  return `${grams(needToBuy(item))} · уточните упаковку`;
}
export function purchaseKey(item: BasketItem): string {
  return JSON.stringify([
    item.productId,
    item.chainId,
    item.offerName,
    item.packageCount,
    item.packageWeightG,
    item.purchasedG,
    needToBuy(item),
  ]);
}
export function shoppingText(view: PeriodView): string {
  const basket = view.result.shopping;
  const incomplete = missingMeals(view).length > 0 || view.result.status === 'NO_FEASIBLE_FOUND';
  const lines = [
    `${incomplete ? 'Предварительный список' : 'Список покупок'}: ${labelDate(view.result.options.from)} — ${labelDate(view.result.options.to)}${(view.result.participants?.length || 0) > 1 ? ` · на ${view.result.participants!.length} чел.` : ''}`,
  ];
  const buy = basket.items.filter((i) => needToBuy(i) > 0);
  for (const chain of [...new Set(buy.map((i) => i.chainName || 'Магазин не выбран'))]) {
    lines.push('', chain);
    for (const item of buy.filter((i) => (i.chainName || 'Магазин не выбран') === chain))
      lines.push(
        `□ ${item.productName} — ${buyAmount(item)}${item.purchaseCost == null ? ' · цена неизвестна' : ` · ${item.estimated ? '≈ ' : ''}${number(item.purchaseCost, 2)} ₽`}`,
      );
  }
  lines.push(
    '',
    basket.estimatedTotal == null
      ? `Известная часть: ${number(basket.knownPurchaseCost, 2)} ₽; есть товары без цены.`
      : `Оценка покупки: ${number(basket.estimatedTotal, 2)} ₽.`,
  );
  lines.push('Цены и наличие при покупке могут отличаться.');
  return lines.join('\n');
}
export type RecentPeriod = { id: string; from: string; to: string };
export function rememberPeriod(userId: string, view: PeriodView) {
  try {
    localStorage.setItem(
      `smartmeal.recent-period.${userId}`,
      JSON.stringify({ id: view.id, from: view.result.options.from, to: view.result.options.to }),
    );
  } catch {
    /* Optional local shortcut; server remains authoritative. */
  }
}
export function recentPeriod(userId: string): RecentPeriod | null {
  try {
    const value = JSON.parse(localStorage.getItem(`smartmeal.recent-period.${userId}`) || 'null');
    return value &&
      typeof value.id === 'string' &&
      calendarDay(value.from) != null &&
      calendarDay(value.to) != null
      ? value
      : null;
  } catch {
    return null;
  }
}
