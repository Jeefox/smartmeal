import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, Navigate, useLocation } from 'react-router';
import { balancedApi } from '../balanced-meals';
import type { NutrientTargets } from '../balanced-meals';
import { useSession } from '../session';
import { useOnline, useApp } from '../state';
import { BackendPending, Loading, Notice, PageHeading } from '../components/ui';
export default function NutritionTargets() {
  const { mode } = useApp();
  return mode === 'api' ? (
    <Navigate to="/profile#nutrition" replace />
  ) : (
    <BackendPending
      title="Цели питания доступны в режиме API"
      description="Выберите SmartMeal API и войдите в аккаунт для индивидуальных настроек."
    />
  );
}
export function NutrientTargetsForm({ embedded = false }: { embedded?: boolean }) {
  const location = useLocation();
  const [expanded, setExpanded] = useState(location.hash === '#nutrition');
  useEffect(() => {
    if (location.hash === '#nutrition') {
      setExpanded(true);
      document.getElementById('nutrition')?.scrollIntoView({ block: 'start' });
    }
  }, [location.hash]);
  const { profile } = useSession(),
    online = useOnline(),
    q = useQuery({
      queryKey: ['nutrition-targets', profile?.userId],
      queryFn: async ({ signal }) => {
        const values = await balancedApi.targets(signal);
        if (!Array.isArray(values))
          throw new Error('Не удалось получить цели питания. Повторите загрузку.');
        return values;
      },
      retry: false,
    });
  const [id, setId] = useState(profile?.userId || ''),
    [draft, setDraft] = useState<NutrientTargets | null>(null),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState(''),
    [dirty, setDirty] = useState(false);
  useEffect(() => {
    if (!q.data || dirty) return;
    const person = q.data.find((x) => x.participantId === id) || q.data[0];
    if (person) {
      setId(person.participantId);
      setDraft({ ...person.settings, expectedRevision: person.revision });
    }
  }, [id, q.data, dirty]);
  async function perform(suggest: boolean) {
    if (!draft || busy || !online) return;
    setBusy(true);
    setMessage('');
    try {
      if (suggest) {
        setDraft(await balancedApi.suggest(draft));
        setDirty(true);
        setMessage(
          'Рассчитаны справочные ориентиры. Проверьте их и отдельно сохраните — цели ещё не изменены.',
        );
      } else {
        await balancedApi.save(id, draft);
        setDirty(false);
        setMessage('Цели сохранены. Они будут учтены при следующем подборе меню.');
        await q.refetch();
      }
    } catch (e) {
      setMessage((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  function change(p: Partial<NutrientTargets>, manual = false) {
    setDirty(true);
    if (draft)
      setDraft({
        ...draft,
        ...p,
        ...(manual ? { targetSource: 'USER_SET' as const } : {}),
      });
  }
  const saved = q.data?.find((person) => person.participantId === profile?.userId)?.settings;
  const summary = saved
    ? [
        saved.proteinTargetG == null ? 'Белок не задан' : `Белок ${saved.proteinTargetG} г/сут`,
        saved.fiberTargetG == null
          ? 'Клетчатка не задана'
          : `Клетчатка ${saved.fiberTargetG} г/сут`,
      ].join(' · ')
    : 'Настройте желаемое количество в сутки';
  return (
    <section
      id={embedded ? 'nutrition' : undefined}
      className={embedded ? 'panel profile-nutrients' : ''}
    >
      {embedded ? (
        <>
          <h2>Белок и клетчатка</h2>
          <p className="muted nutrient-summary">{summary}</p>
        </>
      ) : (
        <PageHeading
          title="Белок, клетчатка и количество еды"
          description="Цели настраиваются отдельно для каждого взрослого участника."
        />
      )}
      <details
        open={embedded ? expanded : true}
        onToggle={(e) => setExpanded(e.currentTarget.open)}
      >
        {embedded && <summary>Изменить цели питания</summary>}
        {q.isPending ? (
          <Loading />
        ) : q.isError ? (
          <Notice tone="warning">
            {q.error.message}
            <button className="button" onClick={() => q.refetch()}>
              Повторить
            </button>
          </Notice>
        ) : (
          draft && (
            <form
              className={embedded ? 'period-options nutrient-form' : 'panel period-options'}
              onSubmit={(e) => {
                e.preventDefault();
                void perform(false);
              }}
            >
              <label>
                Для кого
                <select
                  value={id}
                  disabled={busy}
                  onChange={(e) => {
                    setDirty(false);
                    setId(e.target.value);
                    setMessage('');
                  }}
                >
                  {q.data.map((p) => (
                    <option value={p.participantId} key={p.participantId}>
                      {p.name}
                    </option>
                  ))}
                </select>
              </label>
              <label className="check">
                <input
                  type="checkbox"
                  checked={draft.adultConfirmed}
                  disabled={busy}
                  onChange={(e) =>
                    change({
                      adultConfirmed: e.target.checked,
                      ...(!e.target.checked
                        ? {
                            proteinTargetG: null,
                            fiberTargetG: null,
                            targetSource: 'UNSET',
                          }
                        : {}),
                    })
                  }
                />
                Подтверждаю взрослый профиль
              </label>
              <p className="caption">
                Обычные справочные ориентиры этого режима предназначены для взрослых. Они не
                являются индивидуальным назначением или гарантией сытости.
              </p>
              <label>
                Масса тела, кг · для справочного расчёта
                <input
                  type="number"
                  min="20"
                  max="400"
                  step="0.01"
                  value={draft.weightKg ?? ''}
                  disabled={busy}
                  onChange={(e) =>
                    change({
                      weightKg: e.target.value ? Number(e.target.value) : null,
                      targetSource: 'USER_SET',
                    })
                  }
                />
              </label>
              <button
                className="button"
                type="button"
                disabled={!online || busy || !draft.adultConfirmed || !draft.weightKg}
                onClick={() => void perform(true)}
              >
                Предложить справочные ориентиры
              </button>
              <label>
                Белок в сутки, г · можно не задавать
                <input
                  type="number"
                  min="0"
                  max="400"
                  step="0.01"
                  value={draft.proteinTargetG ?? ''}
                  disabled={busy || !draft.adultConfirmed}
                  onChange={(e) =>
                    change(
                      {
                        proteinTargetG: e.target.value ? Number(e.target.value) : null,
                      },
                      true,
                    )
                  }
                />
              </label>
              <label>
                Клетчатка в сутки, г · можно не задавать
                <input
                  type="number"
                  min="0"
                  max="100"
                  step="0.01"
                  value={draft.fiberTargetG ?? ''}
                  disabled={busy || !draft.adultConfirmed}
                  onChange={(e) =>
                    change(
                      {
                        fiberTargetG: e.target.value ? Number(e.target.value) : null,
                      },
                      true,
                    )
                  }
                />
              </label>
              <details>
                <summary>Размер приёма пищи</summary>
                <label>
                  Предельная суммарная готовая масса, г
                  <input
                    type="number"
                    min="250"
                    max="1200"
                    step="25"
                    value={draft.maxMealG}
                    disabled={busy}
                    onChange={(e) => change({ maxMealG: Number(e.target.value) })}
                  />
                </label>
                <p className="caption">
                  Ограничение поиска по количеству еды, а не обязательная масса тарелки. Для
                  компонентов действуют также отдельные границы рецепта.
                </p>
              </details>
              <p className="caption">
                Справочный расчёт: 0,83 г белка на кг массы и 25 г клетчатки в сутки для взрослой
                анкеты.{' '}
                <a
                  href="https://efsa.onlinelibrary.wiley.com/doi/10.2903/j.efsa.2022.7259"
                  target="_blank"
                  rel="noreferrer"
                >
                  Источник белка
                </a>{' '}
                ·{' '}
                <a
                  href="https://knowledge-for-policy.ec.europa.eu/health-promotion-knowledge-gateway/dietary-fibre-recommendations-2_en"
                  target="_blank"
                  rel="noreferrer"
                >
                  Источник клетчатки
                </a>
              </p>
              <button className="button primary" disabled={!online || busy}>
                Сохранить цели
              </button>
            </form>
          )
        )}
        {dirty && <p className="caption">Изменения анкеты ещё не сохранены.</p>}
        {message && <Notice>{message}</Notice>}
        <button
          className="button"
          disabled={busy || !online}
          onClick={() => {
            setDirty(false);
            void q.refetch();
          }}
        >
          Обновить цели
        </button>
        {!embedded && (
          <Link className="button" to="/menu-generation">
            К подбору меню
          </Link>
        )}
      </details>
    </section>
  );
}
