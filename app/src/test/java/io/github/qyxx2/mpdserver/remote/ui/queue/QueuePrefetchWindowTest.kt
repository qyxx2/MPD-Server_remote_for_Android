package io.github.qyxx2.mpdserver.remote.ui.queue

import org.junit.Assert.assertEquals
import org.junit.Test

class QueuePrefetchWindowTest {
    @Test
    fun prefetchesFirstHundredWithoutWaitingForScroll() {
        assertEquals(100, QueuePrefetchWindow.endExclusive(lastVisibleIndex = 0, totalItems = 400))
        assertEquals(100, QueuePrefetchWindow.endExclusive(lastVisibleIndex = 79, totalItems = 400))
    }

    @Test
    fun opensNextBatchTwentyItemsBeforeCurrentWindowEnds() {
        assertEquals(200, QueuePrefetchWindow.endExclusive(lastVisibleIndex = 80, totalItems = 400))
        assertEquals(200, QueuePrefetchWindow.endExclusive(lastVisibleIndex = 179, totalItems = 400))
        assertEquals(300, QueuePrefetchWindow.endExclusive(lastVisibleIndex = 180, totalItems = 400))
        assertEquals(400, QueuePrefetchWindow.endExclusive(lastVisibleIndex = 380, totalItems = 400))
    }

    @Test
    fun capsWindowAtAvailableRowsAndHandlesEmptyQueue() {
        assertEquals(25, QueuePrefetchWindow.endExclusive(lastVisibleIndex = 0, totalItems = 25))
        assertEquals(0, QueuePrefetchWindow.endExclusive(lastVisibleIndex = 0, totalItems = 0))
        assertEquals(400, QueuePrefetchWindow.endExclusive(lastVisibleIndex = 999, totalItems = 400))
    }
}
