# Buccaneer 1.9.3 — editor, recovery and submenu integration review

## Scope and engineering decisions

This is a targeted repair of the reported keyboard, crash/reopen and submenu regressions, on top of 1.9.2. It is not a claim that arbitrary third-party pages or Fire OS are bug-free. No physical Fire TV or Android WebView provider was available. The previously delivered 1.9.2 source remains untouched.

### 1. Keyboard: preserve Chromium's editor; exclude the system IME separately

**Design defect:** `TvWebView` returned `false` from `onCheckIsTextEditor` and `null` from `onCreateInputConnection`. That conflated suppressing the platform keyboard with disabling the browser's editor. An activity setting that merely says to start with the keyboard hidden is also not a durable exclusion of later IME requests.

**Repair:** remove those overrides. MainActivity sets `FLAG_ALT_FOCUSABLE_IM` on its window, without `FLAG_NOT_FOCUSABLE`. The app QWERTY still routes real key events to Chromium, including backspace, Ctrl+A/Delete and Enter. It does not retain or manufacture an InputConnection, write into an arbitrary frame through messages, or replace the working 1.5 typing transport with a speculative editor implementation. Remote keyboard selection remains independent of Android focus.

**Visibility:** opening page-edit mode reserves a native WebView height above the measured keypad and its bottom margin. The focused field is brought into view after the resize. Keypad layout changes recalculate the reservation; opening an app prompt, closing the keyboard, closing all panels, and navigating restore match-parent sizing. Only an actual height change requests a layout; this is not a recurring resize timer.

**Preview:** a cached bridge snapshot previously took precedence over a freshly read field, allowing a permanently stale preview. A new read-only snapshot is qualified by document, frame and field identity. Same-origin traversal is bounded, cross-origin access is not bypassed, and password snapshots contain only a mask length. Native callbacks reject changed editor sessions and mismatched document/frame/field ownership. A qualified bridge snapshot remains the fallback for an unreachable cross-origin frame; such a fallback cannot guarantee a fresh preview if that frame does not report input.

**Integration review:** page-edit versus prompt state transitions, delayed field reads, document navigation, loss of foreground, keyboard dismissal, remote transport versus typing, and password masking were checked. The native editor behavior of a Fire OS provider is still a device-only verification item; structural and shadow tests do not prove it.

### 2. Crash recovery: a manual retry is not an automatic retry

**Design defect:** the persisted crash budget could remain exhausted when a bookmark was explicitly selected again. Renderer death while paused was acknowledged without disposing the dead WebView.

**Repair:** explicit bookmark/address navigation and Reload receive a fresh retry budget. Automatic startup recovery and forwarded popups preserve the current budget, so redirects cannot reset it and create an unlimited crash loop. The intended URL is recorded before `loadUrl`, covering death before `onPageStarted`.

A renderer-loss callback for the current WebView disposes it even while paused. Recreating the Activity waits for foreground; the resume path requests recovery once. Duplicate or foreign callbacks cannot dispose the current browser. The dead renderer is never asked to stop loading, execute JavaScript, or return a prompt result. Crash counts are committed before recreation, retaining the existing bounded automatic recovery policy.

**Integration review:** automatic startup versus explicit retry, global redirect-loop budget, paused/foreground transitions, duplicate callbacks, popup ownership, pending evaluations, fullscreen cleanup and Activity teardown. The retry fix does not pretend to prevent underlying website/GPU crashes; it prevents the stale browser/retry state from blocking reopening.

### 3. Menus: fit the visible clipping region, not just the window

**Design defect:** a menu could fit inside the screen but remain clipped by an overflow ancestor. Physical pixel offsets were used as CSS offsets even inside scaled wrappers. The recognized selector set missed popups referenced only by an expanded control.

**Repair:** intersect the viewport's 5% vertical TV-safe inset with bounded overflow/paint-clipping ancestor client rectangles. Convert maximum height and movement through the measured CSS scale. Recognize existing menu roles and Radix classes, Material popup classes, and elements explicitly referenced by expanded `aria-controls`/`aria-owns` controls. Fit only eligible positioned popup wrappers, with existing safeguards against moving unrelated controls or media. Menu content scrolls internally and boundary scrolling stays in the menu.

The original DOM parentage, event handlers and framework ownership are preserved. Original inline styles are recorded once and restored only if the current property is still the fitter's value; subsequent site-authored changes are not erased. There is no global CSS inversion/filter, DOM observer, recursive timeout, arbitrary all-div rewriting, or relocation into a different parent.

**Integration review:** clipping ancestor, transformed/scaled wrapper, content-box sizing, repeated fitting, restoration, popup closure, nested scroll boundaries, same-origin iframe mapping and shadow-root scrolling. New regression fixtures run at 960×540, 1280×720 and 1920×1080. Native renderer behavior and actual live season menus were not physically tested.

**Scope limit:** this recognizes particular DOM structures, not every custom menu. Unrecognized, rotated or cross-origin menus cannot be promised fixed by these tests. The entire `tv_ui` helper still deliberately skips Flixer-owned documents; continuous Flixer instrumentation was not enabled to solve a generic menu problem. The existing native wheel fallback remains. If clipping is on Flixer itself, this general fitter repair must not be mistaken for a verified Flixer-specific fix.

## Protected behavior and compatibility

- Original Flixer intro/image protection, native render-reset/request safeguards, Coreflix/Popup Guard button workaround and native dark-mode behavior pass the protected-source checks.
- Five existing JavaScript assets are byte-identical to 1.9.2. Only `tv_ui.js` changed, recorded separately in the approved-repair manifest rather than silently overwriting the original compatibility baseline.
- Playback/seek/quality logic is unchanged in this repair. The prior unresolved live Coreflix rendition verification remains unresolved; 1.9.3 is not evidence that a live Coreflix 720p click was tested successfully.
- Compile SDK 34, minimum SDK 22, target SDK 33, AndroidX WebKit 1.9.0, permissions, hardware acceleration and the supplied launcher resources are retained. There are no bundled ABI-specific native libraries.
- The earlier 17 warning fixes remain. Nine intentional compatibility/durability/JavaScript conditions remain explicitly acknowledged, not newly hidden to produce a clean report. See validation and the historical warning disposition document.

## Test quality and known limits

The before-repair tests reproduced six native contracts and seven DOM contracts failing. A native geometry test was then corrected because Robolectric's WebView has no real Chromium measurement provider: it must check the actual native layout-height request against the measured keypad, not mistake a shadow's zero WebView height for a real device result. The corrected contract was rerun against a disposable, unchanged 1.9.2 runtime and failed on its match-parent height (`-1`) versus the keypad top (`236`), then passed against 1.9.3. The final tests cover the layout reservation and restoration, not physical WebView rendering.

The final suite has 88 Android tests and 84 Chromium assertions, plus seven Node suites and protected-source/compatibility checks. These include existing API-22 startup and API-34 integration coverage. All passed. The seven new editor/recovery contracts were supplemented with seven additional lifecycle/ownership/state-transition checks. No real IME service, PowerVR driver, remote hardware, decoder, live provider or uninstall round-trip was emulated by those test counts.

The signed release was checked for SDKs, non-debuggable manifest, v1/v2/v3 signatures, zip alignment and exact packaged JavaScript bytes. See `VALIDATION_1.9.3.md`, `RELEASE_CHECKS_1.9.3.json`, `validation/` and `review-evidence/`.
