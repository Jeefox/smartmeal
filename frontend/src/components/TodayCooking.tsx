import { useState } from 'react';
import { Link } from 'react-router';
import { useSession } from '../session';
import { usePantryCommand } from '../pantry';
import {
  readBatchPreference,
  saveBatchPreference,
  useTodayCooking,
  batchSuggestions,
  candidateRefs,
} from '../today-cooking';
import type { BatchPreference } from '../today-cooking';
import { labelDate, number } from '../domain';
import { Notice } from './ui';
const weekdays = ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'];
export default function TodayCooking({ date }: { date: string }) {
  const { profile } = useSession(),
    q = useTodayCooking(date),
    command = usePantryCommand();
  const [preference, setPreference] = useState(() => readBatchPreference(profile!.userId));
  const [chosen, setChosen] = useState(''),
    [useBy, setUseBy] = useState(''),
    [weights, setWeights] = useState<Record<string, string>>({});
  const update = (patch: Partial<BatchPreference>) => {
    const next = { ...preference, ...patch };
    setPreference(next);
    saveBatchPreference(profile!.userId, next);
    setChosen('');
  };
  const view = q.data,
    busy = command.busy || !command.online || q.isFetching;
  const planned = view?.batches.filter((b) => b.status === 'PLANNED' && b.cookOn === date) || [];
  const suggestions = view ? batchSuggestions(view, date, preference) : [];
  const chosenGroup = suggestions.find((g) => g.fingerprint === chosen);
  const existingForToday = view?.batches.some(
    (b) =>
      b.status === 'COOKED' && b.allocations.some((a) => a.date === date && a.status === 'ACTIVE'),
  );
  return (
    <section
      className={`today-cooking panel ${!preference.enabled && !planned.length ? 'today-cooking-off' : ''}`}
      aria-label="Готовка сегодня"
    >
      <div className="today-cooking-heading">
        <h2>{preference.enabled || planned.length ? 'Что приготовить' : 'Готовка'}</h2>
        <label className="check">
          <input
            type="checkbox"
            checked={preference.enabled}
            onChange={(e) => update({ enabled: e.target.checked })}
          />
          Готовить с запасом
        </label>
      </div>
      {!preference.enabled && !planned.length && existingForToday && (
        <p className="caption">
          {existingForToday
            ? 'Часть еды уже готова — она отмечена в блюдах ниже.'
            : 'Готовьте только на сегодня. Общие партии — по желанию.'}
        </p>
      )}
      {preference.enabled && (
        <details>
          <summary>Когда и на сколько дней</summary>
          <label>
            Готовить на
            <select
              value={preference.days}
              onChange={(e) => update({ days: Number(e.target.value) as 2 | 3 })}
            >
              <option value={2}>2 дня</option>
              <option value={3}>3 дня</option>
            </select>
          </label>
          <fieldset>
            <legend>Удобные дни готовки</legend>
            {weekdays.map((name, n) => (
              <label className="check" key={n}>
                <input
                  type="checkbox"
                  checked={preference.weekdays.includes(n)}
                  disabled={preference.weekdays.length === 1 && preference.weekdays[0] === n}
                  onChange={(e) =>
                    update({
                      weekdays: e.target.checked
                        ? [...preference.weekdays, n]
                        : preference.weekdays.filter((x) => x !== n),
                    })
                  }
                />
                {name}
              </label>
            ))}
          </fieldset>
          <p className="caption">
            Предложения только в выбранные дни. Срок хранения указываете отдельно для каждой партии.
          </p>
        </details>
      )}
      {q.isError && (
        <p className="caption">
          Не удалось проверить готовые остатки.{' '}
          <button className="text-button" onClick={() => q.refetch()}>
            Повторить
          </button>
        </p>
      )}
      {command.message && <Notice>{command.message}</Notice>}
      {preference.enabled && view && view.drafts.length > 0 && (
        <div>
          <p>Чтобы готовить сразу на несколько дней, подтвердите сохранённое меню периода.</p>
          <button
            className="button secondary"
            disabled={busy || date < view.today}
            onClick={() =>
              command.run('/me/cooking-batches/confirm-period', {
                expectedRevision: view.revision,
                from: date,
                to: view.drafts[view.drafts.length - 1].date,
              })
            }
          >
            Подтвердить меню для готовки
          </button>
        </div>
      )}
      {planned.map((b) => (
        <article className="today-batch" key={b.id} id={`batch-${b.id}`}>
          <h3>
            {b.name} · на{' '}
            {new Set(b.allocations.filter((a) => a.status === 'ACTIVE').map((a) => a.date)).size}{' '}
            дн.
          </h3>
          <p>На всех ≈ {number(b.estimatedYieldG)} г готового.</p>
          <details>
            <summary>Рецепт и ингредиенты на всю партию</summary>
            <ul>
              {b.ingredients.map((i, n) => (
                <li key={n}>
                  {i.name} — {number(i.quantityG)} г
                </li>
              ))}
            </ul>
            <p style={{ whiteSpace: 'pre-line' }}>{b.instructions}</p>
          </details>
          {b.expired ? (
            <p>
              Указанная дата хранения истекла. <Link to="/cooking">Отменить план партии</Link>
            </p>
          ) : (
            <div className="today-batch-action">
              <label>
                Вес готовой партии, г
                <input
                  type="number"
                  min="0.01"
                  step="0.01"
                  max="999999.99"
                  value={weights[b.id] || ''}
                  onChange={(e) => setWeights((w) => ({ ...w, [b.id]: e.target.value }))}
                />
              </label>
              <button
                className="button primary"
                disabled={
                  busy ||
                  b.cookOn > view!.today ||
                  !Number.isFinite(Number(weights[b.id])) ||
                  Number(weights[b.id]) <= 0
                }
                onClick={() =>
                  command.run(`/me/cooking-batches/${b.id}/prepare`, {
                    expectedRevision: view!.revision,
                    actualYieldG: Number(weights[b.id]),
                  })
                }
              >
                Приготовил партию
              </button>
            </div>
          )}
        </article>
      ))}
      {preference.enabled &&
        suggestions.slice(0, 3).map((g) => (
          <div className="today-batch-suggestion" key={g.fingerprint}>
            <button
              className="button secondary"
              disabled={busy}
              onClick={() => {
                setChosen(g.fingerprint);
                setUseBy('');
              }}
            >
              Приготовить {g.name.toLowerCase()} сразу на{' '}
              {new Set(g.entries.map((c) => c.date)).size} дня
            </button>
          </div>
        ))}
      {chosenGroup && (
        <form
          className="today-batch-confirm"
          onSubmit={async (e) => {
            e.preventDefault();
            if (
              await command.run('/me/cooking-batches', {
                expectedRevision: view!.revision,
                cookOn: date,
                useBy,
                entries: candidateRefs(chosenGroup.entries),
              })
            )
              setChosen('');
          }}
        >
          <p>
            {chosenGroup.name}: на всех ≈ {number(chosenGroup.cookedG)} г, до{' '}
            {labelDate(chosenGroup.entries[chosenGroup.entries.length - 1].date)}.
          </p>
          <label>
            Хранить до
            <input
              type="date"
              min={chosenGroup.entries[chosenGroup.entries.length - 1].date}
              value={useBy}
              onChange={(e) => setUseBy(e.target.value)}
              required
            />
          </label>
          <p className="caption">
            Выберите подходящий срок с учётом блюда и условий хранения. Продукты спишутся после
            подтверждения приготовления.
          </p>
          <button
            className="button primary"
            disabled={busy || !useBy || chosenGroup.entries.some((c) => c.date > useBy)}
          >
            Запланировать партию
          </button>
          <button className="text-button" type="button" onClick={() => setChosen('')}>
            Отмена
          </button>
        </form>
      )}
      {preference.enabled &&
        view &&
        !planned.length &&
        !suggestions.length &&
        !view.drafts.length && (
          <p className="caption">
            Сегодня общей готовки не требуется: в ближайшие дни нет одинаковых свободных компонентов
            или выбран другой день готовки.
          </p>
        )}
      {(preference.enabled || planned.length > 0) && (
        <details>
          <summary>Все партии и готовые остатки</summary>
          <Link to="/cooking">Открыть подробный учёт готовой еды</Link>
        </details>
      )}
    </section>
  );
}
