import MealComposition from './MealComposition';
import { useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { Plus, Search } from 'lucide-react';
import { ApiError } from '../api';
import {
  addRecipeToDay,
  mealLabels,
  mealTypes,
  planRecipes,
  snapshotRecipe,
  useDayPlan,
  useRefreshPlan,
  validPortions,
} from '../mealplans';
import type { ApiMealType, MealPlanEntry } from '../mealplans';
import type { RecipeSummary } from '../types';
import { useSession } from '../session';
import { useApp, useOnline } from '../state';
import { useDebounce } from '../hooks';
import {
  Dialog,
  Empty,
  ErrorState,
  Loading,
  Meta,
  Notice,
  NutritionValues,
  RecipeImage,
} from './ui';
export function LiveAddToPlan({
  onClose,
  recipe,
  initialMeal = 'LUNCH',
  date: initialDate,
}: {
  onClose: () => void;
  recipe?: RecipeSummary;
  initialMeal?: ApiMealType;
  date: string;
}) {
  const { notify } = useApp();
  const online = useOnline();
  const session = useSession();
  const [date, setDate] = useState(initialDate);
  const [meal, setMeal] = useState(initialMeal);
  const [portions, setPortions] = useState('1');
  const [selected, setSelected] = useState<RecipeSummary | undefined>(recipe);
  const [search, setSearch] = useState('');
  const q = useDebounce(search);
  const [page, setPage] = useState(0);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const plan = useDayPlan(date);
  const refresh = useRefreshPlan();
  const recipes = useQuery({
    queryKey: ['plan-recipes', session.profile!.userId, q, page],
    queryFn: ({ signal }) => planRecipes(q, page, signal),
    enabled: !recipe,
  });
  const confirmed = plan.data?.status === 'CONFIRMED';
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setError('');
    if (!selected || !validPortions(portions)) {
      setError(
        'Выберите рецепт и укажите положительное число порций до 9999,99, не более 2 знаков после запятой.',
      );
      return;
    }
    if (pending.current || !online || plan.isPending || plan.isError || confirmed) return;
    pending.current = true;
    setBusy(true);
    let saved = false;
    try {
      await addRecipeToDay(date, selected.id, meal, Number(portions));
      saved = true;
      await refresh(date);
      notify('Блюдо добавлено. Итоги обновлены с сервера.');
      onClose();
    } catch (err) {
      if (saved) {
        notify(
          'Блюдо сохранено, но обновить меню не удалось. Обновите страницу перед следующим действием.',
        );
        onClose();
      } else {
        setError(err instanceof Error ? err.message : 'Не удалось добавить блюдо');
        if (err instanceof ApiError && err.status === 409) await refresh(date).catch(() => {});
      }
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }
  return (
    <Dialog
      title="Добавить блюдо в меню"
      onClose={() => {
        if (!busy) onClose();
      }}
      wide
    >
      <form onSubmit={submit} noValidate>
        <div className="form-row">
          <label>
            День меню
            <input
              type="date"
              required
              value={date}
              disabled={busy}
              onChange={(e) => {
                if (e.target.value) {
                  setDate(e.target.value);
                  setError('');
                }
              }}
            />
          </label>
          <label>
            Приём пищи
            <select
              value={meal}
              disabled={busy}
              onChange={(e) => setMeal(e.target.value as ApiMealType)}
            >
              {mealTypes.map((type) => (
                <option value={type} key={type}>
                  {mealLabels[type]}
                </option>
              ))}
            </select>
          </label>
        </div>
        {plan.isPending ? (
          <p className="caption">Проверяем меню на выбранную дату…</p>
        ) : plan.isError ? (
          <ErrorState error={plan.error} retry={() => plan.refetch()} />
        ) : confirmed ? (
          <Notice tone="warning">
            Меню подтверждено и недоступно для изменений. Выберите другой день.
          </Notice>
        ) : !plan.data ? (
          <Notice>
            Для этого дня ещё нет меню. При подтверждении добавления создадим черновик на сервере.
          </Notice>
        ) : null}
        {!recipe && (
          <>
            <label className="search-field">
              <Search size={18} />
              <input
                aria-label="Найти блюдо для меню"
                placeholder="Название рецепта"
                value={search}
                disabled={busy}
                onChange={(e) => {
                  setSearch(e.target.value);
                  setPage(0);
                }}
              />
            </label>
            {recipes.isPending ? (
              <Loading />
            ) : recipes.isError ? (
              <ErrorState error={recipes.error} retry={() => recipes.refetch()} />
            ) : (
              <div className="recipe-picker live-recipe-picker">
                {recipes.data?.content.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    disabled={busy}
                    aria-pressed={selected?.id === item.id}
                    className={selected?.id === item.id ? 'selected' : ''}
                    onClick={() => setSelected(item)}
                  >
                    <RecipeImage recipe={item} />
                    <span>
                      <b>{item.name}</b>
                      <small>
                        {item.preparationTimeMin} мин · {item.servings} исходных порций
                      </small>
                    </span>
                    <Plus size={18} />
                  </button>
                ))}
                {!recipes.data?.content.length && (
                  <Empty title="Рецепты не найдены">
                    Измените запрос или сначала добавьте рецепт в каталог.
                  </Empty>
                )}
              </div>
            )}
            {recipes.data && recipes.data.totalPages > 1 && (
              <div className="pager">
                <button
                  type="button"
                  className="button"
                  disabled={page === 0 || busy}
                  onClick={() => setPage(page - 1)}
                >
                  Назад
                </button>
                <span>
                  {page + 1} / {recipes.data.totalPages}
                </span>
                <button
                  type="button"
                  className="button"
                  disabled={page + 1 >= recipes.data.totalPages || busy}
                  onClick={() => setPage(page + 1)}
                >
                  Далее
                </button>
              </div>
            )}
          </>
        )}
        <p className="muted">
          {selected ? `Выбрано: ${selected.name}` : 'Выберите блюдо из каталога.'}
        </p>
        <label>
          Количество порций
          <input
            type="number"
            min="0.01"
            max="9999.99"
            step="0.01"
            inputMode="decimal"
            value={portions}
            disabled={busy}
            onChange={(e) => setPortions(e.target.value)}
          />
        </label>
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <div className="dialog-actions">
          <button type="button" className="button" onClick={onClose} disabled={busy}>
            Отмена
          </button>
          <button
            className="button primary"
            disabled={!selected || busy || !online || plan.isPending || plan.isError || confirmed}
          >
            {busy ? 'Сохраняем…' : plan.data ? 'Добавить блюдо' : 'Создать меню и добавить'}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
export function SnapshotDialog({ entry, onClose }: { entry: MealPlanEntry; onClose: () => void }) {
  const snapshot = entry.recipeSnapshot;
  return (
    <Dialog title={snapshot.name} onClose={onClose} wide>
      <MealComposition recipe={entry.recipeSnapshot} people={entry.personPortions || []} />
      <Notice>Снимок блюда в меню. Изменения исходного рецепта не меняют эти данные.</Notice>
      <p className="muted">{snapshot.description}</p>
      <Meta recipe={snapshotRecipe(snapshot)} />
      <h3>
        {snapshot.components?.length
          ? 'Общее количество для приготовления по меню'
          : `Выбрано порций: ${entry.portions}`}
      </h3>
      <NutritionValues nutrition={entry.nutrition} label="КБЖУ выбранных порций · расчёт сервера" />
      <h3>Состав исходного рецепта · {snapshot.servings} порц.</h3>
      <ul className="ingredient-list">
        {snapshot.ingredients.map((item) => (
          <li key={item.productId}>
            <span>{item.productName}</span>
            <b>{item.quantityG} г</b>
          </li>
        ))}
      </ul>
      <ol className="recipe-steps">
        {snapshot.instructions
          .split(/\n+/)
          .filter(Boolean)
          .map((step, index) => (
            <li key={index}>
              <span>{index + 1}</span>
              <p>{step}</p>
            </li>
          ))}
      </ol>
      <Link className="text-link" to={`/recipes/${snapshot.recipeId}`}>
        Открыть текущую версию рецепта
      </Link>
    </Dialog>
  );
}
