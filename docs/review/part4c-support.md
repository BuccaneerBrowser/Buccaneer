# Part 4c — Support Kotlin files (11)

# CrashLog.kt (61 lines)

## Purpose
Remembers the **last** problem (app crash, or web renderer process death) so that
Menu > Settings > Diagnostics can show it after the app has restarted. Nothing is sent
anywhere (KDoc L12-15). Also mirrors every record to logcat under tag "Buccaneer".

## Public API (singleton `object CrashLog`, L16)
- `fun install(context: Context)` (L26-44, @Synchronized):
  - Idempotent via `@Volatile installed` (L22, L27-28).
  - Wraps the default uncaught-exception handler: on an uncaught exception it
    (a) `record(app, "Crash in thread <name>", stackTrace)` (L33),
    (b) `DiagnosticLog.recordCritical(app, "uncaught_exception", {thread, type, message})`
    with length caps 120/200/1000 (L34-38),
    (c) always chains to the previous handler (L42) — "never hide the original crash";
    the whole record block is try/catch-swallowed (L39-41).
- `fun record(context, what, details)` (L48-57): formats
  `"yyyy-MM-dd HH:mm:ss  <what>\n<details.take(3000)>"`, `Log.e("Buccaneer", text)`,
  and persists to SharedPreferences file `"crashlog"`, key `"last"` (L18-19), using
  **`commit()`** (L56, @SuppressLint("ApplySharedPref") L47) because the process may
  terminate before an `apply()` flushes.
- `fun last(context): String?` (L59-60): reads back the stored string (null if none).

## Invariants
- Only the most recent record is kept (single key "last" — no history).
- details truncated to 3000 chars; thread/type/message capped in the handler.
- `install` must run before anything else in onCreate (it is the first call).
- record() is safe to call from the dying thread: no allocations that can throw
  meaningfully; all failures swallowed.

## How MainActivity.kt uses it
- L478: `CrashLog.install(this)` — the **first** statement in onCreate (before
  DiagnosticLog.initialize L479 and prefs L480), "so even a failure while building the
  screen is recorded".
- L1098: in `Client.onRenderProcessGone` (L1093-1128) — records
  "The web page crashed" (detail.didCrash()) or "The web page renderer stopped" with
  "Page: <safeUrl(lastPageUrl)>".
- L3928-3935 `report(what, e: Throwable)` — generic catch-all used for app-level
  exceptions: DiagnosticLog "app_error" + `CrashLog.record(this, what, stackTrace)`.
- L3791: Diagnostics panel text: `"Last problem: " + (CrashLog.last(this) ?: "none recorded")`.

## What breaks if changed/removed
- Removing install(): uncaught exceptions leave no trace in the app after restart;
  Diagnostics always shows "none recorded" for app crashes.
- Changing commit()→apply() in record(): on a real crash the write may be lost
  (process dies before flush) — the whole point of the class.
- Changing prefs file/key ("crashlog"/"last"): Diagnostics loses previously recorded
  problems on upgrade.
- Removing the chain to the previous handler (L42): Android's default crash dialog /
  tombstone behavior would be suppressed.
- Removing the record() call in onRenderProcessGone: renderer crashes (separate from
  native GPU process death) would be invisible in Diagnostics.

# CrashRecoveryPolicy.kt (11 lines)

## Purpose
Pure retry policy for renderer crashes — deliberately independent of page-finished
timing and WebView availability (KDoc L3). Decides whether a crash-retry window is
still active and what the next auto-retry count is.

## Public API (`internal object CrashRecoveryPolicy`, L4)
- `const val WINDOW_MS = 600_000L` (L5) — 10-minute retry window.
- `fun expired(started: Long, now: Long): Boolean` (L6):
  `started <= 0L || now - started !in 0L..WINDOW_MS`.
  Expired when: no window (0/negative), negative elapsed (clock skew), or older than
  10 minutes.
- `fun nextCount(url: String, previousUrl: String?, started: Long, count: Int, now: Long): Int`
  (L8-10): if the window is still live → `count.coerceIn(0, 2) + 1` (so the count can
  never exceed 3); if the window expired → reset to 1.
  `url`/`previousUrl` are intentionally unused (@Suppress("UNUSED_PARAMETER") L8) —
  comment L7: "Redirects must not turn every renderer crash into a new retry budget."
  (i.e. a redirect to a different URL does NOT reset the budget; only the 10-minute
  window expiry does.)

## Invariants / state machine
- Crash budget: at most **3 automatic retries** per 10-minute window (counts 1, 2, 3).
- Window timestamp `crashWindowStartedAt` is preserved across same-window crashes
  (MainActivity L1120: `if (sameWindow) started else now`) and reset when expired.
- `coerceIn(0, 2)` guards against corrupted/negative persisted counts.

## How MainActivity.kt uses it
- L491-494 (onCreate): reads `crashWindowStartedAt`; if `<= 0` or
  `CrashRecoveryPolicy.expired(crashWindow, now)` → `clearCrashRecovery()` (L319-323:
  resets crashReloadCount=0, removes lastCrashUrl/crashWindowStartedAt, commits).
- L1115-1121 (onRenderProcessGone): `sameWindow = !expired(started, now)`;
  `crashReloadCount = nextCount(url, previousUrl, started, persistedCount, now)`;
  persists count + lastCrashUrl + window start (commit, L1118-1120); toasts
  "reopening the page" for count <= 2, "automatic retries stopped" for count 3.
- L630-634 (startup recovery): if `crashReloadCount in 1..2` and the saved
  `lastCrashUrl` is http → auto-reopen; if `> 2` → start page + `showCrashRecovery(url)`
  (L325-330: "Page recovery" menu with Retry the page / Return home, both call
  clearCrashRecovery()).
