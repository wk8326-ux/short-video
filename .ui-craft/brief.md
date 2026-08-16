# Product purpose

Play a private AList short-video library with fast vertical swiping and direct CDN delivery.

# Primary user

One private user watching primarily on an Android phone in short, repeat sessions.

# Principles

1. Playback starts before management gets attention.
2. Keep media bytes off the application server.
3. Remember useful state without adding accounts or setup.
4. Show operational facts compactly and on demand.

# Success metric

The user resumes the last clip at the saved position, sees no recent repeats in shuffle mode, and can inspect library health without leaving the PWA.

# Out of scope

- Does not proxy or transcode media.
- Does not add recommendations, social features, or registration.
- Does not change AList anonymous-access policy.
- Does not become a general media-library manager.

# Learned constraints

- **2026-08-16** - Keep the product lightweight and single-purpose. *Why:* Emby already handles long-form media; this surface exists specifically for short-video playback.
- **2026-08-16** - Keep playback chrome transient, especially filenames, progress, and bottom shading. *Why:* The video itself should remain unobstructed once the user has oriented to the current clip.
- **2026-08-16** - Avoid the native Fullscreen API in the Android PWA; use an in-app landscape surface instead. *Why:* Chrome's mandatory fullscreen safety prompt obscures the video and cannot be styled or suppressed by the page.
- **2026-08-16** - Use one 1.5-second playback-chrome window in portrait and landscape. When chrome is hidden, the first surface tap only reveals it; a second tap while visible toggles playback, while direct control and gesture input always performs its own action. *Why:* Controls should be easy to summon without accidentally pausing the current video.
- **2026-08-16** - Keep volume state independent from playback state, and isolate every direct control from surface-tap handling. *Why:* Toggling an audio track must never replay, pause, or otherwise disturb the current video.
- **2026-08-16** - Landscape mode must work when Android rejects the Screen Orientation API. Software-rotate the player in a portrait viewport and remap gesture axes to the rotated coordinate system. *Why:* Browser/PWA installation state and device rotation policy must not disable landscape playback or its gestures.
