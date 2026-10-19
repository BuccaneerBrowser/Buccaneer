# Buccaneer 1.7.1 — focused regression repair

This version addresses the owner's report after testing 1.7. It does not claim that an offline test pass proves real Fire TV playback works. Compile SDK remains 34, target SDK 33, minimum 22. Version code 7, version name 1.7.1.

## Repairs

### Physical Play/Pause and other transport keys

- Physical Play/Pause, Play, Pause, Fast-forward, and Rewind now have a shared route before menu/list/text-panel focus routing. Previously an open native panel could let the key fall through without invoking the browser's playback command.
- The app QWERTY keypad intentionally keeps its Play-as-Go/Enter shortcut during text entry.
- If the top-frame video helper is missing or reports no reachable player, Play/Pause/Play/Pause are passed to the WebView as their native media key, rather than being consumed with only a “No video” toast. Players may handle this via their native media-session/default key path. This is a fallback, not a guarantee for every site's custom player.
- Pause no longer uses the play/start retry path. For an opaque frame it sends Pause, not a center tap that could accidentally toggle playback on.
- Native fullscreen Select and left/right controls remain in place. Held transport keys do not repeatedly toggle play/pause; existing FF/Rewind hold behavior is preserved.

### Bookmark import and layering

- Import validates and applies the selected backup immediately, without the replacement-confirmation menu. A successful import replaces the current bookmark list, matching the existing import format/semantics; it is not a merge.
- Invalid files leave existing bookmarks intact and show the existing error toast.
- Import/export/options menus hide the bookmark panel and come to the front, rather than appearing behind it. Showing bookmarks hides a stale menu. Text panels and QWERTY prompts are also explicitly brought in front when opened, including over custom fullscreen content.

### Flixer seek-bar pointer

- The original touch path is preserved for ordinary Flixer buttons and all pointer clicks on other sites, including Coreflix.
- Only when a one-shot hit test identifies a supported timeline, or an opaque embedded frame, does Flixer Select use a native mouse-primary down/up sequence. This supplies a mouse tool type/button state, not a JavaScript-forged click.
- A bounded, on-demand fallback maps a recognized seek track's horizontal position to the associated video's time. It avoids volume/speed/quality sliders and ordinary buttons, does not guess with multiple videos or unknown duration, respects seekable gaps, and skips a seek already completed by native input.
- The seek helper is evaluated only for an explicit Flixer pointer action. It is not registered at document start and adds no DOM observers, event listeners, CSS changes, intro changes, animations, or recurring timers. It only traverses same-origin frames; opaque frames receive native input, not a same-origin-policy bypass.
- Supported tracks include labelled ARIA/range timeline sliders and recognized Video.js/JW/Plyr/media-time-slider controls. A completely custom unlabelled track may still need the exact show/player URL and device diagnostics. Pointer clicks inside native custom fullscreen content retain the existing fullscreen controls; the new DOM seek fallback applies to the web-page player.

## Protected behavior

Original Flixer intro/image guard, native Flixer firewall/render-reset block, Coreflix/Popup Guard workarounds, and native dark-mode implementation are unchanged. Original protected-helper fingerprint checks remain in the test suite. Quality preference, FMHY theme changes, bookmark paging/tagline, QWERTY, and idle dimming from 1.7 remain.

## Validation commands

```sh
gradle assembleDebug testDebugUnitTest lintDebug
node tests/quality-tests.cjs
node tests/fmhy-theme-tests.cjs
python3 tests/check-protected-fixes.py
node tests/seek-click-tests.cjs
python3 tests/run-dom-tests.py
python3 tests/run-seek-dom-tests.py
```

Use JDK 17, Gradle 8.5 and Android SDK/Build Tools 34. Offline DOM tests require Chromium/Chrome on PATH. Scripted DOM/media objects and Robolectric's mocked API 28 are not Amazon WebView/GPU/player integration tests.

## Device check — required before calling the repair verified

1. On the same failing site/player: short Play/Pause starts, next press pauses, next resumes. Try in-page, native fullscreen, and with a native menu open. Check a held Play press doesn't toggle repeatedly. Separate Pause must never start a video.
2. In a search field, Play remains Go/Enter on the app keyboard. Outside text entry it controls playback.
3. From Bookmarks, open Import/Export/options and verify the active menu is readable. Import a backup: it applies directly with no second confirmation. Verify folders, paging, and an invalid-file attempt.
4. On Flixer, click several positions along the seek bar, both forward/backward; check playback lands near the chosen time and the play/pause/settings buttons still work. Repeat with any alternate embedded player. If native fullscreen is active, exit fullscreen to test the webpage seek bar.
5. Repeat the previously working Coreflix button sequence and Flixer intro-skipping/navigation sequence; check no new crash or dark-theme regression.

## Fresh installation

This deliverable is for a fresh install, not an update over another APK. Preserve your bookmark export outside app-specific storage before removing an older installation. Install the 1.7.1 APK, import the backup directly (no confirmation), and reapply other settings. No signing-key configuration is required for a fresh install.

See VALIDATION_1.7.1.md for the completed check results. Earlier release/audit notes are historical and do not override the changes here.
