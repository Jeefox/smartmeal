import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { LockKeyhole, UnlockKeyhole, RefreshCw, ShoppingBasket, Sparkles } from 'lucide-react';
import { householdApi } from '../household';
import { request } from '../api';
import { dateKey, labelDate, number, shiftDate } from '../domain';
import PeriodShoppingList from '../components/PeriodShoppingList';
import { missingMeals, saveBlockReason, rememberPeriod } from '../period-shopping';
import { mealLabels, mealPlanKey } from '../mealplans';
import {
  BackendPending,
  Dialog,
  Loading,
  Notice,
  NutritionValues,
  PageHeading,
} from '../components/ui';
import { useApp, useOnline } from '../state';
import { useSession } from '../session';
import { invalidateShopping } from '../shopping';
import { readCity } from '../store-preferences';
import { budgetLabel, periodApi, validatePlanningOptions } from '../period-planning';
import type {
  Alternative,
  PeriodView,
  PlanningMeal,
  PlanningOptions,
  Selection,
} from '../period-planning';

const meals: PlanningMeal[] = ['BREAKFAST', 'LUNCH', 'DINNER'];
const money = (value: number | null) =>
  value == null ? 'Цена неизвестна' : `${number(value, 2)} ₽`;
export default function PeriodMenu() {
  const { mode } = useApp();
  const { profile } = useSession();
  return mode === 'demo' ? (
    <BackendPending
      title="Недельный подбор работает через API"
      description="Включите SmartMeal API и войдите в аккаунт: расчёт использует ваши ограничения и сохранённые цены."
    />
  ) : (
    <LivePeriodMenu key={profile?.userId || 'anonymous'} />
  );
}
function LivePeriodMenu() {
  const { profile } = useSession();
  const { selectedDate, setSelectedDate, notify } = useApp();
  const online = useOnline(),
    client = useQueryClient();
  const [params, setParams] = useSearchParams();
  const [from, setFrom] = useState(params.get('from') || selectedDate || dateKey(new Date()));
  const [length, setLength] = useState(7);
  const [budget, setBudget] = useState('');
  const [city, setCity] = useState(readCity() || 'Тула');
  const [selectedChains, setSelectedChains] = useState<number[]>([]);
  const [split, setSplit] = useState(false),
    [estimated, setEstimated] = useState(true);
  const [selectedMembers, setSelectedMembers] = useState<string[]>([]);
  const household = useQuery({
    queryKey: ['household', profile?.userId],
    queryFn: ({ signal }) => householdApi.list(signal),
    enabled: !!profile?.userId,
  });
  const [usePantry, setUsePantry] = useState(true);
  const [repeat, setRepeat] = useState(2),
    [gap, setGap] = useState(2),
    [saving, setSaving] = useState(50);
  const [times, setTimes] = useState<Record<PlanningMeal, number>>({
    BREAKFAST: 15,
    LUNCH: 45,
    DINNER: 45,
  });
  const [view, setView] = useState<PeriodView | null>(null),
    [day, setDay] = useState(0);
  const [tab, setTabValue] = useState<'options' | 'menu' | 'shopping'>(
    params.get('tab') === 'shopping' ? 'shopping' : 'options',
  );
  const [advanced, setAdvanced] = useState(false);
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30000);
    return () => window.clearInterval(timer);
  }, []);
  function setTab(next: 'options' | 'menu' | 'shopping') {
    setTabValue(next);
    if (view) setParams({ id: view.id, tab: next }, { replace: true });
  }
  useEffect(() => {
    if (view && profile?.userId) rememberPeriod(profile.userId, view);
  }, [view, profile?.userId]);
  const [busy, setBusy] = useState(false),
    [error, setError] = useState('');
  const [alternativeFor, setAlternativeFor] = useState<Selection | null>(null),
    [alternatives, setAlternatives] = useState<Alternative[]>([]);
  const [acceptWarnings, setAcceptWarnings] = useState(false);
  const alive = useRef(true),
    locked = useRef(false),
    requestNumber = useRef(0),
    abort = useRef<AbortController | null>(null);
  const commitKeys = useRef(new Map<string, string>());
  const chains = useQuery({
    queryKey: ['retail-chains'],
    queryFn: ({ signal }) => request<{ id: number; name: string }[]>('/retail-chains', { signal }),
  });
  useEffect(() => {
    if (chains.data?.length)
      setSelectedChains((current) => (current.length ? current : [chains.data![0].id]));
  }, [chains.data]);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      abort.current?.abort();
      requestNumber.current++;
    };
  }, []);
  const requestedId = params.get('id');
  useEffect(() => {
    if (!requestedId || view?.id === requestedId) return;
    const controller = new AbortController(),
      serial = ++requestNumber.current;
    setBusy(true);
    locked.current = true;
    periodApi
      .get(requestedId, controller.signal)
      .then((next) => {
        if (!alive.current || serial !== requestNumber.current) return;
        setView(next);
        setDay(0);
        setTabValue(params.get('tab') === 'shopping' ? 'shopping' : 'menu');
        const options = next.result.options;
        setFrom(options.from);
        setLength(next.result.days.length);
        setBudget(options.budgetRub == null ? '' : String(options.budgetRub));
        setCity(options.city);
        setSelectedChains(options.allowedChainIds);
        setSplit(options.purchaseMode === 'TWO_CHAINS');
        setTimes(options.maxPreparationTimeByMeal);
        setRepeat(options.maxRecipeOccurrences);
        setGap(options.minRepeatGapDays);
        setEstimated(options.allowEstimatedPrices);
        setUsePantry(options.usePantry !== false);
        setSelectedMembers(options.householdMemberIds || []);
        setSaving(options.minSplitSavingRub);
      })
      .catch((e) => {
        if (alive.current && serial === requestNumber.current && e.name !== 'AbortError')
          setError(e.message);
      })
      .finally(() => {
        if (alive.current && serial === requestNumber.current) {
          setBusy(false);
          locked.current = false;
        }
      });
    return () => controller.abort();
  }, [requestedId, view?.id]);
  async function run<T>(task: (signal: AbortSignal) => Promise<T>, done: (value: T) => void) {
    if (locked.current || !online) return;
    locked.current = true;
    setBusy(true);
    setError('');
    const controller = new AbortController(),
      serial = ++requestNumber.current;
    abort.current = controller;
    try {
      const result = await task(controller.signal);
      if (alive.current && serial === requestNumber.current) done(result);
    } catch (e) {
      if (
        alive.current &&
        serial === requestNumber.current &&
        e instanceof Error &&
        e.name !== 'AbortError'
      )
        setError(e.message);
    } finally {
      if (alive.current && serial === requestNumber.current) {
        locked.current = false;
        setBusy(false);
      }
    }
  }
  function receive(
    next: PeriodView,
    nextTab: 'menu' | 'shopping' | 'options' = tab === 'options' ? 'menu' : tab,
  ) {
    setView(next);
    setAcceptWarnings(false);
    setTabValue(nextTab);
    setParams({ id: next.id, tab: nextTab }, { replace: true });
  }
  function generate(event?: React.FormEvent) {
    event?.preventDefault();
    const options: PlanningOptions = {
      from,
      to: shiftDate(from, length - 1),
      budgetRub: budget.trim() ? Number(budget) : null,
      allowedChainIds: selectedChains,
      city,
      purchaseMode: split ? 'TWO_CHAINS' : 'SINGLE_CHAIN',
      maxPreparationTimeByMeal: times,
      maxRecipeOccurrences: repeat,
      minRepeatGapDays: gap,
      allowEstimatedPrices: estimated,
      minSplitSavingRub: saving,
      seed: 0,
      usePantry,
      householdMemberIds: selectedMembers,
    };
    const invalid = validatePlanningOptions(options);
    if (invalid) {
      setError(invalid);
      return;
    }
    run(
      (signal) => periodApi.create(options, signal),
      (next) => {
        receive(next, 'menu');
        setDay(0);
      },
    );
  }
  function retryPeriod(allowRepeats: boolean) {
    if (!view || saved) return;
    const options = {
      ...view.result.options,
      maxRecipeOccurrences: allowRepeats
        ? Math.min(14, periodDates.length)
        : view.result.options.maxRecipeOccurrences,
      minRepeatGapDays: allowRepeats ? 0 : view.result.options.minRepeatGapDays,
    };
    run(
      (signal) => periodApi.create(options, signal),
      (next) => {
        setRepeat(next.result.options.maxRecipeOccurrences);
        setGap(next.result.options.minRepeatGapDays);
        receive(next, 'menu');
        setDay(0);
      },
    );
  }
  async function save() {
    if (!view) return;
    let key = commitKeys.current.get(view.id);
    if (!key) {
      key = crypto.randomUUID();
      commitKeys.current.set(view.id, key);
    }
    run(
      (signal) => periodApi.commit(view, key!, acceptWarnings, signal),
      (next) => {
        receive(next, 'shopping');
        setSelectedDate(next.result.options.from);
        notify(`Сохранено дней: ${next.savedPlanIds.length}`);
        for (const item of next.result.days)
          client.invalidateQueries({ queryKey: mealPlanKey(profile!.userId, item.date) });
        invalidateShopping(client, profile!.userId);
      },
    );
  }
  const saved = Boolean(view?.savedPlanIds.length),
    expired = Boolean(view && Date.parse(view.expiresAt) <= now);
  const profileChanged = Boolean(
    view &&
    profile?.dailyCalorieTarget !==
      (view.result.participants?.find((p) => p.accountOwner)?.dailyCalorieTarget ??
        view.result.dailyCalorieTarget),
  );
  const disabled = busy || !online || expired || profileChanged || saved;
  const periodDates = view
    ? Array.from(
        {
          length:
            Math.round(
              (Date.parse(view.result.options.to) - Date.parse(view.result.options.from)) /
                86400000,
            ) + 1,
        },
        (_, index) => shiftDate(view.result.options.from, index),
      )
    : [];
  const selectedDayDate = periodDates[Math.min(day, periodDates.length - 1)];
  const selectedDay =
    view && selectedDayDate
      ? (view.result.days.find((item) => item.date === selectedDayDate) ?? {
          date: selectedDayDate,
          entries: [],
          nutrition: { calories: 0, protein: 0, fat: 0, carbs: 0 },
          calorieDeviation: 0,
          withinTargetTolerance: false,
        })
      : null;
  const basket = view?.result.shopping;
  const missing = view ? missingMeals(view) : [];
  const blockReason = view ? saveBlockReason(view, profile?.dailyCalorieTarget, now) : null;
  function showGaps() {
    const first = missing[0];
    if (first) setDay(Math.max(0, periodDates.indexOf(first.date)));
    setTab('menu');
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
  function editOptions() {
    setAdvanced(true);
    setTab('options');
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
  const savePanel = view && (
    <section className="panel period-save" aria-label="Сохранение меню">
      {saved ? (
        <div className="period-saved">
          <span className="eyebrow">МЕНЮ В КАЛЕНДАРЕ</span>
          <h2>Сохранено дней: {view.savedPlanIds.length}.</h2>
          <p>Список покупок готов. Отмечайте продукты по мере покупки.</p>
          <Link className="text-link" to={`/week?date=${view.result.options.from}`}>
            Открыть меню недели
          </Link>
        </div>
      ) : (
        <>
          <h2>Сохранить меню в календарь</h2>
          {blockReason ? (
            <div className="period-save-block" role="status">
              <strong>{blockReason}</strong>
              {missing.length > 0 ? (
                <p>Галочка согласия с приблизительными ценами не заполняет пропущенные блюда.</p>
              ) : (
                <p>Список можно посмотреть, но перед сохранением нужно обновить подбор.</p>
              )}
              <div className="period-actions">
                {missing.length > 0 && (
                  <button className="button secondary" onClick={showGaps}>
                    Показать незаполненный день
                  </button>
                )}
                {expired || profileChanged ? (
                  <button
                    className="button secondary"
                    disabled={busy || !online}
                    onClick={() => generate()}
                  >
                    Создать свежий подбор
                  </button>
                ) : (
                  <button className="button secondary" onClick={editOptions}>
                    Изменить настройки подбора
                  </button>
                )}
              </div>
            </div>
          ) : view.result.issues.length > 0 ? (
            <div className="period-save-warnings">
              <p>Перед сохранением учтите:</p>
              <ul>
                {view.result.issues.map((i) => (
                  <li key={i.code}>{i.message}</li>
                ))}
              </ul>
              <label className="period-accept">
                <input
                  type="checkbox"
                  checked={acceptWarnings}
                  onChange={(e) => setAcceptWarnings(e.target.checked)}
                />
                Я ознакомился с предупреждениями и сохраняю меню
              </label>
            </div>
          ) : (
            <p className="muted">Все дни заполнены. Сохраните выбранные блюда одним действием.</p>
          )}
          {!online && <p role="status">Нет сети. Сохранение станет доступно после подключения.</p>}
          <button
            className="button primary"
            disabled={
              busy || !online || !!blockReason || (view.result.issues.length > 0 && !acceptWarnings)
            }
            onClick={save}
          >
            {busy ? 'Подождите…' : 'Сохранить меню на весь период'}
          </button>
          {!blockReason && view.result.issues.length > 0 && !acceptWarnings && (
            <p className="caption">Для сохранения отметьте согласие с предупреждениями выше.</p>
          )}
        </>
      )}
    </section>
  );
  return (
    <>
      {tab !== 'shopping' && (
        <PageHeading
          eyebrow="ПИТАНИЕ И ПОКУПКИ ВМЕСТЕ"
          title="Меню под ваш бюджет"
          description="Подбор на 1–14 дней: ограничения из профиля, общий список продуктов и целые упаковки."
        />
      )}
      {!profile?.dailyCalorieTarget && (
        <Notice>
          <Link to="/onboarding">Заполните анкету</Link>, чтобы рассчитать суточную цель.
        </Notice>
      )}
      <div className="period-tabs" role="tablist" aria-label="Недельный подбор">
        {(['options', 'menu', 'shopping'] as const).map((key, i) => (
          <button
            key={key}
            role="tab"
            id={`period-tab-${key}`}
            aria-controls={`period-${key}`}
            aria-selected={tab === key}
            disabled={key !== 'options' && !view}
            className={`button ${tab === key ? 'primary' : 'secondary'}`}
            onClick={() => setTab(key)}
          >
            {i + 1}. {key === 'options' ? 'Настройки' : key === 'menu' ? 'Меню' : 'Покупки'}
          </button>
        ))}
      </div>
      {error && (
        <div className="notice warning" role="alert">
          {error}
          {!view && <Link to="/shopping?view=calendar">Открыть покупки по сохранённым меню</Link>}
          {view && (
            <button
              className="text-button"
              disabled={busy || !online}
              onClick={() => run((signal) => periodApi.get(view.id, signal), receive)}
            >
              Обновить предпросмотр
            </button>
          )}
        </div>
      )}
      {profileChanged && !saved && (
        <Notice>Суточная цель изменилась. Создайте новый подбор с актуальными настройками.</Notice>
      )}

      <section
        id="period-options"
        role="tabpanel"
        aria-labelledby="period-tab-options"
        hidden={tab !== 'options'}
      >
        <form className="panel period-options" onSubmit={generate}>
          <div className="period-fields">
            <label>
              Начало периода
              <input type="date" required value={from} onChange={(e) => setFrom(e.target.value)} />
            </label>
            <label>
              Дней
              <input
                type="number"
                min="1"
                max="14"
                required
                value={length}
                onChange={(e) => setLength(Number(e.target.value))}
              />
            </label>
            <label>
              Бюджет на покупки, ₽
              <input
                type="number"
                min="0.01"
                step="0.01"
                placeholder="Можно не задавать"
                value={budget}
                onChange={(e) => setBudget(e.target.value)}
              />
            </label>
            <label>
              Город
              <input
                required
                maxLength={120}
                value={city}
                onChange={(e) => setCity(e.target.value)}
              />
            </label>
          </div>
          <fieldset className="household-picker">
            <legend>Для кого готовим</legend>
            <p>Вы · {number(profile?.dailyCalorieTarget || 0)} ккал в день — всегда включены.</p>
            {household.isPending && <Loading />}
            {household.error && (
              <Notice>
                Не удалось загрузить профили семьи. Доступен подбор для вас; для семейного подбора
                повторите загрузку.
                <button type="button" className="text-button" onClick={() => household.refetch()}>
                  Повторить
                </button>
              </Notice>
            )}
            <div className="period-checks">
              {household.data?.map((m) => (
                <label key={m.id}>
                  <input
                    type="checkbox"
                    checked={selectedMembers.includes(m.id)}
                    onChange={(e) =>
                      setSelectedMembers((ids) =>
                        e.target.checked ? [...ids, m.id] : ids.filter((id) => id !== m.id),
                      )
                    }
                  />
                  {m.name} · {number(m.dailyCalorieTarget)} ккал
                </label>
              ))}
            </div>
            <p>Бюджет — на всех. Аллергии и исключения применяются ко всему общему меню.</p>
            <Link className="text-link" to="/household">
              Добавить человека или изменить ограничения
            </Link>
          </fieldset>
          <fieldset>
            <legend>Доступные сети</legend>
            {chains.isPending ? (
              <Loading />
            ) : chains.error ? (
              <Notice>
                Не удалось загрузить сети.{' '}
                <button type="button" className="text-button" onClick={() => chains.refetch()}>
                  Повторить
                </button>
              </Notice>
            ) : (
              <div className="period-checks">
                {chains.data?.map((chain) => (
                  <label key={chain.id}>
                    <input
                      type="checkbox"
                      checked={selectedChains.includes(chain.id)}
                      onChange={(e) =>
                        setSelectedChains((current) =>
                          e.target.checked
                            ? [...current, chain.id]
                            : current.filter((id) => id !== chain.id),
                        )
                      }
                    />
                    {chain.name}
                  </label>
                ))}
              </div>
            )}
          </fieldset>
          <div className="period-fields">
            {meals.map((meal) => (
              <label key={meal}>
                {mealLabels[meal]}: до, мин
                <input
                  type="number"
                  min="0"
                  max="240"
                  required
                  value={times[meal]}
                  onChange={(e) =>
                    setTimes((current) => ({ ...current, [meal]: Number(e.target.value) }))
                  }
                />
              </label>
            ))}
          </div>
          <details open={advanced} onToggle={(e) => setAdvanced(e.currentTarget.open)}>
            <summary>Повторы и покупки</summary>
            <label className="pantry-check">
              <input
                type="checkbox"
                checked={usePantry}
                onChange={(e) => setUsePantry(e.target.checked)}
              />
              Учитывать запасы дома
            </label>
            <Link to="/pantry">Сверить домашние запасы</Link>
            <div className="period-fields">
              <label>
                Один рецепт: максимум раз
                <input
                  type="number"
                  min="1"
                  max="14"
                  value={repeat}
                  onChange={(e) => setRepeat(Number(e.target.value))}
                />
              </label>
              <label>
                Полных дней между повторами
                <input
                  type="number"
                  min="0"
                  max="14"
                  value={gap}
                  onChange={(e) => setGap(Number(e.target.value))}
                />
              </label>
              <label>
                Минимальная экономия для второй сети, ₽
                <input
                  type="number"
                  min="0"
                  step="0.01"
                  value={saving}
                  onChange={(e) => setSaving(Number(e.target.value))}
                />
              </label>
            </div>
            <div className="period-checks">
              <label>
                <input
                  type="checkbox"
                  checked={split}
                  onChange={(e) => setSplit(e.target.checked)}
                />
                Разрешить покупки максимум в двух сетях
              </label>
              <label>
                <input
                  type="checkbox"
                  checked={estimated}
                  onChange={(e) => setEstimated(e.target.checked)}
                />
                Разрешить приблизительные цены
              </label>
            </div>
          </details>
          <p className="muted">
            Аллергии, исключения и оборудование — из профиля. Цель:{' '}
            {profile?.dailyCalorieTarget ?? 'не задана'} ккал. Изменения формы применяются после
            нового подбора.
          </p>
          <Link to="/planning-preferences">Изменить аллергии, исключения и оборудование</Link>
          <button
            className="button primary"
            disabled={busy || !online || !profile?.dailyCalorieTarget || !selectedChains.length}
          >
            <Sparkles size={17} />
            {busy ? 'Подбираем…' : 'Подобрать меню'}
          </button>
        </form>
      </section>
      {view && (
        <>
          {tab === 'options' && (
            <div className="period-summary" aria-live="polite">
              <div className="panel">
                <span>Покупки на период</span>
                <strong>
                  {basket?.estimatedTotal == null
                    ? `Известно ${money(basket?.knownPurchaseCost ?? 0)}`
                    : `≈ ${money(basket.estimatedTotal)}`}
                </strong>
                <small>{budgetLabel[basket!.budgetStatus]}</small>
              </div>
              <div className="panel">
                <span>Бюджет</span>
                <strong>
                  {view.result.options.budgetRub == null
                    ? 'Не задан'
                    : money(view.result.options.budgetRub)}
                </strong>
                <small>
                  {labelDate(view.result.options.from)} — {labelDate(view.result.options.to)}
                </small>
              </div>
              <div className="panel">
                <span>Продукты с ценой</span>
                <strong>
                  {basket!.pricedProducts} из {basket!.items.length}
                </strong>
                <small>
                  Приблизительных: {basket!.estimatedProducts} · неизвестных:{' '}
                  {basket!.unknownProducts}
                </small>
              </div>
            </div>
          )}
          {tab !== 'shopping' && blockReason && (missing.length === 0 || tab === 'options') && (
            <div className="notice warning period-incomplete" role="status">
              <strong>{blockReason}</strong>
              <button className="text-button" onClick={editOptions}>
                Изменить настройки
              </button>
            </div>
          )}
          {tab !== 'shopping' && view.result.issues.length > 0 && (
            <details className="panel period-issues">
              <summary>Предупреждения: {view.result.issues.length}</summary>
              {view.result.issues.map((issue) => (
                <p key={issue.code}>{issue.message}</p>
              ))}
            </details>
          )}
          <section
            id="period-menu"
            role="tabpanel"
            aria-labelledby="period-tab-menu"
            hidden={tab !== 'menu'}
          >
            <section className="panel period-overview" aria-label="Меню на весь период">
              <header>
                <div>
                  <span className="eyebrow">
                    {saved
                      ? 'СОХРАНЕНО В КАЛЕНДАРЕ'
                      : missing.length
                        ? 'ПОДБОР НЕ ЗАВЕРШЁН'
                        : 'ВЕСЬ ПЕРИОД ГОТОВ'}
                  </span>
                  <h2>
                    Меню на {periodDates.length} дн.
                    {(view.result.participants?.length || 0) > 1
                      ? ` · ${view.result.participants!.length} чел.`
                      : ''}
                  </h2>
                  <p>
                    Подбор сразу заполняет весь период. Выбор дня ниже — только просмотр и замена
                    блюд. Сохранять каждый день отдельно не нужно.
                  </p>
                </div>
                <div className="period-overview-cost">
                  <span>
                    Покупки{' '}
                    {basket!.estimatedTotal == null
                      ? `от ${money(basket!.knownPurchaseCost)}`
                      : `≈ ${money(basket!.estimatedTotal)}`}
                  </span>
                  <small>{budgetLabel[basket!.budgetStatus]}</small>
                </div>
                <strong className="period-ready-count">
                  {periodDates.filter((date) => !missing.some((m) => m.date === date)).length} из{' '}
                  {periodDates.length} дней заполнено
                </strong>
              </header>
              {missing.length > 0 && !saved && (
                <div className="period-retry">
                  {view.result.searchStatus === 'LIMIT_REACHED' ? (
                    <>
                      <strong>Поиск остановился до заполнения всех дней.</strong>
                      <p>
                        Это не означает, что подходящих блюд нет. Повторите подбор сразу на весь
                        период с теми же настройками. Будет создан новый вариант; закрепления
                        текущего варианта не переносятся.
                      </p>
                      <button
                        className="button primary"
                        disabled={busy || !online || profileChanged}
                        onClick={() => retryPeriod(false)}
                      >
                        <RefreshCw size={17} />
                        {busy ? 'Подбираем весь период…' : 'Повторить подбор всего периода'}
                      </button>
                    </>
                  ) : (
                    <>
                      <strong>На часть дней не хватило подходящих блюд.</strong>
                      <p>
                        Можно создать новый вариант на весь период: разрешить повтор блюда до{' '}
                        {periodDates.length} раз, в том числе в соседние дни. Бюджет, время готовки
                        и ограничения питания сохранятся. Закреплённые блюда текущего варианта не
                        переносятся; сохранённые меню в календаре не меняются.
                      </p>
                      <button
                        className="button primary"
                        disabled={busy || !online || profileChanged}
                        onClick={() => retryPeriod(true)}
                      >
                        <Sparkles size={17} />
                        {busy ? 'Подбираем весь период…' : 'Подобрать весь период с повторами'}
                      </button>
                      <p className="caption">
                        Если блюд всё ещё недостаточно, покажем оставшиеся пропуски — увеличьте
                        время готовки или добавьте рецепты.
                      </p>
                    </>
                  )}
                </div>
              )}
              <div className="period-overview-actions">
                <button
                  className="button primary"
                  onClick={() =>
                    document
                      .getElementById('period-menu-save')
                      ?.scrollIntoView({ behavior: 'smooth', block: 'start' })
                  }
                >
                  {saved ? 'Результат сохранения' : 'К сохранению всего периода'}
                </button>
                <button className="button secondary" onClick={() => setTab('shopping')}>
                  <ShoppingBasket size={17} />
                  Что купить
                </button>
              </div>
              <div className="period-overview-days">
                {periodDates.map((date, i) => {
                  const entries = view.result.entries.filter((entry) => entry.date === date);
                  return (
                    <article
                      className={`period-overview-day ${day === i ? 'is-selected' : ''}`}
                      key={date}
                    >
                      <header>
                        <h3>{labelDate(date)}</h3>
                        <span>
                          {missing.some((m) => m.date === date) ? 'Есть пропуски' : 'Готово'}
                        </span>
                      </header>
                      <dl>
                        {meals.map((meal) => {
                          const entry = entries.find((e) => e.mealType === meal);
                          return (
                            <div key={meal}>
                              <dt>{mealLabels[meal]}</dt>
                              <dd className={entry ? '' : 'period-overview-gap'}>
                                {entry?.recipe.name || 'Не удалось подобрать'}
                              </dd>
                            </div>
                          );
                        })}
                      </dl>
                      <button
                        className="text-button"
                        aria-pressed={day === i}
                        onClick={() => {
                          setDay(i);
                          document
                            .getElementById('period-day-detail')
                            ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
                        }}
                        aria-label={`Подробнее: ${labelDate(date)}`}
                      >
                        Посмотреть и изменить
                      </button>
                    </article>
                  );
                })}
              </div>
            </section>
            {tab === 'menu' && <div id="period-menu-save">{savePanel}</div>}
            {selectedDay && (
              <>
                {(selectedDay.people?.length || 0) > 1 && (
                  <section className="panel household-day">
                    <h3>Питание каждого</h3>
                    {selectedDay.people!.map((p) => (
                      <p key={p.participantId}>
                        <strong>{p.name}</strong>: {number(p.nutrition.calories)} /{' '}
                        {number(p.dailyCalorieTarget)} ккал
                      </p>
                    ))}
                    <p className="caption">
                      Ниже количество порций на всех; индивидуальная доля указана под каждым блюдом.
                    </p>
                  </section>
                )}
                <div className="period-day-heading" id="period-day-detail">
                  <h2>{labelDate(selectedDay.date)}</h2>
                  <span>
                    {number(selectedDay.nutrition.calories)} / {view.result.dailyCalorieTarget} ккал
                    {(view.result.participants?.length || 0) > 1 ? ' на всех' : ''}
                  </span>
                </div>
                <div className="period-meals">
                  {meals.map((meal) => {
                    const entry = selectedDay.entries.find((e) => e.mealType === meal);
                    if (!entry)
                      return (
                        <article className="panel period-missing-meal" key={meal}>
                          <span className="eyebrow">{mealLabels[meal]}</span>
                          <h3>Блюдо не подобрано</h3>
                          <p>При текущих ограничениях подходящего блюда не нашлось.</p>
                          <button className="button secondary" onClick={editOptions}>
                            Настроить подбор
                          </button>
                        </article>
                      );
                    return (
                      <article className="panel" key={entry.id}>
                        {entry.coverImageUrl && (
                          <img
                            className="period-cover"
                            src={entry.coverImageUrl}
                            alt={entry.recipe.name}
                            loading="lazy"
                          />
                        )}
                        <span className="eyebrow">{mealLabels[entry.mealType]}</span>
                        <h3>
                          <Link to={`/recipes/${entry.recipe.recipeId}`}>{entry.recipe.name}</Link>
                        </h3>
                        <p>
                          {number(entry.portions, 2)} порц. · {entry.recipe.preparationTimeMin} мин
                        </p>
                        <p>
                          {number(entry.recipe.nutritionPerServing.calories * entry.portions)} ккал
                        </p>
                        {(entry.personPortions?.length || 0) > 1 && (
                          <ul className="household-portions" aria-label="Порции по людям">
                            {entry.personPortions!.map((p) => (
                              <li key={p.participantId}>
                                <strong>{p.name}</strong>: {number(p.portions, 2)} порц. ·{' '}
                                {number(p.nutrition.calories)} ккал
                              </li>
                            ))}
                          </ul>
                        )}
                        <details>
                          <summary>Почему это блюдо</summary>
                          {entry.explanations.map((text) => (
                            <p key={text}>{text}</p>
                          ))}
                          <Link to={`/recipes/${entry.recipe.recipeId}/planning`}>
                            Настройки порций и группы блюда
                          </Link>
                        </details>
                        <div className="period-actions">
                          <button
                            className="button secondary"
                            disabled={disabled || entry.locked}
                            onClick={() =>
                              run(
                                (signal) => periodApi.alternatives(view, entry, signal),
                                (list) => {
                                  setAlternativeFor(entry);
                                  setAlternatives(list);
                                },
                              )
                            }
                          >
                            Заменить
                          </button>
                          <button
                            className="button secondary"
                            disabled={disabled}
                            aria-pressed={entry.locked}
                            aria-label={`${entry.locked ? 'Открепить' : 'Закрепить'}: ${entry.recipe.name}`}
                            onClick={() =>
                              run((signal) => periodApi.lock(view, entry, signal), receive)
                            }
                          >
                            {entry.locked ? <LockKeyhole size={16} /> : <UnlockKeyhole size={16} />}
                            {entry.locked ? 'Закреплено' : 'Закрепить'}
                          </button>
                        </div>
                      </article>
                    );
                  })}
                </div>
                {!selectedDay.entries.length && (
                  <Notice>
                    Для этого дня не удалось подобрать блюда. Измените время или правила повторов.
                  </Notice>
                )}
                <NutritionValues nutrition={selectedDay.nutrition} />
              </>
            )}
            <div className="period-actions">
              <button
                className="button secondary"
                disabled={disabled}
                onClick={() => run((signal) => periodApi.regenerate(view, signal), receive)}
              >
                <RefreshCw size={16} />
                Пересобрать незакреплённое
              </button>
              <button
                className="button secondary"
                disabled={disabled}
                onClick={() => run((signal) => periodApi.refresh(view, signal), receive)}
              >
                Обновить оценку цен
              </button>
              <button className="button primary" onClick={() => setTab('shopping')}>
                <ShoppingBasket size={17} />К покупкам
              </button>
            </div>
          </section>
          <section
            id="period-shopping"
            role="tabpanel"
            aria-labelledby="period-tab-shopping"
            hidden={tab !== 'shopping'}
          >
            <PeriodShoppingList
              key={`${profile!.userId}:${view.id}`}
              view={view}
              userId={profile!.userId}
              savePanel={tab === 'shopping' ? savePanel : undefined}
              onMissing={showGaps}
            />
            <div className="purchase-footer">
              <Link to="/shopping?view=calendar">Рассчитать покупки для другого периода</Link>
              <Link to="/pantry">Домашние запасы</Link>
            </div>
          </section>
        </>
      )}
      {alternativeFor && (
        <Dialog
          title={`Заменить: ${alternativeFor.recipe.name}`}
          onClose={() => {
            if (!busy) setAlternativeFor(null);
          }}
        >
          <p>Стоимость относится ко всей корзине периода, а не к одной порции.</p>
          {!alternatives.length && (
            <Notice>
              Подходящих замен нет. Можно изменить время, повторы или добавить рецепты.
            </Notice>
          )}
          {alternatives.map((item, i) => (
            <article className="period-alternative" key={`${item.recipeId}-${item.portions}`}>
              <h3>{item.name}</h3>
              <p>
                {item.preparationTimeMin} мин · {number(item.portions, 2)} порц. · день{' '}
                {number(item.dayNutrition.calories)} ккал
              </p>
              <p>
                Покупки: {money(item.newPurchaseCost)}
                {item.deltaPurchaseCost != null &&
                  ` (${item.deltaPurchaseCost >= 0 ? '+' : ''}${money(item.deltaPurchaseCost)})`}{' '}
                · {budgetLabel[item.budgetStatus]}
              </p>
              <button
                className="button primary"
                disabled={busy || !online}
                onClick={() =>
                  run(
                    (signal) => periodApi.replace(view!, alternativeFor, item, signal),
                    (next) => {
                      receive(next);
                      setAlternativeFor(null);
                    },
                  )
                }
              >
                Выбрать вариант {i + 1}
              </button>
            </article>
          ))}
        </Dialog>
      )}
      {busy && <p role="status">Обрабатываем запрос…</p>}
    </>
  );
}
