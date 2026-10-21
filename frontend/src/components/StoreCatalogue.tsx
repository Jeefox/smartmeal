import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { catalogueApi, catalogueKey } from '../catalogue';
import type { StoreProduct } from '../catalogue';
import type { Store } from '../retail';
import { useSession } from '../session';
import { useOnline } from '../state';
import { ErrorState, Loading, Notice } from './ui';
import CreateStoreProduct from './CreateStoreProduct';
import PriceCard from './PriceCard';
import QuickPriceEntry from './QuickPriceEntry';
import CataloguePriceReferences from './CataloguePriceReferences';
export default function StoreCatalogue({ store }: { store: Store }) {
  const user = useSession().profile!.userId,
    online = useOnline();
  const [search, setSearch] = useState(''),
    [name, setName] = useState(''),
    [page, setPage] = useState(0),
    [selected, setSelected] = useState<StoreProduct | null>(null),
    [creating, setCreating] = useState(false),
    [created, setCreated] = useState(false);
  const [quickEntry, setQuickEntry] = useState(false);
  const query = useQuery({
    queryKey: catalogueKey(user, store.id, name, page),
    queryFn: ({ signal }) => catalogueApi.list(store.id, name, page, signal),
    staleTime: 0,
    refetchOnWindowFocus: true,
  });
  function reset() {
    setSelected(null);
    setCreating(false);
    setCreated(false);
  }
  if (quickEntry)
    return (
      <section className="store-catalogue">
        <p>
          {store.chainName} · {store.address}
        </p>
        <button className="button quiet" onClick={() => setQuickEntry(false)}>
          Вернуться к упаковкам
        </button>
        <CataloguePriceReferences />
        <QuickPriceEntry storeId={store.id} />
      </section>
    );
  return (
    <section className="store-catalogue" aria-label="Каталог упаковок магазина">
      <div className="section-heading">
        <div>
          <span className="eyebrow">ТОВАРЫ И ЦЕНЫ</span>
          <h3>{store.chainName}</h3>
          <p>
            {store.city}, {store.address}
          </p>
        </div>
        <button
          className="button"
          disabled={!online || query.isFetching}
          onClick={() => query.refetch()}
        >
          Обновить каталог
        </button>
      </div>
      <CataloguePriceReferences />
      {!online && (
        <Notice tone="warning">
          Нет сети. Полученный каталог может быть устаревшим, создание упаковок и запись цен
          недоступны.
        </Notice>
      )}
      <div className="sku-create-invite">
        <div>
          <h3>Нужно заполнить сразу несколько цен?</h3>
          <p>
            Продукты уже собраны из рецептов. Введите известные цены и сохраните одним действием.
          </p>
        </div>
        <button className="button primary" onClick={() => setQuickEntry(true)}>
          Быстро заполнить цены
        </button>
      </div>
      <form
        className="sku-search"
        onSubmit={(e) => {
          e.preventDefault();
          reset();
          setName(search.trim());
          setPage(0);
          if (name === search.trim() && page === 0) query.refetch();
        }}
      >
        <label>
          <span>Поиск упаковок</span>
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Название на упаковке"
          />
        </label>
        <button className="button primary" disabled={!online}>
          Найти упаковки
        </button>
      </form>
      {query.isPending ? (
        <Loading />
      ) : query.isError && !query.data ? (
        <ErrorState error={query.error} retry={() => query.refetch()} />
      ) : (
        <>
          {query.isError && (
            <Notice tone="warning">
              Каталог не обновился: {query.error.message} Ниже ранее полученные данные; выбор
              недоступен до успешного обновления.
            </Notice>
          )}
          {!query.data?.content.length && !query.isError && (
            <Notice>
              Упаковки не найдены. Измените поиск, вернитесь на предыдущую страницу или добавьте
              недостающую упаковку.
            </Notice>
          )}
          <div className="sku-options" role="group" aria-label="Упаковки магазина">
            {query.data?.content.map((sku) => (
              <button
                className={`store-choice ${selected?.id === sku.id ? 'selected' : ''}`}
                key={sku.id}
                aria-pressed={selected?.id === sku.id}
                disabled={!online || query.isError}
                onClick={() => {
                  setSelected(sku);
                  setCreating(false);
                  setCreated(false);
                }}
              >
                <span>
                  <strong>{sku.name}</strong>
                  <small>
                    {sku.packageWeightG} г · базовый продукт №{sku.productId}
                  </small>
                </span>
              </button>
            ))}
          </div>
          <div className="sku-pager">
            <button
              className="button"
              disabled={!online || page === 0 || query.isFetching}
              onClick={() => {
                reset();
                setPage(page - 1);
              }}
            >
              Предыдущие упаковки
            </button>
            <span>
              Страница {page + 1} · всего {query.data?.totalElements ?? 0}
            </span>
            <button
              className="button"
              disabled={!online || query.isFetching || page + 1 >= (query.data?.totalPages ?? 0)}
              onClick={() => {
                reset();
                setPage(page + 1);
              }}
            >
              Следующие упаковки
            </button>
          </div>
        </>
      )}
      <div className="sku-create-invite">
        <p>Не нашли подходящую упаковку? Сначала проверьте поиск, чтобы не создать дубликат.</p>
        <button
          className="button"
          disabled={!online || creating || query.isError || query.isPending}
          onClick={() => {
            reset();
            setCreating(true);
          }}
        >
          Добавить упаковку
        </button>
      </div>
      {creating && (
        <CreateStoreProduct
          storeId={store.id}
          onCancel={() => setCreating(false)}
          onCreated={(sku) => {
            setSelected(sku);
            setCreating(false);
            setCreated(true);
          }}
        />
      )}
      {created && <Notice>Упаковка создана сервером. Теперь можно внести её цену.</Notice>}
      {selected && <PriceCard key={selected.id} sku={selected} />}
    </section>
  );
}
