import { useState } from 'react';
import {
  ArrowLeftRight,
  ArrowRight,
  Check,
  CheckCheck,
  CircleHelp,
  PackageOpen,
  RefreshCw,
  ShoppingBasket,
  Store,
} from 'lucide-react';
import { Link } from 'react-router';
import { BackendPending, Dialog, Empty, Notice, PageHeading } from '../components/ui';
import { buildShopping, labelDate, money, number, quoteTotals, weekDates } from '../domain';
import { demoOffers } from '../demo';
import { useApp, useOnline } from '../state';
import type { ShoppingItem, ShoppingStatus } from '../types';
import LiveShopping from './LiveShopping';
const qualityLabel = {
  OBSERVED: 'Условное наблюдение',
  ESTIMATE: 'Примерная оценка',
  STALE: 'Устаревшая цена',
  UNKNOWN: 'Цена неизвестна',
};
export default function Shopping() {
  const { mode } = useApp();
  return mode === 'api' ? <LiveShopping /> : <DemoShopping />;
}
function DemoShopping() {
  const {
    mode,
    profile,
    entries,
    selectedDate,
    shopping,
    setShopping,
    offlineConsent,
    setOfflineConsent,
    notify,
  } = useApp();
  const online = useOnline();
  const [confirm, setConfirm] = useState(false);
  const [replacement, setReplacement] = useState<ShoppingItem | null>(null);
  const [priceMode, setPriceMode] = useState<'known' | 'estimate'>('estimate');
  const [showMissing, setShowMissing] = useState(false);
  const dates = weekDates(selectedDate);
  const plan = entries.filter((e) =>
    profile.period === 'week' ? dates.includes(e.date) : e.date === selectedDate,
  );
  const quote = shopping ? quoteTotals(shopping.items, shopping.budget, shopping.priceMode) : null;
  const purchased = shopping?.items.filter((i) => i.status === 'PURCHASED').length || 0;
  function generate() {
    const items = buildShopping(plan, demoOffers);
    setShopping({
      items,
      createdAt: new Date().toISOString(),
      store: profile.store,
      period:
        profile.period === 'week'
          ? `${labelDate(dates[0])} — ${labelDate(dates[6])}`
          : labelDate(selectedDate),
      budget: profile.budget,
      stale: false,
      priceMode,
    });
    setConfirm(false);
    notify('Демонстрационный список построен. Серверное подтверждение меню ещё не подключено.');
  }
  function status(productId: number, next: ShoppingStatus) {
    if (!online || !shopping) return;
    setShopping({
      ...shopping,
      items: shopping.items.map((item) =>
        item.productId === productId ? { ...item, status: next } : item,
      ),
    });
  }
  function replace() {
    if (!replacement || !shopping || !online) return;
    const original = replacement.offer!;
    const packageG = original.packageG === 800 ? 1000 : 400;
    const price = Math.round(
      (((original.priceCents || 10000) * packageG) / original.packageG) * 1.1,
    );
    const packs = Math.ceil(replacement.requiredG / packageG);
    const offer = {
      ...original,
      packageG,
      priceCents: price,
      lowerCents: Math.round(price * 0.9),
      upperCents: Math.round(price * 1.2),
      quality: 'ESTIMATE' as const,
      assumption: 'Условная альтернативная упаковка. Состав SKU не проверен.',
    };
    setShopping({
      ...shopping,
      items: shopping.items.map((item) =>
        item.productId === replacement.productId
          ? {
              ...item,
              offer,
              packs,
              purchasedG: packs * packageG,
              remainderG: packs * packageG - item.requiredG,
              costCents: packs * price,
              consumedCents: Math.round((price * item.requiredG) / packageG),
              lowerCents: packs * offer.lowerCents,
              upperCents: packs * offer.upperCents,
              status: 'PLANNED',
            }
          : item,
      ),
    });
    setReplacement(null);
    notify('Демоупаковка заменена. Оценка корзины пересчитана.');
  }
  return (
    <>
      <PageHeading
        eyebrow="ОТ ПЛАНА — К КОРЗИНЕ"
        title="Покупки без лишнего"
        description={
          mode === 'demo'
            ? 'Всё для вашего меню. С учётом целых упаковок.'
            : 'Список продуктов по вашему меню. Цены и упаковки пока недоступны.'
        }
        actions={
          mode === 'demo' ? (
            <button
              className="button primary"
              disabled={!online || !plan.length}
              onClick={() => setConfirm(true)}
            >
              <RefreshCw size={17} />
              {shopping ? 'Обновить из меню' : 'Сформировать список'}
            </button>
          ) : undefined
        }
      />
      {mode === 'api' ? (
        <BackendPending
          title="Список покупок ещё не подключён"
          description="Меню уже доступно, но серверный список покупок пока не подключён. Цены и упаковки ещё не рассчитываются."
        />
      ) : !online && !offlineConsent ? (
        <Empty title="Нет сохранённого разрешения на offline-чтение">
          Вернитесь в сеть и разрешите локальное чтение списка покупок. Запись без сети не
          выполняется.
        </Empty>
      ) : !shopping ? (
        <>
          <div className="shopping-intro panel">
            <span className="large-icon">
              <ShoppingBasket size={40} />
            </span>
            <h2>Сначала немного планирования</h2>
            <p>
              Соберите блюда, выберите период и посмотрите, какие продукты понадобятся. Стоимость
              еды и полных упаковок покажем отдельно.
            </p>
            {plan.length ? (
              <button
                className="button primary"
                onClick={() => setConfirm(true)}
                disabled={!online}
              >
                Сформировать демосписок <ArrowRight size={16} />
              </button>
            ) : (
              <Link className="button primary" to="/">
                К меню дня <ArrowRight size={16} />
              </Link>
            )}
          </div>
          <div className="shopping-explainer">
            <div className="panel">
              <PackageOpen />
              <h3>Покупаем упаковки</h3>
              <p>На 1250 г риса нужны 2 упаковки по 800 г. Покупка — 200 ₽, а не 156,25 ₽.</p>
            </div>
            <div className="panel">
              <CircleHelp />
              <h3>Неизвестное видно</h3>
              <p>Если цены нет, итог неполный. Такой список не подтверждает соблюдение бюджета.</p>
            </div>
          </div>
        </>
      ) : (
        <>
          {shopping.stale && (
            <Notice tone="warning">
              Меню или настройки изменились. Список требует обновления. Уже отмеченные покупки
              сохранены; новое формирование потребует подтверждения.
            </Notice>
          )}
          <div className="shopping-layout">
            <section>
              <div className="store-heading panel">
                <span className="store-icon">
                  <Store size={24} />
                </span>
                <div>
                  <h2>Демонстрационный магазин</h2>
                  <p>{shopping.period} · личная покупка в магазине</p>
                  <small>Учебный источник · не фактические цены торговых сетей</small>
                </div>
                <Link className="icon-button" to="/stores" aria-label="Изменить магазин">
                  <ArrowRight size={19} />
                </Link>
              </div>
              <div className="shopping-progress">
                <div>
                  <span>
                    Собрано {purchased} из {shopping.items.length}
                  </span>
                  <b>
                    {shopping.items.length
                      ? Math.round((purchased / shopping.items.length) * 100)
                      : 0}
                    %
                  </b>
                </div>
                <div className="progress-track">
                  <i
                    style={{
                      width: `${shopping.items.length ? (purchased / shopping.items.length) * 100 : 0}%`,
                    }}
                  />
                </div>
              </div>
              <div className="section-title compact">
                <h2>Продукты для меню</h2>
                <button
                  className="button quiet"
                  aria-pressed={showMissing}
                  onClick={() => setShowMissing(!showMissing)}
                >
                  {showMissing ? 'Показать все' : 'Не найдено'}
                </button>
              </div>
              <div className="shopping-items">
                {shopping.items
                  .filter((item) => !showMissing || item.status === 'NOT_FOUND')
                  .map((item) => (
                    <article
                      className={`shopping-item ${item.status === 'PURCHASED' ? 'purchased' : ''} ${item.status === 'NOT_FOUND' ? 'not-found' : ''}`}
                      key={item.productId}
                    >
                      <button
                        className="shopping-checkbox"
                        aria-label={`${item.status === 'PURCHASED' ? 'Снять отметку' : 'Куплено'}: ${item.productName}`}
                        aria-pressed={item.status === 'PURCHASED'}
                        disabled={!online}
                        onClick={() =>
                          status(
                            item.productId,
                            item.status === 'PURCHASED' ? 'PLANNED' : 'PURCHASED',
                          )
                        }
                      >
                        {item.status === 'PURCHASED' ? <Check size={21} /> : null}
                      </button>
                      <div className="shopping-item-main">
                        <div className="shopping-item-title">
                          <h3>{item.productName}</h3>
                          <b>{money(item.costCents)}</b>
                        </div>
                        <p>
                          {item.packs != null
                            ? `${item.packs} уп. × ${number(item.offer!.packageG)} г`
                            : 'Упаковка неизвестна'}
                          <span>Нужно {number(item.requiredG, 2)} г</span>
                        </p>
                        <div className="item-tags">
                          <span
                            className={`quality-tag ${item.offer?.quality?.toLowerCase() || 'unknown'}`}
                          >
                            {qualityLabel[item.offer?.quality || 'UNKNOWN']}
                          </span>
                          {item.status === 'NOT_FOUND' && (
                            <span className="quality-tag unknown">Не найдено</span>
                          )}
                        </div>
                        <details>
                          <summary>Упаковка и источник</summary>
                          <p>
                            Приобретено:{' '}
                            {item.purchasedG != null ? `${number(item.purchasedG)} г` : '—'} ·
                            Остаток:{' '}
                            {item.remainderG != null ? `${number(item.remainderG, 2)} г` : '—'}
                          </p>
                          <p>Использованное количество: {money(item.consumedCents)}</p>
                          <p>
                            {item.offer?.source || 'Источник отсутствует'} ·{' '}
                            {item.offer
                              ? labelDate(item.offer.observedAt.slice(0, 10))
                              : 'дата неизвестна'}
                          </p>
                          {item.offer?.assumption && <p>{item.offer.assumption}</p>}
                        </details>
                        <div className="item-actions">
                          <button
                            className="text-button"
                            disabled={!online}
                            onClick={() =>
                              status(
                                item.productId,
                                item.status === 'NOT_FOUND' ? 'PLANNED' : 'NOT_FOUND',
                              )
                            }
                          >
                            {item.status === 'NOT_FOUND' ? 'Вернуть в список' : 'Не найдено'}
                          </button>
                          <button
                            className="text-button"
                            disabled={!online || !item.offer}
                            onClick={() => setReplacement(item)}
                          >
                            <ArrowLeftRight size={14} />
                            Заменить упаковку
                          </button>
                        </div>
                      </div>
                    </article>
                  ))}
              </div>
              {showMissing && !shopping.items.some((i) => i.status === 'NOT_FOUND') && (
                <Empty title="Все товары на месте">Пока нет строк с отметкой «Не найдено».</Empty>
              )}
              <label className="checkbox-label offline-consent">
                <input
                  type="checkbox"
                  checked={offlineConsent}
                  onChange={(event) => setOfflineConsent(event.target.checked)}
                />
                <span>Разрешить локальное чтение этого демосписка без сети</span>
              </label>
              <p className="caption">
                Последнее локальное сохранение списка:{' '}
                {new Date(shopping.createdAt).toLocaleString('ru-RU')}. Это не серверная
                синхронизация.
              </p>
            </section>
            <aside className="quote-aside">
              <div className="panel quote-panel">
                <span className="eyebrow">ОЦЕНКА КОРЗИНЫ</span>
                <h2>{quote?.unknown ? 'Известная часть' : 'Покупка упаковок'}</h2>
                <strong className="quote-amount">{money(quote?.central ?? 0)}</strong>
                {quote && !quote.unknown && (
                  <p className="muted">
                    Диапазон: {money(quote.lower)}–{money(quote.upper)}
                  </p>
                )}
                <p className="caption">
                  {shopping.priceMode === 'known' ? 'По известным ценам' : 'Предварительная оценка'}{' '}
                  · учебный пример
                </p>
                <div className="quote-lines">
                  <div>
                    <span>Используемая еда{quote?.unknown ? ' · часть' : ''}</span>
                    <b>{money(quote?.consumed ?? 0)}</b>
                  </div>
                  <div>
                    <span>Бюджет периода</span>
                    <b>{money(shopping.budget * 100)}</b>
                  </div>
                  <div>
                    <span>{quote?.complete ? 'Остаток по оценке' : 'Остаток бюджета'}</span>
                    <b>
                      {quote?.complete
                        ? money(shopping.budget * 100 - quote.central)
                        : 'Не определён'}
                    </b>
                  </div>
                </div>
                <Notice tone={quote?.status === 'WITHIN_SCENARIO' ? 'neutral' : 'warning'}>
                  {quote?.status === 'INCOMPLETE'
                    ? 'Оценка неполная. Неизвестные или недопустимые для выбранного режима цены не дают подтвердить бюджет.'
                    : quote?.status === 'OVER_BUDGET'
                      ? 'Покупка превышает бюджет. Уменьшите меню или измените параметры.'
                      : quote?.status === 'RISK'
                        ? 'Есть риск превышения: верхняя сценарная сумма больше бюджета.'
                        : 'Укладывается в учебный сценарный диапазон. Это не гарантия цены на кассе.'}
                </Notice>
                <div className="price-coverage">
                  <h3>Качество данных</h3>
                  {[
                    ['Наблюдения', quote?.observed],
                    ['Оценки', quote?.estimates],
                    ['Устаревшие', quote?.stale],
                    ['Неизвестные', quote?.unknown],
                  ].map(([label, value]) => (
                    <div key={label}>
                      <span>{label}</span>
                      <b>{value}</b>
                    </div>
                  ))}
                </div>
                <p className="caption">
                  Доставка и персональные купоны не учтены. Диапазон — учебное инженерное допущение,
                  не статистическая точность. Наличие товара не следует из цены.
                </p>
                <Link to="/stores" className="text-link">
                  Изменить бюджет <ArrowRight size={15} />
                </Link>
              </div>
              <div className="subtle-info">
                <CheckCheck size={19} />
                <p>Отметка «куплено» не означает, что продукт съеден или списан из запасов.</p>
              </div>
            </aside>
          </div>
        </>
      )}
      {confirm && (
        <Dialog
          title={shopping ? 'Обновить список покупок?' : 'Сформировать демосписок?'}
          onClose={() => setConfirm(false)}
        >
          <p className="muted">
            Период:{' '}
            {profile.period === 'week'
              ? `${labelDate(dates[0])} — ${labelDate(dates[6])}`
              : labelDate(selectedDate)}
            . Блюд: {plan.length}.
          </p>
          <label>
            Режим оценки
            <select
              value={priceMode}
              onChange={(event) => setPriceMode(event.target.value as 'known' | 'estimate')}
            >
              <option value="estimate">Предварительная оценка</option>
              <option value="known">По известным ценам</option>
            </select>
          </label>
          <Notice tone="warning">
            Учебные цены и упаковки. Это демонстрация интерфейса, а не подтверждение серверного меню
            или заказ в магазине.
            {shopping ? ' Отметки «куплено» и «не найдено» будут сброшены в новом списке.' : ''}
          </Notice>
          <div className="dialog-actions">
            <button className="button" onClick={() => setConfirm(false)}>
              Отмена
            </button>
            <button
              className="button primary"
              disabled={!online || !plan.length}
              onClick={generate}
            >
              Подтвердить
            </button>
          </div>
        </Dialog>
      )}
      {replacement && (
        <Dialog title="Замена упаковки" onClose={() => setReplacement(null)}>
          <h3>{replacement.productName}</h3>
          {profile.allergies.length ? (
            <Notice tone="warning">
              Состав альтернативного SKU не проверен. При выбранной аллергии замена заблокирована до
              проверки состава.
            </Notice>
          ) : (
            <>
              <p className="muted">
                Учебная альтернативная упаковка того же продукта. Цена и корзина будут пересчитаны
                после подтверждения. Замены ингредиента другим продуктом здесь нет.
              </p>
              <Notice>
                Предполагаем упаковку {replacement.offer?.packageG === 800 ? '1000' : '400'} г. Это
                допущение, а не товар выбранного магазина. Состав не проверен.
              </Notice>
              <div className="dialog-actions">
                <button className="button" onClick={() => setReplacement(null)}>
                  Отмена
                </button>
                <button className="button primary" onClick={replace} disabled={!online}>
                  Выбрать и пересчитать
                </button>
              </div>
            </>
          )}
        </Dialog>
      )}
    </>
  );
}
