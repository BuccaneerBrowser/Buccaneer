# Buccaneer 1.8 — fresh-install APK and source

The installable file is **BuccaneerClaude-1.8-fresh-install.apk**. This is a signed, sideloadable debug APK; no signing step is needed by the installer. Install it as a **new installation**, not over an older build. Save bookmarks outside the app's private/backup folder before uninstalling; import the JSON afterward. Valid imports apply directly without a confirmation window.

## Playback controls

- Tap FF or Rewind: request a 10-second skip.
- Hold: request 30-second skips, escalating to 60 seconds after 2.5 seconds held. At most one held request every 900 ms; release ends further requests (an already requested native burst can finish).
- Fullscreen Left/Right use the same acceleration. FF/Right no longer lower quality; **Menu > Lower video quality** is separate.
- For a player in an inaccessible iframe, held seeking sends three/six separate native arrow key pairs. Actual seconds depend on the player's own arrow-key increment. This does not bypass cross-origin access.
- **Menu > Show playback controls** exposes the browser position bar. In pointer mode, Select on that bar requests an absolute seek in a reachable finite-duration video, without clicking the site underneath. An opaque player does not expose that native position bar; use FF/Rewind or the site's controls.

## What this build includes

Media-key routing independent of menu/list focus; restored original key-event QWERTY input; corrected stale keyboard/tap/prompt lifecycle handling; direct bookmark import and mutually exclusive panels; original protected Flixer/Coreflix customizations; page-local FMHY dark startup; submenu fitting/nested scrolling; preferred exposed-player 400–800p quality with 1080-only fallback; eight-item bookmark paging; corrected tagline; bounded recovery/diagnostics; 20-minute idle overlay with wake-on-input. Unknown playback state keeps the screen bright rather than guessing that a video has stopped.

Read **VALIDATION_1.8.md** for exact checks and limitations. This build is not claimed bug-free or physically verified on Fire TV. The supplied 1.5 Flixer and Coreflix protections remain unchanged. Historical version notes and **FULL_SOURCE_REVIEW.md** describe earlier stages; the latter is a pre-1.8 audit, not the current release status.

---

## Original documentation (historical)

# Buccaneer - a web browser for Amazon Fire TV, driven entirely by the remote

Android app (Kotlin, system WebView) for Fire TV Stick / Fire TV / Fire TV Cube.
Package `com.buccaneer.browser`. Fire OS 5 (Android 5.1) and newer.

* **Start page** - shows eight bookmarks/folders per page, with previous/next arrows when more are available.
* **On-screen keypad** - letters, digits, symbols, `.com` / `www.` / `https://` shortcuts, all operated with the remote.
* **Bookmarks** with optional folders.
* **Popup Guard**, on by default - the `popup-guard.zip` Chrome extension ported to Android.
* **Full-screen video** with remote play/pause, seek and volume.
* **Dark mode** for web pages (on by default), plus a **Settings** menu with **Diagnostics**.

> Status: version 1.1. Version 1.0 was built and run on a Fire TV by its owner; 1.1 (video, scrolling and dark-mode
> fixes, see below) was written without access to the Android SDK, so it is untested until you build it.

## What changed in 1.1

* **Dark mode** for web pages, on by default (Settings > Dark mode).
* **No autoplay** unless you click (Settings > Autoplay without a click) and **one video at a time**: stops the
  "two sound tracks, no picture" problem caused by hidden previews and ads grabbing the single video decoder.
* **Scrolling** works on sites that scroll inside the page (rivestream.app and most web apps): the pointer sends
  mouse-wheel events when the page itself does not scroll.
* The remote's **Play/Pause** now picks the real player (a playing video, or the big embedded player frame) and taps
  it when the browser refuses to start a video before a click.
* **Settings** menu with **Diagnostics**: shows what each video is doing (is a picture being decoded?), the codecs
  the WebView supports, memory, and the last crash or "page killed" message. Crashes are now recorded there.

## Build

Needs Android Studio Hedgehog (2023.1) or newer (it bundles JDK 17), the Android SDK Platform 34
(Studio offers to install it) and internet access for the first Gradle sync.

1. Android Studio > **Open** > pick this `Buccaneer` folder, wait for the Gradle sync.
2. **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
3. The APK is `app/build/outputs/apk/debug/app-debug.apk`.

