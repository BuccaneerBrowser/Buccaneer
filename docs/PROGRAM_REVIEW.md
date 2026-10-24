# BuccaneerClaude -- Full Program Review

**Version reviewed:** 2.3.1 (versionCode 24), tree `<repo-root>`, git HEAD `a390764`
**Date:** 2026-10-22 | **Method:** line-level read of every source file (Kotlin + JS + tests + docs), cross-verified against the live app on the Fire TV via CDP (isolated worlds) and the diagnostic JSONL pulled from a real viewing session.
**Purpose:** a single reference so that future coding sessions can fix one subsystem without regressing another. The recurring failure mode this document exists to prevent: *fix one thing, break another*.

## How to use this document

1. **Before changing any file**, read the subsystem section for that file (Part 2), the interdependency matrix (Part 3), and the invariants list (Part 4).
2. **After changing anything**, run the verification steps in Part 8 and the regression checklist in `TESTING.md`.
3. **Line-level detail** (every function, line numbers, per-file invariants) lives in the companion analysis files:
   - `docs/review/part1a-mainactivity.md` -- MainActivity.kt lines 1-1350 (fields, lifecycle, key handling)
   - `docs/review/part1b-mainactivity.md` -- MainActivity.kt lines 1350-2700 (JS bridge, WebView clients, page-edit queue, navigation)
   - `docs/review/part1c-mainactivity.md` -- MainActivity.kt lines 2700-3956 (fullscreen, video commands, OSD, cursor, menus, bookmarks, watchdog)
   - `docs/review/part2-page-helpers.md` -- page_helpers.js (the `__fb` bridge, cross-frame protocol, quality scanner, fullscreen intercept)
   - `docs/review/part2b-tv-ui.md` -- tv_ui.js (field reporting/editing protocol), seek_click.js, fmhy_theme.js
   - `docs/review/part3-guards.md` -- popup_guard.js, flixer_image_guard.js, seek_click.js, fmhy_theme.js (kill rules, thresholds, PowerVR risk)
   - `docs/review/part4a-support.md` -- DiagnosticLog, Keypad, Osd, CursorView, TvWebView, NativePageEditor
   - `docs/review/part4b-support.md` -- BookmarkStore, BookmarkFiles, BookmarkJobs, StartPage
   - `docs/review/part4c-support.md` -- PopupGuard, Watchdog, CrashLog, NavigationHandoff, UiCoalescer, SeekRepeatPolicy, and the small policies
   - `docs/review/part5-tests.md` -- every test, what it protects, protected-fixes hash mechanism
   - `docs/review/part6-docs.md` -- verified claims inventory of all project docs (which doc claims are true, which are wrong)

**Ground truth order:** source code > this document > older handoff docs. Handoff documents have been found to contain phantom event names and stale claims; `part6-docs.md` lists which ones.

---

## Part 1 -- System overview

### 1.1 What the app is

A single-activity Android TV browser for Fire TV (target device: AFTMM, Android 7.1.2, PowerVR GPU, Amazon Chromium 118 WebView fork). It is **not** a desktop browser: there is no mouse, no system keyboard, no touch. Everything is driven by a D-pad remote and rendered through an on-screen cursor.

The app's job, in one paragraph: host a WebView; translate remote keys into page interactions (cursor movement, taps, typing, transport controls); keep video playing reliably on low-power TV hardware (fullscreen, seek, popup suppression, image load limiting); survive GPU driver crashes (out-of-process watchdog); and persist bookmarks.

### 1.2 Process model

| Process | What it runs | Why |
|---|---|---|
| `com.buccaneer.browser` | MainActivity + WebView + all UI | the app |
| `com.buccaneer.browser:watchdog` | WatchdogService | watches the main process via a bound-service connection; restarts the app after a native (GPU) crash. Native crashes never reach the app's own crash handler -- the watchdog is the only recovery path. |
| (Chromium) GPU/renderer processes | WebView internals | renderer crashes are handled in-app via `onRenderProcessGone` -> `destroyBrowser(rendererGone=true)` + recreate |

### 1.3 View tree (built in `onCreate`, MainActivity.kt L529-594)

```
root (FrameLayout)
+-- webView (TvWebView)          -- the page; TvWebView.onCheckIsTextEditor()=false suppresses the system IME
+-- progress (BarView)           -- 4px loading bar, top
+-- cursor (CursorView)          -- the on-screen pointer (D-pad driven)
+-- startPage (StartPage)        -- launch page: top-10 bookmarks/folders as buttons
+-- menu (MenuPanel)             -- main menu
+-- listPanel (ListPanel)        -- bookmarks list, folder options, move-to-folder
+-- textPanel (TextPanel)        -- URL prompt / bookmark name prompt
+-- keypad (Keypad)              -- the on-screen QWERTY keypad (replaces system IME)
+-- fullscreenBox (FrameLayout)  -- hosts the WebView's custom fullscreen view
+-- osd (Osd)                    -- on-screen display: seek bar, volume, fullscreen hints
+-- dimOverlay (View)            -- idle dimming overlay
```

All of these are added **once** in `onCreate`. The only dynamic `addView` in the whole app is `fullscreenBox.addView(view)` in `enterFullscreen` (L2581) -- see Part 8 (open issues) for the crash this is connected to.

### 1.4 JS injection model

Six JS files are injected at **document-start** (before page scripts run) into **every frame** of every page, via `WebSettings.setDocumentStartJavaScript` (feature-gated; API 25 supports it):

| Asset | Size | Runs in | Purpose |
|---|---|---|---|
| `page_helpers.js` | 1911 L | every frame | the `window.__fb` bridge: video state/commands, cross-frame command forwarding, quality scanner, network probe, fullscreen intercept, pointer poke, dark mode, media health heartbeat |
| `tv_ui.js` | 335 L | every frame | the `window.__tvUi` bridge: field reporting (which input the user clicked), edit/submit of typed text, playback probe |
| `popup_guard.js` | 538 L | every frame | ad/popup suppression: window.open block, target=_blank rewrite, overlay kill rules, the Coreflix `reactOnClick` workaround |
| `flixer_image_guard.js` | 286 L | flixer + cinejoy only | TMDB poster virtualization (src-stripping off-screen posters) + 1x1 firewall coordination with native `shouldInterceptRequest` |
| `seek_click.js` | 72 L | on demand | seek-bar click repair (called by Java, returns a value) |
| `fmhy_theme.js` | 23 L | fmhy host only | one-time theme class toggles (self-disconnecting observer) |

**Frame opacity rule:** a frame cannot reach into a cross-origin child iframe's DOM or window. Streaming sites nest the player 2+ levels deep, each cross-origin. Consequences (verified live 2026-10-22 on novahd.cc -> vidbolt.xyz):
- The top frame's `__fb` cannot see the player's `<video>`; it learns the player's state via the **postMessage bridge** (`__fbCmd`/`__fbRes`) -- the top frame forwards commands, the player frame's own `page_helpers.js` executes them on its local video and replies with `state()`.
- CDP **isolated worlds share the DOM but NOT main-world JS globals**: `window.__fb` is invisible from an isolated world even though it exists in the frame's main world. (Verified: `typeof window.__fb` is `undefined` in an isolated world of the vidbolt frame, while `media_health` events prove the helper is alive there.)
- The only other control path into an opaque player is the player's own UI (taps) or CDP from the host.

### 1.5 The Java<->JS contract (the two bridges)

**`__fb` (page_helpers.js)** -- 28 members, verified live:
`cancelFullscreen, exitCssFullscreen, cssFullscreenActive, selectAt, focusTarget, setDiagnosticLogging, togglePlay, play, playBlocked, pause, seek, volume, toggleMute, unmute, muteButton, playerControls, tapStack, tapStackAll, playerGeometry, frameCenter, diag, state, prepareFullscreen, field, focusIntoView, clear, setQualityPreference`

