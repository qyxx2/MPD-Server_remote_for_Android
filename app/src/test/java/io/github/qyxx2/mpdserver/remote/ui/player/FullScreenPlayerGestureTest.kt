package io.github.qyxx2.mpdserver.remote.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullScreenPlayerGestureTest {
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
