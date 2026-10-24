# Part 9 — Documentation: verified claims inventory

Method: every concrete claim in the current docs was re-verified against the live source tree (v2.3.1, git HEAD `a390764` + uncommitted delta, see §9.3) on 2026-10-21/22 via grep/line-count/execution, plus live CDP checks on the Fire TV. 95 automated checks: **92 passed outright; the 3 "failures" resolved as 1 genuinely stale doc claim, 1 false-positive (comment matched), 1 check-script artifact (verified live instead)**.

## 6.1 Doc inventory and reliability

| Doc | Status | Reliability |
|---|---|---|
| `HANDOFF-full-program.md` (718 L) | Current (written for v2.3.1, corrected once at `a390764`) | **Highest.** Architecture, subsystems, regression map (§5), hard constraints (§6), protected-file gate (§7), symptom→diagnosis (§12). Two errors found in this review (§9.2). |
| `README.md` | Current (v2.3.1) | High for user-facing facts (menu = 14 items, settings = 7 items — both match code). |
| `TESTING.md` | Current (added `480f62e`) | High. The interdependency map + per-site regression checklist. One gap: missing the `viewport_catcher` reason (§9.3). |
| `SECURITY.md` | Current | Short; keystore/secret handling. |
| `RELEASE_2.3.1_NOTES.md`, `RELEASE_2.2_NOTES.md` | Release notes | Verified claims: YouTube auto-hide skip ✓, `allowfullscreen` on player iframes ✓, start page 2×5=10 tiles ✓, minSdk 22 / targetSdk 33 ✓ (build.gradle.kts). |

| `HANDOFF.md` (root, 2.2 era) | Superseded by HANDOFF-full-program.md | Use only for history. |

## 6.2 Verified claims (spot-checked, all PASS)

