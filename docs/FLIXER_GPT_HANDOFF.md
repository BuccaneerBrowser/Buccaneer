# Flixer PowerVR Crash — FULL DATA HANDOFF (for GPT)

> v2, 2026-10-25 ~17:00 MST. Rewritten after v1 was rejected as too thin.
> This version contains the **raw** data (full crash dumps with registers, the
> 20-process crash table, memory state, GPU churn logs) and **sharp,
> hypothesis-driven questions** — not generic ones. Everything is measured, not
> theorized. I have a live device + full adb + CDP + logcat and will run any test
> you specify and report raw output back.
>
> **Hard rule that outranks everything:** the Nova fix (commit `25737ac`, in
> `page_helpers.js`) must never be erased. It is confirmed working on the device.

---

## 0. The 3 questions that matter most (read these first)

1. **The reflow paradox (the crux).** Every page-level reduction I tried
   (`display:none` virtualization, `visibility:hidden`, removing the video iframe)
   made the crash happen **faster** or broke the UI — the *opposite* of the intent.
   My hypothesis: hiding/removing elements invalidates the compositor layer tree →
   Chromium fires a **re-rasterization burst** → that transient GPU spike, stacked
   on the looping video's per-frame compositing, tips the already-saturated PowerVR
   over the edge. **Is that mechanism right? If so, what is the way to cut the
   STEADY-STATE compositor layer count on Chromium 118 / API 25 WITHOUT triggering
   a re-rasterization spike?** (A `content-visibility`-like skip that actually works
   here, a compositor-level layer cap, a flag, or something else.)

2. **The exact driver bug.** The GPU-thread crash is `KEGLReleaseRenderTarget+77`
   (`libIMGegl.so`) reached from `glBindFramebuffer` (fault `0x4`) or `glDrawArrays`
   (fault `0x0`) on the `Chrome_InProcGp` thread. That is the PowerVR driver
   releasing a render target while a framebuffer op is in flight. **Is this a known
   Imagination PowerVR Rogue use-after-free / render-target-eviction bug?** Is there
   a hard driver limit on live render targets / FBOs that Chromium's compositor
   exceeds, and a known mitigation (driver flag, Chromium feature flag, WebView
   setting)?

3. **WebView-level vs page-level.** Given Amazon's Chromium 118 WebView + PowerVR
   Rogue, **which WebView-level knob is most likely to actually help** — and what's
   the exact experiment? Candidates I want ranked: a compositor layer cap, a
   per-WebView GL/rasterization option (NOT whole-app software rendering), a
   `--disable-features`/`--enable-features` Chromium flag affecting layer management
   or GPU memory, `setLayerType`, texture-quality cap, or neutralizing the looping
   video at the network layer (iframe stays, loads nothing) instead of removing it.

The full question list (8 questions) is in §11.

---

## 1. Device & environment (raw)

- **Device:** Fire TV, model `AFTMM`. Build fingerprint
  `Amazon/mantis/mantis:6.0/NS6711/5908N:user/amz-p,release-keys`.
  Android **7.1.2 (API 25)**, ABI **arm** (32-bit). `ro.hardware` = `mt8695`.
- **GPU:** **PowerVR Rogue** — from the crash backtrace
  (`/vendor/lib/egl/libGLESv2_POWERVR_ROGUE.so`, `/vendor/lib/libIMGegl.so`,
  `KEGLReleaseRenderTarget`). Not NVIDIA.
- **WebView:** `com.amazon.webview.chromium`,
  **versionName `118.amazon-webview-v118-5993-tv.5993.155.160`** (Chromium **118**,
  Amazon TV build). (`com.amazon.webview` is the wrapper package.)
- **App:** `com.buccaneer.browser`, versionName **2.3.3**, versionCode **26**,
  minSdk 22, targetSdk 33, compileSdk 34.
- **adb:** `10.4.20.137:5555`. CDP on `tcp:9222` →
  `localabstract:webview_devtools_remote_<PID>`.
