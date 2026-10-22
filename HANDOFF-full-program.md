# HANDOFF — Full Program Understanding (for a new session)

**Purpose.** This handoff exists so a new session can go through the entire
BuccaneerClaude program and *truly understand* it — what each part does, how it
works with every other part, what "push and pull" happens when you change
anything, and how one fix can break something else. The end goal: future coders
make fixes that do **not** regress other areas.

**Your job when you pick this up:**
1. Read this whole document first. It is the map, the interdependency graph, and
   the regression-risk ledger.
2. Then do the file-by-file reading plan in **§9**. Do not skip it — the map here
   tells you *where to look and what to ask*, but you must read the actual code to
   own it.
3. As you read, keep the interdependency map (§5) in your head. For every function
   you read, ask: "what does this depend on, and what depends on this?"
4. Before you change anything, run the regression checklist (§10) after the change.

**The single most important idea in this program:** the features are *not*
independent. They share code paths — key handling, focus, pointer/hover events,
the DOM, and the fullscreen state. A change in one subsystem ripples into the
others. That is why we keep hitting the "fix one thing, break another" loop. This
document is the antidote: it names the shared paths and the known failure modes so
you design fixes that account for the ripple.

---

## 1. What this program is

BuccaneerClaude (`com.buccaneer.browser`) is a **web browser for Android TV /
Fire TV**, driven entirely by a **remote control (D-pad)** — there is **no mouse
and no touch**. It is built and tuned for **streaming sites** (flixer, cinejoy,
nippleflix, coreflix, movy), where the hard problems are: typing into search
fields without a keyboard, playing video in fullscreen, seeking with the remote,
killing ad popups, and surviving a buggy GPU driver.

- **Device:** Fire TV AFTMM, Android 7.1.2, **API 25**, **PowerVR Rogue GPU**.
  Screen 1920×1080; the WebView viewport is **960×540** (DOM coords ×2 = screen
  coords). The GPU driver has a real bug (see §6) that the app must live with.
- **Source tree:** `<repo-root>` (the live dev tree, v2.3.1,
  branch `main`). This is the tree to read and edit.
- **Total size:** ~11,400 lines (main + tests). The core is
  `MainActivity.kt` (3,956 lines) + `page_helpers.js` (1,911 lines). Everything
  else is supporting.

---

## 2. The two layers and the bridge between them

The program is **two layers that must stay in sync**:

### Layer A — Native Kotlin (the app)
- **`MainActivity.kt`** is the heart (3,956 lines). It owns: the WebView, all
  remote-key routing, the on-screen keypad, menus/panels, the start page,
  bookmarks, settings, the virtual cursor, fullscreen handling, the JS bridge,
  diagnostics, and crash recovery.
- Supporting Kotlin files (each has one job — see §9 for the full list):
  `Keypad.kt`, `StartPage.kt`, `Osd.kt`, `CursorView.kt`, `PointerTap.kt`,
  `SeekRepeatPolicy.kt`, `Panels.kt`, `Ui.kt`, `PopupGuard.kt`,
  `BookmarkStore.kt`, `DiagnosticLog.kt`, `Watchdog.kt`, `CrashLog.kt`,
  `CrashRecoveryPolicy.kt`, `IdleDimPolicy.kt`, `NavigationHandoff.kt`,
  `UiCoalescer.kt`, `NativePageEditor.kt`, `TvWebView.kt`, `JsonInputBudget.kt`,
  `BookmarkFiles.kt`, `BookmarkJobs.kt`, `BuccaneerApplication.kt`.

### Layer B — Injected JavaScript (the page helpers)
These run **inside the WebView, at document-start, in every frame**:
- **`page_helpers.js`** (1,911 lines) — the core JS. The `__fb` player-command
  helper, the cross-frame postMessage bridge, CSS-fullscreen intercept, controls
  auto-hide ("poke"), quality preference, network probe, one-video-at-a-time.
- **`popup_guard.js`** (434) — kills ad popups/overlays, blocks `window.open`,
  rewrites `target=_blank`, and the Coreflix/vidcore `reactOnClick` button fix.
- **`tv_ui.js`** (335) — the typing/edit path: reports focused fields to the app,
  applies typed text, and submits on Enter/GO.
- **`flixer_image_guard.js`** (286) — TMDB poster virtualization (strips
  off-screen poster `src`s to cut GPU texture load).
- **`seek_click.js`** (72) — on-demand hit-testing for seeking on the page.
- **`fmhy_theme.js`** (23) — a host-isolated theme helper.

### The bridge (how the two layers talk)
1. **JS → native:** the app exposes `window.Buccaneer` via
   `webView.addJavascriptInterface(Bridge(), "Buccaneer")`. The
   `@JavascriptInterface` methods (MainActivity.kt ~line 1300) are the API the JS
   calls: `diagnosticEvent`, `isGuardEnabled`, `isDarkMode`, `cssFullscreen`,
   `playbackActive`, `activeInputFrame`, `currentDocument`, `editableFocused`,
   `editableBlurred`, `blocked`, `editableClicked`, `exclusiveEnabled`,
   `lowQualityEnabled`, `mediaStarted`, `mediaStopped`, `activeOwner`,
   `tmdbAllowlist`, `isDiagnosticsEnabled`, `pageIsForeground`.
2. **Native → JS:** the app calls `webView.evaluateJavascript(...)` to run
   `__fb.*` commands (seek, play/pause, state) and `__tvUi.edit(...)` (typing).