- Line counts: MainActivity.kt **3956** ✓, page_helpers.js **1911** ✓, tv_ui.js **335** ✓, flixer_image_guard.js **286** ✓, seek_click.js **72** ✓, fmhy_theme.js **23** ✓. (popup_guard.js: see §9.3 — the doc's 434 was correct *at HEAD* but the working tree is now 538.)
- All **19 `@JavascriptInterface` bridge methods** named in the doc exist in MainActivity.kt ✓.
- `__fbCmd`/`__fbRes` postMessage protocol + ancestor-source check ✓; `frameIsFlixer` ✓.
- `__fb.seek` returns `ok`/`frame`/`none`/`failed`; Java treats `'frame'` as success ✓.
- `diagKey(code, branch)` + `key` event with `handler` field ✓.
- Function line numbers (navigateUrl ~1514, dispatchKeyEvent ~1670, enterFullscreen ~2570, etc.) — all confirmed present (±a few lines) ✓.
- `TvWebView.onCheckIsTextEditor() = false` ✓.
- SeekRepeatPolicy: first press 10 s, +30 s after 600 ms hold, +60 s after 2500 ms ✓ (read directly).
- Diagnostic events emitted: `overlay_killed`, `floating_seen`, `pointer_lifted`, `media_health`, `edit_result`, `player_geometry`, `frame_script_alive`, `process_start`, `react_onclick_called` (popup_guard.js L88) ✓.
- **`seek_result` / `seek_fallback` are NOT emitted anywhere** in Kotlin or JS ✓ — this settles the internal contradiction in HANDOFF-full-program.md (§5.3 vs §6 line ~462): **§6's citation of `seek_result`/`seek_fallback` is the stale/phantom one**; §5.3 and §12 are correct. Do not look for those events in logs.
- z-index `2147483647` in CSS-fullscreen intercept ✓.
- popup_guard structural rules (floating iframe z≥1000 <25% viewport; covers ≥10% of video; z ≥ 1,000,000) ✓.
- `tests/protected-fixes.json` + `check-protected-fixes.py` exist and **PASS** ✓.
- All 8 cited commit hashes exist in git history ✓.
- `importBookmarkText` referenced by Regression171Test.kt ✓.
- **No `?.` or `??` in any injected JS** (0 real occurrences in all 5 injected assets; the only match is a comment in flixer_image_guard.js L12 stating the constraint) ✓.
- `pageEpoch` / `interactionSerial` exist ✓.
- `form.requestSubmit()` in tv_ui.js submit path ✓.
- `virtualize()` at top of `sync()` in flixer_image_guard.js ✓.
- `liftPointerIfPlaying` gated on playback ✓.
- `onFlixerPage` vs `onImageGuardedPage` distinct ✓.
- `shouldInterceptRequest` 1×1 firewall for `image.tmdb.org` ✓.
- Watchdog separate process, reopens `lastCrashUrl` ✓.
- `webkitEnterFullscreen` on `HTMLVideoElement.prototype` ✓.
- **Live CDP:** page viewport is exactly **960×540 CSS px at devicePixelRatio 2** (screen 1920×1080) — confirms the doc's viewport claim, which is set by `useWideViewPort=true` + `loadWithOverviewMode=true` (MainActivity L735-736), not by any hardcoded 960×540.

## 6.3 ⚠ UNCOMMITTED DELTA — the tree is ahead of git HEAD

`git status` shows two modified, **uncommitted** files (as of 2026-10-22):

1. **`app/src/main/assets/popup_guard.js`** (+118/−8 lines, 434 → 538):
   - **New `viewport_catcher` rule** in `killOverlays`: a positioned element covering **≥90% of the viewport** at **z ≥ 1000** on a page where `covers_video` cannot fire (no `<video>` yet — home/show/season/pre-roll pages). Targets ad injectors that use z=9999/1000 full-screen click catchers (seen on video site/removed poster site and video site, 2026-10-22). Player + its container chain exempt (`isPlayerChain`). New `overlay_killed` reason value: **`viewport_catcher`** (in addition to `floating_iframe` / `high_z_ad` / `covers_video`).
   - **New `killCatchers()` — minimal poster-grid site scanner.** The full-DOM `killOverlays` walk (querySelectorAll over every div/span/a/iframe) triggers the PowerVR driver crash during poster-grid site's intro, so the heavy scanner stays OFF on poster-grid site — but poster-grid site still gets inline ad click-catchers that the `window.open` guard cannot catch. `killCatchers` scans **only body's direct children + iframes**, applies only the cheap rules (floating iframe ad; full-viewport catcher at z ≥ 1000), exempts the **main player iframe** (largest iframe with a real, non-blank, non-srcdoc src) and its container chain, and skips elements containing a `<video>` or real controls (`button, [role=button], input, select`). Runs immediately + every **5 s**, **no MutationObserver**.
   - Host branch flipped: poster-grid site → `killCatchers` (5 s interval); all other hosts → full `killOverlays` + debounced MutationObserver + slow interval (unchanged).
2. **`tests/protected-fixes.json`** — popup_guard.js hash updated to match the new file (the protected-fixes check still PASSES).

**Consequences for future sessions:**
- The "current" popup guard has **four** kill reasons: `floating_iframe`, `high_z_ad`, `viewport_catcher`, `covers_video`. TESTING.md and HANDOFF-full-program.md list only three — update them when this delta is committed.
- Any future popup-guard change must preserve: the poster-grid site minimal-scan split (PowerVR), the player-iframe exemption (both scanners), the containsVideo/hasControls protections, and the `data-fslogged` once-per-element reconnaissance logging.
- **Commit this delta** (or consciously revert it) before the next release build — a release built from the tree will ship it, but git history will not explain it.

## 6.4 Known doc gaps / corrections (for the next doc update)

1. HANDOFF-full-program.md §6 line ~462 cites `seek_result`/`seek_fallback` — **phantom events, never emitted** (verified by full-tree grep). §5.3/§12 are correct.
2. popup_guard.js line count 434 → 538 (uncommitted delta, §9.3).
3. `viewport_catcher` is a fourth `overlay_killed` reason not listed in TESTING.md/HANDOFF.
4. README's "tap the on-screen seek bar" line is ambiguous: the *native* seek bar is display-only; the line refers to the player's in-page seek bar (or the OSD). Keep the distinction explicit in any rewrite.
5. Historical designs (CSS dark-mode injection, repair199 baseline, 1.9.x menu layout) are no longer documented in this repository.
