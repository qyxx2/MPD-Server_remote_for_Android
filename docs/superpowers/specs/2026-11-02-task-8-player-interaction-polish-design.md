# Task 8 Player Interaction Polish — Design

**Date:** 2026-11-02
**Branch:** feature/task-8-player-interaction-polish
**Base:** main at 4d185f9a9928852f169d3e127e0e7cf25183c731
**Status:** Approved by user on 2026-11-02; implementation has not started.

## 1. Intent

Polish the Android client's queue, full-screen Player, navigation and app-owned popup interactions. The work must make each interaction visually understandable, repeatable and consistent without changing the server-owned playback/queue model or the existing mini-player pull-up and full-screen dismissal contracts.

This is a specifically authorized UI-polish workstream on the Task 8 branch. Task 7's remaining real-device lock-screen acceptance is still open; this work does not mark Task 7 or the overall Android delivery complete.

## 2. Goals

1. Give the full-screen Queue row menu the specified light, compact, left-aligned visual treatment.
2. Make drag initiation predictable from the six-dot handle, use a finger-following translucent row, and make insertion targets depend on actual overlap rather than row-center crossing.
3. Fix the repeat-interaction failure so queue rows can be dragged and their overflow menus opened repeatedly without leaving and re-entering the page.
4. Replace fade-only/none transitions for ordinary routes with fast directional horizontal transitions while preserving special Player/Queue navigation.
5. Reduce timed lyric typography by 2sp for both active and inactive cues, and remove marquee from the second line.
6. Animate the mini-player artwork itself from its measured mini-player bounds to the measured full-screen artwork bounds, synchronized with the existing pull-up progress.
7. Give every app-owned popup/dialog a shared trigger-origin expansion and reverse close animation.

## 3. Non-goals and invariants

- No API, DTO, server behavior, playback semantics, Queue mutation semantics, or service/media-session changes.
- No second playback state, queue model, networking owner, artwork loader or persistent preference.
- Preserve Queue occurrence identity (queue_item_id), fresh-baseline validation, mutation coordination, and authoritative server readback. A drag preview is UI-only.
- Preserve current/played row behavior: only pending occurrences can be moved; current and played rows are not reorder targets.
- Preserve the existing mini-player 50%-of-window drag commit threshold, direct-tap entry, finger-following full-screen surface, bottom-navigation motion and full-screen downward-dismiss behavior.
- Preserve the Player/Queue horizontal pager gesture; ordinary page transitions must not introduce a competing gesture.
- No new runtime dependency unless a platform/API limitation is demonstrated and separately justified.
- System-owned Android dialogs, permission prompts, notification shade and system UI are outside the app-popup animation scope.

## 4. Approaches considered

**A — Patch each page independently.** Adjust each menu/dialog and add local animations. This has a smaller initial footprint, but duplicates anchor measurement, duration, easing and close lifecycle logic; visually similar surfaces can drift apart.

**B — Shared interaction and animation primitives (selected).** Create a shared trigger-bounds/anchor-reveal capability and migrate existing app-owned popup surfaces to it in batches. Queue gestures and the artwork shared-element transition remain domain-specific, but use measured geometry and the same progress/verification discipline. This provides consistent open/close behavior without merging unrelated business state.

**C — Replace every popup with a single full-screen modal implementation.** This would simplify animation ownership, but would unnecessarily replace the existing anchored menu and text-input behavior, increasing focus, IME, accessibility and edge-clamping risk.

**Decision:** Use B. It is the best fit for the existing Compose app: keep anchored menus anchored, keep editable dialogs editable, and share only the geometry/animation mechanism.

## 5. Frozen interaction contracts

### 5.1 Full-screen Queue row menu

- Background is white with Color.White.copy(alpha = 0.94f).
- Shape radius is 18dp; shadow elevation is 7dp; tonal elevation remains 0dp.
- All action labels share one left text edge. Each option has a consistent, global-style line icon before its label; icon size, icon column width and spacing are identical across rows.
- Width is content-driven by the widest action label plus the fixed icon column, gap and horizontal padding. Do not force a screen-width menu or retain a fixed 216–264dp width. A longer song-title/header line must ellipsize and must not widen the action menu.
- Keep the existing action availability, action ordering, click-outside dismissal, Back dismissal and queue-item identity. The title/header can remain, but it is not the width-sizing authority.
- Keep the menu light: no heavy dim layer or oversized card treatment.

### 5.2 Queue drag and insertion