3. **Cross-frame bridge (critical for streaming):** players are usually in
   **cross-origin iframes**, which injected JS **cannot reach directly**
   (opaque DOM). So `page_helpers.js` runs in *every* frame; the **top frame
   forwards** a video command to the **player frame's own `__fb`** via
   `postMessage` (the `__fbCmd`/`__fbRes` protocol, with an ancestor-source
   check). The player frame runs the command on its own `<video>` and replies
   with its `state()`. **Consequence:** `__fb.*` returning the string `'frame'`
   means "the frame's helper ran it" (success), **not** "we couldn't reach it."
   MainActivity treats `'frame'` as success for seek and play/pause (no key
   fallback). Flixer is excluded from this bridge (`frameIsFlixer`) and keeps a
   trusted-tap path.

**Why this matters for regressions:** a fix that changes the bridge protocol, the
`__fb` surface, or the frame-detection logic can silently break seeking,
play/pause, or the OSD on *every* streaming site at once. And because the player
is cross-origin, a lot of "the video isn't doing X" bugs are really "the command
never reached the right frame" — check the frame depth before blaming the player.

---

## 3. The subsystems (what each does, where it lives)

There are **five primary subsystems** (the ones that keep breaking each other)
plus a set of supporting systems.

### Subsystem 1 — Keyboard / Enter / GO (search & forms)
- **What:** lets the user focus a text field, type into it with an on-screen
  keypad, and submit (Enter/GO).
- **Where:** `tv_ui.js` (the `edit`/submit path, field reporting), `Keypad.kt`
  (the on-screen keyboard layout + key handling), `MainActivity.kt` (key routing
  to the keypad, `typeGo`/`typeText` ~line 2885–2980, `openKeypadSmart` ~2766,
  the `editableFocused`/`editableBlurred` bridge methods).
- **How it works:** the page can only ever receive **synthetic (untrusted)** key
  events. When a field is focused, `tv_ui.js` reports it to the app (recording the
  **field identity**: `inputFrame` + `inputFieldId`). The app opens the keypad;
  typing targets the field **by ID** and is only accepted while
  `activeInputFrame()` still matches the reported frame. On GO, the app calls
  `__tvUi.edit("submit")`, which dispatches a synthetic Enter keydown to the
  **recorded** field and submits.

### Subsystem 2 — Fullscreen controls auto-hide
- **What:** in fullscreen, the player's transport controls should disappear after
  a few seconds of no interaction, and reappear on interaction.
- **Where:** `page_helpers.js` (`installControlsAutoHide`, the bounded "poke",
  `__fb`, the CSS-fullscreen intercept), `MainActivity.kt`
  (`liftPointerIfPlaying` ~2200, `hideCursor` ~2080, `sendHover` ~2218,
  `cssFullscreenActive`, `enterFullscreen` ~2570, `onFullscreenKey` ~2621).
- **How it works:** the app uses **CSS fullscreen** (it intercepts
  `requestFullscreen` and styles the player container `position:fixed;
  z-index:2147483647` instead of using Chromium's native fullscreen, which draws
  a stuck center play/pause button). The virtual cursor parks a **persistent mouse
  pointer** in the WebView (`sendHover` → `HOVER_ENTER`/`HOVER_MOVE`, never
  `HOVER_EXIT`); pointer-aware players treat a parked pointer over their controls
  as "user is interacting" and pin the controls visible. `liftPointerIfPlaying()`
  sends `ACTION_HOVER_EXIT` when the cursor idles, **only while video is playing**.

### Subsystem 3 — FF / rewind / play-pause (transport keys)
- **What:** the remote's media keys seek and toggle playback, including in
  fullscreen.
- **Where:** `MainActivity.kt` (`onTransportKey` ~1744, `seekFromKey` ~2399,
  `videoCommand` ~2344, `SeekRepeatPolicy.kt`), `page_helpers.js` (`__fb.seek`,
  the cross-frame bridge).
- **How it works:** a transport key routes to `onTransportKey`, which calls
  `videoCommand("seek(...)")` / `videoCommand("togglePlay()")`. That evals
  `__fb.*` in the **top frame**, which forwards to the **player frame** via the
  postMessage bridge. If the player is in a cross-origin iframe and the command
  can't be resolved, there is a DPAD_LEFT/RIGHT key fallback (silent).

### Subsystem 4 — Popup guard
- **What:** kills ad popups and click-catcher overlays; blocks `window.open`;
  rewrites `target=_blank` links; and contains the Coreflix/vidcore button fix.
- **Where:** `popup_guard.js` (`killOverlays`, the `window.open` override, the
  click listener, `reactOnClick`), `PopupGuard.kt` (native navigation blocking +
  `rules.json`).
- **How it works:** `killOverlays()` runs on a MutationObserver (debounced) + a
  5s interval fallback, and kills elements matching **structural** ad signals only
  (never text — see §6). It is **skipped entirely on flixer** (the scanner
  triggers the PowerVR crash). The `reactOnClick` workaround directly invokes a
  React `onClick` handler on `pointerup` because the vidcore ad script suppresses
  the synthetic `click` that would otherwise fire.

### Subsystem 5 — Image guard (TMDB virtualization)
- **What:** cuts GPU texture load on poster-grid sites (flixer, cinejoy) by
  stripping the `src` of off-screen TMDB posters so the browser never
  requests/decodes them; restores them on scroll-in.
