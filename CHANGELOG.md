# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

### Fixed

- A rescan re-reads rows the catalogue matched but left without artwork. Those rows used to be skipped for good, so a cover the
  catalogue picked up later had nowhere to land and the wall kept a grey card no refresh could clear. On a single pass over the
  seven movie libraries this recovered 137 covers in `M男` alone; the retry is idempotent when the catalogue still has no image.
- A rescan also stops trusting the mirrored catalogue. The copy is kept for ten minutes on purpose, which meant covers uploaded to
  the shared service since the last fetch were matched against the same empty poster that had already failed. One refresh now pays
  the index walk and the next seven libraries reuse it, so the walk happens once per burst of user actions instead of never.

## [1.6.0-beta.17] - 2026-09-19

### Added

- Artwork is served at the size it is drawn. The client appends the pixel width it is about to render, the proxy snaps that onto a
  six-step ladder (`240/360/480/720/1080/1440`), downscales with LANCZOS and answers with WebP. A 1080px cover that used to cross the
  Pacific at 196 KB arrives at 54 KB, and every density on the market shares one cache entry instead of minting its own.
- The shared catalogue index keeps a disk mirror at `data/shared-metadata-index.json`, so a container restart is ready in seconds
  instead of paying the full ~28-page walk again, and a failed refresh reuses the stale copy rather than emptying the catalogue.

### Changed

- Every width is cut from the one copy already on disk. The wall caches the full-size cover as a side effect of its scaled request,
  and the detail page derives from it: opening a film no longer opens a second stream to an artwork host that drops a share of
  connections. `X-Movie-Image-Cache` reports `derived` for that path.
- A library page opened from the wall paints the six covers the wall already has before its own first page lands, and its footer no
  longer claims there is more to load when the page failed.
- The catalogue index walks up to 80 pages (27,667 titles) instead of 20, and is fetched through one process-wide client, so a scan
  no longer rebuilds the same tables for every library.
- A row whose artwork came from the retired TMDB scraper is dropped on the next authoritative pass instead of surviving as a stale
  cover forever.

## [1.6.0-beta.15] - 2026-09-19

### Added

- A finished `短剧` episode now rolls on to the next part of the series, the way a series player would. The previous
  episode stayed on screen after playback ended, so the last frame froze until the user tapped the next part.
- `加载更多` opens the library on its own page: the whole library, its own cursor, and a sort control offering
  `封面优先` / `标题` / `按时间`. The button used to append a few more posters to the wall, which buried every other
  library below the one being expanded, so it read as a dead end rather than an entry point.

### Changed

- The movie header keeps only the three-line library menu. Search, the sort chip and the library/source chips are gone;
  a few hundred titles per library are browsed by opening the library, so the controls were duplicating the navigation.
- Media-source rows are about half their old height: name, item count and section on the first line, icon-only
  `编辑` / `扫描` / `删除` on the second. The metric wall and the cover-refresh button underneath are gone, so a dozen
  libraries now fit on one screen.
- Scanning a movie library finishes the covers on its own, so the manual match button was removed.

### Removed

- TMDB is gone. The shared metadata catalogue is the only artwork source now, so a title the catalogue does not know
  lands in `需确认` instead of being filled from a second provider.

## [1.6.0-beta.14] - 2026-09-19

### Added

- The movie wall now mirrors the libraries the user added: one section per media source, six posters each, and a
  `加载更多` button that only ever extends its own section. The wall used to merge every library into a single grid.
- A three-line button in the movie header jumps straight to a library, so a long wall does not need scrolling.
- The media-source editor offers the service addresses already typed in, plus the root folders used with the picked
  address, so adding a library is one tap plus a folder.

### Changed

- Media-source actions in the management screen are icon-only (edit / scan / refresh covers / delete) with 48dp touch
  targets, so a long name or a busier row no longer squeezes the buttons into two lines.
- Clearing the movie search box returns to the wall that is already loaded instead of refetching it, so the sections
  the user expanded survive the round trip.

### Fixed

- Library sections now follow the order the user added them. The registry rebuild collapsed the database ordering into
  a `set`, so the wall and the management list could order the same libraries differently on every restart; ties inside
  the same second now fall back to `rowid` instead of the library name.
- A failed `加载更多` reports inside its own section. It used to be stored in the catalogue error field, which the wall
  only renders while it is empty, so the failure looked like nothing had happened.

## [1.6.0-beta.13] - 2026-09-19

### Added

- A fifth section, `短剧`, after the movie wall: one card per series, and a series opens an episode picker. Series
  are grouped server-side from their folder (`<剧名> [91crdj-<id>]`), and each episode is an ordinary row in `videos`,
  so playback, progress, prefetch and the cache keys are reused as they are.
- A `drama` media-source section alongside `feed` / `asmr` / `movie`. Scans stay manual, and a re-scan of the feed
  can no longer touch the drama library or the other way round.
- Series titles and posters come from the 91crdj detail page, one request per series, written once and cached in the
  database. Placeholder folder names such as `▶ 立即观看` are replaced by the real title.

### Changed

