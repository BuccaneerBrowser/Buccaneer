/*
 * Popup Guard - Streaming Mode  (port of content.js from the Chrome extension, v2.0)
 *
 * Runs at document start in every frame (same as the extension's "document_start", "all_frames").
 * The ON/OFF state lives in the app (Menu > Popup Guard); it is read live through the
 * window.Buccaneer bridge, so toggling needs no reload. Defaults to ON if the bridge is missing.
 *
 * Port notes
 *  - chrome.storage state check      -> Buccaneer.isGuardEnabled()
 *  - console.warn on blocked open    -> kept, and also reported to the app (toast + counter)
 *  - the second 2000 ms interval with ALLOWED=/(^|\.)7movies\.ac$/ in content.js had an empty
 *    body (it did nothing), so it is not ported. The 7movies.ac redirect rule from rules.json
 *    is enforced natively in PopupGuard.kt instead.
 */
(function () {
  'use strict';

  if (window.__popupGuardInstalled) return;
  try {
    Object.defineProperty(window, '__popupGuardInstalled', { value: true });
  } catch (e) {
    window.__popupGuardInstalled = true;
  }

  function enabled() {
    try {
      if (window.Buccaneer && typeof window.Buccaneer.isGuardEnabled === 'function') {
        return window.Buccaneer.isGuardEnabled() !== false;
      }
    } catch (e) { /* bridge not ready: stay protective */ }
    return true;
  }

  function diagnostic(event, data) {
    try {
      if (window.Buccaneer && typeof window.Buccaneer.diagnosticEvent === 'function') {
        window.Buccaneer.diagnosticEvent(String(event), JSON.stringify(data || {}));
      }
    } catch (e) { /* diagnostics are best effort */ }
  }

  function report(what) {
    try {
      if (window.Buccaneer && typeof window.Buccaneer.blocked === 'function') {
        window.Buccaneer.blocked(String(what));
      }
    } catch (e) { /* ignore */ }
  }

  // Mark the element that received the last pointer event, used by disableAdLayer()
  // as a secondary sweep (the primary fix is the React onClick invocation below).
  var adLayerMarked = null;
  var adLayerMarkedAt = 0;
  // The vidcore player frame's ad script suppresses the synthetic click event that
  // follows pointerup/touchend, so player buttons (pause, seek, settings, menu items)
  // never receive a click and their actions never fire. The fix: in the capture phase,
  // neutralize preventDefault() on pointer/touch events, and on pointerup directly
  // invoke the React onClick handler (walking up the DOM to find it, since React event
  // delegation often puts the handler on a parent element).
  var isPlayerFrame = (window !== window.top);
  function findReactOnClick(el) {
    // Walk up from el to document, looking for a React onClick handler.
    // React event delegation often puts the handler on a parent, not the
    // exact element that was tapped.
    var node = el;
    var hops = 0;
    while (node && node.nodeType === 1 && hops < 15) {
      try {
        var keys = Object.keys(node);
        for (var i = 0; i < keys.length; i++) {
          if (keys[i].indexOf('__reactProps') === 0) {
            var props = node[keys[i]];
            if (props && typeof props.onClick === 'function') {
              return { el: node, handler: props.onClick, hops: hops };
            }
          }
        }
      } catch (e) { /* ignore */ }
      node = node.parentElement;
      hops++;
    }
    return null;
  }
  function reactOnClick(el) {
    var found = findReactOnClick(el);
    if (!found) return false;
    try {
      diagnostic('react_onclick_called', {
        tag: found.el.tagName,
        cls: (found.el.className && typeof found.el.className === 'string' ? found.el.className : '').substring(0, 60),
        hops: found.hops
      });
      found.handler({
        preventDefault: function() {},
        stopPropagation: function() {},
        stopImmediatePropagation: function() {},
        nativeEvent: new MouseEvent('click'),
        target: el,
        currentTarget: found.el
      });
      return true;
    } catch (e) { /* ignore */ }
    return false;
  }
  function markAdLayer(e) {
    try {
      var t = e.target;
      if (t && t.nodeType === 1) {
        adLayerMarked = t;
        adLayerMarkedAt = Date.now();
        var tag = t.tagName;
        if (tag !== 'VIDEO' && tag !== 'AUDIO') {
          // Neutralize preventDefault so the click event is not suppressed.
          if (e.type === 'pointerup' || e.type === 'touchend' ||
              e.type === 'pointerdown' || e.type === 'touchstart') {
            e.preventDefault = function() {};
          }
          // On pointerup, defer the React onClick handler. If the native click
          // event fires on its own (ad script not suppressing it), the deferred
          // call is cancelled — no double-trigger. If the ad script suppresses
          // the click, the deferred call fires and the button works.
          if (e.type === 'pointerup') {
            var target = t;
            var timer = setTimeout(function () {
              deferredClickTimer = 0;
              reactOnClick(target);
            }, 300);
            deferredClickTimer = timer;
          }
        }
      }
    } catch (e2) { /* ignore */ }
  }
  // Only install the pointer-event listener in the vidcore player frame.
  // Installing it in every frame (including the poster-grid site's top frame) interferes with
  // page rendering and triggers GPU driver crashes on this Fire TV.
  var isVidcoreFrame = (window.location.hostname || '').indexOf('vidcore') >= 0;
  var deferredClickTimer = 0;
  if (isVidcoreFrame) {
    var MARK_EVENTS = ['touchstart', 'touchend', 'pointerdown', 'pointerup',
      'mousedown', 'mouseup', 'click'];
    for (var me = 0; me < MARK_EVENTS.length; me++) {
      document.addEventListener(MARK_EVENTS[me], markAdLayer, true);
    }
    // If a native click fires, cancel the deferred React onClick — the click
    // is handling the button itself, so a second invocation would double-trigger.
    document.addEventListener('click', function () {
      if (deferredClickTimer) {
        clearTimeout(deferredClickTimer);
        deferredClickTimer = 0;
      }
    }, true);
  }

  function disableAdLayer() {
    if (!adLayerMarked) {
      diagnostic('disable_ad_layer_no_mark', { topFrame: (window === window.top) ? 1 : 0 });
      return;
    }
    var el = adLayerMarked;
    adLayerMarked = null;
    try {
      if (el.nodeType !== 1) return;
      if (el.tagName === 'VIDEO' || el.tagName === 'AUDIO') {
        diagnostic('disable_ad_layer_skipped_media', { tag: el.tagName });
        return;
      }
      if (el.closest && el.closest('button, [role="button"], input, select, video')) {
        diagnostic('disable_ad_layer_skipped_control', { tag: el.tagName });
        return;
      }
      // Walk up from the marked element and disable every ancestor that covers the
      // video: the ad layer may be a child of a full-viewport wrapper, and the wrapper
      // (or a sibling) is what actually intercepts the next tap.
      var vr = null, videoArea = 0;
      try {
        var vids = document.getElementsByTagName('video');
        for (var v = 0; v < vids.length; v++) {
          var r0;
          try { r0 = vids[v].getBoundingClientRect(); } catch (e3) { continue; }
          var a0 = r0.width * r0.height;
          if (a0 > videoArea) { videoArea = a0; vr = r0; }
        }
      } catch (e4) { /* ignore */ }
      var node = el;
      var hops = 0;
      while (node && node.nodeType === 1 && hops < 12) {
        var s;
        try { s = window.getComputedStyle(node); } catch (e5) { s = null; }
        if (s && s.pointerEvents !== 'none' && s.display !== 'none' && s.visibility !== 'hidden') {
          var r;
          try { r = node.getBoundingClientRect(); } catch (e6) { r = null; }
          var covers = vr && r &&
            Math.max(0, Math.min(r.right, vr.right) - Math.max(r.left, vr.left)) *
            Math.max(0, Math.min(r.bottom, vr.bottom) - Math.max(r.top, vr.top)) >= videoArea * 0.5;
          if (covers) {
            node.style.pointerEvents = 'none';
            diagnostic('overlay_disabled', {
              cls: (node.className && typeof node.className === 'string' ? node.className : '').substring(0, 60),
              tag: node.tagName,
              pos: s ? s.position : '',
              z: s ? s.zIndex : '',
              op: s ? s.opacity : '',
              reason: 'popup_initiator'
            });
          }
        }
        node = node.parentElement;
        hops++;
      }
    } catch (e7) { /* ignore */ }
  }

  // 1) window.open() returns null while the guard is on
  var nativeOpen = window.open;
  window.open = function () {
    if (enabled()) {
      var url = arguments.length > 0 ? arguments[0] : undefined;
      try { console.warn('[Popup Guard] Blocked window.open:', url); } catch (e) { /* ignore */ }
      report('a popup window');
      diagnostic('popup_window_open_blocked', { pageUrl: window.location.href || '', url: typeof url === 'string' ? url : '' });
      disableAdLayer();
      return null;
    }
    return nativeOpen.apply(window, arguments);
  };

  // 2) links that ask for a new tab (target=_blank / _new) open in the same view instead.
  //    A click on such a link is also a tap the ad layer may have intercepted, so mark the
  //    target the same way the window.open() path does: the next blocked popup disables it.
  document.addEventListener('click', function (e) {
    if (!enabled()) return;
    var t = e.target;
    var a = (t && t.closest) ? t.closest('a') : null;
    if (a && (a.target === '_blank' || a.target === '_new')) {
      a.target = '_self';
      diagnostic('popup_target_rewritten', { pageUrl: window.location.href || '', url: a.href || '' });
    }
  }, true);

  // 3) invisible click-catching overlays: any element that covers the video and sits above it
  //    gets pointer-events:none so clicks go through to the player underneath.
  //
  //    The old version only disabled elements matching position:fixed + opacity<0.3 +
  //    z-index>1000 (or wrapping an iframe). The ad layer on vidcore doesn't match those
  //    thresholds (it can be position:absolute, opacity 1, or a low z-index), so it was
  //    never disabled and ate every tap. This version detects the real condition instead:
  //    an element that (a) covers most of the video, (b) is positioned above it in the
  //    stacking order, and (c) is NOT the video, its control bar, or a real control.
  //    That names the ad layer directly rather than guessing from style thresholds.
  // Log-only text of an element, read only when an event is actually logged. Same value as the
  // old eager variable (lowercased, whitespace-collapsed, first 60 chars) without copying the
  // whole text of every element on every scan.
  function elText(el) {
    try {
      return (el.textContent || '').substring(0, 400).toLowerCase().replace(/\s+/g, ' ').trim().substring(0, 60);
    } catch (e) { return ''; }
  }

  function killOverlays() {
    if (!enabled()) return;
    // Find the video we care about: the largest visible <video> in this frame.
    // It may not exist (e.g. the site's home page) — the floating-popup rules below
    // still apply, which is what catches ad popups before a movie has started.
    var video = null, videoArea = 0, vr = null;
    var vids;
    try { vids = document.getElementsByTagName('video'); } catch (e) { return; }
    for (var v = 0; v < vids.length; v++) {
      var r;
      try { r = vids[v].getBoundingClientRect(); } catch (e) { continue; }
      var a = r.width * r.height;
      if (a > videoArea) { videoArea = a; video = vids[v]; }
    }
    var haveVideo = video && videoArea >= 10000;
    if (haveVideo) {
      try { vr = video.getBoundingClientRect(); } catch (e) { vr = null; }
    }
    var viewportArea = (window.innerWidth || 0) * (window.innerHeight || 0);

    // Identify the main player iframe: the largest VISIBLE iframe that (a) loads
    // a real http(s) document and (b) fits within the viewport. Streaming sites
    // put the player iframe at the max z-index (2147483647) + position:fixed when
    // in fullscreen — the SAME z-index the ad injector uses. The video inside is
    // cross-origin, so containsVideo/hasControls can't see it, and a naive high-z
    // rule kills the player (black screen with audio, dead seek bridge — a video site
    // 2026-10-21). Exempt the main player so the high-z rule only ever hits
    // injected ads. Criterion (a): ad click-catcher iframes use about:blank /
    // srcdoc / data: srcs, never http(s). Criterion (b): in-flow iframes (chat
    // panels, embedded content) extend past the viewport edge and can be LARGER
    // than the fullscreen player — the cross-origin player site's 910x678 chat iframe beat the
    // 960x540 player for the exemption, so the high-z rule killed the player
    // (black video, 2026-10-23).
    var playerIframe = null, playerArea = 0;
    var pVw = window.innerWidth || 0, pVh = window.innerHeight || 0;
    var ifrList;
    try { ifrList = document.getElementsByTagName('iframe'); } catch (e) { ifrList = []; }
    for (var fi = 0; fi < ifrList.length; fi++) {
      var f = ifrList[fi];
      var fs; try { fs = getComputedStyle(f); } catch (e) { continue; }
      if (fs.display === 'none' || fs.visibility === 'hidden') continue;
      var fsrc = (f.src || '').toLowerCase();
      // A real player iframe loads an http(s) document. Ad click-catcher
      // iframes use about:blank / srcdoc / data: srcs — never http(s).
      if (fsrc.indexOf('http://') !== 0 && fsrc.indexOf('https://') !== 0) continue;
      var fr; try { fr = f.getBoundingClientRect(); } catch (e) { continue; }
      // A fullscreen player fills the viewport. In-flow iframes (chat panels,
      // embedded content) extend past the viewport edge and can be LARGER than
      // the fullscreen player — see the comment above the loop. Only apply the
      // fit test when the viewport size is known.
      if (pVw > 0 && pVh > 0 && (fr.top < -2 || fr.left < -2 || fr.bottom > pVh + 2 || fr.right > pVw + 2)) continue;
      var fa = fr.width * fr.height;
      if (fa > playerArea) { playerArea = fa; playerIframe = f; }
    }

    var els;
    try { els = document.querySelectorAll('div, span, a, iframe'); } catch (e) { return; }
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el === video) continue;
      var s;
      try { s = window.getComputedStyle(el); } catch (e) { continue; }
      if (s.pointerEvents === 'none') continue;
      if (s.display === 'none' || s.visibility === 'hidden') continue;
      var floating = s.position === 'fixed' || s.position === 'absolute';
      // PERFORMANCE (a ~1000-link portal page, ~10k elements: page dead to taps for ~7 s on
      // the Fire TV): every rule below needs a floating element, except covers-video, which
      // also accepts a position:relative one and needs a video. A static element can never be
      // killed or logged, so stop before the two calls that are costly on a big page
      // (getBoundingClientRect forces layout; the old eager textContent copied the full text of
      // every container, which for nested containers is the page text once per nesting level).
      if (!floating && !(haveVideo && vr && s.position === 'relative')) continue;
      var r;
      try { r = el.getBoundingClientRect(); } catch (e) { continue; }
      var z = parseInt(s.zIndex, 10);
      var hasZ = !isNaN(z);
      var area = r.width * r.height;
      // (a) is an ad overlay. Three signals:
      //     1. a floating iframe with a very high z-index and small size — an ad
      //        creative. Works with or without a video (home pages, pre-roll). The
      //        high-z + small-size bar keeps a large, low-z player iframe safe.
      //     2. covers a significant part of the video (needs a video on the page).
      //     3. a positioned element with an abnormally high z-index (>= 1,000,000)
      //        — an injected ad badge or full-viewport click-catcher on a page
      //        with no video (see isHighZAd below).
      //
      //     An earlier version also killed any floating element whose text matched
      //     ad copy ("free", "download", "offer", "sign up", ...). That matched
      //     ordinary page text: movie synopses and page titles. On a video site and
      //     the lazy-load poster site the hero content layer is position:absolute, carries the
      //     Play button, and its synopsis contains words like "offer" or
      //     "download" — so the rule hid the Play button on show pages
      //     (2026-10-21, "Bad Apples" report). Text is not a reliable ad signal;
      //     real ad popups are iframes (signal 1) or control-less overlays over
      //     the video (signal 2).
      var isIframeAd = el.tagName === 'IFRAME' && floating && hasZ && z >= 1000 &&
        area >= 1000 && (viewportArea <= 0 || area < viewportArea * 0.25);
      // (a3) injected ad overlay: a positioned element with an abnormally high
      //      z-index. Ad injectors force their creative on top by setting the
      //      32-bit max z-index (2147483646/7); legitimate page UI never reaches
      //      1,000,000. This catches the floating ad badges AND the full-viewport
      //      click-catchers that appear on non-video pages (home, show/season
      //      pages) where covers_video can't fire because there is no <video>.
      //      The containsVideo / hasControls checks below still protect real
      //      player UI and modals, so this is safe to apply broadly.
      //      (2026-10-21, a video site "baseball" popups: z=2147483646 fixed DIVs
      //      with an injected <style> block, no controls, no video — slipped
      //      through both isIframeAd and covers_video.)
      // Exempt the main player and its container chain: an injected ad is never
      // the player or an ancestor of it, so this cannot spare a real ad. (The
      // site's fullscreen may put the player's wrapper at a high z-index too.)
      var isPlayerChain = !!(playerIframe && el.contains && el.contains(playerIframe));
      var isHighZAd = floating && hasZ && z >= 1000000 && area >= 1000 && !isPlayerChain;
      // (a4) full-viewport click-catcher: a positioned element covering >=90% of the
      // viewport at a high z-index, on a page where covers_video can't fire (no <video>
      // yet — home, show/season, pre-roll) and the z-index is below the 1,000,000
      // high-z threshold. Ad injectors commonly use 9999 / 1000 for these (seen on
      // a video site/a poster site and a video site, 2026-10-22). Legitimate full-viewport UI (modals,
      // the player container) contains controls or a video, so the containsVideo /
      // hasControls protections below still spare it; the player and its container
      // chain are exempt via isPlayerChain.
      var vpOverlap = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0)) *
        Math.max(0, Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0));
      var coversViewport = floating && hasZ && z >= 1000 &&
        viewportArea > 0 && vpOverlap >= viewportArea * 0.9 && !isPlayerChain;
      var coversVideo = false;
      if (haveVideo && vr) {
        // Only a positioned element can paint above the video in the stacking order;
        // a static, z-less sibling is not the culprit.
        var positioned = floating || s.position === 'relative';
        var above = positioned && (s.zIndex !== 'auto' ? z >= 0 : true);
        var overlapArea = Math.max(0, Math.min(r.right, vr.right) - Math.max(r.left, vr.left)) *
          Math.max(0, Math.min(r.bottom, vr.bottom) - Math.max(r.top, vr.top));
        coversVideo = above && overlapArea >= videoArea * 0.1;
      }
      if (!isIframeAd && !coversVideo && !isHighZAd && !coversViewport) {
        // Reconnaissance: log floating high-z elements we did NOT kill, once each,
        // so a popup that slips through can be identified from the session log.
        if (floating && hasZ && z >= 100 && area >= 1000 && !el.getAttribute('data-fslogged')) {
          try { el.setAttribute('data-fslogged', '1'); } catch (e) { /* ignore */ }
          try {
            if (window.Buccaneer && typeof window.Buccaneer.diagnosticEvent === 'function') {
              window.Buccaneer.diagnosticEvent('floating_seen', JSON.stringify({
                tag: el.tagName,
                src: el.tagName === 'IFRAME' ? (el.src || '').substring(0, 80) : '',
                pos: s.position,
                z: s.zIndex,
                x: Math.round(r.left), y: Math.round(r.top),
                w: Math.round(r.width), h: Math.round(r.height),
                text: elText(el)
              }));
            }
          } catch (e) { /* ignore */ }
        }
        continue;
      }
      // (b) is NOT real player UI: skip elements that contain a video or ANY
      //     interactive controls. An element with buttons is page UI, not an ad
      //     overlay — ad-like words in a synopsis or title must not override
      //     that. (An iframe ad is opaque to querySelector, so this check can
      //     never protect one.)
      var containsVideo = false;
      try { containsVideo = !!(el.querySelector && el.querySelector('video')); } catch (e) { /* ignore */ }
      if (containsVideo) continue;
      var hasControls = false;
      try { hasControls = !!(el.querySelector && el.querySelector('button, [role="button"], input, select')); } catch (e) { /* ignore */ }
      if (hasControls && !isIframeAd) continue;
      // Kill it. Floating popups are out of flow: hide them so they are gone, not just
      // click-through. In-flow layers that cover the video become click-through so taps
      // reach the player underneath.
      if (floating) el.style.display = 'none';
      else el.style.pointerEvents = 'none';
      try {
        if (window.Buccaneer && typeof window.Buccaneer.diagnosticEvent === 'function') {
          window.Buccaneer.diagnosticEvent('overlay_killed', JSON.stringify({
            cls: (el.className && typeof el.className === 'string' ? el.className : '').substring(0, 60),
            tag: el.tagName,
            pos: s.position,
            z: s.zIndex,
            reason: isIframeAd ? 'floating_iframe' : (isHighZAd ? 'high_z_ad' : (coversViewport ? 'viewport_catcher' : 'covers_video')),
            text: elText(el)
          }));
        }
      } catch (e) { /* ignore */ }
    }
  }

  // Minimal popup scan for the poster-grid site. The full-DOM walk in killOverlays
  // (querySelectorAll over every div/span/a/iframe) triggers the PowerVR driver
  // crash during the poster-grid site's intro animation, so the heavy scanner stays OFF on
  // the poster-grid site. But the poster-grid site still gets inline ad click-catchers that the window.open
  // guard cannot catch. This checks only a handful of elements (body's direct
  // children + iframes) and applies only the cheap high-z and full-viewport
  // rules. It is CPU-cheap and does not add the DOM-walk load that crashed the
  // GPU driver. The main player iframe (largest iframe with a real src) and its
  // container chain are exempt, so the player is never killed.
  function killCatchers() {
    if (!enabled()) return;
    var viewportArea = (window.innerWidth || 0) * (window.innerHeight || 0);
    if (viewportArea <= 0) return;
    // Main player iframe: the largest visible iframe that loads a real http(s)
    // document AND fits within the viewport (same logic as killOverlays).
    var playerIframe = null, playerArea = 0;
    var pVw = window.innerWidth || 0, pVh = window.innerHeight || 0;
    try {
      var ifrList = document.getElementsByTagName('iframe');
      for (var pi = 0; pi < ifrList.length; pi++) {
        var f = ifrList[pi];
        var fs; try { fs = getComputedStyle(f); } catch (e) { continue; }
        if (fs.display === 'none' || fs.visibility === 'hidden') continue;
        var fsrc = (f.src || '').toLowerCase();
        // Same two criteria as killOverlays: http(s) src + fits viewport.
        if (fsrc.indexOf('http://') !== 0 && fsrc.indexOf('https://') !== 0) continue;
        var fr; try { fr = f.getBoundingClientRect(); } catch (e) { continue; }
        if (pVw > 0 && pVh > 0 && (fr.top < -2 || fr.left < -2 || fr.bottom > pVh + 2 || fr.right > pVw + 2)) continue;
        var fa = fr.width * fr.height;
        if (fa > playerArea) { playerArea = fa; playerIframe = f; }
      }
    } catch (e) { }
    // Restricted element set: <html>'s and <body>'s direct children + all iframes.
    // NOT the whole DOM. Ad injectors attach their overlay divs to <html> (a sibling
    // of <body>), which body.children alone misses. <head>/<body> themselves are not
    // position:fixed/absolute, so the floating check below filters them out before any
    // heavy work — this stays far under the DOM-walk load that crashed the PowerVR
    // driver, which is why the poster-grid site uses this minimal scanner at all.
    var els = [];
    try {
      var html = document.documentElement;
      if (html) { for (var h = 0; h < html.children.length; h++) els.push(html.children[h]); }
      var body = document.body;
      if (body) { for (var i = 0; i < body.children.length; i++) els.push(body.children[i]); }
      var ifr = document.getElementsByTagName('iframe');
      for (var j = 0; j < ifr.length; j++) els.push(ifr[j]);
    } catch (e) { return; }
    for (var k = 0; k < els.length; k++) {
      var el = els[k];
      if (!el || el === playerIframe) continue;
      var s; try { s = window.getComputedStyle(el); } catch (e) { continue; }
      if (s.pointerEvents === 'none') continue;
      if (s.display === 'none' || s.visibility === 'hidden') continue;
      var r; try { r = el.getBoundingClientRect(); } catch (e) { continue; }
      var floating = s.position === 'fixed' || s.position === 'absolute';
      if (!floating) continue;
      var z = parseInt(s.zIndex, 10);
      var hasZ = !isNaN(z);
      var area = r.width * r.height;
      // Reconnaissance: log floating high-z elements (killed or not), once each,
      // so we can see exactly what the poster-grid site's popups are from the session log.
      if (hasZ && z >= 100 && area >= 1000 && !el.getAttribute('data-fslogged')) {
        try { el.setAttribute('data-fslogged', '1'); } catch (e) { }
        diagnostic('floating_seen', {
          tag: el.tagName,
          src: el.tagName === 'IFRAME' ? (el.src || '').substring(0, 80) : '',
          pos: s.position, z: s.zIndex,
          x: Math.round(r.left), y: Math.round(r.top),
          w: Math.round(r.width), h: Math.round(r.height)
        });
      }
      if (!hasZ || z < 1000) continue;
      var isIframeAd = el.tagName === 'IFRAME' && area >= 1000 && area < viewportArea * 0.25;
      var vpOverlap = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0)) *
        Math.max(0, Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0));
      var isViewportCatcher = vpOverlap >= viewportArea * 0.9;
      // Abnormally high z-index (>= 1,000,000): an injected ad overlay DIV. Ad
      // injectors force their creative to the top with the 32-bit max z-index
      // (2147483646); legitimate page UI never reaches 1,000,000. Mirrors the
      // isHighZAd rule in killOverlays() so the poster-grid site's minimal scanner catches the
      // same overlays. The player + container chain are exempt below, and the
      // video/controls protections still apply.
      var isHighZAd = z >= 1000000 && area >= 1000;
      if (!isIframeAd && !isViewportCatcher && !isHighZAd) continue;
      // Exempt the player and its container chain.
      if (playerIframe && el.contains && el.contains(playerIframe)) continue;
      // Protections: skip if it contains a video or real controls.
      var containsVideo = false;
      try { containsVideo = !!(el.querySelector && el.querySelector('video')); } catch (e) { }
      if (containsVideo) continue;
      var hasControls = false;
      try { hasControls = !!(el.querySelector && el.querySelector('button, [role="button"], input, select')); } catch (e) { }
      if (hasControls && !isIframeAd) continue;
      el.style.display = 'none';
      diagnostic('overlay_killed', {
        cls: (el.className && typeof el.className === 'string' ? el.className : '').substring(0, 60),
        tag: el.tagName,
        pos: s.position,
        z: s.zIndex,
        reason: isIframeAd ? 'floating_iframe' : (isHighZAd ? 'high_z_ad' : 'viewport_catcher')
      });
    }
  }

  // Scanning every element every 1500 ms is heavy on big pages. Only re-check after the DOM
  // changes (debounced), with a slow interval as a fallback for style-only changes.
  // the poster-grid site: the full-DOM walk (MutationObserver + interval over every element)
  // triggers the PowerVR driver crash during the poster-grid site's intro animation, so the
  // heavy scanner stays OFF. But the poster-grid site still gets inline ad click-catchers that
  // the window.open guard cannot catch, so run the MINIMAL killCatchers scan
  // instead: a handful of elements (body's children + iframes), cheap rules only,
  // slow interval, no MutationObserver.
  var __pgHost = '';
  try { __pgHost = (window.location.hostname || '').toLowerCase().replace(/\.$/, ''); } catch (e) {}
  var __pgIsFlixer = /(^|\.)flixer\.(gd|su)$/.test(__pgHost);
  if (__pgIsFlixer) {
    killCatchers();
    setInterval(killCatchers, 5000);
  } else {
    // A scan on a big page is not free on this hardware, and a page that is still loading or
    // hydrating mutates constantly, so a fixed 300 ms debounce re-ran the scan back to back and
    // left the page's main thread no time for taps (a ~1000-link portal page: dead for ~7 s,
    // then the page snapped back to the top). Time each scan and wait at least ten times as
    // long before the next one (at most ~9% of the main thread, never more than 4 s), so a
    // light page keeps the 300 ms response and a heavy one backs off by itself.
    var lastScanMs = 0;
    function nowMs() {
      try { return window.performance.now(); } catch (e) { return Date.now(); }
    }
    function timedScan() {
      var t0 = nowMs();
      try { killOverlays(); } finally { lastScanMs = nowMs() - t0; }
    }
    timedScan();
    var scanTimer = 0;
    function scheduleScan() {
      if (scanTimer) return;
      scanTimer = setTimeout(function () {
        scanTimer = 0;
        timedScan();
      }, Math.min(4000, Math.max(300, lastScanMs * 10)));
    }
    try {
      var mo = new MutationObserver(scheduleScan);
      mo.observe(document.documentElement || document, { childList: true, subtree: true });
    } catch (e) { /* old WebView without MutationObserver: fall back to the interval */ }
    setInterval(timedScan, 5000);
  }
})();
