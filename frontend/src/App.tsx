import {
  ArrowLeft,
  Check,
  CheckCircle2,
  CircleAlert,
  Clock3,
  Database,
  Eye,
  EyeOff,
  Film,
  FastForward,
  Gauge,
  HardDrive,
  LoaderCircle,
  LockKeyhole,
  LogIn,
  LogOut,
  Maximize2,
  Minimize2,
  Pause,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  Rewind,
  ScanSearch,
  Settings2,
  Shuffle,
  SlidersHorizontal,
  Volume2,
  VolumeX,
  XCircle,
} from "lucide-react";
import { lazy, Suspense, useCallback, useEffect, useRef, useState } from "react";
import {
  loadPlaybackState,
  updatePlaybackState,
  type FeedMode,
  type PlaybackState,
} from "./playbackStore";
import type { MediaSurface } from "./playbackStore";

const AsmrLibrary = lazy(() => import("./AsmrLibrary"));

type VideoItem = {
  id: number;
  title: string;
  size: number;
  modified: string | null;
  playUrl: string;
  posterUrl: string | null;
  duration: number | null;
};

type FeedResponse = {
  items: VideoItem[];
  nextCursor: string | null;
  total: number;
  scan: { running: boolean; lastError: string | null };
};

type CachedFeedEntry = {
  items: VideoItem[];
  total: number;
  savedAt: number;
};

type FastStartSummary = {
  total: number;
  checked: number;
  optimized: number;
  notOptimized: number;
  inconclusive: number;
  errors: number;
  pending: number;
};

type LibrarySection = "feed" | "asmr";

type MediaLibrarySource = {
  id: string;
  name: string;
  provider: "alist" | "openlist";
  baseUrl: string;
  rootPath: string;
  section: LibrarySection;
  scanMode: "tree" | "authors" | "authors_recursive";
  anonymous: boolean;
  usernameConfigured: boolean;
  tokenConfigured: boolean;
  enabled: boolean;
  videos: number;
  bytes: number;
  scan: {
    running: boolean;
    lastSuccess: number | null;
    lastError: string | null;
    directories: number;
  };
};

type MediaSourceDraft = Omit<
  MediaLibrarySource,
  "id" | "usernameConfigured" | "tokenConfigured" | "videos" | "bytes" | "scan"
> & { username: string; password: string; token: string };

type AdminStatus = {
  library: {
    videos: number;
    bytes: number;
    guangya: { videos: number; bytes: number };
    asmr: { videos: number; bytes: number };
  };
  scan: {
    running: boolean;
    lastSuccess: number | null;
    lastError: string | null;
    directories: number;
    sources: Record<string, {
      running: boolean;
      lastSuccess: number | null;
      lastError: string | null;
      directories: number;
    }>;
  };
  sources: MediaLibrarySource[];
  fastStart: {
    running: boolean;
    checked: number;
    total: number;
    lastSuccess: number | null;
    lastError: string | null;
    summary: FastStartSummary;
  };
  issues: Array<{
    id: number;
    name: string;
    path: string;
    fast_start: "not_optimized" | "inconclusive" | "error";
    fast_start_detail: string;
  }>;
};

type AdminAction = `scan-${string}` | `source-${string}` | "fast-start";

const MODES: Array<{ value: FeedMode; label: string; icon: typeof Shuffle }> = [
  { value: "shuffle", label: "随机播放", icon: Shuffle },
  { value: "newest", label: "最新优先", icon: Clock3 },
  { value: "oldest", label: "最早优先", icon: Clock3 },
];

const LANDSCAPE_CHROME_VISIBLE_MS = 5000;
const GESTURE_AXIS_THRESHOLD_PX = 14;
const AUTH_HINT_KEY = "short-video-authenticated";
const FEED_CACHE_KEY = "short-video-feed-cache-v1";
const FEED_CACHE_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;

function hasAuthenticationHint(): boolean {
  try {
    return localStorage.getItem(AUTH_HINT_KEY) === "1";
  } catch {
    return false;
  }
}

function setAuthenticationHint(authenticated: boolean): void {
  try {
    if (authenticated) localStorage.setItem(AUTH_HINT_KEY, "1");
    else localStorage.removeItem(AUTH_HINT_KEY);
  } catch {
    // Authentication still works when persistent browser storage is unavailable.
  }
}

function feedCacheId(surface: Exclude<MediaSurface, "asmr">, mode: FeedMode): string {
  return `${surface}:${mode}`;
}

function readFeedCache(
  surface: Exclude<MediaSurface, "asmr">,
  mode: FeedMode,
): CachedFeedEntry | null {
  try {
    const raw = localStorage.getItem(FEED_CACHE_KEY);
    if (!raw) return null;
    const cache = JSON.parse(raw) as { version?: number; entries?: Record<string, CachedFeedEntry> };
    const entry = cache.version === 1 ? cache.entries?.[feedCacheId(surface, mode)] : undefined;
    if (!entry || !Array.isArray(entry.items) || !Number.isFinite(entry.savedAt)) return null;
    if (Date.now() - entry.savedAt > FEED_CACHE_MAX_AGE_MS) return null;
    return entry;
  } catch {
    return null;
  }
}

function writeFeedCache(
  surface: Exclude<MediaSurface, "asmr">,
  mode: FeedMode,
  items: VideoItem[],
  total: number,
): void {
  try {
    const raw = localStorage.getItem(FEED_CACHE_KEY);
    const parsed = raw
      ? JSON.parse(raw) as { version?: number; entries?: Record<string, CachedFeedEntry> }
      : null;
    const entries = parsed?.version === 1 && parsed.entries ? parsed.entries : {};
    entries[feedCacheId(surface, mode)] = {
      items: items.slice(0, 12),
      total,
      savedAt: Date.now(),
    };
    localStorage.setItem(FEED_CACHE_KEY, JSON.stringify({ version: 1, entries }));
  } catch {
    // A missing warm cache only affects startup speed, never playback correctness.
  }
}

type LandscapeGesture = {
  pointerId: number;
  startX: number;
  startY: number;
  lastX: number;
  lastY: number;
  startTime: number;
  targetTime: number;
  axis: "pending" | "horizontal" | "vertical";
  moved: boolean;
  softwareRotated: boolean;
};

function getLandscapeGestureDelta(gesture: LandscapeGesture, clientX: number, clientY: number) {
  const rawX = clientX - gesture.startX;
  const rawY = clientY - gesture.startY;
  return gesture.softwareRotated
    ? { x: rawY, y: -rawX }
    : { x: rawX, y: rawY };
}

