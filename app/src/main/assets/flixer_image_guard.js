/*
 * TMDB image firewall — JS half. DOCUMENT-START script (all origins; see
 * MainActivity.installScripts()). Must run before the page's scripts append
 * the poster grids: an earlier onPageCommitVisible injection was too late
 * (sync saw count:0, the full burst hit the GPU -> KEGLReleaseRenderTarget
 * SIGSEGV).
 *
 * Chromium 118 on API 25: NO `??` or `?.` operators.
 *
 * Two mechanisms cut the GPU texture load that crashes the PowerVR renderer
 * (SIGSEGV in KEGLReleaseRenderTarget):
 *   1. VIRTUALIZE: strip `src` from off-screen TMDB posters so the browser
 *      never requests/decodes them; restore when they scroll into view.
 *      Primary fix — stops the request/decode/composite work entirely.
 *   2. 1x1 FIREWALL: native shouldInterceptRequest serves a 1x1 pixel for
 *      any image.tmdb.org URL not in the allowlist (backstop).
 * The allowlist tracks the actually-visible TMDB images (with a pre-load
 * margin), so only on-screen posters get real textures.
 *
 * VERIFIED (Oct 7 2026): the burst now yields ~125 tmdb_image_blocked events
 * and the allowlist climbs 0->10->1 as posters scroll in; no crash. Side
 * effect: the site's "Emergency intro bypass" fires (1x1 posters never finish
 * the intro animation) — harmless.
 *
 * Must never throw, never block page load, safe to re-inject.
 */
