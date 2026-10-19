# Buccaneer v1.3 Diagnostic Logging Specification

Logging is opt-in and OFF by default. It is intended for real-device Fire TV / Android 7 playback and crash investigation.

## Controls
- Settings > Diagnostics > Diagnostic logging ON/OFF
- Start new diagnostic session
- Stop diagnostic logging
- Export diagnostic log
- Clear diagnostic log
- Show diagnostics snapshot

## Export
The export is a ZIP containing `session.json`, `events.jsonl`, and `README.txt`. URL query strings and fragments are removed. Cookies, authorization values, passwords, signatures, secrets, and common token/key fields are redacted.

## Captured signals
- device/API/app/build metadata
- WebView provider/package/version and User-Agent
- page start/commit/finish and main-frame page/HTTP errors
- navigation/redirect blocking
- popup/new-window requests and temporary popup WebViews
- warning/error console messages
- fullscreen events
- media play/pause/playing/waiting/stalled/ended/error transitions
- media dimensions, ready/network state, buffer-ahead, muted/volume/rate, media errors, and frame counters when exposed by WebView
- one-video-at-a-time owner transitions
- low-memory / pause/resume/destroy breadcrumbs
- Java exception and renderer-crash breadcrumbs

The logging layer does not change playback routing, popup policy, media polling cadence, User-Agent policy, or WebView architecture.
