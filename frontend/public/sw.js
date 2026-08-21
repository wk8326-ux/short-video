const CACHE_NAME = "short-video-shell-v5";
const SHELL = ["/", "/manifest.webmanifest", "/icon.png"];

async function cacheShell() {
  const cache = await caches.open(CACHE_NAME);
  const home = await fetch("/", { cache: "no-store" });
  if (!home.ok) throw new Error(`Shell request failed: ${home.status}`);
  await cache.put("/", home.clone());
  const html = await home.text();
  const assets = [...html.matchAll(/(?:src|href)="(\/assets\/[^"?]+)"/g)]
    .map((match) => match[1]);
  await cache.addAll([...SHELL.slice(1), ...new Set(assets)]);
}

self.addEventListener("install", (event) => {
  event.waitUntil(cacheShell());
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key)))),
  );
  self.clients.claim();
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== "GET" || url.origin !== self.location.origin || url.pathname.startsWith("/api/")) {
    return;
  }

  if (request.mode === "navigate") {
    event.respondWith((async () => {
      const cache = await caches.open(CACHE_NAME);
      const cached = await cache.match("/");
      const update = fetch(request).then(async (response) => {
        if (response.ok) await cache.put("/", response.clone());
        return response;
      });
      if (cached) {
        event.waitUntil(update.catch(() => undefined));
        return cached;
      }
      return update;
    })());
    return;
  }

  event.respondWith(
    caches.match(request).then((cached) => {
      if (cached) return cached;
      return fetch(request).then((response) => {
        if (response.ok) {
          const copy = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(request, copy));
        }
        return response;
      });
    }),
  );
});
