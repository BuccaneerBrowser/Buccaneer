// FMHY's own theme only: no filters or changes to other origins/WebView darkening.
(function () {
  'use strict';
  var host = (location.hostname || '').toLowerCase().replace(/\.$/, '');
  if (!/(^|\.)fmhy\.net$/.test(host)) return;
  var dark;
  try { dark = !!window.Buccaneer.isDarkMode(); } catch (e) { return; }
  try {
    localStorage.setItem('vitepress-display-mode', dark ? 'dark' : 'light');
    localStorage.setItem('vitepress-theme-appearance', dark ? 'dark' : 'light');
  } catch (e) {}
  function apply() {
    var root = document.documentElement;
    if (!root) return false;
    root.classList.toggle('dark', dark); root.classList.toggle('light', !dark);
    return true;
  }
  if (!apply()) {
    var observer = new MutationObserver(function () { if (apply()) observer.disconnect(); });
    observer.observe(document, { childList: true, subtree: true });
    document.addEventListener('DOMContentLoaded', function () { observer.disconnect(); apply(); }, {once:true});
  }
})();
