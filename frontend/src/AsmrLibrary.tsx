import {
  ArrowLeft,
  ChevronRight,
  FastForward,
  Headphones,
  Library,
  LoaderCircle,
  Maximize2,
  Pause,
  Play,
  Rewind,
  Search,
  Video,
  X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { updatePlaybackState, type SavedPosition } from "./playbackStore";

type AsmrAuthor = {
  name: string;
  itemCount: number;
  videoCount: number;
  audioCount: number;
  modified: string | null;
};

type AsmrItem = {
  id: number;
  title: string;
  author: string;
  size: number;
  modified: string | null;
  duration: number | null;
  format: string;
  kind: "audio" | "video";
  playUrl: string;
  posterUrl: string | null;
};

type AsmrLibraryProps = {
  positions: Record<string, SavedPosition>;
  onProgress: (videoId: number, time: number, duration: number) => void;
  onUnauthorized: () => void;
};

type KindFilter = "all" | "video" | "audio";
const ASMR_PLAYER_HISTORY_KEY = "__shortVideoAsmrPlayer";
const ASMR_CHROME_VISIBLE_MS = 1500;
const ASMR_GESTURE_AXIS_THRESHOLD_PX = 12;

type HlsConstructor = typeof import("hls.js").default;

let hlsEnginePromise: Promise<HlsConstructor> | null = null;

function asmrItemKind(item: AsmrItem): "audio" | "video" {
  return item.format.toLowerCase() === "m3u8" ? "video" : item.kind;
}

function normalizeAsmrItem(item: AsmrItem): AsmrItem {
  const kind = asmrItemKind(item);
  return kind === item.kind ? item : { ...item, kind };
}

function preloadHlsEngine(): Promise<HlsConstructor> {
  hlsEnginePromise ??= import("hls.js").then(({ default: Hls }) => Hls);
  return hlsEnginePromise;
}

function primeAsmrItem(item: AsmrItem): void {
  if (item.format.toLowerCase() === "m3u8") void preloadHlsEngine();
  void fetch(item.playUrl, {
    method: "HEAD",
    cache: "no-store",
    redirect: "manual",
  }).catch(() => undefined);
}

export default function AsmrLibrary({
  positions,
  onProgress,
  onUnauthorized,
}: AsmrLibraryProps) {
  const [authors, setAuthors] = useState<AsmrAuthor[]>([]);
  const [selectedAuthor, setSelectedAuthor] = useState<string | null>(null);
  const [items, setItems] = useState<AsmrItem[]>([]);
  const [localPositions, setLocalPositions] = useState(positions);
  const [itemsRequestKey, setItemsRequestKey] = useState(0);
  const [authorSearch, setAuthorSearch] = useState("");
  const [itemSearch, setItemSearch] = useState("");
  const [kind, setKind] = useState<KindFilter>("all");
  const [current, setCurrent] = useState<AsmrItem | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const playerHistoryRef = useRef<string | null>(null);
  const playerHistoryClosingRef = useRef(false);

  const loadAuthors = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const response = await fetch("/api/asmr/authors", { cache: "no-store" });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json() as { items: AsmrAuthor[] };
      setAuthors(data.items);
    } catch {
      setError("无法载入 ASMR 作者列表");
    } finally {
      setLoading(false);
    }
  }, [onUnauthorized]);

  useEffect(() => {
    void loadAuthors();
  }, [loadAuthors]);

  useEffect(() => {
    if (!selectedAuthor) return;
    const controller = new AbortController();
    setLoading(true);
    setError("");
    void fetch(`/api/asmr/authors/${encodeURIComponent(selectedAuthor)}/items`, {
      cache: "no-store",
      signal: controller.signal,
    }).then(async (response) => {
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json() as { items: AsmrItem[] };
      setItems(data.items.map(normalizeAsmrItem));
    }).catch((requestError: unknown) => {
      if (requestError instanceof DOMException && requestError.name === "AbortError") return;
      setError("无法载入该作者的媒体");
    }).finally(() => setLoading(false));
    return () => controller.abort();
  }, [itemsRequestKey, onUnauthorized, selectedAuthor]);

  useEffect(() => {
    if (items[0]) primeAsmrItem(items[0]);
  }, [items]);

  useEffect(() => {
    if (!expanded || !current) return;
    const marker = `${current.id}:${Date.now()}`;
    const existingState = window.history.state;
    const baseState = existingState && typeof existingState === "object"
      ? existingState as Record<string, unknown>
      : {};
    window.history.pushState({ ...baseState, [ASMR_PLAYER_HISTORY_KEY]: marker }, "");
    playerHistoryRef.current = marker;
    playerHistoryClosingRef.current = false;

    const closeFromHistory = () => {
      if (playerHistoryRef.current !== marker) return;
      playerHistoryRef.current = null;
      playerHistoryClosingRef.current = false;
      setExpanded(false);
    };
    window.addEventListener("popstate", closeFromHistory);
    return () => window.removeEventListener("popstate", closeFromHistory);
  }, [current, expanded]);

  const recordProgress = useCallback((videoId: number, rawTime: number, duration: number) => {
    const time = duration - rawTime <= 3 || rawTime / duration >= 0.97
      ? 0
      : Math.max(0, rawTime);
    setLocalPositions((currentPositions) => ({
      ...currentPositions,
      [String(videoId)]: { time, duration, updatedAt: Date.now() },
    }));
    onProgress(videoId, rawTime, duration);
  }, [onProgress]);

  const visibleAuthors = useMemo(() => {
    const query = authorSearch.trim().toLocaleLowerCase();
    if (!query) return authors;
    return authors.filter((author) => author.name.toLocaleLowerCase().includes(query));
  }, [authorSearch, authors]);

  const visibleItems = useMemo(() => {
    const query = itemSearch.trim().toLocaleLowerCase();
    return items.filter((item) => {
      return (kind === "all" || item.kind === kind)
        && (!query || item.title.toLocaleLowerCase().includes(query));
    });
  }, [itemSearch, items, kind]);

  const openAuthor = (author: string) => {
    setSelectedAuthor(author);
    setItems([]);
    setItemSearch("");
    setKind("all");
  };

  const changeExpanded = useCallback((nextExpanded: boolean) => {
    if (nextExpanded) {
      setExpanded(true);
      return;
    }
    const marker = playerHistoryRef.current;
    const currentState = window.history.state as Record<string, unknown> | null;
    if (marker && currentState?.[ASMR_PLAYER_HISTORY_KEY] === marker) {
      if (playerHistoryClosingRef.current) return;
      playerHistoryClosingRef.current = true;
      window.history.back();
      return;
    }
    playerHistoryRef.current = null;
    playerHistoryClosingRef.current = false;
    setExpanded(false);
  }, []);

  const playItem = (rawItem: AsmrItem) => {
    const item = normalizeAsmrItem(rawItem);
    setCurrent(item);
    setExpanded(asmrItemKind(item) === "video");
    updatePlaybackState((state) => ({
      ...state,
      lastVideoId: item.id,
      lastVideoIds: { ...state.lastVideoIds, asmr: item.id },
    }));
  };

  return (
    <section className={`asmr-library${current ? " has-player" : ""}`}>
      <div className="asmr-content">
        {!selectedAuthor ? (
          <>
            <div className="library-heading">
              <div>
                <h1>ASMR</h1>
                <p>{authors.length ? `${authors.length} 位作者` : "作者媒体库"}</p>
              </div>
              <Library size={22} aria-hidden="true" />
            </div>
            <SearchField
              value={authorSearch}
              onChange={setAuthorSearch}
              placeholder="搜索作者"
              label="搜索 ASMR 作者"
            />
            <div className="author-list" aria-label="作者列表">
              {visibleAuthors.map((author) => (
                <button key={author.name} type="button" onClick={() => openAuthor(author.name)}>
                  <span className="author-mark" aria-hidden="true">{author.name.slice(0, 1)}</span>
                  <span className="author-copy">
                    <strong>{author.name}</strong>
                    <small>{author.videoCount} 视频 · {author.audioCount} 音频</small>
                  </span>
                  <span className="author-total">{author.itemCount}</span>
                  <ChevronRight size={18} aria-hidden="true" />
                </button>
              ))}
            </div>
          </>
        ) : (
          <>
            <div className="author-header">
              <button
                className="icon-button"
                type="button"
                aria-label="返回作者列表"
                onClick={() => setSelectedAuthor(null)}
              >
                <ArrowLeft size={21} aria-hidden="true" />
              </button>
              <div>
                <h1>{selectedAuthor}</h1>
                <p>{items.length} 个媒体文件</p>
              </div>
            </div>
            <SearchField
              value={itemSearch}
              onChange={setItemSearch}
              placeholder="搜索标题"
              label={`搜索 ${selectedAuthor} 的媒体`}
            />
            <div className="kind-tabs" role="tablist" aria-label="媒体类型">
              {([
                ["all", "全部"],
                ["video", "视频"],
                ["audio", "音频"],
              ] as const).map(([value, label]) => (
                  <button
                  key={value}
                  type="button"
                  role="tab"
                  aria-selected={kind === value}
                  onClick={() => setKind(value)}
                >
                  {label}
                </button>
              ))}
            </div>
            <div className="media-list" aria-label={`${selectedAuthor} 的媒体列表`}>
              {visibleItems.map((item) => {
                const Icon = item.kind === "audio" ? Headphones : Video;
                const saved = localPositions[String(item.id)];
                const shownDuration = item.duration ?? saved?.duration;
                return (
                <button
                    key={item.id}
                    className={current?.id === item.id ? "is-playing" : ""}
                    type="button"
                    onPointerDown={() => primeAsmrItem(item)}
                    onClick={() => playItem(item)}
                  >
                    <span className="media-kind" aria-hidden="true"><Icon size={19} /></span>
                    <span className="media-copy">
                      <strong>{item.title}</strong>
                      <small>
                        {item.format.toUpperCase()}
                        {shownDuration ? ` · ${formatTime(shownDuration)}` : ""}
                        {saved?.time ? ` · 已看 ${formatTime(saved.time)}` : ""}
                      </small>
                    </span>
                    {current?.id === item.id ? <Pause size={17} aria-hidden="true" /> : <Play size={17} aria-hidden="true" />}
                  </button>
                );
              })}
            </div>
          </>
        )}

        {loading && (
          <div className="library-state" role="status">
            <LoaderCircle className="spinner" size={24} aria-hidden="true" />
            <span>正在载入</span>
          </div>
        )}
        {!loading && error && (
          <div className="library-state" role="alert">
            <span>{error}</span>
            <button
              className="retry-button"
              type="button"
              onClick={() => selectedAuthor
                ? setItemsRequestKey((value) => value + 1)
                : void loadAuthors()}
            >
              重试
            </button>
          </div>
        )}
        {!loading && !error && (
          (!selectedAuthor && visibleAuthors.length === 0)
          || (selectedAuthor && visibleItems.length === 0)
        ) && <div className="library-state">没有匹配的内容</div>}
      </div>

      {current && (
        <AsmrPlayer
          key={current.id}
          item={current}
          expanded={expanded}
          initialTime={localPositions[String(current.id)]?.time ?? 0}
          onExpandedChange={changeExpanded}
          onClose={() => setCurrent(null)}
          onProgress={recordProgress}
        />
      )}
    </section>
  );
}

