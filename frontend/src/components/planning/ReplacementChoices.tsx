import MealComposition from '../MealComposition';
import { useEffect, useRef, useState } from 'react';
import type { Alternative, ReplacementGoal } from '../../period-planning';
import { replacementChoices, replacementLabels } from '../../replacement-choices';
import { money } from '../../planning-presentation';
import { number } from '../../domain';
import { Notice } from '../ui';

export default function ReplacementChoices({
  alternatives,
  busy,
  editable,
  onConfirm,
  remainingPurchases = false,
}: {
  remainingPurchases?: boolean;
  alternatives: Alternative[];
  busy: boolean;
  editable: boolean;
  onConfirm: (a: Alternative) => void;
}) {
  const [goal, setGoal] = useState<ReplacementGoal>('SAME_PRODUCTS');
  const [selected, setSelected] = useState<Alternative | null>(null);
  const review = useRef<HTMLElement>(null);
  useEffect(() => {
    if (selected) review.current?.scrollIntoView({ block: 'nearest' });
  }, [selected]);
  useEffect(() => {
    setSelected(null);
  }, [alternatives]);
  const legacy = alternatives.length > 0 && alternatives.every((a) => !a.impact);
  const choices = legacy ? alternatives : replacementChoices(alternatives, goal);
  function cost(a: Alternative) {
    return (
      <>
        <p>
          {remainingPurchases ? 'Оставшиеся покупки периода' : 'Покупки на весь период'}:{' '}
          {a.newPurchaseCost == null ? 'полная цена неизвестна' : `≈ ${money(a.newPurchaseCost)}`}
        </p>
        {a.deltaPurchaseCost == null ? (
          <p className="caption">Изменение полной стоимости оценить нельзя: не хватает цен.</p>
        ) : (
          <p>
            {a.deltaPurchaseCost < 0
              ? `Дешевле примерно на ${money(-a.deltaPurchaseCost)}`
              : a.deltaPurchaseCost > 0
                ? `Дороже примерно на ${money(a.deltaPurchaseCost)}`
                : 'Оценка стоимости не изменится'}
          </p>
        )}
        {a.budgetStatus === 'OVER' && (
          <p className="caption">После замены закупка всё ещё превышает бюджет.</p>
        )}
        {a.budgetStatus === 'UNKNOWN' && (
          <p className="caption">При неполных ценах проверить бюджет нельзя.</p>
        )}
      </>
    );
  }
  return (
    <section aria-label="Варианты замены">
      <h3>На что заменить</h3>
      <p className="caption">
        Изменится только этот приём пищи.{' '}
        {remainingPurchases
          ? 'Сравниваем оставшуюся закупку с учётом упаковок и запасов.'
          : 'Стоимость сравнивается для всей закупки периода с учётом упаковок.'}
      </p>
      {!legacy && (
        <div className="replacement-modes" aria-label="Как подобрать замену">
          {(Object.keys(replacementLabels) as ReplacementGoal[]).map((g) => (
            <button
              className="button secondary"
              key={g}
              aria-pressed={goal === g}
              disabled={busy}
              onClick={() => {
                setGoal(g);
                setSelected(null);
              }}
            >
              {replacementLabels[g]}
            </button>
          ))}
        </div>
      )}
      {!choices.length && (
        <Notice>
          {goal === 'SAME_PRODUCTS'
            ? 'Без новых позиций в покупках подходящих замен не найдено. Посмотрите более дешёвые или быстрые варианты.'
            : goal === 'CHEAPER'
              ? 'Более дешёвых вариантов по текущей оценке не найдено. При неполных ценах экономия не рассчитывается.'
              : 'Подходящих блюд с меньшим временем приготовления не найдено.'}
        </Notice>
      )}
      {!selected &&
        choices.map((a, i) => (
          <article
            className="period-alternative"
            key={`${a.candidateKey || a.recipeId}-${a.portions}`}
          >
            <h4>{a.name}</h4>
            <p>
              {a.preparationTimeMin} мин · питание дня {number(a.dayNutrition.calories)} ккал
            </p>
            {cost(a)}
            {a.impact && (
              <p>
                {a.impact.addedProducts.length
                  ? `Новых позиций в покупках: ${a.impact.addedProducts.length}`
                  : 'Новых позиций в покупках нет'}{' '}
                · всего {a.impact.purchaseProductsAfter}
              </p>
            )}
            <button
              className="button secondary"
              disabled={busy || !editable}
              onClick={() => setSelected(a)}
            >
              Выбрать вариант {i + 1}
            </button>
          </article>
        ))}
      {selected && (
        <section
          ref={review}
          className="replacement-review panel"
          aria-label="Подтверждение замены"
        >
          <h3>Заменить на «{selected.name}»?</h3>
          {cost(selected)}
          {selected.impact && (
            <>
              {selected.impact.previousPurchaseCost !== undefined && (
                <p>
                  До замены:{' '}
                  {selected.impact.previousPurchaseCost == null
                    ? 'полная цена неизвестна'
                    : `≈ ${money(selected.impact.previousPurchaseCost)}`}
                </p>
              )}
              <p>
                Позиций в покупках: {selected.impact.purchaseProductsBefore} →{' '}
                {selected.impact.purchaseProductsAfter}
              </p>
              <p>
                {selected.impact.addedProducts.length
                  ? `Добавятся: ${selected.impact.addedProducts.map((p) => p.name).join(', ')}`
                  : 'Ничего нового покупать не потребуется. Количество и число упаковок знакомых продуктов могут измениться.'}
              </p>
              {!!selected.impact.removedProducts.length && (
                <p>
                  Больше не нужны в закупке:{' '}
                  {selected.impact.removedProducts.map((p) => p.name).join(', ')}. Уже купленные
                  продукты останутся в запасах.
                </p>
              )}
              <p>
                {selected.impact.minutesSaved > 0
                  ? `Готовить на ${selected.impact.minutesSaved} мин быстрее.`
                  : selected.impact.minutesSaved < 0
                    ? `Готовить на ${-selected.impact.minutesSaved} мин дольше.`
                    : 'Время приготовления не изменится.'}{' '}
                {selected.impact.usePantry
                  ? 'Доступные запасы учтены.'
                  : 'Учёт запасов выключен в настройках подбора.'}
              </p>
              {selected.impact.personPortions.length > 0 && (
                <details>
                  <summary>Порции участников после замены</summary>
                  {selected.proposedRecipe?.components?.length ? (
                    <MealComposition
                      recipe={selected.proposedRecipe}
                      people={selected.impact.personPortions}
                    />
                  ) : (
                    <ul>
                      {selected.impact.personPortions.map((p) => (
                        <li key={p.participantId}>
                          {p.name}: {number(p.portions, 2)} порц. · {number(p.nutrition.calories)}{' '}
                          ккал
                        </li>
                      ))}
                    </ul>
                  )}
                </details>
              )}
            </>
          )}
          {selected.impact?.dataAsOf && (
            <p className="caption">
              Расчёт на {new Date(selected.impact.dataAsOf).toLocaleString('ru-RU')}.
            </p>
          )}
          <p className="caption">
            Остальные блюда останутся на своих местах. Цены приблизительные; если данные изменятся,
            потребуется заново проверить замену.
          </p>
          <button
            className="button primary"
            disabled={busy || !editable}
            onClick={() => onConfirm(selected)}
          >
            Подтвердить замену
          </button>
          <button className="text-button" disabled={busy} onClick={() => setSelected(null)}>
            Назад к вариантам
          </button>
        </section>
      )}
    </section>
  );
}
