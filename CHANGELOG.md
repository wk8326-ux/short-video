# Changelog

All notable changes to this project are documented in this file.

## [1.2.0] - 2026-08-17

### Added

- Add a native Android 8+ client using Kotlin, Jetpack Compose, Media3, and ExoPlayer.
- Add a persistent 1 GiB phone-local media cache with stable keys, prefix/full-file prefetch, and cached-byte playback that bypasses repeat 302 resolution.
- Add ASMR audio and video background playback with lock-screen and notification controls, audio focus, headset-disconnect handling, and a network wake lock.
- Add ASMR video landscape playback with the feed's five-second chrome fade, center play/pause control, progress control, mute control, and horizontal seeking.
- Index the additional `/asmr` AList tree and flatten configured category folders into author-level library entries.

### Fixed

- Restore the active ASMR media into the Android UI when the task is reopened while its media service is still playing.
- Return to portrait when an expanded ASMR video is closed or collapsed from landscape.
- Explicitly opt in to the Media3 cache APIs and resolve Android 8 theme compatibility findings reported by lint.

### Improved

- Stream ASMR search pages into SQLite without retaining duplicate 40k+ item lists in memory.
- Use Tencent mirrors for Gradle and Maven dependencies in restricted network environments.

## [1.1.6] - 2026-08-17

### Fixed

- Keep ASMR media sources mounted when progress is saved so pause and seek no longer reload or restart playback.
- Replace overlapping native and custom ASMR video controls with one consistent control layer, and preserve the last video frame while buffering.
- Support bidirectional ASMR video seek gestures without treating a left swipe as player exit.

### Improved

- Cache the installed PWA shell, authenticated startup hint, and recent short/long feed metadata for a faster repeat launch.
- Allow private 302 play redirects to be reused briefly without caching large media files on the application server.
- Keep portrait feed controls visible at the edges with one-tap playback, while landscape controls hide after five seconds and reserve playback for the center button.
- Retain landscape seek and previous/next gestures while removing the duplicate side playback control.

## [1.1.5] - 2026-08-17

### Fixed

- Remove duplicate ASMR loading placeholders so compact audio keeps one right-side spinner and expanded playback keeps one centered status.
- Treat every ASMR M3U8 item as video across scans, filters, API responses, and playback, even when older browser metadata says audio.
- Make Android back and edge gestures close expanded ASMR playback before leaving the PWA.

### Improved

- Start HLS engine and resolver prewarming as soon as author media arrives, and pass saved playback position into HLS startup.

## [1.1.4] - 2026-08-17

### Improved

- Prewarm the 302 resolver on media pointer intent so non-first ASMR audio and video items start resolving before selection completes.

## [1.1.3] - 2026-08-17

### Improved

- Redesign ASMR video and audio expanded playback views with visible media context and loading states.
- Preload the ASMR HLS engine and first author media resolver without adding work to the Guangya startup path.
- Add left-swipe exit for expanded ASMR video playback while preserving native controls and right-swipe behavior.

## [1.1.2] - 2026-08-17

### Fixed

- Pace ASMR directory requests and retry temporary HTTP 429 responses.

## [1.1.1] - 2026-08-17

### Fixed

- Use a browser User-Agent for AList API requests rejected by the ASMR site's WAF.
- Limit MP4 duration probing to the 30 largest pending files per scan.
- Update Vite to 7.3.6 to resolve development-server security advisories.

## [1.1.0] - 2026-08-17

### Added

- Short and long Guangya surfaces split at 180 seconds.
- Background MP4 duration probing plus browser metadata reporting.
- Independent public ASMR AList indexing by root author folder.
- Author search, media title search, and all/video/audio filters.
- Direct MP3, video, and HLS playback with a persistent mini-player.
- Lazy-loaded ASMR library and HLS engine to protect short-video startup time.

### Changed

- SQLite scans, URL caches, statistics, and Fast Start work are source-aware.
- Playback preferences now remember the selected media surface and per-surface resume item.
- CSP permits HTTPS HLS playlist and segment requests.

## [1.0.0] - 2026-08-16

### Added

- Android-first short-video PWA backed by a nested AList directory.
- Direct 302 media delivery without proxying or transcoding video bytes.
- Vertical feed playback with adjacent-video preload and direct URL prewarming.
- Saved playback progress, mute state, feed mode, and recent-view history.
- Shuffle playback that avoids recently watched videos.
- Lightweight management view for rescans and MP4 Fast Start checks.
- Portrait and landscape playback with seek and video-navigation gestures.
- Software-rotated landscape fallback for Android orientation-lock failures.
- Transient playback controls with isolated volume and playback interactions.
