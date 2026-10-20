import { useState } from 'react';
import type { FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { MapPin, RefreshCw, Store as StoreIcon } from 'lucide-react';
import { ApiError } from '../api';
import { estimateErrorTitle, estimateKey, retailApi } from '../retail';
import type { Store } from '../retail';
import { useSession } from '../session';
import { useOnline } from '../state';
import { Loading, Notice } from './ui';
import EstimateDetails from './EstimateDetails';
import ShoppingComparison from './ShoppingComparison';
import StoreCatalogue from './StoreCatalogue';
import { useSearchParams } from 'react-router';

export default function ShoppingEstimate({
  from,
  to,
  rangeValid,
}: {
  from: string;
  to: string;
  rangeValid: boolean;
}) {
  const userId = useSession().profile!.userId;
  const online = useOnline();
  const [cityInput, setCityInput] = useState('');
  const [city, setCity] = useState('');
  const [chain, setChain] = useState('');
  const [selected, setSelected] = useState<Store | null>(null);
  const [cityError, setCityError] = useState('');
  const [params] = useSearchParams();
  const [mode, setMode] = useState<'single' | 'comparison' | 'catalogue'>(() =>
    params.get('view') === 'catalogue' ? 'catalogue' : 'single',
  );
  const chains = useQuery({
    queryKey: ['retail-chains', userId],
    queryFn: ({ signal }) => retailApi.chains(signal),
    refetchOnWindowFocus: true,
  });
  const stores = useQuery({
    queryKey: ['stores', userId, city, chain],
    queryFn: ({ signal }) => retailApi.stores(city, chain, signal),
    enabled: Boolean(city),
    staleTime: 0,
    refetchOnWindowFocus: true,
  });
  const estimate = useQuery({
    queryKey: estimateKey(userId, from, to, selected?.id ?? null),
    queryFn: ({ signal }) => retailApi.estimate(from, to, selected!.id, signal),
    enabled: mode === 'single' && rangeValid && Boolean(selected),
    staleTime: 0,
    refetchOnWindowFocus: true,
    refetchOnReconnect: true,
  });
  function find(event: FormEvent) {
    event.preventDefault();
    if (!cityInput.trim()) {
      setCityError('Введите город.');
      return;
    }
    setCityError('');
    setSelected(null);
    setCity(cityInput.trim());
    if (cityInput.trim() === city) stores.refetch();
  }
  const data = estimate.data;
  return (
    <section className="panel estimate-panel" aria-label="Расчёт покупок в магазине">
      <div className="estimate-heading">
        <span className="store-icon">
          <StoreIcon size={25} />
        </span>
        <div>
          <span className="eyebrow">ПОКУПКИ В МАГАЗИНЕ</span>
          <h2>От граммов к упаковкам</h2>
          <p>Выберите точку, чтобы оценить стоимость продуктов на выбранный период.</p>
        </div>
      </div>
      <div className="comparison-mode" role="group" aria-label="Режим расчёта">
        <button
          className={`button ${mode === 'single' ? 'primary' : ''}`}
          aria-pressed={mode === 'single'}
          onClick={() => setMode('single')}
        >
          Один магазин
        </button>
        <button
          className={`button ${mode === 'comparison' ? 'primary' : ''}`}
          aria-pressed={mode === 'comparison'}
          onClick={() => setMode('comparison')}
        >
          Сравнить магазины
        </button>
        <button
          className={`button ${mode === 'catalogue' ? 'primary' : ''}`}
          aria-pressed={mode === 'catalogue'}
          onClick={() => setMode('catalogue')}
        >
          Упаковки и цены
        </button>
      </div>
      <form className="store-search" onSubmit={find} noValidate>
        <label>
          <span>Город</span>
          <input
            value={cityInput}
            onChange={(e) => setCityInput(e.target.value)}
            placeholder="Введите ваш город"
            aria-invalid={Boolean(cityError)}
            aria-describedby={cityError ? 'store-city-error' : undefined}
          />
          {cityError && (
            <span id="store-city-error" className="field-error" role="alert">
              {cityError}
            </span>
          )}
        </label>
        <label>
          <span>Торговая сеть</span>
          <select
            value={chain}
            onChange={(e) => {
              setChain(e.target.value);
              setSelected(null);
            }}
          >
            <option value="">Все сети</option>
            {chains.data?.map((item) => (
              <option key={item.id} value={item.id}>
                {item.name}
              </option>
            ))}
          </select>
        </label>
        <button className="button primary" type="submit" disabled={!online}>
          <MapPin size={17} />
          Найти магазины
        </button>
      </form>
      {chains.isError && (
        <Notice tone="warning">
          Не удалось загрузить сети: {chains.error.message} Можно искать по всем сетям.{' '}
          <button className="text-link" onClick={() => chains.refetch()} disabled={!online}>
            Повторить загрузку сетей
          </button>
        </Notice>
      )}
      {city && (
        <div className="store-results">
          <div className="section-heading">
            <h3>Магазины · {city}</h3>
            <button
              className="button quiet"
              disabled={!online || stores.isFetching}
              onClick={() => stores.refetch()}
            >
              <RefreshCw size={15} />
              Обновить магазины
            </button>
          </div>
          {stores.isPending ? (
            <Loading />
          ) : stores.isError ? (
            <Notice tone="warning">
              Не удалось обновить магазины: {stores.error.message}{' '}
              {stores.data
                ? 'Ниже ранее полученные адреса; доступность не подтверждена.'
                : 'Повторите загрузку.'}
            </Notice>
          ) : null}
          {stores.data?.length === 0 && !stores.isError && (
            <p role="status">
              В этом городе по выбранному фильтру нет активных магазинов. Проверьте город или
              выберите другую сеть.
            </p>
          )}
          {mode !== 'comparison' && (
            <div className="store-options" role="group" aria-label="Выберите конкретный магазин">
              {stores.data?.map((store) => (
                <button
                  key={store.id}
                  className={`store-choice ${selected?.id === store.id ? 'selected' : ''}`}
                  aria-pressed={selected?.id === store.id}
                  disabled={!online || stores.isError}
                  onClick={() => setSelected(store)}
                >
                  <StoreIcon size={20} />
                  <span>
                    <strong>{store.chainName}</strong>
                    <small>
                      {store.city}, {store.address}
                    </small>
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>
      )}
      {mode === 'catalogue' &&
        (selected ? (
          <StoreCatalogue key={selected.id} store={selected} />
        ) : (
          <p className="caption">
            Найдите магазин и выберите конкретный адрес для работы с упаковками и ценами.
          </p>
        ))}
      {mode === 'comparison' && (
        <ShoppingComparison
          from={from}
          to={to}
          rangeValid={rangeValid}
          stores={city ? (stores.data ?? []) : []}
          storesUnavailable={stores.isError || stores.isFetching}
        />
      )}
      {!online && (
        <Notice tone="warning">
          Нет сети. Полученные данные хранятся только в памяти вкладки и могут быть устаревшими.
          После подключения обновите расчёт.
        </Notice>
      )}
      {mode === 'single' &&
        (selected ? (
          <div className="estimate-result">
            <div className="section-heading">
              <div>
                <span className="eyebrow">ВЫБРАННЫЙ МАГАЗИН</span>
                <h3>{selected.chainName}</h3>
                <p>
                  {selected.city}, {selected.address}
                </p>
              </div>
              <button
                className="button"
                disabled={!online || !rangeValid || estimate.isFetching}
                onClick={() => estimate.refetch()}
              >
                <RefreshCw size={16} />
                {estimate.isFetching ? 'Рассчитываем…' : 'Обновить расчёт'}
              </button>
            </div>
            {!rangeValid ? (
              <Notice tone="warning">Исправьте период, чтобы получить расчёт.</Notice>
            ) : estimate.isPending ? (
              <Loading />
            ) : estimate.isError ? (
              <div className="estimate-error" role="alert">
                <h3>
                  {estimateErrorTitle(
                    estimate.error instanceof ApiError ? estimate.error.status : undefined,
                  )}
                </h3>
                <p>{estimate.error.message}</p>
                <p>
                  {data
                    ? 'Ниже показан ранее полученный расчёт, он может быть устаревшим.'
                    : 'Попробуйте обновить расчёт или выбрать другой магазин.'}
                </p>
              </div>
            ) : null}
            {rangeValid && data && (
              <>
                <EstimateDetails
                  data={data}
                  updatedAt={estimate.dataUpdatedAt}
                  isFetching={estimate.isFetching}
                />
              </>
            )}
          </div>
        ) : (
          <p className="caption">
            Введите город и выберите конкретный адрес. Потребность в граммах доступна ниже без
            выбора магазина.
          </p>
        ))}
    </section>
  );
}
