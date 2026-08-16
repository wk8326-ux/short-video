# Short Video

Android-first private short-video PWA backed by an AList directory.

```text
Browser -> FastAPI metadata/resolve API -> AList fs/get -> 302 -> drive CDN
```

The application server never proxies or transcodes video bytes.

## Features

- Vertical swipe playback with adjacent-video preload
- First-item direct URL prewarming with concurrent AList request deduplication
- Device-local resume position, mute state, feed mode, and recent history
- Stable shuffle pagination that avoids the most recently watched videos
- Android landscape playback through fullscreen orientation lock
- Authenticated management view for library rescans and MP4 Fast Start checks

Fast Start checks are manual and read at most the first 256 KiB of each MP4-family file. They do not rewrite or transcode media.

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

The index is stored in `/data/library.db` and refreshed every 30 minutes by default. Resolved direct URLs are kept in memory for 10 minutes by default and cleared after each scan. Feed modes are random, newest first, and oldest first.
