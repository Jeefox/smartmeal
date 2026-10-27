import { afterEach, expect, it, vi } from 'vitest';
import {
  advanceBoundary,
  assertBoundary,
  currentBoundary,
  clearPrivateStorage,
} from './session-boundary';
afterEach(() => vi.unstubAllGlobals());
it('invalidates old responses and aborts the old session signal', () => {
  const old = currentBoundary();
  advanceBoundary();
  expect(old.signal.aborted).toBe(true);
  expect(() => assertBoundary(old.epoch)).toThrow();
  expect(() => assertBoundary(currentBoundary().epoch)).not.toThrow();
});
it('clears all personal namespaces in both stores and preserves public/demo data', () => {
  const make = () => {
    const map = new Map<string, string>([
      ['smartmeal.planning.preferences.v1.user', 'private'],
      ['smartmeal.saved-period.user', 'private'],
      ['smartmeal.shopping.sessions.v1.user.id', 'private'],
      ['smartmeal.pending-price-batch.user.1', 'private'],
      ['smartmeal.batch-cooking.v1.user', 'private'],
      ['smartmeal.shopping-checks.user.id', 'private'],
      ['smartmeal.recent-period.user', 'private'],
      ['smartmeal.planning.commit.user.id', 'private'],
      ['smartmeal.v2.preferences.city', 'private'],
      ['smartmeal.v2.mode', '"api"'],
      ['smartmeal.v2.demo.profile', 'demo'],
    ]);
    return {
      get length() {
        return map.size;
      },
      key: (i: number) => [...map.keys()][i] || null,
      removeItem: (key: string) => map.delete(key),
      map,
    };
  };
  const local = make(),
    session = make();
  vi.stubGlobal('window', { localStorage: local, sessionStorage: session });
  clearPrivateStorage();
  expect([...local.map.keys()]).toEqual(['smartmeal.v2.mode', 'smartmeal.v2.demo.profile']);
  expect([...session.map.keys()]).toEqual(['smartmeal.v2.mode', 'smartmeal.v2.demo.profile']);
});

it('an old response is rejected even when a transport ignores abort', async () => {
  const { request } = await import('./api');
  let finish!: (response: Response) => void;
  vi.stubGlobal(
    'fetch',
    vi.fn(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    ),
  );
  const pending = request('/me/profile');
  advanceBoundary();
  finish(
    new Response(JSON.stringify({ userId: 'previous-user' }), {
      headers: { 'Content-Type': 'application/json' },
    }),
  );
  await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
});
