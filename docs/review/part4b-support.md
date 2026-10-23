# BookmarkStore.kt (416 lines)

## Purpose
The app's single source of truth for bookmarks and folders. Persists everything in one
`SharedPreferences` file `bookmarks` (`shared_prefs/bookmarks.xml`) under the key `"data"`
as a single JSON string. Also owns the import/export format, the `previousImport`
undo-snapshot key, URL normalization, and bookmark-name suggestion.

## Data model (L10-13)
- `data class Bookmark(val title: String, val url: String, val folder: String)` — L11.
  Convention: `folder == ""` means "not in a folder" (rendered as "Unsorted" once any
  folders exist) — documented at L10.
- `data class FolderInfo(val name: String, val count: Int)` — L13, used by start page /
  folder list.

## Class structure (L22-416)
State (L24-30):
- `prefs` = `getSharedPreferences("bookmarks", MODE_PRIVATE)` (L24).
- `items: ArrayList<Bookmark>` — the one ordered list; order = display order (L25).
- `folderOrder: ArrayList<String>` — ordered folder names (L26).
- `folderCounts: HashMap<String,Int>` — per-folder counts incl. `""` for unsorted (L27).
- `Snapshot(items, folders, counts)` (L28) + `@Volatile published` (L29) + `publish()`
  (L30): readers (`all()`, `inFolder()`, …) read the immutable snapshot, writers mutate
  the live lists and republish inside `commit()`. This is the thread-safety mechanism:
  all mutations are `@Synchronized` and end in `commit()` → `tidy()` → `save()` →
  `publish()` (L184-188).
- `init` (L32-35): `load()` then `publish()`.

## Public API — reads (L37-65)
- `all(): List<Bookmark>` (L39) — copy of published items.
- `folderNames(): List<String>` (L41) — copy of folder order.
- `hasFolders(): Boolean` (L43) — `published.folders.isNotEmpty()`.
- `inFolder(folder: String): List<Bookmark>` (L45) — filter by exact folder string.
- `count(folder: String): Int` (L47) — `published.counts[folder] ?: 0`.
- `entries(max: Int): List<FolderInfo>` (L53-61) — real folders in order, then
  `FolderInfo("", loose)` appended **only if** at least one real folder exists and loose
  count > 0 (L58); `max <= 0` = no limit (L59).
- `find(url): Bookmark?` (L63) — first item where `sameUrl`.
- `contains(url): Boolean` (L65).

## Public API — bookmark mutations (L69-137, all `@Synchronized`)
- `add(title, url, folder)` (L70-75): trims folder; replaces in place if `sameUrl`
  already exists (L72-73), else appends.
- `remove(url)` (L78-81): removes **all** items matching `sameUrl`.
- `rename(url, title)` (L84-90): no-op if url missing or trimmed title empty.
- `move(url, folder)` (L93-98): changes folder only; trims folder.
- `moveUp(url)` (L102-113): swaps with previous item **in the same folder** (skips
  items of other folders, L106-108).
- `moveDown(url)` (L117-128): mirror of moveUp.
- `moveToTop(url)` (L131-137): moves item to index 0 of the global list (not
  folder-relative).

## Public API — folder mutations (L141-178, all `@Synchronized`)
- `renameFolder(old, new)` (L142-151): no-op if either name empty after trim; rewrites
  every matching item and the order entry.
- `ungroup(folder)` (L155-160): folder deleted, its bookmarks become Unsorted (`""`).
- `moveFolderUp(folder)` (L163-169), `moveFolderToTop(folder)` (L172-177).
- Folders are never stored explicitly — a folder exists iff ≥1 bookmark uses it (L20,
  enforced by `tidy()`).

## Persistence format (L180-324)
JSON shape (produced by `exportJson()` L205-220, consumed by `load()` L300-324):
```json
{ "folders": ["A","B"], "items": [ {"t":"title","u":"url","f":"folder"}, ... ] }
```
- `save()` (L296-298): `prefs.edit().putString("data", exportJson()).apply()` — async
  apply, no failure check on the normal path.