- L1517 (navigateUrl): explicit user navigation clears the budget
  (`if (userInitiated && crashReloadCount > 0) clearCrashRecovery()`).
- L1563 (reloadPage): manual reload clears the budget.

## What breaks if changed/removed
- Removing the 10-minute window or raising the 3-retry cap: a page that reliably
  crashes its renderer (e.g. the PowerVR-triggering pages) loops forever, thrashing
  the GPU/driver.
- Making nextCount reset on URL change (using the unused params): a crash→redirect
  chain would get a fresh retry budget per hop — the exact failure the comment warns
  about.
- Changing WINDOW_MS: too short → budget resets mid-crash-loop; too long → users
  blocked from auto-recovery for a long time.
- Removing the expired() check in onCreate: a stale budget from a previous session
  would suppress legitimate auto-reopen (L630) or wrongly show the recovery menu.

# JsonInputBudget.kt (56 lines)

## Purpose
**Iterative preflight, not a JSON validator** (KDoc L3). It rejects pathological
nesting/recursion *before* the input reaches Android's `JSONTokener`, which is
recursive and can blow the stack (or hang) on deeply nested input. It is a
single-pass, O(n), stack-free scanner that tolerates the *superset* of syntax
Android's JSONTokener accepts (unquoted literals, `//`, `/* */`, `#` line comments,
`'`-quoted strings) — it only enforces a depth budget and basic closure.

## Public API (`internal object JsonInputBudget`, L4)
- `const val MAX_DEPTH = 32` (L5).
- `fun allows(raw: String, maxDepth: Int = MAX_DEPTH): Boolean` (L16-55) — returns
  false iff:
  - nesting depth exceeds `maxDepth` at a `{`/`[` (L43), or
  - a `}`/`]` closes below zero (unbalanced, L44), or
  - a `\` or `/` appears outside a string/comment (invalid, L45).
  Returns true otherwise; "Syntax, closure and types are still checked by the
  existing parser" (L54).

## Scanner state machine (L17-53)
States (flags): `quote` (which quote char opened a string, 0 = none), `literal`
(in an unquoted token), `escaped`, `lineComment`, `blockComment`. Skips a leading
BOM `\ufeff` (L23).
- In `lineComment`: ends at `\n`/`\r` (L28).
- In `blockComment`: ends at `*/` (L29).
- In a string: handles `\` escape (L31-33); closing quote = the opening char (L33).
- `literal && !literalDelimiter(c)` → stay in literal (L35). **Key subtlety** (KDoc
  L7-9): Android JSONTokener accepts unquoted literals *including embedded quotes*;
  only a quote at the START of a token opens a string. `literalDelimiter` (L10-14)
  lists the chars that terminate a literal (`{}[]/\\: ,=;#` + whitespace). Treating
  every quote as an opener would let an unquoted `a'` token hide deeply nested
  arrays (the `'` would be seen as a string opener and swallow the `[`s).
