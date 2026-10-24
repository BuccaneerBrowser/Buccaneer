# PART C — MainActivity.kt lines 2700–3956 (chunk 1: 2700–3149)

## Keypad / prompt / page-edit subsystem (2700–2924)

### cancelPendingJsPrompt() — 2701–2706
- Cancels a pending `JsPromptResult` (`pendingJsPrompt`, set at ~423). Nulls it, calls `result.cancel()` in try/catch, returns true if one existed, false otherwise.
- Invariant: only one pending JS prompt at a time. If `cancel()` throws (e.g. WebView already gone) it is swallowed.

### promptText(title, initial, onCancel, goLabel, onDone) — 2708–2732
- Generic text-entry entry point (URL bar, bookmark name, etc.).
- Steps in order: `finishPendingTap(cancel=true)` (kills any in-flight tap), `interactionSerial++` (invalidates stale async callbacks), `cancelPlayerWork()` (cancels pending player evals/seek), `editorSession++` (invalidates queued page edits), `cancelPageEdits()`, `cancelPendingJsPrompt()`, `hideCursor()`.
- Sets `promptAllowsEmpty=false`, `textMode=TextMode.PROMPT`, stores `promptDone`/`promptCancel`, seeds `buffer` with `initial`, opens keypad with title+goLabel, `bringToFront()`, `setPreview(buffer)`, `updateEditorViewport()`.
- Invariant: entering any editor resets ALL other interaction state (taps, player work, page edits, JS prompts). Breaking this ordering lets a stale tap/eval fire into the new editor.

### promptUrl() — 2734–2738
- `promptText("Enter a web address or search", "", null, "GO") { openUrl(normalizeInput(text)) }`.
- `onCancel=null` → Back just closes (typeGo with empty text invokes cancel only when allowEmpty is false and text empty → here cancel is null so nothing happens; effectively no-op cancel).

### openPageKeypad() — 2740–2763
- Opens keypad for typing INTO the page (TextMode.PAGE). Same teardown sequence as promptText plus `hideIme()`.
- Sets `inputFocusConfirmed=true`, clears `promptDone/promptCancel`, `bufferPassword=false`, title "Type into the web page", goLabel "ENTER".
- `keypad.post { updateEditorViewport() }` — second viewport update after layout settles.
- `webView.requestFocus()` + `eval("window.__fb && __fb.focusIntoView()")` — asks page to scroll focused field into view.
- `syncFieldSoon()` — schedules field-state sync (90ms debounce).

### openKeypadSmart() — 2766–2780
- Long-press Select handler. Captures `interaction`/`editor` serials, evals `window.__fb ? __fb.field() : ''`.
- Callback guards: `foreground`, serials unchanged, `textMode==NONE`, no menu/list/textPanel/startPage shown — else aborts.
- Parses JSON: `hasField = JSONObject(text).optInt("ok",0)==1` (field info from page_helpers.js).
- Dispatch: `hasField ? openPageKeypad() : promptUrl()`.

