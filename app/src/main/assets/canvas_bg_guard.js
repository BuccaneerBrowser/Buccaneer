/*
 * Canvas Background Guard — PowerVR crash mitigation (document start, top frame only).
 *
 * A streaming site's /search page mounts `.liquid-bg`: a full-viewport (100vw x 100vh)
 * position:fixed, z-index:0, pointer-events:none container holding an animated <canvas>.
 * The continuously composited canvas layer crashes the PowerVR GPU driver on this Fire TV
 * (SIGSEGV in KEGLReleaseRenderTarget, RenderThread) — same crash family as the hero
 * carousel churn and the Flixer image burst. The background is purely decorative
 * (pointer-events:none, z-index:0, behind all content), so killing it has zero functional
 * impact.
 *
 * Kill action: display:none on the matching container (removes the compositor layer) AND
 * collapse every canvas inside it to 1x1 (the page's rAF loop keeps drawing; a 1x1 backing
 * store keeps any canvas-level texture work trivial).
 *
 * Detection: MutationObserver on document.documentElement (childList+subtree) — per added
 * node a tag-only canvas check, then a bounded walk-up (<=10 ancestors) to the nearest
 * element matching the signature. A 1500ms backstop poll (tag-only
 * querySelectorAll('canvas')) covers the lazy-stylesheet case (styles applied after
 * insertion, invisible to the observer) and re-shows of a killed background.
 *
 * Top frame only: the confirmed background lives in the top document, and running in
 * cross-origin player iframes risks killing a canvas-based player whose render layer is
 * itself full-viewport fixed pointer-events:none.
 *
 * ES5 only (Chromium 118 / API 25: no ?? or ?. operators). Never throws. No full-DOM walk
 * — only canvases and their bounded ancestor chains are style-inspected.
 */