- `load()` (L300-324): reads `"data"`; if missing → empty store. If
  `JsonInputBudget.allows(raw)` is false → returns leaving items empty but **preserves
  the stored bytes** (comment L304: never re-save/recursively rewrite malformed bytes).
  On any JSON parse exception → clears both lists (L319-322) but does NOT rewrite
  prefs, so corrupt data stays on disk. Then `tidy()` (L323).
- `tidy()` (L191-201): rebuilds `folderCounts` from items (incl. `""`), keeps only
  folders that are actually used, appends newly-used folders missing from the order
  (L196-199). Called at end of every `commit()` and after load/import.
- `exportJson()` (L205-220): same shape as the internal store — export format ==
  internal format.

## Import / undo (L222-332)
- `exportPortableJson()` (L224-235): validates before exporting so a backup can always
  be re-imported: ≤20,000 items and ≤20,000 folders (L225); per item: title ≤2048,
  folder ≤256, url ≤8192, scheme must be http/https with non-empty host (L228-230);
  total UTF-8 size ≤1 MiB (L233). Throws `IOException` with user-facing messages.
- `importJson(raw): Boolean` (L241-294) — the transactional import:
  1. Captures `previousData = prefs["data"]` and `previousUndo = prefs["previousImport"]`
     (L242-243) and copies live items/folderOrder (L245-246).
  2. Rejects (returns false): raw >1 MiB (L248), `JsonInputBudget.allows` false (L249),
     missing `"items"` array (L251), >20,000 items (L252), per item: non-String/missing
     `u` (L257), empty or >8192 url, non-http(s) scheme or empty host (L259), non-String
     `t`/`f` if present (L260-261), title >2048 or folder >256 (L262). Duplicates
     collapsed by `urlKey` (L254, L263).
  3. `"folders"` optional; if present must be a string array ≤20,000, each ≤256 chars
     (L266-274).
  4. Replaces `items`/`folderOrder`, `tidy()` (L276-280).
  5. Writes `previousImport` = the **old** `"data"` string (L281-284) and `"data"` =
     `exportPortableJson()` using `commit()` (synchronous, result checked; `@SuppressLint
     ("ApplySharedPref")` justified at L238 because a durable rollback needs the commit
     result).
  6. On any exception after persistence was attempted: restores `"data"` and
     `previousImport` from the captured values (L288), restores in-memory state
     (L289-291), `tidy()`, returns false.
- `hasPreviousImport(): Boolean` (L328) — `prefs.contains("previousImport")`.
- `undoImport(): Boolean` (L329-332) — re-imports the `previousImport` payload (which
  itself becomes the new `previousImport`, so undo chains).

## Companion object (L334-415)
- `shared(context)` (L335-336): returns `(applicationContext as BuccaneerApplication).bookmarks`
  — the app-wide singleton; never constructs a second instance.
- `SEPARATORS` (L338-340): `" | "`, `" - "`, en-dash, em-dash, `" · "`, `" • "`, `" :: "`,
  `" » "` — title-splitting separators for name suggestion.
- `hostOf(url): String` (L342-349): `Uri.parse(...).host` (null on parse failure → falls
  back to the raw url), strips leading `www.`.
- `sameUrl(a,b)` (L351) = `urlKey(a) == urlKey(b)`.
- `urlKey(raw)` (L353-370): normalization for http(s) URLs: lowercased scheme+host,
  default ports dropped (443/https, 80/http), userinfo preserved, path defaults to `/`,
  query and fragment appended. Non-http(s) or parse failure → trimmed raw string.
- `suggestName(title, url): String` (L378-400): for "Add bookmark". Empty/URL-like/
  about:blank title → `hostName(host)`. Otherwise splits title on SEPARATORS and picks
  the longest part (≥3 alnum chars) whose alnum key is a substring of the host's alnum
  key (L387-397); else the title if ≤24 chars; else `hostName(host)`.
- `hostName(host)` (L403-414): drops TLD; drops two labels for common second-level
  ccTLDs (`co, com, org, net, gov, edu, ac` + 2-letter last label, L409-410);
  capitalizes first char.

