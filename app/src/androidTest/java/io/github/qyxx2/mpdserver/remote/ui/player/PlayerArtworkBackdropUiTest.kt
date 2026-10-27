package io.github.qyxx2.mpdserver.remote.ui.player

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerArtworkBackdropUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun softwareGaussianBlurSoftensTheArtAndTheBackdropRendersWithThemeVeil() {
        val sourceBitmap = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(128 * 64) { index ->
            val x = index % 128
            if (x < 64) AndroidColor.BLACK else AndroidColor.WHITE
        }
        sourceBitmap.setPixels(pixels, 0, 128, 0, 0, 128, 64)

        val blurred = createBlurredArtworkBitmap(sourceBitmap.asImageBitmap())
        assertNotNull("a valid artwork bitmap must produce a backdrop", blurred)
        val blurredBitmap = blurred!!.asAndroidBitmap()
        assertTrue("large art is reduced to a compact backdrop bitmap", blurredBitmap.width <= 360)
        val softenedEdge = AndroidColor.red(blurredBitmap.getPixel(63, 32))
        assertTrue("Gaussian blur must soften the black/white edge", softenedEdge in 20..235)

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                PlayerArtworkBackdrop(blurredArtwork = blurred)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("player-artwork-backdrop").assertIsDisplayed()
        composeRule.onNodeWithTag("player-artwork-blurred-backdrop").assertIsDisplayed()
        composeRule.onNodeWithTag("player-artwork-backdrop-theme-mask").assertIsDisplayed()
    }
}
