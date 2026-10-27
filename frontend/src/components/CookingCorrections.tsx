import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../api';
import { usePantryCommand } from '../pantry';
import { useSession } from '../session';
import { Notice } from './ui';
import { number } from '../domain';
import { validCookedWeight, type CookingChange } from '../cooking-corrections';

export function WeightCorrection({
  batchId,
  weight,
  revision,
}: {
  batchId: string;
  weight: number;
  revision: number;
}) {
  const [open, setOpen] = useState(false),
    [value, setValue] = useState(''),
    [reason, setReason] = useState('Ошибочно указал вес готовой партии');
  const command = usePantryCommand();
  return (
    <div className="cooking-correction">
      {command.message && <Notice>{command.message}</Notice>}
      {!open ? (
        <button
          className="text-button"
          onClick={() => {
            setValue(String(weight));
            setOpen(true);
          }}
        >
          Исправить вес партии
        </button>
      ) : (
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            if (
              validCookedWeight(value) &&
              reason.trim() &&
              (await command.run(`/me/cooking-batches/${batchId}/weight-corrections`, {
                expectedRevision: revision,
                actualYieldG: Number(value),
                reason,
              }))
            )
              setOpen(false);
          }}
        >
          <p>
            Сейчас вся партия: {number(weight)} г. Исправьте общий вес, включая уже съеденную часть.
            Вес порций пересчитается пропорционально; калории, сырые запасы и дата хранения не
            изменятся.
          </p>
          <label>
            Правильный вес всей партии, г
            <input
              type="number"
              min="0.01"
              max="999999.99"
              step="0.01"
              value={value}
              onChange={(e) => setValue(e.target.value)}
              required
            />
          </label>
          <label>
            Причина исправления
            <input
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={300}
              required
            />
          </label>
          <button
            className="button secondary"
            disabled={
              command.busy ||
              !command.online ||
              !validCookedWeight(value) ||
              Number(value) === weight ||
              !reason.trim()
            }
          >
            Сохранить правильный вес
          </button>
          <button
            type="button"
            className="text-button"
            disabled={command.busy}
            onClick={() => setOpen(false)}
          >
            Отмена
          </button>
        </form>
      )}
    </div>
  );
}
export function ConsumptionUndo({
  entryId,
  revision,
  available = true,
}: {
  entryId: string;
  revision: number;
  available?: boolean;
}) {
  const [open, setOpen] = useState(false),
    [reason, setReason] = useState('Ошибочно отметил «съедено»');
  const command = usePantryCommand();
  if (!available)
    return (
      <p className="caption">
        Партия уже списана. Автоматически вернуть съеденное в остатки нельзя.
      </p>
    );
  return (
    <div className="cooking-correction">
      {command.message && <Notice>{command.message}</Notice>}
      {!open ? (
        <button className="text-button" onClick={() => setOpen(true)}>
          Ошибочно отметил «съедено»
        </button>
      ) : (
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            if (
              reason.trim() &&
              (await command.run(`/me/cooking-batches/entries/${entryId}/consumption-reversal`, {
                expectedRevision: revision,
                reason,
              }))
            )
              setOpen(false);
          }}
        >
          <p>
            Вернуть готовую еду в остатки? Сырые продукты не возвращаются, дата хранения остаётся
            прежней. Для отменённого дня еда станет свободным остатком.
          </p>
          <label>
            Причина исправления
            <input
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={300}
              required
            />
          </label>
          <button
            className="button secondary"
            disabled={command.busy || !command.online || !reason.trim()}
          >
            Отменить отметку «съедено»
          </button>
          <button
            type="button"
            className="text-button"
            disabled={command.busy}
            onClick={() => setOpen(false)}
          >
            Назад
          </button>
        </form>
      )}
    </div>
  );
}
export function CorrectionHistory({ batchId }: { batchId: string }) {
  const [open, setOpen] = useState(false),
    { profile } = useSession();
  const q = useQuery({
    queryKey: ['cooking-corrections', profile?.userId, batchId],
    enabled: open && !!profile,
    queryFn: ({ signal }) =>
      request<CookingChange[]>(`/me/cooking-batches/${batchId}/corrections`, { signal }),
  });
  return (
    <details onToggle={(e) => setOpen(e.currentTarget.open)}>
      <summary>История исправлений</summary>
      {q.isPending && open && <p>Загружаем историю…</p>}
      {q.error && (
        <Notice>
          Не удалось загрузить историю. <button onClick={() => q.refetch()}>Повторить</button>
        </Notice>
      )}
      {q.data?.length === 0 && <p>Исправлений пока нет.</p>}
      <ul>
        {q.data?.map((c) => (
          <li key={c.id}>
            {c.kind === 'YIELD'
              ? `Вес партии: ${number(Number(c.before.actualYieldG))} → ${number(Number(c.after.actualYieldG))} г`
              : 'Отменена отметка «съедено»'}
            <p className="caption">
              {new Date(c.createdAt).toLocaleString('ru-RU')} · {c.reason}
            </p>
          </li>
        ))}
      </ul>
      {!!q.data?.length && <p className="caption">Последние 50 исправлений.</p>}
    </details>
  );
}
