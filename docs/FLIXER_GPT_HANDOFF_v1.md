# Flixer PowerVR Crash — Handoff for GPT (please help)

> Written 2026-10-25 ~16:40 MST for a stronger model to read and help debug.
> Everything below is **measured data**, not theory. Every attempt is listed with
> what/why/result. I have a live device and full adb + CDP access and can run any
> test you ask for or gather any data you want — just tell me exactly what to run.
>
> **Hard rule that outranks everything:** the Nova fix (commit `25737ac`, in
> `page_helpers.js`) must never be erased. It is confirmed working on the device.

---

## 0. TL;DR for GPT

- **App:** `com.buccaneer.browser` (an Android TV WebView browser) on a Fire TV.
- **Bug:** the flixer.gd **title-detail page** reliably crashes the browser. The
  **main page and the video player work fine.** Only the detail page dies.
- **Crash:** PowerVR GPU driver null-pointer. Thread `Chrome_InProcGp` (Chromium's
  in-process **GPU** thread), `SIGSEGV fault addr 0x0`, in
  `KEGLReleaseRenderTarget+77` (`libIMGegl.so`) called from `glDrawArrays`
  (`libGLESv2_POWERVR_ROGUE.so`). The GPU **releases a render target mid-draw** and
  dereferences freed memory.
- **Symptom the user sees:** page slows, the play button flickers, then the screen
  goes **black** (GPU compositing fails) and/or the process dies and auto-reloads.
- **Root-cause direction (data-backed):** the detail page puts **~3.6× more
  compositor layers + textures** on the GPU than the working main page, **plus a
  looping YouTube trailer video** the main page doesn't have. That pushes the
  PowerVR GPU past its memory/composite budget → it evicts render targets → an
  in-flight draw hits a freed target → crash.
- **What I tried this session (all failed / made it worse):** (1) virtualize
  off-screen poster cards with `display:none`, (2) downscale the 4K hero backdrop,
  (3) remove the looping YouTube trailer iframe. Details + why each failed in §6.
- **What I need from you:** a fix that cuts the GPU composite/texture load **without
  causing layout reflow** (reflow makes it worse — see §6.1), or a WebView-level
  mitigation. Tell me what to test and I'll run it and report the data.

---

## 1. The crash — exact signature (from `logcat -b crash`)

Two captured crashes, same signature:

**Crash 1 (15:47:58, pid 5093):**
```
#00  pc 00013ec6  /vendor/lib/libIMGegl.so (KEGLReleaseRenderTarget+77)
#01  pc 0003db53  /vendor/lib/egl/libGLESv2_POWERVR_ROGUE.so
#02  pc 0003e66d  /vendor/lib/egl/libGLESv2_POWERVR_ROGUE.so
#03  pc 000229b9  /vendor/lib/egl/libGLESv2_POWERVR_ROGUE.so
#04  pc 0007233f  /vendor/lib/egl/libGLESv2_POWERVR_ROGUE.so
#05  pc 000724f7  /vendor/lib/egl/libGLESv2_POWERVR_ROGUE.so
#06  pc 00086d71  /vendor/lib/egl/libGLESv2_POWERVR_ROGUE.so
#07  pc 0001b8ad  /vendor/lib/egl/libGLESv2_POWERVR_ROGUE.so (glDrawArrays+636)
#08-35  /system/lib/libwebviewextchromium.so
#36  /system/lib/libc.so (__pthread_start+22)
#37  /system/lib/libc.so (__start_thread+6)
```

**Crash 2 (16:01:04, pid 6773):**
```
Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 7153 (Chrome_InProcGp)
pid: 6773, tid: 7153, oom: 0, name: Chrome_InProcGp  >>> com.buccaneer.browser <<<
```

**Read of the signature:**
- Crashing thread = `Chrome_InProcGp` = Chromium's **in-process GPU thread** (the
  compositor), NOT the main thread, NOT a renderer.
