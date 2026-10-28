# Buccaneer — Regression Checklist & Subsystem Map

**Why this file exists.** We keep hitting the same failure loop: fix one thing,
break another. The reason is that the features below **share code paths**
(key handling, focus, pointer/hover events, DOM manipulation). A change in one
ripples into the others. This file is the map of those shared paths plus the
checklist to run **after every fix** so a regression is caught here, not in
normal use on the TV.

**Rule: no fix is done until the checklist below passes.** If a fix cannot be
verified against the checklist, say so explicitly rather than claiming it works.

---

## The five subsystems and where they live

| # | Subsystem | Code | What it does |
|---|-----------|------|--------------|
| 1 | Keyboard / Enter / GO (search) | `assets/tv_ui.js` (`edit`/submit), `Keypad.kt`, `MainActivity.kt` (key routing, `typeGo`/`typeText`) | Types into search fields, submits on Enter/GO |
| 2 | Fullscreen controls autohide | `assets/page_helpers.js` (`installControlsAutoHide`, poke, `__fb`), `MainActivity.kt` (`liftPointerIfPlaying`, `hideCursor`, hover events) | Hides player controls after idle in fullscreen |
| 3 | FF / rewind (transport keys) | `MainActivity.kt` (`seekFromKey`, `videoCommand`, media-key routing), `assets/page_helpers.js` (`__fb.seek`) | Seeks the video on remote FF/rewind |
| 4 | Popup guard | `assets/popup_guard.js` (`killOverlays`, `window.open` guard), `PopupGuard.kt` | Kills ad popups and click-catcher overlays |
| 5 | Image guard (TMDB virtualization) | `assets/flixer_image_guard.js`, `MainActivity.kt` (`onImageGuardedPage`, `shouldInterceptRequest`) | Strips off-screen posters to save memory (prevents GPU crashes) |

## Known interdependencies (what can break what)

- **1 (keyboard) ↔ page key handlers.** The page can only ever receive
  *synthetic* (untrusted) Enter events. React handlers call `preventDefault()`
  on them, so the `proceed` flag is false. The submit path must therefore call
  `form.requestSubmit()` **regardless of `proceed`** when the field is in a
  form. Re-gating submit on `proceed` re-breaks search (the removed poster site bug,
  fixed in `5434e6d`). See the note in `tv_ui.js`.
- **2 (autohide) ↔ pointer/hover events.** The DPAD virtual cursor sends
  `HOVER_ENTER`/`HOVER_MOVE` but never `HOVER_EXIT`, so a player that hides
  controls on pointer-out keeps them pinned visible. The fix lifts the pointer
  (`ACTION_HOVER_EXIT`) only while playback is active. Sending `HOVER_EXIT`
  produces no mouse movement, so it is YouTube-safe. See `liftPointerIfPlaying`.
- **3 (FF/rewind) ↔ start-page overlay + focus.** Transport keys are consumed
  by the app's start page (bookmark launcher) if it is on top, and only reach
  the player when the page/frame has focus. Navigating via CDP `Page.navigate`
  does **not** hide the start page (only the app's own `navigateUrl` does), so
  test transport keys after a real app navigation.
- **4 (popup guard) ↔ real page UI.** The kill rules must be specific enough to
  spare real UI (the video site/lazy-load poster site hero layer carries a Play button and ad-like
  words in the synopsis — a text-based rule hid the Play button, fixed in
  `f0d6c0f`) but broad enough to catch injected ads (the video site "baseball"
  popups used a 32-bit-max z-index on a page with no video, fixed in
  `066fec3`). The safe signals are structural: iframe + high-z + small,
  covers-video, or z-index ≥ 1,000,000 — never text content.
- **5 (image guard) ↔ GPU stability.** The MutationObserver + interval scanner
  is skipped on poster-grid site because it triggers the PowerVR driver crash during the
  intro animation. Do not re-enable a full-DOM scan on poster-grid site.
- **All ↔ PowerVR GPU.** Rapid page navigation on image-heavy pages has caused
  SIGSEGV crash-loops in the RenderThread/GPU. Navigate deliberately; if the app
  crash-loops, restart it and re-forward CDP before continuing.

## Regression checklist (run after every fix)

Sites: poster-grid site, lazy-load poster site, removed poster site, video site, video site. (Add new sites here as they
are added to the bookmarks.)

For **each site**, verify all three:

1. **Search** — focus the search field, type a title, press GO/Enter.
   Results must appear (URL change or client-side results).
2. **Fullscreen autohide** — open a title, play it, enter fullscreen, wait ~10s
   without touching the remote. The transport controls must disappear.
3. **FF / rewind** — while in fullscreen and playing, press FF (video jumps
   forward ~10s) then rewind (jumps back ~10s).

Plus, for the **popup guard** specifically:
4. On a home/show/season page (no video), confirm no floating ad badge or
   full-viewport click-catcher remains after ~10s. Check the diagnostic log for
   `overlay_killed` with `reason: high_z_ad` (or `floating_iframe` /
   `covers_video`) and **no** lingering `floating_seen` for z ≥ 1,000,000.

Plus, for the **image guard** specifically:
5. On the poster-grid and lazy-load poster grids, confirm in-view posters render and the app
   does not crash-loop during the intro animation.

### How to read the diagnostic log

```
adb -s <device> shell "run-as com.buccaneer.browser cat files/diagnostic_session.jsonl"
```

- `overlay_killed` — a popup was removed (check `reason`).
- `floating_seen` — a floating high-z element was spotted but **not** killed.
  Any entry here with `z` ≥ 1,000,000 is a popup that got through — investigate.
- `pointer_lifted` — the autohide pointer-lift fired.
- `react_onclick_called` — the player-frame click workaround fired.

## Process rules (to keep the loop from coming back)

1. **One clean rule per fix.** Fix a subsystem as one coherent rule set, not a
   stack of patch-on-patch gates.
2. **Preserve prior fixes.** The protected files (`popup_guard.js`,
   `page_helpers.js`, `flixer_image_guard.js`, and the pinned `MainActivity.kt`
   regions) are hash-locked in `tests/protected-fixes.json`. If a fix must touch
   one, update the hash and make `tests/check-protected-fixes.py` pass.
3. **Add a code note at the fragile spot** explaining *why* the code is shaped
   the way it is and what breaks if it is changed (see the notes in `tv_ui.js`
   and `popup_guard.js`).
4. **Run the checklist before calling a fix done.** Report honestly which items
   passed and which could not be verified.
