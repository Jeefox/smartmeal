import type { QueryClient } from '@tanstack/react-query';
import { request } from './api';
export type ShoppingNeeds = {
  from: string;
  to: string;
  containsDrafts: boolean;
  items: {
    productId: number;
    productName: string;
    quantityG: number;
    requiredG?: number;
    fromPantryG?: number;
  }[];
};
export const shoppingKey = (userId: string, from: string, to: string) =>
  ['shopping-needs', userId, from, to] as const;
export function calendarDay(value: string): number | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || value.startsWith('0000')) return null;
  const time = Date.parse(`${value}T00:00:00Z`);
  return Number.isFinite(time) && new Date(time).toISOString().slice(0, 10) === value ? time : null;
}
export function periodErrors(from: string, to: string): Record<string, string> {
  const start = calendarDay(from),
    end = calendarDay(to);
  const errors: Record<string, string> = {};
  if (start == null) errors.from = 'Укажите корректную начальную дату.';
  if (end == null) errors.to = 'Укажите корректную конечную дату.';
  if (start != null && end != null) {
    if (end < start) errors.to = 'Конечная дата не может быть раньше начальной.';
    else if ((end - start) / 86_400_000 + 1 > 31)
      errors.to = 'Выберите период не более 31 дня, включая обе даты.';
  }
  return errors;
}
export const readShoppingNeeds = (from: string, to: string, signal?: AbortSignal) =>
  request<ShoppingNeeds>(`/me/shopping-list?${new URLSearchParams({ from, to })}`, { signal });

// Menu changes affect both ingredient needs and all store estimates for this account.
export async function invalidateShopping(client: QueryClient, userId: string) {
  await Promise.all([
    client.invalidateQueries({ queryKey: ['shopping-needs', userId] }),
    client.invalidateQueries({ queryKey: ['shopping-estimate', userId] }),
    client.invalidateQueries({ queryKey: ['shopping-comparison', userId] }),
  ]);
}
