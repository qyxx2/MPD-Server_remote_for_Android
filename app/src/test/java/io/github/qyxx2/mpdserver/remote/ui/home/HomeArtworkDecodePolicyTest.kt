package io.github.qyxx2.mpdserver.remote.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeArtworkDecodePolicyTest {
    @Test
    fun keepsHomepageArtworkAboveThumbnailResolution() {
        val sourceWidth = 2400
        val sourceHeight = 1200
        val sampleSize = HomeArtworkDecodePolicy.inSampleSize(sourceWidth, sourceHeight)

        assertEquals(2, sampleSize)
        val decodedWidth = sourceWidth / sampleSize
        val decodedHeight = sourceHeight / sampleSize
        assertTrue("Home cover should retain at least 768 source pixels", maxOf(decodedWidth, decodedHeight) >= 768)
        assertTrue("Home decode should not exceed twice the target dimension", maxOf(decodedWidth, decodedHeight) <= 1536)
    }

    @Test
    fun keepsModerateAndSmallArtworkAtOriginalResolution() {
        assertEquals(1, HomeArtworkDecodePolicy.inSampleSize(1000, 1000))
        assertEquals(1, HomeArtworkDecodePolicy.inSampleSize(640, 640))
        assertEquals(1, HomeArtworkDecodePolicy.inSampleSize(0, 1000))
        assertEquals(1, HomeArtworkDecodePolicy.inSampleSize(1000, -1))
    }
}
