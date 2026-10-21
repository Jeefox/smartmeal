import { useState } from 'react';
import { useDebounce } from '../hooks';
import { useQuery } from '@tanstack/react-query';
import {
  ArrowLeft,
  ArrowRight,
  ChefHat,
  ChevronLeft,
  ChevronRight,
  Heart,
  Plus,
  Search,
} from 'lucide-react';
import { Link, useParams, useSearchParams } from 'react-router';
import { api } from '../api';
import { LiveAddToPlan } from '../components/LiveMealDialogs';
import { demoCategories, demoProducts } from '../demo';
import { number } from '../domain';
import { useApp } from '../state';
import {
  Dialog,
  Empty,
  ErrorState,
  Loading,
  Meta,
  Notice,
  NutritionValues,
  PageHeading,
  RecipeImage,
} from '../components/ui';
import type { MealType, Recipe } from '../types';
function Pager({
  page,
  pages,
  onChange,
}: {
  page: number;
  pages: number;
  onChange: (page: number) => void;
}) {
  return pages > 1 ? (
    <div className="pager">
      <button
        className="button"
        disabled={page === 0}
        onClick={() => onChange(page - 1)}
        aria-label="Предыдущая страница"
      >
        <ChevronLeft size={18} />
      </button>
      <span>
        {page + 1} / {pages}
      </span>
      <button
        className="button"
        disabled={page + 1 >= pages}
        onClick={() => onChange(page + 1)}
        aria-label="Следующая страница"
      >
        <ChevronRight size={18} />
      </button>
    </div>
  ) : null;
}
export function Catalog() {
  const { mode, recipes, favorites, toggleFavorite } = useApp();
  const [params, setParams] = useSearchParams();
  const search = params.get('q') || '';
  const debounced = useDebounce(search);
  const category = params.get('category') || 'Все';
  const page = Math.max(0, Number(params.get('page')) || 0);
  const time = params.get('maxTime') || '';
  const sort = params.get('sort') || 'createdAt,desc';
  const favoriteOnly = params.get('favorites') === 'true';
  const query = new URLSearchParams({ q: debounced, page: String(page), size: '12', sort });
  query.append('sort', 'id,asc');

  const response = useQuery({
    queryKey: ['recipes', mode, query.toString()],
    queryFn: ({ signal }) => api.recipes(query, signal),
    enabled: mode === 'api',
  });
  function update(key: string, value: string) {
    const next = new URLSearchParams(params);
    value ? next.set(key, value) : next.delete(key);
    if (key !== 'page') next.delete('page');
    setParams(next, { replace: key === 'q' });
  }
  const filtered = recipes
    .filter(
      (recipe) =>
        (!favoriteOnly || favorites.includes(recipe.id)) &&
        (category === 'Все' || recipe.category === category) &&
        (!time || recipe.preparationTimeMin <= Number(time)) &&
        `${recipe.name} ${recipe.description} ${recipe.ingredients.map((item) => item.productName).join(' ')}`
          .toLowerCase()
          .includes(debounced.trim().toLowerCase()),
    )
    .sort((a, b) =>
      sort === 'name,asc'
        ? a.name.localeCompare(b.name, 'ru')
        : sort === 'preparationTimeMin,asc'
          ? a.preparationTimeMin - b.preparationTimeMin
          : 0,
    );
  const list =
    mode === 'demo' ? filtered.slice(page * 12, page * 12 + 12) : response.data?.content || [];
  const total = mode === 'demo' ? filtered.length : response.data?.totalElements || 0;
  const pages = mode === 'demo' ? Math.ceil(filtered.length / 12) : response.data?.totalPages || 0;
  return (
    <>
      <PageHeading
        eyebrow="ВДОХНОВЕНИЕ НА КАЖДЫЙ ДЕНЬ"
        title="Что приготовим?"
        description="Знакомые продукты. Новые любимые блюда."
        actions={
          <Link className="button primary" to="/recipes/new">
            <Plus size={18} />
            Добавить рецепт
          </Link>
        }
      />
      <div className="section-title">
        <h2>
          Каталог рецептов <span className="count">{total}</span>
        </h2>
        {mode === 'demo' && (
          <button
            className={`button quiet ${favoriteOnly ? 'selected' : ''}`}
            onClick={() => update('favorites', favoriteOnly ? '' : 'true')}
            aria-pressed={favoriteOnly}
          >
            <Heart size={16} />
            Избранное
          </button>
        )}
      </div>
      <div className="filters">
        <label className="search-field">
          <Search size={19} />
          <input
            aria-label="Поиск рецептов"
            placeholder="Найти рецепт…"
            value={search}
            onChange={(event) => update('q', event.target.value)}
          />
        </label>
        {mode === 'demo' && (
          <label className="select-field">
            <span className="sr-only">Время приготовления</span>
            <select value={time} onChange={(event) => update('maxTime', event.target.value)}>
              <option value="">Любое время</option>
              <option value="15">До 15 минут</option>
              <option value="30">До 30 минут</option>
              <option value="60">До часа</option>
            </select>
          </label>
        )}
        <label className="select-field">
          <span className="sr-only">Сортировка</span>
          <select value={sort} onChange={(event) => update('sort', event.target.value)}>
            <option value="createdAt,desc">Сначала новые</option>
            <option value="preparationTimeMin,asc">Сначала быстрые</option>
            <option value="name,asc">По названию</option>
          </select>
        </label>
      </div>
      {mode === 'demo' ? (
        <div className="chips">
          {['Все', 'Завтраки', 'Основные блюда', 'Салаты', 'Супы'].map((item) => (
            <button
              key={item}
              className={`chip ${category === item ? 'active' : ''}`}
              aria-pressed={category === item}
              onClick={() => update('category', item === 'Все' ? '' : item)}
            >
              {item === 'Все' ? 'Все рецепты' : item}
            </button>
          ))}
        </div>
      ) : null}
      {mode === 'api' && response.isPending ? (
        <Loading />
      ) : response.isError ? (
        <ErrorState error={response.error} retry={() => response.refetch()} />
      ) : list.length ? (
        <div className="recipe-grid">
          {list.map((recipe) => (
            <article className="recipe-card" key={recipe.id}>
              <Link to={`/recipes/${recipe.id}`} className="recipe-photo-link">
                <RecipeImage recipe={recipe} />
                {recipe.category && <span className="photo-label">{recipe.category}</span>}
              </Link>
              {mode === 'demo' && (
                <button
                  className={`favorite-button ${favorites.includes(recipe.id) ? 'saved' : ''}`}
                  aria-label={`${favorites.includes(recipe.id) ? 'Убрать из избранного' : 'В избранное'}: ${recipe.name}`}
                  aria-pressed={favorites.includes(recipe.id)}
                  onClick={() => toggleFavorite(recipe.id)}
                >
                  <Heart size={18} />
                </button>
              )}
              <div className="recipe-card-body">
                <Link to={`/recipes/${recipe.id}`}>
                  <h3>{recipe.name}</h3>
                </Link>
                <p>{recipe.description || 'Откройте рецепт и посмотрите состав'}</p>
                <Meta recipe={recipe} />
              </div>
            </article>
          ))}
        </div>
      ) : (
        <Empty
          title="Рецепты не нашлись"
          action={
            <button className="button" onClick={() => setParams({})}>
              Сбросить фильтры
            </button>
          }
        >
          {page > 0
            ? 'На этой странице больше нет рецептов. Вернитесь к началу каталога.'
            : 'Попробуйте другой запрос или добавьте собственный рецепт.'}
        </Empty>
      )}
      <Pager
        page={page}
        pages={pages}
        onChange={(next) => {
          update('page', String(next));
          window.scrollTo({ top: 0, behavior: 'instant' });
        }}
      />
      {list.length > 0 && (
        <p className="caption catalog-range">
          Показаны {page * 12 + 1}–{Math.min((page + 1) * 12, total)} из {total} рецептов
        </p>
      )}
    </>
  );
}
export function AddToPlan({ recipe, onClose }: { recipe: Recipe; onClose: () => void }) {
  const { mode, selectedDate } = useApp();
  return mode === 'api' ? (
    <LiveAddToPlan recipe={recipe} date={selectedDate} onClose={onClose} />
  ) : (
    <DemoAddToPlan recipe={recipe} onClose={onClose} />
  );
}
function DemoAddToPlan({ recipe, onClose }: { recipe: Recipe; onClose: () => void }) {
  const { selectedDate, addEntry, mode } = useApp();
  const [meal, setMeal] = useState<MealType>('Обед');
  const [date, setDate] = useState(selectedDate);
  const [portions, setPortions] = useState('1');
  return (
    <Dialog title="Добавить в меню" onClose={onClose}>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (mode === 'demo') {
            addEntry(recipe, meal, date, Number(portions));
            onClose();
          }
        }}
      >
        <p className="muted">{recipe.name}</p>
        <label>
          День
          <input
            type="date"
            value={date}
            onChange={(event) => setDate(event.target.value)}
            required
          />
        </label>
        <label>
          Приём пищи
          <select value={meal} onChange={(event) => setMeal(event.target.value as MealType)}>
            {['Завтрак', 'Обед', 'Ужин', 'Перекус'].map((item) => (
              <option key={item}>{item}</option>
            ))}
          </select>
        </label>
        <label>
          Порций
          <input
            type="number"
            min="0.1"
            max="20"
            step="0.1"
            value={portions}
            onChange={(event) => setPortions(event.target.value)}
            required
          />
        </label>
        <Notice>
          Это изменение демоменю. Данные остаются в этом браузере; серверное меню ещё не подключено.
        </Notice>
        <div className="dialog-actions">
          <button type="button" className="button" onClick={onClose}>
            Отмена
          </button>
          <button className="button primary">Добавить</button>
        </div>
      </form>
    </Dialog>
  );
}
export function RecipeDetail() {
  const { id = '' } = useParams();
  const { mode, recipes, favorites, toggleFavorite } = useApp();
  const response = useQuery({
    queryKey: ['recipe', mode, id],
    queryFn: ({ signal }) => api.recipe(id, signal),
    enabled: mode === 'api',
  });
  const recipe = mode === 'demo' ? recipes.find((item) => item.id === id) : response.data;
  const [portions, setPortions] = useState<string>('');
  const [add, setAdd] = useState(false);
  if (mode === 'api' && response.isPending) return <Loading />;
  if (response.isError)
    return <ErrorState error={response.error} retry={() => response.refetch()} />;
  if (!recipe)
    return (
      <Empty
        title="Рецепт не найден"
        action={
          <Link to="/recipes" className="button">
            В каталог
          </Link>
        }
      >
        Возможно, он недоступен или был удалён.
      </Empty>
    );
  const selected = Number(portions || recipe.servings);
  const valid = Number.isFinite(selected) && selected > 0 && selected <= 100;
  const scaled =
    recipe.nutritionTotal && valid
      ? (Object.fromEntries(
          Object.entries(recipe.nutritionTotal).map(([key, value]) => [
            key,
            (value * selected) / recipe.servings,
          ]),
        ) as typeof recipe.nutritionTotal)
      : null;
  return (
    <>
      <Link className="back-link" to="/recipes">
        <ArrowLeft size={16} />К рецептам
      </Link>
      <PageHeading
        eyebrow={recipe.category || 'ВАША КУЛИНАРНАЯ КНИГА'}
        title={recipe.name}
        description={recipe.description || undefined}
        actions={
          <>
            {mode === 'demo' && (
              <button className="button" onClick={() => toggleFavorite(recipe.id)}>
                <Heart size={17} fill={favorites.includes(recipe.id) ? 'currentColor' : 'none'} />
                Сохранить
              </button>
            )}
            {mode === 'demo' && recipe.mine && (
              <Link className="button" to={`/recipes/${recipe.id}/edit`}>
                Редактировать
              </Link>
            )}
          </>
        }
      />
      <div className="detail-layout">
        <div>
          <RecipeImage className="detail-photo" recipe={recipe} />
          <div className="panel">
            <div className="section-title compact">
              <h2>Как приготовить</h2>
              <Meta recipe={recipe} />
            </div>
            <ol className="recipe-steps">
              {recipe.instructions
                .split(/\n+/)
                .filter(Boolean)
                .map((step, index) => (
                  <li key={index}>
                    <span>{String(index + 1).padStart(2, '0')}</span>
                    <p>{step}</p>
                  </li>
                ))}
            </ol>
            <h3>Оборудование</h3>
            <div className="chips">
              {recipe.equipment?.length ? (
                recipe.equipment.map((item) => (
                  <span key={item} className="chip">
                    {item}
                  </span>
                ))
              ) : (
                <p className="muted">Сведения пока не заполнены</p>
              )}
            </div>
            <h3>Аллергены</h3>
            <p className="muted">
              {recipe.allergens === undefined
                ? 'Данные об аллергенах отсутствуют. Безопасность для аллергии не подтверждена.'
                : recipe.allergens.length
                  ? recipe.allergens.join(', ')
                  : 'В учебных данных не указаны. Проверяйте состав конкретных товаров.'}
            </p>
          </div>
        </div>
        <aside className="detail-aside">
          <div className="panel">
            <h2>Пищевая ценность</h2>
            <NutritionValues
              nutrition={recipe.nutritionPerServing}
              label="На одну исходную порцию"
            />
            <details>
              <summary>Весь исходный рецепт · {recipe.servings} порц.</summary>
              <NutritionValues nutrition={recipe.nutritionTotal} />
            </details>
          </div>
          <div className="panel">
            <div className="section-title compact">
              <h2>Ингредиенты</h2>
              <ChefHat size={20} />
            </div>
            <label className="portion-field">
              Количество порций
              <input
                type="number"
                min="0.1"
                max="100"
                step="0.1"
                value={portions || recipe.servings}
                onChange={(event) => setPortions(event.target.value)}
              />
            </label>
            {!valid && <p className="field-error">Введите от 0,1 до 100 порций</p>}
            <p className="caption">
              Вес сырой съедобной части до приготовления. Покупная масса рассчитывается отдельно на
              сервере.
            </p>
            <ul className="ingredient-list">
              {recipe.ingredients.map((item) => (
                <li key={item.productId}>
                  <span>{item.productName}</span>
                  <b>{valid ? number((item.quantityG * selected) / recipe.servings, 2) : '—'} г</b>
                </li>
              ))}
            </ul>
            {scaled && (
              <details>
                <summary>КБЖУ выбранного количества</summary>
                <NutritionValues nutrition={scaled} />
              </details>
            )}
            <p className="caption">Пересчёт для просмотра не изменяет исходный рецепт.</p>
            <button className="button primary full" onClick={() => setAdd(true)}>
              <Plus size={17} />
              Добавить в меню
            </button>
          </div>
          <div className="panel subtle">
            <h3>Сколько стоит приготовить?</h3>
            <p className="muted">
              {mode === 'api'
                ? 'Цены и упаковки пока не рассчитываются. Сохранённое меню не подтверждает стоимость покупок.'
                : 'Стоимость ингредиентов и покупку упаковок смотрите в расчёте меню. Неизвестная цена не считается нулевой.'}
            </p>
            <Link to="/shopping" className="text-link">
              К покупкам <ArrowRight size={15} />
            </Link>
          </div>
        </aside>
      </div>
      {add && <AddToPlan recipe={recipe} onClose={() => setAdd(false)} />}
    </>
  );
}
export function Products() {
  const { mode } = useApp();
  const [params, setParams] = useSearchParams();
  const q = params.get('q') || '';
  const debounced = useDebounce(q);
  const page = Math.max(0, Number(params.get('page')) || 0);
  const category = params.get('categoryId') || '';
  const query = new URLSearchParams({ q: debounced, page: String(page), size: '12' });
  if (category) query.set('categoryId', category);
  const response = useQuery({
    queryKey: ['products', mode, query.toString()],
    queryFn: ({ signal }) => api.products(query, signal),
    enabled: mode === 'api',
  });
  const categories = useQuery({
    queryKey: ['categories', mode],
    queryFn: ({ signal }) => api.categories(signal),
    enabled: mode === 'api',
  });
  const filtered = demoProducts.filter(
    (p) =>
      p.name.toLowerCase().includes(debounced.toLowerCase()) &&
      (!category || String(p.categoryId) === category),
  );
  const list =
    mode === 'demo' ? filtered.slice(page * 12, page * 12 + 12) : response.data?.content || [];
  const total = mode === 'demo' ? filtered.length : response.data?.totalElements || 0;
  function update(key: string, value: string) {
    const next = new URLSearchParams(params);
    value ? next.set(key, value) : next.delete(key);
    if (key !== 'page') next.delete('page');
    setParams(next, { replace: key === 'q' });
  }
  return (
    <>
      <PageHeading
        eyebrow="ОСНОВА ВАШИХ РЕЦЕПТОВ"
        title="Каталог продуктов"
        description="Пищевая ценность на 100 г сырой съедобной части до приготовления."
      />
      <div className="filters">
        <label className="search-field">
          <Search size={18} />
          <input
            aria-label="Поиск продуктов"
            placeholder="Название продукта"
            value={q}
            onChange={(event) => update('q', event.target.value)}
          />
        </label>
        <select
          aria-label="Категория продукта"
          value={category}
          onChange={(event) => update('categoryId', event.target.value)}
        >
          <option value="">Все категории</option>
          {(mode === 'demo' ? demoCategories : categories.data || []).map((item) => (
            <option key={item.id} value={item.id}>
              {item.name}
            </option>
          ))}
        </select>
      </div>
      {response.isPending && mode === 'api' ? (
        <Loading />
      ) : response.isError ? (
        <ErrorState error={response.error} retry={() => response.refetch()} />
      ) : list.length ? (
        <div className="product-grid">
          {list.map((product) => (
            <article className="panel" key={product.id}>
              <span className="eyebrow">ПРОДУКТ · {product.id}</span>
              <h3>{product.name}</h3>
              {mode === 'api' && (
                <p className="caption">
                  Для покупок: {product.shoppingName ?? 'не указано'}
                  <br />
                  Доля съедобной части:{' '}
                  {product.edibleYield == null ? 'не указана' : number(product.edibleYield, 6)}.
                  Значение из справочника; источник измерения не указан.
                </p>
              )}
              <NutritionValues
                nutrition={{
                  calories: product.caloriesPer100g,
                  protein: product.proteinPer100g,
                  fat: product.fatPer100g,
                  carbs: product.carbsPer100g,
                }}
                label="На 100 г"
              />
            </article>
          ))}
        </div>
      ) : (
        <Empty title="Продукты не найдены">
          Измените запрос или наполните справочник на бэкенде.
        </Empty>
      )}
      <Pager
        page={page}
        pages={mode === 'demo' ? Math.ceil(filtered.length / 12) : response.data?.totalPages || 0}
        onChange={(next) => update('page', String(next))}
      />
      <p className="caption">Всего продуктов: {total}</p>
    </>
  );
}
