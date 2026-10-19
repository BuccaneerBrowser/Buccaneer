# 1.7.1 validation and coverage

## Automated suite

- Android debug build, JDK 17 / Gradle 8.5 / compile SDK and Build Tools 34.
- 22 Robolectric tests on mocked API 28: the previous 15 plus seven focused regression tests covering physical media-key routing in browse/menu/bookmark-list/fullscreen; Play-as-Enter in the app keyboard; native media-key fallback and non-toggling Pause; immediate validated import without confirmation; menu/list front-order transitions; Flixer timeline mouse input versus ordinary-button/other-site touch; and native pointer event coordinates, pressure, tool type and timing.
- 26 offline Chromium DOM assertions: previous 17 plus nine seek-track/child hit-testing, fraction mapping, no duplicate seek, nonseek volume/button/background exclusions, unknown duration, ambiguous player, and RTL checks. Video properties are controlled fixture values; no real stream is played.
- Node tests of the actual seek helper cover no-action probe mode, same-origin frame coordinate mapping, opaque-frame fallback, seekable windows/gaps, input bounds, exclusions, and no installed listeners/timers.
- Original quality-ladder/HLS/Shaka tests, FMHY origin/theme tests, syntax checks for every injected asset, and protected-site fingerprint checks.
- APK signature verification and exact comparison of packaged asset bytes against source.

The native regression test uses Robolectric's fake WebView provider and scripted evaluateJavascript responses. Its pointer viewport is supplied explicitly because the fake provider does not lay out Chromium's native frame. This proves Activity routing/event construction, not that Amazon WebView delivers the input to the real site's JavaScript exactly as expected.

## Device verification still required

No connected Fire TV, real Amazon WebView, DRM playback, actual Flixer seek widget, or Coreflix playback flow was exercised. The repairs are implemented and regression-tested against the identified code paths, not certified as successful on the user's device. See CHANGES_1.7.1.md for the short device checklist and fresh-install steps.

Earlier 1.7 lint warnings were reviewed, not globally suppressed; a new lint run is included in the release build. No baseline was added.

## Completed release results

Build succeeded. All 22 Android tests passed (zero failures/errors). All 26 offline Chromium assertions and the Node/protected-workaround/syntax checks passed. Android lint reports 0 errors and 23 reviewed warnings. APK assets match source bytes; APK signature verification passed. This is a fresh-install package, not an update package.
