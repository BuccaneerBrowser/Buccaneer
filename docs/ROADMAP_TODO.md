# Buccaneer Hardening Plan — Shared Todo / Roadmap

> **What this is:** the durable, cross-session task list for the browser-hardening plan
> (received 2026-10-24; **supersedes all earlier roadmaps**).
>
> **For other sessions:** read this file, load it into the per-session `todo_list` tool,
> do the work, then **write progress back here** (this file is the source of truth; the
> in-session todo tool is only a working copy and does NOT survive on its own).
>
> **How to reference it in chat:** "show the todo", "what's next on the todo?",
> "mark <item> done", "add <X> to the todo", "update the todo", "continue the plan".
>
> **Last updated:** 2026-10-24
> **Legend:** `[x]` done · `[>]` in progress · `[ ]` pending · `[~]` rejected/cancelled

---

## Platform targets (measure separately — do not conflate)

- **Fire TV** — Android 7.1.2 / **API 25** (primary target).
- **Android 11 test box** — **API 30** (secondary target).
- API availability: `onRenderProcessGone` (26+), `WebViewRenderProcess` (29+),
  `ApplicationExitInfo` (30+) are **NOT available on Fire TV**. `Debug.MemoryInfo` is on both.
- Process boundary: web content runs **in the app process on API 25** but in a **separate
  renderer process on API 30** — measurements must account for this.

## Standing constraints

- Investigation-only until measurements justify a change. "Recommend no change if the
  measurements show no meaningful benefit."
- Speculative interventions (blanket codec bans, hot-spare WebViews, generic CSS/GPU
  tweaks, planned restarts) stay **deferred/rejected** unless a measured failure justifies them.
- fmhy testing MUST use the bookmark URL `https://fmhy.net/video` (not the main page).
- Work in `/home/user/BuccaneerClaude-2.0` (live tree).

---

## Plan (in priority order)

### [x] PLAN #1 — M2: fmhyThemeJs duplicate-injection audit
- **NO CHANGE** (measured 2026-10-24). 4 injection sites (MainActivity.kt 904/927/1061/1193),
  3–4 idempotent runs per full load, ~2.8 ms (immaterial). Script is a 23-line idempotent IIFE.

### [ ] PLAN #2 — Establish reliable test & CI baseline
- [ ] Verify current test statuses in local tree (which checks are authoritative)
- [ ] Repair or retire stale checks deliberately (outdated hashes, dead asset refs)
- [ ] Establish non-release CI job: build + run reliable checks, no release-signing secrets

### [ ] PLAN #3 — Profile Popup Guard + aggregate injected-JS workloads
- [ ] Profile full-DOM traversal on non-Flixer sites (CineJoy, Coreflix, Movy): elements visited,
      scan duration/frequency, trigger rate, does it actually catch a popup
- [ ] Inventory all injected scripts, frame scopes, timers, observers, bridge calls (per-script/per-frame)
- [ ] Preserve Flixer reduced path + already-measured low-cost mechanisms (player-pin / dead-video)
- Note: Popup Guard is **NOT** scanning the entire page every 300 ms (earlier assumption corrected).

### [ ] PLAN #4 — Measure OSD work + controlled memory soak
- [ ] Measure OSD JS call rate/latency + whether it updates unchanged values (before touching min interval)
- [ ] Controlled memory soak: repeat a matched nav/playback sequence; record PSS, private dirty,
      Java heap, graphics, nav count, playback time, recovery events; separate trend from warm-up
- [ ] Try `Debug.MemoryInfo` summary.graphics on Fire TV vs PSS/dumpsys (account for API25
      single-process vs API30 renderer split)

### [ ] PLAN #5 — Trace navigation/history + crash recovery
- [ ] Distinguish renderer death from whole-app termination (API25 vs API30 capabilities)
- [ ] Test repeated-failure handling / crash-loop safe landing (full-app watchdog kept separate
      from renderer recovery)

### [ ] PLAN #6 — Investigate playback recovery
- [ ] Session resume qualification: player identity, stale-state rejection, live vs VOD seekability
- [ ] Renderer unresponsiveness on Android 11 box (use runtime `WebViewFeature` checks, not an
      API-level assumption)
- [ ] Classify real stall symptoms (network / timeline / dead video / decoder / compositor)
      before any auto-seek

### [ ] PLAN #7 — Codec / alternative playback (CONDITIONAL on evidence)
- [ ] Record actual stream/codec/decoder + correlate with dropped frames / black screen on a
      repeatable stream
- [ ] Only then: reversible, narrowly-scoped capability experiment, and only if measurements
      implicate a specific path + fallback verified

---

## Separate investigations (not part of the numbered plan)

- [ ] JS bridge security audit (`addJavascriptInterface` exposed to all frames, no origin guarantee)
- [ ] Background restart behavior (Android 10+ only; **NOT** applicable to API 25 Fire TV)
- [ ] target-SDK migration / GeckoView — platform migration, **not** a stability fix

## Parking lot

- [ ] **DEFERRED:** external-player handoff, adaptive site profiles, scheduled restarts —
      need a reproducible failure first
- [~] **REJECTED:** hot-spare WebView, broad CSS safe mode / generic GPU transforms

---

## Prior work (already done — do not redo; for reference)

- [x] M1 — `pendingClearHistory`/`clearHistory` reliability → NO CHANGE (measured on-device)
- [x] P1 — bigFrame timer cost → NO CHANGE
- [x] P2 → NO CHANGE
- [x] S2 — poster-guard event-driven re-check (committed `0db2d6f`)
- [x] log() diagnostic bridge fix (committed `42f263e`)
- [x] fmhy dead-time / popup_guard scan cost (committed `6119b4e`)
- [x] Hydration UX: red X + scroll restore (committed `81ca2e8`)

---

## Git / environment snapshot (as of 2026-10-24)

- Branch `main`, HEAD `81ca2e8`, working tree clean. Local main **4 ahead** of `origin/main`
  (`e1b0b30`): `0db2d6f`, `42f263e`, `6119b4e`, `81ca2e8`. **No push.**
- Gate `tests/check-protected-fixes.py` must PASS before any change lands.
- `popup_guard.js` + `flixer_image_guard.js` are hash-pinned; `page_helpers.js` +
  `flixer_poster_guard.js` are not. `scroll_restore.js` is a new (unpinned) asset.
- Build: `source /home/user/android-env/env.sh && gradle assembleDebug --console=plain`
  (no `gradlew` wrapper). Debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- versionCode=26, versionName="2.3.3" (debug APKs are not releases).
