package io.github.qyxx2.mpdserver.remote.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullScreenPlayerLayoutTest {
    @Test
    fun artworkNeverExceedsEitherAvailableDimension() {
        assertEquals(320f, calculateArtworkSideDp(420f, 320f), 0.001f)
        assertEquals(280f, calculateArtworkSideDp(280f, 640f), 0.001f)
        assertEquals(0f, calculateArtworkSideDp(280f, 0f), 0.001f)
    }

    @Test
    fun artworkUsesAvailableBoundsForWideAndShortWindows() {
        val longPortrait = calculateArtworkSideDp(392f, 470f)
        val compactHeight = calculateArtworkSideDp(392f, 220f)
        assertTrue(longPortrait <= 392f)
        assertTrue(longPortrait <= 470f)
        assertTrue(compactHeight <= 220f)
        assertTrue(compactHeight < longPortrait)
    }
}
