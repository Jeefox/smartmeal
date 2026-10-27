import { request } from './api';
import type { PeriodView } from './period-planning';
export type BasketItem = PeriodView['result']['shopping']['items'][number];
export type SessionItem = {
  id: string;
  snapshot: BasketItem;
  requiredToBuyG: number;
  acquiredG: number;
  remainingG: number;
  suggestedPurchaseG: number;
  packageCount: number | null;
  picked: boolean;
  skipped: boolean;
};
export type ShoppingSession = {
  id: string;
  generationId: string;
  from: string;
  to: string;
  revision: number;
  pantryRevision: number;
  today: string;
  status: 'OPEN' | 'COMPLETED';
  budgetRub: number | null;
  participants: number;
  items: SessionItem[];
  knownEstimateRub: number;
  unknownEstimateProducts: number;
  knownActualTotalRub: number;
  purchasesWithoutPrice: number;
  overlappingOpenSessions: boolean;
  appliedPurchaseKeys: string[];
};
export type Summary = Pick<ShoppingSession, 'id' | 'from' | 'to' | 'status' | 'revision'>;
export type PurchaseLine = {
  itemId: string;
  quantityG: string;
  actualTotalRub: string | null;
  storeProductId: number | null;
  receivedOn: string;
  expiresOn: string | null;
};
export type Purchase = {
  expectedRevision: number;
  expectedPantryRevision: number;
  items: PurchaseLine[];
};
export type Pending =
  | { kind: 'purchase'; key: string; body: Purchase }
  | {
      kind: 'mark';
      key: string;
      itemId: string;
      body: { expectedRevision: number; picked: boolean; skipped: boolean };
    };
export type FormLine = {
  quantity: string;
  price: string;
  received: string;
  expires: string;
  sku: string;
};
export type LocalState = { version: 1; form: Record<string, FormLine>; pending: Pending | null };
export const emptyLocal = (): LocalState => ({ version: 1, form: {}, pending: null });
const uuid = /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i;
const date = /^\d{4}-\d{2}-\d{2}$/;
export function decimal(value: string, positive: boolean, scale: number) {
  return (
    /^\d{1,10}(\.\d+)?$/.test(value) &&
    (!value.includes('.') || value.split('.')[1].length <= scale) &&
    Number.isFinite(Number(value)) &&
    (positive ? Number(value) > 0 : Number(value) >= 0)
  );
}
export function validateLines(lines: PurchaseLine[], today: string) {
  if (!lines.length || lines.length > 100) return 'Выберите от 1 до 100 позиций.';
  if (new Set(lines.map((l) => l.itemId)).size !== lines.length)
    return 'Продукт повторяется в покупке.';
  for (const l of lines) {
    if (!uuid.test(l.itemId) || !decimal(l.quantityG, true, 4))
      return 'Укажите положительный вес, не больше четырёх знаков после запятой.';
    if (l.actualTotalRub !== null && !decimal(l.actualTotalRub, false, 2))
      return 'Проверьте сумму: до двух знаков после запятой.';
    if (
      !date.test(l.receivedOn) ||
      l.receivedOn > today ||
      l.receivedOn < '1900-01-01' ||
      (l.expiresOn !== null &&
        (!date.test(l.expiresOn) || l.expiresOn < l.receivedOn || l.expiresOn > '2200-12-31'))
    )
      return 'Проверьте дату покупки и срок годности.';
    if (
      l.storeProductId !== null &&
      (!Number.isSafeInteger(l.storeProductId) || l.storeProductId <= 0)
    )
      return 'Проверьте код товара магазина.';
  }
  return '';
}
const storageKey = (user: string, id: string) => `smartmeal.shopping.sessions.v1.${user}.${id}`;
export function readLocal(user: string, id: string): LocalState {
  try {
    const x = JSON.parse(localStorage.getItem(storageKey(user, id)) || 'null');
    if (!x || x.version !== 1) return emptyLocal();
    const result = emptyLocal();
    if (x.form && typeof x.form === 'object')
      for (const [key, value] of Object.entries(x.form).slice(0, 100)) {
        const v = value as FormLine;
        if (
          uuid.test(key) &&
          v &&
          ['quantity', 'price', 'received', 'expires', 'sku'].every(
            (k) =>
              typeof v[k as keyof FormLine] === 'string' && v[k as keyof FormLine].length <= 80,
          )
        )
          result.form[key] = v;
      }
    const p = x.pending;
    if (
      p &&
      uuid.test(p.key) &&
      Number.isSafeInteger(p.body?.expectedRevision) &&
      p.body.expectedRevision >= 0
    ) {
      if (
        p.kind === 'mark' &&
        uuid.test(p.itemId) &&
        typeof p.body.picked === 'boolean' &&
        typeof p.body.skipped === 'boolean'
      )
        result.pending = {
          kind: 'mark',
          key: p.key,
          itemId: p.itemId,
          body: {
            expectedRevision: p.body.expectedRevision,
            picked: p.body.picked,
            skipped: p.body.skipped,
          },
        };
      if (
        p.kind === 'purchase' &&
        Number.isSafeInteger(p.body.expectedPantryRevision) &&
        p.body.expectedPantryRevision >= 0 &&
        Array.isArray(p.body.items) &&
        p.body.items.every(
          (l: PurchaseLine) =>
            l &&
            typeof l.quantityG === 'string' &&
            (l.actualTotalRub === null || typeof l.actualTotalRub === 'string') &&
            typeof l.receivedOn === 'string' &&
            (l.expiresOn === null || typeof l.expiresOn === 'string'),
        ) &&
        !validateLines(p.body.items, '2200-12-31')
      )
        result.pending = { kind: 'purchase', key: p.key, body: p.body };
    }
    return result;
  } catch {
    return emptyLocal();
  }
}
export function writeLocal(user: string, id: string, value: LocalState) {
  try {
    localStorage.setItem(storageKey(user, id), JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}
export function clearShoppingLocal(user: string) {
  try {
    const prefix = `smartmeal.shopping.sessions.v1.${user}.`;
    for (let n = localStorage.length - 1; n >= 0; n--) {
      const k = localStorage.key(n);
      if (k?.startsWith(prefix)) localStorage.removeItem(k);
    }
  } catch {
    /* Unavailable storage contains no accessible drafts. */
  }
}
export const shoppingSessions = {
  list: (status = 'OPEN', page = 0, signal?: AbortSignal) =>
    request<{ items: Summary[]; page: number; hasNext: boolean }>(
      `/me/shopping-sessions?status=${status}&page=${page}`,
      { signal },
    ),
  get: (id: string, signal?: AbortSignal) =>
    request<ShoppingSession>(`/me/shopping-sessions/${encodeURIComponent(id)}`, { signal }),
  open: (generationId: string, key: string) =>
    request<ShoppingSession>('/me/shopping-sessions', {
      method: 'POST',
      headers: { 'Idempotency-Key': key },
      body: JSON.stringify({ generationId }),
    }),
  send: (id: string, p: Pending) =>
    request<ShoppingSession>(
      `/me/shopping-sessions/${encodeURIComponent(id)}/${p.kind === 'purchase' ? 'purchases' : `items/${encodeURIComponent(p.itemId)}`}`,
      {
        method: p.kind === 'purchase' ? 'POST' : 'PATCH',
        headers: { 'Idempotency-Key': p.key },
        body: JSON.stringify(p.body),
      },
    ),
};
