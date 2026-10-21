import { mkdir, readFile, writeFile, rename, rmdir } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createInterface } from "node:readline/promises";
import { setTimeout as delay } from "node:timers/promises";
import {
  CHAINS,
  categories,
  sourceContext,
  parsePage,
  parsePyaterochkaPage,
  parseMagnitApi,
  categoryCoverage,
  mergeProducts,
  mergeDatedProducts,
  officialUrl,
} from "./retail_catalogue.mjs";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const args = process.argv.slice(2);
const chain = args.find((x) => !x.startsWith("--"));
if (!CHAINS[chain])
  throw new Error(
    "Usage: node scripts/collect_retail.mjs magnit|globus|perekrestok|pyaterochka [--headed] [--setup] [--watch] [--categories=id,id]",
  );
const OUTPUT = resolve(
  process.env.RETAIL_OUTPUT_DIR || join(ROOT, "data/runtime", chain),
);
const selected = args
  .find((x) => x.startsWith("--categories="))
  ?.slice(13)
  .split(",");
const maxPages = Number(
  args.find((x) => x.startsWith("--max-pages="))?.slice(12) || 5000,
);
if (!Number.isInteger(maxPages) || maxPages < 1 || maxPages > 5000)
  throw new Error("INVALID_MAX_PAGES");

async function atomicJson(path, value) {
  const temporary = `${path}.${process.pid}.tmp`;
  await writeFile(temporary, JSON.stringify(value, null, 2), { mode: 0o600 });
  await rename(temporary, path);
}

