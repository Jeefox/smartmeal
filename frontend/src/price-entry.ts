import { request } from './api';
import { observationInstant, packageErrors, priceErrors } from './catalogue';

export type PriceEntryOffer = {
  storeProductId: number;
  name: string;
  packageWeightG: number;
  price: number | null;
  observedAt: string | null;
};
export type PriceEntryItem = {
  productId: number;
  productName: string;
  shoppingName: string;
  recipeCount: number;
  offers: PriceEntryOffer[];
};
export type PriceEntryPage = {
  content: PriceEntryItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};
export type PriceDraft = {
  productId: number;
  productName: string;
  skuId: string;
  name: string;
  weight: string;
  price: string;
  promotional: boolean;
};
export type PriceBatch = {
  requestId: string;
  observedAt: string;
  entries: {
    productId: number;
    storeProductId?: number;
    name?: string;
    packageWeightG?: number;
    price: number;
    promotional: boolean;
  }[];
};
export type PriceBatchResult = {
  requestId: string;
  storeId: number;
  savedCount: number;
  entries: {
    productId: number;
    storeProductId: number;
    priceObservationId: number;
    createdStoreProduct: boolean;
  }[];
};
export function initialPriceDraft(item: PriceEntryItem): PriceDraft {
  return {
    productId: item.productId,
    productName: item.productName,
    skuId:
      item.offers.length === 1
        ? String(item.offers[0].storeProductId)
        : item.offers.length === 0
          ? 'new'
          : '',
    name: item.shoppingName,
    weight: '',
    price: '',
    promotional: false,
  };
}
export function buildPriceBatch(drafts: PriceDraft[], observedAt: string, requestId: string) {
  const errors: Record<string, string> = {};
  const active = drafts.filter((d) => d.price.trim());
  if (!active.length) errors.form = 'Введите хотя бы одну цену.';
  if (active.length > 100) errors.form = 'За один раз можно сохранить до 100 цен.';
  const timeError = priceErrors('1', observedAt).observedAt;
  if (timeError) errors.observedAt = timeError;
  for (const d of active) {
    const fields = priceErrors(d.price.trim(), observedAt);
    if (fields.price) errors[String(d.productId)] = fields.price;
    else if (d.skuId === 'new') {
      const field = Object.values(packageErrors(d.productId, d.name, d.weight.trim()))[0];
      if (field) errors[String(d.productId)] = field;
    } else if (!/^\d+$/.test(d.skuId) || Number(d.skuId) <= 0)
      errors[String(d.productId)] = 'Выберите упаковку или добавьте новую.';
  }
  const body: PriceBatch = {
    requestId,
    observedAt: observationInstant(observedAt) ?? '',
    entries: active.map((d) => ({
      productId: d.productId,
      ...(d.skuId === 'new'
        ? { name: d.name.trim(), packageWeightG: Number(d.weight) }
        : { storeProductId: Number(d.skuId) }),
      price: Number(d.price.trim().replace(',', '.')),
      promotional: d.promotional,
    })),
  };
  return { body, errors };
}
// Paste is a draft operation only: unmatched or ambiguous rows never create products/prices.
export function parsePriceTable(text: string, items: PriceEntryItem[]) {
  const drafts: PriceDraft[] = [],
    errors: string[] = [];
  const seen = new Set<number>();
  const normalize = (s: string) => s.trim().toLocaleLowerCase('ru-RU');
  text
    .split(/\r?\n/)
    .filter((line) => line.trim())
    .forEach((line, i) => {
      const columns = line.split(line.includes('\t') ? '\t' : ';').map((s) => s.trim());
      if (columns.length !== 3) {
        errors.push(`Строка ${i + 1}: нужны продукт, вес и цена через ; или табуляцию.`);
        return;
      }
      const [name, weight, price] = columns;
      const matches = items.filter((item) =>
        [item.productName, item.shoppingName].some((s) => normalize(s) === normalize(name)),
      );
      if (matches.length !== 1) {
        errors.push(`Строка ${i + 1}: «${name}» не найден однозначно на этой странице.`);
        return;
      }
      const item = matches[0];
      if (seen.has(item.productId)) {
        errors.push(`Строка ${i + 1}: продукт повторяется.`);
        return;
      }
      seen.add(item.productId);
      const offers = item.offers.filter((o) => String(o.packageWeightG) === weight);
      if (offers.length > 1) {
        errors.push(`Строка ${i + 1}: несколько упаковок такого веса, выберите вручную.`);
        return;
      }
      drafts.push({
        ...initialPriceDraft(item),
        skuId: offers.length === 1 ? String(offers[0].storeProductId) : 'new',
        weight,
        price,
      });
    });
  return { drafts, errors };
}
export const priceEntryApi = {
  list: (storeId: number, missingOnly: boolean, q: string, page: number, signal?: AbortSignal) =>
    request<PriceEntryPage>(
      `/stores/${storeId}/price-entry?${new URLSearchParams({ missingOnly: String(missingOnly), q, page: String(page), size: '20' })}`,
      { signal },
    ),
  save: (storeId: number, body: PriceBatch) =>
    request<PriceBatchResult>(`/stores/${storeId}/price-entry`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
};
export function restorePriceBatch(key: string): PriceBatch | null {
  try {
    const body = JSON.parse(sessionStorage.getItem(key) ?? 'null');
    if (
      body &&
      typeof body.requestId === 'string' &&
      typeof body.observedAt === 'string' &&
      Array.isArray(body.entries) &&
      body.entries.length > 0 &&
      body.entries.length <= 100
    )
      return body;
  } catch {
    /* No pending batch available in this browser session. */
  }
  return null;
}