### onEditableClicked() — 2783–2787
- Called by page JS when a real tap lands on an editable (legacy helper verified the active editable, including in cross-origin frames the top frame can't inspect).
- Calls `focusFromPage("{\"ok\":1}")` (defined at 242) to record input frame/field from the page's report.

### canTypeIntoPage() — 2789–2798
- Gate for page typing: needs `pageAvailable() && foreground`, and a recorded field (`inputFrame`/`inputFieldId` non-empty — set from a real tap).
- If no field: `toastOnce("Select the search field again")`, returns false.
- Note: a field recorded from a real tap is a "typing permit" — the JS edit path targets it by ID without Android focus; only an unselected field blocks.

### queuePageEdit(action, text) — 2800–2808
- Enqueues a PageEdit(action, text, editorSession, documentToken, inputFrame, inputFieldId) onto `pageEdits` (ArrayDeque, max 128) then `drainPageEdits()`.
- Guards: `canTypeIntoPage()` and `textMode==PAGE`; rejects if queue ≥128 or text >4096 chars with `toastOnce("Typing queue is full; wait for the page")`.

### currentEdit(edit) — 2810–2813
- Predicate: edit still valid if `foreground && pageAvailable() && textMode==PAGE && edit.session==editorSession && edit.token==documentToken && edit.frame==inputFrame && edit.field==inputFieldId && (inputFocusConfirmed || (frame non-empty && field non-empty))`.
- This is the staleness check that makes queued edits safe across navigation/field changes.

### cancelPageEdits() — 2815–2820
- Invokes `nativeEditCancel` (if a native edit is in flight), clears queue, `pageEditActive=null`.

### drainPageEdits() — 2822–2845
- Serial executor: returns if `pageEditActive != null`; polls first edit.
- If `!currentEdit(edit)` → clears whole queue (context changed).
- Qualified edit (frame+field non-empty) → evals `window.__tvUi && __tvUi.edit(action, text, frame, field, 0)` (JSON-quoted args); unqualified → `dispatchNativePageEdit(edit)`.
- Callback: if `pageEditActive !== edit` (stale) ignore; if `!currentEdit(edit)` → `cancelPageEdits()`.
- Status handling: `"unreachable"` (pre-action result, cross-origin) → native fallback `dispatchNativePageEdit` (cannot duplicate a DOM edit since it never happened). Any other status → `finishPageEdit(edit, accepted = status in {applied, submitted, key-dispatched}, state)`.
- Comment invariant: timeout/exception/blocked is NOT permission to replay keystrokes (prevents double-typing).

### dispatchNativePageEdit(edit) — 2847–2852
- `NativePageEditor.dispatch(webView, action, text) { accepted -> nativeEditCancel=null; finishPageEdit(edit, accepted, null) }`; stores returned cancel handle in `nativeEditCancel`.

### finishPageEdit(edit, accepted, state) — 2854–2878
- Guard `pageEditActive !== edit` → return. Clears active.
- **Diagnostic event: `edit_result`** via `DiagnosticLog.record(this, "edit_result", {action, field, status})` where status = state's "status" or "none".
- If `!currentEdit(edit)` → clear queue, return.
- If `!accepted`: `inputFocusConfirmed=false`, clear queue, keypad preview "(website did not accept input)", `toastOnce("Select the search field again; input was not confirmed")`.
- If accepted and state echoes ok=1 + doc==token + frame/field match: updates `inputFieldState`, `bufferPassword` from pw flag, sets preview (password → bullets up to 8192; else value "v").
- `action=="submit"` → clear queue + `closeKeypad()`. Else `syncFieldSoon()` + `drainPageEdits()` (next queued edit).

### showBufferPreview() — 2881–2883
- Keypad preview = buffer (bullets if `bufferPassword`).

### typeText(s) / typeBackspace() / typeClear() — 2885–2913
- In PAGE mode: mutate buffer, `showBufferPreview()`, `queuePageEdit("insert"/"delete"/"clear", s)`.
- Backspace uses `offsetByCodePoints` (safe for surrogate pairs).
- In PROMPT mode: just mutate buffer + `keypad.setPreview`.

### typeGo() — 2915–2923
- PAGE mode: `queuePageEdit("submit")`.
- PROMPT mode: `allowEmpty=promptAllowsEmpty`; text = buffer (trimmed only if !allowEmpty); captures done/cancel; `closeKeypad()`; if text empty && !allowEmpty → `cancel?.invoke()` else `done?.invoke(text)`.

## Keypad teardown & key dispatch (2925–2978)

### cancelKeypad() — 2925–2929
- If PROMPT mode, captures `promptCancel`; `closeKeypad()`; invokes cancel.

### closeKeypad() — 2931–2948
- Full teardown: `interactionSerial++`, `editorSession++`, `cancelPageEdits()`, clears `inputFrame/inputFieldState/inputFieldId`, `inputFocusConfirmed=true`, `keyboardDismissedUntil = now+1000ms` (suppresses IME for 1s), removes `syncRunnable`, `keypad.close()`, `textMode=NONE`, clears promptAllowsEmpty/done/cancel, `updateEditorViewport()`, `restoreFocus()`.

### dispatchToPage(action, keyCode, meta) — 2950–2959
- Builds a synthetic KeyEvent (VIRTUAL_KEYBOARD, SOURCE_KEYBOARD) and dispatches to **webView** — never the custom view. Comment: in fullscreen the custom view is only the video surface; keys sent there never reach the page.

### pressKey(keyCode, meta) — 2961–2964
- dispatchToPage DOWN then UP.

### sendKeyToPage(keyCode) — 2966–2973
- Guards `pageAvailable() && foreground`. Saves `root.findFocus()`, `webView.requestFocus()`, `pressKey`. Restores previous focus ONLY if a panel is shown (menu/list/textPanel/keypad) — so page keys don't steal focus from an open panel.

### syncFieldSoon() — 2975–2978
- Debounced: removeCallbacks + postDelayed(syncRunnable, 90ms).

## Field sync (2981–3024)

### syncField() — 2981–3024
- Mirrors the page field's real contents (autocomplete, maxlength) into keypad preview.
- Captures session/frame/field/token; qualified = frame+field non-empty.
- Script: qualified → `__tvUi.fieldState(frame, field, 0)`; else `__fb.field()`.
- Callback guards: foreground, session==editorSession, textMode==PAGE; qualified path additionally requires frame/field/token unchanged.
- `validFresh`: qualified → JSON ok==1 && doc==token && frame/field match; unqualified → non-empty string.
- Fallback: if fresh invalid but qualified → use `inputFieldState` snapshot (bridge snapshot of a cross-origin field that is now unreachable).
- Parses ok/v/pw; password → bullets (length coerced 0..8192); sets `bufferPassword`.
- Preview: ok → value (or bullets); else if buffer empty → "(tap a text field in the page first)"; else buffer.

## Menu framework (3026–3115)

### syncFocusBlocking() — 3030–3034
- If any of menu/listPanel/textPanel shown → `startPage.descendantFocusability = FOCUS_BLOCK_DESCENDANTS` else `FOCUS_BEFORE_DESCENDANTS`. Prevents D-pad focus leaking into start page behind a panel.

### restoreFocus() — 3036–3039
- If any panel shown → do nothing. Else focus startPage (if shown) else webView.

### closeAllPanels() — 3041–3067
- Global reset: `interactionSerial++`, `cancelPendingFullscreen()`, `promptAllowsEmpty=false`, `cancelPendingJsPrompt()`, `finishPendingTap(cancel=true)`, hides menu/list/textPanel, nulls menuBack/bookmarksFolder/movingBookmarkUrl.
- If keypad shown: `editorSession++`, `cancelPageEdits()`, clears input state, removes syncRunnable, closes keypad, textMode=NONE, nulls prompt callbacks.
- `updateEditorViewport()`, `syncFocusBlocking()`.

### showMenu(title, items, back) — 3069–3071
- Delegates to `showMenuAt(..., focusIndex=0, back)`.

### showMenuAt(title, items, focusIndex, back) — 3073–3095
- `interactionSerial++`, `cancelPendingFullscreen()`, `finishPendingTap(cancel=true)`, `hideCursor()`, `listPanel.hide()`, `movingBookmarkUrl=null`, `textPanel.hide()`, `menuBack=back`.
- Wraps each MenuItem: on click → `menuBack=null`, `syncFocusBlocking()`, `item.action()`, then `restoreFocus()` (comment: actions that open nothing new — Reload, toggles — hand focus back to page/start page).
- `menu.show(title, wrapped, focusIndex)`, `bringToFront()`, `syncFocusBlocking()`.
- **Focus-index dependency**: `focusIndex` is passed straight to `menu.show` — callers that reopen a menu at a specific row (e.g. bookmark options) rely on this.

### hideMenu() — 3097–3102
- `menu.hide()`, `menuBack=null`, `syncFocusBlocking()`, `restoreFocus()`.

### hideList() — 3104–3111
- `listPanel.hide()`, nulls `bookmarksFolder`/`movingBookmarkUrl`, `syncFocusBlocking()`, `startPage.refresh()`, `restoreFocus()`.

### listBack() — 3113–3115
- If inside a folder (`bookmarksFolder != null`) and `store.hasFolders()` → go up to root `showBookmarks(null, 0)`; else `hideList()`.

## Main menu (3117–3149+)

### showMainMenu() — 3117–(continues 3150+)
- `onStart = startPage.shown()`; `url = onStart ? null : webView.url`.
- Items in order (so far, 2700–3149):
  1. "Scroll submenu/page with D-pad" → `scrollMode=true`, `showCursor()`, toast "D-pad scrolls under pointer; Back returns to pointer mode".
  2. "Start page" → `showStartPage(false)`.
  3. "Reload" → `reloadPage()`.
  4. "Remove bookmark" (if url != null && store.contains(url)) → `editBookmarks { store.remove(url) }` + toast "Bookmark removed"; ELSE "Add bookmark" → `startSaveBookmark()`.
  5. "Bookmarks..." → `showBookmarks(null, 0)`.
  6. "Address / search" → `promptUrl()`.
  7. "Full-screen video" → `fullscreenVideo()`.
  8. "Fit open submenus to screen" → `eval("window.__tvUi ? __tvUi.fitMenus() : 0")` + toast "Adjusted recognized dropdown menus".
- (Continues in chunk 2: more items + showMenu call with focus index.)
# PART C — MainActivity.kt lines 2700–3956 (chunk 2: 3150–3599)

## Main menu (continued) — showMainMenu() 3117–3183
Full item list (in order), with behavior:
1. **"Scroll submenu/page with D-pad"** → `scrollMode=true`, `showCursor()`, toast "D-pad scrolls under pointer; Back returns to pointer mode".
2. **"Start page"** → `showStartPage(false)`.
3. **"Reload"** → `reloadPage()`.
4. **"Remove bookmark"** (only when `url != null && store.contains(url)`) → `editBookmarks { store.remove(url) }` + toast "Bookmark removed"; **else "Add bookmark"** → `startSaveBookmark()`.
5. **"Bookmarks..."** → `showBookmarks(null, 0)`.
6. **"Address / search"** → `promptUrl()`.
7. **"Full-screen video"** → `fullscreenVideo()`.
8. **"Fit open submenus to screen"** → `eval("window.__tvUi ? __tvUi.fitMenus() : 0")` + toast "Adjusted recognized dropdown menus".
9. **"Show playback controls"** → `refreshOsd(75, "Select position • Hold FF/Rewind for larger skips")`; if `customView == null && !cssFullscreenActive` also `showCursor()`.
10. **"Popup Guard: ON/OFF (N blocked)"** (label includes blockedCount when >0) → `toggleGuard()`.
11. **"Block redirects from <site>: ON/OFF"** (only when current URL has a parseable host; site = `PopupGuard.cleanHost(host)`) → `toggleProtectedSite(site)`.
12. **"Back"** → `navigateHistory(-1)`.
13. **"Settings..."** → `showSettings(0)`.
14. **"Exit browser"** → `finish()`.
- Finally `showMenu("Menu", items, null)` — focus index 0, no back-callback.
- Note: item 9's OSD hint text is the exact string shown in the OSD.

## Toggles (3185–3208)

### toggleGuard() — 3185–3190
- `guard.toggle()`; **diag `setting_popup_guard`** {enabled}; sets `webView.settings.javaScriptCanOpenWindowsAutomatically = !on` (guard ON disables JS-opened windows); toast "Popup Guard ON/OFF".

### toggleProtectedSite(site) — 3192–3199
- If `guard.isBuiltIn(site)` → toast "<site> is protected by default", no change. Else `guard.toggleProtected(site)` + toast "Redirects from <site>: blocked/allowed".

### toggleDesktop() — 3201–3208
- Flips `desktopSite`; **diag `setting_desktop`** {enabled}; persists `prefs "desktopSite"`; `applyUserAgent()`; reloads if not on start page; toast "Desktop site ON/OFF".

## Bookmark save flow (3210–3243)

### startSaveBookmark() — 3211–3230
- Guards: `BookmarkJobs.isBusy()` → toast "Wait for the bookmark transfer to finish"; URL must be http(s) else toast "Open a web page first".
- `name = BookmarkStore.suggestName(webView.title, url)`.
- Menu "Save \"<name>\"": "Save (no folder)" → `saveBookmark(name,url,"")`; one "Folder: <f>" per `store.folderNames()`; "New folder..." → `promptText("New folder name", "", null) { saveBookmark(name,url,folderName) }`.

### editBookmarks(action) — 3232–3236
- If `BookmarkJobs.isBusy()` → toast + return false; else run action, return true.

### saveBookmark(name, url, folder) — 3238–3243
- Busy guard; `editBookmarks { store.add(name, url, folder) }`; `startPage.refresh()`; toast "Bookmarked: <name> (folder <f>)".

## Export/import infrastructure (3245–3365)

### requestStoragePermission() — 3248–3253
- Only for SDK 23–28 (legacy Fire OS public Downloads path). Requests WRITE_EXTERNAL_STORAGE if not granted (REQ_STORAGE_PERMISSION).

### onRequestPermissionsResult — 3255–3262
- For REQ_STORAGE_PERMISSION: **diag `storage_permission`** {granted}; if denied toast "Storage permission denied — public Downloads export needs it".

### bookmarkPicker(export) — 3264–3276
- ACTION_CREATE_DOCUMENT (export, EXTRA_TITLE "bookmarks.json") / ACTION_OPEN_DOCUMENT (import), type application/json, CATEGORY_OPENABLE.
- startActivityForResult with REQ_BOOKMARK_EXPORT / REQ_BOOKMARK_IMPORT.
- `ActivityNotFoundException` → toast "No document picker; export uses public Downloads. Import can use Open with Buccaneer." + fallback to `exportBookmarksFile()` / `importBookmarksFile()`.
- Other exception → `report("Cannot open bookmark picker", e)` + toast "Bookmark picker unavailable".

### exportBookmarks() — 3278–3284
- SDK ≤ 28 → `exportBookmarksFile()` directly. Else menu "Export bookmarks": "Choose a file location" → `bookmarkPicker(true)`; "Public Downloads (survives uninstall)" → `exportBookmarksFile()`. Back-callback → `showMainMenu()`.

### importBookmarks() — 3286–3292
- SDK ≤ 28 → `importBookmarksFile()`. Else menu "Import bookmarks": "Choose a JSON file" → `bookmarkPicker(false)`; "Read last backup made by this install" → `importBookmarksFile()`. Back → `showMainMenu()`.

### runFileOperation(work) — 3294–3297
- `owner = BookmarkJobs.UiOwner(activityInstance, interactionSerial)`; `BookmarkJobs.submit { work().copy(owner=owner) }`; if submit fails (already running) toast "A bookmark transfer is already running".

### onBookmarkJob(result) — 3299–3312 (BookmarkJobs.Listener override)
- Returns false (job may retry) unless `foreground && !activityDestroyed && !recoveringRenderer`.
- `result.error` → `report("Bookmark transfer failed", it)`; always `toast(result.message)`.
- If `result.imported`: `startPage.refresh()`; if owner matches this activity instance + interactionSerial and keypad not shown → `closeAllPanels()` + `showBookmarks(null, 0)` (jumps to bookmark list after import).
- Returns true (consumed).

### exportBookmarksFile() — 3314–3318
- `runFileOperation { BookmarkJobs.Result(BookmarkFiles.export(app, bookmarks.exportPortableJson())) }` — writes public Downloads.

### importBookmarkText(text) — 3320–3326
- `runFileOperation { if (!bookmarks.importJson(text)) throw IOException("Not a supported bookmark backup"); Result("Imported N bookmarks", imported=true) }`.

### importBookmarksFile() — 3328–3336
- Same but reads `BookmarkFiles.readSaved(app)` (last backup made by this install).

### importBookmarkUri(uri) — 3338–3348
- Requires `uri.scheme == "content"` else toast "Use a file manager that grants access to the backup".
- `runFileOperation { read via contentResolver.openInputStream(uri) (null → IOException "Cannot read selected backup"); importJson or throw "Not a supported bookmark backup"; Result("Imported N bookmarks", imported=true) }`.

### handleBookmarkIntent(value) — 3350–3360
- Only for type "application/json". URI from ACTION_VIEW data or ACTION_SEND EXTRA_STREAM (deprecated getParcelableExtra).
- `closeAllPanels()`; `importBookmarkUri(uri)`; then `value.action = null` — prevents replaying the import after Activity recreation.

### onNewIntent(value) — 3362–3365
- `intent = value; handleBookmarkIntent(value)`.

## Bookmarks list (3367–3450)

### showBookmarks(folder, focusRow) — 3368
- Overload delegating to `showBookmarks(folder, focusRow, null)`.

### showBookmarks(folder, focusRow, movingUrl) — 3370–3450
- Teardown: `interactionSerial++`, `cancelPendingFullscreen()`, `finishPendingTap(cancel=true)`, `hideCursor()`.
- `view = folder if (folder != null && store.count(folder) > 0) else null` — a folder with 0 bookmarks is treated as gone.
- `bookmarksFolder = view`; `movingBookmarkUrl = movingUrl?.takeIf { store.find(it) != null }`.
- **Folder view** (view==null && hasFolders): title "Bookmark folders"; rows = up to 50 folder entries from `store.entries(0)` with pagination offset = `(focusRow/50*50)` clamped to last 50-block; label = name or "Unsorted" (empty name); sub = "N bookmark(s)"; `more` action (folder options) only for named folders → `showFolderOptions(name, i)`; click → `showBookmarks(name, 0)`.
- **Bookmark view** (else): list = `store.all()` (title "Bookmarks") or `store.inFolder(view)` (title = view or "Unsorted"); empty text "No bookmarks yet. Open a website, press Menu and choose Add bookmark."; rows = up to 50 with same pagination; row = (b.title, `BookmarkStore.hostOf(b.url)`, click → `finishBookmarkMove()` if moving else `openUrl(b.url)`, more → `showBookmarkOptions(b, view, i)` unless moving).
- Footer: while moving → single "Done moving" → `finishBookmarkMove()`. Else: "◀" (if offset>0) → `showBookmarks(view, offset-50)`; "▶" (if offset+50<totalRows) → `showBookmarks(view, offset+50)`; "All folders" (if in a folder && hasFolders) → `showBookmarks(null,0)`; "Export" → `exportBookmarks()`; "Import" → `importBookmarks()`; **"Restore previous"** (only if `store.hasPreviousImport()`) → `runFileOperation { bookmarks.undoImport() or throw "No valid prior import"; Result("Restored previous bookmark list", imported=true) }`; "Close" → `hideList()`.
- Pagination: 50 rows per page; heading shows "Title • page/totalPages" when totalRows>50.
- `menu.hide()`, `menuBack=null`, `textPanel.hide()`.
- `listPanel.show(heading, rows, empty, footer, focusRow - offset, hint)` where hint (moving mode) = "Up / Down: move bookmark     Select / Back: done (saved automatically)".
- `listPanel.bringToFront()`, `syncFocusBlocking()`.
- **Focus-index dependency**: `focusRow` is absolute; the panel receives `focusRow - offset` (page-relative). Callers pass the index of the row to focus (e.g. after delete: `Math.max(0, index-1)`).

### showBookmarkOptions(b, folder, index) — 3452–3473
- Busy guard → toast. `hideList()`. Menu titled b.title, items:
  1. "Move to top" → `store.moveToTop(b.url)`; `showBookmarks(folder, 0)`.
  2. "Move up / down..." → `showBookmarks(folder, index, b.url)` (enters moving mode).
  3. "Rename" → `promptText("Rename bookmark", b.title, { showBookmarks(folder, index) }) { store.rename(b.url, newName); showBookmarks(folder, index) }`.
  4. "Move to folder..." → `showMoveToFolder(b, folder, index)`.
  5. "Delete" → `store.remove(b.url)` + toast "Deleted <title>"; `showBookmarks(folder, max(0, index-1))`.
- Back-callback → `showBookmarks(folder, index)` (returns to list at same row).

### moveBookmarkStep(url, direction) — 3476–3484
- Finds bookmark; `list = store.inFolder(b.folder)`; index; if out of bounds in direction → no-op (stay).
- `editBookmarks { store.moveUp/moveDown(url) }`; recomputes new index; `showBookmarks(bookmarksFolder, updated, url)` (stays in moving mode).
- Called from listPanel key handling (Up/Down in moving mode).

### finishBookmarkMove() — 3486–3491
- Recomputes the moved bookmark's current index in its folder; `showBookmarks(bookmarksFolder, index)` (exits moving mode); `startPage.refresh()`.

### showMoveToFolder(b, folder, index) — 3493–3516
- Menu "Move to...": "No folder" (only if b.folder non-empty) → `store.move(b.url, "")`; one "Folder: <f>" per other folder → `store.move(b.url, f)`; "New folder..." → prompt → `store.move(b.url, name)`.
- All re-open `showBookmarks(folder, max(0, index-1))` (bookmark left the list position).
- Back → `showBookmarkOptions(b, folder, index)`.

### showFolderOptions(name, index) — 3518–3541
- Busy guard; `hideList()`. Menu "Folder: <name>":
  1. "Move to top" → `store.moveFolderToTop(name)`; `showBookmarks(null, 0)`.
  2. "Move up" → `store.moveFolderUp(name)`; `showBookmarks(null, max(0, index-1))`.
  3. "Rename" → prompt → `store.renameFolder(name, newName)`; `showBookmarks(null, index)`.
  4. "Ungroup (keep the bookmarks)" → `store.ungroup(name)`; `showBookmarks(null, 0)`.
- Back → `showBookmarks(null, index)`.

## Dark mode (3543–3599)

### darkModeSupported() — 3549–3555
- True if WebView supports ALGORITHMIC_DARKENING or FORCE_DARK (try/catch → false).

### applyDarkMode() — 3562–3595
- **Verified-working config (comment, do not change)**: FORCE_DARK_ON + strategy 2 (PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING). This is the only config where poster-grid site loads without crashing and video site buttons work; omitting setForceDarkStrategy (render-client default) breaks poster-grid site. SPA portal stays light (VitePress color-scheme) — do NOT inject CSS; every SPA portal dark-mode attempt crashed poster-grid site's PowerVR GPU.
- FORCE_DARK supported → setForceDark(ON/OFF) + setForceDarkStrategy(strategy 2); **diag `dark_mode_applied`** {method:"force_dark_strategy_2", enabled}.
- Else ALGORITHMIC_DARKENING → setAlgorithmicDarkeningAllowed; **diag `dark_mode_applied`** {method:"algorithmic_darkening", enabled}.
- Else **diag `dark_mode_applied`** {method:"none_supported", enabled}.
- All exceptions swallowed (cosmetic feature must never block startup).

### toggleDark() — 3597–(continues 3600+)
- If !darkModeSupported() → toast "This device's WebView cannot darken web pages" (continues in chunk 3).
# PART C — MainActivity.kt lines 2700–3956 (chunk 3: 3600–3956)

## toggleDark() (continued) — 3597–3607
- If `!darkModeSupported()` → toast "This device's WebView cannot darken web pages", return.
- Flips `darkMode`; persists `prefs "darkMode"`; `applyDarkMode()`; reloads if not on start page; toast "Dark mode ON/OFF".

## Other settings toggles (3609–3647)

### toggleAutoplay() — 3609–3615
- Flips `autoplay`; **diag `setting_autoplay`** {enabled}; persists `prefs "autoplay"`; `webView.settings.mediaPlaybackRequiresUserGesture = !autoplay`; toast "Autoplay without a click ON/OFF".

### toggleLowQuality() — 3617–3622
- Flips `lowQuality`; **diag `setting_low_quality`** {enabled}; persists `prefs "lowQuality"`; toast "Prefer low quality streams ON/OFF". (Consumed by injected JS, not here.)

### toggleExclusive() — 3624–3629
- Flips `exclusiveVideo`; **diag `setting_exclusive_video`** {enabled}; persists `prefs "exclusiveVideo"`; toast "One video at a time ON/OFF".

### toggleSoftwareRendering() — 3631–3647
- Flips `softwareRendering`; **diag `setting_software_rendering`** {enabled}; persists `prefs "softwareRendering"`.
- **Safety interlock**: if turning OFF while on a poster-grid site host (flixer.gd / *.flixer.gd / flixer.su / *.flixer.su, parsed from `lastPageUrl`, host lowercased, trailing dot stripped) → toast "Turning OFF on poster-grid site will crash - navigate away first", reverts `softwareRendering=true` + pref, returns (no recreate).
- Otherwise: if URL is http(s), saves `prefs "restoreAfterRecreateUrl" = url` (so onCreate restores after WebView recreation); toast "Software rendering ON/OFF - reloading"; `handler.post { recreate() }`.

## Settings menu (3650–3680)

### showSettings(focus) — 3651–3680
- Comment: "Toggles reopen the menu on the same row so several can be changed in a row."
- Items in order (label, action, re-focus index):
  0. "Dark mode: ON/OFF/not supported here" → `toggleDark()`; `showSettings(0)`.
  1. "Desktop site: ON/OFF" → `toggleDesktop()`; `showSettings(1)`.
  2. "Autoplay without a click: ON/OFF" → `toggleAutoplay()`; `showSettings(2)`.
  3. "Prefer low quality streams: ON/OFF" → `toggleLowQuality()`; `showSettings(3)`.
  4. "One video at a time: ON/OFF" → `toggleExclusive()`; `showSettings(4)`.
  5. "Software rendering: ON/OFF" → `toggleSoftwareRendering()`; `showSettings(5)`.
  6. "Diagnostics..." → `showDiagnosticMenu()`.
- `showMenuAt("Settings", items, focus) { showMainMenu() }` — back returns to main menu.
- **Focus-index dependency**: each toggle re-opens at its own index (0–5), so the D-pad stays on the row just changed.

## Diagnostic menu (3682–3729)

### showDiagnosticMenu() — 3682–3722
- `logging = DiagnosticLog.isEnabled(this)`. Items in order:
  1. "Diagnostic logging: ON/OFF" → if ON: **diag `logging_disabled`**, `DiagnosticLog.stop(this)`, `updatePageDiagnosticState(false)`, toast "Diagnostic logging OFF"; if OFF: `DiagnosticLog.startNewSession(this)`, `updatePageDiagnosticState(true)`, **diag `diagnostic_enabled`** {page: safeUrl}, toast "New diagnostic session started". Re-opens menu.
  2. "Start new diagnostic session" → `startNewSession`, `updatePageDiagnosticState(true)`, **diag `diagnostic_enabled`** {page}, toast, re-open.
  3. "Stop diagnostic logging" → **diag `logging_disabled`**, `stop`, `updatePageDiagnosticState(false)`, toast, re-open.
  4. "Export diagnostic log" → `exportDiagnostics()`.
  5. "Clear diagnostic log" → `DiagnosticLog.clear(this)`, `updatePageDiagnosticState(false)`, toast "Diagnostic log cleared", re-open.
  6. "Show diagnostics snapshot" → `showDiagnostics()`.
- `showMenuAt("Diagnostics", items, 0) { showSettings(4) }` — **back returns to Settings focused on row 4** ("One video at a time"), NOT row 5 (Software rendering) — quirk: the diagnostic menu was inserted between rows 4 and 5 of settings.

### updatePageDiagnosticState(enabled) — 3724–3729
- `webView.evaluateJavascript("window.__fb && __fb.setDiagnosticLogging && __fb.setDiagnosticLogging(true|false)", null)` in try/catch(Throwable). Tells the page helper to start/stop its own logging.

### exportDiagnostics() — 3731–3747
- If `!DiagnosticLog.hasData(this)` → toast "No diagnostic data to export", return.
- ACTION_CREATE_DOCUMENT, type application/zip, EXTRA_TITLE "Buccaneer-diagnostics-<epochMillis>.zip"; `startActivityForResult(REQ_DIAGNOSTIC_EXPORT)`.
- Exception → `report("Cannot open diagnostic export", e)` + toast "Diagnostic export is unavailable".

### versionName() — 3749–3755
- `packageManager.getPackageInfo(packageName, 0).versionName ?: "?"` (try/catch → "?").

## Diagnostics snapshot (3757–3808)

### showDiagnostics() — 3758–3808
- Captures `interaction = interactionSerial`. Builds a text report:
  - "Buccaneer <versionName>"
  - "Device: <MANUFACTURER> <MODEL>, Android <RELEASE> (API <SDK_INT>)"
  - "Web engine: Chrome <n>" (regex `Chrome/([0-9.]+)` from `defaultUa`, else "unknown")
  - "WebView provider: <pkg>/<version>" (WebViewCompat.getCurrentWebViewPackage, try/catch → "unknown")
  - "Memory: <totalMem MB> total, <availMem MB> free, low-RAM device: yes/no" (ActivityManager.getMemoryInfo; catch → "Memory: unknown")
  - "Dark mode: not supported by this WebView / ON / OFF"
  - "Page scripts run before the page: yes / no (fallback)" (`scriptsAtDocumentStart`)
  - "Diagnostic logging: ON/OFF, session: <first 12 chars of currentSessionId>"
  - "Popup Guard: ON/OFF, blocked this session: <blockedCount>"
  - "Autoplay without a click: ON/OFF,  one video at a time: ON/OFF" (note the double space)
  - "Last problem: <CrashLog.last(this) ?: 'none recorded'>"
- If start page shown → `showTextPanel("Diagnostics", sb)` immediately (no video section).
- Else: **diag `diagnostic_snapshot_requested`** {page: safeUrl}; `eval("window.__fb ? __fb.diag() : ''")` → callback guards `foreground && interaction == interactionSerial`; appends "Video on this page:\n" + `describeVideoReport(reportText)`; if report non-blank → **diag `diagnostic_snapshot`** with the full page JSON (`JSONObject(reportText)`, try/catch); `showTextPanel("Diagnostics", sb)`.

### yesNo(v) — 3851
- `v == 1 ? "yes" : "no"`.

### describeVideoReport(text) — 3853–3906
- Null/empty → "  The page helper script is not running on this page.\n".
- Parses `__fb.diag()` JSON:
  - `videos` array: if null/empty → "  No video element found in the main page.\n"; if `frames > 0` → "  A frame from another site is on the page. The player is probably inside it and cannot be inspected from here.\n".
  - Per video i: "  Video <i+1>: shown <w>x<h>, picture <vw>x<vh>, playing|paused[, muted], ready <rs>[, frames <fr> (dropped <dr>)][, ERROR code <e>]".
    - Heuristics: readyState≥2 && picW==0 → "sound is decoded but NO PICTURE yet: stream format, metadata, buffering or decoder state may be involved."; !paused && frames==0 → "playing, but no video frame has been decoded yet."; frames>50 && dropped*4>frames → "many frames are dropped: decoding, rendering, buffering or system load may be involved."
  - `mse` object: null → "This WebView has no MediaSource, which streaming players need."; else "Streaming codecs (MSE): H.264 <yes/no>, HEVC <yes/no>, VP9 <yes/no>, AV1 <yes/no>".
  - Parse exception → "  Could not read the video report.\n".
- Page JSON fields used: videos[].{w,h,vw,vh,fr,dr,p,m,rs,e}, frames, mse.{h264,hevc,vp9,av1}.

## onActivityResult (3810–3849)
- `@Suppress("DEPRECATION")`. Returns early unless `resultCode == RESULT_OK` and `data?.data` present.
- **REQ_BOOKMARK_EXPORT**: `runFileOperation { json = store.exportPortableJson(); resolver.openOutputStream(uri, "wt")?.use { write UTF-8 } ?: throw IOException("Cannot write bookmark document"); Result("Exported N bookmarks to selected document") }`.
- **REQ_BOOKMARK_IMPORT**: `importBookmarkUri(uri)`.
- **REQ_DIAGNOSTIC_EXPORT**: **diag `diagnostic_export_started`**; `DiagnosticLog.exportAsync(app, { resolver.openOutputStream(uri) ?: throw IllegalStateException("Cannot write diagnostic file") }) { error -> handler.post { if (!activityDestroyed && foreground) { error==null ? toast("Diagnostic log exported") : report("Diagnostic export failed", error) + toast("Diagnostic export failed") } } }`; outer catch → report + toast.

## Text panel & helpers (3908–3956)

### showTextPanel(title, text) — 3908–3919
- `interactionSerial++`, `cancelPendingFullscreen()`, `finishPendingTap(cancel=true)`, `hideCursor()`, `menu.hide()`, `listPanel.hide()`, `menuBack=null`, `textPanel.show(title, text)`, `bringToFront()`, `syncFocusBlocking()`.

### hideTextPanel() — 3921–3925
- `textPanel.hide()`, `syncFocusBlocking()`, `restoreFocus()`.

### report(what, e) — 3928–3935
- **diag `app_error`** (via `DiagnosticLog.recordCritical`) {what (≤160 chars), type = e.javaClass.name (≤200), message (≤1000)}.
- `CrashLog.record(this, what, Log.getStackTraceString(e))` — this is what "Last problem" in showDiagnostics displays.

### toast(msg) — 3941–3946
- Cancels `lastToast`, shows LENGTH_SHORT toast, stores in `lastToast`.

### toastOnce(msg) — 3949–3955
- Same toast but rate-limited: at most once per 2500ms (`lastInfoToastAt`), because key repeats must not spam.

Line 3956: closing brace of class MainActivity.
# PART C — SUPPLEMENT: explicitly-named functions that live outside lines 2700–3956

The task named fullscreen, video-command, OSD, cursor and watchdog functions; their actual line
numbers are below (verified against the live tree). They are covered here so Part C is complete
for those topics; the line-ordered core of this part is 2700–3956 in chunks 1–3.

## Fullscreen (native custom view + CSS path)

### onShowCustomView — 1157–1166 (WebChromeClient override)
- Guards `pageAvailable() && foreground`; else `callback?.onCustomViewHidden()` and return.
- **diag `fullscreen_show`**; `enterFullscreen(view, callback)`; on exception → `report("Error entering full screen", e)` + `callback?.onCustomViewHidden()`.

### onHideCustomView — 1168–1175 (WebChromeClient override)
- **diag `fullscreen_hide`**; `leaveFullscreen(false)` (notifyPage=false — the page initiated it); on exception → `report("Error leaving full screen", e)`.

### fullscreenVideo() — 2547–2568
- Comment: "the page only allows full screen after a real tap, so we tap for you."
- `cancelPlayerWork()`; captures `intent = playerIntent`, `interaction = interactionSerial`, `target = customView ?: webView`; sets `pendingFullscreen = true`.
- `eval("window.__fb ? __fb.prepareFullscreen() : 'none'")` → callback guards foreground/interaction/intent/target identity.
  - If raw != "ok": `cancelPendingFullscreen()` + toast "Fullscreen was not prepared; use the player's own button".
  - Else: `postForPage(150)` → guarded `sendTap(webView.width/2f, webView.height/2f)` (the "real tap" the page requires); **the delayed eval is `postForPage(2200)`** (2200 ms, not 2000): if still `customView == null && !cssFullscreenActive` → `cancelPendingFullscreen()` + toast "The page refused full screen. Use the player's own full-screen button."
- `pendingFullscreen` is also cleared by `videoSeek` (2406) and `cancelPendingFullscreen()` (2302).

### enterFullscreen(view, callback) — 2570–2586
- `interactionSerial++`; if `view == null || customView != null` → `callback?.onCustomViewHidden()`, return (never two custom views).
- `cancelPlayerWork()`, `finishPendingTap(cancel=true)`, `if (textMode == PAGE) closeKeypad()`.
- `customView = view`, `customCallback = callback`, adds view to `fullscreenBox` (MATCH/MATCH), `fullscreenBox.visibility = VISIBLE`, `keepScreenOn = true`, `hideCursor()`.
- `refreshOsd(500, "Select: play/pause    Left/Right: seek    Up/Down: volume    Back: exit")`.

### leaveFullscreen(notifyPage) — 2588–2603
- Comment: notifyPage=true only when WE leave (Back key) and must tell the WebView.
- `interactionSerial++`; `view = customView ?: return`; `cancelPlayerWork()`, `finishPendingTap(cancel=true)`; nulls customView/customCallback; removes view from fullscreenBox; `visibility=GONE`, `keepScreenOn=false`; `osd.hideNow()`; `webView.requestFocus()` if page available; `callback?.onCustomViewHidden()` only if notifyPage && pageAvailable.

### exitCssFullscreen() — 2606–2617
- CSS (page-level) fullscreen path, flag `cssFullscreenActive` (declared 265).
- If flag not set → return. `eval("window.__fb ? __fb.exitCssFullscreen() : 'none'")`; callback: the page's exit normally reports back through the bridge and clears the flag; if the flag is STILL set (JS error, mid-navigation) → clear it, `osd.hideNow()`, `webView.requestFocus()`.

### onFullscreenKey(event) — 2621–2695 (keys while video is full screen)
- BACK (rep==0): `customView != null ? leaveFullscreen(true) : exitCssFullscreen()`; always consumed.
- DPAD_CENTER / ENTER / NUMPAD_ENTER (rep==0): `videoCommand("togglePlay()", KEYCODE_SPACE, tapFrame=true)`; consumed.
- DPAD_LEFT: `seekFromKey(event, -1)`; DPAD_RIGHT: `seekFromKey(event, 1)`; consumed (repeat allowed — seekRepeat handles steps).
- DPAD_UP / VOLUME_UP: `adjustVolume(1)` on `rep == 0 || rep % 2 == 0` (throttles key repeat to every other repeat); consumed.
- DPAD_DOWN / VOLUME_DOWN: `adjustVolume(-1)` same throttle; consumed.
- VOLUME_MUTE (rep==0): captures interaction/intent/target; `current()` = foreground + serials + target identity + `!keypad.shown()`. `eval(__fb.unmute())`:
  - "ok"/"frame" → `refreshOsd(1500, "Unmuted")`.
  - "none" → `eval(__fb.muteButton())`: parses {ok,x,y}; if ok → `sendTap(x*w, y*h)` (taps the page's own mute button); else `videoCommand("toggleMute()", KEYCODE_M)`.
  - else → nothing.
- MENU (rep==0): `refreshOsd(0, "Select: play/pause    Left/Right: seek    Up/Down: volume    Back: exit")`.
- Anything else → false (falls through to normal key handling).

## Video commands (2344–2471)

### videoCommand(call, fallbackKey, tapFrame) — 2344–2362
- `cancelPlayerWork()`; captures `intent = playerIntent`, `target = customView ?: webView`.
- `eval("window.__fb ? __fb.$call : 'none'")` → callback guards foreground + intent + target identity.
- **Exact return-value semantics** (jsString(raw)):
  - `"ok"` → page helper ran the command in the top frame. If `tapFrame`: `postForPage(350)` → guarded `retryIfPlayRefused()` (handles browsers that refuse play() until a real click).
  - `"frame"` → the player is in a cross-origin frame; the frame's own helper already ran the command. Special case: `tapFrame && fallbackKey == KEYCODE_MEDIA_PLAY_PAUSE && onFlixerPage` → `tapPlayerFrame(mouse = true)` (poster-grid site's player frame is not scripted; a real tap starts/toggles it).
  - `"none"` or null → no helper / no video: DPAD-style fallback `sendKeyToPage(fallbackKey)`.
  - `else` (any unexpected string) → same fallback `sendKeyToPage(fallbackKey)`.
  - **The DPAD fallback path fires when the page helper is absent, reports no video, or returns garbage** — i.e. it synthesizes the equivalent hardware key (SPACE for play/pause, M for mute) into the page.
- Always ends with `refreshOsd(150, "")`.

### retryIfPlayRefused() — 2365–2372
- Comment: "The browser refuses play() until the page has had a click. Then a real tap on the player does it."
- Guards foreground; captures intent/target; `eval(__fb.playBlocked())`; if result == "1" (play was refused) → `tapPlayerFrame()` (default mouse = onFlixerPage).

### tapPlayerFrame(mouse = onFlixerPage) — 2375–2397
- Comment: "The player is inside a frame from another site, so we tap its middle, as a finger would."
- `eval(__fb.frameCenter())` → JSON {ok, x, y} (fractions); defaults fx=fy=0.5 if unparseable.
- If ok → `sendTap(fx*webView.width, fy*webView.height, mouse)`; else → `sendKeyToPage(KEYCODE_SPACE)` (last-resort play/pause key).

### seekFromKey(event, direction) — 2399–2402
- `seconds = seekRepeat.step(keyCode, repeatCount, downTime, eventTime)` (SeekRepeatPolicy: small step on first press, larger on hold/repeat); if != 0 → `videoSeek(seconds * direction)`.

### videoSeek(seconds) — 2404–2440
- Guards: `pageAvailable() && foreground && seconds != 0`.
- If `pendingFullscreen` → `cancelPendingFullscreen(); playerIntent++` (seeking abandons a pending fullscreen attempt).
- **seekInFlight/queuedSeek**: if a seek is already in flight → `queuedSeek = (queuedSeek + seconds).coerceIn(-120, 120)`, return (coalesce; queue bounded ±120 s).
- Else: `seekInFlight = true`; `generation = ++seekGeneration` (monotonic token invalidating stale callbacks); captures intent/target.
- `valid()` = foreground && generation == seekGeneration && intent == playerIntent && target identity.
- `finish()` (only if valid): `seekInFlight = false`; drains `queuedSeek` into a fresh `videoSeek(next)`.
- `amount = seconds.coerceIn(-60, 60)` (single seek bounded ±60 s); overflow beyond that goes to `queuedSeek` (coerced ±120).
- `eval(__fb.seek(amount))`:
  - `"ok"` or `"frame"` → `refreshOsd(150, "Hold FF/Rewind: larger skips")`; `finish()`.
  - else (no helper/no video) → **DPAD fallback path**: `eval(__fb.focusTarget())` first (focus the selected iframe WITHOUT a center click that might toggle playback), then `count = (abs(amount)/10).coerceIn(1, 6)` key presses of DPAD_RIGHT (amount>0) or DPAD_LEFT, posted at 80 ms intervals, each guarded by `valid() && !keypad.shown() && !startPage.shown()` via `sendKeyToPage(key)`; then `postForPage(count*80+40)` → `refreshOsd(75, "Native seek steps depend on this player")`; `finish()`.

### seekToFraction(fraction) — 2442–2457
- Guards finite + pageAvailable + foreground; `cancelPlayerWork()`; captures intent; `amount = fraction.coerceIn(0f, 1f)`.
- Comment: "Passive, one-shot API only: no new player hooks, observers or site styling."
- Inline IIFE: parses `__fb.state()`; requires `s.f==1` (video found) and finite `s.d > 0`, `s.t`; `t = s.d * amount`; `r = __fb.seek(t - s.t)`; if r != "ok" return r; re-reads state; returns "ok" if `abs(after.t - t) <= 2` else "blocked" (catch → "blocked").
- Callback: guards foreground + intent; if result != "ok" → `toastOnce("This player did not accept the position; use FF/Rewind")`; always `refreshOsd(150, "Select position • Hold FF/Rewind for larger skips")`.

### adjustVolume(dir) — 2459–2471
- If `!audio.isVolumeFixed` (system volume works): `audio.adjustStreamVolume(STREAM_MUSIC, RAISE/LOWER, 0)`; `refreshOsd(0, "")`.
- Else (TV pins system volume): `eval(__fb.volume(dir * 0.1))`; if "none" → `toastOnce("Volume is controlled by your TV")`; `refreshOsd(60, "")`.

## OSD (2473–2544)

### refreshOsd(delayMs, hint) — 2473–2508
- Guards `pageAvailable() && foreground`; `cancelOsdRefresh()` (cancels previous pending refresh — coalescing); captures `request = osdRequest` (incremented elsewhere) and `epoch = pageEpoch`.
- Posts a Runnable (min delay 75 ms): re-checks pageAvailable/foreground/epoch/request; `eval(__fb.state())` → parses {f: found, p: paused, t: time, d: duration, v: volume, m: muted} (all opt defaults); `showOsd(found, paused, t, d, vol, muted, hint)`.
- `osdRefresh` holds the pending runnable (declared 269); `cancelOsdRefresh()` (284) removes it.

### showOsd(found, paused, t, d, videoVolume, muted, hint) — 2510–2544
- status = "No video detected" / "PAUSED" / "PLAYING".
- seek fraction = (t/d).coerceIn(0,1) when found && finite && d>0, else null (drives the seek bar).
- time = formatTime(t) [+ " / " + formatTime(d) if d>0].
- Volume: if system volume not fixed → stream volume/max as "Volume N%"; else video's own volume as "Video volume N%"; muted → fraction 0, "Muted".
- `osd.show(status, seek, time, volText, volFraction, hint)`.
- If `customView == null && cursor.visibility == VISIBLE` → `cursor.bringToFront()` (keep cursor above OSD in non-fullscreen).

### tapAtCursor() hit-testing — 2087–2125
- **First action**: `osd.seekFractionAt(cx, cy)` (Osd.kt:90 — hit-test against the OSD seek bar in root coordinates) → if it returns a fraction: `seekToFraction(it)`, `showCursor()`, return. This is how tapping the seek bar seeks.
- Otherwise: records `lastTapAt`; captures interaction/editor/target; `current()` = foreground + serials + target + `textMode==NONE` + no menu/list/textPanel/startPage.
- `eval(__fb.selectAt(x/w, y/h))` (on-demand element selection, top frame).
- poster-grid site special path (`onFlixerPage && customView == null`): `eval(seekClickJs(x/w, y/h, 'probe'))` → if result in {"track","frame"} use a mouse tap (`sendTap(x, y, mouse=true)`) and `repairSeekClick(x, y, interaction)`; else a touch tap. Comment: mouse input only over a recognized timeline or inside an opaque player frame.
- Non-poster-grid site: `sendTap(x, y)` (touch).
- `showCursor()`; then on-demand field reads at 150 ms and 650 ms: `eval(__fb.field())` → `focusFromPage(state)` (covers poster-grid site without listeners/instrumentation).
- `handler.postDelayed({ hideIme() }, 300)`.

### repairSeekClick(x, y, interaction) — 2128–2136
- Comment: "One-shot fallback for identifiable seek tracks only; no poster-grid site listeners/timers/DOM styling."
- Guards width/height > 0 and `seekClickJs` non-blank; `postForPage(120)`: if still onFlixerPage, no customView, interaction unchanged, no keypad/menu/list/textPanel → `eval(seekClickJs(x/w, y/h))` (re-issues the seek click in the track's frame).

### sendTap(x, y, mouse = false) — 2139–2178
- Comment: "Trusted native input: touch by default, mouse only for poster-grid site pointer/player clicks."
- Guards pageAvailable + foreground; captures `epoch = pageEpoch`, target.
- **diag `tap_dispatched`** {x, y, w, h}.
- If DiagnosticLog enabled: `eval(__fb.tapStack(x, y))` → **diag `tap_stack`** with the element stack JSON (best-effort; shows which element eats the click — for cross-origin players the top element is the <iframe>, which is itself the answer).
- `finishPendingTap(cancel = true)` (previous tap gets CANCEL); creates `PendingTap(target, downAt, x, y, mouse)`; dispatches ACTION_DOWN via `target.dispatchTouchEvent` (PointerTap.event); on exception → `finishPendingTap(cancel=true)`.
- "Navigation can happen synchronously while DOWN is being dispatched" → if `pendingTap !== tap` return.
- Schedules UP after 70 ms: if page gone → `finishPendingTap(cancel=true, deliver=false)`; else cancel if `epoch != pageEpoch || target !== (customView ?: webView)`.

### finishPendingTap(cancel, deliver = true) — 2181–2191
- Comment: "Every live DOWN terminates with UP or CANCEL; renderer loss clears without native calls."
- If cancel: `selectPending = false; selectLongFired = false`.
- Clears pendingTap/tapUp (removes scheduled UP); if no tap or !deliver or !pageAvailable → return (no native call — renderer may be dead).
- Dispatches ACTION_UP (or ACTION_CANCEL) at (tap.x, tap.y) to `tap.target` in try/catch.

## Cursor (2074–2232 + state)

State: `cursor: CursorView` (110, constructed at 536 with `cursorSize`); `hoverActive` (398); `lastHoverAt` (399); `hideCursorRunnable` (400–412); `playbackHeartbeatAt` (152, @Volatile, updated at 129 by the heartbeat UI coalescer and zeroed at 921/2330 on navigation/reset); `cx/cy` pointer position (390–391).

### showCursor() — 2074–2078
- `cursor.visibility = VISIBLE`; cancels and re-posts `hideCursorRunnable` after **6000 ms** (auto-hide).

### hideCursor() — 2080–2085
- Removes hideCursorRunnable; `cursor.visibility = GONE`; `liftPointerIfPlaying()`; `hoverActive = false`.

### liftPointerIfPlaying() — 2200–2215
- Comment: the browser parks the simulated mouse at the last hover position and never tells the page it is gone, so pointer-aware players (video site) see "pointer over the controls" forever and pin controls visible. Lifting only while video plays: while browsing, the parked pointer keeps hover menus open while the user reads them.
- Guards: `hoverActive` true AND `playbackHeartbeatAt > 0` AND `now - playbackHeartbeatAt < 12_000` (heartbeat proves video is playing; 12 s window).
- Sends `ACTION_HOVER_EXIT` (SOURCE_MOUSE) at (cx, cy) to webView via dispatchGenericMotionEvent; **diag `pointer_lifted`** {x, y}; exceptions swallowed ("hover is a nicety").

### sendHover(x, y) — 2218–2232
- Comment: "Mouse-style hover so menus and video controls that react to the pointer appear."
- Rate-limited to one per 40 ms (`lastHoverAt`).
- First hover → ACTION_HOVER_ENTER, subsequent → ACTION_HOVER_MOVE; sets `hoverActive = true`; SOURCE_MOUSE; dispatchGenericMotionEvent; exceptions swallowed.

### CursorView (CursorView.kt:14)
- `class CursorView(context, sizePx = Ui(context).u(24)) : View` — the on-screen pointer drawn at (cx, cy); sized in UI units (24u default).

## Watchdog (662–672 + Watchdog.kt + onDestroy 701–705)

### startWatchdog() — 662–672
- Comment: "The watchdog lives in a second process so it survives a crash that kills this one."
- `startService(WatchdogService)` (separate process); creates an empty `ServiceConnection` (connected/disconnected no-ops), stores it in `mainProcessConnection`, `bindService(MainProcessService, connection, BIND_AUTO_CREATE)`.
- The bind keeps `MainProcessService` alive in the MAIN process; its binder is what the watchdog watches. Whole body in try/catch(Throwable).

### MainProcessService (Watchdog.kt:83–91)
- Lives in the main process; `onBind` returns a fresh `Binder()`; `onUnbind` → `stopSelf()` + return false.

### WatchdogService (Watchdog.kt:24–80)
- Own process, `START_STICKY`. `onCreate` → `watchMainProcess()`: binds to MainProcessService, on connect stores the binder and `linkToDeath({ mainProcessGone() }, 0)`; on disconnect `watched = null`.
- `mainProcessGone()`: reads flags from shared prefs "settings" (MODE_MULTI_PROCESS so main-process writes are read fresh):
  - `cleanExit == true` → user exited normally; do nothing.
  - `wasForeground == false` → killed while backgrounded; do nothing.
  - `allowRestart` crash-loop guard: at most **3 restarts in 10 minutes** (window `watchdogWindowStartedAt`, counter `watchdogRestartCount`).
  - Sets `watchdogRestart = true` and `startActivity(MainActivity, FLAG_ACTIVITY_NEW_TASK)`; MainActivity reopens the page it was on.
- Clean exit is set in `onDestroy` (700): only `isFinishing` sets `cleanExit=true` (commit, not apply — must hit disk before death); a process death leaving it false is the crash being watched. `wasForeground` is set in onResume (676).

### Unbind — onDestroy 701–705
- `mainProcessConnection` unbound in try/catch, nulled. (Unbinding stops MainProcessService via onUnbind→stopSelf; the watchdog's death link then fires only if the main process actually dies.)

## diagKey reference (1734–1737)
- `diagKey(code, handler)` → **diag `key`** {code: KeyEvent.keyCodeToString(code), handler}.
- No `diagKey` calls occur inside lines 2700–3956; all call sites are in the key-dispatch handlers (1744–1975, Part B range).

## Complete diagnostic event list emitted in lines 2700–3956
| Event | Line | Payload |
|---|---|---|
| `edit_result` | 2857 | action, field, status (state's "status" or "none") |
| `setting_popup_guard` | 3187 | enabled |
| `setting_desktop` | 3203 | enabled |
| `storage_permission` | 3259 | granted |
| `dark_mode_applied` | 3576 / 3582 / 3587 | method ("force_dark_strategy_2" \| "algorithmic_darkening" \| "none_supported"), enabled |
| `setting_autoplay` | 3611 | enabled |
| `setting_low_quality` | 3619 | enabled |
| `setting_exclusive_video` | 3626 | enabled |
| `setting_software_rendering` | 3633 | enabled |
| `logging_disabled` | 3687, 3707 | (none) |
| `diagnostic_enabled` | 3694, 3702 | page (DiagnosticLog.safeUrl) |
| `diagnostic_snapshot_requested` | 3797 | page (safeUrl) |
| `diagnostic_snapshot` | 3804 | full page `__fb.diag()` JSON |
| `diagnostic_export_started` | 3833 | (none) |
| `app_error` (recordCritical) | 3929 | what (≤160), type (exception class, ≤200), message (≤1000) |

Events from the supplement ranges (for completeness): `tap_dispatched` {x,y,w,h} (2143), `tap_stack` {element stack JSON} (2155), `pointer_lifted` {x,y} (2211), `fullscreen_show` (1159), `fullscreen_hide` (1169), `popup_requested` {gesture,dialog,guard} (1186).

## showCrashRecovery (325–330)
- Menu "Page recovery" (shown after a renderer crash; `clearCrashRecovery` at 319 resets the recovery state):
  1. "Retry the page" → `clearCrashRecovery(); openUrl(url)`.
  2. "Return home" → `clearCrashRecovery(); showStartPage(true)`.
- Back-callback → `clearCrashRecovery(); showStartPage(true)` (Back also goes home).
- Focus index 0 (via showMenu).
