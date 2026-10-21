import { mkdir, readFile, rename, writeFile, rmdir } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { createInterface } from 'node:readline/promises';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const STORE = process.env.CHIZHIK_STORE_ID || 'HDC5';
const OUTPUT = resolve(process.env.CHIZHIK_OUTPUT_DIR || join(ROOT, 'data/runtime/chizhik'));
const ORIGIN = 'https://chizhik.club';
const BROWSER = process.env.CHIZHIK_BROWSER || 'firefox';

export function cataloguePageError(status, text, categoryCount) {
  if (/не с ботом|пройдите проверку|разверните картинку/i.test(text)) return 'CAPTCHA_REQUIRED';
  if (/Проблемы со связью|считаете блокировку ложной/i.test(text)) return 'CATALOGUE_ACCESS_BLOCKED';
  if (status !== 200) return `CATALOGUE_HTTP_${status || 'NO_RESPONSE'}`;
  return categoryCount > 0 ? null : 'CATALOGUE_NOT_LOADED';
}

export function parseOverview(body) {
  if (!body || typeof body.name !== 'string' || !Array.isArray(body.categories)) {
    throw new Error('INVALID_CATALOGUE_RESPONSE');
  }
  const products = new Map();
  let count = 0;
  for (const category of body.categories) {
    if (!Array.isArray(category.products)) throw new Error('MISSING_PRODUCTS');
    for (const product of category.products) {
      count++;
      if (!Number.isSafeInteger(product.plu) || product.plu <= 0 || !product.name
          || typeof product.is_available !== 'boolean') throw new Error('INVALID_PRODUCT');
      const regular = product.prices?.regular;
      if (product.is_available && (typeof regular !== 'string'
          || !/^\d{1,9}(?:\.\d{1,2})?$/.test(regular) || Number(regular) <= 0)) {
        throw new Error('INVALID_REGULAR_PRICE');
      }
      const item = {
        plu: String(product.plu), name: product.name, uom: product.uom,
        packageLabel: product.property_clarification || '',
        regularPrice: product.is_available ? regular : null,
        available: product.is_available, category: body.name,
      };
      const previous = products.get(item.plu);
      if (previous && JSON.stringify(previous) !== JSON.stringify(item)) {
        throw new Error('CONFLICTING_DUPLICATE_PRODUCT');
      }
      products.set(item.plu, item);
    }
  }
  if (!Number.isInteger(body.products_count) || body.products_count !== count) {
    throw new Error('INCOMPLETE_CATEGORY');
  }
  return [...products.values()];
}

async function atomicJson(path, value) {
  const temporary = `${path}.${process.pid}.tmp`;
  await writeFile(temporary, JSON.stringify(value), { mode: 0o600 });
  await rename(temporary, path);
}

