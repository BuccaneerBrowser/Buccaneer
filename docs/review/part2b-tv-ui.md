# tv_ui.js — chunk 1 (lines 1–170)

## Scope
IIFE, `'use strict'` (L3). Re-entrancy guard: `if (window.__tvUi) return;` (L4) — second injection into same frame is a no-op. (Note: `window.__tvUi` is assigned later in the file; until then the guard relies on the first pass completing synchronously — injection is document-start, so this holds.)

## Frame identity & host (L5–11)
- L5: `host` = lowercased `location.hostname`, trailing dot stripped. **Used later only for host-gated behavior (menu fitting / theme hooks) — not for the field protocol itself.**
- L6: `documentId` = `window.Buccaneer.currentDocument()` (native bridge; try/catch → `''` if bridge absent).
- L7–8: `fieldSequence` counter; `fields` map id→element (per-frame registry).
- L9: `fieldId(el)` — lazy `Object.defineProperty(el,'__buccaneerField',{value:String(++fieldSequence)})`. On defineProperty failure (frozen elements) returns `''` → field unaddressable. Registry is never pruned (leak is bounded by page lifetime; fine).
- L10: `byId(id)` → element or null.
- L11: `frameId = Math.random().toString(36).slice(2)+'-'+Date.now()` — per-document frame token, sent in every report; Java must echo it back as `target`.

## Editable-field detection (L12–26)
- L12: `selector` for dropdown/menu popups (listbox/menu roles, Bootstrap `.dropdown-menu`, Radix, MUI Menu/Popover) — used by the menu-fitting code (chunk 2).
- L13–18 `editable(el)`: accepts `TEXTAREA`, `isContentEditable`, or `INPUT` whose type matches `^(text|search|email|url|tel|number|password)?$` (empty type = text). Excludes button/checkbox/radio/file/date/etc.
- L19–26 `active()`: starts at `document.activeElement`, walks **at most 8** shadow-root hops via `el.shadowRoot.activeElement` (L21–24), then filters through `editable()`. Returns null if the focused element isn't a supported editable.

## Reporting (L27–35)
- L27–30 `snapshot(el)`:
  - `value` = `el.value` if defined, else `textContent` (contentEditable path).
  - `pw` = `el.type==='password'`; if password: `v` forced to `''`, `pw:1`; otherwise `v` = value sliced to **4096 chars**, `pw:0`. `length` = full length (L29) — Java gets length without content for passwords.
  - Payload: `{ok:1, doc:documentId, frame:frameId, field:fieldId(el), focused:hasFocus?1:0, v, pw, length}`.
- L31–35 `reportField()`:
  - Bails if `document.hasFocus()` is false (L32).
  - `el=active()`; bails if null.
  - **Emits to Java:** `window.Buccaneer.editableFocused(frameId, JSON.stringify(snapshot(el)))` (L34) — the field-reporting event. Java's contract: store `frameId` + field id as the "focused field"; this is what the custom keypad types into.
  - Note: `field` in the report is `fieldId(el)` of the *currently active* element — the id Java later passes back as `id`.

## fieldState — read-back (L36–51)
- L37–51 `fieldState(target,id,depth)`: read-only, depth-limited to 5 (L38).
  - If `target===frameId` (L39): requires (a) `window.Buccaneer.activeInputFrame()===frameId` (L41 — Java must report which frame it considers the input owner), (b) `active()` non-null, (c) `document.hasFocus()`, (d) `fieldId(el)===id` (L42). Returns snapshot JSON, else `''`.
  - Otherwise recurses into iframes (L44–49): only frames with `contentDocument` (same-origin) and `__tvUi` present; first non-empty result wins.
  - **Java usage:** polling read-back of field value after typing; `''` = stale/unreachable.

