# FLIXER ATTEMPTS LOG

Purpose: single source of truth for every attempt + result on the Flixer
detail-page crash/black-screen problem. CHECK THIS AT THE START OF EVERY TURN.
Do not repeat an approach already marked FAILED/REVERTED/INCONCLUSIVE.

Device: 10.4.20.137:5555 | App: com.buccaneer.browser | Fire TV AFTMM (Android 7.1.2)
WebView: Chromium 118 (118.amazon-webview-v118-5993-tv) | SoC: mt8695 | GPU: PowerVR Rogue
Crash page: https://flixer.gd/shows?tv=link-click&id=123542 (any show/movie info page)
Nova fix in page_helpers.js — DO NOT ERASE (SHA-256 6fbc9faa3cbb3b3c4c824bd4246f6d2e57bcdb85490541e287a38408e8b395b0)
Clean baseline commit: 25737ac. Build with system `gradle assembleDebug` (no wrapper).
Gate: `python3 tests/check-protected-fixes.py` must PASS before build.

---

## CRASH CAPTURED 2026-10-27 01:41:15Z (episode-list scroll, full context)
- Reproduced by Mommy: scrolling the episode list on a Flixer detail page.
- **Crash:** SIGSEGV, code 1 (SEGV_MAPERR), fault addr 0x4, oom:0,
  **thread Chrome_InProcGp** (the WebView's in-process GPU process — NEW: prior
  crashes were HWUI RenderThread via glDrawArrays). Entry this time:
  `glBindFramebuffer+372` -> `KEGLReleaseRenderTarget+77` (libIMGegl.so).
  Same driver use-after-free signature (KEGLReleaseRenderTarget+77, fault 0x4),
  different entry point: framebuffer bind, not draw.
- **Sequence (UTC, session 7a232bc8, /tmp/bucc_diag10.jsonl 9649 lines):**
  - 01:40:18 user_navigation flixer.gd home; firewall active.
  - 01:40:26-27 home posters + hero downscaled (native intercepts working).
  - 01:40:38 `trailer_video_blocked` — YouTube embed intercepted (CONFIRMED active).
  - ~01:40:45-50 detail page loads (6 more poster_downscaled at 01:40:50).
  - 01:41:00 tap (388,192) -> tap_stack count 25, top = detail overlay
    (`space-y-6 bg-black/40 backdrop-blur-md`).
  - 01:41:07-13: **170x KEYCODE_DPAD_DOWN** (handler browse) = scrolling the
    episode list; `tmdb_allowlist_sync` count climbs 14 -> 15 (episode posters
    entering the pre-load band, getting live textures).
  - 01:41:13.683 last JSONL event; **01:41:15.745 SIGSEGV** (2.06 s gap).
- **Data-backed conclusion:** crash correlates with episode-list scrolling that
  brings new episode posters into the allowlist (live GPU textures growing)
  while the GPU process is mid-composite. The trailer intercept, hero/poster
  downscale, and image guard were ALL active and did not prevent it.
- **Next:** instrumented build (compositor_proxy in flixer_image_guard.js,
  zero behavior change) built + gate PASS. Install (with permission) ->
  reproduce episode-list scroll -> read last compositor_proxy sample
  (realImg/emptyImg/fixedImgs/clippedImgs) at death.

## NEW SESSION 2026-10-26 (post-handoff; DOM re-verified, v1-v4 tested)
- DOM RE-VERIFIED (differs from handoff): the blacking hero (object-[50%_35%],
  945x540, src HzytiKM3riCMcbVYBJhlB.jpg) is in the GIANT PAGE DIV, NOT in the
  overlay. The UI (H1, buttons) is in a SEPARATE fixed overlay
  (div.fixed.inset-0.z-[100], bg-black/90). The modal card is
  div.relative.max-w-6xl.mx-auto.bg-black/95. The hero container
  (div.relative.md:aspect-video, 928x522) has a ZERO-SIZED img (rect [0,0,0,0],
  src RGTCjHGqtV5FP3Img6w1h.jpg, naturalWidth 780) — a DIFFERENT image than the
  real hero.
- v1 (hero fixed z=99 + overlay bg transparent): FAILED (64194B, still black).
  Confirms #29/#31 (low z does not restore).
- v2 (hero fixed z=99999 + overlay z=100000 + overlay bg transparent): PARTIAL
  (154570B). Hero restored as a DIMMED BACKDROP around the modal edges, but the
  modal card's bg-black/95 covers it inside the card.
- v3 (v2 + modal card bg transparent): PARTIAL (154591B). Hero STILL black inside
  the modal card.
- v4 (v3 + trailer black containers [div.absolute.inset-0.w-full.h-full.bg-black,
  2 found] transparent + backdrop [div.fixed.inset-0.bg-black/50] transparent +
  backdrop-filter removed): PARTIAL (241969B, identical t=1s/t=25s). Vision: hero
  STILL black inside the modal. Only a thin strip of the page background peeks out
  around the modal edges.
- KEY FINDING: the hero's raster is restored ONLY for the VISIBLE part (the thin
  strip around the modal edges). The COVERED part (inside the modal card) stays
  black. Making the covering elements transparent AFTER the hero rasterized does
  NOT re-rasterize the covered part. The GPU does not re-rasterize a layer when
  its visibility changes.
- NEXT: apply the fix BEFORE the stall (at t~12s, after the hero loads) so the
  hero is visible from the start and the GPU rasterizes the FULL hero.

