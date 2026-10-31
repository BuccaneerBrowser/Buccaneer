/*
 * hero_churn_guard.js
 *
 * Browser-wide mitigation for the PowerVR hero-carousel texture-churn crash.
 * NOT a root-cause fix - see the GPU churn-limiter / driver-fix follow-up ticket.
 *
 * Registered as a DOCUMENT-START script in MainActivity.installScripts() (all origins);
 * runs in every frame BEFORE any page script, so it wraps window.setInterval before the
 * hero carousel registers its 8000ms timer.
 *
 * Root cause (verified): the hero carousel re-registers an 8000ms timer each cycle and
 * REPLACES the hero <img> nodes; each replacement creates/releases GPU render targets on
 * the Fire TV's PowerVR GPU, which double-frees (SIGSEGV in KEGLReleaseRenderTarget) after
 * ~4.5 min of churn. This guard stops the carousel so the hero stays static.
 *
 * Mechanism (event-driven only - NO timers, NO MutationObservers, NO polling): wrap
 * window.setInterval (then seal it) to track in-range (5000-10000ms) non-app timers while
 * passing the app's own timers through untouched; detect NEW hero nodes via a capture-phase
 * 'load' listener (a WeakSet distinguishes carousel node replacement from the image guard's
 * cache-buster re-fire); on a non-user-driven hero-load, correlate the single tracked fire
 * in the clean window and kill the carousel's re-registered timer (or the fired timer as
 * fallback). Per-route kill cap = 2. Details are annotated inline.
 *
 * Chromium 118 on API 25: NO `??` or `?.` operators.
 * Must never throw, never block page load, and be safe to re-inject.
 */
