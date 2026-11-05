# Lyrics Centered Eight-Line Viewport Implementation Plan

> For agentic workers: Execute inline with the Superpowers test-driven-development workflow. This is one cohesive UI task; steps use checkbox syntax.

**Goal:** Display at most eight complete lyric visual lines centered around the active cue, with symmetric horizontal text alignment and no partial lyric rows at rest.

**Architecture:** Keep the existing LyricsStage and Compose LazyColumn. Measure cue wrapping once at the active typography size, render each cue as one or two equal-height line slots, use seven slots for a one-line active cue and up to eight for a two-line active cue / free browsing, and snap manual scrolling to cue item boundaries. Plain text uses visual-line rows with the same slot/snap behavior.

**Tech Stack:** Kotlin, Jetpack Compose Foundation/Material3, existing Android Compose UI instrumentation tests.

**Spec:** docs/superpowers/specs/2026-11-05-lyrics-centered-eight-line-viewport-design.md

## Global Constraints
- Preserve the existing parser, playback clock, cue seek callback, resource identity and three-second follow reducer.
- Do not change the player progress, transport controls, bottom navigation, Runtime, API, Web or server behavior.
- Add no dependency and no visible lyric container surface.
- Use at most eight visual lines; reduce the count when the available height cannot fit the configured line height.

## Review Focus
- First and last cue: both remain fully visible and can be centered with scroll padding.
- Two-line active cue: both visual rows remain together across automatic positioning and snap.
- Very long cue: the second line stays centered and clipped symmetrically without ellipsis.
- Unknown playback position / plain text: no false active cue, seek or automatic return is introduced.
- Short viewport or font scale: visible row count reduces rather than cutting a line.

---

### Task 1: Complete-line lyrics viewport

**Files:**
- Modify app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/player/LyricsStage.kt
- Modify app/src/main/java/io/github/qyxx2/mpdserver/remote/ui/player/lyrics/LyricsTypographyPolicy.kt
- Modify app/src/test/java/io/github/qyxx2/mpdserver/remote/ui/player/lyrics/LyricsTypographyPolicyTest.kt
- Modify app/src/androidTest/java/io/github/qyxx2/mpdserver/remote/ui/player/LyricsStageUiTest.kt

**Interfaces:**
- Timed cues remain one logical clickable item, containing one or two fixed-height visual lines.
- Plain lyrics remain selectable and freely browsable, but snapping settles at visual-line boundaries.
- Existing LyricsFollowReducer, lyricsClockPresentationFlow and onSeek remain the behavior owners.

- [x] Add failing UI assertions for active-cue centering, complete cue bounds at viewport edges, no more than eight visible visual rows, and centered second-line clipping for an overlong cue.
- [x] Run the focused LyricsStageUiTest and confirm the new assertions fail against the current implementation.
- [x] Implement the fixed-slot viewport, cue-group centering, symmetric text alignment/clip, and row-aligned manual snapping. Keep current gesture and three-second follow logic.
- [x] Add/adjust the minimal typography policy assertions for centered, non-ellipsis overflow and viewport row limits.
- [x] Run focused unit tests and the full lyrics/player instrumentation classes.
- [x] Run git diff --check, build assembleDebug, install the generated debug APK on the connected adb device, and perform a launch/lyrics UI smoke check.
- [x] Review the final diff against the spec and report any device-only behavior not actually exercised.

**Expected result:** Idle lyrics display only complete rows, up to eight visual rows, the active one/two-line cue is centered, manual scroll settles on complete cue boundaries, and long second-line text is center-clipped rather than end-ellipsized.