- Reworked the movie header: search plus a sort icon button on one row, and the library totals move to a horizontally
  scrollable stats chip row underneath, so long numbers no longer wrap.
- Reworked the media-source editor into a full-height bottom sheet: the four sections are equal-width icon tiles
  (视频 / ASMR / 电影 / 短剧), fields carry leading icons, and the save/cancel pair stays pinned at the bottom.
- The media library summary in the management screen now reports the drama count, and a drama source shows its own
  scrape button and match summary.
- Re-balanced the top tab bar for five sections so it no longer overlaps the overflow button on narrow screens.

### Fixed

- Drama posters load again. 91crdj ships every picture as an AES-CBC blob, and the image proxy needed an AES backend
  that the runtime image never installed, so `decrypt_media` raised `ModuleNotFoundError` and every cover answered
  `502 Movie image upstream returned an undecodable payload`. `cryptography` is now an explicit requirement, the
  decrypt error says which backend is missing, and a failure is logged with the offending URL.

## [1.6.0-beta.12] - 2026-09-18

### Changed

- Movie covers come from the shared catalogue only. MetaTube is gone: the client, the settings, the compose service and the filename scraper are removed, so no numbered title can trigger a third-party scrape again.
- A finished movie scan runs the cover pass by itself. Scanning is now the only manual step, which is what closes the loop when a media source changes.
- The per-source management button is relabelled `刷新封面` / `匹配中`, because it no longer scrapes: it re-reads the shared index.
- The movie payload no longer carries `metatubeProvider` / `metatubeId`, and older databases keep their legacy columns untouched.

### Fixed

- A metadata miss no longer blanks a row. Re-running the pass over a matched library used to write `poster_url = NULL` for every title the current provider could not answer, so a switched-off provider wiped the wall.
- Titles carrying a番号 are never sent to TMDB, where a coincidental name used to produce a wrong cover.

## [1.6.0-beta.11] - 2026-09-18

### Added

- Consult an optional read-only shared metadata service (`SHARED_METADATA_BASE_URL` / `SHARED_METADATA_TOKEN`) before MetaTube and TMDB, so movies whose provider scraping is incomplete still resolve a cover, plot and studio.
- Match shared metadata by parent folder, then by the file name without its `@` suffix, then by the raw file name, and cache the remote index for 10 minutes.
- Report skipped directories in the management view after a scan that lost some folders to repeated origin timeouts.

### Fixed

- Stop rescanning directories that the AList origin keeps timing out. Each directory step now has a bounded attempt budget, so a scan always reaches `completed` instead of looping in `interrupted` forever.
- Keep already indexed media active when a directory is skipped, and surface a warning instead of silently dropping the rows.

## [1.6.0-beta.10] - 2026-09-18

### Changed

- Order the movie wall with covered artwork first and unmatched movies last.
- Add sorting to the movie section.

## [1.6.0-beta.9] - 2026-09-17

### Fixed

- Stream large AList directory listings instead of buffering them, so big movie sources stop exhausting memory during a scan.
- Finalize a scan safely so partially scanned sources keep their existing movies.

## [1.6.0-beta.8] - 2026-09-05

### Changed

- Refine the movie detail cover presentation on Android.

## [1.6.0-beta.7] - 2026-09-01

### Changed

- Use the higher-resolution JavBus `cover_url` as the primary landscape movie-wall image.
- Use the smaller `thumb_url` only as the blurred detail-page background, removing image overlap and blurry wall artwork.
- Strip duplicate backdrop layers from the Android and PWA detail heroes for a single clean visual layer.

## [1.6.0-beta.6] - 2026-09-01

### Changed

- Use higher-resolution TMDB posters and original-quality backdrops for the movie detail experience.
- Render the Android and PWA movie detail pages with a full-screen blurred backdrop and softer content veil.
- Keep the landscape-first movie wall on video-frame artwork when no backdrop is available.
- Increase movie artwork caches on the server and Android client, with ETag revalidation for PWA image requests.

## [1.6.0-beta.5] - 2026-08-28

### Added

- Use landscape artwork as the primary movie-wall cover, with portrait artwork as a fallback.
- Persist proxied movie artwork on the server and prewarm the first visible wall items.

### Fixed

- Keep movie-wall artwork requests on the authenticated application origin for reliable mobile loading.

## [1.6.0-beta.4] - 2026-08-27

### Fixed

- Proxy MetaTube movie posters and backdrops through the authenticated application host so JavBus/DMM hotlink protection and mobile-network differences no longer leave matched movies without artwork.
- Follow image redirects, add provider-compatible request headers, validate image content and size, and cache successful artwork on the client for 24 hours.

## [1.6.0-beta.2] - 2026-08-24

### Changed

- Pre-resolve and briefly cache upcoming AList/OpenList 302 targets while retaining stable media-byte cache keys.
- Use format-specific MIME types for common video and ASMR audio containers.
- Use compact `Video`, `ASMR`, and `Movie` labels in the Android media-source editor.

### Fixed

