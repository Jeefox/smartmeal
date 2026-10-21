// Parse data embedded by the official storefronts. Never execute page scripts here.
export const CHAINS = {
  magnit: { origin: "https://magnit.ru", index: "/catalog/", name: "Магнит" },
  globus: {
    origin: "https://www.globus.ru",
    index: "/catalog/",
    name: "Глобус",
  },
  perekrestok: {
    origin: "https://www.perekrestok.ru",
    index: "/cat",
    name: "Перекрёсток",
  },
  pyaterochka: {
    origin: "https://5ka.ru",
    index: "/catalog/",
    name: "Пятёрочка",
  },
};

function script(html, id) {
  const escaped = id.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = html.match(
    new RegExp(
      `<script\\b[^>]*\\bid=["']${escaped}["'][^>]*>([\\s\\S]*?)<\\/script>`,
      "i",
    ),
  );
  if (!match) throw new Error("CATALOGUE_DATA_MISSING_OR_ACCESS_CHECK");
  return JSON.parse(match[1]);
}

export function decodeNuxt(values) {
  const seen = new Map();
  function decode(index) {
    if (index === -1) return undefined;
    if (index === -2) return NaN;
    if (index === -3) return Infinity;
    if (index === -4) return -Infinity;
    if (index === -5) return -0;
    if (index === -6) return null;
    if (!Number.isInteger(index) || index < 0 || index >= values.length)
      throw new Error("INVALID_NUXT_REFERENCE");
    if (seen.has(index)) return seen.get(index);
    const value = values[index];
    if (value === null || typeof value !== "object") return value;
    if (
      Array.isArray(value) &&
      ["EmptyRef", "EmptyShallowRef"].includes(value[0])
    )
      return undefined;
    if (Array.isArray(value) && value[0] === "Set") {
      const result = new Set();
      seen.set(index, result);
      for (const ref of value.slice(1)) result.add(decode(ref));
      return result;
    }
    if (
      Array.isArray(value) &&
      ["Reactive", "ShallowReactive", "Ref", "ShallowRef"].includes(value[0])
    ) {
      const decoded = decode(value[1]);
      seen.set(index, decoded);
      return decoded;
    }
    const result = Array.isArray(value) ? [] : Object.create(null);
    seen.set(index, result);
    for (const [key, ref] of Object.entries(value)) result[key] = decode(ref);
    return result;
  }
  return decode(0);
}

function perekrestokState(html) {
  const match = html.match(
    /window\.__INITIAL_STATE__\s*=\s*([\s\S]*?)<\/script>/,
  );
  if (!match) throw new Error("CATALOGUE_DATA_MISSING_OR_ACCESS_CHECK");
  return JSON.parse(match[1].trim().replace(/;$/, ""));
}

function nextQueries(html) {
  return (
    script(html, "__NEXT_DATA__").props.pageProps.dehydratedState?.queries || []
  );
}

function money(value, kopecks = false) {
  if (value === undefined || value === null || value === "") return null;
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0 || n > 10000000)
    throw new Error("INVALID_PRICE");
  return (kopecks ? n / 100 : n).toFixed(2);
}

export function officialUrl(chain, value) {
  const url = new URL(value, CHAINS[chain].origin);
  if (
    url.origin !== CHAINS[chain].origin ||
    url.username ||
    url.password ||
    url.hash
  )
    throw new Error("UNTRUSTED_URL");
  return url.href;
}

