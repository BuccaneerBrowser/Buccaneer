# Buccaneer 2.2 — working handoff (2026-10-18)

This tree is the live source for the Fire TV build
(`com.buccaneer.browser`, versionCode 22 / 2.2).
Device: Fire TV AFTMM (Android 7.1.2, API 25) at `<device-ip>:5555`.

Build: `source <android-env>/env.sh && gradle assembleDebug`
Install: `adb -s <device-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
Diagnostics: `adb shell "run-as com.buccaneer.browser cat files/diagnostic_session.jsonl"`

## Current state

- Version 2.2, versionCode 22.
- Diagnostics enabled via `shared_prefs/diagnostics.xml` (`enabled=true`).
- Bookmarks and settings in `shared_prefs/`.

## Settings menu (current)

1. Dark mode
2. Desktop site
3. Autoplay without a click
4. Prefer low quality streams (default ON)
5. One video at a time
6. Software rendering
7. Diagnostics...

## Quality preference system

When "Prefer low quality streams" is ON, the app tries to select lower-resolution
streams (480p → 720p → others → 1080p) on HLS.js and Shaka players. Gated behind
the `lowQualityEnabled()` bridge method. When OFF, players choose their own quality.

## Known limitations (verified with diagnostics)

- **Fullscreen video top-alignment:** Bitmovin player in cross-origin iframe.
  CSS unreachable from the top frame. No app-side fix.
- **Quality auto-selection on Bitmovin:** Shaka instance in a closure, quality
  button requires a trusted user gesture. User must select quality manually.
- **Non-fullscreen video black:** PowerVR GPU compositing bug. Video decodes and
  plays audio, but the GPU fails to composite frames through the WebView pipeline.
  Fullscreen works (native CustomView). No app-side fix.
- **PowerVR GPU crashes:** `SIGSEGV` in `KEGLReleaseRenderTarget` on
  `Chrome_InProcGp`. Vendor driver bug. The watchdog restarts the app.

## Fixed issues

- **Flixer search images blank:** Cache-busting query param + allowlist in
  `flixer_image_guard.js`. The native TMDB firewall serves 1×1 pixels for
  non-allowlisted URLs; the browser cached them. Cache-buster forces a new
  request → firewall allows → real image loads.
- **StreamCorner sandbox error:** Error overlay hidden (video plays behind it).
  Sandbox attribute removal + error hider in `page_helpers.js`.
- **Keypad ENTER not submitting:** `Keypad.handleKey` now checks if the
  highlighted key is `Kind.GO` — if so, Enter/Select triggers `onGo()` (submit).
- **Popup guard:** Content-based ad detection (ad text patterns override
  `hasControls` skip) + coverage threshold lowered 50%→10%.
- **Menu reorder:** 14 unconditional items in user-specified order. Back always shown.

## Files modified in 2.2

- `app/src/main/assets/page_helpers.js` — quality preference system, Bitmovin
  scan, network probe, fullscreen centering, StreamCorner sandbox remover +
  error hider, player_scan diagnostic.
- `app/src/main/assets/popup_guard.js` — deferred `reactOnClick` fix (300ms
  after pointerup), content-based ad detection, 10% coverage threshold.
- `app/src/main/assets/flixer_image_guard.js` — cache-busting + allowlist fix.
- `app/src/main/java/com/buccaneer/browser/MainActivity.kt` — `showMainMenu()`
  rewritten (14 unconditional items), "Restore previous" text, low quality
  setting, native UA diagnostic removed.
- `app/src/main/java/com/buccaneer/browser/StartPage.kt` — tile text sizes.
- `app/src/main/java/com/buccaneer/browser/Keypad.kt` — Enter→GO on GO key.
- `app/src/main/java/com/buccaneer/browser/Watchdog.kt` — `.commit()`→`.apply()`.

## Constraints

- Never uninstall without asking (wipes bookmarks/data).
- Do not use CSS filters (`filter:invert`) — known PowerVR crasher.
- Do not enable software rendering as a fix for black-screen video — it breaks
  tap coordinates.
- Cross-origin nested iframes are opaque to injected JS. Verify frame depth
  with `player_geometry` + `frame_script_alive` events before assuming a hook
  will reach the player.
- CDP WebSocket from the host is blocked by origin check (403). Use the app's
  own diagnostic probes instead.
- Change one thing, test, report.