(function () {
  'use strict';

  // Idempotency: this script is re-evaluated (document-start + the
  // evaluateJavascript fallback). Re-injection is a no-op.
  if (window.__heroKiller) { return; }
  window.__heroKiller = true;

  try {
    // ---- Constants ----------------------------------------------------------
    var KILL_MIN = 5000;          // in-range lower bound (ms)
    var KILL_MAX = 10000;         // in-range upper bound (ms)
    var KILL_CAP = 2;             // per-route kill cap (steps d/e)
    var CLEAN_WINDOW_MS = 2000;   // clean-window gate width (ms)
    var HERO_FRACTION = 0.4;      // hero = width >= 0.4 * innerWidth
    var USER_SHIELD_MS = 2000;    // pointerdown within this shields the kill
    var SC3_WINDOW_MS = 40000;    // kill-ineffective window (ms)
    var MAX_TRACKED = 50;         // inventory bound
    var MAX_CALIBRATED = 3;       // calibrated-signature bound (LRU)
    var EVICT_AFTER_MS = 60000;   // evict never-fired records older than this
    var APP_TIMER_NAMES = { timedScan: 1, killCatchers: 1, netAggFlush: 1 };

    var t0 = Date.now();
    function now() { return Date.now() - t0; }

    // ---- State (live references exposed on window for verification) ---------
    var tracked = [];       // in-range non-app timers: {id, ms, src, regT, fires:[]}
    var appTimers = [];     // app timers (untouched): {id, ms, name, regT}
    var kills = [];         // {target, ms, src, t}
    var blocked = [];       // {id, ms, src, t}
    var calibrated = [];    // calibrated src signatures (max 3, LRU)
    var seenHeroes = (typeof WeakSet === 'function') ? new WeakSet() : null;
    var heroLoads = 0;
    var lastPointerDown = -1e9;
    var lastKillT = -1e9;
    var fakeId = -1;

    window.__heroTimersKilled = kills;
    window.__heroTimersBlocked = blocked;
    window.__heroTimersTracked = tracked;
    window.__heroAppTimers = appTimers;
    window.__heroCalibrated = calibrated;
    window.__heroLoads = 0;

    function diag(event, data) {
      try {
        if (window.Buccaneer && typeof window.Buccaneer.diagnosticEvent === 'function') {
          window.Buccaneer.diagnosticEvent(String(event), JSON.stringify(data || {}));
        }
      } catch (e) { /* diagnostics are best effort */ }
    }

    function dump() {
      diag('hero_state_dump', {
        tracked: tracked.length,
        appTimers: appTimers.length,
        kills: kills.length,
        blocked: blocked.length,
        calibrated: calibrated.length,
        heroLoads: heroLoads
      });
    }

    // ---- 1. setInterval wrapper ---------------------------------------------
    var origSetInterval = window.setInterval;
    var origClearInterval = window.clearInterval;
    if (typeof origSetInterval !== 'function') { return; }

    function isAppTimer(fn) {
      try { return !!(fn && fn.name && APP_TIMER_NAMES[fn.name]); } catch (e) { return false; }
    }

    function inRange(ms) {
      return typeof ms === 'number' && ms >= KILL_MIN && ms <= KILL_MAX;
    }

    function findCalibrated(src) {
      for (var i = 0; i < calibrated.length; i++) {
        if (calibrated[i] === src) { return i; }
      }
      return -1;
    }

    function calibrate(src) {
      if (!src) { return; }
      var i = findCalibrated(src);
      if (i === -1) {
        calibrated.push(src);
        if (calibrated.length > MAX_CALIBRATED) { calibrated.shift(); } // LRU evict oldest
      } else {
        calibrated.splice(i, 1);   // move to end (most-recently-used)
        calibrated.push(src);
      }
    }

    function evictStale() {
      var cutoff = now() - EVICT_AFTER_MS;
      for (var i = 0; i < tracked.length; i++) {
        var r = tracked[i];
        if (r.fires.length === 0 && r.regT < cutoff) {
          tracked.splice(i, 1);
          i--;
        }
      }
      if (tracked.length > MAX_TRACKED) {
        tracked.splice(0, tracked.length - MAX_TRACKED);
      }
    }

    function wrappedSetInterval(fn, ms) {
      var args = Array.prototype.slice.call(arguments, 2);
      try {
        if (typeof fn !== 'function') {
          return origSetInterval.apply(window, arguments);
        }
        // App timer: record for verification, pass through COMPLETELY UNTOUCHED
        // (no callback wrapping, no fire tracking, never a kill candidate).
        if (isAppTimer(fn)) {
          var idApp = origSetInterval.apply(window, arguments);
          appTimers.push({ id: idApp, ms: ms, name: fn.name, regT: now() });
          return idApp;
        }
        if (inRange(ms)) {
          var src = '';
          try { src = String(fn).slice(0, 120); } catch (e) { /* leave empty */ }
          // Block a late re-registration that matches a calibrated signature.
          if (findCalibrated(src) !== -1) {
            var fid = fakeId--;
            blocked.push({ id: fid, ms: ms, src: src, t: now() });
            diag('hero_timer_blocked', { ms: ms, src: src });
            return fid;
          }
          var rec = { id: 0, ms: ms, src: src, regT: now(), fires: [] };
          var wrapped = function () {
            try { rec.fires.push(now()); } catch (e) { /* never throw */ }
            return fn.apply(this, arguments);
          };
          rec.id = origSetInterval.apply(window, [wrapped, ms].concat(args));
          tracked.push(rec);
          evictStale();
          return rec.id;
        }
        // Out of range: pass through untouched.
        return origSetInterval.apply(window, arguments);
      } catch (e) {
        // Last resort: never break the page's own timer.
        try { return origSetInterval.apply(window, arguments); } catch (e2) { return 0; }
      }
    }

    window.setInterval = wrappedSetInterval;
    // Seal: block a page from reassigning window.setInterval to bypass the wrapper. App timers are unaffected (they call the sealed wrapper, which tags them and forwards to the original). If the seal fails, fall back to a plain (reassignable) assignment.
    try {
      Object.defineProperty(window, 'setInterval', {
        value: wrappedSetInterval,
        writable: false,
        configurable: false
      });
    } catch (e) {
      try { window.setInterval = wrappedSetInterval; } catch (e2) { /* ignore */ }
    }

    // ---- 2 + 3. Hero-load detection and kill targeting ----------------------
    function sc1(reason) {
      console.warn('[hero-killer] hero-load #2 with no kill; crash risk remains (' + reason + ')');
      diag('hero_sc1_no_kill', { reason: reason });
      dump();
    }

    function handleHeroLoad(B) {
      heroLoads++;
      window.__heroLoads = heroLoads;
      var idx = heroLoads;

      // SC3 (kill ineffective): a hero-load within 40s of the last kill means the carousel is still cycling (production form of the multi-cycle stop assertion).
      if (lastKillT > -1e8 && (B - lastKillT) < SC3_WINDOW_MS) {
        console.warn('[hero-killer] kill ineffective; churn continues');
        diag('hero_kill_ineffective', { sinceKill: B - lastKillT });
        dump();
      }

      // Non-user-driven rule: a pointerdown within USER_SHIELD_MS shields this hero-load (a user tap on the carousel arrows). TV remote D-pad emits keydown, not pointerdown, so remote navigation does NOT shield.
      if (B - lastPointerDown < USER_SHIELD_MS) { return; }

      // Step (b): CLEAN-WINDOW CHECK (HARD GATE). Count the tracked (non-app)
      // fires in the open interval (B - CLEAN_WINDOW_MS, B).
      var count = 0;
      var F = null;
      for (var i = 0; i < tracked.length; i++) {
        var fires = tracked[i].fires;
        for (var j = 0; j < fires.length; j++) {
          var ft = fires[j];
          if (ft > B - CLEAN_WINDOW_MS && ft < B) {
            count++;
            if (count === 1) {
              F = { t: ft, src: tracked[i].src, id: tracked[i].id, ms: tracked[i].ms };
            }
          }
        }
      }
      if (count === 0) {
        // No F, no kill (initial render, or the first load-time timer was
        // cleared before ticking).
        return;
      }
      if (count > 1) {
        // Ambiguous: an unrelated in-range timer fired in the same window as
        // the carousel. NO kill (this is the false-kill path, closed).
        console.warn('[hero-killer] ambiguous clean window (' + count + ' fires); no kill');
        diag('hero_ambiguous_window', { count: count });
        return;
      }

      // count === 1: F is the carousel fire.
      // Step (c): N = most recently registered tracked timer with
      // regT > (F.t - 100ms) [100ms tolerance absorbs clock granularity].
      var N = null;
      for (var k = 0; k < tracked.length; k++) {
        var r = tracked[k];
        if (r.regT > F.t - 100) {
          if (N === null || r.regT > N.regT) { N = r; }
        }
      }

      // Steps (d)/(e): kill the re-registered timer (N.src === F.src) or the
      // fired timer (F) as the persistent-carousel fallback.
      var isNextReg = (N !== null && N.src === F.src);
      var targetId = isNextReg ? N.id : F.id;
      var targetMs = isNextReg ? N.ms : F.ms;
      var targetName = isNextReg ? 'next-registered' : 'fired';

      if (kills.length < KILL_CAP) {
        try { origClearInterval(targetId); } catch (e) { /* ignore */ }
        kills.push({ target: targetName, ms: targetMs, src: F.src, t: B });
        calibrate(F.src);   // step (f): block further matching registrations
        lastKillT = B;
        diag('hero_timer_killed', { target: targetName, ms: targetMs, src: F.src });
      } else {
        console.warn('[hero-killer] kill cap reached; churn continues');
        diag('hero_kill_cap_reached', {});
      }

      // SC1 (no kill): after hero-load event #2, if no kill has happened, the
      // mitigation is not working and the crash risk remains.
      if (idx === 2 && kills.length === 0) {
        sc1('no kill after hero-load #2');
      }
    }

    function onLoad(ev) {
      var t = ev.target || ev.srcElement;
      if (!t || t.tagName !== 'IMG') { return; }
      var iw = window.innerWidth || (document.documentElement && document.documentElement.clientWidth) || 0;
      if (!iw) { return; }
      // First-pass filter (NO layout query): an image whose intrinsic width is below the hero threshold cannot be a hero, so skip it before touching getBoundingClientRect(); this avoids forcing a reflow on every poster/thumbnail load (which blocked the main thread, ANR, on heavy pages).
      var nw = 0;
      try { nw = t.naturalWidth; } catch (e) { return; }
      if (nw < HERO_FRACTION * iw) { return; }
      var w;
      try { w = t.getBoundingClientRect().width; } catch (e) { return; }
      if (w < HERO_FRACTION * iw) { return; }          // not a hero (rendered)
      if (seenHeroes && seenHeroes.has(t)) { return; } // existing node re-fire
      if (seenHeroes) { seenHeroes.add(t); }
      handleHeroLoad(now());
    }

    document.addEventListener('load', onLoad, true);
    document.addEventListener('pointerdown', function () { lastPointerDown = now(); }, true);

    // ---- 5. SPA reset --------------------------------------------------------
    // On route change, reset the kill state so a re-entry uses kill 1 (the cap is per-route). The load/pointerdown listeners are document-level and persist across SPA navigation - no re-attachment needed.
    function resetState() {
      kills.length = 0;
      blocked.length = 0;
      calibrated.length = 0;
      tracked.length = 0;
      appTimers.length = 0;
      heroLoads = 0;
      window.__heroLoads = 0;
      lastKillT = -1e9;
      if (typeof WeakSet === 'function') { seenHeroes = new WeakSet(); }
      diag('hero_spa_reset', {});
    }

    try {
      var origPushState = history.pushState;
      var origReplaceState = history.replaceState;
      if (typeof origPushState === 'function') {
        history.pushState = function () {
          var r = origPushState.apply(history, arguments);
          try { resetState(); } catch (e) { /* ignore */ }
          return r;
        };
      }
      if (typeof origReplaceState === 'function') {
        history.replaceState = function () {
          var r = origReplaceState.apply(history, arguments);
          try { resetState(); } catch (e) { /* ignore */ }
          return r;
        };
      }
      window.addEventListener('popstate', function () {
        try { resetState(); } catch (e) { /* ignore */ }
      }, true);
    } catch (e) { /* SPA hooks are best effort */ }

    diag('hero_killer_installed', {
      killMin: KILL_MIN, killMax: KILL_MAX, killCap: KILL_CAP,
      cleanWindowMs: CLEAN_WINDOW_MS, heroFraction: HERO_FRACTION
    });
  } catch (e) { /* never throw, never block page load */ }
})();
