import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api';
import { apiFields, catalogueApi, invalidateRetail, packageErrors } from '../catalogue';
import type { StoreProduct } from '../catalogue';
import type { Product } from '../types';
import { useSession } from '../session';
import { useOnline } from '../state';
import { ErrorState, Loading, Notice } from './ui';
export default function CreateStoreProduct({
  storeId,
  onCreated,
  onCancel,
}: {
  storeId: number;
  onCreated: (sku: StoreProduct) => void;
  onCancel: () => void;
}) {
  const user = useSession().profile!.userId,
    online = useOnline(),
    client = useQueryClient();
  const [search, setSearch] = useState(''),
    [q, setQ] = useState(''),
    [page, setPage] = useState(0),
    [category, setCategory] = useState('');
  const [picked, setPicked] = useState<Product | null>(null),
    [name, setName] = useState(''),
    [weight, setWeight] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({}),
    [error, setError] = useState<Error | null>(null),
    [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const params = new URLSearchParams({ q, page: String(page), size: '12' });
  if (category) params.set('categoryId', category);
  const products = useQuery({
    queryKey: ['sku-base-products', user, params.toString()],
    queryFn: ({ signal }) => api.products(params, signal),
    refetchOnWindowFocus: true,
  });
  const categories = useQuery({
    queryKey: ['sku-base-categories', user],
    queryFn: ({ signal }) => api.categories(signal),
  });
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (lock.current || !online) return;
    const next = packageErrors(picked?.id ?? null, name, weight);
    setErrors(next);
    setError(null);
    if (Object.keys(next).length) return;
    lock.current = true;
    setBusy(true);
    try {
      const result = await catalogueApi.create({
        storeId,
        productId: picked!.id,
        name: name.trim(),
        packageWeightG: Number(weight),
      });
      await invalidateRetail(client, user, storeId);
      onCreated(result);
    } catch (err) {
      setError(err as Error);
      setErrors(apiFields(err));
    } finally {
      setBusy(false);
      lock.current = false;
    }
  }
  return (
    <section className="sku-create" aria-label="Новая упаковка">
      <div className="section-heading">
        <h3>Добавить упаковку в этот магазин</h3>
        <button className="button quiet" onClick={onCancel} disabled={busy}>
          Отмена
        </button>
      </div>
      <p>
        Выберите существующий базовый продукт. Вес упаковки — покупная масса, которую можно
        сопоставить потребности в покупках.
      </p>
      <form
        className="sku-search"
        onSubmit={(e) => {
          e.preventDefault();
          setQ(search.trim());
          setPage(0);
          setPicked(null);
          if (q === search.trim() && page === 0) products.refetch();
        }}
      >
        <label>
          <span>Поиск базового продукта</span>
          <input value={search} onChange={(e) => setSearch(e.target.value)} disabled={busy} />
        </label>
        <label>
          <span>Категория базового продукта</span>
          <select
            value={category}
            onChange={(e) => {
              setCategory(e.target.value);
              setPage(0);
              setPicked(null);
            }}
            disabled={busy}
          >
            <option value="">Все категории</option>
            {categories.data?.map((item) => (
              <option value={item.id} key={item.id}>
                {item.name}
              </option>
            ))}
          </select>
        </label>
        <button className="button" disabled={!online || busy}>
          Найти продукт
        </button>
      </form>
      {categories.isError && (
        <Notice tone="warning">
          Категории не загрузились. Можно искать по всем категориям.{' '}
          <button className="text-link" onClick={() => categories.refetch()} disabled={!online}>
            Повторить категории
          </button>
        </Notice>
      )}
      {products.isPending ? (
        <Loading />
      ) : products.isError ? (
        <ErrorState error={products.error} retry={() => products.refetch()} />
      ) : (
        <>
          {!products.data?.content.length && (
            <p role="status">Базовые продукты не найдены. Измените поиск или категорию.</p>
          )}
          <div className="sku-base-options" role="group" aria-label="Выбор базового продукта">
            {products.data?.content.map((product) => (
              <button
                className={`store-choice ${picked?.id === product.id ? 'selected' : ''}`}
                key={product.id}
                aria-pressed={picked?.id === product.id}
                disabled={busy || !online}
                onClick={() => setPicked(product)}
              >
                <span>
                  <strong>{product.name}</strong>
                  <small>
                    Для покупок: {product.shoppingName ?? 'название не задано'} · доля съедобной
                    части: {product.edibleYield ?? 'не указана'}
                  </small>
                </span>
              </button>
            ))}
          </div>
          <div className="sku-pager">
            <button
              className="button"
              disabled={!online || busy || page === 0}
              onClick={() => {
                setPage(page - 1);
                setPicked(null);
              }}
            >
              Предыдущие продукты
            </button>
            <span>Страница {page + 1}</span>
            <button
              className="button"
              disabled={!online || busy || page + 1 >= (products.data?.totalPages ?? 0)}
              onClick={() => {
                setPage(page + 1);
                setPicked(null);
              }}
            >
              Следующие продукты
            </button>
          </div>
        </>
      )}
      <form className="sku-create-form" onSubmit={submit} noValidate>
        <p>
          Базовый продукт: <strong>{picked?.name ?? 'не выбран'}</strong>
        </p>
        {errors.productId && (
          <p className="field-error" role="alert">
            {errors.productId}
          </p>
        )}
        <label>
          <span>Название упаковки</span>
          <input
            aria-label="Название упаковки"
            aria-describedby={errors.name ? 'sku-name-error' : undefined}
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={300}
            disabled={busy}
            aria-invalid={Boolean(errors.name)}
          />
          {errors.name && (
            <span id="sku-name-error" className="field-error" role="alert">
              {errors.name}
            </span>
          )}
        </label>
        <label>
          <span>Вес упаковки, г</span>
          <input
            aria-label="Вес упаковки, г"
            aria-describedby={errors.packageWeightG ? 'sku-weight-error' : undefined}
            value={weight}
            inputMode="numeric"
            onChange={(e) => setWeight(e.target.value)}
            disabled={busy}
            aria-invalid={Boolean(errors.packageWeightG)}
          />
          {errors.packageWeightG && (
            <span id="sku-weight-error" className="field-error" role="alert">
              {errors.packageWeightG}
            </span>
          )}
        </label>
        {error && (
          <div className="estimate-error" role="alert">
            <p>{error.message}</p>
            {error instanceof ApiError && error.status === 0 && (
              <p>
                Ответ не получен. Проверьте каталог перед повтором: упаковка могла сохраниться.
                Автоматической повторной записи нет.
              </p>
            )}
          </div>
        )}
        <button className="button primary" disabled={!online || busy}>
          {busy ? 'Создаём…' : 'Создать упаковку'}
        </button>
      </form>
    </section>
  );
}
