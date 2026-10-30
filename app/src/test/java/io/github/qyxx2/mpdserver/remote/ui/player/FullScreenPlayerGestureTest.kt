package io.github.qyxx2.mpdserver.remote.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullScreenPlayerGestureTest {
    @Test
    fun miniPlayerExpansionUsesMeasuredAnchorAndHalfWindowCommitThreshold() {
        assertEquals(0.25f, MiniPlayerExpansionPolicy.revealProgress(100f, 400f), 0.001f)
        assertEquals(1f, MiniPlayerExpansionPolicy.revealProgress(500f, 400f), 0.001f)
        assertEquals(0f, MiniPlayerExpansionPolicy.revealProgress(100f, 0f), 0.001f)
        assertEquals(500f, MiniPlayerExpansionPolicy.thresholdPx(1000f), 0.001f)
        assertEquals(250f, MiniPlayerExpansionPolicy.thresholdPx(500f), 0.001f)
        assertTrue(MiniPlayerExpansionPolicy.thresholdPx(0f).isInfinite())
        assertFalse(MiniPlayerExpansionPolicy.shouldExpand(499f, 1000f))
        assertTrue(MiniPlayerExpansionPolicy.shouldExpand(500f, 1000f))
        assertFalse(MiniPlayerExpansionPolicy.shouldExpand(Float.NaN, 1000f))
        assertFalse(MiniPlayerExpansionPolicy.shouldExpand(500f, 0f))
    }

    @Test
    fun miniPlayerExpansionAcceptsFastUpwardVelocityWithoutHalfWindowDistance() {
        assertFalse(MiniPlayerExpansionPolicy.shouldExpand(
            upwardDistancePx = 250f,
            windowHeightPx = 1000f,
            upwardVelocityDpPerSecond = 999f,
        ))
        assertTrue(MiniPlayerExpansionPolicy.shouldExpand(
            upwardDistancePx = 250f,
            windowHeightPx = 1000f,
            upwardVelocityDpPerSecond = 1000f,
        ))
        assertFalse(MiniPlayerExpansionPolicy.shouldExpand(
            upwardDistancePx = 250f,
            windowHeightPx = 1000f,
            upwardVelocityDpPerSecond = -1500f,
        ))
        assertFalse(MiniPlayerExpansionPolicy.shouldExpand(
            upwardDistancePx = 250f,
            windowHeightPx = 1000f,
            upwardVelocityDpPerSecond = Float.NaN,
        ))
        assertTrue(MiniPlayerExpansionPolicy.shouldExpand(
            upwardDistancePx = 250f,
            windowHeightPx = 0f,
            upwardVelocityDpPerSecond = 1200f,
        ))
    }

    @Test
    fun miniPlayerExpansionStillUsesDistanceForSlowPulls() {
        assertFalse(MiniPlayerExpansionPolicy.shouldExpand(
            upwardDistancePx = 300f,
            windowHeightPx = 1000f,
            upwardVelocityDpPerSecond = 400f,
        ))
        assertTrue(MiniPlayerExpansionPolicy.shouldExpand(
            upwardDistancePx = 500f,
            windowHeightPx = 1000f,
            upwardVelocityDpPerSecond = 400f,
        ))
    }

    @Test
    fun playerDismissProgressDrivesBackdropBlurAndTopCornerRadius() {
        assertEquals(0f, FullScreenPlayerDismissPolicy.dragProgress(0f, 1000f), 0.001f)
        assertEquals(0.5f, FullScreenPlayerDismissPolicy.dragProgress(500f, 1000f), 0.001f)
        assertEquals(1f, FullScreenPlayerDismissPolicy.dragProgress(1200f, 1000f), 0.001f)
        assertEquals(20f, FullScreenPlayerDismissPolicy.backdropBlurDp(1f, 0f, 1000f, 20f), 0.001f)
        assertEquals(10f, FullScreenPlayerDismissPolicy.backdropBlurDp(1f, 500f, 1000f, 20f), 0.001f)
        assertEquals(0f, FullScreenPlayerDismissPolicy.backdropBlurDp(1f, 1000f, 1000f, 20f), 0.001f)
        assertEquals(0f, FullScreenPlayerDismissPolicy.topCornerRadiusDp(1f, 0f, 1000f), 0.001f)
        assertEquals(16f, FullScreenPlayerDismissPolicy.topCornerRadiusDp(1f, 60f, 1000f), 0.001f)
        assertEquals(32f, FullScreenPlayerDismissPolicy.topCornerRadiusDp(1f, 120f, 1000f), 0.001f)
        assertEquals(0.08f, FullScreenPlayerDismissPolicy.entryVerticalScale(0f, 80f, 1000f), 0.001f)
        assertEquals(1f, FullScreenPlayerDismissPolicy.entryVerticalScale(1f, 80f, 1000f), 0.001f)
        assertEquals(0.975f, FullScreenPlayerDismissPolicy.entryHorizontalScale(0f), 0.001f)
        assertEquals(1f, FullScreenPlayerDismissPolicy.entryHorizontalScale(1f), 0.001f)
    }

    @Test
    fun playerDismissRequiresIntentionalDownwardDistanceAndSupportsReturn() {
        assertFalse(FullScreenPlayerDismissPolicy.shouldDismiss(100f, 1000f, 120f))
        assertTrue(FullScreenPlayerDismissPolicy.shouldDismiss(220f, 1000f, 120f))
        assertFalse(FullScreenPlayerDismissPolicy.shouldDismiss(-220f, 1000f, 120f))
        assertFalse(FullScreenPlayerDismissPolicy.shouldDismiss(Float.NaN, 1000f, 120f))
    }

    @Test
    fun onlyAQualifiedDownwardVerticalGestureExits() {
        assertTrue(FullScreenExitGesturePolicy.shouldExit(
            startedInTopBand = true,
            deltaX = 12f,
            deltaY = 110f,
            thresholdPx = 96f,
        ))
        assertFalse(FullScreenExitGesturePolicy.shouldExit(
            startedInTopBand = true,
            deltaX = 20f,
            deltaY = 90f,
            thresholdPx = 96f,
        ))
        assertFalse(FullScreenExitGesturePolicy.shouldExit(
            startedInTopBand = true,
            deltaX = 130f,
            deltaY = 100f,
            thresholdPx = 96f,
        ))
        assertFalse(FullScreenExitGesturePolicy.shouldExit(
            startedInTopBand = true,
            deltaX = 0f,
            deltaY = -140f,
            thresholdPx = 96f,
        ))
        assertFalse(FullScreenExitGesturePolicy.shouldExit(
            startedInTopBand = false,
            deltaX = 0f,
            deltaY = 140f,
            thresholdPx = 96f,
        ))
    }
}