## Key invariants
1. One ordered list; "Top N" = first N entries (L16-18).
2. Folder exists ⇔ ≥1 bookmark with that folder name (tidy enforces).
3. `folderCounts` always consistent with `items` after any commit (rebuilt in tidy).
4. Readers never see partially-updated state (volatile snapshot published last).
5. `data` key is never deleted; malformed bytes are preserved on disk (load L304).
6. Import is all-or-nothing with durable rollback via `previousImport`.
7. URL identity is `urlKey`, not string equality — `add`/`remove`/`find` all
   dedupe/locate via it.

## How MainActivity.kt uses it
- Field `store: BookmarkStore` (L104), initialized `store = BookmarkStore.shared(this)`
  in onCreate (L509).
- L3114: after closing the bookmarks panel, if `bookmarksFolder != null &&
  store.hasFolders()` reopen folder view.
- L3133-3135: on page load, if `store.contains(url)` offers "Remove bookmark" →
  `store.remove(url)`.
- L3211-3229 `startSaveBookmark()`: guards on `BookmarkJobs.isBusy()` (L3212);
  `BookmarkStore.suggestName(webView.title, url)` (L3218); menu built from
  `store.folderNames()` (L3221) + "New folder..."; saves via `saveBookmark` →
  `editBookmarks { store.add(name, url, folder) }` (L3240).
- L3314-3318 `exportBookmarksFile()`: `BookmarkFiles.export(app,
  bookmarks.exportPortableJson())` wrapped in `runFileOperation`.
- L3320-3348: `importBookmarkText` / `importBookmarksFile` / `importBookmarkUri` —
  each `runFileOperation { if (!bookmarks.importJson(text)) throw
  IOException("Not a supported bookmark backup"); BookmarkJobs.Result("Imported
  ${bookmarks.all().size} bookmarks", imported = true) }`.
- L3376-3386 `showBookmarks(folder, offset)`: `store.count(folder) == 0` → treat as
  root (L3376); `movingBookmarkUrl` validated via `store.find` (L3378); root view lists
  `store.entries(0)` when `store.hasFolders()` (L3384-3386).
- L3397-3417: rows from `store.all()` or `store.inFolder(view)`; row subtitle
  `BookmarkStore.hostOf(b.url)` (L3412); 50-row paging.
- L3420: total rows = `store.entries(0).size` (folder root) / `store.all().size` /
  `store.count(view)`.
- L3428-3438: footer — "All folders" when `store.hasFolders()`; "Restore previous"
  only when `store.hasPreviousImport()` → `runFileOperation { bookmarks.undoImport() }`
  (L3431-3437).
- L3444-3469 `showBookmarkOptions`: `store.find` for the moving bookmark; actions
  `moveToTop` (L3457), `rename` (L3463), `remove` (L3469) — all inside
  `editBookmarks { }`.
- L3477-3482 `moveBookmark`: `store.find`, `store.moveUp`/`store.moveDown`
  (L3481), recompute index via `store.inFolder(b.folder).indexOfFirst { it.url == b.url }`.
- L3487-3511 `showMoveToMenu`: `store.find`, `store.move(b.url, "")` (L3497),
  `store.folderNames()` (L3501), `store.move(b.url, f)` (L3504), new folder →
  `store.move(b.url, name)` (L3511).
- L3523-3537 folder options: `store.moveFolderToTop` (L3523), `store.moveFolderUp`
  (L3527), `store.renameFolder` (L3532), `store.ungroup` (L3537).
- L3815-3824 `onActivityResult(REQ_BOOKMARK_EXPORT)`: `bookmarks.exportPortableJson()`
  (L3819) written to the SAF output uri; result string uses `bookmarks.all().size`.
