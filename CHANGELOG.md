# Changelog

All notable changes to this project are documented in this file.

## [1.3.5] - 2026-08-19

### Added

- Add all/video/audio filtering to the ASMR author index and carry the selected type into each author's paginated media library.
- Add a seekable buffered progress line and elapsed/total time to the persistent ASMR audio and collapsed-video player.

### Fixed

- Register the shared Media3 session with `MediaSessionService` and let Media3 own notification foreground promotion, eliminating the foreground-service timeout behind all 12 captured ASMR crashes.
- Isolate ASMR item caches, pagination jobs, and audio queues by author and media type so playback, filtering, navigation, and background controls cannot overwrite each other's state.
- Retry temporary upstream AList `Error 1040: Too many connections` responses with bounded exponential backoff during ASMR library scans.
- Refresh the authoritative short/long library total without replacing or activating the restored shuffle queue, correcting stale `1 / 18` counters.
- Provide a high-contrast foreground at the Compose root and explicit operational text colors so ASMR and management content cannot render black on the dark canvas.

## [1.3.4] - 2026-08-18

### Added

- Continue application-update downloads through WorkManager after the update dialog or app leaves the foreground.
- Resume interrupted APK downloads from a persistent partial file using verified HTTP byte ranges.

### Changed

- Store downloaded updates in the app's persistent files directory and restore active, completed, or interrupted update state when the update screen is reopened.
- Label update actions as background or resumable downloads while retaining Android's required system-installer confirmation.

### Fixed

- Preserve valid downloaded bytes across network failures, process recreation, and WorkManager retries instead of restarting every update from zero.

## [1.3.3] - 2026-08-18

### Added

- Add rotating on-device runtime logs for uncaught exceptions, Media3 failures, playback-service lifecycle, and surface/navigation transitions, with management controls to view, clear, and export a redacted report.
- Replace the Android launcher and themed icons with the supplied application artwork.

### Fixed

- Keep independent short- and long-video sessions, including their original sequence, active item, pagination cursor, play state, and resume position, so switching surfaces no longer starts a new shuffle or loses swipe-back history.
- Reject stale feed responses and playback-end callbacks after a surface or playback-generation change, preventing an old short/long item from advancing while ASMR is open.
- Remove the second feed-loading indicator near the progress control so buffering feedback appears only over the video.
- Clamp restored ASMR list positions, remove duplicate lazy-list keys, reject stale author-media callbacks, and make player/session operations failure-safe to reduce navigation and playback crashes.

### Changed

- Persist the selected ASMR author, current audio/video item, play state, expanded state, queue position, and media progress across page and process changes.
- Cache the complete ASMR author index on-device for 24 hours, allowing immediate full-author search without repeatedly loading the directory.

## [1.3.2] - 2026-08-18

### Fixed

- Invalidate the active playback generation before stopping ExoPlayer so delayed or synchronous end events cannot advance a short or long feed after switching to ASMR.
- Keep the ASMR media session stable while pausing, opening management, or changing background preferences, removing the rapid service restart path that could crash the app.

### Changed

- Treat ASMR audio as music-style playback that always continues through management, screen lock, and app backgrounding; ASMR video retains its explicit background toggle.
- Load the complete ASMR author metadata index once per app session so local search covers every author, while keeping author media lists paginated.
- Replace the static current-audio icon with a compact animated equalizer and remove the redundant audio background toggle from the mini-player.

## [1.2.3] - 2026-08-18

### Added

- Add paged ASMR author/media API responses and progressively render the first 24 results while later pages load in the background.
- Play ASMR audio inside the media list and automatically continue through the author's audio items in order.
- Advance short and long feeds automatically when the active video ends.

### Fixed

- Reuse the same Android `PlayerView` across feed pages, retain the decoded frame, and delay transient buffering feedback to remove short loading flashes.
- Give expanded ASMR playback exclusive back-gesture priority before author navigation and retain both author-list and media-list scroll positions.

### Improved

- Replace visible circular control shells with transparent touch targets and white overlay icons.
- Redesign the top media switcher as centered equal-width tabs with an animated active underline and restrained directional content transitions.
- Replace the default thick slider with a two-pixel progress track, compact circular thumb, buffered range, and subtle playing pulse.

## [1.2.2] - 2026-08-18

### Improved

- Replace dark translucent Android playback controls with higher-contrast white frosted controls and dark foreground icons.
- Move the short-video, long-video, ASMR, and management navigation slightly closer to the top safe-area edge.
- Show complete ASMR author names on a dedicated line for faster scanning and search.

## [1.2.1] - 2026-08-17

### Fixed

- Declare the Android `WAKE_LOCK` permission required by Media3 network wake mode, preventing a `SecurityException` when playback starts.
- Add a manifest regression test that keeps the wake-mode permission contract explicit.

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
