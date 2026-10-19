# Buccaneer1.9.9 — keyboard, menu and preference integration repair

## Changes
- Page-keyboard commands now form an ordered, identity-fenced queue. The preview uses actual field snapshots rather than treating a local buffer as proof of successful typing.
- DOM edits retain selection, maxlength, Unicode deletion and password masking. Enter reaches website key handlers before any implicit form submit; no raw form.submit() or forced reload is injected.
- Inaccessible fields use Chromium's real InputConnection on its requested handler, with bounded acknowledgement and cancellation. Ambiguous/blocked edits are never replayed as fallback keystrokes.
- Submit-triggered blur closes the keypad; blur during typing cancels queued work without submitting.
- Unlabelled positioned submenus are discovered near an actual trusted trigger during the existing finite observation window. Fitting never enlarges the site's max-height. Nested scroll stays inside menus.
- The initial saved quality preference applies to supported discoverable players even when they start at manual1080 and preference revision0. Subsequent deliberate website choices remain respected.1080-only sources remain eligible.
- Cinejoy's existing private-player adapter seeds the saved preference once. A running private player may require reload/reopen, as the settings feedback explains.

## Preserved
MinSDK22, compileSDK34, targetSDK33, WebKit1.9.0; API25 support. Existing Flixer skip/image guard, Popup Guard/Coreflix workaround, FMHY theme/dark mode, seek handling, bookmarks/import and all unrelated native systems retained against the exact shipped1.9.8 APK.

## Validation
152 Android/Robolectric tests passed, including API25 editing/settings/viewport checks; release lint reported no issues under the existing documented lint configuration. All browser regression runners passed. New focused editor/menu/quality tests also passed. Live desktop Cinejoy search edited the actual field, displayed matching results and retained them after Enter without navigation/reload. Its actual season menu fit960x540, scrolled both ways and changed season without starting playback.
1533 prior native app methods compared by normalized resolved DEX instructions:1520 unchanged,13 intentional editor integration/lifecycle changes reviewed,0 missing. Every packaged JavaScript asset and final signed DEX matched tested/compared inputs.

## Limits
No physical AFTMM/FireOS6/Amazon WebView118 was connected. Desktop Chromium and Robolectric are not decoder/GPU/native-engine certification. Coreflix's live URL redirected during the browser attempt, so no live Coreflix search pass is claimed. No claim of every website/player being supported or a bug-free release. Framework onRenderProcessGone exists from API26; API25 renderer-death behavior depends on Amazon's framework/provider and is not certified here.

## Install
Export and verify your bookmark backup before uninstalling the old app. Install the fresh-install APK and import the backup. Uninstalling removes app-local settings/data. Signing keys are intentionally excluded from this source archive.

APK SHA256:e52ecebc64a05c2cf59b565278590aaae70f6f8af8b0fe5932450d6452cac983
