import { useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  catalogueApi,
  latestPriceKey,
  priceErrors,
  observationInstant,
  localDateTime,
  apiFields,
  invalidateRetail,
} from '../catalogue';
import type { StoreProduct, PriceObservation } from '../catalogue';
import { ApiError } from '../api';
import { rubles } from '../retail';
import { useSession } from '../session';
import { useOnline } from '../state';
import { Loading, Notice } from './ui';
export default function PriceCard({ sku }: { sku: StoreProduct }) {
  const user = useSession().profile!.userId,
    online = useOnline(),
    client = useQueryClient();
  const query = useQuery({
    queryKey: latestPriceKey(user, sku.id),
    queryFn: ({ signal }) => catalogueApi.latest(sku.id, signal),
    staleTime: 0,
    refetchOnWindowFocus: true,
  });
  const [price, setPrice] = useState(''),
    [at, setAt] = useState(() => localDateTime()),
    [promo, setPromo] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({}),
    [error, setError] = useState<Error | null>(null),
    [saved, setSaved] = useState<PriceObservation | null>(null),
    [busy, setBusy] = useState(false);
  const lock = useRef(false);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (lock.current || !online) return;
    const next = priceErrors(price, at);
    setErrors(next);
    setError(null);
    setSaved(null);
    if (Object.keys(next).length) return;
    lock.current = true;
    setBusy(true);
    try {
      const result = await catalogueApi.price(sku.id, {
        price: Number(price.replace(',', '.')),
        observedAt: observationInstant(at)!,
        promotional: promo,
      });
      setSaved(result);
      setPrice('');
      // A historical observation need not be the latest. Always read latest again from the server.
      await invalidateRetail(client, user, sku.storeId, sku.id);
    } catch (err) {
      setError(err as Error);
      setErrors(apiFields(err));
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  const canEdit = useSession().profile?.canEditCatalogue;
  return (
    <section className="sku-price-card" aria-label="Цена выбранной упаковки">
      <div className="section-heading">
        <div>
          <span className="eyebrow">ВЫБРАННАЯ УПАКОВКА</span>
          <h3>{sku.name}</h3>
          <p>
            {sku.packageWeightG} г · базовый продукт №{sku.productId}
          </p>
        </div>
        <button
          className="button"
          disabled={!online || query.isFetching || busy}
          onClick={() => query.refetch()}
        >
          Обновить цену
        </button>
      </div>
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <Notice tone="warning">
          Не удалось загрузить последнюю цену: {query.error.message}{' '}
          {query.data ? 'Ранее полученная цена может быть устаревшей.' : 'Повторите загрузку.'}
        </Notice>
      ) : null}
      {query.data && (
        <div className="sku-known-price">
          <span>Последняя известная цена</span>
          <strong>{rubles(query.data.price)}</strong>
          <p>
            {new Date(query.data.observedAt).toLocaleString('ru-RU')} ·{' '}
            {query.data.promotional ? 'Акционная' : 'Неакционная'} ·{' '}
            {query.data.priceSource === 'MANUAL' ? 'Ручной ввод' : query.data.priceSource}
          </p>
        </div>
      )}
      {query.data === null && !query.isError && (
        <Notice>
          Цена ещё не добавлена. Можно внести первое наблюдение ниже. Если упаковка была удалена,
          обновите каталог.
        </Notice>
      )}
      {query.data?.promotional && (
        <Notice tone="warning">
          Последняя запись акционная. Оценка покупок и сравнение магазинов используют последнюю
          неакционную цену; показанная здесь акция в расчёт не входит.
        </Notice>
      )}
      <p className="caption">
        Это наблюдение цены за целую упаковку, а не гарантия актуальной цены или наличия. Новая
        запись добавляет историю, не редактирует предыдущую.
      </p>
      {!online && (
        <Notice tone="warning">
          Нет сети. Цена может быть устаревшей, добавление наблюдения недоступно.
        </Notice>
      )}
      {saved && (
        <div className="notice" role="status">
          Наблюдение добавлено: {rubles(saved.price)} ·{' '}
          {new Date(saved.observedAt).toLocaleString('ru-RU')} ·{' '}
          {saved.promotional ? 'акционная' : 'неакционная'}. Сохранено сервером. Последняя цена
          определяется по дате наблюдения.
        </div>
      )}
      {canEdit && (
        <form className="sku-price-form" onSubmit={submit} noValidate>
          <h3>Добавить наблюдение цены</h3>
          <label>
            <span>Цена упаковки, ₽</span>
            <input
              aria-label="Цена упаковки, ₽"
              aria-describedby={errors.price ? 'sku-price-error' : undefined}
              inputMode="decimal"
              value={price}
              onChange={(e) => setPrice(e.target.value)}
              disabled={busy}
              aria-invalid={Boolean(errors.price)}
            />
            {errors.price && (
              <span id="sku-price-error" className="field-error" role="alert">
                {errors.price}
              </span>
            )}
          </label>
          <label>
            <span>Когда наблюдали цену</span>
            <input
              aria-label="Когда наблюдали цену"
              aria-describedby={errors.observedAt ? 'sku-date-error' : undefined}
              type="datetime-local"
              value={at}
              onChange={(e) => setAt(e.target.value)}
              disabled={busy}
              aria-invalid={Boolean(errors.observedAt)}
            />
            {errors.observedAt && (
              <span id="sku-date-error" className="field-error" role="alert">
                {errors.observedAt}
              </span>
            )}
          </label>
          <p className="caption">
            Часовой пояс устройства: {Intl.DateTimeFormat().resolvedOptions().timeZone}. Дата и
            время будут отправлены в UTC. Будущее время недопустимо.
          </p>
          <label className="sku-checkbox">
            <input
              type="checkbox"
              checked={promo}
              onChange={(e) => setPromo(e.target.checked)}
              disabled={busy}
            />
            Акционная цена
          </label>
          {promo && (
            <Notice>Акционная запись не заменяет неакционную цену в оценках покупок.</Notice>
          )}
          {error && (
            <div className="estimate-error" role="alert">
              <p>{error.message}</p>
              {error instanceof ApiError && error.status === 0 && (
                <p>
                  Сервер не подтвердил запись. Перед повторной отправкой обновите цену: наблюдение
                  могло сохраниться.
                </p>
              )}
            </div>
          )}
          <button
            className="button primary"
            type="submit"
            disabled={!online || busy || !price.trim()}
          >
            {busy ? 'Сохраняем…' : 'Сохранить наблюдение'}
          </button>
        </form>
      )}
    </section>
  );
}
