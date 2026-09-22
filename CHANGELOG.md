# Changelog

All notable changes to this project are documented in this file.

## [1.6.0-beta.27] - 2026-09-22

### Fixed

- 给已有媒体库新增一个 AList 源不再把它甩到电影墙末尾。旧实现是「先移除再插入」，插入时 key 已经是新的，
  顺序自然排到最后；现在改成原地覆盖，只有真正新建的媒体库才会追加到末尾。顺序调整页的手动排序依旧是最上层规则。
- 封面优先排序真的把无封面影片放到最后了。判定条件只看 poster_url，而共享元数据写入的是 poster_url / backdrop_url / thumb
  三个字段里的任意一个，于是只有 backdrop 或只有缩略图的影片被当成「没有封面」，排在了有封面的前面。现在三个字段任一存在即算有封面。
- 电影墙、媒体库子页面、影片详情页现在用同一张封面。之前详情页会去请求另一条 URL（缩放并裁到一侧），
  和电影墙不是同一个缓存条目，所以会出现「墙上有了、详情页还没有」；现在三个位置拿到的是同一个地址。
- 三处封面都不再裁剪。之前电影墙用 Crop 填充，宽高比不一致的封面会被切掉边缘；现在统一按原图比例完整显示。

### Added

- 标题、时间两种排序支持正序/倒序：点一下选中，再点一下切换箭头，菜单里用 ↑ / ↓ 显示当前方向。
  封面优先本身没有反向含义，保持单一状态。
- 媒体库排序会持久化。退出 App 再打开，电影墙和子页面仍按上次选择的方式排列。
- 电影墙的前 6 个封面跟随媒体库子页面的排序：三种展示方式（分栏 / 媒体库 / 单行）预览的都是当前排序下的前 6 部，
  在子页面改完排序，返回电影墙即时同步。

## [1.6.0-beta.26] - 2026-09-22

### Fixed

- 拖动排序真正可用了：改成独立的"调整顺序"页面。管理页的长按拖动一直失败，原因是那一次拖动和管理页自己的滚动是同一个手势，
  滚动总是先赢，卡片根本不会动；现在进入单独页面，拖动只由左侧手柄接管，页面本身不滚动手势竞争，卡片全程跟随手指。
- 只有电影板块参与排序。服务端是按板块分别保存顺序的，短视频/长视频、ASMR、短剧的库不再出现在排序页里，保存也不会移动它们。
- 保存后电影墙同步：点"保存"才提交顺序，服务端按板块写回，电影墙的分组顺序立即按新顺序展示。

### Added

- 调整顺序页每一行都带上移/下移按钮，长按拖动不好操作时可以直接点箭头微调；拖到列表上下边缘时会自动滚动，
  库很多也不用先滚到位置再拖。

## [1.6.0-beta.25] - 2026-09-21

### Fixed

- Dragging a library in the management screen now actually moves it. The rows had no stable identity, so the first time
  a card traded places with a neighbour Compose rebuilt that row and dropped the finger tracking; the card fell back
  where it started. Each row is keyed by its library now, and the drag keeps following the finger to the end of the
  gesture.
- A drag stops at the edge of its own section. The server keeps one order per section and the management list shows
  every section at once, so a card dragged past a library from another section would be put back on the next refresh.
  The card is now fenced inside the block it started in, which is the only move that can be stored.

## [1.6.0-beta.24] - 2026-09-21

### Added

- Each library card in the management screen shows how many root folders it scans, as a folder chip on the name row.
  Tapping the chip expands the folder paths in place, so a library can be checked without opening the editor, and tapping
  it again collapses the list.

## [1.6.0-beta.23] - 2026-09-21

### Added

- Libraries can be dragged into the order the viewer wants. The handle on each row in the management screen moves a
  library up or down, the new order is stored on the server, and the film wall draws its sections in that order, so a
  library that matters can sit above the ones that do not.
- A library remembers where it was left. Opening a film from a library page and coming back returns to the same tile
  instead of snapping to the top of the grid, and each library keeps its own scroll position and its own sort.
- The sort chosen inside a library now reaches the wall. The six tiles a section shows are the first six of that
  library's own order, so "covers first" holds on the wall as well as on the library page.
- Portrait playback gained the horizontal seek gesture that only the landscape surface had. A drag left or right seeks
  by a fraction of the running time from wherever the film actually is, on films, dramas, clips and ASMR alike.

### Changed

- The film detail page is one picture instead of two. The full-bleed blurred backdrop is gone; the hero still, cropped
  to its right half, is the single image, and the title and metadata sit on the gradient beneath it. A film that had a
  cover on the wall now has that same cover here, because both read the same field.

