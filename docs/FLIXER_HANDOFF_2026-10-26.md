# FLIXER BLACK-SCREEN HANDOFF — 2026-10-26

**For a new session. Read this fully before touching anything.**

## 1. THE PROBLEM

Flixer.gd show/movie detail pages (e.g. `https://flixer.gd/shows?tv=link-click&id=123542`) degrade ~20–25 s after load:

- The **hero/backdrop image goes solid black** (raster loss — GPU texture evicted)
- The **UI remains visible** (title, star rating, date, season count, Play/My List buttons, "Returning Series" badge)
- The **DOM is intact** — no crash, no JS error, `document.querySelector('h1').textContent` still returns "LINK CLICK(2021)"
- **Scrolling the episode list accelerates the black** (user-confirmed, 2026-10-26)
- **Clicking an episode crashes the app** (restarts to home screen) — new observation 2026-10-26
- Only recovery: `Page.reload` / `Page.navigate` (degrades again after ~20 s)
- D-pad does NOT recover

The user's requirement: **no black screen, content can play, episodes can be selected.** "Degrading to black isn't going to work."

## 2. DEVICE / ENVIRONMENT

| Item | Value |
|---|---|
| Device | Fire TV AFTMM (Android 7.1.2) |
| ADB | `10.4.20.137:5555` |
| App | `com.buccaneer.browser` (Buccaneer) |
| GPU | PowerVR Rogue (mt8695 SoC) |
| WebView | Chromium 118 Amazon WebView (`118.amazon-webview-v118-5993-tv`) |
| maxTextureSize | 4096 px |
| Crash signature | `Chrome_InProcGp`, SIGSEGV, fault addr `0x0`/`0x4`, `KEGLReleaseRenderTarget+77` in `libIMGegl.so` |
| Toolchain | `/home/user/android-env` (source `env.sh` before gradle) |
| Build | `cd /home/user/BuccaneerClaude-2.0 && source /home/user/android-env/env.sh && gradle assembleDebug` |
| Install | `adb -s 10.4.20.137:5555 install -r app/build/outputs/apk/debug/app-debug.apk` |
| CDP forward | `adb -s 10.4.20.137:5555 forward tcp:9222 localabstract:webview_devtools_remote_<PID>` |
| Diagnostics | `adb shell "run-as com.buccaneer.browser cat files/diagnostic_session.jsonl"` |
| App restart | `adb shell am force-stop com.buccaneer.browser && adb shell monkey -p com.buccaneer.browser 1` |

**App restart behavior:** lands on home/bookmark screen (105,258 B screencap). Flixer bookmark at row 2, col 2. D-pad: `KEYCODE_DPAD_DOWN`, `KEYCODE_DPAD_RIGHT`, `KEYCODE_DPAD_CENTER` to open flixer.gd.

## 3. ROOT CAUSE ANALYSIS (CONFIRMED)

**Raster loss branch** (confirmed by `hero_connected2.py`):
- At t=15 s: hero `<img>` connected, rect [0,0,945,540], src `4NmCfWHzytiKM3riCMcbVYBJhlB.jpg`
- At t=28.5 s: hero still connected, same rect, same src, **in elementsFromPoint stack** (position 28, below YouTube iframe + black bg), **but black on TV**
- Conclusion: the hero's GPU texture is evicted (raster loss), not removed from DOM

**GPU memory pressure is the driver:**
- Giant BODY layer: 1920×25468 device px (~47 MP), `drawsContent=True`, `paintCount=15`. Chromium tiles it (maxTextureSize=4096)
- 18 episode posters at w500 (500×750 px each) = ~27 MB GPU texture memory
- 2 YouTube trailer iframes (1114×626, video ID `HxoR_7jVBCM`) appear at ~20 s
- Image guard virtualizes 657/665 images (sets `img.src=''` for off-screen)
- Scrolling the episode list loads more posters → more GPU memory → hero evicted faster

**What does NOT cause it (disproven):**
- NOT a crash (DOM intact, no JS error)
- NOT React removing the hero (hero_connected2: connected=true at t=28.5 s)
- NOT network/allowlist (control test black at t=24.8 s)
- NOT GPU cache/memory exhaustion from a long session (fresh load, stable 210–340 MB)
- NOT the image guard `sync()` alone (control black at t=24.8 s)
- NOT content-based (time-based, ~20 s after load)
- NOT software rendering (user forbade suggesting it)

