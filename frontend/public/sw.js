const CACHE_NAME = "short-video-shell-v6";
const SHELL = ["/", "/manifest.webmanifest", "/icon.svg"];

function isCacheableResponse(response) {
  const cacheControl = response.headers.get("cache-control") || "";
  return response.ok
    && response.status === 200
    && response.type === "basic"
    && !response.redirected
    && !response.headers.has("content-range")
    && !/\bno-store\b/i.test(cacheControl);
}

function isCacheableAsset(request, response) {
  const url = new URL(request.url);
  return request.method === "GET"
    && url.origin === self.location.origin
    && !url.pathname.startsWith("/api/")
    && url.pathname.startsWith("/assets/")
    && isCacheableResponse(response);
}

function isShellRequest(request) {
  const url = new URL(request.url);
  return url.origin === self.location.origin
    && (url.pathname === "/"
      || url.pathname === "/manifest.webmanifest"
      || url.pathname === "/icon.svg");
}

async function fetchForCache(path) {
  const response = await fetch(new Request(new URL(path, self.location.origin), {
    cache: "no-store",
    redirect: "error",
  }));
  if (!isCacheableResponse(response)) {
    throw new Error(`Static resource request failed: ${path}`);
  }
  return response;
}

async function cacheShell() {
  const cache = await caches.open(CACHE_NAME);
  const home = await fetchForCache("/");
  await cache.put("/", home.clone());
  const html = await home.text();
  const assets = [...html.matchAll(/(?:src|href)="(\/assets\/[^"?]+)"/g)]
    .map((match) => match[1]);
  await Promise.all([...SHELL.slice(1), ...new Set(assets)].map(async (path) => {
    const response = await fetchForCache(path);
    await cache.put(path, response);
  }));
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
      try {
        const response = await fetch(request, { cache: "no-store" });
        if (isCacheableResponse(response)) await cache.put("/", response.clone());
        return response;
      } catch {
        const cached = await cache.match("/");
        if (cached) return cached;
        throw new Error("Application shell is unavailable");
      }
    })());
    return;
  }

  if (!isShellRequest(request) && !url.pathname.startsWith("/assets/")) return;

  event.respondWith(
    caches.match(request).then((cached) => {
      const network = fetch(request).then((response) => {
        if (isCacheableAsset(request, response)
          || (isShellRequest(request) && isCacheableResponse(response))) {
          const copy = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(request, copy));
        }
        return response;
      });
      if (cached) {
        event.waitUntil(network.catch(() => undefined));
        return cached;
      }
      return network;
    }),
  );
});
