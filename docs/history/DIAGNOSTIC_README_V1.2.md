# Buccaneer 1.2 Diagnostic Build

Base: working Claude 1.1 source from `buccclaudlocal(1).zip`.

Changes:
- Added opt-in diagnostic recorder; OFF by default.
- Added navigation, popup, media, console, HTTP error, WebView provider and memory breadcrumbs.
- Added on-demand video diagnostic snapshots and dropped-frame/buffer information where WebView exposes it.
- Added manifest request breadcrumbs for `.m3u8`, `.mpd`, and manifest-like requests.
- Added SAF ZIP export under Settings > Diagnostics.
- Preserved the existing Popup Guard, media helper behavior, WebView settings, dependency stack and playback architecture.
- Fixed the prior compile-breaking `supportMultipleWindows` access by using the actual WebSettings getter call.
- Refreshed the Fire TV banner and rectangular launcher assets from the supplied 1280x720 artwork.

Build contract:
- compileSdk 34
- minSdk 22 (Android 7/API 24-25 supported)
- targetSdk 33 preserved from the proven base
- AndroidX WebKit 1.9.0 preserved
- no Media3/ExoPlayer added

This package contains source only; `app/build` and `.gradle` outputs are intentionally excluded.
