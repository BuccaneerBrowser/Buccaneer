# Final frozen-source validation — Buccaneer 1.9

## Installable artifact

- `BuccaneerClaude-1.9-fresh-install.apk`: **1,216,359 bytes**.
- SHA-256: `843bbd95c9b8c0928c369a4f005316918b35d726650b399114dfecdbb9d8c072`.
- Release build; `android:debuggable` is absent/false. Already signed and zip-alignment verified.
- Android signatures v1, v2 and v3 verified. Certificate SHA-256: `3326ed34f50875fc6991f31102716c79fda7d7df5e068acfbcf8f79275b56e6a`.
- Signed with the existing project-local certificate (its subject is Android Debug); this does not make the APK debuggable. No private key is distributed. This is a sideload release, not a claim of publisher/Play-store signing identity.
- Package `com.buccaneer.browser`; version code 9, version name 1.9; compile SDK 34, target SDK 33, minimum SDK 22. No bundled architecture-specific native code.

## Actual final checks

`assembleRelease testDebugUnitTest lintRelease` completed successfully on the frozen runtime source.

- **58 Android unit/interaction tests, zero failed, zero errors, zero skipped.** API-28 Android/WebView shadows and API-34 content-provider fixtures. Results are in `validation/TEST-*.xml`.
- **39 Chromium DOM assertions passed**: 25 editor/menu/nested-scroll/reopen/restore/transformed-frame checks, 9 seek-click checks, 5 diagnostic safety checks.
- Actual-function JavaScript groups passed: 12 preferred-quality ladders; HLS statics/subclass/constructor failure/manual-mode/reset-cap/unordered-ladder behavior; Shaka soundtrack/1080-only behavior; failed seek and discontinuous ranges; accessible-video selection; late Play refusal; FMHY site scope; seek exclusions; emitted native seek/idle scripts.
- Protected-source fingerprint checks passed for original Flixer intro/image, native Flixer request/reset safeguards, Popup Guard/Coreflix behavior and native dark mode. Passive API corrections have explicit old/new normalization records rather than pretending the full helper is unchanged.
- Runtime/build manifest contains **36 files**, hashed before the final build and checked unchanged afterward.
- **All six packaged JavaScript assets match the frozen source bytes exactly.** APK manifest/version/signature/zip-alignment checked independently after signing.
- Release lint: **zero errors, 26 warnings**, not a warning-free build.

Warning review: four synchronous-preference suggestions concern durable import/rollback and existing crash-recovery persistence; import/rollback run on the worker. Application-owned shared store/context accounts for two static-context warnings and stores only `applicationContext`, not an Activity. The remaining categories are retained target SDK compatibility, an old-API-ignored attribute, retained AndroidX dependency, deliberate JavaScript-enabled WebViews, original icon/density shape warnings, programmatically created custom views lacking XML constructors, and nonlocalized text. No warning is represented as proof of a defect-free release. Existing crash-recovery commits can still briefly block on storage; changing them to asynchronous writes would weaken persistence across an imminent crash.

## Review coverage and remaining constraints

The lead review covered native lifecycle/navigation/renderer teardown; popup and fullscreen ownership; remote routing and key/touch pairs; editor/session/panel focus; relative/absolute seek and target identification; idle state; bookmarks/storage/large-list creation; diagnostics/concurrency/privacy; themes; all six script roles; manifest/SDK/resources/layout implications. The source diff and executed checks are included, not only a completion claim.

Read `LOGIC_REPAIR_REVIEW_1.9.md` for the complete F1–F16 disposition. In particular:

- No actual Fire TV/provider/GPU, live Flixer/Coreflix player, hardware remote, decoder/bandwidth, brightness or uninstall round-trip was tested.
- Opaque seek steps remain player-dependent; an inaccessible Flixer timeline is not claimed universally repaired by an app overlay.
- Unknown opaque playback stays bright; paused opaque players may not dim after twenty minutes. System audio can also prevent dimming.
- Automatic quality cannot control unsupported players or promise a strict height ceiling for nonmonotonic HLS ladders.
- Live-document media/ad arbitration remains a heuristic. Original single-decoder protection was not removed to make this uncertainty disappear.
- Generic menu fitting is reversible and bounded, but cannot repair every site's private controls or closed/cross-origin shadow/frame implementation.
- Free-text diagnostics can still be sensitive. Persistent storage failure cannot guarantee successful rollback on disk.

This is a signed installable code repair with corrected reproducible logic contracts, **not a bug-free or hardware-certified guarantee**. Historical documentation under `docs/history/` is not the current release validation.
