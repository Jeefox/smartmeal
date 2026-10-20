import { useState } from 'react';
import {
  ArrowLeftRight,
  ArrowRight,
  CalendarDays,
  ChevronLeft,
  ChevronRight,
  Flame,
  Leaf,
  LockKeyhole,
  Plus,
  ShoppingBasket,
  Sparkles,
  Trash2,
  UnlockKeyhole,
} from 'lucide-react';
import { Link } from 'react-router';
import { BackendPending, Dialog, Notice, PageHeading, RecipeImage } from '../components/ui';
import { useApp } from '../state';
import { dateKey, labelDate, money, number, planNutrition, shiftDate, weekDates } from '../domain';
import type { MealType, PlanEntry } from '../types';
import LivePlanner from './LivePlanner';
const meals: MealType[] = ['Завтрак', 'Обед', 'Ужин', 'Перекус'];
export default function Planner({ week = false }: { week?: boolean }) {
  const { mode } = useApp();
  return mode === 'api' ? <LivePlanner week={week} /> : <DemoPlanner week={week} />;
}
function DemoPlanner({ week = false }: { week?: boolean }) {
  const {
    mode,
    profile,
    entries,
    changeEntries,
    addEntry,
    recipes,
    selectedDate,
    setSelectedDate,
    shopping,
    notify,
  } = useApp();
  const [pick, setPick] = useState<{ meal: MealType; replace?: PlanEntry } | null>(null);
  const [preview, setPreview] = useState(false);
  const [query, setQuery] = useState('');
  const current = entries.filter((e) => e.date === selectedDate);
  const totals = planNutrition(current);
  const dates = weekDates(selectedDate);
  const weekly = entries.filter((e) => dates.includes(e.date));
  const weeklyTotals = planNutrition(weekly);
  const progress = totals ? Math.min(100, (totals.calories / profile.calories) * 100) : 0;
  function updateEntry(id: string, patch: Partial<PlanEntry>) {
    changeEntries(entries.map((e) => (e.id === id ? { ...e, ...patch } : e)));
  }
  const title = week ? 'Неделя в вашем ритме' : 'Хороший день начинается с плана';
  return (
    <>
      <PageHeading
        eyebrow={week ? 'ПЛАНИРУЕМ БЕЗ СПЕШКИ' : 'ВАШЕ ПИТАНИЕ · ВАШ РИТМ'}
        title={title}
        description={
          week
            ? 'Соберите меню на семь дней. Покупки посчитаем на весь период.'
            : 'Любимые блюда, понятные порции и чуть больше заботы о себе.'
        }
        actions={
          <div className="segmented">
            <Link to="/" className={!week ? 'active' : ''}>
              День
            </Link>
            <Link to="/week" className={week ? 'active' : ''}>
              Неделя
            </Link>
          </div>
        }
      />
      {mode === 'api' ? (
        <BackendPending
          title="Здесь будет ваше меню"
          description="Серверные профиль и меню пока не реализованы. Реальные рецепты доступны в каталоге; план не будет сохраняться только локально."
        />
      ) : (
        <>
          <section className="daily-overview">
            <div className="daily-main">
              <div className="overview-top">
                <span className="eyebrow">
                  <span className="little-dot" /> МЕНЮ {week ? 'НЕДЕЛИ' : 'НА ДЕНЬ'}
                </span>
                <Link to="/profile" className="text-link">
                  Моя цель <ArrowRight size={14} />
                </Link>
              </div>
              <div className="energy-row">
                <div>
                  <span className="overview-date">
                    {week
                      ? `${labelDate(dates[0])} — ${labelDate(dates[6])}`
                      : labelDate(selectedDate, { weekday: 'long', day: 'numeric', month: 'long' })}
                  </span>
                  <h2>
                    {number((week ? weeklyTotals : totals)?.calories || 0)}
                    <span> / {number(profile.calories * (week ? 7 : 1))} ккал</span>
                  </h2>
                  <p>
                    {week
                      ? 'Сумма запланированного на неделю'
                      : 'Запланировано, не фактически съедено'}
                  </p>
                </div>
                <div
                  className="energy-ring"
                  style={
                    {
                      '--progress': `${week ? Math.min(100, ((weeklyTotals?.calories || 0) / (profile.calories * 7)) * 100) : progress}%`,
                    } as React.CSSProperties
                  }
                >
                  <Flame size={24} />
                  <b>
                    {Math.round(
                      week
                        ? ((weeklyTotals?.calories || 0) / (profile.calories * 7)) * 100
                        : progress,
                    )}
                    %
                  </b>
                </div>
              </div>
              <div className="macro-progress">
                {(['protein', 'fat', 'carbs'] as const).map((key, index) => (
                  <div key={key}>
                    <span>
                      {['Белки', 'Жиры', 'Углеводы'][index]}
                      <b>
                        {number((week ? weeklyTotals : totals)?.[key] || 0, 1)}{' '}
                        <small>/ {number(profile[key] * (week ? 7 : 1))} г</small>
                      </b>
                    </span>
                    <div className={`progress-track macro-${index}`}>
                      <i
                        style={{
                          width: `${Math.min(100, (((week ? weeklyTotals : totals)?.[key] || 0) / (profile[key] * (week ? 7 : 1))) * 100)}%`,
                        }}
                      />
                    </div>
                  </div>
                ))}
              </div>
            </div>
            <aside className="budget-overview">
              <span className="eyebrow">БЮДЖЕТ {profile.period === 'week' ? 'НЕДЕЛИ' : 'ДНЯ'}</span>
              <div>
                <h2>{money(profile.budget * 100)}</h2>
                <p>Покупаем целые упаковки</p>
              </div>
              <div className="budget-caption">
                <ShoppingBasket size={18} />
                <p>
                  {shopping
                    ? shopping.stale
                      ? 'Меню изменилось. Оценку нужно обновить.'
                      : 'Учебная оценка доступна в покупках.'
                    : 'Стоимость пока не рассчитана. Неизвестное не считаем нулём.'}
                </p>
              </div>
              <Link to="/stores" className="text-link">
                Магазин и настройки <ArrowRight size={15} />
              </Link>
            </aside>
          </section>
          <div className="planner-toolbar">
            <div className="date-controls">
              <button
                className="icon-button"
                aria-label={week ? 'Предыдущая неделя' : 'Предыдущий день'}
                onClick={() => setSelectedDate(shiftDate(selectedDate, week ? -7 : -1))}
              >
                <ChevronLeft size={19} />
              </button>
              <label>
                <CalendarDays size={16} />
                <span className="sr-only">День меню</span>
                <input
                  aria-label="День меню"
                  type="date"
                  value={selectedDate}
                  onChange={(event) => {
                    if (event.target.value) setSelectedDate(event.target.value);
                  }}
                />
              </label>
              <button
                className="icon-button"
                aria-label={week ? 'Следующая неделя' : 'Следующий день'}
                onClick={() => setSelectedDate(shiftDate(selectedDate, week ? 7 : 1))}
              >
                <ChevronRight size={19} />
              </button>
              <button className="button quiet" onClick={() => setSelectedDate(dateKey(new Date()))}>
                Сегодня
              </button>
            </div>
            <button className="button primary" onClick={() => setPreview(true)}>
              <Sparkles size={17} />
              Составить меню
            </button>
          </div>
          {week && (
            <div className="week-strip">
              {dates.map((date) => {
                const list = entries.filter((e) => e.date === date);
                const sum = planNutrition(list);
                return (
                  <button
                    key={date}
                    onClick={() => setSelectedDate(date)}
                    className={date === selectedDate ? 'active' : ''}
                  >
                    <span>{labelDate(date, { weekday: 'short' })}</span>
                    <b>{labelDate(date, { day: 'numeric' })}</b>
                    <small>{list.length ? `${number(sum?.calories || 0)} ккал` : 'Нет блюд'}</small>
                  </button>
                );
              })}
            </div>
          )}
          <div className="section-title">
            <div>
              <h2>
                {week
                  ? labelDate(selectedDate, { weekday: 'long', day: 'numeric', month: 'long' })
                  : 'Меню на сегодня'}{' '}
                <span className="count">{current.length}</span>
              </h2>
              <p className="caption">Ручной план · снимки рецептов · демоданные</p>
            </div>
            <span className="draft-badge">Черновик</span>
          </div>
          <div className="planner-layout">
            <section className="meal-list">
              {meals.map((meal, index) => {
                const list = current.filter((e) => e.meal === meal);
                return (
                  <div className="meal-slot" key={meal}>
                    <div className="meal-slot-heading">
                      <div>
                        <span className="meal-symbol">{['☀', '◐', '☾', '✳'][index]}</span>
                        <h3>{meal}</h3>
                        <span className="meal-time">
                          {['08:00–10:00', '12:00–14:00', '18:00–20:00', 'В удобное время'][index]}
                        </span>
                      </div>
                      <button
                        className="icon-button"
                        aria-label={`Добавить: ${meal}`}
                        onClick={() => {
                          setQuery('');
                          setPick({ meal });
                        }}
                      >
                        <Plus size={19} />
                      </button>
                    </div>
                    {list.length ? (
                      list.map((entry) => (
                        <article className="meal-card" key={entry.id}>
                          <Link to={`/recipes/${entry.recipe.id}`} className="meal-photo">
                            <RecipeImage recipe={entry.recipe} />
                          </Link>
                          <div className="meal-info">
                            <Link to={`/recipes/${entry.recipe.id}`}>
                              <h3>{entry.recipe.name}</h3>
                            </Link>
                            <p>
                              {entry.recipe.preparationTimeMin} мин <span>·</span>{' '}
                              {entry.recipe.nutritionTotal
                                ? `${number((entry.recipe.nutritionTotal.calories / entry.recipe.servings) * entry.portions)} ккал`
                                : 'КБЖУ не заполнено'}
                            </p>
                            <label className="meal-portions">
                              Порций{' '}
                              <input
                                aria-label={`Порции: ${entry.recipe.name}`}
                                type="number"
                                min="0.1"
                                max="20"
                                step="0.1"
                                value={entry.portions}
                                onChange={(event) => {
                                  const value = Number(event.target.value);
                                  if (value > 0 && value <= 20)
                                    updateEntry(entry.id, { portions: value });
                                }}
                              />
                            </label>
                          </div>
                          <div className="meal-actions">
                            <button
                              className={`icon-button ${entry.locked ? 'selected' : ''}`}
                              aria-label={`${entry.locked ? 'Открепить' : 'Закрепить'}: ${entry.recipe.name}`}
                              aria-pressed={entry.locked}
                              onClick={() => updateEntry(entry.id, { locked: !entry.locked })}
                            >
                              {entry.locked ? (
                                <LockKeyhole size={17} />
                              ) : (
                                <UnlockKeyhole size={17} />
                              )}
                            </button>
                            <button
                              className="icon-button"
                              disabled={entry.locked}
                              aria-label={`Заменить: ${entry.recipe.name}`}
                              onClick={() => {
                                setQuery('');
                                setPick({ meal, replace: entry });
                              }}
                            >
                              <ArrowLeftRight size={17} />
                            </button>
                            <button
                              className="icon-button danger"
                              disabled={entry.locked}
                              aria-label={`Убрать из меню: ${entry.recipe.name}`}
                              onClick={() =>
                                changeEntries(entries.filter((e) => e.id !== entry.id))
                              }
                            >
                              <Trash2 size={16} />
                            </button>
                          </div>
                        </article>
                      ))
                    ) : (
                      <button
                        className="empty-meal"
                        onClick={() => {
                          setQuery('');
                          setPick({ meal });
                        }}
                      >
                        <Plus size={17} />
                        Добавить блюдо <span>Из вашей книги рецептов</span>
                      </button>
                    )}
                  </div>
                );
              })}
            </section>
            <aside className="planner-aside">
              <div className="panel">
                <span className="eyebrow">МАЛЕНЬКИЕ ОРИЕНТИРЫ</span>
                <h3>
                  План — это опора,
                  <br />а не строгие рамки.
                </h3>
                <p className="muted">
                  Цель задана вручную. Изменяйте порции и наблюдайте за суммой дня.
                </p>
                <div className="target-delta">
                  <span>До цели дня</span>
                  <b>
                    {totals
                      ? `${totals.calories > profile.calories ? '+' : ''}${number(totals.calories - profile.calories)} ккал`
                      : 'Нет данных'}
                  </b>
                </div>
                <p className="caption">
                  {totals && totals.calories < profile.calories
                    ? 'Знак «−» означает, что в плане пока меньше заданной цели.'
                    : 'Показываем отклонение плана от вашей цели.'}
                </p>
                <Link to="/profile" className="text-link">
                  Настроить цель <ArrowRight size={15} />
                </Link>
              </div>
              <div className="panel green-panel">
                <Leaf size={28} />
                <h3>От меню к покупкам</h3>
                <p>
                  Состав продуктов суммируется за выбранный период, затем округляется до целых
                  упаковок.
                </p>
                <Link to="/shopping" className="button full">
                  Посмотреть покупки <ArrowRight size={16} />
                </Link>
              </div>
            </aside>
          </div>
        </>
      )}
      {pick && (
        <Dialog
          title={pick.replace ? 'Выбрать замену' : 'Добавить блюдо'}
          onClose={() => setPick(null)}
        >
          <label>
            Поиск рецепта
            <input
              autoFocus
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Название блюда"
            />
          </label>
          {profile.allergies.length > 0 && (
            <Notice tone="warning">
              При ручном выборе проверяйте аллергены. Демо не подтверждает безопасность конкретных
              товаров.
            </Notice>
          )}
          <div className="recipe-picker">
            {recipes
              .filter((r) => r.name.toLowerCase().includes(query.toLowerCase()))
              .map((recipe) => (
                <button
                  key={recipe.id}
                  onClick={() => {
                    if (pick.replace)
                      changeEntries(
                        entries.map((e) =>
                          e.id === pick.replace!.id ? { ...e, recipe: structuredClone(recipe) } : e,
                        ),
                      );
                    else addEntry(recipe, pick.meal, selectedDate);
                    setPick(null);
                  }}
                >
                  <RecipeImage recipe={recipe} />
                  <span>
                    <b>{recipe.name}</b>
                    <small>
                      {recipe.preparationTimeMin} мин ·{' '}
                      {number(recipe.nutritionPerServing?.calories || 0)} ккал/порц.
                    </small>
                  </span>
                  <Plus size={18} />
                </button>
              ))}
          </div>
        </Dialog>
      )}
      {preview && (
        <Dialog title="Автоматический подбор" onClose={() => setPreview(false)}>
          <Notice>Планировщик ещё не подключён. Автоматическое меню не создавалось.</Notice>
          <h3>Что понадобится для подбора</h3>
          <ul className="readiness-list">
            <li>Суточная цель — {profile.calories} ккал, задана вручную</li>
            <li>Ограничения и оборудование — проверяются на сервере</li>
            <li>Цены и упаковки — источник пока не подключён</li>
            <li>
              Закреплённые блюда — {current.filter((e) => e.locked).length}, сохранятся в демоменю
            </li>
          </ul>
          <p className="muted">
            Пока соберите меню вручную. Превышение бюджета и неизвестные цены не будут выдаваться за
            успешный подбор.
          </p>
          <div className="dialog-actions">
            <button
              className="button primary"
              onClick={() => {
                setPreview(false);
                notify('Добавляйте блюда кнопкой «+» рядом с приёмом пищи');
              }}
            >
              Составить вручную
            </button>
          </div>
        </Dialog>
      )}
    </>
  );
}