- `fault addr 0x0` = null-pointer dereference.
- The null deref is inside `KEGLReleaseRenderTarget` (PowerVR EGL "release render
  target"), reached **from** `glDrawArrays` (a draw call).
- Interpretation: while the GPU thread is drawing (`glDrawArrays`), the PowerVR
  driver releases/evicts a render target, and the in-flight draw touches the freed
  target → SIGSEGV. This is a **use-after-free / render-target-eviction race in the
  PowerVR driver**, triggered by GPU load.
- `oom: 0` — the Android OOM killer is not involved; this is a GPU-side failure.

**GPU memory churn (from `logcat`, `IMGMemtrackHAL` = Imagination/PowerVR memtrack HAL):**
```
W IMGMemtrackHAL: hal_get_memory: memtrack cache rebuild was required   (fires repeatedly, clustered around page load/degrade)
E IMGMemtrackHAL: DbgFsParseFile: fopen of /d/pvr/driver_stats failed (Permission denied)
```
The repeated "memtrack cache rebuild" indicates GPU buffers are being allocated and
freed rapidly (churn) — consistent with the driver evicting render targets under
pressure. (`/d/pvr/driver_stats` is permission-denied, so I can't read raw GPU
memory stats as the app user.)

**Silent death:** the process dies *before* `onRenderProcessGone` fires, so
in-app crash-context diagnostics never capture it. The last JSONL diagnostic event
is always 3–6 s before death.

**Reproducibility:** 100% on the detail page. User: "failure rate is 100%." The
detail page also **degrades before it dies** (play-button flicker → black screen)
even when the process survives — i.e. the GPU compositing fails (black) independent
of the process crash.

---

## 2. Device & environment

- **Device:** Fire TV, model `AFTMM` (build fingerprint
  `Amazon/mantis/mantis:6.0/NS6711/5908N:user/amz-p,release-keys`), Android 7.1.2
  (API 25), ARM (32-bit). GPU: **PowerVR Rogue** (`libGLESv2_POWERVR_ROGUE.so`,
  `libIMGegl.so`).
- **adb:** `10.4.20.137:5555`. Package `com.buccaneer.browser`,
  `versionName=2.3.3`, `versionCode=26`.
- **WebView:** system WebView, Chromium ~118 era. **`content-visibility` is parsed
  but NOT enforced** (verified: set `content-visibility:auto` +
  `contain-intrinsic-size` on an off-screen element; it still laid out with a full
  rect). `Page.getLayerTree` CDP method is **not available** in this WebView.
- **CDP:** debugging is ON (`setWebContentsDebuggingEnabled(true)`). Connect via
  `adb forward tcp:9222 localabstract:webview_devtools_remote_<PID>` then
  `http://127.0.0.1:9222/json`. `Performance.getMetrics` works (returns
  LayoutObjects/Nodes/Frames/ProcessTime, **no layer count**).
- **Build env:** `source /home/user/android-env/env.sh`;
  `gradle assembleDebug` (gradle 8.10.2). Install:
  `adb -s 10.4.20.137:5555 install -r app/build/outputs/apk/debug/app-debug.apk`.
  Regression gate (must PASS before build): `python3 tests/check-protected-fixes.py`.
- **Dev tree:** `/home/user/BuccaneerClaude-2.0`, checked out at **`25737ac`**
  (2.3.3 + Nova fix). **Do NOT build from `5ec354f`** (plain 2.3.3, pre-Nova-fix).
- **Diagnostic log:** `/data/data/com.buccaneer.browser/files/diagnostic_session.jsonl`
  (pull with `adb ... shell "run-as com.buccaneer.browser cat ..."`). JS bridge:
  `window.Buccaneer.diagnosticEvent(event, jsonString)`.

---

## 3. The two pages — the key comparison (measured via CDP)

This is the crux. The **main page works**; the **detail page crashes**. I measured
both with identical CDP probes:

| Metric | Main `flixer.gd/` (WORKS) | Detail `flixer.gd/shows?tv=link-click&id=123542` (CRASHES) |
|---|---|---|
| `<img>` count | 182 | **660** (3.6×) |
| `overflow:hidden` clipping containers (compositor-layer proxy) | 190 | **699** (3.7×) |
| Horizontal scroll rows (overflow-x auto/scroll, content wider than container) | 9 | **33** (3.7×) |
| Page scroll height | 4,136 px | **12,376 px** (3×) |
| `<video>` | 0 | **1** (looping YouTube trailer) |
| `<iframe>` | 2 (non-video) | 4 (2× YouTube embed + 2× about:blank) |
| Largest image (hero backdrop) | **3840×2160 (4K)** | **3840×2160 (4K)** — *same* |
| Viewport | 960×540 | 960×540 |

**Conclusions from the comparison:**
1. **The 4K hero is NOT the differentiator** — both pages carry the identical
   3840×2160 hero, and the main page works. So the hero alone is not the trigger.
2. **The detail page has ~3.6× more compositor layers + textures** (699 vs 190
   clipping containers; 660 vs 182 images) and is 3× taller.
3. **The detail page has a looping YouTube trailer video; the main page has zero
   video.** A muted autoplaying looping video is a continuous per-frame GPU
   decode+composite load the main page never carries.
4. Therefore the detail page's total GPU load (textures + compositor layers + live
   video) is ~3.6× the main page's, plus a sustained video load → past the PowerVR
   budget → render-target eviction → crash. The main page is inside the budget.