- **Where:** `flixer_image_guard.js` (`virtualize()`, `sync()`, the allowlist),
  `MainActivity.kt` (`onImageGuardedPage`, `isImageGuardedHost()` ~377,
  `shouldInterceptRequest` native 1×1 firewall, the `tmdbAllowlist` bridge).
- **How it works:** the JS half tracks visible `<img>` TMDB URLs (bounding rect +
  IntersectionObserver) and pushes an allowlist to the native half; the native
  `shouldInterceptRequest` serves a 1×1 pixel for any `image.tmdb.org` URL not in
  the allowlist. Gated to flixer + cinejoy hosts.

### Supporting systems
- **Navigation & start page** — `navigateUrl` (~1514), `showStartPage` (~1617),
  `StartPage.kt`, `NavigationHandoff.kt`. The start page is the bookmark launcher;
  it is shown on launch and hidden when the app navigates via its own
  `navigateUrl`. (CDP `Page.navigate` does **not** hide it — see §6.)
- **Bookmarks** — `BookmarkStore.kt`, `BookmarkFiles.kt`, `BookmarkJobs.kt`,
  `Panels.kt`. Stored in `shared_prefs/bookmarks.xml` (JSON in
  `<string name="data">`).
- **OSD** — `Osd.kt` (display-only status/volume/position bar; the native seek
  bar is **not** interactive).
- **Virtual cursor** — `CursorView.kt`, `PointerTap.kt`, `sendHover`/`hideCursor`
  in MainActivity. The D-pad moves a visible cursor; taps are synthesized.
- **Watchdog & crash recovery** — `Watchdog.kt` (a separate process that restarts
  the app after a native GPU crash and reopens `lastCrashUrl`), `CrashLog.kt`,
  `CrashRecoveryPolicy.kt` (renderer-crash retry budget).
- **Diagnostics** — `DiagnosticLog.kt` writes
  `files/diagnostic_session.jsonl` (keys, media, navigation, popups, console).
  **This is your primary debugging tool** — read it before theorizing.
- **Quality preference** — `page_helpers.js` scans for HLS.js/Shaka players and
  prefers 480p→720p→1080p, gated behind `lowQualityEnabled()`.
- **Idle dim** — `IdleDimPolicy.kt` dims the screen when idle and not playing.
- **One video at a time** — `page_helpers.js` + `mediaStarted`/`mediaStopped`/
  `activeOwner` bridge; pauses other videos when one starts.

---

## 4. How the parts work together (the interactions)

These are the shared mechanisms that tie the subsystems together. **This is where
regressions come from.**

### 4.1 The key-routing funnel (the single most important shared path)
Every remote key enters `dispatchKeyEvent` (MainActivity.kt ~1670) and is routed
to exactly **one** handler, chosen by this priority order:

```
keypad.shown()                          -> keypad.handleKey
!startPage.shown() && isTransportKey()  -> onTransportKey   (media keys)
textPanel.shown()                       -> onTextPanelKey
menu.shown()                            -> onMenuKey
listPanel.shown()                       -> onListKey
customView != null || cssFullscreenActive -> onFullscreenKey
startPage.shown()                       -> onStartKey
else                                    -> onBrowseKey
```

**Consequences:**
- A key goes to **one** place. If the wrong branch is active, the key "disappears."
  Example: if the **start page** is showing, transport keys (FF/rewind) are routed
  to `onStartKey`, **not** `onTransportKey` — so FF/rewind do nothing until the
  start page is hidden. This is why navigating via CDP (which doesn't hide the
  start page) breaks FF/rewind.
- If the **keypad** is showing, *everything* (including media keys) goes to the
  keypad. A stale open keypad will eat media keys as Enter.
- **`diagKey(code, branch)`** logs every key with its branch to the diagnostic
  log. **When a key "doesn't work," read the `key` event's `handler` field first**
  to see which branch consumed it — before blaming the page or the player.

### 4.2 The focus model (WebView vs IME vs start page)
- The app **suppresses the system IME** (`TvWebView.onCheckIsTextEditor() =
  false`), so typing reaches the page via **synthetic key events**, not the
  keyboard. Focusing a text field does **not** hand focus to a system IME.
- The **start page** and the **WebView** compete for keys (see 4.1). Only one is
  "on top" at a time.
- Input focus is also shared with the system IME in subtle ways — if injected
  typing "gets eaten," check `dumpsys input_method` before blaming the keypad.

### 4.3 The pointer / hover model (the virtual cursor)
- The D-pad moves a **virtual cursor** that the app renders (`CursorView`) and
  that it also expresses to the WebView as **mouse hover events** (`sendHover` →
  `ACTION_HOVER_ENTER`/`HOVER_MOVE`).
- The browser **keeps that pointer parked** at the last position forever (it never
  sends `HOVER_EXIT` on its own). This is what keeps hover menus open while the
  user reads them — **desired while browsing**.
- But the same parked pointer is what **pins player controls visible** in
  fullscreen (the player sees "pointer over UI" and never auto-hides). The fix
  (`liftPointerIfPlaying`) sends `HOVER_EXIT` **only while playing**, so browsing
  behavior is unchanged.
- **This one mechanism is the root of both the Movy auto-hide bug and a potential
  YouTube regression** — any change to hover/pointer handling must be checked on
  *both* a pointer-aware auto-hiding player *and* a show-on-activity player like
  YouTube.

