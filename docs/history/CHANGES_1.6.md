# Buccaneer 1.6 — stability, preferred quality, and backup improvements

## Preserved site customizations

- `flixer_image_guard.js` is byte-for-byte identical to the supplied 1.5 source, including the document-start `hasShownIntro` skip.
- `popup_guard.js` is byte-for-byte identical, including the existing player/button workarounds.
- Passive helpers, synthetic clicks, fullscreen preparation, existing Flixer exclusions, and all helper code outside generic quality selection/HLS wrapping are unchanged.
- Native Flixer image firewall/render-reset block and native dark-mode implementation are unchanged.
- Same application ID, compile SDK 34, target SDK 33, minimum SDK 22, AndroidX WebKit 1.9.0. Version code is now 5.

## Preferred video quality

The app selects the highest exposed resolution between **400p and 800p inclusive** (normally 720p; 480p when that is the only in-range choice).

Fallback order when no in-range resolution exists:

1. Highest resolution below 400p, if available.
2. Otherwise the lowest resolution above 800p. A 1080p-only stream is allowed to play at 1080p.
3. Unknown-height ladders are left to the player.

Examples: 360/480/720/1080 -> 720; 480/1080 -> 480; 360/1080 -> 360; 1080 only -> 1080.

HLS retains adaptive downshifting; Shaka gets an ABR maximum-height preference and preserves the current audio language/group. This is a preference for exposed hls.js/Shaka instances, not a guarantee for every website: native HLS, inaccessible instances, and Flixer-owned documents excluded by the original guard retain their existing behavior. Existing manual quality controls remain intact. There is no new global 1080p prohibition.

## Implemented review recommendations

1. **Renderer lifecycle:** immediately detach/destroy the failed main WebView, cancel pending callbacks, ignore stale JavaScript results, and avoid pause/resume calls on a dead view. Recreate with all scripts/settings reapplied.
2. **Retry budget:** at most two automatic retries for the same URL in a ten-minute crash window. `onPageFinished` no longer clears the budget. On the third failure, show Retry / Return home. Manual Retry clears the budget; expired windows reset naturally. Manual software-rendering recreation restores the URL through a separate preference and does not consume a crash retry.
3. **HLS compatibility:** preserve original constructor statics/getters/prototype and use `Hls.Events` with stock manifest-parsed/levels-updated fallbacks. Preferences reapply when the rendition ladder changes.
4. **Diagnostics:** routine events use a single background writer, a bounded 256-entry queue (eight slots reserved for important entries), and 100ms batching. Repeated noisy events are summarized; overflow is reported. Routine log rotation and export are off the UI thread. Rare critical crash events use a separate, bounded, synchronous best-effort file so they do not wait behind routine logging.
5. **Popup cleanup:** track temporary WebViews and destroy them on forwarding, timeout, close, navigation, error, renderer loss, and Activity teardown.
6. **Crash-handler installation:** install the uncaught-exception handler only once per process.
7. **Remote responsiveness:** coalesce OSD refreshes, discard superseded results, and guard delayed page actions against navigation/recreation.
8. **Bookmarks:** legacy devices retain public Downloads; Android 10+ offers the document picker or an explicitly labeled app backup folder for TVs without a picker. Permission is limited to Android 6–9. File I/O is off the UI thread; local backup replacement uses a temporary file. Imports are limited to 1 MiB and require confirmation before replacing bookmarks.

Diagnostic exports now contain `events.jsonl` (routine events) and `critical.jsonl` (critical events), plus `session.json`. Session-start events contain device/provider metadata. Analyze both JSONL files when investigating crashes.

## Installation and data safety

**Do not uninstall the existing app.** Install as an update using the same signing key as the installed version. Bookmarks and settings remain in the same app preferences.

The separately supplied test APK uses this build environment's debug signing key. If Android reports a signature mismatch, rebuild this source using your original signing key/debug keystore and install that APK with `adb install -r`. Uninstalling to get around the mismatch will erase app-private data.

Modern Android's app backup folder is `getExternalFilesDir(DIRECTORY_DOWNLOADS)` and is **removed on uninstall**. Export through the document picker to a permanent location when possible. A TV without a picker can use the fallback and copy the JSON with adb, for example:

```
adb pull /sdcard/Android/data/com.buccaneer.browser/files/Download/bookmarks.json
```

Availability of that path to adb depends on the Fire OS version and device permissions. Legacy public Downloads backups retain their existing location.

## Reproducible checks

```
node tests/quality-tests.cjs
python3 tests/check-protected-fixes.py
gradle assembleDebug testDebugUnitTest
```

Use JDK 17, Gradle 8.5, and Android SDK Platform/Build Tools 34. Test dependencies are test-only and do not increase APK runtime dependencies.

## Required Fire TV regression pass

No physical Fire TV was connected during this change. A successful build or mocked Android unit test is not a device stability guarantee.

1. Update without uninstalling; confirm existing bookmarks/settings survived.
2. Cold-launch Flixer: confirm the intro is skipped and browsing/scrolling still work.
3. Play Coreflix; verify its existing buttons, fullscreen, remote controls, and quality controls.
4. Repeat Coreflix -> Cinejoy -> Flixer three times, including the previously stuck-video sequence.
5. Test mixed-resolution and 1080p-only streams. Check actual decoded height in diagnostics, not just the menu label.
6. Hold seek/volume; check OSD responsiveness and navigate away while it is pending.
7. With Popup Guard disabled, test a normal popup, a blank popup, and repeated popup attempts; re-enable the guard afterward.
8. Enable diagnostics, reproduce a problem, export the ZIP, and inspect both JSONL files.
9. Export/import bookmarks on the device's supported backup path; cancel an import to confirm the old list remains intact.
10. On API 26+ test renderer-loss recovery in a development environment. Older Fire OS/API 25 does not provide `onRenderProcessGone`; an app-process GPU SIGSEGV cannot be recovered through that callback. The preserved Flixer intro skip remains the device-specific prevention measure.

Historical 1.3/Flixer handoff files are retained from the original archive as historical notes, not as current proof of 1.6 behavior.
