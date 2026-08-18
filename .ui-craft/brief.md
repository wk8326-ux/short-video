# Product purpose

Play private Guangya short/long video feeds and browse a separate author-organized ASMR library in a native Android client or fallback PWA, all with direct origin delivery.

# Primary user

One private user watching and listening primarily on an Android phone in repeat sessions.

# Principles

1. Default short-video playback starts before management or optional media engines get attention.
2. Keep media bytes off the application server.
3. Remember useful state without adding accounts or setup.
4. Show operational facts compactly and on demand.

# Success metric

The user resumes media at the saved position, sees no recent repeats in shuffle mode, can browse ASMR by author and format, and can inspect library health without leaving the app.

# Out of scope

- Does not proxy or transcode media.
- Does not add recommendations, social features, or registration.
- Does not change AList anonymous-access policy.
- Does not become a general media-library manager or recommendation surface.

# Learned constraints

- **2026-08-16** - Keep the product lightweight and single-purpose. *Why:* Emby already handles long-form media; this surface exists specifically for short-video playback.
- **2026-08-16** - Keep playback chrome transient, especially filenames, progress, and bottom shading. *Why:* The video itself should remain unobstructed once the user has oriented to the current clip.
- **2026-08-16** - Avoid the native Fullscreen API in the Android PWA; use an in-app landscape surface instead. *Why:* Chrome's mandatory fullscreen safety prompt obscures the video and cannot be styled or suppressed by the page.
- ~~**2026-08-16** - Use one 1.5-second playback-chrome window in portrait and landscape. When chrome is hidden, the first surface tap only reveals it; a second tap while visible toggles playback, while direct control and gesture input always performs its own action.~~ Superseded 2026-08-17 by orientation-specific controls.
- **2026-08-16** - Keep volume state independent from playback state, and isolate every direct control from surface-tap handling. *Why:* Toggling an audio track must never replay, pause, or otherwise disturb the current video.
- **2026-08-16** - Landscape mode must work when Android rejects the Screen Orientation API. Software-rotate the player in a portrait viewport and remap gesture axes to the rotated coordinate system. *Why:* Browser/PWA installation state and device rotation policy must not disable landscape playback or its gestures.
- **2026-08-17** - Split Guangya at exactly 180 seconds: shorter media is short video and media at or above the boundary is long video. *Why:* Five and three-minute feed behavior differ enough that a stable product rule is preferable to an adjustable UI control.
- **2026-08-17** - ASMR opens as an author-first flat media library, then offers all/video/audio filtering inside an author. It never starts media on entry. *Why:* The source can contain both video and audio, and its folder authorship is the useful browsing structure.
- **2026-08-17** - Keep one persistent bottom player while browsing ASMR and lazy-load the HLS engine only when HLS playback begins. *Why:* Playback continuity matters inside the library, while optional HLS code must not tax Guangya first paint.
- **2026-08-17** - Show exactly one ASMR loading indicator. The compact player keeps loading feedback in its right action only, while expanded playback suppresses media icons and metadata until the media is ready. *Why:* Duplicate placeholder and loading layers overlap on narrow Android screens and obscure the state that matters.
- **2026-08-17** - Treat every M3U8 ASMR item as video, regardless of stale browser metadata. *Why:* This source uses M3U8 for video and an audio classification selects the wrong media element and controls.
- **2026-08-17** - Android system back/edge gestures close the expanded ASMR player before they can leave the PWA. *Why:* OS-level edge gestures can preempt page pointer handling, so player dismissal must participate in browser history.
- **2026-08-17** - Keep portrait feed chrome visible at the edges and let any non-control surface tap toggle playback. In landscape, hide chrome after five seconds, let a non-control tap only toggle chrome visibility, and reserve playback for the center button. *Why:* Portrait favors one-tap viewing while landscape needs an unobstructed frame and unambiguous control targets.
- **2026-08-17** - Keep ASMR audio and video playing through screen lock and app backgrounding, while short and long feeds pause outside the foreground. *Why:* ASMR is often consumed as continuous listening, but feed media should never continue unexpectedly.
- **2026-08-17** - Give ASMR video the same landscape control grammar as short and long video: five-second chrome fade, surface tap for chrome, center-only play/pause, horizontal seeking, and automatic portrait restoration when the player closes. *Why:* One video interaction model reduces mistakes and prevents the library from being stranded in landscape.
- ~~**2026-08-18** - Use translucent white playback chrome with dark foreground content, and keep the primary navigation close to the top safe-area edge.~~ Superseded 2026-08-18 by transparent icon controls and a dedicated tab bar.
- **2026-08-18** - Show complete ASMR author names on their own line instead of spending list width on initial avatars. *Why:* Full names are the useful identity and make similar authors easier to find.
- **2026-08-18** - Keep overlay icon hit targets transparent and render the glyph itself in white; do not wrap every control in the same visible circle. *Why:* Repeated circular shells obscure the image and reduce the visual distinction between controls.
- **2026-08-18** - Keep short video, long video, and ASMR as centered top tabs with equal spacing, a short active underline, and restrained directional motion. *Why:* The three peer surfaces should read as one stable navigation module while preserving spatial feedback during switching.
- **2026-08-18** - Play ASMR audio directly from the author media list and continue through later audio items in order. Preserve the author and media list scroll positions across player and hierarchy navigation. *Why:* Audio is a listening workflow, while losing list context turns every playback action into repeated browsing work.
- **2026-08-18** - Fetch ASMR authors and media one page at a time, requesting the next page only near the visible list end. *Why:* An eager full-library crawl causes avoidable recomposition, memory pressure, and browsing instability.
- **2026-08-18** - Deliver Android updates only through the authenticated first-party service and never embed private GitHub credentials in the APK. *Why:* APKs can be inspected, while the existing session boundary already protects private release artifacts.
