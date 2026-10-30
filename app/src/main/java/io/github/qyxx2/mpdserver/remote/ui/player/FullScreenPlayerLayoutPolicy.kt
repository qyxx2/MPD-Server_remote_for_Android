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

/** Pull-up geometry for extracting the mini-player into the full-screen Player. */
internal object MiniPlayerExpansionPolicy {
    fun revealProgress(upwardDistancePx: Float, miniPlayerTopPx: Float): Float {
        if (!upwardDistancePx.isFinite() || !miniPlayerTopPx.isFinite() || miniPlayerTopPx <= 0f) return 0f
        return (upwardDistancePx / miniPlayerTopPx).coerceIn(0f, 1f)
    }

    fun thresholdPx(windowHeightPx: Float): Float {
        val height = windowHeightPx.takeIf { it.isFinite() && it > 0f }
            ?: return Float.POSITIVE_INFINITY
        return height * 0.5f
    }

    /** A quick upward flick may enter the Player before the distance threshold is reached. */
    const val UPWARD_FLING_THRESHOLD_DP_PER_SECOND = 1000f

    fun shouldExpand(
        upwardDistancePx: Float,
        windowHeightPx: Float,
        upwardVelocityDpPerSecond: Float = 0f,
    ): Boolean {
        val distanceThresholdReached = upwardDistancePx.isFinite() &&
            windowHeightPx.isFinite() &&
            windowHeightPx > 0f &&
            upwardDistancePx >= thresholdPx(windowHeightPx)
        val velocityThresholdReached = upwardVelocityDpPerSecond.isFinite() &&
            upwardVelocityDpPerSecond >= UPWARD_FLING_THRESHOLD_DP_PER_SECOND
        return distanceThresholdReached || velocityThresholdReached
    }
}

/** Pure transform policy for the player-only interactive dismissal and mini-player expansion. */
internal object FullScreenPlayerDismissPolicy {
    fun dragProgress(offsetPx: Float, screenHeightPx: Float): Float {
        if (!offsetPx.isFinite() || !screenHeightPx.isFinite() || screenHeightPx <= 0f) return 0f
        return (offsetPx / screenHeightPx).coerceIn(0f, 1f)
    }

    fun shouldDismiss(offsetPx: Float, screenHeightPx: Float, minimumDistancePx: Float): Boolean {
        if (!offsetPx.isFinite() || !screenHeightPx.isFinite() || !minimumDistancePx.isFinite()) return false
        if (screenHeightPx <= 0f || minimumDistancePx <= 0f) return false
        return offsetPx >= maxOf(minimumDistancePx, screenHeightPx * 0.18f)
    }

    fun backdropBlurDp(
        entryProgress: Float,
        offsetPx: Float,
        screenHeightPx: Float,
        maximumBlurDp: Float,
    ): Float {
        val entry = entryProgress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val blur = maximumBlurDp.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
        return blur * entry * (1f - dragProgress(offsetPx, screenHeightPx))
    }

    fun topCornerRadiusDp(entryProgress: Float, offsetPx: Float, screenHeightPx: Float): Float {
        val entry = entryProgress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val progress = dragProgress(offsetPx, screenHeightPx)
        val entryRadius = 14f * (1f - entry)
        val dragRadius = 32f * (progress / 0.12f).coerceIn(0f, 1f)
        return maxOf(entryRadius, dragRadius)
    }

    fun entryVerticalScale(entryProgress: Float, collapsedHeightPx: Float, screenHeightPx: Float): Float {
        val progress = entryProgress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
        if (!collapsedHeightPx.isFinite() || !screenHeightPx.isFinite() || screenHeightPx <= 0f) return 1f
        val collapsed = (collapsedHeightPx / screenHeightPx).coerceIn(0.01f, 1f)
        return collapsed + (1f - collapsed) * progress
    }

    fun entryHorizontalScale(entryProgress: Float): Float {
        val progress = entryProgress.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
        return 0.975f + 0.025f * progress
    }
}
