import { NutrientTargetsForm } from './NutritionTargets';
import { KitchenSummary } from '../components/KitchenSummary';
import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { ArrowRight, Check, ChevronLeft, Leaf, LogOut, RefreshCw, Sparkles } from 'lucide-react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { api, ApiError } from '../api';
import { activityOptions } from '../activity';
import { useApp, useOnline } from '../state';
import { useSession, safeNext } from '../session';
import {
  goalLabels,
  hasQuestionnaire,
  questionnaireErrors,
  questionnaireFrom,
  questionnairePayload,
} from '../questionnaire';
import type { QuestionnaireDraft } from '../questionnaire';
import ActivityField from '../components/ActivityField';
import { Notice, PageHeading } from '../components/ui';
import { number } from '../domain';
import type { EnergyEstimate, Goal, Sex } from '../types';
const errorMessage = (error: unknown) =>
  error instanceof Error ? error.message : 'Действие не выполнено. Повторите попытку.';
function fields(error: unknown): Record<string, string> {
  return error instanceof ApiError
    ? Object.fromEntries(error.fields.map((item) => [item.field, item.message]))
    : {};
}
function FieldError({ name, errors }: { name: string; errors: Record<string, string> }) {
  return errors[name] ? (
    <span className="field-error" id={`field-${name}`} role="alert">
      {errors[name]}
    </span>
  ) : null;
}
export function ServerAuth({ register = false }: { register?: boolean }) {
  const { setMode, notify } = useApp();
  const session = useSession();
  const online = useOnline();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const [registered, setRegistered] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  useEffect(() => {
    setMode('api');
  }, [setMode]);
  useEffect(() => {
    setRegistered(false);
    setError('');
    setErrors({});
    setPassword('');
  }, [register]);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending.current || !online) return;
    if (register && new TextEncoder().encode(password).length > 72) {
      setErrors({ password: 'Пароль должен занимать не более 72 байт UTF-8.' });
      return;
    }
    pending.current = true;
    setBusy(true);
    setError('');
    setErrors({});
    try {
      if (register) {
        await api.register(email, password);
        setPassword('');
        setRegistered(true);
        notify('Запрос обработан. Перейдите ко входу.');
      } else {
        await api.login(email, password);
        setPassword('');
        const profile = await session.refresh();
        notify('Вход выполнен');
        const next = profile.dailyCalorieTarget == null ? '/welcome' : safeNext(params.get('next'));
        navigate(
          profile.equipmentConfigured === false
            ? `/setup/kitchen?next=${encodeURIComponent(next)}`
            : next,
          {
            replace: true,
          },
        );
      }
    } catch (err) {
      setError(errorMessage(err));
      setErrors(fields(err));
      setPassword('');
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }
  return (
    <div className="auth-layout">
      <div className="auth-illustration">
        <Leaf size={42} />
        <h1>
          Чуть меньше рутины.
          <br />
          <span>Чуть больше жизни.</span>
        </h1>
        <p>
          Меню, покупки и ваши предпочтения —<br />в одном спокойном пространстве.
        </p>
      </div>
      <section className="panel auth-form">
        <span className="eyebrow">SMARTMEAL · ЛИЧНЫЙ АККАУНТ</span>
        <h2>
          {registered ? 'Перейдите ко входу' : register ? 'Создать аккаунт' : 'С возвращением'}
        </h2>
        {registered ? (
          <>
            <Notice tone="success">
              Если адрес свободен, аккаунт создан. Войдите с вашим email и паролем. Для
              существующего аккаунта используйте прежний пароль.
            </Notice>
            <Link className="button primary full" to="/login">
              Перейти ко входу
              <ArrowRight size={17} />
            </Link>
          </>
        ) : (
          <form onSubmit={submit}>
            <p className="muted">
              {register
                ? 'Только email и пароль. Анкету можно будет пройти после входа.'
                : 'Войдите, чтобы открыть данные своего аккаунта.'}
            </p>
            {session.profile && (
              <Notice>
                Вы уже вошли.{' '}
                <Link className="text-link" to="/profile">
                  Открыть профиль
                </Link>
              </Notice>
            )}
            <label>
              Email
              <input
                type="email"
                name="email"
                autoComplete="email"
                maxLength={100}
                required
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                aria-invalid={Boolean(errors.email)}
                aria-describedby="field-email"
              />
              <FieldError name="email" errors={errors} />
            </label>
            <label>
              Пароль
              <input
                type="password"
                name="password"
                minLength={register ? 8 : undefined}
                maxLength={72}
                autoComplete={register ? 'new-password' : 'current-password'}
                required
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                aria-invalid={Boolean(errors.password)}
                aria-describedby="field-password"
              />
              <FieldError name="password" errors={errors} />
            </label>
            {error && (
              <p className="form-error" role="alert">
                {error}
              </p>
            )}
            <button className="button primary full" disabled={busy || !online}>
              {busy ? 'Подождите…' : register ? 'Зарегистрироваться' : 'Войти'}
              <ArrowRight size={17} />
            </button>
            <Link className="text-link" to={register ? '/login' : '/register'}>
              {register ? 'Уже есть аккаунт? Войти' : 'Нет аккаунта? Зарегистрироваться'}
            </Link>
          </form>
        )}

        {session.error && (
          <p className="caption">
            Не удалось проверить сессию.{' '}
            <button className="text-button" type="button" onClick={() => session.retry()}>
              <RefreshCw size={14} />
              Повторить
            </button>
          </p>
        )}
      </section>
    </div>
  );
}
export function Welcome() {
  return (
    <>
      <PageHeading
        eyebrow="ДОБРО ПОЖАЛОВАТЬ"
        title="Настроим ваш ориентир?"
        description="Вы уже вошли. Анкету и цель можно заполнить сейчас или вернуться к ним позже."
      />
      <div className="panel welcome-panel">
        <Leaf size={38} />
        <h2>Питание в вашем ритме</h2>
        <p className="muted">
          Короткий опрос поможет получить предварительную оценку энергозатрат и выбрать суточную
          цель. Это не точная персональная норма и не медицинское назначение.
        </p>
        <div className="welcome-actions">
          <Link className="button primary" to="/onboarding">
            Пройти опрос
            <ArrowRight size={16} />
          </Link>
          <Link className="button" to="/recipes">
            Позже, открыть рецепты
          </Link>
          <Link className="text-link" to="/profile">
            Задать цель вручную
          </Link>
        </div>
      </div>
    </>
  );
}
export function TargetForm({ suggestion }: { suggestion?: number }) {
  const session = useSession();
  const { notify } = useApp();
  const online = useOnline();
  const profile = session.profile!;
  const [calories, setCalories] = useState(
    profile.dailyCalorieTarget == null ? '' : String(profile.dailyCalorieTarget),
  );
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  async function save(value: number) {
    if (pending.current || !online) return;
    if (!Number.isInteger(value) || value <= 0 || value > 2147483647) {
      setErrors({ dailyCalorieTarget: 'Введите положительное целое число калорий.' });
      return;
    }
    pending.current = true;
    setBusy(true);
    setError('');
    setErrors({});
    try {
      const saved = await api.updateCalorieTarget(value);
      session.saveProfile(saved);
      setCalories(saved.dailyCalorieTarget == null ? '' : String(saved.dailyCalorieTarget));
      notify('Суточная цель сохранена на сервере');
    } catch (err) {
      setError(errorMessage(err));
      setErrors(fields(err));
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }
  return (
    <div className="target-form">
      <p className="saved-target" role="status">
        Сохранённая цель:{' '}
        <b>
          {profile.dailyCalorieTarget == null
            ? 'не задана'
            : `${number(profile.dailyCalorieTarget)} ккал/день`}
        </b>
      </p>
      {suggestion !== undefined && (
        <button
          type="button"
          className="button primary full"
          disabled={busy || !online || suggestion === profile.dailyCalorieTarget}
          onClick={() => save(suggestion)}
        >
          <Check size={17} />
          {suggestion === profile.dailyCalorieTarget
            ? 'Рекомендация принята'
            : `Принять рекомендацию · ${number(suggestion)} ккал`}
        </button>
      )}
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (!/^\d+$/.test(calories)) {
            setErrors({ dailyCalorieTarget: 'Введите положительное целое число калорий.' });
            return;
          }
          save(Number(calories));
        }}
        noValidate
      >
        <label>
          Своя суточная цель, ккал
          <input
            type="number"
            name="dailyCalorieTarget"
            min="1"
            step="1"
            required
            value={calories}
            onChange={(event) => setCalories(event.target.value)}
            aria-invalid={Boolean(errors.dailyCalorieTarget)}
            aria-describedby="field-dailyCalorieTarget"
          />
          <FieldError name="dailyCalorieTarget" errors={errors} />
        </label>
        <button className="button" disabled={busy || !online}>
          {busy ? 'Сохраняем…' : 'Подтвердить свою цель'}
          <Check size={16} />
        </button>
      </form>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <p className="caption"></p>
    </div>
  );
}
export function AccountProfile() {
  const session = useSession();
  const { notify } = useApp();
  const online = useOnline();
  const navigate = useNavigate();
  async function logout() {
    try {
      await session.logout();
      navigate('/login', { replace: true });
    } catch (error) {
      notify(errorMessage(error));
    }
  }
  const p = session.profile!;
  return (
    <>
      <PageHeading
        eyebrow="ЛИЧНЫЙ АККАУНТ"
        title="Мой профиль"
        description="Ваши параметры, питание и техника."
        actions={
          <button className="button" disabled={!online || session.ending} onClick={logout}>
            <LogOut size={16} />
            {session.ending ? 'Выходим…' : 'Выйти'}
          </button>
        }
      />
      <div className="settings-layout">
        <aside className="panel profile-nav">
          <div className="profile-avatar">
            <Leaf size={32} />
          </div>
          <h2>Личный профиль</h2>

          <Link className="button full" to="/onboarding">
            {hasQuestionnaire(p) ? 'Изменить анкету' : 'Заполнить анкету'}
          </Link>
          <Link className="text-link" to="/recipes">
            Открыть каталог
          </Link>
        </aside>
        <section className="panel settings-form">
          <span className="eyebrow">ВАШ ОРИЕНТИР</span>
          <h2>Суточная цель</h2>
          <TargetForm />
          <div className="account-details">
            <h3>Сохранённая анкета</h3>
            <dl>
              {[
                ['Возраст', p.age == null ? 'Не указан' : `${p.age} лет`],
                ['Пол', p.sex == null ? 'Не указан' : p.sex === 'MALE' ? 'Мужской' : 'Женский'],
                ['Рост', p.heightCm == null ? 'Не указан' : `${p.heightCm} см`],
                ['Вес', p.weightKg == null ? 'Не указан' : `${number(p.weightKg, 2)} кг`],
                [
                  'Активность',
                  activityOptions.find((option) => option.value === p.activityLevel)?.label ||
                    'Не указана',
                ],
                ['Цель', p.goal ? goalLabels[p.goal] : 'Не указана'],
              ].map(([label, value]) => (
                <div key={label}>
                  <dt>{label}</dt>
                  <dd>{value}</dd>
                </div>
              ))}
            </dl>
            <Link className="button" to="/onboarding">
              <Sparkles size={16} />
              {hasQuestionnaire(p)
                ? 'Повторить расчёт и изменить анкету'
                : 'Пройти опрос для расчёта'}
            </Link>
            <Link className="text-link" to="/planning-preferences">
              Аллергии и исключения
            </Link>
          </div>
        </section>
      </div>
      <NutrientTargetsForm embedded />
      <KitchenSummary />
    </>
  );
}
export function Questionnaire() {
  const session = useSession();
  const { notify } = useApp();
  const online = useOnline();
  const profile = session.profile!;
  const [draft, setDraft] = useState(() => questionnaireFrom(profile));
  const [step, setStep] = useState(0);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const [estimate, setEstimate] = useState<EnergyEstimate | null>(null);
  const [savedSignature, setSavedSignature] = useState<string | null>(null);
  const changed = JSON.stringify(draft) !== JSON.stringify(questionnaireFrom(profile));
  useEffect(() => {
    if (!changed) return;
    const listener = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', listener);
    return () => window.removeEventListener('beforeunload', listener);
  }, [changed]);
  function update(patch: Partial<QuestionnaireDraft>) {
    setDraft((current) => ({ ...current, ...patch }));
    setEstimate(null);
    setError('');
  }
  async function calculate() {
    const validation = questionnaireErrors(draft);
    setErrors(validation);
    setError('');
    if (Object.keys(validation).length) {
      setStep(
        validation.age || validation.sex || validation.heightCm || validation.weightKg
          ? 0
          : validation.activityLevel
            ? 1
            : 2,
      );
      return;
    }
    if (pending.current || !online) return;
    pending.current = true;
    setBusy(true);
    try {
      const payload = questionnairePayload(draft);
      const signature = JSON.stringify(payload);
      if (signature !== savedSignature) {
        const saved = await api.updateProfile(payload);
        session.saveProfile(saved);
        setSavedSignature(signature);
        setDraft(questionnaireFrom(saved));
        notify('Анкета сохранена. Суточная цель не изменена.');
      }
      setStep(3);
      setEstimate(await api.energyEstimate());
    } catch (err) {
      setError(errorMessage(err));
      setEstimate(null);
      const field = fields(err);
      setErrors(field);
      if (Object.keys(field).length)
        setStep(
          field.age || field.sex || field.heightCm || field.weightKg
            ? 0
            : field.activityLevel
              ? 1
              : 2,
        );
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }
  function next(event: FormEvent) {
    event.preventDefault();
    if (step < 2) {
      const validation = questionnaireErrors(draft, step);
      setErrors(validation);
      if (!Object.keys(validation).length) setStep(step + 1);
    } else calculate();
  }
  return (
    <>
      <PageHeading
        eyebrow="ОПРОС ДЛЯ РАСЧЁТА"
        title="Ваш день — ваш ориентир"
        description="Сначала сохраним анкету, затем запросим предварительную оценку на сервере."
        actions={
          <Link className="button quiet" to="/recipes">
            Пройти позже
          </Link>
        }
      />
      <section className="panel onboarding">
        <div className="onboarding-progress questionnaire-progress" aria-label="Шаги опроса">
          {['Параметры', 'Активность', 'Цель', 'Результат'].map((label, index) => (
            <span
              className={index <= step ? 'active' : ''}
              aria-current={step === index ? 'step' : undefined}
              key={label}
            >
              <b>{index < step ? <Check size={15} /> : index + 1}</b>
              {label}
            </span>
          ))}
        </div>
        <Notice>
          Автоматический расчёт предназначен для взрослых от 18 лет вне беременности и грудного
          вскармливания. Числа — предварительная оценка, а не точная персональная норма.
        </Notice>
        {step < 3 ? (
          <form onSubmit={next} noValidate>
            <h2>
              {
                ['Параметры тела', 'Как проходит ваш обычный день?', 'Какую цель вы выбираете?'][
                  step
                ]
              }
            </h2>
            {step === 0 && (
              <>
                <div className="form-row">
                  <label>
                    Возраст, лет
                    <input
                      name="age"
                      type="number"
                      min="18"
                      step="1"
                      value={draft.age}
                      onChange={(event) => update({ age: event.target.value })}
                      aria-invalid={Boolean(errors.age)}
                      aria-describedby="field-age"
                    />
                    <FieldError name="age" errors={errors} />
                  </label>
                  <label>
                    Пол
                    <select
                      name="sex"
                      value={draft.sex}
                      onChange={(event) => update({ sex: event.target.value as Sex | '' })}
                      aria-invalid={Boolean(errors.sex)}
                      aria-describedby="field-sex"
                    >
                      <option value="">Выберите</option>
                      <option value="MALE">Мужской</option>
                      <option value="FEMALE">Женский</option>
                    </select>
                    <FieldError name="sex" errors={errors} />
                  </label>
                </div>
                <div className="form-row">
                  <label>
                    Рост, см
                    <input
                      name="heightCm"
                      type="number"
                      min="1"
                      step="1"
                      value={draft.heightCm}
                      onChange={(event) => update({ heightCm: event.target.value })}
                      aria-invalid={Boolean(errors.heightCm)}
                      aria-describedby="field-heightCm"
                    />
                    <FieldError name="heightCm" errors={errors} />
                  </label>
                  <label>
                    Вес, кг
                    <input
                      name="weightKg"
                      type="number"
                      min="0.01"
                      max="999.99"
                      step="0.01"
                      inputMode="decimal"
                      value={draft.weightKg}
                      onChange={(event) => update({ weightKg: event.target.value })}
                      aria-invalid={Boolean(errors.weightKg)}
                      aria-describedby="field-weightKg"
                    />
                    <FieldError name="weightKg" errors={errors} />
                  </label>
                </div>
              </>
            )}
            {step === 1 && (
              <>
                <ActivityField
                  value={draft.activityLevel}
                  onChange={(activityLevel) => update({ activityLevel })}
                  required
                />
                <FieldError name="activityLevel" errors={errors} />
              </>
            )}
            {step === 2 && (
              <fieldset className="goal-options">
                <legend className="sr-only">Цель питания</legend>
                {(['LOSE_WEIGHT', 'MAINTAIN_WEIGHT', 'GAIN_WEIGHT'] as Goal[]).map((goal) => (
                  <label
                    className={`activity-option ${draft.goal === goal ? 'selected' : ''}`}
                    key={goal}
                  >
                    <input
                      type="radio"
                      name="goal"
                      value={goal}
                      checked={draft.goal === goal}
                      onChange={() => update({ goal })}
                    />
                    <span>
                      <b>{goalLabels[goal]}</b>
                    </span>
                  </label>
                ))}
                <FieldError name="goal" errors={errors} />
                <p className="caption">
                  Выбор цели участвует в серверной рекомендации. Текущая подтверждённая калорийность
                  останется прежней до вашего согласия.
                </p>
              </fieldset>
            )}
            {error && (
              <p className="form-error" role="alert">
                {error}
              </p>
            )}
            <div className="editor-actions">
              <button
                type="button"
                className="button quiet"
                disabled={step === 0 || busy}
                onClick={() => {
                  setStep(step - 1);
                  setError('');
                }}
              >
                <ChevronLeft size={16} />
                Назад
              </button>
              <button className="button primary" disabled={busy || !online}>
                {busy
                  ? 'Рассчитываем…'
                  : step === 2
                    ? 'Сохранить анкету и рассчитать'
                    : 'Продолжить'}
                <ArrowRight size={16} />
              </button>
            </div>
          </form>
        ) : (
          <>
            <h2>Ваш предварительный ориентир</h2>
            {busy ? (
              <p role="status" className="muted">
                Запрашиваем расчёт на сервере…
              </p>
            ) : estimate ? (
              <>
                <div className="estimate-grid">
                  {[
                    {
                      value: estimate.restingCalories,
                      title: 'Расход в покое',
                      text: 'Оценка энергии для основных функций организма, без повседневной активности.',
                    },
                    {
                      value: estimate.maintenanceCalories,
                      title: 'Поддержание веса',
                      text: 'Оценка дневного расхода с учётом выбранной активности.',
                    },
                    {
                      value: estimate.recommendedCalories,
                      title: 'Рекомендация под цель',
                      text: `Ориентир сервера для выбранной цели: ${goalLabels[draft.goal as Goal]}.`,
                    },
                  ].map((item) => (
                    <article key={item.title} className="estimate-card">
                      <span>{item.title}</span>
                      <b>
                        {number(item.value)}
                        <small> ккал/день</small>
                      </b>
                      <p>{item.text}</p>
                    </article>
                  ))}
                </div>
                <Notice>
                  Расчёт ничего не сохранил в суточную цель. Вы можете принять рекомендацию, указать
                  своё число или вернуться позже.
                </Notice>
                <TargetForm suggestion={estimate.recommendedCalories} />
              </>
            ) : null}
            {error && (
              <>
                <p className="form-error" role="alert">
                  {error}
                </p>
                <button
                  className="button"
                  type="button"
                  onClick={calculate}
                  disabled={busy || !online}
                >
                  <RefreshCw size={16} />
                  Повторить расчёт
                </button>
              </>
            )}
            <div className="editor-actions">
              <button
                type="button"
                className="button quiet"
                disabled={busy}
                onClick={() => {
                  setStep(0);
                  setEstimate(null);
                  setError('');
                }}
              >
                <ChevronLeft size={16} />
                Изменить анкету
              </button>
              <Link className="button" to="/recipes">
                К рецептам
                <ArrowRight size={16} />
              </Link>
            </div>
          </>
        )}
      </section>
    </>
  );
}