- **CDP capabilities on this WebView:** `Runtime.evaluate`, `Performance.getMetrics`
  (LayoutObjects/Nodes/Frames/ProcessTime — **no layer count**).
  `Page.getLayerTree` = **method not found** (unavailable).
  `content-visibility` = **parsed but NOT enforced** (verified: set
  `content-visibility:auto` + `contain-intrinsic-size` on an off-screen element; it
  still laid out with a full rect).
- **Build:** `source /home/user/android-env/env.sh && gradle assembleDebug`
  (gradle 8.10.2; there is no `./gradlew`). Install:
  `adb -s 10.4.20.137:5555 install -r app/build/outputs/apk/debug/app-debug.apk`.
  Gate before build: `python3 tests/check-protected-fixes.py` (SHA-256 gate).
- **Tree:** `/home/user/BuccaneerClaude-2.0` at **`25737ac`** (2.3.3 + Nova fix).
  **Do NOT build from `5ec354f`** (plain 2.3.3, pre-Nova-fix).
- **Diagnostic log:**
  `/data/data/com.buccaneer.browser/files/diagnostic_session.jsonl` (pull via
  `run-as com.buccaneer.browser cat ...`). JS bridge:
  `window.Buccaneer.diagnosticEvent(event, jsonString)`.

---

## 2. The crash — THREE distinct signatures (raw, from `logcat -b crash`)

There are **two different crash mechanisms**, not one. This matters.

### Crash A — GPU thread (the PowerVR render-target bug)
`10-25 15:41:13.276  2245  2374 F libc: Fatal signal 11 (SIGSEGV), code 1, fault addr 0x4 in tid 2374 (Chrome_InProcGp)`
```
Build fingerprint: 'Amazon/mantis/mantis:6.0/NS6711/5908N:user/amz-p,release-keys'
ABI: 'arm'
pid: 2245, tid: 2374, oom: 0, name: Chrome_InProcGp  >>> com.buccaneer.browser <<<
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x4
    r0 00000000  r1 8ee6ac2c  r2 8c7db200  r3 00000044
    r4 00000000  r5 a1af85c8  r6 8c7ecdb0  r7 00000000
    r8 a1af85cc  r9 00000010  sl 8ee6ac2c  fp 8ea9af08
    ip a1b34fb0  sp 8ee6ac28  lr a1b1bee9  pc a1b1bec6  cpsr a0070030
backtrace:
    #00 pc 00013ec6  /vendor/lib/libIMGegl.so (KEGLReleaseRenderTarget+77)
    #01 pc 0003db53  /vendor/lib/egl/libGLESv2_POWERVR_ROGUE.so
    #02 pc 0003e66d  /vendor/lib/egl/libGLESv2_POWERVR_ROGUE.so
    #03 pc 00023f05  /vendor/lib/egl/libGLESv2_POWERVR_ROGUE.so
    #04 pc 00024a6d  /vendor/lib/egl/libGLESv2_POWERVR_ROGUE.so (glBindFramebuffer+372)
    #05 pc 0000fbe4  /system/lib/libGLESv2.so (glBindFramebuffer+84)
    #06 pc 0295bce1  /system/lib/libwebviewextchromium.so
    #07 pc 02821203  /system/lib/libwebviewextchromium.so
    #08 pc 028297bb  /system/lib/libwebviewextchromium.so
    #09 pc 02822ba1  /system/lib/libwebviewextchromium.so
    #10 pc 02822e4d  /system/lib/libwebviewextchromium.so
    #11 pc 02827ead  /system/lib/libwebviewextchromium.so
    ... (#12–#36 all /system/lib/libwebviewextchromium.so)
    #37 pc 00047353  /system/lib/libc.so (__pthread_start+22)
    #38 pc 0001a0fd  /system/lib/libc.so (__start_thread+6)
```
- Crashing thread = `Chrome_InProcGp` = Chromium's **in-process GPU/compositor
  thread** (not main, not a renderer).
- `fault addr 0x4` = near-null deref. An **earlier variant** of the same crash had
  `fault addr 0x0` and entered via `glDrawArrays+636` instead of
  `glBindFramebuffer+372`. So the null deref is inside
  `KEGLReleaseRenderTarget`, reached from **either** a framebuffer bind **or** a
  draw — i.e. the render target is released while a GL op on it is in flight.
