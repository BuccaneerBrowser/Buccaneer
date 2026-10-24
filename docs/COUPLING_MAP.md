# Coupling Map — PowerVR GPU Load

**Status:** Working document. Updated 2026-10-22.
**Purpose:** Make the *unobvious* connections explicit — the ones where fixing one subsystem
breaks another. The failure loop (see `docs/FAILURE_LOOP.md`) exists because these
couplings are undocumented. This map is the map of them.

---

## 1. The shared resource

The app runs on a **PowerVR GPU** (Fire TV, Android 7.1.2). It is a **single shared,
fragile resource** with a finite, undocumented load budget. Every subsystem that renders,
composites, uploads a texture, resizes a surface, or changes layer order **spends from the
same budget**. There is no per-subsystem isolation: the GPU does not know which subsystem
"owns" a unit of load.

Two known failure modes, both **load-threshold** problems:

| Mode | Symptom | What actually fails |
|------|---------|---------------------|
| **Residual-state accumulation** | Frame drops / "skipping" after a long session | GPU state (render targets, blend modes, layer order) does not fully reset between video sessions; load accumulates across site hops until past a threshold |
| **Compositing / surface failure** | Blank/black video (audio fine) | Video decodes fine (frames climb in `media_health`) but the pixels never reach the screen; recovers on reload/restart |

The critical property: **the failure is not in the subsystem that added the load — it
surfaces in an *unrelated* subsystem** (usually video compositing). That is why the
breakage feels non-intuitive and why it keeps getting re-discovered.

## 2. The budget model

```
        total GPU load = Σ (load spent by every active subsystem)
                          │
        ┌─────────────────┴──────────────────┐
        │  below threshold: everything works  │
        │  at/over threshold: SOME subsystem  │
        │  tips over (usually video composite)│
        └────────────────────────────────────┘
```

- The threshold is **not a fixed number** — it drifts with residual state. A session that
  left residual video state has a *lower* effective threshold than a fresh one.
- Any subsystem that **adds** load can be the straw that breaks it, even if that subsystem
  is "unrelated" to the symptom.
- A subsystem that **removes** load (or prevents load from being added) is always safe and
  usually helpful.

## 3. Subsystem → load map

Each row: the subsystem, what GPU load it spends, and which failure it can *trigger* (not
where the symptom appears).

| Subsystem | GPU load it spends | Can trigger | Notes |
|-----------|-------------------|-------------|-------|
| **Video decode + composite** | High, continuous, per-frame | Compositing failure (black video) when residual state is high | The usual *victim*, not the cause |
| **Residual video state** (not cleared on nav) | Accumulates across site hops | Frame drops (skipping) after long sessions | Cleared by the `videoActive` render reset |
| **Player controls re-composite** (e.g. synthetic `mousemove` → show-controls path) | A re-composite of the controls layer | Compositing failure (black video) | The auto-hide regression: added load, no benefit |
| **Native fullscreen enter/exit** | Surface resize + layer change | Compositing failure | poster-grid site auto-fullscreen suppression removes this |
| **Off-screen poster decode/composite** (TMDB grid) | Texture upload + composite for every image | Crash / compositing failure on poster-grid site-after-video | Cleared by TMDB image virtualization |
| **1×1 image firewall** | Reduces texture load but the request/decode still happens | (incomplete fix) | Why virtualization was needed |
| **Popup/overlay DOM churn** | Layout + composite per kill | Minor; can add up on ad-heavy pages | `popup_guard.js` |
| **Continuous JS polling / instrumentation** | CPU mostly, but any forced re-render adds GPU load | Indirect; heavy instrumentation crashed poster-grid site | Why the poster-grid site fs-guard is minimal (no timers) |
| **Diagnostic logging** | Negligible GPU; but log rotation pressure | Evidence loss (not a GPU failure) | Why `net_segment` was coalesced |

## 4. The non-obvious coupling edges

