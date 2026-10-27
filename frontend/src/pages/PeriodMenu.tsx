import { shoppingProductCount } from '../period-shopping';
import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { RefreshCw, SlidersHorizontal, ShoppingBasket } from 'lucide-react';
import { ApiError, request } from '../api';
import { labelDate } from '../domain';
import { householdApi } from '../household';
import { BackendPending, Dialog, Loading, Notice, PageHeading } from '../components/ui';
import { useApp, useOnline } from '../state';
import { useSession } from '../session';
import { periodApi, validatePlanningOptions } from '../period-planning';
import type { PeriodView, Alternative, PlanningOptions } from '../period-planning';
import { mealPlanKey } from '../mealplans';
import { invalidateShopping } from '../shopping';
import { missingMeals } from '../period-shopping';
import {
  readPlanningPreferences,
  savePlanningPreferences,
  fromOptions,
  draftOptions,
  sameOptions,
  commitKey,
  clearCommitKey,
  pendingCommit,
  rememberSavedPeriod,
} from '../planning-preferences';
import type { PlanningDraft } from '../planning-preferences';
import {
  basketSummary,
  budgetSummary,
  incompleteSummary,
  planningBlock,
  errorRecovery,
} from '../planning-presentation';
import PlanningSetupForm from '../components/planning/PlanningSetupForm';
import PeriodMenuOverview from '../components/planning/PeriodMenuOverview';
import MealChoiceDialog from '../components/planning/MealChoiceDialog';
import PlanningWarningsDialog from '../components/planning/PlanningWarningsDialog';
import PlanningActionBar from '../components/planning/PlanningActionBar';
import PeriodShoppingList from '../components/PeriodShoppingList';
type Tab = 'options' | 'menu' | 'shopping';
export default function PeriodMenu() {
  const { mode } = useApp();
  const { profile } = useSession();
  return mode === 'demo' ? (
    <BackendPending
      title="Недельный подбор работает через API"
      description="Включите SmartMeal API и войдите в аккаунт: подбор учитывает ваши ограничения и сохранённые цены."
    />
  ) : (
    <LivePeriodMenu key={profile?.userId || 'anonymous'} />
  );
}
function LivePeriodMenu() {
  const { profile } = useSession(),
    userId = profile!.userId;
  const { setSelectedDate, notify } = useApp();
  const online = useOnline(),
    client = useQueryClient(),
    navigate = useNavigate();
  const [params, setParams] = useSearchParams(),
    requestedId = params.get('id');
  const [draft, setDraft] = useState<PlanningDraft>(() => ({
    ...readPlanningPreferences(userId),
    ...(params.get('from') ? { from: params.get('from')! } : {}),
  }));
  const [view, setView] = useState<PeriodView | null>(null),
    viewRef = useRef<PeriodView | null>(null);
  const [tab, setTab] = useState<Tab>(
    requestedId
      ? params.get('tab') === 'shopping'
        ? 'shopping'
        : params.get('tab') === 'options'
          ? 'options'
          : 'menu'
      : 'options',
  );
  const [phase, setPhase] = useState<string | null>(null),
    [error, setError] = useState<Error | null>(null),
    [invalid, setInvalid] = useState<string | null>(null),
    [advanced, setAdvanced] = useState(false),
    [now, setNow] = useState(Date.now());
  const [mealId, setMealId] = useState<string | null>(null),
    [alternatives, setAlternatives] = useState<Alternative[] | null>(null),
    [warningFor, setWarningFor] = useState<string | null>(null),
    [relax, setRelax] = useState(false),
    [commitState, setCommitState] = useState<'idle' | 'uncertain' | 'checked'>(() =>
      requestedId && pendingCommit(userId, requestedId) ? 'uncertain' : 'idle',
    );
  const lifecycle = useRef({
      alive: true,
      serial: 0,
      busy: false,
      abort: null as AbortController | null,
    }),
    keys = useRef(new Map<string, string>()),
    chooseDefault = useRef(!requestedId && !draft.chains.length);
  const household = useQuery({
    queryKey: ['household', userId],
    queryFn: ({ signal }) => householdApi.list(signal),
  });
  const chains = useQuery({
    queryKey: ['retail-chains'],
    queryFn: ({ signal }) => request<{ id: number; name: string }[]>('/retail-chains', { signal }),
  });
  const busy = phase !== null;
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30000);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => {
    lifecycle.current.alive = true;
    return () => {
      lifecycle.current.alive = false;
      lifecycle.current.abort?.abort();
      lifecycle.current.serial++;
    };
  }, []);
  useEffect(() => {
    if (chooseDefault.current && chains.data?.length) {
      chooseDefault.current = false;
      setDraft((d) => ({ ...d, chains: [chains.data![0].id] }));
    }
  }, [chains.data]);
  function switchTab(next: Tab) {
    setTab(next);
    setInvalid(null);
    if (viewRef.current) setParams({ id: viewRef.current.id, tab: next }, { replace: true });
  }
  function receive(next: PeriodView, nextTab: Tab = tab) {
    viewRef.current = next;
    setView(next);
    setDraft(fromOptions(next.result.options));
    setWarningFor(null);
    setAlternatives(null);
    setError(null);
    setInvalid(null);
    setTab(nextTab);
    setParams({ id: next.id, tab: nextTab }, { replace: true });
    if (next.savedPlanIds.length) {
      rememberSavedPeriod(userId, next);
      clearCommitKey(userId, next.id);
      setCommitState('idle');
    }
  }
  function afterSaved(next: PeriodView) {
    receive(next, 'shopping');
    setMealId(null);
    setSelectedDate(next.result.options.from);
    notify(`Меню сохранено на ${next.savedPlanIds.length} дней`);
    for (const day of next.result.days)
      client.invalidateQueries({ queryKey: mealPlanKey(userId, day.date) });
    invalidateShopping(client, userId);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
  async function run<T>(
    name: string,
    task: (signal: AbortSignal) => Promise<T>,
    done: (value: T) => void,
  ) {
    const state = lifecycle.current;
    if (state.busy || !online) return;
    state.busy = true;
    const serial = ++state.serial,
      controller = new AbortController();
    state.abort = controller;
    setPhase(name);
    setError(null);
    setInvalid(null);
    try {
      const result = await task(controller.signal);
      if (state.alive && serial === state.serial) done(result);
    } catch (e) {
      if (
        !state.alive ||
        serial !== state.serial ||
        !(e instanceof Error) ||
        e.name === 'AbortError'
      )
        return;
      setError(e);
      setWarningFor(null);
      if (name === 'replace' || name === 'lock') setMealId(null);
      if (name === 'save' && e instanceof ApiError && (e.status === 0 || e.status >= 500)) {
        setCommitState('uncertain');
        const current = viewRef.current;
        if (current)
          try {
            const checked = await periodApi.get(current.id, controller.signal);
            if (state.alive && serial === state.serial) {
              if (checked.savedPlanIds.length) afterSaved(checked);
              else {
                receive(checked, 'menu');
                setCommitState('checked');
                setError(
                  new ApiError(
                    0,
                    'Ответ на сохранение не получен. Запись ещё не подтверждена; можно повторить вручную без создания второго меню.',
                  ),
                );
              }
            }
          } catch {
            /* Read failure never automatically retries a mutation. */
          }
      }
    } finally {
      if (state.alive && serial === state.serial) {
        state.busy = false;
        setPhase(null);
      }
    }
  }
  useEffect(() => {
    const state = lifecycle.current;
    if (!requestedId) {
      if (viewRef.current) {
        viewRef.current = null;
        setView(null);
        setDraft(readPlanningPreferences(userId));
        setTab('options');
        setError(null);
        setMealId(null);
        setWarningFor(null);
      }
      return;
    }
    if (viewRef.current?.id === requestedId) return;
    const controller = new AbortController(),
      serial = ++state.serial;
    state.busy = true;
    state.abort = controller;
    setPhase('load');
    setError(null);
    viewRef.current = null;
    setView(null);
    periodApi
      .get(requestedId, controller.signal)
      .then((next) => {
        if (!state.alive || serial !== state.serial) return;
        receive(
          next,
          params.get('tab') === 'shopping'
            ? 'shopping'
            : params.get('tab') === 'options'
              ? 'options'
              : 'menu',
        );
        if (!next.savedPlanIds.length && pendingCommit(userId, next.id)) setCommitState('checked');
      })
      .catch((e) => {
        if (state.alive && serial === state.serial && e.name !== 'AbortError') setError(e);
      })
      .finally(() => {
        if (state.alive && serial === state.serial) {
          state.busy = false;
          setPhase(null);
        }
      });
    return () => {
      controller.abort();
      if (serial === state.serial) {
        state.serial++;
        state.busy = false;
      }
    };
  }, [requestedId, userId]);
  // Browser Back/Forward may change the view without a new generation ID.
  useEffect(() => {
    if (viewRef.current?.id === requestedId) {
      const next = params.get('tab');
      if (next === 'menu' || next === 'shopping' || next === 'options') setTab(next);
    }
  }, [params, requestedId]);
  const saved = !!view?.savedPlanIds.length,
    expired = !!view && Date.parse(view.expiresAt) <= now;
  const block = view ? planningBlock(view, profile?.dailyCalorieTarget, now) : null;
  const profileChanged =
    !!view &&
    profile?.dailyCalorieTarget !==
      (view.result.participants?.find((p) => p.accountOwner)?.dailyCalorieTarget ??
        view.result.dailyCalorieTarget);
  let edited = true;
  try {
    edited =
      !view ||
      !Number.isInteger(draft.days) ||
      draft.days < 1 ||
      draft.days > 14 ||
      !sameOptions(draftOptions(draft), view.result.options);
  } catch {
    /* Invalid draft needs correction, not a commit. */
  }
  const missing = view ? missingMeals(view) : [];
  const meal = view?.result.entries.find((e) => e.id === mealId);
  const recovery = error ? errorRecovery(error) : null;
  const revisionKey = view ? `${view.id}:${view.revision}` : '';
  useEffect(() => {
    setWarningFor(null);
  }, [revisionKey]);
  function changeDraft(next: PlanningDraft) {
    setDraft(next);
    setInvalid(null);
    setError(null);
  }
  function editConditions() {
    setMealId(null);
    setWarningFor(null);
    switchTab('options');
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
  function newPlan() {
    lifecycle.current.abort?.abort();
    lifecycle.current.serial++;
    lifecycle.current.busy = false;
    setPhase(null);
    viewRef.current = null;
    setView(null);
    setTab('options');
    setParams({}, { replace: true });
    setDraft(readPlanningPreferences(userId));
    chooseDefault.current = false;
    setError(null);
    setInvalid(null);
    setMealId(null);
    setWarningFor(null);
    setCommitState('idle');
  }
  function generate(options?: PlanningOptions) {
    let value: PlanningOptions;
    try {
      if (!options && (!Number.isInteger(draft.days) || draft.days < 1 || draft.days > 14))
        throw new Error('Выберите период от 1 до 14 дней.');
      value = options || draftOptions(draft);
    } catch (e) {
      setInvalid(e instanceof Error ? e.message : 'Проверьте период.');
      return;
    }
    const message = validatePlanningOptions(value);
    if (message) {
      setInvalid(message);
      return;
    }
    if (household.isError && value.householdMemberIds?.length) {
      setInvalid('Сначала загрузите выбранные профили семьи.');
      return;
    }
    if (
      household.data &&
      value.householdMemberIds?.some((id) => !household.data.some((m) => m.id === id))
    ) {
      setInvalid('Пересмотрите состав семьи: выбранный профиль недоступен.');
      return;
    }
    if (!chains.data || value.allowedChainIds.some((id) => !chains.data.some((c) => c.id === id))) {
      setInvalid('Выберите доступные магазины.');
      return;
    }
    run(
      'generate',
      (signal) => periodApi.create(value, signal),
      (next) => {
        savePlanningPreferences(userId, fromOptions(next.result.options));
        setCommitState('idle');
        receive(next, 'menu');
        window.scrollTo({ top: 0, behavior: 'smooth' });
      },
    );
  }
  function reload() {
    if (!view) return;
    run(
      'reload',
      (s) => periodApi.get(view.id, s),
      (next) => {
        if (next.savedPlanIds.length) afterSaved(next);
        else {
          receive(next, 'menu');
          setCommitState(pendingCommit(userId, next.id) ? 'checked' : 'idle');
        }
      },
    );
  }
  function refresh() {
    if (!view) return;
    run(
      'refresh',
      (s) => periodApi.refresh(view, s),
      (next) => receive(next, tab),
    );
  }
  function requestSave(accept = false) {
    const current = viewRef.current;
    if (!current || current.savedPlanIds.length || busy || !online) return;
    if (planningBlock(current, profile?.dailyCalorieTarget, Date.now()) || edited) {
      setInvalid('Перед сохранением пересчитайте меню с актуальными условиями.');
      return;
    }
    if (commitState === 'uncertain') {
      reload();
      return;
    }
    if (current.result.issues.length && !accept) {
      setWarningFor(`${current.id}:${current.revision}`);
      return;
    }
    if (accept && warningFor !== `${current.id}:${current.revision}`) {
      setWarningFor(null);
      setError(new Error('Меню изменилось. Проверьте обновлённый вариант перед сохранением.'));
      return;
    }
    run(
      'save',
      (s) => periodApi.commit(current, commitKey(userId, current.id, keys.current), accept, s),
      afterSaved,
    );
  }
  function retryWhole() {
    if (!view) return;
    if (expired || profileChanged) {
      editConditions();
      return;
    }
    setMealId(null);
    run(
      'regenerate',
      (s) => periodApi.regenerate(view, s),
      (next) => receive(next, 'menu'),
    );
  }
  function applyRecovery() {
    if (!recovery) return;
    switch (recovery.action) {
      case 'refresh':
        refresh();
        break;
      case 'reload':
        reload();
        break;
      case 'create':
        editConditions();
        break;
      case 'calendar':
        navigate(`/week?date=${view?.result.options.from || draft.from}`);
        break;
      case 'login':
        navigate('/login');
        break;
      default:
        break;
    }
  }
  let label = 'Сохранить меню и перейти к покупкам',
    message = 'Все дни сохраняются вместе.',
    action = () => requestSave();
  if (saved) {
    label = 'Открыть меню недели';
    message = `Меню сохранено на ${view!.savedPlanIds.length} дней`;
    action = () => navigate(`/week?date=${view!.result.options.from}`);
  } else if (commitState === 'uncertain') {
    label = 'Проверить сохранение';
    message = 'Не удалось подтвердить результат сохранения. Сначала проверим состояние меню.';
    action = reload;
  } else if (recovery && recovery.action !== 'none') {
    label = recovery.label;
    message = error!.message;
    action = applyRecovery;
  } else if (block) {
    message = expired
      ? 'Срок предпросмотра истёк. Нужен новый подбор.'
      : incompleteSummary(view!) || block;
    label =
      expired || profileChanged
        ? 'Создать новый подбор'
        : missing.length && view!.result.searchStatus === 'LIMIT_REACHED'
          ? 'Повторить подбор всего периода'
          : 'Изменить условия';
    action = expired
      ? editConditions
      : missing.length && view!.result.searchStatus === 'LIMIT_REACHED'
        ? retryWhole
        : editConditions;
  } else if (edited) {
    label = 'Пересчитать меню';
    message = 'Настройки изменены, меню ещё не пересчитано.';
    action = () => generate();
  } else if (commitState === 'checked') {
    label = 'Повторить сохранение';
    message = 'Предыдущее сохранение пока не подтверждено. Повтор не создаст второе меню.';
  } else if (view?.result.issues.length) {
    message = 'Есть предупреждения — покажем их перед сохранением.';
  }
  if (phase === 'save') label = 'Сохраняем меню…';
  else if (busy) label = 'Обновляем…';
  return (
    <div className={`planning-flow ${view && tab !== 'options' ? 'with-planning-action' : ''}`}>
      {tab === 'options' && (
        <PageHeading
          title="Составить меню"
          description="Укажите людей, период и бюджет. Остальное мы подберём."
        />
      )}
      {view && !view.result.options.balancedMeals && (
        <Notice>
          Этот подбор создан по прежним правилам количества. Сохранённые данные не меняются. Для
          индивидуальных граммов, гарниров и учёта клетчатки создайте новый подбор.
          <button
            className="button"
            disabled={busy}
            onClick={() => {
              newPlan();
              setDraft((d) => ({ ...d, balanced: true }));
            }}
          >
            Новый подбор с гарнирами
          </button>
        </Notice>
      )}
      {!profile?.dailyCalorieTarget && (
        <Notice>
          Чтобы составить меню, сначала задайте свою суточную норму.
          <Link className="text-link" to="/onboarding">
            Рассчитать норму
          </Link>
        </Notice>
      )}
      {error && (
        <div role="alert" className="planning-error">
          <Notice tone="warning">
            <p>{error.message}</p>
            {recovery?.action !== 'none' && recovery && view && (
              <button className="text-button" disabled={busy || !online} onClick={applyRecovery}>
                {recovery.label}
              </button>
            )}
          </Notice>
        </div>
      )}
      {phase === 'load' && !view && <Loading />}
      {!view && requestedId && error ? (
        <section className="panel planning-unavailable">
          <h1>Этот вариант сейчас недоступен</h1>
          <p>Создайте новый подбор или откройте покупки по сохранённому меню.</p>
          <button className="button primary" onClick={newPlan}>
            Создать новый подбор
          </button>
          {error instanceof ApiError && (error.status === 0 || error.status >= 500) && (
            <button
              className="button secondary"
              disabled={busy || !online}
              onClick={() =>
                run(
                  'load',
                  (s) => periodApi.get(requestedId!, s),
                  (next) => receive(next, params.get('tab') === 'shopping' ? 'shopping' : 'menu'),
                )
              }
            >
              Повторить загрузку
            </button>
          )}
          <Link className="button secondary" to="/shopping?view=calendar">
            Открыть сохранённые покупки
          </Link>
        </section>
      ) : null}
      {tab === 'options' && (!requestedId || view) && (
        <>
          <PlanningSetupForm
            draft={draft}
            onChange={changeDraft}
            members={household.data || []}
            chains={chains.data || []}
            membersLoading={household.isPending}
            chainsLoading={chains.isPending}
            membersError={household.isError}
            chainsError={chains.isError}
            retryMembers={() => household.refetch()}
            retryChains={() => chains.refetch()}
            busy={busy}
            online={online}
            target={profile?.dailyCalorieTarget}
            onSubmit={() => generate()}
            onCancel={
              view
                ? () => {
                    setDraft(fromOptions(view.result.options));
                    setError(null);
                    switchTab('menu');
                  }
                : undefined
            }
            editing={!!view}
            advanced={advanced}
            onAdvanced={setAdvanced}
            invalid={invalid}
          />
          {view && edited && (
            <Notice>
              Настройки изменены, меню ещё не пересчитано. Старый вариант не будет сохранён с новыми
              условиями.
            </Notice>
          )}
        </>
      )}
      {view && tab !== 'options' && (
        <>
          <header className="planning-result-heading">
            <div>
              <span className="eyebrow">
                {saved
                  ? 'МЕНЮ СОХРАНЕНО'
                  : missing.length
                    ? 'НУЖНО ЗАВЕРШИТЬ ПОДБОР'
                    : 'ВЕСЬ ПЕРИОД ГОТОВ'}
              </span>
              {tab === 'shopping' ? (
                <h2>
                  Меню на {view.result.days.length} дн. · {view.result.participants?.length || 1}{' '}
                  чел.
                </h2>
              ) : (
                <h1>
                  Меню на {view.result.days.length} дн. · {view.result.participants?.length || 1}{' '}
                  чел.
                </h1>
              )}
              <p>
                {labelDate(view.result.options.from)} — {labelDate(view.result.options.to)}
              </p>
            </div>
            <div className="planning-result-tools">
              {saved && (
                <Link className="button secondary" to="/cooking">
                  Готовить на несколько дней
                </Link>
              )}
              <button className="button secondary" disabled={busy} onClick={editConditions}>
                <SlidersHorizontal size={16} />
                Изменить условия
              </button>
              {!saved && (
                <button
                  className="text-button"
                  disabled={busy || !online || expired || profileChanged}
                  onClick={retryWhole}
                >
                  <RefreshCw size={16} />
                  Другой вариант
                </button>
              )}
            </div>
          </header>
          {tab === 'menu' && (
            <section className="panel planning-total" aria-label="Оценка покупок">
              <strong>{basketSummary(view)}</strong>
              <p className="caption">
                Купить: {shoppingProductCount(view)} разных продуктов
                {view.result.options.maxShoppingProducts != null
                  ? ` из ${view.result.options.maxShoppingProducts}`
                  : ''}{' '}
                · общие упаковки на весь период
              </p>
              <span>{budgetSummary(view)}</span>
              {missing.length > 0 && <small>Сумма относится только к подобранным блюдам.</small>}
            </section>
          )}
          <div className="planning-result-navigation" aria-label="Просмотр результата">
            <button
              className={`button ${tab === 'menu' ? 'primary' : 'quiet'}`}
              aria-pressed={tab === 'menu'}
              disabled={busy}
              onClick={() => switchTab('menu')}
            >
              Меню
            </button>
            <button
              className={`button ${tab === 'shopping' ? 'primary' : 'quiet'}`}
              aria-pressed={tab === 'shopping'}
              disabled={busy}
              onClick={() => switchTab('shopping')}
            >
              <ShoppingBasket size={17} />
              {saved ? 'Покупки' : 'Предварительный список'}
            </button>
          </div>
          {missing.length > 0 && (
            <Notice tone="warning">
              <strong>{incompleteSummary(view)}</strong>
              <p>
                {view.result.searchStatus === 'LIMIT_REACHED'
                  ? 'Расчёт остановился раньше завершения. Повторите его для всего периода; закреплённые блюда сохранятся.'
                  : 'Подходящих блюд не хватило при выбранных условиях. Можно изменить время готовки или правила повторов.'}
              </p>
              {view.result.searchStatus !== 'LIMIT_REACHED' && (
                <button
                  className="text-button"
                  disabled={busy || !online || expired}
                  onClick={() => setRelax(true)}
                >
                  Разрешить больше повторов
                </button>
              )}
            </Notice>
          )}
          {invalid && (
            <p role="alert" className="form-error">
              {invalid}
            </p>
          )}
          {tab === 'menu' ? (
            <>
              <PeriodMenuOverview
                view={view}
                busy={busy}
                onMeal={(entry) => {
                  setMealId(entry.id);
                  setAlternatives(null);
                }}
                onMissing={() => {
                  setAdvanced(true);
                  editConditions();
                }}
              />
              <details className="planning-calculation-details">
                <summary>
                  Подробности расчёта и предупреждения ({view.result.issues.length})
                </summary>
                {view.result.issues.map((i, n) => (
                  <p key={`${i.code}-${n}`}>{i.message}</p>
                ))}
                <p className="caption">
                  Оценка на {new Date(view.result.dataAsOf).toLocaleString('ru-RU')}. Цены и наличие
                  могут отличаться.
                </p>
                {!saved && (
                  <button
                    className="button secondary"
                    disabled={busy || !online || expired || profileChanged}
                    onClick={refresh}
                  >
                    Обновить оценку цен и запасов
                  </button>
                )}
              </details>
            </>
          ) : (
            <section id="period-shopping">
              <PeriodShoppingList
                key={`${userId}:${view.id}`}
                view={view}
                userId={userId}
                onMissing={() => switchTab('menu')}
              />
              <div className="purchase-footer">
                <Link
                  to={`/shopping?view=calendar&from=${view.result.options.from}&to=${view.result.options.to}`}
                >
                  Пересчитать покупки по текущим ценам и запасам
                </Link>
                <Link to="/pantry">Домашние запасы</Link>
              </div>
            </section>
          )}
          <PlanningActionBar
            message={
              !online && !saved
                ? 'Нет сети. Сохранение станет доступно после подключения.'
                : message
            }
            label={label}
            onAction={action}
            disabled={busy || (!online && !saved)}
            secondary={
              saved && tab !== 'shopping'
                ? {
                    label: 'Открыть покупки',
                    onClick: () => switchTab('shopping'),
                  }
                : undefined
            }
          />
        </>
      )}
      {meal && (
        <MealChoiceDialog
          entry={meal}
          alternatives={alternatives}
          busy={busy}
          editable={!saved && !expired && !profileChanged && !edited && online}
          onClose={() => {
            setMealId(null);
            setAlternatives(null);
          }}
          onAlternatives={() =>
            run('alternatives', (s) => periodApi.alternatives(view!, meal, s), setAlternatives)
          }
          onReplaceComponent={(componentId) =>
            run(
              'alternatives',
              (s) => periodApi.alternatives(view!, meal, s, componentId),
              (items) => setAlternatives(items.map((a) => ({ ...a, componentId }))),
            )
          }
          onLockComponent={(componentId, locked) =>
            run(
              'lock',
              (s) => periodApi.lockComponent(view!, meal, componentId, locked, s),
              (next) => receive(next, 'menu'),
            )
          }
          onReplace={(a) =>
            run(
              'replace',
              (s) => periodApi.replace(view!, meal, a, s),
              (next) => {
                receive(next, 'menu');
                setMealId(null);
              },
            )
          }
          onLock={() =>
            run(
              'lock',
              (s) => periodApi.lock(view!, meal, s),
              (next) => receive(next, 'menu'),
            )
          }
        />
      )}
      {view && warningFor === revisionKey && (
        <PlanningWarningsDialog
          view={view}
          busy={busy}
          onClose={() => !busy && setWarningFor(null)}
          onConfirm={() => requestSave(true)}
        />
      )}
      {relax && view && (
        <Dialog title="Изменить правила повторов?" onClose={() => setRelax(false)}>
          <p>
            Создадим новый вариант: один рецепт до {view.result.days.length} раз, без промежутка
            между днями. Бюджет, время готовки и ограничения питания останутся прежними. Закрепления
            текущего варианта не переносятся.
          </p>
          <div className="period-actions">
            <button
              className="button primary"
              disabled={busy || !online}
              onClick={() => {
                setRelax(false);
                generate({
                  ...view.result.options,
                  maxRecipeOccurrences: view.result.days.length,
                  minRepeatGapDays: 0,
                });
              }}
            >
              Разрешить повторы и пересчитать
            </button>
            <button className="button secondary" onClick={() => setRelax(false)}>
              Оставить условия
            </button>
          </div>
        </Dialog>
      )}
      {phase && phase !== 'load' && (
        <p role="status" className="planning-operation">
          {phase === 'generate'
            ? 'Составляем меню на весь период…'
            : phase === 'save'
              ? 'Сохраняем меню…'
              : 'Обновляем расчёт…'}
        </p>
      )}
    </div>
  );
}