- `oom: 0` → Android OOM killer not involved; this is a GPU-side failure.

### Crash B — renderer (sandboxed process)
`10-25 16:15:28.396  7877  8008 F libc: Fatal signal 11 (SIGSEGV), code 1, fault addr 0xbac0004 in tid 8008 (ThreadPoolForeg)`
```
pid: 7877, tid: 8008, oom: 0, name: ThreadPoolForeg  >>> com.amazon.webview.chromium:sandboxed_process0 <<<
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0xbac0004
    r0 80000000  r1 0000000f  r2 0000000f  r3 00000000
    r4 0baffedf  r5 0bac0000  r6 8d31ed38  r7 90ffa418
    r8 90ffa508  r9 41af8458  sl 90ffa430  fp 90ffa4c8
    ip 9309f9c5  sp 90ffa3e0  lr 9309fbeb  pc 9309fbf6  cpsr a0010030
backtrace:
    #00 pc 015f6bf6  /system/lib/libwebviewextchromium.so
    #01 pc 0189252f  /system/lib/libwebviewextchromium.so
    #02 pc 02c7ce71  /system/lib/libwebviewextchromium.so
    #03 pc 013b8fe5  /system/lib/libwebviewextchromium.so
    ... (all frames libwebviewextchromium.so, unsymbolicated)
```
- A **renderer** (`sandboxed_process0`) crash on a `ThreadPoolForeground` thread.
  `fault addr 0xbac0004` (r5 = `0xbac0000`, a page-aligned base + 0x4 offset —
  looks like a freed/invalid heap object).

### Crash C — main process abort (cascade from B)
`10-25 16:15:30.491  7780  7780 F libc: Fatal signal 5 (SIGTRAP), code 4 in tid 7780 (ccaneer.browser)`
```
pid: 7780, tid: 7780, oom: 0, name: ccaneer.browser  >>> com.buccaneer.browser <<<
signal 5 (SIGTRAP), code 4 (TRAP_HWBKPT), fault addr 0x93cd118e
Abort message: '[FATAL:crashpad_client_linux.cc(744)] Render process (7877)'s crash
                wasn't handled by all associated webviews, triggering application ...'
backtrace:
    #00 pc 0212e18e  /system/lib/libwebviewextchromium.so
    ...
    #09 pc 00012257  /system/lib/libutils.so (android::Looper::pollInner+626)
    #10 pc 00011f57  /system/lib/libutils.so (android::Looper::pollOnce+26)
```
- The **main** process aborts 2 s after the renderer (7877) died, because the
  renderer's crash "wasn't handled by all associated webviews." This is the
  **crash-loop**: renderer/GPU dies → main aborts → app restarts → page reloads →
  repeats.

**Read:** the user-visible "crash or restart" is at least two mechanisms — (A) the
PowerVR GPU-thread render-target bug, and (B) a renderer crash that cascades to a
main-process abort. Both end in the same restart loop.

---

## 3. GPU memory churn (raw `logcat`, `IMGMemtrackHAL` = PowerVR memtrack HAL)

Fires repeatedly, clustered around page load / degrade:
```
10-25 16:45:24.403   620   632 W IMGMemtrackHAL: hal_get_memory: memtrack cache rebuild was required
10-25 16:45:39.012   620   632 W IMGMemtrackHAL: hal_get_memory: memtrack cache rebuild was required
10-25 16:45:55.897   620   632 W IMGMemtrackHAL: hal_get_memory: memtrack cache rebuild was required
10-25 16:46:51.152   620   632 W IMGMemtrackHAL: hal_get_memory: memtrack cache rebuild was required
10-25 16:46:51.224   620   632 W IMGMemtrackHAL: hal_get_memory: memtrack cache rebuild was required
10-25 16:48:00.008   620   632 W IMGMemtrackHAL: hal_get_memory: memtrack cache rebuild was required
10-25 16:50:00.067   620   632 W IMGMemtrackHAL: hal_get_memory: memtrack cache rebuild was required
10-25 16:50:00.134   620   632 W IMGMemtrackHAL: hal_get_memory: memtrack cache rebuild was required
10-25 16:50:00.333   620   632 W IMGMemtrackHAL: hal_get_memory: memtrack cache rebuild was required
10-25 16:50:00.418   620   632 W IMGMemtrackHAL: hal_get_memory: memtrack cache rebuild was required
10-25 16:52:43.811   620  7509 W IMGMemtrackHAL: hal_get_memory: memtrack cache rebuild was required
```
Plus: `E IMGMemtrackHAL: DbgFsParseFile: fopen of /d/pvr/driver_stats failed (Permission denied)`
— raw GPU memory counters are not readable as the app user.

