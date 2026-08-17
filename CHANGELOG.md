# Changelog

All notable changes to this project are documented in this file.

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
