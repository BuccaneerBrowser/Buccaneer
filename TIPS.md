# Tips — Menu Options

A plain-language guide to what each menu option does and when you'd want it on or off.

## Block redirects from \<site\>

This option only appears while you are on a site — you have to go to the site first, then turn it on or off from that site's menu. Each site remembers its own setting, so turning it on for one site does not affect any other.

- **OFF** — the browser works like a regular browser: links, ads, and redirects are allowed to take you to other sites.
- **ON** — the browser stays on the site. Attempts to redirect you somewhere else (pop-ups, ads, dodgy links) are blocked. Turn this on for a media site you like and have bookmarked, so your browser can't get hijacked away from it.

Tip: bookmarking a site automatically turns this on for that site.

If the site is a listing or portal site (like FMHY) that links out to many other sites, keep this **OFF** — otherwise the links to the listed sites will be blocked and you can't get to them.

Trouble tip: if you click a site or a link and nothing happens (it doesn't go to the page), check the block redirect setting for that site — it may be on and quietly blocking the click.

## Scroll submenu/page with D-pad

Many sites have sections (sidebars, carousels, panels) that scroll separately from the main page. To scroll one of those areas:

1. Place the pointer inside the area you want to scroll.
2. Open the menu and choose **Scroll submenu/page with D-pad**.
3. Use the D-pad to navigate that area.
4. Press **Back** to return to normal navigation.

## Confirm site exit

Stops you from accidentally backing out of a site. When it is on and you press BACK in a way that would leave the site you are on, the browser asks "Exit \<site\>?" instead of just leaving.

- Choose **Yes** to leave the site.
- Choose **No** and you are taken back to the site's home page — you stay on the site, you just go back to the top.

## Desktop site

Some streaming sites only send their full, working layout to desktop computers. With this **ON**, the browser introduces itself as a desktop browser, so those sites display and play correctly. With it **OFF**, the browser introduces itself as a TV/phone browser.

If a site looks broken, or a video plays in the wrong size, try toggling this.

## Autoplay without a click

With this **ON**, a video starts playing as soon as you pick it — no extra click on the video itself. Handy for hands-free TV watching. Turn it **OFF** if you would rather choose exactly when playback starts.

## Prefer low quality streams

With this **ON**, the browser asks for a lower-resolution stream. The picture is softer, but it buffers far less — useful if your internet connection is slow or unreliable. Turn it **OFF** on a fast connection for the sharpest picture.

## One video at a time

With this **ON**, only one video plays at a time. Media pages are full of small preview clips that would otherwise all start playing together and compete for your bandwidth. Keeping this **ON** keeps playback smooth.

## Software rendering

Draws pages using the processor instead of the graphics chip. It works, but it is noticeably slower and is **not recommended** for everyday use. It exists as a workaround for rare display glitches on specific devices or sites.

## Popup Guard

Blocks pop-up windows and intrusive overlay ads. Recommended **ON**.

## IPv6 preference

Some streaming sites sit behind bot protection that blocks the home address on the older network path (IPv4); the newer path (IPv6) is usually not blocked. With this **ON**, the browser connects to those sites over IPv6 when the site has an IPv6 address, and falls back to the normal path automatically when it does not — it can never be worse than having it off.

## Bookmarks

Bookmarks can be exported to — and imported from — the TV's public **Downloads** folder (path: `storage/emulated/0/Downloads`), the same Downloads folder any file manager on the TV shows. How the file is named and found depends on the device's Android version:

- **Older devices (Android 9 and earlier, including Fire TV):** Export writes `Downloads/bookmarks.json`, and Import reads that same file.
- **Newer devices (Android 10 and later):** Export creates a new file in Downloads named `Buccaneer-bookmarks-<date and time>.json`. Import reads the most recently exported backup — or you can pick any backup file in a file manager and choose "Open with Buccaneer".
- Because the file lives in the public Downloads folder instead of inside the app, it survives uninstalling and reinstalling the browser.

## Diagnostics

Opens the diagnostics menu. **Diagnostic logging is OFF by default** — nothing is recorded. If you are having trouble with a site, turn it on: the browser writes a local log of what it is doing (pages, settings, playback events). That log lets a person — or an AI assistant — figure out what went wrong quickly. Share the log when reporting a problem, then turn logging back off.