Command line: `gradle assembleDebug` (Gradle 8.5+, JDK 17, `ANDROID_HOME` set). There are no `gradlew`
scripts in the project; run `gradle wrapper` once if you want them.

## Put it on the Fire TV

1. Fire TV: Settings > My Fire TV > Developer Options > **ADB debugging: ON**.
   (No Developer Options? Settings > My Fire TV > About, click the device name 7 times.)
2. Note the IP: Settings > My Fire TV > About > Network.
3. On your computer: `adb connect <fire-tv-ip>:5555` (accept the prompt on the TV), then
   `adb install app-debug.apk` (fresh install only; uninstall the old app first after saving bookmarks outside app storage).
4. Start it from Apps > **Buccaneer**, or `adb shell am start -n com.buccaneer.browser/.MainActivity`.

No computer? Host the APK somewhere and install it with the *Downloader* app (turn on "Install unknown apps" for it).

## Controls

| Where | Button | What it does |
|---|---|---|
| **Start page** | D-pad / Select | move between the buttons / open |
| | Menu | open the menu |
| | Back | up out of a folder; at the top press twice to exit |
| **Browsing** | D-pad | move the pointer (hold to speed up); push it into a screen edge to scroll |
| | Select | click (the first press only wakes a hidden pointer) |
| | hold Select | open the keypad (types into the page if a text field is active, otherwise asks for an address) |
| | Menu | menu |
| | Back | previous page; from the first page back to the start page |
| | Play/Pause, Rew, FF | play/pause, back 10 s, forward 10 s on the page's video (hold Rew/FF: 30 s) |
| | Volume keys | volume |
| **Keypad** | D-pad / Select | move / type the highlighted key |
| | Rew / FF | delete / space |
| | Play/Pause | GO (open the address, or press Enter in the page) |
| | Menu | switch letters / symbols |
| | Back | close |
| **Full-screen video** | Select or Play/Pause | play / pause |
| | Left / Right (or Rew / FF) | seek 10 s (hold: 30 s) |
| | Up / Down (or volume keys) | volume |
| | Back | leave full screen |

The keypad also opens on its own when you click a text field in a page.

**Menu**: Address / search, Type into page field, Back / Forward / Reload, Add or Remove bookmark, Bookmarks...,
Start page, Full-screen video, Popup Guard ON/OFF, Block redirects from this site ON/OFF, Settings..., Exit.

**Settings** (all saved): Dark mode, Desktop site, Autoplay without a click (off), One video at a time (on),
Diagnostics.

* **Dark mode** uses the WebView's own darkening: sites that ship a dark style use it, the WebView darkens the
  rest. Very old WebViews cannot do this; Settings then says "not supported here".
* **Autoplay without a click** is off, so a hidden preview or an ad cannot start a video or sound by itself.
  Press Select on the player's play button (or the remote's Play) once per page.
* **One video at a time**: when a real player starts, other videos on the page, in any frame, are paused. Weak
  TV hardware has room for one video decoder, and two playing at once gives two sound tracks and no picture.

*Full-screen video* is for pages where the player's own button is awkward to hit: Buccaneer taps the player for you,
which is the "user gesture" browsers insist on before allowing full screen. You can also click the player's own
full-screen button with the pointer.

## Bookmarks, folders and the start page

* Menu > **Add bookmark** asks where to save it: *Save (no folder)*, an existing folder, or *New folder...*
  (typed with the keypad). The name defaults to the site name (for example "7movies"); change it later with Rename.
* The start page shows the **first 8 bookmarks in your list**. As soon as any folder exists it shows the **first 8
  folders** instead; select a folder to see its bookmarks. Bookmarks that are in no folder appear in an
  automatic *Unsorted* folder.
* Menu > **Bookmarks...** lists everything. Each row has an **Options** button: Move to top, Move up, Rename,
  Move to folder, Delete (folders: Move to top, Move up, Rename, Ungroup). That is how you choose what is "top 8".

## Popup Guard - how the Chrome extension was ported

