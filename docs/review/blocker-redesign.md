# Blocker Redesign: Whole-Program Redirect Blocking

> **Status:** Design (not yet implemented). This document captures the full analysis and the
> redesign plan for the popup/redirect blocker. Any session reading this can implement the
> redesign without re-deriving the analysis.
>
> **Why this exists:** The blocker was being patched piecemeal (hardcoded site list + a native
> hook that only catches one redirect mechanism). The user directed a whole-program redesign
> that "works with everything on all sites." This document is the durable record of the
> analysis + design so the understanding doesn't live only in one session's head.

---

## 1. The problem (one paragraph)

Streaming sites' ad scripts redirect the **main frame** to off-site ad domains. The current
blocker only catches *client-side* main-frame navigations (via `shouldOverrideUrlLoading`).
**Server-side HTTP redirects (301/302) bypass `shouldOverrideUrlLoading` entirely** — the
WebView follows them automatically without giving the app a chance to veto. So ad redirect
chains get through. The native block has **never fired** (zero `redirect_blocked` events in
the entire diagnostic log).

---

## 2. Current architecture (three layers)

| Layer | Where | What it catches | Status |
|---|---|---|---|
| **popup_guard.js** (JS) | `assets/popup_guard.js` | `window.open()` popups, `_blank` links, ad overlays | **Working** (blocked 4 popup windows) |
| **`blocksNavigation`** (native) | `PopupGuard.kt` L96, called from `interceptNavigation` L866 → `shouldOverrideUrlLoading` L891 | Client-side main-frame redirects, **only for protected sites** | **Never fired** (0 events) |
| **`shouldInterceptRequest`** | `MainActivity.kt` L1032 | Every request (incl. redirect targets) | Currently only TMDB firewall + media logging |

### Key code locations
- `PopupGuard.kt` — `blocksNavigation(initiatorUrl, target)` L96: returns true (block) if the
  target host differs from the initiator host **and** the initiator host is in the protected set
  (`DEFAULT_PROTECTED` ∪ user-toggled `KEY_PROTECTED`).
- `MainActivity.kt` L866 `interceptNavigation` — calls `guard.blocksNavigation(view?.url, uri)`
  inside `shouldOverrideUrlLoading` (L891, L897).
- `MainActivity.kt` L1514 `openUrl` → L1516 `navigateUrl(url, userInitiated)` → L1521 records
  `user_navigation` (when `userInitiated=true`) or `automatic_navigation`. **This is the anchor.**
- `MainActivity.kt` L902 `onPageStarted` — records `page_started` for every main-frame load.
- `MainActivity.kt` L262 `interactionSerial` — incremented on every user interaction.
- `MainActivity.kt` L394 `lastTapAt` — timestamp of last tap.

### The protected-site list (the piecemeal part)
`PopupGuard.kt` L129 `DEFAULT_PROTECTED` = `7movies.ac, flixer.gd, cinejoy.pk, novahd.cc,
movy.sx, toflix.co, pantyflix.com`. Plus `protect(host)` (L75) auto-adds bookmarked sites.
This list is the "piecemeal" part the user wants replaced with a general mechanism.

---

## 3. The structural gap (with evidence)

`shouldOverrideUrlLoading` is **NOT called for server-side HTTP redirects (301/302)**. The
WebView follows them automatically. The ad redirect chains are server-side redirect chains.

**Evidence (diagnostic log, `/tmp/diag_latest.jsonl`):**
- `redirect_blocked` count = **0** (the native block has NEVER fired — not once).
- The `page_started` events show the redirect chains (each hop is a separate main-frame load):

```
15:06:12 user_navigation  flixer.gd
15:06:26 page_started     ravishwalnut.qpon      <- ad redirect (14s later)
15:06:27 page_started     strategiboost.com      <- ad landing

15:22:31 user_navigation  moviebite.org
15:22:45 page_started     yi.charkshiite.qpon    <- ad redirect (14s later)
15:22:46 page_started     dry-snowflake-2590.sayednaeamm.workers.dev
15:22:47 page_started     pickvivo.com
15:22:49 page_started     www.amazon.com         <- ad landing

15:35:16 page_started     flixer.gd
15:35:25 page_started     www.amazon.com         <- ad redirect (9s later)
```