- Drag begins by pressing/moving on the six-dot handle; there is no long-press delay. Touch slop still distinguishes an intentional drag from a stationary touch.
- The dragged occurrence is represented by a translucent row-sized floating layer that follows the pointer. Keep the canonical list rows in their server-confirmed order until the reorder mutation is confirmed by the existing state flow.
- Use measured bounds for the dragged row and visible target rows. For a pending target, calculate the intersection area between the dragged overlay and the target row, divided by the target row's full area. A target becomes eligible only when the ratio is strictly greater than 50%.
- When eligible, show an immediate, unmistakable insertion cue on that target. On release, insert the dragged occurrence directly before that target (before_queue_item_id = target.queue_item_id). The user must be able to see which row will receive the insertion before releasing.
- Resolve overlapping candidates deterministically: choose the eligible target with the greatest coverage ratio; ties resolve to the nearest target center to the dragged row center. Exclude the dragged row, current row and played history.
- Preserve append-at-end behavior when the user releases below the final pending row and no target row qualifies. Releasing without a changed valid target cancels without submitting.
- Preserve the existing baseline guards (epoch, connection generation, queue revision, current occurrence and pending membership). Any incompatible canonical change cancels the draft; no optimistic server-state rewrite.
- A completed drag, cancellation, invalidated baseline or menu interaction must leave the handle and row actions ready for the next gesture.

### 5.3 Repeated queue interaction reliability

Treat the reported issue as a lifecycle/input-routing bug, not as a styling side effect. Before changing implementation, add reproductions for:
- drag the same row twice;
- drag row A, then row B;
- drag, then immediately open that row's three-dot menu;
- open/dismiss a row menu, then drag;
- cancel a drag, then perform a valid drag;
- repeat drag/menu cycles several times without changing pages.

The initial investigation must trace pointer-input node lifetime/keys, gesture cancellation and consumption, state reset paths, lazy-row reuse, menu composition and mutation gating. Do not declare a root cause until one is reproduced and evidenced. Fix the earliest proven cause and retain the regression tests.

### 5.4 Ordinary page navigation

- Replace fade-only/none transitions for ordinary NavHost destinations with a fast horizontal slide.
- Forward navigation: incoming page moves in from the right while the outgoing page moves left. Back/pop: incoming page moves in from the left while the outgoing page moves right.
- No fade is used for the page content. Use one shared duration/easing policy; proposed default is 200ms with a restrained horizontal travel (one quarter of the viewport), respecting the system animation-duration scale.
- Do not apply this transition to the mini-player-to-full-screen gesture, full-screen dismissal, or the Player/Queue HorizontalPager. Those interactions keep their existing geometry and ownership.
- Route/back-stack semantics, state retention and root navigation selection remain unchanged.

### 5.5 Timed lyric typography

- Active lyric cue: 34sp → 32sp.
- Inactive lyric cue: 28sp → 26sp.
- Preserve the current line heights unless measurement proves clipping; if adjusted, keep the existing visual hierarchy and document the exact values in the batch report.
- The second line of an overlong cue is a fixed, single-line ellipsized label. Remove basicMarquee and any automatic scrolling for that line. Keep the cue's tap-to-seek behavior and the existing follow/selection behavior.

### 5.6 Mini-player artwork shared-element transition

- Animate the album-art rectangle, not the entire Player page. The start rectangle is measured from the mini-player artwork image; the end rectangle is measured from the full-screen Player's artwork stage. Do not hard-code screen coordinates or use the device's nominal resolution.
- Interpolate the four rectangle edges (left, top, right, bottom) from source to destination using the same normalized progress as the existing mini-player pull-up gesture. This gives a continuous diagonal/expanding artwork movement from the mini-player's upper-left position toward the larger full-screen artwork position.
- While the user drags, geometry follows the finger through the existing playerEntryProgress; after the existing 50% commit, the current 250ms settle completes the remaining movement. A direct tap uses the same measured source/target geometry and settle.
- As soon as expansion begins, mini-player title and playback controls quickly recede from view; the artwork remains represented by the moving artwork layer. Avoid showing both source and destination images at full opacity during the handoff.
- Keep using the current identity-checked PlayerArtworkMemoryCache bitmap. The overlay must never display an artwork bitmap whose key does not match the current confirmed media identity. If source/target geometry is not yet measured, use a bounded readiness wait/fallback rather than jumping to guessed coordinates.
- At completion, the overlay hands off to the normal full-screen artwork with no visible jump; on early release/cancel, the source artwork and mini-player restore to their resting state.
- Do not scale or rebuild the full-screen page as part of the artwork animation. Progress, controls, lyrics and the backdrop keep their current presentation/ownership.

### 5.7 App-wide popup/dialog open and close motion

