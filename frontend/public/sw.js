/* Public shell only: never cache API, authentication or private server data. */
const CACHE = 'smartmeal-shell-v2';
self.addEventListener('install', (event) => {
  event.waitUntil(
    (async () => {
      const cache = await caches.open(CACHE);
      const response = await fetch('/');
      if (!response.ok) throw new Error('Shell unavailable');
      const html = await response.clone().text();
      await cache.put('/', response);
      const assets = [...html.matchAll(/(?:src|href)="(\/assets\/[^\"]+)"/g)].map(
        (match) => match[1],
      );
      await cache.addAll([
        ...new Set([
          ...assets,
          '/icons/icon.svg',
          '/icons/icon-192.png',
          '/icons/icon-512.png',
          '/manifest.webmanifest',
        ]),
      ]);
    })(),
  );
  // A new version waits until old tabs close; unsaved forms are not reloaded.
});
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((key) => key.startsWith('smartmeal-shell-') && key !== CACHE)
            .map((key) => caches.delete(key)),
        ),
      ),
  );
});
self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (
    event.request.method !== 'GET' ||
    url.origin !== self.location.origin ||
    url.pathname.startsWith('/api')
  )
    return;
  if (event.request.mode === 'navigate') {
    event.respondWith(fetch(event.request).catch(() => caches.match('/')));
  } else if (url.pathname.startsWith('/assets/') || url.pathname.startsWith('/icons/')) {
    event.respondWith(
      caches.match(event.request).then(
        (cached) =>
          cached ||
          fetch(event.request).then((response) => {
            if (response.ok) {
              const copy = response.clone();
              caches.open(CACHE).then((cache) => cache.put(event.request, copy));
            }
            return response;
          }),
      ),
    );
  }
});
