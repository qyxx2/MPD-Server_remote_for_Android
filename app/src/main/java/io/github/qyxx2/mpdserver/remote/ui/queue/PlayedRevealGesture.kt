package io.github.qyxx2.mpdserver.remote.ui.queue

import kotlin.math.min

/** Pointer-origin contract used to keep Queue reveal separate from exit and row-specific gestures. */
internal enum class PlayedRevealPointerRegion {
    QUEUE_BODY,
    QUEUE_EXIT_BAND,
    DRAG_HANDLE,
    ROW_ACTION,
    SEEK_CONTROL,
}

internal object PlayedRevealGesture {
    fun shouldCapture(
        region: PlayedRevealPointerRegion,
        listAtTop: Boolean,
        hasHistory: Boolean,
        deltaY: Float,
        revealedHeightPx: Float,
    ): Boolean {
        if (region != PlayedRevealPointerRegion.QUEUE_BODY || !listAtTop || !hasHistory) return false
        return when {
            deltaY > 0f -> true
            deltaY < 0f -> revealedHeightPx > 0f
            else -> false
        }
    }

    /**
     * Positive finger movement reveals history. Reverse movement consumes only the already
     * revealed extent; any remainder is left for LazyColumn to scroll its regular rows.
     */
    fun consumedDeltaY(
        deltaY: Float,
        listAtTop: Boolean,
        hasHistory: Boolean,
        revealedHeightPx: Float,
    ): Float {
        if (!listAtTop || !hasHistory || deltaY == 0f) return 0f
        return when {
            deltaY > 0f -> deltaY
            revealedHeightPx > 0f -> -min(-deltaY, revealedHeightPx)
            else -> 0f
        }
    }
}
