import { WeightCorrection, ConsumptionUndo, CorrectionHistory } from './CookingCorrections';
import { readyComponents, useTodayCooking } from '../today-cooking';
import PortionFeedback from './PortionFeedback';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { dateKey } from '../domain';
import type { MealPlan, MealPlanEntry } from '../mealplans';
import { pantryApi, usePantryCommand } from '../pantry';
import { useSession } from '../session';
import { Dialog, Notice } from './ui';
export default function PlanInventory({
  plan,
  entry: onlyEntry,
}: {
  plan: MealPlan;
  entry?: MealPlanEntry;
}) {
  const { profile } = useSession(),
    command = usePantryCommand();
  const query = useQuery({
    queryKey: ['plan-inventory', profile?.userId, plan.id],
    queryFn: ({ signal }) => pantryApi.states(plan.id, signal),
  });
  const [confirmation, setConfirmation] = useState(false);
  const cooking = useTodayCooking(plan.planDate, plan.status !== 'DRAFT');
  const revision = query.data?.revision;
  const disabled = command.busy || !command.online || revision == null || query.isFetching;
  const entryControls = onlyEntry && query.data?.entries.find((s) => s.entryId === onlyEntry.id);
  if (onlyEntry) {
    const state = entryControls?.state,
      entry = onlyEntry;
    const prepared = readyComponents(cooking.data, entry.id);
    const pendingBatch = prepared.some(
      (x) => x.allocation.status === 'ACTIVE' && x.batch.status === 'PLANNED',
    );
    const expiredBatch = prepared.some((x) => x.allocation.status === 'ACTIVE' && x.batch.expired);
    const hasReady = prepared.some(
      (x) => x.allocation.status === 'ACTIVE' && x.batch.status === 'COOKED' && !x.batch.expired,
    );
    const allComponentsReady =
      !!entry.recipeSnapshot.components?.length &&
      entry.recipeSnapshot.components.every((c) =>
        prepared.some(
          (x) =>
            x.allocation.componentId === c.id &&
            x.allocation.status === 'ACTIVE' &&
            x.batch.status === 'COOKED' &&
            !x.batch.expired,
        ),
      );
    const eaten = prepared.some((x) => x.allocation.status === 'CONSUMED');
    return (
      <div
        className="plan-entry-inventory"
        aria-label={`Учёт ингредиентов: ${entry.recipeSnapshot.name}`}
      >
        <p className="caption">
          {state === 'DEDUCTED'
            ? eaten
              ? 'Отмечено съеденным'
              : 'Готово к подаче · ингредиенты списаны'
            : state === 'SKIPPED'
              ? 'Блюдо пропущено'
              : 'Приготовление не отмечено'}
        </p>
        {pendingBatch && (
          <p className="caption">
            Сначала приготовьте общую партию в блоке «Что приготовить» выше.
          </p>
        )}
        {expiredBatch && (
          <p className="caption">
            У готового компонента истекла указанная дата хранения.{' '}
            <Link to="/cooking">Проверить остатки</Link>
          </p>
        )}
        {command.message && <Notice>{command.message}</Notice>}
        <div className="period-actions">
          {plan.status === 'CONFIRMED' && state === 'OPEN' && (
            <>
              <button
                className="button primary"
                disabled={
                  disabled ||
                  pendingBatch ||
                  expiredBatch ||
                  plan.planDate > (query.data?.today || dateKey(new Date()))
                }
                onClick={() =>
                  command.run(`/me/meal-plan-entries/${entry.id}/ingredient-usage`, {
                    expectedRevision: revision,
                  })
                }
              >
                {allComponentsReady
                  ? 'Подать блюдо'
                  : hasReady
                    ? 'Приготовил остальное'
                    : 'Приготовил'}
              </button>
            </>
          )}
          {state === 'DEDUCTED' && hasReady && (
            <button
              className="button primary"
              disabled={
                disabled ||
                pendingBatch ||
                expiredBatch ||
                cooking.isFetching ||
                cooking.data?.revision == null
              }
              onClick={() =>
                command.run(`/me/cooking-batches/entries/${entry.id}/consume`, {
                  expectedRevision: cooking.data!.revision,
                })
              }
            >
              Съедено
            </button>
          )}
          <details className="today-other-actions">
            <summary>Другие действия</summary>
            {eaten && cooking.data && (
              <ConsumptionUndo
                entryId={entry.id}
                revision={cooking.data.revision}
                available={prepared
                  .filter((x) => x.allocation.status === 'CONSUMED')
                  .every((x) => x.batch.status === 'COOKED')}
              />
            )}
            {cooking.data &&
              [...new Map(prepared.map((x) => [x.batch.id, x.batch])).values()].map((b) => (
                <div key={b.id}>
                  <p className="caption">{b.name}</p>
                  {b.status === 'COOKED' && b.actualYieldG != null && (
                    <WeightCorrection
                      batchId={b.id}
                      weight={b.actualYieldG}
                      revision={cooking.data!.revision}
                    />
                  )}
                  <CorrectionHistory batchId={b.id} />
                </div>
              ))}
            {plan.status === 'CONFIRMED' && state === 'OPEN' && (
              <>
                {' '}
                <button
                  className="button quiet"
                  disabled={disabled}
                  onClick={() =>
                    command.run(
                      `/me/meal-plan-entries/${entry.id}/inventory-state`,
                      { expectedRevision: revision, state: 'SKIPPED' },
                      'PUT',
                    )
                  }
                >
                  Пропустить
                </button>
              </>
            )}
            {state === 'DEDUCTED' && !eaten && (
              <button
                className="button quiet"
                disabled={disabled}
                onClick={() =>
                  command.run(`/me/meal-plan-entries/${entry.id}/ingredient-usage/reversal`, {
                    expectedRevision: revision,
                  })
                }
              >
                Отменить ошибочное списание
              </button>
            )}
            {plan.status === 'CONFIRMED' && state === 'SKIPPED' && (
              <button
                className="button quiet"
                disabled={disabled}
                onClick={() =>
                  command.run(
                    `/me/meal-plan-entries/${entry.id}/inventory-state`,
                    { expectedRevision: revision, state: 'OPEN' },
                    'PUT',
                  )
                }
              >
                Вернуть блюдо
              </button>
            )}
            {state === 'DEDUCTED' && <PortionFeedback entry={entry} />}
          </details>
        </div>
      </div>
    );
  }
  return (
    <section className="panel plan-inventory">
      <div className="period-actions">
        <h2>Ингредиенты и запасы</h2>
        <Link to="/pantry">Открыть запасы</Link>
        <Link to="/cooking">Готовить на несколько дней · готовые остатки</Link>
      </div>
      <p className="muted">
        Подтверждение меню резервирует продукты. «Приготовил» списывает сырые ингредиенты; это не
        отметка о съеденных калориях.
      </p>
      {query.error && (
        <Notice>
          Не удалось загрузить учёт ингредиентов.{' '}
          <button onClick={() => query.refetch()}>Повторить</button>
        </Notice>
      )}
      {command.message && <Notice>{command.message}</Notice>}
      {plan.status === 'DRAFT' && <p>Черновик не резервирует и не списывает продукты.</p>}
      {plan.status === 'CANCELLED' && (
        <Notice>
          День отменён. Неприготовленные блюда исключены из покупок, резервы освобождены.
        </Notice>
      )}
      {plan.status === 'CANCELLED' ? (
        <button
          className="button secondary"
          disabled={disabled}
          onClick={() =>
            command.run(`/me/meal-plans/${plan.id}/reopen`, {
              expectedRevision: revision,
            })
          }
        >
          Восстановить в черновик
        </button>
      ) : (
        <button className="button quiet" disabled={disabled} onClick={() => setConfirmation(true)}>
          Отменить день
        </button>
      )}
      {confirmation && (
        <Dialog title="Отменить меню дня?" onClose={() => setConfirmation(false)}>
          <p>
            Резервы неприготовленных блюд освободятся. Уже выполненные списания останутся в истории.
          </p>
          <button
            className="button secondary"
            disabled={disabled}
            onClick={async () => {
              if (
                await command.run(`/me/meal-plans/${plan.id}/cancel`, {
                  expectedRevision: revision,
                })
              )
                setConfirmation(false);
            }}
          >
            Подтвердить отмену дня
          </button>
        </Dialog>
      )}
    </section>
  );
}