## 4. EVERYTHING TRIED (CHRONOLOGICAL)

### 4.1 Native intercepts (in MainActivity.kt `shouldInterceptRequest`)

| # | What | Result |
|---|---|---|
| 1 | **Trailer intercept**: block YouTube embed requests (`youtube.com/embed/*`) → return blank HTML | Eliminates crash, frame times −43% (avg 31.1→17.6 ms, max 283.6→100.1 ms). Page still degrades to black. |
| 2 | **Hero downscale**: `/t/p/original/` → `/t/p/w780/` for allowlisted TMDB URLs | Reduces hero from 4K (~33 MB GPU) to w780 (~1.4 MB). 101 `hero_downscaled` events in diag log. Page still degrades to black. |
| 3 | **Poster downscale**: `/t/p/w500/` → `/t/p/w185/` for allowlisted TMDB URLs (added 2026-10-26) | 17 `poster_downscaled` events in diag log (fix IS firing). **Hero still goes black.** Episode posters are NOT the sole cause. |

### 4.2 JS injection (document-start / evaluateJavascript)

| # | What | Result |
|---|---|---|
| 4 | **Image guard** (`flixer_image_guard.js`): virtualizes 657/665 images (sets `img.src=''` for off-screen), `sync()` every 2000 ms + on DOM change | Contributing factor, not sole cause. Control test (no guard) black at t=24.8 s. |
| 5 | **Poster guard** (`flixer_poster_guard.js`): hides `.__flixer_hover_preview` cards with `opacity:0 !important` on deep links | GPT's recommended target for permanent fix. Not yet the primary fix. |
| 6 | **Hero `position:fixed z=99999`** (from document start, proactive) | **PREVENTS BLACK** (110 s+ stable). But covers UI (title/Play hidden). React does NOT reset the hero's inline styles (static `<img>`, src doesn't change). |
| 7 | **Hero + UI leaves `position:fixed z=100000`** (H1, Play button) | **React resets inline styles** on re-render. H1 goes back to `position:static`. Fix doesn't persist. |
| 8 | **CSS rule for UI** (`h1 { position:fixed !important; z-index:100000 !important; }`) | Held ~110 s, then app went to home screen (105,258 B = bookmark screen, likely crash or user navigation). React/page-reload reset all styles. |
| 9 | **CSS hide trailer** (`display:none` on YouTube iframe container from document start) | Black at t=23.4 s (51,855 B). Hero still black. Hiding trailer does NOT restore hero. |
| 10 | **Remove video container** (DOM removal) | Black at t=19.7 s, flicker = React re-mount. |
| 11 | **Make iframe `position:fixed`** | Black at t=22.9 s. |
| 12 | **Make hero container `position:fixed`** (928×522) | Black at t=21.8 s. |
| 13 | **Make modal card `position:fixed`** (928×1387) | Black at t=21.7 s. |
| 14 | **Make UI ancestor `position:fixed`** (945×540, contains H1) | No black 144 s, but **UI not visible** (ancestor content doesn't render when fixed). |
| 15 | **Make full-page `div.min-h-screen` fixed z=100000** | No visible UI. |
| 16 | **Move hero into overlay** (`position:absolute`) | Black at t=24.4 s. Needs `position:fixed`, not `absolute`. |
| 17 | **`content-visibility:auto`** | Not enforced in Chromium 118 Amazon. |
| 18 | **Shrink body layer** (CSS) | Didn't shrink (triggered stall, black t~13 s). |
| 19 | **WebGL keep-alive** | Did NOT help. |
| 20 | **`translateZ(0)` on UI** | Does NOT create a composited layer on this device. |

### 4.3 CDP diagnostics (scratch scripts in `/home/user/.hermes/cache/scratch/`)

| Script | What it did | Key finding |
|---|---|---|
| `hero_connected2.py` | Checked hero state at t=15 s and t=28.5 s | **DEFINITIVE raster-loss**: hero connected, covers point, in stack, but black on TV |
| `media_dump.py` | Checked for YouTube iframes at t=28 s | **CONFIRMED**: 2 YouTube iframes (`HxoR_7jVBCM`), 1114×626, at (−77,−25) |
| `layer_dump.py` / `layer_events*.py` | Layer tree via `LayerTree.layerTreeDidChange` | Giant BODY layer id=6042: 1920×25468, drawsContent=True, paintCount=15. Others: 7520×460, 1920×2882, 2227×1253, 1856×1962, 1860×2778 (×5), 2044×888 |
| `matrix.py` | 4-variant A/B (hero_fixed, control, content-vis, combined) | hero_fixed survived 60 s; control + content-vis black |
| `css_hide.py` (v1–v3) | CSS hide trailer from document start | v1: entire screen black (10,595 B). v2/v3: black t=23.4 s (51,855 B), hero black, UI visible |
| `combined_test.py` | iframe + bg hidden from document start | NO `<img>` in elementsFromPoint at t=28.5 s (unexplained discrepancy with hero_connected2) |
| `hero_proactive.py` | Hero `position:fixed z=99999` from document start | **SUCCESS**: 110 s+ stable, no black. But covers UI. |
| `hero_fixed_ui.py` through `hero_fixed_ui8.py` | Various UI-on-top approaches | All failed (see table 4.2) |
| `dom_inspect.py`, `dom_ui_inspect.py`, `dom_card_inspect.py` | DOM structure analysis | Detail overlay: `DIV.fixed.inset-0.bg-black/90.z-[100].overflow-y-auto` → modal card `DIV.relative.max-w-6xl.mx-auto.bg-black/95.rounded-xl` (928×1387) → hero container `DIV.relative.md:aspect-video` (928×522) → hero `<img>` + UI |
| `hero_cdp_verify.py` | Hero-only fix, verified via CDP `Page.captureScreenshot` | No black 170 s (CDP screenshots 1,439,434 B). But CDP screenshot showed a DIFFERENT show (tab mismatch). |
| `hero_ui_cdp.py` | Hero + UI leaves fix, verified via CDP | No black 170 s (CDP screenshots 1,443,024 B). But CDP screenshot showed "Dutton Ranch" (tab mismatch). |
| `clean_tab_test.py` | Fresh single-tab test, adb screencap verification | H1 = "LINK CLICK(2021)" confirmed. No black 146 s (1,970,932 B). But adb screencap showed "The Apothecary Diaries" (tab mismatch). |
| `css_rule_test.py` | CSS rule for UI + hero inline fix | Held ~110 s, then app went to home (105,258 B). React/page-reload reset all styles. |

### 4.4 TAB MISMATCH (CRITICAL VERIFICATION BLOCKER)

**Discovered 2026-10-26. This is the #1 blocker for reliable verification.**

- The app has **multiple tabs** (WebViews)
- CDP connects to ONE tab (e.g. LINK CLICK detail page)
- The **screen (adb screencap) shows a DIFFERENT tab** (e.g. "The Terminal List", "Teach You a Lesson", "The Apothecary Diaries", "Dutton Ranch")
- CDP `Page.captureScreenshot` also captures the WRONG tab (showed "Dutton Ranch" while CDP target URL was LINK CLICK)
- **Consequence**: all "no black" results from adb screencap are SUSPECT (wrong tab). CDP `Page.captureScreenshot` is also unreliable.
- **Workaround**: force-stop + restart app → navigate to flixer.gd via D-pad → CDP navigate to LINK CLICK → single tab state. But even then, adb screencap showed a different show.
- **The app has a "continue watching" carousel** that keeps the foreground tab on a different show
- **Only reliable verification**: user is on the page and reports what they see (e.g. "im on the page", "the screen is black")

**CDP target list** (when mismatched):
```
Total targets: 1
0: page | https://flixer.gd/ | Flixer - Stream Free Movies & TV Shows Online
```
(Only 1 target visible, but the screen shows a different page)

## 5. KEY DOM STRUCTURE (LINK CLICK detail page)

```
DIV.fixed.inset-0.bg-black/90.z-[100].overflow-y-auto  (960×540, scrollable)
├── DIV.fixed.inset-0.bg-black/50.backdrop-blur-sm  (backdrop)
└── DIV.min-h-screen.py-[8vh].relative  (960×1441)
    └── DIV.relative.max-w-6xl.mx-auto.bg-black/95.rounded-xl  (928×1387, modal card)
        ├── DIV.relative.md:aspect-video  (928×522, hero container)
        │   ├── IMG.w-full.h-full.object-cover.object-[50%_35%]  (945×540, REAL HERO, idx=21)
        │   ├── H1  (title "LINK CLICK(2021)", 672×48, top=119)
        │   ├── metadata row (star 8, "Returning Series", "April 29, 2021", "5 Seasons")
        │   └── buttons (red Play "Continue S1:E1", "My List", mute, X)
        └── episode list (below hero, lazy-loaded on scroll)
            └── 18 × IMG w500 (500×750, displayed at 180×230)
```

**Hero selector**: idx=21 of `img.w-full.h-full.object-cover`, class `w-full h-full object-cover object-[50%_35%]`, rect [0,0,945,540]. **idx=0 is a 0×0 background image — MUST NOT be used.**

**YouTube trailer iframe** (appears ~20 s):
```
IFRAME: https://www.youtube.com/embed/HxoR_7jVBCM?autoplay=1&controls=0&mute=1&loop=1&playlist=HxoR_7jVBCM&modestbranding=1&showinfo=0&rel=0&enablejsapi=1&playsinline=1
Classes: absolute top-1/2 left-1/2 w-[120%] h-[120%] -translate-x-1/2 -translate-y-1/2
Size: 1114×626 at (−77,−25)
Parent chain: IFRAME → DIV(relative w-full h-full overflow-hidden) → DIV(absolute inset-0 w-full h-full bg-black) → DIV(hidden md:block relative w-full aspect-video)
```

**Stacking map**: hero z=auto (static); posters z=10; overlay z=100.

## 6. SCREENSHOT SIZE REFERENCE (for black detection)

| Size (bytes) | Meaning |
|---|---|
| 51,156 | **BLACK baseline** (hero black, UI visible) |
| 51,855 | css_hide/combined black (hero black, UI visible) |
| 29,976 | verify2/verify3 (LINK CLICK hero black, metadata only) |
| 10,595 | css_hide v1 (entire screen black) |
| 105,258 | **Home/bookmark screen** (NOT black — app went to home) |
| 29,389 | z=99/late z=99999 black |
| 82,971 | hero_both |
| 1,574,514 | proactive success (110 s) |
| 1,903,697 | css_rule_test (no black, ~110 s) |
| 1,970,932 | clean_tab_test (no black, 146 s) |
| 2,177,957 | hero_fixed_ui7 |
| 2,248,069 | reactive z=99999 success |
| 1,439,434 | hero_cdp_verify (CDP screenshot) |
| 1,443,024 | hero_ui_cdp (CDP screenshot) |
| 1,963,359 | flixer_home |
| 943,284 | Terminal List / Teach You a Lesson (wrong tab) |
| 55,959 | user_on_page (LINK CLICK, hero black, UI visible) |

## 7. DIAGNOSTIC LOG (latest session, 11,488 events)

**Event type counts:**
```
tmdb_allowlist_sync: 9552
key: 898
console: 143
frame_script_alive: 129
hero_downscaled: 101
click_listener_registered: 76
page_finished: 76
trailer_video_blocked: 74
page_started: 57
page_commit_visible: 56
process_memory: 55
tmdb_image_blocked: 30
event_burst: 25
poster_downscaled: 17
tap_dispatched: 16
tap_stack: 16
image_firewall: 15
flixer_image_firewall: 15
popup_guard_action: 13
process_start: 12
dark_mode_applied: 12
webview_config: 12
scripts_installed: 12
activity_resume: 12
popup_requested: 11
```

**Process memory** (host-process-not-dedicated-gpu, PSS KB):
```
8494, 6051, 6812, 6311, 6252, 7033, 7988, 7518, 6044, 6456
```
(Stable ~6–8.5 MB host process. GPU process memory not separately sampled.)

**Frame timing** (before/after hero fix):
- Before: avg 31.1 ms, max 283.6 ms
- After: avg 17.6 ms, max 100.1 ms (−43%)

## 8. CURRENT STATE (2026-10-26)

**Installed APK** (debug, PID 27736):
- Trailer intercept (blocks YouTube embeds)
- Hero downscale (`/t/p/original/` → `/t/p/w780/`)
- **Poster downscale** (`/t/p/w500/` → `/t/p/w185/`) — NEW, fires (17 events) but does NOT prevent black hero

**Source state** (`/home/user/BuccaneerClaude-2.0`):
- `MainActivity.kt`: modified (trailer intercept ~line 1075, hero downscale ~line 1135, poster downscale ~line 1170)
- `page_helpers.js`: Nova fix intact (SHA-256 `6fbc9faa3cbb3b3c4c824bd4246f6d2e57bcdb85490541e287a38408e8b395b0`) — **DO NOT OVERWRITE**
- `flixer_image_guard.js`: TMDB firewall, virtualizes 657/665 images
- `flixer_poster_guard.js`: hides hover preview cards
- `flixer_hero_guard.js`: DELETED
- `flixer_card_guard.js`: deleted/reverted

**Git**: baseline `25737ac`. No new commits this session.

**What the user confirmed (2026-10-26):**
- "im on the page" — user navigated to LINK CLICK detail page
- Screenshot (55,959 B): LINK CLICK title/metadata/buttons visible, **hero BLACK**
- "scrolling down the episode list makes the crash happen faster"
- "clicking episodes restarted it" (app crash on episode click)
- "No, and clicking episodes restarted it. i need to see some progress" (poster downscale didn't help)

## 9. OPEN QUESTIONS / NEXT STEPS

1. **Why does the hero go black even with w500→w185?** The poster downscale fires (17 events) but the hero still goes black. Episode posters are NOT the sole cause. What else is using GPU memory?
   - The giant BODY layer (1920×25468, ~47 MP, tiled) is the biggest consumer
   - The 2 YouTube trailer iframes (1114×626, video) add GPU memory
   - Other images (657 virtualized, but some may be in-view)
   - **Next**: measure GPU memory per component. Try blocking the YouTube trailer iframes entirely (not just hiding them). Try reducing the body layer size.

2. **Why does clicking an episode crash the app?** New crash signature (KEGLReleaseRenderTarget). Is it the same GPU memory pressure, or a different bug?
   - **Next**: check diagnostic log for crash events. Try clicking an episode with the trailer blocked.

3. **Tab mismatch** — how to reliably verify the fix on the correct tab?
   - **Next**: find a way to make the CDP-connected tab the foreground tab. Or use the user as the verification oracle (they're on the page).

4. **React resets inline styles** — how to make the UI visible without `position:fixed`?
   - The hero `position:fixed z=99999` works (prevents black) but covers UI
   - React resets UI inline styles on re-render
   - CSS rules with `!important` held ~110 s, then page reloaded
   - **Next**: try a MutationObserver to re-apply the CSS rule when React resets it. Or find a way to make the UI visible without `position:fixed`.

5. **GPT relayed analyses** (from earlier sessions, in `docs/FLIXER_GPT_HANDOFF.md`):
   - Raster/texture diagnosis
   - `position:fixed` breakthrough
   - Image guard churn + poster guard
   - Matrix plan (document-start injection, 4 variants)
   - Timer pattern + trailer hypothesis
   - CSS hide from document start
   - Loose ends (ancestors, CDP vs TV)
   - **DO NOT build workaround yet** (superseded — raster-loss branch confirmed)
   - If React state swap confirmed: neutralize video ID `HxoR_7jVBCM` in `shouldInterceptRequest`
   - If raster loss confirmed: fixed-hero workaround with lower part faded so UI shows through

## 10. CONSTRAINTS & RULES

- **NO software rendering** (user explicitly forbade)
- **NO full-DOM walks** (too slow, triggers stall)
- **Read full docs before theorizing**
- **Data-backed only** — no speculation
- **Don't erase Nova fix** (SHA-256 `6fbc9faa3cbb3b3c4c824bd4246f6d2e57bcdb85490541e287a38408e8b395b0`)
- **Don't build from `5ec354f`**
- **One change at a time** — one hypothesis per install cycle
- **Never uninstall the app without asking** (wipes bookmarks/data)
- **Preserve all prior fixes** — user tracks regressions across sessions
- **Verify, don't guess** — reproduce 3× before claiming success
- **Keep a log** — `docs/FLIXER_ATTEMPTS_LOG.md`, check every turn, don't go in circles
- **User is hands-on** — navigates the remote herself, reports tersely ("crashed")
- **User expects**: no excuses, no theorizing, find the exact line, decompile/diff last known-working build before theorizing, direct no-filler, fix as one clean rule set (not patch-on-patch gates), NEVER suggest software rendering, always base suggestions on data

## 11. FILES

| Path | What |
|---|---|
| `/home/user/BuccaneerClaude-2.0/app/src/main/java/com/buccaneer/browser/MainActivity.kt` | Main code (trailer intercept, hero downscale, poster downscale) |
| `/home/user/BuccaneerClaude-2.0/app/src/main/assets/page_helpers.js` | Nova fix (DO NOT OVERWRITE) |
| `/home/user/BuccaneerClaude-2.0/app/src/main/assets/flixer_image_guard.js` | TMDB firewall, virtualizes images |
| `/home/user/BuccaneerClaude-2.0/app/src/main/assets/flixer_poster_guard.js` | Hides hover preview cards |
| `/home/user/BuccaneerClaude-2.0/docs/FLIXER_ATTEMPTS_LOG.md` | Attempt log (through #31, many pending) |
| `/home/user/BuccaneerClaude-2.0/docs/FLIXER_GPT_HANDOFF.md` | GPT handoff (v2) |
| `/home/user/BuccaneerClaude-2.0/docs/FLIXER_GPT_HANDOFF_v1.md` | GPT handoff (v1) |
| `/home/user/BuccaneerClaude-2.0/docs/FLIXER_CRASH_HANDOFF.md` | Crash handoff |
| `/home/user/BuccaneerClaude-2.0/docs/PROGRAM_REVIEW.md` | Master reference (MUST READ before any Buccaneer work) |
| `/home/user/BuccaneerClaude-2.0/tests/check-protected-fixes.py` | Regression gate |
| `/home/user/.hermes/cache/scratch/cdp.py` | CDP helper class |
| `/home/user/.hermes/cache/scratch/*.py` | Test scripts (see table 4.3) |
| `/tmp/diag_session.jsonl` | Latest diagnostic log (11,488 events) |

## 12. SCRATCH SCRIPTS (in `/home/user/.hermes/cache/scratch/`)

**Newest first:**
- `css_rule_test.py` — CSS rule for UI + hero inline fix (held ~110 s, then page reloaded)
- `clean_tab_test.py` — Fresh single-tab test, adb screencap (H1 confirmed, no black 146 s, but wrong tab)
- `hero_ui_cdp.py` — Hero + UI leaves, CDP verification (no black 170 s, but wrong tab)
- `hero_cdp_verify.py` — Hero-only, CDP verification (no black 170 s, but wrong tab)
- `hero_fixed_ui8.py` — UI leaves fixed (false positive, then black t=27.4 s)
- `hero_fixed_ui7.py` — UI ancestor fixed (no black 144 s, UI not visible)
- `hero_fixed_ui6.py` — Hero img + hero container (container not found)
- `hero_fixed_ui5.py` — Hero container fixed (black t=21.8 s)
- `hero_fixed_ui4.py` — Modal card fixed (black t=21.7 s)
- `hero_fixed_ui3.py` — Hero fixed + detail overlay transparent (UI missing)
- `hero_fixed_ui2.py` — Wrong overlay (Share Flixer modal)
- `hero_fixed_ui.py` — Hero fixed + broad UI fixed (UI not visible)
- `hero_connected2.py` — **DEFINITIVE raster-loss result**
- `dom_card_inspect.py`, `dom_ui_inspect.py`, `dom_inspect.py` — DOM structure
- `hero_proactive.py` — **SUCCESS** (hero position:fixed, 110 s+ stable)
- `matrix.py` — 4-variant A/B
- `combined_test.py` — iframe + bg hidden (DEFINITIVE)
- `css_hide.py` (v1–v3) — CSS hide trailer
- `media_dump.py` — **CONFIRMED** 2 YouTube iframes
- `layer_dump.py`, `layer_events*.py` — Layer tree
- `cdp.py` — CDP helper

## 13. CDP QUIRKS

- Page target URL can be `""` (app on home/bookmark screen)
- `Page.getLayerTree` / `LayerTree.getLayerTree` **unavailable** — use `LayerTree.layerTreeDidChange` (flat `layers` list)
- `captureScreenshot` can return "Internal error" or capture the WRONG tab
- `cdp.py`: `send(method, params=None, timeout=None)` — params must be a dict; `eval(expression, contextId=None, awaitPromise=False, timeout=None)`
- CDP connects to ONE tab, but the app has MULTIPLE tabs (tab mismatch)
- After app restart, CDP setup times out repeatedly (page target URL empty) — wait longer, navigate via D-pad first

## 14. SESSION RECOVERY

If you need detail from earlier sessions:
```
session_search(query='<keywords>', session_id='20261025_152321_f36116f2')
```

**Key anchors**: `hero_connected2`, `tab mismatch`, `raster loss`, `w500`, `poster_downscaled`, `position:fixed`, `KEGLReleaseRenderTarget`