- Else (fresh token start, L36-49):
  - `"` or `'` → open string (L39)
  - `/*` → block comment (L40); `//` or `#` → line comment (L41-42)
  - `{`/`[` → depth++ (reject if > maxDepth) (L43)
  - `}`/`]` → depth-- (reject if < 0) (L44)
  - `\`/`/` outside string/comment → reject (L45)
  - whitespace / `: , = ;` → skip (L46-47)
  - anything else → start a literal (L48)

## Invariants
- Never allocates, never recurses — safe on adversarial input from a web page.
- Accepts a superset of JSONTokener's grammar, so it never rejects valid input the
  real parser would accept (it only rejects what would be pathological/invalid).
- Depth is measured on the raw token stream, not per-JSON-value; 32 is far beyond any
  legitimate page payload but bounds the recursion JSONTokener would do.

## How MainActivity.kt uses it
- L57-74 `jsString(raw)` (top-level helper, used for **every** evaluateJavascript
  result): rejects `raw` if null / "null" / > 1 MiB / `!JsonInputBudget.allows(raw)`
  (L58); after decoding the outer JSON string, if the decoded body looks like JSON
  (`{`, `[`, `#`, `//`, `/*` at L72) it re-checks `JsonInputBudget.allows(decoded)`
  (L73) — "Those must not bypass the decoded-body budget". Callers: idle query
  (L187), unmute (L1942), menu-bar text (L1952), scroll probe (L2040), seek-click
  probe (L2108), `__fb.field()` (L2120), tap-stack (L2153), player intents (L2350),
  playBlocked (L2370), and more.
- L1360 `Bridge.editableFocused(frame, state)`: `state` accepted only if
  `length <= 32768 && JsonInputBudget.allows(state)` before `JSONObject(data)`.
- L1479 `Bridge.tmdbAllowlist(urls)`: `urls` accepted only if
  `length <= 131072 && JsonInputBudget.allows(list)` before `JSONArray(list)`
  (poster-grid site image virtualization allowlist).

## What breaks if changed/removed
- Removing the preflight: a malicious page could return a deeply nested JSON string
  from evaluateJavascript (or send a deeply nested state/allowlist via the bridge)
  and crash the app's Java stack in JSONTokener — turning a web page into a
  reliable app-crash vector (and feeding the crash-recovery/watchdog loop).
- Lowering MAX_DEPTH below real payloads: legitimate page results (e.g. a deep
  tap-stack or allowlist) would be silently dropped → features degrade (idle
  detection, OSB, scroll, seek-click all read these).
- Raising it or removing the depth check: re-exposes the stack-overflow vector.
- Changing literalDelimiter (e.g. adding `'` to it, or removing the literal state):
  the unquoted-literal case breaks — either valid JSONTokener input gets rejected
  (false positive) or the `a'`-hiding-nested-arrays bypass re-opens (false negative).
- Removing the BOM skip (L23): a page returning a BOM-prefixed string would be
  mis-scanned (the BOM would start a "literal" that swallows the first real token).

# NavigationHandoff.kt (50 lines)

## Purpose
UI-thread-only, **replaceable navigation barrier** (KDoc L3-7). Before a native
navigation (loadUrl / goBackOrForward / reload / about:blank), the old document gets
**one bounded chance** (250 ms default) to run a JS cleanup (stop media, release
elements) — explicitly *not* a claim that the decoder/GPU actually freed memory.
Cancellation invalidates late callbacks as well as removing the timeout.

## Public API (`internal class NavigationHandoff`, L8-12)
Constructor takes injected scheduler callbacks (testable, no Handler dependency):
- `postDelayed: (Runnable, Long) -> Boolean`
- `remove: (Runnable) -> Unit`
- `timeoutMs: Long = 250L`

Members:
- `private class Pending(val commit: (String) -> Unit)` (L13-15) with
  `lateinit var deadline: Runnable` — the deadline Runnable is assigned after the
  Pending is constructed (L28).
- `private var pending: Pending?` (L16) — at most one pending handoff at a time.
- `val isPending: Boolean` (L17).
- `fun cancel()` (L19-23): if pending, clear it and `remove(old.deadline)`. Late
  callbacks from a cancelled request are no-ops because `complete` checks
  `pending === request` (L45).
- `fun start(cleanup: ((String?) -> Unit) -> Unit, commit: (String) -> Unit)` (L25-42):
  1. `cancel()` any existing pending request (replaceable).
  2. Create `Pending(commit)`; its deadline Runnable → `complete(request, "cleanup_timeout")`.
  3. Post the deadline with `timeoutMs` (250 ms). If `postDelayed` returns false
     (handler dead) → `complete(request, "cleanup_unscheduled")` and return.
  4. Run `cleanup { result -> complete(request, "cleanup_script_completed" if
     result == "\"stopped\"" else "cleanup_unconfirmed") }` — the JS cleanup returns
     the string `'stopped'` (or `'unconfirmed'`); evaluateJavascript wraps it in JSON
     quotes, hence the `"\"stopped\""` comparison (L36).
  5. If cleanup throws: `if (pending === request) complete(request, "cleanup_unavailable")`
     else rethrow — "Do not mislabel an exception from commit as cleanup failure" (L39-40).
- `private fun complete(request, reason)` (L44-49): **idempotent** — only the current
  pending request completes (`if (pending !== request) return`); clears pending,
  removes the deadline, calls `commit(reason)`.

## State machine
```
idle --start()--> pending (deadline posted, cleanup running)
pending --cleanup callback--> complete(reason) --> idle, commit(reason)
pending --250 ms deadline--> complete("cleanup_timeout") --> idle, commit(reason)
pending --cancel()--> idle (deadline removed; late callbacks ignored)
pending --start() again--> old cancelled, new pending (replaceable)
```
Exactly one of {cleanup callback, deadline, cancel} wins; `complete`'s identity check
guarantees commit fires at most once per request.

## How MainActivity.kt uses it
- Field L120-122: `navigationHandoff = NavigationHandoff({t,d -> handler.postDelayed(t,d)}, { handler.removeCallbacks(it) })` — default 250 ms.
- L1571: `navigationPending() = navigationHandoff.isPending || deferredNavigation != null`.
- L1574-1615 `handoffNavigation(releaseMedia=true, commit)`:
  - Captures `source = webView` and `epoch = pageEpoch`; `current()` = pageAvailable &&
    same view instance && same epoch (guards against renderer recreation mid-handoff).
  - `commitWhenForeground()` (L1579-1583): if `current()` and `foreground` → `commit()`;
    else stores `deferredNavigation = { if (current()) commit() }` — executed in
    onResume (L680-682). This is the "commit when foreground" behavior: a navigation
    requested while backgrounded (e.g. returning from a picker) is deferred until the
    activity is visible again.
  - If old URL is null/blank/about:blank → no cleanup needed: `navigationHandoff.cancel()`
    + `commitWhenForeground()` (L1586-1589).
  - Else logs "navigation_cleanup_dispatched" and `navigationHandoff.start(cleanup, commit)`:
    - cleanup (L1592-1607): `source.stopLoading()` first (marker set BEFORE stopLoading
      because provider callbacks can re-enter), then evaluateJavascript: pause up to 64
      `video,audio` elements; if releaseMedia also `src=''; load(); remove()`; returns
      `'stopped'` or `'unconfirmed'`.
    - commit (L1608-1614): if `current()` → DiagnosticLog.record(reason) then
      `commitWhenForeground()`.
  - Callers: `navigateUrl` L1528-1532 (loadUrl + requestFocus + showCursor),
    `navigateHistory` L1557 (goBackOrForward, releaseMedia=false), `reloadPage` L1568,
    `showStartPage` L1625 (loadUrl about:blank when not initial).
- Cancellation points: `resetDocumentWork` L2317-2321 (called from onPageStarted L911
  and showStartPage L1620) → `deferredNavigation = null; navigationHandoff.cancel()`;
  and the about:blank fast path L1587.
- While pending, WebView callbacks are suppressed: onPageStarted L902-907 (a load that
  predates the pending native command is stopLoading'd, "prior_load_start_ignored"),
  onPageCommitVisible L947, onPageFinished L1072, onProgressChanged L1134 all early-return
  on `navigationPending()`.

## Invariants
- UI-thread only; no synchronization (single-threaded by contract).
- At most one pending request; start() replaces, cancel() kills.
- commit fires exactly once per start(), with reason ∈ {cleanup_script_completed,
  cleanup_unconfirmed, cleanup_timeout, cleanup_unscheduled, cleanup_unavailable}.
- The 250 ms bound means a hung cleanup script can never block navigation.

## What breaks if changed/removed
- Removing the barrier (calling loadUrl directly): the old document's media keeps
  playing/holding decoder + GPU resources during the transition — on this PowerVR
  device that is the crash-prone window; also page scripts from the old document can
  fire callbacks into the new one.
- Removing the 250 ms timeout: a page whose cleanup script hangs (or never returns)
  would block all navigation forever.
- Changing the `"\"stopped\""` comparison: every cleanup would be logged as
  "cleanup_unconfirmed" (cosmetic) — but note commit happens regardless of the reason;
  the reason is diagnostic only.
- Removing commitWhenForeground's foreground check: navigations committed while the
  activity is backgrounded (e.g. right after returning from a picker) can race the
  WebView's paused state.
- Removing the `pending === request` identity check: double commits (deadline +
  cleanup callback both firing) → double loadUrl / duplicate navigation.
- Changing resetDocumentWork to not cancel the handoff: a stale pending commit from a
  previous document could fire into the new page (epoch guard mitigates, but the
  cancel is the primary invalidation).

# PopupGuard.kt (115 lines)

## Purpose
Native half of the "Popup Guard – Streaming Mode" Chrome extension, ported to Android.
The class itself is a small state/policy object (enable flag + protected-domain list +
the declarativeNetRequest rule ported to a Kotlin predicate). The actual page-side
behavior (window.open override, `_blank`→`_self` rewrite, killOverlays every 1500 ms)
lives in `assets/popup_guard.js`, injected at document start by MainActivity. The
mapping table is in the KDoc at L10-23.

## Public API
- Top-level `readAsset(context, name): String` (L7-8) — UTF-8 asset reader used for
  all JS assets (popup_guard.js, page_helpers.js, flixer_image_guard.js, fmhy_theme.js,
  tv_ui.js, seek_click.js).
- `PopupGuard(context)` (L24) — constructor takes only a Context (for SharedPreferences
  "popup_guard", MODE_PRIVATE, L26).
- `val enabled: Boolean` (L32-33) — @Volatile-backed (L28-29); read from the JS bridge
  thread, hence volatile.
- `fun toggle(): Boolean` (L36-40) — flips `on`, persists under key `popupGuardEnabled`
  (L100), returns new state.
- `fun protectedDomains(): List<String>` (L47) — `DEFAULT_PROTECTED` + user set sorted.
- `fun isProtected(host: String): Boolean` (L49-52) — cleanHost then any hostMatches.
- `fun isBuiltIn(host: String): Boolean` (L55-58) — matches only DEFAULT_PROTECTED.
- `fun toggleProtected(host: String)` (L61-68) — add/remove user domain; no-ops for
  empty or built-in hosts; if any existing entry covers the host it removes the
  covering entries, else adds the cleaned host. Persists under `protectedDomains`
  (L101) as a StringSet.
- `fun blocksNavigation(initiatorUrl: String?, target: Uri): Boolean` (L81-90) — the
  rules.json port: returns true iff guard ON, target scheme http/https, initiator
  host matches a protected domain, and target host does NOT match that same domain.
  Subdomains count as same site (hostMatches, L110-111).
- Companion (L99-114): `DEFAULT_PROTECTED = listOf("7movies.ac")` (L108);
  `hostMatches(host, domain)` (L110-111, exact or `.`-suffix); `cleanHost`
  (L113, lowercase + strip `www.`).

## Key invariants / state
- Two persisted keys: `popupGuardEnabled` (default **true**, L29) and
  `protectedDomains` (StringSet, default empty).
- `on` is @Volatile; prefs writes use `apply()` (async) — the in-memory `on` is the
  source of truth for the current process.
- `blocksNavigation` only ever returns true for http(s) targets (L83-84); non-http
  schemes are handled by MainActivity separately.
- Built-in domains (7movies.ac) can never be removed via toggleProtected (L63).

## How MainActivity.kt uses it
- Field `guard: PopupGuard` (L103), constructed in onCreate at L508.
- L511: `guardJs = readAsset(this, "popup_guard.js")` (the readAsset top-level fn).
- L755: diagnostics snapshot includes `popupGuard: guard.enabled`.
- L786: `WebViewCompat.addDocumentStartJavaScript(webView, guardJs, origins)` — JS
  half installed at document start for all origins (fallback: L823
  `evaluateJavascript(guardJs)` on page events when DOCUMENT_START_SCRIPT unsupported).
- L864-885 `interceptNavigation(view, uri, mainFrame)`: for http/https,
  `if (mainFrame && guard.blocksNavigation(view?.url, uri))` → logs
  "redirect_blocked" (initiator+target via DiagnosticLog.safeUrl), `noteBlocked("a
  redirect")` (L1498-1501: DiagnosticLog "popup_guard_action", blockedCount++),
  returns true (consumed). Non-http schemes: `if (guard.enabled) noteBlocked("an app
  link")` and always returns true (L883-884) — nothing on a TV browser can open
  intent:/market:/tel:. Called from `Client.shouldOverrideUrlLoading` (L889-898,
  both new and deprecated overloads).
- L1180-1194 `onCreateWindow` (WebChromeClient): logs "popup_requested" with
  gesture/dialog/guard.enabled; **if guard.enabled → noteBlocked("a popup window")
  and return false** (window.open blocked). This is the port of background.js
  tabs.onCreated/windows.onCreated.
- When guard is OFF (L1195-1243): creates a temporary bounded WebView popup
  (JS on, file/content access off, permissions denied, 10 s timeout via
  popupTimeouts, disposed on render-process-gone / main-frame error / onCloseWindow);
  its first http(s) navigation is forwarded into the main view via
  `navigateUrl(url, userInitiated = false)` if the page epoch is unchanged.
- L3171-3173: menu item "Block redirects from <site>: ON/OFF" built from
  `PopupGuard.cleanHost(host)` + `guard.isProtected(site)` → `toggleProtectedSite`.
- L3185-3190 `toggleGuard()`: `guard.toggle()`, logs "setting_popup_guard", and sets
  `webView.settings.javaScriptCanOpenWindowsAutomatically = !on` (guard ON disables
  script-opened windows at the WebView level too).
- L3192-3195 `toggleProtectedSite(site)`: toasts "$site is protected by default" for
  built-ins; otherwise calls `guard.toggleProtected(site)`.

## What breaks if changed/removed
- Removing the `enabled` flag or changing its default: popup blocking and the
  javaScriptCanOpenWindowsAutomatically coupling in toggleGuard (L3188) break;
  JS bridge reads `enabled` (volatile contract).
- Changing `blocksNavigation` semantics (e.g. blocking subdomains, or non-http
  schemes): redirect interception in interceptNavigation (L867) changes; note the
  contract that UI-initiated navigations (address bar/bookmarks/start page) never
  reach shouldOverrideUrlLoading so are never blocked (KDoc L78-79).
- Changing DEFAULT_PROTECTED or hostMatches: isBuiltIn/toggleProtected menu
  behavior and the 7movies.ac protection change.
- Removing `readAsset`: all six JS assets fail to load (L511-516) — popup guard JS,
  page helpers, poster-grid site image guard (the PowerVR crash fix), theme and TV UI scripts
  all die.
- Renaming prefs keys: existing users lose their guard state/protected list.

# UiCoalescer.kt (49 lines)

## Purpose
Thread-safe coalescer that guarantees **at most one queued UI callback and one pending
value** even during a JavaScript-bridge burst (KDoc L3-6). `[post]` must enqueue
asynchronously (Handler.post), never invoke inline. Merge is only for replaceable
snapshots/counts — **never ordered player commands** (KDoc L6).

## Public API (`internal class UiCoalescer<T : Any>`, L8-13)
Constructor:
- `post: (Runnable) -> Boolean` — must enqueue async; returning false means the queue
  is gone (looper quit).
- `remove: (Runnable) -> Unit`
- `merge: (T, T) -> T = { _, newer -> newer }` — default keeps only the newest value.
- `consume: (T) -> Unit` — applied on the UI thread when the drain runs.

Members:
- `private val lock = Any()` (L14); `pending: T?` (L15); `scheduled: Boolean` (L16).
- `private val drain: Runnable` (L17-26): under lock, takes pending, clears it and
  `scheduled`; then (outside the lock) `if (value != null) consume(value)`. Comment
  L24: consumption may re-enter `offer` — that must enqueue a **separate** drain
  (because `scheduled` was reset before consume runs).
- `fun offer(value: T)` (L28-39): under lock — `pending = pending?.let { merge(it, value) }
  ?: value`; if not scheduled, set scheduled and `post(drain)`; if post fails (false),
  drop the value and reset scheduled (L33-36).
- `fun cancel()` (L42-48): under lock — `remove(drain)`, clear pending, scheduled=false.
  KDoc L41: "Also remove the queued drain so navigation/renderer teardown cannot
  strand it."

## Invariants
- At most one drain Runnable in the Handler queue at any time (scheduled flag).
- At most one pending value; new offers merge into it.
- consume() runs at most once per scheduled drain; re-entrant offers during consume
  schedule a fresh drain (safe because scheduled was reset before consume).
- All state mutations under `lock`; offer() is callable from the JS bridge thread
  (a background thread), consume on the UI thread.
- If the Handler is dead (post returns false), the value is dropped rather than
  leaked.

## How MainActivity.kt uses it (two instances, L123-142)
1. `heartbeatUi = UiCoalescer<Heartbeat>` (L125-130), default merge (newest wins):
   - `Heartbeat(epoch, token)` data class (L123).
   - Offered from the JS bridge: `Bridge.playbackActive(token)` (L1344-1349) — page
     scripts call it repeatedly while video plays; token must match documentToken,
     else ignored.
   - Consume: if `value.epoch == pageEpoch && value.token == documentToken &&
     pageAvailable() && foreground` → `playbackHeartbeatAt = now` (L128-129).
   - Why coalesce: playback heartbeats fire in bursts from JS; only the latest
     timestamp matters. `playbackHeartbeatAt` drives "is playing" detection: the idle
     tick (L173-179) treats the page as playing within 12 s of the heartbeat (or
     audio.isMusicActive), which suppresses idle dimming; `liftPointerIfPlaying`
     (L2200-2206) uses the same 12 s window to lift the hover cursor during playback.
2. `blockedUi = UiCoalescer<BlockedBatch>` (L131-142) with a **counting merge**:
   - `BlockedBatch(epoch, what, count=1)` (L124).
   - merge (L133-136): different epoch → newest wins; same epoch → sum counts
     (coerce to Int.MAX_VALUE).
   - Offered from the JS bridge: `Bridge.blocked(what)` (L1420-1423) — popup_guard.js
     reports each blocked overlay/popup; `what` truncated to 160 chars, default
     "a popup".
   - Consume (L137-141): if epoch still current and page available →
     DiagnosticLog "popup_blocked" {what, count} + `noteBlocked(what, count)`
     (L1498-1501: "popup_guard_action" log, blockedCount += count, toast throttling).
   - Why coalesce: a malicious/buggy page can spam window.open or overlays; without
     coalescing each event would hit log + toast + counter on the UI thread.
3. Cancellation: `resetDocumentWork` (L2317-2323) calls `heartbeatUi.cancel()` and
   `blockedUi.cancel()` on every page start / document rotation — a stale drain from
   the old document must not fire into the new one (epoch/token checks in consume are
   the second line of defense).

## What breaks if changed/removed
- Removing coalescing (calling consume inline per offer): a burst of JS bridge calls
  (e.g. killOverlays loop in popup_guard.js, or a page spamming playbackActive) would
  hammer the UI thread with log writes/toasts — jank on the Fire TV, and in the
  blocked case unbounded toast/log growth.
- Changing blockedUi's merge to newest-wins: the "blocked this session" counter and
  DiagnosticLog counts undercount real blocked events during bursts.
- Removing cancel() from resetDocumentWork: a drain queued by the old document could
  fire after the new page started; the epoch/token guards mostly catch it, but
  blockedUi's consume only checks epoch — a same-epoch race could log against the
  new page.
- Making post() invoke inline (violating the KDoc contract): offer() from the JS
  bridge thread would run consume() off the UI thread → touching UI-thread state
  (playbackHeartbeatAt is @Volatile so it survives, but noteBlocked/toasts would not).
- Removing the post-failure drop (L33-36): after the looper quits, pending would be
  stranded and scheduled stuck true → all later offers silently dropped.

# Watchdog.kt (91 lines)

## Purpose
Out-of-process safety net for native GPU crashes. The PowerVR driver SIGSEGV happens on
Chromium's in-process GPU thread inside libIMGegl.so — no Java handler in the app can
see it; the whole main process just dies. Nothing inside a dead process can restart it,
so `WatchdogService` runs in a **second process** (`:watchdog`, AndroidManifest.xml L28)
and watches the main process's binder. When that binder dies while the app was in use
(foreground, not a clean exit), it relaunches MainActivity, which reopens the page it
was on (`lastCrashUrl`).

## Public API
- `WatchdogService : Service` (L24-80) — declared in manifest with `android:process=":watchdog"`,
  `exported=false` (manifest L28). Not bindable: `onBind` returns null (L35).
  - `onCreate` (L28-31) → `watchMainProcess()`.
  - `onStartCommand` (L33) → `START_STICKY` (system restarts the service if killed).
- `MainProcessService : Service` (L83-91) — lives in the **main** process (manifest L29,
  no process attr). `onBind` returns a fresh `Binder()` (L85); `onUnbind` calls
  `stopSelf()` (L87-90). Its only job: hand out a binder whose death is observable.

## Detection / restart mechanism (state machine)
1. MainActivity.startWatchdog() (L662-672): `startService(WatchdogService)` (auto-creates
   the :watchdog process) and `bindService(MainProcessService, BIND_AUTO_CREATE)` with a
   no-op connection stored as `mainProcessConnection`.
2. WatchdogService.watchMainProcess() (L37-49): binds to MainProcessService in the main
   process; on `onServiceConnected` stores the binder in `watched` and calls
   `service.linkToDeath({ mainProcessGone() }, 0)` (L43). `onServiceDisconnected` clears
   `watched` (L46). Re-entrancy guard: `if (watched != null) return` (L38).
3. When the main process dies, the binder's death recipient fires → `mainProcessGone()`
   (L51-61):
   - `watched = null`
   - reads prefs via `flags()` (L79): `getSharedPreferences("settings",
     MODE_MULTI_PROCESS)` — MODE_MULTI_PROCESS (deprecated, @Suppress L78) so a flag
     written by the main process is read fresh in the other process.
   - **Skip if `cleanExit` is true** (default true, L54) — user exited via Menu>Exit /
     Back>Exit; not a crash.
   - **Skip if `wasForeground` is false** (L55) — system killed a backgrounded app.
   - **Skip if `allowRestart(f)` is false** (L56) — crash-loop guard.
   - Sets `watchdogRestart=true` (L57) and `startActivity(MainActivity,
     FLAG_ACTIVITY_NEW_TASK)` (L59).
4. Crash-loop guard `allowRestart` (L64-75): sliding 10-minute window
   (`watchdogWindowStartedAt`, 600_000 ms); at most **3 restarts** per window
   (`watchdogRestartCount`). First restart in a window starts the window and sets count=1.
5. MainActivity side of the handshake:
   - onCreate L489-490: reads `watchdogRestart` + `lastCrashUrl` **before** clearing
     crash state; L498: writes `cleanExit=false`, `wasForeground=true` (commit) so a
     subsequent death is interpreted as a crash; L499: `startWatchdog()`.
   - L617-627: if `watchdogRestart` was set → remove it, toast "Restarted after the
     crash; reopened the page", `navigateUrl(watchdogUrl)` if it starts with "http",
     else `showStartPage(true)`.
   - onPageStarted L930-932: persists `lastCrashUrl` (commit) on every http page start —
     "a native GPU crash kills the process before any handler runs, so the page has to
     be recorded here for the watchdog restart to reopen it."
   - onPause L658: `wasForeground=false` (commit); onResume L676: `wasForeground=true`.
   - onDestroy L696-703: `if (isFinishing) cleanExit=true` (commit); unbinds
     mainProcessConnection.

## Invariants
- The watchdog only acts on: death + cleanExit==false + wasForeground==true +
  under the 3-restarts/10-min limit.
- `cleanExit` defaults to true (L54) — a fresh install / never-launched app won't
  restart.
- All prefs traffic uses `commit()` on the MainActivity side (synchronous) so the flags
  are durable before a crash can occur; the watchdog side uses `apply()`.
- The watched binder is a plain `Binder()` — no IPC methods; only its liveness matters.

## What breaks if changed/removed
- Removing WatchdogService or the :watchdog process: native GPU crashes (the dominant
  failure mode on this device) leave the app dead; no recovery at all.
- Changing the cleanExit/wasForeground contract (who writes them, defaults): either
  clean exits get "restarted" (watchdog relaunches after user quit) or real crashes are
  missed (backgrounded kill vs crash ambiguity).
- Removing `lastCrashUrl` persistence in onPageStarted: restart lands on the start page
  instead of the crashed page (L620-627 falls back to showStartPage).
- Changing allowRestart limits: <3 → users stuck after 2 crashes; >3 or no window →
  crash-loop risk (PowerVR crash on a specific page could thrash the device).
- Removing MODE_MULTI_PROCESS: on Android 7.1 the :watchdog process may read a stale
  in-memory prefs cache → wrong cleanExit/wasForeground decisions.
- Removing MainProcessService or the bind in startWatchdog: the watchdog has nothing to
  linkToDeath → never fires.

# IdleDimPolicy.kt (11 lines)

## Purpose
Pure decision logic for the screen-dim-on-idle feature. Decoupled from the Activity so the timeout rule is testable in isolation.

## API
- `TIMEOUT_MS = 20 * 60 * 1000L` (L4) — 20 minutes.
- `activity(now)` (L6) — records a user-activity timestamp.
- `shouldDim(now, playing)` (L7-10):
  - **If video is playing: never dim** — and it *also* refreshes `lastActivity` (L8), so the 20-minute clock restarts after playback stops. This is the key invariant: a long movie never dims, and the idle clock does not accumulate during playback.
  - Otherwise: dim when `now - lastActivity >= TIMEOUT_MS`.

## Call sites (MainActivity)
- `idleDim` instance field; `noteUserInput()` calls `idleDim.activity(...)` on every key/tap.
- A periodic check (idle timer in the Activity) calls `shouldDim(SystemClock.elapsedRealtime(), playbackActive)` and toggles the dim overlay (`dimOverlay` alpha) + logs `idle_dim`.

## What breaks if changed
- Removing the `playing` branch → screen dims mid-movie (user-visible regression).
- Changing `TIMEOUT_MS` → changes when the dim kicks in; no other code depends on the constant.
- The policy is stateless except `lastActivity`; it is NOT thread-safe (single main-thread owner — keep it that way).

# Panels.kt (253 lines)

## Purpose
The native UI panel framework: three panel view classes used by MainActivity for all on-screen chrome (menus, bookmark list, diagnostics text). All panels are `FrameLayout` subclasses that live in the activity's root layout, start `GONE`, and are shown/hidden imperatively. They are **not** a WebView — they are real Android views, which is why remote focus works on them without the JS focus machinery.

## Shared conventions (all three panels)
- `visibility = View.GONE` at construction (L21, L89, L209).
- `setBackgroundColor(Colors.DIM)` — a dim scrim over the page behind the panel.
- Inner card/drawer uses `Colors.PANEL` background; `ui.rounded(...)` for rounded corners on the centered panels.
- **Focus rule (critical invariant):** every `show()` posts a `requestFocus()` to the initial target, guarded by `if (visibility == View.VISIBLE && isInside(target, this))` (L64, L180). The guard exists because the posted runnable can run after the panel was already hidden (fast key mashing) — requesting focus on a GONE/detached view would steal focus into a dead view and the remote would appear "stuck". Any new panel must keep this guard.
- `scroll.isFocusable = false` on every ScrollView (L36, L107, L223) — the scroll view must never take remote focus; only the buttons/rows do.

## Classes

### `MenuItem(label, action)` (L12)
Data class for a menu entry. `action` runs after the panel auto-hides.

### `MenuPanel` (L15-71) — left-hand drawer
- Layout: 380-unit-wide drawer pinned `Gravity.START` (L42), title (22sp bold, max 2 lines) + ScrollView of buttons (min height 44u, 6u top margin).
- `show(title, items, focusIndex = 0)` (L45-66):
  - Rebuilds the button list every show (L47 `removeAllViews`) — panels are cheap to rebuild; do not try to cache row views.
  - Each button: `setOnClickListener { hide(); item.action() }` (L52-55) — **the panel hides itself before the action runs**. If an action re-shows a panel (e.g. bookmark options → move-to-folder), the new show wins because it posts focus after the hide.
  - `focusIndex` is clamped to `[0, buttons.size-1]` (L63).
- `hide()` (L68-70).

### `ListRow(title, subtitle, onOpen, onMore?)` (L73-78)
Data class for a list entry; `onMore` null → no "Options" button.

### `ListPanel` (L81-186) — centered card list (bookmarks/folders)
- Layout: centered card, width `min(screenW-60u, 700u)`, height `min(screenH-50u, 470u)` (L119-121); title (24sp, 1 line), optional hint line (13sp, GONE by default), scrollable rows, footer button row.
- Row layout (L132-154): horizontal `LinearLayout` — main tile (weight 1, `ui.tile(title, subtitle, 17, 12)`) + optional 100u-wide "Options" button.
- Empty state: if `rows.isEmpty() && emptyText.isNotEmpty()` → a muted text row (L156-160).
- Footer buttons: equal-weight (0, 44u, weight 1), 8u left margin between (L163-171).
- `show(title, rows, emptyText, footerItems, focusRow, hint?)` (L124-181):
  - Initial focus target (L175-179): first the focused **row's main tile** (`focusRow` clamped), else first **footer button**, else null.
  - Same guarded `post { requestFocus() }` pattern (L180).
- MainActivity drives row navigation (up/down moves focus between `mains`), paging (50 rows/page), and moving-mode; the panel itself is dumb — it only renders and forwards clicks.

### `isInside(v, ancestor)` (L189-196)
Walks `v.parent` up the view tree; true when `v === ancestor` or any ancestor is. Identity comparison (`===`), not equality — views are singletons in this app. Used by the focus guards above.

### `View.shown()` (L199)
`visibility == View.VISIBLE` helper for readable call sites.

### `TextPanel` (L202-253) — scrollable text card (Settings > Diagnostics)
- Layout: centered card `min(screenW-60u, 780u)` × `min(screenH-40u, 490u)`; title (24sp), scrollable body (15sp subtle), fixed hint "Up / Down: scroll        Select or Back: close" (L228).
- `show(title, text)` (L238-243): resets scroll to top (L241) — a long diagnostics report always starts at the top.
- `scrollStep(direction)` (L250-252): `smoothScrollBy(0, direction * ui.u(110))` — 110u per step; MainActivity.onTextPanelKey calls it for DPAD_UP/DOWN while the panel is open.
- No focusable children — the panel itself is the focus target; key handling is entirely in MainActivity (that's why the hint says "Select or Back: close").

## What breaks if changed
- Removing the `isInside`/`VISIBLE` focus guards → focus can land on a hidden panel's view; remote navigation then appears dead (the "stuck menu" symptom). This has been a real bug class before.
- Making a ScrollView focusable → remote up/down scrolls the list instead of moving button focus (or vice versa depending on platform focus logic); the current split (scroll not focusable, rows focusable, TextPanel keys handled in MainActivity) is deliberate.
- Changing "hide before action" in MenuPanel (L52-55) → actions that open another panel fight the old panel for focus.
- Panel sizes use `ui.u()` (density-scaled units) — hardcoding px breaks on other resolutions.

# PointerTap.kt (22 lines)

## Purpose
Builds **real `MotionEvent`s** for the WebView — not JavaScript-dispatched events. The class comment states the design rule: "Real WebView input, not JavaScript-dispatched events. Keep touch behavior as the default."

This matters because Chromium's WebView treats `dispatchTouchEvent`-delivered `MotionEvent`s as trusted input: they trigger real `click` events, focus changes, and site gesture handlers. `element.click()` / synthetic DOM events from JS are untrusted (`isTrusted === false`) and are rejected by many sites (payment flows, Cloudflare challenges, some video players' click-to-play).

## API
- `PointerTap.event(downAt, at, action, x, y, mouse)` (L8-21) → `MotionEvent`:
  - Single pointer, `id = 0`.
  - `toolType`: `TOOL_TYPE_MOUSE` if `mouse` else `TOOL_TYPE_FINGER` (L11).
  - `pressure`: 0 on UP/CANCEL, 1 otherwise (L15) — matches real touch semantics.
  - `buttonState`: `BUTTON_PRIMARY` only for a mouse DOWN (L19).
  - `source`: `SOURCE_MOUSE` vs `SOURCE_TOUCHSCREEN` (L20) — the field Chromium's input pipeline uses to route the event.
  - `downAt` vs `at` are separate timestamps so a DOWN/UP pair has a realistic duration (MainActivity uses a ~70 ms DOWN→UP gap in `sendTap`/`finishPendingTap`).

## Call sites
- MainActivity `sendTap`/`finishPendingTap` (the pointer/tap subsystem, ~L1875-2232): builds DOWN then UP via this helper and calls `webView.dispatchTouchEvent(...)`.

## What breaks if changed
- Switching to `evaluateJavascript("el.click()")` → untrusted events; click-to-play and bot-protected flows stop working. This is a **protected design decision**.
- Changing `source`/`toolType` handling → Chromium may drop or misroute the event (e.g. mouse events with touch source).
- The helper is allocation-per-event; do not add per-event logging here (hot path during drag/scroll).

# SeekRepeatPolicy.kt (20 lines)

## Purpose
Converts remote key-repeat streams into seek step sizes. The class comment states the design rule: "Remote repeat frequency varies by model. Use elapsed hold time, not repeat count." Different Fire TV remotes generate key repeats at different rates; counting repeats would make seek speed remote-dependent. Measuring wall-clock hold time makes the seek rate constant across remotes.

## API
- `reset()` (L5) — clears state (called on key-up / new key).
- `step(code, repeat, downTime, eventTime)` (L9-19) → step size in seconds (or 0 = ignore):
  - `repeat == 0` (initial press): records `key`, `started = downTime`, `lastStep = eventTime`; returns **10** (first press seeks 10 s).
  - Repeat events:
    - Returns 0 (ignore) if the key changed, `downTime` changed (a new physical press), or the event time went backwards (L14) — guards against stale/interleaved repeats.
    - `held = eventTime - started`.
    - Returns 0 while `held < 600 ms` (debounce: no seek until the key has been held 0.6 s) or the gap since the last step is `< 900 ms` (rate limit: at most one step per 0.9 s) (L16).
    - Otherwise returns **60** if `held >= 2500 ms` (fast seek after 2.5 s hold), else **30** (L18).
  - On an accepted step, `lastStep = eventTime` (L17).

## Behavior summary
| Hold time | Seek steps |
|---|---|
| initial press | +10 s |
| 0.6 s – 2.5 s | +30 s per ~0.9 s |
| ≥ 2.5 s | +60 s per ~0.9 s |

## Call sites
- MainActivity seek-key handling (DPAD_LEFT/RIGHT long-press in the video/transport path): calls `step()` on each repeat, applies the returned seconds via the video seek command, and `reset()` on key-up.

## What breaks if changed
- Using repeat count instead of elapsed time → seek speed varies by remote model (the original bug this class fixes).
- Changing the 600 ms / 900 ms / 2500 ms thresholds → changes feel; the ±120 s coalescing in `videoSeek` (MainActivity) is the downstream guard against seek flooding — keep both in mind together.
- The `downTime` comparison is what distinguishes "still holding" from "pressed again" — removing it makes a re-press continue the fast-seek state.

