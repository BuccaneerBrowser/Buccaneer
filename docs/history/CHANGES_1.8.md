# 1.8 changes — fresh installation

This build incorporates the post-1.7.1 source-wide audit corrections, rather than merely rebuilding the delivered 1.7.1 binary. README.md and VALIDATION_1.8.md are the current instructions; other versioned notes are historical.

## Requested long-distance seek

Short FF/Rewind remains 10 seconds. Hold requests 30 seconds, rising to 60 seconds after 2.5 seconds held; requests are limited to one every 900 ms, using event timestamps rather than device-specific repeat counts. Fullscreen Left/Right behave symmetrically. Quality changes are removed from held FF/Right and offered as a separate menu item.

When the passive video helper can reach the video, it seeks directly. When it reports an opaque frame/missing helper, the native fallback sends three/six distinct arrow-key pairs rather than one. Native steps are spaced 80 ms apart, capped at six, and page/lifecycle owned. Pause, navigation, a newer seek or switching the native target cancels unfinished steps; an open keypad or start page blocks them. Actual seconds in opaque players depend on their own arrow-key binding (10 seconds in the user's reported player).

## Audit corrections now included in the APK

- Text focus must be confirmed after a real tap; unrelated D-pad/media input no longer authorizes auto-opening the keypad. Navigation closes the old page keyboard.
- Removed the native editor experiment and restored original direct key-event typing while retaining the optional same-origin DOM editing fallback. System IME connections remain suppressed.
- Scroll hit-testing and deltas convert native pixels into the page's CSS viewport dimensions.
- Media diagnostics no longer probe page canvas contexts/GPU state, and disabled diagnostics return before expensive media inspection. Fixed the player geometry helper lookup.
- Pending native DOWN is paired with UP or CANCEL, including overlapping taps, navigation, fullscreen transitions and teardown; dead renderers are never called back into.
- Owned JavaScript prompts are cancelled or cleared on lifecycle boundaries. Empty/whitespace prompt confirmations remain valid.
- Renderer retry limits cover redirects in the recovery window; counter overflow cannot restart the budget.
- Manual quality selection chooses the nearest lower rendition and passive Flixer quality queries tolerate absent HLS tracking.
- Media buttons route independently of menu/list focus. Actual text-entry mode retains Play-as-Enter. Missing-helper playback preserves the native fallback; explicit Pause does not click/toggle playback.
- Valid bookmark import applies directly with no second confirmation. Panels are mutually exclusive and ordered above fullscreen, and stale deferred focus is guarded.

## Pointer position controls and idle safety

The browser's previously display-only position bar is now hit-tested from the remote pointer, with finite-duration validation and a passive absolute-seek request. Selection does not click the website underneath. Menu > Show playback controls reveals the bar without toggling playback. The bar is available only for an accessible finite-duration video, not a cross-origin opaque video; it does not claim to repair an inaccessible site's own timeline. The existing website timeline mouse probe and one-shot recognized-track fallback remain narrowly scoped to Flixer.

Idle queries combine generic playback with passive player state. Null, error, missing helper and opaque-player uncertainty keep the image bright. Confirmed inactivity retains the 20-minute overlay and first-input wake behavior. This fail-safe may prevent dimming on a paused but unobservable iframe; it avoids darkening a possibly playing video.

## Protected behavior and retained features

Original supplied 1.5 Flixer intro/image safeguards, native Flixer request/reset guards, Popup Guard/Coreflix React click workaround and native dark-mode implementation are preserved. The whole page helper is not byte-identical: documented generic diagnostics/quality fixes and passive empty-list guards remain.

FMHY-only dark startup; bounded generic menu fitting/nested scrolling; exposed-player preferred 400–800p selection with 1080-only fallback; eight-item bookmark/folder paging; tagline correction; bounded diagnostics, safe export/import and renderer recovery remain included.

## Installation and honest limits

Use the supplied signed APK as a fresh installation. Export bookmarks to a location outside app-specific storage before uninstalling; import afterward. No installer-side signing is required. No physical Fire TV, PowerVR driver, actual Flixer stream/DRM playback or live Coreflix input session was available. Automated checks are not a bug-free guarantee. See VALIDATION_1.8.md.
