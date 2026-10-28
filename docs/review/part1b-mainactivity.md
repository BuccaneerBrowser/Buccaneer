# MainActivity.kt PART B — Chunk 1 (lines 1350–1799)

## Bridge class (tail) — `Bridge` inner class ends at line 1496
The `Bridge` class (declared in Part A) is the `@JavascriptInterface` surface injected as `window.Buccaneer` (or similar) into every WebView. All methods run on a **JS bridge thread** (not UI thread) — the universal pattern here: capture `pageEpoch`/`documentToken` on the bridge thread, then `runOnUiThread { re-check epoch+token+foreground+pageAvailable() }`. This double-check is the staleness firewall: a callback arriving from a document that has already been navigated away from is dropped.

### 1351–1352 `activeInputFrame(): String = inputFrame`
- Returns the frame id of the currently focused editable (or `""`). Read-only getter for JS to correlate focus reports. No thread hop (pure field read; safe because it's a short string and worst case stale).

### 1354–1355 `currentDocument(): String = documentToken`
- Returns the current document token (a per-document-generation string, rotated in `onPageStarted`). JS uses it to tag every report so native side can reject stale ones.

### 1357–1390 `editableFocused(frame: String?, state: String?)`
The core focus-report entry point. Params: `frame` (frame id, truncated to 120 chars), `state` (JSON string, must be ≤32768 chars and pass `JsonInputBudget.allows`).
- **Line 1360**: `state` must be valid JSON with `ok==1`; extracts `doc` (the document token JS observed). If `token != documentToken` → drop (stale document).
- **Line 1362–1363**: captures `epoch`; re-checks `token == documentToken` before hopping to UI thread.
- **Line 1364–1366 (UI thread)**: requires `epoch == pageEpoch && token == documentToken && pageAvailable() && foreground` AND either `textMode == TextMode.PAGE` OR the user typed within the last 20 s (`SystemClock.elapsedRealtime() - lastUserInputAt <= 20_000L`). The 20 s window lets the app re-arm typing permission after a pause without an explicit page-mode switch.
- **Line 1367**: extracts `field` id from state JSON.
- **Line 1368–1379 (identity reconciliation)**: if in PAGE mode with a non-empty `inputFrame` and the reported frame/field differs from the recorded one, the report is treated as possibly stale. It only proceeds if the JSON says `focused==1` (line 1372 — a try/catch that returns `true` on parse failure, i.e. *unparseable = not focused = drop*). If it proceeds: `editorSession++` (invalidates in-flight JS editor work), `cancelPageEdits()` (drops queued page edits), clears `buffer` and `bufferPassword`, removes pending `syncRunnable`. Comment: "Read-only identity reconciliation, never forced DOM focus."
- **Line 1380**: in PAGE mode with empty `inputFrame`, requires the last tap to be within the last 2 s (`lastTapAt` within 0–2000 ms of now).
- **Line 1381**: in non-PAGE mode (e.g. keypad mode), requires `lastTapAt > 0` and within 2 s.
- **Line 1382–1387**: commits: `inputFrame = id`, `inputFieldId = fieldId`, `inputFieldState = data`, `inputFocusConfirmed = true`, `focusFromPage(data)`, and if PAGE mode `syncFieldSoon()`.
- **Invariants**: `inputFocusConfirmed` is only set to true here; blur clears it (see below). The 2 s tap window ties focus permission to an actual recent user tap — prevents a page from silently grabbing the typing permit.
- **Breaks if changed**: relaxing the epoch/token checks → stale focus from a dead document re-arms the keypad; removing the 2 s tap window → pages can hijack input without user action; removing the `focused==1` gate → legacy unqualified reports move editor identity and lose typed buffer.

### 1392–1417 `editableBlurred(frame: String?, token: String?, field: String?)`
Blur report; invalidates the typing permit.
- Same epoch/token capture + UI-thread re-check pattern (1395–1399), plus requires `textMode == TextMode.PAGE && frame == inputFrame && field == inputFieldId` — i.e. only a blur of *the exact field we think is focused* matters.
- **Line 1402–1405**: if `pageEditActive?.action == "submit"` (a submit edit is in flight) → `closeKeypad()` and return. Rationale in comment: search boxes blur/remove their input on Enter; the already-dispatched submit must not leave a dead keypad over the results page.
- **Line 1409**: if `inputFieldId` was empty (app never learned the field id) → `inputFocusConfirmed = false` (lose typing permit). If the field id is known, the permit stands: "the JS edit path targets it by ID without Android focus."
- **Line 1410–1414**: `inputFieldState = ""`, `editorSession++`, `cancelPageEdits()`, remove `syncRunnable`, `showBufferPreview()`.
- **Invariants**: blur never forces DOM focus; it only downgrades the native side's confidence.
- **Breaks if changed**: not clearing `inputFieldState` → syncField pushes stale values; not incrementing `editorSession` → in-flight JS edit from the old field lands in the new one.

### 1419–1423 `blocked(what: String?)`
- JS reports a blocked popup/navigation. Truncates `what` to 160 chars (default "a popup") and offers a `BlockedBatch(pageEpoch, w)` into `blockedUi` (a `UiCoalescer` — coalesces multiple block events into one toast, processed on UI thread elsewhere). No direct UI work here.

### 1425–1430 `editableClicked()`
- Legacy (unqualified) editable-click report. Records `DiagnosticLog.record(..., "editable_clicked")`, then UI-thread: if `epoch == pageEpoch && pageAvailable() && foreground` → `onEditableClicked()`. No token check — deliberately permissive for old JS.

### 1432–1437 `editableClickedForDocument(token: String?)`
- Qualified variant: requires `token == documentToken` (checked twice: bridge thread line 1435, UI thread line 1436) before `onEditableClicked()`. This is the modern path.

### 1439–1441 `exclusiveEnabled(): Boolean = exclusiveVideo`
- JS getter for Settings > "One video at a time".

### 1443–1445 `lowQualityEnabled(): Boolean = lowQuality`
- JS getter for Settings > "Prefer low quality streams".

### 1447–1458 `mediaStarted(id: String?)`
- A frame started a real player. `id` must start with `documentToken + ":"` (frame id namespaced by document) — checked on bridge thread (1450) and re-checked on UI thread (1453) along with `epoch == pageEpoch && foreground && pageAvailable()`.
- Sets `mediaOwner = id.take(240)` and logs `media_owner_started` (frame truncated to 120).
- Purpose: exclusive-video mode — whichever frame owns `mediaOwner` is the "active" player; other frames' media is muted/stopped (enforced via JS using `activeOwner()`).
- **Invariants**: `mediaOwner` is only ever set from a token-qualified, epoch-verified report.

### 1460–1470 `mediaStopped(id: String?)`
- Mirror of `mediaStarted`. Same double token check. On UI thread: if `mediaOwner == id` → `mediaOwner = ""` (ownership released, another frame can take over). Logs `media_owner_stopped`.

### 1472–1473 `activeOwner(): String = mediaOwner`
- JS getter so page JS can query which frame currently owns media (for exclusive-video enforcement).

### 1475–1495 `tmdbAllowlist(urls: String?)`
- poster-grid site image virtualization: JS syncs the allowlist of *visible* TMDB image URLs so the native `shouldInterceptRequest` firewall can let only those through (others get 1×1 responses).
- **Line 1478–1479**: input must be ≤131072 chars and pass `JsonInputBudget.allows` (budget guard against JS flooding the bridge).
- **Line 1481–1483**: must parse as `JSONArray`; **max 256 entries** (`arr.length() > 256` → return, keeping the old set).
- **Line 1484–1487**: each entry must be non-empty and ≤8192 chars; collected into `newSet` (a `MutableSet<String>` — the `tmdbAllowlist` field).
- **Line 1488**: atomic-ish swap `tmdbAllowlist = newSet` (field is reassigned, not mutated in place — readers on other threads see a consistent set).
- Logs `tmdb_allowlist_sync` with count, or `tmdb_allowlist_error` on parse failure.
- **Invariants**: on any validation failure the previous allowlist is preserved (fail-safe: images keep working as before).
- **Breaks if changed**: raising the 256/8192/131072 limits → bridge-thread DoS vector; mutating the set in place → race with `shouldInterceptRequest` readers.

### 1496 — end of `Bridge` class.

## noteBlocked (1498–1506)
- Native-side popup-block accounting (called from popup-window handling / intercept paths).
- Logs `popup_guard_action` with `what` (≤160) and `count`.
- `blockedCount` accumulates with overflow protection: `coerceAtMost(Int.MAX_VALUE)` after widening to Long.
- Toasts "Popup Guard blocked $what" **rate-limited to one per 3000 ms** (`lastBlockedToastAt`).
- **Invariants**: `blockedCount` never overflows; toast never spams.

## Navigation section (1508–1664)

### 1512 `openUrl(url) = navigateUrl(url, userInitiated = true)`
- Trivial alias; user-initiated navigations (menu, start page, bookmarks) go through here.

### 1514–1533 `navigateUrl(url: String, userInitiated: Boolean)`
- **1515**: no-op if `!pageAvailable()` (webView not initialized/destroyed).
- **1517**: user-initiated nav with `crashReloadCount > 0` → `clearCrashRecovery()`. Comment: "Explicit user navigation is a fresh attempt; automatic retries/redirects are not." Crash-recovery counters (reload attempts after renderer death) only reset on explicit user action.
- **1518**: `prepareNavigation()` (see below).
- **1519**: logs `user_navigation` or `automatic_navigation` with `DiagnosticLog.safeUrl(url)` (redacts query params/credentials).
- **1520–1523**: if start page is shown → hide it and set `pendingClearHistory = true` (the WebView's history will be cleared once the new page commits — the start page is not a real history entry).
- **1524–1525**: progress bar to 0.05, made visible.
- **1527**: `lastPageUrl = url` — "Preserve intended URL for recovery, even if this process dies before page start" (crash-recovery persistence).
- **1528–1532**: `handoffNavigation { webView.loadUrl(url); webView.requestFocus(); showCursor() }` — the actual load is deferred through the NavigationHandoff teardown (see below).
- **Invariants**: `lastPageUrl` always holds the most recent *intended* URL; `prepareNavigation` always runs before any `loadUrl`.
- **Breaks if changed**: skipping `prepareNavigation` → stale epoch callbacks from the old page re-enter the new one; not setting `lastPageUrl` → crash recovery loses the user's destination.

### 1535–1546 `prepareNavigation(rotateDocument: Boolean = true)`
"Invalidates editor/player/scroll callbacks before *any* native navigation."
- **1537**: `finishPendingTap(cancel = true)` — cancels an in-flight pending tap (see tap state machine in chunk 2).
- **1538**: `leaveFullscreen(true)` — exit customView/CSS fullscreen, notifying the page.
- **1539**: `pageEpoch++` — **the central staleness token**. Every async callback (JS bridge, evaluation callbacks, handoff callbacks) captured the epoch and is dropped if it no longer matches.
- **1540**: `resetDocumentWork(rotateDocument)` — resets editor/player/scroll state; when `rotateDocument` also rotates `documentToken` (the per-document identity JS sees).
- **1541–1542**: hide OSD immediately + cancel any pending OSD refresh runnable.
- **1543**: `popupViews.toList().forEach { disposePopup(it) }` — dispose all open popup WebViews (toList() first because disposePopup mutates the set).
- **1544**: `closeAllPanels()` — menu/list/keypad/text panel.
- **1545**: `lastBackAt = 0L` — resets the double-back-to-exit timer.
- **Invariants**: after this returns, no callback from the previous document can take effect (epoch changed). `rotateDocument=false` is used for history navigation where the document may not change (pushState/hash).
- **Breaks if changed**: forgetting `pageEpoch++` → zombie callbacks; disposing popups without toList() → ConcurrentModificationException.

### 1548–1558 `navigateHistory(offset: Int)`
- **1549**: no-op if page unavailable or start page shown.
- **1550**: only offset -1 or 1 allowed, and must have room in history (`canGoBack`/`canGoForward`).
- **1551–1552**: reads `copyBackForwardList()` and computes the destination URL at `currentIndex + offset` (for `lastPageUrl`).
- **1555**: `prepareNavigation(rotateDocument = false)` — comment: "History may be pushState/hash-only: the existing page and its bridge remain alive. Full-document navigation rotates the token in onPageStarted instead." So the document token is NOT rotated here; if a full-document nav actually happens, `onPageStarted` (Part A) rotates it.
- **1556**: `lastPageUrl = destination` (may be null for odd history entries — guarded).
- **1557**: `handoffNavigation(releaseMedia = false) { webView.goBackOrForward(offset); webView.requestFocus(); showCursor() }` — `releaseMedia=false` because a back/forward nav may return to the same player page; the JS cleanup only pauses media, doesn't strip `src`.
- **Breaks if changed**: using `rotateDocument=true` here would break SPA back/forward (token rotation would make the still-alive page's bridge reports stale).

### 1560–1569 `reloadPage()`
- Guards: page available, start page not shown.
- `destination = webView.url ?: lastPageUrl` — remembers what's being reloaded.
- `clearCrashRecovery()` — a manual reload is a fresh attempt (unlike auto-retries).
- `prepareNavigation()` (rotateDocument=true — reload is a full document).
- Sets `lastPageUrl`, progress 0.05 visible.
- `handoffNavigation { webView.reload(); webView.requestFocus(); showCursor() }`.

### 1571 `navigationPending() = navigationHandoff.isPending || deferredNavigation != null`
- True while a handoff cleanup is in flight OR a commit was deferred due to background. Used to gate actions that must wait for navigation to settle.

### 1573–1615 `handoffNavigation(releaseMedia: Boolean = true, commit: () -> Unit)`
"One shared teardown path for explicit loads, history and return-home." This is the NavigationHandoff usage — the critical async navigation gate.
- **1575**: no-op if `!pageAvailable()`.
- **1576–1578**: captures `source = webView` and `epoch = pageEpoch` **at call time**. `current()` = `pageAvailable() && source === webView && epoch == pageEpoch` — i.e. the WebView instance is still the same AND no newer navigation has bumped the epoch.
- **1579–1583 `commitWhenForeground()`**: if `!current()` → drop. If `foreground` → `commit()` now. Else store `deferredNavigation = { if (current()) commit() }` — the load is deferred until the activity resumes (onResume re-checks; Part A). This prevents loading a URL while backgrounded (saves battery, avoids renderer work nobody sees).
- **1585–1590**: if `oldUrl` is null/blank/`about:blank` → no old document to quiesce: `navigationHandoff.cancel()` (clears any in-flight handoff) and `commitWhenForeground()` directly.
- **1591**: logs `navigation_cleanup_dispatched`.
- **1592–1607**: `navigationHandoff.start(cleanup = { done -> ... }, commit = { reason -> ... })`.
  - **cleanup**: sets the handoff marker **before** `stopLoading` (comment: "provider callbacks can re-enter" — `stopLoading` synchronously fires `onPageFinished`-style callbacks that could re-enter navigation logic). Then `source.stopLoading()` in try/catch. Then `evaluateJavascript` on the *old* document: pauses up to 64 `video`/`audio` elements; if `releaseMedia` also sets `src=''`, calls `load()` and `remove()`s the element (full release); returns `'stopped'` or `'unconfirmed'` (outer try/catch). The result is passed to `done(it)` — the NavigationHandoff's completion callback.
  - **commit** (1608–1614): called by NavigationHandoff when cleanup completes OR times out. If `current()` (same WebView, same epoch): logs `reason` (e.g. "cleanup_completed"/"cleanup_timeout") and `commitWhenForeground()`. Comment: "Script completion/timeout is NOT proof of native resource release." — the handoff timeout exists because a hung page can never finish the cleanup script, and navigation must not be blocked forever.
- **Invariants**: at most one commit per handoff; commit only if the WebView instance and epoch are unchanged; cleanup always runs against the *captured* `source` (not `webView`, which could have been replaced by a renderer-recreate).
- **What checks pageEpoch/interactionSerial here**: the `current()` closure checks `epoch == pageEpoch` (captured at 1577). `interactionSerial` is NOT used here — it's used by tap/seek/evaluation paths (chunk 2).
- **Breaks if changed**: removing `source === webView` check → a commit could load into a *new* WebView created by renderer recovery, double-loading; removing the foreground deferral → loads fire while backgrounded; removing the timeout → a hung cleanup script blocks all navigation.

### 1617–1632 `showStartPage(initial: Boolean)`
- Returns to the app's home/start page.
- **1618**: `lastBackAt = 0L`.
- **1619**: `pageEpoch++` (staleness reset even though this isn't a "navigation" per se).
- **1620**: `resetDocumentWork()` (default rotateDocument=true).
- **1621**: `leaveFullscreen(true)`.
- **1622**: `osd.hideNow()`.
- **1623**: `closeAllPanels()`.
- **1624–1626**: if NOT the initial show (i.e. user navigated back home from a real page) → `handoffNavigation { webView.loadUrl("about:blank") }` — quiesces the old document via the normal handoff. On initial show there's no old document.
- **1627**: `pendingClearHistory = true`.
- **1628–1630**: progress GONE, cursor loading off, cursor hidden.
- **1631**: `startPage.show()`.
- **Note**: unlike `prepareNavigation`, this does NOT dispose popups or cancel OSD refresh — popups from the old page will be cleaned up when the about:blank load's onPageStarted fires (or are orphaned; see popup handling in chunk 3).

### 1634–1652 `normalizeInput(raw: String): String`
- URL-or-query normalizer for address-bar input.
- Trims; empty → empty.
- If starts with `http://`, `https://`, `about:`, `file://` (case-insensitive) → return as-is.
- If contains a space → it's a search query → `searchUrl(s)`.
- IPv4 regex `^\d{1,3}(\.\d{1,3}){3}(:\d+)?(/.*)?$` OR `localhost`/`localhost:`/`localhost/` prefix → `http://$s` (local network/development URLs are http, not https).
- If contains `.` → treat as domain → `https://$s`.
- Else → `searchUrl(s)`.
- **Invariants**: never returns an unparseable scheme; search fallback is Google.

### 1654 `searchUrl(query) = "https://www.google.com/search?q=" + Uri.encode(query)`
- Google search URL builder.

### 1656–1664 `confirmExit()`
- Double-Back-to-exit: if a Back was pressed within the last 2500 ms → `finish()`; else record `lastBackAt` and toast "Press Back again to exit".
- `lastBackAt` is reset by `prepareNavigation`/`showStartPage` so a navigation cancels a pending exit.

## Key routing (1666–1799)

### 1670–1732 `dispatchKeyEvent(event: KeyEvent): Boolean`
The top-level key router. Order of logic:
- **1671**: swallow everything while `recoveringRenderer || activityDestroyed || !foreground` (return true = consumed).
- **1673–1680 (wake key)**: if the key matches `wakeKey` (the key that woke the screen from dim): on DOWN → `noteUserInput()`; on UP → clear `wakeKey` and remove the code from `swallowedUp` (so the *next* press of that key is delivered normally). The waking press itself is consumed.
- **1681–1686 (DOWN)**: if repeat of DPAD_CENTER/ENTER/NUMPAD_ENTER while `selectPending` → `resetIdleActivity()` (long-press Select while selecting shouldn't count as fresh activity for dimming); else `noteUserInput()`. If the screen was dimmed (`wasDimmed`) → set `wakeKey = code` and consume (first press after wake is a "wake press", not a real action).
- **1688–1698 (UP)**: if `selectPending` and key is DPAD_CENTER/ENTER/NUMPAD_ENTER → `selectPending = false`; if `!selectLongFired` → `tapAtCursor()` (a short Select press = click at cursor; a long press already fired its own action). Then if the code is in `swallowedUp` (removed) → consume; else `super.dispatchKeyEvent(event)`.
- **1699**: non-DOWN/UP (CANCEL) → super.
- **1701–1726 (DOWN dispatch)**: wrapped in try/catch → on exception `report("Error while handling key ...")` and return true (swallow, never crash on a key).
  - Branch selection (computed twice — once for the `diagKey` log, once for dispatch):
    1. `keypad.shown()` → "keypad" → `keypad.handleKey(event)`
    2. `!startPage.shown() && isTransportKey(code)` → "transport" → `onTransportKey(event)` (transport keys work even with menu/list focus, but NOT with keypad — see 1743 comment)
    3. `textPanel.shown()` → "textPanel" → `onTextPanelKey`
    4. `menu.shown()` → "menu" → `onMenuKey`
    5. `listPanel.shown()` → "listPanel" → `onListKey`
    6. `customView != null || cssFullscreenActive` → "fullscreen" → `onFullscreenKey`
    7. `startPage.shown()` → "startPage" → `onStartKey`
    8. else → "browse" → `onBrowseKey`
  - **1727–1731**: if handled → add code to `swallowedUp` (so the matching UP is consumed at 1696) and return true; else `super.dispatchKeyEvent(event)`.
- **Invariants**: every DOWN that's handled has its UP swallowed via `swallowedUp` (a HashSet<Int>); the wake-key dance ensures the first key after un-dim never double-fires; exceptions in key handling are reported, not fatal.
- **Breaks if changed**: forgetting `swallowedUp.add` → UP events leak to the page (phantom key presses); reordering branches (e.g. transport after menu) → media keys die when a menu is open.

### 1734–1737 `diagKey(code: Int, handler: String)`
- Logs every key with its resolved handler branch: `DiagnosticLog.record(this, "key", {code, handler})`.

### 1739–1741 `isTransportKey(code: Int): Boolean`
- True for MEDIA_PLAY_PAUSE, MEDIA_PLAY, MEDIA_PAUSE, MEDIA_FAST_FORWARD, MEDIA_REWIND.

### 1743–1755 `onTransportKey(event: KeyEvent): Boolean`
- Comment: "Hardware transport keys are independent of menu/list focus, but not an open text keypad."
- `rep = event.repeatCount`.
- PLAY_PAUSE: only on `rep == 0` → `videoCommand("togglePlay()", KEYCODE_MEDIA_PLAY_PAUSE, tapFrame=true)`.
- PLAY: `rep == 0` → `videoCommand("play()", KEYCODE_MEDIA_PLAY, true)`.
- PAUSE: `rep == 0` → `videoCommand("pause()", KEYCODE_MEDIA_PAUSE)` (no tap frame).
- FAST_FORWARD → `seekFromKey(event, 1)`; REWIND → `seekFromKey(event, -1)` (these DO honor repeat — held key = continuous seek).
- else → false (not a transport key; fall through).
- Returns true (consumed) for the known codes.
- **Invariants**: play/pause never fire on repeats (avoids double-toggle on key auto-repeat); seek uses repeat for acceleration (see `seekFromKey`, chunk 3).

### 1759–1770 `onStartKey(event: KeyEvent): Boolean`
- Start-page key handling. Comment (1757): "start page and panels: Android focus does the work, we only keep focus inside the panel."
- BACK (rep 0): if `!startPage.back()` (start page couldn't handle back, e.g. not in a sub-view) → `confirmExit()`. Consumed.
- MENU (rep 0): `showMainMenu()`. Consumed.
- Everything else → `focusKey(event, startPage)` (keep Android focus inside the start page container — see 1839).

### 1772–1788 `onTextPanelKey(event: KeyEvent): Boolean`
- Text panel (diagnostic/text viewer) keys:
  - UP/DOWN → `textPanel.scrollStep(∓1)`.
  - LEFT/RIGHT → consumed but no-op ("keep the arrows away from the page underneath").
  - BACK/MENU/CENTER/ENTER/NUMPAD_ENTER (rep 0) → `hideTextPanel()`.
  - Anything else (or repeats of the close keys) → `return false` (not consumed — falls through to super).
- Note the `when` returns `true` at the end for the handled cases.

### 1790–1799 `onMenuKey(event: KeyEvent): Boolean` (starts; continues in chunk 2)
- **1791–1798**: MENU key with `repeatCount == 1` (the *second* press — a double-MENU) while start page is not shown → `hideMenu()`, `scrollMode = true`, `wakeKey = KEYCODE_MENU` (the next MENU press is a wake press, not an action), `showCursor()`, toast "Scroll mode: D-pad scrolls under pointer; Back exits, Select clicks". Consumed.
  - This is the "scroll mode" toggle: with the menu closed, D-pad moves the pointer and scrolls the page underneath instead of navigating menus.
- **1799**: `val code = event.keyCode` — continues into chunk 2 (regular menu navigation: arrows move selection, CENTER/ENTER activates, BACK closes).
# MainActivity.kt PART B — Chunk 2 (lines 1800–2249)

## onMenuKey (continued, 1800–1809)
- BACK or MENU with `repeatCount == 0`: captures `menuBack` (the optional back-callback stored when the menu was shown, line 428), `hideMenu()`, then `back?.invoke()`. Consumed. Repeats of BACK/MENU are consumed but do nothing (prevents rapid double-close).
- Everything else → `focusKey(event, menu)` — D-pad moves Android focus inside the menu; Select is left to the focused button's own onClick (returns false for select at 1859).

## onListKey (1811–1836)
- Bookmark list panel key handling.
- **Bookmark-move mode** (`movingBookmarkUrl != null`, set by showMoveToFolder): UP/DOWN → `moveBookmarkStep(moving, ±1)` (move the dragged bookmark up/down the list); BACK/MENU/CENTER/ENTER/NUMPAD_ENTER on `repeatCount == 0` → `finishBookmarkMove()` and set `wakeKey = code` — comment: "A held Select must not open the newly focused bookmark." (the key that finished the move is treated as a wake press so its repeat/UP doesn't activate the newly focused row). All keys consumed in this mode.
- BACK (rep 0) → `listBack()` (navigate up a folder or close the list). MENU (rep 0) → `hideList()`.
- Otherwise → `focusKey(event, listPanel)`.

## focusKey (1838–1863)
" D-pad moves Android focus, but never out of [panel]. Select is left to the focused button."
- Maps DPAD_UP/DOWN/LEFT/RIGHT to `View.FOCUS_UP/DOWN/LEFT/RIGHT`; `isSelect` = CENTER/ENTER/NUMPAD_ENTER.
- If neither a direction nor select → `return false` (unhandled, falls through to super).
- If `currentFocus` is null or **not inside** the panel → request focus on `firstFocusable(panel)` (first visible, focusable, enabled descendant, depth-first). Consumed.
- If `isSelect` → `return false` (select is handled by the focused button itself — this function only manages D-pad containment).
- Otherwise `cur.focusSearch(dir)`; if the next focusable is inside the panel → `requestFocus()`. **If the next focusable would be outside the panel, focus simply stays put** (the `if (next != null && isInside(next, panel))` guard) — this is the containment mechanism. Always returns true (consumed).
- **Invariants**: focus can never escape a panel via D-pad; a panel with no focusable children is a no-op.
- **Breaks if changed**: removing the `isInside` guard on focusSearch → D-pad leaks focus to the page behind the panel.

## firstFocusable (1865–1875)
- Depth-first search for the first `VISIBLE && isFocusable && isEnabled` view. Non-ViewGroup leaves return null.

## onBrowseKey (1879–1973)
The main browsing key handler (no panel open, not on start page).
- **1881–1885 (scroll mode exit)**: if `scrollMode` and key is BACK or MENU → `scrollMode = false`, toast "Pointer mode", consumed.
- **D-pad (1887–1898)**: if `scrollMode` → `scrollPage(±delta)` where `delta = ui.u(60) * (if (rep > 12) 2 else 1)` (double speed after 12 repeats) and `showCursor()`; else `moveCursor(code, rep)` (pointer move). Consumed.
- **CENTER/ENTER/NUMPAD_ENTER (1899–1911)**: "Short press = click (done on key-up), long press = open the keypad."
  - `rep == 0` → `selectPending = true; selectLongFired = false` (arms the select state machine; the actual click happens on key-UP in dispatchKeyEvent 1688–1695).
  - `rep == 1 && !selectLongFired` → `selectLongFired = true; openKeypadSmart()` — the 2nd repeat tick (~500 ms hold) opens the text keypad. Because `selectLongFired` is now true, the key-UP handler (1694) will NOT fire `tapAtCursor()`.
  - Consumed.
- **MENU (1912–1915)**: rep 0 → `showMainMenu()`. Consumed.
- **BACK (1916–1923)**: rep 0 → if `webView.canGoBack()` → `navigateHistory(-1)` else `showStartPage(false)`. Consumed.
- **VOLUME_UP/DOWN (1924–1931)**: `adjustVolume(±1)` (video volume, chunk 3). Consumed.
- **VOLUME_MUTE (1932–1970)**: rep 0 only. This is the "force unmute" path:
  - Captures `interaction = interactionSerial`, `intent = playerIntent`, `target = customView ?: webView`.
  - `current()` closure: `foreground && interaction == interactionSerial && intent == playerIntent && target === (customView ?: webView) && !keypad.shown()` — **this is an interactionSerial check** (along with playerIntent and target identity).
  - `eval("window.__fb ? __fb.unmute() : 'none'")` — JS unmute helper `__fb.unmute()`.
    - Callback: if `!current()` → drop (stale). `jsString(raw)`:
      - `"ok"` or `"frame"` → `refreshOsd(1500, "Unmuted")`.
      - `"none"` → the video is in a frame the script can't reach (cross-origin): `eval("window.__fb ? __fb.muteButton() : ''")` to find the player's mute button coordinates; if the JSON has `ok==1` with `x`/`y` fractions → `sendTap(fx * webView.width, fy * webView.height)` (tap the mute button "as a finger would"); else fallback `videoCommand("toggleMute()", KeyEvent.KEYCODE_M)`.
      - else → nothing.
  - Consumed.
- **1972**: anything else → `return false` (not consumed; goes to super — e.g. keys the page should see).
- **Invariants**: the select state machine (`selectPending`/`selectLongFired`) is only armed here and resolved in dispatchKeyEvent's UP handler; volume/mute never fire on repeats.

## moveCursor (1875–2017)
Pointer movement for browse mode (non-scroll).
- **1976**: `speed = ui.u(10) * (1f + Math.min(repeat, 30) / 3f)` — base 10 dp per tick, accelerating up to 11× at repeat 30 (capped).
- Sets dx/dy per direction.
- **1985–1987**: view size from `root` (fallback `ui.screenW/H`); `edge = ui.u(36)` — the edge-scroll margin.
- **1994–2009 (edge scrolling)**: "Pushing the pointer into a screen edge scrolls the page instead." If moving up and `ny < edge` → `scrollY = (dy * 3f).toInt()` (3× the pointer delta), clamp `ny = edge`. Same for down/left/right. So the pointer pins to a 36 dp edge band and the page scrolls at 3× speed.
- **2010–2011**: clamp `cx`/`cy` to `[0, w-1]`/`[0, h-1]`.
- **2012**: if any scroll accumulated → `scrollPage(scrollX, scrollY)`.
- **2014–2016**: `cursor.moveTo(cx, cy)`, `showCursor()`, `sendHover(cx, cy)` (sends a real mouse hover event to the page — see below).
- **Invariants**: `cx`/`cy` are the single source of truth for pointer position, always within the view.

## scrollPage (2019–2047)
"Scroll the page. When the page itself scrolls we move the document; when it does not (most web apps scroll inside an element) we send mouse-wheel events, which scroll whatever is under the pointer."
- **2024**: no-op if page unavailable or backgrounded.
- **2025–2026**: accumulates into `scrollXPending`/`scrollYPending`, each **coerced to ±1500** (anti-runaway bound).
- **2027–2028**: if a scroll is already pending → return (coalescing; the pending values accumulate).
- **2029**: captures `epoch = pageEpoch`.
- **2030–2046**: `handler.postDelayed({...}, 25L)` — 25 ms debounce:
  - Guard: `!pageAvailable() || !foreground || epoch != pageEpoch` → if `epoch == pageEpoch` reset `scrollPending = false` (so a *newer* scroll can post) and return. (If the epoch changed, the new navigation's own scrollPage will manage the flag — actually if epoch changed, scrollPending stays true but the pending values were already consumed... note: on epoch mismatch `scrollPending` is NOT reset here; it is reset by `resetDocumentWork` in prepareNavigation.)
  - Reads and zeroes `scrollXPending`/`scrollYPending` into `sx`/`sy`.
  - `viewW`/`viewH` = webView dimensions (≥1).
  - `eval`: `__tvUi.scrollAt(cx*scaleX, cy*scaleY, sx*scaleX, sy*scaleY)` where scale factors convert view px → CSS px (`window.innerWidth / viewW`). `__tvUi` is the injected TV-UI JS (loaded from `tvUiJs`).
    - Callback: `scrollPending = false`. If result != `"nested"` (i.e. the page did NOT scroll a nested element) → `sendWheel(cx, cy, sx / notch, -sy / notch)` where `notch = ui.u(60).coerceAtLeast(1)` — converts px delta to wheel "notches" (line 2049 doc: v>0 scrolls up, so `-sy`).
    - If more scroll accumulated during the eval → recurse `scrollPage(0, 0)` to drain it.
- **Invariants**: at most one scroll eval in flight (`scrollPending`); pending deltas bounded ±1500; epoch guard prevents scrolling a dead document.
- **Breaks if changed**: removing the 25 ms debounce → eval flood; removing the ±1500 clamp → unbounded scroll queue from key auto-repeat.

## sendWheel (2049–2072)
" One mouse-wheel event at (x, y). [v] > 0 scrolls up, [v] < 0 scrolls down, [h] > 0 scrolls right."
- No-op if backgrounded or page unavailable.
- Builds a `MotionEvent.ACTION_SCROLL` with `TOOL_TYPE_MOUSE`, `SOURCE_MOUSE`, AXIS_HSCROLL/AXIS_VSCROLL set, and `webView.dispatchGenericMotionEvent(ev)`. Recycled in all paths (obtain/recycle pair).
- Entire body in try/catch — "wheel scrolling is best effort".
- **Invariants**: never throws; event always recycled.

## showCursor (2074–2078)
- Makes the cursor view visible, cancels any pending hide, and posts `hideCursorRunnable` after **6000 ms** (auto-hide idle cursor).

## hideCursor (2080–2085)
- Removes the hide runnable, hides the cursor view, `liftPointerIfPlaying()`, and sets `hoverActive = false`.

## tapAtCursor (2087–2125)
The click action (fired on Select key-UP, or from other paths).
- **2090–2094 (OSD seek)**: `osd.seekFractionAt(cx, cy)` — if the pointer is over the on-screen-display seek bar → `seekToFraction(it)` + `showCursor()` and return (no page tap).
- **2095**: `lastTapAt = now` — **this is what the 2 s focus-permit windows in editableFocused/editableClicked check against**.
- **2096–2099**: captures `interaction = interactionSerial`, `editor = editorSession`, `target = customView ?: webView`. `current()` = `foreground && interaction == interactionSerial && editor == editorSession && target === (customView ?: webView) && textMode == TextMode.NONE && !menu.shown() && !listPanel.shown() && !textPanel.shown() && !startPage.shown()` — **interactionSerial + editorSession checks** for all async continuations.
- **2102**: if the WebView has size → fire-and-forget `eval(__fb.selectAt(x/w, y/h))` — JS-side "select the element at this point" (enables the page to know what was clicked; no callback).
- **2103–2112 (poster-grid site path)**: if `onFlixerPage && customView == null && sized` → runs `seekClickJs` probe with arg `'probe'`: returns `"track"` (over a recognized timeline) or `"frame"` (inside an opaque player frame we can't inspect) → then `sendTap(x, y, mouse=true)` (mouse-style tap) and `repairSeekClick(x, y, interaction)`. Otherwise plain `sendTap(x, y)` (touch). Comment: "Mouse input is only needed over a recognized timeline, or inside an opaque player frame we cannot inspect."
- **2113**: `showCursor()`.
- **2116–2123 (on-demand field reads)**: for delays 150 ms and 650 ms, `postForPage(delay) { if (!current()) return; eval(__fb.field()) { if (!current()) return; if state non-blank → focusFromPage(state) } }`. Comment: "On-demand field reads also cover poster-grid site without adding any listeners or recurring instrumentation to its protected documents." So after a tap, the app opportunistically asks the page "what field is focused now?" twice — this is how tapping an input on poster-grid site arms the typing permit without any persistent listeners.
- **2124**: `handler.postDelayed({ hideIme() }, 300)` — hide the IME 300 ms later (a tap on a non-input shouldn't leave the keyboard up).
- **Invariants**: `lastTapAt` is set BEFORE any async work (the focus-permit window starts at tap time); every async callback re-checks `current()`.

## repairSeekClick (2127–2136)
" One-shot fallback for identifiable seek tracks only; no poster-grid site listeners/timers/DOM styling."
- No-op if WebView unsized or `seekClickJs` blank.
- `postForPage(120)`: re-checks `onFlixerPage && customView == null && interaction == interactionSerial && !keypad.shown() && !menu.shown() && !listPanel.shown() && !textPanel.shown()` (**interactionSerial check**); then runs `seekClickJs(x/w, y/h)` (without 'probe' — the real seek click) to correct a seek that the mouse tap may have missed.
- **Purpose**: on poster-grid site, a mouse tap on the timeline may not register as a seek (their JS expects different event sequences); this one-shot JS call performs the seek directly.

## sendTap (2138–2178)
"Trusted native input: touch by default, mouse only for poster-grid site pointer/player clicks."
- **2140**: no-op if page unavailable or backgrounded.
- **2141**: `epoch = pageEpoch` (captured for the UP decision).
- **2142**: `target = customView ?: webView` (fullscreen custom view gets the tap, else the WebView).
- **2143**: logs `tap_dispatched` with x/y/w/h.
- **2148–2160 (diagnostic)**: if `DiagnosticLog.isEnabled` → `eval(__fb.tapStack(x, y))` — captures the element stack at the tap point (top-frame only; a cross-origin player shows the `<iframe>` as top element, "which is itself the answer"). Logged as `tap_stack`. Best-effort, double try/catch.
- **2161**: `finishPendingTap(cancel = true)` — a new tap cancels any in-flight one (its UP is delivered as CANCEL).
- **2162–2164**: `downAt = now`; creates `PendingTap(target, downAt, x, y, mouse)`; `pendingTap = tap`.
- **2165–2168**: dispatches `ACTION_DOWN` via `PointerTap.event(...)` (a helper that builds a touch or mouse MotionEvent per the `mouse` flag) to `target.dispatchTouchEvent`. If that throws → `finishPendingTap(cancel = true)` (deliver CANCEL for the DOWN we just sent... actually the DOWN may not have landed; the cancel is harmless) in a try/finally that recycles the event.
- **2169–2170**: "Navigation can happen synchronously while DOWN is being dispatched." If `pendingTap !== tap` (a newer tap replaced it, or it was cancelled) → return (our UP must not fire).
- **2171–2177**: posts the UP after **70 ms**:
  - if `pendingTap !== tap` → do nothing.
  - if `!pageAvailable()` → `finishPendingTap(cancel = true, deliver = false)` (renderer gone — don't dispatch any native event).
  - else `finishPendingTap(cancel = epoch != pageEpoch || target !== (customView ?: webView))` — **pageEpoch check**: if a navigation happened in the 70 ms, the UP is delivered as CANCEL (the DOWN went to the old document; an UP on the new one would be a phantom). If the target changed (fullscreen entered/exited) → also CANCEL.
- **Invariants**: every DOWN is matched by exactly one UP or CANCEL (or dropped if the page is gone); `pendingTap` is always the latest; the 70 ms DOWN→UP window is the "tap" duration.
- **Breaks if changed**: not canceling on epoch change → phantom UPs on the new page (double-clicks, menu opens); not canceling on target change → UP lands on the wrong view.

## finishPendingTap (2180–2191)
"Every live DOWN terminates with UP or CANCEL; renderer loss clears without native calls."
- **2182**: if `cancel` → also clear `selectPending`/`selectLongFired` (a cancelled tap must not leave the select state machine armed).
- **2183–2186**: takes `pendingTap`, nulls it, removes the `tapUp` runnable.
- **2187**: if no tap, or `!deliver`, or `!pageAvailable()` → return (renderer loss: clear state, no native dispatch).
- **2188–2190**: builds `PointerTap.event(tap.downAt, now, ACTION_CANCEL if cancel else ACTION_UP, x, y, mouse)` and dispatches to `tap.target` (the ORIGINAL target captured at DOWN time — important: if fullscreen changed, the cancel goes to the view that got the DOWN). try/catch + finally recycle.
- **Invariants**: `pendingTap` is null after this; the target of the terminal event is always the target of the DOWN.

## liftPointerIfPlaying (2193–2215)
"Lift the simulated mouse pointer out of the page... pointer-aware players (video site) see 'pointer over the controls' forever and pin their controls visible. Lifting only while video plays: while browsing, the parked pointer is what keeps hover menus open while the user reads them, so it must stay."
- **2201**: no-op if `!hoverActive` (no hover has been sent).
- **2202–2203**: only lifts if `playbackHeartbeatAt > 0` AND within the last **12 s** (i.e. video is actively playing — the heartbeat is updated by the player's JS while playing; >12 s stale means playback stopped).
- **2204–2211**: sets `hoverActive = false`, dispatches `ACTION_HOVER_EXIT` (SOURCE_MOUSE) at (cx, cy), logs `pointer_lifted`. try/catch — "hover is a nicety; never let it break browsing".
- **Invariants**: hover state is only cleared while playing; while browsing the parked pointer is intentionally kept.

## sendHover (2217–2232)
"Mouse-style hover so menus and video controls that react to the pointer appear."
- **2220–2221**: rate-limited to one hover per **40 ms**.
- **2223**: first hover after a reset sends `ACTION_HOVER_ENTER`, subsequent ones `ACTION_HOVER_MOVE`; sets `hoverActive = true`.
- Dispatches to `webView.dispatchGenericMotionEvent` with SOURCE_MOUSE. try/catch best-effort.
- **Invariants**: `hoverActive` is true iff a hover is currently "parked" in the page; reset by hideCursor/liftPointerIfPlaying.

## requestRendererRecovery (2234–2242)
"Dispose dead renderers immediately, but recreate only while this Activity is foreground."
- **2236**: no-op if already `recoveringRenderer`, activity destroyed, backgrounded, or `rendererRecreatePosted` (dedupe).
- **2237**: sets `rendererRecreatePosted = true`.
- **2238–2241**: `handler.post { rendererRecreatePosted = false; if (recoveringRenderer && !activityDestroyed && foreground) recreate() }` — defers `recreate()` to the next main-loop tick (so the caller's stack unwinds first) and re-checks all conditions at fire time.
- **Invariants**: at most one recreate posted at a time; recreate never happens in background (would waste a renderer process).

## updateEditorViewport (2244–2249, continues in chunk 3)
"Page editing gets an actual viewport above the native keypad, not a covered WebView."
- **2246**: no-op if webView/keypad not initialized or `webViewDestroyed`.
- **2247**: gets `webView.layoutParams` (returns if null).
- **2248–2249**: computes new height: if `textMode == TextMode.PAGE && keypad.shown()` → `total = root.height ?: ui.screenH` (continues next chunk: shrinks the WebView so the keypad doesn't cover the input field being edited).
# MainActivity.kt PART B — Chunk 3 (lines 2250–2699)

## updateEditorViewport (continued, 2250–2259)
- (from chunk 2) When `textMode == TextMode.PAGE && keypad.shown()`: new WebView height = `total - keypad.measuredHeight - keyLp.bottomMargin - 8`, coerced to ≥80 px. Otherwise `MATCH`.
- **2253**: no-op if height unchanged.
- **2254–2255**: applies `lp.height` and re-sets `webView.layoutParams` (triggers relayout).
- **2256–2258**: if still in PAGE mode, `postForPage(30L)` → re-check `textMode == TextMode.PAGE` → `eval(__fb.focusIntoView())` — after the viewport shrinks, scroll the focused field back into view.
- **Invariants**: the WebView is never shorter than 80 px; the keypad never covers the field being edited.

## hideIme (2261–2268)
- Hides the soft keyboard via `InputMethodManager.hideSoftInputFromWindow(webView.windowToken, 0)`. Entire body try/catch — IME state is best-effort (the token can be null after destroy).

## Video section (2270–2695)

### eval (2274–2300) — the central JS-evaluation wrapper
"Bounded completion: native failures and deadlines cannot leave a queue busy."
- **2276**: if `!pageAvailable()` → invoke callback with `null` immediately (bounded completion even when the page is gone).
- **2279–2283 (fire-and-forget)**: if `callback == null` → plain `webView.evaluateJavascript(script, null)` in try/catch (logs "Page script unavailable"). No deadline, no bookkeeping — comment: "Fire-and-forget commands have no result queue to unblock. Do not retain a deadline/closure for two seconds for each such command."
- **2284–2285**: captures `epoch = pageEpoch`; assigns `id = ++evaluationId` (monotonic, starts at 1).
- **2286–2294 `complete(value)`**: idempotent (guarded by `completed` flag). Removes `id` from `pendingEvaluations`, removes the deadline runnable, and **only invokes the callback if `pageAvailable() && epoch == pageEpoch`** — the pageEpoch check: a callback from a dead document is dropped entirely (the caller's `current()`-style guards would drop it too, but this is the central gate).
- **2295–2297**: `deadline = Runnable { complete(null) }`; stored in `pendingEvaluations[id]`; posted with **2000 ms** delay. The `pendingEvaluations` map (HashMap<Long, Runnable>, line 268) exists so `resetDocumentWork` can cancel all outstanding deadlines at once.
- **2298–2299**: `webView.evaluateJavascript(script) { complete(it) }` in try/catch; on exception → `complete(null)`.
- **Invariants**: every evaluation with a callback completes exactly once (real result, 2 s deadline, or exception); `pendingEvaluations` never holds a completed entry; a navigation clears all pending deadlines (via resetDocumentWork) so dead-document callbacks can't fire late.
- **Breaks if changed**: removing the 2 s deadline → a hung page blocks dependent flows (e.g. videoSeek's finish() never runs → seekInFlight stuck true forever); removing the epoch check in `complete` → stale callbacks mutate new-document state.

### cancelPendingFullscreen (2302–2306)
- If `pendingFullscreen` (a `fullscreenVideo()` attempt is in flight): clears the flag and fires `__fb.cancelFullscreen()` (if page available) to abort the page-side fullscreen preparation.

### cancelPlayerWork (2308–2315)
- Cancels all in-flight player work: `cancelPendingFullscreen()`, then `playerIntent++` (invalidates all captured player intents), `seekGeneration++` (invalidates in-flight seeks), `seekInFlight = false`, `queuedSeek = 0`, `seekRepeat.reset()`.
- Called before starting new player work (videoCommand, seekToFraction, fullscreenVideo) and on navigation (via resetDocumentWork).

### resetDocumentWork (2317–2337)
The per-document state reset (called from `prepareNavigation` and `showStartPage`).
- **2318**: `deferredNavigation = null` (a deferred commit is stale once the document rotates).
- **2319**: `cancelPageEdits()` (drops the page-edit queue — see chunk 4/Part C).
- **2320**: `inputFocusConfirmed = true` — NOTE: resets to *true*, not false. Rationale: a fresh document starts with the optimistic assumption that typing is permitted; the first focus report confirms it.
- **2321**: `navigationHandoff.cancel()` (any in-flight handoff is stale).
- **2322–2323**: `heartbeatUi.cancel()`, `blockedUi.cancel()` (drain the coalescer queues).
- **2324**: `interactionSerial++` — **the second staleness token** (pageEpoch covers document generation; interactionSerial covers interaction-level work: taps, player commands, fullscreen).
- **2325**: `cancelPlayerWork()`.
- **2326**: `editorSession++` (invalidates in-flight JS editor work).
- **2327**: if `rotateDocument` → `documentToken = UUID.randomUUID().toString()` — the token JS sees via `currentDocument()`.
- **2328**: `cssFullscreenActive = false`.
- **2329**: `mediaOwner = ""` (exclusive-video ownership resets).
- **2330**: `playbackHeartbeatAt = 0L` (no active playback on the new document).
- **2331–2332**: cancels all pending evaluation deadlines (`pendingEvaluations.values.toList().forEach { handler.removeCallbacks(it) }` — toList() first because `complete()` mutates the map) and clears the map.
- **2333–2336**: `scrollPending = false`, `idleQueryPending = false`, `scrollXPending = 0`, `scrollYPending = 0`.
- **Invariants**: after this returns, every async callback captured before it (eval, tap, seek, handoff, scroll) will fail its epoch/serial/generation check.
- **Breaks if changed**: forgetting `interactionSerial++` → a tap's UP or a player callback from the old page acts on the new one; forgetting the pendingEvaluations clear → 2 s later a dead-document eval callback fires (harmless due to `complete`'s epoch check, but the deadline runnable leaks until then).

### videoCommand (2339–2362)
"Runs a page_helpers.js video command. It answers 'ok', 'frame' (the player lives in a frame from another site; that frame's own copy of the helper ran the command — except on poster-grid site, which keeps the trusted-tap path), or 'none' (no player found; send the key)."
- **2345**: `cancelPlayerWork()` — a new command supersedes any in-flight one.
- **2346–2347**: captures `intent = playerIntent`, `target = customView ?: webView`.
- **2348**: `eval("window.__fb ? __fb.$call : 'none'")` — `call` is a fixed string like `"togglePlay()"`, `"play()"`, `"pause()"` (NOT user input — safe to interpolate).
- Callback: guard `!foreground || intent != playerIntent || target !== (customView ?: webView)` → drop (playerIntent check, not interactionSerial).
  - `"ok"`: if `tapFrame` → `postForPage(350) { if (intent == playerIntent && target === ...) retryIfPlayRefused() }` — after a scripted play, check whether the browser actually refused it (autoplay policy) and tap the player to satisfy the click requirement.
  - `"frame"`: if `tapFrame && fallbackKey == KEYCODE_MEDIA_PLAY_PAUSE && onFlixerPage` → `tapPlayerFrame(mouse = true)` — "poster-grid site's player frame is not scripted: a real tap starts and toggles it." On any other site, "frame" means the frame's own helper already ran the command (nothing to do).
  - `"none"`/null/else → `sendKeyToPage(fallbackKey)` — no player found; synthesize the key into the page (e.g. SPACE for play/pause, M for mute).
- **2360**: `refreshOsd(150, "")` — refresh the on-screen display after any command.
- **Invariants**: `playerIntent` is the staleness token for player commands; `target` identity catches fullscreen enter/exit.

### retryIfPlayRefused (2364–2372)
"The browser refuses play() until the page has had a click. Then a real tap on the player does it."
- Guards `foreground`; captures `intent`, `target`.
- `eval(__fb.playBlocked())` — returns "1" if the page reports play was blocked (autoplay policy).
- Callback: if `foreground && intent == playerIntent && target === ... && jsString(raw) == "1"` → `tapPlayerFrame()` (touch, default mouse=false).

### tapPlayerFrame (2374–2397)
"The player is inside a frame from another site, so we tap its middle, as a finger would."
- Guards `foreground`; captures `intent`, `target`.
- `eval(__fb.frameCenter())` → JSON `{ok, x, y}` (fractions of the frame center).
- Callback: guard `!foreground || intent != playerIntent || target !== ...` → drop. Parses JSON (ok==1, x, y; defaults 0.5/0.5; parse failure → ok=false).
- If ok → `sendTap(fx * webView.width, fy * webView.height, mouse)`; else → `sendKeyToPage(KeyEvent.KEYCODE_SPACE)`.
- `mouse` defaults to `onFlixerPage` (poster-grid site needs mouse events, others touch).

### seekFromKey (2399–2402)
- `seekRepeat.step(keyCode, repeatCount, downTime, eventTime)` — the `SeekRepeatPolicy` (line 169) converts a held key's repeat pattern into a seek delta (larger skips the longer you hold, per the OSD hint "Hold FF/Rewind: larger skips"). If `seconds != 0` → `videoSeek(seconds * direction)`.

### videoSeek (2404–2440)
The seek state machine.
- **2405**: no-op if page unavailable, backgrounded, or `seconds == 0`.
- **2406**: if `pendingFullscreen` → `cancelPendingFullscreen(); playerIntent++` (a seek supersedes a pending fullscreen attempt).
- **2407–2408 (coalescing)**: if `seekInFlight` → accumulate into `queuedSeek` (coerced ±120) and return. Only one seek eval in flight at a time.
- **2409–2411**: `seekInFlight = true`; `generation = ++seekGeneration`; `intent = playerIntent`; `target = customView ?: webView`.
- **2412 `valid()`**: `foreground && generation == seekGeneration && intent == playerIntent && target === (customView ?: webView)` — **seekGeneration + playerIntent checks**.
- **2413–2419 `finish()`**: if `!valid()` → return (a superseding command already reset seekInFlight). Else `seekInFlight = false`; drain `queuedSeek` → if non-zero, recurse `videoSeek(next)`.
- **2420–2421**: `amount = seconds.coerceIn(-60, 60)` (single seek capped at ±60 s); the excess is queued: `queuedSeek = (queuedSeek + seconds - amount).coerceIn(-120, 120)`.
- **2422**: `eval(__fb.seek(amount))`.
- Callback: if `!valid()` → drop.
  - `"ok"` or `"frame"` → `refreshOsd(150, "Hold FF/Rewind: larger skips")`; `finish()`.
  - else (no player found): fallback to native key seeks — first `eval(__fb.focusTarget())` ("Focus the selected iframe without a center click that might toggle playback"), then in its callback (re-checks `valid()`): `count = (abs(amount) / 10).coerceIn(1, 6)` key presses of DPAD_RIGHT (forward) or DPAD_LEFT (back), each via `postForPage(i * 80L)` with guards `valid() && !keypad.shown() && !startPage.shown()` → `sendKeyToPage(key)`. Finally `postForPage(count * 80L + 40L) { if (valid()) { refreshOsd(75, "Native seek steps depend on this player"); finish() } }`.
- **Invariants**: `seekInFlight` is true iff exactly one seek eval (or key-sequence) is in flight; `queuedSeek` is bounded ±120; `finish()` is called exactly once per in-flight seek (guarded by `valid()`).
- **Breaks if changed**: forgetting `seekInFlight = false` in finish → all future seeks just queue forever; removing the ±60 clamp → one giant seek the player can't honor; removing the focusTarget step → the key presses may land on the wrong frame.

### seekToFraction (2442–2457)
Seek to a fraction of the duration (from tapping the OSD seek bar, chunk 2).
- **2443**: no-op if fraction not finite, page unavailable, or backgrounded.
- **2444**: `cancelPlayerWork()` (a fraction seek supersedes everything).
- **2445**: captures `intent = playerIntent`.
- **2446**: `amount = fraction.coerceIn(0f, 1f)`.
- **2448–2452**: one inline JS function (comment: "Passive, one-shot API only: no new player hooks, observers or site styling"):
  - reads `__fb.state()` (JSON: `f` found, `d` duration, `t` current time);
  - validates `f==1`, `d`/`t` finite, `d > 0`;
  - `t = d * amount`; `r = __fb.seek(t - s.t)` (seek by delta from current time); if `r != 'ok'` returns r;
  - re-reads state; returns `'ok'` if `|after.t - t| <= 2` (the seek actually landed, 2 s tolerance) else `'blocked'`.
- Callback: guard `!foreground || intent != playerIntent` → drop. If result != "ok" → `toastOnce("This player did not accept the position; use FF/Rewind")`. Then `refreshOsd(150, "Select position • Hold FF/Rewind for larger skips")`.
- **Invariants**: the seek is verified by re-reading state (not just trusting the seek call); one-shot, no persistent hooks.

### adjustVolume (2459–2471)
- If `!audio.isVolumeFixed` (system volume is adjustable): `audio.adjustStreamVolume(STREAM_MUSIC, RAISE/LOWER, 0)`; `refreshOsd(0, "")`.
- Else (TV pins system volume): `eval(__fb.volume(dir * 0.1))` — adjusts the *video element's* volume by ±0.1. Callback: if result == "none" (no player) → `toastOnce("Volume is controlled by your TV")`; `refreshOsd(60, "")`.
- **Invariants**: never throws; the OSD always reflects whichever volume domain is active.

### refreshOsd (2473–2508)
Refreshes the on-screen display (video status bar) from the page.
- **2474**: no-op if page unavailable or backgrounded.
- **2475**: `cancelOsdRefresh()` (cancels any pending refresh — coalescing).
- **2476–2477**: captures `request = osdRequest` (a counter, line 271 — incremented elsewhere when a new OSD state is forced) and `epoch = pageEpoch`.
- **2478–2505**: posts a runnable after `Math.max(75L, delayMs)`:
  - Guard: `!pageAvailable() || !foreground || epoch != pageEpoch || request != osdRequest` → drop (**pageEpoch check** + request counter).
  - `eval(__fb.state())` → callback guards `!foreground || request != osdRequest` → drop.
  - Parses JSON: `f` (found==1), `p` (paused==1), `t` (time), `d` (duration), `v` (volume, default 1.0), `m` (muted==1). Parse failure → `found = false`.
  - `showOsd(found, paused, t, d, vol, muted, hint)`.
- **Invariants**: at most one refresh in flight; a stale refresh (epoch/request changed) never touches the OSD.

### showOsd (2510–2544)
Renders the OSD.
- **2519**: status text: "No video detected" / "PAUSED" / "PLAYING".
- **2520**: seek fraction = `t/d` coerced 0..1, only if found and both finite and `d > 0`.
- **2521–2525**: time string `formatTime(t)` (+ " / " + duration if d > 0), only if found.
- **2527–2541 (volume)**: if system volume adjustable → fraction from `audio.getStreamVolume(STREAM_MUSIC) / getStreamMaxVolume`, text "Volume N%"; else video volume fraction, text "Video volume N%". If muted → fraction 0, text "Muted".
- **2542**: `osd.show(status, seek, time, volText, volFraction, hint)`.
- **2543**: if not in customView fullscreen and the cursor is visible → `cursor.bringToFront()` (the OSD is drawn over the page; keep the pointer on top).

### fullscreenVideo (2546–2568)
"Menu > Full-screen video: the page only allows full screen after a real tap, so we tap for you."
- **2548**: `cancelPlayerWork()`.
- **2549–2551**: captures `intent = playerIntent`, `interaction = interactionSerial`, `target = customView ?: webView`.
- **2552**: `pendingFullscreen = true`.
- **2553**: `eval(__fb.prepareFullscreen())`.
- Callback: guard `!foreground || interaction != interactionSerial || intent != playerIntent || target !== ...` → drop (**interactionSerial + playerIntent checks**).
  - If result != "ok" → `cancelPendingFullscreen()` + toast "Fullscreen was not prepared; use the player's own button".
  - Else:
    - `postForPage(150)`: if `pendingFullscreen && interaction == interactionSerial && intent == playerIntent && target === ...` → `sendTap(webView.width / 2f, webView.height / 2f)` — the "real tap" the page requires before allowing fullscreen (center of the screen, where the player is).
    - `postForPage(2200)`: timeout — if `interaction == interactionSerial && intent == playerIntent && customView == null && !cssFullscreenActive` (fullscreen never started) → `cancelPendingFullscreen()` + toast "The page refused full screen. Use the player's own full-screen button."
- **Invariants**: `pendingFullscreen` is true from here until the page enters fullscreen (onShowCustomView → enterFullscreen) or a timeout/cancel clears it; `videoSeek` also checks it (line 2406).
- **Breaks if changed**: removing the 2200 ms timeout → `pendingFullscreen` sticks true forever, blocking seeks (line 2406 cancels it, but the flag semantics break) and the cancel JS never runs.

### enterFullscreen (2570–2586) — WebChromeClient.onShowCustomView handler
- **2571**: `interactionSerial++` — entering fullscreen invalidates all in-flight interaction work (taps, player commands).
- **2572–2575**: if `view == null || customView != null` (re-entrant or duplicate) → `callback?.onCustomViewHidden()` and return.
- **2576**: `cancelPlayerWork()`.
- **2577**: `finishPendingTap(cancel = true)` (a pending tap's target was the webView; the view is about to change).
- **2578**: if `textMode == TextMode.PAGE` → `closeKeypad()`.
- **2579–2581**: `customView = view`; `customCallback = callback`; adds the view to `fullscreenBox` at MATCH/MATCH.
- **2582–2583**: `fullscreenBox.visibility = VISIBLE`; `keepScreenOn = true`.
- **2584**: `hideCursor()`.
- **2585**: `refreshOsd(500, "Select: play/pause    Left/Right: seek    Up/Down: volume    Back: exit")` — shows the fullscreen keymap hint.
- **Invariants**: `customView` is non-null iff the WebView is in hardware fullscreen; exactly one customView at a time.

### leaveFullscreen (2588–2603) — WebChromeClient.onHideCustomView handler + manual exit
"[notifyPage] is true when we leave on our own (Back key) and must tell the WebView."
- **2590**: `interactionSerial++`.
- **2591**: `val view = customView ?: return` (no-op if not in fullscreen).
- **2592**: `cancelPlayerWork()`.
- **2593**: `finishPendingTap(cancel = true)`.
- **2594–2596**: captures `callback`; nulls `customView` and `customCallback`.
- **2597–2599**: removes the view from `fullscreenBox`, hides the box, `keepScreenOn = false`.
- **2600**: `osd.hideNow()`.
- **2601**: if page available → `webView.requestFocus()`.
- **2602**: if `notifyPage && pageAvailable()` → `callback?.onCustomViewHidden()` — only called when WE initiated the exit (Back key); when the page initiates it (its own fullscreen exit), the WebChromeClient callback path already knows, and calling it again would be a re-entrancy loop.
- **Invariants**: `customView`/`customCallback` are always nulled together; `onCustomViewHidden` is called at most once per fullscreen session.

### exitCssFullscreen (2605–2617)
"Ask the page to leave page-level (CSS) fullscreen." (CSS `:fullscreen` without the WebChromeClient custom view.)
- **2607**: no-op if `!cssFullscreenActive`.
- **2608**: `eval(__fb.exitCssFullscreen())`.
- Callback (comment: "The page's exit reports back through the bridge and clears the flag. If it never does (JS error, mid-navigation), clear it ourselves."): if `cssFullscreenActive` is still true → set false, `osd.hideNow()`, `webView.requestFocus()`.
- **Invariants**: the flag is cleared by the bridge report (Part A's onFullscreenChanged handler) OR by this fallback; never stuck.

### onFullscreenKey (2620–2695) — "Keys while a video is full screen."
- **BACK** (rep 0): if `customView != null` → `leaveFullscreen(true)` (hardware fullscreen, notify page) else `exitCssFullscreen()` (page-level). Consumed.
- **CENTER/ENTER/NUMPAD_ENTER** (rep 0): `videoCommand("togglePlay()", KEYCODE_SPACE, true)` (tapFrame=true → retryIfPlayRefused path). Consumed.
- **DPAD_LEFT** → `seekFromKey(event, -1)`; **DPAD_RIGHT** → `seekFromKey(event, 1)` (repeat honored → larger skips). Consumed.
- **DPAD_UP / VOLUME_UP**: `if (rep == 0 || rep % 2 == 0) adjustVolume(1)` — fires on press and on every other repeat (halves the repeat rate to avoid volume runaway). Consumed.
- **DPAD_DOWN / VOLUME_DOWN**: same, `adjustVolume(-1)`. Consumed.
- **VOLUME_MUTE** (rep 0): identical force-unmute logic to onBrowseKey's (chunk 2, lines 1932–1970): captures `interaction`/`intent`/`target`, `current()` closure with **interactionSerial check**, `__fb.unmute()` → "ok"/"frame" → `refreshOsd(1500, "Unmuted")`; "none" → `__fb.muteButton()` → tap at (fx,fy) or fallback `videoCommand("toggleMute()", KEYCODE_M)`. Consumed.
- **MENU** (rep 0): `refreshOsd(0, "Select: play/pause    Left/Right: seek    Up/Down: volume    Back: exit")` (re-shows the keymap hint). Consumed.
- Anything else → `return false`.
- **Invariants**: in fullscreen, D-pad is repurposed (no pointer movement); volume keys and D-pad up/down both work; the mute path is duplicated (not shared) between browse and fullscreen modes.

## Line 2697–2699
- Section header comment for "Keyboard input (the on-screen keypad)" — the next section (chunk 4 / Part C) begins at line 2700 with `cancelPendingJsPrompt()`.
