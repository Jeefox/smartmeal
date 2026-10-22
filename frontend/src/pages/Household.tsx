import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { householdApi } from '../household';
import type { HouseholdMember, MemberInput } from '../household';
import { api, request } from '../api';
import { useSession } from '../session';
import { useApp, useOnline } from '../state';
import { BackendPending, Dialog, Loading, Notice, PageHeading } from '../components/ui';
import { number } from '../domain';
const empty: MemberInput = {
  name: '',
  dailyCalorieTarget: 0,
  allergenIds: [],
  excludedProductIds: [],
};
export default function Household() {
  const { mode } = useApp();
  return mode === 'demo' ? (
    <BackendPending
      title="Семья доступна в режиме API"
      description="Профили семьи хранятся в вашем аккаунте."
    />
  ) : (
    <LiveHousehold />
  );
}
function LiveHousehold() {
  const { profile } = useSession(),
    online = useOnline(),
    client = useQueryClient();
  const key = ['household', profile?.userId];
  const query = useQuery({ queryKey: key, queryFn: ({ signal }) => householdApi.list(signal) });
  const restrictions = useQuery({
    queryKey: ['planning-restrictions'],
    queryFn: ({ signal }) =>
      request<{ allergens: { id: number; name: string }[] }>('/planning-restrictions', { signal }),
  });
  const [draft, setDraft] = useState<MemberInput | null>(null),
    [editing, setEditing] = useState<string>(),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState(''),
    [search, setSearch] = useState(''),
    [names, setNames] = useState<{ id: number; name: string }[]>([]),
    [removing, setRemoving] = useState<HouseholdMember | null>(null);
  const products = useQuery({
    queryKey: ['household-product-search', search],
    queryFn: ({ signal }) => api.products(new URLSearchParams({ q: search, size: '12' }), signal),
    enabled: !!draft && search.trim().length >= 2,
  });
  function open(m?: HouseholdMember) {
    setEditing(m?.id);
    setDraft(
      m
        ? {
            name: m.name,
            dailyCalorieTarget: m.dailyCalorieTarget,
            allergenIds: m.allergenIds,
            excludedProductIds: m.excludedProductIds,
          }
        : { ...empty },
    );
    setNames(m?.excludedProducts || []);
    setMessage('');
    setSearch('');
  }
  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (!draft || busy || !online) return;
    setBusy(true);
    setMessage('');
    try {
      await householdApi.save(draft, editing);
      await client.invalidateQueries({ queryKey: key });
      setDraft(null);
      setMessage('Профиль сохранён. Включите человека в новый подбор меню.');
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Не удалось сохранить');
    } finally {
      setBusy(false);
    }
  }
  function toggle(field: 'allergenIds' | 'excludedProductIds', id: number, on: boolean) {
    setDraft((d) =>
      d
        ? { ...d, [field]: on ? [...new Set([...d[field], id])] : d[field].filter((x) => x !== id) }
        : d,
    );
  }
  return (
    <>
      <PageHeading
        title="Семья и порции"
        description="Общие блюда и покупки, своя норма калорий и ограничения для каждого человека."
        actions={
          <button
            className="button primary"
            disabled={!online || busy || query.isPending || (query.data?.length || 0) >= 9}
            onClick={() => open()}
          >
            Добавить человека
          </button>
        }
      />
      <section className="panel household-owner">
        <h2>Вы</h2>
        <p>
          {profile?.dailyCalorieTarget
            ? `${number(profile.dailyCalorieTarget)} ккал в день`
            : 'Укажите суточную норму в своём профиле'}
        </p>
        <Link className="text-link" to="/profile">
          Мой профиль и норма калорий
        </Link>
        <Link className="text-link" to="/planning-preferences">
          Мои аллергии и исключения
        </Link>
      </section>
      {query.isPending && <Loading />}
      {query.error && (
        <Notice>
          <p>Не удалось загрузить семью.</p>
          <button className="button" onClick={() => query.refetch()}>
            Повторить
          </button>
        </Notice>
      )}
      {message && <Notice>{message}</Notice>}
      <div className="household-members">
        {query.data?.map((m) => (
          <article className="panel" key={m.id}>
            <h2>{m.name}</h2>
            <strong>{number(m.dailyCalorieTarget)} ккал в день</strong>
            <p>
              Аллергии: {m.allergenIds.length} · исключённые продукты: {m.excludedProductIds.length}
            </p>
            <div className="period-actions">
              <button className="button secondary" disabled={busy} onClick={() => open(m)}>
                Изменить профиль {m.name}
              </button>
              <button
                className="text-button"
                disabled={busy || !online}
                onClick={() => setRemoving(m)}
              >
                Убрать из семьи: {m.name}
              </button>
            </div>
          </article>
        ))}
      </div>
      {!query.isPending && !query.error && !query.data?.length && (
        <p>
          Добавьте близких, затем отметьте их в настройках подбора. Выбранные люди получают одни
          блюда, но разные порции в пропорции к своим нормам калорий.
        </p>
      )}
      <Link className="button primary" to="/menu-generation">
        Подобрать общее меню
      </Link>
      <p className="caption">
        Ограничения всех выбранных людей объединяются: блюдо должно подходить каждому. Оборудование
        и домашние запасы берутся из вашего аккаунта. Бюджет указывается на всех.
      </p>
      {draft && (
        <form className="panel household-form" onSubmit={save}>
          <h2>{editing ? 'Изменить профиль' : 'Новый человек'}</h2>
          <label>
            Имя
            <input
              autoFocus
              required
              maxLength={80}
              value={draft.name}
              onChange={(e) => setDraft({ ...draft, name: e.target.value })}
            />
          </label>
          <label>
            Суточная норма, ккал
            <input
              required
              type="number"
              min={500}
              max={10000}
              step={1}
              value={draft.dailyCalorieTarget || ''}
              onChange={(e) => setDraft({ ...draft, dailyCalorieTarget: Number(e.target.value) })}
            />
          </label>
          <fieldset>
            <legend>Аллергии этого человека</legend>
            {restrictions.isPending && <Loading />}
            {restrictions.error && (
              <Notice>
                Не удалось загрузить аллергены. Повторите загрузку перед сохранением.
                <button
                  type="button"
                  className="text-button"
                  onClick={() => restrictions.refetch()}
                >
                  Повторить загрузку аллергенов
                </button>
              </Notice>
            )}
            <div className="period-checks">
              {restrictions.data?.allergens.map((a) => (
                <label key={a.id}>
                  <input
                    type="checkbox"
                    checked={draft.allergenIds.includes(a.id)}
                    onChange={(e) => toggle('allergenIds', a.id, e.target.checked)}
                  />
                  {a.name}
                </label>
              ))}
            </div>
          </fieldset>
          <fieldset>
            <legend>Исключённые продукты</legend>
            {draft.excludedProductIds.map((id) => (
              <label key={id}>
                <input
                  type="checkbox"
                  checked
                  onChange={() => toggle('excludedProductIds', id, false)}
                />
                {names.find((p) => p.id === id)?.name || `Продукт №${id}`}
              </label>
            ))}
            <label>
              Найти продукт для исключения
              <input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Не менее двух букв"
              />
            </label>
            {products.error && <p role="alert">Не удалось загрузить продукты.</p>}
            {products.data?.content
              .filter((p) => !draft.excludedProductIds.includes(p.id))
              .map((p) => (
                <label key={p.id}>
                  <input
                    type="checkbox"
                    checked={false}
                    onChange={() => {
                      setNames((n) => [...n, p]);
                      toggle('excludedProductIds', p.id, true);
                    }}
                  />
                  {p.name}
                </label>
              ))}
          </fieldset>
          <div className="period-actions">
            <button
              className="button primary"
              disabled={busy || !online || restrictions.isPending || !!restrictions.error}
            >
              {busy ? 'Сохраняем…' : 'Сохранить профиль'}
            </button>
            <button
              type="button"
              className="button secondary"
              disabled={busy}
              onClick={() => setDraft(null)}
            >
              Отмена
            </button>
          </div>
        </form>
      )}
      {removing && (
        <Dialog title="Убрать человека из семьи?" onClose={() => !busy && setRemoving(null)}>
          <p>
            {removing.name} больше не будет доступен для новых подборов. Сохранённые меню и порции
            останутся.
          </p>
          <button
            className="button"
            disabled={busy || !online}
            onClick={async () => {
              setBusy(true);
              try {
                await householdApi.remove(removing.id);
                await client.invalidateQueries({ queryKey: key });
                setRemoving(null);
              } catch (e) {
                setMessage(e instanceof Error ? e.message : 'Не удалось убрать профиль');
              } finally {
                setBusy(false);
              }
            }}
          >
            Убрать профиль
          </button>
        </Dialog>
      )}
    </>
  );
}
