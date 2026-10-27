# Injected-JS workload inventory (PLAN #3, 2026-10-24)

Per-script, per-frame inventory of every script the app injects into the WebView:
injection timing, frame scope, timers, observers, event listeners, bridge calls,
and recurring work. Source: `MainActivity.kt` (installScripts, injectFallback,
onPageCommitVisible) + all 8 asset files.

## Injection mechanism

Two paths (MainActivity.kt):

1. **Document-start** (primary, API 25 Chromium 118 supports it):
   `WebViewCompat.addDocumentStartJavaScript(js, origins)` in `installScripts()`
   (L871-916). Runs in **every frame** before any page script. `origins = setOf("*")`
   for all scripts except `fmhy_theme.js` (restricted to `https://fmhy.net` +
   `https://*.fmhy.net`).
2. **Fallback** (`injectFallback`, L919-930): if document-start is unavailable,
   `evaluateJavascript` on `onPageStarted` + `onPageCommitVisible`. Scripts are
   idempotent so re-injection is safe.

Additionally, `onPageCommitVisible` (L1056-1115) re-injects specific scripts via
`evaluateJavascript` when the host matches (idempotent, so this is a no-op if the
document-start path already ran in the same frame).

## Script-by-script inventory

### 1. popup_guard.js (595 lines, 30,919 chars) — HASH-PINNED

- **Injection:** document-start, all origins (`*`).
- **Frame scope:** every frame.
- **Purpose:** popup/ad overlay detection + removal. The full-DOM traversal target
  of PLAN #3 profiling.
- **Two scan paths (host-dependent):**
  - **Flixer** (`__pgIsFlixer`, L561-564): `killCatchers()` — minimal scan of
    `<html>`/`<body>` direct children + iframes only. No MutationObserver.
    `setInterval(killCatchers, 5000)`. Cheap: a handful of elements, no full-DOM
    walk. This is the reduced path to preserve.
  - **Non-Flixer** (L565-594): `killOverlays()` — full-DOM walk via
    `document.querySelectorAll('div, span, a, iframe')` (L316). For each element:
    `getComputedStyle` + `getBoundingClientRect`. Adaptive debounce:
    `setTimeout` with delay = `min(4000, max(300, lastScanMs * 10))` — waits at
    least 10x the last scan duration (capped at 4s). Plus `setInterval(timedScan,
    5000)` as fallback. `MutationObserver` (childList + subtree on
    documentElement) triggers `scheduleScan()` on DOM changes.
- **Timers (non-Flixer):**
  - Initial `timedScan()` at install.
  - `setTimeout` (adaptive: 300ms–4000ms) after each DOM mutation.
  - `setInterval(timedScan, 5000)` — permanent fallback.
- **Timers (Flixer):**
  - Initial `killCatchers()` at install.
  - `setInterval(killCatchers, 5000)` — permanent.
- **Observers:**
  - Non-Flixer: `MutationObserver` (childList + subtree) — permanent.
  - Flixer: none.
- **Listeners:**
  - All frames: `click` (capture) — target=_blank rewrite (L231).
  - Vidcore player frame only (`isVidcoreFrame`, L137-153): 6 pointer/touch events
    (touchstart, touchend, pointerdown, pointerup, mousedown, mouseup) + `click`
    (capture) — ad layer marking + React onClick forwarding. `setTimeout(reactOnClick,
    300)` deferred on pointerup.
- **Bridge calls:** `Buccaneer.isGuardEnabled` (per scan), `Buccaneer.diagnosticEvent`
  (overlay_killed, floating_seen, popup_window_open_blocked, popup_target_rewritten,
  react_onclick_called, disable_ad_layer_*), `Buccaneer.blocked` (report).
- **Per-scan cost (non-Flixer):** `querySelectorAll('div, span, a, iframe')` — on a
  ~1000-link portal page (~10k elements) this was measured at ~7s of main-thread
  blocking (L325-330 comment). The adaptive backoff caps it at ~9% of main thread.
- **Per-scan cost (Flixer):** `getElementsByTagName('iframe')` + a handful of
  direct children. Negligible.
- **Note:** hash-pinned in `tests/protected-fixes.json`. Any modification requires
  re-pinning + gate PASS.

### 2. page_helpers.js (2,175 lines) — HASH-PINNED

- **Injection:** document-start, all origins (`*`).
- **Frame scope:** every frame.
- **Purpose:** video discovery, cross-frame command bridge, CSS fullscreen,
  typing API, quality preference, media health, one-video-at-a-time.
- **Flixer guard (L1275):** `if (__isFlixer) return;` — everything below this line
  (continuous capture-phase listeners, media health timers, HLS constructor hooks,
  synthetic click forwarding) is **never installed on Flixer**. The passive `__fb`
  API (video controls, typing) is still exposed.
