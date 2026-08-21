export type FeedMode = "shuffle" | "newest" | "oldest";
export type MediaSurface = "short" | "long" | "asmr" | "movie";

export type SavedPosition = {
  time: number;
  duration: number;
  updatedAt: number;
};

export type PlaybackState = {
  version: 2;
  mode: FeedMode;
  surface: MediaSurface;
  muted: boolean;
  lastVideoId: number | null;
  lastVideoIds: Partial<Record<MediaSurface, number>>;
  recentVideoIds: number[];
  positions: Record<string, SavedPosition>;
};

const DATABASE_NAME = "short-video-player";
const STORE_NAME = "preferences";
const STATE_KEY = "playback";
const FALLBACK_KEY = "short-video-playback-v1";

const DEFAULT_STATE: PlaybackState = {
  version: 2,
  mode: "shuffle",
  surface: "short",
  muted: true,
  lastVideoId: null,
  lastVideoIds: {},
  recentVideoIds: [],
  positions: {},
};

let currentState: PlaybackState = DEFAULT_STATE;
let writeQueue: Promise<void> = Promise.resolve();

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, 1);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) {
        request.result.createObjectStore(STORE_NAME);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

async function readIndexedState(): Promise<unknown> {
  const database = await openDatabase();
  try {
    return await new Promise((resolve, reject) => {
      const request = database.transaction(STORE_NAME, "readonly").objectStore(STORE_NAME).get(STATE_KEY);
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  } finally {
    database.close();
  }
}

async function writeIndexedState(state: PlaybackState): Promise<void> {
  const database = await openDatabase();
  try {
    await new Promise<void>((resolve, reject) => {
      const transaction = database.transaction(STORE_NAME, "readwrite");
      transaction.objectStore(STORE_NAME).put(state, STATE_KEY);
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error);
    });
  } finally {
    database.close();
  }
}

function normalizeState(value: unknown): PlaybackState {
  if (!value || typeof value !== "object") return { ...DEFAULT_STATE };
  const candidate = value as Partial<PlaybackState>;
  const mode: FeedMode = ["shuffle", "newest", "oldest"].includes(candidate.mode ?? "")
    ? (candidate.mode as FeedMode)
    : "shuffle";
  const surface: MediaSurface = ["short", "long", "asmr", "movie"].includes(candidate.surface ?? "")
    ? (candidate.surface as MediaSurface)
    : "short";
  const recentVideoIds = Array.isArray(candidate.recentVideoIds)
    ? candidate.recentVideoIds.filter((id): id is number => Number.isInteger(id) && id > 0).slice(0, 100)
    : [];
  const positions = Object.fromEntries(
    Object.entries(candidate.positions ?? {})
      .filter(([, position]) => {
        return position
          && Number.isFinite(position.time)
          && Number.isFinite(position.duration)
          && Number.isFinite(position.updatedAt);
      })
      .sort(([, left], [, right]) => right.updatedAt - left.updatedAt)
      .slice(0, 500),
  );
  const lastVideoIds = Object.fromEntries(
    Object.entries(candidate.lastVideoIds ?? {}).filter(([key, id]) => {
      return ["short", "long", "asmr", "movie"].includes(key) && Number.isInteger(id) && Number(id) > 0;
    }),
  ) as Partial<Record<MediaSurface, number>>;
  if (!lastVideoIds.short && Number.isInteger(candidate.lastVideoId) && Number(candidate.lastVideoId) > 0) {
    lastVideoIds.short = Number(candidate.lastVideoId);
  }

  return {
    version: 2,
    mode,
    surface,
    muted: candidate.muted !== false,
    lastVideoId: Number.isInteger(candidate.lastVideoId) && Number(candidate.lastVideoId) > 0
      ? Number(candidate.lastVideoId)
      : null,
    lastVideoIds,
    recentVideoIds,
    positions,
  };
}

function readFallback(): unknown {
  try {
    const value = localStorage.getItem(FALLBACK_KEY);
    return value ? JSON.parse(value) : undefined;
  } catch {
    return undefined;
  }
}

function writeFallback(state: PlaybackState): void {
  try {
    localStorage.setItem(FALLBACK_KEY, JSON.stringify(state));
  } catch {
    // Playback remains usable when persistent browser storage is unavailable.
  }
}

export async function loadPlaybackState(): Promise<PlaybackState> {
  let stored: unknown;
  try {
    stored = await readIndexedState();
  } catch {
    stored = readFallback();
  }
  currentState = normalizeState(stored);
  return currentState;
}

export function updatePlaybackState(
  update: Partial<PlaybackState> | ((state: PlaybackState) => PlaybackState),
): void {
  currentState = normalizeState(
    typeof update === "function" ? update(currentState) : { ...currentState, ...update },
  );
  const snapshot = structuredClone(currentState);
  writeQueue = writeQueue.then(async () => {
    try {
      await writeIndexedState(snapshot);
    } catch {
      writeFallback(snapshot);
    }
  });
}
