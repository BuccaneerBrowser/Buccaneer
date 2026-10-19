# BuccaneerClaude — Flixer Crash Handoff (Oct 7 2026)

## What this is
Android TV browser (`com.buccaneer.browser`) at `<repo-root>`.
Goal: stop the **Flixer crash** — a SIGSEGV in `KEGLReleaseRenderTarget`
(PowerVR GPU, `libGLESv2_POWERVR_ROGUE.so`) that fires ~9s after flixer.gd
loads, during the 182-image TMDB poster intro burst.

## Device
Fire TV (AFTMM, Android 7.1.2, API 25, Chromium 118 WebView) at
`<device-ip>:5555`. adb works.

## Tool rules (CRITICAL — follow exactly)
- Load the skill first: `skill_view(name='buccaneer-patch')` and follow it.
- Edit files with `patch`, `write_file`, `read_file`, `search_files` ONLY.
- Do NOT use terminal to read/write/edit source files.
- Terminal ONLY for: `gradle build`, `adb install`, `adb logcat`,
  `adb shell run-as`.
- Build: `source <android-env>/env.sh && cd <repo-root> && gradle assembleDebug`
- Install: `adb -s <device-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
- Diagnostics: `adb shell "run-as com.buccaneer.browser cat files/diagnostic_session.jsonl"`
- Crash log: `adb -s <device-ip>:5555 shell "logcat -d -b crash | tail -40"`
- **Never uninstall** (wipes bookmarks). Don't touch coreflix button code,
  dark mode, or CSS filters. Change one thing, test, report.

## Tools that work well
- `browser_exec` drives a real browser — useful for inspecting flixer's live
  DOM. Flixer's grid is in the **top frame** (0 iframes), 182 `<img>` tags,
  181 on `image.tmdb.org`.
- `execute_code` / `terminal` for parsing the JSONL diagnostic log (grep for
  `tmdb_image_blocked`, `tmdb_allowlist_sync`, `flixer_image_firewall`).
- `patch` works fine on MainActivity.kt in this session (the skill's warning
  that it fails is stale — it worked).

## Environment notes
- Local LLM is EXL3 (qwen3.8-27b) — drops streams at ~100k+ token context.
  Keep prompts small; don't dump big files/logs into context.
- WebView JS is Chromium 118 on API 25 — NO `??` or `?.` in injected JS.
- fmhy never fires `onPageFinished` reliably — use `onPageCommitVisible`.

## What's already done (do NOT redo)
1. `MainActivity.kt` has the native TMDB firewall:
   - `flixerImageGuardJs` field (line ~128), loaded from asset (line ~229).
   - `shouldInterceptRequest()` (line ~555) returns a 1x1 transparent PNG for
     `image.tmdb.org` URLs NOT in `tmdbAllowlist` (only when `onFlixerPage`).
   - `@JavascriptInterface fun tmdbAllowlist(urls)` (line ~805) — JS pushes
     the visible-image allowlist; logged as `tmdb_allowlist_sync`.
   - `onPageCommitVisible()` (line ~503) detects flixer host, sets
     `onFlixerPage`, logs `flixer_image_firewall active=true/false`.
2. `app/src/main/assets/flixer_image_guard.js` — IntersectionObserver-based
   guard. Tracks visible TMDB images, syncs allowlist (capped at 24) to native
   via `window.Buccaneer.tmdbAllowlist(JSON.stringify([...]))`. Idempotent
   (`window.__flixerImgGuard`), no `??`/`?.`, all try/catch.
3. **THE FIX (uncommitted):** registered the guard as a DOCUMENT-START script
   in `installScripts()` (line ~403) via
   `WebViewCompat.addDocumentStartJavaScript(webView, flixerImageGuardJs, origins)`.
   This was the missing piece — injecting only from `onPageCommitVisible` ran
   too late (sync saw `count:0`, allowlist stayed empty, full burst hit GPU).
   At document start it runs in every frame before page scripts, so it catches
   the burst as it happens.

## Current git state
- `59b2f05` HEAD. `MainActivity.kt` modified (M), `flixer_image_guard.js`
  untracked (??). The document-start fix is NOT committed.
- Device runs the 13:49 build = the document-start fix (versionName
  1.2-diagnostic).

## THE OPEN PROBLEM (where to pick up)
The document-start fix **worked for a clean single flixer load** (verified
14:46: 125 `tmdb_image_blocked`, allowlist climbed 0→10→1, no crash).

**But the crash came back (14:58)** when flixer was loaded AFTER other sites.
Repro: played something in coreflix (video got stuck), backed out,
then played cinejoy, then started flixer → **crashed every time after**.

Crash signature is identical: SIGSEGV, fault addr 0x4,
`KEGLReleaseRenderTarget` → `glBindFramebuffer`, thread alternates
`RenderThread` / `Chrome_InProcGp`.

**Key insight:** the 1x1-pixel firewall reduces but does NOT eliminate the
GPU load. When the GPU already has textures from prior video/media (coreflix,
cinejoy), the flixer burst pushes it over the edge. The `tmdb_image_blocked`
events still fire (66 in the crash session) — the firewall is working — but
the *request/decode/composite* for all 182 images still happens, and the
pre-existing GPU state from prior sites tips it into the crash.

**Diagnosis to confirm:** the crash is a GPU resource-exhaustion / render-target
bug in the PowerVR driver, triggered by the combination of (a) prior
video/media GPU state + (b) the flixer poster burst. The 1x1 approach alone is
insufficient.

## Next steps (in order)
1. **Confirm the repro is "flixer after other sites", not "flixer alone".**
   (a) open flixer cold (fresh app start, nothing else) → does it
   crash? (b) play coreflix/cinejoy first, then flixer → does it crash? This
   isolates whether prior GPU state is the trigger.
2. If prior-site state is the trigger, options (pick ONE, test, report):
   - **Prevent the `<img>` elements from loading at all** for off-screen
     posters (strip `src` / set `loading=lazy` / `display:none` until visible)
     rather than just serving 1x1 pixels — this stops the request/decode, not
     just the texture.
   - **Lower the allowlist cap** (currently 24) to reduce concurrent real
     textures.
   - **Force a GPU/render reset** when leaving a video site before flixer
     (e.g. `webView.invalidate()` or a brief off-screen nudge) — unverified,
     risky.
3. Do NOT enable software rendering or CSS filters
   (`filter:invert` — known PowerVR crasher).
4. After any change: build, install, test the exact repro, pull
   diagnostics + crash log, report.

## Constraints (repeat every session)
- Never uninstall (wipes bookmarks).
- Don't touch coreflix button code, dark mode, or CSS filters.
- Change one thing, test, report.
- Preserve all prior fixes across sessions.
- Direct, no-filler communication.
