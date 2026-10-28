/*
 * the poster-grid site poster hover-preview guard.
 *
 * the poster-grid site's poster grids use a single floating card element (position:fixed,
 * z-index:50, bg-[#181818], contains an <img>) that serves TWO purposes:
 *
 *   1. HOVER-PREVIEW: auto-revealed when a poster gains focus (DPAD on a TV).
 *      This covers the poster and intercepts focus, making the grid hard to
 *      navigate. NOT wanted (should be open on click, not mouseover).
 *
 *   2. DETAIL MODAL: revealed when a poster is CLICKED. Shows the enlarged
 *      poster + metadata + a "Play" button that navigates to the watch page.
 *      THIS IS THE NORMAL CLICK-TO-OPEN FLOW and MUST stay visible.
 *
 * The two uses are the SAME DOM element, so we cannot distinguish them by
 * structure. We distinguish them by the TRIGGER that revealed the card:
 *   - last interaction was a CLICK on a poster  -> detail modal -> keep visible
 *   - last interaction was FOCUS on a poster     -> hover-preview -> hide it
 *
 * Mechanism: a CSS rule with `!important` (beats the page's inline opacity:1)
 * targets a class we add to the card. We add the class only when the card was
 * revealed by focus; we remove it when the card was revealed by click. The card
 * is found by a TARGETED scan (divs carrying a `z-50` + `bg-` class, then
 * checked for position:fixed + contains <img>) — NOT a full-DOM walk, so it
 * does not trigger the PowerVR driver crash the full popup scanner does.
 *
 * Re-applied event-driven (deferred ~150 ms after a tracked poster click or
 * focus) plus a slow 2 s safety-net interval for SPA re-creation, where the
 * card element is rebuilt without a page commit (no re-injection).
 * Must never throw, never block page load, safe to re-inject.
 *
 * Chromium 118 on API 25: NO `??` or `?.` operators.
 */
(function () {
  'use strict';

  // Only act on the poster-grid site hosts.
  var __h = '';
  try { __h = (window.location.hostname || '').toLowerCase().replace(/\.$/, ''); } catch (e) {}
  if (!/(^|\.)flixer\.(gd|su)$/.test(__h)) return;

  // Idempotency: re-evaluated on every poster-grid site page commit.
  if (window.__flixerPosterGuard) return;
  window.__flixerPosterGuard = true;

  var CLASS = '__flixer_hover_preview';
  var STYLE_ID = '__flixer_poster_guard_style';

  // 'click' | 'focus' | '' — the last interaction that could have revealed the
  // floating card. A click reveals the detail modal (keep visible); a focus
  // reveals the hover-preview (hide it).
  var lastEventType = '';

  function log(msg, data) {
    try {
      if (window.Buccaneer && typeof window.Buccaneer.diagnosticEvent === 'function') {
        window.Buccaneer.diagnosticEvent(String(msg), JSON.stringify(data || {}));
      }
    } catch (e) { /* never throw — diagnostics are best effort */ }
  }

  function ensureStyle() {
    try {
      if (document.getElementById(STYLE_ID)) return;
      var st = document.createElement('style');
      st.id = STYLE_ID;
      // `!important` beats the page's inline opacity:1, keeping the preview hidden.
      st.textContent = '.' + CLASS + ' { opacity: 0 !important; pointer-events: none !important; }';
      (document.head || document.documentElement).appendChild(st);
    } catch (e) { /* never throw */ }
  }

  // Whole-class match (avoids `z-10` matching `z-100` via substring).
  function hasClass(cls, name) {
    var i, arr = cls.split(' ');
    for (i = 0; i < arr.length; i++) if (arr[i] === name) return true;
    return false;
  }

  // True if the element (or an ancestor up to the poster card) is a poster card.
  // Poster cards carry `cursor-pointer` + `z-10` (verified against live markup:
  // every poster card has both; nav links, buttons, and layout divs do not).
  function isPosterCard(el) {
    try {
      var node = el;
      while (node && node !== document) {
        var cls = '';
        try { cls = (node.className || '').toString(); } catch (e) {}
        if (hasClass(cls, 'cursor-pointer') && hasClass(cls, 'z-10')) return true;
        node = node.parentElement;
      }
    } catch (e) { /* never throw */ }
    return false;
  }

  // Track the last interaction that could reveal the floating card.
  // Capture phase so we see the event before the page's own handlers.
  document.addEventListener('click', function (e) {
    try {
      if (isPosterCard(e.target)) { lastEventType = 'click'; scheduleApply(); }
    } catch (e) { /* never throw */ }
  }, true);
  document.addEventListener('focusin', function (e) {
    try {
      if (isPosterCard(e.target)) { lastEventType = 'focus'; scheduleApply(); }
    } catch (e) { /* never throw */ }
  }, true);

  function findCard() {
    try {
      // Targeted + cheap: the card carries a `z-50` class (Tailwind z-index:50)
      // and a `bg-` class. Filter by both via CSS (no getComputedStyle needed
      // for the match), then verify position:fixed + contains <img> on the few
      // remaining candidates. Keeps per-tick cost low (a handful of
      // getComputedStyle calls, not hundreds).
      var cands = document.querySelectorAll('div[class~="z-50"][class*="bg-"]');
      for (var i = 0; i < cands.length; i++) {
        var el = cands[i];
        var s;
        try { s = window.getComputedStyle(el); } catch (e) { continue; }
        if (s.position !== 'fixed') continue;
        if (!el.querySelector('img')) continue;
        return el;
      }
    } catch (e) { /* never throw */ }
    return null;
  }

  var loggedHide = false;
  var loggedShow = false;
  function apply() {
    ensureStyle();
    var card = findCard();
    if (!card) return;
    if (lastEventType === 'click') {
      // Detail modal (revealed by click): keep it visible. Remove the hiding
      // class in case it was added earlier (e.g. a focus happened before the
      // click on the same card).
      try {
        if (card.classList.contains(CLASS)) {
          card.classList.remove(CLASS);
          if (!loggedShow) { loggedShow = true; log('detail_shown', { cls: String(card.className).slice(0, 60) }); }
        }
      } catch (e) { /* never throw */ }
      return;
    }
    // Hover-preview (revealed by focus, or no tracked interaction): hide it.
    var had = false;
    try { had = card.classList.contains(CLASS); } catch (e) {}
    if (!had) {
      try { card.classList.add(CLASS); } catch (e) { }
      if (!loggedHide) { loggedHide = true; log('preview_hidden', { cls: String(card.className).slice(0, 60) }); }
    }
  }

  // Event-driven re-check. A tracked poster click/focus is the only thing that
  // reveals the card, so defer one apply() after each such event. The 150 ms
  // deferral lets the page's own handlers run first (the card may be created
  // or shown in response to the event). A single pending timer debounces
  // rapid DPAD focus events (one apply per burst, not one per keypress).
  var pendingApplyTimer = null;
  function scheduleApply() {
    if (pendingApplyTimer !== null) return;
    try {
      pendingApplyTimer = setTimeout(function () {
        pendingApplyTimer = null;
        apply();
      }, 150);
    } catch (e) { pendingApplyTimer = null; }
  }

  apply();
  // Safety net only: SPA navigation re-creates the card without a page commit
  // (no re-injection), so a slow periodic re-check must remain. 2 s (was
  // 200 ms) — the event-driven path above handles reveal latency.
  setInterval(apply, 2000);
})();
