# Version 1.6 validation

## Passed

- Android debug APK compilation: `gradle assembleDebug`, JDK 17, Gradle 8.5, Android SDK/Build Tools 34.
- Four Android/JVM regression tests (`gradle testDebugUnitTest`), zero failures/errors/skips, using Robolectric on mocked API 28:
  - Same-URL retry budget, new-URL reset, window expiry, and clock rollback.
  - Actual Activity/WebView renderer-loss teardown, ignored late page-finished callback, and pause/stop/destroy after renderer loss.
  - Idempotent uncaught-exception-handler installation.
  - Bounded diagnostic queue under a blocked writer, overflow reporting, independent critical crash persistence, URL redaction, ZIP export after stop, event-burst aggregation, and new-session isolation.
- JavaScript isolated-function tests: 12 rendition ladders, including 1080p-only fallback; HLS statics/getters/prototype/constructor arguments/events; reapplication after ladder changes; fallback without Reflect or static inheritance; Shaka language/group preservation.
- Original protected-site fingerprint checks: Flixer intro/image guard, Popup Guard/Coreflix-related click code, passive helpers, native Flixer guard/reset block, native dark mode.
- All three injected JavaScript assets pass Node syntax checking.
- Built APK signature verification passes (test/debug signing key, not the original installation key).

## Not performed

- Physical Fire TV installation, playback, GPU/decoder testing, or real-site end-to-end testing.
- Verification of signing-key compatibility with the currently installed app.
- Real-device document-provider/scoped-storage testing.

Robolectric tests validate application logic and mocked lifecycle behavior; they do not establish native GPU stability. See CHANGES_1.6.md for installation cautions and the on-device checklist.
