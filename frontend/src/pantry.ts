import { useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { request, ApiError } from './api';
import { useSession } from './session';
import { useOnline } from './state';
import { invalidateShopping } from './shopping';
export type PantryLot = {
  id: string;
  productId: number;
  productName: string;
  quantityOnHandG: number;
  reservedG: number;
  freeG: number;
  receivedOn: string;
  expiresOn: string | null;
  source: string;
  expired: boolean;
  reservedFor?: {
    entryId: string;
    date: string;
    mealType: string;
    recipeName: string;
    quantityG: number;
  }[];
};
export type PantryView = {
  revision: number;
  today: string;
  lots: PantryLot[];
  unconfirmedPastEntries: number;
};
export type InventoryState = 'OPEN' | 'DEDUCTED' | 'SKIPPED';
export type EntryStates = {
  revision: number;
  today?: string;
  entries: { entryId: string; state: InventoryState; reservedG: Record<string, number> }[];
};
export type PantryHistory = {
  page: number;
  hasNext: boolean;
  items: {
    id: string;
    kind: string;
    createdAt: string;
    movements: {
      lotId: string;
      productId: number;
      productName: string;
      quantityDeltaG: number;
      reason: string;
    }[];
  }[];
};
export const pantryApi = {
  view: (signal?: AbortSignal) => request<PantryView>('/me/pantry', { signal }),
  states: (id: string, signal?: AbortSignal) =>
    request<EntryStates>(`/me/meal-plans/${id}/inventory`, { signal }),
  history: (page: number, signal?: AbortSignal) =>
    request<PantryHistory>(`/me/pantry/operations?page=${page}`, { signal }),
};
export function validGrams(value: string, zero = false): boolean {
  return (
    /^\d{1,10}(\.\d{1,4})?$/.test(value) &&
    Number.isFinite(Number(value)) &&
    (zero ? Number(value) >= 0 : Number(value) > 0)
  );
}
/** Retain the same idempotency key on a network retry of the same command. */
export function usePantryCommand() {
  const client = useQueryClient(),
    { profile } = useSession(),
    online = useOnline();
  const [busy, setBusy] = useState(false),
    [message, setMessage] = useState('');
  const pending = useRef(false),
    previous = useRef<{ signature: string; key: string; payload: unknown } | null>(null);
  async function run(path: string, payload: unknown, method = 'POST') {
    if (pending.current || !online) return false;
    pending.current = true;
    setBusy(true);
    setMessage('');
    const { expectedRevision: _revision, ...intent } = payload as Record<string, unknown>;
    const signature = method + path + JSON.stringify(intent);
    if (previous.current?.signature !== signature)
      previous.current = { signature, key: crypto.randomUUID(), payload };
    let saved = false;
    try {
      await request(path, {
        method,
        body: JSON.stringify(previous.current.payload),
        headers: { 'Idempotency-Key': previous.current.key },
      });
      saved = true;
      previous.current = null;
      await Promise.all([
        client.invalidateQueries({ queryKey: ['pantry', profile?.userId] }),
        client.invalidateQueries({ queryKey: ['pantry-history', profile?.userId] }),
        client.invalidateQueries({ queryKey: ['plan-inventory', profile?.userId] }),
        client.invalidateQueries({ queryKey: ['meal-plan', profile?.userId] }),
        profile ? invalidateShopping(client, profile.userId) : Promise.resolve(),
      ]);
      setMessage('Изменение сохранено.');
      return true;
    } catch (e) {
      if (e instanceof ApiError && e.status > 0 && e.status < 500) previous.current = null;
      setMessage(
        saved
          ? 'Действие сохранено, но обновить экран не удалось. Обновите данные перед следующим действием.'
          : e instanceof Error
            ? e.message
            : 'Не удалось сохранить',
      );
      // Read fresh revision after a conflict, without automatically repeating a mutation.
      await client.invalidateQueries({ queryKey: ['pantry', profile?.userId] });
      await client.invalidateQueries({ queryKey: ['plan-inventory', profile?.userId] });
      return saved;
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }
  return { run, busy, message, online };
}
