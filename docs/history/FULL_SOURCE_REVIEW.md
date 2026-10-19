> Historical pre-1.8 audit. The 1.8 source implements the native OSD hit path, accelerated seeking and unknown-playback dimming safety described in VALIDATION_1.8.md. The physical-device and cross-origin limitations below still apply. This document is retained as an audit trail, not the release's current instructions.

# Buccaneer — full runtime source review

## Release decision

**Not approved as a verified improvement over the user's working 1.5 installation.** Keep 1.5 as the known-working reference. The earlier 1.7/1.7.1 APKs should not have been recommended on the strength of compilation, mocked tests and simplified DOM fixtures. No new installable APK is supplied with this review.

This review compared the original supplied 1.5 source with the subsequent changes, read all 15 runtime Kotlin files and all six current JavaScript assets, and examined the manifest, build configuration and XML resource configuration. Unchanged binary images were compared as files, not visually redesigned. This is source-wide review coverage, **not exhaustive runtime coverage or a bug-free certification**.

## What went wrong in the previous work

1. Too many interacting changes were combined without preserving a behavioral acceptance contract for remote keys, text entry, panel layering and navigation.
2. Test doubles proved the behavior of selected mocks, not Amazon WebView. In particular, a hand-built BaseInputConnection test did not establish that Chromium's connection remains usable after returning null to Android's IME.
3. DOM fixtures used a convenient viewport and simulated player state; they did not reproduce Fire TV native-pixel/CSS-pixel scaling, the actual site's iframe/widget structure, or hardware decoding.
4. A successful build, lint pass and protected-file hashes were treated as stronger readiness evidence than they are. Hashes establish preserved source sections, not successful playback.
5. The real-device working 1.5 version should have remained the acceptance reference. More modifications were not inherently an improvement.

## Reproduced failures and corrective source changes

### Keyboard and remote input

- **Introduced regression: autofocus could steal later remote keys.** The opening gate accepted any input within 20 seconds, including activity resume and D-pad/Play activity, rather than a recent deliberate click. An added test failed against delivered 1.7.1. Corrected to require a real recent tap, a valid editable-field report and existing overlay/dismissal guards. Delayed search fields after an actual tap remain supported.
- **Introduced regression: a null native-editor report could open QWERTY.** Null was accepted as if a text field had been confirmed. The new test failed before correction; null is now rejected.
- **Unsafe experiment removed:** retained Chromium InputConnection and its editor-state/typing path. Direct key-event typing from 1.5 is restored, with same-origin DOM editing as an explicit fallback and no retained IME editor. TvWebView still declines the system IME connection. Focus reports and bounded on-demand field reads drive the app keypad; no recurring Flixer listeners were added.
- **Retained interaction defect: page navigation could leave the old PAGE keypad open.** It could then consume media keys as Enter. The new test failed before correction. Navigation now closes page text-entry mode, resets click/selection state and clears frame-field state.
- **Introduced lifecycle regression: pointer DOWN could lose its terminator.** The epoch guard simply discarded the delayed UP after navigation/fullscreen change. Pending native taps now have explicit ownership, with UP or CANCEL for a live target, cancellation before a second DOWN, and clearing without native calls after renderer loss. Navigation, overlapping taps and pointer construction have focused tests.

### Scrolling and rendering side effects

- **Introduced regression: native pixels were used as CSS hit-test coordinates.** The new scroll test failed before correction. The native viewport now normalizes both pointer coordinates and scroll deltas to the page viewport before calling the nested-scroller helper. This fixes the top-level scaling error; transformed/bordered/custom nested frames still require real integration coverage.
- **Inherited hazard missed by the earlier full-review claim: diagnostics could query/create WebGL contexts on page-owned canvases, even when logging was off.** Arguments were evaluated before the disabled logger returned. The new browser fixture reproduced the unwanted calls. Media diagnostics now return before sampling when disabled, and no longer call getContext at all. Unknown GL state is reported as unknown rather than allocating GPU resources to investigate it.
- **Inherited diagnostic bug:** a free function named playerGeometry was called even though it exists as a public __fb method. Exceptions were swallowed, losing the geometry event. Corrected to the public helper; the new fixture verifies the event is emitted.

### Prompts, recovery and quality

