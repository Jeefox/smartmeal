import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateBooklet } from './collect_dixy.mjs';

const item = { name: '43 неделя', active_from: '19.10.2026', active_to: '25.10.2026',
  download_file: '/upload/iblock/77a/catalogue.pdf' };

test('validates official PDF links and inclusive Moscow expiry', () => {
  const result = validateBooklet(item);
  assert.equal(result.sourceUrl, 'https://dixy.ru/upload/iblock/77a/catalogue.pdf');
  assert.equal(result.validFrom, '2026-10-18T21:00:00.000Z');
  assert.equal(result.expiresAt, '2026-10-25T21:00:00.000Z');
});

test('rejects foreign hosts, invalid dates and reversed intervals', () => {
  assert.throws(() => validateBooklet({ ...item, download_file: 'https://evil.invalid/catalogue.pdf' }));
  assert.throws(() => validateBooklet({ ...item, active_from: '31.02.2026' }));
  assert.throws(() => validateBooklet({ ...item, active_to: '18.10.2026' }));
});
