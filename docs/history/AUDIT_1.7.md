# Version 1.7 audit handoff

## Scope

Reviewed the Kotlin app sources and injected JavaScript for lifecycle, threading, input, navigation, playback, storage, layout, and diagnostics risks. Automated checks cover specific regressions, not proof that all defects are eliminated. No physical Fire TV was connected.

## Concrete repairs

- Start-page paging now handles all bookmarks/folders in groups of eight, safely clamps after edits, and keeps controls stationary on partial pages. Deferred focus callbacks cannot focus removed/hidden tiles or panels.
- URL comparison normalizes only scheme/host/default ports; case-sensitive path/query/fragment components remain distinct. Imports validate before modifying saved data, deduplicate, and roll back memory on commit failure.
- App keyboard/menu routing takes priority over custom fullscreen content. Native editor connections are retained for app typing but withheld from the system IME. DOM fallback does not bypass cross-origin boundaries.
- Optional FMHY/UI document-start scripts cannot prevent the original protected scripts from installing. Their fallback injection is tracked separately.
- Destroyed HLS players leave the tracking list; HLS constructor failures retain original exception behavior. Window-player scans tolerate throwing getters.
- Temp-popup navigation forwards only main-frame requests. First Back near boot no longer accidentally triggers exit. Loading-pointer state clears at full progress even if pageFinished is absent.
- Diagnostic arrays receive recursive URL/secret redaction; oversized payloads are safely omitted rather than cut into invalid JSON. Old-session writes/exports cannot mix or remove the new session's critical records. Crash URL logging is sanitized.
- Invalid slider/drawing fractions do not propagate NaN. OSD callbacks are removed when detached.
- Diagnostic timestamps use an API-22-compatible UTC format; legacy permission calls have explicit SDK bounds recognized by lint.

## Protected behavior

Fingerprint checks confirm the original Flixer intro/image guards, native Flixer firewall/reset block, Coreflix/Popup Guard click behavior, and native dark-mode implementation remain unchanged. The existing generic media timer has one deliberate playback heartbeat addition, below the Flixer exclusion; it is not a new recurring timer. Quality selection still prefers the highest available 400–800p, then the highest lower quality, otherwise the lowest above the range. A 1080p-only stream remains playable. Unsupported players or encrypted/opaque quality controls may not expose selectable levels.

## Remaining lint warnings

No lint baseline was added. Remaining warnings cover deliberate synchronous crash/retry preference persistence, retained target SDK 33, the older WebKit dependency, browser JavaScript enablement, launcher image density/shape, programmatically constructed views lacking XML-tool constructors, one keypad localization suggestion, and an attribute ignored on API 22. These are not silently declared fixed. Compile SDK is 34; target SDK was not raised as an untested behavior change. WebKit was not bulk-upgraded during the site-compatibility repair.

## Remaining risks / real-device verification

- Amazon WebView native input, hardware video decoding, DRM, GPU instability, and actual site interactions require Fire TV tests. Renderer recovery is not a cure for app-process native crashes on older providers.
- Document-start support is important to early site workarounds. Old providers can show a brief FMHY light frame before the fallback.
- Generic fitting cannot reliably repair native select popups, closed shadow roots, unusual clipping ancestors, or every custom season selector. Use the new pointer-targeted scroll mode; provide an exact site/reproduction for remaining failures.
- The dim overlay changes the app image, not physical TV brightness; Fire OS screensaver/sleep remains independent. Verify embedded/quiet/paused players on-device.
- Browser bridge APIs are exposed to web content and frames by design. The new DOM edit path is same-origin-only; this is not a security isolation guarantee for the whole legacy browser. Untrusted pages can invoke exposed bridge methods. Review diagnostic exports before sharing; free-form strings can still contain sensitive material despite structured redaction.
- Existing native Flixer GPU/reset logic, request firewall timing/shared state, and image-response behavior were deliberately not reworked. Their device-specific edge cases remain unverified.
- Normal bookmark startup recovery and underlying storage failure behavior need device testing; keep an exported bookmark backup.
- The test APK signing key differs from the v1.6 APK previously supplied. Do not uninstall to bypass the mismatch. Rebuild with the installed app's signing key to preserve an in-place update.