- **Introduced prompt-lifecycle defects:** a web prompt could be abandoned by panel/navigation cleanup, and the generic text prompt code treated empty or whitespace-only confirmation as cancellation. Pending web prompts now have explicit ownership, cancellation on navigation/cleanup, and valid empty/whitespace confirmation without trimming. Dead-renderer results are discarded without calling back into the dead renderer. Tests verify ownership, cancellation invocation and returned string values; Robolectric does not simulate a real blocked Chromium JavaScript dialog.
- **Retry-policy loophole:** URL changes during redirects could restart the automatic crash budget. The budget is now bounded by the recovery time window rather than exact URL equality and saturates at the stopped-retry state. Manual Retry remains available. The regression suite checks alternating URLs and integer-overflow safety. This intentionally changes the previous per-URL policy.
- **Inherited quality-control defects:** holding FF/Right could issue a quality-switch request on every repeated key after repeat six; manual lowering chose the lowest matching rung rather than the nearest lower one; and passive quality queries on Flixer could access an uninitialized tracking list because active instrumentation exits early there. Corrected to one lowering request per hold, highest supported height at/below the requested target, and safe empty passive results on Flixer. No HLS hooks or recurring instrumentation were added to Flixer-owned documents.

### Previously repaired but re-reviewed

The direct validated bookmark import has no replacement-confirmation screen. Menu/list transitions are mutually exclusive and brought to the front. Bookmark paging, URL case handling, malformed imports, fullscreen keypad routing and diagnostic redaction were rechecked. These are source/test findings, not proof that every Fire TV menu/provider behaves correctly.

## Unresolved release blockers and limitations

### 1. Flixer seeking is not established

There are two different position bars in this program: the website/player's timeline and the browser's native OSD bar. **The native OSD bar is display-only in the reviewed program.** The pointer dispatcher targets WebView/custom fullscreen content; it does not make the native OSD seek bar interactive. The previous repair assumed a website timeline without establishing which bar was involved.

For a website timeline, the one-shot seek helper deliberately cannot reach cross-origin video objects. Mouse input alone has not been demonstrated to fix the actual Flixer player. Coreflix's protected React click workaround constructs a synthetic handler event without full real pointer coordinates; it is another possible interaction for position-dependent controls in vidcore frames, not a proven cause on the user's exact Flixer player. That protected workaround has not been rewritten.

**Neither a generic DOM fixture nor source inspection establishes that the reported Flixer seek failure is fixed.** A release must resolve the actual input-to-player path, rather than rename this uncertainty as success.

### 2. Actual Android/WebView integration has not run

No physical Fire TV or actual Android instrumented app session was available. The restored direct-key design reduces the change from 1.5 but does not prove every search icon, embedded field or Fire OS keyboard presentation. Renderer/GPU/DRM behavior, native fullscreen controls and remote delivery require real platform execution. The user is not being asked to install another candidate as a substitute for that work.

### 3. Broad generic fixes are conditional

- Menu fitting recognizes certain markup and avoids moving surrounding video/control containers. It cannot certify every custom season menu, native select popup, clipped ancestor or closed shadow tree.
- Quality selection is best effort for exposed HLS/Shaka players. Flixer-owned documents intentionally skip active hooks; native HLS and inaccessible instances are not guaranteed to honor 400–800p preference.
- Idle dimming depends on observable playback/audio state. Quiet/custom/opaque players and Fire OS's independent screensaver remain integration risks. The overlay changes the app image, not hardware TV brightness.
- On newer Android/TV builds without a document picker, the optional app-specific backup folder is removed on uninstall. A bookmark export must exist outside app-specific storage before a fresh-install workflow. No bookmark export backs up every app setting.
- The legacy JavaScript bridge is exposed to all web frames. Diagnostic structured redaction is not a guarantee that free-form strings contain no sensitive content. Large bookmark lists are still eagerly rendered in the management panel. Immutable screen metrics across handled configuration changes and protected Flixer timing/shared state remain additional review risks.

## File-by-file coverage

