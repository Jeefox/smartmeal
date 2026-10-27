import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../api';
import { useSession } from '../session';
import { usePantryCommand } from '../pantry';
import type { MealPlan, MealPlanEntry } from '../mealplans';
import type { Alternative } from '../period-planning';
import { Dialog, Notice } from './ui';
import ReplacementChoices from './planning/ReplacementChoices';

type Status = {
  revision: number;
  canReplace: boolean;
  reason: string | null;
  undoId: string | null;
  undoName: string | null;
};
type Preview = { revision: number; from: string; to: string; alternatives: Alternative[] };
export default function SavedMealReplacement({
  plan,
  entry,
}: {
  plan: MealPlan;
  entry: MealPlanEntry;
}) {
  const { profile } = useSession(),
    command = usePantryCommand();
  const [open, setOpen] = useState(false),
    [undo, setUndo] = useState(false);
  const base = `/me/meal-plan-entries/${entry.id}/replacements`;
  const status = useQuery({
    queryKey: [
      'saved-replacements',
      profile?.userId,
      entry.id,
      'status',
      plan.updatedAt,
      plan.status,
      entry.portions,
    ],
    queryFn: ({ signal }) => request<Status>(base + '/status', { signal }),
    enabled: !!profile && plan.status !== 'CANCELLED',
    retry: false,
  });
  const preview = useQuery({
    queryKey: ['saved-replacements', profile?.userId, entry.id, 'preview', plan.updatedAt],
    queryFn: ({ signal }) => request<Preview>(base, { signal }),
    enabled: open && !!profile,
    retry: false,
    staleTime: 0,
  });
  if (plan.status === 'CANCELLED') return null;
  return (
    <div className="saved-meal-replacement">
      {command.message && <Notice>{command.message}</Notice>}
      {status.error ? (
        <button className="text-button" onClick={() => status.refetch()}>
          Повторить проверку замены
        </button>
      ) : status.data?.canReplace ? (
        <button
          className="button secondary"
          disabled={command.busy || !command.online}
          onClick={() => setOpen(true)}
        >
          Заменить блюдо
        </button>
      ) : status.data?.reason ? (
        <p className="caption">{status.data.reason}</p>
      ) : null}
      {status.data?.undoId && (
        <button
          className="text-button"
          disabled={command.busy || !command.online}
          onClick={() => setUndo(true)}
        >
          Отменить последнюю замену
        </button>
      )}
      {open && (
        <Dialog title={`Замена: ${entry.recipeSnapshot.name}`} onClose={() => setOpen(false)} wide>
          <p className="caption">
            Другие блюда останутся на месте. Сравниваем оставшиеся покупки периода с учётом запасов;
            уже потраченные деньги сюда не входят.
          </p>
          {preview.isPending && <p role="status">Подбираем варианты…</p>}
          {preview.error && (
            <Notice>
              {preview.error instanceof Error
                ? preview.error.message
                : 'Не удалось получить замены'}{' '}
              <button onClick={() => preview.refetch()}>Обновить варианты</button>
            </Notice>
          )}
          {preview.data && !preview.isFetching && (
            <ReplacementChoices
              alternatives={preview.data.alternatives}
              busy={command.busy}
              editable={command.online}
              remainingPurchases
              onConfirm={async (a) => {
                if (
                  await command.run(base, {
                    expectedRevision: preview.data!.revision,
                    recipeId: a.recipeId,
                    portions: a.portions,
                    candidateKey: a.candidateKey,
                    comparisonKey: a.comparisonKey,
                  })
                )
                  setOpen(false);
              }}
            />
          )}
          {preview.isFetching && !preview.isPending && <p role="status">Обновляем варианты…</p>}
          {command.message && (
            <Notice>
              {command.message}{' '}
              {!command.busy && (
                <button onClick={() => preview.refetch()}>Проверить варианты снова</button>
              )}
            </Notice>
          )}
        </Dialog>
      )}
      {undo && status.data?.undoId && (
        <Dialog title="Вернуть предыдущее блюдо?" onClose={() => setUndo(false)}>
          <p>
            Вернуть «{status.data.undoName}»? Список покупок и резервы пересчитаются. Уже купленные
            продукты останутся в запасах.
          </p>
          <button
            className="button primary"
            disabled={command.busy || !command.online || status.isFetching}
            onClick={async () => {
              if (
                await command.run(`${base}/${status.data!.undoId}/undo`, {
                  expectedRevision: status.data!.revision,
                })
              )
                setUndo(false);
            }}
          >
            Вернуть предыдущее блюдо
          </button>
          {command.message && <Notice>{command.message}</Notice>}
        </Dialog>
      )}
    </div>
  );
}
