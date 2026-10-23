# Buccaneer 2.3.2

A lightweight web browser for Android TV, designed for remote control navigation and streaming content.

## Features

- **TV-optimized UI** — Full remote control navigation with D-pad, no mouse or touch required
- **Streaming optimized** — Autoplay support, popup blocking, and video playback controls
- **Bookmarks** — Save, organize, and manage bookmarks with folder support
- **Dark mode** — Native dark theme for comfortable night-time viewing
- **Desktop site mode** — Request desktop versions of websites for better layouts
- **Quality preference** — Optionally prefer lower-resolution streams (480p → 720p → 1080p)
- **One video at a time** — Prevents multiple videos from playing simultaneously
- **Popup guard** — Blocks ad popups and overlay ads
- **Crash recovery** — Watchdog service restarts the browser after GPU crashes
- **Keypad** — On-screen keyboard for typing in search fields and forms

## Requirements

- Android TV / Fire TV (tested on Fire TV, Android 7.1.2, API 25)
- Minimum SDK: 22
- Target SDK: 33
- Compile SDK: 34

## Building

Requires JDK 17, Android SDK 34, and Gradle 8.5+.

For a **debug** build (no signing required):

```sh
gradle assembleDebug
```

For a **release** build (signed, proper app icon on Fire TV):

```sh
export BUCCANEER_KEYSTORE_PASSWORD="your-keystore-password"
export BUCCANEER_KEY_PASSWORD="your-key-password"
gradle assembleRelease
```

The keystore file (`buccaneer-release.keystore`) is **not** included in this repo. Both environment variables are **required** — the build will fail with a clear error if they're not set.

### GitHub Actions (automated releases)

The repo includes a GitHub Actions workflow (`.github/workflows/build.yml`) that builds and signs release APKs automatically when a version tag is pushed. The release keystore lives only in GitHub repository secrets — it is never committed to this repo.

**Setup (one-time):**

1. Generate a base64-encoded keystore:
   ```sh
   base64 -w0 buccaneer-release.keystore
   ```

2. Add these secrets to your GitHub repo (Settings → Secrets → Actions):
   - `BUCCANEER_KEYSTORE_BASE64` — the base64-encoded keystore
   - `BUCCANEER_KEYSTORE_PASSWORD` — keystore password
   - `BUCCANEER_KEY_PASSWORD` — key password

3. Tag a release (replace `vX.Y.Z` with the new version):
   ```sh
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```

The workflow will build the signed APK and create a GitHub Release automatically.

The APK is output to `app/build/outputs/apk/release/app-release.apk`.

## Installation

```sh
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or sideload the APK to your device.

## Usage

### Navigation

- **D-pad** — Navigate pages, menus, and interactive elements
- **Select/OK** — Activate the focused element
- **Back** — Go back in history or close menus
- **Menu** — Open the main menu

### Main Menu

The main menu (Menu button) provides:

1. **Scroll submenu/page with D-pad** — D-pad scrolls under the pointer; Back returns to pointer mode
2. **Start page** — Return to the bookmark start page
3. **Reload** — Reload the current page
4. **Add bookmark** — Save the current page (shows "Remove bookmark" if already saved)
5. **Bookmarks...** — Browse saved bookmarks
6. **Address / search** — Open a search/address field
7. **Full-screen video** — Toggle fullscreen video
8. **Fit open submenus to screen** — Adjust recognized dropdown menus
9. **Block redirects from [site]: ON/OFF** — Toggle redirect blocking for current site
10. **Back** — Go back in history
11. **Settings...** — Open settings
12. **Exit browser** — Close the app

### Settings

- **Dark mode** — Toggle dark theme
- **Desktop site** — Request desktop versions of websites
- **Autoplay without a click** — Allow videos to autoplay
- **Prefer low quality streams** — Prefer 480p/720p over 1080p (default ON)
- **One video at a time** — Pause other videos when one starts
- **Software rendering** — Use software rendering (workaround for GPU issues)
- **Popup Guard** — Toggle popup blocking (shows blocked count)
- **Diagnostics** — Enable/disable diagnostic logging

### Bookmarks

- **Add** — From the main menu, saves the current page
- **Browse** — From the main menu, view all bookmarks
- **Organize** — Move bookmarks up/down, create folders
- **Import/Export** — Transfer bookmarks between devices (Downloads folder is default directory)

### Video Playback

- **Play/Pause** — Select/OK on the video, or hardware play/pause button
- **Fullscreen** — Menu → Full screen, or tap the player's fullscreen button
- **Seek** — Hardware fast-forward/rewind buttons, or tap the on-screen seek bar
- **Quality** — Some players expose quality selection in their own UI

## Diagnostics

Diagnostic logging is OFF by default. To enable:

1. Open Settings → Diagnostics
2. Toggle "Diagnostic logging" to ON
3. Restart the app

Diagnostics write to `files/diagnostic_session.jsonl` in the app's private storage.

## Project Structure

```
app/
  src/main/
    java/com/buccaneer/browser/  # Kotlin source
    assets/                       # Injected JavaScript
    res/                          # Resources (icons, layouts)
  build.gradle.kts
tests/                            # Test suite
build.gradle.kts
settings.gradle.kts
```

## License

MIT
