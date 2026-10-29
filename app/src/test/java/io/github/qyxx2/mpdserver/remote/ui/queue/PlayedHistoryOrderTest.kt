package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSourceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayedHistoryOrderTest {
    @Test
    fun historyDisplayOrderMatchesWebAndFiltersCurrentPendingRows() {
        val items = listOf(
            item("current", 0, "same-song"),
            item("recent", -1, "same-song"),
            item("oldest", -5, "same-song"),
            item("middle", -3, "other-song"),
            item("pending", 1, "same-song"),
        )
        assertEquals(
            listOf("oldest", "middle", "recent"),
            PlayedHistoryOrder.inDisplayOrder(items).map { it.queueItemId },
        )
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

    private fun item(id: String, position: Int, songId: String) = QueueItemDto(
        queueItemId = id,
        songId = songId,
        position = position,
        source = QueueSourceDto.MANUAL,
        playbackContextId = "context-$id",
    )
}
