package io.github.qyxx2.mpdserver.remote.ui.home

import io.github.qyxx2.mpdserver.remote.ui.queue.QueueResourceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeArtworkCacheRetentionPolicyTest {
    private val identity = QueueResourceIdentity(
        epoch = "epoch",
        libraryRevision = 1L,
        connectionGeneration = 1L,
        originAddress = "http://server",
    )

    @Test
    fun evictionSkipsImagesVisibleAcrossAllThreeHomeRails() {
        val lruOrder = listOf("recent-0", "history-0", "autoplay-0", "recent-1", "history-1")
            .map { HomeArtworkCacheKey(identity, it) }
        val visibleByRail = listOf(
            setOf(lruOrder[3]),
            setOf(lruOrder[1]),
            setOf(lruOrder[2]),
        )
        val visibleInAnyRail = HomeArtworkCacheRetentionPolicy.visibleKeyUnion(visibleByRail)

        assertEquals(
            listOf(lruOrder[0], lruOrder[4]),
            HomeArtworkCacheRetentionPolicy.evictionCandidates(lruOrder, visibleInAnyRail),
        )
    }

    @Test
    fun evictionCandidatesRemainOldestFirstAfterVisibleItemsArePinned() {
        val lruOrder = listOf("a", "b", "c", "d", "e")
            .map { HomeArtworkCacheKey(identity, it) }
        val visible = setOf(lruOrder[1], lruOrder[4])

        assertEquals(
            listOf(lruOrder[0], lruOrder[2], lruOrder[3]),
            HomeArtworkCacheRetentionPolicy.evictionCandidates(lruOrder, visible),
        )
    }

    @Test
    fun highResolutionBudgetIsAtLeastDoubleThePrevious64MibLimit() {
        assertTrue(HomeArtworkMemoryCache.MAX_RETAINED_BYTES >= 128L * 1024L * 1024L)
    }
}
