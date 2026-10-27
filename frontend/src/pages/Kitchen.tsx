import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { Check, CookingPot, Microwave, Flame, Wind, Blender } from 'lucide-react';
import { kitchenApi, kitchenKey, kitchenChoices, toggleKitchen } from '../kitchen';
import { safeNext, useSession } from '../session';
import { useOnline } from '../state';
import { Loading, ErrorState, PageHeading } from '../components/ui';

export default function KitchenPage({ initial = false }: { initial?: boolean }) {
  const session = useSession(),
    user = session.profile!.userId;
  const query = useQuery({
    queryKey: kitchenKey(user),
    queryFn: ({ signal }) => kitchenApi.get(signal),
    staleTime: 0,
  });
  const [draft, setDraft] = useState<number[] | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState('');
  const [params] = useSearchParams();
  const navigate = useNavigate(),
    client = useQueryClient(),
    online = useOnline();
  const raw = safeNext(params.get('next'));
  const next =
    raw.startsWith('/setup/') || raw.startsWith('/profile/kitchen') ? '/menu-generation' : raw;
  useEffect(() => {
    if (query.data && draft === null) setDraft(query.data.equipmentIds);
  }, [query.data, draft]);
  useEffect(() => {
    if (query.data?.configured && initial && session.profile?.equipmentConfigured === false)
      session.saveProfile({ ...session.profile, equipmentConfigured: true });
  }, [query.data?.configured, initial, session]);
  if (initial && query.data?.configured && session.profile?.equipmentConfigured !== false)
    return <Navigate to={next} replace />;
  async function save() {
    if (busy || !online || draft === null) return;
    setBusy(true);
    setError('');
    try {
      const value = await kitchenApi.save(draft);
      client.setQueryData(kitchenKey(user), value);
      await client.invalidateQueries({ queryKey: ['planning-preferences', user] });
      session.saveProfile({ ...session.profile!, equipmentConfigured: true });
      navigate(initial ? next : '/profile', { replace: true });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось сохранить выбор.');
    } finally {
      setBusy(false);
    }
  }
  const choices = kitchenChoices(query.data?.available || []);
  const icons: Record<string, typeof Flame> = {
    STOVE: CookingPot,
    OVEN: Flame,
    MICROWAVE: Microwave,
    AIR_FRYER: Wind,
    BLENDER: Blender,
  };
  return (
    <>
      <PageHeading
        title="Моя кухня"
        description={
          initial
            ? 'Что есть у вас дома? Подберём блюда для вашей техники.'
            : 'Обновите технику для следующих подборов меню.'
        }
      />
      {query.isPending ? (
        <Loading />
      ) : query.error ? (
        <ErrorState error={query.error} retry={() => query.refetch()} />
      ) : (
        draft && (
          <section className="panel kitchen-panel">
            <div className="kitchen-grid" aria-label="Техника на кухне">
              {choices.map((choice) => {
                const Icon = icons[choice.key] || CookingPot,
                  selected = choice.ids.some((id) => draft.includes(id));
                return (
                  <label
                    key={choice.key}
                    className={`kitchen-choice ${selected ? 'selected' : ''}`}
                  >
                    <input
                      type="checkbox"
                      checked={selected}
                      onChange={() => setDraft(toggleKitchen(draft, choice.ids))}
                      disabled={busy}
                    />
                    <Icon size={28} aria-hidden="true" />
                    <span>
                      <strong>{choice.name}</strong>
                      {choice.description && <small>{choice.description}</small>}
                    </span>
                    {selected && <Check size={18} aria-hidden="true" />}
                  </label>
                );
              })}
            </div>
            {!draft.length && (
              <p className="caption">
                Можно продолжить без техники — останутся блюда без приготовления.
              </p>
            )}
            {error && (
              <p className="form-error" role="alert">
                {error}
              </p>
            )}
            <div className="kitchen-actions">
              <button className="button primary" disabled={busy || !online} onClick={save}>
                {busy ? 'Сохраняем…' : initial ? 'Сохранить и продолжить' : 'Сохранить'}
              </button>
              {!initial && (
                <Link className="button quiet" to="/profile">
                  Отмена
                </Link>
              )}
            </div>
            <p className="caption">Выбор сохранится. Изменить его можно в профиле.</p>
          </section>
        )
      )}
    </>
  );
}
