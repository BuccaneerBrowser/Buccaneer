# Failure Loop — Why Fixing One Thing Breaks Another

**Status:** Working document. Updated 2026-10-22.
**Purpose:** Explain the recurring pattern where a fix to one subsystem breaks another, so we stop re-discovering it each time.
**Companion docs:** `docs/COUPLING_MAP.md` (the map of the non-obvious couplings) and
`CHANGELOG.md` (every fix + what it broke + its GPU-budget impact).

---

## The core mechanism

The app runs on a **PowerVR GPU** (Fire TV, Android 7.1.2). This GPU is a **shared, fragile resource** with two known failure modes:

1. **Residual-state accumulation** — GPU state (render targets, blend modes, layer order) does not fully reset between video sessions. Load accumulates across site hops. Once past a threshold, frames drop (the "skipping" symptom).
2. **Compositing/surface failure** — the video decodes fine (frames climb in `media_health`) but the pixels never reach the screen (blank/black video). Recovers on page reload or app restart.

Both failure modes are **load-threshold** problems: the GPU tolerates a certain total load, and any subsystem that adds load can push it over the edge. The failure is **not** in the subsystem that added the load — it surfaces in an *unrelated* subsystem (usually video compositing). That is why the breakage feels non-intuitive.

## The loop

```
Fix subsystem A (adds/removes GPU load)
        │
        ▼
Total GPU load shifts
        │
        ▼
Subsystem B (near its own threshold) tips over
        │
        ▼
Symptom appears in B (blank video, crash, skip)
        │
        ▼
We "fix" B (adds more load)
        │
        ▼
Subsystem C tips over  →  repeat
```

Each fix is locally correct but globally destabilizing, because the fixes are **additive on a shared budget** and the budget is undocumented.

## Worked example: the auto-hide fix (2026-10-22)

**The fix:** `page_helpers.js` `installControlsAutoHide` — in CSS fullscreen, dispatch a synthetic `mousemove` on the video (bounded: 2 pokes at 1.5s) to kick-start the player's idle timer, which a TV remote never triggers.

**Intended effect:** controls auto-hide in fullscreen (video site/Nova/lazy-load poster site).

**What actually happened:**
- The auto-hide **did not work** — the player ignored the synthetic mousemove (controls stayed up). So the fix achieved nothing.
- The video **went blank in fullscreen** on video site.

**Why the poke broke the video (hypothesis, not confirmed from log):**
The synthetic `mousemove` is indistinguishable to the player's JS from a real pointer event. The player responds by re-running its "show controls" path — re-applying control-bar CSS, which forces a **GPU re-composite of the controls layer**. That re-composite, landing on top of residual GPU state, pushed the PowerVR over its compositing threshold → blank video.