(function () {
  'use strict';

  // ---- Intro skip (PowerVR crash fix) -------------------------------------------
  // The site's intro animation is a heavy GSAP/GPU workload that crashes the
  // PowerVR renderer ~14-17s after load. The site skips it when sessionStorage
  // "hasShownIntro" is set; set it at document start, before the site's own JS
  // reads it. Must run on every document start (session storage resets when
  // the process dies), so it sits BEFORE the idempotency guard below.
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
    // Guarded hosts only: flixer.gd / *.flixer.gd / flixer.su / *.flixer.su and
    // cinejoy.pk / *.cinejoy.pk. (A third poster site was removed: its in-view
    // posters didn't match the allowlist, so the 1x1 firewall blanked its grid.)
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
    var lastCpLog = 0;        // compositor_proxy log throttle (>=2s between logs)

    // ---- Diagnostics: per-cycle timing + error counters -------------------
    var diagVirtMs = 0;       // ms spent in virtualize() last cycle
    var diagCardMs = 0;       // ms spent in virtualizeCards() last cycle
    var diagSyncMs = 0;       // ms spent in sync() total last cycle
    var diagVirtImgs = 0;     // images examined in virtualize() last cycle
    var diagVirtStripped = 0; // images newly stripped last cycle
    var diagVirtRestored = 0; // images restored last cycle
    var diagCardProcs = 0;    // cards examined in virtualizeCards() last cycle
    var diagCardVirt = 0;     // cards newly virtualized last cycle
    var diagCardRest = 0;     // cards restored last cycle
    var diagVirtErrors = 0;   // total exceptions in virtualize() (lifetime)
    var diagCardErrors = 0;   // total exceptions in virtualizeCards() (lifetime)
    var diagSyncCount = 0;    // total sync() calls (lifetime)
    var diagScrollY = 0;      // last known scrollY
    var diagScrollH = 0;      // last known document height

    // ---- Diagnostics: cumulative (non-resetting) counters --------------------
    // Per-cycle counters reset every cycle and logging is throttled >=2s, so a
    // logged zero can hide unsampled work; cumulative counters recover it up to
    // the last emitted sample (work between the last sample and a crash is
    // still not captured). Document/frame-local: reset on full reload.
    var cumStripped = 0;    // total images stripped (lifetime, this document)
    var cumRestored = 0;    // total images restored (lifetime, this document)
    var cumCardVirt = 0;    // total cards virtualized (lifetime, this document)
    var cumCardRest = 0;    // total cards restored (lifetime, this document)

    // ---- Card virtualization (PowerVR crash fix, detail/episode page) --------
    // Detail-page cards are overflow:hidden composited clipping layers — each a
    // GPU render target; too many crash the PowerVR renderer (KEGLReleaseRenderTarget
    // during glDrawArrays). Off-screen cards get content-visibility: hidden +
    // contain-intrinsic-size (not display:none: the box stays in layout via its
    // intrinsic size, so the card's rect stays valid and restore-on-scroll works
    // without reflow), cutting the number of live composited layers.
    var CARD_MARGIN = 600;   // pre-load band around the viewport (px)
    var CARD_BATCH = 12;     // max cards to restore per sync (avoid a burst)
    var cardVirtualized = 0; // count of cards currently virtualized
    var cardCache = null;    // cached list of card containers
    var cardCacheTime = 0;   // when the card cache was built (ms)

    // ---- Episode-row virtualization (PowerVR pressure fix, info-page overlay) ----
    // The info-page overlay's horizontal episode row holds ~20 simultaneously-decoded
    // TMDB posters; that tile-memory pressure is the SIGSEGV trigger. We virtualize
    // the row's off-screen posters (strip/restore) with a churn budget, while all
    // other overlay images (hero, backdrops) keep the v8 restore-only behavior.
    var epRowCache = null;   // { overlay:<el|null>, rows:[...], time:ms, sig:str }
    var bkOvSeq = 0;         // monotonic overlay-id sequence (per document)
    var bkRowSeq = 0;        // monotonic row-id sequence (per document)
    var epGen = 0;           // row-generation counter (per document)
    var epRowState = null;   // { ovId, rowId, sig, firstDetectedAt, lastScrollAt,
                             //   lastScrollLeft, postSettleMax, qualifying }
    var epHiWaterDoc = 0;    // max epDecoded since document load (EVIDENCE ONLY)
    var epStripTimes = [];   // rolling window of episode-row strip timestamps
    var epLastWasHit = false; // set by findEpRows(): true = cache-hit path, false = rescan
    var epPrevDetected = 0;   // epDetected from the previous compositor_proxy sample

    // ---- Flixer info-page overlay override (PowerVR crash fix) ----------------
    // While the info-page overlay (episode list, fixed inset-0 z-[100] composited
    // layer) is open, leave its elements alone: restore any already-virtualized
    // one, never strip/virtualize — strip/restore churn inside the composited
    // overlay is the suspected render-target release crash trigger. Top doc only.
    var overlayOpen = false; // last known overlay state (for guard_state transitions)

    function isTmdbImg(img) {
      if (!img || img.tagName !== 'IMG') { return false; }
      var src = img.currentSrc || img.src || '';
      if (!src) { return false; }
      var h = '';
      try { h = new URL(src, location.href).host.toLowerCase(); }
      catch (e) { return false; }
      return h === 'image.tmdb.org' || h.indexOf('.tmdb.org') === h.length - 9;
    }

    // A TMDB poster is live (src present, decoded GPU texture) or virtualized
    // (src stripped, original in __flixerSrc) — correlates the crash with live count.
    function isTmdbPoster(im) {
      if (!im || im.tagName !== 'IMG') { return false; }
      if (im.__flixerSrc) { return true; }   // already virtualized by the guard
      return isTmdbImg(im);
    }

    // Viewport test (both axes, with margin). Core of the virtualization fix:
    // the 1x1 firewall cut texture load but the request/decode/composite of all
    // ~182 posters still happened; with prior video GPU state resident that
    // residual load pushed the PowerVR driver over the edge.
    function inViewport(img, margin) {
      try {
        var m = margin || 0;
        var r = img.getBoundingClientRect();
        var vh = (window.innerHeight || document.documentElement.clientHeight) + m;
        var vw = (window.innerWidth || document.documentElement.clientWidth) + m;
        // Zero dimensions (1x1 placeholder or not yet laid out): use the parent
        // container's rect — the card may be in view even if the image is collapsed.
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

    // The Flixer info-page overlay (episode list, fixed inset-0 z-[100]) in the
    // TOP document; null when not on a Flixer host, not top, or closed. Cheap
    // querySelector, re-queried up to 3x per sync.
    function findInfoOverlay() {
      try {
        var h = (location.hostname || '').toLowerCase();
        var isFlixer = (h === 'flixer.gd' || h.indexOf('.flixer.gd') !== -1 ||
                        h === 'flixer.su' || h.indexOf('.flixer.su') !== -1);
        if (!isFlixer) { return null; }
        if (window !== window.top) { return null; } // top document only
        return document.querySelector('div.fixed.inset-0[class*="z-[100]"]');
      } catch (e) { return null; }
    }

    // ---- Episode-row virtualization: identity + signature helpers -------------
    // Monotonic per-element IDs (v13's tag+index+class id collided on distinct
    // elements); stable for the element's lifetime.
    function bkOvId(el) { if (!el) { return 'none'; }
      if (!el.__bkOvId) { bkOvSeq++; el.__bkOvId = 'ov' + bkOvSeq; }
      return el.__bkOvId; }

    function bkRowId(el) { if (!el) { return 'none'; }
      if (!el.__bkRowId) { bkRowSeq++; el.__bkRowId = 'row' + bkRowSeq; }
      return el.__bkRowId; }

    function epCanonSrc(img) {
      // Stable content identity, independent of the guard's own src mutations:
      //   strip:          img.__flixerSrc = current attribute; img.src = ''
      //   restore:        img.src = saved; img.__flixerSrc = null
      //   placeholder fix: img.src = original + ('?'|'&') + 'r=' + ts
      // Canonical = the LIVE src attribute when non-empty (the current content —
      // including a site replacement made while the image is stripped), else the
      // saved __flixerSrc; in both cases with the guard's own 'r=' cache-buster
      // param removed.
      var s = img.getAttribute('src') || '';
      if (!s) { s = img.__flixerSrc || ''; }
      var q = s.indexOf('?');
      if (q !== -1) {
        var parts = s.slice(q + 1).split('&');
        var keep = [];
        for (var i = 0; i < parts.length; i++) {
          if (parts[i].indexOf('r=') !== 0) { keep.push(parts[i]); }
        }
        s = s.slice(0, q) + (keep.length ? '?' + keep.join('&') : '');
      }
      return s;
    }

    function epSigOf(rows) {
      // EXACT content identity of the row's image set: per-row JSON arrays of
      // ordered canonical srcs. The generation gate compares this string with
      // === — NO hash in the gate. JSON.stringify makes the serialization
      // UNAMBIGUOUS (v25 V19-1): each src is quoted, so two different source
      // sets can never produce the same string. NO overlay div count (v17 G3):
      // unrelated overlay structure changes must NOT reset the generation.
      try {
        var sig = '';
        for (var i = 0; i < rows.length; i++) {
          var imgs = rows[i].querySelectorAll('img');
          var srcs = [];
          for (var j = 0; j < imgs.length; j++) {
            srcs.push(epCanonSrc(imgs[j]));
          }
          sig += JSON.stringify(srcs) + '#';
        }
        return sig;
      } catch (e) { return 'err'; }
    }

    function epSigHash(sig) {
      // Lossy 32-bit djb2 projection of the EXACT signature — telemetry only
      // (the epSig field), never the identity gate. Compact for the 2 MiB JSONL.
      var h = 5381;
      for (var c = 0; c < sig.length; c++) { h = ((h << 5) + h + sig.charCodeAt(c)) | 0; }
      return h;
    }

    function findEpRows() {
      // Cache hit iff ALL of: same overlay node; every cached row still contained
      // in the overlay; rows non-empty; epSigOf(rows) === cached sig; and every
      // cached row is STILL a horizontal scroll container (overflow-x auto/scroll
      // AND scrollWidth > clientWidth + 50). Miss => rescan the structural rule
      // (overflow-x auto/scroll, scrollWidth > clientWidth + 50, inside the info
      // overlay). Assign bkRowId(row) to every row on first sight.
      var overlay = findInfoOverlay();
      var now = Date.now();
      if (epRowCache && epRowCache.overlay === overlay && overlay &&
          epRowCache.rows.length > 0) {
        var ok = true;
        for (var i = 0; i < epRowCache.rows.length; i++) {
          var r = epRowCache.rows[i];
          if (!overlay.contains(r)) { ok = false; break; }
          var cs;
          try { cs = window.getComputedStyle(r); } catch (e) { ok = false; break; }
          if (cs.overflowX !== 'auto' && cs.overflowX !== 'scroll') { ok = false; break; }
          if (!(r.scrollWidth > r.clientWidth + 50)) { ok = false; break; }
        }
        if (ok && epSigOf(epRowCache.rows) === epRowCache.sig) {
          epLastWasHit = true;
          return epRowCache.rows;
        }
      }
      var rows = [];
      if (overlay) {
        var all = overlay.querySelectorAll('div');
        for (var j = 0; j < all.length; j++) {
          var el = all[j];
          var cs2;
          try { cs2 = window.getComputedStyle(el); } catch (e) { continue; }
          if (cs2.overflowX !== 'auto' && cs2.overflowX !== 'scroll') { continue; }
          if (!(el.scrollWidth > el.clientWidth + 50)) { continue; }
          bkRowId(el); // assign on first sight
          rows.push(el);
        }
      }
      epRowCache = { overlay: overlay, rows: rows, time: now, sig: epSigOf(rows) };
      epLastWasHit = false;
      return rows;
    }

    function isEpRowImg(img, rows) {
      // True iff img's nearest row ancestor (walking up) is one of the cached rows.
      if (!rows || rows.length === 0) { return false; }
      var p = img.parentElement;
      var depth = 0;
      while (p && depth < 10) {
        for (var i = 0; i < rows.length; i++) {
          if (p === rows[i]) { return true; }
        }
        p = p.parentElement;
        depth++;
      }
      return false;
    }

    function inRowViewport(img, row) {
      // "Visible" iff the common intersection of the image rect, the row's clip
      // rect, and the window rect is non-empty (image ∩ row ∩ window).
      try {
        var ir = img.getBoundingClientRect();
        var rr = row.getBoundingClientRect();
        if (ir.width <= 0 || ir.height <= 0) { return false; }
        var x1 = Math.max(ir.left, rr.left, 0);
        var y1 = Math.max(ir.top, rr.top, 0);
        var x2 = Math.min(ir.right, rr.right, window.innerWidth);
        var y2 = Math.min(ir.bottom, rr.bottom, window.innerHeight);
        return x2 > x1 && y2 > y1;
      } catch (e) { return false; }
    }

    function epStripAllowed() {
      // Churn budget: max 4 episode-row strips per rolling 2 s (boundary >= 2000
      // so the window slides exactly at 2 s boundaries). Module-level, shared
      // across ALL sync invocations (timer, MutationObserver, deferred kicks).
      var now = Date.now();
      while (epStripTimes.length > 0 && now - epStripTimes[0] >= 2000) { epStripTimes.shift(); }
      if (epStripTimes.length >= 4) { return false; }
      epStripTimes.push(now);
      return true;
    }

    function virtualize() {
      diagVirtImgs = 0; diagVirtStripped = 0; diagVirtRestored = 0;
      try {
        var overlay = findInfoOverlay();
        var epRows = findEpRows();
        var imgs = document.querySelectorAll('img');
        diagVirtImgs = imgs.length;
        for (var i = 0; i < imgs.length; i++) {
          var img = imgs[i];
          if (!img || img.tagName !== 'IMG') { continue; }
          // OVERLAY OVERRIDE: open overlay — non-episode-row images keep the v8
          // restore-only behavior (strip/restore churn in the composited overlay
          // is the suspected render-target release crash trigger); episode-row
          // images fall through to the normal strip/restore logic below.
          if (overlay && overlay.contains(img)) {
            if (!isEpRowImg(img, epRows)) {
              if (img.__flixerSrc) {
                img.src = img.__flixerSrc;
                img.__flixerSrc = null;
                virtualized--;
                diagVirtRestored++;
                cumRestored++;
              }
              continue;
            }
            // Episode-row image: fall through to normal strip/restore logic.
          }
          // Already virtualized: src is empty, original stored on the element.
          if (img.__flixerSrc) {
            if (inViewport(img, 200)) {
              var liveSrc = img.getAttribute('src') || '';
              if (liveSrc && isEpRowImg(img, epRows)) {
                // v25 B2: the site replaced the content while the image was
                // stripped (strip set src=''; the site then set src=B). Keep the
                // live src, drop the stale saved src. No write: the browser is
                // already loading B.
                img.__flixerSrc = null;
              } else {
                img.src = img.__flixerSrc;
                img.__flixerSrc = null;
              }
              virtualized--;
              diagVirtRestored++;
              cumRestored++;
            }
            continue;
          }
          if (!isTmdbImg(img)) { continue; }
          var src = img.getAttribute('src') || '';
          if (!src) { continue; }
          var isEp = isEpRowImg(img, epRows);
          // v25.2 hysteresis: episode-row images restore at a 200px margin but
          // only strip at a 500px margin. The 200-500px dead zone stops the
          // decode->strip->decode oscillation that mutates src repeatedly inside
          // the composited overlay; each mutation recomposites the layer and on
          // PowerVR Rogue a fast burst of those crashes glBindFramebuffer
          // (SIGSEGV fault addr 0x4, Chrome_InProcGp). Non-episode images keep
          // the 200px strip margin (main-page fast scroll is crash-free).
          var epStripMargin = isEp ? 500 : 200;
          if (!inViewport(img, epStripMargin)) {
            // Episode-row images: require TWO consecutive off-screen samples
            // (debounce; in-view resets the counter) AND the churn budget.
            if (isEp) {
              img.__epOffScreen = (img.__epOffScreen || 0) + 1;
              if (img.__epOffScreen < 2) { continue; }
              if (!epStripAllowed()) { continue; }
              // v25.1: never strip a pending episode-row image (naturalWidth===0).
              // Stripping src='' while the texture allocation is still in flight
              // cancels it mid-flight; on PowerVR Rogue that leaves the GPU in an
              // inconsistent state and crashes glBindFramebuffer (SIGSEGV fault
              // addr 0x4, Chrome_InProcGp) ~2s after a fast-scroll strip burst.
              // Let the image finish decoding first; it will be stripped on a
              // later sample once naturalWidth>1.
              if (img.naturalWidth === 0) { continue; }
            }
            img.__flixerSrc = src;
            img.src = '';
            virtualized++;
            diagVirtStripped++;
            cumStripped++;
          } else if (isEp && inViewport(img, 200)) {
            // In-view (within the 200px restore margin): reset the off-screen
            // debounce counter. The 200-500px dead zone leaves the counter
            // untouched, so an image there neither strips nor resets.
            img.__epOffScreen = 0;
          }
        }
      } catch (e) { diagVirtErrors++; }
    }

    // Card containers: overflow:hidden/clip elements in a list (3+ same-tag
    // siblings) — the composited clipping layers. Cached 5s (re-scan is expensive).
    function findCards() {
      var now = Date.now();
      if (cardCache && now - cardCacheTime < 5000) { return cardCache; }
      var cards = [];
      try {
        var all = document.querySelectorAll('div');
        for (var i = 0; i < all.length; i++) {
          var el = all[i];
          var cs = window.getComputedStyle(el);
          if (cs.overflow !== 'hidden' && cs.overflow !== 'clip') { continue; }
          var parent = el.parentElement;
          if (!parent) { continue; }
          var sameTag = 0;
          for (var j = 0; j < parent.children.length; j++) {
            if (parent.children[j].tagName === el.tagName) { sameTag++; }
          }
          if (sameTag < 3) { continue; }
          cards.push(el);
        }
      } catch (e) { /* never throw */ }
      cardCache = cards;
      cardCacheTime = now;
      return cards;
    }

    function cardInViewport(el, margin) {
      try {
        var m = margin || 0;
        var r = el.getBoundingClientRect();
        if (r.width <= 0 || r.height <= 0) { return false; }
        var vh = (window.innerHeight || 0) + m;
        var vw = (window.innerWidth || 0) + m;
        return r.bottom > -m && r.right > -m && r.top < vh && r.left < vw;
      } catch (e) { return false; }
    }

    // Virtualize off-screen cards, restore those in the pre-load band. Restores
    // are batched (CARD_BATCH per sync): a burst of render targets trips the
    // PowerVR driver race.
    function virtualizeCards() {
      diagCardProcs = 0; diagCardVirt = 0; diagCardRest = 0;
      try {
        var overlay = findInfoOverlay();
        var cards = findCards();
        if (cards.length === 0) { return; }
        diagCardProcs = cards.length;
        // OVERLAY OVERRIDE PASS: restore all virtualized overlay cards (no batch
        // limit — they are the visible content); a separate pass so the
        // CARD_BATCH break below cannot skip them.
        if (overlay) {
          for (var k = 0; k < cards.length; k++) {
            var oel = cards[k];
            if (overlay.contains(oel) && oel.__cardVirtualized) {
              oel.style.setProperty('content-visibility', '');
              oel.style.setProperty('contain-intrinsic-size', '');
              oel.__cardVirtualized = false;
              cardVirtualized--;
              diagCardRest++;
              cumCardRest++;
            }
          }
        }
        var restored = 0;
        for (var i = 0; i < cards.length; i++) {
          var el = cards[i];
          if (overlay && overlay.contains(el)) { continue; } // handled above; never virtualize
          var isVirt = el.__cardVirtualized;
          var inView = cardInViewport(el, CARD_MARGIN);
          if (isVirt && inView) {
            // Restore: let the browser render it again.
            el.style.setProperty('content-visibility', '');
            el.style.setProperty('contain-intrinsic-size', '');
            el.__cardVirtualized = false;
            cardVirtualized--;
            restored++;
            diagCardRest++;
            cumCardRest++;
            if (restored >= CARD_BATCH) { break; } // avoid a burst
          } else if (!isVirt && !inView) {
            // Virtualize: record the size, then hide the content.
            var r = el.getBoundingClientRect();
            if (r.width <= 0 || r.height <= 0) { continue; }
            el.style.setProperty('contain-intrinsic-size', r.width + 'px ' + r.height + 'px');
            el.style.setProperty('content-visibility', 'hidden');
            el.__cardVirtualized = true;
            cardVirtualized++;
            diagCardVirt++;
            cumCardVirt++;
          }
        }
      } catch (e) { diagCardErrors++; }
    }

    function sync() {
      if (syncing) { return; }
      syncing = true;
      diagSyncCount++;
      var _t0 = Date.now();
      try {
        // Log a guard_state transition on overlay open/close (observable
        // detection). No early return: allowlist rebuild + compositor_proxy
        // telemetry stay active while the overlay is open.
        var _ov = findInfoOverlay();
        var _nowOpen = !!_ov;
        if (_nowOpen !== overlayOpen) {
          overlayOpen = _nowOpen;
          try {
            window.Buccaneer.diagnosticEvent('guard_state', JSON.stringify({
              state: _nowOpen ? 'overlay_open' : 'overlay_closed',
              ts: Date.now()
            }));
          } catch (e) { /* bridge not ready */ }
        }
        // Virtualize first: must run before the allowlist rebuild so restored
        // images carry their real src when we read them.
        virtualize();
        var _t1 = Date.now();
        // Then cut live composited layers (off-screen cards -> content-visibility: hidden).
        virtualizeCards();
        var _t2 = Date.now();
        diagVirtMs = _t1 - _t0;
        diagCardMs = _t2 - _t1;
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
          // Loaded as a 1x1 placeholder (native firewall blocked it before the
          // allowlist): the HTTP cache holds the 1x1, so a plain src reset won't
          // refetch — force a cache-busted re-request and allowlist the new URL.
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
        // compositor_proxy (>=2s): live vs virtualized TMDB posters + fixed /
        // overflow-hidden (compositor clipping) parent counts. The last sample
        // before a silent native death shows what was live at crash time.
        var now = Date.now();
        if (now - lastCpLog >= 2000) {
          lastCpLog = now;
          var realImg = 0, emptyImg = 0, fixedImgs = 0, clippedImgs = 0;
          // Episode-row diagnostics (v25 §2d) — counts + generation/settle metrics.
          var epDetected = 0, epTotal = 0, epStripped = 0, epSrc = 0, epDecoded = 0,
              epPending = 0, epPlaceholder = 0, epVisibleDecoded = 0,
              epVisibleStripped = 0, epVisiblePending = 0, epScrolling = 0,
              epSettleMs = 0, epPostSettleMax = 0, epQualifying = 0,
              epOverlayId = 'none', epRowId = 'none', epSig = 0,
              epSigMs = 0, epScanMs = 0;
          var _tSig = Date.now();
          var epRows = findEpRows();
          var _tSigEnd = Date.now();
          if (epLastWasHit) { epSigMs = _tSigEnd - _tSig; } else { epScanMs = _tSigEnd - _tSig; }
          for (var k = 0; k < imgs.length; k++) {
            var im = imgs[k];
            if (!isTmdbPoster(im)) { continue; }
            if (im.__flixerSrc) { emptyImg++; } else { realImg++; }
            // Episode-row image counts (v25 §2d): present / stripped / live-src split.
            if (isEpRowImg(im, epRows)) {
              epTotal++;
              if (im.__flixerSrc) {
                epStripped++;
              } else {
                epSrc++;
                if (im.naturalWidth > 1) { epDecoded++; }
                else if (im.naturalWidth === 0) { epPending++; }
                else { epPlaceholder++; }
              }
              // Visible split: only row images passing inRowViewport (common triple intersection).
              if (epRows.length > 0 && inRowViewport(im, epRows[0])) {
                if (im.__flixerSrc) { epVisibleStripped++; }
                else if (im.naturalWidth > 1) { epVisibleDecoded++; }
                else { epVisiblePending++; }
              }
            }
            var p = im.parentElement;
            if (p && p.className && typeof p.className === 'string') {
              if (p.className.indexOf('fixed') !== -1) { fixedImgs++; }
              if (p.className.indexOf('overflow-hidden') !== -1) { clippedImgs++; }
            }
          }
          // Generation tracking + settle metrics (v25 §2a/§2d).
          if (epRows.length > 0) {
            epDetected = 1;
            var _epOv = findInfoOverlay();
            var _epOvId = bkOvId(_epOv);
            var _epRowId = bkRowId(epRows[0]);
            var _epSigExact = epRowCache ? epRowCache.sig : epSigOf(epRows);
            epSig = epSigHash(_epSigExact);
            var _isReset = false;
            if (!epRowState) {
              _isReset = true; // first detection
            } else if (_epOvId !== epRowState.ovId || _epRowId !== epRowState.rowId) {
              _isReset = true; // (a) element replaced
            } else if (_epSigExact !== epRowState.sig) {
              _isReset = true; // (b) exact signature changed
            } else if (epPrevDetected === 0) {
              _isReset = true; // (c) disappeared and reappeared
            }
            var _firstDet, _lastScroll, _lastScrollLeft, _postSettleMax, _qualifying;
            if (_isReset) {
              epGen++;
              _firstDet = now;
              _lastScroll = now;
              _lastScrollLeft = epRows[0].scrollLeft;
              _postSettleMax = 0;
              _qualifying = 0;
            } else {
              _firstDet = epRowState.firstDetectedAt;
              _lastScroll = epRowState.lastScrollAt;
              _lastScrollLeft = epRowState.lastScrollLeft;
              _postSettleMax = epRowState.postSettleMax;
              _qualifying = epRowState.qualifying;
              if (epRows[0].scrollLeft !== _lastScrollLeft) {
                _lastScroll = now;
                _lastScrollLeft = epRows[0].scrollLeft;
                epScrolling = 1;
              }
            }
            epSettleMs = now - Math.max(_firstDet, _lastScroll);
            if (epSettleMs >= 15000) {
              _qualifying++;
              if (epDecoded > _postSettleMax) { _postSettleMax = epDecoded; }
            }
            if (epDecoded > epHiWaterDoc) { epHiWaterDoc = epDecoded; }
            epRowState = { ovId: _epOvId, rowId: _epRowId, sig: _epSigExact,
                           firstDetectedAt: _firstDet, lastScrollAt: _lastScroll,
                           lastScrollLeft: _lastScrollLeft, postSettleMax: _postSettleMax,
                           qualifying: _qualifying };
            epOverlayId = _epOvId;
            epRowId = _epRowId;
            epPostSettleMax = _postSettleMax;
            epQualifying = _qualifying;
          }
          epPrevDetected = epDetected;
          try {
            diagScrollY = (window.pageYOffset || document.documentElement.scrollTop || 0) | 0;
            diagScrollH = (document.documentElement.scrollHeight || 0) | 0;
            diagSyncMs = Date.now() - _t0;
            window.Buccaneer.diagnosticEvent('compositor_proxy', JSON.stringify({
              realImg: realImg, emptyImg: emptyImg,
              fixedImgs: fixedImgs, clippedImgs: clippedImgs,
              cardHidden: cardVirtualized,
              virtMs: diagVirtMs, cardMs: diagCardMs, syncMs: diagSyncMs,
              virtImgs: diagVirtImgs, virtStripped: diagVirtStripped, virtRestored: diagVirtRestored,
              cardProcs: diagCardProcs, cardVirt: diagCardVirt, cardRest: diagCardRest,
              virtErr: diagVirtErrors, cardErr: diagCardErrors,
              syncN: diagSyncCount, scrollY: diagScrollY, scrollH: diagScrollH,
              cumStripped: cumStripped, cumRestored: cumRestored,
              cumCardVirt: cumCardVirt, cumCardRest: cumCardRest,
              epDetected: epDetected, epTotal: epTotal, epStripped: epStripped,
              epSrc: epSrc, epDecoded: epDecoded, epPending: epPending,
              epPlaceholder: epPlaceholder, epVisibleDecoded: epVisibleDecoded,
              epVisibleStripped: epVisibleStripped, epVisiblePending: epVisiblePending,
              epScrolling: epScrolling, epSettleMs: epSettleMs,
              epHiWaterDoc: epHiWaterDoc, epPostSettleMax: epPostSettleMax,
              epQualifying: epQualifying, epOverlayId: epOverlayId,
              epRowId: epRowId, epSig: epSig, epGen: epGen,
              epSigMs: epSigMs, epScanMs: epScanMs
            }));
          } catch (e) { /* bridge not ready */ }
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

    // Images added/removed after injection (posters are appended lazily):
    // re-observe new TMDB images and re-sync.
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

    // Periodic safety net: the IntersectionObserver may not re-fire for an image
    // already intersecting when virtualize() restored its src.
    try {
      setInterval(sync, 2000);
    } catch (e) { /* never throw */ }
  } catch (e) {
    // Absolute last resort: never let this script break the page.
  }
})();
