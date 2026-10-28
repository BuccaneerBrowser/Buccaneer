# Part 3 — Guard & Utility JS Assets (line-level analysis)

Scope: the four guard/utility assets of Buccaneer 2.3.1, read line-by-line:

| File | Lines | Injection |
|---|---|---|
| `app/src/main/assets/popup_guard.js` | 538 | document-start, all origins, all frames (`MainActivity.kt:786`) |
| `app/src/main/assets/flixer_image_guard.js` | 286 | document-start, all origins, self-gated (`MainActivity.kt:801`) |
| `app/src/main/assets/seek_click.js` | 72 | **not installed** — evaluated on demand only (`MainActivity.kt:2106, 2134`) |
| `app/src/main/assets/fmhy_theme.js` | 23 | document-start, `https://fmhy.net` + `https://*.fmhy.net` only (`MainActivity.kt:805`) |

All four are IIFEs with `'use strict'`. Chromium 118 / API 25 constraint honored: no `??` / `?.` anywhere.

---

## 1. popup_guard.js (538 lines)

Port of the Chrome extension `content.js` v2.0 (header, L1–14). Runs at document-start in every frame. ON/OFF state lives in the app (Menu > Popup Guard), read live through `window.Buccaneer.isGuardEnabled()` so toggling needs no reload; defaults to **ON** when the bridge is missing. The old 7movies.ac redirect rule is enforced natively in `PopupGuard.kt` (L11–13).

### 1.1 Bootstrap, bridge helpers (L15–48)

- **L18–23 — idempotency guard.** `window.__popupGuardInstalled` set via `Object.defineProperty` (L20, non-configurable by default), plain-assignment fallback (L22). Second injection in the same frame is a no-op.
- **L25–32 — `enabled()`.** Returns `window.Buccaneer.isGuardEnabled() !== false` when the bridge exists (L27–29); any exception or missing bridge → `return true` (L30–31). Only an explicit `false` from native disables the guard.
- **L34–40 — `diagnostic(event, data)`.** Best-effort `window.Buccaneer.diagnosticEvent(String(event), JSON.stringify(data || {}))` (L37). Never throws.
- **L42–48 — `report(what)`.** Calls `window.Buccaneer.blocked(what)` (L45). Native side (`MainActivity.kt:1420` `blocked()` → `blockedUi.offer` → `noteBlocked`, `MainActivity.kt:1498–1506`) records the **`popup_guard_action`** diagnostic (`{what, count}`), increments the blocked counter, and shows a toast throttled to one per 3000 ms.

### 1.2 The reactOnClick video site/vidcore workaround (L50–153)

**Problem (L54–59 comment):** the vidcore player frame's ad script suppresses the synthetic `click` event that follows `pointerup`/`touchend`, so player buttons (pause, seek, settings, menu items) never receive a click and their actions never fire.

- **L52–53 — state.** `adLayerMarked` (element that received the last pointer event) and `adLayerMarkedAt` (timestamp). **`adLayerMarkedAt` is written (L110) but never read — vestigial.**
- **L60 — `isPlayerFrame = (window !== window.top)` — declared but never used — vestigial.** The live gate is `isVidcoreFrame` (L137).
- **L61–83 — `findReactOnClick(el)`.** Walks up from `el` to document, **max 15 hops** (L67 `hops < 15`). At each element, `Object.keys(node)` (L69) is scanned for a key starting with **`__reactProps`** (L71); if `props.onClick` is a function it returns `{el: node, handler: props.onClick, hops}` (L74). React event delegation often puts the handler on a *parent*, hence the upward walk.
- **L84–104 — `reactOnClick(el)`.** Emits **`react_onclick_called`** diagnostic (L88–92: `tag`, `cls` truncated to 60 chars, `hops`). Invokes the handler with a synthetic event (L93–100): `{preventDefault: noop, stopPropagation: noop, stopImmediatePropagation: noop, nativeEvent: new MouseEvent('click'), target: el, currentTarget: found.el}`. Returns `true` on success, `false` if no handler found or it threw.
- **L105–133 — `markAdLayer(e)`.** Bound to pointer/mouse events (see below). Records `e.target` if it's an element (L107–110). If target is **not** `VIDEO`/`AUDIO` (L112):
  - **L114–117 — neutralizes `preventDefault`**: for `pointerup`/`touchend`/`pointerdown`/`touchstart` it overwrites `e.preventDefault` with a no-op function, so the ad script cannot suppress the follow-up click.
  - **L122–129 — the 300 ms deferred click**: on `pointerup` it stores the target and sets `setTimeout(→ reactOnClick(target), 300)` (L124–127), saving the handle in `deferredClickTimer` (L128). **Why:** if the native `click` fires on its own (ad script not suppressing it), the document-level capture click listener (L147–152) clears the timer → no double-trigger. If the ad script suppresses the click, the 300 ms timer fires and the button works anyway.
