type HlsConstructor = typeof import("hls.js").default;

let hlsEnginePromise: Promise<HlsConstructor> | null = null;

export type PlaybackSource = {
  url: string;
  format?: string | null;
  initialTime?: number;
  autoPlay?: boolean;
  isCancelled?: () => boolean;
  onFatalError?: () => void;
};

export type PlaybackSession = {
  destroy: () => void;
};

export function preloadHlsEngine(): Promise<HlsConstructor> {
  hlsEnginePromise ??= import("hls.js").then(({ default: Hls }) => Hls);
  return hlsEnginePromise;
}

export function isHlsPlayback(url: string, format?: string | null): boolean {
  return (
    format?.toLowerCase() === "m3u8"
    || /\.m3u8(?:$|[?#])/i.test(url)
  );
}

export function withPlaybackAttempt(url: string, attempt: number): string {
  if (attempt <= 0) return url;
  try {
    const parsed = new URL(url, window.location.origin);
    parsed.searchParams.set("refresh", "1");
    parsed.searchParams.set("attempt", String(attempt));
    return parsed.origin === window.location.origin
      ? `${parsed.pathname}${parsed.search}${parsed.hash}`
      : parsed.toString();
  } catch {
    const separator = url.includes("?") ? "&" : "?";
    return `${url}${separator}refresh=1&attempt=${attempt}`;
  }
}

function clearMedia(media: HTMLMediaElement): void {
  media.pause();
  media.removeAttribute("src");
  media.load();
}

export async function attachPlaybackSource(
  media: HTMLMediaElement,
  source: PlaybackSource,
): Promise<PlaybackSession> {
  let destroyed = false;
  let mediaRecoveryAttempted = false;
  let hls: import("hls.js").default | null = null;
  const formatHls = isHlsPlayback(source.url, source.format);

  const destroy = () => {
    if (destroyed) return;
    destroyed = true;
    hls?.destroy();
    hls = null;
    clearMedia(media);
  };

  media.preload = "auto";
  if (formatHls) {
    const Hls = await preloadHlsEngine();
    if (destroyed || source.isCancelled?.()) return { destroy };
    if (Hls.isSupported()) {
      hls = new Hls({
        enableWorker: true,
        capLevelToPlayerSize: true,
        maxBufferLength: 30,
        maxMaxBufferLength: 60,
        backBufferLength: 30,
        startFragPrefetch: true,
        startPosition: (source.initialTime ?? 0) > 1 ? source.initialTime : -1,
      });
      hls.on(Hls.Events.ERROR, (_, data) => {
        if (!data.fatal || destroyed) return;
        if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !mediaRecoveryAttempted) {
          mediaRecoveryAttempted = true;
          hls?.recoverMediaError();
          return;
        }
        source.onFatalError?.();
      });
      hls.loadSource(source.url);
      hls.attachMedia(media);
    } else {
      media.src = source.url;
      media.load();
    }
  } else {
    media.src = source.url;
    media.load();
  }

  if (source.autoPlay && !destroyed) {
    void media.play().catch(() => undefined);
  }
  return { destroy };
}
