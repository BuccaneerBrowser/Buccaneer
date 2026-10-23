# MainActivity.kt PART A — Chunk 1 (lines 1–450)

## 1–55: Package, imports
- Line 1: `package com.buccaneer.browser`.
- Lines 3–54: imports. Notable: `RenderProcessGoneDetail` (renderer-crash recovery), `GeolocationPermissions`, `PermissionRequest`, `androidx.webkit.WebSettingsCompat/WebViewCompat/WebViewFeature` (feature-gated WebView settings), `android.webkit.JavascriptInterface` (the `Buccaneer` JS bridge), `KeyCharacterMap`/`InputDevice` (keycode→char conversion for page key dispatch), `SystemClock` (all timing uses elapsed/uptime millis, not wall clock).

## 56–74: `jsString(raw: String?)` — top-level helper
- Purpose: `WebView.evaluateJavascript` returns JS values as JSON-encoded strings (a string result arrives as `"escaped"`). This unwraps one level of JSON string encoding into the plain string.
- Line 58: guards — returns null if raw is null, the literal `"null"`, longer than 1 MiB, or fails `JsonInputBudget.allows(raw)`. The 1 MiB cap and budget are a defense against page-controlled data blowing up the Kotlin side.
- Lines 59–63: wraps `raw` in a one-element JSON array and parses it with `JSONArray(...).getString(0)` — this decodes JSON string escapes. On parse failure returns `raw` unchanged (non-string results like numbers/objects pass through as their raw JSON text).
- Lines 64–73: second-pass budget check. Many helper results are JSON objects encoded *inside* a JSON string, so the outer transport check is not enough: skip leading whitespace/BOM (line 67), then if the decoded body looks like a JSON object/array — or starts with `#` or `//`/`/*` (Android's lenient parser accepts comments before an object/array; line 72 `jsonLike`) — re-check `JsonInputBudget.allows(decoded)`, else return null. Ordinary scalar strings stay intact.
- Invariant: callers treat null as "no usable result". Changing the budget behavior changes what page data reaches Kotlin (memory-safety surface).

## 76–86: `formatTime(totalSeconds: Double)` — top-level helper
- Formats seconds as `H:MM:SS` (if h>0) or `M:SS` with `Locale.US` (zero-padded, no locale comma issues). Floors to non-negative long seconds. Used by the OSD for video time.

## 88–98: Class declaration + doc comment
- Doc comment (88–97) is the app's operating manual: start page = top-10 bookmarks/folders as buttons; browsing = D-pad moves an on-screen pointer, Select clicks, pointer at screen edge scrolls; keypad via Menu>Address, tapping a text field, or holding Select; video via Play/Pause, Rew/FF, volume keys. All remote keys arrive in `dispatchKeyEvent()` and are routed by what is on screen.
- Line 98: `class MainActivity : Activity(), BookmarkJobs.Listener` — plain `Activity` (not AppCompat), so no theme/fragment machinery; `BookmarkJobs.Listener` receives bookmark file-job results (export/import).

## 100–117: UI fields
- Line 100: `enum class TextMode { NONE, PROMPT, PAGE }` — keypad mode: NONE = no keypad, PROMPT = answering a JS prompt, PAGE = typing into a page input field.
- Lines 102–105: `lateinit` singletons: `ui: Ui` (app UI container), `guard: PopupGuard` (popup-window firewall), `store: BookmarkStore` (bookmark persistence), `audio: AudioManager` (music-active check for idle policy).
- Lines 107–117: `lateinit` views: `root: FrameLayout`, `webView: TvWebView` (the page), `progress: BarView` (loading bar), `cursor: CursorView` (remote pointer), `startPage: StartPage`, `menu: MenuPanel`, `listPanel: ListPanel`, `textPanel: TextPanel`, `keypad: Keypad`, `fullscreenBox: FrameLayout` (hosts custom video view), `osd: Osd` (on-screen display for seek/volume/fullscreen).

## 119–143: Handler + coalesced UI callbacks
- Line 119: single main-looper `handler` — every posted task in the class goes through it.
- Lines 120–122: `navigationHandoff = NavigationHandoff(postDelayed, removeCallbacks)` — a small state machine (defined elsewhere) that serializes navigation commits vs. page teardown; `isPending` is checked by `navigationPending()` (line 1571).
- Lines 123–130: `Heartbeat(epoch, token)` data class + `heartbeatUi = UiCoalescer<Heartbeat>` — coalesces playback heartbeats from the page (at most one pending at a time). On fire: if `epoch == pageEpoch && token == documentToken && pageAvailable() && foreground`, sets `playbackHeartbeatAt = now`. The epoch+token double check means a heartbeat from an old page generation (pre-reload) is dropped — stale "still playing" signals can't keep the screen bright.
- Lines 131–142: `BlockedBatch(epoch, what, count)` + `blockedUi` coalescer with a `merge` function: if epochs differ, the newer batch wins; if same epoch, counts are summed (saturating at Int.MAX). On fire (epoch == pageEpoch && pageAvailable): records `DiagnosticLog.record(this, "popup_blocked", {what, count})` and calls `noteBlocked(what, count)`. This is the popup-blocker accounting path — every blocked popup window is batched here, so a burst of popups produces one diagnostic record + one toast, not N.
- Line 143: `dimOverlay: View` — the idle-dimming overlay.

## 144–196: Idle/dim policy
- Line 144: `idlePolicy = IdleDimPolicy()` — external policy object (thresholds live there, not here).
- Line 145: `rendererRecreatePosted` — dedupe flag: only one "recreate renderer" task may be pending at a time (checked where renderer recovery is requested).
- Line 146: `@Volatile foreground` — set in onResume/onPause; read from coalescer callbacks and the JS bridge thread. Volatile because it crosses threads.
- Line 147: `lastUserInputAt` — elapsedRealtime of last user input; drives the 60s idle query.
- Line 148: `dimmed` — current dim state.
- Lines 149–150: `wakeKey: Int?`, `wakeTouch` — when the screen is dimmed, the first key/touch is consumed to wake (undim) instead of being delivered. `wakeTouch` set in `dispatchTouchEvent`.
- Line 151: `scrollMode` — pointer-at-edge scroll mode (pointer pinned to edge, D-pad scrolls instead of moving).
- Line 152: `@Volatile playbackHeartbeatAt` — last playback heartbeat timestamp; the idle policy's primary "video is playing" signal.
- Line 153: `@Volatile inputFrame` — frame name of the currently focused input (empty = top frame).
- Lines 154–156: `inputFieldState` (JSON state blob from the page describing the focused field), `inputFieldId`, `inputFocusConfirmed` (page confirmed focus on the field).
- Lines 157–162: page-edit machinery: `PageEdit(action, text, session, token, frame, field)` data class; `pageEdits` ArrayDeque (queued edits); `pageEditActive` (edit currently in flight to the page); `nativeEditCancel` (cancellation hook registered by the native editor path); `keyboardDismissedUntil` — until this timestamp, `focusFromPage` refuses to open the keypad (after a deliberate keyboard dismissal, so a stale focus event doesn't re-open it).
- Lines 163–165: injected JS blobs: `fmhyThemeJs` (FMHY theme script), `tvUiJs` (the main `__tvUi` page script), `seekClickJs` (seek-bar click repair script).
- Lines 166–168: `scrollXPending`, `scrollYPending`, `scrollPending` — coalesced scroll deltas waiting to be sent to the page (one wheel event per frame).
- Lines 169–171: `seekRepeat = SeekRepeatPolicy()` (repeat/hold policy for seek keys), `seekGeneration` (generation counter so stale seek completions are ignored), `idleQueryPending` (only one idle-state JS query in flight).
- Lines 172–196: `idleCheck` self-rescheduling Runnable (every 10s):
  - Guard: `!foreground || activityDestroyed` → return (note: it does NOT reschedule in that case — the loop is restarted in onResume).
  - `playing` = (heartbeat within 12s) OR (audio initialized and `audio.isMusicActive`).
  - If playing → `updateIdle(true)`; else if start page shown or no page → `updateIdle(false)`; else if 60s since last input and no query pending → fire a JS probe: `__tvUi.playback()` → 1, else `__fb.state()` (Facebook player state: `f===1` means playing-ish, `p===0` → 1, `fr===1` → -1), else -1. Callback: `"0"` → `updateIdle(false)` (definitely stopped), `"1"` → playing, anything else (null/unknown/opaque player) → `updateIdle(true)` — deliberately keep bright when unknown, never assume stopped.
  - Reschedules itself every 10s at the end (line 194).
- Invariant: the idle loop only runs while foreground; it must be (re)posted in onResume.

## 198–221: Dim + idle helpers
- `setDimmed(value)` (198–203): no-op if dimOverlay not initialized; sets `dimmed`, toggles overlay visibility, `bringToFront()` when dimming (overlay must sit above the page).
- `updateIdle(playing)` (205–209): foreground/destroyed guard, then `setDimmed(idlePolicy.shouldDim(now, playing))`.
- `noteUserInput()` (211–215): the universal "user did something" hook — cancels any pending fullscreen exit, bumps `interactionSerial` (invalidates in-flight page interactions that capture the serial), resets idle activity.
- `resetIdleActivity()` (217–221): stamps `lastUserInputAt`, feeds `idlePolicy.activity()`, undims immediately.

## 223–240: Touch dispatch
- `dispatchTouchEvent` (223–234): on ACTION_DOWN: `wakeTouch = dimmed` (a touch on a dimmed screen is a wake gesture); if not waking, stamp `lastTapAt` (used by `focusFromPage`'s 2s tap-age window); `noteUserInput()`. On ACTION_MOVE: `noteUserInput()` unless it's a wake gesture. If `wakeTouch`: consume the whole gesture (return true) until UP/CANCEL — the wake touch never reaches the page. Otherwise `super.dispatchTouchEvent`.
- `dispatchGenericMotionEvent` (236–240): mouse scroll/hover events. If the event is SCROLL/HOVER_MOVE/HOVER_ENTER → `noteUserInput()`. If the screen was dimmed, swallow it (return true) — a mouse event while dimmed is a wake, not input. Otherwise super.

## 242–254: `focusFromPage(state: String?)`
- Called from the JS bridge when the page reports a focused input field.
- Always `hideIme()` first (line 243) — the native IME is never used; the on-screen keypad is the only keyboard.
- Guards (line 244): page must be available, foreground, and now >= `keyboardDismissedUntil` (a deliberate dismissal suppresses re-opening for a window).
- Lines 245–248: a real recent tap is required: `lastTapAt > 0` and tap age in 0..2000ms and `state != null`. Rationale in comment: media/D-pad activity and resume are not typing requests; a delayed search panel still gets 2s.
- Line 249: `state` must parse as JSON with `ok == 1` (the page's focus probe succeeded).
- Line 251: never open the keypad if a panel is already up: `textMode != NONE` (keypad already open), `customView != null` (native fullscreen video), `cssFullscreenActive`, menu/list/textPanel/startPage shown. Also protects a pinned editor's qualified state from discovery notifications.
- Line 252: if `inputFrame` is empty (top frame), record `inputFieldState = state` (the qualified field state for later sync).
- Line 253: `openPageKeypad()`.

## 255–273: Generation/epoch fields (the staleness guards)
- Line 255: `activityDestroyed` — set in onDestroy; most callbacks check it to no-op after destroy.
- Line 256: `webViewDestroyed` — set in `destroyBrowser`; `pageAvailable()` is false while true.
- Line 257: `recoveringRenderer` — true while a renderer crash recovery is in progress; `pageAvailable()` false, so page work is suspended.
- Line 258: `@Volatile pageEpoch` — **the master generation counter**. Bumped on every WebView destroy/recreate (`destroyBrowser` line 303) and on navigation commits. Every posted page task captures the epoch and refuses to run if it changed (`postForPage` line 281); coalescer callbacks compare it; page-edit sessions embed it. Purpose: after a page teardown, the flood of pending JS callbacks/posted tasks from the old page must all die.
- Line 259: `@Volatile documentToken` — a UUID identifying the *current document* (page load), distinct from pageEpoch which identifies the WebView generation. Bumped per navigation; heartbeats and page edits carry it so a callback from a previous document is dropped even within the same WebView.
- Line 260: `playerIntent` — generation/serial for player-related work (fullscreen/seek commands capture it; a new player intent or navigation invalidates stale player commands).
- Line 261: `editorSession` — session id for the page-edit pipeline; each queued `PageEdit` carries a session; stale sessions (editor closed, page navigated) are dropped in `drainPageEdits`/`finishPageEdit`.
- Line 262: `interactionSerial` — bumped by every `noteUserInput()`; in-flight page interactions (taps, seek repairs) capture it and self-cancel if the user has since interacted (a new interaction supersedes the old one).
- Line 263: `activityInstance` — UUID of this Activity instance (diagnostics: distinguishes log entries across activity recreations).
- Line 264: `pendingFullscreen` — a fullscreen request is queued (e.g., CSS fullscreen pending or custom-view entry pending); `cancelPendingFullscreen()` (called from `noteUserInput`) drops it.
- Line 265: `cssFullscreenActive` — page-driven (CSS) fullscreen state, as opposed to native customView fullscreen.
- Line 266: `seekInFlight` — a seek JS command is in flight; new seeks queue (`queuedSeek`, line 267) instead of racing.
- Line 268: `pendingEvaluations: HashMap<Long, Runnable>` — in-flight `evaluateJavascript` callbacks keyed by `evaluationId` (line 269); removed when the callback fires or the page generation changes (see `resetDocumentWork`).
- Lines 270–271: `osdRefresh` (self-rescheduling OSD updater) + `osdRequest` (monotonic request id so a stale scheduled refresh is ignored).
- Lines 272–273: `popupViews` (live popup WebViews created by the page) + `popupTimeouts` (per-popup destroy timers) — the popup firewall's live view registry.

## 276–282: `pageAvailable()` / `postForPage()`
- `pageAvailable()` (276–277): `::webView.isInitialized && !webViewDestroyed && !activityDestroyed && !recoveringRenderer`. The single gate for "can we talk to the page".
- `postForPage(delayMs, action)` (279–282): captures `pageEpoch` at schedule time; the task only runs if `pageAvailable() && foreground && epoch == pageEpoch`. This is the standard way to schedule any page work with automatic invalidation.

## 284–288: `cancelOsdRefresh()`
- Removes the pending OSD refresh runnable, nulls it, and bumps `osdRequest` — any already-scheduled refresh that compares the request id will see it's stale.

## 290–297: `disposePopup(view: WebView)`
- Removes the popup's timeout runnable; if the view is in `popupViews` (i.e., we own it): remove from parent, `view.destroy()`, log `DiagnosticLog.record(this, "popup_webview_destroyed")`. All in try/catch — a dying popup must never take the app down.

## 299–315: `destroyBrowser(rendererGone: Boolean)`
- The WebView teardown path. Guards: not initialized or already destroyed → return.
- Order (exact): (1) `finishPendingTap(cancel=true, deliver=!rendererGone)` — cancel any pending Select-tap; if the renderer is alive, still deliver the tap (it may have landed); if the renderer died, don't. (2) `webViewDestroyed = true`. (3) `pageEpoch++` — kills all pending page work. (4) `resetDocumentWork()` — cancels document-scoped work (fullscreen pending, player work, pending evals, etc.). (5) `leaveFullscreen(true)` — tear down custom view / CSS fullscreen, notifying the page. (6) `osd.hideNow()` + `cancelOsdRefresh()`. (7) remove webView from parent. (8) If NOT rendererGone: `stopLoading()` and `removeJavascriptInterface("Buccaneer")` — comment at line 309: no calls other than destroy() on a WebView whose renderer died (those calls can hang or crash). (9) `webView.destroy()` in try/catch.
- Invariant: after this, `pageAvailable()` is false; a new WebView must be created (elsewhere) before any page work resumes.

## 317–330: Crash recovery
- `clearCrashRecovery()` (319–323): `@SuppressLint("ApplySharedPref")` — deliberately uses `commit()` (synchronous) because the values must be persisted *before* retrying a page that may crash immediately again. Resets `crashReloadCount`, removes `lastCrashUrl` and `crashWindowStartedAt`, stores `crashReloadCount=0`.
- `showCrashRecovery(url)` (325–330): menu "Page recovery" with "Retry the page" (clear recovery state, `openUrl(url)`) and "Return home" (clear, `showStartPage(true)`); back-callback also goes home.

## 332–387: Settings + browser-state fields
- Line 333: `prefs: SharedPreferences` (lateinit, created in onCreate).
- Line 334: `@Volatile darkMode` (default true) — read from page scripts via the bridge.
- Line 335: `desktopSite` (default true) — desktop UA mode.
- Line 336: `autoplay` (default false).
- Line 339: `@Volatile lowQuality` (default true) — low-quality stream preference; @Volatile because page scripts read it through the bridge (cross-thread).
- Line 342: `@Volatile exclusiveVideo` (default true) — one video at a time.
- Lines 344–345: `@Volatile mediaOwner` — which page/site currently "owns" the media (exclusive-video arbitration).
- Line 348: `lastPageUrl` — last committed page URL (crash recovery target, back behavior).
- Line 349: `mainProcessConnection` — ServiceConnection for binding to the WebView main process service (used to detect WebView process death / control it).
- Line 350: `pageStartedAt` — timestamp of current page load start (diagnostics, load-timeout).
- Lines 351–352: `defaultUa` / `desktopUa` — the two user agents; `desktopUa` is derived from `defaultUa` via `buildDesktopUa` (line 851).
- Line 353: `pendingClearHistory` — history clear is deferred until the WebView is ready.
- Line 354: `deferredNavigation` — a navigation that must wait (e.g., WebView not ready yet); `navigationPending()` (line 1571) is true if `navigationHandoff.isPending || deferredNavigation != null`.
- Line 355: `crashReloadCount` — consecutive crash-reload counter (bounds automatic retries).
- Line 356: `softwareRendering` — force software WebView rendering (fallback for GPU crashes).
- Lines 357–358: `scriptsAtDocumentStart` / `uiScriptsAtDocumentStart` — whether the guard/helper scripts and UI scripts were injected at document start (vs. fallback injection on page finish).
- Lines 359–361: `guardJs`, `helpersJs`, `flixerImageGuardJs` — the injected script blobs (loaded from assets in `installScripts`, line 778).
- Lines 363–368: TMDB image virtualization comment + `@Volatile tmdbAllowlist: MutableSet<String>` — allowlist of image URLs currently visible on a guarded poster-grid host; requests not in the set get a 1x1 transparent pixel. Purpose: keep GPU texture count low enough to avoid the PowerVR `KEGLReleaseRenderTarget` crash.
- Line 369: `@Volatile onFlixerPage` — active page is flixer (affects tap behavior, e.g., `tapPlayerFrame(mouse = onFlixerPage)` at line 2375).
- Lines 370–372: `@Volatile onImageGuardedPage` — active page is a guarded poster-grid host (flixer or cinejoy) → TMDB image firewall active.
- Lines 374–381: `isImageGuardedHost(host)` — true for `flixer.gd` (+subdomains), `flixer.su` (+subdomains), `cinejoy.pk` (+subdomains). Comment: flixer loads a ~180-poster intro burst; cinejoy lazy-loads a large grid; both produce GPU texture churn that tips the PowerVR driver over.
- Lines 383–387: `@Volatile lastNonFlixerHost` — tracks the last non-flixer host so a video-site → flixer transition can force a render reset (flixer crashes when loaded right after a video site due to residual video GPU state).

## 389–411: Remote pointer state
- Lines 390–391: `cx`, `cy` — pointer position in screen coordinates (the "mouse" the page sees).
- Line 392: `lastTapAt` — uptimeMillis of last real touch-down (see dispatchTouchEvent); the 2s window in `focusFromPage` is measured against this.
- Line 393: `PendingTap(target, downAt, x, y, mouse)` data class — a Select press that is waiting for its UP (down/up pair synthesized for the page).
- Lines 394–397: `pendingTap`, `tapUp` (the scheduled UP runnable), `selectPending` (Select key down, awaiting up), `selectLongFired` (long-Select already fired — the long-press opens the keypad; the subsequent UP must not also click).
- Lines 398–399: `hoverActive`, `lastHoverAt` — hover state for the page (hover events sent while pointer is still).
- Lines 400–411: `hideCursorRunnable` — self-rescheduling: while `cursor.loading` (page loading), keep the pointer visible (re-post every 2s) so its loading bar stays on screen; otherwise hide the cursor, `liftPointerIfPlaying()` (release the synthetic pointer over a playing video so it doesn't block controls), and clear `hoverActive`.

## 413–415: Fullscreen video
- `customView: View?` — the native fullscreen video view (from `onShowCustomView`).
- `customCallback: WebChromeClient.CustomViewCallback?` — its callback (must be invoked on exit).

## 417–425: Keypad state
- Line 418: `textMode` (see enum line 100).
- Line 419: `buffer: StringBuilder` — the text being typed.
- Line 420: `bufferPassword` — buffer holds a password field (masks display).
- Line 421: `pendingJsPrompt: JsPromptResult?` — a JS `prompt()` awaiting the keypad's answer.
- Line 422: `promptAllowsEmpty` — the prompt accepts an empty answer (affects keypad OK behavior).
- Lines 423–424: `promptDone((String) -> Unit)?` / `promptCancel(() -> Unit)?` — the prompt's confirm/cancel continuations.
- Line 425: `syncRunnable = Runnable { syncField() }` — debounced field sync to the page.

## 427–430: Menu state
- Line 428: `menuBack` — the back-continuation captured when a menu is shown (what Back does instead of navigating).
- Line 429: `bookmarksFolder` — current folder in the bookmark list.
- Line 430: `movingBookmarkUrl` — bookmark currently being moved (move-to-folder flow).

## 432–437: Companion object
- Request codes: `REQ_DIAGNOSTIC_EXPORT = 1203`, `REQ_STORAGE_PERMISSION = 1204`, `REQ_BOOKMARK_EXPORT = 1205`, `REQ_BOOKMARK_IMPORT = 1206` — used with `startActivityForResult`/`onActivityResult` and `onRequestPermissionsResult`.

## 439–445: Misc state
- Line 440: `swallowedUp = HashSet<Int>()` — keycodes already swallowed on this DOWN (dedupe: a repeated UP for the same keycode isn't processed twice; see dispatchKeyEvent).
- Line 441: `lastBackAt` — timestamp of last Back (double-back-to-exit timing).
- Lines 442–445: toast throttling: `blockedCount` (blocked popups since last toast), `lastBlockedToastAt`, `lastInfoToastAt`, `lastToast` (reused Toast instance).

## 447–450: Keypad.Listener (start)
- `keypadListener` — anonymous `Keypad.Listener`; `onText(s)` → `typeText(s)`. (Continues in next chunk: onBackspace/onClear/onGo/onCancel etc.)
# MainActivity.kt PART A — Chunk 2 (lines 451–900)

## 451–467: Keypad.Listener (rest)
- `onBackspace()` → `typeBackspace()`, `onClear()` → `typeClear()`, `onGo()` → `typeGo()`, `onClose()` → `cancelKeypad()`. The Keypad view (bottom sheet) drives all text entry; the activity just forwards to the type* functions (defined later, lines 2885–2925).

## 473–637: `onCreate(savedInstanceState)` — exact operation order
1. `super.onCreate` (474).
2. Line 476: `window.addFlags(FLAG_ALT_FOCUSABLE_IM)` — excludes the system IME at the *window* level (comment: not by disabling Chromium's editor). The on-screen keypad is the only keyboard; the native IME must never pop up.
3. Line 478: `CrashLog.install(this)` — installed first so even a failure while building the screen is recorded.
4. Line 479: `DiagnosticLog.initialize(this)`.
5. Lines 480–486: load prefs (`settings` SharedPreferences): `darkMode` (def true), `desktopSite` (def true), `autoplay` (def false), `lowQuality` (def true), `exclusiveVideo` (def true), `crashReloadCount` (def 0).
6. Lines 487–494: watchdog/crash-window read. Read `watchdogRestart`, `lastCrashUrl`, `crashWindowStartedAt` BEFORE clearing the crash window — a watchdog-caused restart means the previous process died in a native crash nothing in-process could catch. If the crash window is unset or expired (`CrashRecoveryPolicy.expired`), `clearCrashRecovery()`.
7. Line 498: persist `cleanExit=false`, `wasForeground=true` (commit). `cleanExit` stays false unless the activity ends normally, so a process death leaving it false is a crash; `wasForeground` tells the watchdog not to reopen an app the system killed while backgrounded.
8. Line 499: `startWatchdog()`.
9. Line 500: `softwareRendering` from prefs.
10. Line 505: `requestStoragePermission()` — bookmark export writes to public Downloads; on API 23+ needs runtime WRITE_EXTERNAL_STORAGE; requested up front so export just works.
11. Lines 507–516: construct `Ui`, `PopupGuard`, `BookmarkStore.shared`, `AudioManager`; read the six injected-JS assets: `popup_guard.js`, `page_helpers.js`, `flixer_image_guard.js`, `fmhy_theme.js`, `tv_ui.js`, `seek_click.js`.
12. Lines 518–529: build view tree: black `root` FrameLayout → `TvWebView` (black bg; `LAYER_TYPE_SOFTWARE` if softwareRendering — avoids the PowerVR SIGSEGV in glBindFramebuffer on flixer) → `progress` BarView (GONE, height ui.u(4)) → `cursor` CursorView (size ui.u(26), GONE, centered: `cx = screenW/2`, `cy = screenH/2`).
13. Lines 543–557: `StartPage` with listener: `openUrl(url)` → `openUrl(url)`; `search()` → `promptUrl()`; `manage(folder)` → `showBookmarks(folder, 0)`.
14. Lines 559–574: `MenuPanel`, `ListPanel`, `TextPanel`, `Keypad` (width min(screenW-40, 820), bottom-center, bottomMargin 14; `keypad.listener = keypadListener`; an OnLayoutChangeListener calls `updateEditorViewport()` — the editor viewport must track the keypad's size).
15. Lines 576–585: `fullscreenBox` (black, GONE), `osd` (width min(screenW-80, 620), bottom-center, margin 30).
16. Lines 587–594: `dimOverlay` — argb(190,0,0,0) (~75% black), GONE, not focusable/clickable, accessibility-irrelevant.
17. Lines 595–613: root OnLayoutChangeListener — on size change: clamp `cx`/`cy` into the new bounds, move cursor, then re-size keypad/osd/menu/list/textPanel children (all the min(...) width formulas recomputed). This is what keeps the UI correct across resolution changes.
18. Line 614: `configureWebView()`.
19. Line 615: `webView.resumeTimers()` (try/catch).
20. Lines 616–635: startup navigation decision, in priority order:
    - Read `lastCrashUrl` (`reopen`) and `restoreAfterRecreateUrl` (`restore`, then removed from prefs).
    - If `watchdogRestart`: remove the flag; if `watchdogUrl` starts with "http" → toast "Restarted after the crash; reopened the page" + `navigateUrl(watchdogUrl, userInitiated=false)`; else `showStartPage(true)`.
    - Else if `restore` starts with "http" → `navigateUrl(restore, false)`.
    - Else if `crashReloadCount in 1..2` and `reopen` is http → `navigateUrl(reopen, false)` (auto-retry the crashed page, at most twice).
    - Else `showStartPage(true)`; and if `crashReloadCount > 2` with a valid reopen URL → `showCrashRecovery(reopen)` (manual retry menu).
21. Line 636: `handleBookmarkIntent(intent)` — deep-link handling (bookmark import via intent).

## 639–659: `onPause()` — exact operation order
1. `DiagnosticLog.record("activity_pause")`, `super.onPause()`.
2. `BookmarkJobs.detach(this)`.
3. `interactionSerial++` — any in-flight page interaction is now stale.
4. `foreground = false`.
5. `selectPending = false`, `wakeKey = null`, `swallowedUp.clear()` — key state fully reset (a key held across a pause must not fire on resume).
6. `cancelPlayerWork()` — cancel pending fullscreen/seek player work.
7. `editorSession++` + `cancelPageEdits()` — all queued page edits die.
8. `finishPendingTap(cancel=true)` — a pending Select tap is cancelled.
9. `handler.removeCallbacks(idleCheck)`; `idleQueryPending = false` — idle loop stops (restarted in onResume).
10. If `pageAvailable()`: `eval("window.__fb && __fb.pause && __fb.pause()")` (pause the Facebook player helper) and `webView.onPause()`.
11. `prefs.edit().putBoolean("wasForeground", false).commit()`.

## 661–672: `startWatchdog()`
- `WatchdogService` runs in a **second process** so it survives a crash that kills this one (comment line 661). `startService` + bind to `MainProcessService` (BIND_AUTO_CREATE) with an empty ServiceConnection stored in `mainProcessConnection` (unbound in onDestroy). Whole thing in try/catch — watchdog failure is non-fatal.

## 674–689: `onResume()` — exact operation order
1. `DiagnosticLog.record("activity_resume")`; persist `wasForeground=true`.
2. `super.onResume()`.
3. If `pageAvailable()`: `webView.resumeTimers()` + `webView.onResume()`.
4. `foreground = true`.
5. Lines 680–682: flush `deferredNavigation` (captured, nulled, invoked) — a navigation that had to wait for the WebView/foreground now runs.
6. `requestRendererRecovery()` — if a renderer crash was pending, recover now (defined line 2235).
7. `BookmarkJobs.attach(this)`.
8. `resetIdleActivity()` — comment: returning from a picker resets idle time but is not a new user command (so it undims without counting as interaction).
9. Restart the idle loop: remove + `postDelayed(idleCheck, 10_000L)`.

## 691–694: `onLowMemory()`
- Records `low_memory` diagnostic with the current page URL (sanitized via `DiagnosticLog.safeUrl`), then super. No eviction action — the OS handles it.

## 696–719: `onDestroy()` — exact operation order
1. `DiagnosticLog.record("activity_destroy")`.
2. Lines 698–700: if `isFinishing` (a normal finish, not a system kill) → persist `cleanExit=true`. A process death that leaves the flag false is the crash the watchdog watches for.
3. Lines 701–705: unbind `mainProcessConnection` (try/catch), null it.
4. Line 706: `cancelPendingJsPrompt()` — a pending JS prompt is cancelled (its JsPromptResult answered with cancel).
5. Line 707: `finishPendingTap(cancel=false, deliver=false)` — drop any pending tap, deliver nothing.
6. Line 708: if pageAvailable, `webView.resumeTimers()` (un-pause timers before teardown).
7. Lines 709–710: `foreground = false`, `activityDestroyed = true` — from here on, all page callbacks no-op.
8. Line 711: `pendingEvaluations.clear()`.
9. Line 712: `handler.removeCallbacksAndMessages(null)` — kill every posted task.
10. Line 713: dispose every live popup WebView.
11. Line 714: if fullscreenBox initialized → `leaveFullscreen(false)` (no page notification — the page is dying).
12. Line 715: `destroyBrowser(false)` — full WebView teardown (rendererGone=false, so it also stopLoading + removes the JS interface).
13. Line 716: `root.removeAllViews()`.
14. Line 717: `BookmarkJobs.detach(this)`.
15. `super.onDestroy()`.

## 725–775: `configureWebView()`
- `@SuppressLint("SetJavaScriptEnabled")` — scripting is required for a browser.
- Settings (exact): `javaScriptEnabled=true`, `domStorageEnabled=true`, `databaseEnabled=true`; `mediaPlaybackRequiresUserGesture = !autoplay` (comment: no video/sound may start by itself — a hidden preview or ad must not take the only video decoder from the chosen player; overridable via Settings>Autoplay); `useWideViewPort=true`, `loadWithOverviewMode=true`; zoom fully disabled (support/builtIn/display all false); `setSupportMultipleWindows(true)` (popups become WebViews the guard can inspect); `javaScriptCanOpenWindowsAutomatically = !guard.enabled` (Popup Guard ON → scripts may not open windows at all); `mixedContentMode = MIXED_CONTENT_COMPATIBILITY_MODE`; `allowFileAccess=false`, `allowContentAccess=false`.
- Lines 747–749: capture `defaultUa` from settings, derive `desktopUa` via `buildDesktopUa`, `applyUserAgent()`.
- Line 750: `applyDarkMode()`.
- Lines 751–759: `webview_config` diagnostic record (desktopSite, autoplay, exclusiveVideo, popupGuard, multiWindow, jsPopupAuto, mixedContentMode).
- Lines 761–763: cookies accepted, including third-party (required for logins).
- Lines 765–767: `WebView.setWebContentsDebuggingEnabled(true)` only when the app is debuggable.
- Line 769: `webView.addJavascriptInterface(Bridge(), "Buccaneer")` — the page↔native bridge (defined later in file, PART B/C).
- Lines 770–771: `webViewClient = Client()`, `webChromeClient = Chrome()` (inner classes below).
- Line 772: download listener → toast "Downloads are not supported in this browser".
- Line 774: `installScripts()`.

## 777–817: `installScripts()`
- Resets `scriptsAtDocumentStart`/`uiScriptsAtDocumentStart` to false.
- If `WebViewFeature.DOCUMENT_START_SCRIPT` supported: register document-start scripts for all origins (`setOf("*")`):
  - `guardJs` (popup_guard.js) and `helpersJs` (page_helpers.js) — must run before any page script, in every frame. Comment: do NOT rewrite navigator.webdriver (keep the environment unmodified for verification compatibility).
  - `flixerImageGuardJs` — the flixer TMDB image firewall, registered at DOCUMENT START. Long comment (788–800): this is the fix for the flixer PowerVR crash. The guard must observe the poster grid BEFORE flixer's scripts append ~180 TMDB posters so its allowlist sync runs during the burst. Injecting only from onPageCommitVisible ran too late (sync saw count:0, allowlist empty, full 182-image burst hit the GPU → SIGSEGV in KEGLReleaseRenderTarget). At document start it runs in every frame before any page script; visible posters get real textures, off-screen ones get 1x1 pixels via shouldInterceptRequest. Self-guards on the flixer host, idempotent, so all origins is safe.
  - Then `scriptsAtDocumentStart = true`.
  - Nested try: `fmhyThemeJs` (only for `https://fmhy.net` + `https://*.fmhy.net`) and `tvUiJs` (all origins); on success `uiScriptsAtDocumentStart = true`; on failure log `ui_scripts_install_failed` (comment 803: an optional UI/theme failure must never prevent Flixer's intro fix).
  - Log `scripts_installed` {mode: document_start, frames: all}.
- Outer catch: `scriptsAtDocumentStart = false`, log `scripts_install_failed` (error class name truncated to 120 chars).

## 819–830: `injectFallback(view)`
- For old WebViews without document-start scripts: on page events, `evaluateJavascript` the guard+helpers (if `!scriptsAtDocumentStart`) and the theme+tvUi scripts (if `!uiScriptsAtDocumentStart`). Scripts are idempotent so double-injection is safe.

## 832–849: `injectDarkMode(view)` — deliberately empty
- Big comment: dark mode is handled entirely by Chromium's render-client darkening (FORCE_DARK_ON, set in `applyDarkMode`, line 3562). NO CSS injection — injected style tags trigger GPU compositing issues on the PowerVR driver (flixer crashes). fmhy (VitePress) doesn't get darkened because it declares color-scheme in its CSS, which makes Chromium skip render-client darkening — known limitation. "Do NOT add any evaluateJavascript style injection here." When dark mode is off, it cleans up style tags left by older app versions (per the doc comment at 832–840). Called from onPageCommitVisible and onPageFinished.

## 851–857: `buildDesktopUa(ua)`
- Extracts the Chrome version from the default UA via regex `Chrome/([0-9.]+)` (fallback "120.0.0.0") and builds a Windows desktop UA: `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/<ver> Safari/537.36`. Comment: changes reported identity only; does not turn Android WebView into desktop Chrome.

## 859–861: `applyUserAgent()`
- `userAgentString = if (desktopSite) desktopUa else defaultUa`.

## 863–885: `interceptNavigation(view, uri, mainFrame)`
- "Port of rules.json: decides whether a page-initiated navigation is allowed." Returns true = block.
- http/https: if mainFrame and `guard.blocksNavigation(view?.url, uri)` → log `redirect_blocked` {initiator, target} (sanitized URLs), `noteBlocked("a redirect")`, return true (block). Else return false (allow).
- `about`, `data`, `blob`, `javascript`, `file` schemes: allowed (return false).
- Anything else (`intent:`, `market:`, `tel:`, custom app schemes): comment — nothing on a TV browser can open them; if guard enabled, `noteBlocked("an app link")`; return true (block).

## 887–898: `inner class Client : WebViewClient()` (start)
- `shouldOverrideUrlLoading(view, request)` (889–892): null request → false; else `interceptNavigation(view, req.url, req.isForMainFrame)`.
- Deprecated string overload (894–898): null → false; else `interceptNavigation(view, Uri.parse(url), true)` (treated as main frame).

## 900: `onPageStarted(view, url, favicon)` — begins here (body in next chunk)
# MainActivity.kt PART A — Chunk 3 (lines 901–1350)

## 900–944: `Client.onPageStarted(view, url, favicon)` — exact operation order
- Guards (901): `!pageAvailable()` or `view != null && view !== webView` → return (only the main WebView, not popups).
- Lines 902–908: if `navigationPending()` — this load predates a pending native command; its notification cannot cancel the command or replace the recovery destination → `view?.stopLoading()` (try/catch) + log `prior_load_start_ignored` + return.
- Line 909: `finishPendingTap(cancel = true)` — a new page kills any pending Select tap.
- Line 910: `pageEpoch++` — **every page start bumps the epoch**; all pending page work from the old page is invalidated.
- Line 911: `resetDocumentWork()` (cancels pending fullscreen, player work, pending evals, etc. — defined line 2317).
- Line 912: `leaveFullscreen(true)` — exit any fullscreen, notifying the page.
- Line 913: `osd.hideNow()`.
- Line 914: `cancelPendingJsPrompt()` — returns whether a web prompt was cancelled.
- Line 915: if `textMode == TextMode.PAGE` or a web prompt was cancelled → `closeKeypad()`.
- Lines 916–926: full state reset: `lastTapAt = 0L` (kills the focusFromPage tap window), `selectPending = false`, `inputFrame/inputFieldState/inputFieldId = ""`, `playbackHeartbeatAt = 0L`, `idleQueryPending = false`, `scrollMode = false`, `scrollXPending/scrollYPending = 0`, `scrollPending = false`.
- Line 927: `cancelOsdRefresh()`; line 928: remove `syncRunnable`.
- Line 929: `lastPageUrl = url ?: ""`.
- Lines 930–932: if url is http → persist `lastCrashUrl` with `commit()`. Comment: a native GPU crash kills the process before any handler runs, so the page must be recorded here for the watchdog restart to reopen it.
- Line 933: `pageStartedAt = now` (elapsedRealtime).
- Lines 934–937: log `page_started` {url (safe), host}.
- Line 938: `injectFallback(view)` (re-injects scripts on old WebViews).
- Line 939: `cursor.loading = true` (cursor stays visible during load — see hideCursorRunnable).
- Lines 940–943: if start page not shown → progress bar at 0.05, VISIBLE.

## 946–999: `Client.onPageCommitVisible(view, url)`
- Guards (947): `!pageAvailable() || navigationPending() || view !== webView` → return.
- Line 948: log `page_commit_visible` {url}.
- Lines 949–951: `injectFallback(view)`, `injectDarkMode(view)` (no-op, see above), and `evaluateJavascript(fmhyThemeJs)` directly (belt-and-suspenders for the theme script).
- Lines 952–964: TMDB image firewall activation. Parse host (lowercased, trailing dot removed). `isFlixer` = flixer.gd/flixer.su (+subdomains); `isImageGuarded` = `isImageGuardedHost(host)` (adds cinejoy.pk). If the guarded state changed: update `onImageGuardedPage`; if leaving a guarded host, `tmdbAllowlist.clear()`; log `image_firewall` {active, host, allowlistSize}.
- Lines 965–990: flixer state change handling. If `onFlixerPage` changed: update, log `flixer_image_firewall` {active, allowlistSize}. **GPU render reset** (971–989): flixer crashes (KEGLReleaseRenderTarget) when loaded after a video site — residual video GPU state tips the PowerVR driver over. On the video-site → flixer transition (`isFlixer && lastNonFlixerHost.isNotEmpty()`): log `flixer_render_reset` {from}, then `pauseTimers()` + `invalidate()`, and 150ms later `resumeTimers()` + `invalidate()` — a forced render reset.
- Lines 991–994: track `lastNonFlixerHost` (only non-flixer, non-empty hosts).
- Lines 995–998: if image-guarded, `evaluateJavascript(flixerImageGuardJs)` (idempotent — safe repeatedly).

## 1001–1018: `Client.onReceivedError(view, request, error)` (API M+)
- If request non-null: build entry {url (safe), host, code (error.errorCode or -1), description, mainFrame}; log as `page_error` (main frame) or `subresource_error`. Comment: which host actually failed (e.g., SSL net_error -202 shows up here as a failed request); recording every failed subresource tells whether it's the video CDN or only ad/tracker domains; the log is bounded so noise is acceptable. Then super.

## 1020–1030: `Client.onReceivedHttpError(view, request, errorResponse)` (API M+)
- Main-frame only: log `http_error` {url, status, reason}. Then super.

## 1032–1069: `Client.shouldInterceptRequest(view, request)`
- Lines 1033–1044: if diagnostics enabled and the path ends in `.m3u8`/`.mpd` or contains `/manifest` → log `media_manifest_request` {url, main, method}.
- Lines 1045–1067: **TMDB image firewall** (the PowerVR crash fix in action): if `onImageGuardedPage` and the request host is `image.tmdb.org` or `*.tmdb.org`: if the exact URL is in `tmdbAllowlist` → return null (allow the real image); otherwise create a 1x1 transparent ARGB_8888 PNG in memory (Bitmap → ByteArrayOutputStream → WebResourceResponse "image/png" inline), log `tmdb_image_blocked` {url}, return the pixel. Keeps GPU texture count low enough to avoid the PowerVR KEGLReleaseRenderTarget crash.
- Line 1068: else `super.shouldInterceptRequest`.

## 1071–1089: `Client.onPageFinished(view, url)`
- Guards (1072): `!pageAvailable() || navigationPending() || view !== webView` → return.
- Line 1073: `loadMs = now - pageStartedAt` (or -1).
- Lines 1074–1077: log `page_finished` {url, loadMs}.
- Lines 1078–1080: `injectFallback`, `injectDarkMode`, `evaluateJavascript(fmhyThemeJs)`.
- Line 1081: `cursor.loading = false`; line 1082: progress bar GONE.
- Lines 1083–1088: if `pendingClearHistory` → clear it and `view?.clearHistory()`. Comment: loading can finish before a delayed GPU crash; keep the URL/time-window retry budget — a page_finished callback is not evidence of stability (i.e., crashReloadCount is NOT cleared here; only `clearCrashRecovery()` after a stable period does that).

## 1091–1128: `Client.onRenderProcessGone(view, detail)` (API O+) — renderer crash recovery
- `@SuppressLint("ApplySharedPref")` — the crash-loop counter must be durable (commit) before recreating the renderer.
- Guard (1095): `view !== webView || recoveringRenderer || activityDestroyed` → return true. Comment: retired callbacks cannot dispose the replacement or misreport a new crash.
- Lines 1096–1103: `crashed = detail != null && detail.didCrash()`; message "The web page crashed" vs "The web page renderer stopped"; `CrashLog.record(...)` with the page; `DiagnosticLog.recordCritical("render_process_gone", {didCrash, detailAvailable, page})`.
- Lines 1104–1109: `recoveringRenderer = true` (pageAvailable() now false); `pendingJsPrompt = null` (comment: the dead renderer owns this result; never call back into it); `handler.removeCallbacksAndMessages(null)` (kill all posted work); dispose all popups; `leaveFullscreen(false)` (no page notify — page is dead); `destroyBrowser(true)` (rendererGone=true → no stopLoading/removeJavascriptInterface calls on the dead WebView).
- Lines 1110–1125: crash-window accounting. If `lastPageUrl` is http: read `lastCrashUrl` (previousUrl) and `crashWindowStartedAt` (started); `sameWindow = !CrashRecoveryPolicy.expired(started, now)`; `crashReloadCount = CrashRecoveryPolicy.nextCount(url, previousUrl, started, oldCount, now)`; persist count + url + window start (kept if same window, else now) with `commit()`. Toast: count <= 2 → "$what - reopening the page", else "$what - automatic retries stopped". If not http: `clearCrashRecovery()` + toast "$what - returning to the start page".
- Line 1126: `requestRendererRecovery()` (recreates the WebView — defined line 2235).
- Line 1127: return true (handled; WebView is dead, don't let the system kill the app).

## 1131–1138: `inner class Chrome : WebChromeClient()` — `onProgressChanged`
- Guards: `!pageAvailable() || startPage.shown() || navigationPending() || view !== webView` → return.
- `progress.fraction = newProgress/100f`; at >= 100: `cursor.loading = false`, bar GONE; else bar VISIBLE.

## 1140–1155: `Chrome.onJsPrompt(view, url, message, defaultValue, result)`
- `result == null` → return false (let default handle).
- If `!pageAvailable() || !foreground` → `result.cancel()`, return true.
- `promptText(title = (message ?: "Enter text").take(160), defaultValue, onCancel = { if (pendingJsPrompt === result) cancelPendingJsPrompt() }, okLabel = "OK", onConfirm = { if (pendingJsPrompt === result) { pendingJsPrompt = null; result.confirm(it) } })`.
- Then `pendingJsPrompt = result`, `promptAllowsEmpty = true`, `hideIme()`, return true.
- The identity checks (`pendingJsPrompt === result`) ensure a late keypad answer for an old prompt can't confirm a new one. The keypad (TextMode.PROMPT) is the prompt UI; the native IME is never shown.

## 1157–1166: `Chrome.onShowCustomView(view, callback)`
- If `!pageAvailable() || !foreground` → `callback?.onCustomViewHidden()`, return.
- Log `fullscreen_show`; `enterFullscreen(view, callback)` (line 2570); on exception → `report("Error entering full screen", e)` + `callback?.onCustomViewHidden()`.

## 1168–1175: `Chrome.onHideCustomView()`
- Log `fullscreen_hide`; `leaveFullscreen(false)` (no page notify — the page itself hid the view); on exception → `report("Error leaving full screen", e)`.

## 1177–1264: `Chrome.onCreateWindow(...)` — popup handling
- Doc comment: port of background.js tabs.onCreated/windows.onCreated — popups and script-opened tabs are closed. Sub-comment: bounded temporary popup view, no native bridge, permissions denied; scripts required to resolve its URL.
- Line 1186: log `popup_requested` {gesture, dialog, guard}.
- Lines 1191–1194: if `guard.enabled` → `noteBlocked("a popup window")`, return false (no window created at all — the guard blocks it).
- Lines 1195–1198: guard OFF: a single-view browser opens the new "tab" in a temporary WebView. `resultMsg` null → false; `msg.obj` must be a `WebView.WebViewTransport` else false; `!pageAvailable() || !foreground || popupViews.isNotEmpty()` → false (only ONE popup at a time).
- Lines 1199–1204: create `temp = WebView(this)`, add to `popupViews`, capture `popupEpoch = pageEpoch`, schedule a 10s timeout runnable that `disposePopup(temp)` (a popup that never resolves its URL dies).
- Lines 1205–1208: temp's WebChromeClient: `onCloseWindow` → `disposePopup(temp)`; `onPermissionRequest` → deny.
- Line 1209: log `popup_webview_created`.
- Lines 1210–1212: temp settings: JS enabled, no file/content access.
- Lines 1213–1260: temp's WebViewClient:
  - `onRenderProcessGone` (API O) → `disposePopup(temp)`, return true.
  - `onReceivedError` (M+): main-frame error → `disposePopup(temp)`.
  - Deprecated `onReceivedError` → `disposePopup(temp)`.
  - `forwarded` flag + `forward(url)` (1231–1243): once only; null or `about:` → ignore; scheme must be http/https else dispose; on success: post to handler — if `popupViews.contains(temp) && pageAvailable() && foreground && popupEpoch == pageEpoch` → log `popup_forwarded` {url}, `disposePopup(temp)`, `navigateUrl(url, userInitiated = false)` (the popup's destination becomes the main page); else dispose.
  - `shouldOverrideUrlLoading` (both overloads) and `onPageStarted` all call `forward(url)` — whichever fires first wins (the `forwarded` flag).
- Lines 1261–1263: `transport.webView = temp`; `msg.sendToTarget()`; return true.

## 1266–1278: `Chrome.onConsoleMessage`
- ERROR or WARNING level only: log `console` {level, message (take 1200), source (safe url), line}. Then super.

## 1280: `Chrome.getDefaultVideoPoster()`
- Returns a 1x1 ARGB_8888 bitmap — no poster image allocation (saves memory on a 2GB TV box).

## 1282–1288: `Chrome.onPermissionRequest`
- Only DRM playback (protected media) is allowed: if the request's resources are non-empty and ALL are `RESOURCE_PROTECTED_MEDIA_ID` → grant; otherwise (camera, microphone, etc.) deny.

## 1290–1295: `Chrome.onGeolocationPermissionsShowPrompt(origin, callback)`
- Always denies: `callback?.invoke(origin, false, false)`.

## 1298–1349: `inner class Bridge` — the `window.Buccaneer` JS interface (start)
- Called by the page scripts (popup_guard.js, page_helpers.js) through `window.Buccaneer`. Runs on a JS bridge thread — hence the epoch captures + runOnUiThread.
- `isDiagnosticsEnabled()` (1300–1301): `DiagnosticLog.isEnabled`.
- `diagnosticEvent(event, details)` (1303–1308): trimmed non-empty event name → `DiagnosticLog.record(this, e, details)`. Page-side diagnostic events.
- `isGuardEnabled()` (1310–1311): `guard.enabled`.
- `isDarkMode()` (1313–1314): `darkMode` (volatile).
- `pageIsForeground()` (1316–1317): `foreground` (volatile).
- `cssFullscreen(active: Boolean)` (1319–1342): page-level (CSS) fullscreen — the page reports enter/exit so remote keys route to it. Captures `epoch = pageEpoch`, then `runOnUiThread`: if `epoch != pageEpoch || !pageAvailable()` → drop (stale page generation); if `cssFullscreenActive == active` → no-op (idempotent); else set it, log `css_fullscreen_on`/`css_fullscreen_off`. On enter: `hideCursor()`, then post a 3s-delayed `liftPointerIfPlaying()` (guarded by epoch + still-active) — comment: the playback heartbeat may not be fresh yet (2s cadence); retry the pointer lift once it can be, so a parked cursor over the controls can't pin pointer-aware players' controls visible. Then `refreshOsd(500, "Select: play/pause    Left/Right: seek    Up/Down: volume    Back: exit")`. On exit: `osd.hideNow()`; if pageAvailable → `webView.requestFocus()`.
- `playbackActive(token: String?)` (1344–1349): the page's playback heartbeat. Captures `epoch = pageEpoch`; if `token != documentToken` → drop (stale document); else `heartbeatUi.offer(Heartbeat(epoch, token))` — coalesced onto the main looper, where it updates `playbackHeartbeatAt` only if epoch+token still match (see lines 125–130). This is the primary "video is playing" signal for the idle/dim policy.