"memtrack cache rebuild" = the driver's GPU-buffer accounting is being rebuilt
repeatedly → GPU buffers are being allocated/freed rapidly (churn), consistent with
render-target eviction under pressure.

---

## 4. Memory state (raw `dumpsys meminfo com.buccaneer.browser`, live)

```
                 Total    Dirty    Clean    Dirty     Size    Alloc     Free
  Native Heap     7903     7756      120     5184    57856    12032    45823
  Dalvik Heap     3616     3592        0      450     9562     5737     3825
        TOTAL   109198    78753    17044     8818    67418    17769    49648
         Native Heap:     7756
            Graphics:    57461      <-- ~57 MB in GPU/graphics memory
       Private Other:     8088
               TOTAL:   109198       TOTAL SWAP PSS:     8818
```
- Total PSS **~109 MB**, of which **Graphics ~57 MB** (the GPU-side footprint),
  Native Heap ~7.9 MB, Dalvik ~3.6 MB.
- In-app `process_memory` samples (JSONL) show `processPssKb` 51,525–85,227 KB,
  `nativeHeapAllocatedBytes` ~14.5 MB, `javaUsedBytes` ~5.8 MB,
  `javaHeadroomBytes` ~396 MB. So the **Java heap is NOT the problem** (huge
  headroom); the pressure is **GPU/graphics-side** (57 MB) + native.

---

## 5. The 20-process crash table (raw, from 9,569-event diagnostic JSONL)

One app session, **20 `process_start` events = 19 crashes in ~1 h 50 m** (a crash
loop). For each process lifetime I took the **last** `compositor_proxy` sample
(before death) and the last `process_memory` PSS:

| proc | start (UTC) | life (s) | last `clippedImgs` | last `realImg` | last `emptyImg` | PSS (KB) | card_guard ev |
|---|---|---|---|---|---|---|---|
| 1  | 21:55:16 | 54   | 200 | 19 | 183 | 80,772 | 0 |
| 2  | 21:56:10 | 354  | 180 | 7  | 174 | 65,172 | 0 |
| 3  | 22:02:04 | 49   | **636** | 21 | 618 | 71,550 | 0 |
| 4  | 22:02:53 | 167  | **636** | 28 | 625 | 71,328 | 0 |
| 5  | 22:05:40 | 191  | –   | –  | –   | 85,227 | 0 |
| 6  | 22:08:51 | 97   | 200 | 11 | 201 | 64,142 | 0 |
| 7  | 22:10:27 | 69   | 200 | 20 | 182 | 75,133 | 0 |
| 8  | 22:11:36 | 350  | 180 | 7  | 174 | 65,827 | 0 |
| 9  | 22:17:26 | 1069 | –   | –  | –   | 78,496 | 0 |
| 10 | 22:35:16 | 51   | –   | –  | –   | 82,111 | 0 |
| 11 | 22:36:06 | 391  | –   | –  | –   | 83,888 | 0 |
| 12 | 22:42:37 | 83   | –   | –  | –   | 73,762 | 0 |
| 13 | 22:44:00 | 81   | –   | –  | –   | 64,668 | 0 |
| 14 | 22:45:21 | 74   | –   | –  | –   | 75,295 | 0 |
| 15 | 22:46:36 | 267  | –   | –  | –   | 76,206 | 0 |
| 16 | 22:51:03 | 320  | –   | –  | –   | –      | 0 |
| 17 | 22:56:23 | 284  | –   | –  | –   | 71,013 | **14** |
| 18 | 23:01:08 | 1189 | –   | –  | –   | 51,525 | **56** |
| 19 | 23:20:57 | 723  | –   | –  | –   | 75,203 | **54** |
| 20 | 23:33:00 | 740  | –   | –  | –   | 62,819 | **55** |

