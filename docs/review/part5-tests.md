# Part 5 — Test Suite Analysis: BuccaneerClaude (com.buccaneer.browser)

Live tree: `<repo-root>` (v2.3.1). All statuses verified by execution on 2026-10-21.

## Live gate status RIGHT NOW

| Check | Command | Result |
|---|---|---|
| Protected fixes | `python3 tests/check-protected-fixes.py` | **PASS** (exit 0): "PASS: Flixer/CineJoy image guard, Popup Guard, native guards, and dark mode preserved." |
| Warning repairs | `python3 tests/check-warning-repairs.py` | **FAIL** (exit 1): prints the protected-fixes PASS line (it runpy's that script), then dies at line 13 with `AssertionError: flixer_image_guard.js` — the 1.9.1 hardware baseline hash is stale vs the current asset. Steps 5–11 of that script (manifest, icons, lint, @SuppressLint inventory) never execute. |

Node .cjs tests (node v26.8.1 installed): **PASS** — seek-click-tests, fmhy-theme-tests, release18-script-tests, seek-mapping-integration-tests. **FAIL (stale vs v2.x assets)** — quality-tests (`pickCapLevel` missing from slice), logic19-tests (`wrapHlsConstructor` missing from slice), quality199-startup (`capHls` missing from slice), integration-pass-tests (4/6 fail: mock window lacks `addEventListener`; `capShaka` not in slice). **CANNOT RUN** — cinejoy199-quality (its asset `cinejoy_quality.js` was removed from the tree).
Headless-Chromium runners (5) and Playwright DOM tests (3): **CANNOT RUN here** — no chromium/chromium-browser/google-chrome binary and no `playwright` module in this environment.
Kotlin Robolectric tests (15 classes): **CANNOT RUN here** — no `gradlew` wrapper script in repo root, no Android SDK (`ANDROID_HOME` unset, no `local.properties`). CI (`.github/workflows/build.yml`) only builds the release APK on tags; it does not run the unit tests.

---

## 1. Kotlin unit tests (`app/src/test/java/com/buccaneer/browser/`)

# Kotlin tests — how to run (all 15 files)

**Location:** `app/src/test/java/com/buccaneer/browser/` (unit tests, `app` module).

**Standard command (from repo root, requires Android SDK + JDK 17):**
```
./gradlew :app:testDebugUnitTest                          # all 15 classes
./gradlew :app:testDebugUnitTest --tests "com.buccaneer.browser.StabilityTest"   # one class
```
Robolectric-based classes need the Android SDK (or Robolectric's auto-downloaded android-all jars) and JDK 17. `GeneralStability196Test` is pure JUnit (no Robolectric) and would run with any JUnit harness.

**Environment status of THIS machine (verified 2026-10-21):**
- NO `gradlew` wrapper script in the repo root (only `gradle/wrapper/gradle-wrapper.properties` pointing at Gradle 8.5; the wrapper jar/script are absent).
- NO `local.properties`, `ANDROID_HOME`/`ANDROID_SDK_ROOT` unset → **the Kotlin tests CANNOT be executed in this environment**. Java exists (`/usr/bin/java`) but there is no Gradle entry point and no Android SDK.
- CI (`.github/workflows/build.yml`) only builds the release APK on tag pushes (`gradle assembleRelease` with JDK 17 + Gradle 8.5, keystore from secrets); it does NOT run the unit-test suite. So the Kotlin tests are run locally/by maintainers, not by CI.

**Common patterns across the 15 classes:**
- Robolectric `@RunWith(RobolectricTestRunner)`, `@LooperMode(PAUSED)`, `@Config(sdk=…)` (mostly 28; matrices: PageEditor199 [22,25,28], Structural1910/KeyboardRecovery193 [25,28], Api34IntegrationSmoke/PortableBackup19 [34], WarningRepair one test at 22).
- Custom `@Implements(WebView)` shadows intercepting `evaluateJavascript` to record/canned-reply the page-helper scripts (`__fb.*`, `__tvUi.*`): `Regression171Test.ScriptWebView` (reused by DeepAudit, Release18, Api34), `Logic19Test.LogicWebView`, `PageEditor199Test.EditorShadow`, `Structural1910Test.ControlledWeb`, `IntegrationPassTest.DelayedWebView`, `KeyboardRecovery193Test.EditorWebView`.
- Heavy reflection into `MainActivity` private fields/methods (get/set/call helpers) — tests break if private names change.
- `GeneralStability196Test` is the only framework-free class (UiCoalescer + JsonInputBudget).
# StabilityTest.kt (188 lines, 8 tests)

**Run:** `./gradlew :app:testDebugUnitTest --tests "com.buccaneer.browser.StabilityTest"` (Robolectric unit test; needs Android SDK + Gradle wrapper).
**Setup:** `@RunWith(RobolectricTestRunner)`, `@Config(sdk=[28])`, `@LooperMode(PAUSED)`.

**Tests & assertions:**
1. `twentyMinuteIdlePolicyProtectsPlaybackAndResetsOnInput` — `IdleDimPolicy.shouldDim` fires exactly at `TIMEOUT_MS` (not 1ms before), is suppressed while playing (`shouldDim(..., true)` false), and `activity(ts)` resets the timer.
2. `dimOverlayWakesWithoutActivatingFirstKey` — after forcing idle, `updateIdle(false)` makes `dimOverlay` VISIBLE; first MENU key-down hides the overlay (GONE) but does NOT open `menu` (MenuPanel.shown() false); second MENU press opens it.
3. `appQwertyNeverBlindlyInjectsTextIntoAFullscreenView` — with a fullscreen `customView` installed, `openPageKeypad()` + `typeText("abc")` injects zero key events into the view; `buffer` stays empty; `TvWebView` has no declared field `editor`.
4. `chromiumEditorIsNotDisabledToHideSystemIme` — `TvWebView` must not override `onCreateInputConnection` or `onCheckIsTextEditor` (Chromium's IME path stays intact).
5. `retryBudgetDoesNotDependOnPageFinished` — `CrashRecoveryPolicy.nextCount(url, lastUrl, lastStart, count, now)`: starts at 1, increments per same-URL retry, resets to 1 on URL change, resets after 600s window; `expired(start, now)` true only when now < start (or per its semantics) — budget logic independent of onPageFinished.
6. `rendererLossDestroysViewAndDoesNotResetRetryBudgetOnFinish` — `onRenderProcessGone` (didCrash) sets `webViewDestroyed=true`, detaches the WebView from its parent, increments `crashReloadCount` to 1 in "settings" prefs; a late `onPageFinished` from the dead view is ignored (count stays 1); pause/stop/destroy lifecycle doesn't touch the dead view.
7. `crashHandlerInstallsOnlyOnce` — `CrashLog.install(app)` is idempotent: second install keeps the same default uncaught exception handler.
8. `diagnosticQueueIsBoundedAndExportsRoutineAndCriticalData` — `DiagnosticLog`: with the writer thread deliberately blocked, 1000 `record()` calls keep the `pending` ArrayBlockingQueue ≤256 (and ≥240, i.e. bounded+backpressure); `recordCritical` bypasses the blocked writer, lands in `diagnostic_critical.jsonl`, and redacts secrets (`token=secret` absent); `exportAsync` produces a ZIP with `events.jsonl` (contains `events_dropped`, `session_stop`) + `critical.jsonl` (contains the crash); a fresh session summarizes repeated `subresource_error` as one `event_burst` with `additionalCount` (no per-item rows); `isEnabled` false after stop; `clear()` works.

**Production code protected:** `IdleDimPolicy` (TIMEOUT_MS, shouldDim, activity), `MainActivity` fields/methods (`idlePolicy`, `dimOverlay`, `menu`, `updateIdle`, `customView`, `openPageKeypad`, `typeText`, `buffer`, `webView`, `webViewDestroyed`), `TvWebView` (no IME overrides, no editor field), `CrashRecoveryPolicy` (nextCount, expired), `CrashLog` (install), `DiagnosticLog` (initialize, startNewSession, record, recordCritical, stop, exportAsync, isEnabled, clear, writer/pending internals), SharedPreferences "settings"/`crashReloadCount`.

# Logic19Test.kt (162 lines, 13 tests)

**Run:** `./gradlew :app:testDebugUnitTest --tests "com.buccaneer.browser.Logic19Test"`.
**Setup:** Robolectric sdk 28, qualifiers `w1280dp-h720dp-mdpi`, custom shadow `LogicWebView` (`@Implements(WebView)`) intercepting `evaluateJavascript`: records every script; can force `__tvUi.scrollAt` to throw (`failScroll`), hang (`holdScroll`), hang `__fb.seek(` (`holdSeek`); canned replies for `__fb.playBlocked()`→"1", `__fb.frameCenter()`→`{"ok":1,"x":0.5,"y":0.5}`, `__fb.play()/pause()`→"ok".
**Purpose (header comment):** regression contracts for independently reproduced interaction failures.

**Tests & assertions:**
1. `pauseCancelsEveryEarlierPlayRepair` — `videoCommand("play()", MEDIA_PLAY, true)` schedules a delayed repair tap; a later `videoCommand("pause()", ...)` invalidates it: zero touch events after 450ms.
2. `backgroundingCancelsPlayRepair` — pausing the Activity cancels the pending play repair (no tap while backgrounded; `foreground` false).
3. `evaluationExceptionReleasesScrollQueue` — an exception thrown by `evaluateJavascript` on `__tvUi.scrollAt` releases `scrollPending`; the next `scrollPage(0,100)` proceeds (new scroll script emitted).
4. `navigationOwnsFullscreenTeardown` — `onPageStarted` for a new URL nulls `customView` (fullscreen target released by navigation).
5. `unqualifiedOldFrameCannotReplaceKeyboardTarget` — a stale `Bridge().editableFocused("old-frame", …)` cannot overwrite a newer `inputFrame` ("new-frame").
6. `missingEvaluationCallbackTimesOutAndUnblocksTheNextScroll` — a scroll whose JS callback never fires times out (~2100ms) clearing `scrollPending`; the next scroll is not blocked.
7. `seekQueueIsBoundedAndLateCompletionAfterPauseCannotDrainIt` — 100 `videoSeek(60)` calls coalesce: exactly 1 in-flight seek callback, `queuedSeek`=120; after `pause()`, a late "ok" completion resets the queue (`queuedSeek`=0, `seekInFlight`=false) without re-seeking.
8. `currentDocumentReportsCannotRedirectAPinnedField` — `Bridge().currentDocument()` token pins `inputFieldId` ("first"); a second `editableFocused` for another field in the same doc is rejected (`inputFieldState` never contains the wrong value).
9. `importRejectsUnsafeUrlsAndMalformedFoldersAndSupportsUndo` — `BookmarkStore.importJson`: rejects `javascript:` URLs, rejects malformed `folders:[42]` (keeps existing data), accepts valid import, `hasPreviousImport()` + `undoImport()` restore the old bookmark.
10. `transferResultWaitsForTheReplacementScreenInsteadOfDisappearing` — `BookmarkJobs`: a job's result is NOT delivered to a detached listener; it waits and is delivered to the newly attached listener.
11. `oldDocumentCannotClaimMediaOwnershipOrExtendPlaybackHeartbeat` — after navigation, `mediaOwner`/`playbackHeartbeatAt` reset; stale-doc `mediaStarted`/`playbackActive` calls are ignored; current-doc `playbackActive` sets a heartbeat; backgrounding clears the owner.
12. `resizedRootClampsPointerAndNativePanelWidthsWithoutRecreatingWebView` — shrinking root to 800×450 clamps `cx`/`cy` and `keypad`/`osd` layout widths to ≤800; the `webView` instance is the same object (no recreation).
13. `largeBookmarkListCreatesOnlyOneManagementPageOfRows` — 10,000 imported bookmarks: `showBookmarks(null,0)` renders exactly 50 rows (mark-0…mark-49); `showBookmarks(null,9950)` renders the last page (mark-9999 present, mark-0 absent).

**Production code protected:** `MainActivity` (`videoCommand`, `scrollPage`, `videoSeek`, `showBookmarks`, fields `scrollPending`, `queuedSeek`, `seekInFlight`, `customView`, `inputFrame`, `inputFieldId`, `inputFieldState`, `store`, `listPanel`, `menu`, `foreground`, `lastTapAt`, `lastUserInputAt`, `mediaOwner`, `playbackHeartbeatAt`, `cx`, `cy`, `keypad`, `osd`, `root`, `webView`), `Bridge` (`editableFocused`, `currentDocument`, `mediaStarted`, `playbackActive`), `BookmarkStore` (importJson, undoImport, hasPreviousImport, all, add), `BookmarkJobs` (attach/detach/submit/isBusy, Result, Listener), `StartPage.hide`.

# Regression171Test.kt (161 lines, 7 tests)

**Run:** `./gradlew :app:testDebugUnitTest --tests "com.buccaneer.browser.Regression171Test"`.
**Setup:** Robolectric sdk 28, `w1280dp-h720dp-mdpi`, shadow `ScriptWebView` (`@Implements(WebView)`) intercepting `evaluateJavascript`: records scripts; canned replies for `'probe'`→seekResult, `__fb.frameCenter()`→center JSON, `__fb.togglePlay()/play()/pause()/seek(`→mediaResult.
**Purpose (header):** exercises real Activity `dispatchKeyEvent`/panel transitions, not just helper methods.

**Tests & assertions:**
1. `hardwarePlayWorksInBrowseMenuBookmarkListAndFullscreen` — `KEYCODE_MEDIA_PLAY_PAUSE`/`KEYCODE_MEDIA_PLAY` dispatched through the real Activity emit exactly one `__fb` media script each in: browse mode, main menu open, bookmark list open, fullscreen (customView set). A repeated key event (`repeatCount=1`) does NOT re-dispatch.
2. `keyboardRetainsPlayAsEnterInsteadOfStartingVideo` — while the URL prompt (`promptUrl`) is open, the media play key does NOT start video (zero media scripts) — Play stays the Enter/Go shortcut for text entry.
3. `missingHelperFallsBackToOneNativeKeyPairAndPauseNeverTapsPlayer` — with no page media helper (`mediaResult="none"`), the hardware key falls back to forwarding exactly one native DOWN/UP key pair to the fullscreen view; with `mediaResult="frame"` and `KEYCODE_MEDIA_PAUSE`, one native pair is forwarded and NO synthesized tap occurs after 400ms.
4. `importAppliesImmediatelyAndInvalidImportKeepsExistingDataWithoutConfirmation` — `importBookmarkText` (invoked via reflection; production function with no call sites in src/main, kept alive by this test) applies a valid JSON import immediately to the store, keeps the bookmark list panel shown (menu closed), and shows NO "Replace current bookmarks?" confirmation; an invalid import (`{"items":[{"u":42}]}`) leaves existing data intact.
5. `importExportMenusReplaceBookmarkPanelAndAreAboveFullscreen` — `showMenu("Import bookmarks", …)` replaces the list panel (menu shown, listPanel hidden) and the MenuPanel sits above `fullscreenBox` in root z-order; reopening bookmarks restores the list panel.
6. `flixerSelectSendsMouseDownUpButOtherSitesStillGetTouch` — on a Flixer page (`onFlixerPage=true`) where the seek probe reports "track", `KEYCODE_DPAD_CENTER` synthesizes a mouse-source ACTION_DOWN+UP pair (`SOURCE_MOUSE`, `TOOL_TYPE_MOUSE`, primary button on down / none on up); when the probe reports "none" (working Flixer button) or on a non-Flixer page, events are touchscreen-sourced. (WebView viewport is faked via mLeft/mTop/mRight/mBottom reflection because Robolectric's fake provider doesn't lay out.)
7. `pointerBuilderSetsCoordinatesToolButtonsPressureAndTiming` — `PointerTap.event(x, y, action, downTime, eventTime, mouse)` builds MotionEvents with exact coordinates, downTime/eventTime, pressure 1.0 on DOWN / 0.0 on UP, and mouse vs finger tool type.

**Production code protected:** `MainActivity` key dispatch (`dispatchKeyEvent` path: `videoCommand`/media handling, `promptUrl`, `showMainMenu`, `showBookmarks`, `closeAllPanels`, `importBookmarkText`, `showMenu`, fields `menu`, `listPanel`, `fullscreenBox`, `root`, `customView`, `onFlixerPage`, `webView`, `store`), `PointerTap.event`, `MenuPanel`/`ListPanel` (shown()), `BookmarkStore`, `BookmarkJobs`.

# PageEditor199Test.kt (143 lines, 13 tests)

**Run:** `./gradlew :app:testDebugUnitTest --tests "com.buccaneer.browser.PageEditor199Test"`.
**Setup:** Robolectric sdk `[22,25,28]` (matrix), `w960dp-h540dp-xhdpi`, shadow `EditorShadow` (`@Implements(WebView)`) intercepting `evaluateJavascript`: records `__tvUi.edit(` commands and their callbacks (other scripts get "null"). Helpers: `report()` simulates `Bridge().editableFocused` with a current-document token; `ack()` feeds a command acknowledgement JSON back.

**Tests & assertions:**
1. `typingDeletionAndSubmitAreOrderedAndDoNotCloseBeforeCommit` — `typeText("a")`, `typeText("b")`, `typeBackspace()`, `typeGo()` emit `__tvUi.edit` commands strictly in order (insert "b", delete, submit); the Keypad stays shown until the ack carries status "submitted".
2. `timeoutNeverReplaysAnUncertainEditOrSubmitsTheWrongText` — after 2200ms with no ack, no second command is emitted (no replay of uncertain edit), keypad stays open, `pageEdits` deque empty.
3. `replacementFieldInvalidatesOldQueueAndOldAcknowledgement` — a new `editableFocused` on field "two" invalidates the pending queue and the stale ack for field "one"; the next `typeText` targets field "two".
4. `rejectedEditDoesNotTurnPreviewBufferIntoAClaimOfTypedText` — a "blocked" ack clears `buffer` (empty) and keeps the keypad open; rejected text is not treated as typed.
5. `pauseCancelsPendingTypingAndSubmission` — Activity pause cancels the pending command; after resume + ack, no queued command was replayed (still 1 command total), keypad still shown.
6. `searchHandlerBlurClosesOnlyAnAlreadyDispatchedSubmit` — `Bridge().editableBlurred` after an acked submit closes the keypad; a later "submitted" ack doesn't reopen it.
7. `blurDuringTypingDoesNotSubmitOrApplyRemainingQueue` — blur before ack: no submit, queue not drained (1 command), keypad stays open.
8. `qualitySettingsOpensChoicesAndPersistsPolicyAcrossNewActivity` — `showSettings(5)` opens the quality menu; clicking "Preferred video quality:" then "Prefer 720p" persists `height=720` + a positive `revision` via `Bridge().qualityPreference()`; a brand-new Activity reads the same height and revision (persistence).
9. `nativeConnectionEditsRealEditableAndPreservesSelection` — `NativePageEditor.apply(web,"insert","search")` on a real EditText-backed InputConnection inserts at the selection ("hello world", sel 6–11 → "hello search"); "delete" removes the selection; "clear" empties it.
10. `nativeDeleteRemovesSelectionOrWholeUnicodeCharacter` — delete removes a selection, or a whole Unicode character (emoji 😀 deleted as one unit).
11. `nativeSubmitUsesDeclaredSearchActionNotBlindViewKeyInjection` — "submit" triggers the declared `IME_ACTION_SEARCH` editor action (recorded by the listener), not blind view key injection.
12. `nativeDispatchIsAsynchronousAndCancellationPreventsQueuedTyping` — `NativePageEditor.dispatch` is async (no immediate edit); cancelling before the looper idles prevents the queued insert and the callback; an accepted dispatch edits and reports true.
13. `nativeDispatchUsesInputConnectionsRequestedHandler` (sdk 25/28 only) — with a proxy InputConnection whose `getHandler` returns a HandlerThread handler, `commitText` executes on that requested handler thread (owner thread == HandlerThread), callback true.

**Production code protected:** `MainActivity` (`typeText`, `typeBackspace`, `typeGo`, `showSettings`, fields `keypad`, `buffer`, `pageEdits`, `inputFieldId`, `inputFieldState`, `menu`), `Bridge` (`editableFocused`, `editableBlurred`, `currentDocument`, `qualityPreference`), `Keypad.shown`, `NativePageEditor` (apply, dispatch — the native-edit path for real InputConnections), `MenuPanel`, `StartPage`.

# DeepAuditTest.kt (132 lines, 11 tests)

**Run:** `./gradlew :app:testDebugUnitTest --tests "com.buccaneer.browser.DeepAuditTest"`.
**Setup:** Robolectric sdk 28, `w1280dp-h720dp-mdpi`, reuses `Regression171Test.ScriptWebView` shadow.

**Tests & assertions:**
1. `autofocusAfterRemoteMovementDoesNotHijackThePlayKey` — after a D-pad RIGHT movement, a page `focusFromPage` report (empty field) must NOT open the Keypad (site autofocus after remote movement can't hijack input).
2. `unconfirmedNativeEditorDoesNotOpenKeyboardEvenAfterATap` — `focusFromPage(null)` (no confirmed native field state) does not open the Keypad even with a recent tap.
3. `delayedTextFieldAfterAnActualTapStillOpensQwerty` — with a tap 700ms ago, a confirmed `focusFromPage` (ok=1) DOES open the Keypad (legit text field after real tap).
4. `navigationClosesPageKeyboardInsteadOfLeavingItToConsumeMediaKeys` — `openPageKeypad()` then `onPageStarted` (new URL): keypad closed; new documents don't inherit text-entry mode.
5. `nestedScrollNormalizesNativePixelsToThePageViewport` — `scrollPage(0,180)` emits a `__tvUi.scrollAt` script that normalizes native pixels via `innerWidth/1280` and `innerHeight/720` (CSS hit-testing, not raw pixels).
6. `redirectsCannotRestartTheAutomaticCrashBudget` — `CrashRecoveryPolicy.nextCount` across 6 alternating-URL redirects stays at 3 (redirects don't reset the budget); count caps at 3 even from `Int.MAX_VALUE`.
7. `navigationTerminatesTheOutstandingNativeTapWithCancel` — an in-flight `sendTap` is terminated by navigation: the fullscreen view receives ACTION_DOWN then ACTION_CANCEL (never a stray UP).
8. `overlappingRemoteTapsCannotInterleaveTwoDownsAndTwoUps` — two overlapping `sendTap` calls produce DOWN, CANCEL, DOWN, UP (first tap cancelled before second begins).
9. `holdingFastForwardDoesNotFloodQualitySwitchRequests` — 16 repeated `KEYCODE_MEDIA_FAST_FORWARD` DOWN events (repeatCount 0–15) emit ZERO `__fb.listLevels()` scripts (no quality-switch flooding on hold).
10. `aWebPromptIsCancelledOnNavigationRatherThanAbandoned` — `onJsPrompt` opens the keypad and stores `pendingJsPrompt`; navigation cancels the prompt (`wasCancelled()` true), closes the keypad, nulls `pendingJsPrompt`; `cancelPendingJsPrompt()` returns false when nothing pending. (Uses a reflective proxy `JsResult$ResultReceiver`.)
11. `aWebPromptCanConfirmAnEmptyOrWhitespaceValue` — confirming a JS prompt with "" or "  " via `typeGo` accepts the value (mStringResult == the exact value) and clears `pendingJsPrompt`.

**Production code protected:** `MainActivity` (`focusFromPage`, `openPageKeypad`, `scrollPage`, `sendTap`, `cancelPendingJsPrompt`, `typeGo`, fields `keypad`, `webView`, `customView`, `pendingJsPrompt`, `lastTapAt`, `lastUserInputAt`), `Keypad.shown`, `CrashRecoveryPolicy.nextCount`, JS prompt handling in the WebChromeClient path, `StartPage.hide`.

# Structural1910Test.kt (127 lines, 10 tests)

**Run:** `./gradlew :app:testDebugUnitTest --tests "com.buccaneer.browser.Structural1910Test"`.
**Setup:** Robolectric sdk `[25,28]` (matrix), `w960dp-h540dp-xhdpi`, shadow `ControlledWeb` (`@Implements(WebView)`): records scripts; can hold the media-cleanup script (`var media=document.querySelectorAll` callback) pending via `holdCleanup`.

**Tests & assertions:**
1. `showingMenuCancelsPendingPointerSelectInsteadOfClickingBehindIt` — DPAD_CENTER DOWN sets `selectPending`; `showMainMenu()` then the CENTER UP must NOT dispatch a page tap behind the menu (`pendingTap` null).
2. `menuReloadInvalidatesOldEditorAndPlayerWorkBeforeWaitingForCleanup` — clicking "Reload" in the menu bumps `pageEpoch`, increments `playerIntent` and `editorSession`, and sets `NavigationHandoff.isPending` — even while the old page's media cleanup callback is still held (invalidation happens before cleanup completes).
3. `pausingWithNativeCleanupPendingMustNotNavigateInBackground` — `openUrl("…/two")` with cleanup held, then Activity pause: when cleanup finally reports "stopped", the WebView has NOT navigated (lastLoadedUrl still "…/one") — no background navigation.
4. `latePageStartCannotCancelDeferredNavigationAndResumeCommitsIt` — the pending navigation is stored in `deferredNavigation`; a stale `onPageStarted("…/late")` cannot discard it; on resume the WebView loads "…/two" and `deferredNavigation` is cleared.
5. `newerNavigationSupersedesDeferredOldCommand` — a second `openUrl("…/three")` while the first is deferred supersedes it; resume loads "…/three".
6. `bookmarkMoveModeReordersRepeatedlyWithoutOptionsAndPersists` — bookmark move mode (via `showBookmarkOptions` + "Move up / down…"): repeated DPAD_UP/DOWN reorders the store in place (position 4→1→3), menu closes / list panel stays, finishing with DPAD_CENTER doesn't swallow the next release (`swallowedUp` set check), `movingBookmarkUrl` cleared, and the reordering persists to a fresh `BookmarkStore` instance.
7. `bookmarkMoveCrossesManagerPagesAndStopsAtFolderBoundaries` — moving down across 55-bookmark manager pages works; stops at the last item of folder "A" (does not cross into folder "B"); BACK cancels move mode.
8. `navigationDiscardsBookmarkMoveMode` — `openUrl` during move mode nulls `movingBookmarkUrl` and closes the list panel.
9. `diagnosticDrainReturnsToIdleAndCanRestartAfterClear` — `DiagnosticLog`: after 300 records + stop, the `flushScheduled` flag returns to false (no recurring idle drain); after `clear()` a new session retains only new events (`retained_event` present, old session's `"reason":"first"` absent) in the exported `events.jsonl`.
10. `historyInvalidatesCommandsButPreservesSameDocumentBridgeAndMediaElements` — history back (`navigateHistory(-1)`) within the same document (one → one#search): bumps `pageEpoch` and `playerIntent` but keeps the same `documentToken` (bridge not rotated); the emitted media cleanup script pauses media (`media[i].pause()`) but does NOT remove elements or clear src; a real cross-document `onPageStarted` DOES rotate `documentToken`.
11. `consumedBackAfterGoingHomeDoesNotExitFromAnEarlierBackPress` — `showStartPage(false)` after a back press resets `lastBackAt` to 0 (browsing and home exit gestures don't share a stale double-Back window).

**Production code protected:** `MainActivity` (`showMainMenu`, `openUrl`, `showBookmarkOptions`, `showBookmarks`, `navigateHistory`, `showStartPage`, fields `selectPending`, `pendingTap`, `pageEpoch`, `playerIntent`, `editorSession`, `navigationHandoff`, `deferredNavigation`, `store`, `movingBookmarkUrl`, `swallowedUp`, `menu`, `listPanel`, `webView`, `documentToken`, `lastBackAt`, `osd`), `NavigationHandoff.isPending`, `BookmarkStore` (add, find, all, inFolder, remove, persistence), `MenuPanel`/`ListPanel`, `DiagnosticLog` (flushScheduled, clear, sessions), `StartPage`.

# Release18Test.kt (121 lines, 10 tests)

**Run:** `./gradlew :app:testDebugUnitTest --tests "com.buccaneer.browser.Release18Test"`.
**Setup:** Robolectric sdk 28, `w1280dp-h720dp-mdpi`, reuses `Regression171Test.ScriptWebView` shadow; `@Before` resets its script list and `mediaResult="ok"`.

**Tests & assertions:**
1. `holdPolicyUsesTimeAndDoesNotFloodAtFastRemoteRepeatRates` — `SeekRepeatPolicy.step`: first repeat step is 10; rapid remote repeats (10ms apart) yield 0 (no seek) until 900ms elapsed, then 30, then 60; a new hold restarts at 10; `reset()` zeroes state. Time-based, not repeat-count-based.
2. `bothTransportDirectionsUse10Then30Then60WithoutQualityChanges` — holding MEDIA_FAST_FORWARD or MEDIA_REWIND (repeats at 1000/1900/2800/3700ms) emits `__fb.seek(±10)` once, `__fb.seek(±30)` twice, `__fb.seek(±60)` at least once; never `listLevels`/`setQuality` (seeking doesn't touch quality).
3. `fullscreenArrowsHaveTheSameLongSeekBehavior` — in fullscreen, DPAD_RIGHT/DPAD_LEFT via `onFullscreenKey` produce the same long-hold seek ramp (reaches `__fb.seek(±60)`).
4. `opaquePlayersGetSixSeparateSeekKeyPairsForSixtySeconds` — for an opaque (frame) player with no JS seek helper (`mediaResult="frame"`), `videoSeek(60)` falls back to 6 separate DPAD_RIGHT DOWN/UP key pairs (one per 10s).
5. `pauseCancelsTheRemainingNativeSeekBurst` — pausing mid-burst: only 1 DPAD_RIGHT pair was sent before pause; the pause key is forwarded; no further seek keys after 500ms.
6. `navigationCancelsNativeSeekBurst` — `onPageStarted` mid-burst cancels all remaining native seek keys (zero keys received).
7. `openingKeyboardStopsNativeSeekKeysFromMovingTheTextCaret` — opening the URL prompt mid-burst stops the seek keys (they'd otherwise move the text caret).
8. `nativeSeekBarHasBoundedHitTestingAndHiddenBarIsNotClickable` — `Osd.seekFractionAt`: center of the seek bar → 0.5f; 10px left of the bar, 50px above, or NaN x → null (bounded hit testing); after `hideNow()` the bar is not clickable (null).
9. `selectingNativePositionBarDoesNotClickTheUnderlyingWebsite` — with the cursor over the native seek bar, `tapAtCursor` seeks via JS (`var t=s.d*0.5` script) and sends NO touch events to the WebView (doesn't click the underlying website).
10. `unknownJavascriptPlaybackStateDoesNotDimTheScreen` — after 21 minutes idle with an unknown playback state, the `idleCheck` runnable does NOT dim (`dimmed` false) and the probe script checks `s.fr===1?-1:0` (unknown = don't dim).

**Production code protected:** `SeekRepeatPolicy` (step, reset), `MainActivity` (`onFullscreenKey`, `videoSeek`, `videoCommand`, `promptUrl`, `tapAtCursor`, fields `customView`, `osd`, `idleCheck`, `dimmed`, `cx`, `cy`, `webView`), `Osd` (show, hideNow, seekFractionAt, seekBar, rootView), `StartPage.hide`.

# AuditTest.kt (117 lines, 7 tests)

**Run:** `./gradlew :app:testDebugUnitTest --tests "com.buccaneer.browser.AuditTest"`.
**Setup:** Robolectric sdk 28, `w1280dp-h720dp-mdpi`. Mostly exercises `StartPage` (home screen) and `BookmarkStore` directly, plus Keypad/BarView/DiagnosticLog.

**Tests & assertions:**
1. `bookmarksPageEightAtATimeAndReturnToFirst` — StartPage paginates bookmarks 8 per page: 21 bookmarks → page 1 shows B1–B8 (not B9), prev arrow disabled; next → B9–B16; next → B17–B21, next arrow disabled; pager stays at the same vertical position after re-layout (top unchanged); last child's bottom ≤ 720 (fits screen); back arrows return to B1; the "Where's me buccaneers?" tagline present; the pager row is ordered BEFORE the "Search / address" action row in the layout.
2. `arrowsHiddenForZeroOrEightAndPageClampedAfterDeletion` — pager arrows hidden when 0 bookmarks or exactly 8 (single page); with 17 bookmarks, after deleting B17 on page 3 the page clamps (next arrow disabled, B9 visible, B17 gone).
3. `folderAndFolderContentsBothPageAndBackRestoresFolderPage` — folder list and folder contents both paginate (F9 with 9 extras pages); entering a folder and paging its contents, `page.back()` returns to the folder list at the same page ("2 / 2" indicator).
4. `bookmarkPathsKeepCaseAndMalformedImportDoesNotReplaceExistingList` — `BookmarkStore.sameUrl`: scheme/port normalization applies ("HTTPS://EXAMPLE.COM:443" == "https://example.com/") but path/query case is preserved (Show ≠ show, ?q=A ≠ ?q=a); malformed imports (non-string url, non-string folder) are rejected keeping existing data; duplicate-URL import (case-insensitive host, same path) collapses to 1 entry.
5. `keypadGetsKeysEvenWhenFullscreenViewExists` — with a fullscreen `customView` present, `Keypad.open("Test","OK")` still receives DPAD keys: DPAD_RIGHT moves `selCol` to 1 (keypad focus not stolen by fullscreen view).
6. `invalidProgressDoesNotReachDrawingCode` — `BarView.fraction` sanitizes input: NaN → 0f, +Infinity → 1f (drawing code never sees invalid floats).
7. `nestedDiagnosticArraysRedactSecretsAndOversizePayloads` — `DiagnosticLog.record` with nested arrays/objects containing `token=private-value`, `password`, `?key=private-value` in URLs, plus `recordCritical` with a 40KB payload: the exported ZIP contains NO occurrence of "private-value" anywhere (recursive redaction of nested structures and oversize payloads).

**Production code protected:** `StartPage` (pagination, arrows, folder drill-down, back, layout stability, tagline, action-row ordering), `BookmarkStore` (add, remove, all, importJson, sameUrl, refresh semantics), `Keypad` (open, selCol, key dispatch priority over fullscreen view), `BarView` (fraction sanitization), `DiagnosticLog` (record, recordCritical, stop, exportAsync, clear — nested redaction), `Ui`.

# IntegrationPassTest.kt (110 lines, 11 tests)

**Run:** `./gradlew :app:testDebugUnitTest --tests "com.buccaneer.browser.IntegrationPassTest"`.
**Setup:** Robolectric sdk 28, `w1280dp-h720dp-mdpi`, shadow `DelayedWebView` (`@Implements(WebView)`): records scripts; can HOLD the callback of one script kind (`hold` = "probe" | "fullscreen" | "field" | "diag", matched by suffix `,'probe'`, `__fb.prepareFullscreen()`, `__fb.field()`, `__fb.diag()`); `finish(raw)` releases all held callbacks with a canned value. Theme: stale/delayed async results must never clobber newer UI state.

**Tests & assertions:**
1. `delayedSmartKeyboardCannotReplaceANewerAddressPrompt` — a held `__fb.field()` result from `openKeypadSmart` arriving after `promptUrl` + typing must not switch `textMode` away from "PROMPT" or clobber `buffer` ("https://keep.test/").
2. `delayedDiagnosticsCannotCoverANewerKeyboard` — a held `__fb.diag()` result from `showDiagnostics` arriving after `promptUrl` must not show `textPanel`; the keypad stays shown.
3. `legacyFocusNotificationDoesNotEraseQualifiedPasswordState` — `Bridge().editableFocused` with pw=1/length=6 sets `textMode` "PAGE"; a legacy `editableClickedForDocument` notification keeps `inputFieldState` exactly as the qualified state (password field not downgraded/erased).
4. `delayedFlixerProbeCannotTapANewFullscreenTarget` — a held Flixer `,'probe'` from `tapAtCursor`; after `enterFullscreen(newer)`, the probe's "frame" result must NOT tap the newer fullscreen target (zero touch events).
5. `bookmarkCompletionCannotDiscardANewerAddressBeingTyped` — a long-running `runFileOperation` (BookmarkJobs) completing after `promptUrl`+typing: keypad still shown, buffer intact, list panel not shown (completion UI doesn't steal the screen).
6. `normalizedBackupSizeCannotExceedItsOwnImporterLimit` — 18,000-item import JSON (raw < 1MB) that would exceed the importer's normalized-size limit is rejected BEFORE commit (`importJson` false, "Old" bookmark retained).
7. `failedFullscreenPreparationCannotTapThePage` — `fullscreenVideo` with a failed `__fb.prepareFullscreen()` (null): no touch events to the WebView, `pendingFullscreen` false.
8. `newUserInputCancelsAnAcknowledgedButNotYetTappedFullscreenRequest` — after a successful prepare, `pendingFullscreen` true; `noteUserInput` before the tap cancels it: no touches, `pendingFullscreen` false, and `__fb.cancelFullscreen()` is emitted.
9. `oversizedOrdinaryFieldsCannotProduceAPretendPortableBackup` — renaming a bookmark to a 2049-char title makes `exportPortableJson()` throw `IOException` with "oversized" in the message (fails before opening a destination); the rename itself persists.
10. `delayedSmartKeyboardCannotOpenWhileActivityIsPaused` — a held field result arriving while the Activity is paused does not open the keypad.
11. `pickerResultSubmittedBeforeResumeRetainsItsCompletionUiOwner` — a file-picker job submitted while paused completes after resume: the completion UI (list panel) is shown by the right owner.

**Production code protected:** `MainActivity` (`openKeypadSmart`, `promptUrl`, `typeText`, `showDiagnostics`, `tapAtCursor`, `enterFullscreen`, `fullscreenVideo`, `noteUserInput`, `runFileOperation`, fields `textMode`, `buffer`, `textPanel`, `keypad`, `listPanel`, `menu`, `inputFieldState`, `onFlixerPage`, `pendingFullscreen`, `webView`, `store`), `Bridge` (`editableFocused`, `editableClickedForDocument`, `currentDocument`), `BookmarkStore` (importJson size limits, exportPortableJson, rename), `BookmarkJobs`, `Keypad`/`TextPanel`/`ListPanel`/`MenuPanel`.

# KeyboardRecovery193Test.kt (59 lines, 12 tests)

**Run:** `./gradlew :app:testDebugUnitTest --tests "com.buccaneer.browser.KeyboardRecovery193Test"`.
**Setup:** Robolectric sdk `[25,28]` (matrix), `w1280dp-h720dp-mdpi`, shadow `EditorWebView` (`@Implements(WebView)`): `__tvUi.fieldState` scripts return a canned `fresh` JSON (simulates a fresh field read); other scripts → "null". Four tests are `@Config(sdk=[28])`-only because `onRenderProcessGone` was introduced at API 26.

**Tests & assertions:**
1. `windowExcludesSystemImeWithoutDisablingTheEditor` — the window flags include `FLAG_ALT_FOCUSABLE_IM` (system IME excluded at window level, editor not disabled).
2. `chromiumEditorConnectionIsNotReplacedWithNull` — `TvWebView` does not override `onCreateInputConnection`/`onCheckIsTextEditor` (no null InputConnection trick).
3. `keyboardReservesUncoveredViewportAndRestoresMatchParent` — with the keypad open, the WebView's `layoutParams.height` is an explicit value in `80..(keypadTop-8)` (reserves only the uncovered viewport, not full shrink); `closeKeypad` restores `MATCH_PARENT` (-1).
4. `freshQualifiedReadWinsOverCachedPreview` — after a cached `editableFocused` ("old"), `syncField` performs a fresh `__tvUi.fieldState` read; the keypad preview shows "new|" (fresh value wins over cached preview).
5. `explicitSameSiteRetryResetsOnlyItsAutomaticRecoveryBudget` — user-initiated `openUrl` to the previously-crashed URL resets `crashReloadCount` to 0 in both prefs and the field (explicit retry is a fresh start).
6. `automaticStartupRetryDoesNotResetBudget` — automatic startup retry (no explicit openUrl) keeps `crashReloadCount`=2 (budget preserved).
7. `pausedRendererDeathStillDisposesDeadBrowser` (sdk 28) — `onRenderProcessGone` while the Activity is paused: `webViewDestroyed` true, view detached from parent.
8. `promptDoesNotLeavePageViewportShrunk` — opening the keypad then the URL prompt: after closing, WebView height restored to MATCH_PARENT (-1) (no leftover shrink).
9. `allPanelCloseRestoresViewport` — `closeAllPanels` after keypad: WebView height restored to -1.
10. `crashBeforePageStartedStillRemembersRequestedUrl` (sdk 28) — renderer death before onPageStarted: `lastCrashUrl` in prefs = the requested URL, `crashReloadCount`=1.
11. `foreignRendererCallbackCannotDisposeCurrentBrowser` (sdk 28) — `onRenderProcessGone` for a DIFFERENT WebView instance is ignored: `webViewDestroyed` false, current view still attached.
12. `pausedRecoveryWaitsForResume` (sdk 28) — renderer death while paused: `rendererRecreatePosted` stays false; `requestRendererRecovery()` while paused still doesn't post; only after resume does `rendererRecreatePosted` become true.
13. `automaticNavigationDoesNotResetRedirectCrashBudget` — `navigateUrl(..., false)` (automatic/redirect navigation) keeps `crashReloadCount`=2 (no budget reset on redirects).
14. `wrongDocumentFreshReadCannotOverwriteQualifiedSnapshot` — a fresh `__tvUi.fieldState` read reporting a different (old) document cannot overwrite the qualified snapshot: preview keeps "correct|".

(Note: 14 @Test methods in the file; the "59" in the task brief is the line count.)

**Production code protected:** `MainActivity` window setup (FLAG_ALT_FOCUSABLE_IM), `TvWebView` (no IME overrides), keypad/viewport layout interplay (`openPageKeypad`, `closeKeypad`, `promptUrl`, `closeAllPanels`, `syncField`, `openUrl`, `navigateUrl`, `requestRendererRecovery`, fields `webView`, `keypad`, `crashReloadCount`, `webViewDestroyed`, `rendererRecreatePosted`, `inputFieldState`, `root`), `Keypad` (shown, previewView), crash-recovery prefs ("settings": crashReloadCount, lastCrashUrl, crashWindowStartedAt), `Bridge` (`editableFocused`, `currentDocument`).

# Api34IntegrationSmokeTest.kt (58 lines, 1 test)

**Run:** `./gradlew :app:testDebugUnitTest --tests "com.buccaneer.browser.Api34IntegrationSmokeTest"`.
**Setup:** Robolectric sdk **34** (the only test in the suite targeting API 34), `w1280dp-h720dp-mdpi`, reuses `Regression171Test.ScriptWebView` shadow. Defines a local `ReadProvider` ContentProvider that serves a fixture file via `openFile` (ParcelFileDescriptor, read-only) — registered through `ShadowContentResolver.registerProviderInternal` with authority "backup-fixture".

**Test & assertions — `api34StartupTransportAndPickerReturnImportWorkTogether`:**
An end-to-end smoke test on API 34:
1. Startup: `startPage` shown, `webView` non-null.
2. Transport: hide start page, dispatch MEDIA_PLAY_PAUSE through the real Activity → `__fb.togglePlay()` script emitted (hardware key → page JS works on API 34).
3. Picker return: write a fixture JSON (`{"items":[{"u":"https://restored.test/","t":"Restored"}]}`) to cacheDir, register the ReadProvider, pause the Activity, invoke `onActivityResult(1206, RESULT_OK, content://backup-fixture/bookmarks.json)` via reflection, resume.
4. After the BookmarkJobs drain: store contains exactly the restored bookmark ("https://restored.test/"); the completion UI is the list panel (shown), with menu and keypad both hidden.

**Production code protected:** `MainActivity` startup wiring on API 34 (`startPage`, `webView`), media-key transport (`dispatchKeyEvent` → `__fb.togglePlay`), `onActivityResult` file-picker path (request code 1206, content:// URI via ContentProvider `openFile`), `BookmarkJobs` completion, `BookmarkStore` import, `ListPanel`/`MenuPanel`/`Keypad` completion-UI ownership.

# GeneralStability196Test.kt (55 lines, 8 tests)

**Run:** `./gradlew :app:testDebugUnitTest --tests "com.buccaneer.browser.GeneralStability196Test"`.
**Setup:** Plain JUnit (NO Robolectric, no Android deps) — pure-logic tests of two production helpers: `UiCoalescer` and `JsonInputBudget`. Fast and framework-independent.

**Tests & assertions:**
1. `tenThousandSnapshotsRetainOneCallbackAndLatestValue` — 10,000 `offer()`s to a `UiCoalescer` (post/remove/acceptor/apply lambdas) queue exactly ONE runnable; draining it emits only the latest value (9999); a subsequent offer queues one more.
2. `countedBurstsPreserveTheFullCount` — with a combining function (`a+b`), 10,000 offers of 1 coalesce into one callback carrying the full sum 10,000 (counts not lost to coalescing).
3. `reentrantOffersScheduleTheirOwnDrain` — an offer made from inside the apply callback schedules its own drain (no lost update, no reentrancy crash).
4. `navigationCancellationDoesNotStrandTheNextDocument` — `cancel()` clears the queued callback; a later offer still drains normally (cancellation doesn't wedge the coalescer).
5. `schedulerRejectionDoesNotRetainOrBlockFutureWork` — if the post lambda rejects (returns false, e.g. executor shutdown), the offer is not retained and later offers still work.
6. `concurrentOffersStillCreateOnlyOneQueuedCallback` — 8 threads × 2,000 offers (synchronized list): still exactly 1 queued runnable, and the combined value is the full 16,000 (thread-safe coalescing).
7. `nestingBoundaryRejectsPathologicalInputsWithoutRecursion` — `JsonInputBudget.allows`: 32 levels of nested arrays OK, 33 rejected, 100,000 rejected (depth budget, no stack overflow from recursion).
8. `quotedBracketsEscapesAndLenientAndroidCommentsDoNotConsumeDepth` — brackets inside quoted strings (double- and single-quoted), escaped quotes/backslashes, and lenient Android-JSON comments (`/* */`, `//`, `#`) do NOT count toward nesting depth; but a real 33-deep array after a comment is still rejected.

**Production code protected:** `UiCoalescer` (latest-value coalescing, combining, reentrancy, cancellation, rejection, thread-safety — used to coalesce rapid UI updates/navigation work) and `JsonInputBudget` (nesting-depth budget for lenient JSON parsing of untrusted input — prevents pathological nesting DoS; comment/string-aware depth counting).

# WarningRepairTest.kt (54 lines, 4 tests)

**Run:** `./gradlew :app:testDebugUnitTest --tests "com.buccaneer.browser.WarningRepairTest"`.
**Setup:** Robolectric sdk 28 (one test overridden to sdk 22 with the `Regression171Test.ScriptWebView` shadow).

**Tests & assertions:**
1. `sharedStoreIsApplicationOwnedAndStableAcrossActivities` — `BookmarkStore.shared(activity)` returns the SAME instance as `BuccaneerApplication.bookmarks` (application-owned singleton, stable across multiple Activity instances); `BookmarkStore` and its Companion declare NO fields of type Context (no context leak in the shared store).
2. `allCustomViewsExposeUsableContextConstructors` — every custom view class (`CursorView`, `Keypad`, `Osd`, `MenuPanel`, `ListPanel`, `TextPanel`, `StartPage`, `BarView`) has a public `(Context)` constructor that constructs successfully (lint-warning repair: no missing/ambiguous constructors).
3. `minimumSdk22StillStartsAndUsesApplicationOwnedBookmarks` (sdk 22) — on the minimum supported SDK, `MainActivity` starts, `startPage` is shown, and `BookmarkStore.shared(activity)` is the application-owned store (minSdk compatibility).
4. `previewFormattingPreservesLiteralInputAndCaret` — `R.string.keyboard_preview` formatting preserves literal input and appends the caret "|": works for "", "100%", "%1$s" (format specifier not re-interpreted), "a\nb", emoji 🚀, and "|" itself (no formatting corruption / no double-format injection).

**Production code protected:** `BuccaneerApplication` (bookmarks field), `BookmarkStore.shared` (singleton ownership, no Context fields), all custom view constructors, `MainActivity` startup at minSdk 22, `R.string.keyboard_preview` resource formatting.

# PortableBackup19Test.kt (47 lines, 2 tests)

**Run:** `./gradlew :app:testDebugUnitTest --tests "com.buccaneer.browser.PortableBackup19Test"`.
**Setup:** Robolectric sdk 34. Defines a local `DownloadsProvider` ContentProvider (authority "media") simulating MediaStore Downloads: records `insert` (uri + ContentValues), `update` (publish = IS_PENDING set to 0), `delete`; `openFile` creates/truncates/opens the target file read-write. Registered via `ShadowContentResolver.registerProviderInternal`.

**Tests & assertions:**
1. `modernExportUsesPublicDownloadsAndPublishesOnlyAfterWrite` — `BookmarkFiles.export(app, data)`:
   - inserts into `MediaStore.Downloads.EXTERNAL_CONTENT_URI` (public Downloads, not app-private storage);
   - `RELATIVE_PATH` = "Download";
   - `IS_PENDING` = 1 at insert (file hidden until published);
   - the JSON data is actually written to the file (openFile path);
   - publish happens (IS_PENDING→0 via update) and only after the write; no delete;
   - the returned result string contains the `DISPLAY_NAME`;
   - the backup URI is persisted in "settings" prefs as `bookmarkBackupUri` starting with "content://media/".
2. `unavailablePublicProviderDoesNotFallBackToAnUninstallDeletedAppFolder` — when the Downloads provider rejects the insert (returns null), `BookmarkFiles.export` throws `IOException` whose message contains "Public Downloads" — it does NOT silently fall back to app-private storage (which would be deleted on uninstall). No publish occurs.

**Production code protected:** `BookmarkFiles.export` (portable backup export: MediaStore Downloads flow, IS_PENDING publish-after-write, DISPLAY_NAME, failure semantics, no private-storage fallback), `BookmarkStore` (export data shape), "settings" prefs `bookmarkBackupUri`.

# BridgeStability196Test.kt (41 lines, 8 tests)

**Run:** `./gradlew :app:testDebugUnitTest --tests "com.buccaneer.browser.BridgeStability196Test"`.
**Setup:** Robolectric sdk 28, `w1280dp-h720dp-mdpi`, PAUSED looper. Exercises the JS↔native `Bridge` under load and hostile input.

**Tests & assertions:**
1. `popupStormPreservesCountAndClampsOverflow` — 10,000 `Bridge().blocked("test")` calls: `blockedCount` is 0 before looper drain (coalesced), exactly 10000 after drain; with the field pre-set to `Int.MAX_VALUE-1`, 10 more calls clamp at `Int.MAX_VALUE` (no overflow).
2. `pendingPopupBurstCannotLeakAcrossNavigation` — a blocked popup from the old document, then `resetDocumentWork(true)`, then one from the new document: after drain `blockedCount` is 1 (old pending burst discarded, not leaked into the new document).
3. `pendingHeartbeatCannotRefreshTheNewDocument` — a pending `playbackActive` heartbeat from the old doc is discarded by `resetDocumentWork` (`playbackHeartbeatAt` 0 after drain); a fresh heartbeat from the current doc sets it to `elapsedRealtime()`.
4. `backgroundHeartbeatIsNotConsumedAsActivePlayback` — a heartbeat arriving while the Activity is paused is NOT consumed as active playback (`playbackHeartbeatAt` stays 0 after drain).
5. `rendererRecoveryDoesNotLeaveCoalescersStranded` — after `onRenderProcessGone` (webViewDestroyed true), the destroyed activity's coalescers are gone; a replacement activity starts clean: `recoveringRenderer` false, `blockedCount` 1 for its own single event (no stranded coalescer state).
6. `deeplyNestedBridgePayloadsCannotReachRecursiveParsers` — a 4000-deep nested array payload: `tmdbAllowlist(deep)` leaves the existing allowlist unchanged (same instance); `editableFocused` with the deep payload in `extra` leaves `inputFrame` empty (hostile depth rejected before recursive parsing).
7. `hostileBackupDepthCannotReplaceOrDestroyExistingBookmarks` — `importJson` with a 4000-deep `"unused"` array is rejected (false); the store's `exportJson()` is byte-identical to before (existing bookmarks neither replaced nor destroyed).
8. `pathologicalDiagnosticsAreOmittedRatherThanParsed` — `Bridge().diagnosticEvent` with a 4000-deep payload: the event is omitted (DiagnosticLog.hasData true from the session, but the hostile payload is not parsed/retained).

**Production code protected:** `Bridge` (blocked, playbackActive, currentDocument, tmdbAllowlist, editableFocused, diagnosticEvent — coalescing, document-scoping, hostile-input rejection), `MainActivity` (`resetDocumentWork`, fields `blockedCount`, `playbackHeartbeatAt`, `webViewDestroyed`, `recoveringRenderer`, `tmdbAllowlist`, `inputFrame`, `webView`), `BookmarkStore` (importJson depth safety, exportJson), `DiagnosticLog` (startNewSession, hasData, stop).

## 2. Protected-fixes mechanism (tests/protected-fixes.json + check scripts)

# tests/protected-fixes.json (26 lines)

**What it is:** Data file consumed ONLY by `tests/check-protected-fixes.py` (a loop over this list). JSON array of 5 check objects.

**Exact pinned entries:**

| # | path | pin type | markers / notes | sha256 |
|---|------|----------|-----------------|--------|
| 1 | `app/src/main/assets/flixer_image_guard.js` | **whole-file SHA-256** | — | `5afdf0b72a3c21cdbad3a99640dd0bb683642163408f199ef1ff5622181d4061` |
| 2 | `app/src/main/assets/popup_guard.js` | **whole-file SHA-256** | — | `da16a8cb3043db6e7d05acf44ff5695d9082336bf68d8dea1a8349db6a093343` |
| 3 | `app/src/main/assets/page_helpers.js` | **whole-file SHA-256** | — | `a96b90508f4c41769cb24e86ef5b7f5c695eb6a700f57d59b3f0a1784b95fdc1` |
| 4 | `app/src/main/java/com/buccaneer/browser/MainActivity.kt` | **marker-delimited region** | after: `    private fun applyDarkMode()` (4-space indent, exact) / before: `    private fun toggleDark()` (4-space indent, exact) | `1f1b2c3d186b636281cedd55b7f69d606ebfa21e543944445e6e836a0231af90` |
| 5 | `app/src/main/java/com/buccaneer/browser/MainActivity.kt` | **marker-delimited region** | after: `            // Detect guarded poster-grid page:` (12-space indent, exact) / before: `        @TargetApi(Build.VERSION_CODES.M)` (8-space indent, exact) | `cc366f65fe5046dfa66377eca18c3fe1cdc9ce7942fde9d90f50d0d7d68bf8af` |

**Region semantics:** for entries 4–5 the hash is over `text[text.index(after):text.index(before)]` — the marker lines themselves are INCLUDED in the hashed region (slice starts at the first char of `after`, ends just before the first char of `before`). If a marker string is missing, `str.index` raises `ValueError` (uncaught → script crashes, effectively a failure).

**MainActivity.kt normalization (applies to entries 4–5):** before hashing, the checker asserts the file contains exactly 2 occurrences of `injectDarkMode(view)` and replaces them with `injectDarkMode(view, url)`. So the stored hashes were computed on the normalized text; the two call sites may legally be written `injectDarkMode(view)` in source.

**Consequence of editing a pinned file without updating the hash:**
- JS assets (1–3): any byte change → `AssertionError: app/src/main/assets/<name>` → exit 1. Gate fails.
- MainActivity.kt: edits **outside** both marker windows are invisible to the gate. Edits **inside** a window → that region's hash mismatches → AssertionError. To change a pinned region legally: edit the code, recompute the SHA-256 of the normalized region text, update the `sha256` field here. Editing a marker line itself changes the region boundary → both hash and (possibly) the count assert break.
- The `injectDarkMode(view)` count assert means you cannot add/remove dark-cleanup call sites without breaking the gate even with updated hashes.

**Current status:** all 5 checks PASS (verified by running check-protected-fixes.py, exit 0, 2026-10-21).

# tests/check-protected-fixes.py (26 lines)

**What it is:** The live protected-fixes gate. A single loop over `tests/protected-fixes.json` (list of check objects). For each check:
1. Reads the whole file at `check['path']` (repo-root relative).
2. If the path ends with `MainActivity.kt`: asserts the byte string `injectDarkMode(view)` occurs exactly **2** times ("Unexpected dark cleanup call drift" otherwise), then **replaces** every `injectDarkMode(view)` with `injectDarkMode(view, url)` before hashing (normalizes a 1.9.2 signature change: unused private arg removed, two call sites keep identical behavior).
3. If the check has `after`/`before` keys: decodes to text, slices `text[text.index(after):]` and/or `[:text.index(before)]` — i.e. a **marker-delimited region** (substring between the two marker strings, markers themselves included at the boundaries).
4. Asserts `sha256(region-or-whole-file) == check['sha256']`; on mismatch the AssertionError message is just the path.
5. Prints `PASS: Flixer/CineJoy image guard, Popup Guard, native guards, and dark mode preserved.`

**Run:** `cd <repo-root> && python3 tests/check-protected-fixes.py`
**Current status: PASS, exit 0** (verified 2026-10-21).

**What it protects (per protected-fixes.json):**
- `app/src/main/assets/flixer_image_guard.js` — whole-file SHA-256 `5afdf0b7…d4061` (Flixer/CineJoy image guard)
- `app/src/main/assets/popup_guard.js` — whole-file SHA-256 `da16a8cb…093343` (popup guard)
- `app/src/main/assets/page_helpers.js` — whole-file SHA-256 `a96b9050…31784b95fdc1` (page helpers)
- `MainActivity.kt` region 1: after `"    private fun applyDarkMode()"` → before `"    private fun toggleDark()"` (dark-mode injection logic), SHA-256 `1f1b2c3d…31af90` (hashed after the injectDarkMode normalization above)
- `MainActivity.kt` region 2: after `"            // Detect guarded poster-grid page:"` → before `"        @TargetApi(Build.VERSION_CODES.M)"` (poster-grid detection), SHA-256 `cc366f65…68bf8af` (same normalization applied)

**Consequence of editing a pinned file without updating the hash:** the script raises `AssertionError: <path>` and exits non-zero — CI/manual gate fails. For whole-file pins (the 3 JS assets) ANY byte change breaks it. For MainActivity.kt only the two marked regions are pinned: edits outside the marker windows are free; edits inside require updating the region SHA-256 in `protected-fixes.json` (recompute over the normalized text). Adding/removing an `injectDarkMode(view)` call site breaks the `count == 2` assert even if hashes were updated.

**Tail comment (lines 18–26):** documents that the former `repair199-baseline.json` check (pinned page_helpers.js, popup_guard.js, flixer_image_guard.js, fmhy_theme.js, tv_ui.js, cinejoy_quality.js to the exact 1.9.9 APK state) was **removed** because that baseline is permanently stale (tree evolved to v2.x; cinejoy_quality.js removed, others reworked). `repair199-baseline.json` is kept on disk only as a historical record. The protected-fixes.json loop is the ongoing check.

# tests/check-warning-repairs.py (28 lines)

**What it is:** A broader "hardware/SDK compatibility + protected scripts" gate. Steps in order:
1. Loads `tests/hardware-compatibility-baseline-1.9.1.json`.
2. Regex-asserts `app/build.gradle.kts` pins `minSdk`, `compileSdk`, `targetSdk` to baseline values, and contains `androidx.webkit:webkit:<baseline.webkit>`.
3. `runpy.run_path('tests/check-protected-fixes.py')` — **runs the protected-fixes gate inside** (that's why its PASS line prints first).
4. Hash-pins four media/theme scripts against `baseline['scriptSha256']`: `flixer_image_guard.js`, `popup_guard.js`, `fmhy_theme.js`, `seek_click.js` (whole-file SHA-256, from the 1.9.1 hardware baseline).
5. Parses `app/src/main/AndroidManifest.xml`: application class must be `.BuccaneerApplication`; `usesCleartextTraffic=true` + `tools:targetApi="m"`; permission set exactly `{INTERNET, WRITE_EXTERNAL_STORAGE}`; the second permission (WRITE_EXTERNAL_STORAGE) must have `maxSdkVersion="28"`.
6. Every `res/mipmap-*/ic_launcher.png` must be RGBA with transparent corner pixel and opaque center pixel (icon alpha sanity).
7. `res/drawable-nodpi/banner.png` must exist; `res/drawable-xhdpi` must NOT exist.
8. `app/lint.xml` issues must be exactly `['OldTargetApi','GradleDependency']`.
9. Collects all `@SuppressLint("…")` across `app/src/main/java/**/*.kt`; sorted list must equal `['ApplySharedPref']*4 + ['SetJavaScriptEnabled']*2` — a closed inventory of suppressed lint warnings.
10. `build.gradle.kts` must not contain the string `'disable'`.
11. Prints a long PASS line about SDK22/34 config, target33, WebView1.9.0, protected scripts, permissions, icons, banner, exception inventory.

**Run:** `cd <repo-root> && python3 tests/check-warning-repairs.py` (needs Pillow for step 6).
**Current status: FAIL, exit 1.** Output:
```
PASS: Flixer/CineJoy image guard, Popup Guard, native guards, and dark mode preserved.
Traceback (most recent call last):
  File ".../tests/check-warning-repairs.py", line 13, in <module>
    assert hashlib.sha256(...).hexdigest()==baseline['scriptSha256'][name],name
AssertionError: flixer_image_guard.js
```
It dies at step 4, first script: `flixer_image_guard.js` no longer matches its **1.9.1** baseline hash (the file has legitimately evolved since 1.9.1; the current hash is pinned by protected-fixes.json instead, which passes). So this script is currently a **stale gate** — it cannot pass without either restoring 1.9.1-era script bytes or updating `hardware-compatibility-baseline-1.9.1.json` (which would defeat its purpose as a historical baseline). Steps 5–11 never execute in the current tree.

**What it protects (if it could run to completion):** SDK pinning in build.gradle.kts, the four 1.9.1-era script hashes, manifest permissions/flags, launcher icon alpha structure, banner presence, absence of drawable-xhdpi, lint suppression list, closed @SuppressLint inventory, and no `disable` in build config.

## 3. Remaining tests/ files (.cjs / .py / .html / .json)

### Node (.cjs)

# tests/quality-tests.cjs (72 lines)

**What it is:** Node VM test of the ACTUAL shipped `app/src/main/assets/page_helpers.js` quality-capping code (no DOM). Slices the real source between markers (`var QUALITY_MIN` → `// Players usually live`; `var hlsInstances` → `document.addEventListener('loadedmetadata'`) and runs it in a `vm` context with a mock `Hls` class (records options, levels, callbacks; `Hls.Events`, `Hls.isSupported`, `Hls.DefaultConfig`).

**Run:** `cd <repo-root> && node tests/quality-tests.cjs`
**Current status (verified 2026-10-21, node v26.8.1): FAILS, exit 1** — `TypeError: c.pickCapLevel is not a function` (line 21). The source slice between `var QUALITY_MIN` and `// Players usually live` no longer exposes `pickCapLevel` in the current page_helpers.js — the test is STALE against the v2.x asset.

**Checks:**
1. `pickCapLevel` over 12 height ladders: picks the highest level ≤ 720 (e.g. [144..1080]→720, [480,1080]→480, [400,800,1080]→800, [1080,2160]→1080, [360,1080]→360, unordered [800,400,720]→800, [0,1080]→1080, [0]→null, []→null).
2. `wrapHlsConstructor()`: wrapped Hls keeps `isSupported()`, `Events`, `DefaultConfig` statics; constructor args preserved; `instanceof` works both ways; MANIFEST_PARSED/LEVELS_UPDATED callbacks registered.
3. Capping: after manifest parse with levels [480,720,1080], `autoLevelCapping=1` and `nextAutoLevel=1`; `capHls` emits no diagnostic events; a LEVELS_UPDATED to [1080] resets the cap to 0.
4. Fallback without `Object.setPrototypeOf` (undefined in context): wrapper still works (statics preserved).
5. Legacy constructor fallback without `Reflect`: `new Hls()` still works.
6. `capShaka`: preserves the active language/audio group (selects the 720p 'en' track, not the 800p 'es' one), sets `abr.restrictions.maxHeight=720`; with only a 1080p track, selects nothing (no replacement) and maxHeight=1080.
7. Destroyed HLS instances are untracked from `hlsInstances`; `trackHls` after destroy is a no-op.
8. A throwing Hls constructor: `wrapHlsConstructor` preserves original throwing behavior (error propagates).
9. `setQuality(480)` (manual): picks the NEAREST supported height (level 2 = 480 in [144,360,480,720,1080]), not the lowest rung; sets `autoLevelCapping`.
10. Full real helper on a Flixer host (flixer.gd): `__fb.listLevels()` returns ok:0 and `__fb.setQuality(720)` returns 'none' without throwing (passive queries safe where active hooks are skipped).

**Protects:** page_helpers.js quality ladder selection, HLS constructor wrapping (statics/instanceof/fallbacks), Shaka capping (language preservation, higher-only fallback), manual setQuality nearest-height, Flixer passive safety.

# tests/logic19-tests.cjs (34 lines)

**What it is:** Node VM test of the ACTUAL shipped `page_helpers.js` — HLS capping edge cases + the full `__fb` passive helper on a simulated Flixer page (mock video/iframe/DOM).

**Run:** `cd <repo-root> && node tests/logic19-tests.cjs`
**Current status (verified 2026-10-21, node v26.8.1): FAILS, exit 1** — `TypeError: ctx.wrapHlsConstructor is not a function` (line 6). The source slice no longer defines `wrapHlsConstructor` in the current page_helpers.js — the test is STALE against the v2.x asset.

**Checks:**
1. Unordered ladder ([1080,720] with mixed bitrates) is NOT falsely represented as a valid 800p ceiling: `capHls` leaves `autoLevelCapping=-1` but sets `nextAutoLevel=1` (ABR remains available).
2. Ordered ladder [480,720,1080] → cap 1; a reset cap (-1) is repaired on the next `capHls`; explicit manual rendition (currentLevel=2, autoLevelEnabled=true) is respected (cap 1); `autoLevelEnabled=false` → cap stays -1 (manual mode untouched).
3. HLS wrapper preserves subclass construction (`class Sub extends Hls` keeps its methods and instanceof).
4. Full helper on simulated Flixer page (flixer.gd, 1280×720, one visible 640×360 video):
   - `__fb.seek(60)` on an unseekable video (setter throws) returns 'failed' and does NOT change time (failed seek not acknowledged as applied).
   - An unrelated IFRAME (contentDocument null) cannot eclipse the accessible movie: `__fb.play()` → 'ok', play called once.
   - With seekable windows [30–60, 90–150]: seek(35) from t=40 clamps to 60 ('ok'); seek(500) clamps to 150; seek(NaN) → 'failed' (time unchanged).
   - Play-refusal tokening: a late play() rejection arriving AFTER a pause does not set playBlocked (0); a fresh play refusal sets it (1); a refusal after successful playback does not (0) — no spurious repair click.

**Protects:** page_helpers.js `capHls` (ladder ordering, cap repair, manual-mode respect), HLS subclassing, `__fb.seek` (seekable-window clamping, invalid input, failure honesty), `__fb.play`/`playBlocked` (attempt tokening vs pause/success).

# tests/integration-pass-tests.cjs (24 lines)

**What it is:** Node VM integration test combining the ACTUAL `page_helpers.js` and `tv_ui.js` sources against a simulated nested-iframe Flixer page and mock CSS style objects. Uses a `check(name, fn)` harness that counts failures and sets `process.exitCode=1` at the end.

**Run:** `cd <repo-root> && node tests/integration-pass-tests.cjs`
**Current status (verified 2026-10-21, node v26.8.1): FAILS, exit 1 — 4 of 6 checks fail.** PASS: both tv_ui.js setFit/restoreMenus CSS checks. FAIL: "focused same-origin iframe exposes its reachable movie…" and "nested reachable movie center…" (`window.addEventListener is not a function` — the mock window lacks addEventListener, which the current page_helpers.js now calls at load), and both Shaka checks (`c.capShaka is not a function` — capShaka is no longer in the quality slice region). The test is STALE against the v2.x assets.

**Fixture (`makePlayer`):** top window (flixer.gd, 1280×720) containing an IFRAME (rect 500,100 → 904,404; offsetWidth/Height 404×304, clientLeft/Top 2) whose same-origin contentDocument holds a 300×200 video at (20,30) in a 400×300 inner window.

**Checks (6):**
1. A focused same-origin iframe exposes its reachable movie instead of becoming opaque: `__fb.state()` reports `f:1`.
2. Nested reachable movie center maps into top-page coordinates: `__fb.frameCenter()` ≈ (672/1280, 232/720) — iframe offset + clientLeft/Top + scale all accounted for.
3. CSS serialization cannot prevent restoration of a fractional menu offset: `setFit(el,'margin-top','148.000006px')` then `restoreMenus()` clears it (mock style with px rounding).
4. Multiple fits of one wrapper restore the ORIGINAL value, not an earlier temporary: original '3px' → fit '10px' → fit '20px' → restore → '3px'.
5. Shaka honors manual ABR-off mode: with `abr.enabled=false`, `capShaka` does NOT select a replacement track (selected stays 0).
6. Shaka repairs an externally reset cap even when the ladder is unchanged: maxHeight reset to Infinity → next `capShaka` restores 720.

**Protects:** page_helpers.js cross-frame state/frameCenter mapping, tv_ui.js `setFit`/`restoreMenus` (original-value restoration, fractional px), Shaka ABR-off respect and cap repair.

# tests/seek-click-tests.cjs (37 lines)

**What it is:** Node VM test of the ACTUAL shipped `app/src/main/assets/seek_click.js` (the lazy seek-repair function) against simulated DOM/media objects. The source is expected to return a `repair(fx, fy, mode?)` function.

**Run:** `cd <repo-root> && node tests/seek-click-tests.cjs` (node installed; runnable).

**Fixture:** a 20px-tall slider track (`role=slider`, `aria-label=Seek`) inside a root; a video (duration 1000, seekable windows configurable); `window.elementFromPoint` returns a swappable `hit` node; counters for installed listeners, timers, and currentTime writes.

**Checks (sequential asserts):**
1. Loading the script installs ZERO listeners and ZERO timers (lazy — no side effects at load).
2. `repair(.5, 610/700, 'probe')` → 'track' with no seek write (probe never seeks).
3. `repair(.5, 610/700)` → 'seeked', currentTime = 500 (50% of 1000).
4. Repeating → 'seeked' but only ONE total write (already-completed native seek not performed twice).
5. A Volume slider (aria-label Volume) → 'none' (no seek).
6. A BUTTON inside the track → 'none'; a non-track node → 'none'.
7. Infinite video duration → 'none'.
8. Two videos in the root (ambiguous player) → 'none'.
9. Negative or NaN fractional x → 'none'.
10. Seekable gaps: windows [100–300, 700–900]; 50% (t=500) is in a gap → clamps to nearest boundary 300, 'seeked'.
11. RTL: with `direction:rtl`, 30% maps to 750 (mirrored).
12. An IFRAME hit with no contentDocument → 'frame'.
13. Same-origin iframe with its own document: coordinates normalized against the frame's own dimensions (clientWidth/Height, rect) → 'seeked' at 500.
14. Final: still zero listeners and zero timers installed.

**Protects:** seek_click.js — lazy loading, probe mode, single-seek idempotency, track identification (role/aria-label, excluding volume/buttons/non-tracks/ambiguous players), finite-duration requirement, seekable-gap clamping, RTL mirroring, iframe coordinate normalization, no side effects.

# tests/fmhy-theme-tests.cjs (13 lines)

**What it is:** Node VM test of the ACTUAL `app/src/main/assets/fmhy_theme.js` — the FMHY site-specific dark/light theme helper. Runs the real source in 10 contexts: 5 hostnames × dark/light.

**Run:** `cd <repo-root> && node tests/fmhy-theme-tests.cjs` (node installed; runnable).

**Checks (per host/dark-mode combo):**
- On `fmhy.net` and `www.fmhy.net`: sets localStorage `vitepress-display-mode` and `vitepress-theme-appearance` to 'dark'/'light' per `Buccaneer.isDarkMode()`, and toggles the `dark`/`light` classes on `document.documentElement` accordingly.
- On `flixer.gd`, `coreflix.test`, `notfmhy.net`: writes ZERO localStorage keys and leaves the class list at 'light' (unrelated origins untouched — no cross-site theme leakage).

**Protects:** fmhy_theme.js — host-gated theme application (FMHY only), VitePress theme keys, dark/light class toggling, origin isolation.

# tests/quality199-startup.cjs (11 lines)

**What it is:** Node VM test of the ACTUAL `page_helpers.js` startup quality-preference logic — slices the real source between `function qualityPreferenceFor(player)` and `function capShaka(player)` and runs it with a mock bridge (`bridgeCall` → quality preference JSON), `QUALITY_MIN=400`, `QUALITY_MAX=800`.

**Run:** `cd <repo-root> && node tests/quality199-startup.cjs`
**Current status (verified 2026-10-21, node v26.8.1): FAILS, exit 1** — `TypeError: c.capHls is not a function` (line 5). The slice between `qualityPreferenceFor` and `capShaka` no longer contains the capHls definition in the current page_helpers.js — the test is STALE against the v2.x asset.

**Checks (5):**
1. First app preference (720, revision 0) overrides the site startup 1080: `capHls` sets `autoLevelCapping=1` and `currentLevel=-1` (forces ABR to re-pick under the cap) — even at revision 0.
2. A later WEBSITE manual quality choice (currentLevel=2, autoLevelEnabled=false) is retained (currentLevel stays 2).
3. A 1080-only source remains playable: cap 0 (no cap applied), no forced level change.
4. An explicit app manual-quality marker (`__fbManualQuality=true`, currentLevel=1) is not overwritten by a stale cap.
5. A NEW explicit app preference (480, revision 2) overrides the older manual marker: cap 0 and `currentLevel=-1` (re-pick).

**Protects:** page_helpers.js `qualityPreferenceFor`/startup cap semantics — app preference vs site choice vs manual marker precedence, revision-based invalidation, 1080-only playability.

# tests/release18-script-tests.cjs (19 lines)

**What it is:** Node VM test of the ACTUAL JavaScript strings embedded in `app/src/main/java/com/buccaneer/browser/MainActivity.kt`. It parses the Kotlin source, extracts the JS string literals from two regions:
- `scriptIn('private fun seekToFraction', 'eval(')` — the absolute-seek script (from the `seekToFraction` function to `) { raw ->`),
- `scriptIn('private val idleCheck', 'eval(')` — the idle/dim playback-state probe.
String literals are unescaped via `JSON.parse('"' + literal + '"')` and concatenated; `$amount` is substituted with `0.5`.

**Run:** `cd <repo-root> && node tests/release18-script-tests.cjs`
**⚠ cwd-sensitive:** it reads `app/src/main/java/com/buccaneer/browser/MainActivity.kt` via a RELATIVE path — must be run from the repo root.

**Checks:**
- Seek script: with `__fb.state()` = {f:1, d:2400, t:40} → result 'ok', one seek call to t=1200 (50% of 2400); if the page rejects the seek → 'blocked'; states {f:0,fr:1}, {f:1,d:0}, {f:1,d:Infinity}, {f:1,d:100,t:NaN} → 'none' with zero calls; missing `__fb` entirely → 'none'.
- Idle script: (playing, state) → 1 for playing-or-paused-with-progress ({f:0,fr:1} playing; {f:1,p:0} paused at start); 0 for {f:1,p:1} (paused mid-playback — user paused, don't dim); -1 for {f:0,fr:1} (no player found — unknown); 0 for {f:0,fr:0}; missing `__fb` → -1; throwing `__fb.state()` → -1 (error = unknown, never dim).

**Protects:** the exact JS emitted by MainActivity.kt's `seekToFraction` (absolute seek with rejection/invalid-state handling) and `idleCheck` (playback-state classification: playing/paused/unknown — unknown must not dim the screen).

# tests/seek-mapping-integration-tests.cjs (14 lines)

**What it is:** Node VM integration test of the ACTUAL `seek_click.js` (source overridable via `SEEK_SOURCE` env var) against a scaled-iframe player fixture. Uses the `check(name, fn)` harness with `process.exitCode=1` on failure.

**Run:** `cd <repo-root> && node tests/seek-mapping-integration-tests.cjs` (node installed; runnable).

**Fixture:** top window (1000×700) whose `elementFromPoint` returns an IFRAME (rect 100,50 → 302,202; clientWidth/Height 400×300, clientLeft/Top 2, offsetWidth/Height 404×304); the frame's inner window (400×300) has a 400×20 slider track at y 250–270 and a 100s video.

**Checks (2):**
1. `scaled iframe timeline maps the intended quarter position including borders` — a tap at top-page coordinates (100+(2+100)*0.5)/1000, (50+(2+260)*0.5)/700 (i.e. the center of the track INCLUDING the 2px client borders) maps to 25% of the video → currentTime 25, 'seeked'.
2. `timeline setter that silently rejects the request is not reported seeked` — when the video's `currentTime` setter is a no-op (silent rejection), the repair reports 'none' (not 'seeked') — honest status when the write didn't take.

**Protects:** seek_click.js — iframe coordinate scaling with border (clientLeft/Top) compensation, and truthful 'seeked' reporting (verify the write actually changed currentTime).

# tests/cinejoy199-quality.cjs (10 lines)

**What it is:** Node VM test of `app/src/main/assets/cinejoy_quality.js` — the CineJoy site-specific quality-seeding helper (`__buccaneerCinejoyQuality`). Simulates cinejoy.pk with a localStorage holding `cinejoy_player_local` settings and a `Buccaneer.qualityPreference` bridge.

**Run:** `cd <repo-root> && node tests/cinejoy199-quality.cjs`
**Current status (verified 2026-10-21): CANNOT RUN, exit 1** — `ENOENT: app/src/main/assets/cinejoy_quality.js` is absent (assets dir contains only flixer_image_guard.js, fmhy_theme.js, page_helpers.js, popup_guard.js, seek_click.js, tv_ui.js). Confirmed removed in v2.x; this test is dead with the asset.

**Checks (5, if the asset exists):**
1. Startup 1080 (site default) is replaced by the saved app preference 720 WITHOUT erasing other settings (volume .4 kept).
2. A later manual website choice (480) is retained (`apply()` → 'unchanged').
3. A NEW app policy (800, revision 1) seeds the nearest supported 720 for the next private player ('next-player'), keeping volume .7.
4. Site-default restore (pref height 0, revision 2) restores the original value only when still app-owned; does NOT create a `buccaneer:cinejoy-quality-v1` marker.
5. On a foreign host (notcinejoy.pk): the helper installs nothing and never touches foreign localStorage (getItem would throw if called).

**Protects:** cinejoy_quality.js — app-preference seeding vs site choice, revision-based ownership, foreign-site isolation.

### Python

# tests/editor199-dom.py (38 lines)

**What it is:** Playwright (Chromium) DOM test of the REAL `app/src/main/assets/tv_ui.js` page helper — the 1.9.9 editor (`__tvUi.edit`) behavior against a live page.

**Run:** `cd <repo-root> && python3 tests/editor199-dom.py`
**Requirements:** `playwright` Python package + a `chromium` binary on PATH (`shutil.which('chromium')`; launches headless with `--no-sandbox --disable-gpu`). Writes results to `review199/editor-dom-results.json`.
**Can it run here? NO (verified 2026-10-21):** `playwright` Python module is not installed and no chromium binary is on PATH in this environment.

**Setup:** 960×540 page with a search form (`input[name=q]`), `#other` input, `#pw` password input, `#rich` contenteditable. Installs a fake `window.Buccaneer` bridge (currentDocument→'test-doc', editableFocused records reports). Loads the real tv_ui.js source. Clicks the search input to get a field report.

**Checks (14):**
1. `insert` changes the actual named form field ("Arrivax") and ack carries the value.
2. `delete` changes the actual site field at the caret ("Arriva").
3. `submit` (Enter) invokes the REAL form submit handler with the typed query ("Arrival") — status "submitted".
4. Site submit handler not bypassed by raw `form.submit()` (value intact).
5. A form-contained SPA Enter keydown handler (preventDefault) is honored: no unwanted form reload (keySearch true, submits still 1).
6. `clear` empties the actual field.
7. Selected range (6–11 of "hello world") is REPLACED, not appended ("hello search").
8. Stale field identity cannot edit a replacement field (status "stale", `#other` untouched).
9. A prevented `beforeinput` is reported "blocked", not "applied".
10. Password edit updates the field WITHOUT returning the secret (ack: pw=1, v='', length=7).
11. contenteditable receives inserted text.
12. contenteditable `clear` uses an actual range (empties it).
13. Formless search Enter reaches the site's keydown listener without a forced reload (status "key-dispatched", performance.timeOrigin unchanged = no navigation).
14. Unreachable/opaque frame reports pre-action fallback status "unreachable".

**Protects:** `tv_ui.js` `__tvUi.edit` (insert/delete/submit/clear semantics, field-identity staleness, password redaction, contenteditable, SPA Enter handling, unreachable-frame fallback).

# tests/menu199-dom.py (25 lines)

**What it is:** Playwright (Chromium) DOM test of the REAL `tv_ui.js` menu-fitting behavior (`__tvUi.fitMenus` / `restoreMenus` / `scrollAt`) at three viewport sizes.

**Run:** `cd <repo-root> && python3 tests/menu199-dom.py`
**Requirements:** playwright + chromium on PATH. Writes `review199/menu-dom-results.json`.
**Can it run here? NO (verified 2026-10-21):** playwright module absent, no chromium binary on PATH.

**Setup:** For each viewport (960×540, 1280×720, 1920×1080): a page with an anchor 80px from the bottom containing a "Seasons" trigger button; clicking it shows (after 200ms delay) an unlabelled popup `#choices` (12 buttons × 64px, site max-height 320px, overflow-y auto) plus an unrelated control block `#unrelated`. Loads the real tv_ui.js.

**Checks (5 per viewport = 15):**
1. Unlabelled delayed popup is repositioned to fit the viewport (top ≥ 5% of height, bottom ≤ 95%).
2. The site's own max-height (320px) is NOT enlarged.
3. Unrelated visible controls are not restyled (no style attribute added to `#unrelated`).
4. `__tvUi.scrollAt` at the popup center scrolls the INTERNAL list: down-scroll reaches the last item ('nested' result, scrollTop == max > 0), up-scroll returns to the first (scrollTop 0).
5. `__tvUi.restoreMenus()` cleanup removes only its own properties (maxHeight cleared, display back to 'block') while preserving website styles (color:'red' kept).

**Protects:** `tv_ui.js` menu fitting/repair (viewport fitting of delayed unlabelled popups, internal-scroll routing, passive cleanup that doesn't clobber site styles).

# tests/structural1910-dom.py (30 lines)

**What it is:** Playwright (Chromium) DOM test of the REAL `tv_ui.js` structural 19.10 behaviors: fitted-menu scrolling and rejected contenteditable edits.

**Run:** `cd <repo-root> && python3 tests/structural1910-dom.py`
**Requirements:** playwright + chromium on PATH. Creates `review1910/` and writes `structural-dom-results.json`. Ends with `assert all(x['pass'])` (non-zero exit on any failure).
**Can it run here? NO (verified 2026-10-21):** playwright module absent, no chromium binary on PATH.

**Part 1 (per viewport 960×540, 1280×720, 1920×1080):** a 2000px-tall page with a fixed menu `#m` (25 buttons × 60px, initially above the viewport at top:-100px). Loads real tv_ui.js, calls `__tvUi.fitMenus()`:
1. 70 repeated small `__tvUi.scrollAt(+40)` steps at the menu's on-screen position reach the bottom of the fitted menu (scrollTop == max > 0).
2. A passive re-fit (`fitMenus` again) preserves the existing scroll position.
3. 70 upward steps return to the first item (scrollTop 0).

**Part 2 (960×540):** a contenteditable `#rich` with "abc"; fake `window.Buccaneer` bridge; after `reportField()`, `document.execCommand` is stubbed to fail (return false):
4. `__tvUi.edit('insert','XYZ',…)` on the rejected command is NOT reported applied (status != 'applied') and the text stays "abc".

**Protects:** `tv_ui.js` fitted-menu scrolling (repeated remote scroll steps on repositioned menus, scroll-position preservation on refit) and honest edit-status reporting when the page rejects the edit.

# tests/run-dom-tests.py (11 lines)

**What it is:** Headless-Chromium DOM test runner for `ui-fixture.html`. No Playwright — plain `chromium --headless --no-sandbox --disable-gpu --allow-file-access-from-files --window-size=1280,720 --virtual-time-budget=5000 --dump-dom <fixture>`, then extracts the JSON array from `<pre id="results">…</pre>` in the dumped DOM, prints PASS/FAIL per check, and asserts **≥17 checks, all passing**.

**Run:** `cd <repo-root> && python3 tests/run-dom-tests.py`
**Requirements:** `chromium` (or `chromium-browser`/`google-chrome`) on PATH. Stdlib-only Python (pathlib, subprocess, re, json, html) — runs on any python3.
**Can it run here? NO (verified 2026-10-21):** no chromium/chromium-browser/google-chrome binary on PATH in this environment (the script would exit with "Install Chromium or Chrome and put it in PATH.").
**Protects:** whatever `ui-fixture.html` exercises (UI fixture DOM behaviors of the page assets — see fixture analysis).

# tests/run-diagnostic-safety-tests.py (11 lines)

**What it is:** Same headless-Chromium `--dump-dom` runner pattern as run-dom-tests.py, targeting `diagnostic-safety-fixture.html`. Extracts JSON from `<pre id="results">`, prints per-check results, asserts **≥5 checks, all passing**.

**Run:** `cd <repo-root> && python3 tests/run-diagnostic-safety-tests.py`
**Requirements:** chromium/chromium-browser/google-chrome on PATH; stdlib-only Python.
**Can it run here? NO (verified 2026-10-21):** no chromium-family binary on PATH.
**Protects:** diagnostic-safety behaviors encoded in the fixture (diagnostic logging safety in the page context — see fixture analysis).

# tests/run-integrated-controller-tests.py (11 lines)

**What it is:** Same headless-Chromium `--dump-dom` runner pattern, targeting `integrated-controller-fixture.html`. Extracts JSON from `<pre id="results">`, prints per-check results, asserts **≥12 checks, all passing**.

**Run:** `cd <repo-root> && python3 tests/run-integrated-controller-tests.py`
**Requirements:** chromium/chromium-browser/google-chrome on PATH; stdlib-only Python.
**Can it run here? NO (verified 2026-10-21):** no chromium-family binary on PATH.
**Protects:** integrated-controller behaviors encoded in the fixture (see fixture analysis).

# tests/run-keyboard-menu-193-tests.py (12 lines)

**What it is:** Same headless-Chromium `--dump-dom` runner pattern, but loops over **three viewport sizes** (960×540, 1280×720, 1920×1080) against `keyboard-menu-193-fixture.html`. For each size: extracts JSON from `<pre id="results">`, prints per-check results prefixed with the viewport, asserts **exactly 11 checks, all passing**.

**Run:** `cd <repo-root> && python3 tests/run-keyboard-menu-193-tests.py`
**Requirements:** chromium/chromium-browser/google-chrome on PATH; stdlib-only Python.
**Can it run here? NO (verified 2026-10-21):** no chromium-family binary on PATH.
**Protects:** keyboard/menu 19.3 behaviors (menu fitting/keyboard interaction across resolutions) encoded in the fixture (see fixture analysis).

# tests/run-seek-dom-tests.py (11 lines)

**What it is:** Same headless-Chromium `--dump-dom` runner pattern, targeting `seek-fixture.html`. Extracts JSON from `<pre id="results">`, prints per-check results, asserts **≥9 checks, all passing**.

**Run:** `cd <repo-root> && python3 tests/run-seek-dom-tests.py`
**Requirements:** chromium/chromium-browser/google-chrome on PATH; stdlib-only Python.
**Can it run here? NO (verified 2026-10-21):** no chromium-family binary on PATH.
**Protects:** seek behaviors encoded in the fixture (seek_click.js / __fb.seek DOM behavior — see fixture analysis).

### HTML fixtures

# tests/ui-fixture.html (35 lines)

**What it is:** Self-contained HTML fixture executed by `run-dom-tests.py` (headless Chromium `--dump-dom`). Loads the REAL `app/src/main/assets/tv_ui.js` and runs ~19 inline checks, writing results JSON into `<pre id="results">`.

**Checks (in order):**
1. `__tvUi` helpers installed.
2. Clipped menu (top:-140px) top recovered by `fitMenus` (≥7px from top).
3. Oversized menu (1000px) fits viewport (bottom ≤ innerHeight-7).
4. Menu is scrollable (scrollHeight > clientHeight, overflowY auto).
5. `scrollAt` on menu → 'nested', menu scrolls, outer page stays still (scrollY 0).
6. At menu bottom boundary, scroll does NOT leak to the page.
7. Delayed focus (700ms) of `#field` is reported via the bridge (`editableFocused`).
8. `__tvUi.type('insert','Z',…)` → 'ok', selection (1–3) replaced: "abcd" → "aZd".
9. Unicode backspace: "a😀" → "a" (whole code point).
10. maxLength=2 respected: insert "bcd" → "ab".
11. `clear` empties the field.
12. Password field: bridge report has v='' and pw=1 (secret never sent to native preview).
13. Shadow-DOM input focus is reported (value "shadow").
14. Frame-targeted keyboard command: insert into an iframe's inner input via srcdoc (frame-scoped tv_ui.js) → "inside!".
15. `restoreMenus` removes the temporary max-height and restores the original position (top -140px).
16. Repeated fitting has no accumulated offset.
17. Closed (display:none) menu: fitting restores original inline styles.
18. Site-authored `!important` style changes are not erased by restore.
19. Shadow-root submenu scrolls via `scrollAt` ('nested').
20. Transformed (scale .5) iframe: scroll routed to the child transport with coordinates and deltas divided by the scale (minus clientLeft).
21. Playing video detected by `__tvUi.playback()`.

**Protects:** tv_ui.js menu fitting/restoration (clipping, viewport fit, style preservation, no drift), field editing (selection replace, unicode, maxlength, clear, password redaction), shadow-DOM and iframe field targeting, nested scroll routing, transformed-iframe coordinate mapping, playback detection.

# tests/diagnostic-safety-fixture.html (19 lines)

**What it is:** Self-contained HTML fixture executed by `run-diagnostic-safety-tests.py` (headless Chromium). Loads the REAL `page_helpers.js` with a mock `Buccaneer` bridge where `isDiagnosticsEnabled` is initially false, and a spy on `HTMLCanvasElement.prototype.getContext`.

**Checks (5):**
1. Logging OFF: page canvas contexts are never queried (0 getContext calls) — no canvas fingerprinting/probing when diagnostics disabled.
2. Logging OFF: no media diagnostic events emitted.
3. Logging ON (`__fb.setDiagnosticLogging(true)`): still no canvas context queries.
4. Logging ON: a 'playing' media event is recorded (`media_playing` diagnostic event).
5. Player geometry diagnostic calls the public helper (`player_geometry` event present).

**Protects:** page_helpers.js diagnostic safety — no canvas probing, no diagnostics when disabled, media events + player geometry only when explicitly enabled.

# tests/integrated-controller-fixture.html (24 lines)

**What it is:** Self-contained HTML fixture executed by `run-integrated-controller-tests.py` (headless Chromium). Loads the REAL `page_helpers.js` AND `tv_ui.js` plus `seek_click.js` (fetched and eval'd) against a transformed (scale .5, 2px border) player iframe with a real seek track — an end-to-end integration of the controller stack in real Chromium.

**Checks (12):**
1. Real Chromium restores canonicalized fractional menu CSS after fit+restore (marginTop/maxHeight both '').
2. Real focused iframe retains exposed video state (`__fb.state()` f===1).
3. Nested video center uses top-document coordinates (scale + clientLeft/Top accounted).
4. Selected nested player stays attached across iframe blur.
5. `prepareFullscreen` → 'ok' creates the `__fb_fs_overlay`; `cancelFullscreen` removes it.
6. A retained (cancelled) overlay cannot request fullscreen (stale pointerup ignored).
7. An old overlay cannot activate a replacement request; the live overlay creates exactly ONE fullscreen request (pointerup+click).
8. Real transformed iframe track is recognized by seek_click.js probe ('track').
9. Transformed timeline selects the intended quarter position (time 25 of 100, 'seeked').
10. Actual DOM media setter rejection (silent no-op) is reported 'none', not false success.

**Protects:** the integrated controller stack in real Chromium — menu CSS canonicalization, cross-frame video state/coordinates, fullscreen overlay lifecycle (tokenization, single request, cancellation), and seek_click.js against a real scaled iframe DOM.

# tests/keyboard-menu-193-fixture.html (21 lines)

**What it is:** Self-contained HTML fixture executed by `run-keyboard-menu-193-tests.py` at THREE viewports (960×540, 1280×720, 1920×1080). Loads the REAL `tv_ui.js` and runs exactly 11 checks per viewport (the runner asserts exactly 11, all passing).

**Fixture:** a 2000px page with: a menu clipped by an overflow:hidden ancestor (`#clipped` inside `#clip`), a scaled (scale .5) clipped popup (`#scaleMenu`), an `aria-expanded`/`aria-controls` popup (`#controlled`), a safe in-viewport menu (`#safe`), and a text `#field`.

**Checks (11 per viewport):**
1. Menu fits inside its CLIPPING ancestor (not just the screen): top/bottom within clip ±7px, still scrollable.
2. Scaled popup recovers its clipped top (≥ max(12, 5% of height)).
3. Expanded aria-controls popup gets fitted even without a menu role.
4. Menu keeps a TV-safe top inset (≥ max(12, 5% of height)).
5. Fitted clipped menu scrolls without moving the page ('nested', scrollY 0).
6. At the menu boundary, scrolling does not redirect into the page.
7. Repeated scaled-menu fitting does not drift (position stable across refits).
8. Qualified field read (`__tvUi.fieldState`) refreshes the value WITHOUT an input event (value changed silently → still read).
9. Qualified read refuses a different field id (returns '').
10. Qualified password snapshot sends length but not the secret (pw=1, v='', length=11).
11. `restoreMenus` restores original styles on all fitted ancestors (maxHeight/marginTop cleared).

**Protects:** tv_ui.js 19.3 keyboard/menu behaviors — clipping-ancestor-aware fitting, scaled popups, aria-controls popups, TV-safe insets, nested scroll containment, no fitting drift, qualified read-only field snapshots (staleness by field id, password redaction), style restoration.

# tests/seek-fixture.html (24 lines)

**What it is:** Self-contained HTML fixture executed by `run-seek-dom-tests.py` (headless Chromium). Fetches and eval's the REAL `seek_click.js` against a real DOM player: 800×450 video, an 800×20 `role=slider aria-label=Seek` track (with a child span), a volume range input, and a Play button.

**Checks (9):**
1. Actual DOM seek track (and its child span) detected → 'seeked'.
2. Seek maps the clicked fraction to duration (75% of 1800s = 1350s).
3. A completed native seek is not duplicated (no second write on repeat).
4. Volume slider is NOT treated as seek ('none', time unchanged).
5. Play button is NOT treated as seek.
6. Plain background is NOT treated as seek.
7. Unknown/live (Infinity) duration is not guessed ('none').
8. Ambiguous multiple players are not guessed ('none' with two videos).
9. RTL seek mapping uses the reversed fraction (25% from left in RTL = 75% → 1350s).

**Protects:** seek_click.js against a REAL DOM — track/child detection, fraction→duration mapping, idempotency, volume/button/background exclusion, finite-duration requirement, single-player requirement, RTL mirroring.

### JSON baselines / approval records

# tests/repair199-baseline.json (14 lines)

**What it is:** Historical record of the 1.9.9 release state. **NOT consumed by any live check** — the check that used it was removed (see the tail comment in `check-protected-fixes.py`). Kept on disk as a one-time snapshot.

**Contents:**
- `sourceApkSha256`: `8c3cb3a6…5f49` — SHA-256 of the 1.9.9 source APK.
- `helperSha256`: `f918b005…18bf` — SHA-256 of a helper (extraction/verification helper used by the removed check).
- `qualityBefore` / `qualityAfter`: exact source snippets documenting the 1.9.9 quality-preference repair in the page helper (a `pref.changed` condition; "after" adds `|| player.__fbManualQuality!==true` so existing storage alone can't establish a deliberate choice).
- `exactAssets`: whole-file SHA-256 of `popup_guard.js` (`9a339611…0823`), `flixer_image_guard.js` (`147f8bcd…83e179`), `fmhy_theme.js` (`6f10767f…d73`) as of 1.9.9.
- `cinejoySha256`: `8439eec9…a45b4` — SHA-256 of `cinejoy_quality.js` (file since REMOVED from the tree).
- `cinejoyBefore` / `cinejoyAfter`: exact snippets documenting the CineJoy quality-seeding repair ("A fresh default does not erase an existing explicit website choice" → "Existing storage alone cannot establish a deliberate user choice in this session. Seed the app preference once…").

**Run:** nothing runs it — it is data only.
**Status:** permanently stale by design (tree is now v2.x). Note the overlap: `flixer_image_guard.js` 1.9.9 hash `147f8bcd…` also appears in `hardware-compatibility-baseline-1.9.1.json` (1.9.1 era), while the CURRENT live hash in `protected-fixes.json` is `5afdf0b7…` — the file has changed since both baselines.

# tests/hardware-compatibility-baseline-1.9.1.json (14 lines)

**What it is:** The 1.9.1 hardware-compatibility baseline, consumed by `tests/check-warning-repairs.py`.

**Contents:**
- `scriptSha256` (whole-file SHA-256, 1.9.1 era):
  - `flixer_image_guard.js`: `147f8bcd54cdd92e3b0d63c25ded876efb329518e0f15db4360f87c89883e179`
  - `fmhy_theme.js`: `6f10767f14524d23fabef756f6f4006184bb19e5972ca6ad07463f8021be4d73`
  - `page_helpers.js`: `46af664e05b73eea3d23c6d8c14bb4d46eebfdeead7fcc31170155227aa1cf61`
  - `popup_guard.js`: `9a3396113202130655ee5b444708e15d39afbc0abafc3b17af9003e456fb0823`
  - `seek_click.js`: `419499f52c6ad72f5ca5b371df02a930ae6db7e44d52b0c1ff5311e1cb321222`
  - `tv_ui.js`: `2c77ad908bb01610837fd2f9be8437722ac21c77320eaab40252145c05efc535`
- `minSdk`: 22, `compileSdk`: 34, `targetSdk`: 33
- `webkit`: "1.9.0" (androidx.webkit dependency version)

**Run:** indirectly — `python3 tests/check-warning-repairs.py` reads it.
**Current status:** STALE. `check-warning-repairs.py` fails at the first script hash (`flixer_image_guard.js`) because the live asset no longer matches its 1.9.1 hash (current live hash `5afdf0b7…` per protected-fixes.json). The SDK pins (22/34/33) and webkit 1.9.0 likely still match build.gradle.kts, but the script never gets that far.

# tests/approved-editor-menu-repair-1.9.3.json (7 lines)

**What it is:** A small approval record for the 1.9.3 editor/menu repair. No live script in tests/ references it (grep shows no consumer) — it documents an approved change against the 1.9.2 base.

**Contents:**
- `baseVersion`: "1.9.2"
- `scriptSha256`: `tv_ui.js` = `71f7919130f71432c68bc0ac40e85c4100bb90de1a8e232f8a82734a647854f5` (the approved post-repair hash of tv_ui.js)
- `reason`: "Qualified read-only editor snapshots; reversible clipping-aware submenu fitting. Flixer early-return remains unchanged."

**Run:** nothing — documentation/approval artifact.
**Protects:** records that the 1.9.3 change to `tv_ui.js` was limited to qualified read-only editor snapshots + clipping-aware submenu fitting, with the Flixer early-return untouched.

# tests/approved-passive-changes-1.9.json (98 lines)

**What it is:** An approval record of the 1.9 "passive changes" to `app/src/main/assets/tv_ui.js` (the `__fb` page helper). A JSON array of 20 `{old, new}` exact source-snippet pairs documenting every approved edit. No live script consumes it (documentation/approval artifact).

**The approved changes (old → new), summarized:**
1. `pickTarget()`/`withVideo()` reworked: adds passive `selectedTarget` identity (no new Flixer listeners/timers); active IFRAME handling; `withVideo` returns 'failed' on exception.
2. `window.__fb` gains `selectAt(fx,fy)` (fractional→pixel) and `focusTarget()`.
3. `seek(delta)`: guards non-finite delta/currentTime; clamps to `seekable` ranges (snaps to nearest edge if outside); returns success only if `|currentTime-wanted|<=1`.
4–7. HLS level enumeration (`listLevels`/quality apply) now filters to the `pickTarget()` video (`h.media !== target.video` skip).
8. Diagnostics add `h: v.videoHeight`.
9. `frameId` becomes `documentId + ':' + random` (document-scoped frame identity via `bridgeCall('currentDocument')`).
10. `editableClicked()` → `editableClickedForDocument(documentId)`.
11. Manual quality set marks `h.__fbManualQuality = true`.
12–15. Play-blocked tracking hardened: `playAttempt` token + `playBlockedVideo` identity; `cancelPlayAttempt()` on pause; `playBlocked()` only true for the same paused video within 1500ms.
16. `playbackActive` bridge call now passes `documentId`.
17–19. Cross-frame support: `attachedTarget(el)` (walks up to 3 frame boundaries), `videoInFrame(frame)` (finds best visible video inside an iframe), IFRAME targets resolve to the inner video when possible; `frameCenter`-style rect math maps iframe-local rects to top-level coordinates (scale factors, clipping, ok:0 on failure).
20. Fullscreen request tokenization: `fullscreenRequest` counter, `__fb.cancelFullscreen()` removes the `__fb_fs_overlay`; `prepareFullscreen` captures a request token and its key handler bails if superseded.

**Run:** nothing — records what was approved and why.
**Protects:** documents the exact approved diff surface of tv_ui.js (player target selection, seek clamping, document-scoped identity, cross-frame video, fullscreen cancellation) so future edits can be compared against the approved baseline.

