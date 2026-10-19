import { useState } from 'react';
import { ArrowRight, Check, ChevronLeft, ChevronRight, Leaf, MapPin, Store } from 'lucide-react';
import { Link, useNavigate } from 'react-router';
import { BackendPending, CheckChip, Notice, PageHeading } from '../components/ui';
import { useApp } from '../state';
import type { Profile } from '../types';
import ActivityField from '../components/ActivityField';
import { AccountProfile, Questionnaire, ServerAuth } from './Account';
const allergyOptions = ['Молоко', 'Яйца', 'Глютен', 'Орехи', 'Рыба', 'Соя'];
const equipmentOptions = [
  'Плита',
  'Сковорода',
  'Духовка',
  'Блендер',
  'Мультиварка',
  'Микроволновка',
];
function TargetFields({
  value,
  onChange,
}: {
  value: Profile;
  onChange: (profile: Profile) => void;
}) {
  return (
    <>
      <Notice>
        Ручная цель — ваш ориентир, не медицинская рекомендация. Автоматический расчёт потребности
        пока не подключён.
      </Notice>
      <div className="form-row">
        <label>
          Калорийность в день, ккал
          <input
            type="number"
            min="1"
            step="1"
            max="20000"
            required
            value={value.calories}
            onChange={(e) => onChange({ ...value, calories: Number(e.target.value) })}
          />
        </label>
        <label>
          Часовой пояс
          <select
            value={value.timezone}
            onChange={(e) => onChange({ ...value, timezone: e.target.value })}
          >
            <option>Europe/Moscow</option>
          </select>
        </label>
      </div>
      <div className="form-row three">
        {(['protein', 'fat', 'carbs'] as const).map((key, index) => (
          <label key={key}>
            {['Белки, г', 'Жиры, г', 'Углеводы, г'][index]}
            <input
              type="number"
              min="1"
              max="5000"
              step="0.1"
              required
              value={value[key]}
              onChange={(e) => onChange({ ...value, [key]: Number(e.target.value) })}
            />
          </label>
        ))}
      </div>
      <p className="caption">
        Источник: задано вручную. Пример 2000 ккал — тестовое значение, не назначение.
      </p>
      <ActivityField
        value={value.activityLevel ?? null}
        onChange={(activityLevel) => onChange({ ...value, activityLevel })}
      />
    </>
  );
}
function Restrictions({
  value,
  onChange,
}: {
  value: Profile;
  onChange: (profile: Profile) => void;
}) {
  return (
    <>
      <h3>Аллергены</h3>
      <p className="muted">
        Отдельно от вкусовых предпочтений. Неполный состав не означает отсутствие аллергенов.
      </p>
      <div className="chips">
        {allergyOptions.map((item) => (
          <CheckChip
            key={item}
            selected={value.allergies.includes(item)}
            onClick={() =>
              onChange({
                ...value,
                allergies: value.allergies.includes(item)
                  ? value.allergies.filter((x) => x !== item)
                  : [...value.allergies, item],
              })
            }
          >
            {item}
          </CheckChip>
        ))}
      </div>
      <label>
        Исключить продукты
        <textarea
          rows={3}
          value={value.excluded}
          onChange={(event) => onChange({ ...value, excluded: event.target.value })}
          placeholder="Например, грибы и оливки"
        />
      </label>
      <p className="caption">
        В демо эти настройки иллюстрируют профиль. Автоматической фильтрации и гарантий безопасности
        нет.
      </p>
    </>
  );
}
function Equipment({ value, onChange }: { value: Profile; onChange: (profile: Profile) => void }) {
  return (
    <>
      <h3>Что есть на вашей кухне?</h3>
      <p className="muted">Обязательное оборудование будет учитываться при серверном подборе.</p>
      <div className="chips">
        {equipmentOptions.map((item) => (
          <CheckChip
            key={item}
            selected={value.equipment.includes(item)}
            onClick={() =>
              onChange({
                ...value,
                equipment: value.equipment.includes(item)
                  ? value.equipment.filter((x) => x !== item)
                  : [...value.equipment, item],
              })
            }
          >
            {item}
          </CheckChip>
        ))}
      </div>
    </>
  );
}
function BudgetFields({
  value,
  onChange,
}: {
  value: Profile;
  onChange: (profile: Profile) => void;
}) {
  return (
    <>
      <label>
        Город
        <input
          value={value.city}
          onChange={(event) => onChange({ ...value, city: event.target.value })}
          placeholder="Введите ваш город"
        />
      </label>
      <div className="form-row">
        <label>
          Бюджет на период, ₽
          <input
            type="number"
            min="1"
            max="10000000"
            step="0.01"
            required
            value={value.budget}
            onChange={(event) => onChange({ ...value, budget: Number(event.target.value) })}
          />
        </label>
        <label>
          Период
          <select
            value={value.period}
            onChange={(event) =>
              onChange({ ...value, period: event.target.value as Profile['period'] })
            }
          >
            <option value="day">День</option>
            <option value="week">Неделя</option>
          </select>
        </label>
      </div>
      <label className="checkbox-label">
        <input
          type="checkbox"
          checked={value.loyalty}
          onChange={(event) => onChange({ ...value, loyalty: event.target.checked })}
        />
        У меня есть карта лояльности выбранного магазина
      </label>
      <p className="caption">
        Карта не включает скидку автоматически: нужны условия конкретного предложения. Доставка и
        персональные купоны не учтены.
      </p>
    </>
  );
}
export function ProfilePage() {
  const { mode } = useApp();
  return mode === 'api' ? <AccountProfile /> : <DemoProfilePage />;
}
function DemoProfilePage() {
  const { mode, profile, setProfile, notify, setShopping } = useApp();
  const [draft, setDraft] = useState(profile);
  const [section, setSection] = useState('Цель');
  return (
    <>
      <PageHeading
        eyebrow="ПОДСТРОИМ ПОД ВАС"
        title="Мой профиль"
        description="Ваши ориентиры, предпочтения и возможности кухни."
      />
      {mode === 'api' ? (
        <BackendPending
          title="Профиль ожидает API"
          description="Личные цели и ограничения должны храниться на сервере. Локальная демоанкета не выдаётся за синхронизированный профиль."
        />
      ) : (
        <div className="settings-layout">
          <div className="panel profile-nav">
            <div className="profile-avatar">
              <Leaf size={32} />
            </div>
            <h2>Демо-профиль</h2>
            <p className="caption">Один взрослый пользователь</p>
            {['Цель', 'Ограничения', 'Оборудование', 'Бюджет'].map((item) => (
              <button
                key={item}
                className={section === item ? 'active' : ''}
                onClick={() => setSection(item)}
              >
                {item}
                <ChevronRight size={16} />
              </button>
            ))}
            <Link to="/onboarding" className="text-link">
              Пройти настройку заново
            </Link>
          </div>
          <form
            className="panel settings-form"
            onSubmit={(event) => {
              event.preventDefault();
              setProfile(draft);
              setShopping((current) => (current ? { ...current, stale: true } : null));
              notify('Демо-профиль сохранён только в этом браузере');
            }}
          >
            <span className="eyebrow">НАСТРОЙКИ ПРОФИЛЯ</span>
            <h2>{section}</h2>
            {section === 'Цель' ? (
              <TargetFields value={draft} onChange={setDraft} />
            ) : section === 'Ограничения' ? (
              <Restrictions value={draft} onChange={setDraft} />
            ) : section === 'Оборудование' ? (
              <Equipment value={draft} onChange={setDraft} />
            ) : (
              <BudgetFields value={draft} onChange={setDraft} />
            )}
            <div className="editor-actions">
              <span className="caption">Только демонстрационные данные</span>
              <button className="button primary">
                Сохранить изменения <Check size={17} />
              </button>
            </div>
          </form>
        </div>
      )}
    </>
  );
}
export function Onboarding() {
  const { mode } = useApp();
  return mode === 'api' ? <Questionnaire /> : <DemoOnboarding />;
}
function DemoOnboarding() {
  const { mode, profile, setProfile, notify } = useApp();
  const [draft, setDraft] = useState(profile);
  const [step, setStep] = useState(0);
  const navigate = useNavigate();
  return (
    <>
      <PageHeading
        eyebrow="ЗНАКОМИМСЯ С ВАШЕЙ КУХНЕЙ"
        title="Начнём с главного"
        description="Четыре коротких шага. Каталог доступен и без заполненного профиля."
      />
      {mode === 'api' ? (
        <BackendPending
          title="Настройка ждёт серверный профиль"
          description="Чтобы сохранять цели между устройствами, необходим API профиля."
        />
      ) : (
        <div className="onboarding panel">
          <div className="onboarding-progress">
            {['Цель', 'Ограничения', 'Кухня', 'Бюджет'].map((item, index) => (
              <span key={item} className={index <= step ? 'active' : ''}>
                <b>{index < step ? <Check size={15} /> : index + 1}</b>
                {item}
              </span>
            ))}
          </div>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              if (step < 3) setStep(step + 1);
              else {
                setProfile(draft);
                notify('Демонстрационные настройки сохранены');
                navigate('/');
              }
            }}
          >
            <h2>
              {
                [
                  'Какой у вас ориентир на день?',
                  'Что важно учесть?',
                  'Чем будем готовить?',
                  'Где и на какую сумму покупаем?',
                ][step]
              }
            </h2>
            {step === 0 ? (
              <TargetFields value={draft} onChange={setDraft} />
            ) : step === 1 ? (
              <Restrictions value={draft} onChange={setDraft} />
            ) : step === 2 ? (
              <Equipment value={draft} onChange={setDraft} />
            ) : (
              <BudgetFields value={draft} onChange={setDraft} />
            )}
            <div className="editor-actions">
              <button
                type="button"
                className="button quiet"
                onClick={() => (step ? setStep(step - 1) : navigate('/recipes'))}
              >
                <ChevronLeft size={16} />
                {step ? 'Назад' : 'В каталог'}
              </button>
              <div className="inline-actions">
                {step > 0 && step < 3 && (
                  <button className="button quiet" type="button" onClick={() => setStep(step + 1)}>
                    Пропустить
                  </button>
                )}
                <button className="button primary">
                  {step === 3 ? 'Готово' : 'Продолжить'}
                  <ArrowRight size={17} />
                </button>
              </div>
            </div>
          </form>
        </div>
      )}
    </>
  );
}
export function StoresPage() {
  const { mode, profile, setProfile, setShopping, notify } = useApp();
  const [draft, setDraft] = useState(profile);
  const [search, setSearch] = useState('');
  const [chain, setChain] = useState('all');
  const stores = [
    {
      id: 'demo-store',
      name: 'Демонстрационный магазин',
      address: 'Учебная точка, адрес не задан',
      status: 'Условные цены и упаковки',
      available: true,
    },
    {
      id: 'demo-unconnected',
      name: 'Магазин без источника',
      address: 'Пример состояния подключения',
      status: 'Источник пока не подключён',
      available: false,
    },
  ];
  return (
    <>
      <PageHeading
        eyebrow="ПОКУПКИ С ПОНЯТНЫМ КОНТЕКСТОМ"
        title="Магазины и бюджет"
        description="Один магазин за расчёт. Вы выбираете, где покупать."
      />
      {mode === 'api' ? (
        <BackendPending
          title="Справочник магазинов ещё не подключён"
          description="Физический магазин не означает доступность его цен. Для выбора нужны серверный справочник точек и источники."
        />
      ) : (
        <div className="stores-layout">
          <section>
            <Notice>
              Точки и цены ниже — учебные примеры. Подключений к реальным торговым сетям пока нет.
            </Notice>
            <div className="filters">
              <label className="search-field">
                <MapPin size={17} />
                <input
                  placeholder="Поиск магазина или адреса"
                  aria-label="Поиск магазина"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                />
              </label>
              <select
                aria-label="Сеть магазина"
                value={chain}
                onChange={(event) => setChain(event.target.value)}
              >
                <option value="all">Все учебные точки</option>
                <option value="available">С условными ценами</option>
              </select>
            </div>
            {stores
              .filter(
                (store) =>
                  store.name.toLowerCase().includes(search.toLowerCase()) &&
                  (chain === 'all' || store.available),
              )
              .map((store) => (
                <article
                  key={store.id}
                  className={`panel store-card ${draft.store === store.id ? 'chosen' : ''}`}
                >
                  <span className="store-logo">
                    <Store size={27} />
                  </span>
                  <div>
                    <h2>{store.name}</h2>
                    <p>{store.address}</p>
                    <span className={`quality-tag ${store.available ? 'estimate' : 'unknown'}`}>
                      {store.status}
                    </span>
                  </div>
                  <button
                    className="button"
                    disabled={!store.available}
                    onClick={() => setDraft({ ...draft, store: store.id })}
                  >
                    {draft.store === store.id ? (
                      <>
                        <Check size={16} />
                        Основной
                      </>
                    ) : (
                      'Выбрать'
                    )}
                  </button>
                </article>
              ))}
            <p className="caption">
              Ручной поиск доступен без геолокации. Реальные адреса и карта появятся со справочником
              точек. Магазин не подменяется автоматически.
            </p>
          </section>
          <form
            className="panel settings-form"
            onSubmit={(event) => {
              event.preventDefault();
              setProfile(draft);
              setShopping((current) => (current ? { ...current, stale: true } : null));
              notify('Демонастройки сохранены. Существующая оценка требует обновления.');
            }}
          >
            <span className="eyebrow">КОНТЕКСТ ПОКУПКИ</span>
            <h2>Бюджет и период</h2>
            <BudgetFields value={draft} onChange={setDraft} />
            <Notice>
              Личная покупка в магазине. Смена бюджета не изменяет сохранённую корзину без явного
              пересчёта.
            </Notice>
            <button className="button primary full">Сохранить настройки</button>
          </form>
        </div>
      )}
    </>
  );
}
export function AuthPage({ register = false }: { register?: boolean }) {
  return <ServerAuth register={register} />;
}
