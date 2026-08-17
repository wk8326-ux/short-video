# Changelog

All notable changes to this project are documented in this file.

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
