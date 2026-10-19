# Buccaneer 2.2 — source changes

## Settings changes

- Android app metadata is now `versionName = "2.2"`, `versionCode = 22`.
- Added **Settings → Prefer low quality streams** (default ON). When ON, the
  app tries to select lower-resolution streams (480p → 720p → others → 1080p)
  on HLS.js and Shaka players. When OFF, players choose their own quality.
- Removed the **Native user agent (verification test)** setting. It was a
  diagnostic leftover that never worked for its intended purpose (Cloudflare
  Turnstile challenges fingerprint the engine, not just the UA string). The
  "Desktop site" toggle covers the two useful modes.

## Quality preference system

- `page_helpers.js` has a quality preference scanner: when `loadedmetadata`
  fires, it scans for HLS.js (`levels` array + `loadSource`) and Shaka
  (`getVariantTracks`) instances and sets the preferred level.
- Preference order: 480p → 720p → others → 1080p (last resort).
- Gated behind the `lowQualityEnabled()` bridge method.
- Bitmovin player scan: finds the settings button and quality options in the
  player UI. Quality selection requires a trusted user gesture (manual).

## StreamCorner fix

- Sandbox attribute removal from player iframes before they load.
- Error overlay hider: the "Remove sandbox attributes" error message is hidden
  when the video is playing behind it.

## Flixer image fix

- Cache-busting query param + allowlist in `flixer_image_guard.js`.
- The native TMDB firewall serves 1×1 pixels for non-allowlisted URLs; the
  browser cached them. Cache-buster forces a new request → firewall allows →
  real image loads.

## Popup guard improvements

- Deferred `reactOnClick` fix (300ms after pointerup) to prevent double-activation.
- Content-based ad detection: ad text patterns override `hasControls` skip.
- Coverage threshold lowered from 50% to 10% of video area.

## Keypad fix

- `Keypad.handleKey` now checks if the highlighted key is `Kind.GO` — if so,
  Enter/Select triggers `onGo()` (submit). Previously ENTER only submitted
  from the grid's own GO key.

## Validation

Source-level changes were reviewed. Build and install verified on the Fire TV
device. Diagnostics confirm expected events.