type SearchFieldProps = {
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  label: string;
};

function SearchField({ value, onChange, placeholder, label }: SearchFieldProps) {
  return (
    <label className="library-search">
      <Search size={18} aria-hidden="true" />
      <span className="sr-only">{label}</span>
      <input
        type="search"
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
      />
      {value && (
        <button type="button" aria-label="清除搜索" onClick={() => onChange("")}>
          <X size={17} aria-hidden="true" />
        </button>
      )}
    </label>
  );
}

type AsmrPlayerProps = {
  item: AsmrItem;
  expanded: boolean;
  initialTime: number;
  onExpandedChange: (expanded: boolean) => void;
  onClose: () => void;
  onProgress: (videoId: number, time: number, duration: number) => void;
};

type PlayerSwipe = {
  pointerId: number;
  startX: number;
  startY: number;
  lastX: number;
  lastY: number;
  startTime: number;
  targetTime: number;
  axis: "pending" | "horizontal" | "cancelled";
  moved: boolean;
};

type GestureSeek = {
  targetTime: number;
  delta: number;
};

function AsmrPlayer({
  item,
  expanded,
  initialTime,
  onExpandedChange,
  onClose,
  onProgress,
}: AsmrPlayerProps) {
  const mediaRef = useRef<HTMLMediaElement | null>(null);
  const swipeRef = useRef<PlayerSwipe | null>(null);
  const initialTimeRef = useRef(initialTime);
  const onProgressRef = useRef(onProgress);
  const currentTimeRef = useRef(initialTime);
  const durationRef = useRef(item.duration ?? 0);
  const restoredRef = useRef(false);
  const lastSavedRef = useRef(initialTime);
  const chromeTimerRef = useRef<number | null>(null);
  const chromeVisibleRef = useRef(true);
  const suppressClickRef = useRef(false);
  const wakeOnlyClickRef = useRef(false);
  const [paused, setPaused] = useState(true);
  const [buffering, setBuffering] = useState(true);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [currentTime, setCurrentTime] = useState(initialTime);
  const [duration, setDuration] = useState(item.duration ?? 0);
  const [detectedKind, setDetectedKind] = useState(asmrItemKind(item));
  const [metadataReady, setMetadataReady] = useState(false);
  const [chromeVisible, setChromeVisible] = useState(true);
  const [gestureSeek, setGestureSeek] = useState<GestureSeek | null>(null);

  useEffect(() => {
    onProgressRef.current = onProgress;
  }, [onProgress]);

  const saveProgress = useCallback(() => {
    if (durationRef.current > 0) {
      onProgressRef.current(item.id, currentTimeRef.current, durationRef.current);
      lastSavedRef.current = currentTimeRef.current;
    }
  }, [item.id]);

  const revealChrome = useCallback(() => {
    if (chromeTimerRef.current !== null) window.clearTimeout(chromeTimerRef.current);
    chromeVisibleRef.current = true;
    setChromeVisible(true);
    chromeTimerRef.current = window.setTimeout(() => {
      chromeVisibleRef.current = false;
      setChromeVisible(false);
      chromeTimerRef.current = null;
    }, ASMR_CHROME_VISIBLE_MS);
  }, []);

  useEffect(() => {
    if (!expanded) {
      if (chromeTimerRef.current !== null) window.clearTimeout(chromeTimerRef.current);
      chromeTimerRef.current = null;
      return;
    }
    revealChrome();
    return () => {
      if (chromeTimerRef.current !== null) window.clearTimeout(chromeTimerRef.current);
      chromeTimerRef.current = null;
    };
  }, [expanded, revealChrome]);

  useEffect(() => {
    const media = mediaRef.current;
    if (!media) return;
    const source = attempt
      ? `${item.playUrl}?refresh=1&attempt=${attempt}`
      : item.playUrl;
    let hls: import("hls.js").default | null = null;
    let cancelled = false;
    setBuffering(true);
    setFailed(false);
    setPaused(true);
    setMetadataReady(false);
    setGestureSeek(null);
    restoredRef.current = false;

    const attachSource = async () => {
      try {
        media.preload = "auto";
        if (item.format.toLowerCase() === "m3u8") {
          const Hls = await preloadHlsEngine();
          if (cancelled) return;
          if (Hls.isSupported()) {
            hls = new Hls({
              enableWorker: true,
              maxBufferLength: 18,
              backBufferLength: 24,
              startFragPrefetch: true,
              startPosition: initialTimeRef.current > 1 ? initialTimeRef.current : -1,
            });
            hls.on(Hls.Events.ERROR, (_, data) => {
              if (!data.fatal) return;
              if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
                hls?.recoverMediaError();
              } else if (attempt < 2) {
                setAttempt((value) => value + 1);
              } else {
                setBuffering(false);
                setFailed(true);
              }
            });
            hls.loadSource(source);
            hls.attachMedia(media);
          } else {
            media.src = source;
            media.load();
          }
        } else {
          media.src = source;
          media.load();
        }
        if (!cancelled) void media.play().catch(() => setPaused(true));
      } catch {
        if (!cancelled) {
          setBuffering(false);
          setFailed(true);
        }
      }
    };
    void attachSource();
    return () => {
      cancelled = true;
      saveProgress();
      media.pause();
      hls?.destroy();
      media.removeAttribute("src");
      media.load();
    };
  }, [attempt, item.format, item.playUrl, saveProgress]);

  useEffect(() => {
    if (!expanded) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") onExpandedChange(false);
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [expanded, onExpandedChange]);

  useEffect(() => {
    const saveWhenHidden = () => {
      if (document.visibilityState === "hidden") saveProgress();
    };
    document.addEventListener("visibilitychange", saveWhenHidden);
    window.addEventListener("pagehide", saveProgress);
    return () => {
      document.removeEventListener("visibilitychange", saveWhenHidden);
      window.removeEventListener("pagehide", saveProgress);
    };
  }, [saveProgress]);

  const togglePlayback = () => {
    const media = mediaRef.current;
    if (!media) return;
    if (media.paused) void media.play();
    else media.pause();
  };

  const handleSurfaceClick = (event: React.MouseEvent<HTMLDivElement>) => {
    if ((event.target as HTMLElement).closest("button, input")) return;
    if (suppressClickRef.current || wakeOnlyClickRef.current) {
      suppressClickRef.current = false;
      wakeOnlyClickRef.current = false;
      return;
    }
    togglePlayback();
    revealChrome();
  };

  const handleSwipeStart = (event: React.PointerEvent<HTMLDivElement>) => {
    if (!expanded) return;
    if ((event.target as HTMLElement).closest("button, input")) return;
    if (!chromeVisibleRef.current) wakeOnlyClickRef.current = true;
    revealChrome();
    if (detectedKind !== "video" || event.button !== 0) return;
    const startTime = currentTimeRef.current;
    swipeRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      lastX: event.clientX,
      lastY: event.clientY,
      startTime,
      targetTime: startTime,
      axis: "pending",
      moved: false,
    };
    try {
      event.currentTarget.setPointerCapture(event.pointerId);
    } catch {
      // Pointer capture is best-effort in embedded Android webviews.
    }
  };

  const handleSwipeMove = (event: React.PointerEvent<HTMLDivElement>) => {
    const swipe = swipeRef.current;
    if (!swipe || swipe.pointerId !== event.pointerId) return;
    swipe.lastX = event.clientX;
    swipe.lastY = event.clientY;
    const deltaX = event.clientX - swipe.startX;
    const deltaY = event.clientY - swipe.startY;
    const absoluteX = Math.abs(deltaX);
    const absoluteY = Math.abs(deltaY);
    if (swipe.axis === "pending") {
      if (Math.max(absoluteX, absoluteY) < ASMR_GESTURE_AXIS_THRESHOLD_PX) return;
      if (absoluteX > absoluteY * 1.15) {
        swipe.axis = "horizontal";
        swipe.moved = true;
        suppressClickRef.current = true;
      } else if (absoluteY > absoluteX * 1.15) {
        swipe.axis = "cancelled";
      } else return;
    }
    if (swipe.axis !== "horizontal" || durationRef.current <= 0) return;
    event.preventDefault();
    const width = Math.max(1, event.currentTarget.clientWidth || window.innerWidth);
    const seekSpan = Math.min(90, Math.max(15, durationRef.current * 0.35));
    const delta = (deltaX / width) * seekSpan;
    const targetTime = Math.max(0, Math.min(durationRef.current, swipe.startTime + delta));
    swipe.targetTime = targetTime;
    setGestureSeek({ targetTime, delta: targetTime - swipe.startTime });
  };

  const handleSwipeEnd = (event: React.PointerEvent<HTMLDivElement>, cancelled: boolean) => {
    const swipe = swipeRef.current;
    if (!swipe || swipe.pointerId !== event.pointerId) return;
    swipeRef.current = null;
    try {
      if (event.currentTarget.hasPointerCapture(event.pointerId)) {
        event.currentTarget.releasePointerCapture(event.pointerId);
      }
    } catch {
      // The browser may release capture before pointerup is delivered.
    }
    if (!cancelled && swipe.axis === "horizontal" && durationRef.current > 0) {
      seek(swipe.targetTime);
    }
    setGestureSeek(null);
    if (cancelled) wakeOnlyClickRef.current = false;
    if (swipe.moved) {
      window.setTimeout(() => {
        suppressClickRef.current = false;
      }, 0);
    }
  };

  const loadedMetadata = () => {
    const media = mediaRef.current;
    if (!media) return;
    const nextDuration = Number.isFinite(media.duration) ? media.duration : 0;
    const isVideo = media instanceof HTMLVideoElement && media.videoWidth > 0;
    const nextKind = item.format.toLowerCase() === "m3u8"
      ? "video"
      : isVideo ? "video" : "audio";
    durationRef.current = nextDuration;
    setDuration(nextDuration);
    setDetectedKind(nextKind);
    setMetadataReady(true);
    const restoreTime = initialTimeRef.current;
    if (!restoredRef.current && restoreTime > 1 && restoreTime < nextDuration - 3) {
      restoredRef.current = true;
      media.currentTime = restoreTime;
      currentTimeRef.current = restoreTime;
      setCurrentTime(restoreTime);
    }
    void fetch(`/api/videos/${item.id}/metadata`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ duration: nextDuration || undefined, mediaKind: nextKind }),
    }).catch(() => undefined);
  };

  const timeUpdated = () => {
    const media = mediaRef.current;
    if (!media) return;
    currentTimeRef.current = media.currentTime;
    setCurrentTime(media.currentTime);
    if (Math.abs(media.currentTime - lastSavedRef.current) >= 3) saveProgress();
  };

  function seek(value: number) {
    const media = mediaRef.current;
    if (!media) return;
    media.currentTime = value;
    currentTimeRef.current = value;
    setCurrentTime(value);
  }

  const MediaElement = asmrItemKind(item) === "audio" ? "audio" : "video";
  const KindIcon = detectedKind === "audio" ? Headphones : Video;
  const kindLabel = detectedKind === "audio" ? "音频" : "视频";
  return (
    <aside
      className={`asmr-player${expanded ? " is-expanded" : ""}${detectedKind === "audio" ? " is-audio" : ""}`}
      role={expanded ? "dialog" : "complementary"}
      aria-modal={expanded || undefined}
      aria-label={`${item.title} 播放器`}
    >
      {expanded && chromeVisible && (
        <div className="player-header">
          <div className="player-heading">
            <span className="player-kicker"><KindIcon size={14} aria-hidden="true" />{kindLabel} · {item.format.toUpperCase()}</span>
            <strong>{item.title}</strong>
            <span>{item.author}</span>
          </div>
          <button className="icon-button player-close" type="button" aria-label="关闭播放器" onClick={() => onExpandedChange(false)}>
            <X size={20} aria-hidden="true" />
          </button>
        </div>
      )}

      <div
        className="asmr-player-media"
        onPointerDown={handleSwipeStart}
        onPointerMove={handleSwipeMove}
        onPointerUp={(event) => handleSwipeEnd(event, false)}
        onPointerCancel={(event) => handleSwipeEnd(event, true)}
        onClick={handleSurfaceClick}
      >
        <MediaElement
          ref={(node) => { mediaRef.current = node; }}
          playsInline
          preload="auto"
          poster={item.posterUrl ?? undefined}
          onLoadedMetadata={loadedMetadata}
          onDurationChange={loadedMetadata}
          onTimeUpdate={timeUpdated}
          onPlaying={() => { setPaused(false); setBuffering(false); }}
          onPause={() => { setPaused(true); saveProgress(); }}
          onWaiting={() => setBuffering(true)}
          onCanPlay={() => setBuffering(false)}
          onSeeking={() => setBuffering(true)}
          onSeeked={() => {
            if ((mediaRef.current?.readyState ?? 0) >= HTMLMediaElement.HAVE_FUTURE_DATA) {
              setBuffering(false);
            }
          }}
          onError={() => {
            if (item.format.toLowerCase() === "m3u8") return;
            if (attempt < 2) setAttempt((value) => value + 1);
            else { setBuffering(false); setFailed(true); }
          }}
        />
        {expanded && detectedKind === "audio" && (
          <div className="audio-stage" aria-hidden="true">
            {metadataReady && (
              <div className="audio-stage-content">
                <span className="audio-stage-icon"><Headphones size={34} /></span>
                <strong>{item.title}</strong>
                <span>{item.author} · {item.format.toUpperCase()}</span>
              </div>
            )}
          </div>
        )}
        {expanded && !failed && (buffering || !metadataReady) && (
          <div
            className={`player-loading${metadataReady ? " is-buffering" : ""}`}
            role="status"
            aria-label={metadataReady ? "正在缓冲" : "正在连接媒体"}
          >
            <LoaderCircle className="spinner" size={24} aria-hidden="true" />
          </div>
        )}
        {expanded && gestureSeek && (
          <div className="gesture-seek" role="status" aria-live="polite">
            {gestureSeek.delta < 0
              ? <Rewind size={22} aria-hidden="true" />
              : <FastForward size={22} aria-hidden="true" />}
            <strong>{gestureSeek.delta < 0 ? "-" : "+"}{Math.abs(Math.round(gestureSeek.delta))} 秒</strong>
            <span>{formatTime(gestureSeek.targetTime)} / {formatTime(duration)}</span>
          </div>
        )}
      </div>

      {!expanded && (
        <>
          <button
            className="mini-player-copy"
            type="button"
            aria-label={`展开 ${item.title}`}
            onClick={() => onExpandedChange(true)}
          >
            <strong>{item.title}</strong>
            <span>{item.author} · {formatTime(currentTime)} / {formatTime(duration)}</span>
          </button>

          <div className="mini-player-actions">
            <button className="icon-button" type="button" aria-label={paused ? "播放" : "暂停"} onClick={togglePlayback}>
              {buffering && !failed
                ? <LoaderCircle className="spinner" size={20} aria-hidden="true" />
                : paused ? <Play size={20} aria-hidden="true" /> : <Pause size={20} aria-hidden="true" />}
            </button>
            <button className="icon-button player-expand" type="button" aria-label="展开播放器" onClick={() => onExpandedChange(true)}>
              <Maximize2 size={20} aria-hidden="true" />
            </button>
            <button className="icon-button player-close" type="button" aria-label="关闭播放器" onClick={onClose}>
              <X size={20} aria-hidden="true" />
            </button>
          </div>
        </>
      )}

      {expanded && metadataReady && chromeVisible && (
        <div className="asmr-playback-controls">
          <button
            className="icon-button"
            type="button"
            aria-label={paused ? "播放" : "暂停"}
            onClick={() => {
              togglePlayback();
              revealChrome();
            }}
          >
            {paused ? <Play size={24} aria-hidden="true" /> : <Pause size={24} aria-hidden="true" />}
          </button>
          <input
            type="range"
            min="0"
            max={duration || 0}
            step="0.1"
            value={Math.min(currentTime, duration || 0)}
            aria-label="播放进度"
            onChange={(event) => {
              seek(Number(event.target.value));
              revealChrome();
            }}
          />
          <span>{formatTime(currentTime)} / {formatTime(duration)}</span>
        </div>
      )}

      {failed && <div className="player-error">媒体加载失败</div>}
    </aside>
  );
}

function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return "0:00";
  const rounded = Math.floor(seconds);
  const hours = Math.floor(rounded / 3600);
  const minutes = Math.floor((rounded % 3600) / 60);
  const remaining = rounded % 60;
  return hours
    ? `${hours}:${String(minutes).padStart(2, "0")}:${String(remaining).padStart(2, "0")}`
    : `${minutes}:${String(remaining).padStart(2, "0")}`;
}
