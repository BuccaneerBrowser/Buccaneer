/*
 * TMDB image firewall — JS half (the poster-grid site + the lazy-load poster site).
 *
 * Registered as a DOCUMENT-START script in MainActivity.installScripts()
 * (WebViewCompat.addDocumentStartJavaScript, all origins). It must run in every
 * frame BEFORE the page's scripts append the TMDB poster grids, so its allowlist
 * sync fires while the intro burst is happening. (An earlier version was injected
 * only from onPageCommitVisible via evaluateJavascript — too late; sync() saw
 * count:0 because images weren't laid out yet, the allowlist stayed empty, and
 * the full burst still hit the GPU → SIGSEGV in KEGLReleaseRenderTarget.)
 *
 * Chromium 118 on API 25: NO `??` or `?.` operators.
 *
 * Purpose: poster-grid sites (the poster-grid site's ~180-poster intro burst; the lazy-load poster site's lazy
 * grid) load many TMDB poster images. Each decoded image becomes a GPU texture;
 * on the PowerVR GPU that load crashes the renderer (SIGSEGV in
 * KEGLReleaseRenderTarget). Two mechanisms cut the load:
 *   1. VIRTUALIZE: strip `src` from off-screen TMDB posters so the browser never
 *      requests/decodes them; restore `src` when they scroll into view. This is
 *      the primary fix — it stops the request/decode/composite work entirely.
 *   2. 1x1 FIREWALL: the native layer (shouldInterceptRequest) serves a 1x1
 *      transparent pixel for any image.tmdb.org URL NOT in the allowlist, as a
 *      backstop for anything that slips past virtualization.
 * This script keeps the allowlist in sync with the set of TMDB images that are
 * actually visible (within a pre-load margin), so only on-screen posters get
 * real textures.
 *
 * VERIFIED (Oct 7 2026, the poster-grid site): the burst now produces ~125
 * tmdb_image_blocked events and the allowlist climbs 0→10→1 as posters scroll
 * into view; no crash. Side effect: the poster-grid site's own "Emergency intro bypass"
 * fires because the 1x1 posters never finish the intro animation — harmless.
 *
 * Must never throw, never block page load, and be safe to re-inject.
 */
