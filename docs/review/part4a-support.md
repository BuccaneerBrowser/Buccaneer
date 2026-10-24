# DiagnosticLog.kt (453 lines)

Kotlin `object` (singleton), package `com.buccaneer.browser`. Opt-in, bounded, background-thread diagnostics for the app. Routine events NEVER do disk I/O on the caller thread; only `recordCritical` is synchronous (reserved for imminent crashes).

## Constants & state (L22–54)
- L23 `PREFS = "diagnostics"` — SharedPreferences file name.
- L24 `KEY_ENABLED = "enabled"`, L25 `KEY_SESSION = "sessionId"` — pref keys.
- L26 `FILE_NAME = "diagnostic_session.jsonl"` — main log, in `context.filesDir`.
- L27 `CRITICAL_FILE = "diagnostic_critical.jsonl"` — separate crash file, `context.filesDir`.
- L28 `MAX_BYTES = 2 MiB` — rotation ceiling for the session file.
- L29 `KEEP_BYTES = 1 MiB` — bytes kept after rotation.
- L30 `FORMAT_VERSION = 3` — written into export `session.json`.
- L31 `stateLock` — guards session/enabled/generation/pending/writerSession/flushScheduled.
- L32 `criticalLock` — guards critical-file writes (shared with the writer so a stale crash can't resurrect a cleared session, L283).
- L33 `pending = ArrayBlockingQueue<Entry>(256)` — bounded queue; overflow = drop.
- L34 `dropped = AtomicInteger` — count of dropped non-important entries, flushed as an `events_dropped` line (L272–274).
- L35–37 `writer` — single-thread scheduled executor, daemon thread named `"Buccaneer-diagnostics"`.
- L38 `@Volatile enabled` (default false), L39 `@Volatile sessionId`, L40 `@Volatile generation` (monotonic; incremented by startNewSession/clear so stale writer batches are discarded), L41 `@Volatile appContext`.
- L42 `initialized` (under stateLock), L43 `writerSession` (session id currently on disk; writer-thread + stateLock).
- L44–45 `lastMemorySampleAt` — writer-thread-only; memory sampling piggybacks on existing events, no timer (comment L44).
- L46 `criticalSession`, L47–49 burst dedupe: `lastBurstEvent`, `lastBurstAt`, `burstCount` (all under stateLock).
- L50–51 `data class Entry(session: String, generation: Long, ts: String, elapsed: Long, event: String, raw: String)` — `raw` is the JSON string of the details object.

## Flush scheduling (L53–70)
`scheduleFlushLocked()` (must be called under stateLock): if `flushScheduled` already true, return; else set it and schedule `flushPending()` after **100 ms** on the writer executor; in `finally`, under stateLock, clear the flag and re-schedule if `pending` is non-empty (covers entries queued during the write, L64–65). On exception, reset flag. Comment L53: disabled diagnostics must not wake CPU 10×/sec — scheduling is work-driven.

## Public API

### `fun initialize(context: Context)` (L72–90)
Idempotent (L75–76). Under stateLock: sets `appContext`, reads prefs → `sessionId`, `enabled`, mirrors into `writerSession`/`criticalSession`. Any Throwable → `enabled = false` (L84). After the lock: if enabled and sessionId blank → `startNewSession(app, "recovered")` (L87); else records `"process_start"` with `metadata(app)` (L88). **Called from MainActivity.kt:479** (onCreate).

### `fun isEnabled(context: Context): Boolean` (L92–93)
Returns the volatile `enabled` (context param unused). Used at MainActivity 1034, 2148, 3683, 3785 (status text), and exposed to JS as `isDiagnosticsEnabled()` at MainActivity 1301.

### `fun startNewSession(context: Context, reason: String = "manual"): String` (L95–115)
Generates `UUID.randomUUID()` id (L97). Builds details on the calling thread: `{reason: reason.take(80), session: id, metadata: metadata(app)}` (L99–100) — metadata collection deliberately stays on caller (comment L98). Under stateLock: bumps `generation`, clears `pending`, resets `dropped`, sets `sessionId`, `enabled = true`, resets burst counters, persists enabled+session to prefs (L110–111), enqueues `"session_start"` as important (L112). Returns id. Called from MainActivity 3692, 3700 (settings toggle) and internally by `initialize` ("recovered").

### `fun stop(context: Context)` (L117–126)
No-op if disabled. Under stateLock: flushes any pending burst via `enqueueBurstLocked()`, enqueues `"session_stop"` (important), sets `enabled = false`, persists disabled to prefs. Called from MainActivity 3688, 3708.

### `fun clear(context: Context)` (L128–152)
Under stateLock: bumps `generation` (captures `clearGeneration`), clears pending, resets dropped, `enabled = false`, `sessionId = null`, resets burst state, removes session key + disables in prefs. Then on the writer thread under criticalLock: only if generation unchanged, deletes the session file, nulls `writerSession`, deletes critical file, nulls `criticalSession` (L141–150). Generation check prevents a concurrent `startNewSession` from having its files deleted. Called from MainActivity 3715 (clear-data UI).

### `fun hasData(context: Context): Boolean` (L154–157)
True if pending non-empty, or session file length > 0, or critical file length > 0. Throws → false. Used at MainActivity 3732 (export button gating).

### `fun currentSessionId(context: Context): String` (L159–160)
`sessionId ?: ""`. Used at MainActivity 3786 (status, truncated to 12 chars).

### `fun record(context, event: String, details: JSONObject? = null)` (L162–181)
Main entry. Fast path: `if (!enabled) return` (L163) — no lock on the off path. Under stateLock (double-checked enabled, L165):
- `cleanEventName(event)` (L167; trims, max 120 chars, empty → "event", L440).
- `elapsed = SystemClock.elapsedRealtime()` (L168).
- **Burst dedupe** (L169–175): "noisy" events = `tmdb_image_blocked`, `subresource_error`, `console`, `popup_blocked` (L170–171). If same noisy event within 1000 ms of `lastBurstAt` → `burstCount++` and return (no enqueue).
- Else `enqueueBurstLocked()` (L176) flushes the previous burst as an `event_burst` entry, updates `lastBurstEvent`/`lastBurstAt` (L177–178), enqueues the event (non-important, L179).

### `fun record(context, event: String, details: String?)` (L183–193)
String overload. Parses `details` into JSON with a safety budget:
- null/blank → `{}`.
- length > 16384 → `{omitted: "Oversized diagnostic payload", characters: N}` (L185–186).
- `!JsonInputBudget.allows(details)` → `{omitted: "Diagnostic nesting exceeded safety budget", characters: N}` (L187–188). `JsonInputBudget` is a separate internal object (JsonInputBudget.kt, also used by MainActivity 58/73/1360/1479 and BookmarkStore 249/304) that bounds nesting depth/size of JSON parsed from untrusted input.
- valid → `JSONObject(details)`; parse failure → `{message: details.take(1000)}` (L191).
Delegates to the JSONObject overload (L192).

### `fun recordCritical(context, event: String, details: JSONObject? = null)` (L285–299)
Synchronous, bounded, best-effort path for imminent crashes (comment L282–284). If disabled → return. Bounding: raw JSON string > 32768 chars → `{omitted: "Oversized critical payload"}` (L288). Under criticalLock: re-checks enabled, `entry.generation == generation`, `entry.session == sessionId` (L292); if session changed or critical file > 256 KiB, deletes it (L294); appends one line (L296). All exceptions swallowed. Called from MainActivity 1099 (`render_process_gone`) and 3929 (`app_error`).

### `fun exportAsync(context, openOutput: () -> OutputStream, completion: (Throwable?) -> Unit)` (L301–313)
Runs on the writer thread: flushes burst + pending (L307–308), then `openOutput().use { exportZip(app, it) }` (L309) — the caller-supplied stream (e.g. a file output from an ACTION_CREATE_DOCUMENT intent) is opened and written on the writer thread; `completion(null)` on success, `completion(e)` on any Throwable. Called from MainActivity 3834 (export flow, after `diagnostic_export_started` at 3833).

### `fun metadata(context: JSONObject)` — actually `fun metadata(context: Context): JSONObject` (L350–385)
Public. Fields: `appVersion` (L351, via `appVersion()` L446–448, versionName or "?"), `manufacturer`, `model`, `androidRelease`, `api` (SDK_INT), `compileSdk: 34` (hardcoded, L356), `targetSdk` (L358–360, -1 on failure), `webViewProvider` (L361, via `webViewProvider()` L449–452: `WebViewCompat.getCurrentWebViewPackage` → "pkg/version" or "unknown"), then in a try (L362–383): `deviceTotalMemMb`, `deviceAvailMemMb`, `deviceLowMemory`, `deviceLowMemoryThresholdMb` (ActivityManager.MemoryInfo, /1048576), `javaMaxHeapMb`, `javaFreeHeapMb`, `javaAllocatedBytes`, `javaUsedBytes`, `javaHeadroomBytes`, `nativeHeapAllocatedBytes` (Debug.getNativeHeapAllocatedSize), `supportedAbis` (comma-joined), `process64Bit` (API ≥ 23, L382). Finally `userAgent` truncated to 500 chars (L384).

### `fun safeUrl(raw: String?): String` (L387–395)
Public. null/blank → "". Parses `Uri`; non-http(s) scheme → just the scheme or "other" (L392); http(s) → `"scheme://host/"` (path/query/fragment stripped) truncated to 2048 (L393); parse failure → "invalid-url". This is the URL redaction primitive used everywhere in MainActivity (e.g. 692, 869–870, 935, 948, 1024, 1039, 1063, 1075, 1098–1102, 1238, 1273, 1519, 2148–2155 area, 3694, 3702, 3797, 3804).

## Private machinery

### `enqueueBurstLocked()` (L195–199)
If `burstCount > 0`, enqueues `event_burst` with `{sourceEvent, additionalCount}` (non-important), resets count.

### `enqueueLocked(event, details, important: Boolean)` (L201–215)
`raw = details.toString()`; if > 32768 chars → bounded `{omitted: "Diagnostic payload exceeded 32 KiB", characters: N}` (L203–204). Builds `Entry(sessionId ?: "", generation, now(), elapsedRealtime, event, bounded)` (L205). If non-important and `pending.remainingCapacity() <= 8` → increment `dropped`, skip (L206–209). If `pending.offer` fails: increment dropped; if important, `pending.poll()` (evict oldest) and re-offer (L210–213). Then `scheduleFlushLocked()` (L214).

### `line(entry: Entry): String` (L217–224) — **JSONL line format**
Re-parses `entry.raw` (fallback `{message: raw.take(1000)}` on parse failure, L218–219), then emits one JSON object per line:
```json
{"ts": "<ISO-8601 UTC ms>", "elapsedMs": <SystemClock.elapsedRealtime>, "session": "<uuid>", "event": "<name>", "data": <sanitized details>}
```
`ts` from `now()` (L445): `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)` with UTC TimeZone. `data` is the result of `sanitize()` — **every entry's data is redacted at write time**, not just export.

### `flushPending()` (L226–280) — writer thread only
- `appContext ?: return` (L228).
- `pending.drainTo(batch, 256)` (L230); filter to `generation == generation` (L231) — stale batches from a previous session are dropped.
- If empty → return (L232).
- **Memory sampling** (L233–256): if enabled, ≥ 15 s since last sample, and batch contains `page_started`, `media_health`, or `session_start` (L234–235): collect `Debug.MemoryInfo` (PSS, privateDirty), Runtime heap stats, native heap; emit a `process_memory` entry with fields `sampleScope: "host-process-not-dedicated-gpu"`, `sampleElapsedMs`, `processPssKb`, `processPrivateDirtyKb`, `javaAllocatedBytes`, `javaUsedBytes`, `javaHeadroomBytes`, `nativeHeapAllocatedBytes` (L244–251), prepended to the batch (L253–254). Comment L238: PSS collection is expensive, never on UI/bridge thread. Any Throwable swallowed (L255).
- Under criticalLock (L257): re-check `current.first().generation != generation` → return (L258). If `writerSession != id` (session changed): delete old target file, set `writerSession`; if critical file belongs to a different session and generation still matches, delete it too (L261–268).
- `rotateIfNeeded(target)` (L269) before and after append (L277).
- Appends all lines as UTF-8 (L270–276); if `dropped` > 0, appends an `events_dropped` line with `{count}` (L272–274).
- `target.parentFile?.mkdirs()` (L275) before append.
- Whole body wrapped in try/catch swallowing everything (L279): "diagnostics must never interrupt browsing".

### `rotateIfNeeded(target: File)` (L337–343)
If file ≤ MAX_BYTES (2 MiB) → nothing. Else read all bytes, keep the last KEEP_BYTES (1 MiB), find the first `\n` (byte 10) at/after the cut point via `indexOfFrom` (L345–348, linear scan), and rewrite the file starting just after that newline (or at `start` if no newline found). Net effect: session file stays ≤ ~1 MiB + one line.

### `exportZip(context, output)` (L316–335) — writer thread only (comment L315)
`exportedSession = writerSession ?: sessionId ?: ""`. Writes a zip with 4 entries via `addEntry` (L342–344):
1. `session.json` — `{formatVersion: 3, session, exportedAt, loggingEnabled}` pretty-printed (L319–322).
2. `events.jsonl` — full bytes of the session file (empty if missing) (L323–324).
3. `critical.jsonl` — critical file bytes, but only if `criticalSession == exportedSession` (L325–329).
4. `README.txt` — fixed text explaining batching, queue capacity 256, `event_burst`/`events_dropped` semantics, critical file, and redaction scope (L330–333).

## Redaction pipeline

### `sanitize(input: JSONObject): JSONObject` (L397–421)
Recursive. Per key (lowercased, L402):
- **Redacted to `"[redacted]"`** (L403–408) if key contains: `cookie`, `authorization`, `token`, `signature`, `password`, `secret`, `apikey`, `api_key`, or equals `hash`, `query`, `fragment`.
- String values (L411–414): if key is `url`/`src`/`href` or contains `url` → `safeUrl(value)`; otherwise `redactText(value)`.
- JSONObject → recurse; JSONArray → `sanitizeArray` (L423–435: objects recurse, arrays recurse, strings starting with http(s):// → `safeUrl`, other strings → `redactText`); other values pass through (numbers, booleans, null).

### `redactText(value: String): String` (L437–438)
Truncates to 2000 chars, then replaces every `https?://[^\s"<>\n]+` (case-insensitive) match with `safeUrl(match)`. So free-text URLs keep only scheme://host/.

### `cleanEventName(raw)` (L440) — trim, take(120), empty → "event".

## Thread-safety summary
- `enabled`, `sessionId`, `generation`, `appContext`, `flushScheduled` are `@Volatile`; all mutations under `stateLock`.
- `pending` is a thread-safe queue; `dropped` is atomic.
- File writes: session file under `criticalLock` (shared with recordCritical and clear, so a stale crash write can't resurrect a cleared session — L283); critical file under `criticalLock`.
- `lastMemorySampleAt`, `writerSession`, `criticalSession`, burst counters: writer-thread-only or under stateLock as noted.
- Writer is a single-thread executor → all flushes/exports serialize.
- Generation counter is the core staleness guard: stale batches (L231, L258), stale critical writes (L292), and stale clear operations (L143) are all discarded.

## How MainActivity.kt uses it (89 references)
- **Lifecycle**: `initialize` at 479 (onCreate); `record("process_start")` internally; `activity_pause` 640, `activity_resume` 675, `low_memory` 692 (with `safeUrl(lastPageUrl)`), `activity_destroy` 697.
- **WebView config**: `webview_config` 751 (JSONObject of settings); `ui_scripts_install_failed` 809, `scripts_installed` 811 (mode=document_start, frames=all), `scripts_install_failed` 815.
- **Navigation**: `redirect_blocked` 868 (initiator/target via safeUrl), `prior_load_start_ignored` 906, `page_started` 934 (url), `page_commit_visible` 948, `image_firewall` 959, `flixer_image_firewall` 967, `flixer_render_reset` 976, `page_error`/`subresource_error` 1015 (main-frame vs subresource), `http_error` 1023, `media_manifest_request` 1038 (gated by `isEnabled` at 1034), `tmdb_image_blocked` 1062, `page_finished` 1074, `user_navigation`/`automatic_navigation` 1519, `navigation_cleanup_dispatched` 1591, generic reason record 1611.
- **Crash/critical**: `render_process_gone` via `recordCritical` 1099 (paired with `CrashLog.record` at 1098), `app_error` via `recordCritical` 3929.
- **Media/fullscreen**: `fullscreen_show` 1159, `fullscreen_hide` 1169, `media_owner_started` 1455 / `media_owner_stopped` 1467 (frame id truncated 120), `css_fullscreen_on/off` 1327.
- **Popups**: `popup_blocked` 139 (what/count), `popup_requested` 1186, `popup_webview_created` 1209, `popup_forwarded` 1238, `popup_webview_destroyed` 295, `popup_guard_action` 1499.
- **Console**: `console` 1270 (message + source via safeUrl) — one of the 4 burst-deduped noisy events.
- **JS bridge**: `isDiagnosticsEnabled()` exposed at 1301; JS→native record at 1307 (`DiagnosticLog.record(this@MainActivity, e, details)`); `setDiagnosticLogging` JS call at 3727.
- **Input**: `editable_clicked` 1428, `key` 1735, `tap_dispatched` 2143 (x/y/w/h), `tap_stack` 2155 (gated by isEnabled 2148), `pointer_lifted` 2211, `edit_result` 2857.
- **Settings**: `setting_popup_guard` 3187, `setting_desktop` 3203, `storage_permission` 3259, `dark_mode_applied` 3576/3582/3587, `setting_autoplay` 3611, `setting_low_quality` 3619, `setting_exclusive_video` 3626, `setting_software_rendering` 3633.
- **Diagnostics UI**: toggle at 3683–3708 (`logging_disabled`/`stop` vs `startNewSession`/`diagnostic_enabled`), `clear` 3715, `hasData` gate 3732, status text `isEnabled` + `currentSessionId().take(12)` at 3785–3786, `diagnostic_snapshot_requested` 3797, `diagnostic_snapshot` 3804 (parses reportText as JSON), `diagnostic_export_started` 3833 + `exportAsync` 3834.

## What would break if changed/removed
- **Removing `initialize` (MainActivity:479)**: `enabled` stays false (prefs never read) → all `record` calls no-op, `isEnabled` false, JS bridge `isDiagnosticsEnabled()` false, export produces empty zip. App still runs — diagnostics are opt-in and best-effort by design.
- **Removing `record`**: 80+ call sites lose their only diagnostics channel; no crash forensics. `recordCritical` (1099, 3929) would still write to the critical file only if `enabled` is true — but without `initialize`/`startNewSession` it never becomes true.
- **Changing the JSONL schema** (L221–223): any external tooling that parses `diagnostic_session.jsonl` (the export zip `events.jsonl`) breaks. `formatVersion` is 3; bump it when changing fields.
- **Changing `safeUrl`** (L387–395): affects both the redaction guarantee and every `url`/`page` field in logs; weakening it (keeping paths/queries) would leak potentially sensitive data into shared exports — the README (L330–333) explicitly promises path/query/fragment redaction.
- **Changing `sanitize` key list** (L403–407): risk of leaking secrets (cookies/tokens) into exports.
- **Removing the generation guard** (L40, L231, L258, L292, L143): a slow writer batch from an old session could append to a new session's file, or `clear` could delete a new session's file.
- **Changing queue size 256 / burst window 1000 ms** (L33, L172): affects `events_dropped`/`event_burst` semantics documented in the export README (L330–333).
- **Removing `recordCritical`**: render_process_gone and app_error would have no synchronous record before process death — the watchdog (:watchdog process) restarts the app, but post-mortem evidence of the crash would be lost.
- **Removing `exportAsync`**: the export UI (MainActivity 3833–3834, gated by `hasData` 3732) fails; users can't share diagnostics.
- **Changing `metadata` field names** (L350–385): breaks any tooling that reads `session.json`/`session_start` metadata; low risk otherwise.
- **`JsonInputBudget.allows` dependency** (L187): if that internal object is removed, the string overload of `record` loses its nesting-depth guard against untrusted JS-supplied JSON (MainActivity 1307 forwards JS strings here) — a DoS vector (deeply nested JSON).
# Keypad.kt (331 lines)

`class Keypad(context: Context, private val ui: Ui = Ui(context)) : LinearLayout(context)` — an on-screen keyboard (letters/digits/symbols) driven by the Fire TV remote. Because the app suppresses the system IME (`TvWebView.onCheckIsTextEditor() = false` + `FLAG_ALT_FOCUSABLE_IM` at MainActivity:476), this widget is the *only* text input path. The selected key is tracked in this class (not with Android focus) so the WebView keeps focus and its text cursor while typing (header comment L11–24). `isFocusable = false` (L60) enforces this.

## Public API

### `interface Listener` (L27–33)
```kotlin
interface Listener {
    fun onText(s: String)     // a character or multi-char text inserted
    fun onBackspace()         // delete
    fun onClear()             // clear the whole buffer
    fun onGo()                // GO/ENTER action (submit / navigate)
    fun onClose()             // close the keypad
}
```
`var listener: Listener? = null` (L41). MainActivity installs `keypadListener` (MainActivity 447–467): `onText→typeText(s)`, `onBackspace→typeBackspace()`, `onClear→typeClear()`, `onGo→typeGo()`, `onClose→cancelKeypad()`.

### `fun open(title: String, goLabel: String)` (L92–103)
Shows the keypad: stores `goLabel` (text of the big action key: "GO", "ENTER", "OK"), sets title, resets `layer = 0` (letters), `shift = false`, `selRow = 1`, `selCol = 0`, rebuilds the grid via `build()`, clears the preview, sets `visibility = VISIBLE`. Called from MainActivity 2728 (`promptText` — used by `promptUrl` with goLabel "GO", MainActivity 2734–2738) and 2756 (`openPageKeypad`, goLabel "ENTER", title "Type into the web page").

### `fun close()` (L105–107)
`visibility = GONE`. Called from MainActivity 2941 (`closeKeypad()`) and 3060.

### `fun setPreview(text: String)` (L109–112)
Sets the preview box text via string resource `R.string.keyboard_preview` (a caret is appended by the resource). MainActivity calls it constantly: 2730 (initial buffer), 2864 ("(website did not accept input)"), 2873, 2882 (password → `•`.repeat(length)), 2892, 2902, 2912, 3016 (mirrors the page field's real contents, password masked), 3018 ("(tap a text field in the page first)"), 3020.

### `fun handleKey(event: KeyEvent): Boolean` (L114–151)
Called by MainActivity for **every** remote key press while the keypad is open (routing at MainActivity 1714: `keypad.shown() -> keypad.handleKey(event)`; the `shown()` helper is presumably an extension checking visibility). Returns `true` for all handled keys (L150), `false` only for unrecognized non-printable keys (L147). Logic per keyCode:
- **DPAD_LEFT/RIGHT** → `moveHorizontal(∓1)` (L118–119); **DPAD_UP/DOWN** → `moveVertical(∓1)` (L120–121).
- **DPAD_CENTER / ENTER / NUMPAD_ENTER** (L122–131): only on the first event (`rep == 0`). If the currently highlighted key is `Kind.GO` → `listener?.onGo()` (and records `DiagnosticLog.record(context, "keypad_go", {via: "enter"})`, L128 — the only DiagnosticLog call in this file); otherwise `press()` (types the highlighted key). Comment L125: Enter acts as GO when the cursor is on the GO key, matching the label.
- **MEDIA_REWIND / DEL** → `listener?.onBackspace()` (L132–133). Note: no `rep == 0` guard — hold-to-repeat delete works (header comment L19 "hold to repeat").
- **MEDIA_FAST_FORWARD** → `if (rep == 0) listener?.onText(" ")` (L134) — space.
- **MEDIA_PLAY_PAUSE / MEDIA_PLAY / MEDIA_PAUSE** → `if (rep == 0) listener?.onGo()` (L135–137) — the Play/Pause remote button is the primary GO trigger.
- **MENU** → `if (rep == 0) toggleLayer()` (L138) — letters ↔ symbols.
- **BACK** → `if (rep == 0) listener?.onClose()` (L139).
- **else** (L140–148): physical USB/BT keyboard passthrough — if `event.unicodeChar >= 32` and no Ctrl/Alt, `listener?.onText(String(Character.toChars(uc)))` and return true; else return false.

## Key grid layout

`private enum class Kind { CHAR, TEXT, SHIFT, SYM, ABC, SPACE, DEL, CLEAR, GO }` (L35). `KeyDef(label, kind, weight = 1)` (L37–39) with `value == label`.

**Letters layer** (`letterRows()`, L157–171), 5 rows:
1. `1234567890` (10 CHAR)
2. `qwertyuiop` (10 CHAR)
3. `asdfghjkl-` (10 CHAR)
4. `zxcvbnm./:` (10 CHAR)
5. `SHIFT` (SHIFT), `SYM` (SYM), `.com` (TEXT), `SPACE` (SPACE, weight 3), `DEL` (DEL), `CLEAR` (CLEAR), `GO` (GO, weight 2)

**Symbols layer** (`symbolRows()`, L173–195), 5 rows:
1. `!@#$%^&*()` (10 CHAR)
2. `-_=+[]{}\|` (10 CHAR)
3. `;:'",.<>/?` (10 CHAR)
4. `` ` `` (CHAR), `~` (CHAR), `www.` (TEXT, w2), `https://` (TEXT, w3), `.net` (TEXT), `.org` (TEXT), `.io` (TEXT)
5. `ABC` (ABC), `.com` (TEXT), `@` (CHAR), `SPACE` (SPACE, w3), `DEL` (DEL), `CLEAR` (CLEAR), `GO` (GO, w2)

Rows are rendered as horizontal `LinearLayout`s with `weightSum = 10f` (L211); each key gets `LayoutParams(0, MATCH, weight)` with gap margins `ui.u(3)` (L207, L216–217); row height `ui.u(40)` (L221). `keyViews: ArrayList<ArrayList<TextView>>` mirrors `rows` (L53). SHIFT key is tinted `Colors.GREEN` when active (L215, L236).

**Kind semantics** (from `press()`, L302–326):
- `CHAR`: insert `label`; if `shift` is on and the char is a letter, insert uppercase and **auto-reset shift** + `refreshLabels()` (L306–314).
- `TEXT`: insert the whole multi-char label (`.com`, `www.`, `https://`, `.net`, `.org`, `.io`) (L315).
- `SPACE` → `onText(" ")` (L316); `DEL` → `onBackspace()` (L317); `CLEAR` → `onClear()` (L318); `GO` → `onGo()` (L319).
- `SHIFT` → toggle `shift`, `refreshLabels()` (L320–323).
- `SYM`/`ABC` → `toggleLayer()` (L324).

## State & selection

- `layer` (0 letters / 1 symbols, L47), `shift` (L48), `selRow`/`selCol` (L49–50), `goLabel` (L51), `rows` (L52).
- `select(r, c)` (L243–253): clamps into bounds, un-selects old key view, sets `isSelected = true` on the new one (selection styling comes from the TextView selector, not focus).
- `centerOf(row, col)` (L255–259): weighted center of a key in units of weight (sum of preceding weights + half own weight).
- `nearestCol(row, center)` (L261–272): picks the key in the target row whose weighted center is closest — so moving vertically between rows of different key widths lands on the visually nearest key.
- `moveVertical(delta)` (L274–279): **wraps around** (`(selRow + delta + rows.size) % rows.size`, L277) and snaps to the nearest column.
- `moveHorizontal(delta)` (L281–285): **wraps around** within the row (`(selCol + delta + n) % n`, L284).
- `toggleLayer()` (L287–298): to symbols → selection jumps to row 4 col 0 (the `ABC` key, so one more Menu press returns); to letters → row 4 col 1 (the `SYM` key). Then `build()` rebuilds the grid.
- `build()` (L203–227): removes all views, rebuilds from `rows`, ends with `select(selRow, selCol)`.
- `refreshLabels()` (L229–239): re-labels all keys in place (used for SHIFT toggling) without rebuilding.

## View structure (init block, L55–88)
Vertical LinearLayout, initially `GONE`, rounded panel background `ui.rounded(Colors.PANEL, Color_OUTLINE, ui.u(2), 14)` with `Color_OUTLINE = #FF2F4C7A` (companion, L328–330). Children: `titleView` (15sp muted, L62), `previewView` (24sp white bold, single-line, ellipsize START — long text scrolls showing the end, L65–69), `keysBox` (vertical, L75–77), and a hint line (L79–87): "Select: type    Rew: delete    FF: space    Play/Pause: go    Menu: symbols    Back: close".

## How MainActivity.kt uses it
- **Field**: `private lateinit var keypad: Keypad` (L115).
- **Construction** (L568–574): `Keypad(this, ui)`; listener installed (L569); width `min(screenW - u(40), u(820))`, placed bottom-center of the root FrameLayout with bottom margin `u(14)` (L570–573); an `OnLayoutChangeListener` triggers `updateEditorViewport()` (L574) so the page-editing viewport shrinks above the keypad (see L2244–2251: viewport height = total − keypad.measuredHeight − bottomMargin − 8, min 80).
- **Key routing** (L1703, L1714): in the key dispatch state machine, when `keypad.shown()` the whole event goes to `keypad.handleKey(event)` before menu/list/textPanel handling; the focus-mode string is "keypad" (L1703).
- **Opening**: `promptText()` (L2708–2732) — used for URL entry (`promptUrl`, goLabel "GO", L2734–2738) and other prompts (default goLabel "OK"); `openPageKeypad()` (L2740–2763) — goLabel "ENTER", then `webView.requestFocus()` + `__fb.focusIntoView()` + `syncFieldSoon()`; `openKeypadSmart()` (L2766+) — long-press Select: if the page has an active text field → `openPageKeypad()`, else `promptUrl()` (L2778). Also `openPageKeypad()` at L253 (start page) and L1908 (long-press Select).
- **Closing**: `closeKeypad()` (L2931–2941) calls `keypad.close()`; `cancelKeypad()` (L2925–2927) = cancel + close. Closed on: Back (via `onClose`→`cancelKeypad`), navigation start (L915: `textMode == TextMode.PAGE || cancelledWebPrompt → closeKeypad()`), page edit submit (L2875), L2578, L3060, and the "dead keypad over results" guard (L1401–1403).
- **Input handling**: `typeText/typeBackspace/typeClear/typeGo` (via listener, L447–467) operate on `buffer` (a `StringBuilder`) and dispatch into the page field or URL bar depending on `textMode` (PROMPT vs PAGE); preview updates at L2882/2892/2902/2912; password masking at L2882/3016.
- **Gating**: many code paths check `keypad.shown()` to avoid interfering: L1937/2659 (player intent validity), L2133 (poster-grid site page post), L2434 (sendKeyToPage suppression), L3306.

## What would break if changed/removed
- **Removing the class**: no text input at all — URL bar, page field editing, and all `promptText` flows (JS prompt/confirm substitutes) die. The app becomes read-only.
- **Changing `Listener` signatures**: MainActivity 447–467 breaks at compile time; the `onGo` vs `press()` split is load-bearing — `typeGo()` (MainActivity) submits the URL or triggers the page's form submit, while `press()` types the GO label's action; conflating them would make the GO key insert text instead of submitting.
- **Changing `handleKey` return contract** (must return true for handled keys): MainActivity 1714 relies on it to stop propagation; returning false for DPAD keys would let the WebView receive them too.
- **Changing wrap-around navigation** (L277, L284): remote users depend on infinite D-pad movement; clamping would strand the cursor at grid edges.
- **Changing the `Kind.GO` ENTER special-case** (L122–131): ENTER/DPAD_CENTER on the GO key must call `onGo()` (submit), not type; removing it would make the GO key untypeable-by-Enter and break the "matches the label" contract.
- **Changing row/weight layout** (L157–195): `nearestCol`/`centerOf` are weight-based, so the grid stays consistent under any weights, but the hardcoded jump targets in `toggleLayer` (row 4 col 0/1, L290–295) assume the ABC/SYM keys sit at those positions — moving them would make Menu land on the wrong key.
- **Changing `open()` reset behavior** (L96–99): state (layer/shift/selection) is reset on every open; not resetting would leak SHIFT state between URL entry and page editing.
- **Removing `setPreview`**: the user loses the only feedback of what's being typed (the WebView's cursor is visible but the buffer lives in MainActivity); password masking (L2882/3016) would also be lost.
- **Changing `isFocusable = false`** (L60): the keypad would steal Android focus from the WebView, destroying the page's text cursor — the core invariant of the design (header comment L13–15).
# Osd.kt (106 lines)

`class Osd(context: Context, private val ui: Ui = Ui(context)) : LinearLayout(context)` — a small on-screen display for video playback: play/pause status, position (seek bar + time), volume (bar + text), and a hint line. Auto-hides after 3.5 s. `isFocusable = false` (L25) so it never steals remote focus.

## View structure (init, L20–54)
Vertical LinearLayout, initially `GONE`, rounded background `argb(225, 8, 22, 46)` radius 14 (L23), padding `u(20)/u(12)/u(20)/u(14)` (L24). Children top→bottom:
1. `statusView` — 24sp white bold (L27–28): "PLAYING" / "PAUSED" / "No video detected".
2. `seekBar` — `BarView(context, track=argb(90,255,255,255), Colors.ACCENT)`, height `u(7)`, top margin `u(8)` (L30–35).
3. `timeView` — 15sp `Colors.SUBTLE` (L37–38): e.g. "12:34 / 56:78".
4. `volView` — 16sp white (L40–43): e.g. "Volume 80%" / "Video volume 80%" / "Muted".
5. `volBar` — `BarView(context, track, Colors.GREEN)`, height `u(7)` (L45–48).
6. `hintView` — 13sp `Colors.MUTED` (L50–53): remote-control hint text; hidden when empty.

`hideRunnable` (L18): sets `visibility = GONE`.

## Public API

### `fun show(status: String, seekFraction: Float?, timeText: String, volText: String, volFraction: Float, hint: String)` (L56–82)
- Sets `statusView.text = status` (L65).
- **`seekFraction` is 0..1, or `null` to hide the position bar** (no video found) (L56 doc, L66–74): null → both `seekBar` and `timeView` GONE; else VISIBLE, `seekBar.fraction = seekFraction`, `timeView.text = timeText`.
- `volView.text = volText`, `volBar.fraction = volFraction` (L75–76).
- `hintView` shown only when hint non-empty (L77–78).
- Makes itself VISIBLE (L79), then **reschedules auto-hide**: `removeCallbacks(hideRunnable); postDelayed(hideRunnable, 3500)` (L80–81) — every show restarts the 3.5 s timer.

### `fun seekFractionAt(rootX: Float, rootY: Float): Float?` (L89–100)
Hit-test for tapping the seek bar with the remote pointer. Doc (L89): coordinates are in the Activity root, "just like its remote pointer". Returns null when: Osd not VISIBLE, seekBar not VISIBLE, `seekBar.width <= 0`, or coordinates non-finite (L91–92). Otherwise converts root coordinates into seekBar-local coordinates via `getLocationOnScreen` on both `seekBar` and `rootView` (L93–97). Returns null if outside the bar with a **5-px vertical tolerance** (`y < -ui.u(5) || y > seekBar.height + ui.u(5)`, L98) and strict horizontal bounds (L98). Otherwise returns `(x / seekBar.width).coerceIn(0f, 1f)` (L99) — the seek fraction.

### `fun hideNow()` (L102–105)
Cancels the pending hide and sets GONE immediately.

### `override fun onDetachedFromWindow()` (L84–87)
Removes the hide callback (prevents a stale GONE-after-detach and leaks).

## How MainActivity.kt uses it
- **Field/construct** (L581): `osd = Osd(this, ui)` added to root (presumably top-center; not shown in the excerpt but placed in the root FrameLayout near the cursor/progress).
- **Data source**: `refreshOsd(delayMs, hint)` (L2473–2508) — debounced; after ≥75 ms it evals `window.__fb.state()` in the page and parses a JSON object with fields `f` (video found, 0/1), `p` (paused, 0/1), `t` (current position seconds), `d` (duration seconds), `v` (video volume 0..1), `m` (muted, 0/1) (L2481–2501), then calls `showOsd(...)`.
- **Rendering**: `showOsd(found, paused, t, d, videoVolume, muted, hint)` (L2510–2544):
  - status = "No video detected" / "PAUSED" / "PLAYING" (L2519).
  - `seek = (t/d).coerceIn(0,1)` only when found and both finite and d > 0, else null (L2520) — this is what hides the bar for non-video pages.
  - time = `formatTime(t)` + " / " + `formatTime(d)` when d > 0 (L2521–2525).
  - volume: if `audio.isVolumeFixed` (Fire TV hardware volume) use the **system** music stream volume (`getStreamVolume/getStreamMaxVolume`, L2529–2533); otherwise the page's video volume (L2534–2537); muted → fraction 0 + "Muted" (L2538–2541).
  - `osd.show(status, seek, time, volText, volFraction, hint)` (L2542); re-raises the cursor above the Osd if visible (L2543).
- **Trigger points**: fullscreen enter (L1336, L2585: hint "Select: play/pause    Left/Right: seek    Up/Down: volume    Back: exit"), unmute (L1943, L2663: "Unmuted", 1500 ms), seek actions (L2360, L2426 "Hold FF/Rewind: larger skips", L2436 "Native seek steps depend on this player", L2455 "Select position • Hold FF/Rewind for larger skips", L3153), periodic refresh (L2463, L2468, L2690).
- **Hiding**: `osd.hideNow()` at L306 (onCreate/destroy area), L913 (navigation start), L1338, L1541, L1622, L2600, L2613.
- **Seek tap**: `tapAtCursor()` (L2087–2094): before dispatching a page tap, it asks `osd.seekFractionAt(cx, cy)`; if the pointer is over the seek bar it calls `seekToFraction(it)` and shows the cursor instead of tapping the page — the Osd's seek bar is a live, tappable control.

## What would break if changed/removed
- **Removing the class**: no playback status feedback (play/pause, position, volume) and no tap-to-seek on the timeline; `refreshOsd`/`showOsd` (MainActivity 2473–2544) and `tapAtCursor`'s seek branch (2090–2094) must be deleted together.
- **Changing `show()` parameter order/types**: MainActivity 2542 breaks at compile time; the `seekFraction: Float?` null-means-hide contract (L56, L66) is relied on by L2520.
- **Changing the 3500 ms auto-hide** (L81): shorter hides the Osd mid-hint; longer leaves a permanent overlay over video.
- **Changing `seekFractionAt` tolerance/return contract** (L89–100): it must return null for "not over the bar" (tapAtCursor falls through to a page tap, L2090–2094); a non-null miss would turn ordinary page taps near the top of the screen into seeks. The 5-px vertical tolerance (L98) is deliberate for a 7-px-tall bar.
- **Removing `hideNow()`**: the Osd would linger up to 3.5 s after navigation/fullscreen-exit (L913, L2600, L2613), covering the page.
- **Changing the volume source logic** (MainActivity 2529–2537, not in this file): on Fire TV the hardware volume is fixed, so the Osd shows the *system* stream volume; mixing these up misreports volume.
# CursorView.kt (61 lines)

`class CursorView(context: Context, private val sizePx: Int = Ui(context).u(24)) : View(context)` — the on-screen mouse pointer for the TV remote. A filled white circle with a black ring; the **hot-spot is the centre of the circle** (header comment L10–13). While a page is loading, a thin accent-colored bar under the dot shows the loading state ("no ring around the pointer" — the bar replaces the usual visual affordance during loads).

## Public API

### `var loading: Boolean` (L21–27)
Custom setter: only invalidates when the value actually changes (L23–26). When true, `onDraw` paints the loading bar (L49–53).

### `fun moveTo(x: Float, y: Float)` (L56–60)
"Put the hot-spot at screen position (x, y)" (L56). Sets `translationX = x - sizePx/2f`, `translationY = y - sizePx/2f` (L58–59) — the view's top-left is offset so the circle's centre (the hot-spot) lands exactly on (x, y). Uses translation, not layout, so movement is cheap and doesn't trigger re-layout of the root FrameLayout.

## Internals
- Paints: `fill` — white `argb(230,255,255,255)`, FILL style (L16, L30–31); `ring` — black STROKE, width `max(2f, sizePx/9f)` (L17, L32–34); `bar` — `Colors.ACCENT` (L18, L35). `barRect: RectF` (L19).
- `isClickable = false`, `isFocusable = false` (L36–37) — the pointer must never intercept remote focus or clicks.
- `onMeasure` (L40–42): fixed `sizePx × sizePx` regardless of spec.
- `onDraw` (L44–54): circle radius `r = sizePx/2 - ring.strokeWidth` (L46) so the ring isn't clipped; draws fill then ring (L47–48); if `loading`, draws a rounded bar of height `max(3f, sizePx/12f)` across the bottom edge (L49–52).

## How MainActivity.kt uses it
- **Field/construct** (L110, L535–541): `cursorSize = ui.u(26)`; `cursor = CursorView(this, cursorSize)`; initially GONE; added to root FrameLayout with exact `cursorSize × cursorSize` layout params (L538); initial position = screen centre (`cx = screenW/2f`, `cy = screenH/2f`, L539–540) via `cursor.moveTo(cx, cy)` (L541). The pointer "remembers where it was" (comment L2088–2089) so the first press after load clicks the remembered spot.
- **Position tracking**: `cursor.moveTo(cx, cy)` at L599 (remote pointer movement) and L2014.
- **Show/hide**: `showCursor()`/`hideCursor()` (L2075, L2082) toggle `visibility`; `hideCursorRunnable` (L400–411) auto-hides after inactivity — **but keeps the pointer visible while `cursor.loading` is true** (L403–406), re-posting every 2 s, so the loading bar stays on screen during long loads.
- **Loading state**: set `true` at L939 (`page_started`), `false` at L1081 (`page_finished`), L1136 (progress ≥ 100), L1622 (page cleanup).
- **Z-order**: `showOsd` re-raises the cursor above the Osd when both visible (L2543: `cursor.bringToFront()`).
- The cursor's position (`cx`, `cy`) doubles as the tap target for `tapAtCursor()` (L2087+) and is fed to `osd.seekFractionAt(cx, cy)` (L2090) and `__fb.selectAt(x/webView.width, y/webView.height)` (L2102) — the CursorView is the visual half of the pointer/tap system; the coordinates live in MainActivity.

## What would break if changed/removed
- **Removing the class**: the user loses the visible pointer; remote navigation (move Select to aim, press to tap) becomes blind — the core interaction model of the app (tap-to-navigate without a mouse) breaks.
- **Changing `moveTo` semantics** (L56–60): the centre-hot-spot contract is relied on by every coordinate computation in MainActivity (L2090, L2102, L2143 `tap_dispatched` x/y); shifting the hot-spot to a corner would offset every tap by up to `sizePx/2` (13 px at the default 26 px size) — clicks would land on the wrong element.
- **Changing `onMeasure`** (L40–42): the layout params at L538 assume exactly `sizePx`; a different measured size would desync the translation math in `moveTo`.
- **Removing the `loading` bar** (L21–27, L49–52): users lose the only loading feedback tied to the pointer; the `hideCursorRunnable` keep-alive logic (L403–406) would also need removal or it would keep a bare dot on screen forever.
- **Making it focusable/clickable** (L36–37): the cursor would steal remote focus from the WebView and swallow Select presses — the entire tap-dispatch pipeline (MainActivity L2087+) breaks.
- **Changing the default size** (`Ui(context).u(24)`, L14): MainActivity passes its own `ui.u(26)` (L535), so the default only matters for other potential callers — none exist in the codebase.
# TvWebView.kt (14 lines)

`class TvWebView(context: Context) : WebView(context)` — a single-method WebView subclass. Its entire purpose is to override `onCheckIsTextEditor()` to return `false` (L13), so Chromium never treats this WebView as a text editor that can request the platform IME.

## The one override

### `override fun onCheckIsTextEditor(): Boolean = false` (L13)
`WebView.onCheckIsTextEditor()` is the hook Chromium's Android embedding calls to decide whether the view is a text editor eligible for IME input. Returning `false` means the WebView will **never** call `InputMethodManager.showSoftInput` / request the system keyboard for its input fields.

## Why (comment L6–12)
- The app has its own remote-friendly on-screen keypad (Keypad.kt); the system IME must never be requested for this WebView (L8–9).
- The window flag alone is **not** sufficient: MainActivity sets `FLAG_ALT_FOCUSABLE_IM` (MainActivity:476, comment "Exclude the system IME at the window, not by disabling Chromium's editor"), but on **Android 7.1 (Fire TV)** that flag does not suppress **FireTVIME** — when the IME pops up it takes focus, and dismissing it with Back blurs the field and kills typing (L9–11).
- So the suppression is done at the WebView level instead. Comment L12: "This is the 1.5 behaviour, restored" — i.e. v1.5 of the app had this behavior, it was lost, and this class restores it.
- Note the deliberate split (L6): "Keep Chromium's editor intact" — Chromium's internal editor (caret, selection, the `InputConnection` that `NativePageEditor` drives) stays fully functional; only the *platform IME request* is disabled. This is what lets `NativePageEditor.dispatch` inject text into page fields without any keyboard UI.

## How MainActivity.kt uses it
- **Field**: `private lateinit var webView: TvWebView` (L108) — typed as TvWebView, not WebView.
- **Construction**: `webView = TvWebView(this)` (L522), then added to the root layout (L529) and configured (webview_config logged at L751).
- Every other use in MainActivity is through the `webView` reference (loadUrl, evaluateJavascript, WebChromeClient callbacks, etc.) — none of them depend on the subclass beyond the `onCheckIsTextEditor` behavior.

## What would break if changed/removed
- **Reverting to plain `WebView`**: on Fire TV (Android 7.1.2), focusing a page text field would pop up FireTVIME; it steals focus from the WebView, and Back-to-dismiss blurs the field, killing the typing session (per L9–11). The custom Keypad flow (openPageKeypad → webView.requestFocus() at MainActivity:2760) would intermittently hand input to the system IME instead of the native editor path.
- **Returning `true`**: same as above — Chromium would request the IME for every editable field.
- **The `FLAG_ALT_FOCUSABLE_IM` flag (MainActivity:476) cannot replace this class** — the comment explicitly says the flag alone fails on Android 7.1; both are needed (flag for the window, override for the view).
- **Renaming/relocating the class**: MainActivity L108/L522 must be updated; it's the only WebView in the app, so there is no fallback instance.
- **Impact on NativePageEditor**: none directly — `dispatch` takes a `WebView` (L19 of NativePageEditor.kt), so it would compile with a plain WebView, but the *runtime* IME interference would corrupt the input flow it serves.
# NativePageEditor.kt (84 lines)

`internal object NativePageEditor` — the dispatch mechanism that injects text into web-page form fields **without any system IME**. Header (L14): "No system IME. The real Chromium InputConnection handles otherwise inaccessible fields." It drives the WebView's real Chromium `InputConnection` (the same object the system IME would use), so fields that are hard to reach via DOM scripts (shadow DOM, iframes, custom widgets) can still receive input.

## Public (internal) API

### `fun dispatch(view: WebView, action: String, text: String, result: (Boolean) -> Unit): () -> Unit` (L19–44)
Acquires the InputConnection on the **view (main) thread**, executes the edit on **Chromium's requested input thread** (the connection's own handler, API ≥ 24), and reports a single boolean via `result`. Returns a **cancel function**. Contract (comment L16–18): a cancelled/timed-out command is **never replayed**; Chromium owns the connection (the app never closes it).

Mechanics:
1. `main = Handler(Looper.getMainLooper())` (L20); `done = AtomicBoolean(false)` (L21) — the single completion guard; every completion path uses `done.compareAndSet(false, true)` so the callback fires **exactly once**.
2. `info = EditorInfo()`; `connection = view.onCreateInputConnection(info)` in a try/catch → null on failure (L22–23). `onCreateInputConnection` both creates the connection and fills `info` with the focused field's editor state (input type, IME options).
3. **Timeout**: `timeout = Runnable { if (done.compareAndSet(false, true)) result(false) }` (L24), posted via `main.postDelayed(timeout, 1800L)` (L38) — an edit that doesn't complete within **1.8 s** is reported as failed.
4. **Target thread**: `target = connection?.handler ?: main` on API ≥ 24 (L26), else `main` — the InputConnection must be used on the thread that created it (Chromium's input thread on modern Android).
5. **Task** (L28–37): runs on `target`; if already done (cancelled/timed out) → do nothing (no replay). Otherwise computes `accepted = connection != null && info.inputType != InputType.TYPE_NULL && perform(connection, info, action, text)` (L30–31) — `TYPE_NULL` means no editable field is focused. Then posts the completion back to main: `done.compareAndSet`, removes the timeout, `result(accepted)` (L32–35).
6. **Post failure**: if `target.post(task)` fails (L39), the task never runs, so the fallback completes with `false` on main (L39–42).
7. **Cancel function** (L43): `{ done.set(true); target.removeCallbacks(task); main.removeCallbacks(timeout) }` — marks done (so a late task/timeout can't fire the callback), and removes both pending runnables.

### `internal fun apply(view: WebView, action: String, text: String): Boolean` (L46–51)
Synchronous helper, "for connection-level tests on the owning thread only" (L46). Creates the connection, returns false if null or `inputType == TYPE_NULL`, else `perform(...)` directly. Not used by the app's runtime path — test/diagnostic only.

## `perform(connection, info, action, text): Boolean` (L53–83)
The action switch; returns true if the edit was accepted, false otherwise; any exception → false (L82).

- **`"insert"`** (L56): `connection.commitText(text, 1)` — inserts `text` at the cursor, selection collapses to the end (newSel = 1).
- **`"delete"`** (L57–67):
  - If there's a selection (`connection.getSelectedText(0)` non-empty) → `commitText("", 1)` replaces the selection with nothing (L58–59).
  - Else on API ≥ 24 → `deleteSurroundingTextInCodePoints(1, 0)` (L60) — deletes one *code point* before the cursor (correct for emoji/surrogate pairs).
  - Else (API 25 target is ≥ 24 so this branch is defensive, but kept) → reads `getTextBeforeCursor(2, 0)`, and if the last two chars form a surrogate pair, deletes 2 chars, else 1, via `deleteSurroundingText(units, 0)` (L61–66).
- **`"clear"`** (L68): `performContextMenuAction(android.R.id.selectAll) && commitText("", 1)` — select-all then replace with empty; both must succeed.
- **`"submit"`** (L69–79):
  - Determine the editor action: `info.actionId` if non-zero, else `info.imeOptions and EditorInfo.IME_MASK_ACTION` (L70).
  - If it's a real action (not `IME_ACTION_UNSPECIFIED`/`IME_ACTION_NONE`) → `connection.performEditorAction(editorAction)` (L71–72) — e.g. IME_ACTION_GO/SEARCH/SEND for that field.
  - Else synthesize an ENTER key: `sendKeyEvent` DOWN then UP with `KEYCODE_ENTER` (L73–77), requiring both to be accepted.
- **else** → false (L80). Unknown actions are rejected, not guessed.

## Threading & state invariants
- `dispatch` must be called on the main/view thread (it calls `onCreateInputConnection`, which is a view-thread API).
- The edit executes on Chromium's input thread (API ≥ 24); completion is marshalled back to main.
- Exactly-once completion: `AtomicBoolean done` guards task, timeout, and post-failure paths.
- No replay: a cancelled or timed-out command is dropped (L29, L39–42, L43) — MainActivity relies on this ("A timeout/exception/blocked edit is NOT permission to replay keystrokes", MainActivity:2841).
- The connection is never closed by this object — Chromium owns it (comment L17).

## How MainActivity.kt uses it
- **Single call site**: `dispatchNativePageEdit(edit)` (L2847–2852):
  ```kotlin
  nativeEditCancel = NativePageEditor.dispatch(webView, edit.action, edit.text) { accepted ->
      nativeEditCancel = null
      finishPageEdit(edit, accepted, null)
  }
  ```
  The returned cancel function is stored in `nativeEditCancel` so a newer edit/navigation can cancel a pending one.
- **When it's used**: `drainPageEdits()` (L2822–2845) processes queued `PageEdit`s (action ∈ insert/delete/clear/submit, from the Keypad via `typeText`/`typeBackspace`/`typeClear`/`typeGo`). If the edit names a specific frame+field (`qualified`, L2827), it first tries the JS path `__tvUi.edit(action, text, frame, field, 0)` (L2832–2834); only when the JS path reports `"unreachable"` (L2840–2842 — a pre-action result, so falling back can't duplicate a DOM edit) does it fall through to `dispatchNativePageEdit` (L2829 for unqualified edits, L2842 for unreachable ones).
- **Result handling**: `finishPageEdit` (L2854–2878) logs `edit_result` to DiagnosticLog (L2857–2859); on `!accepted` it sets `inputFocusConfirmed = false`, clears the queue, shows keypad preview "(website did not accept input)" (L2864) and a toast (L2865); on accept with a page state report it refreshes the preview (L2868–2874); `submit` closes the keypad (L2875).

## What would break if changed/removed
- **Removing the object**: page-field editing degrades to the JS `__tvUi.edit` path only; fields it can't reach (unreachable status) become completely uneditable — no fallback. Login forms, search boxes in shadow DOM/iframes would stop working for a meaningful subset of sites.
- **Changing the `dispatch` signature or the once-only contract**: MainActivity stores the cancel function in `nativeEditCancel` (L2849) and relies on exactly-once `result`; a double callback would double-apply edits or corrupt `finishPageEdit`'s `pageEditActive` bookkeeping (L2855).
- **Changing the 1.8 s timeout** (L38): shorter → false "website did not accept input" on slow Chromium input threads (common on the PowerVR Fire TV); longer → the keypad appears stuck and `nativeEditCancel` stays set, blocking cancellation semantics.
- **Changing `perform` action names** (`insert`/`delete`/`clear`/`submit`, L55–80): the `PageEdit.action` strings come from the Keypad flow in MainActivity; renaming breaks the switch silently (returns false → "not accepted" UX).
- **Dropping the `TYPE_NULL` check** (L30, L50): edits would be attempted with no focused field; `commitText` on a null-typed connection is undefined behavior in Chromium.
- **Using the connection off its owning thread**: `InputConnection` is not thread-safe; posting the task to `main` instead of `connection.handler` (L26) on API ≥ 24 would race Chromium's input thread and can crash or drop input.
- **Replaying after timeout/cancel**: explicitly forbidden (L16–18, L29) — replaying a "submit" after timeout could double-submit a form (double purchase, double post).
- **Removing the surrogate-pair handling in "delete"** (L60–66): backspace would corrupt emoji/CJK text (deleting half a pair → replacement character).