### 4.4 The fullscreen model (CSS fullscreen + cross-origin players)
- The app **intercepts `requestFullscreen`** and enters **CSS fullscreen**
  (styles the player container `position:fixed; inset:0; z-index:2147483647`)
  instead of Chromium's native fullscreen (which draws a stuck center button).
- `cssFullscreenActive` (MainActivity) is the app-side flag; the JS side reports
  it via the `cssFullscreen(active)` bridge method. **Key routing depends on it**
  (4.1): when `cssFullscreenActive` is true, keys route to `onFullscreenKey`.
- The player is usually in a **cross-origin iframe**. The fullscreen element is
  the **iframe**, not the video. You can style the iframe from the top frame but
  **cannot reach the video inside it**.
- **The player iframe goes to `z-index:2147483647` + `position:fixed` in
  fullscreen — the same max z-index ad injectors use.** This is why the popup
  guard's high-z rule must exempt the player (see §5, the coreflix regression).

### 4.5 The navigation model
- `navigateUrl(url, userInitiated)` is the app's own navigation path. It **hides
  the start page**, updates `lastPageUrl`/`lastCrashUrl`, bumps `pageEpoch`, and
  resets page-edit/click/selection state.
- **`pageEpoch` and `interactionSerial` are the load-bearing invalidation
  mechanism.** Async callbacks (JS evals, taps, seeks) capture the epoch/serial at
  issue time and are discarded if the value changed (i.e., the user navigated or
  the page changed). **Do not "simplify" or remove these** — they prevent stale
  callbacks from acting on the wrong page. A code audit found 0 TODOs, 0
  commented-out code, and these guards are the design, not debt.
- Navigating **closes text-entry mode**, resets click/selection state, and clears
  frame-field state — otherwise a stale keypad/field leaks into the new page.

---

## 5. The push/pull: what breaks when you change what (the regression map)

This is the core of the handoff. Each entry: **change X → can break Y → because
Z.** Read this before touching any subsystem.

### 5.1 Keyboard / Enter (Subsystem 1)
- **Re-gating submit on the `proceed` flag breaks search.** The page can only
  receive synthetic (untrusted) Enter. React handlers call `preventDefault()` on
  them, so `proceed` is false. The submit path **must** call
  `form.requestSubmit()` regardless of `proceed` when the field is in a form.
  (The nippleflix bug, fixed in `5434e6d`.) If you "clean up" the submit path and
  re-introduce a `proceed` gate, search breaks again. **There is a code note in
  `tv_ui.js` — read it.**
- **Changing field-identity recording breaks typing.** The open path MUST record
  the field identity (`inputFrame` + `inputFieldId`); typing targets the field by
  ID and is only accepted while `activeInputFrame()` matches. An open that records
  no identity rejects every edit including Enter. (This was a past break.)
- **The keypad is shared with media keys.** If the keypad is open, media keys go
  to the keypad (4.1). A change that leaves the keypad open across navigation
  makes media keys act as Enter.

### 5.2 Fullscreen auto-hide (Subsystem 2)
- **The pointer-lift is gated on playback for a reason.** `liftPointerIfPlaying`
  sends `HOVER_EXIT` only while video plays. If you lift the pointer while
  *browsing*, you break hover menus (they close while the user reads them). If you
  *don't* lift while playing, controls stay pinned (the Movy bug). The gate is the
  fix, not a limitation.
- **`HOVER_EXIT` is YouTube-safe, but the poke is not.** `HOVER_EXIT` generates no
  mousemove, so it can't re-trigger YouTube's show-on-activity. But the bounded
  "poke" in `page_helpers.js` (`installControlsAutoHide`, `maxPokes`) *does*
  generate mousemove. A **continuous** poke perpetually re-shows controls on
  show-on-activity players (YouTube) and keeps the center button visible on
  auto-hiding players. It must stay **bounded** (~2–3 pokes). A change to the poke
  can fix one player type while regressing another — **regression-check on a
  second site with a different player type** before committing.
- **The CSS-fullscreen intercept must cover both entry paths.** It overrides
  `requestFullscreen`/`webkitEnterFullscreen`. On this WebView (Chromium 118, API
  25), `webkitEnterFullscreen` lives on `HTMLVideoElement.prototype`, **not**
  `HTMLMediaElement.prototype`. A player that calls `video.webkitEnterFullscreen()`
  bypasses an intercept that only patched the media-element prototype. Iterate both
  prototypes.

### 5.3 FF / rewind (Subsystem 3)
- **The start page blocks transport keys.** If the start page is showing, FF/rewind
  route to `onStartKey`, not `onTransportKey` (4.1). Navigating via CDP
  `Page.navigate` does **not** hide the start page (only the app's `navigateUrl`
  does), so FF/rewind appear dead after a CDP navigation. Test transport keys after
  a *real* app navigation.
- **The cross-frame seek is silent on fallback.** When `__fb.seek` returns
  `frame`/`none` (player in a cross-origin iframe), the code sends DPAD_LEFT/RIGHT
  keys to the page with **no diagnostic and no OSD** — if the iframe isn't focused,
  the keys vanish and the user sees nothing. Check `seek_result`/`seek_fallback`
  events; a missing result means the eval deadline fired and the fallback ran.
- **Measuring seeks on a playing video confounds the delta.** A +10s seek measured
  over a 3.5s wait reads ~+6.5 net (playback eats into it). **Pause first**, then
  seek, then measure — paused deltas are exact.