- v5 (hero fixed z=99999 [fully visible, above overlay] + UI bar (H1's parent) fixed
  z=100000, applied at t~14s BEFORE the stall): PARTIAL BREAKTHROUGH. Screenshot
  2,248,069B (2.2MB = fully rendered, matches #28), IDENTICAL at t=1s and t=25s
  (NO degradation). Vision: hero FULLY RESTORED (clear image, not black). BUT the
  page UI (title, rating, buttons) is GONE — the hero (z=99999) covers it, and the
  UI bar (z=100000) did NOT render on top.
- KEY FINDING (v5): making the hero position:fixed z=99999 (fully visible, above the
  overlay) FULLY restores the hero AND it SURVIVES the stall (no degradation at
  t=25s). This is the fix direction. The remaining problem is putting the page UI on
  top of the hero (z=100000) — the UI bar's fixed position/z-index didn't work.
- NEXT: inspect why the UI bar (z=100000) didn't render on top (position? z-index?
  empty?). Then make the UI visible on top of the hero.

- v6 (hero fixed z=99999 [above overlay] + CLONE the UI bar (H1's parent) and append
  to document.body at z=100000 + hide the original UI bar, applied at t~14s BEFORE
  the stall): *** FULL SUCCESS ***. Screenshot 1,439,484B (1.4MB, fully rendered),
  IDENTICAL at t=1s and t=25s (NO degradation). Vision-confirmed: hero FULLY
  RESTORED (clear two-person still with embers, NOT black) AND all UI visible on top
  (title "LINK CLICK (2021)", rating 8, "Returning Series" badge, date, "5 Seasons",
  and all 4 buttons: Continue S1:E1, My List, mute, close). Everything legible.
- ROOT CAUSE of v5 failure: the UI bar (z=100000) was INSIDE the overlay's stacking
  context (position:fixed z=100), so its z-index was relative to the overlay, not the
  root. The hero (z=99999, in the root context) was still on top. Cloning the UI bar
  and appending it to document.body (a direct child of body, root stacking context)
  at z=100000 puts it ABOVE the hero (z=99999).
- THE FIX (v6): (1) hero position:fixed z=99999 (own layer, above overlay, raster
  restored, survives the stall), (2) clone the UI bar (H1's parent) and append to
  body at z=100000 (escapes the overlay's stacking context), (3) hide the original
  UI bar. This is the working fix — persist it to source.
- NEXT: verify the fix survives navigation (episode click, back, reload) and other
  shows, then persist to the app source (page_helpers.js or similar).

- SOURCE PERSISTENCE (2026-10-26): v6 fix added to page_helpers.js in the flixer
  section (before `if (__isFlixer) return;`), as installFlixerHeroFix(). Design:
  one capture-phase 'load' listener on document (removed after the fix applies) —
  NO timers, NO MutationObservers, NO polling (respects the flixer GPU-crash
  constraint: "No timers, no continuous polling"). When the hero img
  (object-[50%_35%]) fires 'load', applyFix() runs: hero position:fixed at its
  current rect + z=99999; clone H1's parent (UI bar) to document.body at z=100000
  (id __fb_uiBar_clone); hide original UI bar. Guard: window.__fbFlixerHeroFix.
  RISK NOTE: 'load' fires when the hero img finishes loading (t~5-10s); if the
  hero's rect is not final at that moment, the fixed size may be slightly off.
  VERIFY ON DEVICE: (1) hero not black after 30s, (2) UI visible on top,
  (3) no GPU crash (no SIGSEGV in logcat), (4) fix survives navigation.

- ON-DEVICE VERIFICATION (2026-10-26 ~17:02 MST): BUILT (gradle assembleDebug,
  gate PASS), installed (debug install -r, data preserved), app restarted
  (PID 3753). Navigated to a Flixer detail page. RESULTS:
  (1) fix AUTO-APPLIED from source: window.__fbFlixerHeroFix=true, hero
      position:fixed z=99999 rect(0,0,945,540) [identical to v6 CDP test],
      UI clone #__fb_uiBar_clone at z=100000. The 'load' listener fired and
      applied the fix with NO timers/observers.
  (2) VISIBLE page: hero shows REAL ARTWORK (not black), UI (title, badges,
      synopsis, Play/List/Details buttons) rendered ON TOP of the hero.
  (3) STABLE 3+ min: screenshots byte-identical (1,898,501B) across t=25s and
      t=90s+ — NO degradation (the ~20s stall did NOT black the hero).
  (4) NO GPU crash: no SIGSEGV in logcat since app restart (all crash-buffer
      entries are stale, pre-15:36, from before this install).
  TAB-MISMATCH CAVEAT: the VISIBLE page was "Lupin" while the CDP target was
  the "Link Click" page (title/h1 confirm CDP=Link Click). This is the known
  #1 verification blocker (CDP + screencap can capture different tabs). The fix
  is confirmed APPLIED on the Link Click tab (fixFlag=true) and confirmed
  RENDERING on the visible tab (Lupin). Direct visual of the Link Click page
  itself is blocked by the tab mismatch — Mommy should navigate to Link Click
  herself to confirm that specific page renders (fix is per-document, so it
  applies identically to every Flixer detail page).

## CURRENT STATE (updated 2026-10-26 ~00:30 MST)
- Installed build: trailer intercept + native hero downscale (see attempt #11).
- Crash: ELIMINATED in testing (no crash in 120+ D-pad moves; was crashing at ~35-40).
- Speed: improved (avg frame 17.6ms vs 31.1ms; max 100ms vs 283ms).
- REMAINING PROBLEM: page still degrades to BLACK (GPU stops compositing) under
  sustained navigation OR after ~30-60s idle. DOM stays intact (CDP: 664 imgs, Play
  present) but GPU won't repaint; does NOT recover from D-pad moves.
- CANNOT REPRODUCE via CDP: tested 120s+ idle, 30+ D-pad moves, 5+ nav cycles
  (main<->detail), Play button -> watch page -> back, aggressive scroll. Page
  stays rendered throughout (25KB screenshots, rAF working, GPU mem stable 326-370MB).
- GPU mem on detail page: 326-370MB (out of 1.3GB total). Big layer 1920x24452
  (~188MB at 4 bytes/pixel). 664 images, 36 backdrop-filter elements, ~3568
  potential compositor layers.
- Trailer intercept: CONFIRMED WORKING (YouTube iframes show blank content via CDP
  isolated world). Hero downscale: CONFIRMED WORKING (hero is w780, not original).
- Hiding 29 off-screen rows (body height 12226->4672) did NOT reduce GPU mem
  (layer not re-allocated) and page stayed stable.
- USER VERDICT (2026-10-26): "degrading to black isn't going to work... it's no good
  at all like this, people cant play things or select episodes... you stopped but its
  not fixed. fail." It HAS worked before on this device/site (old build) — so it is
  solvable. User: do NOT dig through old code (too old), but it's proof it's possible.
- USER PROCESS RULE: keep this log, check it each turn, do not go in circles.

## ACTIVE GOAL
Make the detail page STAY rendered (no black) so the user can play + select episodes.
The black-screen GPU compositing failure is the target. Crash is already fixed.

## NEXT (concrete, not yet done)
- KEY REFRAME: the page is FULLY RENDERED at t=10s (1.7MB), then stalls at t=20s.
  The stall is NOT during load — it's ~10s AFTER the page is fully rendered and static.
  So the trigger is something that happens AFTER rendering, not during loading.
- RULED OUT this session: hero image (#18), cache-buster re-requests (#20, also
  REQUIRED for render), keep-alive (#16), early virtualization (#17), layer splitting
  (#14). Do NOT retry these.
- REMAINING CANDIDATES for the t~10-20s trigger:
  (a) GPU memory exhaustion over time (leak) — TEST: monitor GPU mem (memtrack)
      every 5s from t=0 to t=30s; if it climbs steadily, the GPU is leaking.
  (b) The sync()'s virtualize() (strips/restores src every 2s) causing re-renders.
  (c) The page's own JS (flixer's animations/timers) triggering a GPU op.
  (d) The big layer (1920x24452, 188MB) being re-allocated/tiled over time.
- IMMEDIATE NEXT TEST: GPU memory over time (t=0 to t=30s) to check for a leak.
  This is data-driven and will distinguish (a) from (b)/(c)/(d).

## LATEST FINDING (2026-10-25 ~21:35, detail page measurement)
- Detail page: DOM loads fully (664 imgs, Play visible, readyState=complete) but the GPU
  shows a FROZEN 49KB mostly-black frame (identical at t=45s and t=75s). The full render
  is ~1.9MB; 49KB = black/loading frame.
- Main thread during freeze: ALIVE and FAST. simple eval (1+1) = 150ms; getBoundingClientRect
  on the tall scroll container = 10ms wall / 0ms in-page; offsetHeight fast.
- => The image guard's sync() (3 passes x 660 imgs x getBoundingClientRect every 2s) is
  EXONERATED: the main thread and layout are NOT the bottleneck. The failure is the
  GPU/compositor itself not rendering a fully-loaded DOM.
- Logcat across the freeze: NO GPU/EGL/context-loss/compositor errors. Silent stall.
- So: main thread OK, layout OK, JS OK -> the GPU/compositor is the problem. Focus there.

---

## LATEST FINDINGS (2026-10-26 morning, detail page)
- maxTextureSize = 4096px (WebGL gl.getParameter(MAX_TEXTURE_SIZE)). The giant page
  layer is 24452px = 6x the GPU's max texture size. The GPU renders small layers fine
  (the Share Flixer modal rendered) but fails on the giant layer.
- TIMELINE (reproduced 3x): the page renders FULLY at t~10s (993KB-1.7MB screenshot),
  then degrades to BLACK at t~20s (29KB). The compositor works for ~10s, then STALLS.
- DECISIVE: the compositor is FULLY STALLED after the degradation. Hiding the poster
  rows, hiding the hero, or ANY DOM change produces a BYTE-IDENTICAL screenshot
  (29389B). The GPU produces NO new frames; DOM changes do not trigger a re-composite.
  This is not a specific-element problem — the compositor has stopped.
- KEEP-ALIVE RULED OUT: an infinite CSS rotation injected at t=1s (BEFORE the stall)
  did NOT prevent the degradation (still black at t=20s). The stall is NOT an
  idle/resume bug — a busy compositor still stalls.
- EARLY VIRTUALIZATION RULED OUT: collapsing 12 of 17 rows at t=11s (before the stall)
  did NOT prevent the black screen.
- LAYER SPLITTING RULED OUT: will-change:transform on the 33 rows + hero (applied late
  and early at t=6s) does NOT fix the black screen. Late: TEXT renders (title, rating,
  badge, seasons) but IMAGES stay black. Early: still black after 90s.
- IMAGE LOAD STATE: 660/665 images have naturalWidth===0 (not loaded). The image guard
  virtualizes 657/665 (empty src). Only 8 have src + are loaded. The hero IS loaded
  (naturalWidth 780) and on-screen (0,0, 945x540) but NOT compositing.
- PAGE HEIGHT IS VARIABLE: 1055px when partially loaded, 12108px when fully loaded.
- RECOVERY: only Page.reload / Page.navigate recovers a stalled compositor (then it
  degrades again). D-pad moves do NOT recover it.

## ATTEMPT LOG (newest last; each = what / result / verdict)

#28 HERO position:fixed test (CDP) — BREAKTHROUGH
  WHAT: after the hero turned black, made the hero position:fixed (top:0, left:0,
  945x540, z-index:99999, will-change:transform) — promoting it to a separate layer
  OUTSIDE the giant 1920x25468 layer.
  RESULT: BREAKTHROUGH. The screenshot jumped from 51156B (black) to 2,248,069B
  (2.2MB, fully rendered). Vision-confirmed: the hero image (photo of 4 people) is
  fully visible. Hero state: pos=fixed, complete=true, nw=780, nh=439.
  BUT: the hero is now z-index:99999, so it's ON TOP of everything — the page UI
  (title, metadata, Play button, which have z-index 100) is COVERED by the hero.
  VERDICT: KEY FINDING. The hero was inside the giant 1920x25468 layer, whose
  rasterized content was being lost. Making the hero position:fixed (a separate layer
  OUTSIDE the giant layer) RESTORED the hero's rasterized content. This is the fix
  direction. Next: lower the z-index (to < 100) so the page UI is on top of the hero,
  and verify the page UI (title, metadata, Play button) is visible.

#29 HERO position:fixed z=1 test (CDP) — FAILED
  WHAT: after the hero turned black, made the hero position:fixed (top:0, left:0,
  945x540, z-index:1, will-change:transform) — a low z-index to keep the page UI on top.
  RESULT: FAILED. The screenshot stayed at 51156B (still black). The hero was NOT
  restored at z-index:1.
  VERDICT: z-index:1 does NOT restore the hero. The hero needs a HIGH z-index to
  escape the giant layer's stacking context. (z-index:99999 worked, z-index:1 didn't.)

#30 HERO z=99999 + UI overlay z=100000 test (CDP) — PARTIAL
  WHAT: after the hero turned black, made the hero position:fixed z-index:99999
  (restores the hero) AND raised the UI overlay (div.fixed.inset-0.bg-black/90.z-[100])
  to z-index:100000 (to put the page UI on top of the hero).
  RESULT: PARTIAL. The screenshot was 82971B (hero NOT fully restored). Vision-confirmed:
  the page UI (title, metadata, Play button) is visible, but the hero is COVERED by the
  UI overlay's semi-transparent black background (bg-black/90 + bg-black/50 backdrop).
  The hero is dimmed to near-black.
  VERDICT: The UI overlay's background covers the hero when the overlay is raised above
  the hero. The overlay forms a stacking context (position:fixed + z-index:100), so its
  descendants (title, metadata, Play button) cannot escape above the hero. Need a
  different approach to put the page UI on top of the hero without covering it.

#31 HERO position:fixed z=99 test (GPT-directed, CDP) — FAILED
  WHAT: after the hero turned black, made the hero position:fixed (top:0, left:0,
  945x540, z-index:99, will-change:transform) — below the overlay's z-index:100.
  Left the overlay and its content untouched.
  RESULT: FAILED. The screenshot stayed at 29389B (still black). The hero was NOT
  restored at z-index:99. Persistence: still 29389B after 30s.
  VERDICT: z-index:99 does NOT restore the hero. The hero needs a z-index ABOVE the
  overlay's 100 to be restored. (z-index:99999 worked, z-index:99 didn't.) This
  confirms GPT's "second outcome": the successful rendering depends on the z-index being
  high enough to escape the overlay's stacking context, not just position:fixed.
  NEXT: compare the hero's compositing layer before/after the intervention (per GPT).

#27 INDEPENDENT-PAINT PROBE (GPT-directed, CDP)
  WHAT: after the hero turned black (t~30s), placed two independent fixed-position
  elements over the black region: (1) a 120x120 solid red square with green border
  (CSS color, no image), (2) a copy of the hero image (same already-loaded URL) in a
  new <img> element. Screenshot after each.
  RESULT: DECISIVE — GPT's FIRST outcome. BOTH probes appeared in the black region
  (vision-confirmed):
    - Red square with green border: YES (appeared top-left)
    - Cyan-bordered hero image copy: YES (appeared, a photo of 4 people)
    - Probe img state: complete=true, nw=780, nh=439 (loaded fine)
    - Byte counts: black=51156B, +color=51184B (+28B, solid color compresses well),
      +image=238914B (+187KB), after-remove=51156B (back to black)
  VERDICT: KEY FINDING. The compositor IS producing new frames (both probes appeared).
  The image resource IS available and can be presented (the copy appeared). The problem
  is SPECIFIC TO THE ORIGINAL HERO ELEMENT'S LAYER — its rasterized content is lost, but
  a NEW element with the same image works fine. So the fix is to RE-CREATE the hero
  element (remove and re-add, or clone and replace) when it goes black. Next: test
  hero re-creation.

#26 Log mining of the failure run (diagnostic_session.jsonl, 12,713 events)
  WHAT: extracted the native diagnostic log and correlated tmdb_image_blocked /
  tmdb_allowlist_sync / subresource_error / low_memory events with the hero failure.
  RESULT: GPT Outcome B confirmed.
    - Hero URL was NOT blocked: all 6 tmdb_image_blocked events are the generic base
      URL (https://image.tmdb.org/), none match the hero currentSrc
      (w3agwUSMpJ4X9t1jGrDtqqRPjDQ.jpg).
    - No subresource errors for the hero: all 9 subresource_error are the generic base
      URL with net::ERR_FAILED.
    - No low_memory on the detail page: all 3 low_memory events are on the home page.
    - Allowlist count=3 (only 3 URLs in the allowlist, synced 12,079 times — very
      frequent, but the hero is loaded so it's in the allowlist).
  VERDICT: The failure is NOT a network/allowlist issue. The image remains loaded
  (complete=true, naturalWidth=780) but its rendered content is lost. The problem is in
  RASTERIZATION, TEXTURE AVAILABILITY, or LAYER PRESENTATION. Next: independent-paint
  probe (GPT's next diagnostic) to classify which stage fails.

#25 HERO-STATE + RECOVERY test (GPT-directed, CDP)
  WHAT: at t~15s (rendered), at the moment the hero turns black, and after recovery probes,
  collected: element identity, image state (currentSrc/complete/naturalW/H/rect/computed
  display/visibility/opacity/filter/transform), page state (DOM node identity, URL change,
  JS alive, layer-tree events), and two recovery probes (visibility toggle off/on in
  separate rAFs; transform nudge translateZ(0)).
  RESULT: DECISIVE. The hero is an <img> (945x540, TMDB w780). At render AND at black,
  its state is IDENTICAL and fully valid:
    - complete=true, naturalWidth=780, naturalHeight=439 (resource loaded, correct dims)
    - currentSrc UNCHANGED (same URL at render and black)
    - DOM node still attached (not detached)
    - display=block, visibility=visible, opacity=1, filter=none, transform=none (all normal)
    - JS alive (1+1=2)
    - layer-tree change events: 118 -> 119 (no major structural change)
  Recovery probes: visibility toggle (off/on in separate rAFs) = NO effect (still 51KB
  black). Transform nudge (translateZ(0)) = NO effect (still 51KB black).
  VERDICT: KEY FINDING. The image RESOURCE is fully valid when it turns black — the
  problem is NOT a lost/invalidated image, NOT a URL change, NOT a styling change, NOT a
  detached node. The image's RENDERED CONTENT has been lost at the GPU/compositor level
  (rasterization/compositing failure). DOM/style changes cannot recover it (the compositor
  is fully stalled, producing no new frames). This points to a GPU/compositor bug: the
  rasterized content of the (large) layer is being lost/evicted and cannot be re-composited.
  NOTE: the hero is only 780x439 (small), but it lives inside the giant 1920x24452 layer.
  The GPU is likely losing the rasterized content of the GIANT layer (which contains the
  hero), not the hero image itself.

#24 GPT freeze test — freeze ALL of sync()'s work after full render (CDP)
  WHAT: let the image guard run normally through initial load (incl. cache-buster).
  Once the page was fully rendered (t=15s, 1.7MB, Play visible), freeze ALL of sync()'s
  work: override window.Buccaneer.tmdbAllowlist to a no-op (blocks the native push +
  counts sync() calls) AND override HTMLImageElement.prototype.src setter to a no-op
  (blocks virtualize strip/restore + cache-buster re-requests + counts src changes).
  This blocks sync() from the timer, IntersectionObserver, AND MutationObserver.
  RESULT: PARTIAL. The freeze was effective (sync=0, src=0 after freeze — the guard's
  work was completely blocked). But the page STILL degraded to black at t~32s (51KB,
  vision-confirmed: hero black, only text/buttons). HOWEVER, the stall was DELAYED:
  page fully rendered at t=15s, degraded at t~32s (~17s after render) vs. the usual
  ~10s after render. So the image guard's sync() is a CONTRIBUTING FACTOR that
  accelerates the stall (~12s delay when frozen), but it is NOT the sole cause — there
  is a SECOND trigger that eventually stalls the compositor even with the guard frozen.
  VERDICT: KEY FINDING. Two causes: (1) image guard sync() accelerates the stall,
  (2) an unknown second cause triggers it eventually. Next: find the second cause
  (page JS timers? compositor internal timer? big-layer re-tiling?).

#23 WebGL keep-alive — continuous WebGL render loop (CDP)
  WHAT: inject a 256x256 WebGL canvas with a continuous requestAnimationFrame render
  loop (rotating triangle, heavy GPU workload) at t=1.2s. This keeps the GPU genuinely
  busy (unlike the tiny 3px CSS rotation in #16).
  RESULT: FAILED. The WebGL canvas was rendering (glka:true), but the page STILL
  degraded to black at t=20s (31986B).
  VERDICT: KEY INSIGHT. The GPU itself is FINE (it's rendering the WebGL canvas). The
  stall is specific to the PAGE'S COMPOSITOR (Chromium's layer compositor, a separate
  GL context), not the GPU in general. A keep-alive in a separate GL context does NOT
  keep the page's compositor busy. Combined with #16 (CSS keep-alive in the page's
  compositor also failed), the "compositor idle" hypothesis is WEAK — the stall is a
  Chromium-compositor bug at ~20s, not a GPU idle/power-save issue.

#22 Animation/intro check (investigation)
  WHAT: checked document.getAnimations().length, GSAP presence, and sessionStorage
  hasShownIntro at t=4s,8s,12s,16s,20s,24s on the detail page.
  RESULT: intro IS skipped (hasShownIntro=true). GSAP is NOT loaded (gsap=false). CSS
  animations: 6 at t=4s, 5 at t=8s, 2 at t=12s, 0 at t=16s/20s/24s. So by the time of
  the stall (t=20s), NO animations are running.
  VERDICT: RULED OUT. The stall is NOT caused by running animations (the flixer intro
  is skipped, GSAP absent, CSS animations finished by t=16s). The stall is purely
  TIME-BASED (~20s after load), independent of content and animations.

#21 GPU memory over time (investigation)
  WHAT: monitored GPU memory (dumpsys meminfo Graphics) every 5s from t=0 to t=35s on
  the detail page.
  RESULT: GPU memory is STABLE (no leak). Values (KB): 332688, 236464, 214157, 341847,
  338167, 317935, 317935 (t=5s to t=35s). Fluctuates 210-340MB but does NOT climb
  steadily. GPU has 1.3GB total; using ~25%.
  VERDICT: RULED OUT. GPU memory exhaustion/leak is NOT the trigger. The GPU is not
  running out of memory.

#20 Cache-buster re-request block — override src setter to ignore ?r=/&r= URLs (CDP)
  WHAT: override HTMLImageElement.prototype.src setter to ignore cache-buster URLs
  (the image guard's sync() re-request mechanism, lines 172-178). Applied at t=0.9s.
  RESULT: FAILED (cache-buster is NOT the trigger). The override blocked 83 re-requests
  over 90s (blocked count: 13,25,34,42,49,59,66,73,83), but the page STILL degraded to
  black at t=20s (29976B). ALSO: blocking the re-requests made the t=10s render WORSE
  (48KB vs 1.7MB in the control) — the cache-buster re-requests are NECESSARY for the
  on-screen images to load (they were initially blocked as 1x1 placeholders).
  VERDICT: RULED OUT. The cache-buster re-request is not the trigger; it's required for
  the page to render. Do NOT block it.

#19 Poster-rows block — hide entire rows container before the stall (CDP)
  WHAT: navigate to detail page; at t~4s (rows container had 3 children), set
  display:none on the entire rows container (div.relative.-mt-16.z-10.pb-20).
  RESULT: INCONCLUSIVE. Page did NOT render at t=10s (23786B, already black/degraded),
  then stayed black (29389B). Unlike the no-hero test (which rendered 1.7MB at t=10s),
  hiding the rows container changed the page's behavior — it never rendered properly.
  Cannot determine if the rows are the trigger (the page didn't reach the rendered state).
  VERDICT: INCONCLUSIVE. Hiding the rows container may itself trigger the bug (layout
  shift) or the page just degraded early this run.

#18 Hero block — hide hero image before the stall (CDP)
  WHAT: navigate to detail page; at t~4s (hero not yet loaded, naturalWidth 0), set
  display:none + remove src on the hero image.
  RESULT: FAILED (hero is NOT the trigger). Page rendered FULLY at t=10s (1714541B,
  1.7MB — hero, title, Play, posters all painted), then degraded to black at t=20s
  (29389B). Blocking the hero image did NOT prevent the stall.
  VERDICT: RULED OUT. The hero image texture is NOT the trigger for the stall.

#17 Early virtualization — collapse off-screen rows before the stall (CDP)
  WHAT: navigate to detail page; at t~11s (17 rows present), collapse rows 5-17
  (display:none) to reduce the layer count BEFORE the ~20s stall.
  RESULT: FAILED. Still black (29KB) from t=20s onward.
  VERDICT: RULED OUT. Reducing the row count early does not prevent the stall.

#16 Keep-alive animation — infinite CSS rotation before the stall (CDP)
  WHAT: inject a 3px infinite-rotation element at t=1s (before the ~20s stall) to keep
  the compositor busy (the S21 green-screen fix).
  RESULT: FAILED. Confirmed injected at t=1s, but the page still degraded to black at
  t=20s.
  VERDICT: RULED OUT. The stall is NOT an idle/resume bug; a busy compositor still stalls.

#15 DOM-change test — hide rows / hide hero while stalled (CDP)
  WHAT: with the page already black, hide the poster rows, then hide the hero image.
  RESULT: DECISIVE. All screenshots BYTE-IDENTICAL (29389B). The compositor is fully
  stalled — DOM changes do not trigger a re-composite. Not a specific-element problem.
  VERDICT: CONFIRMS the compositor-stall mechanism. Recovery requires a reload.

#14 Layer splitting — will-change:transform on rows + hero (CDP)
  WHAT: force the 33 poster rows + hero into separate compositor layers (34 layers) to
  avoid the giant 24452px layer exceeding maxTextureSize (4096px).
  RESULT: FAILED. Late: TEXT renders (title, rating, badge, seasons) but IMAGES (hero,
  Play, posters) stay black. Early (t=6s): still black after 90s.
  VERDICT: RULED OUT. Splitting the layer does not fix the black screen.

#13 maxTextureSize measurement + render/stall timeline (investigation)
  WHAT: measured the GPU's max texture size (WebGL) and the page's render/stall timeline.
  RESULT: maxTextureSize = 4096px. The page layer is 24452px (6x max). The page renders
  fully at t~10s, then degrades to black at t~20s (reproduced 3x).
  VERDICT: CONFIRMED the layer is 6x the max texture size; the stall is ~10s after load.

#12 CDP stress test (investigation, not a code change)
  WHAT: Extensive CDP testing of the detail page: 120s+ idle, 30+ D-pad moves
  (adb input keyevent), 5+ nav cycles (main<->detail), Play button -> watch page
  -> back, aggressive scroll, hiding 29 off-screen rows.
  RESULT: CANNOT REPRODUCE the black screen. Page stays rendered throughout
  (25KB screenshots, rAF working, GPU mem stable 326-370MB). GPU mem on detail
  page: 326-370MB (out of 1.3GB total). Big layer 1920x24452 (~188MB). 664 images,
  36 backdrop-filter elements, ~3568 potential compositor layers. Trailer intercept
  CONFIRMED WORKING (YouTube iframes blank). Hero downscale CONFIRMED WORKING (w780).
  Hiding off-screen rows reduced body height 62% but did NOT reduce GPU mem (layer
  not re-allocated).
  VERDICT: INCONCLUSIVE. The black screen is either (a) intermittent and I've been
  lucky, (b) triggered by the app's native navigation (not CDP Page.navigate), or
  (c) already fixed by the current build. Need user to test on the real device
  with the real remote and report.

#1 Card guard v1 — vertical-grid virtualization (flixer_card_guard.js, 196 lines)
  WHAT: virtualize off-screen poster cards in vertical grid.
  RESULT: FAILED. Site DOM had CHANGED — cards are now in horizontal scroll rows
  (flex, >=20 children, clipping on row container, inner width ~3680px). Guard events
  near-zero; page still crashed/reset.
  VERDICT: REVERTED. Do not retry vertical-grid assumption.

#2 Card guard v2 — horizontal-row virtualization (rewritten flixer_card_guard.js, 176 lines)
  WHAT: virtualize off-screen cards in the horizontal rows.
  RESULT: FAILED. Page still degraded (Play flicker -> black); guard events near-zero.
  VERDICT: REVERTED. DOM virtualization of cards does not stop the GPU failure.

#3 Hero downscale v1 — URL rewrite (flixer_hero_guard.js, 145 lines)
  WHAT: JS rewrites hero TMDB src /t/p/original/ -> /t/p/w780/.
  RESULT: REGRESSION. Page slow + flaky, Play button black, episodes unselectable.
  Cause: TMDB firewall allowlist sync re-requested the rewritten URL in a loop.
  VERDICT: REVERTED. JS URL rewrite conflicts with the image-guard allowlist cycle.

#4 Hero downscale — canvas
  WHAT: draw hero to canvas, toDataURL, serve downscaled.
  RESULT: FAILED. Cross-origin TMDB image TAINTS the canvas; toDataURL throws
  SecurityError.
  VERDICT: RULED OUT. Canvas cannot read the hero.

#5 content-visibility:auto (CDP injection)
  WHAT: set content-visibility:auto on off-screen sections to skip layout/paint.
  RESULT: FAILED. This WebView does NOT enforce content-visibility (off-screen child
  still laid out).
  VERDICT: RULED OUT. Not supported in this Chromium 118 Amazon build.

#6 display:none live-window scheme (CDP)
  WHAT: hide off-screen content with display:none in a scrolling window.
  RESULT: FAILED. App crashed; watchdog did not restart (budget exhausted).
  VERDICT: REVERTED/ABANDONED.

#7 border-radius:0 on 300 poster wrappers (CDP A/B)
  WHAT: remove rounded clipping to reduce GPU round-corner cost.
  RESULT: INCONCLUSIVE. Page survived 210s, but CONTROL (no change) also survived
  215s. No measurable effect.
  VERDICT: NOT THE FIX.

#8 YouTube trailer iframe DOM removal (CDP)
  WHAT: remove the looping YouTube trailer <iframe> from the DOM.
  RESULT: FAILED. Produced a 0-byte framebuffer; destabilized the GPU.
  VERDICT: RULED OUT. DOM removal of the video is the WRONG mechanism.

#9 TRAILER INTERCEPT (shouldInterceptRequest)  *** KEPT ***
  WHAT: MainActivity.kt shouldInterceptRequest — on a guarded page, intercept
  youtube.com / *.youtube.com /embed/ requests, return blank HTML. No DOM change.
  RESULT: SUCCESS (partial). "crashes less", "best so far". Removed the continuous
  per-frame video decode+composite load.
  VERDICT: KEEP. This is the foundation of the current build.

#10 Poster-guard hypothesis (investigation, not a change)
  WHAT: tested whether flixer_poster_guard.js caused the Play-button flicker.
  RESULT: REJECTED. Zero candidates matching div[class~="z-50"][class*="bg-"] +
  position:fixed + <img> on the detail page. Play button is in a z-[100] overlay
  (fixed inset-0 bg-black/90 overflow-y-auto), not a z-50 poster card.
  VERDICT: NOT THE CAUSE.

#11 Hero downscale v2 — NATIVE intercept  *** INSTALLED (current) ***
  WHAT: MainActivity.kt shouldInterceptRequest — on a guarded page, for an
  image.tmdb.org /t/p/original/ URL that IS in the tmdbAllowlist, fetch the /t/p/w780/
  variant (fetchImageBytes, 6s timeout) and return it directly as the response.
  No DOM change, no URL change, bypasses the firewall for that request. Falls back to
  the 4K original on any fetch failure. Logs hero_downscaled.
  RESULT: PARTIAL SUCCESS.
    + Hero now 780x439 (was 3840x2160) — ~31MB GPU memory saved (confirmed via CDP
      naturalWidth + hero_downscaled events, bytes 30-74KB).
    + CRASH ELIMINATED: no crash in 120+ D-pad moves (was crashing ~35-40 moves,
      SIGSEGV KEGLReleaseRenderTarget).
    + Faster: avg frame 17.6ms (was 31.1ms), max 100ms (was 283ms), frames>50ms
      1.4% (was 6.7%).
    + Renders correctly on fresh load (hero, Play, title all visible).
    - STILL degrades to BLACK under sustained navigation or ~30-60s idle (DOM intact,
      GPU stops compositing, no recovery from D-pad).
  VERDICT: KEEP (real improvement: no crash + faster). Does NOT fix the black screen.

---

## CONFIRMED FACTS (measured, not speculation)
- Crash signature: SIGSEGV, fault addr 0x4, thread RenderThread,
  KEGLReleaseRenderTarget+77 in libIMGegl.so (PowerVR GLES) <- glDrawArrays/glblBindFramebuffer.
- Compositor layers: MAIN page 68 total / 47 drawsContent / ~59.85 MP.
  DETAIL page 119 total / 90 drawsContent / ~107.55 MP. Detail = 1.75x main.
- BIGGEST layer on detail: id 151, 1920 x 24452 (~47 MP) — the entire page content in
  ONE layer (single fixed inset-0 overflow-y-auto scroll container).
  Other big layers: 1920x5990 (x2), 1860x5886 (x2), 2044x3996, 1920x2882.
- DOM: MAIN 182 imgs / 190 clipping / 9 rows / ~4136px / 0 videos.
  DETAIL 660-675 imgs / 699 clipping / 33 rows / ~12376px / 1 looping YouTube trailer.
- LAYER TREE IS STRUCTURALLY STATIC during navigation: big layer 151 created once,
  paintCount 0->1 on first focus change, then ZERO structural changes across 45+
  D-pad focus changes. It is NOT being repeatedly invalidated/recreated.
- MEMTRACK cache rebuilds are PERIODIC system noise (fire at :00 of minutes), NOT
  navigation-triggered. The one 8-rebuild burst was Chrome-tracing overhead, not nav.
- Canvas is TAINTED (cross-origin TMDB) — cannot extract hero pixels.
- content-visibility NOT enforced in this WebView.
- The black screen: DOM fully intact (CDP: 664 imgs, Play offsetParent!=null) but the
  GPU stops compositing. Happens under navigation AND idle. Does not recover from
  D-pad moves. Process does NOT crash (PID stable) — it's a compositing failure,
  distinct from the (now-fixed) KEGLReleaseRenderTarget crash.
- Frame timing (rAF): before hero fix avg 31.1ms / max 283.6ms; after avg 17.6ms /
  max 100.1ms.
- maxTextureSize = 4096px (WebGL). The giant page layer (24452px) is 6x the max.
- TIMELINE: the page renders fully at t~10s, then the compositor STALLS at t~20s
  (reproduced 3x). The stall is NOT an idle bug (a keep-alive animation does not
  prevent it). After the stall, DOM changes do NOT trigger a re-composite
  (byte-identical screenshots) — the compositor is fully stopped. Recovery requires a
  Page.reload / Page.navigate (then it degrades again). D-pad moves do NOT recover it.

## RULED OUT (do NOT retry)
- JS URL rewrite of hero (allowlist re-request loop) — #3
- Canvas downscale (tainted) — #4
- content-visibility (not enforced) — #5
- DOM removal of trailer iframe (destabilizes GPU) — #8
- border-radius:0 (no effect) — #7
- Card DOM virtualization (doesn't stop GPU failure) — #1,#2
- Keep-alive animation (does not prevent the stall) — #16
- Early row virtualization (does not prevent the stall) — #17
- Layer splitting via will-change (text renders, images don't) — #14
- Hero image block (hero texture is NOT the trigger) — #18
- Cache-buster re-request block (NOT the trigger; required for render) — #20
- **`display:none` on the browse grid** (621-img / 32-row container behind the detail overlay)
  to cut static compositor workload — **2026-10-26, FAILED: crashed 18:29:46** (same
  KEGLReleaseRenderTarget). Hiding the browse grid does NOT stop the crash-on-episode-click.
  The episode list (what the user scrolls) is a separate workload — see
  `docs/FLIXER_MASTER_HANDOFF_2026-10-26.md`.
- Software rendering (user: NEVER suggest)
- Full-DOM walks (user constraint)
- Rasterization / hardware-accel changes before isolating the workload (GPT constraint)

## KEY CONSTRAINTS (standing)
- No software rendering. No full-DOM walks. One change at a time. Data-backed only.
- Don't erase Nova fix. Don't build from 5ec354f.
- May launch/restart the app anytime (constraint REVOKED by user).
- Trailer intercept (#9) is the proven foundation — keep it, don't remove it.
- GPT guidance: isolate the remaining cost before another optimization; the big
  1920x24452 layer's owner + repaint behavior is the thing to pin down.
