# Buccaneer 1.9.2 — warning repairs and compatibility exceptions

## Outcome and scope

The prior release reported 26 Android lint warnings. This maintenance release corrects **17 underlying findings** and explicitly documents **nine remaining intentional conditions**. The normal lint report has zero unhandled findings. This is not “26 bugs fixed” and not “all warnings eliminated without exceptions.” The unused private dark-cleanup parameter and its two call arguments were removed, with no change to the dark-mode implementation. One test-only Kotlin warning remains for a deprecated framework constructor used to simulate renderer loss. The protected-source check canonicalizes this exact two-call signature cleanup before comparing the original native site-guard fingerprint.

The user's hardware/version support is preserved: compile SDK 34, target SDK 33, minimum SDK 22, AndroidX WebKit 1.9.0, package name, permissions, native dark-mode strategy, and all six injected JavaScript assets. No Flixer intro/image behavior, Coreflix button workaround, player-quality policy or remote mapping was modified. This is not the fix for the separately reported Coreflix 720p regression; that still requires identifying and reproducing the actual player path.

## Seventeen corrected findings

| Previous lint finding | Count | Actual repair |
| --- | ---: | --- |
| StaticFieldLeak | 2 | Removed both static store/context roots. `BuccaneerApplication` owns one lazily initialized store; `BookmarkStore.shared(context)` obtains it from the application's lifecycle instead of a companion singleton. Worker jobs and Activity recreation still share the same store. |
| ViewConstructor | 8 | Added real Java-visible Context constructors using default arguments and `@JvmOverloads` for CursorView, Keypad, Osd, MenuPanel, ListPanel, TextPanel, StartPage and BarView. Existing explicit-argument constructors and call sites remain unchanged. |
| IconLauncherShape | 5 | Converted the five launcher PNGs to RGBA, clearing only the connected near-black outside background. Existing artwork, canvas dimensions and density variants remain; no new logo was substituted. All five variants were visually inspected individually. |
| IconMissingDensityFolder | 1 | Declared the existing fixed-pixel TV banner density-independent by moving it to drawable-nodpi without changing its bytes. This avoids asking Android to infer missing density-specific versions. |
| SetTextI18n | 1 | Moved the keyboard preview/caret format into a positional string resource. Tests cover empty text, percent signs, literal format strings, newlines, emoji and caret characters. |

The application ownership change is a concrete lifetime-structure improvement, not proof that the previous application-context references leaked an Activity. The view/resource fixes are not represented as playback repairs.

## Nine explicit lint exceptions

| Condition | Count | Reason and exact treatment |
| --- | ---: | --- |
| ApplySharedPref | 4 | Local method annotations on import rollback, crash recording, crash-loop reset and renderer-loss recovery. Import rollback needs a synchronous success/failure result; crash writes must survive a process/renderer retry. Replacing them with `apply()` would weaken durability. The existing short blocking-write tradeoff remains; import processing stays on the worker. |
| SetJavaScriptEnabled | 2 | Local method annotations on primary WebView configuration and temporary popup creation. Scripting is required for the streaming sites and popup URL resolution. Existing file-access/permission/popup safeguards remain; enabling scripting still carries a security review obligation. |
| OldTargetApi | 1 | `app/lint.xml` accepts this build-file finding because target 33 is retained at the user's explicit hardware-compatibility request. A target-SDK migration is not smuggled into warning cleanup. |
| GradleDependency | 1 | `app/lint.xml` accepts only the message about a newer AndroidX WebKit than 1.9.0. The known SDK-34/min-22 dependency is retained, not silently upgraded. No claim is made that every newer version is incompatible. |
| UnusedAttribute | 1 | `tools:targetApi="m"` declares the intentional API-23+ application attributes. `usesCleartextTraffic=true` is ignored on API 22, whose existing behavior already allows cleartext; supported versions retain the override for HTTP streams. The unsupported-on-22 condition is acknowledged, not falsely described as a new runtime version guard. |

There is no lint baseline, no global `disable`, no blanket source-file suppression and no suppression of the 17 repaired findings. The compatibility test checks the exact exception inventory. With these waivers removed in a disposable audit copy, the remaining lint findings can be counted independently of the normal handled report; see validation for the actual result.

## Verification and remaining limits

New tests verify application-owned sharing across two Activities, all eight generated Context constructors, literal preview formatting and API-22 startup. Existing API-34 startup/remote/picker and API-28 interaction suites are retained. A source-baseline check verifies the unchanged SDKs, dependency, permissions and six scripts; the original protected-site fingerprint checker still passes.

Tests use Robolectric and Chromium, not a physical Fire TV/PowerVR device. The new Application class and corrected resource packaging have automated coverage but no physical launcher/uninstall round-trip. Existing opaque-player, menu, quality, persistence and diagnostic limits remain in the historic integration review and current validation report. The cleanup does not establish bug-free or hardware-certified operation.
