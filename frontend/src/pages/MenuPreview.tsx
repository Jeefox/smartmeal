import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { CalendarDays, Clock3, Sparkles } from 'lucide-react';
import { ApiError } from '../api';
import { apiFields } from '../catalogue';
import { dateKey, labelDate, number } from '../domain';
import { mealLabels, mealPlanApi, mealPlanKey } from '../mealplans';
import { previewApi, previewFormErrors, timeFields, warningText } from '../preview';
import type { MenuPreview as Preview, PreviewOptions } from '../preview';
import { calendarDay, invalidateShopping } from '../shopping';
import { useApp, useOnline } from '../state';
import { useSession } from '../session';
import { BackendPending, Loading, Notice, NutritionValues, PageHeading } from '../components/ui';

type Bundle = { options: PreviewOptions; result: Preview; profileTarget: number | null };
export default function MenuPreviewPage() {
  const { mode } = useApp();
  if (mode === 'demo')
    return (
      <BackendPending
        title="Предпросмотр доступен через SmartMeal API"
        description="Включите API и войдите в аккаунт, чтобы получить меню от сервера."
      />
    );
  return <LiveMenuPreview />;
}
function LiveMenuPreview() {
  const { profile } = useSession();
  const user = profile!.userId,
    target = profile!.dailyCalorieTarget;
  const { selectedDate, setSelectedDate, notify } = useApp(),
    online = useOnline(),
    client = useQueryClient(),
    navigate = useNavigate();
  const [params] = useSearchParams();
  const [date, setDate] = useState(params.get('date') ?? selectedDate ?? dateKey(new Date()));
  const [times, setTimes] = useState(['15', '45', '45']);
  const [bundle, setBundle] = useState<Bundle | null>(null),
    [errors, setErrors] = useState<Record<string, string>>({}),
    [error, setError] = useState<Error | null>(null);
  const [generating, setGenerating] = useState(false),
    [saving, setSaving] = useState(false),
    [invalidated, setInvalidated] = useState(false);
  const active = useRef(true);
  const serial = useRef(0),
    controller = useRef<AbortController | null>(null),
    generateLock = useRef(false),
    saveLock = useRef(false);
  function discard() {
    serial.current++;
    controller.current?.abort();
    generateLock.current = false;
    setGenerating(false);
    setBundle(null);
    setInvalidated(false);
    setError(null);
    setErrors({});
  }
  useEffect(() => {
    discard();
  }, [target]);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
      serial.current++;
      controller.current?.abort();
    };
  }, []);
  const dateValid = calendarDay(date) != null;
  const existing = useQuery({
    queryKey: mealPlanKey(user, date),
    queryFn: ({ signal }) => mealPlanApi.find(date, signal),
    enabled: dateValid,
    staleTime: 0,
    refetchOnWindowFocus: true,
  });
  const fresh = Boolean(
    bundle &&
    bundle.profileTarget === target &&
    bundle.options.planDate === date &&
    timeFields.every(([key], i) => bundle.options[key] === Number(times[i])),
  );
  async function generate(event: FormEvent) {
    event.preventDefault();
    if (generateLock.current || saveLock.current || !online || target == null) return;
    const next = previewFormErrors(date, times);
    setErrors(next);
    setError(null);
    if (Object.keys(next).length) return;
    const options: PreviewOptions = {
      planDate: date,
      breakfastMaxPreparationTimeMin: Number(times[0]),
      lunchMaxPreparationTimeMin: Number(times[1]),
      dinnerMaxPreparationTimeMin: Number(times[2]),
    };
    generateLock.current = true;
    setGenerating(true);
    setBundle(null);
    setInvalidated(false);
    const requestId = ++serial.current;
    const abort = new AbortController();
    controller.current = abort;
    try {
      const result = await previewApi.generate(options, abort.signal);
      if (requestId !== serial.current) return;
      setBundle({ options, result, profileTarget: target });
    } catch (err) {
      if (requestId !== serial.current || (err instanceof Error && err.name === 'AbortError'))
        return;
      setError(err as Error);
      setErrors(apiFields(err));
    } finally {
      if (requestId === serial.current) {
        generateLock.current = false;
        setGenerating(false);
      }
    }
  }
  async function save() {
    if (
      saveLock.current ||
      generateLock.current ||
      !bundle ||
      !fresh ||
      invalidated ||
      !online ||
      existing.data ||
      existing.isPending ||
      existing.isError
    )
      return;
    saveLock.current = true;
    setSaving(true);
    setError(null);
    setErrors({});
    try {
      const saved = await previewApi.save(bundle.options, bundle.result);
      if (!active.current) return;
      await client.invalidateQueries({ queryKey: ['meal-plan', user], refetchType: 'none' });
      client.setQueryData(mealPlanKey(user, saved.planDate), saved);
      await Promise.allSettled([invalidateShopping(client, user)]);
      setSelectedDate(saved.planDate);
      notify('Меню сохранено как черновик. Подтвердите его после проверки.');
      navigate(`/?date=${saved.planDate}`, { replace: true });
    } catch (err) {
      setError(err as Error);
      setErrors(apiFields(err));
      if (err instanceof ApiError && [0, 404, 409].includes(err.status)) {
        setInvalidated(true);
        client.invalidateQueries({ queryKey: mealPlanKey(user, date) });
      }
    } finally {
      saveLock.current = false;
      setSaving(false);
    }
  }
  const result = fresh ? bundle?.result : undefined;
  return (
    <>
      <Notice>
        Для индивидуальных количеств и сочетаний с гарнирами доступен обновлённый подбор.
        <Link className="button" to="/menu-generation">
          Подбор с гарнирами и клетчаткой
        </Link>
      </Notice>
      <PageHeading
        eyebrow="ИДЕЯ ДЛЯ ВАШЕГО ДНЯ"
        title="Подобрать меню"
        description="Три блюда под вашу сохранённую цель. Сначала предпросмотр, затем отдельное сохранение."
        actions={
          <Link className="button" to={`/?date=${dateValid ? date : selectedDate}`}>
            К обычному меню
          </Link>
        }
      />
      <section className="panel menu-preview-options">
        <form onSubmit={generate} noValidate>
          <div className="preview-options-grid">
            <label>
              <span>Дата меню</span>
              <input
                aria-label="Дата меню"
                type="date"
                value={date}
                disabled={saving}
                onChange={(e) => {
                  discard();
                  setDate(e.target.value);
                }}
                aria-invalid={Boolean(errors.planDate)}
              />
              {errors.planDate && (
                <span className="field-error" role="alert">
                  {errors.planDate}
                </span>
              )}
            </label>
            {timeFields.map(([key, label], index) => (
              <label key={key}>
                <span>{label} · максимум минут</span>
                <input
                  aria-label={`${label} · максимум минут`}
                  inputMode="numeric"
                  type="number"
                  min="0"
                  step="1"
                  value={times[index]}
                  disabled={saving}
                  onChange={(e) => {
                    discard();
                    setTimes((values) =>
                      values.map((value, i) => (i === index ? e.target.value : value)),
                    );
                  }}
                  aria-invalid={Boolean(errors[key])}
                />
                {errors[key] && (
                  <span className="field-error" role="alert">
                    {errors[key]}
                  </span>
                )}
              </label>
            ))}
          </div>
          <div className="preview-generate-actions">
            <button
              className="button primary"
              type="submit"
              disabled={!online || generating || saving || target == null}
            >
              <Sparkles size={18} />
              {generating
                ? 'Подбираем…'
                : bundle
                  ? 'Сформировать заново'
                  : 'Сформировать предпросмотр'}
            </button>
            <span className="caption">
              Цель профиля: {target == null ? 'не задана' : `${number(target)} ккал`} · лимиты по
              умолчанию 15 / 45 / 45 мин.
            </span>
          </div>
        </form>
      </section>
      {target == null && (
        <Notice tone="warning">
          Для подбора нужна сохранённая суточная цель.{' '}
          <Link className="text-link" to="/profile">
            Задать цель в профиле
          </Link>
        </Notice>
      )}
      {!online && (
        <Notice tone="warning">
          Нет сети. Показанный предпросмотр может быть устаревшим; формирование и сохранение
          недоступны.
        </Notice>
      )}
      {dateValid && existing.data && (
        <Notice tone="warning">
          На эту дату уже есть меню. Предпросмотр можно посмотреть, но сохранить поверх
          существующего нельзя.{' '}
          <Link className="text-link" to={`/?date=${date}`}>
            Открыть существующее меню
          </Link>{' '}
          или выберите другую дату.
        </Notice>
      )}
      {existing.isError && (
        <Notice tone="warning">
          Не удалось проверить меню на эту дату: {existing.error.message} Сохранение недоступно до
          проверки.{' '}
          <button
            className="text-link"
            disabled={!online || saving}
            onClick={() => existing.refetch()}
          >
            Проверить дату повторно
          </button>
        </Notice>
      )}
      {error && (
        <div className="estimate-error" role="alert">
          <h2>Не удалось выполнить запрос</h2>
          <p>{error.message}</p>
          {invalidated && (
            <p>
              {error instanceof ApiError && error.status === 0
                ? 'Сервер не подтвердил сохранение: меню могло сохраниться. Сначала откройте обычное меню и проверьте дату.'
                : 'Предпросмотр мог устареть: изменились рецепт, профиль или условия, либо на дату уже создали меню. Проверьте дату и сформируйте предпросмотр заново.'}
            </p>
          )}
          {error instanceof ApiError && error.status === 409 && !result && (
            <p>
              Проверьте цель профиля и доступность трёх разных рецептов для выбранных ограничений.{' '}
              <Link className="text-link" to="/profile">
                Открыть профиль
              </Link>
            </p>
          )}
          {Object.entries(errors)
            .filter(([key]) => key !== 'planDate' && !timeFields.some(([field]) => field === key))
            .map(([key, message]) => (
              <p key={key}>{message}</p>
            ))}
          {invalidated && (
            <Link className="text-link" to={`/?date=${date}`}>
              Проверить обычное меню на эту дату
            </Link>
          )}
        </div>
      )}
      {generating && <Loading />}
      {result && (
        <section className="menu-preview-result" aria-label="Предпросмотр меню">
          <div className="section-heading">
            <div>
              <span className="eyebrow">ЕЩЁ НЕ СОХРАНЕНО</span>
              <h2>{labelDate(result.planDate)}</h2>
            </div>
            <span className="badge">Завтрак · обед · ужин</span>
          </div>
          {result.warnings.map((code) => (
            <Notice tone="warning" key={code}>
              {warningText(code)}
            </Notice>
          ))}
          <div className="preview-meal-grid">
            {result.entries.map((entry) => (
              <article className="panel preview-meal" key={entry.mealType}>
                <span className="eyebrow">{mealLabels[entry.mealType]}</span>
                <h3>{entry.recipeName}</h3>
                <div className="recipe-meta">
                  <span>{number(entry.portions, 2)} порц.</span>
                  <span>
                    <Clock3 size={15} />
                    {entry.preparationTimeMin} мин.
                  </span>
                </div>
                <NutritionValues
                  nutrition={entry.nutrition}
                  label="КБЖУ выбранной порции · расчёт сервера"
                />
                <p className="caption">
                  {entry.lastPlannedDate
                    ? `Последний раз в меню: ${labelDate(entry.lastPlannedDate)}.`
                    : 'В предыдущие 21 день не планировалось.'}
                </p>
              </article>
            ))}
          </div>
          <section className="panel preview-day-total">
            <div>
              <span className="eyebrow">ИТОГО ЗА ДЕНЬ</span>
              <h2>Цель: {number(result.dailyCalorieTarget)} ккал</h2>
              <NutritionValues
                nutrition={result.nutritionTotal}
                label="Итог рассчитан сервером; округление строк может отличаться на сотую."
              />
            </div>
            <div className="preview-deviation">
              <span>Отклонение от цели</span>
              <strong>
                {result.calorieDeviation > 0 ? '+' : ''}
                {number(result.calorieDeviation, 2)} ккал
              </strong>
              <span className="badge">
                {result.withinTargetTolerance
                  ? 'В пределах 10% от цели'
                  : 'За пределами 10% от цели'}
              </span>
              <p className="caption">
                Плюс — выше цели, минус — ниже. Порции — стандартные порции рецепта, не вес.
              </p>
            </div>
          </section>
          <div className="panel preview-save">
            <div>
              <h3>Сохранить этот вариант?</h3>
              <p>
                Создадим черновик с этими тремя блюдами и порциями. Подтверждение дня — отдельно в
                обычном меню.
              </p>
            </div>
            <button
              className="button primary"
              disabled={
                !online ||
                saving ||
                generating ||
                invalidated ||
                !fresh ||
                Boolean(existing.data) ||
                existing.isPending ||
                existing.isError
              }
              onClick={save}
            >
              <CalendarDays size={18} />
              {saving ? 'Сохраняем…' : 'Сохранить меню как черновик'}
            </button>
          </div>
          {invalidated && (
            <Notice tone="warning">
              Этот вариант больше нельзя сохранить без нового предпросмотра. Автоматического повтора
              записи нет.
            </Notice>
          )}
        </section>
      )}
      <p className="caption preview-scope">
        Подбор на один день выполняется сервером. Бюджет, цены и заготовки не учитываются;
        одинаковые настройки могут дать тот же вариант. Это план питания, а не медицинское
        назначение.
      </p>
    </>
  );
}
