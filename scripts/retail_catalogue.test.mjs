import test from "node:test";
import assert from "node:assert/strict";
import {
  decodeNuxt,
  parsePage,
  parsePyaterochkaPage,
  parseMagnitApi,
  packageLabel,
  categoryCoverage,
  mergeProducts,
  mergeDatedProducts,
  officialUrl,
  sourceContext,
} from "./retail_catalogue.mjs";

test("A newer category price replaces an older cached observation, never the reverse", () => {
  const products = new Map(),
    dates = new Map();
  const old = { id: "1", currentPrice: "10.00" },
    fresh = { id: "1", currentPrice: "12.00" };
  mergeDatedProducts(products, [old], "2026-10-20T00:00:00.000Z", dates);
  mergeDatedProducts(products, [fresh], "2026-10-21T00:00:00.000Z", dates);
  mergeDatedProducts(products, [old], "2026-10-20T00:00:00.000Z", dates);
  assert.equal(products.get("1").currentPrice, "12.00");
});

const next = (queries) =>
  `<script id="__NEXT_DATA__" type="application/json">${JSON.stringify({ props: { pageProps: { dehydratedState: { queries } } } })}</script>`;
const perek = (feed) =>
  `<script>window.__INITIAL_STATE__ = ${JSON.stringify({ catalog: { feeds: { category_100: feed } } })};</script>`;

test("Nuxt references preserve primitive numbers and shared references", () => {
  const root = decodeNuxt([
    { x: 1, y: 1, empty: 3, set: 4 },
    { price: 2 },
    5299,
    ["EmptyRef", 2],
    ["Set", 2],
  ]);
  assert.equal(root.x.price, 5299);
  assert.equal(root.x, root.y);
  assert.equal(root.empty, undefined);
  assert.deepEqual([...root.set], [5299]);
});

test("Only official catalogue URLs are accepted", () => {
  assert.throws(() =>
    officialUrl("magnit", "https://magnit.ru.evil.test/catalog/"),
  );
  assert.throws(() =>
    officialUrl("magnit", "https://secret@ magnit.ru/catalog/"),
  );
  assert.equal(
    officialUrl("globus", "/catalog/"),
    "https://www.globus.ru/catalog/",
  );
});

test("Missing catalogue or protection HTML does not become an empty successful snapshot", () => {
  assert.throws(() =>
    parsePage("globus", "<html>Проверка браузера</html>", { id: "1" }, 1),
  );
});

test("Globus separates the ordinary price from loyalty-card price", () => {
  const html = next([
    {
      queryKey: ["catalog-product-list"],
      state: {
        data: {
          pages: [
            {
              data: {
                category: { name: "Крупы" },
                products: {
                  pagination: { page: 2, total: 41, total_page: 2 },
                  items: [
                    {
                      id: "22205_ST",
                      name_optional: "Крупа гречневая",
                      name_required: "900г",
                      url: "/products/grechka/",
                      price: 10999,
                      cost: 14399,
                      active: true,
                      quantity: 0,
                      quantity_max: 100,
                      price_representation: { comments: ["без карты"] },
                      promotions: [],
                      unit_price_text: "1 шт",
                    },
                  ],
                },
              },
            },
          ],
        },
      },
    },
  ]);
  const data = parsePage("globus", html, { id: "1" }, 2);
  assert.equal(data.hasNext, false);
  assert.equal(data.products[0].regularPrice, "143.99");
  assert.equal(data.products[0].currentPrice, "109.99");
  assert.equal(data.products[0].available, true); // quantity is the shopping cart, not stock.
  assert.equal(data.products[0].conditions.cardRequired, true);
  assert.throws(() => parsePage("globus", html, { id: "1" }, 1), /PAGINATION/);
});

test("Perekrestok uses product PLU URL and avoids an unconditional price for multibuy", () => {
  const data = parsePage(
    "perekrestok",
    perek({
      paginator: { current: 1, nextPageExists: false },
      items: [
        {
          id: 12,
          title: "Рис 900г",
          masterData: {
            slug: "ris-900g",
            plu: "456",
            unitName: "шт",
            weight: 900,
          },
          primaryCategory: { title: "Рис" },
          catalogPrimaryCategory: { id: 101 },
          balanceState: "many",
          priceTag: {
            price: 9990,
            grossPrice: null,
            discountMinimumAmount: 2,
            labels: [],
          },
        },
      ],
    }),
    { id: "100" },
    1,
  );
  assert.equal(data.products[0].regularPrice, null);
  assert.equal(
    data.products[0].sourceUrl,
    "https://www.perekrestok.ru/cat/101/p/ris-900g-456",
  );
});

