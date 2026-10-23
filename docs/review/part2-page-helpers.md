# page_helpers.js — Chunk 1 (lines 1–450)

## Header / install guard (1–31)
- L1–10: File header comment. Declares the contract: runs in **every frame at document start**; app calls via `evaluateJavascript()` in the **top frame**. Video API: `togglePlay() play() pause() seek(sec) volume(delta) toggleMute() state() prepareFullscreen()` → returns `"ok" | "frame" | "none"`; plus `frameCenter()` (tap point for unscriptable player), `diag()` (Settings > Diagnostics). Typing API: `field() focusIntoView() clear()`. Also: reports to app when a real tap landed in a text field (auto-open keypad), and enforces "one video at a time".
- L11–15: IIFE, `'use strict'`. Idempotence guard: `window.__fbInstalled` (L14–15) — second injection in same frame returns immediately.
- L17–24: **Flixer host detection**: `__host` = lowercased `window.location.hostname` with trailing dot stripped (try/catch). `__isFlixer = /(^|\.)flixer\.(gd|su)$/.test(__host)` — matches `flixer.gd`, `flixer.su`, and any subdomain of either.
- L17–21 comment: **Why flixer is excluded** — PowerVR GPU driver on this Fire TV crashes (SIGSEGV in `KEGLReleaseRenderTarget`) when the DOM observers, HLS hooks, and event listeners run alongside flixer's intro animation. On flixer-owned documents: **no continuous capture, no timers, no synthetic clicks**. The passive `__fb` API (video controls, typing) is still exposed.
- L26–29: Dark mode note: handled by WebView native `FORCE_DARK_ON` in `MainActivity.applyDarkMode()`. Explicit warning: do NOT inject `filter:invert()` on `<html>` — it crashed the PowerVR GPU process (`libGLESv2_POWERVR_ROGUE.so` SIGSEGV) and took down flixer.gd.
- L31: `MIN_SIDE = 100` — min width/height (px) for an element to count as "visible".

