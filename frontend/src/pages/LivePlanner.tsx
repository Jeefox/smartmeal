import SavedMealReplacement from '../components/SavedMealReplacement';
import TodayCooking from '../components/TodayCooking';
import TodayPortions from '../components/TodayPortions';
import MealComposition, { FiberLabel } from '../components/MealComposition';
import { useEffect, useRef, useState } from 'react';
import { useQueries } from '@tanstack/react-query';
import {
  CalendarDays,
  Check,
  ChevronLeft,
  ChevronRight,
  Flame,
  Plus,
  RefreshCw,
  ShoppingBasket,
  Trash2,
} from 'lucide-react';
import { Link, useSearchParams } from 'react-router';
import PlanInventory from '../components/PlanInventory';
import { ApiError } from '../api';
import { dateKey, labelDate, number, shiftDate, weekDates } from '../domain';
import {
  createOrFindPlan,
  mealLabels,
  mealPlanApi,
  mealPlanKey,
  mealTypes,
  snapshotRecipe,
  useDayPlan,
  useRefreshPlan,
  validPortions,
} from '../mealplans';
import type { ApiMealType, MealPlanEntry } from '../mealplans';
import { useApp, useOnline } from '../state';
import { useSession } from '../session';
import {
  Dialog,
  Empty,
  ErrorState,
  Loading,
  Notice,
  NutritionValues,
  PageHeading,
  RecipeImage,
} from '../components/ui';
import { LiveAddToPlan, SnapshotDialog } from '../components/LiveMealDialogs';
function PortionEditor({
  entry,
  busy,
  onSave,
}: {
  entry: MealPlanEntry;
  busy: boolean;
  onSave: (value: number) => Promise<boolean>;
}) {
  const [draft, setDraft] = useState(String(entry.portions));
  const [error, setError] = useState('');
  return (
    <form
      className="live-portion-form"
      onSubmit={async (event) => {
        event.preventDefault();
        setError('');
        if (!validPortions(draft)) {
          setError('Порции: от 0,01 до 9999,99, максимум 2 знака после запятой.');
          return;
        }
        await onSave(Number(draft));
      }}
    >
      <label>
        Порций
        <input
          aria-label={`Порции: ${entry.recipeSnapshot.name}`}
          type="number"
          min="0.01"
          max="9999.99"
          step="0.01"
          inputMode="decimal"
          disabled={busy}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
        />
      </label>
      <button
        className="button quiet"
        disabled={busy || Number(draft) === entry.portions}
        type="submit"
      >
        Сохранить порции
      </button>
      {error && (
        <p className="field-error" role="alert">
          {error}
        </p>
      )}
    </form>
  );
}
export default function LivePlanner({ week = false }: { week?: boolean }) {
  const { selectedDate: stateDate, setSelectedDate, notify } = useApp();
  const [params, setParams] = useSearchParams();
  const dateParam = params.get('date');
  const selectedDate =
    dateParam &&
    /^\d{4}-\d{2}-\d{2}$/.test(dateParam) &&
    !Number.isNaN(Date.parse(dateParam + 'T12:00:00Z')) &&
    new Date(dateParam + 'T12:00:00Z').toISOString().slice(0, 10) === dateParam
      ? dateParam
      : week
        ? stateDate
        : dateKey(new Date());
  useEffect(() => {
    if (selectedDate !== stateDate) setSelectedDate(selectedDate);
  }, [selectedDate, stateDate, setSelectedDate]);
  const session = useSession();
  const userId = session.profile!.userId;
  const online = useOnline();
  const query = useDayPlan(selectedDate);
  const plan = query.data;
  const refresh = useRefreshPlan();
  const [add, setAdd] = useState<ApiMealType | null>(null);
  const [detail, setDetail] = useState<MealPlanEntry | null>(null);
  const [confirm, setConfirm] = useState(false);
  const [remove, setRemove] = useState<MealPlanEntry | null>(null);
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const [error, setError] = useState('');
  const dates = weekDates(selectedDate);
  const weekData = useQueries({
    queries: week
      ? dates.map((date) => ({
          queryKey: mealPlanKey(userId, date),
          queryFn: ({ signal }: { signal: AbortSignal }) => mealPlanApi.find(date, signal),
          staleTime: 0,
        }))
      : [],
  });
  const confirmed = !!plan && plan.status !== 'DRAFT';
  const family = (plan?.participants?.length || 0) > 1;
  const target = plan?.dailyCalorieTarget ?? null;
  const calories = plan?.nutritionTotal.calories;
  const progress = target && calories != null ? Math.min(100, (calories / target) * 100) : null;
  async function mutate(action: () => Promise<unknown>, message: string) {
    if (pending.current || !online) return false;
    pending.current = true;
    setBusy(true);
    setError('');
    let saved = false;
    try {
      await action();
      saved = true;
      await refresh(selectedDate);
      notify(message);
      return true;
    } catch (err) {
      if (saved) {
        setError(
          'Изменение выполнено на сервере, но обновить меню не удалось. Нажмите «Обновить» перед следующим действием.',
        );
        return true;
      }
      setError(err instanceof Error ? err.message : 'Не удалось изменить меню');
      if (err instanceof ApiError && err.status === 409)
        await refresh(selectedDate).catch(() => {});
      return false;
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }
  function selectDate(date: string) {
    setSelectedDate(date);
    const next = new URLSearchParams(params);
    next.set('date', date);
    setParams(next);
    setError('');
    setAdd(null);
    setDetail(null);
    setConfirm(false);
    setRemove(null);
  }
  return (
    <div className={week ? 'week-planner' : 'today-screen'}>
      <PageHeading
        eyebrow={week ? 'МЕНЮ НЕДЕЛИ' : undefined}
        title={
          week ? 'Меню недели' : selectedDate === dateKey(new Date()) ? 'Сегодня' : 'Меню на день'
        }
        description={
          week ? 'Выберите день недели. Каждый день хранится и подтверждается отдельно.' : undefined
        }
        actions={
          <div className="preview-planner-actions">
            <Link className="button primary" to={`/menu-generation?from=${selectedDate}`}>
              Подобрать меню
            </Link>
            <div className="segmented">
              <Link to={`/?date=${selectedDate}`} className={!week ? 'active' : ''}>
                День
              </Link>
              <Link to={`/week?date=${selectedDate}`} className={week ? 'active' : ''}>
                Неделя
              </Link>
            </div>
          </div>
        }
      />
      <div className="planner-toolbar">
        <div className="date-controls">
          <button
            className="icon-button"
            disabled={busy}
            aria-label={week ? 'Предыдущая неделя' : 'Предыдущий день'}
            onClick={() => selectDate(shiftDate(selectedDate, week ? -7 : -1))}
          >
            <ChevronLeft size={19} />
          </button>
          <label>
            <CalendarDays size={16} />
            <input
              type="date"
              aria-label="День меню"
              disabled={busy}
              value={selectedDate}
              onChange={(event) => {
                if (event.target.value) selectDate(event.target.value);
              }}
            />
          </label>
          <button
            className="icon-button"
            disabled={busy}
            aria-label={week ? 'Следующая неделя' : 'Следующий день'}
            onClick={() => selectDate(shiftDate(selectedDate, week ? 7 : 1))}
          >
            <ChevronRight size={19} />
          </button>
          {(week || selectedDate !== dateKey(new Date())) && (
            <button
              className="button quiet"
              disabled={busy}
              onClick={() => selectDate(dateKey(new Date()))}
            >
              Сегодня
            </button>
          )}
        </div>
        <button
          className="button"
          disabled={busy || query.isFetching || !online}
          onClick={() => {
            setError('');
            query.refetch();
          }}
        >
          <RefreshCw size={16} />
          <span className={week ? undefined : 'sr-only'}>Обновить</span>
        </button>
      </div>
      {week && (
        <div className="week-strip">
          {dates.map((date, index) => {
            const result = weekData[index];
            return (
              <button
                key={date}
                disabled={busy}
                className={date === selectedDate ? 'active' : ''}
                onClick={() => selectDate(date)}
              >
                <span>{labelDate(date, { weekday: 'short' })}</span>
                <b>{labelDate(date, { day: 'numeric' })}</b>
                <small>
                  {result?.isPending
                    ? 'Загрузка'
                    : result?.isError
                      ? 'Ошибка'
                      : result?.data
                        ? `${number(result.data.nutritionTotal.calories)} ккал`
                        : 'Нет меню'}
                </small>
              </button>
            );
          })}
        </div>
      )}
      {error && (
        <div className="form-error" role="alert">
          {error}
        </div>
      )}
      {query.isPending ? (
        <Loading />
      ) : query.isError && !plan ? (
        <ErrorState error={query.error} retry={() => query.refetch()} />
      ) : plan === null ? (
        <Empty
          title="На этот день ещё нет меню"
          action={
            <button
              className="button primary"
              disabled={busy || !online}
              onClick={() =>
                mutate(() => createOrFindPlan(selectedDate), 'Черновик меню открыт на сервере')
              }
            >
              <Plus size={17} />
              {busy ? 'Создаём…' : 'Создать меню на день'}
            </button>
          }
        >
          Создайте черновик на {labelDate(selectedDate)}, затем добавьте рецепты. Пустой день не
          подменяется демоданными.
        </Empty>
      ) : plan ? (
        <>
          {query.isError && (
            <Notice tone="warning">
              Не удалось обновить меню. Показаны ранее полученные данные. Нажмите «Обновить»;
              повторять сохранение автоматически не будем.
            </Notice>
          )}
          {plan.status === 'CONFIRMED' && <p className="caption">Меню подтверждено</p>}
          <section className="daily-overview">
            <div className={`daily-main ${plan.status === 'CONFIRMED' ? 'today-confirmed' : ''}`}>
              <div className="overview-top">
                <span className="eyebrow">
                  {labelDate(selectedDate, {
                    weekday: 'long',
                    day: 'numeric',
                    month: 'long',
                  })}
                </span>
                <span className="draft-badge">
                  {plan.status === 'CANCELLED'
                    ? 'Отменено'
                    : confirmed
                      ? 'Подтверждено'
                      : 'Черновик'}
                </span>
              </div>
              <div className="energy-row">
                <div>
                  <h2>
                    {!week && <span>{family ? 'По меню на всех ' : 'По меню '}</span>}
                    {number(calories!)}
                    <span>{target ? ` / ${number(target)} ккал` : ' ккал · цель не задана'}</span>
                  </h2>
                  <p>
                    {family
                      ? `На всех: ${plan.participants!.length} чел. · индивидуальные нормы ниже`
                      : 'Запланировано, не фактически съедено · итоги сервера'}
                  </p>
                </div>
                <div
                  className="energy-ring"
                  style={{ '--progress': `${progress ?? 0}%` } as React.CSSProperties}
                >
                  <Flame size={23} />
                  <b>{progress == null ? '—' : `${number(progress)}%`}</b>
                </div>
              </div>
              <details className="today-nutrition">
                <summary>Питание за день: калории, белок и клетчатка</summary>
                <NutritionValues
                  nutrition={plan.nutritionTotal}
                  label="КБЖУ меню дня · значения получены от сервера"
                />
                {(family || plan.entries.some((e) => e.recipeSnapshot.components?.length)) && (
                  <div className="household-day" aria-label="Питание каждого">
                    {plan.people?.map((p) => (
                      <p key={p.participantId}>
                        <strong>{p.name}</strong>: {number(p.nutrition.calories)} /{' '}
                        {number(p.dailyCalorieTarget)} ккал
                        {plan.entries.some((e) => e.recipeSnapshot.components?.length) && (
                          <>
                            {' · белок '}
                            {number(p.nutrition.protein)}
                            {p.proteinTargetG ? ` / ${number(p.proteinTargetG)}` : ''} г{' · '}
                            <FiberLabel
                              known={p.nutrition.knownFiber}
                              complete={p.nutrition.fiberComplete}
                            />
                            {p.fiberTargetG ? ` / цель ${number(p.fiberTargetG)} г` : ''}
                          </>
                        )}
                      </p>
                    ))}
                  </div>
                )}
              </details>
              <p className="caption">
                {family
                  ? 'Нормы и состав семьи сохранены вместе с этим меню.'
                  : confirmed
                    ? 'Цель — снимок на момент подтверждения.'
                    : 'Цель — текущая сохранённая калорийность профиля.'}
              </p>
              {!target && (
                <Link className="text-link" to="/profile">
                  Задать цель в профиле
                </Link>
              )}
            </div>
            <aside className="budget-overview today-shopping-summary">
              <span className="eyebrow">ПОКУПКИ И БЮДЖЕТ</span>
              <ShoppingBasket size={27} />
              <h2 className="live-budget-title">Продукты по меню</h2>
              <p>
                Выберите магазин в списке покупок, чтобы рассчитать упаковки и оценочную стоимость.
              </p>
              <Link
                to={`/shopping?from=${week ? dates[0] : selectedDate}&to=${week ? dates[6] : selectedDate}`}
                className="text-link"
              >
                К покупкам
              </Link>
            </aside>
          </section>
          {!week && plan.status !== 'CANCELLED' && (
            <TodayCooking key={`${userId}:${selectedDate}`} date={selectedDate} />
          )}
          <div className="section-title">
            <div>
              <h2>
                Блюда на день <span className="count">{plan.entries.length}</span>
              </h2>
              <p className="caption">Состав и питание из сохранённых снимков</p>
            </div>
            {!confirmed && (
              <button
                className="button primary"
                disabled={busy || !online || !plan.entries.length || !target || query.isError}
                onClick={() => setConfirm(true)}
              >
                <Check size={17} />
                Подтвердить меню
              </button>
            )}
          </div>
          {!confirmed && (!plan.entries.length || !target) && (
            <p className="caption">
              {!plan.entries.length
                ? 'Для подтверждения добавьте хотя бы одно блюдо.'
                : 'Для подтверждения сначала задайте суточную цель в профиле.'}
            </p>
          )}
          <div className="meal-list">
            {mealTypes
              .filter(
                (type) =>
                  type !== 'SNACK' || !confirmed || plan.entries.some((e) => e.mealType === type),
              )
              .map((type, index) => (
                <section className="meal-slot" key={type}>
                  <div className="meal-slot-heading">
                    <div>
                      <span className="meal-symbol">{['☀', '◐', '☾', '✳'][index]}</span>
                      <h3>{mealLabels[type]}</h3>
                    </div>
                    {!confirmed && !family && (
                      <button
                        className="icon-button"
                        disabled={busy || !online || query.isError}
                        aria-label={`Добавить: ${mealLabels[type]}`}
                        onClick={() => setAdd(type)}
                      >
                        <Plus size={19} />
                      </button>
                    )}
                  </div>
                  {plan.entries
                    .filter((entry) => entry.mealType === type)
                    .sort((a, b) => a.position - b.position)
                    .map((entry) => (
                      <article className="meal-card live-meal-card" key={entry.id}>
                        <button
                          className="meal-photo"
                          aria-label={`Снимок блюда: ${entry.recipeSnapshot.name}`}
                          onClick={() => setDetail(entry)}
                        >
                          <RecipeImage recipe={snapshotRecipe(entry.recipeSnapshot)} />
                        </button>
                        <div className="meal-info">
                          <button className="snapshot-title" onClick={() => setDetail(entry)}>
                            <h3>{entry.recipeSnapshot.name}</h3>
                          </button>
                          <p>
                            {entry.recipeSnapshot.preparationTimeMin} мин ·{' '}
                            {number(entry.nutrition.calories)} ккал
                            {entry.recipeSnapshot.components?.length
                              ? ' · составной приём пищи'
                              : ` · ${entry.portions} порц.`}
                          </p>
                          {family && !entry.recipeSnapshot.components?.length && (
                            <ul className="household-portions" aria-label="Порции по людям">
                              {entry.personPortions?.map((p) => (
                                <li key={p.participantId}>
                                  <strong>{p.name}</strong>: {number(p.portions, 2)} порц. ·{' '}
                                  {number(p.nutrition.calories)} ккал
                                </li>
                              ))}
                            </ul>
                          )}
                          {!week && (
                            <TodayPortions
                              entry={entry}
                              date={selectedDate}
                              confirmed={plan.status === 'CONFIRMED'}
                            />
                          )}
                          {!!entry.recipeSnapshot.components?.length && (
                            <details className="today-recipe-details">
                              <summary>Рецепт и подробности количества</summary>
                              <MealComposition
                                recipe={entry.recipeSnapshot}
                                people={entry.personPortions || []}
                              />
                            </details>
                          )}
                          <SavedMealReplacement plan={plan} entry={entry} />
                          {confirmed ? (
                            <>
                              <PlanInventory plan={plan} entry={entry} />
                            </>
                          ) : entry.recipeSnapshot.components?.length ? (
                            <span className="caption">
                              Индивидуальные количества заданы подбором
                            </span>
                          ) : (
                            <PortionEditor
                              key={`${entry.id}-${entry.portions}`}
                              entry={entry}
                              busy={busy || !online || query.isError}
                              onSave={(value) =>
                                mutate(
                                  () => mealPlanApi.portions(plan.id, entry.id, value),
                                  'Порции сохранены. Итоги обновлены.',
                                )
                              }
                            />
                          )}
                        </div>
                        {!confirmed && (
                          <button
                            className="icon-button danger"
                            disabled={busy || !online || query.isError}
                            aria-label={`Удалить: ${entry.recipeSnapshot.name}`}
                            onClick={() => setRemove(entry)}
                          >
                            <Trash2 size={18} />
                          </button>
                        )}
                      </article>
                    ))}
                  {!plan.entries.some((entry) => entry.mealType === type) &&
                    (!confirmed && !family ? (
                      <button
                        className="empty-meal"
                        disabled={busy || !online || query.isError}
                        onClick={() => setAdd(type)}
                      >
                        <Plus size={17} />
                        Добавить блюдо
                      </button>
                    ) : (
                      <p className="caption">Нет блюд</p>
                    ))}
                </section>
              ))}
          </div>
          <details>
            <summary>О меню и расчёте</summary>
            <p>
              Здесь показано запланированное питание. Приготовление и употребление готовой еды
              отмечаются отдельно. Подробности покупок и оценки стоимости доступны в списке покупок.
            </p>
          </details>
        </>
      ) : null}
      <details className="today-day-settings">
        <summary>Запасы и настройки дня</summary>
        <Link to={`/menu-preview?date=${selectedDate}`}>Подобрать меню на один день</Link>
        {plan && <PlanInventory key={plan.id} plan={plan} />}
      </details>
      {add && <LiveAddToPlan initialMeal={add} date={selectedDate} onClose={() => setAdd(null)} />}{' '}
      {detail && <SnapshotDialog entry={detail} onClose={() => setDetail(null)} />}
      {confirm && plan && !confirmed && (
        <Dialog
          title="Подтвердить меню?"
          onClose={() => {
            if (!busy) setConfirm(false);
          }}
        >
          <Notice tone="warning">
            Будут зафиксированы снимки блюд и цель на момент подтверждения. После этого порции и
            состав дня изменить нельзя. Текущая цель показанного меню:{' '}
            {target ? `${number(target)} ккал` : 'не задана'}.
          </Notice>
          <div className="dialog-actions">
            <button className="button" disabled={busy} onClick={() => setConfirm(false)}>
              Отмена
            </button>
            <button
              className="button primary"
              disabled={busy || !online}
              onClick={async () => {
                await mutate(() => mealPlanApi.confirm(plan.id), 'Меню подтверждено на сервере');
                setConfirm(false);
              }}
            >
              Подтвердить день
            </button>
          </div>
        </Dialog>
      )}
      {remove && plan && !confirmed && plan.entries.some((entry) => entry.id === remove.id) && (
        <Dialog
          title="Удалить блюдо из меню?"
          onClose={() => {
            if (!busy) setRemove(null);
          }}
        >
          <p className="muted">{remove.recipeSnapshot.name}. Сам рецепт останется в каталоге.</p>
          <div className="dialog-actions">
            <button className="button" disabled={busy} onClick={() => setRemove(null)}>
              Отмена
            </button>
            <button
              className="button primary"
              disabled={busy || !online}
              onClick={async () => {
                if (
                  await mutate(
                    () => mealPlanApi.remove(plan.id, remove.id),
                    'Блюдо удалено. Итоги обновлены.',
                  )
                )
                  setRemove(null);
              }}
            >
              Удалить блюдо
            </button>
          </div>
        </Dialog>
      )}
    </div>
  );
}
