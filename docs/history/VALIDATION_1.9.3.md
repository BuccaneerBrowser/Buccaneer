# Final validation — Buccaneer 1.9.3

## Installable artifact

- **BuccaneerClaude-1.9.3-fresh-install.apk**, 1,228,647 bytes.
- SHA-256: `71d5fd2b9002eb71283e2ca6d1802e77372b186a01514ffea6702ec733ec03ae`.
- Package `com.buccaneer.browser`, version code **12**, name **1.9.3**.
- Release variant, not debuggable; zip alignment passed; v1/v2/v3 APK signatures verified. No v4 sidecar is needed.
- Local certificate SHA-256: `3326ed34f50875fc6991f31102716c79fda7d7df5e068acfbcf8f79275b56e6a`. Its subject is Android Debug; that certificate label does not make this release APK debuggable. This is an already-signed sideload build, not a Play-store publisher identity. Private key excluded; the recipient does not need to sign it.
- Compile SDK **34**, target SDK **33**, minimum SDK **22**, AndroidX WebKit **1.9.0** retained. No bundled architecture-specific native libraries. Permissions and existing hardware strategy unchanged.

## Executed final checks

`testDebugUnitTest lintRelease assembleRelease` completed successfully against the frozen source. All **42 runtime/build/configuration files** match the release evidence. The prior delivered 1.9.2 runtime remains unchanged.

- **88 Android tests**, zero failures/errors/skips. Existing API-22 startup, API-34 provider/remote integration and API-28 interaction suites pass, with 14 new editor/recovery contracts. Robolectric does not run a real Chromium editor or Fire OS IME. The viewport test checks the actual requested native height against the measured keypad; it does not substitute a zero-height shadow for physical rendering.
- **84 Chromium DOM assertions**, zero failures. Existing 51 checks plus 11 new clipping/editor/scroll contracts at each of **960×540, 1280×720 and 1920×1080**. These dimensions are local browser windows, not Fire TV screenshots. Tests cover clipped-parent bounds, scaled popups, aria-controlled popups, safe inset, nested scroll/boundaries, no offset drift, qualified fresh field snapshots, wrong field rejection, password privacy and style restoration.
- **Seven Node suites** pass. These cover quality ladders/manual ownership, FMHY theme isolation, seek mapping, emitted native seek/idle scripts and integration regressions. PASS log lines are not represented as an inflated Node assertion count.
- Protected-site checks pass for the original Flixer intro/image/native safeguards, Coreflix/Popup Guard workaround and dark mode. Five existing assets remain byte-identical to 1.9.2; the approved editor/menu change to `tv_ui.js` has its own recorded hash.
- All six JavaScript assets in the signed APK match the frozen source bytes exactly. Manifest, SDKs, non-debuggable state, signatures and alignment were checked after signing.

## Before/after evidence

Initial native contracts reproduced six failures out of seven, and the new DOM fixture reproduced seven failures out of eight. The native measurement test initially also encountered Robolectric's absent WebView layout provider; it was corrected to inspect the real native layout request. That corrected contract was separately executed against unchanged 1.9.2 and failed (`Viewport -1, keyboard top 236`), then passed in the final 1.9.3 suite. This correction is documented rather than described as physical rendering proof.

Before logs/XML are under `review-evidence/before/`. Final Android XML, build log and manifest/signature reports are under `validation/`. Final script/DOM logs and precise runtime diffs are under `review-evidence/`.

## Warning audit

Normal release lint: **zero unhandled findings**. All existing exception scopes remain explicit and are checked by `tests/check-warning-repairs.py`.

A separate copy of this exact 1.9.3 runtime, with all six lint annotations removed, the two lint-config exclusions removed, and the manifest target-API acknowledgement removed, was built with `lintRelease`. Its source was compared back to the release, allowing only those exact transformations. The raw report has **nine warnings, zero errors**:

| Condition | Count | Retained reason |
| --- | ---: | --- |
| ApplySharedPref | 4 | Deliberately durable critical state/backup writes |
| SetJavaScriptEnabled | 2 | Browser and guarded popup functionality require JavaScript |
| OldTargetApi | 1 | User-requested compatibility strategy remains target 33 |
| GradleDependency | 1 | Preserve AndroidX WebKit 1.9.0/SDK-34 baseline |
| UnusedAttribute | 1 | API-23 application attribute intentionally ignored on API 22 |

These are not nine newly fixed bugs or zero raw warnings. The earlier 17 underlying lint fixes remain. No lint baseline/global blanket suppression was introduced. One test-only compiler notice remains for the deprecated framework constructor used by the renderer-loss fake; production compilation added no warnings. Raw XML/text, build log and provenance checks are included under `validation/`.

## Boundaries of verification

No physical Fire TV, PowerVR GPU, actual Android/Fire OS keyboard service, remote, decoder, website-player session, low-bandwidth stream or uninstall/import round-trip was available. Local tests do not guarantee their behavior.

The helper continues to skip Flixer-owned documents to protect the original crash workaround. Therefore these generic submenu tests are not a claim of a live Flixer-menu fix. Unrecognized/cross-origin menus retain native wheel fallback. Prior live Coreflix rendition verification also remains open. No program-wide “bug-free” claim is made.
