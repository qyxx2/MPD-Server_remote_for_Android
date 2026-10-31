package io.github.qyxx2.mpdserver.remote.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeArtworkPrefetchPolicyTest {
    @Test
    fun prefetchesOnlyVisibleCardsAndTwoLookAheadItems() {
        assertEquals(
            listOf(4, 5, 6, 7, 8),
            HomeArtworkPrefetchPolicy.indices(
                visibleItemIndexes = listOf(4, 5, 6),
                itemCount = 20,
            ),
        )
    }

    @Test
    fun clampsPrefetchAtTheEndOfTheRail() {
        assertEquals(
            listOf(7, 8, 9),
            HomeArtworkPrefetchPolicy.indices(
                visibleItemIndexes = listOf(7, 8),
                itemCount = 10,
            ),
        )
    }

    @Test
    fun ignoresEmptyAndOutOfRangeVisibility() {
        assertEquals(emptyList<Int>(), HomeArtworkPrefetchPolicy.indices(emptyList(), 12))
        assertEquals(emptyList<Int>(), HomeArtworkPrefetchPolicy.indices(listOf(12, 14), 12))
        assertEquals(emptyList<Int>(), HomeArtworkPrefetchPolicy.indices(listOf(0), 0))
    }
}
