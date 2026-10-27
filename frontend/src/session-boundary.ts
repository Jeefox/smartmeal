// No credentials or profile data are sent between tabs.
let epoch = 0;
let boundary = new AbortController();
export function currentBoundary() {
  return { epoch, signal: boundary.signal };
}
export function assertBoundary(expected: number) {
  if (epoch !== expected) throw new DOMException('Session changed', 'AbortError');
}
export function advanceBoundary() {
  epoch++;
  boundary.abort();
  boundary = new AbortController();
}
const prefixes = [
  'smartmeal.planning.',
  'smartmeal.saved-period.',
  'smartmeal.recent-period.',
  'smartmeal.shopping-checks.',
  'smartmeal.shopping.sessions.',
  'smartmeal.pending-price-batch.',
  'smartmeal.batch-cooking.',
  'smartmeal.today.',
];
export function clearPrivateStorage() {
  for (const name of ['localStorage', 'sessionStorage'] as const) {
    try {
      const storage = window[name];
      for (let i = storage.length - 1; i >= 0; i--) {
        const key = storage.key(i);
        if (
          key &&
          (prefixes.some((p) => key.startsWith(p)) || key === 'smartmeal.v2.preferences.city')
        )
          storage.removeItem(key);
      }
    } catch {
      /* Storage may be disabled. Session state still resets. */
    }
  }
  if ('caches' in window)
    void caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys.filter((key) => key.startsWith('smartmeal-user-')).map((key) => caches.delete(key)),
        ),
      )
      .catch(() => {});
}
