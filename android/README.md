# Android client

Private Android 8+ client for `short.deepfuck.you`.

## Playback model

- Metadata and authentication use the existing FastAPI service.
- A cache miss resolves `/api/videos/{id}/play`, then ExoPlayer connects directly to the 302 target.
- Cached ranges use stable media IDs or query-free origin URLs, so an expired signed URL does not invalidate downloaded bytes.
- The application server never carries media payloads.

## Cache policy

- Capacity: 1 GiB, least-recently-used eviction.
- Short video up to 96 MiB: full-file prefetch.
- Larger short video: first 24 MiB.
- Long video and direct ASMR audio/video: first 12 MiB.
- HLS: playlists and segments are cached as playback requests them.

Removing the app or clearing its storage removes the cache and saved playback state.

## In-app updates

Open `更多 -> 检查更新`. The client uses the existing authenticated session to fetch the release manifest and APK from `short.deepfuck.you`, shows download progress, verifies SHA-256, and then opens Android's system installer. The first use asks for permission to install unknown apps for this application; Android does not allow a private app to install silently.

Update APKs must keep the same application ID and signing certificate. The downloaded file is kept only in the app's private cache and is exposed to the system installer through a read-only `FileProvider` grant.

## Background playback

ASMR audio and video run through `PlaybackService`, a Media3 foreground `MediaSessionService`. Playback continues with the screen locked or the app in the background and remains controllable from the notification and lock screen. Short and long video feeds pause when the activity leaves the foreground.

Android 13+ asks for notification permission when ASMR playback first starts. Denying the permission does not change the media route, but system playback controls may not remain visible.

## Build

JDK 17 and Android SDK 35 are required.

```powershell
.\gradlew.bat lintDebug testDebugUnitTest assembleDebug --no-daemon
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

The debug APK is suitable for private installation. Keep the signing key used for any future release APK stable, otherwise Android will require uninstalling the previous build before an upgrade.