- **Timers (non-Flixer only):**
  - `installFsResumeGuard`: setInterval 250ms, max 10 checks (~2.5s), on CSS-fs enter.
  - `installControlsAutoHide`: setInterval 1500ms, max 2 pokes, on fullscreenchange.
  - `scanForQualityPlayers`: setInterval 1000ms, max 30 attempts (~30s), on loadedmetadata.
  - `installSandboxRemover`: setInterval 500ms, stops after 30s.
  - `installStreamCornerErrorHider`: setInterval 1000ms, stops after 60s.
  - Media health sampler: setTimeout 3000ms, self-rescheduling, play-gated,
    diagnostics-gated (no timer when diagnostics OFF or nothing playing).
  - Cross-frame coordination fallback: setInterval 2000ms (always, non-Flixer).
- **Observers:** none (event-driven).
- **Listeners (non-Flixer):**
  - `message` (cross-frame command bridge, always).
  - `fullscreenchange` / `webkitfullscreenchange` (capture).
  - `loadedmetadata` (capture) — triggers quality scan + network probe.
  - `play` / `pause` / `ended` (capture) — one-video-at-a-time + media diagnostics.
  - 10 media diagnostic events: loadedmetadata, canplay, play, playing, waiting,
    stalled, pause, ended, error, emptied (capture).
  - `click` (capture) — real-tap detection + iframe tap forwarding.
  - `playing` (capture) — schedules health sample.
- **Bridge calls:** `Buccaneer.currentDocument`, `Buccaneer.isDiagnosticsEnabled`,
  `Buccaneer.diagnosticEvent`, `Buccaneer.exclusiveEnabled`, `Buccaneer.activeOwner`,
  `Buccaneer.mediaStarted`, `Buccaneer.mediaStopped`, `Buccaneer.playbackActive`,
  `Buccaneer.pageIsForeground`, `Buccaneer.cssFullscreen`, `Buccaneer.editableClickedForDocument`.
- **Flixer path:** passive `__fb` API only. No timers, no capture listeners, no
  quality scan, no health sampler. This is the reduced path to preserve.

### 3. flixer_image_guard.js (288 lines, 12,549 chars) — HASH-PINNED

- **Injection:** document-start, all origins (`*`). Self-guards on host:
  `flixer.gd` / `*.flixer.gd` / `flixer.su` / `*.flixer.su` / `cinejoy.pk` /
  `*.cinejoy.pk`. Returns immediately on other hosts.
- **Frame scope:** every frame (but only active on guarded hosts).
- **Purpose:** TMDB image firewall — virtualize off-screen posters (strip src),
  1x1 backstop via native `shouldInterceptRequest`, allowlist sync.
- **Timers:**
  - `setInterval(sync, 2000)` — periodic safety-net sync (always on guarded hosts).
  - `setTimeout(sync, 250)`, `setTimeout(sync, 1000)`, `setTimeout(sync, 3000)` —
    initial deferred syncs.
- **Observers:**
  - `IntersectionObserver` (rootMargin 200px, threshold 0) — watches all TMDB imgs.
  - `MutationObserver` (childList + subtree on documentElement) — watches for new
    images, re-observes, re-syncs.
- **Listeners:** none.
- **Bridge calls:** `Buccaneer.tmdbAllowlist(JSON.stringify(list))` — syncs the
  allowlist to the native 1x1 firewall.
- **Per-tick cost:** `sync()` calls `virtualize()` (querySelectorAll('img'),
  getBoundingClientRect per img) + rebuilds allowlist (querySelectorAll('img') again,
  getBoundingClientRect per TMDB img). Two full img passes per 2s tick.

### 4. flixer_poster_guard.js (177 lines, 7,409 chars)

- **Injection:** document-start (via installScripts? NO — see below). Actually
  injected via `evaluateJavascript` in `onPageCommitVisible` (L1113) **only when
  `isFlixer`**. NOT registered as a document-start script.
- **Frame scope:** top frame only (evaluateJavascript runs in the top frame).
  Self-guards on Flixer host.
- **Purpose:** hide the DPAD-focus-triggered hover-preview card; keep the
  click-triggered detail modal visible.
- **Timers:**
  - `setInterval(apply, 2000)` — safety-net for SPA re-creation (was 200ms,
    reduced to 2s).
  - `setTimeout(apply, 150)` — debounced, event-driven (one pending timer).
- **Observers:** none.
- **Listeners:**
  - `click` (capture) — tracks last interaction type.
  - `focusin` (capture) — tracks last interaction type.
