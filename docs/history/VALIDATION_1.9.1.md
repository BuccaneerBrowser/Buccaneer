# Final validation — Buccaneer 1.9.1

## Installable artifact

- `BuccaneerClaude-1.9.1-fresh-install.apk`: **1,220,455 bytes**.
- SHA-256: `98855c8c6a410af8b97bcfc7750faefc276beb919beb08c3a38ae60f066208a1`.
- Release variant, not a debug APK; `android:debuggable` absent/false. Already signed and zip alignment checked. The user does not need to sign it.
- v1/v2/v3 Android signatures verified. Certificate SHA-256: `3326ed34f50875fc6991f31102716c79fda7d7df5e068acfbcf8f79275b56e6a`.
- Existing local signing certificate, whose subject is Android Debug. That certificate name does not make the release debuggable. No publisher/Play-store identity is claimed; the private key is excluded.
- Package `com.buccaneer.browser`, version code **10**, version name **1.9.1**, compile SDK **34**, target SDK **33**, minimum SDK **22**. Retained compatibility configuration, not a target-SDK migration.
- Fresh-install deliverable. Preserve bookmark JSON outside app-specific storage before uninstalling any prior installation; import it afterward. Public-provider availability and uninstall survival were not physically tested on Fire OS.

## Executed checks

`testDebugUnitTest lintRelease assembleRelease` completed successfully. The runtime/build-source hashes were recorded while the final build was in progress and verified unchanged after build/signing. This establishes the tested/built runtime identity, not reproducible ZIP timestamps.

- **70 Android tests: zero failures, errors or skips.** Includes the previous 58 plus 11 interaction regressions and one API-34 integrated smoke test. Test XML for all nine classes is in `validation/`.
- API-34 smoke uses Robolectric, a shadow WebView and a fixture ContentProvider: startup, remote Play/Pause script dispatch, Activity pause, import picker result, resume, worker completion, stored bookmark and visible management panel. It is not an actual Fire TV UI/provider run.
- **51 real-Chromium DOM assertions passed:** 25 editor/menu/nested-scroll/restoration/iframe checks, 9 seek-click checks, 5 diagnostic safety checks and 12 combined-controller checks. The latter include iframe-selected media, fractional CSS serialization, prepared/cancelled/stale fullscreen handlers, and scaled/bordered timeline mapping. Fullscreen request execution is stubbed; DOM layout/hit testing and event handling are real.
- Actual-function Node suites passed: quality ladders and HLS wrapper behavior; manual choices and reset caps; discontinuous seeks; stale playback refusal; FMHY origin scope; narrow seek exclusions; emitted native idle/seek scripts; six new helper/menu/Shaka contracts; two corrected transformed-seek/rejected-setter contracts. Individual logs are included; a `PASS` line may cover multiple assertions and is not reported as a separate test count.
- Protected original Flixer intro/image, native request/render-reset, Popup Guard/Coreflix and native dark configuration checks passed. Passive API changes have explicit old/new records.
- **40 runtime/build files** in `validation/runtime-source-sha256.json` unchanged through verification. The separately delivered 1.9 runtime was also checked unchanged.
- **All six JavaScript assets inside the signed APK match the frozen source bytes.** Manifest version, minimum/target SDK, non-debuggable status, signature schemes and zip alignment checked separately after signing.
- Release lint: **zero errors, 26 warnings**. One retained production Kotlin unused-parameter warning and a test deprecation warning also remain; no warning-free claim.

## Lint disposition

Unchanged warning categories: 4 synchronous preference writes; 1 retained older target; 1 attribute ignored on older API; 1 AndroidX dependency age; 2 deliberate JavaScript-enabled WebViews; 2 application-owned static store/context references; 5 launcher icon shape and 1 density-folder warnings; 8 programmatic custom-view constructor warnings; 1 nonlocalized-text warning. Import/rollback writes are on the worker. Crash-recovery commits can briefly block, but replacing them with asynchronous persistence would weaken imminent-crash durability. Static store ownership is application context, not Activity retention. The retained compatibility and presentation warnings are not silently counted as fixed.

## Review and remaining boundaries

Read `TOTAL_PASS_1.9.1.md` for the design/implementation findings and cross-module disposition. `review-evidence/` contains before-failure contracts and source diffs; `validation/` contains final outputs. Historic reports under `docs/history/` are not current approval.

No physical Fire TV/WebView/GPU/remote, live Flixer/Coreflix player, decoder/bandwidth stress run, real fullscreen handoff or uninstall/reinstall transfer was available. Opaque-player seek intervals remain player-dependent; inaccessible timelines are not universally fixed. Unknown opaque playback remains bright. Generic menu repair, automatic quality and player/ad arbitration retain their documented limits. Axis-aligned frame scaling is covered, arbitrary rotation/perspective is not. Oversized ordinary bookmark edits require shortening before portable export. Free-text diagnostics can still be sensitive, and persistent storage failure cannot guarantee disk rollback.

This is an installable, regression-tested repair with an additional logic/integration review, **not a claim of a bug-free or hardware-certified release**.
