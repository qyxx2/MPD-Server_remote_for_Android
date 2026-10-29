package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSourceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayedRevealStateTest {
    @Test
    fun arbitraryFractionalDeltasStayContinuousAndReleaseDoesNotSnap() {
        var state = PlayedRevealState().beginGesture()
        state = state.dragBy(0.2f)
        assertEquals(0.2f, state.revealedHeightPx, 0.0001f)

        state = state.dragBy(0.8f)
        assertEquals(1.0f, state.revealedHeightPx, 0.0001f)
        state = state.dragBy(0.75f)
        assertEquals(1.75f, state.revealedHeightPx, 0.0001f)

        val released = state.freeze()
        assertFalse(released.gestureActive)
        assertEquals("release preserves the exact fractional extent", 1.75f, released.revealedHeightPx, 0.0001f)
        assertEquals("a frozen state ignores late pointer movement", released, released.dragBy(10f))
    }

    @Test
    fun cancellationFreezesAndTheNextGestureContinuesFromTheSameFractionalHeight() {
        var state = PlayedRevealState().beginGesture().dragBy(37.25f)
        state = state.freeze()
        assertEquals(37.25f, state.revealedHeightPx, 0.0001f)

        state = state.beginGesture().dragBy(12.5f)
        assertEquals("the next gesture starts from the frozen height", 49.75f, state.revealedHeightPx, 0.0001f)

        state = state.freeze().beginGesture().dragBy(-8.25f)
        assertEquals("an upward continuation reduces the existing reveal", 41.5f, state.revealedHeightPx, 0.0001f)
    }

    @Test
    fun reverseMovementClampsAtZeroAndMeasuredDataEndWithoutSnapping() {
        var state = PlayedRevealState().beginGesture().dragBy(20.25f)
        state = state.dragBy(-3.5f)
        assertEquals(16.75f, state.revealedHeightPx, 0.0001f)

        state = state.dragBy(-100f)
        assertEquals(0f, state.revealedHeightPx, 0.0001f)

        state = state.dragBy(80f).clampToMeasuredEnd(63.5f)
        assertEquals("unavailable history cannot create phantom reveal height", 63.5f, state.revealedHeightPx, 0.0001f)
        assertTrue(state.gestureActive)
    }

    @Test
    fun delayedPlayedReadIsRejectedWhenAnyCanonicalScopeDimensionChanges() {
        val request = PlayedReadGeneration(
            epoch = "epoch-a",
            queueRevision = 17L,
            connectionGeneration = 4L,
            explicitStopGeneration = 2L,
            originAddress = "http://server-a",
            requestGeneration = 9L,
        )
        assertTrue(request.stillMatches("epoch-a", 17L, 4L, 2L, "http://server-a", 9L, false))
        assertFalse(request.stillMatches("epoch-b", 17L, 4L, 2L, "http://server-a", 9L, false))
        assertFalse(request.stillMatches("epoch-a", 18L, 4L, 2L, "http://server-a", 9L, false))
        assertFalse(request.stillMatches("epoch-a", 17L, 5L, 2L, "http://server-a", 9L, false))
        assertFalse(request.stillMatches("epoch-a", 17L, 4L, 3L, "http://server-a", 9L, false))
        assertFalse(request.stillMatches("epoch-a", 17L, 4L, 2L, "http://server-b", 9L, false))
        assertFalse(request.stillMatches("epoch-a", 17L, 4L, 2L, "http://server-a", 10L, false))
        assertFalse(request.stillMatches("epoch-a", 17L, 4L, 2L, "http://server-a", 9L, true))
    }

    @Test
    fun orderedHistoryUsesOccurrencePositionAndWindowExpansionUsesOnlyAlreadyLoadedItems() {
        val items = listOf(
            item("current", 0, "same-song"),
            item("recent", -1, "same-song"),
            item("oldest", -5, "same-song"),
            item("middle", -3, "other-song"),
        )

        assertEquals(
            listOf("recent", "middle", "oldest"),
            PlayedRevealWindow.ordered(items).map { it.queueItemId },
        )
        assertEquals(100, PlayedRevealWindow.initialCount(250))
        assertEquals(200, PlayedRevealWindow.nextCount(currentCount = 100, totalCount = 250))
        assertEquals(250, PlayedRevealWindow.nextCount(currentCount = 200, totalCount = 250))
        assertEquals(250, PlayedRevealWindow.nextCount(currentCount = 250, totalCount = 250))
    }

    private fun item(id: String, position: Int, songId: String) = QueueItemDto(
        queueItemId = id,
        songId = songId,
        position = position,
        source = QueueSourceDto.MANUAL,
        playbackContextId = "context-$id",
    )
}
