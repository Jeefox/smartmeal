import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest';
import {
  defaultDraft,
  draftOptions,
  fromOptions,
  sameOptions,
  readPlanningPreferences,
  savePlanningPreferences,
  commitKey,
  clearCommitKey,
  pendingCommit,
  rememberSavedPeriod,
  savedPeriod,
} from './planning-preferences';
import type { PeriodView } from './period-planning';
function memory() {
  const m = new Map<string, string>();
  return {
    getItem: (k: string) => m.get(k) ?? null,
    setItem: (k: string, v: string) => m.set(k, v),
    removeItem: (k: string) => m.delete(k),
  };
}
beforeEach(() => {
  vi.stubGlobal('localStorage', memory());
  vi.stubGlobal('sessionStorage', memory());
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2035-01-12T10:00:00Z'));
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
describe('planning preferences', () => {
  it('restores conditions, never an old start date, and keeps different accounts separate', () => {
    const d = {
      ...defaultDraft(),
      from: '2034-12-01',
      days: 5,
      budget: '4000',
      chains: [1, 2],
      members: ['11111111-1111-4111-8111-111111111111'],
    };
    savePlanningPreferences('a', d);
    expect(readPlanningPreferences('a')).toEqual({ ...d, from: '2035-01-12' });
    expect(readPlanningPreferences('b').members).toEqual([]);
    expect(readPlanningPreferences('b').budget).toBe('');
  });
  it('rejects malformed storage, invalid money and incomplete timing values', () => {
    localStorage.setItem('smartmeal.planning.preferences.v1.a', '{');
    expect(readPlanningPreferences('a').days).toBe(7);
    for (const values of [
      { ...defaultDraft(), days: 100 },
      { ...defaultDraft(), budget: 'NaN' },
      { ...defaultDraft(), times: { BREAKFAST: 5 } },
      { ...defaultDraft(), chains: ['1'] },
      { ...defaultDraft(), members: ['not-a-uuid'] },
    ]) {
      localStorage.setItem(
        'smartmeal.planning.preferences.v1.a',
        JSON.stringify({ version: 1, values }),
      );
      expect(readPlanningPreferences('a')).toEqual(defaultDraft());
    }
  });
  it('compares canonical conditions regardless of JSON key order or selection order', () => {
    const d = { ...defaultDraft(), chains: [1, 2], members: ['b', 'a'] };
    const a = draftOptions(d),
      b = {
        ...JSON.parse(JSON.stringify(a)),
        seed: 20,
        allowedChainIds: [2, 1],
        householdMemberIds: ['a', 'b'],
      };
    expect(sameOptions(a, b)).toBe(true);
    expect(sameOptions(a, { ...b, budgetRub: 500 })).toBe(false);
    expect(fromOptions(a)).toEqual(d);
  });
  it('retains the mutation key across reload but not between users or generations', () => {
    const first = commitKey('a', 'g', new Map());
    expect(commitKey('a', 'g', new Map())).toBe(first);
    expect(commitKey('b', 'g', new Map())).not.toBe(first);
    expect(commitKey('a', 'other', new Map())).not.toBe(first);
    clearCommitKey('a', 'g');
    expect(pendingCommit('a', 'g')).toBeNull();
  });
  it('only updates the saved-shopping shortcut after a successful commit', () => {
    const view = {
      id: 'gen-1',
      savedPlanIds: ['p'],
      result: { options: { from: '2035-01-01', to: '2035-01-05' } },
    } as PeriodView;
    rememberSavedPeriod('a', view);
    rememberSavedPeriod('a', { ...view, id: 'draft', savedPlanIds: [] });
    expect(savedPeriod('a')?.id).toBe('gen-1');
    expect(savedPeriod('b')).toBeNull();
  });
  it('remains usable when browser storage is not available', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw Error();
      },
      setItem: () => {
        throw Error();
      },
    });
    expect(readPlanningPreferences('a')).toEqual(defaultDraft());
    expect(() => savePlanningPreferences('a', defaultDraft())).not.toThrow();
    vi.stubGlobal('sessionStorage', {
      getItem: () => {
        throw Error();
      },
      setItem: () => {
        throw Error();
      },
      removeItem: () => {
        throw Error();
      },
    });
    const cache = new Map<string, string>();
    expect(commitKey('a', 'g', cache)).toBe(commitKey('a', 'g', cache));
  });
});