**The signature of an ad redirect chain:** a series of `page_started` events to *different
domains*, within seconds of each other, with **no `user_navigation` in between** and **no user
interaction**. The user navigated once (to the streaming site), and then the main frame bounced
through ad domains automatically.

---

## 4. The redesign: anchor-based redirect detection

### Core heuristic

Replace the hardcoded site list with a **general heuristic** that works on all sites:

1. **Track the anchor URL** — set from `user_navigation` events (the URL the user explicitly
   navigated to, via bookmark/search/address bar/start page).
2. **Track the anchor's interaction serial** — the `interactionSerial` at the moment of the
   anchor navigation.
3. **On each `page_started`**, check three conditions:
   - **(a)** Is the target a **different domain** than the anchor? (Subdomains and path changes
     count as the same domain — never block those.)
   - **(b)** Has the user **NOT interacted** since the anchor? (`interactionSerial` unchanged
     since the anchor — no taps, no key presses.)
   - **(c)** Is it within a **short window** (default 30 s) of the anchor navigation?
4. **If all three are true** → it's an ad redirect → **navigate back to the anchor URL** and
   record a `redirect_blocked` diagnostic event.

### Why this works on all sites (the whole-program mindset)

- It's based on the **actual URL change** (`page_started`), **not the navigation mechanism**.
  So it catches client-side redirects, server-side redirects (301/302), and multi-hop ad
  redirect chains — all of them.
- It does **not** require a hardcoded list of protected sites. Every site is protected by the
  same rule.
- New sites are **automatically protected** — no need to add them to any list.
- The "no user interaction" check is what makes it safe: a real user navigation (tapping a
  link, typing a URL) always changes `interactionSerial`, so it is never blocked.

### False-positive analysis (why it's safe)

| Scenario | Blocked? | Why |
|---|---|---|
| Ad redirect chain (flixer.gd → amazon.com) | **Yes** | Different domain, no interaction, within window |
| User taps a link to another site | No | Tap changes `interactionSerial` → condition (b) fails |
| User types a new URL | No | New `user_navigation` resets the anchor |
| Same-site path change (flixer.gd/movie/x → flixer.gd/movie/y) | No | Same domain → condition (a) fails |
| Subdomain (flixer.gd → cdn.flixer.gd) | No | Subdomain counts as same domain → condition (a) fails |
| Player in an iframe (flixer.gd → vidbolt.xyz iframe) | No | Iframe loads don't fire main-frame `page_started` |
| Main frame → player domain (rare) | Maybe | Mitigated by safe-domain list + short window; user is usually interacting with the player |

### Safe-domain list (escape hatch)

A configurable list of domains that are **allowed** even if they differ from the anchor (for
sites where the main frame legitimately navigates to a different domain, e.g. a player domain).
Default: empty. Stored in SharedPreferences. This is the "individual fixes" the user allowed —
used only when a specific site needs it.

---

## 5. Implementation plan

### Step 1 — Add anchor tracking to `MainActivity.kt`
Add three fields (near `lastPageUrl` / `pageStartedAt`):
```kotlin
private var anchorUrl: String? = null        // the user-navigated URL
private var anchorAt: Long = 0L              // elapsedRealtime of the anchor navigation
private var anchorInteraction: Long = 0L     // interactionSerial at the anchor
```
Set them in `navigateUrl(url, userInitiated=true)` (L1516), right where `user_navigation` is
recorded (L1521):
```kotlin
if (userInitiated) {
    anchorUrl = url
    anchorAt = SystemClock.elapsedRealtime()
    anchorInteraction = interactionSerial
}
```

### Step 2 — Add redirect detection in `onPageStarted` (L902)
After the existing `page_started` recording (L936), add:
```kotlin
maybeBlockAdRedirect(url)
```
New method:
```kotlin
private fun maybeBlockAdRedirect(url: String?) {
    val anchor = anchorUrl ?: return
    if (url == null || !url.startsWith("http")) return
    val now = SystemClock.elapsedRealtime()
    if (now - anchorAt > REDIRECT_WINDOW_MS) return          // (c) window
    if (interactionSerial != anchorInteraction) return        // (b) user interacted
    val aHost = cleanHost(Uri.parse(anchor).host ?: return)
    val tHost = cleanHost(Uri.parse(url).host ?: return)
    if (aHost.isEmpty() || tHost.isEmpty()) return
    if (tHost == aHost || tHost.endsWith(".$aHost") || aHost.endsWith(".$tHost")) return  // (a) same domain
    if (safeDomains().any { d -> tHost == d || tHost.endsWith(".$d") }) return             // safe domain
    // AD REDIRECT DETECTED -> go back to the anchor
    DiagnosticLog.record(this, "redirect_blocked", JSONObject().apply {
        put("anchor", DiagnosticLog.safeUrl(anchor))
        put("target", DiagnosticLog.safeUrl(url))
        put("elapsedMs", now - anchorAt)
    })
    noteBlocked("a redirect")
    handoffNavigation { webView.loadUrl(anchor) }
}
```
(`cleanHost` / `hostMatches` logic already exists in `PopupGuard.kt` companion — reuse or
duplicate the small helper.)

