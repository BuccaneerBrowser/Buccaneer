// Scroll restore.
//
// Some SPA frameworks (e.g. VitePress/Vue portals) call window.scrollTo(0, 0) when
// hydration completes, which yanks the user back to the top of a page they had already
// scrolled down. The app cannot prevent the page from doing this, but it can restore the
// user's position right after.
//
// This script runs at document start (before any page script). It captures the native
// scrollTo and wraps window.scrollTo. When the page scrolls to the top while the user is
// well below the top, that is a reset — the script restores the previous position using
// the captured native scrollTo (so the page cannot intercept the restore).
//
// Detection happens in the scrollTo wrapper itself (synchronous, at the moment the page
// calls scrollTo), not only via a timer: a setInterval tick is delayed while the page
// thread is busy (hydration), which is exactly when the reset occurs, so a timer alone
// can miss the intermediate scrolled state. A light interval is kept as a fallback for
// resets that do not go through window.scrollTo.
//
// A gradual user scroll to the top never triggers this: the user's own scrolling does not
// call window.scrollTo(0, 0) from well below the top in one step, and the interval
// fallback requires a large single-tick drop. A 2 s debounce prevents rapid re-restores.
// The script is self-guarding and idempotent.
(() => {
  if (window.__scrollRestoreActive) return;
  window.__scrollRestoreActive = true;

  // Capture the native scrollTo now, before any page script can replace window.scrollTo.
  const nativeScrollTo = window.scrollTo.bind(window);

  let lastRestoreAt = 0;
  // Diagnostic counter: incremented each time a restore fires. Readable via CDP as
  // window.__scrollRestoreCount so tests can confirm the restore actually ran.
  window.__scrollRestoreCount = 0;

  // Restore the previous position after letting the page's reset and any re-layout settle.
  const restore = (y) => {
    const now = Date.now();
    if (now - lastRestoreAt <= 2000) return;
    lastRestoreAt = now;
    window.__scrollRestoreCount++;
    setTimeout(() => {
      try { nativeScrollTo(0, y); } catch (e) {}
    }, 150);
  };

  // Diagnostic: log every window.scrollTo call so we can see when (and whether) the page
  // resets the scroll. The restore uses nativeScrollTo (not this hook), so only
  // page-originated calls appear here.
  window.__scrollLog = [];
  const origScrollTo = window.scrollTo;
  window.scrollTo = function (x, y) {
    try {
      window.__scrollLog.push({ t: Date.now(), x: x, y: y });
      if (window.__scrollLog.length > 300) window.__scrollLog.shift();
      // Reset to the top while the user is well below: restore the previous position.
      const curY = window.scrollY || 0;
      if (y <= 5 && curY > 100) restore(curY);
    } catch (e) {}
    return origScrollTo.apply(window, arguments);
  };

  // Fallback: catch resets that do not go through window.scrollTo (e.g. scrollTop = 0).
  // Requires an ABRUPT drop to the top in a single tick (a gradual user scroll never does).
  let lastY = 0;
  const tick = () => {
    const y = window.scrollY || 0;
    const drop = lastY - y;
    if (lastY > 100 && y <= 5 && drop >= 150) restore(lastY);
    lastY = y;
  };
  setInterval(tick, 100);
})();
