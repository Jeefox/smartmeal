import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router';
import { Package, Plus, History } from 'lucide-react';
import { api, request } from '../api';
import type { Product } from '../types';
import { BackendPending, Loading, Notice, PageHeading } from '../components/ui';
import { useApp } from '../state';
import { useSession } from '../session';
import { dateKey, number } from '../domain';
import { pantryApi, usePantryCommand, validGrams } from '../pantry';
import type { PantryLot } from '../pantry';
export default function Pantry() {
  const { mode } = useApp();
  return mode === 'api' ? (
    <LivePantry />
  ) : (
    <BackendPending
      title="Домашние запасы доступны через API"
      description="Войдите в аккаунт в режиме API. Личные остатки хранятся на сервере."
    />
  );
}
function LivePantry() {
  const { profile } = useSession(),
    [params] = useSearchParams(),
    command = usePantryCommand();
  const query = useQuery({
    queryKey: ['pantry', profile?.userId],
    queryFn: ({ signal }) => pantryApi.view(signal),
  });
  const [page, setPage] = useState(0),
    [text, setText] = useState(''),
    [productId, setProductId] = useState(params.get('productId') || '');
  const [quantity, setQuantity] = useState(params.get('quantityG') || ''),
    [expiry, setExpiry] = useState('');
  const [received, setReceived] = useState(dateKey(new Date())),
    [price, setPrice] = useState('');
  const [purchase, setPurchase] = useState(params.get('purchase') === '1'),
    [error, setError] = useState('');
  const [filter, setFilter] = useState('all');
  const history = useQuery({
    queryKey: ['pantry-history', profile?.userId, page],
    queryFn: ({ signal }) => pantryApi.history(page, signal),
  });
  const products = useQuery({
    queryKey: ['pantry-products', text],
    queryFn: ({ signal }) => api.products(new URLSearchParams({ q: text, size: '30' }), signal),
  });
  const initialProduct = params.get('productId');
  const selected = useQuery({
    queryKey: ['pantry-selected-product', initialProduct],
    enabled: !!initialProduct,
    queryFn: ({ signal }) =>
      request<Product>(`/products/${encodeURIComponent(initialProduct!)}`, { signal }),
  });
  const choices = [
    ...(selected.data ? [selected.data] : []),
    ...(products.data?.content || []),
  ].filter((x, i, all) => all.findIndex((y) => y.id === x.id) === i);
  async function add(event: React.FormEvent) {
    event.preventDefault();
    setError('');
    if (!validGrams(quantity) || !productId) {
      setError('Выберите продукт и укажите положительную массу, максимум 4 знака после запятой.');
      return;
    }
    const item = {
      productId: Number(productId),
      quantityG: Number(quantity),
      receivedOn: received,
      expiresOn: expiry || null,
    };
    const payload = purchase
      ? {
          expectedRevision: query.data!.revision,
          items: [{ ...item, actualPriceRub: price ? Number(price) : null }],
        }
      : { ...item, expectedRevision: query.data!.revision };
    if (await command.run(purchase ? '/me/pantry/purchases' : '/me/pantry/lots', payload)) {
      setQuantity('');
      setPrice('');
    }
  }
  const today = query.data?.today || dateKey(new Date());
  const lots = (query.data?.lots || []).filter(
    (l) =>
      filter === 'all' ||
      (filter === 'expired' && l.expired) ||
      (filter === 'unknown' && !l.expiresOn) ||
      (filter === 'soon' &&
        !l.expired &&
        !!l.expiresOn &&
        Date.parse(l.expiresOn) - Date.parse(today) <= 7 * 86400000),
  );
  return (
    <>
      <PageHeading
        eyebrow="ДОМАШНИЕ ПРОДУКТЫ"
        title="Запасы"
        description="Сырые продукты до приготовления. Масса без внешней упаковки; бананы — с кожурой, макароны — сухие."
        actions={
          <Link className="button secondary" to="/menu-generation">
            Подобрать с учётом запасов
          </Link>
        }
      />
      {!command.online && (
        <Notice>Нет сети. Изменение запасов станет доступно после подключения.</Notice>
      )}
      {query.isPending && <Loading />}
      {query.error && (
        <Notice>
          {query.error.message} <button onClick={() => query.refetch()}>Повторить загрузку</button>
        </Notice>
      )}
      {(error || command.message) && <Notice>{error || command.message}</Notice>}
      {!!query.data?.unconfirmedPastEntries && (
        <Notice>
          У {query.data.unconfirmedPastEntries} блюд прошлых дней не подтверждено приготовление.
          Остатки могли измениться — сверьте их или отметьте приготовление в меню.
        </Notice>
      )}
      <div className="pantry-layout">
        <section className="panel">
          <h2>
            <Plus size={19} /> {purchase ? 'Добавить покупку' : 'Внести запас'}
          </h2>
          <p className="muted">
            Начните с одного продукта. Необязательно описывать всё, что есть дома.
          </p>
          <form className="period-options" onSubmit={add}>
            <label>
              Найти продукт
              <input value={text} onChange={(e) => setText(e.target.value)} />
            </label>
            {products.error && <Notice>Не удалось загрузить продукты.</Notice>}
            <label>
              Продукт
              <select required value={productId} onChange={(e) => setProductId(e.target.value)}>
                <option value="">Выберите продукт</option>
                {choices.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Масса, г
              <input
                required
                inputMode="decimal"
                type="number"
                min="0.0001"
                max="9999999999.9999"
                step="0.0001"
                value={quantity}
                onChange={(e) => setQuantity(e.target.value)}
              />
            </label>
            <div className="period-fields">
              <label>
                Дата поступления
                <input
                  required
                  type="date"
                  max={today}
                  value={received}
                  onChange={(e) => setReceived(e.target.value)}
                />
              </label>
              <label>
                Годен до включительно
                <input
                  type="date"
                  min={received}
                  value={expiry}
                  onChange={(e) => setExpiry(e.target.value)}
                />
              </label>
            </div>
            <label className="pantry-check">
              <input
                type="checkbox"
                checked={purchase}
                onChange={(e) => setPurchase(e.target.checked)}
              />
              Это фактическая покупка
            </label>
            {purchase && (
              <label>
                Заплачено, ₽ — необязательно
                <input
                  type="number"
                  min="0"
                  step="0.01"
                  value={price}
                  onChange={(e) => setPrice(e.target.value)}
                />
              </label>
            )}
            <button
              className="button primary"
              disabled={command.busy || !command.online || !query.data}
            >
              Добавить в запасы
            </button>
          </form>
        </section>
        <section className="panel">
          <h2>
            <Package size={19} /> Что есть дома
          </h2>
          <label>
            Показать
            <select value={filter} onChange={(e) => setFilter(e.target.value)}>
              <option value="all">Все партии</option>
              <option value="soon">Срок в ближайшие 7 дней</option>
              <option value="unknown">Срок не указан</option>
              <option value="expired">Просроченные</option>
            </select>
          </label>
          {query.data && !lots.length && (
            <p className="muted">Таких запасов пока нет. Добавьте продукт или измените фильтр.</p>
          )}
          {lots.map((lot) => (
            <LotEditor key={lot.id} lot={lot} revision={query.data!.revision} command={command} />
          ))}
        </section>
      </div>
      <section className="panel pantry-history">
        <h2>
          <History size={19} /> История изменений
        </h2>
        {history.isPending && <Loading />}
        {history.error && <Notice>Не удалось загрузить историю.</Notice>}
        {history.data?.items.length === 0 && (
          <p className="muted">Здесь появятся поступления, исправления и списания.</p>
        )}
        {history.data?.items.map((op) => (
          <article key={op.id}>
            <h3>
              {operationName(op.kind)}{' '}
              <small>{new Date(op.createdAt).toLocaleString('ru-RU')}</small>
            </h3>
            {op.movements.map((m, i) => (
              <p key={`${m.lotId}-${i}`}>
                {m.productName}: {m.quantityDeltaG > 0 ? '+' : ''}
                {number(m.quantityDeltaG, 2)} г · {operationName(m.reason)}
              </p>
            ))}
            {!op.movements.length && (
              <p className="muted">Изменено состояние или параметры, без движения продуктов.</p>
            )}
          </article>
        ))}
        <div className="period-actions">
          <button className="button quiet" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Назад
          </button>
          <span>Страница {page + 1}</span>
          <button
            className="button quiet"
            disabled={!history.data?.hasNext}
            onClick={() => setPage(page + 1)}
          >
            Далее
          </button>
        </div>
      </section>
    </>
  );
}
function operationName(kind: string) {
  const names: Record<string, string> = {
    INITIAL: 'Начальный запас',
    PURCHASE: 'Покупка',
    ADJUSTMENT: 'Сверка остатка',
    DISCARD: 'Списание',
    COOKING: 'Приготовление',
    REVERSAL: 'Отмена списания',
    METADATA: 'Срок годности',
    STATE: 'Состояние блюда',
    CANCEL: 'Отмена дня',
    REOPEN: 'Восстановление дня',
  };
  return names[kind.split(':')[0]] || kind;
}
function LotEditor({
  lot,
  revision,
  command,
}: {
  lot: PantryLot;
  revision: number;
  command: ReturnType<typeof usePantryCommand>;
}) {
  const [quantity, setQuantity] = useState(String(lot.quantityOnHandG)),
    [reason, setReason] = useState('Сверка остатка'),
    [expiry, setExpiry] = useState(lot.expiresOn || '');
  const [discard, setDiscard] = useState(false),
    [error, setError] = useState('');
  return (
    <article className={`pantry-lot ${lot.expired ? 'pantry-expired' : ''}`}>
      <h3>{lot.productName}</h3>
      <p>
        <strong>{number(lot.quantityOnHandG, 2)} г</strong> в наличии · {number(lot.reservedG, 2)} г
        в резерве · <strong>{number(lot.freeG, 2)} г свободно</strong>
      </p>
      <p className="muted">
        Поступил {lot.receivedOn} · {lot.expiresOn ? `Годен до ${lot.expiresOn}` : 'Срок не указан'}
        {lot.expired ? ' · Не используется в меню' : ''}
      </p>
      {!!lot.reservedFor?.length && (
        <details>
          <summary>Для каких блюд отложено</summary>
          {lot.reservedFor.map((r) => (
            <p key={r.entryId}>
              <Link to={`/?date=${r.date}`}>
                {r.date} · {r.recipeName}
              </Link>{' '}
              — {number(r.quantityG, 2)} г
            </p>
          ))}
        </details>
      )}
      <details>
        <summary>Сверить остаток или исправить партию</summary>
        {error && <p role="alert">{error}</p>}
        <form
          className="period-options"
          onSubmit={async (e) => {
            e.preventDefault();
            setError('');
            if (!validGrams(quantity, !discard)) {
              setError('Проверьте массу.');
              return;
            }
            await command.run(`/me/pantry/lots/${lot.id}/${discard ? 'discards' : 'adjustments'}`, {
              expectedRevision: revision,
              quantityG: Number(quantity),
              reason,
            });
          }}
        >
          <label className="pantry-check">
            <input
              type="checkbox"
              checked={discard}
              onChange={(e) => {
                setDiscard(e.target.checked);
                setQuantity('');
              }}
            />
            Списать указанное количество, а не установить остаток
          </label>
          <label>
            {discard ? 'Списать, г' : 'Сейчас осталось, г'}
            <input
              required
              type="number"
              min={discard ? '0.0001' : '0'}
              step="0.0001"
              value={quantity}
              onChange={(e) => setQuantity(e.target.value)}
            />
          </label>
          <label>
            Причина
            <input
              required
              maxLength={300}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
          <button className="button secondary" disabled={command.busy || !command.online}>
            {discard ? 'Списать' : 'Установить фактический остаток'}
          </button>
        </form>
        <form
          className="period-options"
          onSubmit={async (e) => {
            e.preventDefault();
            await command.run(
              `/me/pantry/lots/${lot.id}`,
              { expectedRevision: revision, expiresOn: expiry || null },
              'PATCH',
            );
          }}
        >
          <label>
            Исправить срок годности
            <input
              type="date"
              min={lot.receivedOn}
              value={expiry}
              onChange={(e) => setExpiry(e.target.value)}
            />
          </label>
          <button className="button quiet" disabled={command.busy || !command.online}>
            Сохранить срок
          </button>
        </form>
      </details>
    </article>
  );
}
