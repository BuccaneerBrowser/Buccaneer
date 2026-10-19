# 1.8 validation

## Scope

All runtime Kotlin files and JavaScript assets, manifest/theme/storage/SDK/build settings and protected 1.5 behavior were reviewed in the source-wide audit. The additional 1.8 input, native seek fallback, OSD pointer route and idle-state changes received a separate integration diff review. Original resources and site safeguards were retained rather than redesigned.

## Automated checks

The final signed APK is built from this exact source with Gradle 8.5, JDK 17, Android compile SDK 34, target SDK 33 and min SDK 22. AndroidX WebKit remains 1.9.0. Build, test and lint outcomes are recorded in RELEASE_CHECKS.json after packaging.

- 43 Android/Robolectric tests: 8 stability, 7 bookmark/UI audit, 7 media/import/overlay regressions, 11 deeper-audit regressions and 10 release-1.8 tests.
- 31 offline Chromium DOM assertions: 17 menu/keyboard/playback, 9 seek-track and 5 diagnostics/canvas safety.
- Node checks for 12 quality ladders, HLS constructor/statics/events/fallback/destruction and Shaka selection, including 1080-only availability; FMHY theme isolation; lazy seek-click mapping/exclusions/seekable windows/RTL/frame behavior.
- Additional tests extract the actual emitted 1.8 absolute-seek and idle JavaScript from MainActivity. They cover reachable/blocked/absent/invalid-duration seeks and playing/paused/opaque/missing/error playback states.
- All six JavaScript assets pass syntax checking.
- Original Flixer intro/image-guard and Popup Guard assets are byte-identical to supplied 1.5. Native Flixer guards/reset block and applyDarkMode are exact. Protected passive helper/early-return comparison explicitly normalizes only the three documented passive quality corrections; it does not claim the whole helper is unchanged.
- APK signature/version/package and all packaged script bytes are checked; the source archive is checked for missing source entries, build caches and private signing keys.

## Interpretation and remaining limits

Robolectric runs API-28 framework shadows, a scripted WebView and supplied layout geometry, not Fire OS/Chromium integration. Chromium DOM fixtures use controlled media properties, not streaming/decoding. The new seek tests prove command values, timing/routing, native key pairing and cancellation—not an actual Flixer player's response. Opaque-player accelerated skips multiply the player's native step; exact seconds are not observable from the top frame.

No physical Fire TV, GPU/PowerVR, DRM, native media decoder or real remote was available. Flixer's inaccessible website timeline remains unverified; the browser position bar is a separate control and is offered only when passive state exposes a finite-duration video. QWERTY behavior across every site's search icon, menu fitting across arbitrary custom markup, FMHY live-site timing and quality preference on inaccessible/native players are not certified by these tests.

Dimming is an app-image overlay, not television backlight control. Unknown player state intentionally keeps the screen bright. Fire OS screensaver behavior is independent.

The final build has no lint errors and 23 warnings, recorded in RELEASE_CHECKS.json. These warnings include intentional synchronous crash/recovery persistence, retained target/dependency levels, required browser JavaScript, programmatic view constructors, original icon assets and a text-localization warning. No dependency/target jump or cosmetic icon rewrite was made merely to silence them.

Historical FULL_SOURCE_REVIEW.md retains specific underlying risks: generic all-frame bridge exposure, possible sensitive diagnostic free text, eager very-large bookmark lists and fixed display metrics on unusual configuration changes. This is a bounded reviewed improvement candidate, not a bug-free/device-certified claim.