- **L137 — `isVidcoreFrame`** = hostname contains `vidcore` (case-sensitive substring).
- **L139–153 — listener installation, vidcore frames only.** For each of `touchstart, touchend, pointerdown, pointerup, mousedown, mouseup, click` (L140–141) a **capture-phase** `document.addEventListener(..., markAdLayer, true)` is installed (L142–144). Plus a capture-phase `click` listener (L147–152) that cancels `deferredClickTimer` when a native click fires. **L134–136 comment: installing this in every frame (incl. poster-grid site's top frame) interferes with page rendering and triggers GPU driver crashes on this Fire TV** — hence the vidcore-only gate.

### 1.3 `disableAdLayer()` — secondary tap-through sweep (L155–212)

Called from the `window.open` block path (L222). Disables the ad layer that initiated the popup, as a secondary fix (primary fix = the React onClick above).

- **L156–159:** no mark → emits **`disable_ad_layer_no_mark`** `{topFrame: 0|1}` and returns.
- **L163–167:** skips non-elements and `VIDEO`/`AUDIO` (**`disable_ad_layer_skipped_media`**, L165).
- **L168–171:** skips if the marked element is inside `button, [role="button"], input, select, video` (**`disable_ad_layer_skipped_control`**, L169) — real controls are never disabled.
- **L175–184:** finds the largest `<video>` rect (`document.getElementsByTagName('video')`, L177) → `vr`, `videoArea`.
- **L185–210:** walks **up to 12 ancestors** (L187 `hops < 12`) of the marked element. For each ancestor whose computed style is interactive (`pointerEvents !== 'none'`, `display !== 'none'`, `visibility !== 'hidden'`, L190), computes overlap with the video rect (L193–195). If the ancestor **covers ≥ 50% of the video area** (`>= videoArea * 0.5`, L195), sets `node.style.pointerEvents = 'none'` (L197) and emits **`overlay_disabled`** (L198–205: `cls` 60 chars, `tag`, `pos`, `z`, `op`, `reason: 'popup_initiator'`). Rationale (L172–174): the ad layer may be a child of a full-viewport wrapper; the wrapper (or a sibling) is what intercepts the *next* tap.

### 1.4 Rule 0 — `window.open` block (L214–226)

- **L215** saves `nativeOpen`. **L216–226** replaces `window.open`: if `enabled()` → `console.warn('[Popup Guard] Blocked window.open:', url)` (L219), `report('a popup window')` (L220 → native `popup_guard_action` + toast), **`popup_window_open_blocked`** diagnostic (L221: `pageUrl`, `url`), `disableAdLayer()` (L222), **returns `null`** (L223). Otherwise delegates to native (L225).

### 1.5 Rule 1 — `target=_blank` rewrite (L228–239)

Capture-phase `click` listener (L231). If enabled and the click target is inside an `<a>` with `target === '_blank'` or `'_new'` (L235), rewrites `a.target = '_self'` (L236) so the link opens in the same view, and emits **`popup_target_rewritten`** (L237: `pageUrl`, `url`).

### 1.6 `killOverlays()` — the full-DOM scanner (L251–420)

History (L241–250): the old rule only matched `position:fixed + opacity<0.3 + z-index>1000` (or wrapping an iframe); the vidcore ad layer (position:absolute, opacity 1, low z) never matched. The current version detects the *real* condition: covers the video, above it in stacking order, and is not real player UI.

**Setup:**
- **L252:** bails if `!enabled()`.
- **L256–264:** finds the largest visible `<video>` (`getElementsByTagName('video')` + `getBoundingClientRect`) → `video`, `videoArea`.
- **L265:** `haveVideo = video && videoArea >= 10000` (≈ 100×100 px minimum).
- **L267–268:** `vr` = video rect.
- **L269:** `viewportArea = innerWidth * innerHeight`.
- **L280–292 — main-player-iframe identification.** Iterates all iframes; skips `display:none`/`visibility:hidden` (L286); skips empty src, `about:blank`, or `srcdoc*` srcs (L287–288); keeps the **largest by rect area** with a real content URL → `playerIframe`. Rationale (L271–279): streaming sites put the player iframe at z-index 2147483647 + `position:fixed` in fullscreen — the *same* z-index ad injectors use; the video inside is cross-origin so `containsVideo`/`hasControls` can't see it; a naive high-z rule killed the player (black screen with audio, dead seek bridge — video site 2026-10-21). Ad click-catcher iframes use `about:blank`/`srcdoc` and are not the largest, so they remain catchable.

**Element walk (L294–419):** `document.querySelectorAll('div, span, a, iframe')` (L295) — **full-DOM walk, the PowerVR-risky operation**. Per element:
- **L298:** skip the video element itself.
- **L300–302:** computed style; skip `pointerEvents === 'none'`, `display === 'none'`, `visibility === 'hidden'`.
- **L304–308:** rect, `floating = position fixed|absolute` (L305), `z = parseInt(zIndex)` (L306), `area` (L308).
- **L310–311:** `text` = lowercased `textContent` — **kept for reconnaissance logging only**; the text-based kill rule was removed (see L321–329).

**The four kill signals (L312–369):**

1. **`isIframeAd` (L330–331) — floating-iframe rule:**
   `el.tagName === 'IFRAME' && floating && hasZ && z >= 1000 && area >= 1000 && (viewportArea <= 0 || area < viewportArea * 0.25)`
   i.e. a floating iframe with **z ≥ 1000**, **area ≥ 1000 px²**, and **area < 25% of the viewport**. Works with or without a video (home pages, pre-roll). The high-z + small-size bar keeps a large low-z player iframe safe.
2. **`coversVideo` (L360–369) — covers-video rule** (only when `haveVideo && vr`):
   - `positioned = floating || position === 'relative'` (L364) — only a positioned element can paint above the video.
   - `above = positioned && (s.zIndex !== 'auto' ? z >= 0 : true)` (L365).
   - `overlapArea` = intersection of element rect and video rect (L366–367).
   - **`coversVideo = above && overlapArea >= videoArea * 0.1`** (L368) — ≥ **10% of the video area**.
3. **`isHighZAd` (L347) — high-z rule:**
   `floating && hasZ && z >= 1000000 && area >= 1000 && !isPlayerChain`
   i.e. positioned element with **z ≥ 1,000,000** (ad injectors force the 32-bit max 2147483646/7; legitimate UI never reaches 1e6) and area ≥ 1000 px². Added 2026-10-21 for video site "baseball" popups: z=2147483646 fixed DIVs with an injected `<style>` block, no controls, no video — slipped through both `isIframeAd` and `covers_video` (L340–342).
4. **`coversViewport` (L358–359) — full-viewport rule:**
   `floating && hasZ && z >= 1000 && viewportArea > 0 && vpOverlap >= viewportArea * 0.9 && !isPlayerChain`
   where `vpOverlap` = intersection with the viewport (L356–357). I.e. positioned, **z ≥ 1000**, covering **≥ 90% of the viewport**. Added 2026-10-22 for full-viewport click-catchers at z 9999/1000 on non-video pages (home, show/season, pre-roll) where `covers_video` can't fire (video site/removed poster site, video site — L348–355).

**Main-player-iframe exemption (L343–347):**
`var isPlayerChain = !!(playerIframe && el.contains && el.contains(playerIframe));`
An element is exempt if it **contains the player iframe** — which includes the player iframe itself (an element contains itself) and its entire **container chain** (site fullscreen may put the player's wrapper at high z too, L344–345). An injected ad is never the player or an ancestor of it, so the exemption cannot spare a real ad. Applied to `isHighZAd` (L347) and `coversViewport` (L359).

**Reconnaissance (L370–390):** if *no* rule matched, a floating element with `z >= 100 && area >= 1000` that hasn't been logged yet gets `data-fslogged="1"` set (L373–374) and emits **`floating_seen`** once each (L377–385: `tag`, `src` (iframe, 80 chars), `pos`, `z`, `x`, `y`, `w`, `h`, `text` 60 chars) — so a popup that slips through can be identified from the session log.

**Protections (L391–401):**
- **L396–398 — `containsVideo`:** `el.querySelector('video')` → **skip**. An element containing a video is player UI. (An iframe ad is opaque to `querySelector`, so this can never protect an ad iframe.)
- **L399–401 — `hasControls`:** `el.querySelector('button, [role="button"], input, select')` → **skip if `hasControls && !isIframeAd`**. Elements with buttons/inputs/selects are page UI, not ad overlays — ad-like words in a synopsis must not override that (L391–395). This is the structural replacement for the removed text-based rule (L321–329): the old rule killed movie synopses and the Play button on video site/lazy-load poster site because the hero content layer is `position:absolute`, carries the Play button, and its synopsis contains words like "offer"/"download" (2026-10-21 "Bad Apples" report).

**The kill (L402–418):**
- **L405:** floating elements → `el.style.display = 'none'` (out-of-flow popups are hidden, not just click-through).
- **L406:** in-flow layers covering the video → `el.style.pointerEvents = 'none'` (taps pass through to the player).
- **L407–418:** emits **`overlay_killed`** (L409–416: `cls` 60 chars, `tag`, `pos`, `z`, `reason` = `floating_iframe` | `high_z_ad` | `viewport_catcher` | `covers_video` (L414), `text` 60 chars).

### 1.7 `killCatchers()` — the poster-grid site MINIMAL scan (L431–506)

Why (L422–430): the full-DOM walk in `killOverlays` (`querySelectorAll` over every div/span/a/iframe) triggers the **PowerVR driver crash during poster-grid site's intro animation**, so the heavy scanner stays OFF on poster-grid site. But poster-grid site still gets inline ad click-catchers that the `window.open` guard can't catch. This checks only a handful of elements (body's direct children + iframes) and applies only the cheap rules. CPU-cheap, no DOM-walk load.

- **L432–434:** bail if `!enabled()` or `viewportArea <= 0`.
- **L436–449:** identical main-player-iframe detection (largest iframe with real non-blank/non-srcdoc src).
- **L451–457 — restricted element set:** `body.children` (direct children only, L454) + all iframes (L456). **Not the whole DOM.**
- Per element (L458–505):
  - **L460:** skip `!el` and `el === playerIframe`.
  - **L461–463:** skip `pointerEvents none` / `display none` / `visibility hidden`.
  - **L464–466:** `floating = fixed|absolute`; **`if (!floating) continue;`** — only positioned elements are considered.
  - **L467–469:** `z`, `hasZ`, `area`.
  - **L472–481 — recon:** `hasZ && z >= 100 && area >= 1000 && !data-fslogged` → set `data-fslogged`, emit **`floating_seen`** (L474–480; note: no `text` field in this variant).
  - **L482:** `if (!hasZ || z < 1000) continue;` — the cheap "high-z" gate: **z ≥ 1000** required.
  - **L483 — `isIframeAd`:** `el.tagName === 'IFRAME' && area >= 1000 && area < viewportArea * 0.25`.
  - **L484–486 — `isViewportCatcher`:** `vpOverlap >= viewportArea * 0.9` (≥ 90% of viewport).
  - **L487:** continue unless one of the two matched.
  - **L489 — player exemption:** `if (playerIframe && el.contains && el.contains(playerIframe)) continue;` — player iframe and its container chain are never killed.
  - **L491–496 — protections:** `containsVideo` skip (L491–493); `hasControls && !isIframeAd` skip (L494–496).
  - **L497:** kill = `el.style.display = 'none'` (always hide — there is no in-flow branch because only floating elements pass L466).
  - **L498–504:** emits **`overlay_killed`** with `reason: 'floating_iframe' | 'viewport_catcher'`.

Note the asymmetry vs `killOverlays`: the minimal scan has **no `coversVideo` rule and no z ≥ 1,000,000 rule** — only the z ≥ 1000-gated iframe-ad and viewport-catcher rules (L427–428 comment: "cheap high-z and full-viewport rules only").

### 1.8 Scheduling (L508–537)

- **L516–518:** `__pgHost` = lowercased hostname with trailing dot stripped; **`__pgIsFlixer = /(^|\.flixer\.(gd|su)$)/`** — matches `flixer.gd`, `*.flixer.gd`, `flixer.su`, `*.flixer.su` (lazy-load poster site is NOT in this set — lazy-load poster site gets the full walk).
- **poster-grid site branch (L519–521):** `killCatchers()` immediately + `setInterval(killCatchers, 5000)`. **No MutationObserver.**
- **Other hosts (L522–536):** `killOverlays()` immediately; `scheduleScan()` debounces at **300 ms** (L525–531); a **MutationObserver** on `document.documentElement` with `{childList: true, subtree: true}` (L533–534) calls `scheduleScan`; plus `setInterval(killOverlays, 5000)` (L536) as a fallback for **style-only** changes (z-index/opacity changes don't mutate the DOM). L508–509 comment: scanning every element every 1500 ms was too heavy on big pages.

### 1.9 Diagnostic event catalog (popup_guard.js)

| Event | Line | Payload |
|---|---|---|
| `react_onclick_called` | 88–92 | `tag`, `cls` (60), `hops` |
| `disable_ad_layer_no_mark` | 157 | `topFrame` (0/1) |
| `disable_ad_layer_skipped_media` | 165 | `tag` |
| `disable_ad_layer_skipped_control` | 169 | `tag` |
| `overlay_disabled` | 198–205 | `cls` (60), `tag`, `pos`, `z`, `op`, `reason: 'popup_initiator'` |
| `popup_window_open_blocked` | 221 | `pageUrl`, `url` |
| `popup_target_rewritten` | 237 | `pageUrl`, `url` |
| `floating_seen` | 377–385 (full), 474–480 (minimal) | `tag`, `src` (iframe), `pos`, `z`, `x`, `y`, `w`, `h`, `text` (full only) |
| `overlay_killed` | 409–416 (full), 498–504 (minimal) | `cls` (60), `tag`, `pos`, `z`, `reason`, `text` (full only) |
| `popup_guard_action` | native, `MainActivity.kt:1499` (via `report()` → `Buccaneer.blocked`) | `what` ('a popup window'), `count` |

### 1.10 Timers / observers (popup_guard.js)

| Timer/observer | Period | Scope |
|---|---|---|
| deferred click `setTimeout` | 300 ms, per `pointerup` | vidcore frames only |
| `scheduleScan` debounce | 300 ms | non-poster-grid site hosts |
| `setInterval(killOverlays)` | 5000 ms | non-poster-grid site hosts |
| `setInterval(killCatchers)` | 5000 ms | poster-grid site hosts |
| `MutationObserver` (childList+subtree) | event-driven | non-poster-grid site hosts only |
| capture listeners (7 events + click cancel) | per input event | vidcore frames only |

### 1.11 DOM operations & PowerVR risk

- **`killOverlays`:** `querySelectorAll('div, span, a, iframe')` over the **entire DOM** + per-element `getComputedStyle` + `getBoundingClientRect` + `textContent` — the heavy operation. Runs on MutationObserver (debounced 300 ms) + every 5 s on every non-poster-grid site page. **This is the documented cause of the poster-grid site PowerVR crash** (L422–424, L510–515) — hence it is disabled on poster-grid site. On normal hosts it is the accepted cost.
- **`killCatchers`:** body's direct children + iframes only; per-element `getComputedStyle`/`getBoundingClientRect` — cheap.
- **`disableAdLayer`:** `getElementsByTagName('video')` + ≤12 ancestor hops with computed styles — cheap, event-driven.
- **`markAdLayer`:** per-pointer-event target handling, no DOM queries — cheap, vidcore only.

### 1.12 What breaks if a threshold/rule changes (popup_guard.js)

- **`z >= 1000` (isIframeAd L331, coversViewport L358, killCatchers L482):** lower → false positives on legitimate UI at z 500–999 (nav bars, dropdowns, z-999 modals get hidden). Raise above 1000 → false negatives: ad injectors on video site/removed poster site and video site use **z 9999/1000** (L351–352) — raising to e.g. 10000 would let full-viewport click-catchers through.
- **`area < viewportArea * 0.25` (L331, L483):** raise the fraction → misses large ad iframes (false negatives). Lower it → kills legitimate iframes that are < 25% of the viewport (e.g. a secondary video embed, a chat widget) — false positives. The 25% bar is what keeps the (non-largest) legitimate iframes from being treated as ad creatives.
- **`area >= 1000` (L331, L347, L373, L472, L483):** raise → misses small ad badges (false negatives). Lower → kills small legitimate positioned elements (corner badges, tooltips) — false positives.
- **`z >= 1000000` (isHighZAd L347):** lower (e.g. to 1000) → collides with normal high-z UI; the player-chain exemption would save the player but other site UI at z 10k–999k (some sites use z 99999 for legitimate overlays) could be killed. Raise above 2147483646 → the video site "baseball" popups (z=2147483646) slip through again.
- **`coversViewport >= 0.9` (L358, L486):** raise → misses 80–90% catchers. Lower → kills legitimate full-viewport UI without controls (loading splashes, video letterbox overlays) — the `containsVideo`/`hasControls` protections (L396–401) are what makes 0.9 safe; lowering the overlap bar makes those protections do more of the work.
- **`coversVideo >= 0.1` (L368):** raise → misses partial overlays (small ad bars over the video). Lower → kills legitimate UI overlapping 10–20% of the video (e.g. a corner "share" widget — though one with a button is protected by `hasControls`).
- **`videoArea >= 10000` (L265):** raise → `coversVideo` disabled for small players (false negatives on small-viewport players). Lower → the video-detection logic activates for tiny decorative videos.
- **`isPlayerChain` exemption removed (L346/L489):** the video site watch-iframe (z 2147483647 + position:fixed in fullscreen) is killed → **black screen with audio, dead seek bridge** (the 2026-10-21 incident, L271–279). This is the single most dangerous change in the file.
- **`hasControls` protection removed (L400–401, L495–496):** modals and the hero content layer (Play button) get killed — the 2026-10-21 "Bad Apples" class of false positives returns.
- **`containsVideo` protection removed (L397–398, L491–493):** the player container itself gets killed → black screen.
- **300 ms deferred click (L127):** raise → player buttons feel laggy (tap, wait). Lower → the native click may not have fired yet → **double-trigger** (button action fires twice, e.g. double-pause).
- **MutationObserver removed (L533–534):** DOM-inserted popups only get scanned every 5 s → up to 5 s of dead taps.
- **5000 ms interval removed (L536/L521):** style-only changes (ad script raising z-index without DOM mutation) are never re-scanned → persistent click-catcher.
- **killCatchers replaced by killOverlays on poster-grid site:** PowerVR driver crash during the intro animation (L422–424).
- **vidcore-only gate on markAdLayer removed (L137–139):** pointer listeners in every frame interfere with page rendering and crash the GPU driver (L134–136).
- **`window.open` null-return removed (L223):** popups open (or the WebView crashes trying to open a window on TV).
- **`_blank`→`_self` rewrite removed (L236):** new-tab links do nothing visible in the TV WebView (or open a dead popup).

---

## 2. flixer_image_guard.js (286 lines)

TMDB image firewall, JS half (header L1–34). Registered as a **document-start** script in `MainActivity.installScripts()` (`WebViewCompat.addDocumentStartJavaScript`, all origins — L788–801). It must run in every frame **before** the page's scripts append the TMDB poster grids, so its allowlist sync fires while the intro burst is happening. The earlier `onPageCommitVisible`-only injection ran too late: `sync()` saw count:0 (images not laid out), the allowlist stayed empty, and the full burst hit the GPU → **SIGSEGV in `KEGLReleaseRenderTarget`** (L6–10).

Two mechanisms (L14–26):
1. **VIRTUALIZE** — strip `src` from off-screen TMDB posters so the browser never requests/decodes them; restore when they scroll into view. Primary fix: stops request/decode/composite work entirely.
2. **1×1 FIREWALL** — native `shouldInterceptRequest` serves a 1×1 transparent pixel for any `image.tmdb.org` URL **not** in the allowlist, as a backstop.

Verified (L28–31, Oct 7 2026, poster-grid site): the burst produces ~125 `tmdb_image_blocked` events and the allowlist climbs 0→10→1 as posters scroll into view; no crash. Side effect: poster-grid site's own "Emergency intro bypass" fires because the 1×1 posters never finish the intro animation — harmless.

### 2.1 Intro skip (L38–51) — before the idempotency guard

poster-grid site's intro animation is a heavy GSAP/GPU workload; on the PowerVR GPU it crashes the renderer ~14–17 s after load (L39–41). poster-grid site skips the intro when `sessionStorage` has `hasShownIntro` set. **L46–51:** at document start, if the host matches `/(^|\.flixer\.(gd|su)$)/` (L48), `sessionStorage.setItem('hasShownIntro', 'true')` (L49) — set **before** poster-grid site's own JS reads it, so the intro never runs and poster-grid site loads like a repeat visit. Placed **before** the idempotency guard (L44–45) because `sessionStorage` resets when the process dies, so it must run on **every** poster-grid site document start.

### 2.2 Idempotency & host gate (L53–61)

- **L54–55:** `window.__flixerImgGuard` guard — the script is re-evaluated on every poster-grid site page commit (`MainActivity.kt:996–997` re-injects on commit; L1080 on finish); only the first evaluation per frame proceeds.
- **L60–61 — host gate regex:** `/(^|\.flixer\.(gd|su)$|(^|\.cinejoy\.pk$)/` on lowercased `location.host`. Runs on: **flixer.gd, \*.flixer.gd, flixer.su, \*.flixer.su, cinejoy.pk, \*.cinejoy.pk**. (Matches the native `isImageGuardedHost`, `MainActivity.kt:377–381`.) Extended from poster-grid site-only to poster-grid site+lazy-load poster site on 2026-10-21.

### 2.3 Constants & state (L63–71)

- **L63 `MAX = 24`** — cap on allowlist size (most-recently-visible first).
- **L64 `ROOT_MARGIN = '200px'`** — pre-load band around the viewport.
- **L65 `THRESHOLD = 0`** — IntersectionObserver fires as soon as any pixel is visible.
- **L68–71:** `visible = []` (currently-visible TMDB URLs, MRU order), `io = null` (IntersectionObserver), `syncing = false` (re-entrancy flag), `virtualized = 0` (count of posters whose src was stripped — **written but never read; vestigial counter**).

### 2.4 `isTmdbImg(img)` (L73–81)

`IMG` tag (L74); `src = img.currentSrc || img.src` (L75); host via `new URL(src, location.href).host.toLowerCase()` (L78); true iff host is `image.tmdb.org` or ends with `.tmdb.org` (L80: `h.indexOf('.tmdb.org') === h.length - 9`).

### 2.5 `inViewport(img, margin)` (L89–115)

`getBoundingClientRect` (L92); `vh`/`vw` = viewport + margin (L93–94). **Zero-dimension handling (L95–111):** if the image has zero width/height (1×1 placeholder or not yet laid out), walks up to **6 parent levels** (L101) looking for a non-zero rect (the card may be in the viewport even though the image is collapsed); if none found → `false` (L110). Visibility test (L112–113): `r.bottom > -m && r.right > -m && r.top < vh && r.left < vw`.

### 2.6 `virtualize()` (L117–142)

The primary fix (L83–88: the 1×1 firewall reduced GPU texture load but the request/decode/composite for all 182 images still happened; with prior video GPU state resident, that residual load pushed the PowerVR driver over the edge).

- **L119:** `document.querySelectorAll('img')` — all images in the frame.
- **L124–131 — restore path:** if `img.__flixerSrc` is set (already virtualized) and `inViewport(img, 200)` → `img.src = img.__flixerSrc` (L126), clear the flag (L127), `virtualized--` (L128).
- **L132–139 — strip path:** for non-virtualized TMDB images with a non-empty `src` attribute (L133–134) that are **not** in the 200 px pre-load band (L135) → save `img.__flixerSrc = src` (L136), **`img.src = ''`** (L137, strip), `virtualized++` (L138).

### 2.7 `sync()` (L144–195)

Re-entrancy-guarded by `syncing` (L145–146, L194).
1. **L152 — `virtualize()` first** (L148–151 comment: must run before the allowlist is rebuilt so restored images carry their real src when read).
2. **L153–177 — rebuild the list from the live DOM:** `querySelectorAll('img')` (L154); for each TMDB image in the 200 px band (L159–160): `src = currentSrc || src` (L161); dedupe via `seen` (L162–164); **1×1 placeholder re-request (L165–176):** if `naturalWidth === 1 && naturalHeight === 1` (the image was served as a 1×1 by the native firewall before it entered the allowlist), the HTTP cache holds the 1×1 response so a plain src reset won't refetch → build a **cache-busted URL** `realSrc + '?r='|'&r=' + Date.now()` (L172), set `img.src = busted` (L173), and add the busted URL to the list (L174–175) so the firewall lets it through.
3. **L178–185:** mark off-screen TMDB images `img2.__flixerBlocked = true` (for later re-request bookkeeping).
4. **L188:** cap — `if (list.length > MAX) list = list.slice(list.length - MAX)` (keeps the **last** 24; DOM order is a proxy for most-recently-appended, L186–187).
5. **L189–192:** `visible = list`; **`window.Buccaneer.tmdbAllowlist(JSON.stringify(list))`** (L191) — the bridge to native.

### 2.8 `onIntersect(entries)` (L197–216)

For each intersecting entry whose target is a TMDB image with a src (L201–205): move the src to the **front** of `visible` (MRU order, L206–208), cap at MAX (L209). Then `sync()` (L214).

### 2.9 `observeAll()` (L218–232)

Lazily creates **one** IntersectionObserver (L220–226): `root: null`, `rootMargin: '200px'`, `threshold: 0`. Observes all current TMDB images (L227–230).

### 2.10 `watchDom()` (L236–262)

**MutationObserver** on `document.documentElement`, `{childList: true, subtree: true}` (L238, L260). For each mutation: for each added element node (L244–246): if it's a TMDB img → `io.observe(n)` (L247); also `n.querySelectorAll('img')` (L248) → observe each TMDB img inside (L250–252). Any removed nodes → `changed = true` (L255). If changed → `sync()` (L257). Purpose (L234–235): poster-grid site appends posters lazily.

### 2.11 Kickoff & timers (L264–282)

- **L265–267:** `observeAll(); watchDom(); sync();`
- **L271–275 — deferred syncs:** `setTimeout(sync, 250)`, `setTimeout(sync, 1000)`, `setTimeout(sync, 3000)` — catch images that appear right after commit (before the MutationObserver settles) and re-assert the allowlist.
- **L280–282 — `setInterval(sync, 2000)`** — periodic safety net: the IntersectionObserver may not re-fire for an image that was **already intersecting** when its src got restored by `virtualize()`; a slow sync catches those and re-asserts the allowlist.
- **L283–285:** outer try/catch — "never let this script break the page."

### 2.12 Native coordination

- **`Buccaneer.tmdbAllowlist`** (`MainActivity.kt:1477–1495`): budget checks (payload ≤ 131072 chars, ≤ 256 entries, each ≤ 8192 chars, L1479/L1483/L1486), replaces the `@Volatile tmdbAllowlist` set (L1488), logs **`tmdb_allowlist_sync`** `{count}` (L1489–1491); errors → **`tmdb_allowlist_error`** (L1493).
- **`shouldInterceptRequest`** (`MainActivity.kt:1032–1069`): when `onImageGuardedPage` and the request host is `image.tmdb.org` / `*.tmdb.org` (L1048–1050): URL in allowlist → `return null` (real image, L1052–1054); otherwise → **1×1 transparent PNG** `WebResourceResponse` (L1056–1061) + **`tmdb_image_blocked`** log (L1062–1064; marked "noisy" in `DiagnosticLog.kt:170`).
- **`onImageGuardedPage`** is set in `onPageCommitVisible` (`MainActivity.kt:955–960`) from `isImageGuardedHost(host)`; when leaving a guarded page the allowlist is **cleared** (L958) and **`image_guard_state`** is logged (L960–969).
- The script is also re-injected via `evaluateJavascript` on commit (L996–997) and finish (L1080) — the idempotency guard (L54) makes that safe.

### 2.13 What it logs

The script itself emits **no** `diagnosticEvent` and no console output. Its observability is entirely via the bridge: `tmdbAllowlist` → native **`tmdb_allowlist_sync`** (per sync, every 2 s + IO/MO callbacks); native **`tmdb_image_blocked`** per blocked image URL.

### 2.14 Timers / observers (flixer_image_guard.js)

| Timer/observer | Period | Notes |
|---|---|---|
| `setTimeout(sync)` | 250 / 1000 / 3000 ms (one-shot) | post-commit catch-up |
| `setInterval(sync)` | 2000 ms | safety net for already-intersecting restored images |
| `IntersectionObserver` | event-driven | rootMargin 200 px, threshold 0, one shared instance |
| `MutationObserver` | event-driven | childList+subtree on documentElement |

### 2.15 DOM operations & PowerVR risk

- `sync()` runs `querySelectorAll('img')` + per-image `getBoundingClientRect` (via `inViewport`) **every 2 s** and on every IO/MO callback — moderate CPU, but this is the *fix* for the GPU crash: it keeps the allowlist tight so only on-screen posters get real GPU textures.
- `virtualize()` does the same walk and **writes `img.src`** (strip/restore) — style/layout writes, but only for images crossing the 200 px band.
- The IO observes every TMDB image; the MO watches the whole document. Both are event-driven and bounded by the host gate (poster-grid site/lazy-load poster site only).
- Net effect on PowerVR: **reduces** GPU texture load (the whole point); the residual JS cost is small compared to the 180-poster decode burst it prevents.

### 2.16 What breaks if a threshold/rule changes (flixer_image_guard.js)

- **Host gate (L61):** add a host → the 1×1 firewall applies there (fine for poster-grid sites, breaks non-grid sites that use TMDB images for real UI). Remove lazy-load poster site → lazy-load poster site's lazy grid crashes the GPU again (the 2026-10-21 extension reason). Remove poster-grid site → the original SIGSEGV returns.
- **`MAX = 24` (L63):** raise → more real textures resident → GPU load up → crash risk returns. Lower → visible posters served as 1×1 → blank posters on screen (false negatives for the user).
- **`ROOT_MARGIN` / the 200 px band (L64, L125, L135, L160, L182):** lower → posters pop in late while scrolling (visible 1×1 flash). Raise → more textures resident → GPU load up.
- **`THRESHOLD = 0` (L65):** raise → allowlist entries lag behind actual visibility → 1×1 flashes.
- **2000 ms interval (L281):** raise → allowlist goes stale; restored-src images that were already intersecting stay 1×1 longer (blank posters). Remove → the "already intersecting" case is never re-synced → permanent blank posters after scroll.
- **`virtualize()` removed (L117–142):** all ~182 images are requested/decoded/composited → the "poster-grid site after other sites" crash returns (L83–88) — the 1×1 firewall alone was not enough once prior video GPU state was resident.
- **Cache-buster block (L170–176) removed:** 1×1 placeholders are cached by the browser; without the `?r=` bust, re-requests never reach the network → posters that entered the allowlist late stay blank forever.
- **`syncing` re-entrancy guard (L145–146) removed:** nested syncs (IO callback → sync → src restore → IO callback) could interleave and corrupt `visible`.
- **Intro-skip `sessionStorage` write (L46–51) removed:** poster-grid site's GSAP intro runs → renderer SIGSEGV ~14–17 s after load (L39–41).
- **Idempotency guard (L54–55) removed:** each page commit re-runs the whole setup → duplicate observers/timers → CPU load and duplicate allowlist churn.
- **`inViewport` parent-walk depth 6 (L101):** lower → collapsed images in deep card hierarchies are never considered visible → never virtualized/restored correctly.

---

## 3. seek_click.js (72 lines)

**Not an installed script.** Header (L1–2): "Called only after an explicit poster-grid site pointer click. No install-time code, listeners, observers, animations, CSS, or recurring timers. Never changes ordinary button clicks." It is a self-contained function evaluated on demand via `evaluateJavascript` with coordinates:

- **Probe** (`MainActivity.kt:2106`): `eval("($seekClickJs)(${x/webView.width},${y/webView.height},'probe')")` inside `tapAtCursor()` — only when `onFlixerPage && customView == null && webView.width/height > 0` (L2103). If the result is `"track"` or `"frame"` the tap is sent as a **mouse** event (`sendTap(x, y, mouse=true)`, L2109) and `repairSeekClick` is scheduled (L2110); otherwise a normal touch tap (L2112).
- **Repair** (`MainActivity.kt:2128–2136`): `repairSeekClick` posts after **120 ms** and re-evaluates in seek mode (no `'probe'` arg) — "one-shot fallback for identifiable seek tracks only; no poster-grid site listeners/timers/DOM styling."

### 3.1 Entry (L70–71)

`if (!isFinite(fx) || !isFinite(fy) || fx < 0 || fx > 1 || fy < 0 || fy > 1) return 'none';` — normalized 0..1 coordinates; then `seekAt(window, fx*innerWidth, fy*innerHeight, 0)`.

### 3.2 `seekAt(win, x, y, depth)` (L5–69)

- **L6:** recursion limit `depth > 3` → `'none'` (max 4 frame levels).
- **L7–8:** `doc.elementFromPoint(x, y)`; no element → `'none'`.
- **L9–19 — iframe descent:** if the hit element is an `IFRAME`: needs `contentDocument` (same-origin) else returns **`'frame'`** (L11) — an opaque cross-origin player frame, which the native side treats as "send a mouse tap" (L2108). Otherwise computes scale factors `sx`/`sy` = `getBoundingClientRect` size ÷ layout size (`offsetWidth || (clientWidth + 2*clientLeft) || rect width`, L13–14) to undo CSS `transform: scale()`, validates `sx, sy > 0` (L15), and recurses into `el.contentWindow` with the transformed coordinates (L16–17).
- **L20–23 — shadow DOM:** descends open shadow roots at the hit point, **max 4 levels** (`si < 4 && el.shadowRoot && el.shadowRoot.elementFromPoint`), stopping if the inner hit is empty or the same element.
- **L24–36 — track search, up 7 ancestors** (`up < 7`):
  - **L26–28:** `role`, `name` (lowercased `aria-label` + `data-plyr` + `name` attributes), `cls` (string class only).
  - **L29 — exclusion:** if `name + cls` matches `/volume|brightness|speed|quality/` → `'none'` — volume/brightness/speed/quality sliders must never become seeks.
  - **L30–31 — `known` tracks:** class matches `/\b(vjs-progress-holder|jw-slider-time|plyr__progress)\b/` (Video.js, JW Player, Plyr), or `data-media-time-slider` attribute, or tag `MEDIA-TIME-SLIDER` (native).
  - **L32 — `labelled`:** (`role === 'slider'` or `INPUT[type=range]`) **and** name matches `/seek|timeline|progress|playback position|time/`.
  - **L33:** `known || labelled` → `track = node`, break.
  - **L35:** if the node is a `BUTTON` → `'none'` — "buttons inside a control bar must not become seeks by searching too far upward."
- **L37–39 — geometry gate:** no track → `'none'`; track rect must satisfy `r.width >= 80 && r.width >= r.height * 3` (wide, short bar) **and** the tap point must be inside the rect.
- **L40:** `mode === 'probe'` → return **`'track'`** (probe mode never seeks).
- **L41–50 — player association:** walk up to **9 ancestors** (`vi < 9`); stop at `body`/`documentElement` → `'none'` (L45); `container.querySelectorAll('video')` — **more than 1 video → `'none'`** (L47, "do not seek a different player/ad merely because it exists elsewhere"); exactly 1 → `video` (L48). Then `video.duration` must be finite and > 0 (L50) — no live/unknown-duration seeks.
- **L51–53:** `fraction = clamp((x - r.left) / r.width, 0, 1)`; **RTL** (L52): if `getComputedStyle(track).direction === 'rtl'` → `fraction = 1 - fraction`; `wanted = duration * fraction`.
- **L54–64 — seekable windows:** if `video.seekable` is non-empty: if `wanted` is inside some range → keep it; otherwise snap to the **nearest seekable edge** (L56–63) — "respect live/DVR seekable windows and gaps; do not jump outside buffered timelines."
- **L65–68:** set `video.currentTime = wanted` only if `|currentTime - wanted| > max(1, duration * 0.001)` (L66, no-op tolerance); returns **`'seeked'`** if within tolerance afterwards, else `'none'`; any exception → `'none'` (L68).

**Return values:** `'none'` | `'frame'` | `'track'` (probe only) | `'seeked'`.

### 3.2b Hosts, timers, DOM ops, PowerVR risk

- **Hosts:** poster-grid site only — gated natively by `onFlixerPage` (`MainActivity.kt:966`), and only when no `customView` is active.
- **Timers:** none in the script; the native side adds a 120 ms `postForPage` before the repair pass.
- **DOM ops:** one `elementFromPoint`, bounded walks (≤4 frame levels, ≤4 shadow hops, ≤7 up for track, ≤9 up for player), `querySelectorAll('video')` on ≤9 containers, one `getComputedStyle`. All on-demand, per explicit tap.
- **PowerVR risk:** negligible — zero install-time footprint, no observers, no style writes, no recurring work.

### 3.3 What breaks if a threshold/rule changes (seek_click.js)

- **`depth > 3` (L6):** lower → players nested in >3 iframes are never probed (false negatives → taps fall back to touch, which poster-grid site's player may ignore). Raise → deeper, slower walks on exotic pages.
- **`'frame'` return for opaque iframes (L11):** remove → cross-origin player taps are never sent as mouse events → seeking in cross-origin players dies.
- **L29 exclusion regex (`volume|brightness|speed|quality`):** remove → dragging/tapping a volume, brightness, speed, or quality slider **seeks the video** (false positive).
- **L35 BUTTON stop:** remove → tapping a control-bar button whose ancestor is a track-like element could be interpreted as a seek (false positive); with it, such taps fall back to ordinary touch.
- **L39 geometry gate (`width >= 80`, `width >= height*3`, point inside):** tighten → legitimate short/narrow tracks (small players, mobile-sized UI) are rejected (false negatives). Loosen → any wide element (e.g. a progress bar for a download, a wide button row) becomes a seek target (false positives).
- **L47 `videos.length > 1 → 'none'`:** loosen → a tap could seek the wrong player (an ad preview or background video in the same container). Tighten (require the video to be the *largest*) → more false negatives on unusual layouts.
- **L50 finite-duration requirement:** remove → live streams get `currentTime` set to arbitrary values → stalls/crashes in the media pipeline.
- **L55–64 seekable snapping:** remove → seeks jump outside buffered/live windows → player stalls or throws.
- **L66 tolerance `max(1, duration*0.001)`:** raise → small seeks (a second or two) are ignored (false negatives). Lower → redundant `currentTime` writes on every tap (player jitter).
- **L52 RTL flip:** remove → RTL players seek backwards from the wrong end.

---

## 4. fmhy_theme.js (23 lines)

SPA portal (fmhy.net, a VitePress site) theme forcing (L1: "SPA portal's own theme only: no filters or changes to other origins/WebView darkening").

- **L4–5 — host gate:** `host = (location.hostname || '').toLowerCase().replace(/\.$/, '')`; **returns unless `/(^|\.fmhy\.net$)/`** matches — i.e. `fmhy.net` and `*.fmhy.net` only. (Native registration also restricts it to `https://fmhy.net` + `https://*.fmhy.net`, `MainActivity.kt:805`.)
- **L7 — dark source:** `dark = !!window.Buccaneer.isDarkMode()` (native `MainActivity.kt:1314`, the app's dark-mode flag); if the bridge throws → the whole script returns (L7).
- **L8–11 — VitePress localStorage keys:** sets `vitepress-display-mode` and `vitepress-theme-appearance` to `'dark'`/`'light'` — VitePress reads these to pick its theme, so the site's own theme logic agrees with the app.
- **L12–17 — `apply()`:** toggles the `dark`/`light` classes on `document.documentElement` (L15); returns `true` only if `documentElement` exists (L13–14).
- **L18–22 — late-DOM fallback:** if `apply()` fails at document-start (no `documentElement` yet): installs a **MutationObserver** on `document` (`childList: true, subtree: true`, L20) that re-runs `apply()` and **self-disconnects** on first success (L19); plus a `{once: true}` `DOMContentLoaded` listener that disconnects the observer and applies (L21).

**Why it exists** (`MainActivity.kt:832–848`): dark mode is handled entirely by Chromium render-client darkening (`FORCE_DARK_ON`); SPA portal declares `color-scheme` in its CSS, which makes Chromium *skip* render-client darkening — so SPA portal would stay light. No CSS injection is used anywhere (injected style tags triggered PowerVR compositing crashes on poster-grid site, L843–848).

### 4.1 Injection, timers, DOM ops, PowerVR risk

- **Injection:** document-start for the two SPA portal origins (`MainActivity.kt:805`); re-injected via `evaluateJavascript` in `injectFallback` (L827) and `onPageFinished` (L1080). Idempotent: class toggles and localStorage writes are idempotent; the observer self-disconnects.
- **Timers:** none. **Observers:** one self-disconnecting MutationObserver (only when `documentElement` is absent at injection).
- **DOM ops:** `classList.toggle` ×2 on `<html>`; `localStorage.setItem` ×2. Negligible.
- **PowerVR risk:** none.

### 4.2 What breaks if a threshold/rule changes (fmhy_theme.js)

- **Host gate (L5):** widen → other VitePress sites get forced `dark`/`light` classes and localStorage keys, conflicting with their own theme logic (false positives on unrelated sites). Narrow (e.g. drop subdomains) → subdomain pages stay light in dark mode.
- **`isDarkMode` bridge check (L7):** if it started throwing or returning the wrong value, SPA portal's theme would silently stop following the app (the `return` on L7 means *no* theme forcing at all).
- **localStorage keys (L9–10):** VitePress renamed/changed its keys → the site's own JS overwrites the values → theme reverts to the site default.
- **MutationObserver fallback (L18–22) removed:** if `documentElement` doesn't exist at injection time (very early document-start), the theme is never applied → SPA portal stays in its default (light) theme despite dark mode.
- **`{once: true}` DOMContentLoaded (L21) removed:** the observer would keep running until `apply()` succeeds — minor CPU, but on a page that never gets a `documentElement` it would run forever.

---

## 5. Cross-file summary

### 5.1 Exact host conditions

| File | Registration | Self-gate | Effective hosts |
|---|---|---|---|
| popup_guard.js | document-start, `*` origins, all frames | poster-grid site branch: `/(^|\.flixer\.(gd|su)$)/` (L518); vidcore listeners: hostname contains `vidcore` (L137) | **all hosts** (full walk) except flixer.gd/su + subdomains (minimal scan); pointer workarounds only in vidcore frames |
| flixer_image_guard.js | document-start, `*` origins, all frames | `/(^|\.flixer\.(gd|su)$\|(^|\.cinejoy\.pk$)/` (L61); intro-skip only poster-grid site (L48) | flixer.gd, \*.flixer.gd, flixer.su, \*.flixer.su, cinejoy.pk, \*.cinejoy.pk |
| seek_click.js | none (on-demand eval) | n/a | poster-grid site only, via native `onFlixerPage` flag, no customView |
| fmhy_theme.js | document-start, `https://fmhy.net` + `https://*.fmhy.net` | `/(^|\.fmhy\.net$)/` (L5) | fmhy.net, \*.fmhy.net |

### 5.2 All timers / intervals / observers

| File | Mechanism | Period | Host scope |
|---|---|---|---|
| popup_guard.js | deferred click `setTimeout` | 300 ms per pointerup | vidcore frames |
| popup_guard.js | scan debounce | 300 ms | non-poster-grid site |
| popup_guard.js | `setInterval(killOverlays)` | 5000 ms | non-poster-grid site |
| popup_guard.js | `setInterval(killCatchers)` | 5000 ms | poster-grid site |
| popup_guard.js | MutationObserver (childList+subtree) | event-driven | non-poster-grid site only |
| popup_guard.js | 8 capture listeners | per input event | vidcore frames |
| flixer_image_guard.js | `setTimeout(sync)` ×3 | 250/1000/3000 ms one-shot | poster-grid site+lazy-load poster site |
| flixer_image_guard.js | `setInterval(sync)` | 2000 ms | poster-grid site+lazy-load poster site |
| flixer_image_guard.js | IntersectionObserver (200 px, threshold 0) | event-driven | poster-grid site+lazy-load poster site |
| flixer_image_guard.js | MutationObserver (childList+subtree) | event-driven | poster-grid site+lazy-load poster site |
| seek_click.js | — | none | — |
| fmhy_theme.js | MutationObserver (self-disconnecting) | event-driven, once | SPA portal |

### 5.3 DOM operations & PowerVR risk ranking

1. **popup_guard.js `killOverlays` (non-poster-grid site):** full-DOM `querySelectorAll('div, span, a, iframe')` + per-element computed style + rect + textContent, on MO + every 5 s — **highest risk**; documented as the poster-grid site-intro crash cause, hence disabled there.
2. **flixer_image_guard.js `sync`/`virtualize`:** full-`img` walk + per-image rect every 2 s and on IO/MO callbacks, plus `img.src` writes — moderate, but it is the *mitigation* for the GPU texture crash (net negative GPU load).
3. **popup_guard.js `killCatchers` (poster-grid site):** body's direct children + iframes only — cheap; the deliberate replacement.
4. **popup_guard.js `disableAdLayer` / `markAdLayer`:** bounded ancestor walks / per-event target handling — cheap.
5. **seek_click.js:** on-demand only, bounded walks — negligible.
6. **fmhy_theme.js:** two class toggles — negligible.

### 5.4 Diagnostic events (complete list)

- **popup_guard.js:** `react_onclick_called`, `disable_ad_layer_no_mark`, `disable_ad_layer_skipped_media`, `disable_ad_layer_skipped_control`, `overlay_disabled` (reason `popup_initiator`), `popup_window_open_blocked`, `popup_target_rewritten`, `floating_seen` (full + minimal variants), `overlay_killed` (reasons `floating_iframe` / `high_z_ad` / `viewport_catcher` / `covers_video` in full; `floating_iframe` / `viewport_catcher` in minimal), plus native `popup_guard_action` via `report()`.
- **flixer_image_guard.js:** none directly; native `tmdb_allowlist_sync`, `tmdb_image_blocked`, `tmdb_allowlist_error`, `image_guard_state`.
- **seek_click.js:** none (returns values to the native caller).
- **fmhy_theme.js:** none.

### 5.5 Vestigial code noted

- popup_guard.js L53 `adLayerMarkedAt` (written, never read); L60 `isPlayerFrame` (declared, never used).
- flixer_image_guard.js L71 `virtualized` counter (written, never read).

### 5.6 Hash protection

`popup_guard.js` is hash-protected by `tests/protected-fixes.json` — any edit (including whitespace) requires regenerating the stored hash; the other three assets are not noted as protected.
