# Buccaneer 1.9.2 — clean-install release

Install **BuccaneerClaude-1.9.2-fresh-install.apk**. It is an already-signed release APK, not an unsigned project or a debug APK. You do not need to sign it. The historical filename is not evidence of the AI model used.

This deliverable is for a **new installation**, as requested. If an older Buccaneer is installed, first preserve any wanted bookmark JSON outside its app-specific storage, then uninstall it and install this APK. Never assume an app-specific backup folder survives uninstall. Import the saved JSON after installation. Signing keys are intentionally excluded from the source ZIP.

## Controls and functionality

- Remote Play/Pause is routed independently of native menu/list focus, except while typing. Newer playback instructions invalidate delayed older repairs.
- Short FF/Rewind: request 10 seconds. Holding the same button requests 30-second steps, then 60-second steps after 2.5 seconds. Repeat frequency is bounded. For opaque embedded players this becomes a bounded burst of their own arrow-key seek steps, so the **actual duration depends on that player**.
- App QWERTY remains the keyboard; native key events are the normal typing transport. Android IME input connections are suppressed. Long Select can open the app keyboard when automatic field discovery is unavailable.
- The app position bar is selectable when an accessible player exposes finite duration. Unknown/live/opaque timelines are not fabricated. The existing narrow Flixer mouse/website-timeline fallback remains; actual Flixer timeline operation needs device/player verification.
- Preferred exposed-player automatic quality is 400–800p, with 1080p allowed when that is the only available rendition. Lower available resolutions remain usable on slower connections. Explicit manual HLS selections and Shaka ABR-off selections are respected. Unsupported/opaque players are left alone; a nonmonotonic HLS ladder gets a preferred starting level, not a falsely promised height ceiling.
- FMHY theme startup is site-local. Other sites retain the original native dark-mode strategy.
- Recognized dropdown menus get temporary, reversible fitting and nested scrolling; repeated opens do not accumulate margins. Shadow-root nested scroll and transformed same-origin iframe coordinates are handled. Unrecognized/cross-origin menus retain native fallback.
- Start-page bookmarks/folders appear eight at a time, with backward/forward arrows above the bottom buttons when needed. Management pages create at most 50 rows at a time. There is no eight-bookmark storage limit.
- Tagline begins “Where's me buccaneers?”
- After 20 minutes without input and with confirmed stopped playback, a dark overlay dims the picture; the first input wakes it without activating a button. **Unknown opaque playback keeps the screen bright** to avoid dimming a movie. This is not control of the TV's physical backlight. System-wide active audio can also prevent dimming.

## Bookmark transfers

Import applies directly, with **no second confirmation dialog**. Validation and persistence run on an application-owned worker, not the browser renderer. Invalid input preserves the current list. “Restore previous bookmark list” reverses a successful import.

On API 29+, no-picker export uses public **MediaStore Downloads**, not an uninstall-deleted app folder. The completion message names the JSON file. If that provider is unavailable, export reports failure rather than pretending a private fallback is portable. A document picker can be used when installed. Import can use the picker, the last backup URI made by this installation, or a file manager's **Open with/Share → Buccaneer** for `application/json` content URIs. After uninstall, the old remembered URI is gone; select the public JSON again. Fire OS provider/file-manager availability is not physically verified here.

Backups are bounded to 1 MiB and 20,000 input records. Titles, folders, URLs and folder-array types are validated. Normalized imports and all production exports must also fit these bounds. Oversized ordinary edits are not silently truncated: export reports the problem before opening a destination, and the affected bookmark must be shortened first. This is a safety/resource bound, not an eight-bookmark limit.

## Review and verification

Read **WARNING_REPAIRS_1.9.2.md** for the warning dispositions and **VALIDATION_1.9.2.md** for final build/test evidence. The prior whole-program integration review remains in `docs/history/TOTAL_PASS_1.9.1.md`. Older documentation is under `docs/history/` and is historical, not current release approval.

Original Flixer intro/image safeguards, native Flixer render-reset/request blocks, Coreflix/Popup Guard workarounds, and native dark-mode configuration are protected by source fingerprint checks. Passive controls were corrected separately; this is not a claim that the entire helper is byte-identical to 1.5. No dormant Flixer virtualization code was activated.

No physical Fire TV, target WebView/GPU driver, live player, uninstall round-trip, or low-bandwidth decoder test was available. This release has a substantive logic review and reproducible interaction tests; **it is not certified bug-free**.

## Build from source

JDK 17, Android SDK 34, Gradle 8.5. Use an installed Gradle 8.5 (the supplied project has no wrapper). Run `gradle assembleRelease testDebugUnitTest lintRelease`. The project retains min SDK 22 and target SDK 33 from the existing compatibility strategy; compile SDK is 34. Release output in the source project is unsigned until signed with your own key. The supplied APK is already signed.

Compatibility/warning checks: `python3 tests/check-warning-repairs.py`.

Script checks: `node tests/quality-tests.cjs`, `node tests/logic19-tests.cjs`, `node tests/fmhy-theme-tests.cjs`, `node tests/seek-click-tests.cjs`, `node tests/release18-script-tests.cjs`, `node tests/integration-pass-tests.cjs`, `node tests/seek-mapping-integration-tests.cjs`, `python3 tests/check-protected-fixes.py`; Chromium DOM runners in `tests/run-*-tests.py`. Android unit tests use API-28 shadows plus API-34 public-provider fixtures; neither substitutes for a real device.

## Warning maintenance release

This version fixes 17 previous lint findings in application-context ownership, custom-view constructors, launcher icon transparency, banner density declaration and keyboard-preview formatting. Nine intentional findings are explicitly waived to preserve SDK-22/Fire TV compatibility, required scripting and durable recovery writes. The report distinguishes fixes from exceptions; an empty handled lint report is not a claim that all underlying tradeoffs disappeared. No global lint disable or baseline was added. The unused private dark-cleanup parameter and its two call arguments were removed without changing behavior. One test-only deprecated framework-constructor warning remains.

Compile SDK 34, target SDK 33, minimum SDK 22, and AndroidX WebKit 1.9.0 remain unchanged. All six JavaScript assets are byte-identical to 1.9.1. The reported Coreflix manual-720p problem is not claimed fixed by this warning-only maintenance release.
