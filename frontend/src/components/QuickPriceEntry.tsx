import { useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../api';
import { localDateTime, invalidateRetail } from '../catalogue';
import { rubles } from '../retail';
import { useSession } from '../session';
import { useOnline } from '../state';
import {
  buildPriceBatch,
  initialPriceDraft,
  parsePriceTable,
  priceEntryApi,
  restorePriceBatch,
} from '../price-entry';
import type { PriceBatch, PriceDraft, PriceEntryItem } from '../price-entry';
import { ErrorState, Loading, Notice } from './ui';

export default function QuickPriceEntry({ storeId }: { storeId: number }) {
  const { profile } = useSession();
  return profile?.canEditCatalogue ? <Editor storeId={storeId} /> : null;
}
function Editor({ storeId }: { storeId: number }) {
  const user = useSession().profile!.userId,
    online = useOnline(),
    client = useQueryClient();
  const storageKey = `smartmeal.pending-price-batch.${user}.${storeId}`;
  const [pending, setPending] = useState<PriceBatch | null>(() => restorePriceBatch(storageKey));
  const [busy, setBusy] = useState(false),
    saving = useRef(false);
  const [missingOnly, setMissing] = useState(true),
    [q, setQuery] = useState(''),
    [search, setSearch] = useState(''),
    [page, setPage] = useState(0);
  const [observedAt, setDate] = useState(localDateTime());
  const [drafts, setDrafts] = useState<Record<number, PriceDraft>>({});
  const [errors, setErrors] = useState<Record<string, string>>({}),
    [message, setMessage] = useState('');
  const [paste, setPaste] = useState(''),
    [pasteErrors, setPasteErrors] = useState<string[]>([]);
  const query = useQuery({
    queryKey: ['price-entry', user, storeId, missingOnly, q, page],
    queryFn: ({ signal }) => priceEntryApi.list(storeId, missingOnly, q, page, signal),
    staleTime: 0,
    refetchOnWindowFocus: false,
  });
  const locked = busy || Boolean(pending);
  const filled = Object.values(drafts).filter((d) => d.price.trim()).length;
  function update(item: PriceEntryItem, patch: Partial<PriceDraft>) {
    setDrafts((current) => ({
      ...current,
      [item.productId]: { ...(current[item.productId] ?? initialPriceDraft(item)), ...patch },
    }));
    setErrors({});
    setMessage('');
  }
  async function save() {
    if (saving.current || !online) return;
    let body = pending;
    if (!body) {
      const built = buildPriceBatch(Object.values(drafts), observedAt, crypto.randomUUID());
      const badRow = Object.values(drafts).find((d) => built.errors[String(d.productId)]);
      setErrors(
        badRow
          ? {
              ...built.errors,
              form: `Проверьте строку «${badRow.productName}»: ${built.errors[String(badRow.productId)]}`,
            }
          : built.errors,
      );
      if (Object.keys(built.errors).length) return;
      body = built.body;
      try {
        sessionStorage.setItem(storageKey, JSON.stringify(body));
      } catch {
        /* The in-memory key still protects manual retries. */
      }
      setPending(body);
    }
    saving.current = true;
    setBusy(true);
    setMessage('');
    setErrors({});
    try {
      const result = await priceEntryApi.save(storeId, body);
      setPending(null);
      setDrafts({});
      setPaste('');
      setPage(0);
      try {
        sessionStorage.removeItem(storageKey);
      } catch {
        /* Server idempotency also protects a retained copy. */
      }
      setMessage(`Сохранено цен: ${result.savedCount}.`);
      // Saving has succeeded even if the subsequent refresh is offline. Do not offer to re-save it.
      await Promise.allSettled([
        invalidateRetail(client, user, storeId),
        client.invalidateQueries({ queryKey: ['price-entry', user, storeId] }),
        client.invalidateQueries({ queryKey: ['latest-price', user] }),
      ]);
    } catch (error) {
      const uncertain =
        !(error instanceof ApiError) ||
        error.status === 0 ||
        [401, 403, 408, 429].includes(error.status) ||
        error.status >= 500;
      if (!uncertain) {
        setPending(null);
        try {
          sessionStorage.removeItem(storageKey);
        } catch {
          /* Keep in-memory form available. */
        }
      }
      setErrors({
        form: uncertain
          ? 'Сохранение не подтверждено. Данные оставлены для безопасного повтора того же запроса. При ошибке доступа обновите сессию.'
          : error instanceof Error
            ? error.message
            : 'Не удалось сохранить цены.',
      });
    } finally {
      saving.current = false;
      setBusy(false);
    }
  }
  return (
    <section className="quick-price-entry" aria-label="Быстрое заполнение цен">
      <div>
        <span className="eyebrow">МЕНЬШЕ РУЧНОЙ РАБОТЫ</span>
        <h3>Цены для ваших рецептов</h3>
        <p>
          Сначала продукты, которые встречаются в большем количестве рецептов. Заполните любые
          известные цены — остальные можно пропустить.
        </p>
      </div>
      <Notice>
        Указывайте цену одной упаковки и её фактический вес. Не переносите сюда цену за килограмм
        весового товара. Название предложено из каталога — уточните его по упаковке. Данные не
        заполняются предполагаемыми ценами.
      </Notice>
      {!online && (
        <Notice tone="warning">Нет сети. Ввод сохранён в этой форме, отправка недоступна.</Notice>
      )}
      {pending && (
        <Notice tone="warning">
          Есть запрос на сохранение {pending.entries.length} цен без подтверждённого ответа.
          Повторите его: сервер не создаст дубли. Редактирование доступно после ответа.
        </Notice>
      )}
      {message && (
        <p className="success-message" role="status">
          {message}
        </p>
      )}
      {errors.form && (
        <p className="field-error" role="alert">
          {errors.form}
        </p>
      )}
      <fieldset disabled={locked} className="quick-price-controls">
        <form
          className="sku-search"
          onSubmit={(e) => {
            e.preventDefault();
            setQuery(search.trim());
            setPage(0);
          }}
        >
          <label>
            Найти продукт
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              maxLength={100}
              placeholder="Например, рис"
            />
          </label>
          <button className="button" disabled={!online}>
            Найти
          </button>
        </form>
        <label className="checkbox-label">
          <input
            type="checkbox"
            checked={missingOnly}
            onChange={(e) => {
              setMissing(e.target.checked);
              setPage(0);
            }}
          />
          Только без обычной цены
        </label>
        <label>
          Когда наблюдали цены
          <input
            type="datetime-local"
            value={observedAt}
            max={localDateTime()}
            onChange={(e) => {
              setDate(e.target.value);
              setErrors({});
            }}
          />
        </label>
        {errors.observedAt && (
          <p className="field-error" role="alert">
            {errors.observedAt}
          </p>
        )}
        <details className="quick-price-paste">
          <summary>Вставить таблицу из Excel или текст</summary>
          <p className="caption">
            Три столбца: продукт; вес упаковки в граммах; цена. Подойдут строки текущей страницы.
            Вставка только заполняет форму — проверьте её перед сохранением.
          </p>
          <label htmlFor={`quick-price-table-${storeId}`}>Таблица цен</label>
          <textarea
            id={`quick-price-table-${storeId}`}
            rows={4}
            value={paste}
            onChange={(e) => setPaste(e.target.value)}
            placeholder={'Рис;900;89,99\nОвсяные хлопья;500;79,90'}
          />
          <button
            type="button"
            className="button"
            disabled={!query.data || query.isError || !paste.trim()}
            onClick={() => {
              const parsed = parsePriceTable(paste, query.data!.content);
              setPasteErrors(parsed.errors);
              if (parsed.errors.length) return;
              setDrafts((current) => ({
                ...current,
                ...Object.fromEntries(parsed.drafts.map((d) => [d.productId, d])),
              }));
              setErrors({});
            }}
          >
            Заполнить из таблицы
          </button>
          {pasteErrors.map((error, i) => (
            <p key={i} className="field-error" role="alert">
              {error}
            </p>
          ))}
        </details>
      </fieldset>
      {query.isPending ? (
        <Loading />
      ) : query.isError ? (
        <ErrorState error={query.error} retry={() => query.refetch()} />
      ) : (
        <>
          <p className="caption">
            Найдено продуктов: {query.data?.totalElements ?? 0}. Заполненные строки сохраняются при
            переключении страниц.
          </p>
          {!query.data?.content.length && (
            <Notice>
              {missingOnly
                ? 'Нет подходящих продуктов без обычной цены. Снимите фильтр, чтобы обновить известные цены.'
                : 'Продукты не найдены. Здесь показаны ингредиенты существующих рецептов.'}
            </Notice>
          )}
          <fieldset disabled={locked || !online} className="quick-price-rows">
            {query.data?.content.map((item) => {
              const d = drafts[item.productId] ?? initialPriceDraft(item),
                offer = item.offers.find((o) => String(o.storeProductId) === d.skuId);
              return (
                <article
                  className="quick-price-row"
                  key={item.productId}
                  aria-label={item.productName}
                >
                  <div>
                    <h4>{item.productName}</h4>
                    <span className="caption">В рецептах: {item.recipeCount}</span>
                  </div>
                  <label>
                    Упаковка
                    <select
                      value={d.skuId}
                      onChange={(e) => update(item, { skuId: e.target.value })}
                    >
                      <option value="" disabled>
                        Выберите упаковку
                      </option>
                      {item.offers.map((o) => (
                        <option key={o.storeProductId} value={o.storeProductId}>
                          {o.name} · {o.packageWeightG} г
                        </option>
                      ))}
                      <option value="new">Новая упаковка</option>
                    </select>
                  </label>
                  {d.skuId === 'new' && (
                    <>
                      <label>
                        Название упаковки
                        <input
                          maxLength={300}
                          value={d.name}
                          onChange={(e) => update(item, { name: e.target.value })}
                        />
                      </label>
                      <label>
                        Вес упаковки, г
                        <input
                          inputMode="numeric"
                          value={d.weight}
                          onChange={(e) => update(item, { weight: e.target.value })}
                          placeholder="По этикетке"
                        />
                      </label>
                    </>
                  )}
                  <label>
                    Цена упаковки, ₽
                    <input
                      inputMode="decimal"
                      value={d.price}
                      onChange={(e) => update(item, { price: e.target.value })}
                      placeholder="Не заполнено"
                      aria-invalid={Boolean(errors[String(item.productId)])}
                    />
                  </label>
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={d.promotional}
                      onChange={(e) => update(item, { promotional: e.target.checked })}
                    />
                    Акция / специальная цена
                  </label>
                  {offer?.price != null && (
                    <p className="caption">
                      Последняя обычная: {rubles(offer.price)} ·{' '}
                      {new Date(offer.observedAt!).toLocaleString('ru-RU')}
                    </p>
                  )}
                  {errors[String(item.productId)] && (
                    <p className="field-error" role="alert">
                      {errors[String(item.productId)]}
                    </p>
                  )}
                </article>
              );
            })}
          </fieldset>
          <div className="sku-pager">
            <button
              className="button"
              disabled={locked || !online || page === 0 || query.isFetching}
              onClick={() => setPage(page - 1)}
            >
              Предыдущие продукты
            </button>
            <span>Страница {page + 1}</span>
            <button
              className="button"
              disabled={
                locked || !online || query.isFetching || page + 1 >= (query.data?.totalPages ?? 0)
              }
              onClick={() => setPage(page + 1)}
            >
              Следующие продукты
            </button>
          </div>
        </>
      )}
      <div className="quick-price-save">
        <p>
          {pending ? `К повтору: ${pending.entries.length}` : `Введено цен: ${filled}`} · Пустые
          цены пропускаются
        </p>
        <button
          className="button primary"
          disabled={!online || busy || (!pending && filled === 0)}
          onClick={save}
        >
          {busy ? 'Сохраняем…' : pending ? 'Повторить тот же запрос' : 'Сохранить заполненные цены'}
        </button>
      </div>
      <p className="caption">
        Сохраняются ручные наблюдения. Акции остаются в истории, но не участвуют в текущем подборе
        по обычным ценам. Упаковки и цены сохраняются одним действием: при ошибке весь запрос
        отменяется.
      </p>
    </section>
  );
}
