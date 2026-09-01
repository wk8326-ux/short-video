import { ArrowLeft, Film, LoaderCircle, Play, Search, Star } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
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
  duration: number | null;
};

type MovieResponse = {
  items: MovieItem[];
  total: number;
  nextOffset: number | null;
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
  const [items, setItems] = useState<MovieItem[]>([]);
  const [selected, setSelected] = useState<MovieItem | null>(null);
  const [query, setQuery] = useState("");
  const [appliedQuery, setAppliedQuery] = useState("");
  const [offset, setOffset] = useState(0);
  const [nextOffset, setNextOffset] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState("");

  const loadMovies = useCallback(async (nextOffsetValue: number, replace: boolean, search: string) => {
    if (replace) setLoading(true);
    else setLoadingMore(true);
    try {
      const params = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(nextOffsetValue) });
      if (search) params.set("q", search);
      const response = await fetch(`/api/movies?${params.toString()}`, { cache: "no-store" });
      if (response.status === 401) {
        onUnauthorized();
        return;
      }
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = (await response.json()) as MovieResponse;
      setItems((current) => replace ? data.items : [...current, ...data.items]);
      setOffset(nextOffsetValue + data.items.length);
      setNextOffset(data.nextOffset);
      setError("");
    } catch {
      setError("电影目录暂时无法载入");
    } finally {
      setLoading(false);
      setLoadingMore(false);
    }
  }, [onUnauthorized]);

  useEffect(() => {
    void loadMovies(0, true, appliedQuery);
  }, [appliedQuery, loadMovies]);

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

      {loading && items.length === 0 ? (
        <div className="feed-state" role="status"><LoaderCircle className="spinner" size={28} aria-hidden="true" /><p>正在载入电影目录</p></div>
      ) : error && items.length === 0 ? (
        <div className="feed-state" role="alert"><p>{error}</p></div>
      ) : items.length === 0 ? (
        <div className="feed-state" role="status"><Film size={30} aria-hidden="true" /><p>还没有电影索引</p><span>先在管理页添加电影媒体源并手动扫描</span></div>
      ) : (
        <>
          <div className="movie-grid">
            {items.map((item) => <MovieCard key={item.id} item={item} onOpen={() => void openMovie(item)} position={positions[String(item.videoId)]} />)}
          </div>
          {nextOffset !== null && (
            <button className="secondary-action movie-load-more" type="button" disabled={loadingMore} onClick={() => void loadMovies(offset, false, appliedQuery)}>
              {loadingMore && <LoaderCircle className="spinner" size={17} aria-hidden="true" />} {loadingMore ? "正在载入" : "载入更多"}
            </button>
          )}
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
  const restored = useRef(false);
  const [playing, setPlaying] = useState(false);
  const detailBackgroundUrl = movie.backdropUrl ?? movie.wallUrl ?? movie.posterUrl;
  useEffect(() => {
    if (!restored.current && videoRef.current && position?.time) {
      videoRef.current.currentTime = position.time;
      restored.current = true;
    }
  }, [position]);
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
        <video ref={videoRef} src={movie.playUrl} controls playsInline preload="metadata" onPlay={() => { setPlaying(true); onWatched(movie.videoId); }} onPause={(event) => { setPlaying(false); const current = event.currentTarget; if (duration > 0) onProgress(movie.videoId, current.currentTime, duration); }} onTimeUpdate={(event) => { const current = event.currentTarget; const currentDuration = Number.isFinite(current.duration) && current.duration > 0 ? current.duration : duration; if (currentDuration > 0) onProgress(movie.videoId, current.currentTime, currentDuration); }} />
        {!playing && <span className="movie-player-hint"><Play size={19} aria-hidden="true" />选择播放开始观看</span>}
      </div>
      <div className="movie-detail-facts"><span>{movie.runtimeMinutes ? `${movie.runtimeMinutes} 分钟` : "时长待探测"}</span>{movie.rating && <span><Star size={14} aria-hidden="true" />{movie.rating.toFixed(1)}</span>}{movie.matchStatus !== "matched" && <span className="movie-match-status">{movie.matchStatus === "pending" ? "待刮削" : "待确认"}</span>}</div>
      <button className="sr-only" type="button" onClick={onBack}>关闭电影详情</button>
    </section>
  );
}
