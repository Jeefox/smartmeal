import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import { Check, Copy, ShoppingBasket } from 'lucide-react';
import { Link } from 'react-router';
import { budgetLabel } from '../period-planning';
import type { PeriodView } from '../period-planning';
import {
  buyAmount,
  grams,
  missingMeals,
  needToBuy,
  purchaseKey,
  shoppingText,
} from '../period-shopping';
import { number, labelDate } from '../domain';
import { Dialog, Notice } from './ui';
const money = (value: number) => `${number(value, 2)} ₽`;
export default function PeriodShoppingList({
  view,
  userId,
  savePanel,
  onMissing,
}: {
  view: PeriodView;
  userId: string;
  savePanel?: ReactNode;
  onMissing?: () => void;
}) {
  const basket = view.result.shopping,
    items = basket.items.filter((i) => needToBuy(i) > 0),
    home = basket.items.filter((i) => needToBuy(i) === 0);
  const storageKey = `smartmeal.shopping-checks.${userId}.${view.id}`;
  const [checked, setChecked] = useState<string[]>([]),
    [loaded, setLoaded] = useState(''),
    [copy, setCopy] = useState(''),
    [fallback, setFallback] = useState(false);
  const [storageError, setStorageError] = useState(false);
  useEffect(() => {
    try {
      const stored = JSON.parse(localStorage.getItem(storageKey) || '[]');
      setChecked(Array.isArray(stored) ? stored.filter((x) => typeof x === 'string') : []);
    } catch {
      setChecked([]);
    }
    setLoaded(storageKey);
  }, [storageKey]);
  function toggle(key: string) {
    const next = checked.includes(key) ? checked.filter((x) => x !== key) : [...checked, key];
    setChecked(next);
    try {
      localStorage.setItem(storageKey, JSON.stringify(next));
    } catch {
      setStorageError(true);
    }
  }
  const picked = items.filter((i) => checked.includes(purchaseKey(i))).length;
  const partial = missingMeals(view).length > 0 || view.result.status === 'NO_FEASIBLE_FOUND';
  return (
    <div className="purchase-list">
      <div className="purchase-heading">
        <div>
          <span className="eyebrow">
            {partial ? 'ПРЕДВАРИТЕЛЬНЫЙ СПИСОК' : 'МОЖНО БРАТЬ В МАГАЗИН'}
          </span>
          <h1>Что купить</h1>
          <p>
            {labelDate(view.result.options.from)} — {labelDate(view.result.options.to)}
            {(view.result.participants?.length || 0) > 1
              ? ` · на ${view.result.participants!.length} чел.`
              : ''}
          </p>
        </div>
        <button
          className="button secondary"
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(shoppingText(view));
              setCopy('Список скопирован');
            } catch {
              setFallback(true);
            }
          }}
        >
          <Copy size={16} />
          Скопировать список
        </button>
      </div>
      <div className="purchase-total panel">
        <ShoppingBasket size={24} />
        <div>
          <span>
            {basket.estimatedTotal == null ? 'Известная часть суммы' : 'На покупки примерно'}
          </span>
          <strong>{money(basket.estimatedTotal ?? basket.knownPurchaseCost)}</strong>
        </div>
        <span className="purchase-progress" role="status">
          Собрано {picked} из {items.length}
        </span>
      </div>
      <p className="caption">
        {budgetLabel[basket.budgetStatus]}
        {view.result.options.budgetRub != null
          ? ` · бюджет ${money(view.result.options.budgetRub)}`
          : ''}
        .
      </p>
      {copy && (
        <p role="status" className="caption">
          {copy}
        </p>
      )}
      {storageError && (
        <Notice>
          Отметки доступны до закрытия страницы: браузер не разрешил сохранить их на этом
          устройстве.
        </Notice>
      )}
      {partial && (
        <Notice>
          <strong>Меню ещё не заполнено полностью.</strong> Здесь покупки только для подобранных
          блюд.{' '}
          {onMissing && (
            <button className="text-button" onClick={onMissing}>
              Показать незаполненный день
            </button>
          )}
        </Notice>
      )}
      <p className="caption purchase-help">
        Отмечайте то, что положили в корзину. Галочка сохраняется на этом устройстве и не списывает
        продукты дома.
      </p>
      {basket.unknownProducts > 0 && (
        <Notice>
          Товары без цены: {basket.unknownProducts}. Итоговая покупка может стоить больше указанной
          суммы.
        </Notice>
      )}
      {basket.estimatedProducts > 0 && (
        <p className="caption">Цены приблизительные. Наличие товара и цену проверьте в магазине.</p>
      )}
      {!items.length && (
        <div className="panel purchase-empty">
          <Check size={24} />
          <h3>{home.length ? 'Всё необходимое уже есть дома' : 'Покупать пока нечего'}</h3>
          <p>
            {home.length
              ? 'Новые покупки для этого меню не нужны.'
              : 'Сначала подберите блюда для меню.'}
          </p>
        </div>
      )}
      {[...new Set(items.map((i) => i.chainName || 'Магазин не выбран'))].map((chain) => {
        const group = items.filter((i) => (i.chainName || 'Магазин не выбран') === chain);
        const unknown = group.some((i) => i.purchaseCost == null);
        return (
          <section className="purchase-store panel" key={chain} aria-label={`Покупки: ${chain}`}>
            <header>
              <h3>{chain}</h3>
              <span>
                {unknown ? 'Известно ' : '≈ '}
                {money(group.reduce((sum, i) => sum + (i.purchaseCost ?? 0), 0))}
              </span>
            </header>
            <ul>
              {group.map((item) => {
                const key = purchaseKey(item),
                  picked = checked.includes(key);
                return (
                  <li className={`purchase-row ${picked ? 'is-picked' : ''}`} key={item.productId}>
                    <label className="purchase-pick">
                      <input
                        type="checkbox"
                        aria-label={`Взял: ${item.productName}`}
                        checked={picked}
                        disabled={loaded !== storageKey}
                        onChange={() => toggle(key)}
                      />
                      <span className="purchase-tick" aria-hidden="true">
                        <Check size={16} />
                      </span>
                    </label>
                    <div className="purchase-product">
                      <h4>{item.productName}</h4>
                      <p className="purchase-quantity">{buyAmount(item)}</p>
                      {item.applicability === 'REFERENCE_ONLY' && (
                        <span className="purchase-reference">Ориентир другой сети</span>
                      )}
                      {item.offerName && item.offerName !== item.productName && (
                        <p className="purchase-sku">{item.offerName}</p>
                      )}
                      <details>
                        <summary>Для меню и источник цены</summary>
                        <p>
                          Для рецептов: {grams(item.requiredG)}. Из запасов:{' '}
                          {grams(item.fromPantryG ?? 0)}. Докупить: {grams(needToBuy(item))}.
                        </p>
                        {item.remainderG != null && (
                          <p>Прогноз остатка упаковки после меню: {grams(item.remainderG)}.</p>
                        )}
                        {item.applicability === 'REFERENCE_ONLY' && (
                          <p>
                            Это ориентир другого источника. Предложение в этой сети не подтверждено.
                          </p>
                        )}
                        <p>
                          {item.sourceName || 'Источник не указан'}
                          {item.sourceScope ? ` · ${item.sourceScope}` : ''}
                          {item.observedAt
                            ? ` · ${new Date(item.observedAt).toLocaleDateString('ru-RU')}`
                            : ''}
                        </p>
                        {item.sourceUrl && (
                          <a href={item.sourceUrl} target="_blank" rel="noreferrer">
                            Открыть источник цены
                          </a>
                        )}
                        {item.purchasedG != null && item.purchasedG > 0 && (
                          <p>
                            <Link
                              to={`/pantry?purchase=1&productId=${item.productId}&quantityG=${item.purchasedG}`}
                            >
                              Добавить фактическую покупку в домашние запасы
                            </Link>
                          </p>
                        )}
                      </details>
                    </div>
                    <strong className="purchase-price">
                      {item.purchaseCost == null
                        ? 'Нет цены'
                        : `${item.estimated ? '≈ ' : ''}${money(item.purchaseCost)}`}
                    </strong>
                  </li>
                );
              })}
            </ul>
          </section>
        );
      })}
      {home.length > 0 && (
        <details className="panel purchase-home">
          <summary>Уже есть дома · {home.length}</summary>
          <ul>
            {home.map((i) => (
              <li key={i.productId}>
                <span>{i.productName}</span>
                <strong>{grams(i.fromPantryG ?? i.requiredG)}</strong>
              </li>
            ))}
          </ul>
        </details>
      )}
      <p className="caption">
        Оценка на {new Date(view.result.dataAsOf).toLocaleString('ru-RU')}. Стоимость доставки и
        личные скидки не учтены.
      </p>
      {savePanel}
      {fallback && (
        <Dialog title="Скопируйте список покупок" onClose={() => setFallback(false)}>
          <textarea
            aria-label="Текст списка покупок"
            className="purchase-copy"
            readOnly
            value={shoppingText(view)}
            autoFocus
            onFocus={(e) => e.currentTarget.select()}
          />
          <p>Выделите текст и скопируйте обычным способом.</p>
        </Dialog>
      )}
    </div>
  );
}
