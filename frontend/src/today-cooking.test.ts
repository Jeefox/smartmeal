import { beforeEach, afterEach, it, expect, vi } from 'vitest';
import {
  defaultBatchPreference,
  readBatchPreference,
  saveBatchPreference,
  batchSuggestions,
  readyComponents,
} from './today-cooking';
import type { CookingView } from './cooking';
beforeEach(() => {
  const map = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => map.get(k) || null,
    setItem: (k: string, v: string) => map.set(k, v),
  });
});
afterEach(() => vi.unstubAllGlobals());
it('batch cooking is opt in and preferences never cross accounts', () => {
  expect(readBatchPreference('a').enabled).toBe(false);
  saveBatchPreference('a', { enabled: true, days: 3, weekdays: [0, 3] });
  expect(readBatchPreference('a')).toEqual({ enabled: true, days: 3, weekdays: [0, 3] });
  expect(readBatchPreference('b')).toEqual(defaultBatchPreference());
});
it('invalid stored preferences return safe opt-out defaults', () => {
  for (const v of [
    { enabled: 'yes', days: 2, weekdays: [0] },
    { enabled: true, days: 14, weekdays: [0] },
    { enabled: true, days: 2, weekdays: [] },
    { enabled: true, days: 2, weekdays: [-1] },
  ]) {
    localStorage.setItem('smartmeal.batch-cooking.v1.a', JSON.stringify(v));
    expect(readBatchPreference('a').enabled).toBe(false);
  }
});
const view = {
  today: '2026-10-25',
  candidates: [0, 1, 2, 3].map((i) => ({
    ref: { entryId: '' + i, componentId: 'rice' },
    recipeName: 'Рис',
    fingerprint: 'same',
    date: `2026-10-${25 + i}`,
    mealType: 'DINNER',
    cookedG: 200,
  })),
  batches: [],
  drafts: [],
  revision: 0,
} satisfies CookingView;
it('suggests the same component across days only when opted in and scheduled', () => {
  expect(batchSuggestions(view, '2026-10-25', defaultBatchPreference())).toEqual([]);
  expect(
    batchSuggestions(view, '2026-10-25', { enabled: true, days: 2, weekdays: [0, 3] })[0].entries,
  ).toHaveLength(2);
  expect(
    batchSuggestions(view, '2026-10-26', { enabled: true, days: 3, weekdays: [0, 3] }),
  ).toEqual([]);
  expect(batchSuggestions(view, '2026-10-24', { enabled: true, days: 3, weekdays: [6] })).toEqual(
    [],
  );
});
it('does not prepare past the next selected cooking day', () => {
  const p = { enabled: true, days: 3 as const, weekdays: [0, 2] };
  expect(batchSuggestions(view, '2026-10-25', p)[0].entries).toHaveLength(2);
  expect(readyComponents(undefined, 'entry')).toEqual([]);
});