### 5.4 Popup guard (Subsystem 4) — the most regression-prone
- **NEVER use text-based ad detection.** An earlier rule killed any floating
  element whose text matched ad copy ("free", "download", "offer", "sign up"). It
  matched movie synopses and page titles and **hid the Play button** inside the
  Movy/CineJoy hero layer (all 31 `overlay_killed` events were false positives).
  Text is not an ad signal; **structure is**. The current rules are structural only:
  (1) floating iframe, z≥1000, <25% viewport; (2) positioned element covering ≥10%
  of the video; (3) a positioned element with **z-index ≥ 1,000,000** (injected ad).
  Any element containing controls (`button, [role=button], input, select`) is never
  killed.
- **The high-z rule MUST exempt the main player iframe.** (The coreflix regression,
  `066fec3` → `e6c984f`.) The player iframe goes to `z-index:2147483647` +
  `position:fixed` in fullscreen — the **same max z-index** the ad injector uses.
  The video inside is cross-origin, so `containsVideo`/`hasControls` can't see it,
  and a naive high-z rule **killed the player → black screen with audio + dead
  seek bridge (FF/rewind dead)**. The fix identifies the main player iframe
  (largest iframe with a real, non-blank, non-srcdoc src) and exempts it **and its
  container chain** (`el.contains(playerIframe)`) from the high-z rule. **Lesson: a
  z-index threshold alone is not a safe ad signal — fullscreen players use the same
  max z-index as ad injectors. Any high-z kill rule must exempt the active player.**
- **The `reactOnClick` workaround is protected and delicate.** It defers a direct
  React `onClick` call to 300ms after `pointerup`; a document-level `click`
  listener (capture) cancels the timer if a native click fires first. This
  preserves the suppressed-click path (timer fires, button works) and prevents
  double-trigger (native click cancels the timer). **Do not** use a DOM-walk
  `__buccaneerHandled` mark (it broke all buttons except play/pause). If the ad
  script stops suppressing the real click, an ungated direct call double-activates
  (play→pause, menu opens then closes).
- **The overlay scanner is skipped on flixer** — the MutationObserver + interval
  that walks every DOM element triggers the PowerVR crash during flixer's intro
  animation. Do not re-enable a full-DOM scan on flixer.