## perform() — synthetic typing (L52–76)
- L53: guard `!el || el.disabled || el.readOnly` → `'none'`.
- L54: maps action → `inputType`: insert→`insertText`, clear→`deleteByCut`, delete→`deleteContentBackward`.
- L55: dispatches `beforeinput` (bubbles, **cancelable**); if page calls `preventDefault` → returns `'blocked'` (L55). This is the only page-veto point.
- L56–61 **contentEditable branch**:
  - clear: select all contents via Range (L57).
  - `document.execCommand('insertText'|'delete', ...)` (L58); if rejected → `'blocked'`.
  - `reportField()` then `'ok'` (L60).
- L62–75 **input/textarea branch**:
  - L62–63: `start/end` from `selectionStart/End`; if null (e.g. number input quirk) → end of value.
  - L65: `clear` → select whole value.
  - L66: `delete` with collapsed selection at position >0: step back one char; **extra step-back if the char is a UTF-16 low surrogate preceded by a high surrogate** (surrogate-pair backspace; regex on `\uDC00-\uDFFF` / `\uD800-\uDBFF`).
  - L67: `maxLength` enforcement for insert (truncates `insert` to fit).
  - L68: compute `next` value string.
  - L69–71: **set value through the prototype's `value` setter** (`HTMLTextAreaElement.prototype` / `HTMLInputElement.prototype` descriptor) so page `value` setters/observers fire; fallback plain assignment.
  - L72: `setSelectionRange` to after inserted text (try/catch).
  - L73–74: dispatch `InputEvent('input', {bubbles, inputType, data})`; on failure (old WebView InputEvent quirks) fall back to plain `Event('input')`.
  - L75: `reportField()` → `'ok'`.
- Return values: `'ok' | 'blocked' | 'none'`.

## edit() — top-level action dispatcher (L77–141)
- L78: action whitelist `insert|delete|clear|submit` → else `{status:'unsupported'}`.
- L79: depth > 5 → `{status:'unreachable'}`.
- L80: `owns = target===frameId && window.Buccaneer.activeInputFrame()===frameId` — **stale-check core**: this frame must both be the target AND be the frame Java says owns input.
- L81–133 if `owns`:
  - L85: `el = active() || byId(id)` — prefer live focus; fall back to the recorded field element.
  - L86–87 **stale conditions** → `{status:'stale'}`: no el, no id, `fieldId(el)!==id` (live focus is a *different* field than the recorded one), `!el.isConnected` (element removed from DOM), `el.disabled`, `el.readOnly`, or (contentEditable && el!==active() — contentEditable needs the live selection, so recorded-element fallback is disallowed for it).
  - L88 comment: validate before any action; never fall back after a side effect.
  - **submit path (L90–126)** — see chunk notes: dispatches synthetic `keydown Enter` (L103, `isTrusted===false`), then `keypress` only if keydown not prevented (L104). `proceed` is **deliberately NOT used to gate the form submit** (L91–101 comment documents the regression: old `form && proceed` gate caused "typing works but Enter does nothing" when React/SPA handlers `preventDefault` the synthetic key without searching).
    - L105: `form = el.form` (null for contentEditable / formless widgets).
    - L106–122 **with form**: find first enabled `BUTTON|INPUT[type=submit]` submitter (L108–111); prefer `form.requestSubmit(submitter?)` (L112–114); fallback (L115–119): create hidden temporary `button[type=submit]`, append to form, `click()` in try/finally, remove — preserves native validation + submit handlers. Then `keyup Enter` (L120), return `{status:'submitted'}`.
    - L123–125 **no form**: dispatch `keyup` (L124), return `{status:'key-dispatched'}` — best-effort for formless search widgets; no forced navigation.
  - **insert/delete/clear path (L127–131)**: `perform(...)` → snapshot with added `status: applied|blocked|unsupported` (L129) and `stillActive: el.isConnected && active()===el ? 1:0` (L130). Returns snapshot JSON.
  - L132: any exception → `{status:'uncertain'}` (Java should treat as "verify via fieldState").