### Fixed

- A library could stay blank forever when its cover pass was requested while another one was already running. The pass
  was refused instead of queued, so three libraries — 月度更新, 洗脑轮奸 and RKI系列 — sat with every row pending and
  drew an empty wall. Passes are queued now and the pending libraries are picked up again after a restart, which fills
  the covers the shared index has held all along.

## [1.6.0-beta.22] - 2026-09-20

### Added

- The movie player is one immersive surface in portrait too. Starting a film no longer pins a sixteen by nine strip
  under the top edge with the metadata page and the controls below it; the picture fills the screen, letterboxed in the
  middle, and the controls fade away after five quiet seconds. One tap anywhere brings them back, the way the landscape
  surface has always worked, and a paused film keeps them on screen because the pause was deliberate.
- Each library page warms its own first screens of covers, so opening a library draws a filled grid instead of stacking
  its first six tiles one network round trip at a time while the rest of the page sits empty.

### Changed

- The picture cache on the server is eight times larger (256MB to 2GB) and the warmed width now matches the width a
  phone actually asks for. Snapping a 178dp tile on a 3x screen lands on 720px; the warm-up was filling 480px entries
  that nothing ever read, so most first paint still crossed to the artwork host. Both sizes are still cut from the same
  cached original, so a 2x phone pays a local resize instead of a second download.
- Every section of the movie and drama walls is warmed, capped at forty-eight rows, in the order they come on screen.
  Warming only the first two sections left a wall that starts empty as soon as the viewer scrolls.
- A phone keeps the covers it has seen: the artwork cache on the device goes from 256MB to 1GB, and the memory cache
  from 0.18 to 0.25 of the heap. Covers are immutable, so a library opened once should open off local storage next time.

### Fixed

- A cover that failed upstream is remembered for ten minutes instead of being retried on every scroll. The artwork
  hosts take about twelve seconds to admit they are down, so a dead cover used to cost a stall per pass over it, and
  the warm-up spent a download slot on every section for a picture that could not arrive.

## [1.6.0-beta.21] - 2026-09-20

### Added

- Videos can be shrunk into a floating window from any of the four surfaces -- short clips, long clips, films and short
  dramas, plus ASMR video. The window keeps the running picture and its own aspect ratio, and the app keeps playing while
  the window stays on screen, so a film can be watched over another app the way a system video player allows. Leaving the
  app entirely still pauses everything that is not flagged for background playback: the floating window only survives while
  it is visible.
- The short-drama wall is grouped by library the way the film wall is. The downloader's first-level folders ("短剧", "漫剧",
  …) become sections with six tiles each and a "加载更多" that opens that category on its own page, so a wall of two hundred
  and forty series no longer arrives as one undifferentiated list. The recent strip now scrolls with those sections instead
  of being pinned over them.

### Changed

- The library tile on the movie wall stitches four covers instead of six. Two
  tiles share a row, so a 3x2 grid left each cover about 60dp wide -- narrower
  than the shelf tiles below it -- and the artwork read as slivers. The grid is
  square now, which doubles every cover and makes the tile taller, so a wall of
  libraries is legible without opening one.

### Fixed

- A finished drama episode no longer erases its series from the "最近播放" strip. Short-drama episodes run two or three
  minutes, so finishing one is the normal outcome rather than an edge case, and a strip that dropped completed series left
  only the one title that had been abandoned mid-episode -- which read as "the list only ever shows one entry". A series now
  stays on the strip and points at the next episode in playback order; the finale replays from the top instead. Episode
  order comes from the same filename key the reader uses, because "第9集" sorts after "第10集" as text.
- The strip is refetched after the detail page reports progress, so a title appears the moment it is left rather than one
  visit later. Switching between the four surfaces refreshes it too, and the short-drama request now cancels the one it
  replaces -- a late response from a previous query could otherwise overwrite the newer list forever.
- If the remembered episode of a drama is already finished, the strip and the detail page resume from the next one instead
  of reopening the closing seconds of the episode just watched.

## [1.6.0-beta.20] - 2026-09-20

### Added

- A media source can carry several root folders. One source is one login and one address, but the folders under it are a
  list, so libraries that belong together -- the same drive split across directories -- can be scanned and counted as one
  entry instead of being registered again for every folder. The editor keeps one row per folder with an "add folder" action,
  and the roots are stored as a JSON list while `rootPath` stays as the first entry so older rows keep working.
- The movie wall opens on a "最近播放" strip: the ten films the server last saw in progress, newest first, each with the
  fraction already watched drawn under its cover. A wall sorted by anything else buries the one row a viewer actually
  wants, so it sits above every library and never scrolls away sideways. The short-drama wall gets the same strip.