## Video discovery (33–118)
- L35–37 `fsElement()`: `document.fullscreenElement || document.webkitFullscreenElement || null`.
- L39–50 `isVisible(el)`: getBoundingClientRect (try/catch → false); requires width & height ≥ MIN_SIDE (100px); must intersect viewport (bottom>0, right>0, top<innerHeight, left<innerWidth, using the element's own document's window); computed style visibility≠'hidden', display≠'none'.
- L53–64 `collect(doc, depth, out)`: gathers all `<video>` in `doc` **and in same-origin iframes**, depth-limited (recursion depth param; cross-origin frames throw → skipped).
- L67–89 `findVideo()`: prefers the fullscreen element (if it's a VIDEO, or contains a video via `querySelector('video')`); else scans `collect(document, 2, all)` (depth 2) and picks highest score = `width*height * (playing ? 1.5 : 1)` among visible videos.
- L92–94 `isOpaque(f)`: true if `f.contentDocument` is inaccessible (cross-origin) — "a frame from another site".
- L96–101 `area(el)`: width*height via getBoundingClientRect.
- L104–118 `bigFrame()`: biggest **visible opaque** iframe in this document.
- L120–127 `countOpaqueFrames()`: count of visible opaque iframes (used later, e.g. diag).

## Target selection (129–182)
- L129–133: Comment: remote-control priority — a playing video here wins; else a big cross-origin frame (embedded player) beats a small video (ad/banner). "Passive selected-player identity. No new Flixer listeners or timers."
- L133 `selectedTarget`: sticky selection cache `{video: el} | {frame: el} | null`.
- L134–145 `attachedTarget(el)`: verifies element still attached to live document tree; walks up through `win.frameElement` chain (max 3 levels) verifying each frame element is visible and attached; returns true only if it reaches the top `window`. Guards against detached/removed players.
- L146–156 `videoInFrame(frame)`: for a same-origin frame, finds best video inside (same scoring as findVideo, depth 2).
- L157–169 `pickTarget()`: (1) if `selectedTarget` still attached & visible → reuse; (2) if `document.activeElement` is a visible IFRAME → `{video: videoInFrame(it)}` or `{frame: it}` (focus-follows); (3) `findVideo()` → `{video}`; (4) `bigFrame()` → `{frame}`; else null.
- L171–182 `selectAt(x, y)`: tap-point selection (called by app after a real tap): `elementFromPoint`; if IFRAME → select video inside or the frame itself, returns `'ok'|'frame'`; else walks up ≤8 parent levels: VIDEO node → select; node containing exactly 1 visible video → select; >1 videos → stop; returns `'ok'|'none'`.

## Cross-frame command bridge (184–345)
- L186–190 comment: app can only script top frame; this helper runs in every frame; top frame forwards commands to the player frame's copy, which runs them and answers back. Works in fullscreen and normal.
- L191 `frameCmdSeq`: monotonically increasing id for commands.
- L192 `lastFrameState`: `{at: ms, state: {...}}` — newest state reported by a player frame.
- L194–196 `frameIsFlixer(frame)`: tests `frame.src`'s host (split('/')[2]) against flixer regex.
- L198–204 `forwardToFrame(frame, cmd, arg)`: `frame.contentWindow.postMessage({__fbCmd: 1, id: frameCmdSeq, cmd, arg}, '*')`.
- L207–251 `runOnVideo(v, cmd, arg)` — the command executor, returns 'ok'|'failed'|'none':
  - `togglePlay` (209–211): if paused/ended → `safePlay(v)`; else `cancelPlayAttempt()` + `v.pause()`.
  - `play` (212–214): `safePlay(v)`.
  - `pause` (215–219): `cancelPlayAttempt()`; pauses **all** videos in this doc + same-origin iframes (depth 2) — "one video at a time". Returns 'none' if no videos.
  - `seek` (220–236): `arg` is a **delta** (seconds), not absolute. Guards: arg & currentTime must be finite. `wanted = max(0, currentTime + arg)`; clamps to duration if finite; if `v.seekable` has ranges: if wanted inside a range keep it, else snap to **nearest seekable edge** (min distance to any range edge). Sets `v.currentTime = wanted`; returns 'ok' only if actual currentTime within 1s of wanted (else 'failed' — some players reject seeks).
  - `volume` (237–240): `arg` is a **delta**; unmutes first (`v.muted = false`), then `v.volume = clamp01(round((v.volume+arg)*100)/100)`.
  - `toggleMute` (241–243): flips `v.muted`.
  - `unmute` (244–247): unsets `muted` on **every** video in this document (not just target); 'ok' if any changed, else 'none'.
  - default → 'failed'.
- L254–265 `frameCmd(cmd, arg)`: runs a command **in this frame** (invoked by the message listener): `pickTarget()`; video → `runOnVideo`; frame → forward one level down (`forwardToFrame` → returns 'frame' if sent, 'none' if not).
- L267–328 **message listener** (`window.addEventListener('message', ...)`):
  - `m.__fbRes === 1` (270–274): a player frame answered one of our commands → store `lastFrameState = {at: Date.now(), state: m.state}` (keeps frame state fresh for `state()` merging).
  - `m.__fbFs === 1 || m.__fbFsCmd === 1` (275–308): **CSS fullscreen protocol**. If `__isFlixer` → **return immediately** (Flixer keeps native fullscreen path; no CSS-fs handling at all).
    - `__fbFs` (child→parent notification, 277–299): child frame entered/exited CSS fullscreen; parent finds the iframe whose `contentWindow === e.source`; on `on===1`: sets `cssFs.frameChild = e.source`, `cssFsEnter(fr)` (make the iframe element itself CSS-fullscreen in parent), and if this window isn't top → `cssFsPostParent(true)` (bubble up). On exit: clears `frameChild`, exits if active, posts `__fbFs:1,on:0` back to child, bubbles to parent.
    - `__fbFsCmd` (ancestor→descendant command, 300–307): ancestor made this frame's element CSS-fullscreen; this frame enters/exits internally: `pickTarget()`; on → `cssFsEnter(cssFsTarget(t.video))`; off → `cssFsExit()`.
  - `m.__fbCmd !== 1` → return (309).
  - **Ancestor-source check** (310–319): accepts commands **only from an ancestor frame** — walks `parent` chain up to depth 6, requires `e.source === w` for some ancestor window. Never from sibling pages. If not from ancestor → ignore.
  - Reply (320–327): `reply(result)` posts `{__fbRes: 1, id: m.id, result, state: st}` to `parent`, where `st = JSON.parse(window.__fb.state())` — the frame's own current state snapshot. Executes `frameCmd(m.cmd, m.arg)`; on exception → reply('failed').
- L334–345 `videoOp(cmd, arg)`: top-level command dispatcher (called by the `__fb` API): `pickTarget()`; video → `runOnVideo`; frame → **if not a flixer frame** and `forwardToFrame` succeeds → 'frame'; (flixer frames are NOT scripted — the app uses its trusted-tap path for flixer, L341 comment). If no target at all → 'frame' (L344: even with no target, returns 'frame' so the app falls back to tapping).

## Play-block handling (347–358)
- L349: `playBlockedAt` (ms timestamp), `playBlockedVideo` (the refused video), `playAttempt` (generation counter).
- L350 `cancelPlayAttempt()`: increments generation, clears blocked state.
- L351–358 `safePlay(v)`: calls `v.play()`; if it returns a promise, `.catch(refused)`; if it throws → refused. `refused` only records if the attempt is still current (generation match) and the video is still paused: sets `playBlockedAt = Date.now()`, `playBlockedVideo = v`. Purpose (L347–348 comment): when the browser refuses play() (no user gesture yet), the app can tap the player — a real tap is the user gesture the browser wants.

## CSS fullscreen subsystem (360–450)
- L362–374 comment: **Why CSS fullscreen instead of native**: Chromium's native WebView FullScreenView draws a center play/pause button that stays on screen while paused. Instead: make the player container page-level CSS fullscreen (position:fixed over viewport, black bg); site's own player UI (auto-hides on idle) stays in charge; native FullScreenView never created → center button never drawn. Players like Clappr check `document.fullscreenElement` and listen for `fullscreenchange`, so both are faked: while active, `fullscreenElement` reports our element (patching happens later in file), and a synthetic `fullscreenchange` is dispatched on enter/exit. **Flixer keeps the native path** (its document returns early — see later in file).
- L376 `cssFs` state: `{active, el, saved, frameChild, resumeTimer, wasPlaying}`.
- L377 `__nativeFsFns = {}` — stash for original native fullscreen fns (patched later in file).
- L379–384 `cssFsSave(el)`: saves inline style values+priorities for: position, left, top, right, bottom, width, height, margin, z-index, background, object-fit.
- L386–389 `cssFsRestore(el, s)`: restores saved inline styles.
- L391–393 `cssFsNotifyApp(on)`: calls `window.Buccaneer.cssFullscreen(bool)` (the @JavascriptInterface method) — tells Java CSS fullscreen is on/off.
- L395–397 `cssFsPostParent(on)`: posts `{__fbFs: 1, on: 0|1}` to parent (cross-frame CSS-fs bubbling).
- L401–407 `cssFsTarget(el)`: a bare `<video>` becomes its **player container** (via `playerContainer(el)`, defined later) so the site's own controls come along; else the element itself.
- L409–430 `cssFsEnter(el)`: no-op if already active. Saves styles; sets (all `!important`): position:fixed, left/top/right/bottom:0, width:100vw, height:100vh, margin:0, z-index:2147483647, background:#000; if VIDEO → object-fit:contain. Sets `cssFs.el`, `cssFs.active=true`; dispatches synthetic `Event('fullscreenchange')` on document; if top frame → `cssFsNotifyApp(true)` (Java), else `cssFsPostParent(true)`; installs resume guard; returns resolved Promise (mimics requestFullscreen's promise).
- L432–450 `installFsResumeGuard(el)` (start): **auto-resume-on-pause logic**. Entering CSS fullscreen resizes the player container/iframe; on some players (notably the vidbolt player inside a cross-origin iframe) that resize **pauses the video**. If the video was playing before enter, watch for the first ~2.5 s and resume if the transition paused it. Short window so a deliberate later user pause is never fought. Only active in the frame that actually holds the `<video>`; in an ancestor frame `el` is the `<iframe>` and no video is reachable → no-op there. Implementation: clears any prior `resumeTimer`; `findVideo()` = cssFs.el if VIDEO else `querySelector('video')`; if no video, or paused/ended → `wasPlaying=false`, return; else `wasPlaying=true`, `checks=0` (timer loop continues in next chunk).
# page_helpers.js — Chunk 2 (lines 451–900)

## CSS fullscreen completion (451–473)
- L451–459: `installFsResumeGuard` timer: `setInterval` every **250 ms**; max **10 checks** (~2.5 s window, L453). On each tick: if `!cssFs.active || checks > 10` → clear & stop. If the video is `paused && !ended` → `safePlay(vv)` (resume) and stop the timer. So: at most one auto-resume attempt within 2.5 s of entering CSS fullscreen.
- L462–473 `cssFsExit()`: no-op unless active; clears `active`, `el`, `resumeTimer`, `wasPlaying`; `cssFsRestore(el, saved)`; dispatches synthetic `fullscreenchange`; top frame → `cssFsNotifyApp(false)` (Java), else `cssFsPostParent(false)`.

## Controls auto-hide "pointer poke" (475–555)
- L475–482 comment: player controls auto-hide on **pointer idle**; a TV remote is not a pointer, so on CineJoy-style sites the controls (and big center play/pause button) stay up forever. Fix: while video is fullscreen, dispatch a **synthetic mousemove on the video every 1.5 s** — same pointer activity a mouse would produce, triggering the player's own idle logic. No CSS/class changes. Pause button is a child of the control bar, hides with it. **Gated behind the flixer guard** (flixer's player must not get synthetic input).
- L483–555 `installControlsAutoHide()`: idempotent via `window.__fbAutoHideInstalled`.
  - `poke()` (488–519): finds the fullscreen video (fsElement, or first playing+visible video via `collect(document, 2, ...)`); dispatches `new MouseEvent('mousemove', {bubbles: true, cancelable: true, view, clientX/Y = video center, screenX/Y, button: 0, buttons: 0})` on the video.
  - `schedule()` (520–540): **bounded pokes**: `maxPokes = 2`, interval 1500 ms. Comment (522–527): a few pokes start the idle timer on CineJoy-style players (controls stay up forever without pointer events); poking **continuously** would keep resetting the idle timer on MovieBite-style players that auto-hide on their own, so their controls never go away. Hence: poke a bounded number of times (2), then stop and let the player's own idle logic take over.
  - `onFsChange()` (541–552): on `fullscreenchange`/`webkitfullscreenchange` (capture phase, L553–554): if fullscreen element exists → `active=true`, `schedule()`, plus `setTimeout(poke, 600)` to hide controls right away; else clear timer.
  - **Flixer exclusion**: the install call itself is guarded later in the file (see chunk 3) — the function exists in every frame but is only *installed* on non-flixer documents.