**Trailer URL (detail page):**
`https://www.youtube.com/embed/HxoR_7jVBCM?autoplay=1&controls=0&mute=1&loop=1&playlist=...`
(`autoplay=1`, `mute=1`, `loop=1` — a muted, looping, background video.)

---

## 4. Detail-page DOM structure (measured)

- The poster grid is **horizontal scroll rows**, not a vertical grid (the site's DOM
  changed since the last session's notes).
- Each row: a `div` with `overflow-x:auto/scroll`; its single child (the content
  strip) is **~3,680 px wide** inside a **~928 px** container → ~20 cards per row,
  only ~5 visible at once.
- Each card:
  ```
  div.flex-shrink-0.relative.cursor-pointer   (176×230, overflow:visible)
    div.w-full.h-full.overflow-hidden.rounded-md   (clipping wrapper, overflow:hidden)
      img   (poster)
  ```
- There are **660 per-poster clipping wrappers** (`overflow-hidden rounded-md`) —
  these are the dominant clipping layers (the other ~39 of the 699 are row/section
  wrappers + the hero).
- The hero: a `[class*="75vh"]/ [class*="100vh"]` section containing the 4K TMDB
  backdrop `<img>` (`image.tmdb.org/t/p/original/...`, naturalWidth 3840) + a
  metadata strip (rating/status/date/seasons) + the title/overview.
- The trailer is a separate cross-origin YouTube `<iframe>` (not inside the hero img).

---

## 5. All data gathered

### 5.1 `compositor_proxy` (previous session's instrument, in `flixer_image_guard.js`)
Logs every ≥2 s: `realImg` (TMDB posters with live src = decoded GPU textures),
`emptyImg` (virtualized, src stripped), `fixedImgs`, `clippedImgs` (posters whose
card container className contains `overflow-hidden`).
- **Crash A** (measurement-only build, 181-poster page): session 21:56:10→22:00:28
  (~4 min 18 s). Steady state: `realImg:7, emptyImg:174, fixedImgs:0,
  clippedImgs:180`. Workload **constant** (not a leak). Died 2 s after last sample.
- **Crash B** (measurement + `visibility:hidden` build, 616-poster page): session
  22:02:53→22:03:29 (36 s). `realImg:7→25, emptyImg:593→610, clippedImgs:616`. Died
  right after a DPAD_CENTER tap.
- **Finding: more card containers = faster crash.** 180 `clippedImgs` → 4 min;
  616 `clippedImgs` → 36 s. Clear inverse correlation between clipping-container
  count and time-to-crash.

### 5.2 Stock 2.3.3 baseline (`/tmp/bucc_diag7.jsonl`, 10,752 lines)
- **21 sessions, 20/21 crashed.** Always 3–6 s after the last diagnostic event
  (silent native death). Crashes span `img 203–653`, `allowlist 8–24` — neither
  count alone discriminates.

### 5.3 CDP `Performance.getMetrics` (detail page, this session)
`LayoutObjects: 5728, Nodes: 10065, Frames: 9, JSEventListeners: 3903,
ProcessTime: ~270 s`. No layer-count metric exposed.

### 5.4 GPU memory
`IMGMemtrackHAL: memtrack cache rebuild was required` fires repeatedly around
load/degrade (see §1). `/d/pvr/driver_stats` is permission-denied to the app user,
so raw GPU memory numbers are not directly readable.

---

## 6. EVERYTHING tried (what / why / result)

### 6.1 THIS session (all three deployed together, then reverted by user)
1. **Card virtualization** — `display:none` on poster cards outside a horizontal
   live window (`[scrollLeft-600, scrollLeft+clientWidth+600]`) in each scroll row;
   restored on scroll. Injected at document-start via a new `flixer_card_guard.js`.
   - **Result: FAILED, made it WORSE.** It did hide 394/660 cards, but the user
     reported: **the play button disappeared, the episode list disappeared**, and a
     random video played in the background. The page rendered mostly black with only
     a metadata strip. `display:none` causes **layout reflow**, which on this
     PowerVR path increases GPU churn and broke the visible UI. **Reverted.**
   - **Lesson: any change that triggers reflow (display:none, removing large
     elements) makes this crash worse, not better.**
2. **Hero downscale** — rewrite the hero backdrop src from `/t/p/original/` to
   `/t/p/w1280/` (3840×2160 → 1280×720, ~9× fewer pixels).
   - **Result: it worked mechanically** (hero became 1280×720, verified), **but the
     page still went black.** Not the differentiator (main page has the same 4K
     hero and works). Kept as a possible secondary optimization, but it did not fix
     the crash. **Reverted** (part of the batch the user pulled).
3. **Trailer block** — remove the looping YouTube `<iframe>` from the DOM.
   - **Result: it worked mechanically** (0 YouTube iframes remained), **but the page
     was worse** and "a random video played in the background." Also, **the trailer
     block was already tried in a previous session** (see 6.2) — so this was going
     in circles. **Reverted.**

> **Net: all three of this session's changes were reverted.** The source tree is
> back to clean `25737ac`. (The device still has the last built APK installed — see
> §8 — but no new install was done per the user's instruction.)

### 6.2 Previous session (all reverted by user — "made the entire system slow and unstable")
- **`compositor_proxy` measurement** (in `flixer_image_guard.js`) — WORKS, produced
  the card-count→crash correlation. **Keep this instrument.** (Currently not in the
  installed build; re-add per §7 if resuming measurement.)
- **`visibility:hidden` on virtualized images** — FAILED, made it worse (instant
  freeze then crash). Hiding the image does not remove the card's clipping layer.
- **Trailer block + YouTube iframe DOM removal** (`popup_guard.js`) — built+installed,
  reverted. Data showed the iframe was **not** the trigger anyway (crash 19 s after
  removal).
- **Bug #1–4, Item 5, Item 9** (roadmap items) — built+installed, reverted.
- **Diagnostics:** `crash_context` (in `onRenderProcessGone` — never fires because
  of silent death), `flixer_load_context`, `gpu_state` (5 s periodic),
  `processStartElapsed` — added, reverted.
- **`MAX_STRIP_PER_SYNC=10`** rate-limit + `image_guard_sync` logging — part of the
  "slow/unstable" batch, reverted.

---

## 7. Ruled out (each with the data that ruled it out)

- **YouTube iframe / trailer as the trigger** — removed it (`trailer_iframe_removed`
  fired, zero youtube frames); crash still happened 19 s later. (It is still a
  *contributing load*, just not the sole trigger.)
- **Memory pressure / OOM (Android)** — `oom:0`; workload constant across minutes
  (not a leak).
- **`content-visibility:auto`** — parsed but **not enforced** in this WebView fork
  (off-screen child still laid out with full rect). Not a viable fix here.
- **Software rendering** — explicitly NOT a fix; user rejected it; docs reject it.
  (`MainActivity.kt:631` is OFF — leave OFF.)
- **NVIDIA driver** — this is a PowerVR GPU, not NVIDIA.
- **`crash_context` diagnostic** — useless (process dies before
  `onRenderProcessGone`).

---

## 8. Current state

- **Source tree:** clean at `25737ac` (2.3.3 + Nova fix). This session's three
  changes are **reverted** (`MainActivity.kt` restored via git;
  `flixer_card_guard.js` deleted). Only untracked docs remain
  (`FLIXER_CRASH_HANDOFF.md`, `ROADMAP_TODO.md`, `review/popup-js-inventory.md`).
- **Device:** now has the **clean baseline APK** (built from `25737ac`, installed
  2026-10-25). Launching/restarting the app is allowed whenever needed (the old
  "user launches it" constraint was revoked 2026-10-25).
- **Nova fix:** commit `25737ac`, in `page_helpers.js`, hash
  `6fbc9faa3cbb3b3c4c824bd4246f6d2e57bcdb85490541e287a38408e8b395b0`. **DO NOT
  ERASE.**
- **Git map:**
  ```
  5ec354f  2.3.3 (NO Nova fix — do NOT build from here)
  25737ac  + Nova fix  <- SAFE BASELINE (current)
  6f61e06  Flixer crash loop (popup_guard)  <- REVERTED by user
  e174717  Flixer crash loop (scattered UI) <- REVERTED by user
  ```

---

## 9. What I need from you (GPT) — please answer / direct

1. **GPU budget:** What is the realistic GPU texture/composite memory budget for a
   PowerVR Rogue on Fire TV (Android 7.1.2, 32-bit)? Roughly how many ~176×230
   poster textures + a 4K hero + a live 720p video can it hold before it starts
   evicting render targets?
2. **The driver bug:** Is `KEGLReleaseRenderTarget` null-deref during `glDrawArrays`
   a known PowerVR use-after-free triggered by render-target eviction under load?
   Are there known mitigations (driver flags, WebView settings, compositor
   limits)? What did "other versions" change that fixed it (the user says it was
   fixed before)?
3. **The core constraint:** every page-level reduction I tried that caused **reflow**
   (`display:none`, removing the iframe) made it **worse**. What is a way to reduce
   the GPU composite/texture load **without triggering layout reflow**? (e.g. is
   there a compositor-level cap, a way to limit live layers, a WebView flag, or a
   non-reflowing virtualization technique that actually works on Chromium 118 /
   API 25?)
4. **WebView-level vs page-level:** Should the fix target the WebView/compositor
   (e.g. limit number of composited layers, disable a feature, cap texture size,
   force a different rasterization path) rather than the page DOM? What specific
   WebView settings / flags / `WebSettings` / `WebViewCompat` calls are worth
   testing on API 25?
5. **The video:** Is the looping muted YouTube trailer a significant enough
   contributor that neutralizing it *without reflow* (e.g. blocking the network
   request so the iframe stays but loads nothing, vs removing the iframe) is worth
   trying? Which is less likely to cause reflow/churn?
6. **Priorities:** Given the data, what is the single highest-probability fix to
   try first, and what is the exact experiment (code + measurement) to validate it?

---

## 10. Tests I can run for you (say which)

I have full adb + CDP + logcat access and can report raw data. Available probes:
- **CDP page metrics:** img count, `overflow:hidden` container count, horizontal-row
  count + per-row card count/width, page height, iframe/video inventory, hero image
  natural size, `Performance.getMetrics` (LayoutObjects/Nodes/Frames/ProcessTime).
- **Frame rate:** `requestAnimationFrame` count over 1 s (compositor health).
- **GPU logs:** `logcat` filtered for `IMGMemtrackHAL`, `PVRSRV`, `EGL`, `GL_`,
  `RenderThread`, `hwui`, `gralloc`, `dequeue`, `release`, `cache`, `oom`.
- **Crash backtraces:** `logcat -b crash` (full register + backtrace dumps).
- **Diagnostic JSONL:** pull `diagnostic_session.jsonl`, analyze pre-crash window.
- **A/B any JS injection** at document-start (I can build+install+measure, one
  change at a time, and revert if it regresses).
- **Network-level block** of the YouTube request (WebView `shouldInterceptRequest`)
  to test "iframe present but empty" vs "iframe removed."
- **Texture-size experiments:** force hero/posters to smaller TMDB sizes and measure
  time-to-crash.

Please tell me the **exact** experiment(s) to run and the **exact** data to capture,
and I'll execute and report back.

---

## 11. Hard constraints (violating = user gets angry)

1. **No software rendering.** Never suggest it.
2. **No full-DOM walks on flixer** (`querySelectorAll('*')`-style). Reuse the
   existing img walk; className string checks only. (Note: this session's
   `findRows()` used `querySelectorAll('div')`+`getComputedStyle` — that is a
   full-DOM walk and should be avoided in any fix.)
3. **Read the FULL docs before shipping:** `docs/PROGRAM_REVIEW.md` (~80k) and
   `docs/review/part3-guards.md` (~51k).
4. **Launching/restarting the app is allowed whenever needed** (old "user launches it" constraint revoked 2026-10-25).
5. **Every suggestion must cite specific data.** No speculation.
6. **Do NOT erase the Nova fix** (`25737ac`, `page_helpers.js`).
7. **Do NOT build from `5ec354f`** (pre-Nova-fix).
8. **One change at a time, test after each.**
9. **Fix a subsystem as one clean rule set**, never patch-on-patch gates.
10. **Be direct; act on the obvious default; no filler.**

---

## 12. Key files

- `app/src/main/assets/flixer_image_guard.js` — TMDB image firewall (MAX=24 decoded
  cap; document-start; sync immediate + 250ms/1s/3s + 2s). Home of `compositor_proxy`.
- `app/src/main/assets/page_helpers.js` — **Nova fix** (`25737ac`). DO NOT ERASE.
- `app/src/main/assets/popup_guard.js` — popup guard (`killCatchers`, `playerRoot`,
  `isPlayerUI`).
- `app/src/main/assets/flixer_poster_guard.js` — poster hover-preview guard.
- `app/src/main/java/com/buccaneer/browser/MainActivity.kt` — `:799` debugging on;
  `:1344` `diagnosticEvent`; `:631` `softwareRendering` (OFF); `:468`
  `isImageGuardedHost`; `~1109–1127` `flixer_render_reset`.
- `docs/PROGRAM_REVIEW.md`, `docs/review/part3-guards.md` — read in full.
- `tests/check-protected-fixes.py` + `tests/protected-fixes.json` — SHA-256 gate.

## 13. Data files (on this machine, `/tmp/`)

- `bucc_diag7.jsonl` (10,752) — stock 2.3.3, 21 sessions, 20/21 crashed. **Primary baseline.**
- `bucc_diag8.jsonl` — `compositor_proxy` measurement; Crash A (181 posters, 4 min).
- `bucc_diag9.jsonl` — measurement + `visibility:hidden`; Crash B (616 posters, 36 s).
- `bucc_diag10.jsonl`, `bucc_diag11.jsonl` — this session (crash windows + card-guard events).
- Older: `bucc_diag.jsonl` (7,478), `bucc_diag2.jsonl`, `bucc_diag3.jsonl` (9,330),
  `bucc_diag4.jsonl` (10,014), `bucc_diag5.jsonl` (10,461).
