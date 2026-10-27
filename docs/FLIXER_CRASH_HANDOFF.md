# Flixer PowerVR Crash — Full Session Handoff

> For a fresh session. Everything tried (every attempt, what/why/result), all data
> gathered (every number, every file), current state, and the path forward.
> Read top-to-bottom. Last updated 2026-10-25 ~15:20 MST.
>
> **The Nova fix (commit `25737ac`) is confirmed working on the device. Do not erase it.**

---

## 0. Hard constraints (violating any of these = user gets angry)
1. **Software rendering is NOT a fix.** Never suggest it. `softwareRendering` / `LAYER_TYPE_SOFTWARE` rejected by user AND by docs. (`MainActivity.kt:631` is OFF — leave it OFF.)
2. **No full-DOM walks on flixer.** Flixer page rule: restricted element set, cheap rules, slow interval, no observer. `document.querySelectorAll('*')`-style walks risk the PowerVR path. Reuse the existing img walk; className string checks only.
3. **Read the FULL program docs before shipping** — `docs/PROGRAM_REVIEW.md` (~80k chars) and `docs/review/part3-guards.md` (~51k chars). Not just "relevant" sections. The user has enforced this repeatedly: "the documents tell you how to not break the program."
4. **Launching/restarting the app is allowed whenever needed.** (The old "user starts it" constraint was revoked 2026-10-25.)
5. **Every suggestion must cite specific data.** No speculation. "data backed?" is a real, repeated question.
6. **Do NOT erase the Nova fix** (commit `25737ac`, in `page_helpers.js`). The user spent a long time on it.
7. **Do NOT build from / reinstall plain 2.3.3 (`5ec354f`).** It is *before* the Nova fix and wipes it from the device. Build from `25737ac` or later. (This exact mistake was made this session and had to be undone.)
8. **One change at a time, test after each.** ("lets do 1 at a time and test after each")
9. **Fix a subsystem as one clean rule set, never patch-on-patch gates.**
10. **Be direct, act on the obvious default, don't re-ask what's decided.** The user is frustrated with slowness and with not listening. No filler, no excuses, no theorizing — "when they say it's the code, treat it as fact and find the exact line."

---

