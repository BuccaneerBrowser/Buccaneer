# Final validation — Buccaneer 1.9.2

## Signed fresh-install artifact

- File: `BuccaneerClaude-1.9.2-fresh-install.apk` — **1,224,551 bytes**.
- SHA-256: `610b5f9fd217b7cb61b631e3081c6269efbeba7b542fe0c69a268947c61dce4a`.
- Package `com.buccaneer.browser`, version code 11, version name 1.9.2.
- True release variant, debuggable absent/false, zip alignment checked; signatures v1/v2/v3 verified.
- Existing local certificate SHA-256: `3326ed34f50875fc6991f31102716c79fda7d7df5e068acfbcf8f79275b56e6a`. Its subject is Android Debug; this does not make the release debuggable. This is a signed sideload release, not a Play-store publisher identity. Private key excluded. The user need not sign the supplied APK.
- Compile SDK **34**, target SDK **33**, minimum SDK **22**, AndroidX WebKit **1.9.0** unchanged. No bundled architecture-specific native libraries. Existing hardware/version requirements, permissions and compatibility strategy retained.

## Final executed checks

`testDebugUnitTest lintRelease assembleRelease` completed successfully against the final source. All **42 runtime/build/configuration files** match the recorded hashes after build and signing. The previous 1.9.1 delivered runtime remains unchanged.

- **74 Android tests, zero failures/errors/skips.** Prior 70 plus four warning/compatibility tests: shared application-owned store across Activities, all eight usable Context constructors, preview formatting, and minimum **API-22 startup**. Existing **API-34** startup/Play-Pause/picker-import and API-28 interaction suites remain. Robolectric is a simulated Android environment, not a physical device.
- **51 Chromium DOM assertions passed**: 25 editor/menu/nested-scroll/iframe, 9 seek, 5 diagnostic-safety, 12 integrated-controller checks. Browser scripts are byte-identical to 1.9.1. These checks do not certify actual provider/decoder behavior or a physical fullscreen handoff.
- All seven Node suites passed, including the emitted native absolute-seek/idle script suite rerun after the final signature cleanup. Logs are included. PASS lines are not used as an inflated assertion/test count.
- Original protected-site fingerprint checks pass. The exact removal of an unused argument at two private dark-cleanup calls is canonicalized before checking the original native fingerprint; no darkening implementation or site guard was changed.
- Compatibility check verifies unchanged SDKs, dependency, permissions and six JavaScript hashes, actual icon transparency, density-independent banner and narrow lint-exception inventory.
- All six assets inside the signed APK match the frozen source bytes. Manifest/version/SDK/debuggable/signatures/zip alignment checked after final signing.

## Warning audit — fixes are not confused with waivers

The previous **26 lint warnings** became **17 corrected underlying findings + nine explicitly accepted conditions**. Normal release lint has **zero unhandled findings**. A separate source copy with *every* lint waiver removed was also built and audited: it reports exactly **nine warnings, zero errors**:

- ApplySharedPref: 4.
- SetJavaScriptEnabled: 2.
- OldTargetApi: 1.
- GradleDependency: 1.
- UnusedAttribute: 1.

The raw audit's runtime source was compared with the shipped source: the only differences are the six local annotations, two-item lint config and manifest API-intent declaration. `validation/raw-audit-waiver-removals.diff`, both lint XML reports and the raw build log are included. No baseline or blanket lint disable was added. See `WARNING_REPAIRS_1.9.2.md` for the reason for each exception.

The unused production parameter warning was corrected without changing behavior. **One test-only Kotlin deprecation notice remains**, from the framework constructor used to simulate renderer loss. This release is not represented as literally warning-free.

## Scope and remaining limits

This is a warning-maintenance release, **not a claimed fix for the separate Coreflix manual-720p regression**. No physical Fire TV/PowerVR/WebView, live streaming provider, real remote, decoder/bandwidth stress, launcher or uninstall/reinstall transfer was available. Automated API-22/API-34 checks support the compatibility review but cannot certify a device.

The nine accepted lint conditions remain real tradeoffs: required JavaScript, legacy HTTP behavior, retained SDK/library choices and potentially blocking durable writes. Existing opaque-player, menu, quality, storage and diagnostic constraints remain. Historic whole-program review and validation are in `docs/history/`; they are not substituted for this release's current checks.

For a clean installation, preserve bookmark JSON outside app-specific storage before uninstalling any previous version, install the already-signed APK, and import the JSON afterward. No claim is made that an app-private backup survives uninstall.
