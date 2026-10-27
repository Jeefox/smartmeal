import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import ShoppingEstimate from '../components/ShoppingEstimate';
import { useQuery } from '@tanstack/react-query';
import { CalendarDays, Leaf, PackageOpen, RefreshCw, ShoppingBasket } from 'lucide-react';
import { Link, Navigate, useSearchParams } from 'react-router';
import { useApp, useOnline } from '../state';
import { useSession } from '../session';
import { ApiError } from '../api';
import { labelDate, number, weekDates } from '../domain';
import { periodErrors, readShoppingNeeds, shoppingKey } from '../shopping';
import { Empty, Loading, Notice, PageHeading } from '../components/ui';
export default function LiveShopping() {
  const [params] = useSearchParams();
  if (!params.has('from') && !params.has('to') && !params.has('view'))
    return <Navigate replace to="/shopping-lists" />;
  return <ShoppingByPeriod />;
}
function ShoppingByPeriod() {
  const { selectedDate } = useApp();
  const session = useSession();
  const userId = session.profile!.userId;
  const online = useOnline();
  const [params, setParams] = useSearchParams();
  const defaults = weekDates(selectedDate);
  const from = params.get('from') ?? defaults[0],
    to = params.get('to') ?? defaults[6];
  const [draftFrom, setFrom] = useState(from),
    [draftTo, setTo] = useState(to);
  const [errors, setErrors] = useState<Record<string, string>>({});
  useEffect(() => {
    setFrom(from);
    setTo(to);
    setErrors({});
  }, [from, to]);
  const invalid = periodErrors(from, to);
  const rangeValid = !Object.keys(invalid).length;
  const query = useQuery({
    queryKey: shoppingKey(userId, from, to),
    queryFn: ({ signal }) => readShoppingNeeds(from, to, signal),
    enabled: rangeValid,
    staleTime: 0,
    refetchOnWindowFocus: true,
  });
  const data = query.data;
  const draftChanged = draftFrom !== from || draftTo !== to;
  function apply(event: FormEvent) {
    event.preventDefault();
    const next = periodErrors(draftFrom, draftTo);
    setErrors(next);
    if (Object.keys(next).length) return;
    const nextParams = new URLSearchParams(params);
    nextParams.set('from', draftFrom);
    nextParams.set('to', draftTo);
    setParams(nextParams);
    if (draftFrom === from && draftTo === to) query.refetch();
  }
  function preset(start: string, end: string) {
    setFrom(start);
    setTo(end);
    setErrors({});
    const next = new URLSearchParams(params);
    next.set('from', start);
    next.set('to', end);
    setParams(next);
  }
  const visibleErrors = Object.keys(errors).length ? errors : invalid;
  return (
    <>
      <PageHeading
        eyebrow="ПО ВАШЕМУ МЕНЮ"
        title="Продукты для покупок"
        description="Покупная масса с учётом очистки, уже рассчитанная сервером. Обе даты включены."
        actions={
          <button
            className="button"
            disabled={!online || query.isFetching || !rangeValid}
            onClick={() => query.refetch()}
          >
            <RefreshCw size={17} />
            {query.isFetching ? 'Обновляем…' : 'Обновить список'}
          </button>
        }
      />
      <section className="panel shopping-period-panel">
        <form className="shopping-period-form" onSubmit={apply} noValidate>
          <label>
            <span>С даты</span>
            <input
              type="date"
              name="from"
              value={draftFrom}
              onChange={(event) => {
                setFrom(event.target.value);
                setErrors({});
              }}
              aria-invalid={Boolean(visibleErrors.from)}
              aria-describedby="period-from-error"
            />
            {visibleErrors.from && (
              <span className="field-error" id="period-from-error" role="alert">
                {visibleErrors.from}
              </span>
            )}
          </label>
          <label>
            <span>По дату включительно</span>
            <input
              type="date"
              name="to"
              value={draftTo}
              onChange={(event) => {
                setTo(event.target.value);
                setErrors({});
              }}
              aria-invalid={Boolean(visibleErrors.to)}
              aria-describedby="period-to-error"
            />
            {visibleErrors.to && (
              <span className="field-error" id="period-to-error" role="alert">
                {visibleErrors.to}
              </span>
            )}
          </label>
          <button className="button primary" disabled={!online} type="submit">
            <CalendarDays size={16} />
            Показать список
          </button>
        </form>
        <div className="period-presets">
          <button className="button quiet" onClick={() => preset(selectedDate, selectedDate)}>
            Выбранный день
          </button>
          <button className="button quiet" onClick={() => preset(defaults[0], defaults[6])}>
            Выбранная неделя
          </button>
          <span className="caption">От 1 до 31 дня · обе границы включены</span>
        </div>
        {draftChanged && (
          <p className="caption" role="status">
            Период изменён в полях. Нажмите «Показать список», чтобы применить его.
          </p>
        )}
      </section>
      <ShoppingEstimate from={from} to={to} rangeValid={rangeValid} />
      {!rangeValid ? (
        <Notice tone="warning">
          Период в адресе страницы некорректен. Исправьте даты и примените выбор. Запрос не
          отправлен.
        </Notice>
      ) : query.isPending ? (
        <Loading />
      ) : query.isError && !data ? (
        <div className="error-state" role="alert">
          <PackageOpen size={30} />
          <h2>
            {query.error instanceof ApiError && query.error.status === 400
              ? 'Проверьте период'
              : 'Не удалось получить список'}
          </h2>
          <p>{query.error.message}</p>
          <button className="button" disabled={!online} onClick={() => query.refetch()}>
            <RefreshCw size={16} />
            Повторить
          </button>
        </div>
      ) : data ? (
        <>
          {query.isError && (
            <Notice tone="warning">
              Не удалось обновить список: {query.error.message} Ниже показаны ранее полученные
              данные, они могут быть устаревшими.
            </Notice>
          )}
          {!online && (
            <Notice tone="warning">
              Нет сети. Показан ранее полученный список из памяти этой вкладки. Актуальность меню не
              подтверждена; после восстановления сети обновите список.
            </Notice>
          )}
          <div className="live-shopping-layout">
            <section>
              <div className="panel shopping-source">
                <span className="store-icon">
                  <ShoppingBasket size={25} />
                </span>
                <div>
                  <h2>
                    {labelDate(data.from)} — {labelDate(data.to)}
                  </h2>
                  <p>По сохранённым версиям рецептов в меню</p>
                </div>
                <span className="count" aria-label="Количество продуктов">
                  {data.items.length}
                </span>
              </div>
              {data.containsDrafts && (
                <div role="status">
                  <Notice tone="warning">
                    В периоде есть черновые меню. Список предварительный: изменения блюд или порций
                    изменят потребность. Перед покупкой обновите его.
                  </Notice>
                </div>
              )}
              {data.items.length ? (
                <ul className="live-needs-list" aria-label="Потребность в продуктах">
                  {data.items.map((item) => (
                    <li key={item.productId}>
                      <span className="needs-symbol" aria-hidden="true">
                        <Leaf size={17} />
                      </span>
                      <span className="needs-name">
                        {item.productName}
                        <small className="muted">
                          {' '}
                          · нужно {number(item.requiredG ?? item.quantityG, 2)} г · дома{' '}
                          {number(item.fromPantryG ?? 0, 2)} г
                        </small>
                        {item.quantityG > 0 && (
                          <Link
                            to={`/pantry?purchase=1&productId=${item.productId}&quantityG=${item.quantityG}`}
                          >
                            Купил — подтвердить массу
                          </Link>
                        )}
                      </span>
                      <strong className="needs-quantity">
                        {number(item.quantityG, 2)} <small>г</small>
                      </strong>
                    </li>
                  ))}
                </ul>
              ) : (
                <Empty
                  title="На этот период нет продуктов"
                  action={
                    <Link className="button primary" to={`/?date=${data.from}`}>
                      Открыть меню
                    </Link>
                  }
                >
                  В меню за выбранные даты нет ингредиентов для списка покупок. Можно выбрать другой
                  период или добавить блюда.
                </Empty>
              )}
              <p className="caption">
                {query.isFetching
                  ? 'Обновляем данные…'
                  : `Данные получены: ${new Date(query.dataUpdatedAt).toLocaleString('ru-RU')}.`}{' '}
                Сервер объединяет один продукт из разных блюд по его ID.
              </p>
            </section>
            <aside className="panel needs-information">
              <span className="eyebrow">ЧТО В ЭТОМ СПИСКЕ</span>
              <PackageOpen size={30} />
              <h2>Только нужное количество</h2>
              <p>
                Потребность рассчитана по порциям из меню и сохранённому составу рецептов.
                Учитываются черновики и подтверждённые дни.
              </p>
              <Notice>
                Здесь указана покупная масса с учётом очистки. Выберите магазин выше для расчёта
                целых упаковок и оценочной стоимости. Доступные домашние запасы уже вычтены; резервы
                других меню защищены.
              </Notice>
              <p className="caption">
                Отметки «куплено» и замены появятся после подключения их сохранения. Сейчас список
                доступен для просмотра.
              </p>
              <Link to={`/?date=${data.from}`} className="text-link">
                Перейти к меню
              </Link>
            </aside>
          </div>
        </>
      ) : null}
    </>
  );
}