- Java calls these via `evaluateJavascript`; results come back JSON-string-encoded and are unwrapped by the top-level `jsString()` helper (MainActivity L56-74), which enforces a 1 MiB cap + `JsonInputBudget` on page-controlled data.
- `state()` returns a compact object, e.g. `{"f":1,"p":1,"t":0,"d":0,"h":0,"v":1,"m":0}` (fullscreen, playing, ..., volume, muted). In the top frame it **merges the player frame's last reported state (10 s freshness)** so the OSD shows position for cross-origin players.
- **Return-value semantics that must not change:** `seek`/`togglePlay` etc. return `'ok'` (acted on the local video), `'frame'` (forwarded to a cross-origin player frame whose own helper ran it -- **a success**, no fallback), `'none'`/`'failed'` (no video / didn't take). Only the last case triggers the DPAD_LEFT/RIGHT key fallback. Treating `'frame'` as failure was a previous bug -- do not "simplify" this.
- `dispatchToPage` **always targets the webView** -- the fullscreen custom view is only a render surface; keys sent to it vanish. (This was the fullscreen-keys bug.)

**`__tvUi` (tv_ui.js)** -- 8 members, verified live:
`scrollAt, fitMenus, restoreMenus, reportField, fieldState, edit, type, playback`

- The keyboard opens **only** for a field the user's click focused: tv_ui.js flags reports `viaClick` within a 1 s window after a trusted click; Java opens the keypad on flagged reports only.
- The open path **must record field identity** (`inputFrame` + `inputFieldId`) from the report; typing targets the field by ID; the JS edit path accepts edits only when `activeInputFrame()` matches the reported frame. An open that records no identity rejects every edit including Enter (this was a real break).
- `edit()` returns `stale` when the recorded field no longer matches (React re-rendered the input) -> the app toasts "Select the search field again; input was not confirmed".
- Submit = synthetic Enter keydown to the **recorded** field; formless search widgets get the key only.

### 1.6 Key routing (the spine of the app)

Every remote key arrives at `dispatchKeyEvent` -> `onKey`. The routing decision tree (full detail in part1a) is state-based, in this priority order:

1. **Menu/list/text panel open** -> panel navigation (D-pad moves highlight, Select activates, Back closes; `menuBack` callback chain)
2. **Keypad open** -> keypad grid navigation; ENTER/DPAD_CENTER on the GO key triggers submit (`onGo`), elsewhere presses the highlighted key; Play/Pause always submits
3. **Fullscreen (native customView or CSS)** -> `onFullscreenKey`: Back exits (native: `leaveFullscreen(true)`; CSS: `exitCssFullscreen()`), Select = play/pause, Left/Right = seek (via `videoSeek`), Up/Down = volume, media keys pass through
4. **OSD seek bar hit** -> `tapAtCursor` hit-tests `Osd.seekFractionAt` **before** dispatching a tap: a key press while the OSD is visible becomes a seek, not a click
5. **Normal browse** -> D-pad moves the cursor (`sendHover` dispatches hover events; the pointer is *parked* -- see pointer-lift invariant), Select = tap (`tapAtCursor` -> `sendTap`), Back = history back (with `lastBackAt` double-back-to-start-page logic), Menu = main menu, long-press Select = keypad, media keys = transport
6. **Start page** -> startPage handles its own grid navigation

**Long-press detection uses a timer, not key repeats** (500 ms `postDelayed` on key-down, cancelled on key-up): CEC remotes send no repeats. The key-up handler must clear the pending flag before firing the short action, or the short action fires after the long action (closes the menu the user just opened).

**Invalidation (the load-bearing architecture):** `pageEpoch` (bumped on every navigation/page generation change) and `interactionSerial` (bumped on user input and fullscreen transitions) are checked by nearly every async callback (eval results, posted runnables, coalesced UI). A callback from an old generation is dropped silently. This looks like patch-on-patch; it is the design. **Do not remove or "simplify" these checks.**

### 1.7 The five user-visible subsystems and their shared code paths

The regression-prone core (per `TESTING.md`): **keyboard/Enter, fullscreen autohide, FF/rewind, popup guard, image guard**. They share: key handling (one decision tree), focus (WebView vs IME vs panel), pointer/hover events (cursor parking vs player autohide), and the DOM (guards walk it, the player lives in it). Any change to one can ripple into all five -- Part 4 is the push/pull matrix.

---

## Part 2 -- Subsystem deep dives

Each section: **what it does -> where it lives -> the contract -> invariants -> what breaks if you change it -> how to verify.** Line numbers refer to the 2.3.1 tree; the part files in `docs/review/` have the exhaustive per-function detail.

### 2.1 Input & key routing

**What:** one decision tree turns every remote key into an action. States: panel open (menu/list/text), keypad open, fullscreen, OSD-visible, normal browse, start page.

**Where:** `MainActivity.onKey`/`onKeyDown` (part1a), `onFullscreenKey` (L2621+), `tapAtCursor`, `sendTap`, `sendHover`, `Keypad.handleKey`.

**Contract:**
- Key events are logged **before** the handler runs: `diagKey` records `{code, handler}` where `handler` is the consuming branch (`browse`, `fullscreen`, `startPage`, `keypad`, `transport`, ...). A `key` event with handler `transport` proves the key reached the app, **not** that the action landed.
- The OSD seek bar is hit-tested **before** tap dispatch: while the OSD is visible, a key press becomes a seek (`Osd.seekFractionAt`), not a click.
- Holding past the first key repeat (~500 ms) opens the keypad by design; long-press uses a timer (CEC remotes send no repeats).
- `swallowedUp` (HashSet of keycodes) + `lastBackAt` implement Back-key semantics: first Back goes to history, a second Back within the window goes to the start page.

**Invariants:**
- The branch order is the behavior. Reordering branches re-routes keys.
- `noteUserInput()` bumps `interactionSerial` -- any code that captures the serial at start and checks it at end is invalidated by a user input. That is intended.
- `wakeKey`/`wakeTouch`: while dimmed, the first key/touch is consumed to undim, not delivered.

**Breaks if changed:** wrong branch order -> keys land in the wrong state (the classic "menu opens when I press seek" class of bugs). Removing the OSD hit-test -> taps land on the seek bar instead of seeking. Repeat-based long-press -> CEC remotes can't open menus.

**Verify:** pull the JSONL, read the `key` events' `handler` field for the exact keypress in question. `tap_stack` with `count: 0` means the tap landed on nothing (no element at that point).

### 2.2 Keyboard & typing (page editing)

**What:** the app's own QWERTY keypad replaces the system IME. Typing reaches the page as synthetic key events; ENTER submits.

**Where:** `Keypad.kt` (grid, `Kind.GO`, `handleKey`), `tv_ui.js` (`reportField`, `edit`, `type`, `fieldState`), MainActivity page-edit queue (`PageEdit`, `queuePageEdit`, `drainPageEdits`, `dispatchNativePageEdit`, `finishPageEdit`), `NativePageEditor.kt`.

**Contract (the 2026-10-17 clean rewrite -- do not re-patch):**
1. The keyboard opens **only** for a field the user's click focused. tv_ui.js flags a report `viaClick` within 1 s of a trusted click. Java opens on flagged reports only; unflagged reports just sync the preview of the recorded field.
2. The open path **records field identity** (`inputFrame` + `inputFieldId`) from the report. Typing targets the field by ID. The JS edit path accepts edits only when `activeInputFrame()` matches the reported frame. An open that records no identity rejects every edit including Enter (this was the break that made "it wants me to select the search field" appear).
3. Typing permit = an open editor. Failed edits never replay keystrokes. Submit closes. A new click reopens.
4. `edit()` returns `stale` when the recorded field id no longer matches (page re-rendered) -> toast "Select the search field again".
5. ENTER/DPAD_CENTER on the GO key -> `onGo()` (submit); elsewhere -> `press()` the highlighted key. Play/Pause submits from any position. GO is labeled "ENTER" in page-edit mode, "GO" in prompts.

**Invariants:** no `lastTapAt` window, no `keyboardDismissedUntil` cooldown abuse, no `inputFocusConfirmed` gate, no post-tap field probe -- those were stacked patches that caused the keyboard opening on stray focus. `keyboardDismissedUntil` exists but only blocks `focusFromPage` re-opening after a deliberate dismissal.

**Breaks if changed:** the field-identity recording (point 2) is the single most fragile link -- a React re-render between click and first keystroke produces `stale`. Do not build a parallel edit path in page_helpers.js (a reimplementation there returns `stale` and makes typing worse).

**Verify:** `edit_result` events carry `{action, field, status}` -- `applied` vs `stale` is the ground truth. `keypad_go` logs `{via: "enter"|"select"|"playpause"}`.

### 2.3 Navigation & page lifecycle

**What:** URL navigation, history, renderer crash recovery, popup windows.

**Where:** `navigateUrl`, `handoffNavigation` + `NavigationHandoff.kt` (Pending state machine, `commitWhenForeground`), `destroyBrowser(rendererGone)`, `onRenderProcessGone`, `onPageStarted/onPageCommitVisible/onPageFinished`, `shouldInterceptRequest` (image firewall), `onFlixerPage`/`onImageGuardedPage` host detection, `popupViews`/`popupTimeouts` (popup windows).

**Contract:**
- `pageEpoch` bumps on every page generation change; every async callback (eval results, posted runnables, coalesced UI, heartbeats) checks `epoch == pageEpoch` before acting. Mismatch -> drop silently.
- `onPageCommitVisible` is the reliable "page is real" signal; **`onPageFinished` is not reliable on fmhy** -- code that waits for it hangs there.
- `NavigationHandoff` serializes "commit the navigation" vs "tear down the page" so a navigation requested during teardown isn't lost and a teardown during navigation doesn't kill the new page.
- Renderer crash: `onRenderProcessGone` -> `destroyBrowser(rendererGone=true)` removes the webView from its parent (L308), nulls it, and a recreate task (deduped by `rendererRecreatePosted`) rebuilds it. `crashReloadCount` bounds reload attempts; `showCrashRecovery(url)` offers the user a retry.
- Host detection: `isImageGuardedHost()` is the **single source of truth** for image-guarded hosts (flixer + cinejoy); `onFlixerPage` stays flixer-only (player/tap/seek behavior). Keep the two separate.
- `shouldInterceptRequest` returns 1x1 responses for off-allowlist TMDB images **only on guarded hosts** -- extending it to other hosts breaks lazy-load detectors and load-event reporting (tried 2026-10-16, reverted: no speed gain, Coreflix quality button stopped opening).

**Invariants:** the epoch/serial checks throughout are the architecture, not debt (0 TODO/FIXME in the codebase; 13 swallowed exceptions, all intentional: WebView teardown, malformed JSON from the bridge, synthetic event rejection).

**Breaks if changed:** removing an epoch check -> a stale eval result from the old page acts on the new page (ghost taps, wrong seek). Changing `onPageCommitVisible` handling -> fmhy hangs. Widening the image firewall -> lazy-load UI breaks site-wide.

**Verify:** `page_started` / `page_commit_visible` / `page_finished` events; `navigation_cleanup_dispatched` + `cleanup_script_completed` (or `cleanup_timeout`); `http_error` for status codes.

### 2.4 Fullscreen (native + CSS)

**What:** two fullscreen paths. Native: the WebView's custom-view fullscreen (video element goes fullscreen). CSS: the app intercepts `requestFullscreen`/`webkitEnterFullscreen` and enters a page-level (CSS) fullscreen that keeps the site's own controls -- native Android fullscreen on Fire TV draws a persistent center play/pause button that conflicts with the browser UI, hence the intercept.

**Where:** `enterFullscreen` (L2570), `leaveFullscreen` (L2589), `exitCssFullscreen` (L2606), `onShowCustomView`/`onHideCustomView`, `prepareFullscreen`, the fullscreen intercept in page_helpers.js, `cssFullscreenActive` flag.

**Contract:**
- `enterFullscreen(view, callback)`: guarded by `view == null || customView != null` -> refuse + `callback.onCustomViewHidden()`. Bumps `interactionSerial`. Cancels player work + pending taps. Adds the view to `fullscreenBox`.
- `leaveFullscreen(notifyPage)`: removes the view, hides the box, `osd.hideNow()`, refocuses the webView, and -- only if we left on our own (Back key) -- calls `callback.onCustomViewHidden()` to tell the WebView.
- CSS fullscreen: the intercept overrides `requestFullscreen` **and** `webkitEnterFullscreen`. Gotcha: on Chromium 118/API 25, `webkitEnterFullscreen` lives on `HTMLVideoElement.prototype`, **not** `HTMLMediaElement.prototype` -- patch whichever prototype has the method, and also patch `document.webkitExitFullscreen` (the player's exit path).
- The player-container heuristic for CSS fullscreen must NOT be a pure area walk (when the video fills the viewport, every ancestor has the same area and the walk never stops). Primary signal: the first ancestor (within ~3x video area) that contains a control `<button>`; size walk only as fallback.
- **Flixer is excluded from the intercept** (existing behavior -- the flixer player manages its own fullscreen).
- When entering CSS fullscreen pauses the video (some players do), the app auto-resumes it (commit `4b2a260`).
- `onShowCustomView` fires mid-transition: any JS probe that needs a stable page state must be delayed (the `playerDomProbe` eval is wrapped in `postDelayed(..., 2000)` -- a synchronous eval there returns the literal `"none"`).
- For cross-origin iframe players, the fullscreen element is the **IFRAME**, not the video. You can style the iframe from the top frame (black background for letterboxing) but cannot reach the video inside.

**Invariants:** `dispatchToPage` always targets the webView, never the custom view. The 2000 ms delayed probe is not a hack to clean up. Flixer exclusion is load-bearing.

**Breaks if changed:** the double-activation history: a direct React-handler call (the Coreflix workaround) double-fires if the real click also arrives -- the workaround defers to a 300 ms timer that a capture-phase click listener cancels. Don't "simplify" the timer.

**Verify:** `css_fullscreen_on`/`css_fullscreen_off` events; `fullscreen_element` diagnostic (tag/id/class/size of the fullscreen element); screenshot after ~10 s idle (controls + center button gone), tap (reappear), wait (re-hide).

### 2.5 Video transport (seek / play / volume)

**What:** media keys (FF/rewind/play-pause/volume) control the page's video, including cross-origin players.

**Where:** `videoSeek`, `videoCommand`, `tapPlayerFrame`, `SeekRepeatPolicy.kt`, `__fb.seek/togglePlay/...`, the `__fbCmd`/`__fbRes` postMessage bridge, `media_health` heartbeat.

**Contract:**
- `videoSeek` returns `'ok'` (seeked the local video directly), `'frame'` (forwarded to a cross-origin player frame whose own helper ran the seek -- **SUCCESS**, no fallback), or `'none'`/`'failed'`. Only the last runs the DPAD_LEFT/RIGHT key fallback (after `__fb.focusTarget`), and that fallback shows an OSD ("Native seek steps depend on this player").
- There is **no** dedicated seek diagnostic event -- `seek_result`/`seek_fallback` do not exist in the source (a prior handoff cited them; verified absent). Diagnose dead FF/rewind via the `key` event's `handler` field + `media_health` (did position actually change?).
- `SeekRepeatPolicy`: elapsed hold time, not repeats -- first press = 10 s, +30 s after 600 ms hold, +60 s after 2500 ms. (CEC remotes send no repeats; repeat-based logic silently fails on them.)
- `state()` in the top frame merges the player frame's last reported state (10 s freshness) so the OSD shows position for cross-origin players.
- The `media_health` heartbeat (from page_helpers.js, every frame) is the playback signal for the idle/dim policy: `playbackHeartbeatAt` within 12 s = playing.

**Invariants:** do not re-add key fallbacks for `'frame'`. Do not "simplify" the message listener or the ancestor-source check in the postMessage bridge.

**Verify:** measure seeks **paused** (a +10 s seek measured over a 3.5 s wait reads ~+6.5 net -- playback eats into it; paused deltas are exact). `media_health` position deltas are the ground truth.

### 2.6 Popup guard (popup_guard.js)

**What:** suppresses ad popups and floating ad layers without killing legitimate UI.

**Where:** `popup_guard.js` (JS, hash-protected), `PopupGuard.kt` (native side: window.open blocking, target=_blank rewrite, popup window lifecycle).

**Contract -- kill rules (structure only, NEVER text):**
1. Floating iframe with z >= 1000 and < 25 % viewport area (ad creative).
2. Positioned element covering >= 10 % of the video.
3. Positioned element with z-index >= 1,000,000 (injected ad click-catchers use the 32-bit max z).
4. Positioned element covering >= 90 % of the viewport at z >= 1000 (pre-roll click-catchers on pages with no video yet).
- **Player exemption:** the main player iframe -- the largest iframe with a real, non-blank, non-srcdoc src -- and its container chain (`el.contains(playerIframe)`) are exempt from the high-z and viewport rules. Coreflix's `watch-iframe` goes to z-index 2147483647 + position:fixed in fullscreen, the SAME max z as ad injectors; killing it caused black screen with audio + dead seek bridge (the `e6c984f` regression fix).
- **Control protection:** any element containing `button, [role=button], input, select` is never killed (iframe ads are opaque to querySelector, so this check can't protect an iframe -- rule 1's area bound covers that).
- **Text is not an ad signal.** The old text-matching rule killed movie synopses and page titles and hid the Play button inside Movy/CineJoy hero layers (31 `overlay_killed` events, all false positives). Never reintroduce it.
- **Flixer runs the MINIMAL scan** (`killCatchers`): body's direct children + iframes only, cheap rules only, slow interval, **no MutationObserver** -- the full-DOM `querySelectorAll('div, span, a, iframe')` walk crashes the PowerVR GPU during flixer's intro animation. This is the general pattern for any DOM work on flixer: restricted element set, cheap rules, slow interval, no observer.
- **`reactOnClick` (Coreflix/vidcore buttons):** the ad script suppresses synthetic clicks after pointerup, so the workaround invokes React's onClick handler directly -- deferred to a 300 ms `setTimeout` after pointerup, cancelled by a document-level capture-phase `click` listener if a real click fires first. Without the deferral, a real click + direct call double-activates (play->pause, menu flashes then closes).
- `floating_seen` recon log stays for popups that slip through; any `floating_seen` with z >= 1,000,000 is a popup that got through -- investigate.

**Invariants:** hash-protected (`tests/protected-fixes.json`) -- any edit requires updating the stored hash. The ad network injects based on accumulated session/cookies, so popups may not reproduce in a fresh session; verify kill logic with a synthetic high-z element if the real popup won't appear.

**Verify:** `overlay_killed` (with `reason`: `floating_iframe`/`high_z_ad`/`viewport_catcher`/`covers_video`), `floating_seen`, `react_onclick_called`, `popup_guard_action`, `popup_blocked`.

### 2.7 Image guard (flixer_image_guard.js)

**What:** cuts GPU texture load on poster-heavy sites (flixer, cinejoy) by virtualizing off-screen TMDB posters (src-stripping) and firewalling off-allowlist image requests (1x1 responses via native `shouldInterceptRequest`).

**Where:** `flixer_image_guard.js` (JS), `shouldInterceptRequest` + `onImageGuardedPage` (native), `isImageGuardedHost()` (single source of truth: flixer + cinejoy).

**Contract:**
- Host gate: `/(^|\.)(flixer\.(gd|su))|(^|\.)(cinejoy\.pk)$/` -- JS and native must agree; `isImageGuardedHost()` is the native side of that agreement.
- `virtualize()` strips `src` from off-screen TMDB posters so the browser never requests/decodes them; called at the top of `sync()` (wired 2026-10-21, `1bc1ecd` -- before that it was dead code and the 09:33 flixer crash proved the 1x1 firewall alone does NOT prevent the PowerVR bug).
- `sync()` runs on a 2 s interval + IntersectionObserver (200 px margin) + MutationObserver (childList+subtree) -- **on flixer this is the mitigation, not a risk**: it reduces GPU texture load.
- The guard reduces crash probability; it does **not** prevent the vendor PowerVR driver bug. The watchdog crash-recovery remains the safety net.
- CineJoy uses native `loading="lazy"` + real `<img>` TMDB tags, so the benefit there is modest (lazy-loading already defers most off-screen images) -- verified: 373 off-screen posters src-stripped, 26 loaded.

**Invariants:** keep the guard flixer/cinejoy-only. Extending it changes behavior on every site (lazy-load detectors, load-event reporting) -- it was tried, reverted, and the user must approve any extension.

**Verify:** `image_firewall` / `flixer_image_firewall` (`active`, `allowlistSize`), `tmdb_allowlist_sync` (count), `tmdb_image_blocked`; `dumpsys meminfo com.buccaneer.browser | grep Graphics` for GPU texture memory.

### 2.8 Cursor & pointer (the parked-pointer problem)

**What:** the D-pad moves an on-screen cursor; `sendHover` dispatches hover events as it moves.

**Where:** `CursorView.kt`, `sendHover`, `liftPointerIfPlaying`, `hideCursorRunnable`, `playbackHeartbeatAt`.

**Contract:**
- The virtual cursor **parks** a persistent mouse pointer in the WebView (HOVER_ENTER/HOVER_MOVE, never HOVER_EXIT) -- the browser keeps the pointer at the last position forever.
- Pointer-aware players (Movy's `PlayerUIProvider`: controls stay visible while `paused || loading || popups || seeking || isPointerOverUI`) treat a parked pointer over their control area as "user is interacting" and pin the controls visible -- symptom: "controls hide the first time, not the second".
- **The fix (`217a88a`): `liftPointerIfPlaying()`** sends `ACTION_HOVER_EXIT` when the cursor idles (`hideCursorRunnable`) or is explicitly hidden, **but only while video plays** (fresh `playbackHeartbeatAt` < 12 s). While browsing, the parked pointer is what keeps hover menus open while the user reads them -- it must stay.
- HOVER_EXIT generates no mousemove, so it cannot re-trigger YouTube's show-on-activity.
- `MotionEvent.obtain` has no 3-arg (downTime, eventTime, action) overload -- use the 6-arg form with the cursor coords.

**Invariants:** the playing-only condition is the whole fix. Lifting the pointer while browsing breaks hover menus; never lifting it re-breaks player autohide.

**Verify:** `pointer_lifted` events (x, y); screenshot in CSS fullscreen after ~10 s idle.

### 2.9 The pointer poke (page_helpers.js)

**What:** a bounded synthetic mousemove poke that kicks stuck player idle-timers.

**Contract:**
- Synthetic mousemove does **not** trigger autohide on players that ignore untrusted events (`isTrusted === false`) -- verified on CineJoy.
- But it **does** re-wake show-on-activity players (YouTube shows controls on ANY pointer activity, hides after ~3 s idle). A continuous 1.5 s poke perpetually re-shows YouTube controls.
- **The bound is the general rule: poke ~3 times, then stop.** It kicks the idle timer on stuck-control players (a few pokes are enough to start it) and stops interfering with auto-hiding ones. Continuous poking is wrong for all three player types.
- Because the poke runs on every site, a change to it can fix one player type while regressing another -- regression-check on a second site with a different player type before committing.

**Verify:** screenshot after ~10 s idle (controls + center button gone), tap (reappear), wait (re-hide). Do it on an auto-hiding player AND a stuck-control player.

### 2.10 OSD

**What:** on-screen display for seek bar, volume, and fullscreen hints.

**Where:** `Osd.kt` (rendering, `seekFractionAt` hit-test), `refreshOsd`, `seekToFraction`, `osdRefresh`/`osdRequest` (coalesced refresh), `formatTime`.

**Contract:** `seekFractionAt(x)` maps a screen x to a seek fraction -- used by `tapAtCursor` to decide "this key press is a seek, not a tap" while the OSD is visible. OSD refresh is coalesced (`osdRequest` counter + single pending runnable).

**Breaks if changed:** the hit-test is the bridge between key routing and seeking -- a wrong fraction mapping seeks to the wrong time; a missing hit-test sends taps to the seek bar.

### 2.11 Bookmarks

**What:** persist, browse, import/export, organize bookmarks.

**Where:** `BookmarkStore.kt` (416 L), `BookmarkFiles.kt`, `BookmarkJobs.kt` (async file jobs + `Listener`), `StartPage.kt` (top-10 launch grid), MainActivity menu flows (`showBookmarks`, `showBookmarkOptions`, `showFolderOptions`, `showMoveToFolder`, `startSaveBookmark`).

**Contract:**
- Bookmarks live in `shared_prefs/bookmarks.xml` as JSON inside `<string name="data">` -- **not** in `files/bookmarks.json` (that file is empty/absent).
- The last import is stored as `previousImport` in the same prefs -- the restore feature reads that key.
- `importBookmarkText` has zero call sites in `src/main` but is load-bearing: `Regression171Test` calls it via reflection. Grep the whole `app/` directory before deleting any "dead" function.
- Menu focus indices: removing a Settings item must shift the `showSettings(N)` focus indices of every later row, or toggles reopen on the wrong line.

**Verify:** pull `shared_prefs/bookmarks.xml` via `run-as`; check the JSON parses and `previousImport` is present after an import.

### 2.12 Watchdog & crash recovery

**What:** survives native GPU crashes (PowerVR driver SIGSEGV in `libIMGegl.so` -- no app-side fix exists) and renderer crashes.

**Where:** `Watchdog.kt` (91 L, `:watchdog` process), `WatchdogService`, `MainProcessService`, `CrashLog.kt`, `CrashRecoveryPolicy.kt`, `startWatchdog`, `onRenderProcessGone`, `destroyBrowser`.

**Contract:**
- The watchdog is a **separate process** bound to `MainProcessService`. When the main process dies (native crash), the binding drops, the watchdog restarts the app.
- `CrashLog.install` sets a default uncaught-exception handler that records to `diagnostic_critical.jsonl` + the `crashlog` prefs, then **lets Android handle the crash as usual** (the process still dies; the watchdog restarts it).
- The diagnostic `sessionId` is **persisted in prefs** and recovered on restart (`startNewSession(app, "recovered")`) -- so one session id can span process restarts. Don't assume a session id = one process lifetime.
- Renderer crash (in-process): `onRenderProcessGone` -> `destroyBrowser(rendererGone=true)` -> recreate. `crashReloadCount` bounds retries; `showCrashRecovery(url)` offers the user a retry.
- Native crashes never appear in the diagnostic JSONL -- they show as a silent gap followed by `process_start`. The logcat tombstone is the source of truth: `adb shell "logcat -d -b crash"`. Top frames in `/vendor/lib/libIMGegl.so` (`KEGLReleaseRenderTarget`, RenderThread) = PowerVR driver bug; frames only in `libwebviewextchromium.so` on `CrRendererMain` = engine null-pointer. Neither is fixable app-side when the `process_memory` sample near the crash shows healthy PSS.

**Invariants:** software rendering is **not** a fix for GPU crashes (it breaks all page interaction -- `tap_stack` returns `count: 0` -- and does not prevent the SIGSEGV). Never recommend it.

**Verify:** `ps` shows both `com.buccaneer.browser` and `:watchdog`; kill the main process as the app's UID (`run-as com.buccaneer.browser kill -9 <pid>` -- plain `adb shell kill` is refused) and confirm a NEW pid + activity focused again.

### 2.13 Diagnostic logging

**What:** opt-in, bounded, background-thread JSONL logging -- the primary debugging tool.

**Where:** `DiagnosticLog.kt` (453 L), `diagnostics.xml` prefs (`enabled`), `files/diagnostic_session.jsonl` + `files/diagnostic_critical.jsonl`.

**Contract:**
- Routine events NEVER do disk I/O on the caller thread; only `recordCritical` is synchronous. A 256-entry bounded queue feeds a single writer thread; overflow = drop (counted, flushed as `events_dropped`).
- Rotation: 2 MiB ceiling, 1 MiB kept. Flushes are work-driven (100 ms debounce), not timer-driven -- disabled diagnostics must not wake the CPU.
- `recordCritical` writes to a separate file (crash context must survive a mid-write crash).
- The `files/` directory is created **only at app startup when diagnostics are enabled** -- enabling via prefs while running won't create it until force-stop + relaunch.
- Prefs format: **no `android:` namespace prefix** -- `<boolean name="enabled" value="true" />`. The `android:` prefix causes a KXmlParser crash at startup.
- JSONL is UTC; logcat is device-local (7 h offset on this setup) -- match timestamps with care.
- `jsString()` + `JsonInputBudget` bound page-controlled data reaching Kotlin (1 MiB cap, budget re-check on decoded JSON).

**Verify:** `adb shell "run-as com.buccaneer.browser cat files/diagnostic_session.jsonl"`. Check for a `logging_disabled` event before pulling evidence -- if logging was off during the user's test there is nothing to read.

### 2.14 Start page

**What:** the launch screen -- top-10 bookmarks/folders as a navigable grid.

**Where:** `StartPage.kt` (300 L), `showStartPage`, the `startPage` handler branch in key routing.

**Contract:** the start page is a native view (not a web page) shown over the webView at launch and after double-Back. Navigation from it goes through `navigateUrl`. `flixer_render_reset` logs when leaving flixer back to the start page (renderer state reset).

### 2.15 Dark mode, UA, navigation policy

**Dark mode (2.3.1 design -- do not regress):**
- `darkMode` defaults to **true** (MainActivity L334), read from `settings.xml` in onCreate.
- Applied via **Chromium native force-dark**: `WebSettingsCompat.setForceDark(FORCE_DARK_ON)` + algorithmic darkening (`applyDarkMode`, L3572+), logging `dark_mode_applied`.
- `injectDarkMode(view)` (L841) is a **deliberate no-op** with a warning comment: every attempt to force dark via CSS injection (style tag, color-scheme override) broke flixer with a PowerVR SIGSEGV (`KEGLReleaseRenderTarget`). fmhy stays light. **Do NOT add any `evaluateJavascript` style injection there.** (The old 1.9.x "two injection sites with filter:invert" design is gone -- do not restore it.)
- page_helpers.js L26 documents the same: dark mode is the WebView's native force-dark, not page CSS.

**User agent:** `desktopSite` toggle swaps between the default (Amazon TV) UA and a built desktop UA (`buildDesktopUa` -- Chrome version parsed from the real UA). It changes reported identity only; it does not make the engine match (see the Turnstile limitation in Part 6).

**Navigation policy:** `interceptNavigation` (L864, a port of rules.json) decides whether a page-initiated navigation is allowed. Main-frame http(s) navigations are checked against `guard.blocksNavigation(currentUrl, target)` -- the native side of the popup guard's redirect blocking; blocked redirects log `redirect_blocked` + `noteBlocked("a redirect")`. Non-http schemes (about/data/blob/javascript/file) pass; other schemes are blocked.

---

## Part 3 -- Interdependency matrix (push/pull analysis)

This is the answer to "what happens when I change X". Rows = the thing you change; columns = the subsystems that feel it. **H** = high risk (known past regression or shared code path), **M** = medium (shared state, test carefully), **L** = low.

| Change -> | Key routing | Typing | Fullscreen | Transport | Popup guard | Image guard | Cursor/pointer | Idle/dim |
|---|---|---|---|---|---|---|---|---|
| **Key handler branches** | -- | H (keypad open/close) | H (fullscreen branch) | H (transport branch) | L | L | H (tap dispatch) | M (noteUserInput) |
| **`interactionSerial` semantics** | H | H | H | H | L | L | M | M |
| **`pageEpoch` semantics** | M | H (edit queue) | M | H (stale seeks) | L | L | L | M (heartbeat) |
| **tv_ui.js field protocol** | M | -- | L | L | L | L | L | L |
| **page_helpers.js `__fb` API** | L | L | H (fullscreen intercept) | H (seek/play commands) | L | L | M (poke) | H (state/heartbeat) |
| **postMessage bridge** | L | L | M | H (cross-origin commands) | L | L | L | M (state merge) |
| **popup_guard.js kill rules** | L | L | H (player exemption) | M (dead seek bridge if player killed) | -- | L | L | L |
| **flixer_image_guard** | L | L | L | L | L | -- | L | L |
| **Cursor hover / pointer lift** | M (tap dispatch) | L | H (controls autohide) | L | L | L | -- | M (heartbeat gate) |
| **Pointer poke bound** | L | L | H (YouTube vs CineJoy) | L | L | L | M | L |
| **OSD hit-test** | H (seek vs tap) | L | M (fullscreen OSD) | H (seek fraction) | L | L | L | L |
| **SeekRepeatPolicy** | L | L | L | -- | L | L | L | L |
| **Fullscreen intercept (JS)** | L | L | -- | M (auto-resume) | L | L | M | L |
| **`shouldInterceptRequest`** | L | L | L | L | L | H (allowlist) | L | L |
| **Idle/dim policy** | M (wake key) | L | L | L | L | L | L | -- |
| **DiagnosticLog** | L (diagKey) | L (edit_result) | L | L | L | L | L | L |

### The five shared-code-path clusters (where regressions actually come from)

1. **Key handling <-> everything.** One decision tree serves browse, fullscreen, keypad, OSD, menus. Any branch change re-routes keys in other states. Past regressions: ENTER-as-press vs ENTER-as-submit (fixed 2026-10-18 by the GO-key check in `Keypad.handleKey`); transport keys consumed by the OSD seek bar (by design -- `tapAtCursor` hit-tests first).
2. **Focus <-> typing <-> IME.** Input focus is shared with the system IME (FireTVIME): synthetic key events reach the page only while the WebView holds focus; focusing a text field hands focus to the IME, so injected typing and ENTER get eaten. `TvWebView.onCheckIsTextEditor() = false` is what keeps the system keyboard out. Check live state with `dumpsys input_method | grep -iE 'mCurFocusedWindow|mInputShown'` before blaming the keypad.
3. **Pointer state <-> player autohide.** The parked pointer (2.8) and the poke (2.9) both write pointer state that players read. A change to either can fix one player type and break another -- the bound (3 pokes) and the playing-only lift are the two load-bearing numbers.
4. **DOM walkers <-> GPU.** popup_guard's full walk and the image guard's img walk are the only continuous DOM work. On flixer they are restricted (minimal scan, 2 s sync) because the full walk crashed PowerVR during the intro animation. Any new DOM work must follow the flixer pattern: restricted element set, cheap rules, slow interval, no observer.
5. **Async callbacks <-> page generation.** Every eval result, posted runnable, and coalesced UI callback must check `pageEpoch`/`interactionSerial`. A new async path that skips the check is a future ghost-action bug.

### Regression history (fix-one-breaks-another timeline)

From git history + docs (verified in part6):

| Date | Fix | What it broke | Resolution |
|---|---|---|---|
| 2026-10-16 | Extended image firewall beyond flixer | Coreflix quality button stopped opening (off-screen image load events drive UI) | Reverted; guard stays flixer/cinejoy-only |
| 2026-10-16 | flixer typing guard removed (tv_ui host return) | (risk) PowerVR intro crash could return | Accepted tradeoff; flixer now uses the identical typing path |
| 2026-10-18 | ENTER/GO submit in keypad | -- | GO-key check in handleKey; label "ENTER" in page-edit, "GO" in prompts |
| 2026-10-18 | `reactOnClick` direct React call | Double-activation when the real click also arrives (play->pause, menu flash) | 300 ms deferred timer + capture-click cancel |
| 2026-10-20 | Cross-frame video command bridge | -- | `'frame'` = success; flixer excluded; `dispatchToPage` always webView |
| 2026-10-20 | CSS fullscreen intercept | (a) `webkitEnterFullscreen` on the wrong prototype; (b) area-walk heuristic returned bare body-child | Patch both prototypes; button-containing-ancestor primary signal |
| 2026-10-21 | Text-based ad rule in popup_guard | Killed movie synopses/page titles, hid Play button (31 false positives) | Removed; structure-only rules |
| 2026-10-21 | High-z kill rule (z >= 1,000,000) | Killed coreflix's fullscreen player iframe (same max z) -> black screen + dead seek bridge | Player-iframe + container-chain exemption (`e6c984f`) |
| 2026-10-21 | Parked pointer pinned player controls | "Controls hide the first time, not the second" | `liftPointerIfPlaying()` -- HOVER_EXIT only while playing (`217a88a`) |
| 2026-10-21 | TMDB virtualize() wired into sync() | -- | Off-screen posters src-stripped; guard extended to cinejoy (`1bc1ecd`) |
| 2026-10-20 | (crash) "child already has a parent" x2 | app restart (watchdog) | Open issue -- see Part 8 |

**The pattern:** almost every regression came from a rule that was true for the site being fixed but not for another site (text = ad? max-z = ad? full-DOM walk = safe? continuous poke = helpful?). The general rule: **any rule that classifies page content must be structure-scoped, host-scoped where the problem is host-specific, and tested on a second site with a different player type.**

---

## Part 4 -- Invariants & DO-NOT-TOUCH list

Code that looks like patch-on-patch but is load-bearing. Removing or "simplifying" any of these recreates a past regression.

1. **`pageEpoch` + `interactionSerial` checks** (35 + 31 refs in MainActivity). The invalidation architecture. A 2026-10-18 audit found 0 TODO/FIXME, 0 commented-out code, 13 swallowed exceptions (all intentional).
2. **`'frame'` = success** in video command return values. No key fallback for `'frame'`.
3. **`dispatchToPage` always targets the webView.** The fullscreen custom view is a render surface only.
4. **The 2000 ms delayed eval in `onShowCustomView`** (synchronous eval there returns `"none"` -- the WebView is mid-transition).
5. **Both prototypes patched** for the fullscreen intercept (`HTMLVideoElement` AND `HTMLMediaElement`, whichever has `webkitEnterFullscreen`; plus `document.webkitExitFullscreen`).
6. **Button-containing-ancestor** as the primary player-container signal (area walk is fallback only).
7. **Flixer exclusions:** fullscreen intercept excluded; popup_guard uses the minimal scan; no MutationObserver/full-DOM walks/continuous timers on flixer.
8. **Player-iframe + container-chain exemption** in every high-z/viewport kill rule. A z-index threshold alone is NOT a safe ad signal.
9. **Structure-only ad detection.** Text is not an ad signal.
10. **300 ms deferred `reactOnClick`** + capture-click cancel. No DOM-walk `__buccaneerHandled` mark (broke all buttons except play/pause).
11. **Pointer-lift only while playing** (fresh `playbackHeartbeatAt` < 12 s).
12. **Poke bound ~ 3.** Continuous poking is wrong for every player type.
13. **Field-identity recording on keyboard open** (`inputFrame` + `inputFieldId`); edit path checks `activeInputFrame()`.
14. **`onPageCommitVisible`** as the page-ready signal (not `onPageFinished` -- unreliable on fmhy).
15. **Image firewall scoped to `isImageGuardedHost()`** (flixer + cinejoy). `onFlixerPage` stays flixer-only.
16. **Timer-based long-press** (500 ms), not key repeats. Key-up clears the pending flag before firing the short action.
17. **`TvWebView.onCheckIsTextEditor() = false`** -- the system IME stays out of the typing path.
18. **OSD hit-test before tap dispatch** in `tapAtCursor`.
19. **No CSS filters** (`filter:invert`) -- known PowerVR crasher. Dark mode is the two injection sites (MainActivity `injectDarkMode` + page_helpers.js document-start), both using non-filter techniques.
20. **No `??` / `?.` in injected JS** -- Chromium 118 on API 25.
21. **Prefs format:** no `android:` namespace prefix in SharedPreferences XML.
22. **Protected hashes:** editing `popup_guard.js`, `flixer_image_guard.js`, `page_helpers.js`, or the marker-delimited MainActivity.kt regions requires updating `tests/protected-fixes.json` and passing `tests/check-protected-fixes.py`.
23. **`importBookmarkText`** -- zero call sites in main, load-bearing for `Regression171Test` (reflection).
24. **Web SQL** is a memory lever, not a speed lever -- never present it as a performance improvement; localStorage/IndexedDB/cookies are separate and unaffected.
25. **The watchdog is the only recovery from native GPU crashes.** No app-side fix exists for the PowerVR driver bug; report it honestly.

---

## Part 5 -- Live verification evidence (2026-10-22)

Everything in this section was observed on the real device (Fire TV AFTMM, <device-ip>:5555, app 2.3.1/versionCode 24) during a real viewing session (novahd.cc -> vidbolt.xyz player, plus movy.sx, toflix.co, flixer.gd, cinejoy.pk in the same session). This is the ground truth the code analysis was checked against.

### 5.1 Bridge surface (CDP, main world of the top frame)

`window.__fb` -- 28 members, live:
`cancelFullscreen, exitCssFullscreen, cssFullscreenActive, selectAt, focusTarget, setDiagnosticLogging, togglePlay, play, playBlocked, pause, seek, volume, toggleMute, unmute, muteButton, playerControls, tapStack, tapStackAll, playerGeometry, frameCenter, diag, state, prepareFullscreen, field, focusIntoView, clear, setQualityPreference`

`window.__tvUi` -- 8 members, live:
`scrollAt, fitMenus, restoreMenus, reportField, fieldState, edit, type, playback`

`__fb.state()` live value: `{"f":1,"p":1,"t":0,"d":0,"h":0,"v":1,"m":0}` -- matches the video actually playing inside the cross-origin player frame (verified by isolated-world probe: `paused:false`). **This proves the cross-frame state merge works on a real cross-origin player.**

### 5.2 Cross-origin player anatomy (novahd.cc -> vidbolt.xyz)

- Frame tree: top frame `novahd.cc/watch/s/288673/1/4` + Cloudflare Turnstile iframe (`challenges.cloudflare.com/.../cf-chl-widget-*`) + player iframe `vidbolt.xyz/tv/288673/1/N?theme=00A8E1&autoPlay=true`.
- The player iframe is the CSS-fullscreen element: `IFRAME.cls="absolute inset-0 z-0 h-full w-full border-0"`, 960x540 filling the 960x540 window.
- Inside the vidbolt frame (isolated-world probe): one `<video>`, 960x480, `src=blob:https://vidbolt.xyz/...` (MSE), playing. **No** HLS.js/Shaka/Bitmovin/jwplayer/videojs on window -- a fully custom player (`vb-*` classes: `vb-big-play`, `vb-pill`, `vb-seekbtn`, `vb-play`, `vb-epbtn`, `vb-skip`, `vb-season`, `vb-ep`).
- Consequences (all consistent with the code): the quality scanner cannot reach this player (no window instance); `playerControls` from the top frame correctly reports "no control bar with 3+ buttons" (controls are inside the opaque iframe); all transport must go through the `__fbCmd` bridge or taps.
- **CDP subtlety verified:** `typeof window.__fb` is `undefined` inside an isolated world of the player frame even though the helper is alive there (isolated worlds share the DOM, not main-world JS globals). `media_health` events with `pageUrl: https://vidbolt.xyz/`, `topFrame: 0`, `frameKind: cross-origin-or-opaque` are the proof the helper runs in that frame.

### 5.3 Live diagnostic event catalog (one real session, 5889 events)

High-frequency: `net_segment` (2066 -- MSE segment URLs; note: one CDN `sacdn.hakunaymatata.com` dominated -- the network probe can be spammy on segment-heavy sites and eats the 2 MiB rotation budget), `media_health` (1100 -- the playback heartbeat), `key` (883, with `handler` branch), `console` (512 -- page console warnings, e.g. Cloudflare's `Permissions-Policy: Unrecognized feature: 'hid'`), `event_burst` (502 -- dedupe of console spam), `process_memory` (228 -- PSS ~92-98 MB, healthy).

Behavioral (each confirms a subsystem working on real sites):
- `tap_dispatched` (33) + `tap_stack` (33) + `click` (32, with `target`, `inPlayerUi`) -- the tap path
- `edit_result` (7, `status: applied`) + `keypad_go` (`via: enter`) -- the typing path
- `pointer_lifted` (12) -- the pointer-lift fix firing
- `css_fullscreen_on`/`css_fullscreen_off` (2 each) -- the CSS fullscreen path
- `overlay_killed` (1: `hero-layer is-ready` DIV, reason `covers_video`) -- **watch item**: a "hero-layer" was killed by the covers-video rule; if users report a hidden Play button on that site class, this is the first event to check
- `floating_seen` (3: DIV z=200 fixed, text "2026|1 season...") -- popups that slipped through, recon-logged
- `sandbox_removed_early` (36, Cloudflare challenge frames) -- sandbox handling on Turnstile iframes
- `image_firewall`/`flixer_image_firewall` (`active: true` on flixer.gd) -- the guard active
- `frame_script_alive` (25, incl. `topFrame: 0` on the challenge frame) -- helpers alive in child frames
- `media_owner_started`/`stopped` (14 each, with frame id) -- media ownership tracking
- `http_error` (1: toflix.co 429) -- rate-limit visibility
- `user_navigation` (4: movy.sx) -- user-initiated navigations
- `flixer_render_reset` (1, `from: www.movy.sx`) -- leaving flixer

### 5.4 Crash forensics (from `diagnostic_critical.jsonl`)

Two uncaught `IllegalStateException: "The specified child already has a parent. You must call removeView() on the child's parent first."` on the **main** thread, 2026-10-20 22:31:04 and 22:31:38 UTC (34 s apart). The `CrashLog` handler records then rethrows -> process died -> watchdog restarted -> session id recovered from prefs (same `sessionId` b28af98a spans the crash, confirmed by matching `elapsedMs` deltas).

Analysis: the only dynamic `addView` in the app is `fullscreenBox.addView(view)` in `enterFullscreen` (L2581). The `customView != null` guard covers the common double-request, but a path where the incoming view already has a parent while `customView` is null (e.g. a WebView-internal view reuse across a renderer recovery, or a second `onShowCustomView` after an abnormal teardown) still throws.

**Context from `shared_prefs/settings.xml` (pulled live):** `lastCrashUrl: https://pantyflix.com/`, `watchdogRestartCount: 1`, `cleanExit: false`. pantyflix.com serves the same content as novahd.cc (same episode id 288673 in the live session) -- the user was switching episodes (URL went `/1/4` -> `/1/6` during the session). Episode switches on these sites re-enter fullscreen with a new video element. Working hypothesis: the crash trigger is a fullscreen re-request during an episode switch where the previous custom view's teardown had not completed -- i.e. `onShowCustomView` arriving with a view that still has a parent while `customView` was nulled by an intervening path. **Inferred, not proven** -- the diagnostic context rotated away. Reproduce by switching episodes in fullscreen on a cross-origin-iframe player site while watching the logcat tombstone.

---

## Part 6 -- Known limitations (platform, not bugs)

1. **PowerVR GPU driver crashes** (SIGSEGV in `libIMGegl.so`, `KEGLReleaseRenderTarget`, RenderThread) -- vendor bug, no app-side fix. The watchdog restarts automatically. The image guard reduces texture pressure (crash probability), not the bug itself.
2. **Chromium engine null-pointer crashes** (`CrRendererMain`, frames only in `libwebviewextchromium.so`) -- engine bug; handled by renderer recovery.
3. **Cross-origin iframe opacity** -- a frame cannot reach into a cross-origin child's DOM/window. Players nested 2+ levels deep are controllable only via the postMessage bridge (if the frame runs our helper) or the player's own UI.
4. **CDP from the host works** (raw-socket WebSocket, no Origin header, masked client frames) -- in-page inspection is available; the app's diagnostic JSONL is still the better tool for key-routing and app-side state.
5. **Cloudflare Turnstile fingerprints the engine**, not just the UA -- the Fire TV WebView is Amazon's fork (`com.amazon.webview.chromium/118.amazon-webview-...-tv`); a UA override alone can't pass a live challenge. If the challenge script is alive, receives the click, runs ~10 s, then 403 -> the failure is at the solve/fingerprint stage.
6. **`onPageFinished` unreliable on fmhy** -- use `onPageCommitVisible`.
7. **adb `input keyevent` codes are not what you expect on this device** (91->MUTE, 92->PAGE_UP, not media rewind) -- use the real remote or the diagnostic `key` events as ground truth.
8. **`install --no-cache-data` unsupported on Android 7.1** -- the equivalent is `pm clear` + `install -r` (wipes data).
9. **Debug APKs carry the DEBUGGABLE flag** -> Fire TV launcher icon quirk (icon disappears after install, returns after reboot); release builds lose `run-as` (no diagnostics, no data rescue).
10. **Web SQL** -- disabling it frees a few MB on sites that use it; it is a memory lever, not a speed lever.
11. **Software rendering** -- breaks all page interaction (`tap_stack` count 0, no click events) and does NOT prevent GPU crashes. Never a fix.

---

## Part 7 -- Open issues (for the next coding session)

1. **"Child already has a parent" main-thread crash** (Part 5.4). Crashed on pantyflix.com, likely during an episode switch in fullscreen (working hypothesis -- see 5.4). Candidate fix: in `enterFullscreen`, before `fullscreenBox.addView(view)`, do `if (view.parent != null) (view.parent as? ViewGroup)?.removeView(view)` -- mirroring the defensive pattern already used in `destroyBrowser` (L293, L308). Also audit whether `leaveFullscreen` can be skipped on a renderer-recovery path (stale `customView` pointing at a view whose parent changed). **Do not ship without reproducing** -- the crash context (diagnostic log) rotated away, so the exact trigger is inferred, not proven.
2. **`overlay_killed` on `hero-layer`** (Part 5.3). One event in the live session: a `hero-layer is-ready` DIV killed by the `covers_video` rule. If the user reports a hidden Play button on that site class, check whether the hero layer contains the play control (the `hasControls` protection may not cover it if the control is an iframe or a non-standard element).
3. **`net_segment` volume** -- 2066 events in one session from a single CDN; the 2 MiB rotation means other evidence gets pushed out on segment-heavy sites. Consider a per-host cap or coalescing (e.g. count + first/last URL per host per minute) -- a diagnostics-only change, low risk.
4. ~~**`cssFullscreenActive` returned `{}`**~~ -- **resolved during review:** it is a *function* (`page_helpers.js` L652: `cssFullscreenActive: function () { return cssFs.active ? 1 : 0; }`), so evaluating it without calling it returns `{}` under CDP `returnByValue`. Not a bug. Java tracks its own `cssFullscreenActive` boolean, updated from the bridge callback (MainActivity L1325-1326).

---

## Part 8 -- Verification cookbook

### 8.1 The standard loop (one hypothesis per install)

```bash
cd <repo-root>
source <android-env>/env.sh
gradle assembleDebug 2>&1 | tail -5        # if it fails, re-run WITHOUT the pipe -- the e: line is the error
adb -s <device-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-ip>:5555 shell am force-stop com.buccaneer.browser   # a new APK doesn't run until the process restarts
```

Then: user tests -> pull diagnostics -> read the events -> keep or revert (`git checkout <file>`). Never stack untested changes.

### 8.2 Diagnostics

- Enable: `shared_prefs/diagnostics.xml` -> `<boolean name="enabled" value="true" />` (NO `android:` prefix), then force-stop + relaunch (the `files/` dir is created only at startup when enabled).
- Pull: `adb shell "run-as com.buccaneer.browser cat files/diagnostic_session.jsonl"` (debug builds only; release builds refuse `run-as`).
- Crash log: `adb shell "logcat -d -b crash"` -- filter for `com.buccaneer.browser`. JSONL is UTC, logcat is device-local (7 h offset).
- A newly installed APK doesn't run until restart -- check `session_start` metadata `appVersion` before blaming a fix.
- `run-as` also fails if `files/` doesn't exist (diagnostics never enabled) -- check for a `logging_disabled` event first.

### 8.3 CDP (verified working 2026-10-22)

```bash
PID=$(adb -s <device-ip>:5555 shell pidof com.buccaneer.browser)
adb -s <device-ip>:5555 forward tcp:9222 localabstract:webview_devtools_remote_$PID   # re-forward after every app restart (socket is per-pid)
curl -s http://127.0.0.1:9222/json    # page targets + webSocketDebuggerUrl
```

Client requirements: HTTP/1.1 upgrade with `Upgrade: websocket` + `Sec-WebSocket-Key`, **NO Origin header** (403 otherwise), **client frames MUST be masked** (RFC 6455 -- unmasked frames are silently dropped, looks like a hang), server->client frames unmasked. A working client lives in the scratch dir as `cdp.py` (~100 lines: handshake, masked send, `_recv_frame`, event buffering).

Key calls: `Runtime.evaluate` (returnByValue, contextId), `Page.getFrameTree` (frames nest under `frameTree.frame` / `childFrames[].frame`), `Page.createIsolatedWorld(frameId, worldName)` -> executionContextId -- this is how you inspect a CROSS-ORIGIN iframe (executionContextCreated events do NOT replay on attach). **Isolated worlds share the DOM but not main-world JS globals** -- `window.__fb` is invisible from an isolated world; use the app's own diagnostic events (`media_health`, `frame_script_alive`) to prove a helper is alive in a frame.

### 8.4 Tests

**Current status (verified by execution 2026-10-21/22 -- do not treat failures as caused by your edit; several are pre-existing staleness):**

| Check | Command | Status |
|---|---|---|
| Protected fixes | `python3 tests/check-protected-fixes.py` | **PASS** |
| Warning repairs | `python3 tests/check-warning-repairs.py` | **FAIL (pre-existing)** -- stale 1.9.1 hardware-baseline hash for `flixer_image_guard.js`; dies at its line 13 before steps 5-11 run |
| Node .cjs (seek-click, fmhy-theme, release18-script, seek-mapping-integration) | `node tests/<name>` | **PASS** |
| Node .cjs (quality-tests, logic19-tests, quality199-startup, integration-pass-tests) | `node tests/<name>` | **FAIL (stale vs v2.x assets)** -- reference functions removed from the assets (`pickCapLevel`, `wrapHlsConstructor`, `capHls`, `capShaka`) |
| cinejoy199-quality | -- | **CANNOT RUN** -- its asset `cinejoy_quality.js` was removed from the tree |
| Headless-Chromium runners (5) + Playwright DOM tests (3) | -- | **CANNOT RUN here** -- no chromium/playwright in this environment |
| Kotlin unit tests (15 classes) | `gradle test` (with `source <android-env>/env.sh`) | not run in this review; CI does not run them (builds release APK on tags only) |

- `python3 tests/check-protected-fixes.py` -- hash-pins `popup_guard.js`, `flixer_image_guard.js`, `page_helpers.js` (whole-file SHA-256) + marker-delimited regions of `MainActivity.kt`. **Editing a pinned file requires updating its hash** (MainActivity entries use `after`/`before` marker strings -- the marker text must match the current comment).
- Before building, run the protected-fixes check; if it fails, distinguish pre-existing failure from your own edit by re-checking the assertion against the untouched tree (`git stash` + re-run).
- `repair199-baseline.json` is a historical record only (its check was removed 2026-10-21).

### 8.5 Device state checks

```bash
adb -s <device-ip>:5555 shell dumpsys package com.buccaneer.browser | grep -E 'versionName|versionCode'
adb -s <device-ip>:5555 shell dumpsys window | grep mCurrentFocus
adb -s <device-ip>:5555 shell dumpsys input_method | grep -iE 'mCurFocusedWindow|mInputShown'
adb -s <device-ip>:5555 shell dumpsys meminfo com.buccaneer.browser | grep Graphics
adb -s <device-ip>:5555 shell "run-as com.buccaneer.browser cat shared_prefs/settings.xml"
```

Second test device: TVision HUB v2 (Android 11) at <device2-ip>:5555 -- release-signed APK (no `run-as`); debug builds require uninstall first (wipes data -- ask first).

### 8.6 Verifying a fix actually ran

1. `session_start` metadata `appVersion` in the JSONL matches the installed version.
2. The app process was restarted after install (new pid).
3. The specific diagnostic events for the changed path appear with the expected payloads.
4. For crash-recovery changes: prove the restart happened -- `ps` shows main + `:watchdog`, kill the main process as the app's UID (`run-as com.buccaneer.browser kill -9 <pid>`), confirm a NEW pid + activity focused again.

# Part 9 -- Documentation: verified claims inventory

Method: every concrete claim in the current docs was re-verified against the live source tree (v2.3.1, git HEAD `a390764` + uncommitted delta, see S9.3) on 2026-10-21/22 via grep/line-count/execution, plus live CDP checks on the Fire TV. 95 automated checks: **92 passed outright; the 3 "failures" resolved as 1 genuinely stale doc claim, 1 false-positive (comment matched), 1 check-script artifact (verified live instead)**.

## 6.1 Doc inventory and reliability

| Doc | Status | Reliability |
|---|---|---|
| `HANDOFF-full-program.md` (718 L) | Current (written for v2.3.1, corrected once at `a390764`) | **Highest.** Architecture, subsystems, regression map (S5), hard constraints (S6), protected-file gate (S7), symptom->diagnosis (S12). Two errors found in this review (S9.2). |
| `README.md` | Current (v2.3.1) | High for user-facing facts (menu = 14 items, settings = 7 items -- both match code). |
| `TESTING.md` | Current (added `480f62e`) | High. The interdependency map + per-site regression checklist. One gap: missing the `viewport_catcher` reason (S9.3). |
| `SECURITY.md` | Current | Short; keystore/secret handling. |
| `RELEASE_2.3.1_NOTES.md`, `RELEASE_2.2_NOTES.md` | Release notes | Verified claims: YouTube auto-hide skip v, `allowfullscreen` on player iframes v, start page 2x5=10 tiles v, minSdk 22 / targetSdk 33 v (build.gradle.kts). |

| `HANDOFF.md` (root, 2.2 era) | Superseded by HANDOFF-full-program.md | Use only for history. |

## 6.2 Verified claims (spot-checked, all PASS)

- Line counts: MainActivity.kt **3956** v, page_helpers.js **1911** v, tv_ui.js **335** v, flixer_image_guard.js **286** v, seek_click.js **72** v, fmhy_theme.js **23** v. (popup_guard.js: see S9.3 -- the doc's 434 was correct *at HEAD* but the working tree is now 538.)
- All **19 `@JavascriptInterface` bridge methods** named in the doc exist in MainActivity.kt v.
- `__fbCmd`/`__fbRes` postMessage protocol + ancestor-source check v; `frameIsFlixer` v.
- `__fb.seek` returns `ok`/`frame`/`none`/`failed`; Java treats `'frame'` as success v.
- `diagKey(code, branch)` + `key` event with `handler` field v.
- Function line numbers (navigateUrl ~1514, dispatchKeyEvent ~1670, enterFullscreen ~2570, etc.) -- all confirmed present (+-a few lines) v.
- `TvWebView.onCheckIsTextEditor() = false` v.
- SeekRepeatPolicy: first press 10 s, +30 s after 600 ms hold, +60 s after 2500 ms v (read directly).
- Diagnostic events emitted: `overlay_killed`, `floating_seen`, `pointer_lifted`, `media_health`, `edit_result`, `player_geometry`, `frame_script_alive`, `process_start`, `react_onclick_called` (popup_guard.js L88) v.
- **`seek_result` / `seek_fallback` are NOT emitted anywhere** in Kotlin or JS v -- this settles the internal contradiction in HANDOFF-full-program.md (S5.3 vs S6 line ~462): **S6's citation of `seek_result`/`seek_fallback` is the stale/phantom one**; S5.3 and S12 are correct. Do not look for those events in logs.
- z-index `2147483647` in CSS-fullscreen intercept v.
- popup_guard structural rules (floating iframe z>=1000 <25% viewport; covers >=10% of video; z >= 1,000,000) v.
- `tests/protected-fixes.json` + `check-protected-fixes.py` exist and **PASS** v.
- All 8 cited commit hashes exist in git history v.
- `importBookmarkText` referenced by Regression171Test.kt v.
- **No `?.` or `??` in any injected JS** (0 real occurrences in all 5 injected assets; the only match is a comment in flixer_image_guard.js L12 stating the constraint) v.
- `pageEpoch` / `interactionSerial` exist v.
- `form.requestSubmit()` in tv_ui.js submit path v.
- `virtualize()` at top of `sync()` in flixer_image_guard.js v.
- `liftPointerIfPlaying` gated on playback v.
- `onFlixerPage` vs `onImageGuardedPage` distinct v.
- `shouldInterceptRequest` 1x1 firewall for `image.tmdb.org` v.
- Watchdog separate process, reopens `lastCrashUrl` v.
- `webkitEnterFullscreen` on `HTMLVideoElement.prototype` v.
- **Live CDP:** page viewport is exactly **960x540 CSS px at devicePixelRatio 2** (screen 1920x1080) -- confirms the doc's viewport claim, which is set by `useWideViewPort=true` + `loadWithOverviewMode=true` (MainActivity L735-736), not by any hardcoded 960x540.

## 6.3 ! UNCOMMITTED DELTA -- the tree is ahead of git HEAD

`git status` shows two modified, **uncommitted** files (as of 2026-10-22):

1. **`app/src/main/assets/popup_guard.js`** (+118/-8 lines, 434 -> 538):
   - **New `viewport_catcher` rule** in `killOverlays`: a positioned element covering **>=90% of the viewport** at **z >= 1000** on a page where `covers_video` cannot fire (no `<video>` yet -- home/show/season/pre-roll pages). Targets ad injectors that use z=9999/1000 full-screen click catchers (seen on coreflix/nippleflix and movy, 2026-10-22). Player + its container chain exempt (`isPlayerChain`). New `overlay_killed` reason value: **`viewport_catcher`** (in addition to `floating_iframe` / `high_z_ad` / `covers_video`).
   - **New `killCatchers()` -- minimal flixer scanner.** The full-DOM `killOverlays` walk (querySelectorAll over every div/span/a/iframe) triggers the PowerVR driver crash during flixer's intro, so the heavy scanner stays OFF on flixer -- but flixer still gets inline ad click-catchers that the `window.open` guard cannot catch. `killCatchers` scans **only body's direct children + iframes**, applies only the cheap rules (floating iframe ad; full-viewport catcher at z >= 1000), exempts the **main player iframe** (largest iframe with a real, non-blank, non-srcdoc src) and its container chain, and skips elements containing a `<video>` or real controls (`button, [role=button], input, select`). Runs immediately + every **5 s**, **no MutationObserver**.
   - Host branch flipped: flixer -> `killCatchers` (5 s interval); all other hosts -> full `killOverlays` + debounced MutationObserver + slow interval (unchanged).
2. **`tests/protected-fixes.json`** -- popup_guard.js hash updated to match the new file (the protected-fixes check still PASSES).

**Consequences for future sessions:**
- The "current" popup guard has **four** kill reasons: `floating_iframe`, `high_z_ad`, `viewport_catcher`, `covers_video`. TESTING.md and HANDOFF-full-program.md list only three -- update them when this delta is committed.
- Any future popup-guard change must preserve: the flixer minimal-scan split (PowerVR), the player-iframe exemption (both scanners), the containsVideo/hasControls protections, and the `data-fslogged` once-per-element reconnaissance logging.
- **Commit this delta** (or consciously revert it) before the next release build -- a release built from the tree will ship it, but git history will not explain it.

## 6.4 Known doc gaps / corrections (for the next doc update)

1. HANDOFF-full-program.md S6 line ~462 cites `seek_result`/`seek_fallback` -- **phantom events, never emitted** (verified by full-tree grep). S5.3/S12 are correct.
2. popup_guard.js line count 434 -> 538 (uncommitted delta, S9.3).
3. `viewport_catcher` is a fourth `overlay_killed` reason not listed in TESTING.md/HANDOFF.
4. README's "tap the on-screen seek bar" line is ambiguous: the *native* seek bar is display-only; the line refers to the player's in-page seek bar (or the OSD). Keep the distinction explicit in any rewrite.
5. Historical designs (CSS dark-mode injection, repair199 baseline, 1.9.x menu layout) are no longer documented in this repository.

---

## Part 10 -- Line-level reference index

The exhaustive per-function analysis (every line of every file, with line numbers and per-file invariants) lives in the companion files in `docs/review/`. Use this index to jump straight to the file you're about to touch.

| You are changing... | Read first |
|---|---|
| `MainActivity.kt` lines 1-1350 (fields, lifecycle, WebView setup, Client/Chrome callbacks) | `docs/review/part1a-mainactivity.md` |
| `MainActivity.kt` lines 1351-2699 (bridge tail, navigation, key routing, pointer/tap, video commands, OSD, fullscreen) | `docs/review/part1b-mainactivity.md` |
| `MainActivity.kt` lines 2700-3956 (keypad, page-edit queue, menus, bookmarks, settings, diagnostics, watchdog) | `docs/review/part1c-mainactivity.md` |
| `page_helpers.js` (the `__fb` bridge, cross-frame protocol, quality scanner, network probe, fullscreen intercept, flixer guard) | `docs/review/part2-page-helpers.md` |
| `tv_ui.js` / `seek_click.js` / `fmhy_theme.js` | `docs/review/part2b-tv-ui.md` |
| `popup_guard.js` / `flixer_image_guard.js` (kill rules, thresholds, PowerVR risk) | `docs/review/part3-guards.md` |
| `DiagnosticLog.kt` / `Keypad.kt` / `Osd.kt` / `CursorView.kt` / `TvWebView.kt` / `NativePageEditor.kt` | `docs/review/part4a-support.md` |
| `BookmarkStore.kt` / `BookmarkFiles.kt` / `BookmarkJobs.kt` / `StartPage.kt` | `docs/review/part4b-support.md` |
| `PopupGuard.kt` / `Watchdog.kt` / `CrashLog.kt` / `CrashRecoveryPolicy.kt` / `NavigationHandoff.kt` / `UiCoalescer.kt` / `JsonInputBudget.kt` / `IdleDimPolicy.kt` / `Panels.kt` / `PointerTap.kt` / `SeekRepeatPolicy.kt` | `docs/review/part4c-support.md` |
| Any test, or the protected-fixes hash mechanism | `docs/review/part5-tests.md` |
| Trusting (or doubting) any project doc | `docs/review/part6-docs.md` (Part 9 of this document) |

**Source file map (v2.3.1 tree):**

| File | Lines | Role (one line) |
|---|---|---|
| `MainActivity.kt` | 3956 | God class: lifecycle, key routing, both bridges, WebView clients, navigation, fullscreen, video commands, OSD, cursor, keypad, page-edit queue, menus, bookmarks, settings, diagnostics, watchdog wiring |
| `page_helpers.js` | 1911 | `__fb` bridge in every frame: video state/commands, cross-frame postMessage forwarding, quality scanner, network probe, CSS-fullscreen intercept, pointer poke, media health |
| `tv_ui.js` | 335 | `__tvUi` bridge: field reporting/editing protocol, menu fitting, playback probe |
| `popup_guard.js` | 538 (uncommitted) | Ad/popup suppression: window.open block, target=_blank rewrite, overlay kill rules (4 reasons), flixer minimal scanner, reactOnClick workaround |
| `flixer_image_guard.js` | 286 | TMDB poster virtualization on flixer + cinejoy; 1x1 firewall coordination |
| `seek_click.js` | 72 | Seek-bar click repair, called on demand from Java |
| `fmhy_theme.js` | 23 | fmhy.net theme class toggles |
| `DiagnosticLog.kt` | -- | JSONL session log: rotation, queue, redaction, critical events, zip export |
| `Watchdog.kt` | 91 | Out-of-process crash recovery (binder death -> relaunch lastCrashUrl) |
| `CrashLog.kt` | -- | Uncaught-exception handler: log + rethrow |
| `CrashRecoveryPolicy.kt` | -- | Crash-window accounting (restart budget) |
| `NavigationHandoff.kt` | -- | Cleanup->commit navigation flow |
| `PopupGuard.kt` | -- | Native side of the popup guard (redirect blocking policy) |
| `UiCoalescer.kt` | -- | Coalesces rapid UI updates (OSD, cursor) |
| `JsonInputBudget.kt` | -- | Caps page-controlled JSON fed back to Java |
| `IdleDimPolicy.kt` | 11 | 20-min idle dim; never while playing |
| `Panels.kt` | 253 | MenuPanel / ListPanel / TextPanel native UI |
| `PointerTap.kt` | 22 | Real MotionEvent construction (trusted input) |
| `SeekRepeatPolicy.kt` | 20 | Hold-time-based seek step sizing (10/30/60 s) |
| `Keypad.kt` | -- | On-screen QWERTY keypad (5x2 grid, GO/SHIFT semantics) |
| `Osd.kt` | -- | OSD rendering + `seekFractionAt` hit-test |
| `CursorView.kt` | -- | On-screen pointer |
| `TvWebView.kt` | -- | `onCheckIsTextEditor()=false` (suppresses system IME) |
| `NativePageEditor.kt` | -- | Chromium input-thread text insertion (JS-first fallback) |
| `BookmarkStore.kt` | 416 | Bookmarks persistence (JSON in prefs, transactional import/undo) |
| `BookmarkFiles.kt` | 57 | Public-Downloads export/import transport |
| `BookmarkJobs.kt` | 31 | Single-slot background bookmark worker |
| `StartPage.kt` | 300 | Launch page: native 2x5 grid of bookmarks/folders |
| `BuccaneerApplication.kt` | -- | Application class (CrashLog install) |
| `Ui.kt` | -- | Density-scaled UI units + widget helpers |
| `Colors.kt` | -- | Color constants |

**Test map:** see `docs/review/part5-tests.md` -- 15 Kotlin test classes, 6 Node .cjs suites, 5 headless-Chromium runners, 3 Playwright DOM tests, 2 Python gate scripts. Current status: `check-protected-fixes.py` **PASSES**; `check-warning-repairs.py` fails on a stale 1.9.1 baseline hash (pre-existing); 4 of 6 Node .cjs suites fail on stale references to removed v1.x functions (pre-existing).
