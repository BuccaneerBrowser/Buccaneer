/*
 * Video Layer Guard — black-video (audio-only) fix for movy.sx.
 *
 * Symptom: the <video> element is fully healthy (readyState=4, playing, visible,
 * 853x480) but the user sees a black screen with audio. The video recovers on its
 * own after a variable interval. Diagnostic data (movy_black2.jsonl, 14:47:30-
 * 14:50:58 black window) shows 70 consecutive healthy media_health events with
 * zero network errors, zero guard kills, zero render resets, and NO memory
 * pressure (black window PSS 78MB < healthy window 87MB). This is a rendering
 * issue: the video's rasterized frames are not reaching the screen.
 *
 * Mechanism (matches the hero-image fix f61956c): the PowerVR GPU drops the
 * rasterized content of a composited layer under sustained load. The hero fix
 * gave the element its own compositor layer (position:fixed) so it survived the
 * drop. For the video, we force it into its own compositor layer via
 * will-change:transform. This is safe: it does not change the element's layout
 * position (unlike position:fixed), it only promotes the element to its own
 * GPU layer so the compositor keeps its raster alive.
 *
 * Why will-change:transform (not position:fixed): the video is a <video>
 * element that the player positions within its container. position:fixed would
 * pull it out of the layout flow and break the player's positioning.
 * will-change:transform promotes the element to its own compositor layer
 * WITHOUT changing its layout position — the safest possible promotion.
 *
 * Detection: a single bounded scan for <video> elements (querySelectorAll,
 * tag-only, no full-DOM walk). Applied once per video element (idempotent via a
 * data attribute). A 5s backstop re-scan covers the case where the player
 * swaps <video> elements (destroys one, creates a new one).
 *
 * Top frame only: the video lives in the top document on movy.sx. Running in
 * cross-origin player iframes risks interfering with a player that manages its
 * own layers.
 *
 * Host-scoped to movy.* (any TLD/subdomain): the black-video issue is observed on
 * movy.sx. Per the
 * host-scoping rule, a host-specific problem gets a host-scoped guard.
 *
 * ES5 only (Chromium 118 / API 25: no ?? or ?. operators). Never throws. No
 * full-DOM walk — only <video> elements are style-inspected.
 */
(function () {
  'use strict';

  if (window.__videoLayerGuard) return;
  try {
    Object.defineProperty(window, '__videoLayerGuard', { value: true });
  } catch (e) {
    window.__videoLayerGuard = true;
  }

  // Top frame only — see the header comment.
  if (window !== window.top) return;

  // Host-scoped to movy.* — see the header comment.
  try {
    var host = (window.location.host || '').toLowerCase();
    if (!/(^|\.)movy\.[a-z0-9-]+$/.test(host)) return;
  } catch (e) { return; }

  function diag(event, data) {
    try {
      if (window.Buccaneer && typeof window.Buccaneer.diagnosticEvent === 'function') {
        window.Buccaneer.diagnosticEvent(String(event), JSON.stringify(data || {}));
      }
    } catch (e) { /* diagnostics are best effort */ }
  }

  // Promote a <video> element to its own compositor layer. Idempotent: skips
  // elements already marked.
  function promoteVideo(v) {
    if (!v || v.tagName !== 'VIDEO') return false;
    if (v.__vlPromoted) return false;
    try {
      v.style.willChange = 'transform';
      v.__vlPromoted = true;
      return true;
    } catch (e) { return false; }
  }

  // Throttle for the cover diagnostic: only re-log when the covering element
  // changes or 30s pass, so a persistent cover doesn't flood the log.
  var lastCoverKey = '';
  var lastCoverLog = 0;

  // Diagnostic: find the topmost element at the video's center pixel. If it is
  // not the video (or a descendant of it), something is covering the video —
  // the likely cause of "healthy video, black screen". Log its identity so we
  // can see exactly what is on top. ES5, throw-safe, no DOM walk (single
  // elementFromPoint per video).
  function checkCover(v) {
    try {
      var r = v.getBoundingClientRect();
      if (!r || r.width < 50 || r.height < 50) return;
      var cx = r.left + r.width / 2;
      var cy = r.top + r.height / 2;
      var top = document.elementFromPoint(cx, cy);
      if (!top) return;
      // The video itself or a descendant of it is fine (that IS the video).
      if (top === v || v.contains(top)) return;
      // Something else is on top of the video's center pixel.
      var cs = window.getComputedStyle(top);
      var key = top.tagName + '|' + (top.className || '') + '|' + (cs.zIndex || 'auto') + '|' + (cs.backgroundColor || '');
      var now = Date.now();
      if (key === lastCoverKey && (now - lastCoverLog) < 30000) return;
      lastCoverKey = key;
      lastCoverLog = now;
      // Walk up the ancestor chain (bounded: 4 levels) to find any opaque or
      // image background that would actually hide the video. This is a short
      // parent walk, NOT a full-DOM walk.
      var chain = [];
      var anc = top;
      for (var a = 0; a < 4 && anc && anc !== document.body; a++) {
        anc = anc.parentElement;
        if (!anc) break;
        var acs = window.getComputedStyle(anc);
        chain.push({
          tag: anc.tagName,
          cls: String(anc.className || '').slice(0, 60),
          bg: acs.backgroundColor || '',
          bgi: (acs.backgroundImage || 'none') !== 'none' ? 'img' : '',
          z: acs.zIndex || 'auto',
          pos: acs.position || 'static'
        });
      }
      diag('video_covered', {
        tag: top.tagName,
        cls: String(top.className || '').slice(0, 80),
        id: top.id || '',
        z: cs.zIndex || 'auto',
        pos: cs.position || 'static',
        bg: cs.backgroundColor || '',
        bgi: (cs.backgroundImage || 'none') !== 'none' ? 'img' : '',
        chain: chain
      });
    } catch (e) { /* never throw */ }
  }

  // Bounded scan for <video> elements. Tag-only querySelectorAll — no full-DOM
  // walk. Promotes each un-promoted video to its own compositor layer and
  // checks whether something is covering it.
  function scan() {
    var promoted = 0;
    try {
      var vids = document.querySelectorAll('video');
      for (var i = 0; i < vids.length; i++) {
        if (promoteVideo(vids[i])) promoted++;
        checkCover(vids[i]);
      }
    } catch (e) { /* never throw */ }
    if (promoted > 0) {
      diag('video_layer_promoted', { count: promoted });
    }
  }

  // Initial scan + 5s backstop (covers the player swapping <video> elements).
  // The 5s interval is low-frequency (no continuous polling) and does a
  // tag-only querySelectorAll (no full-DOM walk), so it stays within the
  // Invariant 7 constraints.
  scan();
  setInterval(scan, 5000);
})();
