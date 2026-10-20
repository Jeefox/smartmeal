import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowDown,
  ArrowLeft,
  ArrowUp,
  Check,
  ChevronLeft,
  ChevronRight,
  Plus,
  Search,
  Trash2,
} from 'lucide-react';
import { Link, useNavigate, useParams } from 'react-router';
import { api, ApiError } from '../api';
import { demoProducts } from '../demo';
import { useApp, useOnline } from '../state';
import { Dialog, ErrorState, Loading, Notice, PageHeading } from '../components/ui';
import { useDebounce } from '../hooks';
import type { Product, RecipeInput } from '../types';
type Row = { key: string; productId: number; name: string; quantity: string };
export default function RecipeEditor() {
  const { id } = useParams();
  const edit = Boolean(id);
  const { mode, recipes, saveRecipe, notify } = useApp();
  const online = useOnline();
  const navigate = useNavigate();
  const client = useQueryClient();
  const detail = useQuery({
    queryKey: ['recipe', mode, id],
    queryFn: ({ signal }) => api.recipe(id!, signal),
    enabled: edit && mode === 'api',
  });
  const source = mode === 'demo' ? recipes.find((r) => r.id === id) : detail.data;
  const [step, setStep] = useState(0);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [minutes, setMinutes] = useState('25');
  const [servings, setServings] = useState('2');
  const [instructions, setInstructions] = useState('');
  const [rows, setRows] = useState<Row[]>([]);
  const [picker, setPicker] = useState(false);
  const [search, setSearch] = useState('');
  const debounced = useDebounce(search);
  const [productPage, setProductPage] = useState(0);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const [serverError, setServerError] = useState('');
  const [done, setDone] = useState(false);
  useEffect(() => {
    if (source) {
      setName(source.name);
      setDescription(source.description || '');
      setMinutes(String(source.preparationTimeMin));
      setServings(String(source.servings));
      setInstructions(source.instructions);
      setRows(
        source.ingredients.map((item) => ({
          key: crypto.randomUUID(),
          productId: item.productId,
          name: item.productName,
          quantity: String(item.quantityG),
        })),
      );
    }
  }, [source]);
  const dirty = !done && (name !== '' || rows.length > 0 || instructions !== '');
  useEffect(() => {
    if (!dirty) return;
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [dirty]);
  const products = useQuery({
    queryKey: ['editor-products', mode, debounced, productPage],
    queryFn: ({ signal }) =>
      api.products(
        new URLSearchParams({ q: debounced, page: String(productPage), size: '20' }),
        signal,
      ),
    enabled: picker && mode === 'api',
  });
  const productList =
    mode === 'demo'
      ? demoProducts.filter((p) => p.name.toLowerCase().includes(debounced.toLowerCase()))
      : products.data?.content || [];
  const productPages = mode === 'demo' ? 1 : products.data?.totalPages || 0;
  function addProduct(product: Product) {
    if (rows.some((row) => row.productId === product.id)) {
      setServerError('Этот продукт уже добавлен. Измените его массу в составе.');
      return;
    }
    setRows((current) => [
      ...current,
      { key: crypto.randomUUID(), productId: product.id, name: product.name, quantity: '100' },
    ]);
    setPicker(false);
    setServerError('');
  }
  function validate(current: number) {
    const next: Record<string, string> = {};
    if (current === 0) {
      if (!name.trim()) next.name = 'Укажите название';
      if (!Number.isInteger(Number(minutes)) || Number(minutes) < 0 || minutes === '')
        next.minutes = 'Введите целое число минут от 0';
      if (!Number.isInteger(Number(servings)) || Number(servings) <= 0)
        next.servings = 'Введите целое число порций больше 0';
    }
    if (current === 1) {
      if (!rows.length) next.ingredients = 'Добавьте хотя бы один продукт';
      rows.forEach((row, index) => {
        if (
          !Number.isFinite(Number(row.quantity)) ||
          Number(row.quantity) <= 0 ||
          Number(row.quantity) > 99999999.99 ||
          !/^(\d+)(\.\d{1,2})?$/.test(row.quantity)
        )
          next[`ingredients[${index}].quantityG`] =
            'Масса от 0,01 г, не более 2 знаков после запятой';
      });
    }
    if (current === 2 && !instructions.trim()) next.instructions = 'Опишите приготовление';
    setErrors(next);
    return Object.keys(next).length === 0;
  }
  function move(index: number, delta: number) {
    setRows((current) => {
      const next = [...current];
      [next[index], next[index + delta]] = [next[index + delta], next[index]];
      return next;
    });
  }
  async function submit() {
    if (!validate(2) || savingRef.current) return;
    setServerError('');
    if (!online) {
      setServerError('Нет сети. Сохранение приостановлено; форма остаётся открытой.');
      return;
    }
    savingRef.current = true;
    setSaving(true);
    const input: RecipeInput = {
      name: name.trim(),
      description: description.trim(),
      preparationTimeMin: Number(minutes),
      servings: Number(servings),
      instructions: instructions.trim(),
      ingredients: rows.map((row) => ({
        productId: row.productId,
        quantityG: Number(row.quantity),
      })),
    };
    try {
      const recipe =
        mode === 'demo'
          ? saveRecipe({
              ...input,
              id: id || crypto.randomUUID(),
              mine: true,
              category: 'Мои рецепты',
            })
          : edit
            ? await api.updateRecipe(id!, input)
            : await api.createRecipe(input);
      setDone(true);
      await client.invalidateQueries({ queryKey: ['recipes'] });
      await client.invalidateQueries({ queryKey: ['recipe', mode, recipe.id] });
      notify(mode === 'demo' ? 'Деморецепт сохранён в браузере' : 'Рецепт сохранён на сервере');
      navigate(`/recipes/${recipe.id}`);
    } catch (error) {
      if (error instanceof ApiError) {
        if ([404, 405, 501].includes(error.status))
          setServerError('Этот метод API пока не реализован. Данные формы сохранены на экране.');
        else setServerError(error.message);
        if (error.fields.length) {
          const fields: Record<string, string> = {};
          error.fields.forEach((item) => {
            fields[item.field] = item.message;
          });
          setErrors(fields);
          const ingredient = error.fields.some((item) => item.field.startsWith('ingredients'));
          setStep(
            ingredient
              ? 1
              : error.fields.some((item) =>
                    ['name', 'servings', 'preparationTimeMin'].includes(item.field),
                  )
                ? 0
                : 2,
          );
        }
      } else setServerError('Сохранить не удалось. Повторите попытку.');
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  }
  if (edit && mode === 'api' && detail.isPending) return <Loading />;
  if (detail.isError) return <ErrorState error={detail.error} />;
  if (edit && !source)
    return <ErrorState error={new Error('Рецепт для редактирования не найден')} />;
  return (
    <>
      <Link
        className="back-link"
        to="/recipes"
        onClick={(event) => {
          if (dirty && !window.confirm('Перейти в каталог и потерять несохранённые изменения?'))
            event.preventDefault();
        }}
      >
        <ArrowLeft size={16} />К каталогу
      </Link>
      <PageHeading
        eyebrow="ВАША КУЛИНАРНАЯ ИДЕЯ"
        title={edit ? 'Редактировать рецепт' : 'Новый рецепт'}
        description="Немного деталей — и ваша идея станет частью меню."
      />
      <div className="editor-layout">
        <section className="panel editor-panel">
          <div className="steps-nav">
            {['Основное', 'Ингредиенты', 'Приготовление'].map((label, index) => (
              <button
                key={label}
                type="button"
                className={step === index ? 'current' : step > index ? 'complete' : ''}
                disabled={index > step}
                onClick={() => setStep(index)}
              >
                <span>{step > index ? <Check size={15} /> : index + 1}</span>
                {label}
              </button>
            ))}
          </div>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              if (step < 2) {
                if (validate(step)) setStep(step + 1);
              } else submit();
            }}
          >
            {step === 0 && (
              <>
                <h2>Расскажите о блюде</h2>
                <label>
                  Название
                  <input
                    autoFocus
                    value={name}
                    maxLength={200}
                    onChange={(event) => setName(event.target.value)}
                    aria-invalid={Boolean(errors.name)}
                    aria-describedby="name-error"
                  />
                  {errors.name && (
                    <span id="name-error" className="field-error">
                      {errors.name}
                    </span>
                  )}
                </label>
                <label>
                  Описание <span className="optional">необязательно</span>
                  <textarea
                    rows={3}
                    value={description}
                    onChange={(event) => setDescription(event.target.value)}
                  />
                </label>
                <div className="form-row">
                  <label>
                    Время, мин
                    <input
                      type="number"
                      min="0"
                      step="1"
                      value={minutes}
                      onChange={(event) => setMinutes(event.target.value)}
                    />
                    {(errors.minutes || errors.preparationTimeMin) && (
                      <span className="field-error">
                        {errors.minutes || errors.preparationTimeMin}
                      </span>
                    )}
                  </label>
                  <label>
                    Исходных порций
                    <input
                      type="number"
                      min="1"
                      step="1"
                      value={servings}
                      onChange={(event) => setServings(event.target.value)}
                    />
                    {errors.servings && <span className="field-error">{errors.servings}</span>}
                  </label>
                </div>
                <Notice>
                  Фото и оборудование добавим после расширения API. Рецепт без фотографии получит
                  аккуратную заглушку.
                </Notice>
              </>
            )}
            {step === 1 && (
              <>
                <div className="section-title compact">
                  <h2>Из чего готовим?</h2>
                  <button
                    type="button"
                    className="button"
                    onClick={() => {
                      setSearch('');
                      setProductPage(0);
                      setPicker(true);
                    }}
                  >
                    <Plus size={17} />
                    Продукт
                  </button>
                </div>
                <p className="muted">
                  Масса сырой съедобной части до приготовления, в граммах. Поправку на очистку
                  рассчитает сервер при покупке.
                </p>
                {errors.ingredients && <p className="field-error">{errors.ingredients}</p>}
                {rows.length === 0 ? (
                  <div className="ingredient-empty">Добавьте первый продукт из справочника.</div>
                ) : (
                  rows.map((row, index) => (
                    <div className="editor-ingredient" key={row.key}>
                      <span className="row-number">{index + 1}</span>
                      <div>
                        <b>{row.name}</b>
                        <label className="weight-input">
                          <span className="sr-only">Масса: {row.name}</span>
                          <input
                            type="number"
                            min="0.01"
                            max="99999999.99"
                            step="0.01"
                            inputMode="decimal"
                            value={row.quantity}
                            onChange={(event) =>
                              setRows((current) =>
                                current.map((item) =>
                                  item.key === row.key
                                    ? { ...item, quantity: event.target.value }
                                    : item,
                                ),
                              )
                            }
                          />
                          г
                        </label>
                        {errors[`ingredients[${index}].quantityG`] && (
                          <p className="field-error">{errors[`ingredients[${index}].quantityG`]}</p>
                        )}
                      </div>
                      <div className="ingredient-controls">
                        <button
                          type="button"
                          className="icon-button"
                          disabled={index === 0}
                          onClick={() => move(index, -1)}
                          aria-label={`Поднять: ${row.name}`}
                        >
                          <ArrowUp size={16} />
                        </button>
                        <button
                          type="button"
                          className="icon-button"
                          disabled={index === rows.length - 1}
                          onClick={() => move(index, 1)}
                          aria-label={`Опустить: ${row.name}`}
                        >
                          <ArrowDown size={16} />
                        </button>
                        <button
                          type="button"
                          className="icon-button danger"
                          onClick={() =>
                            setRows((current) => current.filter((item) => item.key !== row.key))
                          }
                          aria-label={`Удалить: ${row.name}`}
                        >
                          <Trash2 size={16} />
                        </button>
                      </div>
                    </div>
                  ))
                )}
              </>
            )}
            {step === 2 && (
              <>
                <h2>Как приготовить?</h2>
                <label>
                  Инструкции
                  <textarea
                    autoFocus
                    rows={9}
                    value={instructions}
                    placeholder="Каждый шаг — с новой строки"
                    onChange={(event) => setInstructions(event.target.value)}
                  />
                  {errors.instructions && (
                    <span className="field-error">{errors.instructions}</span>
                  )}
                </label>
                <Notice>
                  КБЖУ в режиме API рассчитывает сервер. Локальный расчёт используется только для
                  демонстрационных рецептов.
                </Notice>
              </>
            )}
            {serverError && (
              <div role="alert" className="form-error">
                {serverError}
              </div>
            )}
            <div className="editor-actions">
              <button
                type="button"
                className="button"
                disabled={step === 0 || saving}
                onClick={() => setStep(step - 1)}
              >
                <ChevronLeft size={17} />
                Назад
              </button>
              <button type="submit" className="button primary" disabled={saving || !online}>
                {saving ? 'Сохраняем…' : step === 2 ? 'Сохранить рецепт' : 'Продолжить'}
                {!saving && <ChevronRight size={17} />}
              </button>
            </div>
          </form>
        </section>
        <aside className="panel editor-help">
          <span className="eyebrow">ПРОСТЫЕ ДЕТАЛИ</span>
          <h3>
            Ваш рецепт —<br />
            ваши правила.
          </h3>
          <p>
            Выбирайте продукты с подходящей обработкой: сухой рис и варёный рис — разные
            ингредиенты.
          </p>
          <p>Не переводим миллилитры и штуки в граммы без заданной массы или плотности.</p>
          <p>Обязательные поля и состав проверяются перед сохранением.</p>
          <span className="botanical">✳</span>
        </aside>
      </div>
      {picker && (
        <Dialog title="Выбрать продукт" onClose={() => setPicker(false)}>
          <label className="search-field">
            <Search size={18} />
            <input
              autoFocus
              aria-label="Поиск ингредиента"
              value={search}
              onChange={(event) => {
                setSearch(event.target.value);
                setProductPage(0);
              }}
              placeholder="Начните вводить название…"
            />
          </label>
          {products.isPending && mode === 'api' ? (
            <Loading />
          ) : products.isError ? (
            <ErrorState error={products.error} retry={() => products.refetch()} />
          ) : (
            <div className="product-picker">
              {productList.map((product) => (
                <button
                  type="button"
                  disabled={rows.some((row) => row.productId === product.id)}
                  key={product.id}
                  onClick={() => addProduct(product)}
                >
                  <span>
                    <b>{product.name}</b>
                    <small>{product.caloriesPer100g} ккал / 100 г</small>
                  </span>
                  {rows.some((row) => row.productId === product.id) ? (
                    <Check size={18} />
                  ) : (
                    <Plus size={18} />
                  )}
                </button>
              ))}
              {!productList.length && (
                <p className="muted">
                  Ничего не найдено. Продукты должны быть добавлены на бэкенде.
                </p>
              )}
            </div>
          )}
          {productPages > 1 && (
            <div className="pager">
              <button
                className="button"
                disabled={productPage === 0}
                onClick={() => setProductPage((p) => p - 1)}
              >
                Назад
              </button>
              <span>
                {productPage + 1} / {productPages}
              </span>
              <button
                className="button"
                disabled={productPage + 1 >= productPages}
                onClick={() => setProductPage((p) => p + 1)}
              >
                Далее
              </button>
            </div>
          )}
        </Dialog>
      )}
    </>
  );
}
