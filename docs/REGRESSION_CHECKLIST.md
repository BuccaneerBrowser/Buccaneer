# Regression Checklist

Run this after **every** fix + build + install, before reporting a fix as done.
Goal: break the whack-a-mole loop where fixing one feature breaks another.

## Why this exists

Previous sessions kept fixing popups / search / fullscreen / ff-rewind and breaking
each other. We never had all of them working at once. The root cause: the popup
guard (`killOverlays` / `killCatchers`) and the input/fullscreen handling are too
aggressive — each reaches beyond what it should. This checklist forces a full pass
after every change so breakage is caught immediately, not discovered later.

## When to run

- After every code change + build + install.
- Before reporting any fix as "done."
- When the user reports a new issue (to check for regressions).

## The five features

### 1. Popups (blocked)
- **Test:** Navigate to a site with known popups (flixer movie page, or a page where a `z=2147483647` overlay appears).
- **How:** Manual (DPAD) — the popup is DPAD-focus-triggered; it cannot be reproduced via CDP.
- **Expected:** The popup does NOT appear (or is killed within ~200ms).
- **Automated signal:** `overlay_killed` events in the diagnostic log with `reason: high_z_ad | floating_iframe | viewport_catcher`.
- **Pass/Fail:** ___

### 2. Search / keyboard
- **Test:** Open search, type a query, navigate results.
- **How:** Manual (DPAD) — typing and result navigation require the remote.
- **Expected:** Search opens, accepts input, shows results, is navigable, and does not crash.
- **Automated signal:** Search UI element present in the DOM (CDP).
- **Pass/Fail:** ___

### 3. Fullscreen playback
- **Test:** Play a video in fullscreen (YouTube, vidbolt, or any player site).
- **How:** Manual (DPAD) — enter fullscreen, play video.
- **Expected:** Video plays correctly in fullscreen — no black screen, no crash, controls visible.
- **Automated signal:** Video element in fullscreen state (CDP); NO `overlay_killed` events with `reason: covers_video` on player-control classes (`ytp-*`, `player-controls-*`, `vb-*`).
- **Pass/Fail:** ___

### 4. FF / rewind remote buttons (in fullscreen)
- **Test:** In fullscreen, press the remote's FF and rewind buttons.
- **How:** Manual (DPAD) — requires the remote.
- **Expected:** FF and rewind work (video skips forward / backward).
- **Automated signal:** None (DPAD-dependent).
- **Pass/Fail:** ___

### 5. Poster auto-open (flixer)
- **Test:** Navigate the flixer movie grid with DPAD over several posters.
- **How:** Manual (DPAD) — the preview is DPAD-focus-triggered.
- **Expected:** The floating preview does NOT auto-open on focus. Pressing OK opens the movie page.
- **Automated signal:** Poster guard active via CDP (`window.__flixerPosterGuard === true`, `#__flixer_poster_guard_style` present).
- **Pass/Fail:** ___

## Automated checks (CDP smoke test)

Run after each build:
```
node /tmp/cdp_regression.mjs <url>
```
It checks (on the given page):
- Popup guard active + any `overlay_killed` signals in the page.
- Search UI presence.
- Video element state (present, fullscreen, playing).
- Poster guard active (flixer only).

It does **not** check: FF/rewind, fullscreen feel, DPAD navigation smoothness —
those require the remote and are manual.

## Known breakage vectors (from the log)

- **`covers_video` kills on player controls** — the `killOverlays()` full scanner
  kills YouTube/vidbolt player-control containers (`ytp-player-content`,
  `player-controls-background-container`, `vb-bg`). This breaks fullscreen playback
  and ff/rewind. The `isPlayerChain` / `containsVideo` / `hasControls` exemptions
  must spare these. If `covers_video` kills appear on player classes, the exemption
  is failing.
- **`high_z_ad` kills** — the new `isHighZAd` rule (z >= 1,000,000 && area >= 1000)
  kills max-z ad overlays. Correct on flixer/cinejoy. Must NOT kill legitimate
  high-z UI (player, modals with video).
- **Poster guard interval** — the 200ms re-apply scan is flixer-only; must not run
  on other hosts (check the host guard at the top of `flixer_poster_guard.js`).

## Results log

| Date       | Build (PID) | Popups | Search | Fullscreen | FF/Rew | Poster | Notes |
|------------|-------------|--------|--------|------------|--------|--------|-------|
|            |             |        |        |            |        |        |       |

## Notes

- The popup and poster auto-open are DPAD-focus-triggered, so they cannot be fully
  reproduced via CDP. They require the user's remote to verify.
- The automated checks are a smoke test, not a full verification.
- A crash (process restart) on a TMDB site (flixer/cinejoy) is likely a PowerVR GPU
  issue; check `tmdb_allowlist_sync` counts and `deviceAvailMemMb` in the log.
