/*
 * Flixer poster -> info page. DOCUMENT-START script, all origins (self-gates
 * to flixer.* below). Wired in MainActivity.installScripts().
 *
 * WHY: on the TV a poster click is a silent no-op in the site's desktop path,
 * and the hover popup that briefly appears is too short-lived to navigate.
 * The popup's "i" button (aria-label "search.moreInfo") opens the show info
 * view (season/episode selectable) — that is the required destination.
 *
 * HOW (deliberately simple): when the user activates a poster card, make sure
 * the popup is up, then click its "i" button — the site's own control, doing
 * the site's own thing. No screen-size spoofing, no layout involvement.
 *
 * Must never throw, never block the page, safe to re-inject (idempotent).
 */
(function () { 'use strict';
  if (window.__flixerPosterInfo) { return; }
  window.__flixerPosterInfo = true;
  try {
    var host = (location.host || '').toLowerCase();
    if (!/(^|\.)flixer\.[a-z0-9-]+$/.test(host)) { return; }

    var lastAction = 0;

    function isFlixerCard(el) {
      // Walk up from the click target to the poster card wrapper.
      var cur = el;
      for (var hops = 0; cur && cur !== document.body && hops < 8; hops++) {
        var cls = String(cur.className || '');
        if (cls.indexOf('cursor-pointer') !== -1 &&
            cur.querySelector && cur.querySelector('img[src*="tmdb"], img[data-src*="tmdb"]')) {
          return cur;
        }
        cur = cur.parentElement;
      }
      return null;
    }

    function findPopup() {
      var divs = document.querySelectorAll('div[class*="bg-[#181818]"]');
      for (var i = 0; i < divs.length; i++) {
        if (divs[i].querySelector('a[href^="/watch/"]')) { return divs[i]; }
      }
      return null;
    }

    function clickInfoButton(popup) {
      var btns = popup.querySelectorAll('button');
      for (var i = 0; i < btns.length; i++) {
        var label = (btns[i].getAttribute('aria-label') || '').toLowerCase();
        if (label.indexOf('info') !== -1) { btns[i].click(); return true; }
      }
      if (btns.length > 0) { btns[btns.length - 1].click(); return true; } // last button = "i"
      return false;
    }

    function openInfo(card) {
      // Bring the popup up (the site shows it on hover/focus of the card).
      try {
        card.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, cancelable: true }));
      } catch (e) { /* never throw */ }
      // Bounded settle window: the popup fades in over ~180ms.
      var tries = 0;
      var timer = setInterval(function () {
        tries++;
        var popup = findPopup();
        if (popup) {
          clearInterval(timer);
          clickInfoButton(popup);
        } else if (tries >= 15) {
          clearInterval(timer);
        }
      }, 100);
    }

    document.addEventListener('click', function (ev) {
      try {
        var now = Date.now();
        if (now - lastAction < 2000) { return; } // debounce double activations
        var t = ev.target;
        if (!t || !t.closest) { return; }
        // Real links/buttons keep their own behavior (e.g. Play).
        if (t.closest('a, button')) { return; }
        var card = isFlixerCard(t);
        if (!card) { return; }
        lastAction = now;
        openInfo(card);
      } catch (e) { /* never throw */ }
    }, true);
  } catch (e) { /* never throw */ }
})();
