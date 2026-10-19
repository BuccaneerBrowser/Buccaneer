# Version 1.7 validation

## Automated checks

- Android debug build with JDK 17, Gradle 8.5, SDK/Build Tools 34. Android lint is also run; remaining warnings are reviewed in AUDIT_1.7.md.
- Fifteen Robolectric tests on mocked API 28 (eight stability tests and seven audit tests): retry policy; dead-WebView lifecycle cleanup; one-time crash-handler installation; bounded diagnostic queue/export; 20-minute idle/playback policy; wake-only first key; system IME suppression; retained native editor typing/deletion/clearing and password masking; bookmark/folder paging and 720p layout; case-sensitive URL deduplication and malformed imports; fullscreen keyboard routing; NaN drawing safety; nested diagnostic redaction.
- Seventeen offline Chromium DOM assertions covering padded/min-height/clipped menus, nested scrolling without moving the outer page, menu boundaries, delayed focus, selection replacement, Unicode deletion, maxLength, clearing, password preview redaction, shadow-root focus, same-origin frame typing, and playback detection.
- FMHY theme-key/class and origin-isolation tests.
- Twelve quality-ladder scenarios plus HLS/Shaka constructor, static/prototype/event preservation, destroyed-instance cleanup, error propagation, language-group preservation, and 1080p-only fallback checks.
- Original protected-site fingerprints. The only intentional change in the old helper timer is a playback heartbeat; click/button behavior and Flixer exclusions are preserved.
- Syntax checks for all injected JavaScript assets.
- Debug APK signature verification succeeds. Certificate comparison confirms this build does not share the v1.6 supplied test APK's signing key; in-place updates need the installed version's signing key.

## Not established by automated checks

Physical Fire TV GPU stability, actual site/player integration, real Amazon WebView input-connection behavior, every custom submenu layout, device signing-key compatibility, and interaction with Fire OS's own screensaver. These need the real-device pass described in CHANGES_1.7.md.
