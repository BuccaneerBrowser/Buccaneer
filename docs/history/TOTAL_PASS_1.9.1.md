# Buccaneer 1.9.1 — whole-program interaction review

## Scope and method

This is a further design/integration pass followed by an implementation/failure-path pass by the same assistant, not independent human sign-off or two separate reviewers. The 1.9 delivery remains untouched. This source and its APK replace it as the recommended clean-install candidate.

The review examined ownership and ordering, not only syntax or lint: which document, Activity, UI interaction, editor session and player intent may still act when asynchronous work returns; what happens when a menu, keyboard, fullscreen view, navigation or lifecycle transition intervenes; and whether successful persistence can produce an unusable backup. Prior review material is retained under `docs/history/` as history, not fresh test evidence.

## Findings repaired in this pass

| Interaction / design failure | Repair and exercised contract |
| --- | --- |
| A slow smart-keyboard field query could replace a newer address prompt and its typed buffer. | UI-interaction and editor-session ownership gate completion. A newer panel or prompt wins; background completion cannot open the keyboard. |
| A late diagnostics query could cover a newer keyboard. | Snapshot display requires the requesting UI interaction and foreground Activity to remain current. |
| Legacy field notifications could overwrite qualified password/field preview state. | Notifications do not replace pinned editor state. The document/frame/field-qualified transport remains authoritative. |
| A late Flixer click probe could click a newly opened native fullscreen view. | Probe, native target identity and repair retain the original interaction. No late tap is redirected into a replacement view or panel. |
| Fullscreen preparation failure was treated too loosely, and delayed activation could outlive the user's next command. | Only explicit `ok` permits the prepared center tap. Input/panel/seek/lifecycle transitions cancel it. Page-side request generations invalidate retained overlay handlers; cancellation removes the blocking overlay. |
| Bookmark worker completion could discard an address being typed by opening management over it. | Worker result carries only Activity-instance/interaction identifiers, not an Activity reference. Data completes independently; management opens only for its still-current requester. Otherwise the current editor remains and the result is announced. |
| Returning from a document picker could invalidate the import's otherwise-current UI owner. | Pause invalidates pre-pause UI work. Resume resets idle timing without impersonating a fresh user command. The API-34 test covers pause → picker result → resume → import completion. |
| Valid input under 1 MiB could normalize into an over-limit backup, making an accepted import unexportable/unrestorable. | Validate normalized portable JSON before persisting an import; on rejection restore the in-memory previous list. All production export paths validate before opening/truncating a destination. |
| A focused accessible iframe was classified as opaque, hiding its reachable video state. | Resolve exposed child videos and retain attachment through their same-origin frame chain. Cross-origin fallback remains opaque, not bypassed. |
| Nested-player center coordinates did not belong to the top page; transformed timeline coordinates could be wrong. | Map frame borders and axis-aligned scale into top-page coordinates. Seek hit testing maps back into the child viewport. Real Chromium exercises a scaled, bordered frame and a quarter-position seek. |
| CSS canonicalization and repeated fitting of one wrapper could prevent restoration or preserve a temporary margin as the original. | Save the original property once, compare the browser's actual serialized applied value, and restore only still-owned changes. |
| Shaka automatic restrictions could override an explicitly manual mode or fail to recover a reset cap on an unchanged ladder. | Respect exposed ABR-off configuration; compare the effective cap before treating the cached ladder as already applied. |
| A timeline setter that silently ignored a seek could still be reported successful. | Read back the accepted time within the existing tolerance before returning `seeked`. |

Retained force-unmute and manual-quality callback chains also received the interaction/player-intent checks, without redesigning their remote mappings. Popup forwarding now requires foreground ownership. Pending synthetic taps are cancelled when replacement native panels take focus.

## Cross-module design and implementation disposition