## 1. Current state (verified 2026-10-25 ~15:17–15:20 MST)
- **Device:** Fire TV AFTMM (Android 7.1.2) at `10.4.20.137:5555` via adb. Package `com.buccaneer.browser`. `versionName=2.3.3`, `versionCode=26`.
- **Dev tree:** `/home/user/BuccaneerClaude-2.0`, checked out at **`25737ac`** (2.3.3 + Nova fix). Working tree clean except this handoff file.
- **Installed on device:** build from `25737ac`. **Nova fix verified present** — `page_helpers.js` in the APK hashes to `6fbc9faa3cbb3b3c4c824bd4246f6d2e57bcdb85490541e287a38408e8b395b0` (matches working tree), 40 mouseleave-guard lines. `lastUpdateTime=2026-10-25 15:17:23`.
- **Build env:** `source /home/user/android-env/env.sh`; gradle `/home/user/android-env/gradle-8.10.2/bin/gradle`.
  - Build: `gradle assembleDebug --no-daemon`
  - Install: `adb -s 10.4.20.137:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
  - Gate (must PASS before build): `python3 tests/check-protected-fixes.py`
- **Gate mechanics:** `tests/protected-fixes.json` is a **LIST** of `{path, sha256}` (plus marker-delimited regions for `MainActivity.kt`). When you edit a protected asset, recompute its `sha256` in that JSON or the gate fails. Helper:
  ```python
  import hashlib, json
  path='app/src/main/assets/<file>.js'
  h=hashlib.sha256(open(path,'rb').read()).hexdigest()
  pf=json.load(open('tests/protected-fixes.json'))
  for c in pf:
      if c.get('path')==path: c['sha256']=h
  json.dump(pf, open('tests/protected-fixes.json','w'), indent=2)
  ```

### Git history (the map — memorize this)
```
5ec354f  Buccaneer 2.3.3                       <- plain 2.3.3, NO Nova fix. DO NOT build from here.
25737ac  Fix Nova fullscreen control flicker    <- Nova fix (stale-pointer mouseleave guard, page_helpers.js +86). SAFE BASELINE.
6f61e06  Fix Flixer crash loop (popup_guard)    <- REVERTED by user (slow/unstable).
e174717  Fix Flixer crash loop (scattered UI)   <- REVERTED by user (slow/unstable).
```
- **`25737ac` = the safe baseline** (2.3.3 + Nova fix, without the reverted Flixer-crash-loop commits).
- **`stash@{0}`** = ~380 lines of earlier work across 6 files (Bug #1–4, Item 5, Item 9, trailer block, iframe DOM removal, `visibility:hidden`, 3 diagnostics, `MAX_STRIP_PER_SYNC`, `image_guard_sync`). ALL reverted by the user because it "made the entire system slow and unstable." Do NOT blindly restore it. A backup of this session's `compositor_proxy` measurement is at `/tmp/my_measurement_backup.js`.

---

## 2. The crash (what it is — data-confirmed)
- **Signature:** PowerVR `KEGLReleaseRenderTarget+77` (`/vendor/lib/libIMGegl.so`) via **`glDrawArrays`** (`libGLESv2_POWERVR_ROGUE.so`), on **HWUI RenderThread** (the compositor). `SIGSEGV`, `oom:0`, fault addr `0x4`.
- **Exact call path (from `logcat -b crash`):**
  ```
  #00  KEGLReleaseRenderTarget+77   /vendor/lib/libIMGegl.so
  #08  glDrawArrays+318            /vendor/lib/egl/libGLESv2_POWERVR_ROGUE.so
  #09-36 libwebviewextchromium.so
  #37  libwebviewchromium_plat_support.so
  #38-43 libhwui.so
  #44  RenderThread::threadLoop    libhwui.so
  ```
- **It is a use-after-free / race in the PowerVR GPU driver**, triggered by the compositor's draw load. NOT the GPU process, NOT memory pressure, NOT the YouTube iframe.
- **Silent native death:** the process dies *before* `onRenderProcessGone` fires, so in-app "crash context" diagnostics never capture it. The last JSONL event is always 3–6s before death.
- **100% reproducible** on flixer. ("failure rate is 100%, i need your full efforts")
- **It is fixable.** ("We have fixed this in other versions so i know its possible.")
- Docs (`PROGRAM_REVIEW.md` Part 2.12 / inv.25 / limitation 1) claim "no app-side fix exists" and "watchdog = only recovery" — but the user has demonstrably fixed it before, so treat the docs as incomplete, not absolute.

---

## 3. ALL data gathered

### 3.1 `compositor_proxy` measurement (the key new instrument this session)
Added to `flixer_image_guard.js` `sync()` (runs every 2s, reuses the existing img walk — no full-DOM walk, className string checks only). Logs `compositor_proxy` every ≥2s with:
- `realImg` — TMDB posters with a live src (decoded GPU textures)
- `emptyImg` — virtualized posters (src stripped, no texture)
- `fixedImgs` — posters whose card container className contains `fixed`
- `clippedImgs` — posters whose card container className contains `overflow-hidden`

**Crash A — measurement-only build, 181-poster page:**
- Session `21:56:10 → 22:00:28` (~4 min 18s).
- Steady state every 2s: `realImg:7, emptyImg:174, fixedImgs:0, clippedImgs:180`.
- Workload **constant** (not a leak). Died 2s after the last sample. No user interaction at death.

**Crash B — measurement + `visibility:hidden` build, 616-poster page:**
- Session `22:02:53 → 22:03:29` (36s).
- `realImg:7→25, emptyImg:593→610, fixedImgs:0, clippedImgs:616`.
- Died right after a `KEYCODE_DPAD_CENTER` tap + `popup_requested`. User report: "froze instantly then crashed."

**Findings (the evidence):**
1. **More card containers = faster crash.** 180 `clippedImgs` → 4 min; 616 `clippedImgs` → 36s. Clear inverse correlation between card count and time-to-crash.
2. **Cards are NOT `position:fixed`** (`fixedImgs:0` in every sample). An earlier hypothesis (from a `poster_guard` log showing a `fixed` class) was WRONG — that element was a popup/overlay, not the poster cards.
3. **Cards ARE `overflow:hidden`** (`clippedImgs` ≈ total posters). They are clipping containers.
4. **Workload is constant, not growing** — the crash is a race at steady state, not resource exhaustion / leak.
5. **`visibility:hidden` on the images made it WORSE** (instant freeze then crash). Hiding the image does not remove the card container / clipping layer.

### 3.2 Stock 2.3.3 baseline (`/tmp/bucc_diag7.jsonl`, 10,752 lines)
- **21 sessions, 20/21 crashed.** Always 3–6s after the last diagnostic event (silent native death).
- Crashes span `img 203–653`, `allowlist 8–24` — **neither count alone is a discriminator.**
- Last crash in that file: 21:06:53, YouTube iframe already removed, `img=220`, `allow=24`, 6s after a scroll tap.

### 3.3 Ruled out (each with data)
- **YouTube iframe** — NOT the trigger. Removed it (`trailer_iframe_removed` fired, zero youtube frames), crash still happened 19s later (21:06:34 removal → 21:06:53 crash).
- **Memory pressure / OOM** — `oom:0`, workload constant across minutes.
- **`flixer_render_reset` missing** — secondary issue only. The 2.3.2 "fix" (`MainActivity.kt` ~1109–1127) fires only when `lastNonFlixerHost` is non-empty; on a watchdog restart that's empty, so it never fires. Not the root cause.
- **NVIDIA driver** — ruled out earlier (this is a PowerVR GPU, not NVIDIA).
- **`crash_context` diagnostic** — useless: process dies before `onRenderProcessGone`, so it logged 0 events across all diagnostic builds.

### 3.4 Earlier diagnostic builds (context)
- `/tmp/bucc_diag4.jsonl` (10,014) — first diagnostic build. 0 `crash_context`. 3 `flixer_load_context` all showed `videoActive=False, lastNonFlixerHost='', renderResetFired=False, tmdbAllowlistSize=0, crashReloadCount=0`.
- `/tmp/bucc_diag5.jsonl` (10,461) — lockup + crash. Last session 41s, 220 img, allow=24, `trailer_iframe_removed` 21:06:34, crash 21:06:53.
- `/tmp/bucc_diag3.jsonl` (9,330) — post-trailer-block. `flixer_render_reset` fired exactly once (16:55:15, from nippleflix.com); all later crashes had zero render reset.

---

## 4. ALL attempts (every one, what/why/result)

### This session (flixer crash focus)
1. **`compositor_proxy` measurement** (flixer_image_guard.js) — ADDED, WORKS. Logs realImg/emptyImg/fixedImgs/clippedImgs every 2s. This is the instrument that produced the card-count→crash correlation. **Keep it.**
2. **`visibility:hidden` on virtualized images** (in `virtualize()`: set `visibility:hidden` when stripping src, save prior in `__flixerVis`, restore on re-entry) — **FAILED, made it worse** (instant freeze then crash, Crash B). Reverted. Why it fails: hides the *image* but the *card container* (`overflow:hidden`) + its clipping layer remain — the dominant workload is untouched.

### Earlier this session (all reverted by user — "made the entire system slow and unstable")
3. **Trailer block + YouTube iframe DOM removal** (`popup_guard.js`: `killCatchers`, `playerRoot`, `isPlayerUI`) — built+installed, then reverted. Data later showed the iframe was NOT the trigger anyway.
4. **Bug #1–4** (4 Buccaneer bugs) — patched, built+installed, reverted.
5. **Item 5, Item 9** (roadmap items) — built+installed, reverted.
6. **Diagnostics:** `crash_context` (in `onRenderProcessGone` — never fires), `flixer_load_context` (in `onPageCommitVisible`), `gpu_state` (5s periodic on flixer), `processStartElapsed` (set in `onCreate`) — added, built+installed, reverted.
7. **`MAX_STRIP_PER_SYNC=10`** rate-limit (limit images stripped per sync to avoid GPU bursts) + **`image_guard_sync`** logging (durationMs/imgCount/allowlistSize/stripped/restored) — part of the "slow/unstable" batch, reverted.

### Unrelated work this session (NOT the flixer crash — don't confuse)
8. **Hermes compression fix** — threshold set to **160k** in `/home/user/.hermes/config.yaml` (user requested both users at 160k; was 100k). 12/12 tests passed. Gateway PID was 789298. `/home/hermes/.hermes/config.yaml` (other user) is permission-denied, no sudo — their threshold not set.

---

## 5. Working hypothesis (data-backed, UNCONFIRMED)
- The crash is driven by the **number of `overflow:hidden` card containers** the HWUI compositor must composite (each = a clipping layer / draw target). More cards → more `glDrawArrays` → higher chance of hitting the PowerVR use-after-free.
- **The fix must reduce the number of composited card containers**, NOT the images inside them.
- Candidate directions (NOT validated — measure first, do not guess):
  a. **Virtualize the card container**, not just the image — take the whole off-screen card out of the compositor's work (e.g. `display:none` on the card, or move it out of the composited region). RISK: `display:none` changes layout/reflow — must verify it doesn't break the grid or trigger the churn that crashes.
  b. **Cap how many cards are live at once** (aggressive virtualization of the poster grid) so the compositor never sees >N clipping layers.
  c. **Reduce per-card layer cost** (remove `overflow:hidden`/rounded clipping where possible) — changes site styling, high risk.
- **Get the real compositor layer count first** (see §6) and confirm the card-count→crash correlation before changing anything.

---

## 6. Next steps (in order)
1. **Get the REAL compositor layer count**, not the DOM proxy. WebView debugging is ON (`MainActivity.kt:799` `setWebContentsDebuggingEnabled(true)`). Use CDP from the host: find the `webview_devtools_remote_<PID>` unix socket, `adb forward`, query the layer tree / compositor status, poll ~2s, log to a file. The last sample before the silent death = true layer count at crash. Confirm it tracks `clippedImgs`.
2. **Research** the PowerVR `KEGLReleaseRenderTarget` + `glDrawArrays` RenderThread crash (known triggers, mitigations, what "other versions" changed). A web search was started; top hits point to GPU-driver use-after-free under sustained draw load and WebView+HWUI `RenderThread` SIGSEGV with many layers.
3. **Design the fix** from (1)+(2): reduce composited card containers. ONE clean rule set (no patch-on-patch gates).
4. **Measure before/after** with `compositor_proxy` + CDP layer count. Ship only if the layer count drops AND the crash stops.
5. Read FULL docs before shipping. Run `check-protected-fixes.py`. Build from `25737ac`+ (never `5ec354f`). Install; launching is allowed.

---

## 7. The `compositor_proxy` measurement code (to re-add if needed)
In `flixer_image_guard.js`:
- State: `var lastCpLog = 0;`
- Helper (place after `isTmdbImg`):
```js
function isTmdbPoster(im) {
  if (!im || im.tagName !== 'IMG') { return false; }
  if (im.__flixerSrc) { return true; }   // already virtualized by the guard
  return isTmdbImg(im);
}
```
- In `sync()`, after the "mark off-screen images as blocked" loop:
```js
var now = Date.now();
if (now - lastCpLog >= 2000) {
  lastCpLog = now;
  var realImg = 0, emptyImg = 0, fixedImgs = 0, clippedImgs = 0;
  for (var k = 0; k < imgs.length; k++) {
    var im = imgs[k];
    if (!isTmdbPoster(im)) { continue; }
    if (im.__flixerSrc) { emptyImg++; } else { realImg++; }
    var p = im.parentElement;
    if (p && p.className && typeof p.className === 'string') {
      if (p.className.indexOf('fixed') !== -1) { fixedImgs++; }
      if (p.className.indexOf('overflow-hidden') !== -1) { clippedImgs++; }
    }
  }
  try {
    window.Buccaneer.diagnosticEvent('compositor_proxy', JSON.stringify({
      realImg: realImg, emptyImg: emptyImg,
      fixedImgs: fixedImgs, clippedImgs: clippedImgs
    }));
  } catch (e) { /* bridge not ready */ }
}
```
- Native side: `window.Buccaneer.diagnosticEvent(event, details)` → `DiagnosticLog.record` (`MainActivity.kt:1344`). Lands in `/data/data/com.buccaneer.browser/files/diagnostic_session.jsonl`.
- Pull: `adb -s 10.4.20.137:5555 shell "run-as com.buccaneer.browser cat /data/data/com.buccaneer.browser/files/diagnostic_session.jsonl" > /tmp/bucc_diagN.jsonl`
- NOTE: this measurement is currently NOT in the installed build (the device is on clean `25737ac`). Re-add it per above when you resume the crash work.

---

## 8. Key files
- `app/src/main/assets/flixer_image_guard.js` — image guard. `MAX=24` decoded cap. DOCUMENT-START; sync immediate + 250ms/1s/3s + 2s. (Where `compositor_proxy` + the failed `visibility:hidden` lived.)
- `app/src/main/assets/page_helpers.js` — **Nova fix** (commit `25737ac`, +86 lines, stale-pointer mouseleave guard). **DO NOT ERASE.**
- `app/src/main/assets/popup_guard.js` — popup guard (`killCatchers`, `playerRoot`, `isPlayerUI`).
- `app/src/main/assets/flixer_poster_guard.js` — poster guard (hides popups/overlays; the "fixed" class seen in its logs was a popup, not a card).
- `app/src/main/java/com/buccaneer/browser/MainActivity.kt` — `:799` debugging on; `:1344` `diagnosticEvent`; `~1109–1127` `flixer_render_reset`; `:468` `isImageGuardedHost`; `:631` `softwareRendering` (OFF — do not enable); `:3925` softwareRendering toggle.
- `app/src/main/java/com/buccaneer/browser/PopupGuard.kt` — `:133` `novahd.cc` (the Nova site).
- `docs/PROGRAM_REVIEW.md` (~80k), `docs/review/part3-guards.md` (~51k) — READ IN FULL before shipping.
- `docs/ROADMAP_TODO.md` — PLAN #1 done; #2–#7 pending; flixer crash not on the list.
- `tests/check-protected-fixes.py` + `tests/protected-fixes.json` — SHA-256 gate.

## 9. Data files (on this machine, `/tmp/`)
- `bucc_diag7.jsonl` (10,752) — stock 2.3.3, 21 sessions, 20/21 crashed. **Primary baseline.**
- `bucc_diag8.jsonl` — measurement-only build; Crash A (181 posters, 4 min).
- `bucc_diag9.jsonl` — measurement + `visibility:hidden`; Crash B (616 posters, 36s).
- Older: `bucc_diag.jsonl` (7,478), `bucc_diag2.jsonl` (crash session), `bucc_diag3.jsonl` (9,330), `bucc_diag4.jsonl` (10,014), `bucc_diag5.jsonl` (10,461).
- `/tmp/my_measurement_backup.js` — this session's `compositor_proxy` measurement (backup).
- `/tmp/flixer_image_guard_measure.js` — measurement-only flixer_image_guard.js (backup).

## 10. Misc state (not the crash — don't lose it)
- Compression threshold **160k** in `/home/user/.hermes/config.yaml` (both users requested). Gateway PID was 789298.
- `/home/hermes/.hermes/config.yaml` = other user's, permission denied, no sudo.
- Item 6 (crash ledger) — paused.
- ADB orphan-recovery warning — unresolved, cosmetic.
- Session ID this session: `20261025_104951_87d508ab`.