These are the connections that are *not* intuitive — the ones that cause the loop:

1. **A pointer event can kill video.** A synthetic `mousemove` (auto-hide) is
   indistinguishable to the player's JS from a real pointer. The player re-runs its
   show-controls path → GPU re-composite → black video. *The input subsystem and the video
   subsystem are coupled through the GPU budget, not through any shared code.*

2. **A poster grid can crash a video site's aftermath.** Loading poster-grid site (a poster grid)
   *after* a video site crashes, because the video left residual GPU state and the grid's
   ~182-image decode/composite pushed it over the edge. *Two unrelated pages are coupled
   through residual state that outlives the navigation.*

3. **A "diagnostic" can hide the real problem.** `media_health` measures the `<video>`
   element's *decode* state (frames, readyState), not the *rendered* pixels. A compositing
   failure shows **identical** `media_health` to a healthy video (frames keep climbing). So
   the log cannot directly confirm a compositing failure — it must be inferred from timing
   + the load model. *The measurement subsystem is coupled to the failure mode in a way
   that makes the failure invisible to the probe.*

4. **A network failure can masquerade as a GPU failure.** A video from the site that goes black
   because its HLS segments fail (`net::ERR_FAILED`, `0×0`, `readyState=0`) looks
   superficially like a compositing failure (black video). The discriminator: a network
   failure has `readyState=0` and no frames; a compositing failure has frames climbing.
   *Diagnosis must check `readyState`/`frames` before blaming the GPU.*

5. **A fix that adds load to solve a non-GPU problem is suspect.** If the fix's mechanism
   is a GPU operation (re-render, re-composite, surface change) but the problem it solves
   is not GPU-related, it is likely to tip the budget. (This is the auto-hide lesson.)

## 5. Smoking-gun comments in the code

These comments mark where the coupling was discovered. Keep them accurate:

- `flixer_image_guard.js` (~L89) — "the 1×1 firewall reduced GPU texture load but the
  request/decode/composite for all 182 images still happened. With prior video GPU state
  resident, that residual load pushed the PowerVR driver over the edge."
- `MainActivity.kt` (~L395) — "poster-grid site crashes (KEGLReleaseRenderTarget) when loaded after
  a video site (video site/lazy-load poster site) — residual video GPU state tips the PowerVR driver over
  the edge."
- `MainActivity.kt` (~L1678) — "Script completion/timeout is NOT proof of native resource
  release." (the basis for the `videoActive` render reset)
- `page_helpers.js` `installFlixerFsGuard` — "No timers, no continuous polling — to avoid
  the PowerVR GPU crash that heavier instrumentation triggers on poster-grid site."

## 6. How to use this map when making a fix

1. **Identify the load delta.** Does the fix add, remove, or is it neutral on the GPU
   budget? (Record it in the CHANGELOG entry.)
2. **If it adds load:** ask "is the problem it solves GPU-related?" If not, prefer a
   fix that does not add GPU work.
3. **Check the victim, not just the fix.** After a load-adding fix, regression-test the
   *video composite* path (fullscreen playback) even if the fix is in an unrelated
   subsystem (input, popups, posters).
4. **Discriminate the failure before blaming the GPU.** Black video: check `readyState`
   and `frames` first. `readyState=0` + no frames = network (CDN) failure, not GPU.
   Frames climbing + black = compositing (GPU) failure.
5. **Clear residual state when you can.** The `videoActive` render reset is the model:
   clear accumulated load at a safe boundary (navigation) so it cannot tip the next
   subsystem.

## 7. Open questions

- What is the actual GPU budget on this device? (We have symptoms, not a number.)
- Can we add a log signal that captures the *rendered* output (not just decode state), so
  a compositing failure is visible in the log? (e.g., a periodic pixel-readback or a
  "did the surface present" counter.)
- Is the residual-state threshold stable, or does it drift with device temperature /
  memory pressure over a long session?