function App() {
  const [authState, setAuthState] = useState<"checking" | "signed-in" | "signed-out">(
    () => hasAuthenticationHint() ? "signed-in" : "checking",
  );
  const [statusError, setStatusError] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    void fetch("/api/auth/status", { cache: "no-store", signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = (await response.json()) as { authenticated: boolean };
        setAuthenticationHint(data.authenticated);
        setAuthState(data.authenticated ? "signed-in" : "signed-out");
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === "AbortError") return;
        if (!hasAuthenticationHint()) {
          setStatusError("暂时无法连接服务器");
          setAuthState("signed-out");
        }
      });
    return () => controller.abort();
  }, []);

  const requireLogin = useCallback(() => {
    setAuthenticationHint(false);
    setAuthState("signed-out");
  }, []);

  if (authState === "checking") return <AuthLoading />;
  if (authState === "signed-out") {
    return (
      <LoginScreen
        initialError={statusError}
        onAuthenticated={() => {
          setAuthenticationHint(true);
          setAuthState("signed-in");
        }}
      />
    );
  }
  return <PlayerApp onUnauthorized={requireLogin} />;
}

function AuthLoading() {
  return (
    <main className="auth-shell">
      <div className="auth-loading" role="status" aria-label="正在检查登录状态">
        <LoaderCircle className="spinner" size={28} aria-hidden="true" />
      </div>
    </main>
  );
}

type LoginScreenProps = {
  initialError: string;
  onAuthenticated: () => void;
};

function LoginScreen({ initialError, onAuthenticated }: LoginScreenProps) {
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(initialError);
  const inputRef = useRef<HTMLInputElement>(null);

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting) return;
    if (!password) {
      setError("请输入密码");
      inputRef.current?.focus();
      return;
    }
    setSubmitting(true);
    setError("");
    try {
      const response = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ password }),
      });
      if (response.ok) {
        onAuthenticated();
        return;
      }
      if (response.status === 429) {
        const seconds = Math.max(1, Number(response.headers.get("Retry-After") || 60));
        setError(`尝试次数过多，请在 ${Math.ceil(seconds / 60)} 分钟后重试`);
      } else if (response.status === 401) {
        setError("密码不正确");
      } else {
        setError("暂时无法登录");
      }
      inputRef.current?.focus();
      inputRef.current?.select();
    } catch {
      setError("暂时无法连接服务器");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="auth-shell">
      <div className="auth-brand" aria-label="短片">
        <span aria-hidden="true" />
        短片
      </div>
      <section className="auth-panel" aria-labelledby="auth-title">
        <LockKeyhole className="auth-mark" size={30} strokeWidth={1.8} aria-hidden="true" />
        <h1 id="auth-title">继续上次播放</h1>
        <p className="auth-copy">输入访问密码</p>
        <form className="auth-form" onSubmit={submit} noValidate>
          <input type="text" name="username" value="short-video" autoComplete="username" readOnly hidden />
          <label htmlFor="access-password">访问密码</label>
          <div className={`password-control${error ? " has-error" : ""}`}>
            <input
              ref={inputRef}
              id="access-password"
              name="password"
              type={showPassword ? "text" : "password"}
              value={password}
              autoComplete="current-password"
              aria-invalid={Boolean(error)}
              aria-describedby={error ? "password-error" : undefined}
              onChange={(event) => {
                setPassword(event.target.value);
                if (error) setError("");
              }}
            />
            <button
              className="password-toggle"
              type="button"
              aria-label={showPassword ? "隐藏密码" : "显示密码"}
              onClick={() => setShowPassword((visible) => !visible)}
            >
              {showPassword ? <EyeOff size={20} aria-hidden="true" /> : <Eye size={20} aria-hidden="true" />}
            </button>
          </div>
          <div className="auth-error" id="password-error" role="alert" aria-live="polite">
            {error}
          </div>
          <button className="auth-submit" type="submit" disabled={submitting}>
            {submitting ? <LoaderCircle className="spinner" size={19} aria-hidden="true" /> : <LogIn size={19} aria-hidden="true" />}
            {submitting ? "正在验证" : "进入"}
          </button>
        </form>
      </section>
    </main>
  );
}

type PlayerAppProps = { onUnauthorized: () => void };

