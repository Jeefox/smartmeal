import { ApiError } from './api';
import type { PeriodView } from './period-planning';
import { missingMeals, saveBlockReason } from './period-shopping';
import { number } from './domain';
import { mealLabels } from './mealplans';
export function money(value: number) {
  return `${number(value, 2)} ₽`;
}
export function basketSummary(view: PeriodView) {
  const b = view.result.shopping;
  return b.estimatedTotal == null || b.unknownProducts > 0
    ? `Известная часть — ${money(b.knownPurchaseCost)} · товаров без цены: ${b.unknownProducts}`
    : `Покупки примерно ${money(b.estimatedTotal)}${view.result.options.budgetRub != null ? ` / бюджет ${money(view.result.options.budgetRub)}` : ''}`;
}
export function budgetSummary(view: PeriodView) {
  const b = view.result.shopping,
    budget = view.result.options.budgetRub;
  if (b.estimatedTotal == null || b.unknownProducts > 0)
    return 'Полную стоимость и попадание в бюджет пока проверить нельзя';
  if (budget == null) return 'Бюджет не задан';
  return b.estimatedTotal > budget
    ? `На ${money(b.estimatedTotal - budget)} выше бюджета`
    : 'По оценке в бюджете';
}
export function incompleteSummary(view: PeriodView) {
  const missing = missingMeals(view);
  if (!missing.length) return null;
  return (
    `Не подобрано приёмов пищи: ${missing.length}. ` +
    [...new Set(missing.map((m) => m.meal))]
      .map((meal) => `${mealLabels[meal]} — ${missing.filter((m) => m.meal === meal).length}`)
      .join(', ') +
    '.'
  );
}
export type Recovery = 'reload' | 'refresh' | 'create' | 'calendar' | 'login' | 'none';
export function errorRecovery(error: Error): { action: Recovery; label: string } {
  if (!(error instanceof ApiError)) return { action: 'none', label: '' };
  if (error.status === 401) return { action: 'login', label: 'Войти в аккаунт' };
  const code = error.code;
  if (code === 'MEAL_PLAN_DATE_CONFLICT') return { action: 'calendar', label: 'Открыть календарь' };
  if (
    code === 'PANTRY_CHANGED' ||
    code === 'SHOPPING_ESTIMATE_CHANGED' ||
    code === 'REPLACEMENT_COMPARISON_CHANGED'
  )
    return { action: 'refresh', label: 'Обновить расчёт' };
  if (code === 'GENERATION_REVISION_CONFLICT' || code === 'GENERATION_ALREADY_SAVED')
    return { action: 'reload', label: 'Проверить состояние меню' };
  if (
    error.status === 410 ||
    code === 'PLANNING_CONTEXT_CHANGED' ||
    code === 'GENERATION_NOT_FOUND'
  )
    return { action: 'create', label: 'Создать новый подбор' };
  return { action: 'none', label: '' };
}
export function planningBlock(view: PeriodView, target: number | null | undefined, now: number) {
  return saveBlockReason(view, target, now);
}
export function warningGroups(view: PeriodView) {
  const groups = new Map<string, string[]>();
  for (const issue of view.result.issues) {
    const title =
      issue.code === 'BUDGET_EXCEEDED'
        ? 'Бюджет'
        : issue.code.includes('PRICE')
          ? 'Цены'
          : issue.code === 'TARGET_NOT_REACHED'
            ? 'Питание'
            : issue.code.includes('PANTRY')
              ? 'Домашние запасы'
              : 'Условия подбора';
    groups.set(title, [...(groups.get(title) || []), issue.message]);
  }
  return [...groups].map(([title, messages]) => ({ title, messages }));
}

export function planningDiagnostics(view: PeriodView) {
  return view.result.issues.filter((i) =>
    /^(CANDIDATES_|MISSING_YIELD_|INSUFFICIENT_|COMPACT_BASKET_|SHOPPING_PRODUCT_LIMIT|NO_FEASIBLE_FOUND|TARGET_NOT_REACHED)/.test(
      i.code,
    ),
  );
}
