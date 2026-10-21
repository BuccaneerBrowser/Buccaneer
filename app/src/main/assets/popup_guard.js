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
  // Installing it in every frame (including flixer's top frame) interferes with
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

    var els;
    try { els = document.querySelectorAll('div, span, a, iframe'); } catch (e) { return; }
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el === video) continue;
      var s;
      try { s = window.getComputedStyle(el); } catch (e) { continue; }
      if (s.pointerEvents === 'none') continue;
      if (s.display === 'none' || s.visibility === 'hidden') continue;
      var r;
      try { r = el.getBoundingClientRect(); } catch (e) { continue; }
      var floating = s.position === 'fixed' || s.position === 'absolute';
      var z = parseInt(s.zIndex, 10);
      var hasZ = !isNaN(z);
      var area = r.width * r.height;
      // Element text, kept for the reconnaissance log below.
      var text = '';
      try { text = (el.textContent || '').toLowerCase(); } catch (e) { /* ignore */ }
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
      //     ordinary page text: movie synopses and page titles. On Movy and
      //     CineJoy the hero content layer is position:absolute, carries the
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
      //      (2026-10-21, coreflix "baseball" popups: z=2147483646 fixed DIVs
      //      with an injected <style> block, no controls, no video — slipped
      //      through both isIframeAd and covers_video.)
      var isHighZAd = floating && hasZ && z >= 1000000 && area >= 1000;
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
      if (!isIframeAd && !coversVideo && !isHighZAd) {
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
                text: text.replace(/\s+/g, ' ').trim().substring(0, 60)
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
            reason: isIframeAd ? 'floating_iframe' : (isHighZAd ? 'high_z_ad' : 'covers_video'),
            text: text.replace(/\s+/g, ' ').trim().substring(0, 60)
          }));
        }
      } catch (e) { /* ignore */ }
    }
  }
  // Scanning every element every 1500 ms is heavy on big pages. Only re-check after the DOM
  // changes (debounced), with a slow interval as a fallback for style-only changes.
  // Flixer: skip the overlay scanner entirely. The MutationObserver + 5s interval that
  // walks every element in the DOM adds GPU/CPU load that triggers the PowerVR driver
  // crash during flixer's intro animation. Flixer has few popups; the window.open guard
  // above still protects it.
  var __pgHost = '';
  try { __pgHost = (window.location.hostname || '').toLowerCase().replace(/\.$/, ''); } catch (e) {}
  var __pgIsFlixer = /(^|\.)flixer\.(gd|su)$/.test(__pgHost);
  if (!__pgIsFlixer) {
    killOverlays();
    var scanTimer = 0;
    function scheduleScan() {
      if (scanTimer) return;
      scanTimer = setTimeout(function () {
        scanTimer = 0;
        killOverlays();
      }, 300);
    }
    try {
      var mo = new MutationObserver(scheduleScan);
      mo.observe(document.documentElement || document, { childList: true, subtree: true });
    } catch (e) { /* old WebView without MutationObserver: fall back to the interval */ }
    setInterval(killOverlays, 5000);
  }
})();
