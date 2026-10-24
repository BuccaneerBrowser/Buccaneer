/*
 * Buccaneer page helpers (window.__fb). Runs in every frame at document start.
 * The app calls these through evaluateJavascript() in the top frame:
 *   video:   togglePlay() play() pause() seek(sec) volume(delta) toggleMute() state() prepareFullscreen()
 *            -> "ok" | "frame" (player is in a frame we cannot script) | "none"
 *            frameCenter() (where to tap a player we cannot script), diag() (Settings > Diagnostics)
 *   typing:  field() focusIntoView() clear()
 * It also tells the app when a real tap landed in a text field, so the keypad can open by itself,
 * and keeps a page from playing two videos at once (see "one video at a time" below).
 */
(function () {
  'use strict';

  if (window.__fbInstalled) return;
  window.__fbInstalled = true;

  // the poster-grid site: skip all instrumentation. The PowerVR GPU driver on this Fire TV crashes
  // (SIGSEGV in KEGLReleaseRenderTarget) when our DOM observers, HLS hooks, and event
  // listeners run alongside the poster-grid site's intro animation. The passive __fb API is still
  // exposed (video controls, typing) but no continuous capture, timers, or synthetic
  // clicks are installed on the poster-grid site-owned documents.
  var __host = '';
  try { __host = (window.location.hostname || '').toLowerCase().replace(/\.$/, ''); } catch (e) {}
  var __isFlixer = /(^|\.)flixer\.(gd|su)$/.test(__host);
  var __isStreamCorner = /(^|\.)streamcorner\.st$/.test(__host);

  // NOTE: dark mode is handled by the WebView's native force-dark (FORCE_DARK_ON),
  // configured in MainActivity.applyDarkMode(). Do NOT inject CSS filters here —
  // filter:invert() on <html> crashes the PowerVR GPU process on the Fire TV
  // (SIGSEGV in libGLESv2_POWERVR_ROGUE.so), which took down the poster-grid site host.

  var MIN_SIDE = 100;

  // ------------------------------------------------------------------ finding the video

  function fsElement() {
    return document.fullscreenElement || document.webkitFullscreenElement || null;
  }

  function isVisible(el) {
    var r;
    try { r = el.getBoundingClientRect(); } catch (e) { return false; }
    if (!r || r.width < MIN_SIDE || r.height < MIN_SIDE) return false;
    var win = (el.ownerDocument && el.ownerDocument.defaultView) || window;
    if (r.bottom <= 0 || r.right <= 0 || r.top >= win.innerHeight || r.left >= win.innerWidth) return false;
    try {
      var cs = win.getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none') return false;
    } catch (e) { /* ignore */ }
    return true;
  }

  // all <video> elements in this document and in same-origin iframes
  function collect(doc, depth, out) {
    var vids = doc.getElementsByTagName('video');
    for (var i = 0; i < vids.length; i++) out.push(vids[i]);
    if (depth <= 0) return;
    var frames = doc.getElementsByTagName('iframe');
    for (var j = 0; j < frames.length; j++) {
      try {
        var d = frames[j].contentDocument;
        if (d) collect(d, depth - 1, out);
      } catch (e) { /* cross-origin frame */ }
    }
  }

  // the video that is full screen, else the biggest visible one (a playing one counts 1.5x)
  function findVideo() {
    var fe = fsElement();
    if (fe) {
      if (fe.tagName === 'VIDEO') return fe;
      var inner = fe.querySelector ? fe.querySelector('video') : null;
      if (inner) return inner;
    }
    var all = [];
    collect(document, 2, all);
    var best = null;
    var bestScore = 0;
    for (var i = 0; i < all.length; i++) {
      var v = all[i];
      if (!isVisible(v)) continue;
      var r = v.getBoundingClientRect();
      var score = r.width * r.height * ((!v.paused && !v.ended) ? 1.5 : 1);
      if (score > bestScore) {
        best = v;
        bestScore = score;
      }
    }
    return best;
  }

  // a frame from another site: we cannot look inside, but the player may live in there
  function isOpaque(f) {
    try { return !f.contentDocument; } catch (e) { return true; }
  }

  function area(el) {
    try {
      var r = el.getBoundingClientRect();
      return r.width * r.height;
    } catch (e) { return 0; }
  }

  // the biggest visible frame from another site
  function bigFrame() {
    var frames = document.getElementsByTagName('iframe');
    var best = null;
    var bestArea = 0;
    for (var i = 0; i < frames.length; i++) {
      var f = frames[i];
      if (!isVisible(f) || !isOpaque(f)) continue;
      var a = area(f);
      if (a > bestArea) {
        bestArea = a;
        best = f;
      }
    }
    return best;
  }

  function countOpaqueFrames() {
    var frames = document.getElementsByTagName('iframe');
    var n = 0;
    for (var i = 0; i < frames.length; i++) {
      if (isVisible(frames[i]) && isOpaque(frames[i])) n++;
    }
    return n;
  }

  // What should the remote control? A video that is playing right here wins. Otherwise a big frame
  // from another site (a typical embedded player) beats a small video (an ad or a banner).
  // Returns {video: el}, {frame: el} or null.
  // Passive selected-player identity. No new the poster-grid site listeners or timers.
  var selectedTarget = null;
  function attachedTarget(el) {
    try {
      var doc=el.ownerDocument||document,win=doc.defaultView||window;
      if(!doc.documentElement.contains(el))return false;
      for(var depth=0;win!==window && depth<3;depth++) {
        var frame=win.frameElement;
        if(!frame || !isVisible(frame) || !frame.ownerDocument.documentElement.contains(frame))return false;
        win=frame.ownerDocument.defaultView;
      }
      return win===window;
    }catch(e){return false;}
  }
  function videoInFrame(frame) {
    try {
      var doc=frame.contentDocument;if(!doc)return null;
      var all=[],best=null,score=0;collect(doc,2,all);
      for(var i=0;i<all.length;i++)if(isVisible(all[i])){
        var r=all[i].getBoundingClientRect(),n=r.width*r.height*((!all[i].paused&&!all[i].ended)?1.5:1);
        if(n>score){score=n;best=all[i];}
      }
      return best;
    }catch(e){return null;}
  }
  function pickTarget() {
    if (selectedTarget) {
      var el = selectedTarget.video || selectedTarget.frame;
      try { if (attachedTarget(el) && isVisible(el)) return selectedTarget; } catch (e) {}
      selectedTarget = null;
    }
    var active = document.activeElement;
    if (active && active.tagName === 'IFRAME' && isVisible(active)) {var inside=videoInFrame(active);return inside?{video:inside}:{frame:active};}
    var v = findVideo();
    if (v) return {video: v};
    var f = bigFrame();
    return f ? {frame: f} : null;
  }

  function selectAt(x, y) {
    var el = document.elementFromPoint(x, y), node = el;
    if (el && el.tagName === 'IFRAME') {var inside=videoInFrame(el);selectedTarget=inside?{video:inside}:{frame:el};return inside?'ok':'frame';}
    for (var i=0;node && i<8;i++,node=node.parentElement) {
      if (node === document.body || node === document.documentElement) break;
      if (node.tagName === 'VIDEO') { selectedTarget = {video:node}; return 'ok'; }
      var videos = node.querySelectorAll ? node.querySelectorAll('video') : [];
      if (videos.length === 1 && isVisible(videos[0])) { selectedTarget = {video:videos[0]}; return 'ok'; }
      if (videos.length > 1) break;
    }
    return 'none';
  }

  // ------------------------------------------------------------------ cross-frame video commands
  //
  // The app can only script the top frame, but this helper runs in every frame. When the
  // player's video lives in a frame from another site, the top frame forwards the command to
  // that frame's copy of this helper, which runs it on its own video and answers back. That
  // is how Play/Pause and FF/Rewind reach players we cannot script directly — in full screen
  // as well as normal.
  var frameCmdSeq = 0;
  var lastFrameState = null; // { at: ms, state: {...} } — newest state a player frame reported

  function frameIsFlixer(frame) {
    try { return /(^|\.)flixer\.(gd|su)$/.test(((frame.src || '').split('/')[2] || '').toLowerCase()); } catch (e) { return false; }
  }

  function forwardToFrame(frame, cmd, arg) {
    try {
      frameCmdSeq++;
      frame.contentWindow.postMessage({ __fbCmd: 1, id: frameCmdSeq, cmd: cmd, arg: arg }, '*');
      return true;
    } catch (e) { return false; }
  }

  // Run a video command on one <video>. Returns 'ok' | 'failed'.
  function runOnVideo(v, cmd, arg) {
    switch (cmd) {
      case 'togglePlay':
        if (v.paused || v.ended) safePlay(v); else { cancelPlayAttempt(); v.pause(); }
        return 'ok';
      case 'play':
        safePlay(v);
        return 'ok';
      case 'pause':
        cancelPlayAttempt();
        var all = []; collect(document, 2, all);
        for (var i = 0; i < all.length; i++) { try { all[i].pause(); } catch (e) { /* ignore */ } }
        return all.length ? 'ok' : 'none';
      case 'seek':
        if (!isFinite(arg) || !isFinite(v.currentTime)) return 'failed';
        var wanted = Math.max(0, v.currentTime + arg);
        if (isFinite(v.duration)) wanted = Math.min(v.duration, wanted);
        var ranges = v.seekable;
        if (ranges && ranges.length) {
          var inside = false, nearest = 0, distance = Infinity;
          for (var j = 0; j < ranges.length; j++) {
            var a = ranges.start(j), b = ranges.end(j);
            if (wanted >= a && wanted <= b) { inside = true; break; }
            var edge = wanted < a ? a : b, dm = Math.abs(edge - wanted);
            if (dm < distance) { distance = dm; nearest = edge; }
          }
          if (!inside) wanted = nearest;
        }
        v.currentTime = wanted;
        return Math.abs(v.currentTime - wanted) <= 1 ? 'ok' : 'failed';
      case 'volume':
        v.muted = false;
        v.volume = Math.max(0, Math.min(1, Math.round((v.volume + arg) * 100) / 100));
        return 'ok';
      case 'toggleMute':
        v.muted = !v.muted;
        return 'ok';
      case 'unmute':
        var done = 0, vids = document.getElementsByTagName('video');
        for (var k = 0; k < vids.length; k++) { try { if (vids[k].muted) { vids[k].muted = false; done++; } } catch (e) { /* ignore */ } }
        return done > 0 ? 'ok' : 'none';
      default:
        return 'failed';
    }
  }

  // Run a command on this frame's own video (called by the message listener below).
  function frameCmd(cmd, arg) {
    var t = pickTarget();
    if (!t) return 'none';
    if (t.video) {
      try { return runOnVideo(t.video, cmd, arg); } catch (e) { return 'failed'; }
    }
    if (t.frame) {
      // The video sits in a nested frame: hand the command one level down.
      return forwardToFrame(t.frame, cmd, arg) ? 'frame' : 'none';
    }
    return 'none';
  }

  window.addEventListener('message', function (e) {
    var m = e.data;
    if (!m) return;
    if (m.__fbRes === 1) {
      // A player frame answered one of our commands: keep its reported state fresh.
      if (m.state && typeof m.state === 'object') lastFrameState = { at: Date.now(), state: m.state };
      return;
    }
    if (m.__fbFs === 1 || m.__fbFsCmd === 1) {
      if (__isFlixer) return; // the poster-grid site keeps the native fullscreen path.
      if (m.__fbFs === 1) {
        // A child frame entered/exited CSS fullscreen: fill/restore its iframe element.
        var fr = null;
        if (e.source) {
          var frames = document.getElementsByTagName('iframe');
          for (var i = 0; i < frames.length; i++) {
            try { if (frames[i].contentWindow === e.source) { fr = frames[i]; break; } } catch (x) { /* ignore */ }
          }
        }
        if (m.on === 1) {
          if (!fr) return;
          cssFs.frameChild = e.source;
          cssFsEnter(fr);
          if (window !== window.top) cssFsPostParent(true);
        } else {
          var child = cssFs.frameChild;
          cssFs.frameChild = null;
          if (cssFs.active) cssFsExit();
          if (child) { try { child.postMessage({ __fbFs: 1, on: 0 }, '*'); } catch (x) { /* ignore */ } }
          if (window !== window.top) cssFsPostParent(false);
        }
        return;
      }
      // __fbFsCmd: an ancestor made this frame's element CSS-fullscreen; enter/exit internally.
      var t = pickTarget();
      if (m.on === 1) {
        if (t && t.video) cssFsEnter(cssFsTarget(t.video));
      } else {
        cssFsExit();
      }
      return;
    }
    if (m.__fbCmd !== 1) return;
    // Accept commands only from an ancestor frame (the top frame), never from sibling pages.
    var fromAncestor = false;
    try {
      var w = parent, depth = 0;
      while (w && w !== window && depth < 6) {
        if (e.source === w) { fromAncestor = true; break; }
        w = w.parent; depth++;
      }
    } catch (x) { /* ignore */ }
    if (!fromAncestor) return;
    var reply = function (result) {
      try {
        var st = null;
        try { st = JSON.parse(window.__fb.state()); } catch (x) { /* ignore */ }
        parent.postMessage({ __fbRes: 1, id: m.id, result: result, state: st }, '*');
      } catch (x) { /* ignore */ }
    };
    try { reply(frameCmd(m.cmd, m.arg)); } catch (err) { reply('failed'); }
  });

  // Run a video command on the current target. The video may be in this document (or a
  // same-origin frame) — then we drive it directly — or in a frame from another site, whose
  // own copy of this helper receives the command by message.
  // Returns 'ok' | 'failed' | 'frame' (forwarded to a player frame) | 'none'.
  function videoOp(cmd, arg) {
    var t = pickTarget();
    if (!t) return 'none';
    if (t.video) {
      try { return runOnVideo(t.video, cmd, arg); } catch (e) { return 'failed'; }
    }
    if (t.frame) {
      // the poster-grid site keeps the app's trusted-tap path: its player must be started by a real touch.
      if (!frameIsFlixer(t.frame) && forwardToFrame(t.frame, cmd, arg)) return 'frame';
    }
    return 'frame';
  }

  // When the browser refuses play() (no click on this page yet) we remember it, so the app can tap
  // the player instead: a real tap is the user gesture the browser wants.
  var playBlockedAt = 0, playBlockedVideo = null, playAttempt = 0;
  function cancelPlayAttempt(){playAttempt++;playBlockedAt=0;playBlockedVideo=null;}
  function safePlay(v) {
    cancelPlayAttempt();var attempt=playAttempt;
    function refused(){if(attempt===playAttempt && v.paused){playBlockedAt=new Date().getTime();playBlockedVideo=v;}}
    try {
      var p=v.play();
      if(p && typeof p.catch==='function')p.catch(refused);
    }catch(e){refused();}
  }

  // ------------------------------------------------------------------ full screen

  // Page-level (CSS) fullscreen. Native WebView fullscreen (Chromium's FullScreenView)
  // draws a center play/pause button that stays on screen while the video is paused.
  // Instead of native fullscreen we make the player container a page-level (CSS)
  // fullscreen: position:fixed over the whole viewport, black background, and the site's
  // own player UI (which auto-hides on idle) stays in charge. Chromium's native
  // FullScreenView is never created, so the center button is never drawn.
  //
  // Players like Clappr check document.fullscreenElement and listen for fullscreenchange,
  // so we fake both: while active, fullscreenElement reports our element, and we dispatch
  // a synthetic fullscreenchange on enter/exit.
  //
  // the poster-grid site keeps the native path: its document returns early below, so none of this is
  // installed there.

  var cssFs = { active: false, el: null, saved: null, frameChild: null, resumeTimer: 0, wasPlaying: false, savedScroll: null, scrollGuard: null };
  var __nativeFsFns = {};

  function cssFsSave(el) {
    var props = ['position', 'left', 'top', 'right', 'bottom', 'width', 'height', 'margin', 'z-index', 'background', 'object-fit'];
    var s = {};
    for (var i = 0; i < props.length; i++) s[props[i]] = { v: el.style.getPropertyValue(props[i]), p: el.style.getPropertyPriority(props[i]) };
    return s;
  }

  function cssFsRestore(el, s) {
    var keys = Object.keys(s);
    for (var i = 0; i < keys.length; i++) el.style.setProperty(keys[i], s[keys[i]].v, s[keys[i]].p);
  }

  function cssFsNotifyApp(on) {
    try { if (window.Buccaneer && window.Buccaneer.cssFullscreen) window.Buccaneer.cssFullscreen(on ? true : false); } catch (e) { /* ignore */ }
  }

  function cssFsPostParent(on) {
    try { parent.postMessage({ __fbFs: 1, on: on ? 1 : 0 }, '*'); } catch (e) { /* ignore */ }
  }

  // The GPU video layer inside a cross-origin player iframe is positioned in the TOP
  // frame's document coordinates, not viewport coordinates. If the top frame page is
  // scrolled when CSS fullscreen is entered, the video renders shifted by the scroll
  // amount (the position:fixed iframe stays put, but the video layer does not). So we
  // pin the top frame to (0,0) while CSS fullscreen is active and restore the original
  // scroll on exit. Only the top frame is affected; in a child frame this is a no-op.
  function cssFsInstallScrollGuard() {
    if (window !== window.top) return;
    if (cssFs.scrollGuard) return;
    cssFs.savedScroll = { x: window.scrollX || 0, y: window.scrollY || 0 };
    cssFs.scrollGuard = function () {
      if (cssFs.active && (window.scrollX !== 0 || window.scrollY !== 0)) window.scrollTo(0, 0);
    };
    window.addEventListener('scroll', cssFs.scrollGuard, true);
    window.scrollTo(0, 0);
  }

  function cssFsRemoveScrollGuard() {
    if (window !== window.top) return;
    if (cssFs.scrollGuard) {
      window.removeEventListener('scroll', cssFs.scrollGuard, true);
      cssFs.scrollGuard = null;
    }
    if (cssFs.savedScroll) {
      var s = cssFs.savedScroll;
      cssFs.savedScroll = null;
      try { window.scrollTo(s.x, s.y); } catch (e) { /* ignore */ }
    }
  }

  // The element to make CSS-fullscreen: a bare <video> becomes its player container,
  // so the site's own controls come along.
  function cssFsTarget(el) {
    if (el && el.tagName === 'VIDEO') {
      var c = playerContainer(el);
      if (c && c !== el) return c;
    }
    return el;
  }

  function cssFsEnter(el) {
    if (!el || cssFs.active) return Promise.resolve();
    unpinPlayerFrame(); // restore original positioning so cssFsSave captures it
    cssFs.saved = cssFsSave(el);
    var st = el.style;
    st.setProperty('position', 'fixed', 'important');
    st.setProperty('left', '0', 'important');
    st.setProperty('top', '0', 'important');
    st.setProperty('right', '0', 'important');
    st.setProperty('bottom', '0', 'important');
    st.setProperty('width', '100vw', 'important');
    st.setProperty('height', '100vh', 'important');
    st.setProperty('margin', '0', 'important');
    st.setProperty('z-index', '2147483647', 'important');
    st.setProperty('background', '#000', 'important');
    if (el.tagName === 'VIDEO') st.setProperty('object-fit', 'contain', 'important');
    cssFs.el = el;
    cssFs.active = true;
    cssFsInstallScrollGuard();
    try { document.dispatchEvent(new Event('fullscreenchange')); } catch (e) { /* ignore */ }
    if (window === window.top) cssFsNotifyApp(true); else cssFsPostParent(true);
    installFsResumeGuard(el);
    return Promise.resolve();
  }

  // Entering CSS fullscreen resizes the player container (or the iframe that holds the
  // player) to fill the viewport. On some players — notably the vidbolt player inside a
  // cross-origin iframe — that resize pauses the video. If the video was playing before we
  // entered fullscreen, watch for the first ~2.5 s and resume it if the transition paused
  // it. The short window means a deliberate user pause (which happens later) is never
  // fought. Only active in the frame that actually holds the <video>; in an ancestor frame
  // `el` is the <iframe> element and no video is reachable, so this is a no-op there.
  function installFsResumeGuard(el) {
    if (cssFs.resumeTimer) { clearInterval(cssFs.resumeTimer); cssFs.resumeTimer = 0; }
    function findVideo() {
      var e = cssFs.el;
      if (!e) return null;
      if (e.tagName === 'VIDEO') return e;
      return e.querySelector ? e.querySelector('video') : null;
    }
    var v = findVideo();
    if (!v || v.paused || v.ended) { cssFs.wasPlaying = false; return; }
    cssFs.wasPlaying = true;
    var checks = 0;
    cssFs.resumeTimer = setInterval(function () {
      checks++;
      if (!cssFs.active || checks > 10) { clearInterval(cssFs.resumeTimer); cssFs.resumeTimer = 0; return; }
      var vv = findVideo();
      if (vv && vv.paused && !vv.ended) {
        safePlay(vv);
        clearInterval(cssFs.resumeTimer); cssFs.resumeTimer = 0;
      }
    }, 250);
  }

  function cssFsExit() {
    if (!cssFs.active) return;
    var el = cssFs.el;
    cssFs.active = false;
    cssFs.el = null;
    cssFsRemoveScrollGuard();
    if (cssFs.resumeTimer) { clearInterval(cssFs.resumeTimer); cssFs.resumeTimer = 0; }
    cssFs.wasPlaying = false;
    try { if (el) cssFsRestore(el, cssFs.saved); } catch (e) { /* ignore */ }
    cssFs.saved = null;
    pinPlayerFrame(); // re-pin the player iframe now that CSS fullscreen is gone
    try { document.dispatchEvent(new Event('fullscreenchange')); } catch (e) { /* ignore */ }
    if (window === window.top) cssFsNotifyApp(false); else cssFsPostParent(false);
  }

  // ------------------------------------------------------------------ player frame pinning
  //
  // On the PowerVR GPU (Fire TV) the video layer inside a cross-origin player iframe is
  // only composited when the iframe is position:fixed. In normal mode (position:absolute)
  // the video renders black even though it is playing. So we pin the player iframe to
  // position:fixed and add a scroll handler that repositions it as the page scrolls, so it
  // still appears to scroll normally. Disabled while CSS fullscreen is active (the
  // cssFsEnter logic owns the iframe then). Top frame only; a no-op in child frames.
  var playerPin = { frame: null, docLeft: 0, docTop: 0, scrollHandler: null, timer: 0 };

  function pinPlayerFrame() {
    if (window !== window.top) return;
    if (cssFs.active) return;
    var f = bigFrame();
    if (!f) return;
    if (playerPin.frame === f && f.style.position === 'fixed') return;
    if (playerPin.frame && playerPin.frame !== f) unpinPlayerFrame();
    var r; try { r = f.getBoundingClientRect(); } catch (e) { return; }
    if (r.width < 100 || r.height < 100) return;
    playerPin.frame = f;
    playerPin.docLeft = r.left + (window.scrollX || 0);
    playerPin.docTop = r.top + (window.scrollY || 0);
    var st = f.style;
    st.setProperty('position', 'fixed', 'important');
    st.setProperty('left', Math.round(r.left) + 'px', 'important');
    st.setProperty('top', Math.round(r.top) + 'px', 'important');
    st.setProperty('width', Math.round(r.width) + 'px', 'important');
    st.setProperty('height', Math.round(r.height) + 'px', 'important');
    if (!playerPin.scrollHandler) {
      playerPin.scrollHandler = function () {
        if (!playerPin.frame || cssFs.active) return;
        playerPin.frame.style.setProperty('top', Math.round(playerPin.docTop - (window.scrollY || 0)) + 'px', 'important');
        playerPin.frame.style.setProperty('left', Math.round(playerPin.docLeft - (window.scrollX || 0)) + 'px', 'important');
      };
      window.addEventListener('scroll', playerPin.scrollHandler, true);
    }
    playerPin.scrollHandler();
  }

  function unpinPlayerFrame() {
    if (!playerPin.frame) return;
    var f = playerPin.frame;
    var st = f.style;
    st.removeProperty('position');
    st.removeProperty('left');
    st.removeProperty('top');
    st.removeProperty('width');
    st.removeProperty('height');
    if (playerPin.scrollHandler) {
      window.removeEventListener('scroll', playerPin.scrollHandler, true);
      playerPin.scrollHandler = null;
    }
    playerPin.frame = null;
    playerPin.docLeft = 0;
    playerPin.docTop = 0;
  }

  function installPlayerPin() {
    if (window.__playerPinInstalled) return;
    window.__playerPinInstalled = true;
    playerPin.timer = setInterval(pinPlayerFrame, 1000);
  }

  // Dead-video auto-retry: if the player's video never loads (stays 0x0 / readyState=0
  // for ~8s — the HLS segment fetch failed, e.g. a flaky CDN), reload the page once to
  // retry. The video element itself is 0x0, so we detect a "player" by the video's
  // container being a reasonable size (>= 200x150), not the video's own rect. Bounded by a
  // localStorage cooldown so a hard-down CDN doesn't cause an infinite reload loop.
  function installDeadVideoRetry() {
    if (window.__deadVideoRetryInstalled) return;
    window.__deadVideoRetryInstalled = true;
    var deadSince = 0;
    var LS_KEY = '__deadVideoRetry';
    var COOLDOWN_MS = 60000; // don't re-reload the same URL within 60s
    var DEAD_MS = 8000; // a video dead for 8s is considered failed

    function isDeadVideo(v) {
      try {
        if (v.videoWidth !== 0 || v.videoHeight !== 0) return false;
        if (v.readyState !== 0) return false;
        // Must be in a "player" container (>= 200x150), not a tiny/trailer video.
        var el = v.parentElement || v;
        for (var i = 0; i < 4 && el; i++) {
          var r = el.getBoundingClientRect();
          if (r.width >= 200 && r.height >= 150) return true;
          el = el.parentElement;
        }
        return false;
      } catch (e) { return false; }
    }

    function findDeadVideo() {
      var all = [];
      collect(document, 2, all);
      for (var i = 0; i < all.length; i++) {
        if (isDeadVideo(all[i])) return all[i];
      }
      return null;
    }

    setInterval(function () {
      var v = findDeadVideo();
      if (v) {
        if (!deadSince) deadSince = Date.now();
        if (Date.now() - deadSince >= DEAD_MS) {
          try {
            var now = Date.now();
            var url = location.href;
            var raw = localStorage.getItem(LS_KEY);
            var last = raw ? JSON.parse(raw) : null;
            if (last && last.url === url && (now - last.ts) < COOLDOWN_MS) {
              deadSince = 0; // already retried recently; stop
              return;
            }
            localStorage.setItem(LS_KEY, JSON.stringify({ url: url, ts: now }));
          } catch (e) { /* ignore */ }
          try { console.warn('[dead-video-retry] reloading: video 0x0/readyState=0 for ' + (Date.now() - deadSince) + 'ms'); } catch (e) { /* ignore */ }
          location.reload();
        }
      } else {
        deadSince = 0;
      }
    }, 2000);
  }

  // Player controls auto-hide in full screen. The player's own CSS only hides them on pointer
  // idle; a TV remote is not a pointer, so on sites like the lazy-load poster site, the controls (and the big
  // centre play/pause button) stay up forever. While the video is full screen we dispatch a
  // synthetic mousemove on the video every 1.5 s — the same pointer activity a mouse would
  // produce, which makes the player's own idle logic hide the controls. No CSS, no class
  // changes, no site styling: we only feed the player the activity it already expects.
  // The pause button is a child of the control bar, so it hides with the bar.
  // Gated behind the poster-grid site guard below: the poster-grid site's player must not get synthetic input.
  function installControlsAutoHide() {
    if (window.__fbAutoHideInstalled) return;
    window.__fbAutoHideInstalled = true;
    var active = false;
    var timer = 0;
    function poke() {
      var v = null;
      try {
        var fe = fsElement();
        if (fe) {
          if (fe.tagName === 'VIDEO') v = fe;
          else v = fe.querySelector ? fe.querySelector('video') : null;
        }
        if (!v) {
          var all = [];
          collect(document, 2, all);
          for (var i = 0; i < all.length; i++) {
            var a = all[i];
            if (!a.paused && !a.ended && isVisible(a)) { v = a; break; }
          }
        }
      } catch (e) { v = null; }
      if (!v) return;
      try {
        var r = v.getBoundingClientRect();
        var cx = Math.round(r.left + r.width / 2);
        var cy = Math.round(r.top + r.height / 2);
        var doc = v.ownerDocument;
        var win = doc.defaultView || window;
        v.dispatchEvent(new MouseEvent('mousemove', {
          bubbles: true, cancelable: true, view: win,
          clientX: cx, clientY: cy,
          screenX: cx, screenY: cy,
          button: 0, buttons: 0
        }));
      } catch (e) { /* ignore */ }
    }
    function schedule() {
      if (timer) return;
      // A few pokes are enough to start the idle timer on players whose controls
      // stay up forever without a pointer event (the lazy-load poster site-style). Poking
      // continuously instead keeps resetting the idle timer on players that
      // auto-hide on their own (MovieBite-style), so their controls — and the
      // center play/pause button — never go away. Poke a bounded number of times,
      // then stop and let the player's own idle logic take over.
      var pokes = 0;
      var maxPokes = 2;
      timer = setInterval(function () {
        if (!active) return;
        pokes++;
        if (pokes > maxPokes) {
          clearInterval(timer);
          timer = 0;
          return;
        }
        poke();
      }, 1500);
    }
    function onFsChange() {
      var fe = fsElement();
      active = !!fe;
      if (active) {
        schedule();
        // Hide the controls right away; the player's idle timer then keeps them hidden.
        setTimeout(poke, 600);
      } else if (timer) {
        clearInterval(timer);
        timer = 0;
      }
    }
    document.addEventListener('fullscreenchange', onFsChange, true);
    document.addEventListener('webkitfullscreenchange', onFsChange, true);
  }

  function enterFullscreen(el) {
    var fn = el.requestFullscreen || el.webkitRequestFullscreen || el.webkitRequestFullScreen ||
      el.mozRequestFullScreen || el.msRequestFullscreen;
    if (fn) {
      try {
        var p = fn.call(el);
        if (p && typeof p.catch === 'function') p.catch(function () { /* refused */ });
      } catch (e) { /* ignore */ }
    } else if (el.tagName === 'VIDEO' && el.webkitEnterFullscreen) {
      try { el.webkitEnterFullscreen(); } catch (e) { /* ignore */ }
    }
  }



  // The player's root: the first ancestor of the video that holds the player's own
  // control buttons. Making THAT element CSS-fullscreen keeps the site's controls
  // visible, and it matches (or sits inside) the container the player tracks for its
  // fullscreen state, so the player recognizes the session. A pure size-based walk
  // cannot stop when the video fills the viewport (every ancestor is the same size)
  // and ends at the page wrapper, so the button check is the primary signal; the
  // size walk remains the fallback for players without in-page buttons.
  function playerContainer(v) {
    var r = v.getBoundingClientRect();
    var area = r.width * r.height;
    var best = v;
    var p = v.parentElement;
    var hops = 0;
    while (p && p !== document.body && p !== document.documentElement && hops < 6) {
      var pr = p.getBoundingClientRect();
      var parea = pr.width * pr.height;
      if (parea <= area * 3 && p.querySelector && p.querySelector('button')) return p;
      if (parea > area * 1.35) break;
      best = p;
      p = p.parentElement;
      hops++;
    }
    return best;
  }

  // ------------------------------------------------------------------ text fields

  var TEXT_TYPES = ['', 'text', 'search', 'email', 'url', 'tel', 'number', 'password'];

  function editableOf(el) {
    if (!el) return null;
    if (el.tagName === 'TEXTAREA') return el;
    if (el.tagName === 'INPUT') {
      var t = (el.getAttribute('type') || '').toLowerCase();
      return TEXT_TYPES.indexOf(t) >= 0 ? el : null;
    }
    if (el.isContentEditable) return el;
    return null;
  }

  function activeEditable() {
    var el = document.activeElement;
    var guard = 0;
    while (el && el.tagName === 'IFRAME' && guard < 3) {
      try {
        var d = el.contentDocument;
        if (!d || !d.activeElement) break;
        el = d.activeElement;
      } catch (e) { break; }
      guard++;
    }
    return editableOf(el);
  }

  // set value the way a user would, so React/Vue style frameworks notice it
  function setNativeValue(el, value) {
    var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) desc.set.call(el, value); else el.value = value;
  }

  // ------------------------------------------------------------------ public API

  var fullscreenRequest=0;
  window.__fb = {
    cancelFullscreen: function () {
      fullscreenRequest++;
      var old=document.getElementById('__fb_fs_overlay');if(old&&old.parentNode)old.parentNode.removeChild(old);
      return 'ok';
    },
    // Ask the page to leave page-level (CSS) fullscreen (the app's Back key).
    exitCssFullscreen: function () {
      if (!cssFs.active) return 'none';
      var child = cssFs.frameChild;
      cssFs.frameChild = null;
      cssFsExit();
      if (child) { try { child.postMessage({ __fbFs: 1, on: 0 }, '*'); } catch (e) { /* ignore */ } }
      return 'ok';
    },
    // 1 while the page is in page-level (CSS) fullscreen.
    cssFullscreenActive: function () { return cssFs.active ? 1 : 0; },
    selectAt: function (fx,fy) { return selectAt(fx*window.innerWidth,fy*window.innerHeight); },
    focusTarget: function () { var t=pickTarget(); if(!t)return 'none'; try{(t.frame||t.video).focus();return t.frame?'frame':'ok';}catch(e){return 'none';} },
    setDiagnosticLogging: function (enabled) {
      diagnosticEnabled = !!enabled;
      if (!__isFlixer && diagnosticEnabled) {
        if (typeof installNetworkProbe === 'function') installNetworkProbe();
        if (typeof scheduleHealthSample === 'function') scheduleHealthSample();
      }
      if (!__isFlixer && !diagnosticEnabled) {
        if (typeof healthTimer !== 'undefined' && healthTimer) { clearTimeout(healthTimer); healthTimer = 0; }
        if (typeof __netFlushTimer !== 'undefined' && __netFlushTimer) {
          clearInterval(__netFlushTimer);
          __netFlushTimer = 0;
        }
        if (typeof __netAgg !== 'undefined') __netAgg = {};
      }
      return diagnosticEnabled ? 1 : 0;
    },
    togglePlay: function () {
      return videoOp('togglePlay');
    },

    play: function () {
      return videoOp('play');
    },

    // 1 if the browser refused a play() in the last second and a tap on the player is needed
    playBlocked: function () {
      var target=pickTarget();
      return target && target.video===playBlockedVideo && target.video.paused && playBlockedAt>0 && (new Date().getTime()-playBlockedAt)<1500 ? 1 : 0;
    },

    // pauses every video we can reach (also used when the app goes to the background)
    pause: function () {
      cancelPlayAttempt();
      var all = [];
      collect(document, 2, all);
      for (var i = 0; i < all.length; i++) {
        try { all[i].pause(); } catch (e) { /* ignore */ }
      }
      var f = bigFrame();
      if (f && !frameIsFlixer(f) && forwardToFrame(f, 'pause')) return 'ok';
      return all.length ? 'ok' : (f ? 'frame' : 'none');
    },

    seek: function (delta) {
      return videoOp('seek', delta);
    },

    volume: function (delta) {
      return videoOp('volume', delta);
    },

    toggleMute: function () {
      return videoOp('toggleMute');
    },

    // Force-unmute: the player's own UI may be broken (e.g. a cross-origin frame whose control
    // script throws), so set muted=false directly on every video we can reach.
    unmute: function () {
      var done = 0;
      var vids = document.getElementsByTagName('video');
      for (var i = 0; i < vids.length; i++) {
        try {
          if (vids[i].muted) { vids[i].muted = false; done++; }
        } catch (e) { /* cross-origin or detached */ }
      }
      if (done > 0) return 'ok';
      var t = pickTarget();
      if (t && t.frame && !frameIsFlixer(t.frame) && forwardToFrame(t.frame, 'unmute')) return 'frame';
      return 'none';
    },

    // Where the player's mute button is, as a fraction of the screen: the last button in the
    // player's control bar (mute is the rightmost on most players). Used when the video element
    // is in a frame we cannot script: the app taps this spot, as a finger would.
    muteButton: function () {
      var t = pickTarget();
      var el = t ? (t.frame || t.video) : null;
      if (!el) return JSON.stringify({ ok: 0 });
      var btns = el.querySelectorAll ? el.querySelectorAll('button') : [];
      var last = null;
      for (var i = 0; i < btns.length; i++) {
        if (isVisible(btns[i])) last = btns[i];
      }
      if (!last) return JSON.stringify({ ok: 0 });
      var r = last.getBoundingClientRect();
      var w = window.innerWidth || 1;
      var h = window.innerHeight || 1;
      if (r.width < 4 || r.height < 4) return JSON.stringify({ ok: 0 });
      return JSON.stringify({ ok: 1, x: (r.left + r.width / 2) / w, y: (r.top + r.height / 2) / h });
    },

    // Diagnostic: dump the player's control bar structure so we can see what the buttons are.
    playerControls: function () {
      var t = pickTarget();
      var el = t ? (t.frame || t.video) : null;
      if (!el) return JSON.stringify({ ok: 0 });
      var bar = null;
      var all = el.querySelectorAll ? el.querySelectorAll('div, ul, nav') : [];
      for (var i = 0; i < all.length; i++) {
        var btns = all[i].querySelectorAll ? all[i].querySelectorAll('button, [role="button"]') : [];
        if (btns.length >= 3) { bar = all[i]; break; }
      }
      if (!bar) return JSON.stringify({ ok: 0, note: 'no control bar with 3+ buttons' });
      var btns = [...bar.querySelectorAll('button, [role="button"]')];
      return JSON.stringify({
        ok: 1,
        barClass: bar.className.substring(0, 80),
        buttons: btns.map(function (b) {
          var r = b.getBoundingClientRect();
          return {
            cls: b.className.substring(0, 50),
            aria: b.getAttribute('aria-label') || '',
            title: b.getAttribute('title') || '',
            text: (b.textContent || '').trim().substring(0, 20),
            visible: r.width > 0 && r.height > 0,
            x: Math.round(r.left + r.width / 2),
            y: Math.round(r.top + r.height / 2)
          };
        })
      });
    },

    // Diagnostic: the full element stack at a tap point, top (frontmost) to bottom.
    // document.elementsFromPoint returns the elements that would receive a pointer event
    // at (x, y), frontmost first. The FIRST entry is what actually eats the tap. If that is
    // not a button / control / video, something is covering the player UI. This names the
    // exact ad layer so killOverlays() can target it instead of guessing.
    tapStack: function (x, y) {
      var out = [];
      try {
        var els = document.elementsFromPoint(x, y) || [];
        for (var i = 0; i < els.length; i++) {
          var el = els[i];
          var s;
          try { s = window.getComputedStyle(el); } catch (e) { s = null; }
          var r;
          try { r = el.getBoundingClientRect(); } catch (e) { r = null; }
          var coversVideo = 0;
          try {
            var vids = document.getElementsByTagName('video');
            for (var v = 0; v < vids.length; v++) {
              var vr = vids[v].getBoundingClientRect();
              if (vr.width > 100 && vr.height > 100 &&
                  r && r.width > 100 && r.height > 100 &&
                  r.left < vr.right && r.right > vr.left &&
                  r.top < vr.bottom && r.bottom > vr.top) { coversVideo = 1; break; }
            }
          } catch (e) { /* ignore */ }
          out.push({
            tag: el.tagName,
            id: el.id || '',
            cls: (el.className && typeof el.className === 'string') ? el.className.substring(0, 60) : '',
            pos: s ? s.position : '',
            z: s ? s.zIndex : '',
            pe: s ? s.pointerEvents : '',
            op: s ? s.opacity : '',
            w: r ? Math.round(r.width) : 0,
            h: r ? Math.round(r.height) : 0,
            coversVideo: coversVideo,
            isControl: !!(el.closest && el.closest('button, [role="button"], .player-controls, .control, video')) ? 1 : 0
          });
        }
      } catch (e) { /* ignore */ }
      return JSON.stringify({
        ok: 1,
        x: Math.round(x), y: Math.round(y),
        topFrame: window.top === window ? 1 : 0,
        url: (window.location.href || '').substring(0, 120),
        count: out.length,
        stack: out
      });
    },

    // Ask every same-origin iframe to capture its own tap stack at the equivalent point,
    // and return them all. Cross-origin iframes cannot be reached (the ad layer lives in a
    // cross-origin frame), so for those we report the iframe's own rect so the app can
    // confirm the tap fell inside it. This is how we see inside the vidcore player frame.
    tapStackAll: function (x, y) {
      var result = { ok: 1, x: Math.round(x), y: Math.round(y), frames: [] };
      // This frame's own stack.
      try {
        var self = JSON.parse(this.tapStack(x, y));
        result.self = self;
      } catch (e) { /* ignore */ }
      // Walk same-origin iframes and ask each for its stack at the mapped point.
      var frames = document.getElementsByTagName('iframe');
      for (var i = 0; i < frames.length; i++) {
        var f = frames[i];
        var r;
        try { r = f.getBoundingClientRect(); } catch (e) { continue; }
        if (r.width < 50 || r.height < 50) continue;
        var inside = x >= r.left && x <= r.right && y >= r.top && y <= r.bottom;
        var entry = {
          src: (f.src || '').substring(0, 120),
          rect: { l: Math.round(r.left), t: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) },
          tapInside: inside ? 1 : 0
        };
        try {
          var cw = f.contentWindow;
          var cd = f.contentDocument;
          if (cw && cd) {
            // Same-origin: we can reach in directly.
            var fx = x - r.left, fy = y - r.top;
            var fb = cw.__fb;
            if (fb && typeof fb.tapStack === 'function') {
              entry.stack = JSON.parse(fb.tapStack(fx, fy));
            }
          }
        } catch (e) {
          entry.crossOrigin = 1;   // cannot reach in: the ad layer is in here
        }
        result.frames.push(entry);
      }
      return JSON.stringify(result);
    },

    // Diagnostic: report the iframe's bounding rect (top frame) or video rect (iframe),
    // so we can see where the player actually is on screen.
    playerGeometry: function () {
      var result = { ok: 1 };
      if (window.top === window) {
        var iframe = document.querySelector('iframe');
        if (iframe) {
          var r = iframe.getBoundingClientRect();
          result.iframe = { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) };
        }
        result.winW = window.innerWidth;
        result.winH = window.innerHeight;
      } else {
        var v = document.querySelector('video');
        if (v) {
          var r = v.getBoundingClientRect();
          result.video = { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) };
        }
        result.winW = window.innerWidth;
        result.winH = window.innerHeight;
      }
      return JSON.stringify(result);
    },

    // Where to tap to press play on a player we cannot script: the middle of its visible part,
    // as a fraction of the screen (so it does not depend on page zoom).
    frameCenter: function () {
      var t = pickTarget();
      var el = t ? (t.frame || t.video) : null;
      if (!el) return JSON.stringify({ ok: 0 });
      var r = el.getBoundingClientRect();
      var win=(el.ownerDocument && el.ownerDocument.defaultView)||window;
      try {
        for(var depth=0;win!==window && depth<3;depth++){
          var frame=win.frameElement;if(!frame)return JSON.stringify({ok:0});
          var fr=frame.getBoundingClientRect(),sx=fr.width/(frame.offsetWidth||fr.width),sy=fr.height/(frame.offsetHeight||fr.height);
          if(!(sx>0 && sy>0))return JSON.stringify({ok:0});
          var left=Math.max(0,r.left),right=Math.min(win.innerWidth,r.right),top=Math.max(0,r.top),bottom=Math.min(win.innerHeight,r.bottom);
          if(right<=left||bottom<=top)return JSON.stringify({ok:0});
          r={left:fr.left+((frame.clientLeft||0)+left)*sx,right:fr.left+((frame.clientLeft||0)+right)*sx,
             top:fr.top+((frame.clientTop||0)+top)*sy,bottom:fr.top+((frame.clientTop||0)+bottom)*sy};
          win=frame.ownerDocument.defaultView;
        }
        if(win!==window)return JSON.stringify({ok:0});
      }catch(e){return JSON.stringify({ok:0});}
      var w = window.innerWidth || 1;
      var h = window.innerHeight || 1;
      var left = Math.max(r.left, 0);
      var right = Math.min(r.right, w);
      var top = Math.max(r.top, 0);
      var bottom = Math.min(r.bottom, h);
      if (right <= left || bottom <= top) return JSON.stringify({ ok: 0 });
      return JSON.stringify({ ok: 1, x: ((left + right) / 2) / w, y: ((top + bottom) / 2) / h });
    },

    // Report for Settings > Diagnostics: what each video on the page is really doing, and which
    // codecs this WebView claims to support.
    diag: function () {
      var out = { videos: [], frames: countOpaqueFrames(), mse: null };
      var all = [];
      collect(document, 2, all);
      for (var i = 0; i < all.length && out.videos.length < 4; i++) {
        var v = all[i];
        var r;
        try { r = v.getBoundingClientRect(); } catch (e) { r = { width: 0, height: 0 }; }
        var q = null;
        try { q = v.getVideoPlaybackQuality ? v.getVideoPlaybackQuality() : null; } catch (e) { q = null; }
        out.videos.push({
          w: Math.round(r.width), h: Math.round(r.height),
          vw: v.videoWidth || 0, vh: v.videoHeight || 0,
          rs: v.readyState, ns: v.networkState,
          p: v.paused ? 1 : 0, m: v.muted ? 1 : 0,
          vol: typeof v.volume === 'number' ? Number(v.volume.toFixed(2)) : 0,
          rate: typeof v.playbackRate === 'number' ? Number(v.playbackRate.toFixed(2)) : 1,
          t: v.currentTime || 0, d: isFinite(v.duration) ? v.duration : 0,
          ba: (function () {
            try {
              if (v.buffered && v.buffered.length > 0) {
                return Math.max(0, Number((v.buffered.end(v.buffered.length - 1) - v.currentTime).toFixed(2)));
              }
            } catch (e) { /* ignore */ }
            return -1;
          }()),
          src: v.currentSrc || v.src || '',
          e: v.error ? v.error.code : 0,
          fr: q ? q.totalVideoFrames : -1, dr: q ? q.droppedVideoFrames : -1,
          vis: isVisible(v) ? 1 : 0
        });
      }
      var MS = window.MediaSource || window.WebKitMediaSource;
      if (MS && typeof MS.isTypeSupported === 'function') {
        var sup = function (type) {
          try { return MS.isTypeSupported(type) ? 1 : 0; } catch (e) { return 0; }
        };
        out.mse = {
          h264: sup('video/mp4; codecs="avc1.640028"'),
          hevc: sup('video/mp4; codecs="hvc1.1.6.L93.B0"'),
          vp9: sup('video/webm; codecs="vp9"'),
          av1: sup('video/mp4; codecs="av01.0.05M.08"')
        };
      }
      return JSON.stringify(out);
    },

    state: function () {
      var t = pickTarget();
      if (!t || !t.video) {
        // The player lives in another site's frame: report the state its helper last sent back.
        if (t && t.frame && lastFrameState && Date.now() - lastFrameState.at < 10000) {
          return JSON.stringify(lastFrameState.state);
        }
        return JSON.stringify({ f: 0, fr: (t && t.frame) ? 1 : 0 });
      }
      var v = t.video;
      return JSON.stringify({
        f: 1,
        p: v.paused ? 1 : 0,
        t: v.currentTime || 0,
        d: isFinite(v.duration) ? v.duration : 0,
        h: v.videoHeight || 0,
        v: v.volume,
        m: v.muted ? 1 : 0
      });
    },

    // Puts a transparent layer over the page. The app then taps the screen centre; that real tap
    // is the user gesture browsers require before they allow full screen.
    prepareFullscreen: function () {
      var request=++fullscreenRequest;
      var target = null;
      var t = pickTarget();
      if (t && t.video) {
        var v = t.video;
        if (v.ownerDocument !== document) {
          var win = v.ownerDocument.defaultView;
          target = (win && win.frameElement) ? win.frameElement : null;
        }
        if (!target) target = playerContainer(v);
      } else if (t && t.frame) {
        target = t.frame;
      }
      if (!target) return 'none';

      var old = document.getElementById('__fb_fs_overlay');
      if (old && old.parentNode) old.parentNode.removeChild(old);

      var ov = document.createElement('div');
      ov.id = '__fb_fs_overlay';
      ov.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;' +
        'z-index:2147483647;background:rgba(0,0,0,0.01);';
      var done = false;
      function remove() { if (ov.parentNode) ov.parentNode.removeChild(ov); }
      function handler(e) {
        if(request!==fullscreenRequest){remove();return;}
        try { e.preventDefault(); e.stopPropagation(); } catch (x) { /* ignore */ }
        if (!done) {
          done = true;
          if (target && frameIsFlixer(target)) {
            // the poster-grid site keeps the native fullscreen path (trusted tap, PowerVR constraints).
            var nfn = __nativeFsFns.requestFullscreen || __nativeFsFns.webkitRequestFullscreen || null;
            if (nfn) {
              try { var p = nfn.call(target); if (p && typeof p.catch === 'function') p.catch(function () { /* refused */ }); } catch (x) { /* ignore */ }
            }
          } else {
            enterFullscreen(target);
            if (target && target.tagName === 'IFRAME' && !frameIsFlixer(target)) {
              // Tell the frame to go CSS-fullscreen internally, so its player (and its
              // fullscreen state) matches the now-fullscreen iframe element.
              try { target.contentWindow.postMessage({ __fbFsCmd: 1, on: 1 }, '*'); } catch (x) { /* ignore */ }
            }
          }
          setTimeout(remove, 500);
        }
      }
      ov.addEventListener('pointerup', handler, true);
      ov.addEventListener('touchend', handler, true);
      ov.addEventListener('mouseup', handler, true);
      ov.addEventListener('click', handler, true);
      (document.body || document.documentElement).appendChild(ov);
      setTimeout(function () { if (!done) { done = true; remove(); } }, 4000);
      return 'ok';
    },

    // what is in the focused text field (the keypad mirrors it)
    field: function () {
      var el = activeEditable();
      if (!el) return JSON.stringify({ ok: 0 });
      var isPw = el.tagName === 'INPUT' && (el.getAttribute('type') || '').toLowerCase() === 'password';
      var val = (el.value !== undefined) ? el.value : (el.textContent || '');
      return JSON.stringify({ ok: 1, v: String(val), pw: isPw ? 1 : 0 });
    },

    // keep the field in the top part of the screen: the keypad covers the bottom
    focusIntoView: function () {
      var el = activeEditable();
      if (!el) return 'none';
      try { el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (e) { /* ignore */ }
      try {
        if (el.ownerDocument === document) {
          var r = el.getBoundingClientRect();
          window.scrollBy(0, (r.top + r.height / 2) - window.innerHeight * 0.22);
        }
      } catch (e) { /* ignore */ }
      return 'ok';
    },

    clear: function () {
      var el = activeEditable();
      if (!el) return 'none';
      try {
        if (el.value !== undefined) {
          setNativeValue(el, '');
        } else {
          el.textContent = '';
        }
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      } catch (e) { return 'none'; }
      return 'ok';
    },

    // Set the preferred quality order. The app calls this once at startup.
    // Preference: 480p first, then 720p, then others, 1080p as last resort.
    setQualityPreference: function (order) {
      try {
        qualityPreference = Array.isArray(order) ? order.slice() : [480, 720, 1080];
        return 'ok';
      } catch (e) { return 'none'; }
    }
  };

  // ------------------------------------------------------------------ quality preference
  // Prefer lower resolutions: 480p > 720p > others > 1080p (last resort).
  // Hooks into HLS.js and Shaka players when they become available.
  // Gated behind the "Prefer low quality streams" setting (bridge: lowQualityEnabled).
  var qualityPreference = [480, 720, 1080];

  function lowQualityOn() {
    try {
      var b = window.Buccaneer;
      if (b && typeof b.lowQualityEnabled === 'function') return b.lowQualityEnabled() === true;
    } catch (e) { /* ignore */ }
    return false;
  }

  function pickPreferredLevel(levels) {
    // levels: array of {height, ...} from HLS.js or similar
    if (!levels || !levels.length) return -1;
    // Sort by preference: exact matches first (in order), then closest below, then closest above
    var scored = [];
    for (var i = 0; i < levels.length; i++) {
      var h = levels[i] && levels[i].height || 0;
      if (!h) continue;
      var score = 10000; // default: high = bad
      for (var p = 0; p < qualityPreference.length; p++) {
        if (h === qualityPreference[p]) { score = p; break; }
      }
      if (score === 10000) {
        // Not an exact match: distance from closest preferred level
        var minDist = Infinity;
        for (var q = 0; q < qualityPreference.length; q++) {
          var d = Math.abs(h - qualityPreference[q]);
          if (d < minDist) minDist = d;
        }
        score = 100 + minDist;
      }
      scored.push({ idx: i, h: h, score: score });
    }
    if (!scored.length) return -1;
    scored.sort(function (a, b) { return a.score - b.score; });
    return scored[0].idx;
  }

  function applyQualityHls(hls) {
    if (!hls || typeof hls !== 'object' || !Array.isArray(hls.levels) || hls.__fbQualitySet) return;
    if (!lowQualityOn()) return;
    var idx = pickPreferredLevel(hls.levels);
    if (idx < 0) return;
    hls.__fbQualitySet = true;
    try {
      hls.currentLevel = idx;
      diagnostic('quality_set', { player: 'hls', level: idx, height: hls.levels[idx].height, url: window.location.href || '' });
    } catch (e) { /* ignore */ }
  }

  function applyQualityShaka(player) {
    if (!player || typeof player.getVariantTracks !== 'function' || player.__fbQualitySet) return;
    if (!lowQualityOn()) return;
    var tracks;
    try { tracks = player.getVariantTracks() || []; } catch (e) { return; }
    var best = null, bestScore = Infinity;
    for (var i = 0; i < tracks.length; i++) {
      var h = tracks[i].height || 0;
      if (!h) continue;
      var score = 10000;
      for (var p = 0; p < qualityPreference.length; p++) {
        if (h === qualityPreference[p]) { score = p; break; }
      }
      if (score === 10000) {
        var minDist = Infinity;
        for (var q = 0; q < qualityPreference.length; q++) {
          var d = Math.abs(h - qualityPreference[q]);
          if (d < minDist) minDist = d;
        }
        score = 100 + minDist;
      }
      if (score < bestScore) { bestScore = score; best = tracks[i]; }
    }
    if (!best) return;
    player.__fbQualitySet = true;
    try {
      player.selectVariantTrack(best, true);
      diagnostic('quality_set', { player: 'shaka', height: best.height, url: window.location.href || '' });
    } catch (e) { /* ignore */ }
  }

  // Scan for HLS.js / Shaka players on window and apply quality preference.
  // Runs on a short interval until a player is found (players load async).
  var qualityScanTimer = 0;
  function scanForQualityPlayers() {
    if (qualityScanTimer) return; // already scanning
    var attempts = 0;
    qualityScanTimer = setInterval(function () {
      attempts++;
      if (attempts > 30) { clearInterval(qualityScanTimer); qualityScanTimer = 0; return; } // stop after ~30s
      var found = false;
      try {
        for (var key in window) {
          var o;
          try { o = window[key]; } catch (e) { continue; }
          if (!o || typeof o !== 'object') continue;
          // HLS.js instance
          if (o.levels && Array.isArray(o.levels) && typeof o.loadSource === 'function') {
            applyQualityHls(o);
            found = true;
          }
          // Shaka player
          if (typeof o.getVariantTracks === 'function' && typeof o.load === 'function') {
            applyQualityShaka(o);
            found = true;
          }
        }
      } catch (e) { /* ignore */ }
      // Also check for Shaka in the shaka namespace (shaka.player.Instance)
      try {
        if (window.shaka && window.shaka.player) {
          // Shaka instances are often stored in arrays or can be found via the player element
          var video = document.querySelector('video');
          if (video) {
            // Check if the video element has a Shaka player attached
            var shakaPlayer = video.shakaPlayer || video.__shakaPlayer;
            if (shakaPlayer && typeof shakaPlayer.getVariantTracks === 'function') {
              applyQualityShaka(shakaPlayer);
              found = true;
            }
          }
          // Check common global player variables
          var commonNames = ['player', 'shakaPlayer', 'videoPlayer', 'streamPlayer'];
          for (var ci = 0; ci < commonNames.length; ci++) {
            var p = window[commonNames[ci]];
            if (p && typeof p.getVariantTracks === 'function') {
              applyQualityShaka(p);
              found = true;
            }
          }
        }
      } catch (e) { /* ignore */ }
      if (found) { clearInterval(qualityScanTimer); qualityScanTimer = 0; }
    }, 1000);
  }

  // (quality scan listener is registered after the poster-grid site guard below)

  // ------------------------------------------------------------------ one video at a time
  // Weak TV hardware has room for one video decoder. When a hidden preview, an ad and the real
  // player all start together you get two sound tracks and a black picture. The app says (through
  // the Buccaneer bridge, which exists in every frame) whether this is switched on and which frame
  // started a real player last; every frame then stops its own media when another frame took over.
  var documentId = bridgeCall('currentDocument') || '';
  var frameId = documentId + ':' + String(Math.random()).slice(2) + '-' + String(new Date().getTime());

  function bridgeCall(name, arg) {
    try {
      var b = window.Buccaneer;
      if (b && typeof b[name] === 'function') return arg === undefined ? b[name]() : b[name](arg);
    } catch (e) { /* ignore */ }
    return undefined;
  }

  var diagnosticEnabled = false;
  try {
    diagnosticEnabled = !!(window.Buccaneer && typeof window.Buccaneer.isDiagnosticsEnabled === 'function' &&
      window.Buccaneer.isDiagnosticsEnabled());
  } catch (e) { diagnosticEnabled = false; }

  // Prove the script is alive in this frame: report once when diagnostics turn on.
  if (diagnosticEnabled) {
    try {
      var b = window.Buccaneer;
      if (b && typeof b.diagnosticEvent === 'function') {
        b.diagnosticEvent('frame_script_alive', JSON.stringify({
          url: window.location.href || '',
          topFrame: window.top === window ? 1 : 0,
          hasVideo: document.getElementsByTagName('video').length > 0
        }));
      }
    } catch (e) { /* ignore */ }
  }

  // the poster-grid site: suppress the player's auto-fullscreen. The poster-grid site watch page calls
  // requestFullscreen() on the player container when playback starts, which triggers
  // native fullscreen. The video should stay inline until the user clicks the player
  // or the menu button. So we block requestFullscreen() calls that happen within a
  // short window after page load / each play event (the auto calls), and allow later
  // calls (user-initiated).
  //
  // Minimal by design: one capture-phase 'play' listener + one prototype modification.
  // No timers, no continuous polling — to avoid the PowerVR GPU crash that heavier
  // instrumentation triggers on the poster-grid site.
  if (__isFlixer) {
    (function installFlixerFsGuard() {
      if (window.__fbFlixerFsGuard) return;
      window.__fbFlixerFsGuard = true;
      var WINDOW_MS = 5000;
      var suppressUntil = Date.now() + WINDOW_MS;
      document.addEventListener('play', function () {
        suppressUntil = Date.now() + WINDOW_MS;
      }, true);
      var proto = window.Element.prototype;
      var names = ['requestFullscreen', 'webkitRequestFullscreen', 'webkitRequestFullScreen', 'mozRequestFullScreen', 'msRequestFullscreen'];
      for (var i = 0; i < names.length; i++) {
        var n = names[i];
        if (typeof proto[n] !== 'function') continue;
        var orig = proto[n];
        proto[n] = function () {
          if (Date.now() < suppressUntil) {
            return Promise.reject(new Error('auto fullscreen suppressed'));
          }
          return orig.apply(this, arguments);
        };
      }
    })();
  }

  // the poster-grid site: stop here. The passive __fb API above is available for explicit Android-driven
  // commands (video controls, typing). Everything below installs continuous capture-phase
  // event listeners, media health timers, HLS constructor hooks, and synthetic click
  // forwarding — all of which add GPU/CPU load that triggers the PowerVR driver crash
  // during the poster-grid site's intro animation.
  if (__isFlixer) return;

  // Dead-video auto-retry: reload once if the player's video never loads (flaky CDN).
  installDeadVideoRetry();

  // Pin the player iframe to position:fixed so the PowerVR GPU composites its video layer
  // in normal mode (it only composites when the iframe is position:fixed).
  installPlayerPin();

  // Auto-hide the player's controls in full screen (the lazy-load poster site and similar): synthetic pointer
  // activity on the video, driven by the player's own idle logic. Not installed on the poster-grid site.
  //
  // YouTube (and other show-on-activity players) do the OPPOSITE of hide-on-idle: any pointer
  // activity re-shows the controls, which then hide after ~3 s. Our 1.5 s mousemove fires before
  // that hide timer expires, so it perpetually re-wakes YouTube's controls — they never stay
  // hidden. So we skip the synthetic poke on YouTube and let its own idle logic do the hiding.
  var __isYouTube = /(^|\.)youtube\.com$|(^|\.)youtu\.be$/.test(__host);
  if (!__isYouTube) installControlsAutoHide();

  // Replace native fullscreen with the CSS fullscreen above, so Chromium's native
  // FullScreenView (and its persistent center button) is never created. Runs at document
  // start, before page scripts; never on the poster-grid site (which returned above).
  (function installCssFullscreenIntercept() {
    if (window.__fbFsIntercept) return;
    window.__fbFsIntercept = true;
    var proto = window.Element.prototype;
    var names = ['requestFullscreen', 'webkitRequestFullscreen', 'webkitRequestFullScreen', 'mozRequestFullScreen', 'msRequestFullscreen'];
    for (var i = 0; i < names.length; i++) {
      var n = names[i];
      if (typeof proto[n] !== 'function') continue;
      __nativeFsFns[n] = proto[n];
      proto[n] = function () {
        var el = cssFsTarget(this);
        if (!el) return Promise.reject(new Error('no fullscreen target'));
        return cssFsEnter(el);
      };
    }
    // The legacy media API also triggers native fullscreen. Players on Android try
    // video.webkitEnterFullscreen() BEFORE requestFullscreen(), and on this WebView the
    // method lives on HTMLVideoElement.prototype (HTMLMediaElement.prototype has none),
    // so both prototypes must be covered or the player's first-choice path goes native.
    var mediaProtos = [window.HTMLVideoElement, window.HTMLMediaElement];
    for (var mp = 0; mp < mediaProtos.length; mp++) {
      var mpProto = mediaProtos[mp] && mediaProtos[mp].prototype;
      if (!mpProto) continue;
      if (typeof mpProto.webkitEnterFullscreen === 'function') {
        __nativeFsFns.webkitEnterFullscreen = mpProto.webkitEnterFullscreen;
        mpProto.webkitEnterFullscreen = function () {
          return cssFsEnter(cssFsTarget(this));
        };
      }
      if (typeof mpProto.webkitExitFullscreen === 'function') {
        __nativeFsFns.webkitExitFullscreen = mpProto.webkitExitFullscreen;
        mpProto.webkitExitFullscreen = function () {
          if (cssFs.active) { cssFsExit(); return; }
          try { __nativeFsFns.webkitExitFullscreen.call(this); } catch (e) { /* ignore */ }
        };
      }
    }
    // Fake the document's fullscreen state so player logic (Clappr and friends) sees a
    // normal fullscreen session.
    var docProto = Object.getPrototypeOf(document);
    var nativeFsElement = Object.getOwnPropertyDescriptor(docProto, 'fullscreenElement');
    var nativeFsEnabled = Object.getOwnPropertyDescriptor(docProto, 'fullscreenEnabled');
    try {
      Object.defineProperty(document, 'fullscreenElement', {
        configurable: true,
        get: function () {
          if (cssFs.active) return cssFs.el;
          try {
            if (nativeFsElement && nativeFsElement.get) return nativeFsElement.get.call(this) || null;
            return document.webkitFullscreenElement || null;
          } catch (e) { return null; }
        }
      });
      Object.defineProperty(document, 'fullscreenEnabled', {
        configurable: true,
        get: function () {
          if (cssFs.active) return true;
          try { if (nativeFsEnabled && nativeFsEnabled.get) return !!nativeFsEnabled.get.call(this); } catch (e) { /* ignore */ }
          return true;
        }
      });
      var origExit = docProto.exitFullscreen;
      document.exitFullscreen = function () {
        if (cssFs.active) { cssFsExit(); return Promise.resolve(); }
        try { return origExit ? origExit.call(document) : Promise.resolve(); } catch (e) { return Promise.resolve(); }
      };
      var origWebkitExit = docProto.webkitExitFullscreen;
      document.webkitExitFullscreen = function () {
        if (cssFs.active) { cssFsExit(); return; }
        try { if (origWebkitExit) origWebkitExit.call(document); } catch (e) { /* ignore */ }
      };
    } catch (e) { /* if the document state cannot be faked, players may misbehave, but fullscreen itself still works */ }
  })();

  // the cross-origin player site: remove sandbox attribute from player iframes BEFORE they load.
  // The player checks its security context and refuses to play if sandboxed.
  // We need to remove the sandbox attribute before the iframe navigates.
  if (__isStreamCorner) installSandboxRemover();

  // the cross-origin player site: remove sandbox attribute from player iframes BEFORE they load.
  // The player checks its security context and refuses to play if sandboxed.
  // We need to remove the sandbox attribute before the iframe navigates.
  function installSandboxRemover() {
    if (window.__fbSandboxRemoverInstalled) return;
    window.__fbSandboxRemoverInstalled = true;
    
    // Hook iframe element creation to remove sandbox attribute before navigation
    var origCreateElement = document.createElement;
    document.createElement = function(tagName) {
      var el = origCreateElement.apply(this, arguments);
      if (tagName && tagName.toLowerCase() === 'iframe') {
        // Remove sandbox attribute immediately
        el.removeAttribute('sandbox');
        // Ensure the iframe is allowed to go fullscreen (element API requires this
        // permission for cross-origin frames; without it the player's fullscreen
        // button is a no-op).
        el.setAttribute('allowfullscreen', 'allowfullscreen');
        var allow = el.getAttribute('allow') || '';
        if (allow.indexOf('fullscreen') === -1) {
          el.setAttribute('allow', (allow ? allow + ', ' : '') + 'fullscreen');
        }
        if (diagnosticEnabled) {
          diagnostic('sandbox_removed_early', { url: window.location.href || '' });
        }
      }
      return el;
    };
    
    // Also check existing iframes and remove sandbox / ensure fullscreen permission
    function removeSandboxFromIframes() {
      var iframes = document.querySelectorAll('iframe[sandbox], iframe:not([allowfullscreen])');
      for (var i = 0; i < iframes.length; i++) {
        var f = iframes[i];
        if (f.hasAttribute('sandbox')) {
          f.removeAttribute('sandbox');
        }
        if (!f.hasAttribute('allowfullscreen')) {
          f.setAttribute('allowfullscreen', 'allowfullscreen');
        }
        var allow = f.getAttribute('allow') || '';
        if (allow.indexOf('fullscreen') === -1) {
          f.setAttribute('allow', (allow ? allow + ', ' : '') + 'fullscreen');
        }
        if (diagnosticEnabled) {
          diagnostic('sandbox_removed', { 
            iframeSrc: (f.src || '').substring(0, 100),
            url: window.location.href || ''
          });
        }
      }
    }
    
    // Run immediately and on a short interval to catch dynamically added iframes
    removeSandboxFromIframes();
    var sandboxTimer = setInterval(removeSandboxFromIframes, 500);
    // Stop after 30 seconds (iframes should be loaded by then)
    setTimeout(function() { clearInterval(sandboxTimer); }, 30000);
    
    // Hide the cross-origin player site error overlay (video plays behind it)
    installStreamCornerErrorHider();
  }
  
  // The cross-origin player site player shows a "Remove sandbox attributes" error overlay
  // even when the video is playing. Hide the overlay so the user can see the video.
  function installStreamCornerErrorHider() {
    if (window.__fbScErrorHiderInstalled) return;
    window.__fbScErrorHiderInstalled = true;
    
    function hideErrorOverlay() {
      // Look for the error message in the current frame
      var errorEl = document.querySelector('[class*="error"], [class*="sandbox"], [id*="error"]');
      if (errorEl && errorEl.textContent && /sandbox|remove.*attribute/i.test(errorEl.textContent)) {
        errorEl.style.display = 'none';
        if (diagnosticEnabled) {
          diagnostic('sc_error_hidden', { text: errorEl.textContent.substring(0, 100) });
        }
      }
      // Also check for overlay divs covering the video
      var overlays = document.querySelectorAll('div[style*="position: absolute"], div[style*="position:absolute"]');
      for (var i = 0; i < overlays.length; i++) {
        var o = overlays[i];
        if (o.textContent && /sandbox|remove.*attribute/i.test(o.textContent)) {
          o.style.display = 'none';
          if (diagnosticEnabled) {
            diagnostic('sc_overlay_hidden', { text: o.textContent.substring(0, 100) });
          }
        }
      }
    }
    
    // Run on a short interval to catch the error overlay when it appears
    hideErrorOverlay();
    var errorTimer = setInterval(hideErrorOverlay, 1000);
    // Stop after 60 seconds
    setTimeout(function() { clearInterval(errorTimer); }, 60000);
  }


  // Start quality preference scanning when a video element appears (player is loading).
  // Registered after the poster-grid site guard: the interval scan adds CPU load that triggers
  // the PowerVR driver crash during the poster-grid site's intro animation.
  document.addEventListener('loadedmetadata', function () {
    if (!qualityScanTimer) scanForQualityPlayers();
    if (diagnosticEnabled) {
    // Diagnostic: report what player instances are available in this frame
    diagnostic('player_scan', {
      url: window.location.href || '',
      topFrame: window.top === window ? 1 : 0,
      hls: typeof window.Hls === 'function' ? 1 : 0,
      shaka: typeof window.shaka !== 'undefined' ? 1 : 0,
      videoCount: document.getElementsByTagName('video').length,
      iframeCount: document.getElementsByTagName('iframe').length,
      windowKeys: (function() {
        var keys = [];
        try {
          for (var k in window) {
            var o;
            try { o = window[k]; } catch (e) { continue; }
            if (o && typeof o === 'object') {
              if (o.levels && Array.isArray(o.levels)) keys.push(k + ':hls');
              if (typeof o.getVariantTracks === 'function') keys.push(k + ':shaka');
              if (typeof o.loadSource === 'function') keys.push(k + ':loadSource');
            }
          }
        } catch (e) {}
        return keys.slice(0, 10);
      })()
    });
    // Install diagnostics-only network hooks only when logging is enabled.
    installNetworkProbe();
    }
    // Check for Bitmovin player instances
    if (lowQualityOn() || diagnosticEnabled) scanForBitmovin();
  }, true);

  // Bitmovin player has a quality API: getAvailableQualities(), getQuality(), setQuality()
  // Scan for Bitmovin instances on window and in iframes.
  function scanForBitmovin() {
    try {
      // Check window for Bitmovin
      var bm = window.bitmovin;
      if (bm && bm.Player) {
        diagnostic('bitmovin_found', { source: 'window.bitmovin', version: bm.Player.version || '' });
      }
      // Check for player instances in common locations
      var players = [];
      try {
        if (window.bitmovin && window.bitmovin.Player) {
          // Bitmovin players are often stored in arrays or objects
          for (var k in window) {
            try {
              var o = window[k];
              if (o && typeof o === 'object' && o.getAvailableQualities) {
                players.push({ key: k, type: 'window' });
              }
            } catch (e) { /* ignore */ }
          }
        }
      } catch (e) { /* ignore */ }
      // Check document for player containers
      var containers = document.querySelectorAll('.bitmovinplayer-container, [data-bitmovin-player]');
      if (containers.length > 0) {
        diagnostic('bitmovin_container', { count: containers.length, id: containers[0].id || '' });
      }
      // Find Bitmovin quality button in the player UI
      var qualityBtn = document.querySelector('.bitmovin-quality-menu, [class*="quality"], .bitmovin-settings-menu, [class*="settings"]');
      if (qualityBtn) {
        diagnostic('bitmovin_quality_btn', {
          tag: qualityBtn.tagName,
          cls: (qualityBtn.className || '').substring(0, 100),
          id: qualityBtn.id || ''
        });
      }
      // Find all buttons in the player container
      var playerContainer = document.querySelector('.bitmovinplayer-container');
      if (playerContainer) {
        var buttons = playerContainer.querySelectorAll('button, [role="button"], .bitmovin-button');
        var btnInfo = [];
        for (var bi = 0; bi < Math.min(buttons.length, 10); bi++) {
          btnInfo.push({
            tag: buttons[bi].tagName,
            cls: (buttons[bi].className || '').substring(0, 60),
            text: (buttons[bi].textContent || '').substring(0, 30)
          });
        }
        diagnostic('bitmovin_buttons', { count: buttons.length, buttons: btnInfo });
      }
      // Try to set quality via Bitmovin UI: click settings, then select quality
      trySetBitmovinQuality();
      if (players.length > 0 || containers.length > 0) {
        diagnostic('bitmovin_scan', { players: players.length, containers: containers.length });
      }
    } catch (e) { /* ignore */ }
  }

  // Try to set quality by interacting with the Bitmovin player UI.
  // Click the settings button, wait for the quality menu, then click the preferred quality.
  function trySetBitmovinQuality() {
    if (!lowQualityOn()) return;
    try {
      var settingsBtn = document.querySelector('.bmpui-ui-settingstogglebutton');
      if (!settingsBtn) return;
      // Click the settings button to open the menu
      settingsBtn.click();
      diagnostic('bitmovin_settings_clicked', {});
      // Wait for the quality menu to appear, then select the preferred quality
      setTimeout(function () {
        try {
          // Look for quality options in the settings menu
          var qualityOptions = document.querySelectorAll('.bmpui-ui-qualityoption, [class*="quality"] [class*="option"], .bmpui-ui-settingsmenu [class*="quality"]');
          if (qualityOptions.length > 0) {
            diagnostic('bitmovin_quality_options', { count: qualityOptions.length });
            // Find the preferred quality
            var best = null;
            var bestScore = Infinity;
            for (var qi = 0; qi < qualityOptions.length; qi++) {
              var opt = qualityOptions[qi];
              var text = (opt.textContent || '').trim();
              // Parse height from text (e.g., "720p", "1080p", "Auto")
              var m = text.match(/(\d{3,4})p?/i);
              if (m) {
                var h = parseInt(m[1], 10);
                var score = 0;
                for (var pi = 0; pi < qualityPreference.length; pi++) {
                  var d = Math.abs(h - qualityPreference[pi]);
                  if (d < score) score = d;
                }
                if (score < bestScore) { bestScore = score; best = opt; }
              }
            }
            if (best) {
              best.click();
              diagnostic('bitmovin_quality_selected', { text: (best.textContent || '').trim() });
            }
          } else {
            diagnostic('bitmovin_quality_menu_not_found', {});
          }
        } catch (e) { /* ignore */ }
      }, 500);
    } catch (e) { /* ignore */ }
  }

  // Probe network requests to understand what the MSE player downloads.
  // COALESCED per host: a segment-heavy site (HLS/DASH) can emit thousands of
  // per-request events in one session, which pushes other evidence out of the
  // 2 MiB diagnostic rotation. Instead of one event per segment, aggregate per
  // host over a 10 s window and emit a single summary (count + first/last URL +
  // method counts). Diagnostics-only; never affects page behavior.
  var __netAgg = {};
  var __netAggCap = 128;
  var __netFlushTimer = 0;
  function netAggRecord(u, method) {
    if (!diagnosticEnabled) return;
    try {
      var host = 'unknown';
      try { host = new URL(u, location.href).host; } catch (e2) { host = 'unknown'; }
      var now = Date.now();
      try { if (typeof performance !== 'undefined' && performance.now) now = performance.now(); } catch (e3) {}
      var e = __netAgg[host];
      if (!e) {
        var n = 0; for (var k in __netAgg) { if (__netAgg.hasOwnProperty(k)) n++; }
        if (n >= __netAggCap) { netAggFlush(); }
        e = __netAgg[host] = { count: 0, first: u.substring(0, 200), last: u.substring(0, 200), t0: now, t1: now, methods: {} };
      }
      e.count++;
      e.last = u.substring(0, 200);
      e.t1 = now;
      var m = method || 'GET';
      e.methods[m] = (e.methods[m] || 0) + 1;
    } catch (e) { /* ignore */ }
  }
  function netAggFlush() {
    try {
      for (var host in __netAgg) {
        if (!__netAgg.hasOwnProperty(host)) continue;
        var e = __netAgg[host];
        if (e && e.count > 0) {
          diagnostic('net_segment_summary', {
            host: host,
            count: e.count,
            first: e.first,
            last: e.last,
            windowMs: Math.round(e.t1 - e.t0),
            methods: e.methods
          });
        }
        delete __netAgg[host];
      }
    } catch (e) { /* ignore */ }
  }
  var __netProbeInstalled = false;
  function installNetworkProbe() {
    if (!__netProbeInstalled) {
    __netProbeInstalled = true;
    var origFetch = window.fetch;
    window.fetch = function(url, opts) {
      if (!diagnosticEnabled) return origFetch.apply(this, arguments);
      try {
        var u = String(url);
        if (/\.(m3u8|m4s|ts|mp4|mpd)(\?|$)/i.test(u) || /segment|chunk|part/i.test(u)) {
          netAggRecord(u, (opts && opts.method) || 'GET');
        }
      } catch (e) { /* ignore */ }
      return origFetch.apply(this, arguments);
    };
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) {
      if (!diagnosticEnabled) return origOpen.apply(this, arguments);
      try {
        var u = String(url);
        if (/\.(m3u8|m4s|ts|mp4|mpd)(\?|$)/i.test(u) || /segment|chunk|part/i.test(u)) {
          netAggRecord(u, method);
        }
      } catch (e) { /* ignore */ }
      return origOpen.apply(this, arguments);
    };
    }
    try { if (!__netFlushTimer) __netFlushTimer = setInterval(netAggFlush, 10000); } catch (e) { /* ignore */ }
  }

  function diagnostic(event, data) {
    if (!diagnosticEnabled) return;
    try {
      var b = window.Buccaneer;
      if (b && typeof b.diagnosticEvent === 'function') b.diagnosticEvent(String(event), JSON.stringify(data || {}));
    } catch (e) { /* diagnostics are best effort */ }
  }

  function mediaDescription(m) {
    var q = null;
    try { q = m.getVideoPlaybackQuality ? m.getVideoPlaybackQuality() : null; } catch (e) { q = null; }
    // A later disconnected range is not playable buffer at the current position.
    // Bound diagnostic work even for a hostile/fragmented TimeRanges object.
    var bufferAhead = -1, bufferRanges = [], bufferRangeCount = -1;
    var bufferRangesTruncated = false;
    try {
      var ranges = m.buffered, position = m.currentTime;
      bufferRangeCount = ranges ? ranges.length : 0;
      bufferRangesTruncated = bufferRangeCount > 32;
      if (isFinite(position)) {
        bufferAhead = bufferRangesTruncated ? -1 : 0;
        for (var bi = 0; bi < Math.min(bufferRangeCount, 32); bi++) {
          var bs = ranges.start(bi), be = ranges.end(bi);
          if (!isFinite(bs) || !isFinite(be) || be < bs) continue;
          bufferRanges.push([Number(bs.toFixed(2)), Number(be.toFixed(2))]);
          if (bs <= position && position < be) bufferAhead = Math.max(0, be - position);
        }
      }
    } catch (e) { bufferAhead = -1; }
    var frameKind = 'unknown', mediaWindow = null;
    try {
      mediaWindow = m.ownerDocument && m.ownerDocument.defaultView;
      if (mediaWindow && mediaWindow.top === mediaWindow) frameKind = 'top';
      else if (mediaWindow) {
        // Compare with the top, not with this injection's own window. A denied
        // top read means cross-origin or an opaque sandbox, not same-origin.
        try {
          var ownOrigin = mediaWindow.location.origin, topOrigin = mediaWindow.top.location.origin;
          frameKind = ownOrigin === 'null' || topOrigin === 'null' ? 'opaque' :
            (ownOrigin === topOrigin ? 'same-origin' : 'cross-origin');
        } catch (e) { frameKind = 'cross-origin-or-opaque'; }
      }
    } catch (e) { /* leave unknown */ }
    // Diagnostic reads must not create a GL context on a page-owned canvas.
    // getContext() is not a read-only capability check: it can allocate GPU state
    // or lock an unused canvas to the wrong renderer. Report the state as unknown.
    var canvasCount = 0, webgl = -1;
    try { canvasCount = m.ownerDocument.getElementsByTagName('canvas').length; } catch (e) {}
    var out = {
      kind: m.tagName || 'MEDIA',
      pageUrl: window.location.href || '',
      topFrame: mediaWindow ? (mediaWindow.top === mediaWindow ? 1 : 0) : -1,
      // "decoded but not displayed" is only distinguishable from a stalled decoder if we know
      // whether this element is the one on screen and which kind of frame it lives in.
      visible: isVisible(m) ? 1 : 0,
      frameKind: frameKind,
      canvasCount: canvasCount,
      webgl: webgl,
      src: m.currentSrc || m.src || '',
      width: m.videoWidth || 0,
      height: m.videoHeight || 0,
      readyState: m.readyState,
      networkState: m.networkState,
      paused: m.paused ? 1 : 0,
      muted: m.muted ? 1 : 0,
      volume: typeof m.volume === 'number' ? Number(m.volume.toFixed(2)) : 0,
      playbackRate: typeof m.playbackRate === 'number' ? Number(m.playbackRate.toFixed(2)) : 1,
      currentTime: isFinite(m.currentTime) ? Number(m.currentTime.toFixed(2)) : 0,
      duration: isFinite(m.duration) ? Number(m.duration.toFixed(2)) : 0,
      bufferAhead: Number(bufferAhead.toFixed ? bufferAhead.toFixed(2) : bufferAhead),
      bufferRangeCount: bufferRangeCount,
      bufferRangesTruncated: bufferRangesTruncated ? 1 : 0,
      bufferRanges: bufferRanges,
      error: m.error ? (m.error.code || 0) : 0
    };
    if (q) {
      out.frames = typeof q.totalVideoFrames === 'number' ? q.totalVideoFrames : -1;
      out.dropped = typeof q.droppedVideoFrames === 'number' ? q.droppedVideoFrames : -1;
    }
    return out;
  }

  var geometryReported = false;
  // Existing media events/coordination refresh the native flag in each frame.
  // No new idle-frame bridge poll, timer, message channel or the poster-grid site instrumentation.
  function refreshDiagnostics() {
    var enabled = bridgeCall('isDiagnosticsEnabled');
    if (typeof enabled === 'boolean' && enabled !== diagnosticEnabled) window.__fb.setDiagnosticLogging(enabled);
  }

  function diagnosticMediaEvent(e) {
    refreshDiagnostics();
    if (!diagnosticEnabled || !isMedia(e.target)) return;
    diagnostic('media_' + e.type, mediaDescription(e.target));
    // Report player geometry once when playback starts: the frame hosting the video
    // reports the video rect; the top frame reports the iframe rect.
    if (!geometryReported && e.type === 'playing') {
      geometryReported = true;
      try { diagnostic('player_geometry', window.__fb.playerGeometry()); } catch (x) { /* ignore */ }
    }
  }

  function isMedia(el) {
    return !!el && (el.tagName === 'VIDEO' || el.tagName === 'AUDIO');
  }

  function playingMedia() {
    var list = document.querySelectorAll('video, audio');
    var out = [];
    for (var i = 0; i < list.length; i++) {
      if (!list[i].paused && !list[i].ended) out.push(list[i]);
    }
    return out;
  }

  // a real player: audio that is not muted, or a visible video that is not a muted background loop
  function isRealPlayer(m) {
    if (m.tagName === 'AUDIO') return !m.muted;
    return isVisible(m) && !(m.muted && m.loop);
  }

  function pauseQuietly(m) {
    try { m.pause(); } catch (e) { /* ignore */ }
  }

  document.addEventListener('play', function (e) {
    var m = e.target;
    if (!isMedia(m) || bridgeCall('exclusiveEnabled') !== true) return;
    if (isRealPlayer(m)) {
      var playing = playingMedia();
      for (var i = 0; i < playing.length; i++) {
        if (playing[i] !== m) pauseQuietly(playing[i]);
      }
      bridgeCall('mediaStarted', frameId);
    } else if (bridgeCall('activeOwner')) {
      pauseQuietly(m);   // hidden / background media waits while a real player is running
    }
  }, true);

  function mediaStopped(e) {
    if (!isMedia(e.target) || bridgeCall('exclusiveEnabled') !== true) return;
    if (playingMedia().length === 0) bridgeCall('mediaStopped', frameId);
  }
  document.addEventListener('pause', mediaStopped, true);
  document.addEventListener('ended', mediaStopped, true);

  var MEDIA_DIAG_EVENTS = ['loadedmetadata', 'canplay', 'play', 'playing', 'waiting', 'stalled', 'pause', 'ended', 'error', 'emptied'];
  for (var mdi = 0; mdi < MEDIA_DIAG_EVENTS.length; mdi++) {
    document.addEventListener(MEDIA_DIAG_EVENTS[mdi], diagnosticMediaEvent, true);
  }
  document.addEventListener('playing', function () { scheduleHealthSample(); }, true);

  // Opt-in health samples: enough to distinguish buffering from dropped-frame/render pressure.
  // There is no repeating timer while diagnostics are OFF; the sample reschedules itself only
  // while a media element is actively playing and diagnostics remain enabled.
  var healthTimer = 0;
  function scheduleHealthSample() {
    if (!diagnosticEnabled || healthTimer) return;
    healthTimer = setTimeout(function () {
      healthTimer = 0;
      refreshDiagnostics();
      if (!diagnosticEnabled) return;
      var playing = playingMedia();
      if (playing.length === 0) return;
      // Use the same local target selection as remote controls; do not silently
      // attribute the first playing advertisement to a selected opaque player.
      var target = pickTarget();
      if (target && target.video && target.video.ownerDocument === document) {
        var description = mediaDescription(target.video);
        description.sampleScope = 'local-selected-player';
        diagnostic('media_health', description);
      }
      scheduleHealthSample();
    }, 3000);
  }


  // Cross-frame coordination cannot be event-driven (another frame only tells the app), so this
  // is a fallback check. 2000 ms is enough: the play/pause events above handle the common case.
  setInterval(function () {
    var playing = playingMedia();
    if (playing.length === 0) return;
    if (typeof refreshDiagnostics === 'function') refreshDiagnostics();
    if (bridgeCall('pageIsForeground') === false) {
      for (var bg = 0; bg < playing.length; bg++) pauseQuietly(playing[bg]);
      return;
    }
    for (var pi = 0; pi < playing.length; pi++) {
      if (isRealPlayer(playing[pi])) { bridgeCall('playbackActive', documentId); break; }
    }
    if (playing.length === 0 || bridgeCall('exclusiveEnabled') !== true) return;
    var owner = bridgeCall('activeOwner');
    if (owner && owner !== frameId) {
      for (var i = 0; i < playing.length; i++) pauseQuietly(playing[i]);
    }
  }, 2000);

  // A real tap (not a script click) that ends with a text field focused -> tell the app.
  document.addEventListener('click', function (e) {
    if (!e.isTrusted) return;
    // If the tap landed on the player iframe, forward it into the iframe: the page's own
    // container can swallow touches that should reach the embedded player's controls.
    try {
      var t = e.target;
      var f = (t && t.tagName === 'IFRAME') ? t : (t && t.closest ? t.closest('iframe') : null);
      if (f && f.contentWindow) {
        var r = f.getBoundingClientRect();
        if (e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom) {
          var fx = (e.clientX - r.left) / (r.width || 1);
          var fy = (e.clientY - r.top) / (r.height || 1);
          var iw = f.contentWindow.innerWidth || r.width;
          var ih = f.contentWindow.innerHeight || r.height;
          var ev = new MouseEvent('click', {
            bubbles: true, cancelable: true, view: f.contentWindow,
            clientX: fx * iw, clientY: fy * ih,
            screenX: fx * iw, screenY: fy * ih
          });
          var doc = f.contentDocument;
          if (doc) {
            var target = doc.elementFromPoint(fx * iw, fy * ih) || doc.documentElement;
            target.dispatchEvent(ev);
          }
        }
      }
    } catch (x) { /* cross-origin: cannot forward */ }
    if (diagnosticEnabled) {
      try {
        var t = e.target;
        diagnostic('click', {
          pageUrl: window.location.href || '',
          topFrame: window.top === window ? 1 : 0,
          target: t ? (t.tagName || '') + (t.id ? '#' + t.id : '') : '',
          inPlayerUi: !!(t && t.closest && t.closest('button, [role=button], .player-controls, .control, video')) ? 1 : 0,
          x: Math.round(e.clientX), y: Math.round(e.clientY)
        });
      } catch (x) { /* ignore */ }
    }
    setTimeout(function () {
      try {
        var ae = document.activeElement;
        var ed = editableOf(ae);
        // Only open the keypad when the tap actually landed on the field itself.
        // Otherwise a tap on the player/video area can leave a chat box focused as a
        // side effect and pop the keypad unexpectedly (the cross-origin player site chat, etc.).
        var onField = false;
        if (ed && e.target) {
          onField = (e.target === ed) || (ed.contains && ed.contains(e.target));
          // A label or wrapper around the field also counts as a deliberate tap on it.
          if (!onField && e.target.closest) {
            var lab = e.target.closest('label');
            if (lab && lab.contains && lab.contains(ed)) onField = true;
          }
        }
        if (ed && onField && window.Buccaneer &&
            typeof window.Buccaneer.editableClicked === 'function') {
          window.Buccaneer.editableClickedForDocument(documentId);
        }
      } catch (x) { /* ignore */ }
    }, 40);
  }, true);

  // Confirm the click listener is registered in this frame.
  if (diagnosticEnabled) {
    try {
      var b = window.Buccaneer;
      if (b && typeof b.diagnosticEvent === 'function') {
        b.diagnosticEvent('click_listener_registered', JSON.stringify({
          url: window.location.href || '',
          topFrame: window.top === window ? 1 : 0
        }));
      }
    } catch (e) { /* ignore */ }
  }
})();