## Native fullscreen entry (557–568)
- L557–568 `enterFullscreen(el)`: tries `el.requestFullscreen || el.webkitRequestFullscreen || el.webkitRequestFullScreen || el.mozRequestFullScreen || el.msRequestFullscreen` (call, swallow promise rejection); else if VIDEO with `el.webkitEnterFullscreen` (legacy WebKit video fullscreen) → call it.

## Player container heuristic (572–595)
- L572–578 comment: **player root** = first ancestor of the video that holds the player's own control buttons. Making THAT element CSS-fullscreen keeps site controls visible and matches the container the player tracks for fullscreen state. A pure size-based walk can't stop when the video fills the viewport (every ancestor same size) and ends at the page wrapper → **the button check is the primary signal; the size walk is the fallback** for players without in-page buttons.
- L579–595 `playerContainer(v)`: walks up from video (max 6 hops, stops at body/html): for each parent `p`: if `parea <= area * 3` (not much bigger than the video) **and** `p.querySelector('button')` exists → **return p** (button-containing ancestor = primary signal). If `parea > area * 1.35` → break (too big, stop). Else `best = p`, continue. Returns `best` (fallback: last size-acceptable ancestor, or the video itself).

## Text fields (597–631)
- L599 `TEXT_TYPES = ['', 'text', 'search', 'email', 'url', 'tel', 'number', 'password']`.
- L601–610 `editableOf(el)`: TEXTAREA → el; INPUT with type in TEXT_TYPES → el; `el.isContentEditable` → el; else null.
- L612–624 `activeEditable()`: `document.activeElement`, walking **down** through up to 3 nested same-origin iframes (activeElement in each frame's document), then `editableOf`.
- L627–631 `setNativeValue(el, value)`: sets value via the **native prototype setter** (`HTMLTextAreaElement.prototype` or `HTMLInputElement.prototype` `value` descriptor) so React/Vue-style frameworks notice the change (bypasses framework-patched instance setters).

## Public API `window.__fb` (633–900, part 1)
- L635 `fullscreenRequest = 0` — generation counter for `cancelFullscreen`.
- L636 `window.__fb = { ... }` — the complete API surface (Java calls these via evaluateJavascript in the top frame):
  - **L637–641 `cancelFullscreen()`**: bumps `fullscreenRequest` (invalidates any in-flight fullscreen request), removes `#__fb_fs_overlay` element if present. Returns 'ok'. (The overlay is created by the fullscreen intercept — see later chunks.)
  - **L643–650 `exitCssFullscreen()`**: the app's **Back key** handler for CSS fullscreen. If not active → 'none'. Clears `frameChild`, `cssFsExit()`, posts `{__fbFs: 1, on: 0}` to the child frame (so the child exits its internal CSS fs). Returns 'ok'.
  - **L652 `cssFullscreenActive()`**: returns 1 while CSS fullscreen is active, else 0.
  - **L653 `selectAt(fx, fy)`**: takes **fractions** (0..1) of screen, converts to px, calls the internal `selectAt` (tap-point player selection). Returns 'ok'|'frame'|'none'.
  - **L654 `focusTarget()`**: `pickTarget()`; focuses `t.frame || t.video`; returns 'frame' if it's a frame, 'ok' if a video, 'none' if no target/focus failed.
  - **L655–660 `setDiagnosticLogging(enabled)`**: sets `diagnosticEnabled`; if **not flixer** and enabled → `scheduleHealthSample()` (immediate health sample); if not flixer and disabled → `clearTimeout(healthTimer)`. Returns 1/0. (Flixer never runs the health sampler.)
  - **L661–663 `togglePlay()`** → `videoOp('togglePlay')`.
  - **L665–667 `play()`** → `videoOp('play')`.
  - **L670–673 `playBlocked()`**: returns 1 if the browser refused a `play()` within the **last 1500 ms** AND the current target video is the blocked one AND it's still paused → the app should tap the player (real tap = user gesture).
  - **L676–686 `pause()`**: pauses **every** video reachable in this document + same-origin iframes (depth 2) — also used when the app goes to background. Then if there's a big opaque frame that's **not flixer** → `forwardToFrame(f, 'pause')` → 'ok'. Returns 'ok' if local videos paused, 'frame' if only a frame existed, 'none' if nothing.
  - **L688–690 `seek(delta)`** → `videoOp('seek', delta)` (delta in seconds).
  - **L692–694 `volume(delta)`** → `videoOp('volume', delta)` (delta, e.g. ±0.1).
  - **L696–698 `toggleMute()`** → `videoOp('toggleMute')`.
  - **L702–714 `unmute()`**: force-unmute — sets `muted=false` directly on every video in this document (player's own UI may be broken, e.g. cross-origin frame whose control script throws). If any changed → 'ok'. Else if target is a non-flixer frame → forward 'unmute' → 'frame'. Else 'none'.
  - **L719–734 `muteButton()`**: where the player's mute button is, **as a fraction of the screen**: the **last visible button** in the player's control bar (mute is rightmost on most players). Used when the video is in a frame we cannot script: the app taps this spot. Returns JSON `{ok:0}` or `{ok:1, x, y}` (fractions).
  - **L737–765 `playerControls()`** (diagnostic): dumps the player's control bar structure. Finds the first `div/ul/nav` containing **3+** `button`/`[role="button"]` elements; returns JSON `{ok:1, barClass (≤80 chars), buttons: [{cls (≤50), aria, title, text (≤20), visible, x, y (px center)}]}`. If no bar with 3+ buttons → `{ok:0, note:'no control bar with 3+ buttons'}`.
  - **L772–816 `tapStack(x, y)`** (diagnostic): full element stack at a tap point via `document.elementsFromPoint` (frontmost first — first entry eats the tap). For each element: tag, id, cls (≤60), position, zIndex, pointerEvents, opacity, w/h, `coversVideo` (1 if it overlaps a ≥100×100 video), `isControl` (1 if inside/itself a button/[role=button]/.player-controls/.control/video). Returns JSON `{ok:1, x, y, topFrame, url (≤120), count, stack}`. Purpose: if the frontmost element isn't a control, something (an ad layer) covers the player UI — names the exact layer so `killOverlays()` can target it.
  - **L822–859 `tapStackAll(x, y)`** (diagnostic): this frame's own stack (`this.tapStack`) + walks **all iframes** ≥50×50: for each, records src (≤120), rect, `tapInside`; same-origin frames → calls `f.contentWindow.__fb.tapStack(fx, fy)` directly (mapped coords) and includes the stack; cross-origin → marks `crossOrigin: 1` (the ad layer lives in there; app confirms tap fell inside via rect). This is how we see inside the vidcore player frame.
  - **L863–883 `playerGeometry()`** (diagnostic): top frame → first iframe's rect + winW/winH; non-top frame → first video's rect + winW/winH. JSON.
  - **L887–900+ `frameCenter()`** (start): where to tap to press play on a player we cannot script: middle of its **visible part**, as a **fraction of the screen** (zoom-independent). `pickTarget()` → el; getBoundingClientRect; then walks up through up to 3 ancestor frames, mapping the rect through each frame's border (clientLeft) and scale (`sx = fr.width/(frame.offsetWidth||fr.width)`, same for y), clipping to each frame's viewport (innerWidth/innerHeight). Continues in next chunk.
# page_helpers.js — Chunk 3 (lines 901–1350)

## frameCenter completion (901–914)
- L901–904: continues the ancestor-frame rect mapping loop (up to 3 levels): maps left/right/top/bottom through each frame's `clientLeft`/`clientTop` border and scale `sx`/`sy`; if the walk never reaches the top `window` → `{ok:0}`.
- L906–913: final clip to the top window viewport (`innerWidth`/`innerHeight`); if no visible area → `{ok:0}`; else returns `{ok:1, x: (centerX)/w, y: (centerY)/h}` — **fractions of the screen**, zoom-independent.

## `diag()` (916–963)
- Report for Settings > Diagnostics: what each video is really doing + which codecs the WebView claims to support.
- `out = {videos: [], frames: countOpaqueFrames(), mse: null}`.
- Up to **4** videos (from `collect(document, 2, all)`). Per video: `w,h` (rect), `vw,vh` (videoWidth/Height), `rs` (readyState), `ns` (networkState), `p` (paused), `m` (muted), `vol` (2dp), `rate` (playbackRate 2dp), `t` (currentTime), `d` (duration if finite), `ba` (buffered-ahead seconds: `buffered.end(last) - currentTime`, -1 if none), `src` (currentSrc||src), `e` (error code), `fr` (totalVideoFrames), `dr` (droppedVideoFrames) from `getVideoPlaybackQuality()`, `vis` (visible 0/1).
- **MSE codec probe** via `window.MediaSource || window.WebKitMediaSource` `isTypeSupported`: `h264` = `video/mp4; codecs="avc1.640028"`, `hevc` = `video/mp4; codecs="hvc1.1.6.L93.B0"`, `vp9` = `video/webm; codecs="vp9"`, `av1` = `video/mp4; codecs="av01.0.05M.08"`. Returns JSON.

## `state()` (965–984) — the state contract
- `pickTarget()`.
- If no local video: if target is a **frame** and `lastFrameState` exists and is **< 10000 ms old** (10 s freshness, L969) → return that frame's last reported state JSON (merged from the `__fbRes` message). Else `{f: 0, fr: 1}` (frame present but no fresh state) or `{f: 0, fr: 0}`.
- If local video: returns `{f: 1, p: paused, t: currentTime, d: duration (0 if not finite), h: videoHeight, v: volume, m: muted}`.
- **Field semantics**: `f` = "have a real video here" (1/0), `fr` = "player is in a frame" (1/0), `p` paused, `t` time, `d` duration, `h` video height, `v` volume, `m` muted. Java parses this JSON to drive the UI.

## `prepareFullscreen()` (986–1042) — the user-gesture bridge
- Purpose (L986–987): puts a **transparent layer over the page**; the app then taps screen centre; that **real tap** is the user gesture browsers require before allowing fullscreen.
- L989: `request = ++fullscreenRequest` (generation).
- L990–1001: pick target: if target is a video in another frame → the iframe element (`win.frameElement`); else `playerContainer(v)`; if target is a frame → the frame. No target → 'none'.
- L1004–1010: removes any old `#__fb_fs_overlay`; creates a new `<div id="__fb_fs_overlay">` with `position:fixed; left/top:0; width:100vw; height:100vh; z-index:2147483647; background:rgba(0,0,0,0.01)` (nearly transparent).
- L1013–1034 `handler(e)`: on the first pointerup/touchend/mouseup/click (capture): `preventDefault`+`stopPropagation`; if generation stale → just remove. If not done: mark done; **if target is a flixer frame** → call the **native** `requestFullscreen`/`webkitRequestFullscreen` (from `__nativeFsFns`) on the target (Flixer keeps the native fullscreen path — trusted tap + PowerVR constraints); **else** → `enterFullscreen(target)` (which is intercepted → CSS fullscreen); and if target is a non-flixer IFRAME → post `{__fbFsCmd: 1, on: 1}` into it so the frame goes CSS-fullscreen internally (its player + fullscreen state match the now-fullscreen iframe). Then `setTimeout(remove, 500)`.
- L1035–1038: listeners on the overlay: `pointerup`, `touchend`, `mouseup`, `click` (all capture).
- L1039: append to body (or documentElement).
- L1040: safety timeout — if no tap within **4000 ms**, remove the overlay.
- Returns 'ok'.

## Typing API (1044–1080)
- **L1045–1051 `field()`**: what's in the focused text field (the keypad mirrors it). `activeEditable()`; if none → `{ok:0}`. `isPw` = INPUT type password. `val` = `el.value` if defined else `el.textContent`. Returns `{ok:1, v: String(val), pw: 0|1}`.
- **L1054–1065 `focusIntoView()`**: keeps the field in the **top part** of the screen (keypad covers the bottom). `activeEditable()`; `el.scrollIntoView({block:'center', inline:'nearest'})`; if in this document, `window.scrollBy(0, (r.top + r.height/2) - innerHeight*0.22)` to push it toward the top 22% line. Returns 'ok'/'none'.
- **L1067–1080 `clear()`**: clears the active editable. If it has `.value` → `setNativeValue(el, '')` (native setter, framework-aware); else `el.textContent = ''`. Dispatches `input` + `change` (bubbling) so frameworks update. Returns 'ok'/'none'.

## `setQualityPreference()` (1082–1089)
- Sets the preferred quality order. The app calls this **once at startup**. Preference default: **480p first, then 720p, then others, 1080p last resort**. `qualityPreference = Array.isArray(order) ? order.slice() : [480,720,1080]`. Returns 'ok'/'none'.

## Quality preference subsystem (1092–1230)
- L1093–1095 comment: prefer lower resolutions 480p > 720p > others > 1080p. Hooks into HLS.js and Shaka players when they become available. **Gated behind the "Prefer low quality streams" setting** (bridge: `lowQualityEnabled`).
- L1096 `qualityPreference = [480, 720, 1080]` (default).
- L1098–1104 `lowQualityOn()`: returns `window.Buccaneer.lowQualityEnabled() === true` (the Java bridge).
- L1106–1132 `pickPreferredLevel(levels)`: `levels` = array of `{height, ...}`. Scores each: exact match to a preferred height → score = its index (lower = better); non-exact → `100 + minDistance` to closest preferred height. Sorts ascending, returns index of best (or -1).
- L1134–1144 `applyQualityHls(hls)`: guard — must have `levels` array and not already set (`__fbQualitySet`); must `lowQualityOn()`. Picks preferred level, sets `hls.currentLevel = idx`, marks `__fbQualitySet`, emits `diagnostic('quality_set', {player:'hls', level, height, url})`.
- L1146–1175 `applyQualityShaka(player)`: guard — must have `getVariantTracks` and not already set; must `lowQualityOn()`. Gets `getVariantTracks()`, scores each track's `height` the same way, picks best, `player.selectVariantTrack(best, true)`, marks `__fbQualitySet`, emits `diagnostic('quality_set', {player:'shaka', height, url})`.
- L1179–1230 `scanForQualityPlayers()`: **when it registers** — runs on a **1000 ms interval** until a player is found (players load async). Stops after **30 attempts (~30 s)** (L1185). Each tick:
  - Iterates **all `window` keys** (L1188): an object with `levels` array + `loadSource` function → HLS.js instance → `applyQualityHls`; an object with `getVariantTracks` + `load` function → Shaka → `applyQualityShaka`.
  - Also checks `window.shaka && window.shaka.player`: looks at `document.querySelector('video')` for `video.shakaPlayer || video.__shakaPlayer`; and common global names `['player','shakaPlayer','videoPlayer','streamPlayer']`.
  - Stops the interval once `found`.
- L1232 comment: "(quality scan listener is registered after the flixer guard below)" — i.e. the actual `scanForQualityPlayers()` call / its trigger happens after the flixer early-return (see chunk 4).

## One-video-at-a-time setup + diagnostics init (1234–1268)
- L1234–1238 comment: **one video at a time** — weak TV hardware has room for one video decoder; when a hidden preview, an ad and the real player all start together you get two soundtracks and a black picture. The app (through the `Buccaneer` bridge, present in every frame) says whether this is switched on and which frame started a real player last; every frame then stops its own media when another frame took over.
- L1239 `documentId = bridgeCall('currentDocument') || ''` — the Java bridge reports a stable id for this document.
- L1240 `frameId = documentId + ':' + random + '-' + timestamp` — unique per frame.
- L1242–1248 `bridgeCall(name, arg)`: helper to call `window.Buccaneer[name](arg)` safely (returns undefined if absent/throws).
- L1250–1254 `diagnosticEnabled`: initialized from `window.Buccaneer.isDiagnosticsEnabled()`.
- L1256–1268: if diagnostics enabled, emit **`frame_script_alive`** once (proves the script is alive in this frame): payload `{url, topFrame: 0|1, hasVideo: 0|1}` via `b.diagnosticEvent('frame_script_alive', JSON.stringify(...))`.

## FLIXER GUARD — the hard stop (1270–1275)
- L1270–1274 comment: **Flixer: stop here.** The passive `__fb` API above is available for explicit Android-driven commands (video controls, typing). Everything below installs **continuous capture-phase event listeners, media health timers, HLS constructor hooks, and synthetic click forwarding** — all of which add GPU/CPU load that triggers the **PowerVR driver crash during flixer's intro animation**.
- **L1275: `if (__isFlixer) return;`** — THE flixer guard. Everything below this line is **never installed on flixer**.

## Post-flixer installs (1277–1350)
- L1277–1285: **Controls auto-hide install** (non-flixer only). `__isYouTube = /(^|\.)youtube\.com$|(^|\.)youtu\.be$/.test(__host)`. Comment (1280–1283): YouTube (and other show-on-activity players) do the OPPOSITE of hide-on-idle — any pointer activity re-shows controls, which hide after ~3 s. Our 1.5 s mousemove fires before that hide timer expires, so it would **perpetually re-wake YouTube's controls** (they never stay hidden). So the synthetic poke is **skipped on YouTube**; its own idle logic does the hiding. → `if (!__isYouTube) installControlsAutoHide();`
- L1287–1290: **CSS fullscreen intercept** — replaces native fullscreen with CSS fullscreen so Chromium's native FullScreenView (and its persistent center button) is never created. Runs at document start, before page scripts; never on flixer (returned above).
- L1290–1304 `installCssFullscreenIntercept()` (IIFE, idempotent via `window.__fbFsIntercept`):
  - **Patches `window.Element.prototype`** (L1293) for names `['requestFullscreen','webkitRequestFullscreen','webkitRequestFullScreen','mozRequestFullScreen','msRequestFullscreen']`. For each that exists: stashes the original in `__nativeFsFns[n]`, replaces with a function that calls `cssFsEnter(cssFsTarget(this))` (returns a rejected promise if no target).
  - L1305–1326: **legacy media API**. Comment: players on Android try `video.webkitEnterFullscreen()` BEFORE `requestFullscreen()`, and on this WebView the method lives on **`HTMLVideoElement.prototype`** (`HTMLMediaElement.prototype` has none), so **both prototypes must be covered** or the player's first-choice path goes native. Iterates `[window.HTMLVideoElement, window.HTMLMediaElement]` prototypes: if `webkitEnterFullscreen` exists → stash in `__nativeFsFns.webkitEnterFullscreen`, replace with `cssFsEnter(cssFsTarget(this))`. If `webkitExitFullscreen` exists → stash, replace: if `cssFs.active` → `cssFsExit()`, else call the native one.
  - L1327–1350: **Fake the document's fullscreen state** so player logic (Clappr and friends) sees a normal fullscreen session. Gets `docProto = Object.getPrototypeOf(document)`; captures native `fullscreenElement` and `fullscreenEnabled` property descriptors. Then `Object.defineProperty(document, 'fullscreenElement', {configurable:true, get: ...})` → returns `cssFs.el` when active, else falls back to the native getter (or `document.webkitFullscreenElement`). Similarly `fullscreenEnabled` → true when active, else native (default true).
# page_helpers.js — Chunk 4 (lines 1351–1800)

## CSS fullscreen intercept completion (1351–1362)
- L1351–1355: `document.exitFullscreen` override: if `cssFs.active` → `cssFsExit()` + resolved promise; else call native `docProto.exitFullscreen`.
- L1356–1360: `document.webkitExitFullscreen` override: same pattern (cssFsExit if active, else native).
- L1361: the whole document-state faking is in a try/catch — "if the document state cannot be faked, players may misbehave, but fullscreen itself still works".
- L1362: end of `installCssFullscreenIntercept()` IIFE.

## StreamCorner sandbox remover (1364–1430)
- L1364–1367 comment: **StreamCorner** — remove `sandbox` attribute from player iframes **BEFORE they load**; the player checks its security context and refuses to play if sandboxed; must happen before the iframe navigates. `installSandboxRemover()` called.
- L1372–1430 `installSandboxRemover()` (idempotent via `window.__fbSandboxRemoverInstalled`):
  - **Hooks `document.createElement`** (L1377–1396): wraps it; when creating an `iframe`: immediately `removeAttribute('sandbox')`, sets `allowfullscreen="allowfullscreen"`, and appends `fullscreen` to the `allow` attribute if absent (element API requires this permission for cross-origin frames; without it the player's fullscreen button is a no-op). Emits `diagnostic('sandbox_removed_early', {url})` if diagnostics on.
  - `removeSandboxFromIframes()` (L1399–1420): queries `iframe[sandbox], iframe:not([allowfullscreen])`; for each: removes sandbox, adds allowfullscreen, adds `fullscreen` to `allow`; emits `diagnostic('sandbox_removed', {iframeSrc (≤100), url})`.
  - L1423–1426: runs immediately + on a **500 ms interval**, stops after **30 s** (iframes should be loaded by then).
  - L1429: also calls `installStreamCornerErrorHider()`.

## StreamCorner error hider (1432–1465)
- L1432–1433 comment: the StreamCorner player shows a "Remove sandbox attributes" error overlay **even when the video is playing**; hide it so the user can see the video.
- L1434–1465 `installStreamCornerErrorHider()` (idempotent via `window.__fbScErrorHiderInstalled`):
  - `hideErrorOverlay()`: finds `[class*="error"], [class*="sandbox"], [id*="error"]` whose text matches `/sandbox|remove.*attribute/i` → `display:none`; emits `diagnostic('sc_error_hidden', {text (≤100)})`. Also finds `div[style*="position: absolute"]`/`position:absolute` overlays with the same text → hide; emits `diagnostic('sc_overlay_hidden', {text})`.
  - L1461–1464: runs immediately + **1000 ms interval**, stops after **60 s**.

## Quality scan trigger (1468–1501)
- L1468–1470 comment: start quality preference scanning when a video element appears (player loading). **Registered after the flixer guard**: the interval scan adds CPU load that triggers the PowerVR driver crash during flixer's intro animation.
- L1471–1501: `document.addEventListener('loadedmetadata', handler, true)` (capture). On any media `loadedmetadata`:
  - If `!qualityScanTimer` → `scanForQualityPlayers()` (starts the 1 s interval scan).
  - Emits `diagnostic('player_scan', {url, topFrame, hls: 0|1 (window.Hls is function), shaka: 0|1 (window.shaka defined), videoCount, iframeCount, windowKeys: up to 10 keys tagged ':hls'/':shaka'/':loadSource'})`.
  - `installNetworkProbe()` (start network request logging for MSE segment analysis).
  - `scanForBitmovin()`.

## Bitmovin quality (1503–1608)
- L1503–1561 `scanForBitmovin()`: Bitmovin has a quality API (`getAvailableQualities/getQuality/setQuality`). Checks `window.bitmovin.Player` → `diagnostic('bitmovin_found', {source:'window.bitmovin', version})`. Scans window for objects with `getAvailableQualities`. Queries `.bitmovinplayer-container, [data-bitmovin-player]` → `diagnostic('bitmovin_container', {count, id})`. Finds quality/settings button (`.bitmovin-quality-menu, [class*="quality"], .bitmovin-settings-menu, [class*="settings"]`) → `diagnostic('bitmovin_quality_btn', {tag, cls (≤100), id})`. Dumps up to 10 buttons in the container → `diagnostic('bitmovin_buttons', {count, buttons:[{tag, cls (≤60), text (≤30)}]})`. Calls `trySetBitmovinQuality()`. If players/containers found → `diagnostic('bitmovin_scan', {players, containers})`.
- L1565–1608 `trySetBitmovinQuality()`: if `!lowQualityOn()` return. Finds `.bmpui-ui-settingstogglebutton` → `.click()` → `diagnostic('bitmovin_settings_clicked', {})`. After **500 ms**: finds quality options (`.bmpui-ui-qualityoption, [class*="quality"] [class*="option"], .bmpui-ui-settingsmenu [class*="quality"]`) → `diagnostic('bitmovin_quality_options', {count})`. Parses height from each option's text via `/(\\d{3,4})p?/i`, scores by distance to `qualityPreference`, clicks the best → `diagnostic('bitmovin_quality_selected', {text})`. If no menu → `diagnostic('bitmovin_quality_menu_not_found', {})`.

## Network probe (1610–1636)
- L1610–1611 comment: probe network requests to understand what the MSE player downloads; logs segment URLs (m3u8, m4s, ts, mp4) to help identify quality variants.
- L1612–1636 `installNetworkProbe()` (idempotent via `__netProbeInstalled`):
  - **Hooks `window.fetch`** (L1616–1625): wraps it; if the URL matches `/\\.(m3u8|m4s|ts|mp4|mpd)(\\?|$)/i` **or** `/segment|chunk|part/i` → `diagnostic('net_segment', {url (≤200), method})`. Always calls the original fetch.
  - **Hooks `XMLHttpRequest.prototype.open`** (L1626–1635): same URL-pattern check → `diagnostic('net_segment', {url (≤200), method})`. Calls the original open.
  - **URL patterns logged as `net_segment`**: file extensions `.m3u8`, `.m4s`, `.ts`, `.mp4`, `.mpd` (followed by `?` or end), OR any URL containing `segment`, `chunk`, or `part`.

## `diagnostic()` helper (1638–1644)
- L1638–1644: `diagnostic(event, data)` — the single funnel for all diagnostic events. If `!diagnosticEnabled` → return. Calls `window.Buccaneer.diagnosticEvent(String(event), JSON.stringify(data || {}))`. Best-effort (try/catch).

## `mediaDescription()` (1646–1718)
- L1646–1718: builds a rich description object for a media element `m` (used by media diagnostic events + health samples):
  - `getVideoPlaybackQuality()` → frames/dropped.
  - **Buffer analysis** (L1649–1666): comment — "a later disconnected range is not playable buffer at the current position. Bound diagnostic work even for a hostile/fragmented TimeRanges object." `bufferRangeCount = ranges.length`; `bufferRangesTruncated = count > 32`; iterates up to **32** ranges; `bufferAhead` = max(0, end - position) for the range containing the current position (only if not truncated).
  - **frameKind** (L1667–1680): 'top' if `mediaWindow.top === mediaWindow`; else compares `mediaWindow.location.origin` vs `mediaWindow.top.location.origin` → 'opaque' (either origin is 'null'), 'same-origin', 'cross-origin'; on throw → 'cross-origin-or-opaque'. Comment: compare with the top, not this injection's own window; a denied top read means cross-origin or opaque sandbox, not same-origin.
  - **canvas/webgl** (L1681–1685): comment — "Diagnostic reads must not create a GL context on a page-owned canvas. getContext() is not a read-only capability check: it can allocate GPU state or lock an unused canvas to the wrong renderer. Report the state as unknown." So `webgl = -1` always (never calls getContext); `canvasCount` = number of `<canvas>`.
  - Output object: kind (tagName), pageUrl, topFrame (0/1/-1), visible, frameKind, canvasCount, webgl(-1), src, width, height, readyState, networkState, paused, muted, volume, playbackRate, currentTime, duration, bufferAhead, bufferRangeCount, bufferRangesTruncated, bufferRanges, error. Plus `frames`/`dropped` if quality available.

## Diagnostics refresh + media events (1720–1788)
- L1720 `geometryReported = false` — one-shot flag.
- L1721–1726 `refreshDiagnostics()`: reads `bridgeCall('isDiagnosticsEnabled')`; if it's a boolean and differs from `diagnosticEnabled` → `window.__fb.setDiagnosticLogging(enabled)`. Comment: "Existing media events/coordination refresh the native flag in each frame. No new idle-frame bridge poll, timer, message channel or Flixer instrumentation."
- L1728–1738 `diagnosticMediaEvent(e)`: `refreshDiagnostics()`; if enabled and `isMedia(e.target)` → `diagnostic('media_' + e.type, mediaDescription(e.target))`. Also: **once**, when `e.type === 'playing'` and not yet reported → `diagnostic('player_geometry', window.__fb.playerGeometry())` (the frame hosting the video reports the video rect; the top frame reports the iframe rect).
- L1740–1742 `isMedia(el)`: VIDEO or AUDIO.
- L1744–1751 `playingMedia()`: all `video, audio` in this document that are not paused and not ended.
- L1753–1757 `isRealPlayer(m)`: a real player = AUDIO that is not muted, OR a visible video that is not a muted background loop (`!(m.muted && m.loop)`).
- L1759–1761 `pauseQuietly(m)`: `m.pause()` in try/catch.
- **L1763–1775 `play` listener** (capture): if media and `bridgeCall('exclusiveEnabled') === true`:
  - If `isRealPlayer(m)`: pause **all other** playing media in this frame (`playingMedia()` excluding m), then `bridgeCall('mediaStarted', frameId)` (tell Java this frame started a real player).
  - Else (not a real player) and `bridgeCall('activeOwner')` is truthy (some frame owns a real player) → `pauseQuietly(m)` (hidden/background media waits while a real player is running).
- **L1777–1782 `pause`/`ended` listeners** (`mediaStopped`, capture): if media and `exclusiveEnabled === true` and `playingMedia().length === 0` → `bridgeCall('mediaStopped', frameId)` (this frame no longer has any playing media).
- **L1784–1787**: `MEDIA_DIAG_EVENTS = ['loadedmetadata','canplay','play','playing','waiting','stalled','pause','ended','error','emptied']` — registers `diagnosticMediaEvent` (capture) on **all 10** of these. So the diagnostic event names are `media_loadedmetadata`, `media_canplay`, `media_play`, `media_playing`, `media_waiting`, `media_stalled`, `media_pause`, `media_ended`, `media_error`, `media_emptied`.
- **L1788**: `document.addEventListener('playing', function(){ scheduleHealthSample(); }, true)` — a health sample is scheduled whenever media starts playing.

## Media health / playback heartbeat (1790–1800, start)
- L1790–1792 comment: **Opt-in health samples**: enough to distinguish buffering from dropped-frame/render pressure. **No repeating timer while diagnostics are OFF**; the sample reschedules itself only while a media element is actively playing AND diagnostics remain enabled.
- L1793 `healthTimer = 0`.
- L1794–1800 `scheduleHealthSample()` (start): if `!diagnosticEnabled || healthTimer` → return. Sets `healthTimer = setTimeout(function(){ healthTimer = 0; refreshDiagnostics(); if (!diagnosticEnabled) return; var playing = playingMedia(); ...` — continues in next chunk. So it's a **self-rescheduling, play-gated** sampler (not a fixed continuous timer).
# page_helpers.js — Chunk 5 (lines 1801–1911)

## Media health sampler completion (1801–1812)
- L1801: if no playing media → return (stop rescheduling).
- L1802–1803 comment: "Use the same local target selection as remote controls; do not silently attribute the first playing advertisement to a selected opaque player."
- L1804–1809: `target = pickTarget()`; if `target.video` and its `ownerDocument === document` → `mediaDescription(target.video)`, set `description.sampleScope = 'local-selected-player'`, emit **`diagnostic('media_health', description)`**.
- L1810: `scheduleHealthSample()` — reschedule.
- L1811: the `setTimeout` interval is **3000 ms**. So: **health samples every 3 s, only while (a) diagnostics enabled AND (b) at least one media element is playing in this frame** — self-rescheduling, play-gated, opt-in. No timer exists while diagnostics are OFF or nothing is playing.

## Cross-frame coordination fallback timer (1815–1833)
- L1815–1816 comment: cross-frame coordination cannot be event-driven (another frame only tells the app), so this is a **fallback check**. **2000 ms** is enough: the play/pause events above handle the common case.
- L1817–1833: `setInterval(..., 2000)`:
  - `playing = playingMedia()`; if none → return.
  - `refreshDiagnostics()`.
  - If `bridgeCall('pageIsForeground') === false` → **pause all playing media** (backgrounding) and return.
  - If any playing media is `isRealPlayer` → `bridgeCall('playbackActive', documentId)` (report active playback to Java; break after first).
  - If `playing.length === 0 || bridgeCall('exclusiveEnabled') !== true` → return.
  - `owner = bridgeCall('activeOwner')`; if `owner && owner !== frameId` (another frame owns the real player) → **pause all this frame's playing media** (enforces one-video-at-a-time across frames).

## Real-tap click listener (1835–1897)
- L1835 comment: a **real tap** (not a script click) that ends with a text field focused → tell the app.
- L1836: `document.addEventListener('click', handler, true)` (capture).
- L1837: **`if (!e.isTrusted) return;`** — only genuine user taps (script-dispatched clicks are ignored).
- L1838–1862: **tap forwarding into iframes** — comment: if the tap landed on the player iframe, forward it into the iframe; the page's own container can swallow touches that should reach the embedded player's controls. Finds the iframe (`e.target` or `e.target.closest('iframe')`); if the tap point is inside the iframe's rect: computes fractional position `fx, fy`; maps to the iframe's inner size (`innerWidth/innerHeight`); dispatches a synthetic `MouseEvent('click', {bubbles, cancelable, view: f.contentWindow, clientX/Y, screenX/Y})` on `doc.elementFromPoint(...)` (or documentElement). Cross-origin → caught/ignored (cannot forward).
- L1863–1874: if `diagnosticEnabled` → emit **`diagnostic('click', {pageUrl, topFrame, target: tag+id, inPlayerUi: 0|1 (inside button/[role=button]/.player-controls/.control/video), x, y})`**.
- L1875–1896: after **40 ms** (let focus settle): `ae = document.activeElement`; `ed = editableOf(ae)`. Comment (1879–1881): **only open the keypad when the tap actually landed on the field itself** — otherwise a tap on the player/video area can leave a chat box focused as a side effect and pop the keypad unexpectedly (StreamCorner chat, etc.). `onField` = `e.target === ed` OR `ed.contains(e.target)` OR (the tapped element's `closest('label')` contains `ed` — a label/wrapper around the field counts as a deliberate tap on it). If `ed && onField && window.Buccaneer.editableClicked` → **`window.Buccaneer.editableClickedForDocument(documentId)`** (tells Java to open the keypad).

## Click-listener confirmation (1899–1910)
- L1899–1910: if `diagnosticEnabled`, emit **`diagnostic('click_listener_registered', {url, topFrame})`** — confirms the click listener is registered in this frame.
- L1911: end of IIFE.