async function collect() {
  await mkdir(OUTPUT, { recursive: true, mode: 0o700 });
  const lock = join(OUTPUT, "collector.lock");
  await mkdir(lock); // Refuse overlapping runs and concurrent writes to the browser profile.
  let context;
  const retrievedAt = new Date().toISOString();
  try {
    const { firefox } = await import(
      pathToFileURL(join(ROOT, "frontend/node_modules/playwright/index.mjs"))
    );
    context = await firefox.launchPersistentContext(
      join(OUTPUT, "profile-firefox"),
      {
        headless: !args.includes("--headed") && !args.includes("--setup"),
        firefoxUserPrefs: { "dom.ipc.processCount": 1 },
      },
    );
    await context.route("**/*", (route) =>
      ["image", "font", "media"].includes(route.request().resourceType())
        ? route.abort()
        : route.continue(),
    );
    const page = await context.newPage();
    let deniedCatalogueStatus = null;
    page.on("response", (response) => {
      const url = new URL(response.url());
      if (
        chain === "pyaterochka" &&
        url.hostname === "5d.5ka.ru" &&
        url.pathname.startsWith("/api/catalog/") &&
        [403, 429].includes(response.status())
      ) {
        deniedCatalogueStatus = response.status();
      }
    });
    async function load(url) {
      let response;
      for (let attempt = 0; ; attempt++) {
        try {
          response = await page.goto(officialUrl(chain, url), {
            waitUntil: "domcontentloaded",
            timeout: 45000,
          });
          break;
        } catch (error) {
          if (attempt || error.name !== "TimeoutError") throw error;
          await delay(7000);
        }
      }
      if (response?.status() !== 200)
        throw new Error(`CATALOGUE_HTTP_${response?.status()}`);
      if (chain === "perekrestok") {
        await page.waitForFunction(
          () =>
            [...document.scripts].some((script) =>
              script.textContent.includes("window.__INITIAL_STATE__"),
            ),
          null,
          { timeout: 15000 },
        );
      } else {
        await page
          .locator(
            chain === "magnit"
              ? "script#__NUXT_DATA__"
              : "script#__NEXT_DATA__",
          )
          .first()
          .waitFor({ state: "attached", timeout: 15000 });
      }
      return page.content();
    }
    let html;
    if (args.includes("--setup")) {
      const setupUrl =
        args.find((x) => x.startsWith("--setup-url="))?.slice(12) ||
        CHAINS[chain].origin + CHAINS[chain].index;
      try {
        await page.goto(officialUrl(chain, setupUrl), {
          waitUntil: "domcontentloaded",
          timeout: 45000,
        });
      } catch (error) {
        console.warn(
          "Страница пока не загрузилась; окно оставлено для ручной проверки:",
          error.name,
        );
      }
      const rl = createInterface({
        input: process.stdin,
        output: process.stdout,
      });
      await rl.question(
        "Дождитесь каталога, при необходимости пройдите проверку вручную, затем нажмите Enter: ",
      );
      rl.close();
      html = await page.content();
    } else html = await load(CHAINS[chain].origin + CHAINS[chain].index);
    const contextInfo = sourceContext(chain, html);
    let tree = categories(chain, html);
    let magnitTemplate;
    if (chain === "magnit") {
      const dismiss = page.getByRole("button", {
        name: "Не сейчас",
        exact: true,
      });
      if (await dismiss.isVisible()) await dismiss.click();
      const responsePromise = page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === "/webgate/v2/goods/search",
        { timeout: 30000 },
      );
      responsePromise.catch(() => {});
      await page
        .getByRole("button", { name: "По цене", exact: true })
        .first()
        .click();
      const response = await responsePromise;
      if (response.status() !== 200)
        throw new Error(`CATALOGUE_HTTP_${response.status()}`);
      magnitTemplate = response.request().postDataJSON();
      if (magnitTemplate.storeCode !== contextInfo.storeId)
        throw new Error("SOURCE_CONTEXT_CHANGED");
    }
    async function pyatJson(path) {
      const url = `https://5d.5ka.ru/api/catalog/${path}`;
      for (let attempt = 0; ; attempt++) {
        try {
          return await page.evaluate(async (url) => {
            const response = await fetch(url, {
              credentials: "include",
              signal: AbortSignal.timeout(30000),
            });
            if (!response.ok)
              throw new Error(`CATALOGUE_HTTP_${response.status}`);
            return response.json();
          }, url);
        } catch (error) {
          if (deniedCatalogueStatus)
            throw new Error(`CATALOGUE_HTTP_${deniedCatalogueStatus}`);
          if (attempt || /HTTP_|SyntaxError/.test(error.message)) throw error;
          await delay(15000);
        }
      }
    }
    if (chain === "pyaterochka") {
      if (
        contextInfo.mode !== "delivery" ||
        !/^[A-Z0-9]+$/.test(contextInfo.storeId)
      )
        throw new Error("UNVERIFIED_SOURCE_MODE");
      const index = await pyatJson(
        `v4/stores/${contextInfo.storeId}/categories?mode=delivery&include_subcategories=1&include_restrict=true`,
      );
      if (!Array.isArray(index) || index.some((c) => !/^[A-Z0-9]+$/.test(c.id)))
        throw new Error("INVALID_CATEGORY_TREE");
      tree = index.map((c) => ({
        id: c.id,
        name: c.name,
        url: `https://5ka.ru/catalog/`,
      }));
    }
    if (selected) {
      const known = new Set(tree.map((c) => c.id));
      if (selected.some((id) => !known.has(id)))
        throw new Error("UNKNOWN_CATEGORY_ID");
      tree = tree.filter((c) => selected.includes(c.id));
    } else if (!args.includes("--all")) {
      tree = tree.filter((c) =>
        /овощ|фрукт|молоч|молоко|сыр|яйц|мясо|птиц|рыб|морепродукт|бакале|круп|макарон|хлеб|выпеч|консерв|заморо|орех|снек|сухие завтраки|мюсли/i.test(
          c.name || "",
        ),
      );
    }
    if (!tree.length) throw new Error("CATEGORY_TREE_EMPTY");
    const collected = [];
    const products = new Map();
    const productDates = new Map();
    const categoryDirectory = join(OUTPUT, "categories");
    await mkdir(categoryDirectory, { recursive: true, mode: 0o700 });
    const pageDirectory = join(OUTPUT, "pages");
    await mkdir(pageDirectory, { recursive: true, mode: 0o700 });
    async function savePage(category, pageNumber, source, items) {
      if (!items.length) return;
      await atomicJson(
        join(pageDirectory, `${category.id}-${pageNumber}.json`),
        {
          schemaVersion: 1,
          chain,
          complete: true,
          retrievedAt: new Date().toISOString(),
          sourceContext: source,
          scope: "OBSERVED_PAGE",
          categories: [
            { id: category.id, page: pageNumber, name: category.name },
          ],
          products: items,
        },
      );
    }
    let oldestRetrievedAt = retrievedAt;
    let requests = 0;
    const failedCategories = [];
    for (const category of tree) {
      try {
        if (!/^[A-Za-z0-9_-]+$/.test(category.id))
          throw new Error("INVALID_CATEGORY_ID");
        const categoryPath = join(categoryDirectory, `${category.id}.json`);
        if (!args.includes("--force")) {
          let cached;
          try {
            cached = JSON.parse(await readFile(categoryPath, "utf8"));
          } catch {}
          if (
            cached?.complete === true &&
            cached.chain === chain &&
            JSON.stringify(cached.sourceContext) ===
              JSON.stringify(contextInfo) &&
            Date.now() - Date.parse(cached.retrievedAt) >= 0 &&
            Date.now() - Date.parse(cached.retrievedAt) < 7 * 86400000 &&
            cached.categories?.length === 1 &&
            cached.categories[0].id === category.id &&
            Array.isArray(cached.products)
          ) {
            mergeDatedProducts(
              products,
              cached.products,
              cached.retrievedAt,
              productDates,
            );
            collected.push(cached.categories[0]);
            if (cached.retrievedAt < oldestRetrievedAt)
              oldestRetrievedAt = cached.retrievedAt;
            console.log(
              `${chain}: ${category.id}, using complete snapshot from ${cached.retrievedAt}`,
            );
            continue;
          }
        }
        const unique = new Set();
        const categoryRetrievedAt = new Date().toISOString();
        const categoryProducts = new Map();
        const observedTotals = [];
        let magnitOffset = 0;
        for (let pageNumber = 1; ; pageNumber++) {
          if (++requests > maxPages)
            throw new Error("PAGE_LIMIT_REACHED_SNAPSHOT_NOT_PUBLISHED");
          const url = new URL(category.url);
          if (chain === "magnit") {
            url.searchParams.set("sortBy", "price");
            url.searchParams.set("order", "desc");
          }
          if (pageNumber > 1 || chain === "globus")
            url.searchParams.set("page", String(pageNumber));
          let data;
          if (chain === "magnit") {
            const categoryId = Number(category.id.split("-")[0]);
            const body = {
              ...magnitTemplate,
              categories: [categoryId],
              pagination: { limit: 33, offset: magnitOffset },
            };
            const response = await page.evaluate(async (body) => {
              const r = await fetch("/webgate/v2/goods/search", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
                signal: AbortSignal.timeout(30000),
              });
              if (!r.ok) throw new Error(`CATALOGUE_HTTP_${r.status}`);
              return r.json();
            }, body);
            data = parseMagnitApi(
              response,
              category,
              magnitOffset,
              contextInfo.storeId,
            );
            magnitOffset = data.nextOffset;
          } else if (chain === "pyaterochka") {
            const offset = (pageNumber - 1) * 12;
            const response = await pyatJson(
              `v2/stores/${contextInfo.storeId}/categories/${category.id}/products?mode=delivery&include_restrict=true&limit=12&offset=${offset}`,
            );
            data = parsePyaterochkaPage(
              response,
              category,
              offset,
              contextInfo.storeId,
            );
          } else {
            html = await load(url.href);
            const actualContext = sourceContext(chain, html);
            try {
              data = parsePage(chain, html, category, pageNumber);
            } catch (error) {
              await writeFile(join(OUTPUT, "last-failed-page.html"), html, {
                mode: 0o600,
              });
              throw error;
            }
            await savePage(category, pageNumber, actualContext, data.products);
            if (
              actualContext.storeId !== contextInfo.storeId ||
              actualContext.cityId !== contextInfo.cityId ||
              actualContext.purchaseMethod !== contextInfo.purchaseMethod
            ) {
              throw new Error("SOURCE_CONTEXT_CHANGED");
            }
          }
          if (chain === "magnit" || chain === "pyaterochka") {
            await savePage(category, pageNumber, contextInfo, data.products);
          }
          observedTotals.push(data.total);
          const previousSize = unique.size;
          for (const p of data.products) unique.add(p.id);
          if (
            chain !== "magnit" &&
            data.hasNext &&
            unique.size === previousSize
          )
            throw new Error("PAGINATION_MADE_NO_PROGRESS");
          mergeProducts(categoryProducts, data.products);
          console.log(
            `${chain}: ${category.id}, page=${pageNumber}, products=${unique.size}, total=${data.total ?? "?"}`,
          );
          if (!data.hasNext) break;
          await delay(chain === "pyaterochka" ? 3500 : 1500);
        }
        const coverage = categoryCoverage(observedTotals, unique.size);
        const completedCategory = {
          ...category,
          productCount: unique.size,
          ...coverage,
        };
        collected.push(completedCategory);
        await atomicJson(categoryPath, {
          schemaVersion: 1,
          chain,
          complete: true,
          retrievedAt: categoryRetrievedAt,
          sourceContext: contextInfo,
          scope: "SELECTED_CATEGORIES",
          categories: [completedCategory],
          products: [...categoryProducts.values()],
        });
        mergeDatedProducts(
          products,
          [...categoryProducts.values()],
          categoryRetrievedAt,
          productDates,
        );
      } catch (error) {
        // A missing/changed section must not starve all later sections. Access
        // checks, rate limits, changed scope and explicit bounds stop the source.
        if (
          /HTTP_403|HTTP_429|ACCESS_CHECK|SOURCE_CONTEXT|PAGE_LIMIT|waitForFunction|locator.waitFor/.test(
            error.message,
          )
        )
          throw error;
        failedCategories.push(category.id);
        console.error(
          `${chain}: category ${category.id} failed: ${error.message.slice(0, 180)}`,
        );
      }
    }
    if (failedCategories.length)
      throw new Error(`INCOMPLETE_CATEGORIES:${failedCategories.join(",")}`);
    const snapshot = {
      schemaVersion: 1,
      chain,
      complete: true,
      retrievedAt: oldestRetrievedAt,
      finishedAt: new Date().toISOString(),
      sourceContext: contextInfo,
      scope: selected
        ? "SELECTED_CATEGORIES"
        : args.includes("--all")
          ? "ALL_DISCOVERED_CATEGORIES"
          : "FOOD_CATEGORIES",
      categories: collected,
      products: [...products.values()],
    };
    await atomicJson(join(OUTPUT, "latest.json"), snapshot);
    await atomicJson(join(OUTPUT, "status.json"), {
      success: true,
      retrievedAt,
      productCount: products.size,
      categoryCount: collected.length,
      scope: snapshot.scope,
    });
    console.log(
      `SAVED ${chain}: ${products.size} products, ${collected.length} complete categories`,
    );
  } catch (error) {
    await atomicJson(join(OUTPUT, "status.json"), {
      success: false,
      attemptedAt: retrievedAt,
      error: error.message.slice(0, 180),
      previousSnapshotRetained: true,
    });
    throw error;
  } finally {
    await context?.close();
    await rmdir(lock);
  }
}

do {
  let success = false;
  try {
    await collect();
    success = true;
  } catch (error) {
    console.error(`${chain}: ${error.message}`);
    if (!args.includes("--watch")) process.exitCode = 1;
  }
  if (!args.includes("--watch")) break;
  await delay((success ? 7 : 1) * 86400000);
} while (true);
