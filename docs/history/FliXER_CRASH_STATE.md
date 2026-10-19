# Flixer Crash - Full State (Oct 7, 2026)

## The Problem
Flixer crashes with SIGSEGV in KEGLReleaseRenderTarget (PowerVR GPU driver) ~9-10s after load, during the 182-image intro burst.

## Root Cause
The 182-image TMDB poster burst at t=6s overwhelms the PowerVR GE8300 GPU on the AFTMM (1.28GB RAM, 32-bit). GPU memory drops from ~500MB to ~290MB during the burst, then the driver crashes in KEGLReleaseRenderTarget.

## What Works
- Software rendering (LAYER_TYPE_SOFTWARE) → flixer loads fine, no crash (proves page is fine, crash is GPU-specific)
- UA change to Windows Chrome (helps with bot detection on other sites)

## What Does NOT Work (all tested, all still crash)
- isVidcoreFrame gate alone → crashes
- Skipping overlay scanner + all instrumentation on flixer → 1/5 passed (not reliable)
- Disabling all scripts on flixer → crashes
- LAYER_TYPE_HARDWARE explicit → crashes
- Lazy-loading images (loading="lazy") → crashes
- Removing intro elements → crashes, made it worse
- Force-dark OFF on flixer → crashes
- about:blank GPU defrag before flixer → crashes
- Auto-restart on GPU crash → recovers but doesn't prevent crash
- 1.4 zip ("final-audited-stability") → crashes
- Cap detection + instrumentation gating → crashed the app entirely

## Key Facts
- Device: Fire TV AFTMM, Android 7.1.2, PowerVR ROGUE GPU (IMG GE8300), 1.28GB RAM, 32-bit
- WebView: Chromium 118 (Amazon WebView v118)
- Flixer intro: 84 divs/2 images at t=0-4s → 631 divs/182 images/540 animated elements at t=6s
- Crash timing: ~9-10s after page_started, during intro animation
- Crash thread: Chrome_InProcGp OR RenderThread (alternates)
- javaFreeHeapMb: 0 at every flixer page_started (JVM heap exhausted)
- Flixer image host: image.tmdb.org (TMDB poster images)

## Current Source State
- Branch: master
- HEAD: 59b2f05 (flixer instrumentation gating — unverified, not a fix)
- Working tree: clean
- Diagnostics: ON

## Next Approach: TMDB Image Virtualization
The 182-image burst is the trigger. Fix: intercept TMDB image requests at the native level (shouldInterceptRequest) and only allow a small window of visible images to load at full size. Everything else gets a 1x1 transparent placeholder.

Architecture:
1. Native layer (MainActivity.kt): shouldInterceptRequest() checks if page is flixer + request is image.tmdb.org. If URL is in the allowlist → return null (normal load). Otherwise → return 1x1 transparent pixel WebResourceResponse.
2. JS layer (flixer_image_guard.js): IntersectionObserver tracks which TMDB images are near the viewport. Loads real images (at appropriate TMDB size: w185/w342) for visible ones, replaces offscreen ones with transparent placeholder. Syncs allowlist to native layer via bridge.
3. Max ~18 live images at any time.

## Files
- Source: <SOURCE_DIR>
- Key files: MainActivity.kt, popup_guard.js, page_helpers.js, DiagnosticLog.kt
- New file: flixer_image_guard.js (to be created)
- Bookmarks: 6 sites (Fmhy, Cinejoy, Movy, Coreflix, MovieBite, Flixer)