- L134–140 **iframe recursion** (when this frame doesn't own input): iterate `document.getElementsByTagName('iframe')`; only same-origin frames (`contentDocument` accessible) with `__tvUi`; first frame whose result status ≠ `'unreachable'` wins (L138). Else `{status:'unreachable'}`.

## type() — legacy/simple wrapper (L142–155)
- L142–155 `type(action,text,target,depth)`: depth ≤5. If `target===frameId`: `el=active()`; calls `edit(action,text,target, el?fieldId(el):'', depth)` (L146) — note it passes the **live** active field's id, not a recorded one; returns `'ok'` iff parsed status is `'applied'`, else `'none'`.
- L149–154: recurses iframes; first `'ok'` wins; else `'none'`.
- **Java usage:** simpler entry point (likely `type()` JS interface) vs. `edit()`; both exist — Java may use either.

## Menu fitting (L156–170, continued in chunk 2)
- L156: `fitted=[]` — records of style overrides applied to menu/dropdown elements.
- L157–168 `restoreMenus()`: for each saved element, for each saved property: **restore only if the current value still equals the value we applied AND priority is still `important`** (L163) — i.e., don't clobber later site-authored changes. Restores original value/priority or removes property. Clears `fitted`.
- L169–170 `setFit(el,name,value)` start: finds existing `fitted` entry for the element (L170) — continuation in chunk 2.
# tv_ui.js — chunk 2 (lines 171–335)

## setFit completion (L171–177)
- L171: new `fitted` entry `{el, props:[]}` if none for this element.
- L172–173: find-or-create property record; on first touch saves **original** `value`/`priority` (L173), `applied:''`.
- L174: applies `setProperty(name,value,'important')`.
- L175–176: `prop.applied` = the value **actually stored** after Chromium canonicalization (fractional px etc.) — restore compares against this, not the requested string.

## Menu discovery (L178–218)
- L180: state: `menuTrigger` (last trusted-click trigger), `menuBefore` (popups already present at click time), `structuralMenus` (learned role-less popups, kept across scans).
- L181–197 `nearbyPopups(trigger)`: discovers **newly opened** plain-div popups near the trigger.
  - L183: walks **up to 3** ancestor levels from `trigger.parentElement`, stopping at body/documentElement.
  - L185: **hard cap 64 nodes** checked (`checked<64`); L187: first 8 children get one level of grandchildren pushed (bounded BFS-ish).
  - L188: candidates must be `DIV|UL|OL|NAV|SECTION`, not the trigger/its ancestors, not already found.
  - L189–191: `getComputedStyle` + `getBoundingClientRect`; must be `position:absolute|fixed`, visible, `width>=60`, `width <= 0.9*innerWidth`, `height>=20`, and contain **no** `video,iframe,canvas,input,textarea`.
  - L192: must contain **≥2** of `button, a[href], [role=option|menuitem|menuitemradio]` — i.e. looks like a menu, not any div.
- L198–201 `rememberMenuTrigger(target)`: `target.closest('button,[role="button"],[aria-haspopup]')`; snapshots `menuBefore = nearbyPopups(trigger)` at click time.
- L202–218 `menuCandidates()`:
  - L203: base set = `document.querySelectorAll(selector)` (L12 selector: listbox/menu roles, Bootstrap, Radix, MUI).
  - L204–211: adds elements referenced by `[aria-expanded="true"][aria-controls|aria-owns]` triggers (up to 48 triggers, ids split on whitespace, `getElementById`).
  - L212: prunes `structuralMenus` to still-connected & visible.
  - L213–215: `nearbyPopups(menuTrigger)` minus `menuBefore` = **newly opened** popups; added to `structuralMenus` (cap 8).
  - L216: merges structural menus in. Returns combined list.

## fitMenus() (L232–273)
- L235–239: **scroll-position preservation**: before restoring old fits, records `scrollTop/Left` of every previously-fitted element (removing `max-height` can clamp scrollTop to 0 — L233–234 comment).
- L240: `restoreMenus()` (chunk 1).
- L241–242: `menus = menuCandidates()`, cap **24** menus per pass; `h = innerHeight || 720`.
- L243–244: skip too-small / hidden / containing `video,iframe,canvas`.
- L245–246: wrapper = Radix popper wrapper, else the element itself if positioned, else positioned parent.
- L247–248: `verticalBounds(el,h)` (L219–231: safe band = 5% of viewport min 12px; walks **up to 16** parents; any parent with clipping overflow or `contain: paint|strict|content` tightens bounds to its client area, scale-corrected, ±8px padding). Skip if band <40px or menu already inside bounds.
- L249–253: if wrapper ≠ el, wrapper must not contain interactive elements outside the menu (safety: don't move a wrapper that would drag other controls).
- L254–257: scale factors (CSS px vs physical px, for transformed popups); bail if non-finite.
- L258: inset = padding+border for content-box elements.
- L259–263: applies via `setFit`: `min-height:0`, `max-height:min(siteMax, available)` (L260–261 — **never exceeds the site's own max-height**), `overflow-y:auto`, `overscroll-behavior-y:contain`.
- L264–265: if menu still overflows the band, shifts wrapper `margin-top` by the overflow amount (scale-corrected, added to existing margin).
- L268–271: restores saved scroll positions.
- L272: returns count changed.

## scrollAt() (L274–295)
- L274–286 `scrollAxis(el,amount,vertical)`: from target element up to root, finds first ancestor with `overflow(auto|scroll|overlay)` and scrollable extent >1px, scrolls it, returns true. **Keeps boundary scrolling inside the menu, not the page** (L281). Crosses shadow roots via `getRootNode().host` (L283).
- L287–295 `scrollAt(x,y,dx,dy)`:
  - L288: **calls `fitMenus()` first** (every scroll re-fits).
  - `elementFromPoint(x,y)`; descends up to **8** shadow roots (L289).
  - L290–293: if IFRAME: scale-corrected coordinate transform, recurse into `contentWindow.__tvUi.scrollAt(...)`; returns `'frame'` (even on cross-origin failure — the frame *is* the target).
  - L294: else `'nested'` if a nested scroller moved, `'none'` otherwise.
  - Return values to native: `'ok'` never — actually: `'frame' | 'nested' | 'none'`.

## playback() (L296)
- True if any `video`/`audio` is playing (`!paused && !ended && readyState>=1`). Native uses this to suppress/ignore key routing while media plays (seek vs. typing disambiguation).

## Public API (L297)
- `window.__tvUi = {scrollAt, fitMenus, restoreMenus, reportField, fieldState, edit, type, playback}` — this is the cross-frame + native-facing surface.

## Timers & observers (L298–327)
- L299: `menuObserver=null, menuExpiry=0, menuFitTimer=0, menuWatch=0, menuScans=0, fieldTimers=[]`.
- L300–305 `stopMenuWatch()`: **generation counter** `menuWatch++` invalidates all pending callbacks (they check `watch!==menuWatch`); disconnects observer; clears expiry/fit timers and all `fieldTimers`.
- L306–323 `watchMenus()`:
  - L308: `scan()`: no-op if generation changed; **max 10 scans** (`menuScans++<10`).
  - L309–312: `schedule()`: coalesces mutations — one `setTimeout(scan, 120ms)` at a time (`menuFitTimer` guard); stops scheduling after 9 scans.
  - L313–318: `MutationObserver` on `document.documentElement`, `{childList:true, subtree:true}` — **self-limiting: dies by expiry (5s), by scan budget (10), or on next trusted click / Escape / pagehide**. No permanent observer.
  - L319–321: two `fieldTimers` at **150ms** and **650ms**: `reportField(); scan();` — the **viaClick 1s window**: after a trusted click, field reports are re-emitted for ~1s so Java learns the field the user's click focused even if focus settles late.
  - L322: `menuExpiry` at **5000ms**: final scan, then `stopMenuWatch()`.
- L324: `pagehide` → stop watch, restoreMenus, clear structural state.
- L325: `resize` → `fitMenus` (permanent listener; cheap when nothing fitted).
- L326: `focus` → `setTimeout(reportField,0)`.
- L327: `keydown` (capture): **Escape** → stop watch + `setTimeout(fitMenus,0)`.
- L328: `focusin` (capture) → `setTimeout(reportField,0)` — the main field-report trigger (any focus, trusted or synthetic).
- L329–332: `focusout` (capture): if the leaving element is editable → **emits `window.Buccaneer.editableBlurred(frameId, documentId, fieldId(el))`** (L331).
- L333: `input` (capture) → `reportField` (reports on every keystroke, including our own synthetic input).
- L334: `click` (capture): **only if `e.isTrusted`** → `rememberMenuTrigger(e.target); watchMenus();` — this is the click that starts the 5s menu-watch + 1s field-report window. Synthetic clicks (from our own code) never trigger it.

## Diagnostic events emitted to Java (complete list)
1. `window.Buccaneer.editableFocused(frameId, snapshotJSON)` — L34. Triggers: focusin (L328), focus (L326), input (L333), after every `perform()` (L60/L75), and the two post-click timers (L319–321). Snapshot: `{ok,doc,frame,field,focused,v,pw,length}`.
2. `window.Buccaneer.editableBlurred(frameId, documentId, fieldId)` — L331, on focusout from an editable.
3. `window.Buccaneer.activeInputFrame()` — L41/L80, **read** (Java→JS query) for the stale check.
4. `window.Buccaneer.currentDocument()` — L6, read at init for `doc` field.

## What Java must do with each report
- On `editableFocused(frameId, json)`: parse; store `frame` + `field` as the active input target; set `activeInputFrame()` to return this `frameId`; open the custom keypad only for this field. Password fields: `pw:1`, `v:''` — keypad must not expect content; `length` is the true length.
- On `editableBlurred(frameId, doc, field)`: close/disarm the keypad for that field.
- Before each `edit()`/`fieldState()` call Java must make `activeInputFrame()` return the frameId from the latest `editableFocused` — otherwise every call returns `stale`/`unreachable`.
- `edit()` result statuses: `applied` (verify via returned snapshot or `fieldState`), `blocked` (page vetoed via beforeinput), `stale` (field changed/removed — re-report needed), `uncertain` (exception — re-read via `fieldState`), `submitted` (form submitted), `key-dispatched` (no form; best-effort), `unsupported`, `unreachable` (no frame had the field).

## PowerVR / performance risk assessment
- MutationObserver subtree on documentElement for ≤5s after each trusted click (L314–316): bounded by 120ms coalescing + 10 scans; each `fitMenus()` does ≤24 menus × (getComputedStyle + getBoundingClientRect + ≤16 ancestor walks) — moderate layout thrash on weak GPU/CPU; acceptable because bounded and self-terminating.
- `nearbyPopups` capped at 64 nodes; `menuCandidates` capped at 48 triggers / 8 structural / 24 fitted per pass.
- No permanent observers/polling (L298 comment); `resize`/`focus`/`input`/`click` listeners are O(1) dispatch.
- `scrollAt` calls `fitMenus()` on every scroll event (L288) — if Java scrolls rapidly this re-fits each frame; bounded by the same caps but is the hottest path.
- `getComputedStyle`/`getBoundingClientRect` per candidate can force layout on PowerVR; all loops are hard-capped, so worst case is bounded.
# seek_click.js — full file (lines 1–72)

## Purpose & contract
- **Invoked imperatively by native code** as `seek_click(fx, fy, mode)` — an IIFE receiving `(fx, fy, mode)` (L3). Called **only after an explicit Flixer pointer click** (L1 comment). No install-time code, no listeners, no observers, no CSS, no timers (L1–2). Pure function: runs once, returns a string to the native caller.
- `fx`, `fy` are **normalized viewport coordinates** (0..1) — validated at L70: must be finite, `0<=fx<=1`, `0<=fy<=1`, else `'none'`.
- `mode`: `'probe'` = detect only (returns `'track'` if a seek track is under the point, without seeking); any other value = perform the seek.
- **Return values to native:** `'seeked'` (seek applied & verified), `'none'` (no track / not seekable / refused), `'track'` (probe: track found), `'frame'` (hit point is inside an iframe that couldn't be traversed — cross-origin or no `__tvUi`-style access).

## seekAt(win, x, y, depth) (L5–69)
- L6: depth cap **3** (nested iframes) → `'none'`.
- L7–8: `doc.elementFromPoint(x,y)`; null → `'none'`.
- L9–19 **iframe branch**:
  - L11: no `contentDocument` (cross-origin) → `'frame'`.
  - L12–15: scale factors `sx/sy` = rendered size / layout size (handles CSS-transformed frames); invalid → `'none'`.
  - L16–17: coordinate transform: convert to frame-local CSS px, subtract clientLeft/Top, then **map into the iframe's own viewport units** (`* contentWindow.innerWidth / max(1, clientWidth)`); recurse depth+1.
  - L18: any exception → `'frame'`.
- L20–23: descend **up to 4 open shadow roots** at the hit point (`shadowRoot.elementFromPoint`), no unrelated traversal.
- L24–36 **track discovery** — walk **up to 7** ancestors from the hit element:
  - L26–27: collect `role`, `aria-label` + `data-plyr` + `name` attributes (lowercased).
  - L28: className (string only — SVG `className` is an object, guarded).
  - L29: **veto**: if name+class matches `/volume|brightness|speed|quality/` → `'none'` — never hijack volume/brightness/speed/quality sliders.
  - L30–31 `known`: class contains `vjs-progress-holder` (Video.js) / `jw-slider-time` (JW Player) / `plyr__progress` (Plyr), or `data-media-time-slider` attribute, or tag `MEDIA-TIME-SLIDER` (native media element).
  - L32 `labelled`: `role=slider` or `input[type=range]` whose label matches `/seek|timeline|progress|playback position|time/`.
  - L33: first match → `track`, break.
  - L35: **safety**: hitting a `BUTTON` while walking up → `'none'` (buttons in a control bar must not become seeks).
- L37: no track → `'none'`.
- L38–39 **shape/position gate**: track rect must be `width>=80`, `width >= 3*height` (horizontal bar), and the click point must be **inside** the track rect.
- L40: `mode==='probe'` → return `'track'` (no seek).
- L41–50 **player association**: walk **up to 9** ancestors from the track;
  - L45: reaching body/documentElement without finding a player → `'none'`.
  - L46–48: first container with **exactly one** `<video>` wins; **>1 videos → `'none'`** (never seek a different player/ad that merely exists in the document — L41–42 comment).
  - L50: video must have finite `duration > 0` (excludes live streams with no duration).
- L51: `fraction = clamp01((x - r.left) / r.width)`.
- L52: **RTL support**: if track's computed `direction==='rtl'`, `fraction = 1-fraction`.
- L53: `wanted = duration * fraction`.
- L54–64 **seekable-window respect** (live/DVR): if `video.seekable` is non-empty and `wanted` is outside all ranges, snap to the **nearest range edge** (L56–63) — never jumps outside buffered timelines.
- L65–68: apply:
  - L66: skip if already within `max(1s, 0.1% of duration)` of target.
  - `video.currentTime = wanted`.
  - L67: **verify**: re-read `currentTime`; return `'seeked'` if within tolerance, else `'none'` (e.g. page prevented the seek).
  - L68: exception → `'none'`.

## Host conditions
- **No hostname gating at all** — works on any page; host specificity comes from the player-widget heuristics (Video.js/JW/Plyr/native media controls) and the Flixer click that precedes the call.
- Practical targets: any page with a recognized time slider under the click point.

## Timers/observers
- **None.** Single synchronous pass. No DOM mutations other than `video.currentTime`.

## DOM operations & PowerVR risk
- `elementFromPoint` (forces layout), `getBoundingClientRect` ×2, `getComputedStyle` ×1, `querySelectorAll('video')` up the tree — all O(small) and bounded by the depth caps (3/4/7/9). No layout thrash risk; safe on PowerVR.
- No style writes, no observers, no retained references — zero memory footprint after return.

## What breaks if changed
- Removing the L29 veto → volume/brightness/speed clicks would seek.
- Removing L35 button veto → clicking control-bar buttons (play/pause) would seek.
- Removing the single-video rule (L47) → clicking one player's slider could seek a different player or an ad.
- Removing seekable clamping (L55–64) → DVR/live streams jump to unbuffered times (stall/black frame).
- Changing the return strings breaks the native contract (Java branches on `'seeked'/'track'/'frame'/'none'`).
- The L16–17 coordinate math assumes the iframe's content viewport is scaled uniformly; non-uniform transforms would mis-map — currently guarded only by the sx/sy>0 check.
# fmhy_theme.js — full file (lines 1–23)

## Purpose
Forces the FMHY site (a VitePress-based site) to follow the **app's** dark/light mode instead of the site's own default. Comment (L1): FMHY's own theme only — no filters, no effect on other origins or WebView-level darkening.

## Host condition (L4–5)
- `host` = lowercased `location.hostname`, trailing dot stripped (L4).
- L5: **gates on `/(^|\.)fmhy\.net$/`** — only `fmhy.net` itself or any subdomain (`*.fmhy.net`). Any other host: the IIFE returns immediately, zero side effects.

## Dark-mode source (L6–7)
- `dark = !!window.Buccaneer.isDarkMode()` — native bridge query; **if the bridge is missing/throws, the whole script aborts** (L7 `return`) — no theme forcing without the app.

## localStorage seeding (L8–11)
- Sets `vitepress-display-mode` and `vitepress-theme-appearance` to `'dark'`/`'light'` (L9–10) — VitePress reads these keys to pick its theme, so the site's own JS agrees with us.
- Wrapped in try/catch (L8/L11): localStorage may be unavailable (private mode / blocked); failure is silently ignored — the classList path below still applies.

## apply() (L12–17)
- Toggles `dark`/`light` classes on `document.documentElement` to match `dark` (L15).
- Returns `true` only if `document.documentElement` exists (L14–16) — i.e., only once the `<html>` node is present.

## MutationObserver — self-disconnecting (L18–22)
- If `apply()` fails at document-start (no `<html>` yet — normal for document-start injection):
  - L19: creates a `MutationObserver` whose callback calls `apply()` and **`observer.disconnect()` on the first successful apply** — the observer lives only until `<html>` exists, then is gone.
  - L20: observes `document` with `{childList:true, subtree:true}`.
  - L21: belt-and-suspenders: a **`{once:true}` DOMContentLoaded** listener also disconnects the observer and re-applies.
- So the observer is bounded by: first successful `apply()` (typically the moment `<html>` is inserted), or DOMContentLoaded, whichever first. **No permanent observer, no polling.**

## Timers
- None.

## DOM operations & PowerVR risk
- `classList.toggle` ×2 on `<html>` (L15) — trivial. One transient MutationObserver on the whole document until `<html>` exists (L19–20) — negligible; disconnects within milliseconds of parse start. No layout thrash, no retained state. Safe on PowerVR.

## What breaks if changed
- Widening the host regex (L5) → other sites' themes get force-toggled (the L1 comment's guarantee).
- Removing the `isDarkMode` abort (L7) → would force a theme with no app signal.
- Making the observer permanent (removing L19's `disconnect()`) → permanent whole-document subtree observation on fmhy.net = continuous CPU/layout cost on a weak device, and it would re-toggle classes on every DOM mutation.
- Removing the localStorage keys (L9–10) → VitePress's own script may override our classes on load, causing a theme flash/fight.
- Removing the DOMContentLoaded fallback (L21) → if `apply()` never succeeds before DOMContentLoaded (edge case), the observer would stay attached until then anyway; the fallback is the safety net that guarantees disconnection.