(function () {
  'use strict';

  // ---- Intro skip (PowerVR crash fix) -------------------------------------------
  // the poster-grid site's intro animation is a heavy GSAP/GPU workload. On the Fire TV's
  // PowerVR GPU it crashes the renderer (SIGSEGV in KEGLReleaseRenderTarget)
  // ~14-17s after load. The poster-grid site skips the intro when sessionStorage has
  // "hasShownIntro" set. We set it at document start — BEFORE the poster-grid site's own JS
  // reads it — so the intro never runs and the poster-grid site loads fast like a repeat visit.
  // Must run on every poster-grid site document start (sessionStorage resets when the
  // process dies), so this is placed BEFORE the idempotency guard below.
  try {
    var __h = (location.host || '').toLowerCase();
    if (/(^|\.)flixer\.(gd|su)$/.test(__h)) {
      sessionStorage.setItem('hasShownIntro', 'true');
    }
  } catch (e) { /* never throw */ }

  // Idempotency: this script is re-evaluated on every poster-grid site page commit.
  if (window.__flixerImgGuard) { return; }
  window.__flixerImgGuard = true;

  try {
    // Only act on guarded poster-grid hosts: the poster-grid site (the poster-grid site.gd / *.the poster-grid site.gd /
    // the poster-grid site.su / *.the poster-grid site.su) and the lazy-load poster site (the lazy-load poster site.pk / *.the lazy-load poster site.pk).
    // (a poster site was removed: its in-view posters don't match the allowlist,
    // so the native 1x1 firewall blanked the home-page grid.)
    var host = (location.host || '').toLowerCase();
    if (!/(^|\.)flixer\.(gd|su)$|(^|\.)cinejoy\.pk$/.test(host)) { return; }

    var MAX = 24;            // cap on allowlist size (most-recently-visible first)
    var ROOT_MARGIN = '200px'; // pre-load band around the viewport
    var THRESHOLD = 0;        // fire as soon as any pixel is visible

    // Currently-visible TMDB image URLs, most-recently-visible first.
    var visible = [];
    var io = null;
    var syncing = false;
    var virtualized = 0;      // count of posters whose src was stripped

    function isTmdbImg(img) {
      if (!img || img.tagName !== 'IMG') { return false; }
      var src = img.currentSrc || img.src || '';
      if (!src) { return false; }
      var h = '';
      try { h = new URL(src, location.href).host.toLowerCase(); }
      catch (e) { return false; }
      return h === 'image.tmdb.org' || h.indexOf('.tmdb.org') === h.length - 9;
    }

    // Virtualization: strip src from off-screen TMDB posters so the browser
    // never requests/decodes them. Restores src when they scroll into view.
    // This is the real fix for the "the poster-grid site after other sites" crash: the 1x1
    // firewall reduced GPU texture load but the request/decode/composite for
    // all 182 images still happened. With prior video GPU state resident,
    // that residual load pushed the PowerVR driver over the edge.
    function inViewport(img, margin) {
      try {
        var m = margin || 0;
        var r = img.getBoundingClientRect();
        var vh = (window.innerHeight || document.documentElement.clientHeight) + m;
        var vw = (window.innerWidth || document.documentElement.clientWidth) + m;
        // If the image has zero dimensions (1x1 placeholder or not yet laid out),
        // check the parent container instead — the card may be in the viewport
        // even though the image itself is collapsed.
        if (r.width <= 0 || r.height <= 0) {
          var p = img.parentElement;
          var depth = 0;
          while (p && depth < 6) {
            var pr = p.getBoundingClientRect();
            if (pr.width > 0 && pr.height > 0) {
              r = pr;
              break;
            }
            p = p.parentElement;
            depth++;
          }
          if (r.width <= 0 || r.height <= 0) { return false; }
        }
        return r.bottom > -m && r.right > -m &&
          r.top < vh && r.left < vw;
      } catch (e) { return false; }
    }

    function virtualize() {
      try {
        var imgs = document.querySelectorAll('img');
        for (var i = 0; i < imgs.length; i++) {
          var img = imgs[i];
          if (!img || img.tagName !== 'IMG') { continue; }
          // Already virtualized: src is empty, original stored on the element.
          if (img.__flixerSrc) {
            if (inViewport(img, 200)) {
              img.src = img.__flixerSrc;
              img.__flixerSrc = null;
              virtualized--;
            }
            continue;
          }
          if (!isTmdbImg(img)) { continue; }
          var src = img.getAttribute('src') || '';
          if (!src) { continue; }
          if (!inViewport(img, 200)) {
            img.__flixerSrc = src;
            img.src = '';
            virtualized++;
          }
        }
      } catch (e) { /* never throw */ }
    }

    function sync() {
      if (syncing) { return; }
      syncing = true;
      try {
        // First, virtualize: strip src from off-screen TMDB posters (so the
        // browser never requests/decodes them) and restore src for the ones that
        // have scrolled into the pre-load band. Must run before the allowlist is
        // rebuilt so restored images carry their real src when we read them.
        virtualize();
        // Rebuild the list from the live DOM so it always reflects reality.
        var imgs = document.querySelectorAll('img');
        var list = [];
        var seen = {};
        for (var i = 0; i < imgs.length; i++) {
          var img = imgs[i];
          if (!isTmdbImg(img)) { continue; }
          if (!inViewport(img, 200)) { continue; }
          var src = img.currentSrc || img.src || '';
          if (!src || seen[src]) { continue; }
          seen[src] = true;
          list.push(src);
          // If this image was loaded as a 1x1 placeholder (blocked by the native
          // firewall before it entered the allowlist), force a re-request with a
          // cache-buster. The browser's HTTP cache holds the 1x1 response, so a
          // plain src reset won't trigger a network fetch. The cache-busted URL
          // is added to the allowlist so the firewall lets it through.
          if (img.naturalWidth === 1 && img.naturalHeight === 1) {
            var realSrc = img.getAttribute('src') || src;
            var busted = realSrc + (realSrc.indexOf('?') !== -1 ? '&' : '?') + 'r=' + Date.now();
            img.src = busted;
            list.push(busted);
            seen[busted] = true;
          }
        }
        // Mark off-screen images as blocked so we can re-request them later.
        for (var j = 0; j < imgs.length; j++) {
          var img2 = imgs[j];
          if (!isTmdbImg(img2)) { continue; }
          if (!inViewport(img2, 200)) {
            img2.__flixerBlocked = true;
          }
        }
        // Most-recently-visible first: DOM order is a good proxy (newer posters
        // are appended later). Cap to MAX.
        if (list.length > MAX) { list = list.slice(list.length - MAX); }
        visible = list;
        try {
          window.Buccaneer.tmdbAllowlist(JSON.stringify(list));
        } catch (e) { /* bridge not ready yet; will sync again on next change */ }
      } catch (e) { /* never throw */ }
      syncing = false;
    }

    function onIntersect(entries) {
      try {
        for (var i = 0; i < entries.length; i++) {
          var e = entries[i];
          if (e.isIntersecting) {
            var img = e.target;
            if (isTmdbImg(img)) {
              var src = img.currentSrc || img.src || '';
              if (src) {
                var idx = visible.indexOf(src);
                if (idx !== -1) { visible.splice(idx, 1); }
                visible.unshift(src); // most-recently-visible first
                if (visible.length > MAX) { visible.length = MAX; }
              }
            }
          }
        }
        sync();
      } catch (e) { /* never throw */ }
    }

    function observeAll() {
      try {
        if (!io) {
          io = new IntersectionObserver(onIntersect, {
            root: null,
            rootMargin: ROOT_MARGIN,
            threshold: THRESHOLD
          });
        }
        var imgs = document.querySelectorAll('img');
        for (var i = 0; i < imgs.length; i++) {
          if (isTmdbImg(imgs[i])) { io.observe(imgs[i]); }
        }
      } catch (e) { /* never throw */ }
    }

    // Watch for images added/removed after injection (the poster-grid site appends posters
    // lazily). Re-observe new TMDB images and re-sync.
    function watchDom() {
      try {
        var mo = new MutationObserver(function (mutations) {
          try {
            var changed = false;
            for (var i = 0; i < mutations.length; i++) {
              var m = mutations[i];
              var added = m.addedNodes;
              for (var j = 0; j < added.length; j++) {
                var n = added[j];
                if (n.nodeType !== 1) { continue; }
                if (isTmdbImg(n)) { io.observe(n); changed = true; }
                var inner = n.querySelectorAll ? n.querySelectorAll('img') : null;
                if (inner) {
                  for (var k = 0; k < inner.length; k++) {
                    if (isTmdbImg(inner[k])) { io.observe(inner[k]); changed = true; }
                  }
                }
              }
              if (m.removedNodes.length > 0) { changed = true; }
            }
            if (changed) { sync(); }
          } catch (e) { /* never throw */ }
        });
        mo.observe(document.documentElement, { childList: true, subtree: true });
      } catch (e) { /* never throw */ }
    }

    // Kick off: observe existing images, then start watching for new ones.
    observeAll();
    watchDom();
    sync();

    // A few deferred syncs to catch images that appear right after commit
    // (before the MutationObserver settles) and to re-assert the allowlist.
    try {
      setTimeout(sync, 250);
      setTimeout(sync, 1000);
      setTimeout(sync, 3000);
    } catch (e) { /* never throw */ }

    // Periodic safety net: the IntersectionObserver may not re-fire for an
    // image that was already intersecting when its src got restored by
    // virtualize(). A slow sync catches those and re-asserts the allowlist.
    try {
      setInterval(sync, 2000);
    } catch (e) { /* never throw */ }
  } catch (e) {
    // Absolute last resort: never let this script break the page.
  }
})();