export function categories(chain, html) {
  if (chain === "magnit") {
    const found = new Map();
    for (const match of html.matchAll(
      /<a\b[^>]*href="(https:\/\/magnit\.ru\/catalog\/\d[^"?]*)"[^>]*>([\s\S]*?)<\/a>/g,
    )) {
      const url = officialUrl(chain, match[1]);
      const name = match[2]
        .replace(/<[^>]+>/g, " ")
        .replace(/\s+/g, " ")
        .trim();
      found.set(url, { id: new URL(url).pathname.split("/").pop(), name, url });
    }
    return [...found.values()];
  }
  if (chain === "globus") {
    const query = nextQueries(html).find(
      (q) =>
        (Array.isArray(q.queryKey) ? q.queryKey[0] : q.queryKey) ===
        "catalog-main",
    );
    const items = query?.state.data.data.menu.catalog;
    if (!Array.isArray(items) || !items.length)
      throw new Error("CATEGORY_TREE_MISSING");
    return items.map((c) => ({
      id: String(c.id),
      name: c.name,
      url: officialUrl(chain, c.url),
    }));
  }
  if (chain === "perekrestok") {
    const c = perekrestokState(html).catalog.category;
    return c.list
      .map((id) => c.data[id].category)
      .map((x) => ({
        id: String(x.id),
        name: x.title,
        url: officialUrl(chain, `/cat/c/${x.id}/${x.slug}`),
      }));
  }
  // Use links actually rendered by the shop; category slugs are not generated from names.
  return [...new Set([...html.matchAll(/href="([^"#]+)"/g)].map((x) => x[1]))]
    .filter((url) => /^\/catalog\/[^/]+--[A-Z0-9]+\/?$/.test(url))
    .map((url) => ({
      id: url.match(/--([A-Z0-9]+)/)[1],
      url: officialUrl(chain, url),
    }));
}

export function sourceContext(chain, html) {
  if (chain === "magnit") {
    const store = decodeNuxt(script(html, "__NUXT_DATA__")).state
      ?.$scurrentStore;
    if (!store?.externalId?.storeCode || !store.address)
      throw new Error("SOURCE_CONTEXT_MISSING");
    return {
      storeId: String(store.externalId.storeCode),
      address: store.address,
      scope: "SITE_DEFAULT_STORE",
    };
  }
  if (chain === "globus") {
    const queries = nextQueries(html);
    const context = queries.find((q) => q.queryKey === "context")?.state.data
      .data.context;
    if (!context?.store_id) throw new Error("SOURCE_CONTEXT_MISSING");
    const store = queries
      .find((q) => q.queryKey === "directories-stores")
      ?.state.data.data.stores?.find((s) => s.id === context.store_id);
    return {
      storeId: String(context.store_id),
      address: store?.full_addr || null,
      scope: "SITE_DEFAULT_STORE",
      purchaseMethod: context.purchase_method,
    };
  }
  if (chain === "perekrestok") {
    const city = perekrestokState(html).geo.currentCity;
    if (!city?.name) throw new Error("SOURCE_CONTEXT_MISSING");
    return {
      city: city.name,
      cityId: String(city.id),
      scope: "SITE_DEFAULT_REGION",
    };
  }
  const props = script(html, "__NEXT_DATA__").props.pageProps.props;
  const store = JSON.parse(props.catalogStore);
  if (!store.storeId) throw new Error("SOURCE_CONTEXT_MISSING");
  return {
    storeId: String(store.storeId),
    scope: "SITE_DEFAULT_STORE",
    mode: JSON.parse(props.catalogModeStore).mode,
  };
}

export function parsePage(chain, html, category, expectedPage) {
  let products,
    page,
    total = null,
    pages = null,
    hasNext;
  if (chain === "magnit") {
    const root = decodeNuxt(script(html, "__NUXT_DATA__"));
    const result = Object.entries(root.data).find(([key]) =>
      key.startsWith("goods-search:"),
    )?.[1];
    if (!result?.pagination) throw new Error("PRODUCTS_MISSING");
    ({ page, total } = result.pagination);
    pages = Math.ceil(total / result.pagination.pageSize);
    hasNext = page < pages;
    products = result.goods.map((p) => {
      const weighted = p.weighted?.isWeighted === true;
      let currentPrice = money(p.price),
        regularPrice = money(p.oldPrice) || currentPrice;
      if (weighted) {
        if (p.weighted.unitLabel !== "1кг" || !p.weighted.unitPrice)
          throw new Error("UNKNOWN_WEIGHT_PRICE_UNIT");
        currentPrice = money(p.weighted.unitPrice, true);
        // Old price is shown for the shelf quantity, not necessarily for 1 kg.
        regularPrice = p.oldPrice ? null : currentPrice;
      }
      return {
        id: String(p.id),
        name: p.title,
        sourceUrl: officialUrl(chain, p.link),
        regularPrice,
        currentPrice,
        currency: "RUB",
        soldByWeight: weighted,
        packageLabel: weighted ? "1 кг" : p.unit || p.title,
        category: category.name || category.id,
        available: p.quantity > 0,
        conditions: p.badges?.map((b) => b.text) || [],
      };
    });
  } else if (chain === "globus") {
    const query = nextQueries(html).find(
      (q) => q.queryKey[0] === "catalog-product-list",
    );
    const data = query?.state.data.pages?.[0]?.data;
    if (!data?.products?.pagination) throw new Error("PRODUCTS_MISSING");
    ({ page, total, total_page: pages } = data.products.pagination);
    hasNext = page < pages;
    products = data.products.items.map((p) => {
      const cardRequired = p.price_representation?.comments?.some((x) =>
        /без карты/i.test(x),
      );
      const weighted = p.unit_price_text === "1 кг";
      return {
        id: p.id,
        name: `${p.name_optional} ${p.name_required}`.trim(),
        sourceUrl: officialUrl(chain, p.url),
        currentPrice: money(p.price, true),
        regularPrice:
          p.package_type === "weight-by-piece"
            ? null
            : money(p.cost, true) ||
              (cardRequired ? null : money(p.price, true)),
        currency: "RUB",
        soldByWeight: weighted,
        packageLabel: weighted ? "1 кг" : p.name_required || "",
        category: data.category.name,
        available: p.active === true && p.quantity_max > 0,
        conditions: {
          cardRequired: Boolean(cardRequired),
          promotions: p.promotions,
          display: p.price_representation,
          packageType: p.package_type,
          priceUnit: p.unit_price_text,
        },
      };
    });
  } else if (chain === "perekrestok") {
    const state = perekrestokState(html);
    const feed = state.catalog.feeds[`category_${category.id}`];
    if (!feed?.paginator) throw new Error("PRODUCTS_MISSING");
    page = feed.paginator.current;
    hasNext = feed.paginator.nextPageExists;
    const links = [...html.matchAll(/href="(\/cat\/\d+\/p\/[^"#?]+)"/g)].map(
      (x) => x[1],
    );
    products = feed.items.map((p) => ({
      id: String(p.id),
      name: p.title,
      sourceUrl: officialUrl(
        chain,
        links.find((url) =>
          url.endsWith(`/${p.masterData.slug}-${p.masterData.plu}`),
        ) ||
          `/cat/${p.catalogPrimaryCategory?.id || p.primaryCategory.id}/p/${p.masterData.slug}-${p.masterData.plu}`,
      ),
      currentPrice: money(p.priceTag.price, true),
      regularPrice:
        money(p.priceTag.grossPrice, true) ||
        (p.priceTag.discountCondition || p.priceTag.discountMinimumAmount
          ? null
          : money(p.priceTag.price, true)),
      currency: "RUB",
      soldByWeight: p.masterData.unitName === "кг",
      packageLabel:
        p.masterData.unitName === "кг"
          ? "1 кг"
          : p.masterData.weight
            ? `${p.masterData.weight} г`
            : p.title,
      category: category.name || p.primaryCategory?.title || "",
      available: ["many", "few"].includes(p.balanceState),
      conditions: {
        condition: p.priceTag.discountCondition,
        minimumAmount: p.priceTag.discountMinimumAmount,
        labels: p.priceTag.labels,
      },
    }));
  } else {
    throw new Error("PYATEROCHKA_CATALOGUE_NOT_YET_VERIFIED");
  }
  if (
    page !== expectedPage ||
    !Array.isArray(products) ||
    (hasNext && !products.length)
  )
    throw new Error("PAGINATION_MISMATCH");
  for (const p of products) {
    if (!p.id || !p.name || p.name.length > 500 || !p.currentPrice)
      throw new Error("INVALID_PRODUCT");
  }
  return { products, page, total, pages, hasNext };
}

export function mergeProducts(target, products) {
  for (const p of products) {
    const old = target.get(p.id);
    if (
      old &&
      (old.currentPrice !== p.currentPrice ||
        old.regularPrice !== p.regularPrice)
    ) {
      throw new Error("PRICE_CHANGED_DURING_COLLECTION");
    }
    target.set(p.id, p);
  }
}

export function mergeDatedProducts(target, products, date, dates) {
  for (const product of products) {
    if (!dates.has(product.id) || dates.get(product.id) <= date) {
      target.set(product.id, product);
      dates.set(product.id, date);
    }
  }
}

export function parsePyaterochkaPage(
  payload,
  category,
  offset,
  storeId,
  limit = 12,
) {
  if (!Array.isArray(payload.products) || !payload.name)
    throw new Error("PRODUCTS_MISSING");
  const products = payload.products.map((p) => {
    let regularPrice = money(p.prices.regular);
    let currentPrice = money(p.prices.discount) || regularPrice;
    const weighted = p.uom === "кг";
    if (weighted) {
      const label = (p.price_unit_label || "").replace(/\s/g, "");
      if (label === "100г") {
        regularPrice = (Number(regularPrice) * 10).toFixed(2);
        currentPrice = (Number(currentPrice) * 10).toFixed(2);
      } else if (!["кг", "1кг"].includes(label))
        throw new Error("UNKNOWN_WEIGHT_PRICE_UNIT");
    }
    return {
      id: String(p.plu),
      name: p.name,
      sourceUrl: `https://5d.5ka.ru/api/catalog/v2/stores/${storeId}/categories/${category.id}/products?mode=delivery&include_restrict=true&limit=12&offset=${offset}#plu=${p.plu}`,
      regularPrice,
      currentPrice,
      currency: "RUB",
      soldByWeight: weighted,
      packageLabel: weighted ? "1 кг" : p.property_clarification || p.name,
      category: payload.name,
      available: p.is_available === true,
      conditions: { promo: p.promo, labels: p.labels, cpdLabels: p.cpd_labels },
    };
  });
  return {
    products,
    page: offset / limit + 1,
    total: null,
    hasNext: products.length === limit,
  };
}

export function packageLabel(name) {
  if (/\d\s*[xх×*]\s*\d/i.test(name)) return "";
  const matches = [
    ...name.matchAll(/(\d+(?:[.,]\d+)?)\s*(кг|мл|шт|г|л)(?=\s|$|[,.)])/gi),
  ];
  return matches.length === 1 ? `${matches[0][1]} ${matches[0][2]}` : "";
}

export function parseMagnitApi(payload, category, offset, storeId) {
  const p = payload.pagination;
  if (
    !Array.isArray(payload.items) ||
    !p ||
    p.offset !== offset ||
    typeof p.hasMore !== "boolean" ||
    (p.hasMore && (!Number.isInteger(p.nextOffset) || p.nextOffset <= offset))
  )
    throw new Error("PAGINATION_MISMATCH");
  const products = payload.items.map((item) => {
    if (item.storeCode !== storeId) throw new Error("SOURCE_CONTEXT_CHANGED");
    const weighted = item.weighted?.isWeighted === true;
    let currentPrice = money(item.price, true);
    let regularPrice = money(item.promotion?.oldPrice, true) || currentPrice;
    if (weighted) {
      if (item.weighted.unitLabel !== "1кг")
        throw new Error("UNKNOWN_WEIGHT_PRICE_UNIT");
      currentPrice = money(item.weighted.unitPrice, true);
      regularPrice = item.promotion?.oldPrice ? null : currentPrice;
    }
    if (!currentPrice || !item.id || !item.name)
      throw new Error("INVALID_PRODUCT");
    return {
      id: item.id,
      name: item.name,
      sourceUrl: officialUrl("magnit", `/product/${item.id}-${item.seoCode}`),
      currentPrice,
      regularPrice,
      currency: "RUB",
      soldByWeight: weighted,
      packageLabel: weighted ? "1 кг" : packageLabel(item.name),
      category: category.name || category.id,
      available: item.quantity > 0,
      conditions: { promotion: item.promotion, badges: item.badges },
    };
  });
  return {
    products,
    total: null,
    sourceTotal: p.totalCount,
    hasNext: p.hasMore,
    nextOffset: p.nextOffset,
  };
}

// Live stock can change while the pages are being read. Preserve that fact rather
// than presenting the crawl as an atomic inventory snapshot.
export function categoryCoverage(totals, count) {
  const known = totals.filter((n) => n !== null);
  if (!known.length) return { verification: "END_OF_PAGINATION" };
  if (known.some((n) => !Number.isInteger(n) || n < 0))
    throw new Error("INVALID_TOTAL");
  const min = Math.min(...known),
    max = Math.max(...known);
  if (max - min > Math.max(5, Math.ceil(max * 0.05)))
    throw new Error("CATALOGUE_CHANGED_DURING_COLLECTION");
  if (count < min || count > max) throw new Error("INCOMPLETE_CATEGORY");
  return {
    verification: min === max ? "EXACT_COUNT" : "LIVE_CATALOGUE_CHANGED",
    sourceCountMin: min,
    sourceCountMax: max,
  };
}
