import { FiberLabel } from '../MealComposition';
import { LockKeyhole, ChevronRight } from 'lucide-react';
import type { PeriodView, Selection } from '../../period-planning';
import { calendarDay } from '../../shopping';
import { labelDate, number, shiftDate } from '../../domain';
import { mealLabels } from '../../mealplans';
import { shoppingProductCount } from '../../period-shopping';
import { planningDiagnostics } from '../../planning-presentation';
import { incompleteSummary } from '../../planning-presentation';
const meals = ['BREAKFAST', 'LUNCH', 'DINNER'] as const;
export default function PeriodMenuOverview({
  view,
  busy,
  onMeal,
  onMissing,
}: {
  view: PeriodView;
  busy: boolean;
  onMeal: (entry: Selection) => void;
  onMissing: () => void;
}) {
  const o = view.result.options,
    start = calendarDay(o.from),
    end = calendarDay(o.to);
  const dates =
    start == null || end == null
      ? []
      : Array.from({ length: Math.min(14, Math.max(0, (end - start) / 86400000 + 1)) }, (_, i) =>
          shiftDate(o.from, i),
        );
  const missing = incompleteSummary(view);
  return (
    <section className="panel menu-overview" aria-label="Меню на весь период">
      <p className="caption">
        К покупке: {shoppingProductCount(view)} разных продуктов
        {view.result.options.maxShoppingProducts != null
          ? ` / максимум ${view.result.options.maxShoppingProducts}`
          : ''}
        . Упаковки общие для всего меню.
      </p>
      {planningDiagnostics(view).length > 0 && (
        <section className="notice warning" role="region" aria-label="Что мешает подбору">
          <h3>Что мешает подбору</h3>
          <ul>
            {planningDiagnostics(view).map((i) => (
              <li key={i.code}>{i.message}</li>
            ))}
          </ul>
          <button className="button secondary" disabled={busy} onClick={onMissing}>
            Изменить условия подбора
          </button>
        </section>
      )}
      <div className="menu-table-heading" aria-hidden="true">
        <span>День</span>
        {meals.map((m) => (
          <span key={m}>{mealLabels[m]}</span>
        ))}
      </div>
      {dates.map((date) => {
        const day = view.result.days.find((d) => d.date === date);
        return (
          <article className="menu-day" key={date} data-date={date}>
            <div className="menu-date">
              <h3>
                {labelDate(date, {
                  weekday: 'short',
                  day: 'numeric',
                  month: 'short',
                })}
              </h3>
            </div>
            <dl className="menu-day-meals">
              {meals.map((meal) => {
                const entry = view.result.entries.find(
                  (e) => e.date === date && e.mealType === meal,
                );
                return (
                  <div className="menu-meal" key={meal}>
                    <dt>{mealLabels[meal]}</dt>
                    <dd>
                      {entry ? (
                        <button
                          className="menu-dish"
                          disabled={busy}
                          onClick={() => onMeal(entry)}
                          aria-label={`${mealLabels[meal]}, ${labelDate(date)}: ${entry.recipe.name}`}
                        >
                          <span>{entry.recipe.name}</span>
                          {entry.locked && <LockKeyhole size={14} aria-label="Оставлено в меню" />}
                          <ChevronRight size={15} />
                        </button>
                      ) : (
                        <button className="menu-dish menu-dish-missing" onClick={onMissing}>
                          Не удалось подобрать
                          <ChevronRight size={15} />
                        </button>
                      )}
                    </dd>
                  </div>
                );
              })}
            </dl>
            {day && (
              <details className="menu-day-nutrition">
                <summary>Питание за {labelDate(date)}</summary>
                {(day.people?.length || 0) > 0 ? (
                  day.people!.map((p) => (
                    <p key={p.participantId}>
                      <strong>{p.name}</strong>: {number(p.nutrition.calories)} /{' '}
                      {number(p.dailyCalorieTarget)} ккал
                      {view.result.options.balancedMeals && (
                        <>
                          <br />
                          Белок: {number(p.nutrition.protein, 1)} г
                          {p.proteinTargetG != null
                            ? ` / ${number(p.proteinTargetG, 1)} г`
                            : ' · цель не задана'}
                          <br />
                          <FiberLabel
                            known={p.nutrition.knownFiber}
                            complete={p.nutrition.fiberComplete}
                          />
                          {p.fiberTargetG != null
                            ? ` / цель ${number(p.fiberTargetG, 1)} г`
                            : ' · цель не задана'}
                        </>
                      )}
                    </p>
                  ))
                ) : (
                  <p>
                    {number(day.nutrition.calories)} / {number(view.result.dailyCalorieTarget)} ккал
                  </p>
                )}
              </details>
            )}
          </article>
        );
      })}
      <p className="caption menu-overview-help">
        {missing ||
          `${dates.length} из ${dates.length} дней заполнено. Все дни сохраняются вместе.`}
      </p>
    </section>
  );
}
