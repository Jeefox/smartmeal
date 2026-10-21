import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../api';
import { useSession } from '../session';
import { rubles } from '../retail';
import { Notice } from './ui';

type Reference = {
  id: number;
  productId: number;
  productName: string;
  name: string;
  packageWeightG: number;
  price: number;
  sourceName: string;
  sourceUrl: string;
  sourceScope: string;
  retrievedAt: string;
  expiresAt: string;
  soldByWeight?: boolean;
};
export default function CataloguePriceReferences() {
  const [expanded, setExpanded] = useState(false);
  const user = useSession().profile!.userId;
  const coverage = useQuery({
    queryKey: ['price-references-coverage', user],
    queryFn: ({ signal }) =>
      request<
        { productId: number; productName: string; priced: boolean; lastError: string | null }[]
      >('/price-references/coverage', { signal }),
    retry: false,
    refetchInterval: 60000,
  });
  const status = useQuery({
    queryKey: ['price-references-status', user],
    queryFn: ({ signal }) =>
      request<
        {
          id: number;
          productName: string;
          enabled: boolean;
          lastSuccessAt: string | null;
          lastError: string | null;
        }[]
      >('/price-references/status', { signal }),
    retry: false,
    refetchInterval: 60000,
  });
  const query = useQuery({
    queryKey: ['price-references', user],
    queryFn: ({ signal }) => request<Reference[]>('/price-references', { signal }),
    retry: false,
    staleTime: 60000,
    refetchInterval: 60000,
  });
  if (query.isPending) return <p className="caption">Загружаем каталожные ориентиры…</p>;
  if (query.isError)
    return (
      <Notice tone="warning">
        Каталожные ориентиры не загрузились.{' '}
        <button className="button quiet" onClick={() => query.refetch()}>
          Повторить загрузку ориентиров
        </button>
      </Notice>
    );
  if (!query.data?.length && !status.data?.length && !coverage.data?.length) return null;
  const best = new Map<number, Reference>();
  for (const item of query.data ?? []) {
    const previous = best.get(item.productId);
    if (!previous || item.price / item.packageWeightG < previous.price / previous.packageWeightG) {
      best.set(item.productId, item);
    }
  }
  const all = [...best.values()].sort((a, b) => a.productName.localeCompare(b.productName, 'ru'));
  const visible = expanded ? all : all.slice(0, 6);
  return (
    <section aria-label="Каталожные ориентиры" className="catalogue-reference-panel">
      <h3>Цены из опубликованных каталогов</h3>
      <Notice>
        Эти цены используются для предварительной оценки, когда в выбранном магазине ещё нет
        наблюдений. Они относятся к указанному источнику; цена и наличие в вашей точке Тулы не
        подтверждены.
      </Notice>
      {status.data && status.data.length > 0 && (
        <Notice
          tone={status.data.some((item) => item.enabled && item.lastError) ? 'warning' : undefined}
        >
          {status.data.some((item) => item.enabled)
            ? 'Цены проверяются автоматически раз в сутки, пока сервер приложения работает.'
            : 'Автоматическое обновление отключено в настройках сервера.'}
          {status.data.some((item) => item.enabled && item.lastError)
            ? ' Часть источников сейчас недоступна. Повторим позже; дата старых цен не продлевается.'
            : status.data.some((item) => item.enabled && !item.lastSuccessAt)
              ? ' Первое обновление ожидается.'
              : ' Последние проверки выполнены.'}
        </Notice>
      )}
      {coverage.data && coverage.data.length > 0 && (
        <div>
          <p className="caption">
            Ориентиры найдены для {coverage.data.filter((item) => item.priced).length} из{' '}
            {coverage.data.length} продуктов в рецептах. Новые ингредиенты попадают в поиск
            автоматически.
          </p>
          {coverage.data.some((item) => !item.priced) && (
            <details>
              <summary>Для каких продуктов ещё нет цен</summary>
              <ul>
                {coverage.data
                  .filter((item) => !item.priced)
                  .map((item) => (
                    <li key={item.productId}>
                      {item.productName} —{' '}
                      {item.lastError
                        ? 'пока не найден подходящий товар с проверяемой ценой и массой'
                        : 'ожидает автоматического поиска'}
                    </li>
                  ))}
              </ul>
            </details>
          )}
        </div>
      )}
      <ul className="catalogue-reference-list">
        {visible.map((item) => (
          <li key={item.id}>
            <div>
              <strong>{item.productName}</strong>
              <p className="caption">
                {item.name}
                {item.soldByWeight ? ' · на вес' : ` · ${item.packageWeightG} г`}
              </p>
            </div>
            <strong>
              {rubles(item.price)} / {item.soldByWeight ? 'кг' : 'уп.'}
            </strong>
            <p className="caption">
              {item.sourceName} · просмотрено{' '}
              {new Date(item.retrievedAt).toLocaleDateString('ru-RU')} · используем до{' '}
              {new Date(item.expiresAt).toLocaleDateString('ru-RU')} ·{' '}
              <a href={item.sourceUrl} target="_blank" rel="noreferrer">
                Источник цены
              </a>
            </p>
          </li>
        ))}
      </ul>
      {all.length > 6 && (
        <button className="button quiet" onClick={() => setExpanded(!expanded)}>
          {expanded ? 'Свернуть ориентиры' : `Показать все ${all.length} продуктов с ценами`}
        </button>
      )}
    </section>
  );
}
