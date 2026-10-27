package io.github.qyxx2.mpdserver.remote.ui.player

import kotlin.math.abs
import kotlin.math.min

/** Pure sizing policy shared by artwork layout and unit tests. Inputs and output use dp. */
internal fun calculateArtworkSideDp(
    availableWidthDp: Float,
    availableHeightDp: Float,
): Float {
    if (!availableWidthDp.isFinite() || !availableHeightDp.isFinite()) return 0f
    return min(availableWidthDp.coerceAtLeast(0f), availableHeightDp.coerceAtLeast(0f))
}

/** Exit gestures must begin in the dedicated app-content top band and be a long downward vertical drag. */
internal object FullScreenExitGesturePolicy {
    fun shouldExit(
        startedInTopBand: Boolean,
        deltaX: Float,
        deltaY: Float,
        thresholdPx: Float,
    ): Boolean = startedInTopBand &&
        deltaY.isFinite() &&
        deltaX.isFinite() &&
        thresholdPx.isFinite() &&
        thresholdPx > 0f &&
        deltaY >= thresholdPx &&
        deltaY > abs(deltaX)
}