The critical detail: **the poke added a GPU load (the re-composite) while producing no benefit (controls didn't hide).** A pure net-negative on the shared budget.

**Why the log can't confirm it directly:** `media_health` measures the `<video>` element's *decode* state (frames, readyState), not the *rendered* pixels. A compositing failure shows identical `media_health` to a healthy video (frames keep climbing). The blank moment is only visible to the user's eyes, not to the probe. So the causal link is inferred from timing (blank appeared only on the auto-hide-fix build) + the load-threshold model, not from a direct log signal.

**Resolution:** reverted the auto-hide change; re-pinned `page_helpers.js`; rebuilt and installed. Video recovered.

## Worked example: video site black video — NOT a GPU problem (2026-10-22)

This is the counter-example that keeps the loop honest: **not every black video is a
compositing/GPU failure.**

**Symptom:** only *some* videos from the site went black; a different video played fine.

**Diagnosis (from `blackvideo_diag.jsonl`):** the black videos' HLS segments failed at the
network level — `Failed to fetch` + `Hls error` console errors, `net::ERR_FAILED`
(code=-1) from the CDN domains (`calmsummit.top`, `sun.paleoak.top`, `laserlamp.top`). The
`<video>` never received data: `videoWidth=0`, `videoHeight=0`, `readyState=0`, `frames=0`.
A healthy video on the same site showed `readyState=4` with real dimensions.

**Why it is not the GPU:** the video data simply never arrived. The app was verified not to
be interfering (`shouldInterceptRequest` only logs manifests + blocks non-allowlisted TMDB
images; it does not touch video segments). The GPU was healthy — there was nothing to
composite.

**The discriminator (now a standing rule):** black video → check `readyState` and `frames`
*first*.
- `readyState=0`, no frames, network errors → **CDN/network failure** (not the app, not the GPU).
- frames climbing, `readyState=4`, but black → **compositing/surface failure** (GPU).

**Fix:** a bounded dead-video auto-retry (`installDeadVideoRetry` in `page_helpers.js`): if
a video stays `0×0`/`readyState=0` for 8 s inside a player container, reload once, with a
60 s per-URL cooldown. It is **armed but unexercised** (the test video recovered on its
own; the CDN was flaky, not dead).

**Lesson:** before blaming the shared GPU budget, rule out the non-GPU causes. The failure
loop tempts you to assume "black video = GPU," but a network failure looks identical to the
user. The `readyState`/`frames` check is cheap and decisive.

## Worked example: the GPU-accumulation fix — a fix that *removes* load (2026-10-22)

The auto-hide example shows a load-*adding* fix tipping the budget. This is the safe
pattern: a fix that *removes* accumulated load.

**Problem:** residual video GPU state accumulated across video-site hops; after a long
streaming session the PowerVR tipped over (frame drops / "skipping").

**Fix:** `videoActive` (set on real playback) + a render reset in `handoffNavigation`
(`pauseTimers → invalidate → resumeTimers → invalidate`, 150 ms) that fires when navigating
*away* from a page that had playback. It clears residual state at a safe boundary (the
navigation) before the next site's video composites.

**Why it is safe:** it *removes* load rather than adding it, so it cannot tip another
subsystem. It runs after media teardown, before the next page loads. Verified: 7
`video_render_reset` in `test1_diag`, frame drops 0–3.5%, no buildup.

**Rule:** when the failure is accumulation, the fix is to *clear* the accumulation at a
boundary — not to add more work.

## The lesson (the rule to apply to every future fix)

1. **Treat the PowerVR GPU as a shared budget.** Every change that adds a GPU operation (re-composite, layer change, texture upload, surface resize) spends from that budget, even if it "shouldn't matter."
2. **A fix that adds load to fix a non-GPU problem is suspect.** If the fix's mechanism is a GPU operation (re-render, re-composite, surface change) but the problem it solves is not GPU-related, it is likely to tip the budget. Prefer fixes that do not add GPU work.
3. **Verify the fix actually works before shipping it.** The auto-hide fix shipped a GPU load that did not even achieve its goal. A fix that doesn't work but adds load is the worst kind.
4. **Blank video ≠ decode problem.** When the video is blank but `media_health` shows frames climbing, it is a compositing/surface failure (GPU), not a stream/decode problem. Do not chase the stream.
5. **Document the load, not just the fix.** Each changelog entry should note whether the fix *adds, removes, or is neutral* on the GPU budget.

## Open questions / open items

- **Rendered-output signal:** can we add a log signal that captures the *rendered* output
  (not just decode state), so a compositing failure is visible in the log? (e.g., a periodic
  pixel-readback or a "did the surface present" counter.) Still open.
- **Auto-hide without a synthetic pointer event:** the synthetic-`mousemove` approach is a
  dead end (the player ignores it and it adds GPU load). Needs a zero-GPU-cost method
  (directly toggling the player's idle state via JS, or a CSS-only approach that does not
  force a re-composite) — or defer. Still open.
- **Dead-video retry unexercised:** the retry is installed and logging (`console.warn`) but
  has not fired on a real black video. The next real video site black video is the true test —
  confirm a `[dead-video-retry] reloading:` line appears and the video then plays.
- **poster-grid site fullscreen centering:** the `Gravity.CENTER` fix is kept; the residual
  top-alignment on some poster-grid site pages is a page-layout quirk (video top-aligned *inside* the
  page/WebView, which gravity cannot reach). **Leave-it** — a CSS/JS
  reposition carries the same regression risk that broke auto-hide.
- **GPU budget:** what is the actual budget on this device? (We have symptoms, not a
  number.) Does the residual-state threshold drift with temperature / memory pressure over
  a long session?