- Refresh an expired signed media URL once without changing the active item or playback position.
- Start each horizontal seek gesture from the latest playback position across feed, ASMR, and movie players.
- Isolate mute preferences between short video, long video, ASMR, and movie surfaces.
- Keep Android status-bar icons visible above media with an explicit dark safe-area background.

## [1.6.0-beta.1] - 2026-08-21

### Added

- Add a lightweight movie library backed by independently managed AList/OpenList sources.
- Add source-scoped manual scanning and TMDB metadata scraping with progress and match summaries.
- Add native Android and PWA movie walls, search, details, playback, seeking, landscape, and resume progress.

### Changed

- Rename the user-facing application from `短片` to `deepfuck` while retaining its existing package and local storage identities.
- Keep movie scanning and metadata scraping manual and isolated from short-video, long-video, and ASMR playback state.

## [1.5.1] - 2026-08-21

### Fixed

- Keep the ASMR mini-player in the page layout instead of overlaying the media list, so rows no longer show through or receive touches beneath the liquid-glass surface.
- Pin Android debug and release builds to the existing stable signing certificate so in-app updates can replace previously installed versions without removing local data.

## [1.5.0] - 2026-08-21

### Added

- Apply the restrained liquid-glass visual system across the native Android client, including navigation, playback controls, ASMR mini-player, management source cards, login, and update dialogs.
- Add responsive top-tab sizing, accessible selected states, safe-area-aware ASMR list insets, and subtle press/highlight feedback for Android controls.

### Changed

- Move ASMR portrait video controls for landscape mode and video background playback into the lower-right one-hand reach zone; landscape keeps its existing control layout.
- Keep media content and flat ASMR lists unobstructed while limiting glass surfaces to navigation, control clusters, dialogs, mini-player, and actionable source cards.

## [1.4.3] - 2026-08-20

### Fixed

- Defer short/long feed sequence refreshes triggered by a completed source scan until the management screen is closed or the feed becomes visible again.
- Prevent feed activation and automatic playback while the management screen is visible, including asynchronous feed callbacks that finish after management was opened.

## [1.4.2] - 2026-08-19

### Changed

- Run APK downloads as expedited Android `dataSync` foreground work with a persistent progress notification, additional retry capacity, and retained HTTP range-resume data so downloads continue reliably after lock screen.
- Make all AList/OpenList scans explicitly manual and per-source; application and container startup no longer scans any library or starts a periodic scan loop.

### Fixed

- Follow each manually started source scan through its real terminal state before refreshing the affected library.
- Invalidate Android ASMR author and media caches after an ASMR source scan, refresh the current list immediately, and remove authors that no longer contain active media.
- While an ASMR scan is running, let Android and PWA author views poll the existing index endpoint until the completed scan is visible, including scans started from another client.

## [1.4.1] - 2026-08-19

### Fixed

- Publish each ASMR author's same-type media queue to Android MediaSession so lock-screen and notification controls expose working previous and next actions.
- Automatically continue ASMR videos in author order, including appending later API pages without reloading the current item.
- Keep the in-app now-playing state, expanded video surface, persistence, prefetching, and background service synchronized after system media controls change items.

## [1.4.0] - 2026-08-19

### Added

- Add persistent AList/OpenList source management with create, edit, enable/disable, per-source statistics, and independent scan actions in Android and PWA.
- Aggregate any number of sources into the short/long feed or ASMR section while retaining the 180-second short/long split.
- Seed the existing library as three independently managed sources: Guangya, `asmrgay / asmr`, and `asmrgay / asmr6`.

### Changed

- Route scans, direct URL resolution, duration probes, and Fast Start checks through each media source's own runtime client and cache.
- Scope media uniqueness to source plus path so identical paths from different AList/OpenList servers can coexist.
- Replace the Android and PWA launcher artwork with the supplied mint player icon.

### Fixed

- Rescanning one media source no longer scans or changes any other source in the same section.
- Preserve existing media IDs and indexed records while migrating legacy databases to source-scoped paths.

## [1.3.7] - 2026-08-19

### Changed

- Replace the Android launcher artwork with the supplied red-and-white player icon.
- Mark each library scan with a unique token and deactivate only media missing from that scan instead of rewriting every active ASMR row up front.
- Increase the container's temporary filesystem from 16 MB to 64 MB for large SQLite scan operations.

### Fixed

- Prevent `database or disk is full` during large ASMR scans while preserving stable media IDs, source isolation, and removed-file detection.
- Give the collapsed ASMR player an explicit foreground input plane so taps on its empty area cannot activate media rows underneath it.

## [1.3.6] - 2026-08-19

### Fixed

- Recover pagination for legacy 18-item shuffle sessions whose cached cursor is missing while the authoritative library total still has unseen videos.
- Persist the shuffle exclusion set and resume item across process restarts so every page continues the same randomized sequence instead of silently resetting it.

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
## [1.6.0-beta.3] - 2026-08-27

- Add private MetaTube scraping for exact numbered media, with TMDB retained for ordinary movies.
- Store and show metadata source, release date, studio, genres, and performers in movie details.
- Add confirmed media-source deletion that removes only the local index, never the AList/OpenList files.
- Include the pending Android player and management UI refinements in the next APK update.