- The movie wall can be drawn three ways, chosen from a menu on the left of the header that mirrors the library menu on the
  right: the existing shelves (six tiles per library plus "加载更多"), a wall of library tiles where each library is one
  mosaic stitched from its first six covers, and single-row shelves where each library is one sideways row with a trailing
  "更多" tile, the way a media server lists a collection. The choice is remembered locally.
- Watch progress is reported to the server while playing (throttled to one report per fifteen seconds, plus an immediate
  one on seek, pause, surface change and leaving the app). Resume position is therefore no longer local-only: a reinstall or
  a second device reopens a film where the last one stopped. `POST /api/progress` records it and
  `GET /api/movies/recent` / `GET /api/dramas/recent` read the two strips back.
- Continuing a short drama from the strip reopens the series and drops straight into the interrupted episode, preferring
  the episode the server recorded and falling back to the first one when the series has changed since.

### Changed

- The short-drama header no longer carries a search field and a count tile; both were read-only chrome above a wall that
  already lists everything, and they are replaced by the recent strip. Searching short dramas is gone with them.
- Adding or editing a source accepts an empty `rootPath` as long as at least one root in the list is filled, and the scan
  walks every root the source declares instead of only the first.

## [1.6.0-beta.19] - 2026-09-20

### Added

- The shared catalogue is re-checked against the second version of the metadata API. A live walk of the whole inventory now
  returns 28,629 rows with 28,369 carrying a poster address, byte-for-byte the same counts the contract documents
  (`ready` 18,852 / `missing` 7,955 / `missing_poster` 1,305 / `missing_nfo` 107 / Emby-only 410), and exact
  `media_path` queries answer with the same single row the app's own folded path keys produce. Matching therefore needs no
  change; the numbers behind the movie wall are the numbers the service publishes.

### Fixed

- The folded catalogue is no longer kept in memory. Every row used to be collected into a Python list and then folded into
  three dictionaries, which is what put the container over its memory cap and had the kernel kill it about every forty
  seconds: the rows are now written into a SQLite mirror (`data/shared-metadata-index.db`) one page at a time, and only the
  page being read is ever resident. The whole 28,629-row catalogue costs 0.1 MiB of Python memory to publish, against the
  ~18 MiB the folding dictionaries alone needed on top of the rows themselves.
- A restart no longer re-walks the catalogue. The mirror records the walk it came from, so a container that comes back up
  serves covers in 0.02 s instead of paying the fifty-second fetch again; the old 11 MB JSON mirror is removed once the
  database that replaced it is in place.
- The mirror behind the folded tables is released when it is replaced or the client closes. Each walk publishes its own
  read-only connection, so without this a long-running container kept one file handle per walk until it was redeployed, and
  a closed client kept the cache file locked after it could no longer answer from it.
- Cover coverage across the seven movie libraries is re-measured from the live service: 2,400 of 2,468 titles have artwork
  (was 2,334 before path matching). Of the 68 that do not, 64 have no row in the shared catalogue at all -- split files such
  as `DVD #1 / DVD #2` and `.wmv` shards that have no entry of their own -- and 4 are rows the service holds for a different
  release in the same folder, which are left without a cover rather than given the wrong one.

## [1.6.0-beta.18] - 2026-09-20

### Added

- The movie catalogue is matched by whole media path first. The shared service's second version publishes one row per media
  file keyed by its complete path, and that key is now the primary identity, so a folder holding dozens of unrelated releases
  can no longer hand one file another release's cover. Across the seven movie libraries this lifted 62 rows onto a cover
  (2,334 → 2,396 of 2,468); all 2,396 come from a path hit, and the four rows still matched by code carry no image at source.

### Fixed

- A scan no longer asks the catalogue for one title at a time. An index row already carries the overview, studio, actors,
  genres and both cover URLs, so a pass over 27k titles now costs one walk instead of thousands of `/metadata` round trips.
- The catalogue walk covers the whole catalogue even when the service clamps the page size it was asked for: the walk
  advances by the rows that actually arrived, so records past a clamp are no longer skipped.
- A cold catalogue is now retried long enough to be read. The service rebuilds its inventory on a cold `/index` and the
  gateway in front of it answers `502` for the first thirty seconds of that build, so the old twenty-second ladder never
  saw the catalogue at all; the ladder now spans about five minutes.
- A rescan checks the catalogue's `index_version` before walking it: an unchanged inventory is proved with one row instead of
  the ~28-page walk, and the full walk is forced once a day because Emby-side artwork is invisible to that version.
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
