import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../api';
import { useOnline } from '../state';
import type { PortionProfile } from '../balanced-meals';
import { roleLabels } from '../balanced-meals';
import { Loading, Notice } from './ui';
type Fiber = {
  fiberPer100g: number | null;
  source: string | null;
  quality: 'UNKNOWN' | 'REFERENCE' | 'LABEL';
};
export default function PortionMetadata({ id, product }: { id: string; product: boolean }) {
  const path = product ? `/products/${id}/fiber` : `/recipes/${id}/portion-profile`,
    online = useOnline();
  const q = useQuery({
    queryKey: ['portion-metadata', path],
    queryFn: ({ signal }) => request<PortionProfile | Fiber>(path, { signal }),
    retry: false,
  });
  const [draft, setDraft] = useState<PortionProfile | Fiber | null>(null),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState('');
  useEffect(() => {
    if (q.data) setDraft(q.data);
  }, [q.data]);
  function change(p: object) {
    if (draft) setDraft({ ...draft, ...p });
  }
  return (
    <details className="panel balanced-metadata">
      <summary>{product ? 'Данные клетчатки' : 'Размер порции и роль блюда'}</summary>
      {q.isPending ? (
        <Loading />
      ) : q.isError ? (
        <Notice tone="warning">
          {q.error.message}
          <button className="button" onClick={() => q.refetch()}>
            Повторить загрузку
          </button>
        </Notice>
      ) : (
        draft && (
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              if (busy || !online) return;
              setBusy(true);
              try {
                await request(path, {
                  method: 'PUT',
                  body: JSON.stringify(draft),
                });
                setMessage('Данные сохранены. Существующие меню не пересчитываются.');
                await q.refetch();
              } catch (err) {
                setMessage((err as Error).message);
              } finally {
                setBusy(false);
              }
            }}
          >
            {product ? (
              <>
                <label>
                  Клетчатка на 100 г съедобного продукта
                  <input
                    type="number"
                    min="0"
                    max="100"
                    step="0.001"
                    value={(draft as Fiber).fiberPer100g ?? ''}
                    disabled={busy}
                    onChange={(e) =>
                      change({
                        fiberPer100g: e.target.value ? Number(e.target.value) : null,
                      })
                    }
                  />
                </label>
                <p className="caption">
                  Пустое поле — неизвестно. Ноль вводите только при подтверждённых данных.
                </p>
                <label>
                  Качество данных
                  <select
                    value={(draft as Fiber).quality}
                    disabled={busy}
                    onChange={(e) =>
                      change({
                        quality: e.target.value,
                        ...(e.target.value === 'UNKNOWN' ? { fiberPer100g: null } : {}),
                      })
                    }
                  >
                    <option value="UNKNOWN">Нет данных</option>
                    <option value="REFERENCE">Справочный состав</option>
                    <option value="LABEL">Этикетка или измерение</option>
                  </select>
                </label>
              </>
            ) : (
              <>
                <label>
                  Роль блюда
                  <select
                    value={(draft as PortionProfile).role}
                    disabled={busy}
                    onChange={(e) => change({ role: e.target.value })}
                  >
                    {Object.entries(roleLabels).map(([role, label]) => (
                      <option key={role} value={role}>
                        {label}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  Выход всего готового рецепта, г
                  <input
                    type="number"
                    min="0.01"
                    step="0.01"
                    value={(draft as PortionProfile).cookedYieldG ?? ''}
                    disabled={busy}
                    onChange={(e) =>
                      change({
                        cookedYieldG: e.target.value ? Number(e.target.value) : null,
                      })
                    }
                  />
                </label>
                <label>
                  Как получен выход
                  <select
                    value={(draft as PortionProfile).yieldQuality}
                    disabled={busy}
                    onChange={(e) =>
                      change({
                        yieldQuality: e.target.value,
                        ...(e.target.value === 'UNKNOWN' ? { cookedYieldG: null } : {}),
                      })
                    }
                  >
                    <option value="UNKNOWN">Неизвестен</option>
                    <option value="ESTIMATED">Приблизительная оценка</option>
                    <option value="AUTHOR_REPORTED">Указан автором</option>
                    <option value="MEASURED">Взвешен после приготовления</option>
                  </select>
                </label>
                {(['minG', 'maxG', 'stepG'] as const).map((field, i) => (
                  <label key={field}>
                    {
                      [
                        'Минимальное количество на человека, г',
                        'Максимальное количество на человека, г',
                        'Шаг количества, г',
                      ][i]
                    }
                    <input
                      type="number"
                      min={field === 'stepG' ? 10 : 1}
                      max="1200"
                      value={(draft as PortionProfile)[field]}
                      disabled={busy}
                      onChange={(e) => change({ [field]: Number(e.target.value) })}
                    />
                  </label>
                ))}
                <label className="check">
                  <input
                    type="checkbox"
                    checked={(draft as PortionProfile).containsStarch}
                    disabled={busy}
                    onChange={(e) => change({ containsStarch: e.target.checked })}
                  />
                  Уже содержит крахмалистый гарнир
                </label>
                <label className="check">
                  <input
                    type="checkbox"
                    checked={(draft as PortionProfile).containsVegetables}
                    disabled={busy}
                    onChange={(e) => change({ containsVegetables: e.target.checked })}
                  />
                  Уже содержит овощную часть
                </label>
              </>
            )}
            <label>
              Источник или основание данных
              <textarea
                maxLength={1000}
                value={draft.source || ''}
                disabled={busy}
                onChange={(e) => change({ source: e.target.value || null })}
              />
            </label>
            <button className="button primary" disabled={!online || busy}>
              Сохранить данные порции
            </button>
          </form>
        )
      )}
      {message && <Notice>{message}</Notice>}
    </details>
  );
}
