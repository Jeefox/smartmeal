import {
  WeightCorrection,
  ConsumptionUndo,
  CorrectionHistory,
} from '../components/CookingCorrections';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { cookingApi, cookingGroups } from '../cooking';
import type { CookingBatch, CookingCandidate } from '../cooking';
import { usePantryCommand } from '../pantry';
import { useSession } from '../session';
import { useApp } from '../state';
import { dateKey, shiftDate, number, labelDate } from '../domain';
import { BackendPending, Loading, Notice, PageHeading } from '../components/ui';
import { mealLabels } from '../mealplans';
export default function Cooking() {
  const { mode } = useApp();
  return mode === 'api' ? (
    <LiveCooking />
  ) : (
    <BackendPending
      title="Готовка доступна через API"
      description="Войдите в аккаунт для работы с меню и остатками готовой еды."
    />
  );
}
function LiveCooking() {
  const { profile } = useSession(),
    command = usePantryCommand();
  const [from, setFrom] = useState(dateKey(new Date())),
    [to, setTo] = useState(shiftDate(dateKey(new Date()), 6));
  const q = useQuery({
    queryKey: ['cooking', profile?.userId, from, to],
    queryFn: ({ signal }) => cookingApi.view(from, to, signal),
    enabled: !!profile && !!from && !!to,
  });
  const [storage, setStorage] = useState(''),
    [selected, setSelected] = useState<Record<string, boolean>>({}),
    [outputs, setOutputs] = useState<Record<string, string>>({}),
    [confirmClose, setConfirmClose] = useState<string | null>(null);
  const view = q.data,
    busy = command.busy || !command.online;
  const key = (c: CookingCandidate) => c.ref.entryId + ':' + c.ref.componentId;
  const picked = (entries: CookingCandidate[]) =>
    entries.filter((e) => selected[key(e)] ?? e.date <= shiftDate(from, 1));
  async function run(path: string, payload: Record<string, unknown>) {
    if (!view) return;
    await command.run(path, { ...payload, expectedRevision: view.revision });
  }
  function batch(b: CookingBatch) {
    const active = b.allocations.filter((a) => a.status === 'ACTIVE');
    return (
      <article className="panel" key={b.id}>
        <h3>{b.name}</h3>
        <p className="caption">
          Количество на всех участников вместе; личные количества сохранены в меню.
        </p>
        <p>
          {b.status === 'PLANNED'
            ? `Приготовить ${labelDate(b.cookOn)}`
            : b.status === 'COOKED'
              ? `Готово · осталось ${number(b.remainingG)} г · свободно ${number(b.freeG)} г`
              : b.status === 'CANCELLED'
                ? 'План отменён'
                : 'Остаток списан'}
        </p>
        <p className="caption">
          Указанная дата хранения: {labelDate(b.useBy)}
          {b.expired ? ' · истекла' : ''}. Эта дата задаётся вами с учётом блюда и условий хранения.
        </p>
        <details>
          <summary>Что и сколько приготовить</summary>
          <p className="caption">
            Приблизительный выход: {number(b.estimatedYieldG)} г. Сырые ингредиенты, съедобная
            часть:
          </p>
          <ul>
            {b.ingredients.map((i, n) => (
              <li key={n}>
                {i.name}: {number(i.quantityG)} г
              </li>
            ))}
          </ul>
          <p style={{ whiteSpace: 'pre-line' }}>{b.instructions}</p>
          <Link to={`/recipes/${b.recipeId}`}>Открыть рецепт</Link>
        </details>
        <ul>
          {b.allocations
            .filter((a) => a.status !== 'RELEASED')
            .map((a) => (
              <li key={a.id}>
                {labelDate(a.date)} ·{' '}
                {mealLabels[a.mealType as keyof typeof mealLabels] || a.mealType} ·{' '}
                {number(a.cookedG)} г{a.status === 'CONSUMED' ? ' · съедено' : ' · отложено'}
              </li>
            ))}
        </ul>
        {b.status === 'PLANNED' && !b.expired && b.cookOn <= view!.today && (
          <div>
            <label>
              Фактический вес готовой партии, г
              <input
                type="number"
                min="0.01"
                max="999999.99"
                step="0.01"
                value={outputs[b.id] ?? ''}
                onChange={(e) => setOutputs((o) => ({ ...o, [b.id]: e.target.value }))}
              />
            </label>
            <button
              className="button primary"
              disabled={busy || !outputs[b.id] || Number(outputs[b.id]) <= 0}
              onClick={() =>
                run(`/me/cooking-batches/${b.id}/prepare`, { actualYieldG: Number(outputs[b.id]) })
              }
            >
              Приготовил партию · списать сырые продукты
            </button>
          </div>
        )}
        {b.status === 'COOKED' &&
          !b.expired &&
          [
            ...new Map(
              active.filter((a) => a.date <= view!.today).map((a) => [a.entryId, a]),
            ).values(),
          ].map((a) => (
            <div key={a.id}>
              <Link to={`/?date=${a.date}`}>
                Подготовить остальные компоненты {labelDate(a.date)}
              </Link>
              <button
                className="button secondary"
                disabled={busy || !a.mealReady}
                onClick={() => run(`/me/cooking-batches/entries/${a.entryId}/consume`, {})}
              >
                Съедено · {labelDate(a.date)} {mealLabels[a.mealType as keyof typeof mealLabels]}
              </button>
            </div>
          ))}
        {b.status === 'COOKED' && !b.expired && b.freeG > 0 && (
          <div>
            <p>Свободный остаток можно отложить для того же рецепта:</p>
            {view!.candidates
              .filter((c) => c.fingerprint === b.fingerprint && c.date <= b.useBy)
              .map((c) => (
                <button
                  className="button secondary"
                  key={key(c)}
                  disabled={busy}
                  onClick={() => run(`/me/cooking-batches/${b.id}/assign`, { entries: [c.ref] })}
                >
                  Использовать {labelDate(c.date)} · {number(c.cookedG)} г
                </button>
              ))}
          </div>
        )}
        <details>
          <summary>Исправить ошибку</summary>
          {b.status === 'COOKED' && b.actualYieldG != null && (
            <WeightCorrection batchId={b.id} weight={b.actualYieldG} revision={view!.revision} />
          )}
          {[
            ...new Set(b.allocations.filter((a) => a.status === 'CONSUMED').map((a) => a.entryId)),
          ].map((id) => (
            <ConsumptionUndo
              key={id}
              entryId={id}
              revision={view!.revision}
              available={view!.batches
                .filter((other) =>
                  other.allocations.some((a) => a.entryId === id && a.status === 'CONSUMED'),
                )
                .every((other) => other.status === 'COOKED')}
            />
          ))}
          <CorrectionHistory batchId={b.id} />
        </details>
        {(b.status === 'PLANNED' || b.status === 'COOKED') &&
          (confirmClose === b.id ? (
            <div role="alert">
              <p>
                {b.status === 'PLANNED'
                  ? 'Отменить общую готовку? Блюда снова будут готовиться отдельно.'
                  : 'Списать весь оставшийся готовый остаток? Сырые продукты не вернутся, будущим блюдам потребуется новое приготовление.'}
              </p>
              <button
                className="button secondary"
                disabled={busy}
                onClick={async () => {
                  await run(`/me/cooking-batches/${b.id}/discard`, {});
                  setConfirmClose(null);
                }}
              >
                Подтвердить
              </button>
              <button className="text-button" disabled={busy} onClick={() => setConfirmClose(null)}>
                Назад
              </button>
            </div>
          ) : (
            <button className="text-button" disabled={busy} onClick={() => setConfirmClose(b.id)}>
              {b.status === 'PLANNED' ? 'Отменить план партии' : 'Списать готовый остаток'}
            </button>
          ))}
      </article>
    );
  }
  return (
    <div className="cooking-page">
      <PageHeading
        title="Готовка на несколько дней"
        description="Общие партии для сохранённого меню. Приготовление списывает сырые продукты один раз, отметка «съедено» уменьшает остаток готовой еды."
      />
      <div className="panel cooking-range">
        <label>
          Меню с<input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label>
          По
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
        <Link to="/">Меню и приготовление отдельных блюд</Link>
        <Link to="/pantry">Сырые запасы</Link>
      </div>
      {q.isPending && <Loading />}
      {q.error && (
        <Notice>
          Не удалось загрузить готовку. <button onClick={() => q.refetch()}>Повторить</button>
        </Notice>
      )}
      {command.message && <Notice>{command.message}</Notice>}
      {view && (
        <>
          {view.drafts.length > 0 && (
            <section className="panel">
              <h2>Подтвердить сохранённое меню</h2>
              <p>
                Дней в черновике: {view.drafts.length}. Подтверждение зафиксирует меню и
                зарезервирует сырые продукты; списания пока не будет.
              </p>
              <button
                className="button primary"
                disabled={busy}
                onClick={() => run('/me/cooking-batches/confirm-period', { from, to })}
              >
                Подтвердить меню периода для готовки
              </button>
            </section>
          )}
          <section>
            <h2>Сегодня готовим и используем остатки</h2>
            {view.batches
              .filter(
                (b) => b.status === 'COOKED' || (b.status === 'PLANNED' && b.cookOn <= view.today),
              )
              .map(batch)}
            {!view.batches.some(
              (b) => b.status === 'COOKED' || (b.status === 'PLANNED' && b.cookOn <= view.today),
            ) && <p>На сегодня общих партий нет. Выберите компоненты ниже.</p>}
          </section>
          <section className="panel">
            <h2>Объединить приготовление</h2>
            <p>
              Выберите одинаковые компоненты на разные дни. По умолчанию отмечены ближайшие два дня;
              это выбор планирования, а не рекомендация срока хранения.
            </p>
            <label>
              Хранить приготовленную партию до
              <input
                type="date"
                min={from}
                value={storage}
                onChange={(e) => setStorage(e.target.value)}
              />
            </label>
            <p className="caption">
              Укажите подходящую дату с учётом конкретного блюда и условий хранения. Все выбранные
              приёмы пищи должны быть до этой даты.
            </p>
            {cookingGroups(view.candidates).map((g) => (
              <article className="panel" key={g.fingerprint}>
                <h3>{g.name}</h3>
                {g.entries.map((c) => (
                  <label className="check" key={key(c)}>
                    <input
                      type="checkbox"
                      disabled={busy}
                      checked={selected[key(c)] ?? c.date <= shiftDate(from, 1)}
                      onChange={(e) => setSelected((s) => ({ ...s, [key(c)]: e.target.checked }))}
                    />
                    {labelDate(c.date)} · {mealLabels[c.mealType as keyof typeof mealLabels]} · ≈{' '}
                    {number(c.cookedG)} г
                  </label>
                ))}
                <p>
                  Вся партия: ≈ {number(picked(g.entries).reduce((sum, c) => sum + c.cookedG, 0))} г
                  готового.
                </p>
                <button
                  className="button secondary"
                  disabled={
                    busy ||
                    !storage ||
                    !picked(g.entries).length ||
                    picked(g.entries).some((c) => c.date < from || c.date > storage)
                  }
                  onClick={() =>
                    run('/me/cooking-batches', {
                      cookOn: from,
                      useBy: storage,
                      entries: picked(g.entries).map((c) => c.ref),
                    })
                  }
                >
                  Запланировать общую партию
                </button>
              </article>
            ))}
            {!view.candidates.length && (
              <p>
                Нет свободных компонентов подтверждённого меню. Подтвердите сохранённые дни или
                создайте меню. Для старых рецептов также нужен выход готового блюда.
              </p>
            )}
          </section>
          {view.batches.some((b) => b.status === 'PLANNED' && b.cookOn > view.today) && (
            <section>
              <h2>Готовка позже</h2>
              {view.batches
                .filter((b) => b.status === 'PLANNED' && b.cookOn > view.today)
                .map(batch)}
            </section>
          )}
        </>
      )}
    </div>
  );
}
