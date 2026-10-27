import { Check } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { useSession } from '../session';
import { savedPeriod } from '../planning-preferences';
import { useApp, useOnline } from '../state';
import { ApiError } from '../api';
import { labelDate, number } from '../domain';
import { Dialog, Loading, Notice, PageHeading } from '../components/ui';
import PlanningActionBar from '../components/planning/PlanningActionBar';
import { shoppingSessions, readLocal, writeLocal, validateLines } from '../shopping-session';
import type {
  ShoppingSession,
  SessionItem,
  LocalState,
  Pending,
  FormLine,
  PurchaseLine,
} from '../shopping-session';
const rub = (n: number) => `${number(n, 2)} ₽`,
  grams = (n: number) => `${number(n, 2)} г`;
export default function ShoppingSessions() {
  const { id } = useParams(),
    [params] = useSearchParams(),
    session = useSession(),
    { mode } = useApp();
  if (mode !== 'api')
    return <Notice>Покупки с пополнением запасов доступны в режиме SmartMeal API.</Notice>;
  if (!session.profile) return <Loading />;
  return id ? (
    <SessionDetails
      key={`${session.profile.userId}:${id}`}
      id={id}
      userId={session.profile.userId}
    />
  ) : params.get('source') ? (
    <OpenSource
      key={`${session.profile.userId}:${params.get('source')}`}
      source={params.get('source')!}
    />
  ) : (
    <SessionIndex userId={session.profile.userId} />
  );
}
function OpenSource({ source }: { source: string }) {
  const nav = useNavigate(),
    online = useOnline(),
    [error, setError] = useState(''),
    [attempt, setAttempt] = useState(0);
  const key = useRef(crypto.randomUUID()),
    promise = useRef<Promise<ShoppingSession> | null>(null);
  useEffect(() => {
    let active = true;
    if (!online) return;
    promise.current ??= shoppingSessions.open(source, key.current);
    promise.current
      .then((v) => {
        if (active) nav(`/shopping-lists/${v.id}`, { replace: true });
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [source, nav, online, attempt]);
  return (
    <>
      <PageHeading title="Открываем список покупок" description="По вашему сохранённому меню" />
      {error ? (
        <Notice tone="warning">
          {error}
          <button
            className="button"
            disabled={!online}
            onClick={() => {
              promise.current = null;
              setError('');
              setAttempt((x) => x + 1);
            }}
          >
            Повторить открытие
          </button>
        </Notice>
      ) : online ? (
        <Loading />
      ) : (
        <Notice>Нет сети. Подключитесь, чтобы открыть серверный список.</Notice>
      )}
      <Link className="text-link" to="/shopping-lists">
        Все списки покупок
      </Link>
    </>
  );
}
function SessionIndex({ userId }: { userId: string }) {
  const recent = savedPeriod(userId);
  const [status, setStatus] = useState('OPEN'),
    [page, setPage] = useState(0),
    online = useOnline();
  const q = useQuery({
    queryKey: ['shopping-sessions', userId, status, page],
    queryFn: ({ signal }) => shoppingSessions.list(status, page, signal),
    retry: false,
  });
  return (
    <>
      <PageHeading
        title="Списки покупок"
        description="Отметки и фактические покупки сохраняются в вашем аккаунте."
        actions={
          <Link className="button" to="/menu-generation">
            Составить меню
          </Link>
        }
      />
      {recent && (
        <p>
          <Link className="button" to={`/shopping-lists?source=${encodeURIComponent(recent.id)}`}>
            Открыть покупки последнего сохранённого меню · {labelDate(recent.from)} —{' '}
            {labelDate(recent.to)}
          </Link>
        </p>
      )}
      <label>
        Показать
        <select
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setPage(0);
          }}
        >
          <option value="OPEN">Незавершённые</option>
          <option value="ALL">Все списки</option>
          <option value="COMPLETED">Завершённые</option>
        </select>
      </label>
      {q.isPending ? (
        <Loading />
      ) : q.isError ? (
        <Notice tone="warning">
          {q.error.message}
          <button className="button" disabled={!online} onClick={() => q.refetch()}>
            Повторить
          </button>
        </Notice>
      ) : (
        <>
          <div className="shopping-session-index">
            {q.data.items.map((s) => (
              <Link
                className="panel shopping-session-link"
                key={s.id}
                to={`/shopping-lists/${s.id}`}
              >
                <strong>
                  {labelDate(s.from)} — {labelDate(s.to)}
                </strong>
                <span>{s.status === 'OPEN' ? 'Продолжить покупки' : 'Покупки завершены'}</span>
              </Link>
            ))}
          </div>
          {!q.data.items.length && (
            <Notice>Списков пока нет. Сохраните меню и откройте покупки по нему.</Notice>
          )}
          <div className="button-row">
            <button className="button" disabled={!page} onClick={() => setPage((p) => p - 1)}>
              Назад
            </button>
            <span>Страница {page + 1}</span>
            <button
              className="button"
              disabled={!q.data.hasNext}
              onClick={() => setPage((p) => p + 1)}
            >
              Далее
            </button>
          </div>
        </>
      )}
      <Link className="text-link" to="/shopping?view=calendar">
        Рассчитать продукты по датам
      </Link>
    </>
  );
}
function SessionDetails({ id, userId }: { id: string; userId: string }) {
  const online = useOnline(),
    client = useQueryClient(),
    key = ['shopping-session', userId, id],
    alive = useRef(true),
    busyRef = useRef(false),
    navigate = useNavigate();
  const [local, setLocal] = useState<LocalState>(() => readLocal(userId, id)),
    localRef = useRef(local);
  localRef.current = local;
  const confirmation = useRef<{ revision: number; pantryRevision: number } | null>(null);
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(''),
    [message, setMessage] = useState(''),
    [confirm, setConfirm] = useState(false),
    [filter, setFilter] = useState('remaining'),
    [review, setReview] = useState(false),
    [storageError, setStorageError] = useState(false);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  const q = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => shoppingSessions.get(id, signal),
    staleTime: 0,
    retry: false,
    refetchOnWindowFocus: true,
    refetchOnReconnect: true,
  });
  const view = q.data;
  function store(next: LocalState) {
    localRef.current = next;
    setLocal(next);
    const ok = writeLocal(userId, id, next);
    setStorageError(!ok);
    return ok;
  }
  useEffect(() => {
    if (!view) return;
    const p = localRef.current.pending;
    if (p?.kind === 'purchase' && view.appliedPurchaseKeys.includes(p.key)) {
      store({ ...localRef.current, pending: null, form: {} });
      setConfirm(false);
      setError('');
      setMessage('Покупка уже добавлена в запасы. Повторять её не нужно.');
    }
  }, [view]);
  async function reload() {
    try {
      const next = await shoppingSessions.get(id);
      if (alive.current) client.setQueryData(key, next);
      return next;
    } catch (e) {
      if (alive.current) setError(e instanceof Error ? e.message : 'Не удалось обновить список');
      return null;
    }
  }
  async function send(p: Pending) {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError('');
    setMessage('');
    try {
      const result = await shoppingSessions.send(id, p);
      if (!alive.current) return;
      client.setQueryData(key, result);
      store({
        ...localRef.current,
        pending: null,
        form: p.kind === 'purchase' ? {} : localRef.current.form,
      });
      setConfirm(false);
      setReview(false);
      setMessage(
        p.kind === 'purchase'
          ? `Добавлено в запасы: ${p.body.items.length} поз.`
          : 'Отметка сохранена на сервере.',
      );
      client.invalidateQueries({ queryKey: ['pantry'] });
      client.invalidateQueries({ queryKey: ['shopping-sessions', userId] });
      await reload();
    } catch (e) {
      if (!alive.current) return;
      setConfirm(false);
      const err = e as ApiError;
      setError(err.message);
      if (err.status >= 400 && err.status < 500) {
        store({ ...localRef.current, pending: null });
        setReview(p.kind === 'purchase');
        await reload();
      } else {
        setMessage('Результат пока неизвестен. Проверяем список без повторной покупки.');
        await reload();
      }
    } finally {
      busyRef.current = false;
      if (alive.current) setBusy(false);
    }
  }
  function mark(i: SessionItem, picked: boolean, skipped: boolean) {
    if (!view || localRef.current.pending || busyRef.current) return;
    const p: Pending = {
      kind: 'mark',
      key: crypto.randomUUID(),
      itemId: i.id,
      body: { expectedRevision: view.revision, picked, skipped },
    };
    if (!store({ ...localRef.current, pending: p })) return;
    if (online) void send(p);
    else
      setMessage(
        'Отметка пока сохранена только на этом устройстве. Отправьте её после подключения.',
      );
  }
  function edit(item: string, field: keyof FormLine, value: string) {
    const old = localRef.current.form[item];
    if (!old) return;
    store({
      ...localRef.current,
      form: { ...localRef.current.form, [item]: { ...old, [field]: value } },
    });
  }
  const selected = view?.items.filter((i) => i.picked && !i.skipped && i.remainingG > 0) || [];
  function openDialog() {
    if (!view) return;
    confirmation.current = { revision: view.revision, pantryRevision: view.pantryRevision };
    const form = { ...localRef.current.form };
    for (const i of selected)
      form[i.id] ??= {
        quantity: String(i.suggestedPurchaseG),
        price: '',
        received: view.today,
        expires: '',
        sku: '',
      };
    store({ ...localRef.current, form });
    setError('');
    setReview(false);
    setConfirm(true);
  }
  function purchase() {
    if (!view || !online || localRef.current.pending || busyRef.current || !confirmation.current)
      return;
    if (
      view.revision !== confirmation.current.revision ||
      view.pantryRevision !== confirmation.current.pantryRevision
    ) {
      setConfirm(false);
      setReview(true);
      setError('Список или запасы изменились. Проверьте покупку ещё раз.');
      return;
    }
    const items: PurchaseLine[] = selected.map((i) => {
      const f = localRef.current.form[i.id];
      return {
        itemId: i.id,
        quantityG: f.quantity.replace(',', '.'),
        actualTotalRub: f.price.trim() ? f.price.replace(',', '.') : null,
        receivedOn: f.received,
        expiresOn: f.expires || null,
        storeProductId: f.sku.trim() ? Number(f.sku) : null,
      };
    });
    const invalid = validateLines(items, view.today);
    if (invalid) {
      setError(invalid);
      return;
    }
    const p: Pending = {
      kind: 'purchase',
      key: crypto.randomUUID(),
      body: {
        expectedRevision: confirmation.current.revision,
        expectedPantryRevision: confirmation.current.pantryRevision,
        items,
      },
    };
    if (store({ ...localRef.current, pending: p })) void send(p);
  }
  async function retry() {
    const p = localRef.current.pending;
    if (!p || !online || busyRef.current) return;
    if (!writeLocal(userId, id, localRef.current)) {
      setStorageError(true);
      return;
    }
    const next = await reload();
    if (!next) return;
    if (p.kind === 'purchase' && next.appliedPurchaseKeys.includes(p.key)) return;
    void send(p);
  }
  if (!view)
    return (
      <>
        <PageHeading title="Что купить" />
        {q.isPending ? (
          <Loading />
        ) : (
          <Notice tone="warning">
            {q.error?.message}
            <button className="button" onClick={() => q.refetch()}>
              Повторить загрузку
            </button>
          </Notice>
        )}
        <Link to="/shopping-lists">Все списки</Link>
      </>
    );
  const blocked = busy || !!local.pending,
    done = view.items.filter((i) => i.remainingG === 0).length,
    skipped = view.items.filter((i) => i.skipped && i.remainingG > 0).length;
  const visible = view.items
    .filter(
      (i) =>
        filter === 'all' ||
        (filter === 'picked'
          ? i.picked && !i.skipped && i.remainingG > 0
          : !i.skipped && i.remainingG > 0),
    )
    .sort((a, b) => Number(a.picked) - Number(b.picked));
  const groups = new Map<string, SessionItem[]>();
  for (const i of visible) {
    const chain = i.snapshot.chainName || 'Магазин на ваш выбор';
    groups.set(chain, [...(groups.get(chain) || []), i]);
  }
  return (
    <div className="planning-flow with-planning-action shopping-session-flow">
      <PageHeading
        eyebrow="К СОХРАНЁННОМУ МЕНЮ"
        title="Что купить"
        description={`${labelDate(view.from)} — ${labelDate(view.to)} · ${view.participants} чел.`}
        actions={
          <Link className="button" to="/shopping-lists">
            Все списки
          </Link>
        }
      />
      <p className="caption">
        Галочка — продукт собран в корзину. В запасы он попадёт после подтверждения покупки.
      </p>
      <div className="panel shopping-session-summary">
        <strong>
          {view.unknownEstimateProducts
            ? `Известная часть оценки покупок: ≈ ${rub(view.knownEstimateRub)} · без цены: ${view.unknownEstimateProducts}`
            : `Оценка списка покупок: ≈ ${rub(view.knownEstimateRub)}`}
        </strong>
        <strong>
          {view.status === 'COMPLETED'
            ? `Список завершён: куплено ${done}, пропущено ${skipped}`
            : `Осталось ${view.items.filter((i) => !i.skipped && i.remainingG > 0).length} поз. · собрано ${selected.length}`}
        </strong>
        <span>
          Указанные фактические расходы: {rub(view.knownActualTotalRub)}
          {view.purchasesWithoutPrice > 0
            ? ` · поступлений без суммы: ${view.purchasesWithoutPrice}`
            : ''}
        </span>
        {view.budgetRub != null && <span>Бюджет на весь период: {rub(view.budgetRub)}</span>}
        <span className="caption">
          Расчётные цены в строках — снимок на момент подбора, не подтверждение цены на кассе.
        </span>
      </div>
      {view.overlappingOpenSessions && (
        <Notice tone="warning">
          Есть другой незавершённый список с пересекающимися датами. Проверьте его, чтобы не купить
          продукты дважды.
        </Notice>
      )}
      {(!online || q.isError) && (
        <Notice tone="warning">
          {!online ? 'Нет сети.' : 'Не удалось обновить список.'} Показаны ранее полученные данные.
          Пополнение запасов требует подключения.
        </Notice>
      )}
      {storageError && (
        <Notice tone="warning">
          Не удалось сохранить состояние на этом устройстве. Проверьте доступ к локальному хранилищу
          перед повтором.
        </Notice>
      )}
      {error && !confirm && <Notice tone="warning">{error}</Notice>}
      {message && <p role="status">{message}</p>}
      {local.pending && (
        <section className="panel" aria-label="Неподтверждённое действие">
          <h2>Действие ещё не подтверждено</h2>
          <p>
            Исходный запрос сохранён на этом устройстве. Сначала проверим состояние; повтор
            использует тот же ключ.
          </p>
          <button
            className="button primary"
            disabled={!online || busy}
            onClick={() => void retry()}
          >
            {local.pending.kind === 'purchase'
              ? 'Проверить и повторить покупку'
              : 'Отправить сохранённую отметку'}
          </button>
          {local.pending.kind === 'mark' && (
            <button
              className="button"
              disabled={busy}
              onClick={() => {
                store({ ...localRef.current, pending: null });
                void reload();
              }}
            >
              Отменить неподтверждённую отметку
            </button>
          )}
        </section>
      )}
      <div className="segmented" aria-label="Фильтр покупок">
        {[
          ['remaining', 'Осталось'],
          ['picked', 'Собрано'],
          ['all', 'Все'],
        ].map(([value, label]) => (
          <button
            className={`button ${filter === value ? 'primary' : ''}`}
            aria-pressed={filter === value}
            key={value}
            onClick={() => setFilter(value)}
          >
            {label}
          </button>
        ))}
      </div>
      {groups.size === 0 && (
        <Notice>
          {view.status === 'COMPLETED'
            ? 'Покупки по этому списку завершены.'
            : 'Нет позиций для этого фильтра.'}
        </Notice>
      )}
      {[...groups].map(([chain, items]) => (
        <section className="panel purchase-store" key={chain} aria-label={`Покупки: ${chain}`}>
          <header>
            <h3>{chain}</h3>
          </header>
          <ul>
            {items.map((i) => {
              const complete = i.remainingG === 0,
                pending =
                  local.pending?.kind === 'mark' && local.pending.itemId === i.id
                    ? local.pending
                    : null;
              return (
                <li key={i.id} className={`purchase-row ${i.picked ? 'is-picked' : ''}`}>
                  <label className="purchase-pick">
                    <input
                      type="checkbox"
                      aria-label={`Собрано: ${i.snapshot.productName}`}
                      checked={pending ? pending.body.picked : i.picked}
                      disabled={blocked || complete || i.skipped}
                      onChange={(e) => mark(i, e.target.checked, false)}
                    />
                    <span className="purchase-tick" aria-hidden="true">
                      <Check size={16} />
                    </span>
                  </label>
                  <div className="purchase-product">
                    <h3>{i.snapshot.productName}</h3>
                    <p>
                      {complete
                        ? 'Куплено'
                        : i.skipped
                          ? 'Пропущено'
                          : i.packageCount && i.snapshot.packageWeightG
                            ? `${i.packageCount} уп. × ${grams(i.snapshot.packageWeightG)}`
                            : grams(i.suggestedPurchaseG)}
                      {i.acquiredG > 0 ? ` · внесено ${grams(i.acquiredG)}` : ''}
                    </p>
                    {i.acquiredG > 0 && !complete && (
                      <p>Куплено частично · осталось {grams(i.remainingG)}</p>
                    )}
                    <details>
                      <summary>Количество и источник цены</summary>
                      <p>
                        Докупить по меню: {grams(i.requiredToBuyG)}. Осталось: {grams(i.remainingG)}
                        .
                      </p>
                      {i.snapshot.offerName && <p>{i.snapshot.offerName}</p>}
                      <p>
                        {i.snapshot.purchaseCost == null
                          ? 'Цена неизвестна'
                          : `Исходная оценка строки: ≈ ${rub(i.snapshot.purchaseCost)}`}
                      </p>
                      <p>
                        {i.snapshot.sourceName || 'Источник не указан'}
                        {i.snapshot.observedAt
                          ? ` · ${new Date(i.snapshot.observedAt).toLocaleDateString('ru-RU')}`
                          : ''}
                      </p>
                      {!complete && (
                        <button
                          className="text-button"
                          disabled={blocked || !online}
                          onClick={() => mark(i, false, !i.skipped)}
                        >
                          {i.skipped ? 'Вернуть в список' : 'Не покупать'}
                        </button>
                      )}
                    </details>
                  </div>
                </li>
              );
            })}
          </ul>
        </section>
      ))}
      <Link className="text-link" to="/pantry">
        Посмотреть домашние запасы
      </Link>
      <PlanningActionBar
        ariaLabel="Покупка продуктов"
        label={
          view.status === 'COMPLETED'
            ? 'Посмотреть запасы'
            : `Добавить покупку в запасы · ${selected.length} поз.`
        }
        message={
          view.status === 'COMPLETED'
            ? 'Приобретённые продукты уже учтены в запасах.'
            : blocked
              ? 'Дождитесь подтверждения текущего действия.'
              : selected.length
                ? 'Проверьте фактические количества перед внесением.'
                : 'Отметьте купленные продукты.'
        }
        onAction={view.status === 'COMPLETED' ? () => navigate('/pantry') : openDialog}
        disabled={
          busy ||
          !!local.pending ||
          (view.status !== 'COMPLETED' && (!selected.length || !online || q.isFetching))
        }
      />
      {review && !local.pending && (
        <Notice tone="warning">
          Данные обновились. Введённые количества сохранены. Снова выберите нужные строки и
          проверьте покупку.
        </Notice>
      )}
      {confirm && (
        <Dialog
          title="Подтвердить фактическую покупку"
          onClose={() => {
            if (!busy) setConfirm(false);
          }}
        >
          <p>
            Проверьте вес каждого продукта. Суммы необязательны и относятся ко всей строке покупки.
          </p>
          {selected.map((i) => {
            const f = local.form[i.id];
            return (
              f && (
                <fieldset className="shopping-purchase-line" key={i.id}>
                  <legend>{i.snapshot.productName}</legend>
                  <label>
                    Фактическая масса, г
                    <input
                      inputMode="decimal"
                      value={f.quantity}
                      disabled={busy}
                      onChange={(e) => edit(i.id, 'quantity', e.target.value)}
                    />
                  </label>
                  <p className="caption">
                    {i.packageCount && i.snapshot.packageWeightG
                      ? `Предложено ${i.packageCount} уп. × ${grams(i.snapshot.packageWeightG)}`
                      : 'Упаковка неизвестна — проверьте фактический вес.'}
                  </p>
                  <label>
                    Сумма за всю строку, ₽ · необязательно
                    <input
                      inputMode="decimal"
                      value={f.price}
                      disabled={busy}
                      onChange={(e) => edit(i.id, 'price', e.target.value)}
                    />
                  </label>
                  <label>
                    Дата покупки
                    <input
                      type="date"
                      value={f.received}
                      max={view.today}
                      disabled={busy}
                      onChange={(e) => edit(i.id, 'received', e.target.value)}
                    />
                  </label>
                  <label>
                    Годен до · необязательно
                    <input
                      type="date"
                      value={f.expires}
                      min={f.received}
                      disabled={busy}
                      onChange={(e) => edit(i.id, 'expires', e.target.value)}
                    />
                  </label>
                </fieldset>
              )
            );
          })}
          {error && <p role="alert">{error}</p>}
          <button
            className="button primary"
            disabled={busy || !online || storageError}
            onClick={purchase}
          >
            {busy ? 'Добавляем…' : 'Добавить в запасы'}
          </button>
          <button className="button" disabled={busy} onClick={() => setConfirm(false)}>
            Отмена
          </button>
        </Dialog>
      )}
    </div>
  );
}
