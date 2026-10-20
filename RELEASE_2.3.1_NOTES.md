# Buccaneer 2.3.1

## What's new

### Start page UI
- New header: logo centered in the tagline ("Where's me buccaneers? [logo] Under me bucc'n hat!"), no separate title text
- 10 bookmarks per screen (2 rows of 5), up from 8
- Running version shown under the hint line (read live from the package, always matches the installed build)
- Darker background (dark gray) and slightly muted button text for nighttime viewing

### Fixes
- **Keypad no longer pops up unexpectedly** — the on-screen keypad now only opens when you tap a text field directly. Previously, a tap on the player/video area could leave a chat box focused as a side effect and trigger the keypad (seen on StreamCorner).
- **YouTube fullscreen controls stay hidden** — the auto-hide feature (which sends synthetic pointer activity to hide controls on sites like CineJoy) now skips YouTube. YouTube's player re-shows controls on any pointer activity, so the synthetic poke was perpetually re-waking them. YouTube's own idle logic now handles hiding.
- **Player iframes get fullscreen permission** — all player iframes now have `allowfullscreen` and `allow="fullscreen"` set, ensuring the fullscreen permission is granted (alongside the existing sandbox removal).

## Notes
- The CoreFlux fullscreen issue (video in a cross-origin iframe) is a limitation of the old system WebView's Fullscreen API permissions check, not a Buccaneer bug. The video plays in its normal layout; fullscreen via the page's `iframe.requestFullscreen()` is blocked by the WebView.

## Build
- versionCode 24, versionName 2.3.1
- minSdk 22 (Android 5.1 / Fire OS 5), targetSdk 33