**Read of the table:**
- `realImg` (decoded TMDB textures) stays **7–28 in every flixer process** — the
  image firewall's MAX=24 cap is working. **Texture count is NOT the crash driver.**
- `clippedImgs` (compositor clipping containers) is the variable that tracks
  time-to-crash: **636 → 49–167 s; 180–200 → 350 s+.** More compositor layers =
  faster crash.
- The **detail page** (636 clipped) dies in < 3 min. The **main page** (180–200
  clipped) lives ~5.8 min — so "the main page works" is really "the main page is on
  a slower crash timer," not "the main page is safe."
- Procs 17–20 (my reverted card-guard build) lived **284–1189 s** — longer — but the
  user reported the UI broke (play button + episode list hidden). So the card guard
  traded crash-rate for broken UI. (See §8.)
- PSS never correlates with the crash (51–85 MB across both fast and slow deaths) —
  again pointing at GPU/compositor load, not process memory.

---

## 6. Main page vs detail page (raw CDP metrics)

Identical CDP probe on both, same viewport (960×540):

| Metric | Main `flixer.gd/` (slower crash) | Detail `flixer.gd/shows?tv=link-click&id=123542` (fast crash) |
|---|---|---|
| `<img>` | 182 | **660** (3.6×) |
| `overflow:hidden` clipping containers (compositor-layer proxy) | 190 | **699** (3.7×) |
| Horizontal scroll rows (overflow-x auto/scroll, content wider than box) | 9 | **33** (3.7×) |
| Page scroll height | 4,136 px | **12,376 px** (3×) |
| `<video>` | 0 | **1** (looping YouTube trailer) |
| `<iframe>` | 2 (non-video) | 4 (2× YouTube embed + 2× about:blank) |
| Largest image (hero backdrop) | **3840×2160 (4K)** | **3840×2160 (4K)** — *identical* |
| `Performance.getMetrics` | — | LayoutObjects 5728, Nodes 10065, Frames 9, ProcessTime ~270 s |

**Conclusions:**
1. The **4K hero is NOT the differentiator** — both carry the identical 3840×2160
   hero; the main page survives it.
2. The detail page has **~3.7× more compositor layers** (699 vs 190 clipping
   containers) and is 3× taller.
3. The detail page has a **looping muted YouTube trailer**; the main page has zero
   video.
4. So the detail page's total GPU load (compositor layers + a live looping video) is
   ~3.7× the main page's → past the PowerVR budget → render-target eviction → crash.

**Trailer URL (detail page):**
`https://www.youtube.com/embed/HxoR_7jVBCM?autoplay=1&controls=0&mute=1&loop=1&playlist=...`
(`autoplay=1`, `mute=1`, `loop=1` — muted, looping, background video; cross-origin
iframe, cannot be paused from the parent.)

---

## 7. Detail-page DOM structure (measured)

