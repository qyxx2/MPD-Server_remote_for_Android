package io.github.qyxx2.mpdserver.remote.ui.shell

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.ui.player.ConfirmedPlaybackStatus
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiniPlayerUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun readyArtworkBitmapIsRenderedInTheExistingCoverSurface() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                MiniPlayer(
                    presentation = presentation(ConfirmedPlaybackStatus.PLAYING),
                    artwork = bitmap,
                    playbackControlEnabled = true,
                    onOpen = {},
                    onTogglePlayback = {},
                )
            }
        }

        composeRule.onNodeWithTag("mini-player-artwork").assertIsDisplayed()
        composeRule.onNodeWithTag("mini-player-artwork-image").assertIsDisplayed()
    }

    @Test
    fun pausedMiniPlayerDispatchesResumeWithoutOpeningThePlayer() {
        var toggleCount = 0
        var openCount = 0
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                MiniPlayer(
                    presentation = presentation(ConfirmedPlaybackStatus.PAUSED),
                    playbackControlEnabled = true,
                    onOpen = { openCount += 1 },
                    onTogglePlayback = { toggleCount += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("继续播放").assertIsEnabled().performClick()

        assertEquals(1, toggleCount)
        assertEquals(0, openCount)
    }

    @Test
    fun playingMiniPlayerDispatchesPauseWithoutOpeningThePlayer() {
        var toggleCount = 0
        var openCount = 0
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                MiniPlayer(
                    presentation = presentation(ConfirmedPlaybackStatus.PLAYING),
                    playbackControlEnabled = true,
                    onOpen = { openCount += 1 },
                    onTogglePlayback = { toggleCount += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("暂停播放").assertIsEnabled().performClick()

        assertEquals(1, toggleCount)
        assertEquals(0, openCount)
    }

    @Test
    fun unknownPlaybackStateKeepsTheControlDisabled() {
        var toggleCount = 0
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                MiniPlayer(
                    presentation = presentation(ConfirmedPlaybackStatus.UNKNOWN),
                    playbackControlEnabled = false,
                    onOpen = {},
                    onTogglePlayback = { toggleCount += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("播放状态未确认").assertIsNotEnabled()
        assertEquals(0, toggleCount)
    }

    private fun presentation(status: ConfirmedPlaybackStatus) = MiniPlayerPresentation(
        title = "真实曲目",
        hasTrack = true,
        playbackStatus = status,
        statusLabel = if (status == ConfirmedPlaybackStatus.UNKNOWN) "状态未确认" else null,
    )
}
