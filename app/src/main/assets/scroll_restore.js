// Scroll restore. Some SPA frameworks (VitePress/Vue portals) call scrollTo(0,0) on
// hydration, yanking the user back to the top; the app can't stop the page but can
// restore the position right after. Runs at document start: captures the native
// scrollTo, wraps window.scrollTo, and restores the previous position (via the captured
// native scrollTo, so the page can't intercept the restore) when the page scrolls to the
// top while the user is well below.
//
// Detection is in the wrapper itself (synchronous), not only a timer: a setInterval tick
// is delayed while the page thread is busy (hydration) — exactly when the reset occurs —
// so a timer alone can miss the intermediate state. A light interval remains as a fallback
// for resets that don't go through window.scrollTo. A gradual user scroll never triggers
// this (the interval fallback requires a large single-tick drop); a 2 s debounce prevents
// rapid re-restores. Self-guarding and idempotent.
(() => {
  if (window.__scrollRestoreActive) return;
  window.__scrollRestoreActive = true;

  // Capture the native scrollTo now, before any page script can replace window.scrollTo.
  const nativeScrollTo = window.scrollTo.bind(window);

  // Track the URL at injection time. On SPA navigation (URL change), the new "page"
  // should start at the top — don't restore the old scroll position. Only block
  // scroll-to-top on the same URL (hydration reset).
  const initialUrl = location.href;
  const samePage = () => location.href === initialUrl;

  let lastRestoreAt = 0;
  // Diagnostic counter, incremented per restore; readable via CDP as window.__scrollRestoreCount so tests can confirm the restore ran.
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

  // Diagnostic: log every window.scrollTo call (the restore uses nativeScrollTo, not this hook, so only page-originated calls appear here).
  window.__scrollLog = [];
  const origScrollTo = window.scrollTo;
  window.scrollTo = function (x, y) {
    try {
      window.__scrollLog.push({ t: Date.now(), x: x, y: y });
      if (window.__scrollLog.length > 300) window.__scrollLog.shift();
      // Reset to the top while the user is well below: restore the previous position.
      // Only on the same page (hydration reset) — not on SPA navigation (new page).
      const curY = window.scrollY || 0;
      if (y <= 5 && curY > 100 && samePage()) restore(curY);
    } catch (e) {}
    return origScrollTo.apply(window, arguments);
  };

  // Fallback: catch resets that do not go through window.scrollTo (e.g. scrollTop = 0).
  // Requires an ABRUPT drop to the top in a single tick (a gradual user scroll never does).
  let lastY = 0;
  const tick = () => {
    const y = window.scrollY || 0;
    const drop = lastY - y;
    if (lastY > 100 && y <= 5 && drop >= 150 && samePage()) restore(lastY);
    lastY = y;
  };
  setInterval(tick, 100);
})();
