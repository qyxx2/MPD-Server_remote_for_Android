# Lyrics centered eight-line viewport design

## Intent
Remove partial lyric rows at the top and bottom of the player lyrics area. Show at most eight complete visual lines, centered around the active timed lyric where available, without adding a visible container surface.

## Goals
- Timed lyrics display at most eight complete visual lines. To keep a single active line centered on a complete row, use seven visual slots for a one-line active cue; a two-line cue uses eight. Each cue occupies one or two fixed-height visual slots, so cue boundaries and viewport boundaries align.
- The active cue is centered as a group: one-line cues sit on the center row in a seven-row viewport; two-line cues straddle the center in an eight-row viewport. Start/end padding permits the first and last cues to use the same alignment.
- Manual scrolling settles on complete cue boundaries. Existing manual-follow pause and three-second return behavior remains.
- Plain lyrics use complete visual-line rows and settle on whole rows; no timed follow/seek behavior is added.
- Center each visual line horizontally. A long cue's second line remains unwrapped, centered, and clipped symmetrically at the viewport edges instead of ellipsis or rightward marquee.
- Keep the player controls, progress/footer anchors, lyric parsing, seek owner and server contract unchanged.

## Approaches
- Tune padding and item scroll offsets: small patch, but does not provide a reliable whole-line viewport or whole-row manual settling.
- Replace cue list with an eight-cell static window: predictable edges, but breaks natural browsing and existing scroll interaction.
- Keep a virtualized list with fixed visual-line slots, cue-group items, symmetric content padding and snap-to-item scrolling: preserves browsing, virtualization and existing gesture state while making every resting viewport boundary land on a complete cue boundary. Selected because it preserves the current architecture and requires no dependency or server changes.

## Constraints
- Maximum eight visual lines; reduce the count when the available height cannot fit the current typography.
- Cue group height is an exact multiple of the visual-line slot height.
- No border, card background, shadow or decorative clipping frame.
- Preserve current playback identity, cue seek and three-second follow semantics.