- The poster grid is **horizontal scroll rows** (the site's DOM changed since the
  last session's notes — it is NOT a vertical grid).
- Each row: a `div` with `overflow-x:auto/scroll`; its single child (the content
  strip) is **~3,680 px** wide inside a **~928 px** container → ~20 cards/row, ~5
  visible at once.
- Each card:
  ```html
  <div class="flex-shrink-0 relative cursor-pointer">   <!-- 176×230, overflow:visible -->
    <div class="w-full h-full overflow-hidden rounded-md">   <!-- clipping wrapper -->
      <img ...>   <!-- poster -->
    </div>
  </div>
  ```
- **660 per-poster clipping wrappers** (`overflow-hidden rounded-md`) = the dominant
  clipping layers (the other ~39 of the 699 are row/section wrappers + hero).
- Hero: a `[class*="75vh"]`/`[class*="100vh"]` section with the 4K TMDB backdrop
  `<img>` (`image.tmdb.org/t/p/original/...`, naturalWidth 3840) + a metadata strip
  (rating/status/date/seasons) + title/overview.
- Trailer: a separate cross-origin YouTube `<iframe>` (not inside the hero img).

---

## 8. EVERYTHING tried (raw, what / why / result)

### This session (all three deployed together, then REVERTED by user)
1. **Card virtualization** — `display:none` on poster cards outside a horizontal
   live window (`[scrollLeft−600, scrollLeft+clientWidth+600]`) per scroll row;
   restored on scroll. Injected at document-start via a new `flixer_card_guard.js`.
   - **Result: FAILED, made it worse.** It did hide 394/660 cards, but the user
     reported the **play button and episode list disappeared** and the page rendered
     mostly black with only a metadata strip. `display:none` → **layout reflow** →
     GPU churn spike → broke the visible UI. **Reverted.**
   - **Lesson: any change that triggers reflow makes this crash worse, not better.**
2. **Hero downscale** — rewrite hero src `/t/p/original/` → `/t/p/w1280/`
   (3840×2160 → 1280×720, ~9× fewer pixels).
   - **Result: worked mechanically** (hero verified 1280×720) **but the page still
     went black.** Not the differentiator (main page has the same 4K hero and
     survives). **Reverted** (part of the pulled batch).
3. **Trailer block** — remove the looping YouTube `<iframe>` from the DOM.
   - **Result: worked mechanically** (0 YouTube iframes) **but the page was worse**
     and "a random video played in the background." Also **already tried in a prior
     session** (going in circles). **Reverted.**

> **Net: all three reverted.** Source tree is clean at `25737ac`. (The device still
> has the last built APK — the one with these three — but no new install was done,
> per the user's instruction.)

### Previous session (all reverted — "made the entire system slow and unstable")
- **`compositor_proxy`** instrument (in `flixer_image_guard.js`) — **WORKS**,
  produced the clippedImgs→crash correlation. **Keep this.**
- **`visibility:hidden`** on virtualized images — **FAILED, worse** (instant freeze
  then crash). Hiding the image doesn't remove the card's clipping layer, and it
  still triggers invalidation.
- **Trailer block + YouTube iframe DOM removal** (`popup_guard.js`) — built,
  installed, reverted. Data showed the iframe was **not** the sole trigger (crash
  19 s after removal).
- **Bug #1–4, Item 5, Item 9** (roadmap items) — built, installed, reverted.
- **Diagnostics:** `crash_context` (in `onRenderProcessGone` — **never fires**
  because of the silent native death), `flixer_load_context`, `gpu_state` (5 s
  periodic), `processStartElapsed` — added, reverted.
- **`MAX_STRIP_PER_SYNC=10`** rate-limit + `image_guard_sync` logging — part of the
  "slow/unstable" batch, reverted.

---

## 9. Ruled out (each with the data that ruled it out)

- **YouTube iframe / trailer as the sole trigger** — removed it
  (`trailer_iframe_removed` fired, zero youtube frames); crash still happened 19 s
  later. (Still a *contributing load*, just not the sole cause.)
- **Android OOM / memory pressure** — `oom:0` on every crash; PSS 51–85 MB with no
  correlation to crash timing; Java headroom ~396 MB.
- **`content-visibility:auto`** — parsed but **not enforced** in this WebView fork
  (off-screen child still laid out with a full rect). Not a viable fix here.
- **Software rendering** — explicitly NOT a fix; user rejected it; docs reject it.
  (`MainActivity.kt:631` `softwareRendering` is OFF — leave OFF.)
- **NVIDIA driver** — this is PowerVR, not NVIDIA.
- **`crash_context` diagnostic** — useless (process dies before
  `onRenderProcessGone` fires).

---

## 10. Current state

- **Source:** clean at `25737ac` (2.3.3 + Nova fix). This session's three changes
  reverted (`MainActivity.kt` restored via git; `flixer_card_guard.js` deleted).
  Only untracked docs remain.
- **Device:** now has the **clean baseline APK** (built from `25737ac`, installed
  2026-10-25; `flixer_card_guard.js` absent, Nova fix present). Launching/restarting
  the app is allowed whenever needed (the old "user launches it" constraint was
  revoked 2026-10-25).
- **Nova fix:** commit `25737ac`, in `page_helpers.js`, hash
  `6fbc9faa3cbb3b3c4c824bd4246f6d2e57bcdb85490541e287a38408e8b395b0`. **DO NOT ERASE.**
- **Git map:**
  ```
  5ec354f  2.3.3 (NO Nova fix — do NOT build from here)
  25737ac  + Nova fix  <- SAFE BASELINE (current)
  6f61e06  Flixer crash loop (popup_guard)  <- REVERTED
  e174717  Flixer crash loop (scattered UI) <- REVERTED
  ```

---

## 11. The questions (sharp, data-tied — please answer / direct)

1. **The reflow paradox (§0.1).** Confirm or refute: does hiding/removing elements
   cause a re-rasterization burst that tips the saturated GPU over? If yes — what is
   the concrete way to reduce **steady-state** compositor layers on Chromium 118 /
   API 25 **without** a re-rasterization spike? Name the exact mechanism (a working
   `content-visibility` equivalent, a compositor layer cap, a specific flag, or a
   different virtualization technique) and how to apply it.

2. **The PowerVR bug (§0.2).** Is `KEGLReleaseRenderTarget` null-deref during
   `glBindFramebuffer`/`glDrawArrays` a known PowerVR Rogue use-after-free triggered
   by render-target eviction? Is there a hard driver limit on live render targets /
   FBOs, and what is the known mitigation (driver flag, Chromium feature flag,
   WebView setting)?

3. **The two crash mechanisms (§2).** Crash A (GPU thread, PowerVR) and Crash B
   (renderer `sandboxed_process0`, `ThreadPoolForeg`, cascading to a main-process
   SIGTRAP abort) — are these two faces of one root cause (GPU saturation) or two
   separate bugs? For Crash B, is the renderer crash GPU-related (the renderer's own
   GL context) or a memory/logic crash? **What single measurement would distinguish
   them?**

4. **The layer-count threshold (§5).** Data: 636 clipped → 49–167 s; 180–200 →
   350 s+. Can you estimate the actual Chromium **layer / backing-FBO count**
   threshold where PowerVR Rogue starts evicting render targets? Since
   `Page.getLayerTree` is unavailable, what is the **cheapest reliable way to measure
   the true compositor layer count** on Chromium 118 / API 25 (a CDP method, a
   `--enable-logging` flag, a `WebSettings` call, a JS probe)?

5. **WebView-level knobs (§0.3).** Rank these by likelihood of helping, and give the
   exact experiment for the top one: (a) a compositor layer cap, (b) a per-WebView
   GL/rasterization option that is **not** whole-app software rendering, (c) a
   `--disable-features`/`--enable-features` Chromium flag affecting layer management
   or GPU memory, (d) `WebView.setLayerType`, (e) a texture-quality cap, (f)
   neutralizing the looping video at the **network** layer (iframe stays, loads
   nothing) instead of removing it. Which are safe on API 25?

6. **The looping video (§6).** What is the GPU cost of a continuously-composited
   looping muted video vs a static page on PowerVR Rogue? Is it significant enough
   that neutralizing it (without reflow) would meaningfully extend time-to-crash?
   Network-block (iframe present, empty) vs DOM-remove — which causes less
   re-rasterization?

7. **The "main page works" reframe (§5).** The data shows the main page (180–200
   clipped) also crashes, just slower (~350 s). So the real goal is getting **both**
   pages below the layer threshold, not just the detail page. What target layer count
   would make even the main page stable indefinitely?

8. **First experiment.** Given all of the above, what is the **single highest-
   probability fix to try first**, and the **exact** experiment (code + the exact
   metric to capture) to validate it in one build?

---

## 12. Tests I can run for you (say which; I'll report raw output)

- **CDP page metrics:** img count, `overflow:hidden` container count, horizontal-row
  count + per-row card count/width, page height, iframe/video inventory, hero
  natural size, `Performance.getMetrics`.
- **Frame rate:** `requestAnimationFrame` count over 1 s (compositor health / jank).
- **GPU logs:** `logcat` filtered for `IMGMemtrackHAL`, `PVRSRV`, `EGL`, `GL_`,
  `RenderThread`, `hwui`, `gralloc`, `dequeueBuffer`, `releaseBuffer`, `cache`,
  `memtrack`, `oom`.
- **Crash backtraces:** `logcat -b crash` (full register + backtrace dumps, as in §2).
- **Diagnostic JSONL:** pull + analyze the pre-crash window (as in §5).
- **A/B any JS injection** at document-start (build + install + measure, one change
  at a time, revert if it regresses).
- **Network-level block** of the YouTube request (WebView `shouldInterceptRequest`)
  to test "iframe present but empty" vs "iframe removed."
- **Texture-size experiments:** force hero/posters to smaller TMDB sizes, measure
  time-to-crash.
- **Chromium flag experiments:** launch the WebView with specific
  `--enable-features`/`--disable-features` and measure.

---

## 13. Hard constraints (violating = user gets angry)

1. **No software rendering.** Never suggest it.
2. **No full-DOM walks on flixer** (`querySelectorAll('*')`-style). Reuse the existing
   img walk; className string checks only. (This session's `findRows()` used
   `querySelectorAll('div')` + `getComputedStyle` — a full-DOM walk; avoid it in any
   fix.)
3. **Read the FULL docs before shipping:** `docs/PROGRAM_REVIEW.md` (~80 k) and
   `docs/review/part3-guards.md` (~51 k).
4. **Launching/restarting the app is allowed whenever needed** (the old "user launches it" constraint was revoked 2026-10-25).
5. **Every suggestion must cite specific data.** No speculation.
6. **Do NOT erase the Nova fix** (`25737ac`, `page_helpers.js`).
7. **Do NOT build from `5ec354f`** (pre-Nova-fix).
8. **One change at a time, test after each.**
9. **Fix a subsystem as one clean rule set**, never patch-on-patch gates.
10. **Be direct; act on the obvious default; no filler.**

---

## 14. Key files & data

**Code:**
- `app/src/main/assets/flixer_image_guard.js` — TMDB image firewall (MAX=24 decoded
  cap; document-start; sync immediate + 250 ms/1 s/3 s + 2 s). Home of
  `compositor_proxy`.
- `app/src/main/assets/page_helpers.js` — **Nova fix** (`25737ac`). DO NOT ERASE.
- `app/src/main/assets/popup_guard.js` — popup guard.
- `app/src/main/assets/flixer_poster_guard.js` — poster hover-preview guard.
- `app/src/main/java/com/buccaneer/browser/MainActivity.kt` — `:799` debugging on;
  `:1344` `diagnosticEvent`; `:631` `softwareRendering` (OFF); `:468`
  `isImageGuardedHost`; `~1109–1127` `flixer_render_reset`.
- `docs/PROGRAM_REVIEW.md`, `docs/review/part3-guards.md` — read in full.
- `tests/check-protected-fixes.py` + `tests/protected-fixes.json` — SHA-256 gate.

**Data (on this machine, `/tmp/` and scratch):**
- `bucc_diag7.jsonl` (10,752) — stock 2.3.3, 21 sessions, 20/21 crashed. **Primary baseline.**
- `bucc_diag8.jsonl` — `compositor_proxy`; Crash A (181 posters, 4 min).
- `bucc_diag9.jsonl` — measurement + `visibility:hidden`; Crash B (616 posters, 36 s).
- `bucc_diag10.jsonl`, `bucc_diag11.jsonl` — this session (crash windows + card-guard events).
- `bucc_diag_latest.jsonl` (9,569) — the 20-process crash table source (§5).
- `raw_crash_buffer.txt` (859 lines) — full `logcat -b crash` (the 3 signatures in §2).
- Older: `bucc_diag.jsonl` (7,478), `bucc_diag2.jsonl`, `bucc_diag3.jsonl` (9,330),
  `bucc_diag4.jsonl` (10,014), `bucc_diag5.jsonl` (10,461).