- All mutating calls are wrapped in `editBookmarks { ... }` (a helper that presumably
  re-renders the list and returns the lambda's Boolean) or in `runFileOperation` for
  import/export jobs.

## What would break if changed/removed
- Renaming the `"data"` key or JSON shape (`t`/`u`/`f`, `items`, `folders`): existing
  installs lose all bookmarks (load falls back to empty store); `previousImport`
  payloads become invalid → undo breaks.
- Removing `previousImport` handling: no undo for imports; "Restore previous" menu item
  (MainActivity L3431) and `undoImport` disappear.
- Changing `urlKey` normalization: duplicate detection changes — `add` may create
  duplicates, `remove`/`contains` (L3133) may miss matches.
- Removing the snapshot/publish pattern or `@Synchronized`: import/export run on worker
  threads (`runFileOperation`) while the UI reads — torn reads possible.
- Changing `entries()` semantics (Unsorted appended only when folders exist, L58):
  start page "Top 10" and the bookmarks root view (L3384-3386, L3420) render
  differently.
- Raising/loosening the 20,000 / 1 MiB / field-length limits: `exportPortableJson`
  (L225-233) and `importJson` (L248-274) must stay in sync or backups become
  non-restorable (violates the L222 contract).
- `shared()` cast to `BuccaneerApplication`: removing the app-level `bookmarks`
  property breaks MainActivity L509.
- `suggestName`/`hostOf` are static and used at MainActivity L3218/L3412 — signature
  changes break both call sites.
# BookmarkFiles.kt (57 lines)

## Purpose
File-based backup/restore transport for bookmark exports. Deliberately uses the **public
Downloads** location ("never silently an uninstall-deleted app folder", L18) so a backup
survives app uninstall. `internal object` — only used within the app module.

## Public API
### `read(input: InputStream): String` (L13-17)
Streams the input in 4096-byte chunks into a `ByteArrayOutputStream`; throws
`IOException("Backup exceeds 1 MiB")` the moment accumulated size would exceed 1 MiB
(L15) — matches the 1 MiB import limit in `BookmarkStore.importJson` (BookmarkStore.kt
L248). Decodes as UTF-8 (L16). `input.use` guarantees close.

### `export(context: Context, json: String): String` (L19-47)
Returns a user-facing success message. Two code paths:
- **API 29+** (L21-40): MediaStore Downloads.
  - Filename `Buccaneer-bookmarks-<epochMillis>.json` (L22), MIME `application/json`,
    `RELATIVE_PATH` = `Environment.DIRECTORY_DOWNLOADS`, `IS_PENDING=1` (L23-27).
  - `resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)`; null →
    `IOException("Public Downloads provider unavailable; choose a document location")`
    (L30-31).
  - Writes UTF-8 bytes, flush (L33-34); then publishes with `IS_PENDING=0` —
    `resolver.update(...) <= 0` → `IOException("Cannot publish public backup")`
    (L35-36).
  - On **any** throwable: deletes the pending uri (best-effort, L38) and rethrows —
    no half-published file left behind.
  - On success stores the uri in `settings` SharedPreferences under key
    `"bookmarkBackupUri"` (L37) — this is what `readSaved` uses later.
  - Returns `"Exported to Downloads/$filename (survives uninstall)"` (L39).
- **API < 29** (L41-46): deprecated direct filesystem.
  - `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` (L41);
    `mkdirs` failure → `IOException("Cannot create Downloads")` (L42).
  - Writes to `bookmarks.json.tmp` then atomic `renameTo` onto `bookmarks.json`;
    rename failure → delete temp, `IOException("Cannot replace backup")` (L43-45).
  - Returns `"Exported to Downloads/bookmarks.json (survives uninstall)"` (L46).

### `readSaved(context: Context): String` (L48-56)
- **API 29+**: reads `bookmarkBackupUri` from `settings` prefs; missing →
  `IOException("Choose a backup, or use a file manager's Open with Buccaneer")` (L50-51);
  opens `contentResolver.openInputStream(Uri.parse(value))` (null →
  `IOException("Backup unavailable")`, L52) and delegates to `read`.
- **API < 29**: reads `Downloads/bookmarks.json` directly (L54-55) — throws
  `FileNotFoundException` if absent (not wrapped).

## Invariants
- 1 MiB cap enforced at the stream level, independent of (and consistent with) the
  store-level cap.
- API 29+ export is pending-until-published; the saved uri is only written after a
  successful publish.
- The `settings` SharedPreferences file (distinct from `bookmarks`) holds
  `bookmarkBackupUri`.

## How MainActivity.kt uses it
- L3317 `exportBookmarksFile()`: `runFileOperation { BookmarkJobs.Result(BookmarkFiles.export(app, bookmarks.exportPortableJson())) }` — the message returned by `export` becomes the toast.
- L3332 `importBookmarksFile()`: `val raw = BookmarkFiles.readSaved(app)` then `bookmarks.importJson(raw)` inside `runFileOperation`.
- L3343-3344 `importBookmarkUri(uri)`: for SAF-picked `content://` uris,
  `BookmarkFiles.read(app.contentResolver.openInputStream(uri) ?: throw
  IOException("Cannot read selected backup"))` then `importJson`.
- All three run on the BookmarkJobs worker thread via `runFileOperation` (MainActivity
  L3294-3297), never on the main thread.

## What would break if changed/removed
- Removing the `bookmarkBackupUri` setting or renaming the key: "Import" of a previous
  file backup (MainActivity L3328-3336) fails on API 29+ with the "Choose a backup"
  error; export still works but the saved-location round trip is gone.
- Changing the 1 MiB cap: must stay ≤ the cap in `BookmarkStore.importJson` (L248) or
  `read`/`importJson` reject at different layers with different messages.
- Changing the filename scheme: only affects new exports; `readSaved` on API <29 is
  hardcoded to `bookmarks.json` (L54), so renaming that file breaks old-API import.
- Removing the IS_PENDING publish dance: users could see zero-size/hidden backup files
  in file managers.
# BookmarkJobs.kt (31 lines)

## Purpose
Process-wide single-slot job queue for bookmark file transfers (export/import/undo).
Comment L9: "Transfer ownership is process-wide; a browser renderer/Activity is not its
owner" — i.e. the job survives Activity recreation; results are matched back to the UI
that started them via `UiOwner`.

## Public API (all on `object BookmarkJobs`, L10)
### Nested types
- `data class UiOwner(val activity: String, val interaction: Long)` (L11) — identity of
  the UI that submitted the job: an activity-instance token plus an interaction serial.
  Used to discard stale results after the user has moved on or the activity changed.
- `data class Result(val message: String, val imported: Boolean = false, val error:
  Throwable? = null, val owner: UiOwner? = null)` (L12) — `message` is toast text;
  `imported=true` tells the UI to refresh the start page and reopen the bookmarks list;
  `error` is reported via diagnostics; `owner` is stamped by the submitter.
- `interface Listener { fun onBookmarkJob(result: Result): Boolean }` (L13) — returns
  `true` if the result was consumed (clears the pending result), `false` to keep it
  pending for a later attach.

### State (L14-18)
- `busy: AtomicBoolean` (L14) — at most one job at a time process-wide.
- `worker`: single-thread executor, daemon thread named `"Buccaneer-bookmarks"` (L15).
- `main`: main-looper Handler (L16).
- `listener: WeakReference<Listener>` (L17) — the Activity is not leaked.
- `pending: Result?` (L18) — a completed result waiting for a live listener.

### Functions
- `isBusy(): Boolean` (L19).
- `attach(value: Listener)` (L20): sets the weak ref and immediately `deliver()`s — so
  a result that finished while the UI was gone is delivered on resume.
- `detach(value: Listener)` (L21): clears only if the current listener is `value`
  (identity) — a newer attach wins.
- `submit(work: () -> Result): Boolean` (L22-29):
  - `busy.compareAndSet(false, true)`; returns `false` if a job is already running
    (caller toasts "A bookmark transfer is already running").
  - Runs `work()` on the worker thread; **any** Throwable is caught and converted to
    `Result("Bookmark transfer failed; existing bookmarks kept", error=e)` (L25) — the
    job never dies silently and never crashes the worker.
  - On completion posts to main: `busy=false`, `pending=result`, `deliver()` (L26).
- `deliver()` (L30, private): no-op if no live listener or no pending result; if
  `listener.onBookmarkJob(result)` returns true, `pending=null`.

## State machine
`idle → (submit) → busy → (worker done) → main: idle + pending → (attach/deliver
consumes) → pending=null`.
- A result delivered to a listener that returns false stays pending until the next
  attach/deliver succeeds.
- `detach` never cancels a running job; the job completes and its result waits in
  `pending`.

## How MainActivity.kt uses it
- `class MainActivity : Activity(), BookmarkJobs.Listener` (L98).
- `onPause()` (L642): `BookmarkJobs.detach(this)` (then `interactionSerial++` L643).
- `onResume()` (L684): `BookmarkJobs.attach(this)` — re-delivers any pending result.
- `onDestroy()` (L717): `BookmarkJobs.detach(this)`.
- `runFileOperation(work: () -> BookmarkJobs.Result)` (L3294-3297): stamps
  `UiOwner(activityInstance, interactionSerial)`, `BookmarkJobs.submit { work().copy(owner = owner) }`;
  on submit failure toasts "A bookmark transfer is already running".
- `onBookmarkJob(result)` (L3299-3312): returns false (keep pending) if
  `!foreground || activityDestroyed || recoveringRenderer` (L3300); reports
  `result.error` via `report("Bookmark transfer failed", it)` (L3301); toasts
  `result.message` (L3302); if `result.imported` → `startPage.refresh()` (L3304) and,
  if `result.owner` matches the current activity instance and interaction serial and
  the keypad is not shown, closes all panels and `showBookmarks(null, 0)`
  (L3305-3309); returns true.
- `BookmarkJobs.isBusy()` guards: `startSaveBookmark` (L3212), `saveBookmark` (L3233),
  L3239, L3453, L3519 — all toast "Wait for the bookmark transfer to finish".
- Job bodies: export (L3317), text import (L3320-3326), file import (L3328-3336),
  SAF-uri import (L3338-3348), undo (L3431-3337→L3433-3436), SAF export in
  `onActivityResult` (L3818-3823).

## What would break if changed/removed
- Removing the `busy` gate: concurrent imports could interleave with
  `BookmarkStore.importJson`'s `previousImport` snapshot and corrupt the undo chain.
- Changing `Listener.onBookmarkJob`'s Boolean contract: the pending-result retention
  logic (L30) depends on false = "not consumed yet".
- Dropping the WeakReference: Activity leak across process-wide job completion.
- Changing `UiOwner` fields: MainActivity L3306's staleness check
  (`owner.activity == activityInstance && owner.interaction == interactionSerial`)
  breaks — stale results would reopen the bookmarks panel at wrong times.
- Removing the Throwable catch in `submit`: an exception in `work()` would kill the
  single worker thread permanently (executor doesn't restart it), and `busy` would
  never clear — all future bookmark transfers dead.
# StartPage.kt (300 lines)

## Purpose
The launch screen shown when the browser starts (and via Menu > Start page).
**Implementation note:** despite the task brief describing it as "a native HTML page
rendered in a WebView", the actual implementation is a **pure native Android view
hierarchy** — a `FrameLayout` subclass built from `LinearLayout`s, `ImageView` and
`Ui`-provided buttons/tiles (L20-24, L50-59). No WebView, no HTML. D-pad moves between
buttons; Select opens one (L17).

Layout (documented L11-19): ten bookmarks/folders at a time (2 rows of 5), paging
arrows above the bottom actions; folder contents page independently; Back returns to
the folder-list page. "Top 10" = first 10 in the store's list order.

## Public API
### `class StartPage @JvmOverloads constructor(context, ui: Ui = Ui(context), store: BookmarkStore = BookmarkStore.shared(context)) : FrameLayout` (L20-24)
`listener: Listener?` (L32).

### `interface Listener` (L26-30)
- `openUrl(url: String)` — open a bookmark.
- `search()` — open the search/address prompt.
- `manage(folder: String?)` — open the bookmarks manager; `null` = root, `""` = Unsorted.

### Instance methods
- `show()` (L61-68): resets `folder=null`, `pageIndex=0`, `folderListPage=0`, renders,
  sets VISIBLE, `focusFirst()`.
- `hide()` (L70-72): GONE.
- `refresh()` (L75-84): re-render keeping current page; if the visible folder became
  empty (`store.inFolder(current).isEmpty()`, L78) fall back to the folder list at
  `folderListPage` (L79-80). No-op when not VISIBLE (L76).
- `back(): Boolean` (L87-94): if inside a folder, return to the folder list (restoring
  `folderListPage`); returns true if it did something, false at top level.
- `focusFirst()` (L96-100): posts a request-focus on `firstFocusable`, guarded by
  visibility, `renderGeneration` (stale-render check) and `f.parent != null`.
- `shown()` — inherited? No: `shown()` is called by MainActivity (e.g. L179, L251); it
  is the standard `View` visibility check (`visibility == VISIBLE`) — StartPage does
  not override it.

### State (L44-50)
- `folder: String?` — null = top level; `""` = Unsorted (L44-45).
- `pageIndex` — current page of the *visible* level (L46).
- `folderListPage` — remembered folder-list page so Back restores it (L47).
- `renderGeneration` — incremented per render; invalidates pending focus posts (L48).
- `firstFocusable` — first focusable view of the current render (L49).
- `content: LinearLayout` — vertical container (L50).
- `init` (L52-59): starts GONE, background `Colors.BG`, padding `ui.u(40/26/40/16)`.

### Version label (L34-42)
`versionLabel: String by lazy` — read live from
`packageManager.getPackageInfo(packageName, 0).versionName` (always matches the
installed build); `""` on any exception. Rendered as `"Buccaneer $versionLabel"`
(L241).

## Rendering (`render()`, L104-246)
1. Bumps `renderGeneration`, clears `content`, resets `firstFocusable` (L105-107).
2. **Header** (L109-128): tagline text `"Where's me buccaneers?"` (15sp,
   `Colors.SUBTLE`) + `R.drawable.buccaneer_logo` ImageView (36×36 `ui.u`, FIT_CENTER)
   + `"Under me bucc'n hat!"`.
3. **Tiles** (L130-152), three modes:
   - Inside a folder (`f != null`, L134-139): `store.inFolder(f)`; subtitle
     `"Folder: Unsorted"` if `f==""` else `"Folder: $f"`; `bookmarkTile` per entry.
   - Folders exist (L140-145): `store.entries(0)`; subtitle `"Your bookmark folders"`;
     `folderTile` per `FolderInfo`.
   - No folders (L146-151): `store.all()`; subtitle `"No bookmarks yet"` /
     `"Your bookmarks"`; `bookmarkTile` per entry.
   Each mode clamps `pageIndex` into `0..lastPage(total)` (L137/L143/L149).
4. **Subtitle** (L154-160): 17sp `Colors.MUTED`, centered, 1 line, ellipsize END.
5. **Empty state** (L162-172): message "Open a website, press Menu, then choose
   \"Add bookmark\".\nYour bookmarks and folders will show up here." (17sp SUBTLE).
   Otherwise `addGrid(tiles, total > MAX_TILES)` (L174).
6. **Pager** (L177-203), only when `total > MAX_TILES`: `◀` / counter `"n / N"` / `▶`
   small buttons (64×34 `ui.u`); disabled arrows are non-focusable and alpha 0.3;
   clicking sets `pageIndex` and re-renders + `focusFirst()`.
7. **Action bar** (L205-230): `ui.button` 230×46 `ui.u`, 12 `ui.u` gaps:
   - `"Search / address"` → `listener?.search()` (L220).
   - In a folder: `"All folders"` → `back()` (L222) and `"Manage folder"` →
     `listener?.manage(f)` (L223).
   - Top level: `"Manage bookmarks"` → `listener?.manage(null)` (L225).
   First button becomes `firstFocusable` if nothing else did (L217).
8. **Hint** (L232-239): `"Menu: options    Select: open    Back: "` +
   `"up one level"` (in folder) or `"exit"` (top).
9. **Version** (L241-245): `"Buccaneer $versionLabel"`, 13sp MUTED.

### Grid (`addGrid`, L248-274)
5 columns; tile width `min(ui.u(190), (screenW - ui.u(80) - 4*gap)/5)`, height
`ui.u(104)`, gap `ui.u(12)`. When `reserveTwoRows` (i.e. more than one page exists)
exactly 2 rows of 5 are laid out, padding missing slots with non-focusable,
a11y-ignored placeholder `View`s (L261-264) — keeps the screen height stable across
pages. Otherwise rows are filled as needed. `firstFocusable` falls back to `tiles[0]`
(L273).

### Tiles
- `bookmarkTile(b)` (L276-280): `ui.tile(b.title, BookmarkStore.hostOf(b.url), 30, 15)`;
  click → `listener?.openUrl(b.url)`.
- `folderTile(info)` (L282-294): label `"Unsorted"` if name empty else the name;
  subtitle `"1 bookmark"` / `"N bookmarks"`; click → remember `folderListPage =
  pageIndex`, set `folder = info.name`, `pageIndex = 0`, re-render + focus (L286-292).

### Companion (L296-299)
`MAX_TILES = 10`; `lastPage(total) = if (total <= 0) 0 else (total - 1) / MAX_TILES`.

## Key invariants / state machine
- Levels: TOP (folder==null) ⇄ FOLDER (folder != null). Entering a folder saves the
  top-level page in `folderListPage`; `back()`/`refresh()`-on-empty restore it.
- `pageIndex` is per-visible-level and always clamped to `lastPage(total)` on render —
  a shrinking list can never leave the pager out of range.
- Every render is a full teardown/rebuild of `content` (no view recycling); the
  generation counter prevents a stale posted `requestFocus` from hitting a new render's
  views.
- Data comes exclusively from `BookmarkStore` (`inFolder`, `entries(0)`, `all()`,
  `hasFolders()`, `hostOf`) — StartPage never touches prefs itself.
- Initial state is GONE; the activity controls show/hide.

## How MainActivity.kt uses it
- Field `startPage: StartPage` (L111).
- Construction (L543-557): `StartPage(this, ui, store)` — explicit `ui` and the
  app-wide `store`; `listener` wired: `openUrl` → `this@MainActivity.openUrl(url)`
  (L545-547), `search` → `promptUrl()` (L549-551), `manage(folder)` →
  `showBookmarks(folder, 0)` (L553-555). Added to `root` full-screen (L557).
- Shown at launch / Menu > Start page: L1631 `startPage.show()` (after loading
  `about:blank` into the WebView, L1625).
- Hidden on navigation: L1520-1521 `startPage.hide()` when a URL is entered.
- Back key (L1762): `if (rep == 0 && !startPage.back()) confirmExit()` — Back first
  pops a folder, only exits the app at top level.
- Key routing: L1704-1720 — while `startPage.shown()`, D-pad/Select events go to
  `onStartKey(event)` (L1720) and focus is kept on the start page (L1769
  `focusKey(event, startPage)`); the WebView is not the key target.
- `startPage.shown()` gates many behaviors: idle tracking (L179), text-mode entry
  (L251), key injection to page (L1134, L2434, L2770), page-available checks
  (L1549, L1561), menu key handling (L1791), diagnostics panel (L3793-3796).
- `startPage.refresh()` after bookmark mutations: after `hideList()` (L3109), after
  saving a bookmark (L3241), after a successful import job (L3304), after moving a
  bookmark (L3490).
- `startPage.descendantFocusability` is toggled (L3032) and `startPage.focusFirst()`
  is called when focus should return to the start page (L3038).
- L3118: `val onStart = startPage.shown()` (focus restore logic).

## What would break if changed/removed
- Removing `folderListPage` memory: Back/refresh would always return to page 0 of the
  folder list — deep folder browsing UX breaks.
- Changing `MAX_TILES`/grid math: `lastPage`, the 2-row reservation and pager
  arithmetic (L253, L258, L298) all assume 5 columns × 2 rows; mismatched values leave
  holes or overflow.
- Changing the `Listener` interface: MainActivity L544-556 implements all three
  methods; adding a method without a default breaks compilation, renaming breaks the
  tile/action-bar wiring (L220-225, L278, L286-292).
- Removing `refresh()`'s empty-folder fallback (L78-81): after deleting a folder's
  last bookmark from the manager, the start page would stay on a now-empty folder
  instead of returning to the list.
- Changing `renderGeneration`/`focusFirst` guards: stale `requestFocus` posts could
  focus views that were removed, losing D-pad focus after re-renders.
- Removing the version label: cosmetic only, but it is the only place the installed
  `versionName` is displayed.
- Changing tile click behavior: `openUrl` is the only path from the start page to a
  website (L545-547); `manage` is the only path into the bookmarks manager
  (L553-555).
- Note for the review: the brief's "HTML page in a WebView" description does not match
  the code — StartPage is native views; any WebView-based assumptions (JS injection,
  `loadUrl`, renderer crashes affecting the start page) do not apply.
