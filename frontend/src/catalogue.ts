import type { QueryClient } from '@tanstack/react-query';
import { ApiError, request } from './api';
import type { Page } from './types';
export type StoreProduct = {
  id: number;
  storeId: number;
  productId: number;
  name: string;
  packageWeightG: number;
  active: boolean;
  createdAt: string;
  updatedAt: string;
};
export type PriceObservation = {
  id: number;
  storeProductId: number;
  price: number;
  observedAt: string;
  priceSource: string;
  promotional: boolean;
  createdAt: string;
};
export type NewStoreProduct = {
  storeId: number;
  productId: number;
  name: string;
  packageWeightG: number;
};
export type NewPrice = { price: number; observedAt: string; promotional: boolean };
export const catalogueKey = (
  user: string,
  storeId: number,
  name: string,
  page: number,
  productId?: number,
) => ['store-catalogue', user, storeId, name, page, productId ?? null] as const;
export const latestPriceKey = (user: string, skuId: number) =>
  ['latest-price', user, skuId] as const;
export const catalogueApi = {
  list: (storeId: number, name: string, page: number, signal?: AbortSignal, productId?: number) => {
    const params = new URLSearchParams({
      storeId: String(storeId),
      page: String(page),
      size: '20',
    });
    if (name.trim()) params.set('name', name.trim());
    if (productId != null) params.set('productId', String(productId));
    return request<Page<StoreProduct>>(`/store-products?${params}`, { signal });
  },
  latest: async (skuId: number, signal?: AbortSignal): Promise<PriceObservation | null> => {
    try {
      return await request<PriceObservation>(`/store-products/${skuId}/prices/latest`, { signal });
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null;
      throw error;
    }
  },
  create: (body: NewStoreProduct) =>
    request<StoreProduct>('/store-products', { method: 'POST', body: JSON.stringify(body) }),
  price: (skuId: number, body: NewPrice) =>
    request<PriceObservation>(`/store-products/${skuId}/prices`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
};
export function localDateTime(date = new Date()) {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
export function observationInstant(value: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return null;
  const date = new Date(value);
  return Number.isFinite(date.getTime()) && localDateTime(date) === value
    ? date.toISOString()
    : null;
}
export function priceErrors(price: string, observedAt: string, now = Date.now()) {
  const errors: Record<string, string> = {};
  if (!/^\d{1,10}([.,]\d{1,2})?$/.test(price) || Number(price.replace(',', '.')) <= 0)
    errors.price = 'Введите положительную цену: до 10 цифр до запятой и 2 после.';
  const instant = observationInstant(observedAt);
  if (!instant) errors.observedAt = 'Укажите корректные местные дату и время.';
  else if (Date.parse(instant) > now)
    errors.observedAt = 'Дата наблюдения не может быть в будущем.';
  return errors;
}
export function packageErrors(productId: number | null, name: string, weight: string) {
  const errors: Record<string, string> = {};
  if (!productId) errors.productId = 'Выберите базовый продукт из каталога.';
  if (!name.trim() || name.trim().length > 300)
    errors.name = 'Название должно содержать от 1 до 300 символов.';
  if (!/^\d+$/.test(weight) || Number(weight) <= 0 || Number(weight) > 2147483647)
    errors.packageWeightG = 'Укажите положительный целый вес упаковки в граммах.';
  return errors;
}
export const apiFields = (error: unknown) =>
  error instanceof ApiError
    ? Object.fromEntries(error.fields.map((item) => [item.field, item.message]))
    : {};
export async function invalidateRetail(
  client: QueryClient,
  userId: string,
  storeId: number,
  skuId?: number,
) {
  await Promise.all([
    client.invalidateQueries({ queryKey: ['store-catalogue', userId, storeId] }),
    ...(skuId == null
      ? []
      : [client.invalidateQueries({ queryKey: latestPriceKey(userId, skuId) })]),
    client.invalidateQueries({ queryKey: ['shopping-estimate', userId] }),
    client.invalidateQueries({ queryKey: ['shopping-comparison', userId] }),
  ]);
}