- **Lifecycle/navigation/renderer teardown:** document epochs and evaluation deadlines remain the primary page-work boundaries; the interaction serial adds UI ownership, not a second renderer owner. Pause cancels player work and invalidates older UI callbacks; resume does not suppress a valid picker transaction. Temporary popup WebViews retain bounded disposal.
- **Remote input and playback:** existing Play/Pause routing, native fullscreen dispatch, matched synthetic down/up/cancel handling, bounded seek serialization and hold acceleration are retained. The new cancellation guards do not remap the remote or install continuous Flixer interception.
- **Editors/focus/panels:** native-first QWERTY and suppressed Android IME remain. Qualified editor state cannot be replaced by an unqualified discovery message. Menus, bookmark lists, text panels and prompts invalidate stale async open requests.
- **Media helpers/quality/seeking:** passive helper changes are separately recorded; selected same-origin players remain reachable without treating inaccessible frames as scriptable. HLS manual selection behavior is retained; Shaka now follows the same manual-choice principle. Seek acknowledgement is evidence of accepted state, not an optimistic return string.
- **Idle/theme/menu geometry:** the original 20-minute policy and cautious unknown-playback handling remain. FMHY startup stays site-local; native dark settings remain unchanged. Menu fitting is reversible and nested scrolling retains its native fallback.
- **Bookmarks/storage/transfers:** start-page eight-tile paging and bounded 50-row management remain. Worker jobs are application-owned and serial; weak listener delivery is separate from data ownership. No import confirmation dialog was reintroduced. Public Downloads/picker paths remain; normalized round-trip limits are now enforced before publication.
- **Diagnostics/security/configuration:** bounded diagnostics and URL redaction remain; delayed UI is guarded. Manifest, SDK compatibility settings, resources and all six packaged script roles were checked alongside native changes. No permission or target-SDK migration was added merely to silence lint.
- **Protected site behavior:** original Flixer intro/image safeguards, native request/render-reset guards, Popup Guard/Coreflix workarounds and native dark strategy remain protected by fingerprint checks. Passive API fixes have explicit normalization records; this is not a claim that the entire helper is byte-identical to 1.5. Dormant Flixer virtualization was not enabled.

## Evidence and test integrity

`review-evidence/` includes the six failing Android interaction contracts against the previous runtime and six failing actual-function JavaScript contracts, plus source diffs. The transformed-seek Node fixture initially lacked a DOM method; its corrected before/after run is separately identified as `seek-mapping-before-corrected-fixture.log`. A mock omission was not counted as a production bug. The independent real-Chromium transformed-frame test exercises actual DOM methods.

New Android tests include failure/no-target fullscreen preparation, cancellation after new input, paused callback refusal, portable-size rejection, picker-resume ownership, and the API-34 startup/remote/import sequence. `validation/` contains the final results. Compiler/test-harness mistakes encountered while adding tests were corrected before release validation; a failed build was never treated as approval.

## What remains unproven or limited

No physical Fire TV, its WebView/GPU, live Flixer/Coreflix provider, real hardware remote, decoder/bandwidth behavior, physical fullscreen transition or uninstall/reinstall transfer was available. Chromium fullscreen requests are stubbed; API-34 tests use Robolectric and a fixture provider. These are useful interaction tests, not device certification.

Opaque players determine their own seek step; inaccessible timelines cannot be universally repaired. Unknown opaque playback deliberately stays bright and a paused opaque player may therefore not dim. Generic menu fitting cannot repair every closed/cross-origin control. Quality enforcement is limited to exposed supported APIs; unordered HLS ladders do not acquire a fictitious strict ceiling. Player/ad selection remains heuristic. Diagnostic free text can contain sensitive information. Persistent storage failure cannot promise successful disk rollback. Ordinary bookmark edits may exceed portable field/size bounds; export then reports the problem before opening a destination rather than silently truncating the list. Arbitrary rotation/skew/perspective frame transforms are not promised by the axis-aligned coordinate mapping.

This pass repaired reproduced design and implementation conflicts. It does not establish a bug-free program or eliminate the need for device testing.
