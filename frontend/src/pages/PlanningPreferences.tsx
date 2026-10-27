import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { request, api } from '../api';
import { Loading, Notice, PageHeading } from '../components/ui';
import { useApp, useOnline } from '../state';
import { BackendPending } from '../components/ui';
import { useSession } from '../session';

type Choice = { id: number; name: string };
type Preferences = { allergenIds: number[]; excludedProductIds: number[]; equipmentIds: number[] };
type View = { values: Preferences; excludedProducts: Choice[] };
export default function PlanningPreferences() {
  const { mode } = useApp();
  return mode === 'api' ? (
    <LivePlanningPreferences />
  ) : (
    <BackendPending
      title="Ограничения доступны в режиме API"
      description="Включите SmartMeal API для изменения настроек вашего аккаунта."
    />
  );
}
function LivePlanningPreferences() {
  const { profile } = useSession();
  const userId = profile?.userId;
  const online = useOnline(),
    client = useQueryClient();
  const query = useQuery({
    queryKey: ['planning-preferences', userId],
    queryFn: ({ signal }) => request<View>('/me/planning-preferences', { signal }),
  });
  const catalogues = useQuery({
    queryKey: ['planning-restrictions'],
    queryFn: ({ signal }) =>
      request<{ allergens: Choice[]; equipment: Choice[] }>('/planning-restrictions', { signal }),
  });
  const [draft, setDraft] = useState<Preferences | null>(null),
    [names, setNames] = useState<Choice[]>([]),
    [text, setText] = useState(''),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState('');
  const products = useQuery({
    queryKey: ['excluded-product-search', text],
    queryFn: ({ signal }) => api.products(new URLSearchParams({ q: text, size: '12' }), signal),
  });
  useEffect(() => {
    if (query.data) {
      setDraft(query.data.values);
      setNames(query.data.excludedProducts);
    }
  }, [query.data]);
  function toggle(field: keyof Preferences, id: number, on: boolean) {
    setDraft((current) =>
      current
        ? {
            ...current,
            [field]: on ? [...current[field], id] : current[field].filter((x) => x !== id),
          }
        : current,
    );
  }
  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (busy || !online) return;
    setBusy(true);
    setMessage('');
    try {
      await request('/me/planning-preferences', {
        method: 'PUT',
        body: JSON.stringify({ ...draft, equipmentIds: null }),
      });
      await client.invalidateQueries({ queryKey: ['planning-preferences', userId] });
      setMessage('Ограничения сохранены.');
    } catch (e) {
      setMessage(e instanceof Error ? e.message : 'Не удалось сохранить');
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <PageHeading
        title="Ограничения для подбора"
        description="Исключим неподходящие продукты из нового меню."
      />
      {(query.isPending || catalogues.isPending) && <Loading />}
      {(query.error || catalogues.error) && <Notice>Не удалось загрузить настройки.</Notice>}
      {message && <Notice>{message}</Notice>}
      {draft && !query.isPending && (
        <form className="panel period-options" onSubmit={save}>
          <fieldset>
            <legend>Аллергии</legend>
            <div className="period-checks">
              {catalogues.data?.allergens.map((choice) => (
                <label key={choice.id}>
                  <input
                    type="checkbox"
                    checked={draft.allergenIds.includes(choice.id)}
                    onChange={(e) => toggle('allergenIds', choice.id, e.target.checked)}
                  />
                  {choice.name}
                </label>
              ))}
            </div>
          </fieldset>
          <Link className="text-link" to="/profile/kitchen">
            Кухонная техника →
          </Link>
          <fieldset>
            <legend>Исключённые продукты</legend>
            <label>
              Найти продукт
              <input value={text} onChange={(e) => setText(e.target.value)} />
            </label>
            <div className="period-checks">
              {[...names, ...(products.data?.content || [])]
                .filter((choice, i, all) => all.findIndex((x) => x.id === choice.id) === i)
                .map((choice) => (
                  <label key={choice.id}>
                    <input
                      type="checkbox"
                      checked={draft.excludedProductIds.includes(choice.id)}
                      onChange={(e) => {
                        toggle('excludedProductIds', choice.id, e.target.checked);
                        if (e.target.checked)
                          setNames((current) =>
                            current.some((x) => x.id === choice.id)
                              ? current
                              : [...current, choice],
                          );
                      }}
                    />
                    {choice.name}
                  </label>
                ))}
            </div>
          </fieldset>
          <button className="button primary" disabled={busy || !online}>
            Сохранить ограничения
          </button>
        </form>
      )}
      <Link to="/menu-generation">К подбору меню</Link>
    </>
  );
}
