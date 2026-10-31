package io.github.qyxx2.mpdserver.remote.ui.home

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class HomeArtworkMemoryCacheTest {
    @Test
    fun homepageDecoderRetainsLargeCoverResolutionInsteadOfQueueThumbnailSize() = runBlocking {
        val source = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
        source.recycle()

        val decoded = HomeArtworkMemoryCache.decodeHighResolution(output.toByteArray())
        assertTrue("High-resolution Home artwork should decode successfully", decoded != null)
        assertTrue(
            "Home artwork must not be reduced to the Queue cache's 160px thumbnail",
            decoded!!.width > 160,
        )
        assertTrue("Large Home covers should retain at least 768 source pixels", decoded.width >= 768)
    }
}
