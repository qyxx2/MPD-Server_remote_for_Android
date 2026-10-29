package io.github.qyxx2.mpdserver.remote.ui.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayedRevealGestureTest {
    @Test
    fun onlyDownwardBodyGestureAtListTopCanStartReveal() {
        assertTrue(
            PlayedRevealGesture.shouldCapture(
                region = PlayedRevealPointerRegion.QUEUE_BODY,
                listAtTop = true,
                hasHistory = true,
                deltaY = 12f,
                revealedHeightPx = 0f,
            ),
        )
        assertFalse(capture(region = PlayedRevealPointerRegion.QUEUE_BODY, atTop = false, deltaY = 12f))
        assertFalse(capture(region = PlayedRevealPointerRegion.QUEUE_BODY, atTop = true, deltaY = -12f))
        assertFalse(capture(region = PlayedRevealPointerRegion.QUEUE_BODY, atTop = true, deltaY = 12f, hasHistory = false))
        assertFalse(capture(region = PlayedRevealPointerRegion.QUEUE_BODY, atTop = true, deltaY = 0f))
    }

    @Test
    fun exitBandRowActionsAndDragHandleNeverBecomePlayedRevealSources() {
        listOf(
            PlayedRevealPointerRegion.QUEUE_EXIT_BAND,
            PlayedRevealPointerRegion.DRAG_HANDLE,
            PlayedRevealPointerRegion.ROW_ACTION,
            PlayedRevealPointerRegion.SEEK_CONTROL,
        ).forEach { region ->
            assertFalse(
                "$region must remain separate from Played reveal",
                capture(region = region, atTop = true, deltaY = 30f),
            )
        }
    }

    @Test
    fun reverseDragConsumesOnlyTheAlreadyRevealedExtent() {
        assertEquals(
            18f,
            PlayedRevealGesture.consumedDeltaY(
                deltaY = 18f,
                listAtTop = true,
                hasHistory = true,
                revealedHeightPx = 0f,
            ),
            0.0001f,
        )
        assertEquals(
            -7.5f,
            PlayedRevealGesture.consumedDeltaY(
                deltaY = -25f,
                listAtTop = true,
                hasHistory = true,
                revealedHeightPx = 7.5f,
            ),
            0.0001f,
        )
        assertEquals(
            0f,
            PlayedRevealGesture.consumedDeltaY(
                deltaY = -25f,
                listAtTop = true,
                hasHistory = true,
                revealedHeightPx = 0f,
            ),
            0.0001f,
        )
    }

    private fun capture(
        region: PlayedRevealPointerRegion,
        atTop: Boolean,
        deltaY: Float,
        hasHistory: Boolean = true,
    ) = PlayedRevealGesture.shouldCapture(
        region = region,
        listAtTop = atTop,
        hasHistory = hasHistory,
        deltaY = deltaY,
        revealedHeightPx = 0f,
    )
}