- **Bridge calls:** `Buccaneer.diagnosticEvent` (preview_hidden, detail_shown).
- **Per-tick cost:** `apply()` → `findCard()` = querySelectorAll
  `div[class~="z-50"][class*="bg-"]` + getComputedStyle on candidates +
  querySelector('img'). Targeted scan, NOT full-DOM. Low cost.

### 5. scroll_restore.js (72 lines, 3,297 chars)

- **Injection:** document-start, all origins (`*`).
- **Frame scope:** every frame.
- **Purpose:** restore scroll position after SPA hydration resets to top.
- **Timers:**
  - `setInterval(tick, 100)` — fallback for resets not going through scrollTo.
    Reads `window.scrollY` per tick. Very light.
  - `setTimeout(restore, 150)` — deferred restore after detection.
- **Observers:** none.
- **Listeners:** none (wraps `window.scrollTo` directly).
- **Bridge calls:** none.
- **Per-tick cost:** one `window.scrollY` read + comparison. Negligible.

### 6. fmhy_theme.js (23 lines, 1,006 chars)

- **Injection:** document-start, restricted to `https://fmhy.net` +
  `https://*.fmhy.net`. Also re-injected via evaluateJavascript in
  onPageCommitVisible + onPageFinished.
- **Frame scope:** top frame only (origin-restricted).
- **Purpose:** set VitePress dark/light theme on the SPA portal.
- **Timers:** none.
- **Observers:** `MutationObserver` (childList + subtree) — only if `apply()` fails
  on first try (document not ready). Disconnects once applied.
- **Listeners:** `DOMContentLoaded` (once) — disconnects observer + applies.
- **Bridge calls:** `Buccaneer.isDarkMode`.
- **Per-tick cost:** none (one-shot).

### 7. tv_ui.js (335 lines, 20,382 chars)

- **Injection:** document-start, all origins (`*`).
- **Frame scope:** every frame.
- **Purpose:** remote-driven UI — field typing, menu fitting, scroll, playback
  detection.
- **Timers:**
  - `setTimeout(scan, 120)` — menu fit scan (debounced, max 10 scans).
  - `setTimeout(reportField+scan, 150)`, `setTimeout(reportField+scan, 650)` —
    post-click field reports.
  - `setTimeout(stopMenuWatch, 5000)` — menu watch expiry.
  - All menu-watch timers are cleared on `pagehide` / Escape / new click.
- **Observers:**
  - `MutationObserver` (childList + subtree on documentElement) — only during
    active menu watch (5s window). Disconnected after.
- **Listeners:**
  - `pagehide` — cleanup.
  - `resize` — fitMenus.
  - `focus` — reportField.
  - `keydown` (capture) — Escape stops menu watch.
  - `focusin` (capture) — reportField.
  - `focusout` (capture) — editableBlurred bridge.
  - `input` (capture) — reportField.
  - `click` (capture) — rememberMenuTrigger + watchMenus (only on isTrusted).
- **Bridge calls:** `Buccaneer.currentDocument`, `Buccaneer.editableFocused`,
  `Buccaneer.activeInputFrame`, `Buccaneer.editableBlurred`.
- **Per-tick cost:** menu watch is bounded (5s, max 10 scans). No permanent
  polling.

### 8. seek_click.js (72 lines, 4,215 chars)

- **Injection:** NOT a persistent script. Called on-demand via
  `evaluateJavascript` after an explicit user tap on the player (L2318, L2346).
- **Frame scope:** top frame (evaluateJavascript).
- **Purpose:** seek the video by tapping the progress bar. One-shot function.
- **Timers:** none.
- **Observers:** none.
- **Listeners:** none.
- **Bridge calls:** none.
- **Per-tick cost:** none (one-shot).

## Recurring workload summary (per frame)

| Script | Timer | Interval | Duration | Gate |
|--------|-------|----------|----------|------|
| popup_guard (non-Flixer) | timedScan (adaptive) | 300ms–4000ms | **permanent** | DOM mutation + 5s fallback |
| popup_guard (non-Flixer) | setInterval fallback | 5000ms | **permanent** | always |
| popup_guard (Flixer) | killCatchers | 5000ms | **permanent** | Flixer only |
| page_helpers | fsResumeGuard | 250ms | 2.5s (10 checks) | CSS-fs enter |
| page_helpers | controlsAutoHide | 1500ms | 3s (2 pokes) | fullscreenchange |
| page_helpers | qualityScan | 1000ms | 30s (30 attempts) | loadedmetadata |
| page_helpers | sandboxRemover | 500ms | 30s | always (non-Flixer) |
| page_helpers | errorHider | 1000ms | 60s | always (non-Flixer) |
| page_helpers | healthSampler | 3000ms | until stop | diagnostics ON + playing |
| page_helpers | coordFallback | 2000ms | **permanent** | always (non-Flixer) |
| scroll_restore | tick | 100ms | **permanent** | always |
| flixer_image_guard | sync | 2000ms | **permanent** | guarded hosts only |
| flixer_poster_guard | apply | 2000ms | **permanent** | Flixer only |
| tv_ui | menuWatch | 120ms | 5s (10 scans) | click-triggered |