test("Pyaterochka converts price per 100 g and uses short final page", () => {
  const p = parsePyaterochkaPage(
    {
      name: "Овощи",
      products: [
        {
          plu: 1,
          name: "Морковь",
          uom: "кг",
          prices: { regular: "6.90", discount: "5.90" },
          price_unit_label: "100 г",
          is_available: true,
        },
      ],
    },
    { id: "251C51627" },
    12,
    "35XY",
  );
  assert.equal(p.products[0].regularPrice, "69.00");
  assert.equal(p.products[0].currentPrice, "59.00");
  assert.equal(p.products[0].packageLabel, "1 кг");
  assert.equal(p.hasNext, false);
  assert.equal(p.page, 2);
});

test("Pyaterochka refuses unknown weight units instead of guessing", () => {
  assert.throws(
    () =>
      parsePyaterochkaPage(
        {
          name: "Овощи",
          products: [
            {
              plu: 1,
              name: "Морковь",
              uom: "кг",
              prices: { regular: "6.90" },
              price_unit_label: null,
            },
          ],
        },
        { id: "A" },
        0,
        "35XY",
      ),
    /WEIGHT_PRICE_UNIT/,
  );
});

test("Products shared by categories are deduplicated; changing prices fail collection", () => {
  const items = new Map();
  const p = { id: "1", currentPrice: "10.00", regularPrice: "12.00" };
  mergeProducts(items, [p]);
  mergeProducts(items, [{ ...p }]);
  assert.equal(items.size, 1);
  assert.throws(
    () => mergeProducts(items, [{ ...p, currentPrice: "11.00" }]),
    /PRICE_CHANGED/,
  );
});

test("Globus captures the default region without copying session identifiers", () => {
  const html = next([
    {
      queryKey: "context",
      state: {
        data: {
          data: {
            context: {
              store_id: 5011,
              purchase_method: 3,
              device_id: "PRIVATE",
            },
          },
        },
      },
    },
    {
      queryKey: "directories-stores",
      state: {
        data: {
          data: {
            stores: [{ id: 5011, full_addr: "Москва" }],
          },
        },
      },
    },
  ]);
  const context = sourceContext("globus", html);
  assert.equal(context.address, "Москва");
  assert.equal(JSON.stringify(context).includes("PRIVATE"), false);
});

test("Magnit follows the explicit cursor, which differs from offset plus limit", () => {
  const raw = {
    pagination: {
      offset: 0,
      limit: 33,
      nextOffset: 35,
      hasMore: true,
      totalCount: 223,
    },
    items: [
      {
        id: "1",
        storeCode: "992301",
        name: "Лук репчатый",
        seoCode: "luk",
        price: 3709,
        promotion: {},
        quantity: 10,
        weighted: { isWeighted: true, unitLabel: "1кг", unitPrice: 5299 },
      },
    ],
  };
  const page = parseMagnitApi(raw, { name: "Овощи и фрукты" }, 0, "992301");
  assert.equal(page.nextOffset, 35);
  assert.equal(page.total, null); // Upstream total includes filtered positions; hasMore is authoritative.
  assert.equal(page.products[0].regularPrice, "52.99");
  assert.equal(page.products[0].packageLabel, "1 кг");
  assert.throws(() => parseMagnitApi(raw, {}, 33, "992301"), /PAGINATION/);
  assert.throws(() => parseMagnitApi(raw, {}, 0, "OTHER"), /CONTEXT/);
});

test("Package mass is extracted conservatively; multipacks are not guessed", () => {
  assert.equal(packageLabel("Гречка 900г"), "900 г");
  assert.equal(packageLabel("Молоко 3.2% 1л"), "1 л");
  assert.equal(packageLabel("Йогурт 2x100г"), "");
  assert.equal(packageLabel("Молоко"), "");
});

test("Globus does not treat a variable-weight piece as freely divisible kilograms", () => {
  const html = next([
    {
      queryKey: ["catalog-product-list"],
      state: {
        data: {
          pages: [
            {
              data: {
                category: { name: "Овощи, фрукты" },
                products: {
                  pagination: { page: 1, total: 1, total_page: 1 },
                  items: [
                    {
                      id: "1",
                      name_optional: "Банан 1 штука",
                      name_required: "120-280г",
                      url: "/products/banan/",
                      price: 14299,
                      cost: null,
                      active: true,
                      quantity_max: 100,
                      package_type: "weight-by-piece",
                      price_representation: { comments: [] },
                      unit_price_text: "1 кг",
                    },
                  ],
                },
              },
            },
          ],
        },
      },
    },
  ]);
  const item = parsePage("globus", html, { id: "1" }, 1).products[0];
  assert.equal(item.currentPrice, "142.99");
  assert.equal(item.regularPrice, null);
  assert.equal(item.conditions.packageType, "weight-by-piece");
});

test("Live catalogue changes are explicit; missing pages and major drift are rejected", () => {
  assert.equal(
    categoryCoverage([624, 627], 627).verification,
    "LIVE_CATALOGUE_CHANGED",
  );
  assert.equal(categoryCoverage([624, 624], 624).verification, "EXACT_COUNT");
  assert.throws(() => categoryCoverage([624, 627], 600), /INCOMPLETE/);
  assert.throws(() => categoryCoverage([624, 800], 800), /CHANGED/);
});