### Step 3 — Keep the existing layers
- **Keep `blocksNavigation`** (client-side redirect block for protected sites) as a secondary
  layer. It's harmless and catches the client-side case earlier.
- **Keep `popup_guard.js`** (popup windows).
- **Keep `protect(host)`** (bookmark auto-protect) — still useful for the `blocksNavigation`
  layer and the safe-domain list.

### Step 4 — Make it configurable (SharedPreferences)
- `redirectGuardEnabled` (default ON) — master switch.
- `redirectWindowMs` (default 30000) — the window.
- `safeDomains` (default empty) — the escape-hatch list.
- Add a Settings menu toggle (next to the existing Popup Guard toggle).

### Step 5 — Diagnostics
- `redirect_blocked` event now fires for **both** the native `blocksNavigation` block and the
  new anchor-based block (add a `mechanism` field: `"native"` vs `"anchor"`).
- This lets us verify the new mechanism is actually firing (unlike before, where it was 0).

---

## 6. What this replaces (and what it keeps)

| Old (piecemeal) | New (whole-program) |
|---|---|
| Hardcoded `DEFAULT_PROTECTED` list | General anchor heuristic (works on all sites) |
| `blocksNavigation` only (client-side) | Anchor heuristic (client-side + server-side + chains) |
| New site = add to list | New site = automatically protected |
| `protect(host)` on bookmark | Still kept (for `blocksNavigation` + safe domains) |

The hardcoded list becomes a **fallback**, not the primary mechanism. The primary mechanism is
the anchor heuristic, which is site-agnostic.

---

## 7. Tunable parameters (defaults)

| Parameter | Default | Notes |
|---|---|---|
| `redirectGuardEnabled` | ON | Master switch |
| `redirectWindowMs` | 30000 | Long enough to catch ad redirects (~15 s), short enough to avoid false positives |
| `safeDomains` | empty | Escape hatch for sites with legitimate cross-domain main-frame nav |

---

## 8. Verification plan (after implementation)

1. Build + install (measure the build time).
2. Navigate to flixer.gd (or any streaming site) with diagnostics ON.
3. Wait for the ad redirect to fire.
4. Pull `diagnostic_session.jsonl` and confirm:
   - A `redirect_blocked` event with `mechanism: "anchor"` fires.
   - The main frame returns to the anchor URL (flixer.gd).
   - The user is not stranded on the ad domain.
5. Confirm no false positives: tap a link / type a URL and confirm those are NOT blocked.

---

## 9. Risks and mitigations

| Risk | Mitigation |
|---|---|
| False positive (legit cross-domain nav blocked) | Safe-domain list + "no interaction" check + short window |
| Anchor never set (user lands via crash-recovery) | `anchorUrl` stays null → detection is a no-op (safe) |
| Window too short (misses slow redirects) | Tunable `redirectWindowMs`; start at 30 s, adjust from diagnostics |
| Window too long (blocks a legit quick nav) | "No interaction" check is the primary guard; window is secondary |
| PowerVR crash on rapid re-navigation | `handoffNavigation` + `pageEpoch` guards already handle stale callbacks |

---

## 10. Relationship to the user's directives

- **"Whole program mindset that works with everything on all sites"** → the anchor heuristic is
  site-agnostic; it works on every site without a hardcoded list.
- **"Built for scalability"** → new sites are automatically protected; the mechanism is reusable.
- **"Some sites will need individual fixes"** → the safe-domain list is the per-site escape hatch.
- **"Bookmark auto-protect"** → still kept (`protect(host)`), now feeding the `blocksNavigation`
  layer and the safe-domain list.
