import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../api';
import { useSession } from '../session';
import { useOnline } from '../state';
import type { MealPlanEntry } from '../mealplans';
import { Notice } from './ui';
type Feedback = { participantId: string; rating: string; revision: number };
const ratings = [
  ['TOO_LITTLE', 'Мало'],
  ['ENOUGH', 'Достаточно'],
  ['TOO_MUCH', 'Слишком много'],
] as const;
export default function PortionFeedback({ entry }: { entry: MealPlanEntry }) {
  const { profile } = useSession(),
    online = useOnline(),
    base = `/me/meal-plan-entries/${entry.id}/portion-feedback`,
    q = useQuery({
      queryKey: ['portion-feedback', profile?.userId, entry.id],
      queryFn: ({ signal }) => request<Feedback[]>(base, { signal }),
      retry: false,
    });
  const [busy, setBusy] = useState(false),
    [message, setMessage] = useState('');
  const people = entry.personPortions?.length
    ? entry.personPortions.map((p) => ({ id: p.participantId, name: p.name }))
    : [{ id: profile!.userId, name: 'Вы' }];
  return (
    <details className="portion-feedback">
      <summary>Хватило ли еды?</summary>
      <p className="caption">
        Оценка количества не меняет калорийность или сохранённое меню автоматически.
      </p>
      {q.isError ? (
        <Notice tone="warning">
          {q.error.message}
          <button className="button" onClick={() => q.refetch()}>
            Обновить оценки
          </button>
        </Notice>
      ) : (
        people.map((p) => (
          <fieldset key={p.id}>
            <legend>{p.name}</legend>
            {ratings.map(([rating, label]) => (
              <button
                className="button"
                type="button"
                key={rating}
                disabled={!online || busy || !q.data}
                aria-pressed={
                  q.data?.some((f) => f.participantId === p.id && f.rating === rating) || false
                }
                onClick={async () => {
                  setBusy(true);
                  setMessage('');
                  try {
                    const current = q.data?.find((f) => f.participantId === p.id);
                    await request(`${base}/${p.id}`, {
                      method: 'PUT',
                      body: JSON.stringify({
                        expectedRevision: current?.revision || 0,
                        rating,
                      }),
                    });
                    setMessage('Оценка сохранена. Цели питания не изменены.');
                    await q.refetch();
                  } catch (e) {
                    setMessage((e as Error).message);
                    await q.refetch();
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                {label}
              </button>
            ))}
          </fieldset>
        ))
      )}
      {message && <p role="status">{message}</p>}
    </details>
  );
}