- Inventory and cover every app-owned Popup, DropdownMenu and AlertDialog in the current app: song action menu; Queue row menu; Queue clear/save dialogs; output details dialog; library sort menu; Playlist create/rename/delete/add dialogs; and any additional app-owned popup found during implementation.
- The triggering row/button supplies measured bounds. The final popup/dialog uses its normal measured bounds and existing edge clamping. The visual transform origin is the trigger center expressed relative to the final popup bounds, so the content appears to expand outward from the item/button that invoked it. Because Popup and Dialog may use separate Android windows, normalize anchor and surface rectangles into the same screen coordinate space (for example, LayoutCoordinates.positionOnScreen() plus size) before calculating the relative transform origin; never compare unrelated window-local bounds.
- Opening proposal: a quick 180ms reveal, scale from 0.82 to 1.0 around the trigger origin, with alpha 0 to 1. The modal scrim, where present, fades in with the same transition. Closing reverses the same transform and alpha before disposal.
- Use the same duration/easing contract for menus and dialogs. The animation should communicate origin, not make the surface feel slow or bounce.
- Outside tap, Back, selection/action, and explicit dismissal all use the reverse close motion. Keep focus, keyboard/IME, text input, accessibility semantics, click targets, dismissal policy and action callback behavior intact. Do not delay or duplicate server mutations just to wait for an animation.
- If a surface has no visible trigger (for example, an internally raised error), use its owning control/container as the anchor; do not invent a screen-center source.

## 6. Shared implementation boundaries

- Shared popup capability owns only anchor geometry, transform origin, open/close progress, duration/easing and the delayed-disposal lifecycle. The owning screen continues to own the dialog's content, form state, action availability and callbacks.
- Queue reorder state remains in the Queue screen and QueueReorderDraft; overlap resolution should be a pure/testable policy where practical. Pointer input remains attached to the six-dot handle, not the whole row.
- Artwork geometry is a transient presentation layer coordinated by the navigation shell. It consumes the existing artwork bitmap/key and existing expansion progress; it does not own artwork loading or playback state.
- Ordinary route transition definitions belong at the NavHost level and must identify special routes/pager transitions that are intentionally excluded.
- Shared motion values must be centralized rather than allowing each screen to choose arbitrary timings.

## 7. Verification and acceptance

Each batch follows test-first development: add a focused failing test, confirm it fails for the intended behavior, implement the smallest change, then run focused and relevant full regression suites.

**Queue menu:** Compose UI checks verify 94% white background, 18dp radius, 7dp shadow, consistent icons/left text edge, width based on longest action label, header truncation, outside dismissal and Back dismissal.

**Queue gestures:** pure tests cover intersection ratios below/equal/above 50%, multiple overlapping candidates, tie-breaking, append-at-end and invalid targets. Device/UI tests verify handle-only drag, preview follows the pointer, highlight appears only after the threshold, release inserts before the highlighted occurrence, cancel sends no mutation, and repeated drag/menu cycles remain operable. Verify mutation still uses occurrence ID and baseline validation.

**Navigation and lyrics:** UI tests verify forward and pop directions, timing/progress, no fade, and that full-screen Player/Queue gesture behavior is unchanged. Lyrics UI tests verify 32sp/26sp, a long second line remains static and ellipsized, and cue tap-to-seek remains functional.

**Artwork:** instrumentation verifies measured source and destination bounds, progress-linked interpolation during drag, tap settle, early-release recovery, final handoff without a frame jump, and artwork identity protection during song/origin changes.

**Popups:** reusable animation tests verify trigger-origin transform, opening and reverse closing, action selection, outside tap, Back, IME/focus behavior and disposal only after close completes. Screen-level tests verify all inventoried popup surfaces are migrated.

**Final regression:** clean offline Gradle unit tests, Lint, Debug APK assembly, Android-test compilation/assembly, focused Queue/Player/navigation/Playlist instrumentation, git diff --check, and real-device visual review. Device/API coverage and any pre-existing test failures must be reported precisely.

## 8. Risks and controls

- **Pointer-input cancellation/recomposition:** diagnose first in B0; keep gesture detector identity stable across draft updates and verify repeated operations at device level.
- **Overlap ambiguity during fast drags:** use measured rectangles and deterministic highest-coverage targeting; highlight only after the strict threshold is met.
- **Popup focus and IME regressions:** preserve each existing surface's host/focus semantics and test editable dialogs with keyboard shown and hidden.
- **Artwork handoff mismatch:** use exact artwork cache identity and measured bounds; never draw stale bitmap as a transition placeholder.
- **Navigation conflicts:** keep special Player/Queue surfaces and their gesture/pager transitions out of ordinary NavHost slide behavior.
- **Task sequencing:** the work may improve Task 8 UI behavior but does not satisfy Task 7's remaining system-media real-device acceptance or the overall delivery gate.

## 9. Approval boundary

This document freezes the requested visual/interaction behavior and the selected shared-animation approach. Implementation details not affecting those contracts (file-level API names, internal state holder structure and test fixture shape) are left to the batch plan and builder. The user approved this design on 2026-11-02. The contract matrix and implementation plan are the next planning artifacts; product code remains unchanged until the implementation plan is separately approved.