(function () {
  'use strict';

  if (window.__canvasBgGuard) return;
  try {
    Object.defineProperty(window, '__canvasBgGuard', { value: true });
  } catch (e) {
    window.__canvasBgGuard = true;
  }

  // Top frame only — see the header comment.
  if (window !== window.top) return;

  // Host-scoped: the full-viewport animated canvas background is a single streaming site's feature
  // (the /search page's .liquid-bg). Per the host-scoping rule, a host-specific problem
  // gets a host-scoped guard — running on every origin risks killing a canvas-based
  // player on another site whose render layer is itself full-viewport fixed
  // pointer-events:none. Same host-guard pattern as flixer_image_guard.js.
  try {
    var host = (window.location.host || '').toLowerCase();
    if (!/(^|\.)cinejoy\.pk$/.test(host)) return;
  } catch (e) { return; }

  function diag(event, data) {
    try {
      if (window.Buccaneer && typeof window.Buccaneer.diagnosticEvent === 'function') {
        window.Buccaneer.diagnosticEvent(String(event), JSON.stringify(data || {}));
      }
    } catch (e) { /* diagnostics are best effort */ }
  }

  // Signature: a decorative full-viewport canvas background. ALL must hold on one element:
  // position:fixed + pointer-events:none (read via getComputedStyle — the site sets these
  // in a stylesheet, not inline) + covers >=90% of the viewport (intersection formula,
  // same as popup_guard.js — a raw width*height comparison misbehaves for oversized or
  // transformed elements) + contains a <canvas> (or IS one). The pointer-events:none
  // requirement is the key safety: any interactive surface (video player, game) is
  // pointer-events:auto.
  function matchesSignature(el) {
    var s;
    try { s = window.getComputedStyle(el); } catch (e) { return false; }
    if (s.position !== 'fixed') return false;
    if (s.pointerEvents !== 'none') return false;
    var vw = window.innerWidth || 0, vh = window.innerHeight || 0;
    var viewportArea = vw * vh;
    if (viewportArea <= 0) return false;
    var r;
    try { r = el.getBoundingClientRect(); } catch (e) { return false; }
    var vpOverlap = Math.max(0, Math.min(r.right, vw) - Math.max(r.left, 0)) *
      Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0));
    return vpOverlap >= viewportArea * 0.9;
  }

  // Walk up from a canvas (including the canvas itself) to the nearest element matching
  // the signature. Bounded (<=10 hops). Never targets documentElement or body — hiding
  // the page root would hide the entire page. allowKilled: when true, a previously killed
  // (__bgKilled) ancestor is returned as-is (its rect is zero while display:none, so the
  // signature test cannot see it) — used by the backstop poll.
  function findTarget(canvasEl, allowKilled) {
    var node = canvasEl;
    var hops = 0;
    while (node && node.nodeType === 1 && hops <= 10) {
      if (node === document.documentElement || node === document.body) break;
      if (node.__bgKilled) {
        if (allowKilled) return node;
      } else if (matchesSignature(node)) {
        return node;
      }
      node = node.parentElement;
      hops++;
    }
    return null;
  }

  function collectCanvases(t) {
    var out = [];
    try {
      if (t.tagName === 'CANVAS') out.push(t);
      var cs = t.querySelectorAll ? t.querySelectorAll('canvas') : null;
      if (cs) { for (var i = 0; i < cs.length; i++) out.push(cs[i]); }
    } catch (e) { /* ignore */ }
    return out;
  }

  function killTarget(t) {
    // Measure before hiding (a display:none rect is zero).
    var r = null, s = null;
    try { r = t.getBoundingClientRect(); } catch (e) { }
    try { s = window.getComputedStyle(t); } catch (e) { }
    try { t.style.display = 'none'; } catch (e) { }
    var cs = collectCanvases(t);
    for (var i = 0; i < cs.length; i++) {
      try { cs[i].width = 1; cs[i].height = 1; } catch (e) { }
    }
    try { t.__bgKilled = true; } catch (e) { }
    diag('canvas_bg_killed', {
      tag: t.tagName,
      cls: (t.className && typeof t.className === 'string' ? t.className : '').substring(0, 60),
      w: r ? Math.round(r.width) : 0,
      h: r ? Math.round(r.height) : 0,
      z: s ? s.zIndex : '',
      canvases: cs.length
    });
  }

  function processNode(node) {
    if (!node || node.nodeType !== 1) return;
    var canvas = null;
    if (node.tagName === 'CANVAS') {
      canvas = node;
    } else {
      try { canvas = node.querySelector ? node.querySelector('canvas') : null; } catch (e) { canvas = null; }
    }
    if (!canvas) return;
    var target = findTarget(canvas, false);
    if (target) killTarget(target);
  }

  try {
    // documentElement, not body: at document start body is usually null, and the codebase
    // pattern (flixer_image_guard.js, popup_guard.js) observes documentElement.
    var mo = new MutationObserver(function (mutations) {
      for (var i = 0; i < mutations.length; i++) {
        var added = mutations[i].addedNodes;
        for (var j = 0; j < added.length; j++) {
          processNode(added[j]);
        }
      }
    });
    mo.observe(document.documentElement || document, { childList: true, subtree: true });
  } catch (e) { /* no MutationObserver: the poll below still covers it */ }

  // Backstop poll: covers (a) the lazy-stylesheet case — the element is inserted before
  // its CSS loads, so the observer's signature test sees position:static and misses it;
  // (b) an SPA re-show of a killed background (a style change, invisible to the childList
  // observer); (c) a canvas re-sized back to full size by the page's rAF loop (re-collapse
  // to 1x1). Tag-only querySelectorAll — no full-DOM walk.
  var lastReassertMs = 0;
  setInterval(function () {
    var canvases;
    try { canvases = document.querySelectorAll('canvas'); } catch (e) { return; }
    for (var i = 0; i < canvases.length; i++) {
      var target = findTarget(canvases[i], true);
      if (!target) continue;
      var s;
      try { s = window.getComputedStyle(target); } catch (e) { continue; }
      if (s.display !== 'none') {
        killTarget(target);
      } else {
        var cs = collectCanvases(target);
        var grew = false;
        for (var k = 0; k < cs.length; k++) {
          if (cs[k].width > 1 || cs[k].height > 1) {
            try { cs[k].width = 1; cs[k].height = 1; } catch (e) { }
            grew = true;
          }
        }
        if (grew && Date.now() - lastReassertMs > 10000) {
          lastReassertMs = Date.now();
          diag('canvas_bg_reasserted', { tag: target.tagName, canvases: cs.length });
        }
      }
    }
  }, 1500);
})();
