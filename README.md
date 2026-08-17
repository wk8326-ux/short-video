# Private Media Player

Android-first private media PWA backed by two AList sources.

```text
Guangya: Browser -> FastAPI metadata/resolve API -> AList fs/get -> 302 -> drive CDN
ASMR:    Browser -> FastAPI library/resolve API -> AList fs/get -> 302 -> HLS/audio origin
```

The application server never proxies or transcodes media bytes.

## Features

- Three top-level surfaces: short video, long video, and ASMR
- Guangya duration split at 180 seconds (`< 180` short, `>= 180` long)
- Vertical swipe playback with adjacent-video preload for Guangya media
- First-item direct URL prewarming with concurrent AList request deduplication
- Device-local resume position, mute state, feed mode, and recent history
- Stable shuffle pagination that avoids the most recently watched videos
- Android landscape playback through fullscreen orientation lock
- Author-first ASMR library with title search and all/video/audio filters
- Adaptive MP3/direct-video/HLS playback with a persistent bottom mini-player
- Authenticated management view for library rescans and MP4 Fast Start checks

Fast Start checks are manual and read at most the first 256 KiB of each MP4-family file. They do not rewrite or transcode media.

Guangya duration classification runs in the background using small MP4 range reads. Each scan checks at most 30 pending files, largest first; browser metadata is also reported after successful playback. Existing files without known duration temporarily remain in the short-video feed so an upgrade never leaves the default screen empty.

The ASMR scanner indexes only the root author folders and their direct media files. It deliberately does not recurse into HLS segment directories. `hls.js` stays out of the default short-video startup bundle and is warmed only after the ASMR library is active. The first author media resolver is also prewarmed without proxying media bytes. The ASMR playlist and segment origin must allow browser CORS access.

ASMR directory API requests are paced at four requests per second by default and retry temporary HTTP 429 responses. This keeps the public source scan polite and predictable.

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
- `ASMR_REQUEST_INTERVAL_SECONDS`: minimum delay between ASMR AList API calls, default `0.25`.
- `DURATION_BOUNDARY_SECONDS`: Guangya short/long boundary, default `180`.
- `METADATA_PROBE_BATCH_SIZE`: maximum MP4 duration probes per scan, default `30`.
- `ASMR_EXTENSIONS`: direct files indexed inside each author directory.
