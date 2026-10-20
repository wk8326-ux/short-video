import { ArrowLeft, Film, LoaderCircle, Play, Search, Star } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  attachPlaybackSource,
  withPlaybackAttempt,
  type PlaybackSession,
} from "./playback";
import type { SavedPosition } from "./playbackStore";

type MovieItem = {
  id: number;
  videoId: number;
  title: string;
  originalTitle: string | null;
  year: number | null;
  overview: string;
  posterUrl: string | null;
  backdropUrl: string | null;
  wallUrl: string | null;
  rating: number | null;
  runtimeMinutes: number | null;
  matchStatus: "pending" | "matched" | "ambiguous" | "unmatched" | "manual";
  playUrl: string;
  format: string | null;
  duration: number | null;
  source: string;
};

type MovieGroup = {
  sourceId: string;
  name: string;
  items: MovieItem[];
  total: number;
  nextOffset: number | null;
};

type MovieSource = {
  id: string;
  name: string;
  provider: "alist" | "openlist" | "tvbox" | "html";
  libraryIds: string[];
  libraryCount: number;
  total: number;
};

type MovieGroupsResponse = {
  groups: MovieGroup[];
  sources: MovieSource[];
  selectedSourceId: string | null;
  total: number;
  scan: { running: boolean };
};

type MovieLibraryProps = {
  positions: Record<string, SavedPosition>;
  onProgress: (videoId: number, time: number, duration: number) => void;
  onWatched: (videoId: number) => void;
  onUnauthorized: () => void;
};

const PAGE_SIZE = 24;

