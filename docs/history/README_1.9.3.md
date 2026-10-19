# Buccaneer 1.9.3 — fresh-install release

Use **BuccaneerClaude-1.9.3-fresh-install.apk**. It is an already-signed, non-debuggable release APK; you do not need to sign it. The historical filename does not identify the AI model that worked on it.

For a new installation: export any wanted bookmarks to a public location outside the app's private storage, uninstall the old app, install this APK, then import the saved JSON. Uninstalling can delete app-specific backups. Source and release evidence are supplied separately; the signing key is not distributed.

## Repairs in this release

- **Keyboard:** preserve Chromium's editor instead of returning null for its InputConnection; exclude the system IME at the Activity window. Continue using the app QWERTY and original direct native-key typing/deleting path. Give the web page a real native viewport above the keyboard, restore it on every close/prompt/navigation path, and prefer fresh document/frame/field-qualified preview reads over stale cached snapshots.
- **Crash/reopen:** explicit navigation to the same crashed bookmark now resets the automatic retry budget. Automatic retries and popup redirects do not. A renderer that dies while the app is paused is disposed immediately; Activity recreation waits for foreground. The intended URL is remembered even before the first page-start callback.
- **Clipped menus:** fit recognized dropdowns inside their clipping ancestors and a 5% vertical TV-safe inset, account for scaled popup wrappers, and recognize expanded aria-controlled popups as well as menu roles/Radix/Material structures. Keep menu content internally scrollable, original DOM parentage and reversible inline styles.

These repairs pass the local regression suites. They are not a certification of the physical Fire TV IME/WebView/GPU or every website menu. **Flixer continuous UI instrumentation remains deliberately disabled**; the general menu fitter must not be mistaken for a verified Flixer-menu fix. Unknown/cross-origin menu structures still rely on native wheel behavior.

## Retained controls and features

- Play/Pause remains independent of native menu/list focus except during app-keyboard entry.
- Short FF/Rewind requests 10 seconds. Holding requests 30-second steps and then 60-second steps after 2.5 seconds, with bounded repeat frequency. Opaque embedded players may translate this into their own arrow-key steps, so actual duration depends on the player.
- The app timeline is selectable when an accessible player exposes finite duration. Existing narrow Flixer mouse/timeline repair remains; live/opaque positions are not invented.
- Preferred automatic quality is 400–800p for supported exposed players, with 1080p allowed when it is the only available rendition. Manual HLS selections and Shaka ABR-off selections are respected. This release does not claim successful live Coreflix 720p verification.
- FMHY uses site-local theme startup; native dark-mode behavior elsewhere is retained.
- Start-page bookmarks/folders appear eight at a time with backward/forward arrows when needed. Storage is not limited to eight bookmarks. Management pages create at most 50 rows at a time.
- Tagline begins “Where's me buccaneers?”
- After 20 minutes without input and with confirmed stopped playback, an overlay dims the picture. First input wakes it without activating a control. Unknown opaque playback stays bright to avoid dimming a movie. This does not change the TV's physical backlight.
- Flixer intro/image/native safeguards and Coreflix/Popup Guard button workarounds remain protected by fingerprint checks.

## Menus and keyboard

A recent field click opens the app keyboard; long Select or **Menu → Type into page field** is the fallback for fields that do not report focus. While typing: Select types, Rewind deletes, FF inserts a space, Play/Pause submits, Menu changes symbols, Back closes.

For recognized website dropdowns use **Menu → Fit open submenus to screen**. **Menu → Scroll submenu/page with D-pad** scrolls under the pointer; Back returns to pointer mode. Recognition is deliberately narrow, rather than rewriting arbitrary containers and breaking site controls.

## Bookmark transfers

Import validates and applies directly with **no second confirmation dialog**. Background persistence is application-owned. Invalid JSON preserves the current list; “Restore previous bookmark list” reverses a successful import.

On API 29+, no-picker export uses public MediaStore Downloads, not an uninstall-deleted private folder. Provider failure is reported, not silently redirected to private storage. A document picker can be used when installed. Import can use a picker, a backup URI remembered by this installation, or a file manager's Open with/Share → Buccaneer for `application/json` content URIs. After uninstall, select the public JSON again; the old remembered URI no longer exists. Provider availability on Fire OS was not physically tested.

Backup input is bounded to 1 MiB and 20,000 records. Titles/folders/URLs and array types are validated. Normalized imports and exports must also fit those bounds. Oversized edits are not silently truncated: export reports the problem before opening a destination.

## Compatibility, build and evidence

Compile SDK **34**, minimum SDK **22**, target SDK **33**, AndroidX WebKit **1.9.0** are unchanged. No ABI-specific native libraries are bundled. Existing permissions, hardware acceleration and launcher resources are retained.

Build with JDK 17, Android SDK 34 and installed Gradle 8.5 (no wrapper is included):

```sh
gradle --no-daemon testDebugUnitTest lintRelease assembleRelease
python3 tests/check-protected-fixes.py
python3 tests/check-warning-repairs.py
for f in tests/*tests.cjs; do node "$f" || exit; done
for f in tests/run-*-tests.py; do python3 "$f" || exit; done
```

Chromium DOM tests need Chromium/Chrome in PATH; icon verification needs Pillow. A locally built release is unsigned until signed with your own key. The supplied APK is already signed.

Final evidence: **88 Android tests, 84 Chromium assertions, seven Node suites, zero unhandled lint findings**. A separate no-waiver lint audit still shows the nine explicitly acknowledged conditions, not zero raw warnings. One test-only compiler deprecation remains for the framework renderer-loss fake.

Read `REVIEW_1.9.3.md`, `VALIDATION_1.9.3.md` and `RELEASE_CHECKS_1.9.3.json`. Detailed logs and before/after fixtures are in `validation/` and `review-evidence/`. Older reports under `docs/history/` are historical, not current release approval. No physical Fire TV, live streaming player, decoder, uninstall round-trip or low-bandwidth test was available; this release is **not certified bug-free**.