| Runtime file | Review scope / disposition |
|---|---|
| MainActivity.kt | All initialization, WebView configuration, script registration, navigation, callbacks, bridge methods, remote routing, pointer dispatch, fullscreen, keypad, menus, bookmarks, settings, diagnostics and teardown paths; corrections above; actual platform/player execution still absent. |
| TvWebView.kt | Compared to original; retained-editor experiment removed; original raw key route restored; no system IME connection is exposed. |
| BookmarkStore.kt | Canonical URL boundaries, import validation/rollback, deduplication, ordering, folder mutations, persistence and startup handling; extreme list size/storage failures remain risks. |
| CrashLog.kt | Process-wide installation, previous-handler chaining, bounded crash text and deliberate synchronous crash persistence. |
| CrashRecoveryPolicy.kt | Recovery-window, redirects, stop threshold, expiry/clock boundaries and overflow. |
| DiagnosticLog.kt | Queue bounds, thread ownership, rotation, session/critical-file behavior, export serialization and structured redaction; free-form privacy/concurrency limits explicitly retained. |
| IdleDimPolicy.kt | Monotonic inactivity/playback reset; native integration and unknown-player limits. |
| Keypad.kt | All layouts, weights, focus-free selection, layer/shift transitions, remote/media/USB-key routes and listeners; Unicode/dead-key edge cases are not exhaustively tested. |
| CursorView.kt | Hotspot geometry, loading indicator and non-interactive overlay behavior. |
| Osd.kt | Timer cleanup, status/volume/position display; native seek bar is not interactive. |
| Panels.kt | Menu/list/text construction, focus confinement, deferred focus, footer actions and ordering. |
| PointerTap.kt | Event source/tool/button/pressure/timing and touch-vs-mouse scope. |
| PopupGuard.kt | Domain matching, default/custom protections, navigation routing and shared state. |
| StartPage.kt | Eight-item paging, folder-page restoration, deletion clamping, partial-page geometry and focus. |
| Ui.kt | Scaling, focus/selected backgrounds, tile/key sizing, progress drawing and NaN handling; stale metrics/configuration risk noted. |
| flixer_image_guard.js | Read fully; intro session flag, host/idempotency checks, allowlist/observer/sync behavior; unchanged from supplied 1.5. |
| popup_guard.js | Read fully; window.open, link rewrite, React workaround and overlay scanners; unchanged from supplied 1.5. |
| page_helpers.js | Read fully; passive target/media/text APIs, frame boundaries, exclusivity, diagnostics, quality wrapping and all timers/listeners; generic corrections above. |
| fmhy_theme.js | Host isolation, storage keys, root readiness, observer cleanup and no global filter injection; real-site timing unverified. |
| tv_ui.js | Editable focus/report/edit behavior, frame origin boundaries, menu fitting, nested scrolling and playback reporting; conditional site support. |
| seek_click.js | On-demand-only operation, hit testing, frame mapping, player association, finite duration/seekable windows and exclusions; actual Flixer seeking not established. |

Manifest/storage permissions, themes, SDK levels, dependency/build settings and unchanged image resources were also checked. Compile SDK 34 is not the same thing as target SDK 34; target SDK remains 33 as in the reviewed configuration.

## Protected-source verification

Original Flixer intro/image-guard and Popup Guard assets remain byte-for-byte unchanged, as do the native Flixer guard/reset block and native dark-mode implementation. The helper protection test was refined from a broad prefix that included generic diagnostics to the actual passive API plus Flixer early-return boundary. Three explicit passive-quality corrections are normalized when comparing that prefix: two empty-list guards and the nearest-lower quality comparison. This is recorded rather than silently claiming the whole helper is unchanged. No tests of the protected site workarounds were removed to claim a pass.

## Validation interpretation

Five new Android tests were first run against delivered 1.7.1: four failed and the delayed-text-field positive control passed. The new five-check browser diagnostic fixture failed three checks before correction. Those failures demonstrate specific missed defects, not every cause of the user's experience.

Final automated results are appended with the evidence bundle. Robolectric uses a fake WebView, scripted evaluateJavascript responses and an explicitly supplied viewport. Its JsResult cancellation shadow records a flag rather than invoking a real Chromium receiver. Chromium DOM fixtures control media properties rather than decoding a real stream. These limitations are part of the results, not fine print that turns a pass into a device certification.

## Next release gate

A future release must establish the actual Flixer timeline/OSD interaction and run real Android/Fire TV acceptance against the working 1.5 behavior for remote playback, typing, menus, navigation, imports and protected sites. Until then, no claim that the reviewed source is bug-free or a verified overall improvement is justified. This report and source bundle are review/correction artifacts, not another APK for the user to trial.

### Completed automated results

- 33 Android/Robolectric tests passed; zero failures/errors.
- 31 offline Chromium DOM assertions passed (17 UI + 9 seek + 5 diagnostic safety).
- Node quality/theme/seek checks, protected-source comparisons and all JavaScript syntax checks passed.
- Android test compilation and lint succeeded: 0 errors, 23 warnings.
- No current reviewed-source APK was packaged or supplied. The prior 1.7.1 APK does not contain these additional corrections.
