import { request } from './api';
export type RetailChain = { id: number; code: string; name: string };
export type Store = {
  id: number;
  chainId: number;
  chainName: string;
  city: string;
  address: string;
};
export type ShoppingEstimateItem = {
  productId: number;
  productName: string;
  requiredWeightG: number;
  storeProductId: number | null;
  storeProductName: string | null;
  packageWeightG: number | null;
  packageCount: number | null;
  packagePrice: number | null;
  purchaseCost: number | null;
  remainderG: number | null;
  priceObservedAt: string | null;
  priceSource: string | null;
  referencePrice?: boolean;
  referenceSourceUrl?: string | null;
  referenceSourceName?: string | null;
  referenceSourceScope?: string | null;
};
export type ShoppingEstimate = {
  from: string;
  to: string;
  storeId: number;
  containsDrafts: boolean;
  complete: boolean;
  knownPurchaseCost: number;
  items: ShoppingEstimateItem[];
};
export const estimateKey = (userId: string, from: string, to: string, storeId: number | null) =>
  ['shopping-estimate', userId, from, to, storeId] as const;
export type ShoppingComparisonResponse = {
  from: string;
  to: string;
  containsDrafts: boolean;
  shoppingEstimateResponseList: ShoppingEstimate[];
};
export const uniqueStoreIds = (ids: number[]) => [...new Set(ids)].sort((a, b) => a - b);
export function comparisonSelectionError(ids: number[]) {
  const unique = uniqueStoreIds(ids);
  if (!unique.length) return 'Выберите хотя бы один магазин.';
  if (unique.length > 10) return 'Можно сравнить не более 10 магазинов.';
  if (unique.some((id) => !Number.isSafeInteger(id) || id <= 0)) return 'Некорректный ID магазина.';
  return '';
}
export const comparisonKey = (userId: string, from: string, to: string, ids: number[]) =>
  ['shopping-comparison', userId, from, to, uniqueStoreIds(ids)] as const;
export const retailApi = {
  chains: (signal?: AbortSignal) => request<RetailChain[]>('/retail-chains', { signal }),
  stores: (city: string, chainId: string, signal?: AbortSignal) => {
    const params = new URLSearchParams({ city: city.trim() });
    if (chainId) params.set('chainId', chainId);
    return request<Store[]>(`/stores?${params}`, { signal });
  },
  compare: (from: string, to: string, storeIds: number[], signal?: AbortSignal) =>
    request<ShoppingComparisonResponse>(
      `/me/shopping-comparison?${new URLSearchParams({ from, to, storeIds: uniqueStoreIds(storeIds).join(',') })}`,
      { signal },
    ),
  estimate: (from: string, to: string, storeId: number, signal?: AbortSignal) =>
    request<ShoppingEstimate>(
      `/me/shopping-estimate?${new URLSearchParams({ from, to, storeId: String(storeId) })}`,
      { signal },
    ),
};
export const rubles = (value: number) =>
  new Intl.NumberFormat('ru-RU', {
    style: 'currency',
    currency: 'RUB',
    minimumFractionDigits: 2,
  }).format(value);
export function estimateErrorTitle(status?: number) {
  if (status === 400) return 'Проверьте параметры расчёта';
  if (status === 404) return 'Расчёт или магазин недоступен';
  if (status === 409) return 'Магазин больше не активен';
  return 'Не удалось рассчитать покупки';
}