async function collect() {
  if (!/^[A-Z0-9]{1,20}$/.test(STORE)) throw new Error('INVALID_STORE_ID');
  await mkdir(OUTPUT, { recursive: true, mode: 0o700 });
  const lock = join(OUTPUT, 'collector.lock');
  try { await mkdir(lock); } catch (error) {
    if (error.code === 'EEXIST') throw new Error('COLLECTOR_ALREADY_RUNNING');
    throw error;
  }
  let browser;
  const startedAt = new Date().toISOString();
  try {
    if (!['firefox', 'chromium'].includes(BROWSER)) throw new Error('UNSUPPORTED_BROWSER');
    const engines = await import(pathToFileURL(join(ROOT, 'frontend/node_modules/playwright/index.mjs')));
    const setup = process.argv.includes('--setup');
    browser = await engines[BROWSER].launchPersistentContext(join(OUTPUT, `profile-${BROWSER}`),
      { headless: !setup && !process.argv.includes('--headed') });
    const page = browser.pages()[0] || await browser.newPage();
    const response = await page.goto(`${ORIGIN}/catalog/`, { waitUntil: 'domcontentloaded', timeout: 30000 });
    if (setup) {
      const input = createInterface({ input: process.stdin, output: process.stdout });
      try {
        await input.question('В отдельном Firefox откройте каталог и вручную завершите проверку сайта, если она появится. Затем нажмите Enter здесь. ');
      } finally {
        input.close();
      }
    }
    try {
      await page.locator('a[href*="/catalog/"]').first().waitFor({ timeout: 15000 });
    } catch {
      // A protection page may return HTTP 200. Inspect content before publishing anything.
    }
    const categoryCount = await page.locator('a[href*="/catalog/"]').count();
    const pageError = cataloguePageError(setup ? 200 : response?.status(),
      await page.locator('body').innerText(), categoryCount);
    if (pageError) throw new Error(pageError);
    if (setup) {
      await atomicJson(join(OUTPUT, 'status.json'), { state: 'SESSION_READY', browser: BROWSER,
        storeId: STORE, checkedAt: new Date().toISOString() });
      console.log('Проверка пройдена. Загружаю категории в этом же окне Firefox.');
    }
    const links = await page.locator('a[href*="/catalog/"]').evaluateAll(elements => elements.map(a => a.href));
    const categories = [...new Set(links.flatMap(link => {
      const url = new URL(link);
      const match = url.pathname.match(/^\/catalog\/[a-z0-9-]+--([A-Z0-9]+)\/$/);
      return url.origin === 'https://chizhik.club' && match ? [match[1]] : [];
    }))];
    if (!categories.length || categories.length > 100) throw new Error('INVALID_CATEGORY_LIST');
    const results = [];
    for (const category of categories) {
      const sourceUrl = `https://app.chizhik.club/delivery/api/catalog/v2/stores/${STORE}/categories/${category}/overview?mode=store`;
      // Use the collector's own browser session. Never copy a personal Firefox profile.
      const reply = await page.evaluate(async url => {
        const result = await fetch(url, { credentials: 'include', signal: AbortSignal.timeout(20000) });
        return { status: result.status, body: result.ok ? await result.json() : null };
      }, sourceUrl);
      if (reply.status !== 200) throw new Error(`CATEGORY_HTTP_${reply.status}`);
      results.push({ id: category, sourceUrl, products: parseOverview(reply.body) });
      console.log(`Loaded category ${category}: ${results.at(-1).products.length} products.`);
      await delay(5000);
    }
    const snapshot = { schemaVersion: 1, storeId: STORE, complete: true,
      retrievedAt: startedAt, categories: results };
    await atomicJson(join(OUTPUT, 'latest.json'), snapshot);
    await atomicJson(join(OUTPUT, 'status.json'), { state: 'SUCCESS', checkedAt: new Date().toISOString(),
      storeId: STORE, categories: results.length, products: results.reduce((n, c) => n + c.products.length, 0) });
    console.log(`Saved ${results.length} categories for ${STORE}.`);
  } catch (error) {
    await atomicJson(join(OUTPUT, 'status.json'), { state: 'FAILED', storeId: STORE,
      checkedAt: new Date().toISOString(), error: error.message });
    throw error;
  } finally {
    try {
      await browser?.close();
    } finally {
      await rmdir(lock);
    }
  }
}

async function main() {
  if (process.argv.includes('--setup') && process.argv.includes('--watch')) {
    throw new Error('SETUP_AND_WATCH_ARE_MUTUALLY_EXCLUSIVE');
  }
  if (process.argv.includes('--validate')) {
    const path = process.argv[process.argv.indexOf('--validate') + 1];
    const products = parseOverview(JSON.parse(await readFile(path, 'utf8')));
    console.log(JSON.stringify({ products: products.length, priced: products.filter(p => p.regularPrice).length }));
    return;
  }
  do {
    let success = false;
    try { await collect(); success = true; } catch (error) {
      console.error(error.message);
      if (!process.argv.includes('--watch')) { process.exitCode = 1; return; }
    }
    if (!process.argv.includes('--watch')) return;
    // Weekly after success; retry once a day after a source failure.
    await delay((success ? 7 : 1) * 24 * 60 * 60 * 1000);
  } while (true);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
