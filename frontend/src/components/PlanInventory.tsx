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
  const revision = query.data?.revision;
  const disabled = command.busy || !command.online || revision == null || query.isFetching;
  const entryControls = onlyEntry && query.data?.entries.find((s) => s.entryId === onlyEntry.id);
  if (onlyEntry) {
    const state = entryControls?.state,
      entry = onlyEntry;
    return (
      <div
        className="plan-entry-inventory"
        aria-label={`Учёт ингредиентов: ${entry.recipeSnapshot.name}`}
      >
        <p className="caption">
          {state === 'DEDUCTED'
            ? 'Ингредиенты списаны'
            : state === 'SKIPPED'
              ? 'Блюдо пропущено'
              : 'Приготовление не отмечено'}
        </p>
        {command.message && <Notice>{command.message}</Notice>}
        <div className="period-actions">
          {plan.status === 'CONFIRMED' && state === 'OPEN' && (
            <>
              <button
                className="button primary"
                disabled={disabled || plan.planDate > (query.data?.today || dateKey(new Date()))}
                onClick={() =>
                  command.run(`/me/meal-plan-entries/${entry.id}/ingredient-usage`, {
                    expectedRevision: revision,
                  })
                }
              >
                Приготовил
              </button>
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
          {state === 'DEDUCTED' && (
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
        </div>
      </div>
    );
  }
  return (
    <section className="panel plan-inventory">
      <div className="period-actions">
        <h2>Ингредиенты и запасы</h2>
        <Link to="/pantry">Открыть запасы</Link>
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
            command.run(`/me/meal-plans/${plan.id}/reopen`, { expectedRevision: revision })
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
