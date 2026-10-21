import { mkdir, mkdtemp, readFile, rename, writeFile, rmdir } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { setTimeout as delay } from 'node:timers/promises';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const OUTPUT = resolve(process.env.DIXY_OUTPUT_DIR || join(ROOT, 'data/runtime/dixy'));
const INDEX = 'https://dixy.ru/booklet/';

export function validateBooklet(item) {
  if (!item || typeof item.name !== 'string' || !item.name.trim() || item.name.length > 300
      || typeof item.download_file !== 'string'
      || !/^\/upload\/iblock\/[a-z0-9/_-]+\.pdf$/i.test(item.download_file)) {
    throw new Error('INVALID_BOOKLET');
  }
  const date = value => {
    if (typeof value !== 'string' || !/^\d{2}\.\d{2}\.\d{4}$/.test(value)) throw new Error('INVALID_DATE');
    const [d, m, y] = value.split('.').map(Number);
    const timestamp = Date.UTC(y, m - 1, d);
    const check = new Date(timestamp);
    if (check.getUTCFullYear() !== y || check.getUTCMonth() !== m - 1 || check.getUTCDate() !== d) throw new Error('INVALID_DATE');
    return timestamp;
  };
  const from = date(item.active_from);
  const to = date(item.active_to);
  if (to < from || to - from > 366 * 86400000) throw new Error('INVALID_PERIOD');
  return { name: item.name, sourceUrl: 'https://dixy.ru' + item.download_file,
    activeFrom: item.active_from, activeTo: item.active_to,
    validFrom: new Date(from - 3 * 3600000).toISOString(),
    expiresAt: new Date(to + 86400000 - 3 * 3600000).toISOString() };
}

async function atomicJson(path, data) {
  const temporary = `${path}.${process.pid}.tmp`;
  await writeFile(temporary, JSON.stringify(data, null, 2), { mode: 0o600 });
  await rename(temporary, path);
}

async function collect() {
  await mkdir(OUTPUT, { recursive: true, mode: 0o700 });
  const lock = join(OUTPUT, 'collector.lock');
  await mkdir(lock);
  let browser;
  try {
    const { firefox } = await import(pathToFileURL(join(ROOT, 'frontend/node_modules/playwright/index.mjs')));
    browser = await firefox.launch({ headless: !process.argv.includes('--headed') });
    const page = await browser.newPage({ acceptDownloads: true });
    const indexPromise = page.waitForResponse(response => response.url() === 'https://dixy.ru/ajax/json.php?block=booklets', { timeout: 45000 });
    // Keep a handler attached even if navigation fails before the response arrives.
    indexPromise.catch(() => {});
    const response = await page.goto(INDEX, { waitUntil: 'domcontentloaded', timeout: 30000 });
    if (response?.status() !== 200) throw new Error(`INDEX_HTTP_${response?.status()}`);
    const index = await indexPromise;
    if (index.status() !== 200) throw new Error(`BOOKLETS_HTTP_${index.status()}`);
    const raw = await index.json();
    if (!Array.isArray(raw) || !raw.length || raw.length > 30) throw new Error('INVALID_BOOKLET_INDEX');
    const now = new Date();
    const offers = raw.map(validateBooklet).filter(item => new Date(item.validFrom) <= now && new Date(item.expiresAt) > now);
    if (!offers.length) throw new Error('NO_CURRENT_BOOKLETS');
    const run = await mkdtemp(join(OUTPUT, 'run-'));
    const files = [];
    for (const [i, offer] of offers.entries()) {
      const downloadPromise = page.waitForEvent('download', { timeout: 45000 });
      downloadPromise.catch(() => {});
      await page.evaluate(url => {
        const link = document.createElement('a');
        link.href = url;
        link.download = 'catalogue.pdf';
        document.body.appendChild(link);
        link.click();
        link.remove();
      }, offer.sourceUrl);
      const download = await downloadPromise;
      const path = join(run, `catalogue-${i + 1}.pdf`);
      await download.saveAs(path);
      const bytes = await readFile(path);
      if (bytes.length < 100 || bytes.length > 50_000_000 || !bytes.subarray(0, 5).equals(Buffer.from('%PDF-'))) {
        throw new Error('INVALID_PDF_DOWNLOAD');
      }
      let textPath = null;
      try {
        textPath = path.replace(/\.pdf$/, '.txt');
        await promisify(execFile)('pdftotext', ['-layout', path, textPath], { timeout: 30000 });
      } catch (error) {
        textPath = null;
        console.warn('PDF downloaded; text extraction unavailable: ' + error.code);
      }
      files.push({ ...offer, path, textPath, bytes: bytes.length,
        sha256: createHash('sha256').update(bytes).digest('hex') });
      console.log(`Downloaded ${offer.name}, ${offer.activeFrom}–${offer.activeTo}: ${bytes.length} bytes.`);
    }
    const result = { schemaVersion: 1, chain: 'DIXY', kind: 'PROMOTIONAL_BOOKLET',
      scope: 'Открытый буклет сети Дикси. Конкретный магазин не выбран; регион применимости не подтверждён.',
      complete: true, retrievedAt: new Date().toISOString(), indexUrl: INDEX, booklets: files };
    await atomicJson(join(OUTPUT, 'latest.json'), result);
    await atomicJson(join(OUTPUT, 'status.json'), { state: 'SUCCESS', checkedAt: result.retrievedAt, booklets: files.length });
  } catch (error) {
    await atomicJson(join(OUTPUT, 'status.json'), { state: 'FAILED', checkedAt: new Date().toISOString(), error: error.message });
    throw error;
  } finally {
    try { await browser?.close(); } finally { await rmdir(lock); }
  }
}

async function main() {
  do {
    let ok = false;
    try { await collect(); ok = true; } catch (error) {
      console.error(error.message);
      if (!process.argv.includes('--watch')) { process.exitCode = 1; return; }
    }
    if (!process.argv.includes('--watch')) return;
    await delay((ok ? 7 : 1) * 86400000);
  } while (true);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
