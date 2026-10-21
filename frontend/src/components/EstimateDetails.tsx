import { PackageOpen } from 'lucide-react';
import { number } from '../domain';
import { rubles } from '../retail';
import type { ShoppingEstimate } from '../retail';
import { Notice } from './ui';
export default function EstimateDetails({
  data,
  updatedAt,
  isFetching = false,
}: {
  data: ShoppingEstimate;
  updatedAt: number;
  isFetching?: boolean;
}) {
  return (
    <>
      {data.containsDrafts && (
        <Notice tone="warning">
          Расчёт включает черновые меню. Изменения блюд и порций изменят покупки; перед походом в
          магазин обновите расчёт.
        </Notice>
      )}
      <div className="estimate-total">
        <div>
          <span>
            {data.items.some((item) => item.referencePrice)
              ? 'Предварительная стоимость с ориентирами'
              : data.complete
                ? 'Оценочная стоимость'
                : 'Известная часть стоимости'}
          </span>
          <strong>{rubles(data.knownPurchaseCost)}</strong>
        </div>
        <span className="badge">{data.complete ? 'Все продукты покрыты' : 'Расчёт неполный'}</span>
      </div>
      {!data.complete && (
        <Notice tone="warning">
          Сумма включает строки с известной ценой и отмеченные каталожные ориентиры. Для остальных
          продуктов стоимость не определена. Каталожный ориентир не подтверждает цену в выбранном
          магазине — эта сумма не является подтверждённым бюджетом.
        </Notice>
      )}
      {!data.items.length ? (
        <p role="status">
          На этот период нет продуктов для расчёта. Оценочная стоимость —{' '}
          {rubles(data.knownPurchaseCost)}.
        </p>
      ) : (
        <ul className="estimate-items" aria-label="Упаковки и стоимость">
          {data.items.map((item) => (
            <li key={item.productId}>
              <div className="estimate-item-title">
                <h3>{item.productName}</h3>
                <span>Нужно {number(item.requiredWeightG, 2)} г</span>
              </div>
              {item.purchaseCost == null ? (
                <Notice tone="warning">
                  Нет предложения с известной ценой. Стоимость не определена.
                </Notice>
              ) : (
                <>
                  <p className="estimate-sku">
                    <PackageOpen size={17} />
                    {item.storeProductName}
                  </p>
                  <div className="estimate-item-values">
                    <span>
                      {item.referencePrice && item.packageCount == null
                        ? `${number(item.requiredWeightG, 2)} г · на вес`
                        : `${item.packageCount} уп. × ${number(item.packageWeightG!, 2)} г`}
                    </span>
                    <span>
                      {rubles(item.packagePrice!)} /{' '}
                      {item.referencePrice && item.packageCount == null ? 'кг' : 'уп.'}
                    </span>
                    <strong>{rubles(item.purchaseCost)}</strong>
                  </div>
                  <p className="caption">
                    Остаток после приготовления: {number(item.remainderG!, 0)} г · округлён вниз до
                    целых граммов.
                  </p>
                  {item.referencePrice && (
                    <Notice tone="warning">
                      Каталожный ориентир · {item.referenceSourceName}. {item.referenceSourceScope}
                      {item.referenceSourceUrl && (
                        <a href={item.referenceSourceUrl} target="_blank" rel="noreferrer">
                          {' '}
                          Открыть источник
                        </a>
                      )}
                    </Notice>
                  )}
                  <p className="caption">
                    {item.referencePrice ? 'Источник просмотрен: ' : 'Цена на '}{' '}
                    {item.priceObservedAt
                      ? new Date(item.priceObservedAt).toLocaleString('ru-RU')
                      : 'неизвестную дату'}{' '}
                    · источник:{' '}
                    {item.referencePrice
                      ? 'каталог (приблизительно)'
                      : item.priceSource === 'MANUAL'
                        ? 'ручной ввод'
                        : (item.priceSource ?? 'не указан')}
                  </p>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      <p className="caption">
        Последняя неакционная цена без ограничения давности. Цены и наличие в магазине могут
        отличаться. Выбирается целое число одинаковых упаковок для каждого продукта; сочетания
        разных упаковок не сравниваются. Домашние запасы не вычитаются.
      </p>
      <p className="caption">
        {isFetching
          ? 'Обновляем расчёт…'
          : `Расчёт получен: ${new Date(updatedAt).toLocaleString('ru-RU')}.`}
      </p>
    </>
  );
}
