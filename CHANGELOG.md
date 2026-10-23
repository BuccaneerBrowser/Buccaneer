# Changelog

**Purpose:** Record every fix **and what it broke as a result**. This is the antidote to the
whack-a-mole failure loop (see `docs/FAILURE_LOOP.md`). Each entry notes the GPU-budget
impact, because on this PowerVR device a fix that *adds* GPU load can tip an unrelated
subsystem over its threshold even when the fix itself is locally correct.

**How to read an entry:**
- **Fix:** what the change does.
- **Broke / risked:** what it broke, or what it put at risk. "None observed" is only valid
  after a regression pass (`docs/REGRESSION_CHECKLIST.md`).
- **GPU:** `adds` / `removes` / `neutral` on the shared PowerVR budget. This is the field
  that predicts the next failure.
- **Status:** `verified` (confirmed on device) · `armed` (installed, not yet exercised by a
  real failure) · `reverted` (shipped then pulled) · `leave-it` (user decision not to fix).

> **Rule:** before shipping any fix, ask "does this add a GPU operation?" If yes and the
> problem it solves is not GPU-related, it is suspect (FAILURE_LOOP lesson #2).

---

## Unreleased — working tree (on top of 2.3.1, 2026-10-22)

Not yet committed. This is the current state of the device build.

### Piped redirect — ABANDONED (no code written)
- **Fix:** n/a — prototype was approved and mapped (`MainActivity.kt` navigation
  choke points + settings mechanism) but never coded.
- **Why abandoned:** pre-build verification (3 non-rickroll videos on device)
  failed: both CORS-allowing Piped instances (`pipedapi.ducks.party`,
  `api.piped.private.coffee`) now return HTTP 500 `SignInConfirmNotBotException`
  — YouTube is bot-blocking the instances' server IPs for new fetches. The one
  earlier success (rickroll) was served from instance cache. 13/15 official
  instances remain dead/CORS-blocked. Whack-a-mole against YouTube's
  infrastructure; not fixable in the app. User decision 2026-10-22: abandon.
- **Broke / risked:** none — no code shipped.
- **GPU:** neutral (no change).
- **Status:** `abandoned`. Full evidence: `docs/YOUTUBE_ADS.md` §8. Remaining
  options if wanted again: SmartTube sideload (unaffected — uses device's own
  IP), preventive player-config stripping, YouTube Premium.

### GPU render reset (general) — `videoActive`
- **Fix:** residual video GPU state accumulates across video sites and tips the PowerVR
  driver over the edge (frame drops after a long session; black video where compositing
  fails but audio is fine). New `@Volatile videoActive` is set when a page starts real
  playback (`media_owner_started`) and consumed by a render reset in `handoffNavigation`
  (`commit` lambda): `pauseTimers → invalidate → resumeTimers → invalidate` (150 ms), the
  same proven dance as the flixer reset. Fires when navigating *away* from a page that had
  playback — covers video→video and video→other, not just video→flixer.
- **Broke / risked:** none observed. This *removes* load, so it cannot tip another
  subsystem. The reset runs after media teardown, before the next page loads.
- **GPU:** **removes** (clears residual state before the next composite).
- **Status:** `verified` — `test1_diag` showed 7 `video_render_reset`, frame drops 0–3.5%,
  no buildup. User: "skipping is gone" (monitoring for a full long-day session).
- **File:** `MainActivity.kt` (`videoActive` field ~L398; set ~L1483; reset ~L1678).

### Flixer fullscreen gravity fix
- **Fix:** the WebView fullscreen `VideoView` measures to the video's own aspect ratio
  (a 2.39:1 film is ~1920×780 on this 16:9 screen); `FrameLayout`'s default top-left
  gravity pinned it to the top with a black bar below. `enterFullscreen` now adds the view
  with `lp.gravity = Gravity.CENTER` (a 16:9 video fills the box exactly, so gravity is a
  no-op there). Also added a defensive `view.parent?.removeView(view)` to avoid
  "child already has a parent" on renderer-recovery paths.
- **Broke / risked:** none observed. Remaining top-alignment on *some* flixer pages is a
  page-layout quirk (the video is top-aligned *inside* the page/WebView, which gravity
  cannot reach) — **not** fixed, by user decision.
- **GPU:** **neutral** (one-time layout change; no new continuous GPU ops).
- **Status:** `verified` for the gravity case; the residual page-layout offset is
  `leave-it` (user: "leave it" — a CSS/JS reposition carries the same regression risk that
  broke auto-hide).
- **File:** `MainActivity.kt` `enterFullscreen` (~L2666).

### Dead-video auto-retry
- **Fix:** some MovY videos go black because their HLS segments fail at the network level
  (`net::ERR_FAILED` from `calmsummit.top` / `sun.paleoak.top` / `laserlamp.top`); the
  `<video>` never receives data (`0×0`, `readyState=0`). `installDeadVideoRetry()` polls
  every 2 s; if a video stays `0×0`/`readyState=0` for 8 s **inside a player container**
  (≥200×150 — the video's own rect is 0×0, so we test the container), it reloads the page
  once. A per-URL `localStorage` cooldown (60 s) prevents hammering a hard-down CDN.
  Non-flixer pages only. Log is `console.warn` (the app captures WARNING, not LOG) so a
  fired retry is visible in the diagnostic log as `[dead-video-retry] reloading: …`.
- **Broke / risked:** the 2 s poll is cheap JS (no GPU). A *fired* reload is a full page
  load (a big GPU event) but only on a genuinely dead video, and is bounded by the
  cooldown. No regression observed.
- **GPU:** **neutral** (poll); a fired retry **adds** one full page load.
- **Status:** `armed` — installed and logging, but **not yet exercised** by a real black
  video (the one test recovered on its own; 0 retry log lines). The next real black video
  is the true test.
- **File:** `page_helpers.js` `installDeadVideoRetry()` + call site after the flixer guard.

### Flixer auto-fullscreen suppression
- **Fix:** the flixer watch page calls `requestFullscreen()` on the player when playback
  starts, forcing native fullscreen before the user wants it. `installFlixerFsGuard()`
  blocks `requestFullscreen` calls within 5 s of page load / each `play` event (the auto
  calls) and allows later ones (user-initiated). **Minimal by design:** one capture-phase
  `play` listener + one prototype modification. No timers, no continuous polling —
  deliberately, because heavier instrumentation triggers the PowerVR crash on flixer.
- **Broke / risked:** none observed. It *removes* an unwanted native-fullscreen composite.
- **GPU:** **removes** (prevents an auto native-fullscreen composite).
- **Status:** `verified` (flixer no longer force-fullscreens on play).
- **File:** `page_helpers.js` `installFlixerFsGuard()` (flixer-only, before the flixer
  early-return).

### Anchor-based ad-redirect guard
- **Fix:** server-side 301/302 redirect chains bypass `shouldOverrideUrlLoading` (the
  native `blocksNavigation` never fires for them). `maybeBlockAdRedirect()` treats a
  main-frame load to a *different domain*, with no user interaction since the anchor
  navigation and within a 30 s window, as an ad redirect and returns the user to the
  anchor. Safe-domain escape hatch + 2 s cooldown. See `docs/review/blocker-redesign.md`.
- **Broke / risked:** a legitimate cross-domain redirect within 30 s with no interaction
  would be bounced to the anchor. Mitigated by the safe-domain list. No regression
  observed.
- **GPU:** **neutral** (native, no GPU ops).
- **Status:** `verified` (logs `redirect_blocked` with `mechanism: anchor`).
- **File:** `MainActivity.kt` (`anchorUrl`/`maybeBlockAdRedirect`, ~L356 / ~L1537).

### Flixer poster guard (new asset)
- **Fix:** the flixer poster grid auto-opens a floating preview on DPAD focus, making the
  grid hard to navigate. New `flixer_poster_guard.js` keeps the hover-preview hidden so
  the grid is navigable; posters still open on click (DPAD OK → movie page). Injected
  flixer-only.
- **Broke / risked:** none observed. 200 ms re-apply scan is flixer-only (host-guarded).
- **GPU:** **neutral** (JS; no continuous GPU ops).
- **Status:** `verified`.
- **File:** `app/src/main/assets/flixer_poster_guard.js`; injection in `MainActivity.kt`.

### Coalesced network probe
- **Fix:** the per-segment `net_segment` diagnostic event emitted thousands of events on a
  segment-heavy HLS/DASH site, pushing other evidence out of the 2 MiB diagnostic
  rotation. Replaced with a per-host aggregate over a 10 s window: one `net_segment_summary`
  event (count + first/last URL + method counts). Diagnostics-only; never affects page
  behavior.
- **Broke / risked:** none (diagnostic-only).
- **GPU:** **neutral** (reduces diagnostic log pressure, not GPU).
- **Status:** `verified`.
- **File:** `page_helpers.js` `__netAgg` / `netAggRecord` / `netAggFlush`.

### nippleflix removed from image-guard host list
- **Fix:** nippleflix's in-view posters did not match the TMDB allowlist, so the native
  1×1 firewall **blanked the home-page poster grid**. Removed nippleflix from
  `isImageGuarded`.
- **Broke / risked:** none — this *fixes* a blank-grid regression.
- **GPU:** **neutral** (stops an unnecessary firewall path).
- **Status:** `verified`.
- **File:** `MainActivity.kt` `isImageGuarded` (~L388).

---

## 2.3.1 (committed, 2026-10-20 → 2026-10-22)

### Auto-hide player controls (CSS fullscreen) — **REVERTED**
- **Fix (attempted):** in CSS fullscreen, dispatch a synthetic `mousemove` on the video
  (bounded: 2 pokes at 1.5 s) to kick-start the player's idle timer, which a TV remote
  never triggers.
- **Broke:** the auto-hide **did not work** (the player ignored the synthetic event) **and**
  the video went **blank in fullscreen** on MovY. The poke re-ran the player's
  "show controls" path → a GPU re-composite of the controls layer → tipped the PowerVR
  over its compositing threshold. A pure net-negative on the shared budget.
- **GPU:** **adds** (a re-composite) while achieving nothing.
- **Status:** **reverted** (2026-10-22). `page_helpers.js` restored to `active = !!fe;`,
  re-pinned, rebuilt, installed. Video recovered. **This is the canonical failure-loop
  example** — see `docs/FAILURE_LOOP.md`.
- **File:** `page_helpers.js` `installControlsAutoHide`.

### Popup guard — high-z ad overlays + viewport catcher + flixer scanner
- **Fix:** kill injected ad overlays (`z >= 1,000,000 && area >= 1000`), floating ad
  iframes, and viewport-catcher elements on non-video pages; exempt the main player iframe
  and player-control classes (`ytp-*`, `player-controls-*`, `vb-*`) from `covers_video`
  kills.
- **Broke / risked:** the early versions killed legitimate page UI that merely mentioned
  ad words, and killed player-control containers (breaking fullscreen + ff/rewind). Fixed
  by the `isPlayerChain`/`containsVideo`/`hasControls` exemptions and the player-iframe
  exemption.
- **GPU:** **neutral** (JS DOM removal; no continuous GPU ops).
- **Status:** `verified` — `overlay_killed` events with `reason: high_z_ad |
  floating_iframe | viewport_catcher`; no `covers_video` kills on player classes.
- **Files:** `popup_guard.js`, `PopupGuard.kt`.

### CSS (page-level) fullscreen replaces native fullscreen
- **Fix:** replaced native WebView fullscreen with page-level (CSS) fullscreen so
  cross-origin players keep working; auto-resume the video if entering CSS fullscreen
  pauses it; lift the simulated pointer when the cursor idles during playback.
- **Broke / risked:** CSS fullscreen means `fsElement()` is null (the player uses CSS, not
  the Fullscreen API) — this is the root cause the auto-hide patch tripped over.
- **GPU:** **neutral**.
- **Status:** `verified`.
- **Files:** `page_helpers.js` (CSS fullscreen module), `MainActivity.kt`.

### FF/rewind + play/pause in fullscreen (cross-origin players)
- **Fix:** forward FF/rewind and play/pause to cross-origin players in fullscreen.
- **Broke / risked:** none observed.
- **GPU:** **neutral**.
- **Status:** `verified`.
- **Files:** `page_helpers.js`, `MainActivity.kt`.

### TMDB image virtualization (extended to cinejoy)
- **Fix:** strip `src` from off-screen TMDB posters so the browser never requests/decodes
  them; restore `src` when they scroll into view. This is the real fix for the "flixer
  after other sites" crash: the 1×1 firewall reduced GPU *texture* load, but the
  request/decode/composite for all ~182 images still happened, and with prior video GPU
  state resident that residual load pushed the PowerVR over the edge.
- **Broke / risked:** none observed. (nippleflix later had to be removed from this guard —
  see Unreleased.)
- **GPU:** **removes** (skips off-screen image decode/composite).
- **Status:** `verified`.
- **Files:** `flixer_image_guard.js`, `MainActivity.kt`.

### tv_ui: always submit the form on Enter/GO
- **Fix:** the search form now submits on Enter/GO even when the page would otherwise
  swallow the key.
- **Broke / risked:** none observed.
- **GPU:** **neutral**.
- **Status:** `verified`.
- **File:** `tv_ui.js`.

---

## Older releases

See `docs/history/` for 1.3 → 1.9.9 change and validation notes. 2.2/2.3/2.3.1 release
notes are in git history (`git log --oneline`).