### 5.5 Image guard (Subsystem 5)
- **The guard is flixer + cinejoy only.** Extending it to other hosts changes
  behavior on every site (lazy-load detectors, load-event reporting) and was tried
  and reverted (Coreflix's quality button broke). `isImageGuardedHost()` is the
  single source of truth; `onImageGuardedPage` is separate from `onFlixerPage`
  (which stays flixer-only for player/tap/seek behavior).
- **`virtualize()` was dead code until wired up** (`1bc1ecd`). It is now called at
  the top of `sync()`. The benefit on cinejoy is modest (native `loading="lazy"`
  already defers most off-screen images); it reduces GPU texture load / crash
  probability but does **not** prevent the vendor PowerVR bug.

### 5.6 Cross-cutting: the PowerVR GPU crash
- **It is a vendor driver bug, not our code.** Every crash is a `SIGSEGV` in
  `KEGLReleaseRenderTarget` in `/vendor/lib/libIMGegl.so`, reached from Chromium's
  GPU/render code. It fires under GPU load (video compositing, animations,
  texture/framebuffer churn). **There is no app-side fix** (short of software
  rendering, which is ruled out). The **Watchdog** restarts the app and reopens
  `lastCrashUrl`.
- **What reduces crash probability:** the image guard (fewer textures), avoiding
  rapid page navigation on image-heavy pages, and not adding recurring
  DOM-walking timers/observers on flixer.
- **Never suggest software rendering as a fix.** It breaks tap coordinates and does
  not prevent the crash. It is off the table permanently.

### 5.7 The general rule (why the loop keeps happening)
The five subsystems share: **key handling** (4.1), **focus** (4.2), **pointer/
hover** (4.3), **the DOM** (popup guard + image guard both mutate it), and
**fullscreen state** (4.4). A change to any shared mechanism can ripple into every
subsystem that uses it. **Before you change a shared mechanism, list every
subsystem that uses it and check each one.** That is the whole discipline.

---

## 6. Known pitfalls and hard constraints (do not violate)

- **PowerVR GPU crashes** are a vendor bug. No app-side fix. Watchdog is the
  safety net. Never suggest software rendering.
- **Cross-origin iframes are opaque to injected JS.** Players are usually 2+ levels
  deep (top → player host → video iframe), each cross-origin. Quality preference,
  player hooks, and DOM probes only work in the frame that owns the player. Verify
  frame depth with `player_geometry` + `frame_script_alive` events before assuming
  a hook reaches the player.
- **The virtual cursor parks a persistent pointer** (HOVER_ENTER/MOVE, never
  HOVER_EXIT). Lift it only while playing (`liftPointerIfPlaying`).
- **Text is not an ad signal.** Never reintroduce text-based ad detection.
- **z-index alone is not a safe ad signal.** Fullscreen players use the same max
  z-index as ad injectors. Exempt the active player.
- **flixer is special.** Excluded from: the overlay scanner (PowerVR), the
  fullscreen intercept, the cross-frame seek bridge (uses trusted-tap), and active
  quality instrumentation. Do not "unify" flixer with the other hosts.
- **`pageEpoch` / `interactionSerial` are the design, not debt.** Do not remove
  them during cleanup.
- **CDP `Page.navigate` does not hide the start page.** Only the app's
  `navigateUrl` does. Use a real app navigation (or ADB bookmark selection) when
  testing transport keys.
- **CDP from the host works** via a raw-socket WebSocket client that sends **no
  Origin header** and **masks client frames** (RFC 6455). A helper lives in the
  scratch dir as `cdp.py` (rewrite if gone). Cross-origin frames:
  `Page.getFrameTree` → frameId → `Page.createIsolatedWorld` → `Runtime.evaluate`.
- **`eval()` in `onShowCustomView` returns `"none"`** — the WebView is
  mid-transition. Delay JS probes that need a stable state (fullscreen,
  post-navigation) by ~2s, don't fire them synchronously from the lifecycle
  callback.
- **`MotionEvent.obtain` has no 3-arg overload** on this API — use the 6-arg form
  with the cursor coords.
- **WebView JS is Chromium 118 on API 25** — no `??` or `?.` in injected JS.
- **SharedPreferences XML uses NO namespace prefix** — `<boolean name="k"
  value="true"/>`, not `android:name`. The `android:` prefix crashes startup.
- **Diagnostics JSONL is unreadable on non-debuggable (release) builds** —
  `run-as` refuses. Stay on debug builds for development.
- **The diagnostic log is your primary tool.** Read it before theorizing. A `key`
  event's `handler` field tells you which branch consumed a key; `overlay_killed`
  / `floating_seen` tell you what the popup guard did; `pointer_lifted` confirms
  the auto-hide lift; `seek_result`/`seek_fallback` confirm the seek path.

---

## 7. The protected files and the test gate

Some files are **hash-protected** so that a fix can't silently revert a prior fix:
- `tests/protected-fixes.json` lists the protected files/regions and their SHA-256.
- `tests/check-protected-fixes.py` hashes them and fails on mismatch. It currently
  **passes cleanly (exit 0)**.

**The rule:** if your fix must edit a protected file (`popup_guard.js`,
`page_helpers.js`, `flixer_image_guard.js`, or a pinned region of
`MainActivity.kt`), you **must** update its hash in `protected-fixes.json` and make
`check-protected-fixes.py` pass. The `MainActivity.kt` entries use `after`/`before`
marker strings, so the marker text must match the current comment. If a baseline
goes stale because a pinned file was removed/refactored, remove the obsolete pin and
re-pin the file to its current state (whole-file hash) — do not leave a
permanently-failing check, and verify a failure is pre-existing (via `git stash` +
re-run) before blaming your edit.

**Run `python3 tests/check-protected-fixes.py` after any change to a protected
file.**

---

## 8. Environment and how to drive the device

- **Device:** Fire TV AFTMM (Android 7.1.2, API 25, PowerVR) at
  `<device-ip>:5555` via adb.
- **App:** `com.buccaneer.browser`, activity `.MainActivity`.
- **Live source:** `<repo-root>` (v2.3.1).
- **Build:** `cd <repo-root> && source
  <android-env>/env.sh && gradle :app:assembleDebug` →
  `app/build/outputs/apk/debug/app-debug.apk`. (There is **no** `gradlew` wrapper —
  use `gradle`.)
- **Install:** `adb -s <device-ip>:5555 install -r
  app/build/outputs/apk/debug/app-debug.apk`. **Reinstalling force-stops the app and
  loses the user's current page** — warn her first.
- **CDP (inspect the live page without touching the remote):**
  `adb -s <device-ip>:5555 forward tcp:9222
  localabstract:webview_devtools_remote_$(adb -s <device-ip>:5555 shell pidof
  com.buccaneer.browser | tr -d '\r')` then use the `cdp.py` helper. **Re-forward
  after every app restart** (the socket is per-pid).
- **Diagnostic log (use this first):** `adb -s <device-ip>:5555 shell "run-as
  com.buccaneer.browser cat files/diagnostic_session.jsonl"`. Timestamps are UTC.
- **Bookmarks:** `adb -s <device-ip>:5555 shell "run-as com.buccaneer.browser cat
  shared_prefs/bookmarks.xml"` (JSON in `<string name="data">`).
- **Crash logs:** `adb -s <device-ip>:5555 shell "logcat -d -b crash"` (filter for
  `com.buccaneer.browser`). Native GPU crashes show as a silent gap in the JSONL
  followed by `process_start`; the logcat tombstone is the source of truth.
- **Screen:** 1920×1080; WebView viewport 960×540 (DOM coords ×2 = screen coords).
  Screenshot: `adb -s <device-ip>:5555 exec-out screencap -p > /tmp/x.png`.

### Standing rules (from the user — do not violate)
- **NEVER suggest software rendering as a fix.** Off the table permanently.
- **Never uninstall the app** without asking (wipes bookmarks/data). Reinstall
  (`install -r`) is fine.
- **Commit locally only. Do NOT push to GitHub** unless she explicitly says so.
  Git identity: `Buccaneer <BuccaneerBrowser@users.noreply.github.com>`. Scrub
  personal data before any push.
- **NEVER publish the keystore / signing key** anywhere public.
- **Preserve all prior fixes** — she tracks regressions across sessions.
- **Fix a subsystem as one clean rule set**, never patch-on-patch defensive gates.
- **No excuses / no theorizing** — when she says it's the code, find the exact
  line. Decompile/diff the last known-working build before theorizing.
- **Own mistakes plainly.** She is hands-on: she navigates the remote herself and
  reports tersely. Use the app's built-in diagnostics before ad-hoc probes.
- **One hypothesis per install cycle** — make one targeted change, build, install,
  test. If it fails, revert before the next approach.
- **Deliver files over LAN HTTP** (`python3 -m http.server 8099 --bind 0.0.0.0` in
  the file's dir, send `http://<host-ip>:8099/<file>`). MEDIA: attachments do not
  work for her. Zip small text files so the TV browser downloads instead of
  rendering them.

---

## 9. Your reading plan (go through the code)

Read in this order. For **each** file, answer: (1) what is its one job? (2) what
does it expose (public API, bridge methods, globals)? (3) what does it depend on?
(4) what depends on it? (5) what breaks if I change it? Keep §5 (the regression
map) in mind the whole time.

### Pass 1 — the native core
1. **`MainActivity.kt`** (3,956 lines) — read top to bottom. Focus on:
   - State fields (~line 119–443): `pageEpoch`, `interactionSerial`,
     `cssFullscreenActive`, `customView`, `hoverActive`, `playbackHeartbeatAt`,
     `inputFrame`/`inputFieldId`, `mediaOwner`, `lastCrashUrl`.
   - The JS bridge `@JavascriptInterface` methods (~1300).
   - `navigateUrl` (~1514), `showStartPage` (~1617).
   - **`dispatchKeyEvent` (~1670) — the key-routing funnel (§4.1). Read it
     line by line.**
   - `onTransportKey` (~1744), `onBrowseKey`, `onFullscreenKey` (~2621),
     `onStartKey` (~1759), `onMenuKey`/`onListKey`/`onTextPanelKey`.
   - `hideCursor` (~2080), `tapAtCursor` (~2087), `liftPointerIfPlaying` (~2200),
     `sendHover` (~2218).
   - `videoCommand` (~2344), `seekFromKey` (~2399).
   - `enterFullscreen` (~2570), `leaveFullscreen` (~2589).
   - `openKeypadSmart` (~2766), `typeText` (~2885), `typeGo` (~2915).
   - `isImageGuardedHost` (~377), `shouldInterceptRequest`.
   - WebView setup + script injection (where the JS assets are loaded at
     document-start).
2. **`TvWebView.kt`** — `onCheckIsTextEditor() = false` (IME suppression), raw key
   route.
3. **`Keypad.kt`** — the on-screen keyboard; `handleKey` (Enter→GO on the GO key).
4. **`StartPage.kt`** — the bookmark launcher; paging, focus, `back()`.
5. **`Osd.kt`** — display-only status bar (the native seek bar is not interactive).
6. **`CursorView.kt`**, **`PointerTap.kt`** — the virtual cursor + tap synthesis.
7. **`SeekRepeatPolicy.kt`** — hold-to-seek timing (first press 10s, +30s after
   600ms, +60s after 2500ms). Uses elapsed hold time, **not** key repeats (CEC
   remotes don't repeat).
8. **`Panels.kt`**, **`Ui.kt`** — menu/list/text construction, focus confinement,
   scaling.
9. **`PopupGuard.kt`** — native navigation blocking + `rules.json`.
10. **`BookmarkStore.kt`**, **`BookmarkFiles.kt`**, **`BookmarkJobs.kt`** —
    bookmark persistence, import/validation, ordering.
11. **`DiagnosticLog.kt`** — the JSONL writer (queue bounds, rotation, redaction).
12. **`Watchdog.kt`**, **`CrashLog.kt`**, **`CrashRecoveryPolicy.kt`** — crash
    recovery (the watchdog is the only thing that can act on a native GPU crash).
13. **`IdleDimPolicy.kt`**, **`NavigationHandoff.kt`**, **`UiCoalescer.kt`**,
    **`NativePageEditor.kt`**, **`JsonInputBudget.kt`**, **`BuccaneerApplication.kt`**.

### Pass 2 — the JS layer
14. **`page_helpers.js`** (1,911 lines) — the core JS. Focus on: the `__fb`
    helper (seek/play/pause/state), the **cross-frame postMessage bridge**
    (`__fbCmd`/`__fbRes`, ancestor-source check), the **CSS-fullscreen intercept**
    (`enterFullscreen`, `playerContainer`, `prepareFullscreen`, `onFsChange`),
    `installControlsAutoHide` (the bounded poke), the quality preference scanner,
    the network probe, one-video-at-a-time, and the flixer early-return guard.
15. **`popup_guard.js`** (434) — `killOverlays` (the three structural signals +
    the player-iframe exemption), the `window.open` override, the `target=_blank`
    rewrite, and the **`reactOnClick`** Coreflix/vidcore fix (read the code notes).
16. **`tv_ui.js`** (335) — the `edit`/submit path (read the code note about
    always calling `form.requestSubmit()`), field reporting, frame-origin
    boundaries, menu fitting, nested scrolling.
17. **`flixer_image_guard.js`** (286) — `virtualize()`, `sync()`, the allowlist,
    the host gate.
18. **`seek_click.js`** (72), **`fmhy_theme.js`** (23).

### Pass 3 — the tests and the gate
19. **`tests/check-protected-fixes.py`** + **`tests/protected-fixes.json`** — the
    protection gate (§7).
20. **`app/src/test/`** — the regression suites. Note which functions are
    load-bearing for tests (e.g. `importBookmarkText` is referenced by
    `Regression171Test.kt` even with no call site in `src/main`).
21. **`TESTING.md`** — the regression checklist (§10).

### Pass 4 — verify your understanding
After reading, you should be able to answer, without looking it up:
- Trace a FF keypress from the remote to the video: which functions, in what
  order, and where it can silently fail?
- Why does the popup guard's high-z rule exempt the player iframe, and what breaks
  if you remove the exemption?
- Why must the keyboard submit path call `form.requestSubmit()` regardless of
  `proceed`?
- What is `pageEpoch` for, and what breaks if you remove it?
- Why is the overlay scanner skipped on flixer?
- What is the difference between `onFlixerPage` and `onImageGuardedPage`?
- How does a key get routed when the start page is showing vs. when a video is in
  CSS fullscreen?
If you can't answer these, re-read the relevant section.

---

## 10. The regression checklist (run after every fix)

**No fix is done until this passes.** If a fix can't be verified against it, say so
explicitly rather than claiming it works. (Full version in `TESTING.md`.)

Sites: flixer, cinejoy, nippleflix, coreflix, movy. For **each site**:
1. **Search** — focus the field, type a title, press GO/Enter. Results appear.
2. **Fullscreen auto-hide** — open a title, play, enter fullscreen, wait ~10s
   without touching the remote. Transport controls disappear.
3. **FF / rewind** — in fullscreen and playing, FF (jumps ~10s forward) then rewind
   (jumps ~10s back).

Plus:
4. **Popup guard** — on a home/show/season page (no video), no floating ad badge or
   full-viewport click-catcher remains after ~10s. Check the log for
   `overlay_killed` (`reason: high_z_ad` / `floating_iframe` / `covers_video`) and
   **no** lingering `floating_seen` with z ≥ 1,000,000.
5. **Image guard** — on flixer/cinejoy poster grids, in-view posters render and the
   app doesn't crash-loop during the intro animation.

**Process rules:**
1. One clean rule per fix (not patch-on-patch gates).
2. Preserve prior fixes (the protected files, §7).
3. Add a code note at the fragile spot explaining *why* the code is shaped that way
   and what breaks if changed (see the notes in `tv_ui.js` and `popup_guard.js`).
4. Run the checklist before calling a fix done; report honestly which items passed
   and which could not be verified.

---

## 11. Recent history (what was just fixed, so you don't re-break it)

Most recent first (all local commits, none pushed):
- **`e6c984f`** — popup_guard: exempt the main player iframe from the high-z ad
  rule (fixed the coreflix black-screen regression caused by `066fec3`).
- **`066fec3`** — popup_guard: kill high-z injected ad overlays on non-video pages
  (the coreflix "baseball" popups).
- **`480f62e`** — added `TESTING.md` (regression checklist + interdependency map).
- **`5434e6d`** — tv_ui: always submit the form on Enter/GO, not just when the page
  lets the key through (the nippleflix search fix).
- **`a0c8694`** — removed the stale `repair199-baseline.json` check; pinned
  `page_helpers.js` to its current state (whole-file hash).
- **`1bc1ecd`** — wired up the dead `virtualize()`; extended the image guard to
  cinejoy.
- **`217a88a`** — `liftPointerIfPlaying()`: lift the simulated pointer when the
  cursor idles during playback (the Movy controls auto-hide fix).
- **`f0d6c0f`** — popup_guard: stop killing page UI that merely mentions ad words
  (the Movy Play-button fix — removed text-based ad detection).

The three "stability" issues (flixer image guard on cinejoy, Movy Play button,
Movy controls auto-hide) are all **resolved and user-verified**. The coreflix
popup + player-exemption work is the most recent. The known remaining gap: after a
GPU crash, the watchdog reopens the URL but **not** the video/scroll position
(separate, optional task).

---

## 12. Where to look when a specific symptom appears

- **"Search doesn't submit on Enter/GO"** → read the `edit_result` events (action +
  status). If `stale`, the recorded field id no longer matches (React re-rendered
  the input) — the user must re-select the field. Check the `tv_ui.js` submit path
  and the field-identity recording.
- **"Controls don't auto-hide in fullscreen"** → check `pointer_lifted` events and
  whether the cursor is parked over the controls. Check `liftPointerIfPlaying` and
  the bounded poke.
- **"FF/rewind does nothing"** → read the `key` event's `handler` field. If it's
  `startPage`, the start page is on top (navigate via the app, not CDP). If it's
  `transport`, check `seek_result`/`seek_fallback` — a missing result means the
  cross-frame eval timed out and the silent DPAD fallback ran.
- **"A popup got through"** → check `floating_seen` for z ≥ 1,000,000 (a high-z ad
  that wasn't killed) or a new ad structure the structural rules don't match.
- **"The Play button / hero content disappeared"** → you (or a prior change)
  reintroduced an over-broad kill rule. Check `overlay_killed` for false positives;
  the rule must be structural and must respect the controls/contains-video
  protections.
- **"Black screen with audio in fullscreen"** → the player iframe was killed (check
  `overlay_killed` for the `watch-iframe` / player class) or a GPU crash (check
  logcat). Confirm the player-iframe exemption is intact.
- **"App crashed and came back"** → GPU crash (logcat tombstone in
  `KEGLReleaseRenderTarget`); the watchdog restarted it. No app-side fix.
- **"Typing appears but Enter doesn't submit"** → see the first bullet; also check
  whether the page's own JS intercepts keydown (some search forms need an explicit
  submit).

---

*This handoff is a map, not a substitute for reading the code. The map tells you
where to look and what to ask; the code is the truth. Read it, keep the
interdependency graph in your head, and run the checklist after every change.*