export default function MovieLibrary({ positions, onProgress, onWatched, onUnauthorized }: MovieLibraryProps) {
  const [groups, setGroups] = useState<MovieGroup[]>([]);
  const [sources, setSources] = useState<MovieSource[]>([]);
  const [selectedSourceId, setSelectedSourceId] = useState<string | null>(null);
  const [total, setTotal] = useState(0);
  const [selected, setSelected] = useState<MovieItem | null>(null);
  const [query, setQuery] = useState("");
  const [appliedQuery, setAppliedQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [loadingGroups, setLoadingGroups] = useState<Set<string>>(() => new Set());
  const [error, setError] = useState("");
  const requestGeneration = useRef(0);
  const selectedSourceIdRef = useRef<string | null>(null);

  const loadGroups = useCallback(async (sourceId: string | null, search: string) => {
    const generation = ++requestGeneration.current;
    setLoading(true);
    try {
      const params = new URLSearchParams({ perGroup: String(PAGE_SIZE) });
      if (search) params.set("q", search);
      if (sourceId) params.set("sourceId", sourceId);
      const response = await fetch(`/api/movies/groups?${params.toString()}`, { cache: "no-store" });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = (await response.json()) as MovieGroupsResponse;
      if (generation !== requestGeneration.current) return;
      setGroups(data.groups);
      setSources(data.sources);
      setSelectedSourceId(data.selectedSourceId);
      selectedSourceIdRef.current = data.selectedSourceId;
      setTotal(data.total);
      setError("");
    } catch {
      if (generation !== requestGeneration.current) return;
      setGroups([]);
      setTotal(0);
      setError("电影目录暂时无法载入");
    } finally {
      if (generation === requestGeneration.current) setLoading(false);
    }
  }, [onUnauthorized]);

  useEffect(() => {
    void loadGroups(selectedSourceIdRef.current, appliedQuery);
  }, [appliedQuery, loadGroups]);

  const changeSource = (sourceId: string) => {
    selectedSourceIdRef.current = sourceId;
    setSelectedSourceId(sourceId);
    setGroups([]);
    setTotal(0);
    void loadGroups(sourceId, appliedQuery);
  };

  const loadGroupMore = async (group: MovieGroup) => {
    if (group.nextOffset === null || loadingGroups.has(group.sourceId)) return;
    setLoadingGroups((current) => new Set(current).add(group.sourceId));
    try {
      const params = new URLSearchParams({
        limit: String(PAGE_SIZE),
        offset: String(group.nextOffset),
      });
      if (appliedQuery) params.set("q", appliedQuery);
      const response = await fetch(
        `/api/movies/groups/${encodeURIComponent(group.sourceId)}?${params.toString()}`,
        { cache: "no-store" },
      );
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const page = (await response.json()) as MovieGroup;
      setGroups((current) => current.map((entry) => entry.sourceId === group.sourceId
        ? { ...entry, items: [...entry.items, ...page.items], nextOffset: page.nextOffset, total: page.total }
        : entry));
    } catch {
      setError("更多电影暂时无法载入");
    } finally {
      setLoadingGroups((current) => {
        const next = new Set(current);
        next.delete(group.sourceId);
        return next;
      });
    }
  };

  const openMovie = async (item: MovieItem) => {
    try {
      const response = await fetch(`/api/movies/${item.id}`, { cache: "no-store" });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      setSelected((await response.json()) as MovieItem);
    } catch {
      setSelected(item);
    }
  };

  if (selected) {
    return (
      <MovieDetail
        movie={selected}
        position={positions[String(selected.videoId)]}
        onBack={() => setSelected(null)}
        onProgress={onProgress}
        onWatched={onWatched}
      />
    );
  }

  return (
    <section className="movie-library" aria-labelledby="movie-library-title">
      <div className="movie-toolbar">
        <div>
          <p className="section-label">电影</p>
          <h1 id="movie-library-title">片库</h1>
        </div>
        <form className="movie-search" onSubmit={(event) => { event.preventDefault(); setAppliedQuery(query.trim()); }}>
          <Search size={17} aria-hidden="true" />
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索电影" aria-label="搜索电影" />
        </form>
      </div>

      {loading && groups.length === 0 ? (
        <div className="feed-state" role="status"><LoaderCircle className="spinner" size={28} aria-hidden="true" /><p>正在载入电影目录</p></div>
      ) : error && groups.length === 0 ? (
        <div className="feed-state" role="alert"><p>{error}</p></div>
      ) : groups.length === 0 ? (
        <div className="feed-state" role="status"><Film size={30} aria-hidden="true" /><p>还没有电影索引</p><span>先在管理页添加电影媒体源并手动扫描</span></div>
      ) : (
        <>
          <div className="movie-source-controls">
            {sources.length > 1 ? (
              <label className="movie-source-picker">
                <span>媒体源</span>
                <select value={selectedSourceId ?? ""} onChange={(event) => changeSource(event.target.value)}>
                  {sources.map((source) => (
                    <option key={source.id} value={source.id}>
                      {source.name} · {source.total} 部
                    </option>
                  ))}
                </select>
              </label>
            ) : (
              <span className="movie-source-single"><Film size={16} aria-hidden="true" />{sources[0]?.name ?? "电影目录"}</span>
            )}
            <span>{total} 部</span>
          </div>
          {error && <p className="movie-inline-error" role="alert">{error}</p>}
          <div className="movie-group-list">
            {groups.map((group) => (
              <section className="movie-group" key={group.sourceId} aria-labelledby={`movie-group-${group.sourceId}`}>
                <header>
                  <h2 id={`movie-group-${group.sourceId}`}>{group.name}</h2>
                  <span>{group.total} 部</span>
                </header>
                <div className="movie-grid">
                  {group.items.map((item) => (
                    <MovieCard
                      key={item.id}
                      item={item}
                      onOpen={() => void openMovie(item)}
                      position={positions[String(item.videoId)]}
                    />
                  ))}
                </div>
                {group.nextOffset !== null && (
                  <button
                    className="secondary-action movie-load-more"
                    type="button"
                    disabled={loadingGroups.has(group.sourceId)}
                    onClick={() => void loadGroupMore(group)}
                  >
                    {loadingGroups.has(group.sourceId) && <LoaderCircle className="spinner" size={17} aria-hidden="true" />}
                    {loadingGroups.has(group.sourceId) ? "正在载入" : "载入更多"}
                  </button>
                )}
              </section>
            ))}
          </div>
        </>
      )}
    </section>
  );
}

function MovieCard({ item, onOpen, position }: { item: MovieItem; onOpen: () => void; position?: SavedPosition }) {
  const progress = position && position.duration > 0 ? Math.min(1, position.time / position.duration) : 0;
  const [imageUrl, setImageUrl] = useState(item.wallUrl ?? item.posterUrl);
  useEffect(() => {
    setImageUrl(item.wallUrl ?? item.posterUrl);
  }, [item.wallUrl, item.posterUrl]);
  return (
    <button className="movie-card" type="button" onClick={onOpen} aria-label={`打开 ${item.title}`}>
      <span className="movie-poster">
        {imageUrl ? <img src={imageUrl} alt="" loading="lazy" decoding="async" onError={() => { if (imageUrl !== item.posterUrl) setImageUrl(item.posterUrl); else setImageUrl(null); }} /> : <span className="movie-poster-placeholder"><Film size={28} aria-hidden="true" /></span>}
        {progress > 0 && <span className="movie-progress"><span style={{ width: `${progress * 100}%` }} /></span>}
      </span>
      <span className="movie-card-copy"><strong>{item.title}</strong><small>{item.year ?? "年份未知"}</small></span>
    </button>
  );
}

function MovieDetail({ movie, position, onBack, onProgress, onWatched }: { movie: MovieItem; position?: SavedPosition; onBack: () => void; onProgress: MovieLibraryProps["onProgress"]; onWatched: MovieLibraryProps["onWatched"] }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const playbackSessionRef = useRef<PlaybackSession | null>(null);
  const initialTimeRef = useRef(position?.time ?? 0);
  const restored = useRef(false);
  const [playing, setPlaying] = useState(false);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const detailBackgroundUrl = movie.backdropUrl ?? movie.wallUrl ?? movie.posterUrl;
  const source = withPlaybackAttempt(movie.playUrl, attempt);

  useEffect(() => {
    if (!restored.current && videoRef.current && position?.time) {
      videoRef.current.currentTime = position.time;
      restored.current = true;
    }
  }, [position]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    let cancelled = false;
    setFailed(false);
    void attachPlaybackSource(video, {
      url: source,
      format: movie.format,
      initialTime: initialTimeRef.current,
      isCancelled: () => cancelled,
      onFatalError: () => {
        if (!cancelled) setFailed(true);
      },
    }).then((session) => {
      if (cancelled) {
        session.destroy();
        return;
      }
      playbackSessionRef.current = session;
    }).catch(() => {
      if (!cancelled) setFailed(true);
    });
    return () => {
      cancelled = true;
      playbackSessionRef.current?.destroy();
      playbackSessionRef.current = null;
      video.pause();
    };
  }, [movie.format, source]);

  const duration = movie.duration ?? position?.duration ?? 0;
  return (
    <section className="movie-detail" aria-labelledby="movie-detail-title">
      {detailBackgroundUrl && <img className="movie-detail-wall" src={detailBackgroundUrl} alt="" />}
      <div className="movie-detail-veil" aria-hidden="true" />
      <button className="movie-back" type="button" onClick={onBack}><ArrowLeft size={19} aria-hidden="true" />返回片库</button>
      <div className="movie-detail-hero">
        <div className="movie-detail-copy">
          <div className="movie-detail-poster">{movie.posterUrl ? <img src={movie.posterUrl} alt="" /> : <Film size={34} aria-hidden="true" />}</div>
          <div><p className="section-label">电影详情</p><h1 id="movie-detail-title">{movie.title}</h1><p className="movie-meta">{movie.year ?? "年份未知"}{movie.rating ? ` · ${movie.rating.toFixed(1)} 分` : ""}</p></div>
        </div>
      </div>
      <p className="movie-overview">{movie.overview || "暂无简介，先从文件名开始观看。"}</p>
      <div className="movie-player-wrap">
        <video ref={videoRef} controls playsInline preload="metadata" onPlay={() => { setPlaying(true); onWatched(movie.videoId); }} onPause={(event) => { setPlaying(false); const current = event.currentTarget; if (duration > 0) onProgress(movie.videoId, current.currentTime, duration); }} onTimeUpdate={(event) => { const current = event.currentTarget; const currentDuration = Number.isFinite(current.duration) && current.duration > 0 ? current.duration : duration; if (currentDuration > 0) onProgress(movie.videoId, current.currentTime, currentDuration); }} />
        {failed ? (
          <button className="movie-player-hint" type="button" onClick={() => setAttempt((value) => value + 1)}>
            <Play size={19} aria-hidden="true" />重新连接视频
          </button>
        ) : !playing && <span className="movie-player-hint"><Play size={19} aria-hidden="true" />选择播放开始观看</span>}
      </div>
      <div className="movie-detail-facts"><span>{movie.runtimeMinutes ? `${movie.runtimeMinutes} 分钟` : "时长待探测"}</span>{movie.rating && <span><Star size={14} aria-hidden="true" />{movie.rating.toFixed(1)}</span>}{movie.matchStatus !== "matched" && <span className="movie-match-status">{movie.matchStatus === "pending" ? "待刮削" : "待确认"}</span>}</div>
      <button className="sr-only" type="button" onClick={onBack}>关闭电影详情</button>
    </section>
  );
}
