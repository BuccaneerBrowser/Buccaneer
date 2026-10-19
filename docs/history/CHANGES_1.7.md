# Buccaneer 1.7 — bookmark paging and browser reliability

## Start-page bookmark paging and tagline

The start page now shows eight bookmarks at a time. When there are more than eight, Previous/Next arrows and a page counter appear above the bottom Search/address and Manage buttons. Arrows are hidden for eight or fewer entries and disabled at the list ends. Folder lists and folder contents also paginate. Returning from a folder restores the folder-list page; edits/deletions refresh and clamp the current page. Partial last pages retain the same pager position.

The tagline is now **Where's me buccaneers? — Under me bucc'n hat!**

## Additional audit fixes

The audit repaired case-sensitive bookmark URL deduplication, validated imports before changing saved data, preserved fullscreen QWERTY input routing, prevented stale panel-focus callbacks, cleaned up destroyed HLS instances, made optional UI-script installation independent of protected site scripts, and improved diagnostic redaction/session isolation. It also fixed API 22 compatibility checks and timestamp formatting. See AUDIT_1.7.md for scope and remaining risks.

## FMHY dark startup

A separate document-start asset sets FMHY's `vitepress-display-mode` and `vitepress-theme-appearance` preferences to match the app's Dark mode setting and applies the matching root classes. It only runs on fmhy.net/subdomains. Commit/finished callbacks reassert the theme as a fallback on older providers.

No global force-dark configuration was changed. No CSS inversion/filter is injected. The Flixer intro/image guard, native Flixer firewall/reset block, Coreflix/Popup Guard click workarounds, and native dark-mode implementation remain unchanged. Providers without document-start support may still show a brief light frame before the fallback applies.

## Submenu fitting and scrolling

- Recognized floating listboxes/menus (ARIA, Bootstrap-style, and Radix) that extend outside the viewport receive a bounded height, usable vertical overflow, and a positioning correction. Padding, borders, and minimum heights are accounted for. Ordinary inline navigation lists, videos, canvases, and iframe containers are not resized.
- Scrolling first targets the nearest scrollable container under the pointer, not the outer page. At a nested scroller's boundary it does not scroll the page behind the menu. Native mouse-wheel events are the fallback for custom handlers and cross-origin frames.
- For remote operation: **place the pointer over the list, then Menu -> Scroll submenu/page with D-pad**. Holding Menu also enters this mode when the remote generates held-key repeats. The pointer stays put while the D-pad scrolls the list. **Back exits scroll mode without navigating away; Select still clicks.**
- **Menu -> Fit open submenus to screen** is available for a recognized menu that needs another fitting pass.

These are conservative generic repairs, not a guarantee for every website's custom widget. Native HTML select popups, nonstandard markup, and menus clipped by unusual ancestors may need a site-specific fix. For those, record the site URL, show/season selector, and reproduction steps.

## App QWERTY keyboard

- TvWebView now returns no connection to the system IME, but retains Chromium's editor for app-driven typing, deletion, selection replacement, and clearing. This is important for embedded/cross-origin fields and sites that require normal browser editing behavior.
- Focus changes detect delayed search fields and open-shadow-root inputs, not only the original 40ms post-click window. Focus reports are accepted around recent user input; they do not log field values. Password previews are masked.
- JavaScript text prompts also use the existing app QWERTY keypad instead of Android's prompt editor.
- When no native editor is available, a tested DOM editing fallback covers local/same-origin fields and frames. Cross-origin fields use native editing/key events; no cross-origin message-based text-editing bypass is introduced.
- The existing QWERTY layout and symbol layer are unchanged.

This controls editors inside the browser, not another app's or Fire OS's keyboard (for example a system file picker). Closed shadow roots and nonstandard editors still need real-device verification.

## Twenty-minute idle dimming

The browser displays a dark native overlay after approximately 20 minutes without user input or detected playback. It dims the **app's displayed image**, not the TV's hardware backlight/brightness setting.

Playback checks combine native audio activity, frame-local playback heartbeats piggybacked on the existing two-second media coordination timer, and a read-only top-page check while idle. There is no added repeating JavaScript timer or new instrumentation on Flixer-owned documents. A stalled/unsupported player that cannot report its state may require device-specific follow-up.

Remote, touch, or mouse input restores full visibility. The first wake press/tap is consumed so it cannot accidentally select, navigate, or pause a video. Subsequent input operates normally. Playback detected during a dimmed state also restores visibility on the next check. Paused playback counts as inactivity; the timeout starts from the last observed playback/input activity. The check interval is ten seconds.

Fire OS's own screensaver/sleep settings remain separate and unchanged. Foreground resume restores visibility and starts a fresh idle interval.

## Compatibility and installation

Same package (`com.buccaneer.browser`), preferences/bookmarks, compile SDK 34, target SDK 33, minimum SDK 22, and AndroidX WebKit 1.9.0. Version code is 6, version name is 1.7. All 1.6 quality/stability changes remain in place.

**Do not uninstall.** Update with the signing key used by the installed app. The attached test APK uses a debug key from this build environment. Its key differs from the v1.6 test APK previously supplied in this chat, so it is not a drop-in update over that APK. For an in-place update, rebuild the source using the key for your installed version; do not delete the installed app to bypass a signature mismatch. Uninstalling erases app-private bookmarks/settings.

## Tests and real-device checklist

```
gradle assembleDebug testDebugUnitTest lintDebug
node tests/quality-tests.cjs
node tests/fmhy-theme-tests.cjs
python3 tests/check-protected-fixes.py
python3 tests/run-dom-tests.py
```

Use JDK 17, Gradle 8.5, SDK/Build Tools 34. DOM fixtures require Chromium/Chrome on PATH. They are offline fixtures, not real-site or Fire TV tests.

On the Fire TV:

1. Open FMHY cold with app Dark mode ON, then open Coreflix and Flixer; verify all themes and the preserved intro/button behavior. Repeat after toggling Dark mode OFF/ON.
2. Open the season menus that previously failed; check the first/last entries, scroll-mode behavior, and the fitting action. Record any remaining site-specific failure.
3. Test both search icons, a delayed search panel, an embedded search box, backspace/clear/Enter, and keyboard dismissal/reopening. Confirm only the app QWERTY keypad appears.
4. Leave a paused/stopped page idle for 20 minutes; check dimming and a wake-only first press. Repeat during at least 25 minutes of playback, including an embedded player, to check that it stays bright.
5. Try 0, 8, 9, and 21 bookmarks; move both directions, open/back out of folders, and delete entries on the last page. Check all controls fit at your TV resolution.
6. Repeat Coreflix -> Cinejoy -> Flixer and the previous stuck-video sequence. Check bookmark preservation after updating.

No physical Fire TV was connected. See VALIDATION_1.7.md for automated results and limitations. Historical 1.3/1.6 documents remain for reference.
