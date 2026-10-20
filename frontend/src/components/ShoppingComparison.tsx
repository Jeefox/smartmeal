import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { MapPin, RefreshCw, X } from 'lucide-react';
import { ApiError } from '../api';
import { labelDate } from '../domain';
import {
  comparisonKey,
  comparisonSelectionError,
  estimateErrorTitle,
  retailApi,
  rubles,
} from '../retail';
import type { Store } from '../retail';
import { useOnline } from '../state';
import { useSession } from '../session';
import { Loading, Notice } from './ui';
import EstimateDetails from './EstimateDetails';

type Applied = { from: string; to: string; stores: Store[] };
export default function ShoppingComparison({
  from,
  to,
  rangeValid,
  stores,
  storesUnavailable,
}: {
  from: string;
  to: string;
  rangeValid: boolean;
  stores: Store[];
  storesUnavailable: boolean;
}) {
  const userId = useSession().profile!.userId;
  const online = useOnline();
  const [selected, setSelected] = useState<Store[]>([]);
  const [applied, setApplied] = useState<Applied | null>(null);
  const [error, setError] = useState('');
  const [detailId, setDetailId] = useState<number | null>(null);
  const detailsRef = useRef<HTMLElement>(null);
  useEffect(() => {
    if (detailId != null) detailsRef.current?.scrollIntoView({ block: 'start' });
  }, [detailId]);
  useEffect(() => {
    setApplied(null);
    setDetailId(null);
  }, [from, to]);
  const matches = Boolean(applied && applied.from === from && applied.to === to);
  const query = useQuery({
    queryKey: comparisonKey(userId, from, to, applied?.stores.map((s) => s.id) ?? []),
    queryFn: ({ signal }) =>
      retailApi.compare(
        from,
        to,
        applied!.stores.map((s) => s.id),
        signal,
      ),
    enabled: rangeValid && matches,
    staleTime: 0,
    refetchOnWindowFocus: true,
    refetchOnReconnect: true,
  });
  function toggle(store: Store) {
    const next = selected.some((s) => s.id === store.id)
      ? selected.filter((s) => s.id !== store.id)
      : [...selected, store];
    if (next.length > 10) {
      setError('Можно сравнить не более 10 магазинов.');
      return;
    }
    setSelected(next);
    setError('');
    setApplied(null);
    setDetailId(null);
  }
  function compare() {
    const nextError = comparisonSelectionError(selected.map((s) => s.id));
    setError(nextError);
    if (nextError || !rangeValid) return;
    if (matches) query.refetch();
    else setApplied({ from, to, stores: [...selected] });
  }
  // Hide responses for an earlier period/selection. Query cancellation also aborts the old GET.
  const data = matches && rangeValid ? query.data : undefined;
  const full = data?.shoppingEstimateResponseList.filter((item) => item.complete) ?? [];
  const partial = data?.shoppingEstimateResponseList.filter((item) => !item.complete) ?? [];
  const hasProducts =
    data?.shoppingEstimateResponseList.some((item) => item.items.length > 0) ?? false;
  const minCost = full[0]?.knownPurchaseCost;
  const detail = data?.shoppingEstimateResponseList.find((item) => item.storeId === detailId);
  function name(id: number) {
    return applied?.stores.find((s) => s.id === id);
  }
  return (
    <section className="comparison-panel" aria-label="Сравнение магазинов">
      <p className="caption">
        Добавьте от 1 до 10 конкретных точек. Можно искать другие города и сети: уже выбранные
        адреса останутся в списке.
      </p>
      <div className="store-options" role="group" aria-label="Магазины для сравнения">
        {stores.map((store) => (
          <button
            key={store.id}
            className={`store-choice ${selected.some((s) => s.id === store.id) ? 'selected' : ''}`}
            aria-pressed={selected.some((s) => s.id === store.id)}
            disabled={!online || storesUnavailable}
            onClick={() => toggle(store)}
          >
            <MapPin size={20} />
            <span>
              <strong>{store.chainName}</strong>
              <small>
                {store.city}, {store.address}
              </small>
              <small>
                {selected.some((s) => s.id === store.id)
                  ? 'Добавлен в сравнение'
                  : 'Добавить в сравнение'}
              </small>
            </span>
          </button>
        ))}
      </div>
      <div className="comparison-selection">
        <h3>Выбрано магазинов: {selected.length} / 10</h3>
        {!selected.length && (
          <p className="caption">Найдите магазины по городу выше и добавьте нужные адреса.</p>
        )}
        <ul aria-label="Выбранные магазины">
          {selected.map((store) => (
            <li key={store.id}>
              <span>
                <strong>{store.chainName}</strong> · {store.city}, {store.address}
              </span>
              <button
                className="button quiet"
                onClick={() => toggle(store)}
                aria-label={`Убрать ${store.chainName}, ${store.address}`}
              >
                <X size={17} />
              </button>
            </li>
          ))}
        </ul>
        {error && (
          <p className="field-error" role="alert">
            {error}
          </p>
        )}
        <button
          className="button primary"
          disabled={!online || !rangeValid || (matches && query.isFetching)}
          onClick={compare}
        >
          <RefreshCw size={17} />
          {matches && query.isFetching
            ? 'Сравниваем…'
            : matches
              ? 'Обновить сравнение'
              : 'Сравнить выбранные магазины'}
        </button>
        {!rangeValid && <Notice tone="warning">Исправьте период перед сравнением.</Notice>}
        {!matches && selected.length > 0 && (
          <p className="caption" role="status">
            Выбор не рассчитан. Нажмите «Сравнить выбранные магазины» для применённого периода.
          </p>
        )}
      </div>
      {matches && rangeValid && query.isPending && <Loading />}
      {matches && rangeValid && query.isError && (
        <div className="estimate-error" role="alert">
          <h3>
            {estimateErrorTitle(query.error instanceof ApiError ? query.error.status : undefined)}
          </h3>
          <p>{query.error.message}</p>
          <p>
            {data
              ? 'Показано предыдущее сравнение, оно может быть устаревшим.'
              : 'Сравнение не выполнено целиком. Проверьте выбранные магазины и повторите запрос.'}
          </p>
        </div>
      )}
      {data && (
        <section className="comparison-results" aria-label="Результаты сравнения">
          <h3>
            {labelDate(data.from)} — {labelDate(data.to)}
          </h3>
          {data.containsDrafts && (
            <Notice tone="warning">
              В сравнении есть черновые меню. Результат предварительный: изменения блюд и порций
              изменят покупки.
            </Notice>
          )}
          {!hasProducts && (
            <Notice>
              В выбранном периоде нет продуктов. Сравнивать стоимость закупки пока нечего.
            </Notice>
          )}
          <p className="caption">
            Оценки по последним неакционным ценам без ограничения давности. Наличие и текущая цена
            не гарантируются. Полная оценка означает покрытие всех продуктов известными ценами.
          </p>
          {(
            [
              { title: 'Полные оценки', items: full },
              { title: 'Неполные оценки · только известная часть', items: partial },
            ] as const
          ).map(
            (group) =>
              group.items.length > 0 && (
                <section key={group.title} className="comparison-group" aria-label={group.title}>
                  <h3>{group.title}</h3>
                  {group.items === partial && (
                    <Notice tone="warning">
                      Эти суммы не являются полным бюджетом. Порядок по известной части не
                      показывает, где вся закупка дешевле.
                    </Notice>
                  )}
                  <div className="comparison-cards">
                    {group.items.map((item) => (
                      <article className="comparison-card" key={item.storeId}>
                        <h4>{name(item.storeId)?.chainName ?? `Магазин ${item.storeId}`}</h4>
                        <p>
                          {name(item.storeId)?.city}, {name(item.storeId)?.address}
                        </p>
                        <span className="caption">
                          {item.complete ? 'Оценочная стоимость' : 'Известная часть стоимости'}
                        </span>
                        <strong className="comparison-cost">
                          {rubles(item.knownPurchaseCost)}
                        </strong>
                        <span className="badge">
                          {!item.items.length
                            ? 'Нет продуктов'
                            : item.complete
                              ? 'Все продукты покрыты'
                              : `Без цены: ${item.items.filter((row) => row.purchaseCost == null).length}`}
                        </span>
                        {hasProducts &&
                          full.length > 1 &&
                          item.complete &&
                          item.knownPurchaseCost === minCost && (
                            <p className="comparison-best">Минимальная оценка среди полных</p>
                          )}
                        <button
                          className="button"
                          onClick={() => setDetailId(item.storeId)}
                          aria-expanded={detailId === item.storeId}
                        >
                          Подробнее<span className="sr-only">: {name(item.storeId)?.address}</span>
                        </button>
                      </article>
                    ))}
                  </div>
                </section>
              ),
          )}
          {!full.length && hasProducts && (
            <Notice tone="warning">
              Полных оценок нет. Определить магазин с минимальной стоимостью всей закупки нельзя.
            </Notice>
          )}
          {detail && (
            <section
              ref={detailsRef}
              className="comparison-details"
              aria-label="Подробности выбранного магазина"
            >
              <div className="section-heading">
                <div>
                  <h3>{name(detail.storeId)?.chainName}</h3>
                  <p>
                    {name(detail.storeId)?.city}, {name(detail.storeId)?.address}
                  </p>
                </div>
                <button className="button quiet" onClick={() => setDetailId(null)}>
                  Скрыть подробности
                </button>
              </div>
              <EstimateDetails
                data={detail}
                updatedAt={query.dataUpdatedAt}
                isFetching={query.isFetching}
              />
            </section>
          )}
          <p className="caption">
            {query.isFetching
              ? 'Обновляем сравнение…'
              : `Сравнение получено: ${new Date(query.dataUpdatedAt).toLocaleString('ru-RU')}.`}
          </p>
        </section>
      )}
    </section>
  );
}
