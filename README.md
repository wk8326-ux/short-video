# Private Media Player

Private Android media player and fallback PWA backed by two AList sources.

```text
Guangya: Browser -> FastAPI metadata/resolve API -> AList fs/get -> 302 -> drive CDN
ASMR:    Browser -> FastAPI library/resolve API -> AList fs/get -> 302 -> HLS/audio origin
```

The application server never proxies or transcodes media bytes. Both Android and the PWA follow the authenticated play endpoint's 302 response and fetch media directly from the drive origin or CDN.

## Features

- Four top-level surfaces: short video, long video, ASMR, and movies
- Guangya duration split at 180 seconds (`< 180` short, `>= 180` long)
- Vertical swipe playback with adjacent-video preload for Guangya media
- First-item direct URL prewarming with concurrent AList request deduplication
- Device-local resume position, mute state, feed mode, and recent history
- Stable shuffle pagination that avoids the most recently watched videos
- Android landscape playback through fullscreen orientation lock
- Author-first ASMR library with title search and all/video/audio filters
- Adaptive MP3/direct-video/HLS playback with a persistent bottom mini-player
- Native Android client with a persistent 1 GiB media cache and stable cache keys
- Independent ASMR audio/video background toggles with lock-screen and notification controls
- Progressive ASMR list rendering, inline sequential audio playback, and remembered list positions
- ASMR video landscape playback with five-second chrome fade and horizontal seeking
- Authenticated management view for library rescans, source deletion, movie metadata scraping, and MP4 Fast Start checks
- Authenticated Android in-app updates with download progress and SHA-256 verification
- Redacted on-device runtime logs with management-page view, clear, and export actions

Fast Start checks are manual and read at most the first 256 KiB of each MP4-family file. They do not rewrite or transcode media.

Guangya duration classification runs in the background using small MP4 range reads. Each scan checks at most 30 pending files, largest first; browser metadata is also reported after successful playback. Existing files without known duration temporarily remain in the short-video feed so an upgrade never leaves the default screen empty.

The ASMR scanner keeps `/asmr6` compatibility and searches configured `/asmr` trees by media extension. Known category folders are flattened to the first author folder beneath them, while HLS segment files remain excluded. `hls.js` stays out of the default PWA short-video startup bundle and is warmed only after the ASMR library is active. The first author media resolver is also prewarmed without proxying media bytes. The ASMR playlist and segment origin must allow browser CORS access; the native Android client does not depend on browser CORS.

ASMR directory API requests are paced at four requests per second by default and retry temporary HTTP 429 responses. This keeps the public source scan polite and predictable.

## Android client

The native client lives in `android/`, targets Android 8.0 and later, and uses Media3/ExoPlayer. It reuses the existing login, feed, ASMR, management, and 302 play APIs.

Cached media is stored in the app's private storage and capped at 1 GiB with least-recently-used eviction. Short videos up to 96 MiB are prefetched in full; larger short videos cache the first 24 MiB; long video and audio cache the first 12 MiB. Already cached bytes bypass play-URL resolution, and HLS playlists/segments use stable keys without signed query parameters.

Short and long feeds pause whenever their page or the app foreground is left. ASMR audio and video each have a separate persisted background toggle in their player controls; enabled media continues through management views, screen lock, and app backgrounding. Android exposes enabled ASMR playback through a foreground media session with notification and lock-screen play/pause controls, audio focus, headset-disconnect handling, and a network wake lock. On Android 13 or later, allow notifications when prompted to keep the controls visible.

Build an installable internal APK:

```powershell
cd android
.\gradlew.bat lintDebug testDebugUnitTest assembleDebug --no-daemon
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. The checked-in Gradle configuration uses Tencent mirrors first so builds do not depend on direct access to GitHub or Maven Central.

Android updates are published from `/data/app-update`, which is inside the existing bind-mounted data directory. The API reads `manifest.json` for every check and serves the referenced APK only to an authenticated session. A private GitHub token is never bundled into the client.

```json
{
  "versionCode": 164,
  "versionName": "1.6.0-beta.5",
  "apkFile": "deepfuck-android-v1.6.0-beta.5.apk",
  "sha256": "5384ad5a587846f4a838e2dd8b44805f40e7366b26e489920c28d6a92fc2adb3",
  "size": 4211000,
  "notes": "电影墙优先使用横版封面，并增加服务端封面持久化缓存与首屏预热。"
}
```

The first updater-enabled build still needs one manual installation. From later versions, use `更多 -> 检查更新`; downloads continue in the background and resume from retained bytes after interruption. Android asks once for permission to install unknown apps, then the system installer completes each upgrade.

## Local development

```bash
cp .env.example .env
python app/auth.py init-env .env
python -m venv .venv
.venv/bin/pip install -r requirements-dev.txt
cd frontend && npm install && npm run build
uvicorn app.main:app --reload
```

Set `STATIC_DIR=frontend/dist` when running the built frontend outside Docker. During Vite development, `/api` is proxied to `127.0.0.1:8000`.

## Docker

```bash
cp .env.example .env
python app/auth.py init-env .env
docker compose up -d --build
```

The service binds only to `127.0.0.1:18083`. The included nginx site exposes it through `short.deepfuck.you`.

## Configuration

All settings are environment variables documented in `.env.example`. Application access uses one password stored as a scrypt hash. The signed, HttpOnly session cookie lasts 180 days by default; changing the password invalidates existing sessions.

To change the application password, run `python app/auth.py set-password .env` and restart the container. AList authentication remains independent and can use an AList token or username/password later.

The index is stored in `/data/library.db` and refreshed every 30 minutes by default. Existing Guangya IDs survive the multi-source migration. Each source is deactivated and refreshed independently, so an ASMR scan failure cannot remove Guangya rows. Resolved direct URLs are cached independently per source for 10 minutes by default and cleared after its scan. Feed modes are random, newest first, and oldest first.

Key source settings:

- `ALIST_BASE_URL` / `ALIST_MEDIA_PATH`: private Guangya AList source.
- `ASMR_BASE_URL` / `ASMR_MEDIA_PATH`: public ASMR AList source.
- `ASMR_SEARCH_PATHS`: additional AList trees searched by media extension, default `/asmr`.
- `ASMR_AUTHOR_GROUP_PATHS`: category folders whose first child is treated as the author.
- `ASMR_SEARCH_RESULT_LIMIT`: upper bound for each extension search, default `100000`.
- `ASMR_REQUEST_INTERVAL_SECONDS`: minimum delay between ASMR AList API calls, default `0.25`.
- `DURATION_BOUNDARY_SECONDS`: Guangya short/long boundary, default `180`.
- `METADATA_PROBE_BATCH_SIZE`: maximum MP4 duration probes per scan, default `30`.
- `ASMR_EXTENSIONS`: direct files indexed inside each author directory.
- `METATUBE_BASE_URL` / `METATUBE_TOKEN`: optional private MetaTube service. Exact numbered media uses it before TMDB; ordinary films remain on TMDB.