| Extension (Chrome, Manifest V3) | Buccaneer |
|---|---|
| `popupGuardEnabled` in storage, default ON; popup.html toggle; ON/OFF badge | Menu > Popup Guard: ON/OFF. Saved, default ON. A toast and a counter in the menu show what was blocked |
| `background.js` closes tabs opened by scripts (`tabs.onCreated` with an opener) and popup windows (`windows.onCreated`) | `onCreateWindow` refuses every new window while the guard is ON, and scripts may not open windows on their own |
| `rules.json` (declarativeNetRequest): block top-level navigations that a page on `7movies.ac` starts toward another site | `PopupGuard.blocksNavigation()` from `shouldOverrideUrlLoading`. Same rule: initiator on a protected domain, destination on a different domain, subdomains count as the same site |
| `content.js`: `window.open` returns null and logs `[Popup Guard] Blocked window.open:` | `assets/popup_guard.js`, same behaviour |
| `content.js`: links with `target=_blank` / `_new` become `_self` | `assets/popup_guard.js` |
| `content.js`: every 1500 ms, fixed + nearly transparent (opacity < 0.3) + z-index > 1000 `div`/`iframe` get `pointer-events:none` | `assets/popup_guard.js`, same test and interval |
| `content.js`: a second 2000 ms interval with `ALLOWED=/(^|\.)7movies\.ac$/` whose body was empty | not ported (it did nothing) |

Differences you will notice:

* As in the extension, a link you click on a protected site that leads to a *different* site is blocked. Go there
  with Menu > Address / search, a bookmark, or the start page; those are never blocked.
* The extension only knew `7movies.ac`. Protect another site: open it, Menu > **Block redirects from <site>**
  (turn it off the same way). To ship more built-in sites add them to `DEFAULT_PROTECTED` in `PopupGuard.kt`.
* The scripts run before the page's own scripts in every frame (when the phone's WebView supports it) and read the
  ON/OFF switch live, so toggling needs no reload.

## Project layout

```
app/src/main/java/com/buccaneer/browser/
  MainActivity.kt   key routing, pointer, WebView setup, video control, menus
  StartPage.kt      top-8 start page      BookmarkStore.kt  bookmarks + folders
  Keypad.kt         on-screen keypad      PopupGuard.kt     native half of the port
  Panels.kt         menu + bookmark list  Osd.kt, Ui.kt, CursorView.kt, TvWebView.kt
app/src/main/assets/
  popup_guard.js    port of content.js    page_helpers.js   video + text-field helpers
app/src/main/res/   icons and TV banner generated from buccaneer_app_icon_final.png
```

## When video does not play or the app crashes

Settings > **Diagnostics** shows the device, memory, the web engine version, the last problem Buccaneer recorded
(a crash, or "the web page crashed" / "Android stopped the web page to free memory") and, while a page is open,
what each video on it is doing:

* `sound is decoded but NO PICTURE` - the device cannot decode that stream's video format (see the codec line).
* `many frames are dropped` - the device is too slow for that stream.
* `A frame from another site is on the page` - the player is inside it and cannot be inspected from the page.

For a crash with no message, capture the system log on your computer while you reproduce it:

```
adb connect <fire-tv-ip>:5555
adb logcat -c
(start the app, play the video until it crashes)
adb logcat -d -v time > buccaneer-log.txt
```

Debug builds also allow remote inspection: open `chrome://inspect` in Chrome on the same computer (with the
Fire TV connected through adb) to see the page's console and network.

## Limits and things that may need tuning on real hardware

* Clicks, scrolling and typing are *injected* (touch events, mouse hover, key events). They are the most likely
  place to need adjustment on a particular Fire OS version.
* Many remotes send volume to the TV by IR/HDMI-CEC and the app never sees it. That is why Up/Down also change the
  volume in full screen. If the device pins its system volume, Buccaneer changes the video's own volume instead.
* If the player sits in a frame from another site, the page cannot be scripted. Buccaneer then sends the player
  Space / arrow keys, which works with most players but not all.
* Typing into a text field inside such a frame works, but the keypad preview cannot mirror what the field holds.
* Many Fire TV models have little memory and a single hardware video decoder. Heavy pages (desktop site, several
  ads) can make Android stop the web page; Settings > Desktop site: OFF gives lighter pages.
* Netflix, Prime Video, Disney+ and similar services block or do not support embedded WebViews; use their apps.
* Fire OS 5 sticks ship an old WebView, so some modern sites misbehave there.
* Downloads and file upload dialogs are not supported.