## Permanent (non-stopping) timers per frame

1. **popup_guard timedScan** (adaptive 300ms–4000ms + 5s fallback) — non-Flixer
   only. Full-DOM walk: querySelectorAll('div, span, a, iframe') + getComputedStyle
   + getBoundingClientRect per element. The heaviest recurring workload on
   non-Flixer pages.
2. **popup_guard killCatchers** (5000ms) — Flixer only. Minimal: a handful of
   elements. Negligible.
3. **page_helpers coordFallback** (2000ms) — non-Flixer only. Reads
   `playingMedia()` (querySelectorAll video,audio), calls `bridgeCall` 2-3x per tick.
4. **scroll_restore tick** (100ms) — all frames. Reads `window.scrollY`.
5. **flixer_image_guard sync** (2000ms) — guarded hosts only. Two full
   querySelectorAll('img') passes + getBoundingClientRect per TMDB img.
6. **flixer_poster_guard apply** (2000ms) — Flixer only. Targeted CSS scan.

## Flixer reduced path (to preserve)

On Flixer hosts, the following are **reduced or absent**:
- popup_guard: **reduced** — `killCatchers()` (minimal scan, 5s interval) instead
  of `killOverlays()` (full-DOM walk). No MutationObserver. This is the reduced
  path to preserve.
- page_helpers: everything after L1275 (`if (__isFlixer) return;`) is **absent** —
  no capture listeners, no quality scan, no health sampler, no sandbox remover,
  no error hider, no coordFallback timer, no controls auto-hide, no CSS-fs
  intercept. Passive `__fb` API only.
- flixer_image_guard: **active** (TMDB firewall, needed on Flixer).
- flixer_poster_guard: **active** (Flixer only).
- scroll_restore: **active** (all hosts).
- tv_ui: **active** (all hosts).

The Flixer frame runs: popup_guard (killCatchers) + page_helpers (passive `__fb`
only) + flixer_image_guard + flixer_poster_guard + scroll_restore + tv_ui.

## Non-Flixer frame (full workload)

A non-Flixer frame runs: popup_guard (killOverlays, full-DOM walk) + page_helpers
(full, with all timers and capture listeners) + scroll_restore + tv_ui. No
flixer_image_guard, no flixer_poster_guard, no fmhy_theme (unless on fmhy.net).

## Bridge call frequency (non-Flixer, per frame)

- **coordFallback (2s):** `pageIsForeground`, `playbackActive` (if playing),
  `exclusiveEnabled`, `activeOwner` (if exclusive). 2-4 bridge calls per 2s.
- **healthSampler (3s, diagnostics ON + playing):** `diagnosticEvent` with
  mediaDescription. 1 bridge call per 3s.
- **tv_ui:** `editableFocused` on focus/input, `editableBlurred` on focusout,
  `activeInputFrame` on edit. Event-driven, not periodic.
- **page_helpers media events:** `diagnosticEvent` on 10 media event types
  (diagnostics ON only).

## Key observations for profiling

1. **popup_guard.js killOverlays is the only full-DOM traversal** on non-Flixer
   sites. It walks `querySelectorAll('div, span, a, iframe')` — on a ~1000-link
   portal page (~10k elements) this was measured at ~7s of main-thread blocking
   (L325-330 comment). The adaptive backoff (wait 10x last scan duration, capped
   at 4s) limits it to ~9% of main thread, but on a constantly-mutating page the
   MutationObserver keeps triggering scans.
2. **The heaviest recurring non-popup workload** on non-Flixer is the
   page_helpers coordFallback (2s) + the bounded timers that fire during the
   first 30-60s after page load (sandboxRemover 30s, errorHider 60s,
   qualityScan 30s).
3. **On Flixer**, the heaviest recurring workload is flixer_image_guard sync
   (2s, two full img passes) — but this is necessary for the PowerVR crash fix.
   popup_guard on Flixer is negligible (killCatchers, handful of elements).
4. **scroll_restore (100ms)** is the highest-frequency timer but the cheapest
   (one scrollY read).
5. **tv_ui menu watch** is bounded (5s) and click-triggered — not a steady-state
   cost.
6. **The Flixer reduced path** (popup_guard killCatchers + page_helpers passive
   `__fb` only) is the baseline to preserve. Any change to the non-Flixer path
   must not alter the Flixer path.