function PlayerApp({ onUnauthorized }: PlayerAppProps) {
  const [preferences, setPreferences] = useState<PlaybackState | null>(null);
  const [mode, setMode] = useState<FeedMode>("shuffle");
  const [surface, setSurface] = useState<MediaSurface>("short");
  const [items, setItems] = useState<VideoItem[]>([]);
  const [activeIndex, setActiveIndex] = useState(0);
  const [landscapeIndex, setLandscapeIndex] = useState<number | null>(null);
  const [muted, setMuted] = useState(true);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [total, setTotal] = useState(0);
  const [menuOpen, setMenuOpen] = useState(false);
  const [view, setView] = useState<"player" | "manage">(
    window.location.pathname === "/manage" ? "manage" : "player",
  );
  const feedRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const cursorRef = useRef<string | null>(null);
  const activeIndexRef = useRef(0);
  const landscapeIndexRef = useRef<number | null>(null);
  const loadingRef = useRef(false);
  const generationRef = useRef(0);
  const abortRef = useRef<AbortController | null>(null);
  const initialFeedRef = useRef(true);
  const recentRef = useRef<number[]>([]);
  const sessionExcludeRef = useRef<number[]>([]);
  const sessionStartRef = useRef<number | null>(null);

  useEffect(() => {
    let cancelled = false;
    void loadPlaybackState().then((state) => {
      if (cancelled) return;
      recentRef.current = state.recentVideoIds;
      setMode(state.mode);
      setSurface(state.surface);
      setMuted(state.muted);
      setPreferences(state);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const handlePopState = () => {
      setView(window.location.pathname === "/manage" ? "manage" : "player");
      setMenuOpen(false);
    };
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  useEffect(() => {
    if (!menuOpen) return;
    const closeMenu = (event: PointerEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) setMenuOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setMenuOpen(false);
    };
    document.addEventListener("pointerdown", closeMenu);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", closeMenu);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [menuOpen]);

  const requestPage = useCallback(async (
    reset: boolean,
    requestedMode: FeedMode,
    requestedSurface: Exclude<MediaSurface, "asmr">,
  ) => {
    if (reset) abortRef.current?.abort();
    else if (loadingRef.current || !cursorRef.current) return;
    const controller = new AbortController();
    abortRef.current = controller;
    loadingRef.current = true;
    setLoading(true);
    setError("");
    const generation = generationRef.current;
    const params = new URLSearchParams({
      limit: "12",
      mode: requestedMode,
      category: requestedSurface,
    });
    if (!reset && cursorRef.current) params.set("cursor", cursorRef.current);
    if (sessionExcludeRef.current.length) params.set("exclude", sessionExcludeRef.current.join(","));
    if (sessionStartRef.current) params.set("start", String(sessionStartRef.current));

    try {
      const response = await fetch(`/api/feed?${params.toString()}`, {
        cache: "no-store",
        signal: controller.signal,
      });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = (await response.json()) as FeedResponse;
      if (generation !== generationRef.current) return;
      if (reset && (data.items.length > 0 || !data.scan.running)) {
        writeFeedCache(requestedSurface, requestedMode, data.items, data.total);
      }
      setItems((current) => {
        if (reset) {
          if (data.items.length === 0 && data.scan.running && current.length > 0) return current;
          return data.items;
        }
        const loaded = new Set(current.map((item) => item.id));
        return [...current, ...data.items.filter((item) => !loaded.has(item.id))];
      });
      cursorRef.current = data.nextCursor;
      setTotal(data.total);

      if (reset && data.items.length === 0 && data.scan.running) {
        window.setTimeout(() => {
          if (generation === generationRef.current) {
            void requestPage(true, requestedMode, requestedSurface);
          }
        }, 1600);
      } else if (reset && data.items.length === 0 && data.scan.lastError) {
        setError("目录索引失败，请稍后重试");
      }
    } catch (requestError) {
      if (requestError instanceof DOMException && requestError.name === "AbortError") return;
      if (generation === generationRef.current) setError("无法载入视频列表");
    } finally {
      if (abortRef.current === controller) {
        loadingRef.current = false;
        if (generation === generationRef.current) setLoading(false);
      }
    }
  }, [onUnauthorized]);

  useEffect(() => {
    if (!preferences || surface === "asmr") return;
    generationRef.current += 1;
    cursorRef.current = null;
    sessionExcludeRef.current = mode === "shuffle" ? [...recentRef.current] : [];
    sessionStartRef.current = preferences.lastVideoIds[surface] ?? null;
    initialFeedRef.current = false;
    const cached = readFeedCache(surface, mode);
    setItems(cached?.items ?? []);
    setTotal(cached?.total ?? 0);
    activeIndexRef.current = 0;
    setActiveIndex(0);
    feedRef.current?.scrollTo({ top: 0 });
    void requestPage(true, mode, surface);
    return () => abortRef.current?.abort();
  }, [mode, preferences, requestPage, surface]);

  useEffect(() => {
    if (!cursorRef.current || activeIndex < items.length - 4) return;
    if (surface !== "asmr") void requestPage(false, mode, surface);
  }, [activeIndex, items.length, mode, requestPage, surface]);

  useEffect(() => {
    if (view !== "player") return;
    const root = feedRef.current;
    if (!root) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (landscapeIndexRef.current !== null) return;
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((left, right) => right.intersectionRatio - left.intersectionRatio)[0];
        const rawIndex = visible?.target.getAttribute("data-index");
        if (rawIndex !== null && rawIndex !== undefined) {
          const nextIndex = Number(rawIndex);
          activeIndexRef.current = nextIndex;
          setActiveIndex(nextIndex);
        }
      },
      { root, threshold: [0.6, 0.8] },
    );
    root.querySelectorAll("[data-index]").forEach((element) => observer.observe(element));
    requestAnimationFrame(() => {
      root.querySelector(`[data-index="${activeIndexRef.current}"]`)?.scrollIntoView({ block: "start" });
    });
    return () => observer.disconnect();
  }, [items, view]);

  const markWatched = useCallback((videoId: number) => {
    const recent = [videoId, ...recentRef.current.filter((id) => id !== videoId)].slice(0, 100);
    recentRef.current = recent;
    updatePlaybackState((state) => ({
      ...state,
      lastVideoId: videoId,
      lastVideoIds: { ...state.lastVideoIds, [surface]: videoId },
      recentVideoIds: recent,
    }));
  }, [surface]);

  const recordProgress = useCallback((videoId: number, rawTime: number, duration: number) => {
    if (!Number.isFinite(rawTime) || !Number.isFinite(duration) || duration <= 0) return;
    const time = duration - rawTime <= 3 || rawTime / duration >= 0.97 ? 0 : Math.max(0, rawTime);
    updatePlaybackState((state) => ({
      ...state,
      lastVideoId: videoId,
      positions: {
        ...state.positions,
        [String(videoId)]: { time, duration, updatedAt: Date.now() },
      },
    }));
  }, []);

  const changeMode = (nextMode: FeedMode) => {
    setMode(nextMode);
    setMenuOpen(false);
    updatePlaybackState({ mode: nextMode });
  };

  const changeSurface = (nextSurface: MediaSurface) => {
    if (nextSurface === surface) return;
    landscapeIndexRef.current = null;
    setLandscapeIndex(null);
    setMenuOpen(false);
    setSurface(nextSurface);
    updatePlaybackState({ surface: nextSurface });
  };

  const changeMuted = useCallback((nextMuted: boolean) => {
    setMuted(nextMuted);
    updatePlaybackState({ muted: nextMuted });
  }, []);

  const beginLandscape = useCallback((index: number) => {
    landscapeIndexRef.current = index;
    activeIndexRef.current = index;
    setLandscapeIndex(index);
    setActiveIndex(index);
  }, []);

  const finishLandscape = useCallback((index: number) => {
    if (landscapeIndexRef.current !== index) return;
    landscapeIndexRef.current = null;
    activeIndexRef.current = index;
    setLandscapeIndex(null);
    setActiveIndex(index);
    requestAnimationFrame(() => {
      const feed = feedRef.current;
      feed?.scrollTo({ top: index * feed.clientHeight, behavior: "auto" });
    });
  }, []);

  const navigateLandscape = useCallback((index: number, direction: -1 | 1) => {
    if (landscapeIndexRef.current !== index) return;
    const nextIndex = Math.max(0, Math.min(items.length - 1, index + direction));
    if (nextIndex === index) return;
    const feed = feedRef.current;
    feed?.scrollTo({ top: nextIndex * feed.clientHeight, behavior: "auto" });
    landscapeIndexRef.current = nextIndex;
    activeIndexRef.current = nextIndex;
    setLandscapeIndex(nextIndex);
    setActiveIndex(nextIndex);
  }, [items.length]);

  const keepSinglePlayback = useCallback(() => {
    const allowedIndex = landscapeIndexRef.current ?? activeIndexRef.current;
    feedRef.current?.querySelectorAll<HTMLVideoElement>("video").forEach((video) => {
      const ownerIndex = Number(video.closest<HTMLElement>("[data-index]")?.dataset.index);
      if (ownerIndex !== allowedIndex && !video.paused) video.pause();
    });
  }, []);

  const openManagement = () => {
    setMenuOpen(false);
    window.history.pushState({ shortVideoManage: true }, "", "/manage");
    setView("manage");
  };

  const closeManagement = () => {
    if (window.history.state?.shortVideoManage) {
      window.history.back();
    } else {
      window.history.replaceState({}, "", "/");
      setView("player");
    }
  };

  const logout = async () => {
    setMenuOpen(false);
    try {
      const response = await fetch("/api/auth/logout", { method: "POST" });
      if (response.ok || response.status === 401) onUnauthorized();
      else setError("暂时无法退出登录");
    } catch {
      setError("暂时无法退出登录");
    }
  };

  if (view === "manage") {
    return <ManagementView onBack={closeManagement} onUnauthorized={onUnauthorized} />;
  }

  const selectedMode = MODES.find((entry) => entry.value === mode) ?? MODES[0];
  const effectiveActiveIndex = landscapeIndex ?? activeIndex;

  return (
    <main className={`app-shell${surface === "asmr" ? " is-library" : ""}`}>
      <header className="top-bar">
        <div className="surface-tabs" role="tablist" aria-label="媒体分类">
          {([
            ["short", "短视频"],
            ["long", "长视频"],
            ["asmr", "ASMR"],
          ] as const).map(([value, label]) => (
            <button
              key={value}
              type="button"
              role="tab"
              aria-selected={surface === value}
              onClick={() => changeSurface(value)}
            >
              {label}
            </button>
          ))}
        </div>
        <div className="mode-control" ref={menuRef}>
          <button
            className="icon-button"
            type="button"
            aria-label="播放设置"
            aria-expanded={menuOpen}
            aria-controls="player-menu"
            onClick={() => setMenuOpen((open) => !open)}
          >
            <SlidersHorizontal size={21} strokeWidth={2} aria-hidden="true" />
          </button>
          {menuOpen && (
            <div className="mode-menu" id="player-menu" role="menu">
              {surface !== "asmr" && MODES.map((entry) => {
                const Icon = entry.icon;
                return (
                  <button
                    type="button"
                    role="menuitemradio"
                    aria-checked={entry.value === mode}
                    key={entry.value}
                    onClick={() => changeMode(entry.value)}
                  >
                    <Icon size={18} aria-hidden="true" />
                    <span>{entry.label}</span>
                    {entry.value === mode && <Check className="menu-check" size={17} aria-hidden="true" />}
                  </button>
                );
              })}
              {surface !== "asmr" && <div className="menu-separator" role="separator" />}
              <button type="button" role="menuitem" onClick={openManagement}>
                <Settings2 size={18} aria-hidden="true" />
                <span>管理</span>
                <span />
              </button>
              <button className="logout-item" type="button" role="menuitem" onClick={() => void logout()}>
                <LogOut size={18} aria-hidden="true" />
                <span>退出登录</span>
                <span />
              </button>
            </div>
          )}
        </div>
      </header>

      {surface === "asmr" ? (
        <Suspense fallback={<div className="feed-state"><LoaderCircle className="spinner" size={28} aria-hidden="true" /></div>}>
          <AsmrLibrary
            positions={preferences?.positions ?? {}}
            onProgress={recordProgress}
            onUnauthorized={onUnauthorized}
          />
        </Suspense>
      ) : <div className="feed" ref={feedRef} aria-label={`${selectedMode.label}视频列表`}>
        {items.map((item, index) => (
          <VideoSlide
            key={`${mode}-${item.id}`}
            item={item}
            index={index}
            total={total}
            active={index === effectiveActiveIndex}
            nearby={Math.abs(index - effectiveActiveIndex) <= 1}
            landscape={index === landscapeIndex}
            muted={muted}
            initialTime={preferences?.positions[String(item.id)]?.time ?? 0}
            onMutedChange={changeMuted}
            onProgress={recordProgress}
            onWatched={markWatched}
            onLandscapeIntent={beginLandscape}
            onLandscapeExit={finishLandscape}
            onLandscapeNavigate={navigateLandscape}
            onPlaybackStarted={keepSinglePlayback}
          />
        ))}

        {items.length === 0 && (
          <div className="feed-state" role="status">
            {loading || !preferences ? (
              <>
                <LoaderCircle className="spinner" size={28} aria-hidden="true" />
                <p>正在准备视频</p>
              </>
            ) : (
              <>
                <p>{error || "目录中没有可播放的视频"}</p>
                {error && (
                  <button className="retry-button" type="button" onClick={() => void requestPage(true, mode, surface)}>
                    <RefreshCw size={18} aria-hidden="true" />
                    重试
                  </button>
                )}
              </>
            )}
          </div>
        )}
      </div>}

      {error && items.length > 0 && <div className="notice" role="status">{error}</div>}
    </main>
  );
}

type ManagementViewProps = {
  onBack: () => void;
  onUnauthorized: () => void;
};

function ManagementView({ onBack, onUnauthorized }: ManagementViewProps) {
  const [status, setStatus] = useState<AdminStatus | null>(null);
  const [error, setError] = useState("");
  const [actions, setActions] = useState<Set<AdminAction>>(() => new Set());
  const [editingSource, setEditingSource] = useState<MediaLibrarySource | null | undefined>();
  const sourceDialogTrigger = useRef<HTMLElement | null>(null);
  const closeSourceDialog = useCallback(() => setEditingSource(undefined), []);

  const refresh = useCallback(async () => {
    try {
      const response = await fetch("/api/admin/status", { cache: "no-store" });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      setStatus((await response.json()) as AdminStatus);
      setError("");
    } catch {
      setError("无法读取服务状态");
    }
  }, [onUnauthorized]);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => void refresh(), 3000);
    return () => window.clearInterval(timer);
  }, [refresh]);

  const runAction = async (kind: AdminAction) => {
    if (actions.has(kind)) return;
    setActions((current) => new Set(current).add(kind));
    setError("");
    const force = kind === "fast-start" && status?.fastStart.summary.pending === 0;
    const endpoint = kind === "fast-start"
      ? `/api/admin/fast-start?force=${force ? "true" : "false"}`
      : `/api/admin/sources/${encodeURIComponent(kind.slice(5))}/scan`;
    try {
      const response = await fetch(endpoint, { method: "POST" });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      await refresh();
    } catch {
      setError(kind.startsWith("scan-") ? "无法启动目录扫描" : "无法启动 Fast Start 检查");
    } finally {
      setActions((current) => {
        const next = new Set(current);
        next.delete(kind);
        return next;
      });
    }
  };

  const saveSource = async (source: MediaLibrarySource | null, draft: MediaSourceDraft) => {
    const action: AdminAction = `source-${source?.id ?? "new"}`;
    if (actions.has(action)) return;
    setActions((current) => new Set(current).add(action));
    setError("");
    try {
      const response = await fetch(
        source ? `/api/admin/sources/${encodeURIComponent(source.id)}` : "/api/admin/sources",
        {
          method: source ? "PUT" : "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(draft),
        },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      const body = await response.json().catch(() => ({})) as { detail?: string };
      if (!response.ok) throw new Error(body.detail || `HTTP ${response.status}`);
      setEditingSource(undefined);
      await refresh();
    } catch (sourceError) {
      setError(sourceError instanceof Error ? sourceError.message : "无法保存媒体源");
    } finally {
      setActions((current) => {
        const next = new Set(current);
        next.delete(action);
        return next;
      });
    }
  };

  const summary = status?.fastStart.summary;
  const optimizedPercent = summary?.checked ? (summary.optimized / summary.checked) * 100 : 0;
  const issuePercent = summary?.checked
    ? ((summary.notOptimized + summary.errors) / summary.checked) * 100
    : 0;
  const inconclusivePercent = summary?.checked ? (summary.inconclusive / summary.checked) * 100 : 0;

  return (
    <main className="manage-shell" id="main-content">
      <header className="manage-header">
        <button className="icon-button manage-back" type="button" aria-label="返回播放器" onClick={onBack}>
          <ArrowLeft size={22} aria-hidden="true" />
        </button>
        <div>
          <h1>媒体管理</h1>
          <p>{status ? formatDate(status.scan.lastSuccess) : "正在读取状态"}</p>
        </div>
        <button
          className="icon-button manage-add"
          type="button"
          aria-label="添加媒体源"
          onClick={(event) => {
            sourceDialogTrigger.current = event.currentTarget;
            setEditingSource(null);
          }}
        >
          <Plus size={22} aria-hidden="true" />
        </button>
      </header>

      {error && <div className="manage-error" role="alert">{error}</div>}

      {!status ? (
        <div className="manage-loading" role="status">
          <LoaderCircle className="spinner" size={28} aria-hidden="true" />
        </div>
      ) : (
        <div className="manage-content">
          <section className="library-overview" aria-labelledby="library-title">
            <div className="section-heading">
              <div>
                <p className="section-label">媒体库</p>
                <h2 id="library-title">{status.library.videos}<span> 个视频</span></h2>
              </div>
              <Film size={28} strokeWidth={1.6} aria-hidden="true" />
            </div>
            <dl className="library-facts">
              <div>
                <dt><HardDrive size={17} aria-hidden="true" />容量</dt>
                <dd>{formatBytes(status.library.bytes)}</dd>
              </div>
              <div>
                <dt><Database size={17} aria-hidden="true" />目录</dt>
                <dd>{status.scan.directories}</dd>
              </div>
              <div>
                <dt><Gauge size={17} aria-hidden="true" />索引</dt>
                <dd>{status.scan.running ? "扫描中" : status.scan.lastError ? "异常" : "正常"}</dd>
              </div>
            </dl>
            <div className="source-scans">
              {status.sources.map((source) => {
                const action: AdminAction = `scan-${source.id}`;
                const busy = source.scan.running || actions.has(action);
                return (
                  <div className="source-scan-row" key={source.id}>
                    <div className="source-scan-copy">
                      <div className="source-scan-heading">
                        <h3>{source.name}</h3>
                        <span>{source.section === "feed" ? "短视频 / 长视频" : "ASMR"}</span>
                      </div>
                      <p className="source-address">{source.baseUrl}{source.rootPath}</p>
                      <p>{source.videos} 项 · {source.scan.directories} 个目录 · {source.enabled ? formatDate(source.scan.lastSuccess) : "已停用"}</p>
                      {source.scan.lastError && <p className="source-scan-error" role="alert">{source.scan.lastError}</p>}
                    </div>
                    <div className="source-actions">
                      <button
                        className="icon-button"
                        type="button"
                        aria-label={`编辑 ${source.name}`}
                        onClick={(event) => {
                          sourceDialogTrigger.current = event.currentTarget;
                          setEditingSource(source);
                        }}
                      >
                        <Pencil size={18} aria-hidden="true" />
                      </button>
                      <button
                        className="secondary-action"
                        type="button"
                        disabled={!source.enabled || busy}
                        aria-busy={busy}
                        onClick={() => void runAction(action)}
                      >
                        {busy ? <LoaderCircle className="spinner" size={18} aria-hidden="true" /> : <RefreshCw size={18} aria-hidden="true" />}
                        {busy ? "扫描中" : "扫描"}
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          </section>

          <section className="fast-start-section" aria-labelledby="fast-start-title">
            <div className="section-heading compact">
              <div>
                <p className="section-label">播放启动</p>
                <h2 id="fast-start-title">MP4 Fast Start</h2>
              </div>
              <ScanSearch size={27} strokeWidth={1.6} aria-hidden="true" />
            </div>

            <div
              className="health-track"
              role="img"
              aria-label={`已优化 ${summary?.optimized ?? 0}，未优化 ${summary?.notOptimized ?? 0}，无法判断 ${summary?.inconclusive ?? 0}，错误 ${summary?.errors ?? 0}`}
            >
              <span className="healthy" style={{ width: `${optimizedPercent}%` }} />
              <span className="unhealthy" style={{ width: `${issuePercent}%` }} />
              <span className="unknown" style={{ width: `${inconclusivePercent}%` }} />
            </div>

            <dl className="fast-start-facts">
              <div>
                <dt><CheckCircle2 size={17} aria-hidden="true" />已优化</dt>
                <dd>{summary?.optimized ?? 0}</dd>
              </div>
              <div>
                <dt><XCircle size={17} aria-hidden="true" />未优化</dt>
                <dd>{summary?.notOptimized ?? 0}</dd>
              </div>
              <div>
                <dt><CircleAlert size={17} aria-hidden="true" />待确认</dt>
                <dd>{(summary?.pending ?? 0) + (summary?.inconclusive ?? 0) + (summary?.errors ?? 0)}</dd>
              </div>
            </dl>

            <div className="section-action">
              {status.fastStart.running ? (
                <span className="running-label" role="status">
                  <LoaderCircle className="spinner" size={17} aria-hidden="true" />
                  正在检查 {status.fastStart.checked} / {status.fastStart.total}
                </span>
              ) : (
                <button className="primary-action" type="button" onClick={() => void runAction("fast-start")}>
                  {actions.has("fast-start") ? <LoaderCircle className="spinner" size={18} aria-hidden="true" /> : <ScanSearch size={18} aria-hidden="true" />}
                  {actions.has("fast-start") ? "正在启动" : summary?.pending ? "检查未分析视频" : "重新检查"}
                </button>
              )}
            </div>
          </section>

          <section className="issue-section" aria-labelledby="issue-title">
            <div className="issue-heading">
              <h2 id="issue-title">需要关注</h2>
              <span>{status.issues.length}</span>
            </div>
            {status.issues.length ? (
              <ul className="issue-list">
                {status.issues.map((issue) => (
                  <li key={issue.id}>
                    <div>
                      <p>{issue.name}</p>
                      <span>{issue.path}</span>
                    </div>
                    <strong>{fastStartLabel(issue.fast_start)}</strong>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="empty-issues">已检查的视频没有发现启动布局问题</p>
            )}
          </section>
        </div>
      )}
      {editingSource !== undefined && (
        <MediaSourceDialog
          source={editingSource}
          saving={actions.has(`source-${editingSource?.id ?? "new"}`)}
          returnFocus={sourceDialogTrigger.current}
          onClose={closeSourceDialog}
          onSave={(draft) => void saveSource(editingSource, draft)}
        />
      )}
    </main>
  );
}

type MediaSourceDialogProps = {
  source: MediaLibrarySource | null;
  saving: boolean;
  returnFocus: HTMLElement | null;
  onClose: () => void;
  onSave: (draft: MediaSourceDraft) => void;
};

function MediaSourceDialog({ source, saving, returnFocus, onClose, onSave }: MediaSourceDialogProps) {
  const dialogRef = useRef<HTMLElement>(null);
  const [name, setName] = useState(source?.name ?? "");
  const [provider, setProvider] = useState<"alist" | "openlist">(source?.provider ?? "alist");
  const [baseUrl, setBaseUrl] = useState(source?.baseUrl ?? "");
  const [rootPath, setRootPath] = useState(source?.rootPath ?? "/");
  const [section, setSection] = useState<LibrarySection>(source?.section ?? "feed");
  const [scanMode, setScanMode] = useState<"tree" | "authors" | "authors_recursive">(
    source?.scanMode ?? "tree",
  );
  const [anonymous, setAnonymous] = useState(source?.anonymous ?? true);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [token, setToken] = useState("");
  const [enabled, setEnabled] = useState(source?.enabled ?? true);
  const valid = Boolean(name.trim() && /^https?:\/\//i.test(baseUrl.trim()) && rootPath.trim());

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
      if (event.key !== "Tab") return;
      const focusable = dialogRef.current?.querySelectorAll<HTMLElement>(
        "button:not([disabled]), input:not([disabled]), select:not([disabled])",
      );
      if (!focusable?.length) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener("keydown", closeOnEscape);
    return () => {
      window.removeEventListener("keydown", closeOnEscape);
      returnFocus?.focus();
    };
  }, [onClose, returnFocus]);

  return (
    <div className="source-dialog-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section ref={dialogRef} className="source-dialog" role="dialog" aria-modal="true" aria-labelledby="source-dialog-title">
        <header>
          <h2 id="source-dialog-title">{source ? "编辑媒体源" : "添加媒体源"}</h2>
          <button className="icon-button" type="button" aria-label="关闭" onClick={onClose}>
            <XCircle size={21} aria-hidden="true" />
          </button>
        </header>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (!valid || saving) return;
            onSave({
              name: name.trim(),
              provider,
              baseUrl: baseUrl.trim(),
              rootPath: rootPath.trim(),
              section,
              scanMode: section === "feed" ? "tree" : scanMode === "tree" ? "authors_recursive" : scanMode,
              anonymous,
              username,
              password,
              token,
              enabled,
            });
          }}
        >
          <label>
            <span>名称</span>
            <input value={name} onChange={(event) => setName(event.target.value)} autoFocus required />
          </label>
          <div className="source-form-pair">
            <label>
              <span>服务类型</span>
              <select value={provider} onChange={(event) => setProvider(event.target.value as "alist" | "openlist")}>
                <option value="alist">AList</option>
                <option value="openlist">OpenList</option>
              </select>
            </label>
            <label>
              <span>归属板块</span>
              <select
                value={section}
                onChange={(event) => {
                  const value = event.target.value as LibrarySection;
                  setSection(value);
                  setScanMode(value === "feed" ? "tree" : "authors_recursive");
                }}
              >
                <option value="feed">短视频 / 长视频</option>
                <option value="asmr">ASMR</option>
              </select>
            </label>
          </div>
          <label>
            <span>服务地址</span>
            <input type="url" value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} placeholder="https://example.com" required />
          </label>
          <label>
            <span>根目录</span>
            <input value={rootPath} onChange={(event) => setRootPath(event.target.value)} placeholder="/media" required />
          </label>
          {section === "asmr" && (
            <label>
              <span>作者目录结构</span>
              <select value={scanMode} onChange={(event) => setScanMode(event.target.value as "authors" | "authors_recursive")}>
                <option value="authors">一级作者目录</option>
                <option value="authors_recursive">嵌套作者目录</option>
              </select>
            </label>
          )}
          <label className="source-toggle">
            <span>匿名访问</span>
            <input type="checkbox" checked={anonymous} onChange={(event) => setAnonymous(event.target.checked)} />
          </label>
          {!anonymous && (
            <>
              <label>
                <span>用户名{source?.usernameConfigured ? "（已配置）" : ""}</span>
                <input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" />
              </label>
              <label>
                <span>密码</span>
                <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} placeholder="留空保持不变" autoComplete="new-password" />
              </label>
              <label>
                <span>令牌{source?.tokenConfigured ? "（已配置）" : ""}</span>
                <input type="password" value={token} onChange={(event) => setToken(event.target.value)} placeholder="可选，留空保持不变" autoComplete="off" />
              </label>
            </>
          )}
          <label className="source-toggle">
            <span>启用媒体源</span>
            <input type="checkbox" checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />
          </label>
          <footer>
            <button className="secondary-action" type="button" onClick={onClose}>取消</button>
            <button className="primary-action" type="submit" disabled={!valid || saving}>
              {saving && <LoaderCircle className="spinner" size={17} aria-hidden="true" />}
              {saving ? "保存中" : "保存"}
            </button>
          </footer>
        </form>
      </section>
    </div>
  );
}

type VideoSlideProps = {
  item: VideoItem;
  index: number;
  total: number;
  active: boolean;
  nearby: boolean;
  landscape: boolean;
  muted: boolean;
  initialTime: number;
  onMutedChange: (muted: boolean) => void;
  onProgress: (videoId: number, time: number, duration: number) => void;
  onWatched: (videoId: number) => void;
  onLandscapeIntent: (index: number) => void;
  onLandscapeExit: (index: number) => void;
  onLandscapeNavigate: (index: number, direction: -1 | 1) => void;
  onPlaybackStarted: () => void;
};

function VideoSlide({
  item,
  index,
  total,
  active,
  nearby,
  landscape,
  muted,
  initialTime,
  onMutedChange,
  onProgress,
  onWatched,
  onLandscapeIntent,
  onLandscapeExit,
  onLandscapeNavigate,
  onPlaybackStarted,
}: VideoSlideProps) {
  const slideRef = useRef<HTMLElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const gestureRef = useRef<LandscapeGesture | null>(null);
  const suppressClickRef = useRef(false);
  const retryTimerRef = useRef<number | null>(null);
  const chromeTimerRef = useRef<number | null>(null);
  const chromeVisibleRef = useRef(true);
  const restoredRef = useRef(false);
  const metadataReportedRef = useRef(false);
  const currentTimeRef = useRef(initialTime);
  const durationRef = useRef(0);
  const lastPersistedRef = useRef(initialTime);
  const [paused, setPaused] = useState(true);
  const [buffering, setBuffering] = useState(true);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [currentTime, setCurrentTime] = useState(initialTime);
  const [duration, setDuration] = useState(0);
  const [chromeVisible, setChromeVisible] = useState(true);
  const [seeking, setSeeking] = useState(false);
  const [gestureSeek, setGestureSeek] = useState<{ targetTime: number; delta: number } | null>(null);
  const engaged = active;
  const source = attempt ? `${item.playUrl}?refresh=1&attempt=${attempt}` : item.playUrl;

  const saveProgress = useCallback(() => {
    if (durationRef.current > 0) {
      onProgress(item.id, currentTimeRef.current, durationRef.current);
      lastPersistedRef.current = currentTimeRef.current;
    }
  }, [item.id, onProgress]);

  const clearChromeTimer = useCallback(() => {
    if (chromeTimerRef.current === null) return;
    window.clearTimeout(chromeTimerRef.current);
    chromeTimerRef.current = null;
  }, []);

  const hideChrome = useCallback(() => {
    clearChromeTimer();
    chromeVisibleRef.current = false;
    setChromeVisible(false);
  }, [clearChromeTimer]);

  const revealChrome = useCallback(() => {
    if (!engaged) return;
    clearChromeTimer();
    chromeVisibleRef.current = true;
    setChromeVisible(true);
    if (!landscape || seeking) return;
    chromeTimerRef.current = window.setTimeout(() => {
      chromeVisibleRef.current = false;
      setChromeVisible(false);
      chromeTimerRef.current = null;
    }, LANDSCAPE_CHROME_VISIBLE_MS);
  }, [clearChromeTimer, engaged, landscape, seeking]);

  useEffect(() => {
    restoredRef.current = false;
    metadataReportedRef.current = false;
  }, [source]);

  useEffect(() => {
    return () => {
      if (retryTimerRef.current !== null) window.clearTimeout(retryTimerRef.current);
      clearChromeTimer();
    };
  }, [clearChromeTimer]);

  useEffect(() => {
    clearChromeTimer();
    if (!engaged) {
      chromeVisibleRef.current = false;
      setChromeVisible(false);
      return;
    }
    revealChrome();
    return clearChromeTimer;
  }, [clearChromeTimer, engaged, landscape, revealChrome, seeking]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    video.muted = muted;
  }, [muted]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    if (engaged) {
      const promise = video.play();
      if (promise) promise.catch(() => setPaused(true));
    } else {
      video.pause();
    }
  }, [engaged, source]);

  useEffect(() => {
    const video = videoRef.current;
    if (nearby && video && video.readyState === HTMLMediaElement.HAVE_NOTHING) video.load();
  }, [nearby]);

  useEffect(() => {
    if (!engaged) return;
    const flushWhenHidden = () => {
      if (document.visibilityState === "hidden") saveProgress();
    };
    window.addEventListener("pagehide", saveProgress);
    document.addEventListener("visibilitychange", flushWhenHidden);
    return () => {
      window.removeEventListener("pagehide", saveProgress);
      document.removeEventListener("visibilitychange", flushWhenHidden);
      saveProgress();
    };
  }, [engaged, saveProgress]);

  useEffect(() => {
    if (!landscape) return;
    const exitOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      const orientation = screen.orientation as (ScreenOrientation & { unlock?: () => void }) | undefined;
      orientation?.unlock?.();
      onLandscapeExit(index);
    };
    window.addEventListener("keydown", exitOnEscape);
    return () => window.removeEventListener("keydown", exitOnEscape);
  }, [index, landscape, onLandscapeExit]);

  const togglePlayback = () => {
    const video = videoRef.current;
    if (!video || failed) return;
    if (video.paused) void video.play();
    else video.pause();
  };

  const handleSurfaceClick = (event: React.MouseEvent<HTMLElement>) => {
    if ((event.target as HTMLElement).closest("button, input")) return;
    if (suppressClickRef.current) {
      suppressClickRef.current = false;
      return;
    }
    if (landscape) {
      if (chromeVisibleRef.current) hideChrome();
      else revealChrome();
      return;
    }
    togglePlayback();
  };

  const handlePlaybackControl = (event: React.MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    revealChrome();
    togglePlayback();
  };

  const toggleLandscape = async () => {
    const video = videoRef.current;
    if (!video) return;
    const orientation = screen.orientation as (ScreenOrientation & {
      lock?: (value: "landscape") => Promise<void>;
      unlock?: () => void;
    }) | undefined;
    if (landscape) {
      orientation?.unlock?.();
      onLandscapeExit(index);
      return;
    }
    onLandscapeIntent(index);
    try {
      await orientation?.lock?.("landscape");
    } catch {
      // Installed Android PWAs remain usable when orientation lock is unavailable.
    }
  };

  const handleError = () => {
    setBuffering(false);
    if (attempt === 0) {
      retryTimerRef.current = window.setTimeout(() => setAttempt(1), 350);
      return;
    }
    setFailed(true);
  };

  const retry = () => {
    setFailed(false);
    setBuffering(true);
    setAttempt((value) => value + 1);
  };

  const seek = (value: number) => {
    const video = videoRef.current;
    if (!video) return;
    video.currentTime = value;
    currentTimeRef.current = value;
    setCurrentTime(value);
  };

  const handleGestureStart = (event: React.PointerEvent<HTMLElement>) => {
    const interactiveTarget = (event.target as HTMLElement).closest("button, input");
    if (!landscape || event.button !== 0) return;
    if (interactiveTarget) return;
    const startTime = currentTimeRef.current;
    gestureRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      lastX: event.clientX,
      lastY: event.clientY,
      startTime,
      targetTime: startTime,
      axis: "pending",
      moved: false,
      softwareRotated: window.matchMedia("(orientation: portrait)").matches,
    };
    try {
      event.currentTarget.setPointerCapture(event.pointerId);
    } catch {
      // Pointer capture is best-effort in embedded Android webviews.
    }
  };

  const handleGestureMove = (event: React.PointerEvent<HTMLElement>) => {
    const gesture = gestureRef.current;
    if (!gesture || gesture.pointerId !== event.pointerId) return;
    gesture.lastX = event.clientX;
    gesture.lastY = event.clientY;
    const { x: deltaX, y: deltaY } = getLandscapeGestureDelta(gesture, event.clientX, event.clientY);
    const absoluteX = Math.abs(deltaX);
    const absoluteY = Math.abs(deltaY);

    if (gesture.axis === "pending") {
      if (Math.max(absoluteX, absoluteY) < GESTURE_AXIS_THRESHOLD_PX) return;
      if (absoluteX > absoluteY * 1.15) gesture.axis = "horizontal";
      else if (absoluteY > absoluteX * 1.15) gesture.axis = "vertical";
      else return;
      gesture.moved = true;
      suppressClickRef.current = true;
    }

    event.preventDefault();
    if (gesture.axis !== "horizontal" || durationRef.current <= 0) return;
    const width = Math.max(1, slideRef.current?.clientWidth ?? window.innerWidth);
    const seekSpan = Math.min(90, Math.max(15, durationRef.current * 0.35));
    const delta = (deltaX / width) * seekSpan;
    const targetTime = Math.max(0, Math.min(durationRef.current, gesture.startTime + delta));
    gesture.targetTime = targetTime;
    setSeeking(true);
    setGestureSeek({ targetTime, delta: targetTime - gesture.startTime });
  };

  const finishGesture = (event: React.PointerEvent<HTMLElement>, commit: boolean) => {
    const gesture = gestureRef.current;
    if (!gesture || gesture.pointerId !== event.pointerId) {
      if (!commit) {
        suppressClickRef.current = false;
      }
      return;
    }
    gestureRef.current = null;
    try {
      if (event.currentTarget.hasPointerCapture(event.pointerId)) {
        event.currentTarget.releasePointerCapture(event.pointerId);
      }
    } catch {
      // The browser may release capture before pointercancel is delivered.
    }

    if (commit && gesture.axis === "horizontal" && durationRef.current > 0) {
      seek(gesture.targetTime);
    } else if (commit && gesture.axis === "vertical") {
      const { y: distance } = getLandscapeGestureDelta(gesture, gesture.lastX, gesture.lastY);
      const height = Math.max(1, slideRef.current?.clientHeight ?? window.innerHeight);
      const threshold = Math.max(56, Math.min(96, height * 0.18));
      if (Math.abs(distance) >= threshold) {
        onLandscapeNavigate(index, distance < 0 ? 1 : -1);
      }
    }

    setSeeking(false);
    setGestureSeek(null);
    if (gesture.moved) {
      window.setTimeout(() => {
        suppressClickRef.current = false;
      }, 0);
    }
  };

  const loadedMetadata = (video: HTMLVideoElement) => {
    const nextDuration = Number.isFinite(video.duration) ? video.duration : 0;
    durationRef.current = nextDuration;
    setDuration(nextDuration);
    if (nextDuration > 0 && !metadataReportedRef.current) {
      metadataReportedRef.current = true;
      void fetch(`/api/videos/${item.id}/metadata`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ duration: nextDuration, mediaKind: "video" }),
      }).catch(() => undefined);
    }
    if (nextDuration > 0 && !restoredRef.current) {
      restoredRef.current = true;
      if (initialTime > 1 && initialTime < nextDuration - 3) {
        video.currentTime = initialTime;
        currentTimeRef.current = initialTime;
        setCurrentTime(initialTime);
      }
    }
  };

  const timeUpdated = (video: HTMLVideoElement) => {
    const nextTime = video.currentTime;
    currentTimeRef.current = nextTime;
    setCurrentTime(nextTime);
    if (engaged && Math.abs(nextTime - lastPersistedRef.current) >= 2) saveProgress();
  };

  return (
    <article
      ref={slideRef}
      className={`video-slide${landscape ? " is-landscape" : ""}${chromeVisible ? " chrome-visible" : ""}`}
      data-index={index}
      aria-label={item.title}
      onClick={handleSurfaceClick}
      onPointerDown={handleGestureStart}
      onPointerMove={handleGestureMove}
      onPointerUp={(event) => finishGesture(event, true)}
      onPointerCancel={(event) => finishGesture(event, false)}
    >
      <video
        ref={videoRef}
        src={source}
        poster={item.posterUrl ?? undefined}
        preload={nearby ? "auto" : "none"}
        muted={muted}
        playsInline
        loop
        onPlay={() => {
          onPlaybackStarted();
          setPaused(false);
        }}
        onPause={() => {
          setPaused(true);
          saveProgress();
        }}
        onLoadStart={() => setBuffering(true)}
        onCanPlay={() => setBuffering(false)}
        onPlaying={() => {
          setBuffering(false);
          onWatched(item.id);
        }}
        onWaiting={() => setBuffering(true)}
        onError={handleError}
        onLoadedMetadata={(event) => loadedMetadata(event.currentTarget)}
        onDurationChange={(event) => loadedMetadata(event.currentTarget)}
        onTimeUpdate={(event) => timeUpdated(event.currentTarget)}
      />

      <div className="video-shade" aria-hidden="true" />

      {engaged && buffering && !failed && (
        <div className="media-status" role="status" aria-label="正在缓冲">
          <LoaderCircle className="spinner" size={30} aria-hidden="true" />
        </div>
      )}

      {engaged && !buffering && !failed && (
        <button
          className={`center-play${paused ? " is-paused" : " is-playing"}`}
          type="button"
          aria-label={paused ? "播放" : "暂停"}
          onClick={handlePlaybackControl}
        >
          {paused
            ? <Play size={30} fill="currentColor" aria-hidden="true" />
            : <Pause size={30} fill="currentColor" aria-hidden="true" />}
        </button>
      )}

      {engaged && failed && (
        <div className="media-status failed" role="alert">
          <p>视频暂时无法播放</p>
          <button className="retry-button" type="button" onClick={(event) => { event.stopPropagation(); retry(); }}>
            <RefreshCw size={18} aria-hidden="true" />
            重试
          </button>
        </div>
      )}

      {landscape && gestureSeek && (
        <div className="gesture-seek" aria-hidden="true">
          {gestureSeek.delta < 0 ? <Rewind size={21} /> : <FastForward size={21} />}
          <strong>{formatPlaybackTime(gestureSeek.targetTime)}</strong>
          <span>{gestureSeek.delta < 0 ? "-" : "+"}{Math.abs(Math.round(gestureSeek.delta))} 秒</span>
        </div>
      )}

      <div className="video-meta">
        <p className="video-title">{item.title}</p>
        <p className="video-count">
          <span>{index + 1}</span>
          <span aria-hidden="true"> / </span>
          <span>{total}</span>
        </p>
      </div>

      {engaged && (
        <div className="side-controls">
          <button className="icon-button media-button" type="button" aria-label={muted ? "打开声音" : "静音"} onClick={(event) => { event.stopPropagation(); revealChrome(); onMutedChange(!muted); }}>
            {muted ? <VolumeX size={23} aria-hidden="true" /> : <Volume2 size={23} aria-hidden="true" />}
          </button>
          <button className="icon-button media-button" type="button" aria-label={landscape ? "退出横屏" : "横屏播放"} onClick={(event) => { event.stopPropagation(); void toggleLandscape(); }}>
            {landscape ? <Minimize2 size={22} aria-hidden="true" /> : <Maximize2 size={22} aria-hidden="true" />}
          </button>
        </div>
      )}

      <div className="progress-wrap">
        <input
          type="range"
          min="0"
          max={duration || 0}
          step="0.05"
          value={Math.min(currentTime, duration || 0)}
          disabled={!duration || failed}
          aria-label={`${item.title}播放进度`}
          onFocus={() => setSeeking(true)}
          onBlur={() => setSeeking(false)}
          onPointerDown={() => setSeeking(true)}
          onPointerUp={() => setSeeking(false)}
          onPointerCancel={() => setSeeking(false)}
          onClick={(event) => event.stopPropagation()}
          onChange={(event) => seek(Number(event.target.value))}
          style={{ "--progress": duration ? `${(currentTime / duration) * 100}%` : "0%" } as React.CSSProperties}
        />
      </div>
    </article>
  );
}

function formatPlaybackTime(seconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const remainingSeconds = totalSeconds % 60;
  if (hours) {
    return `${hours}:${String(minutes).padStart(2, "0")}:${String(remainingSeconds).padStart(2, "0")}`;
  }
  return `${minutes}:${String(remainingSeconds).padStart(2, "0")}`;
}

function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** index).toFixed(index >= 3 ? 1 : 0)} ${units[index]}`;
}

function formatDate(timestamp: number | null): string {
  if (!timestamp) return "尚未完成扫描";
  return `上次扫描 ${new Intl.DateTimeFormat("zh-CN", {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(timestamp * 1000))}`;
}

function fastStartLabel(status: "not_optimized" | "inconclusive" | "error"): string {
  if (status === "not_optimized") return "未优化";
  if (status === "error") return "检查失败";
  return "无法判断";
}

export default App;
