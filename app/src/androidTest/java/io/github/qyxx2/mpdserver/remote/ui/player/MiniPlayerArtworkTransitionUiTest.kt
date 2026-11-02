package io.github.qyxx2.mpdserver.remote.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.ui.shell.MiniPlayer
import io.github.qyxx2.mpdserver.remote.ui.shell.MiniPlayerPresentation
import io.github.qyxx2.mpdserver.remote.ui.player.ConfirmedPlaybackStatus
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MiniPlayerArtworkTransitionUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val key = PlayerArtworkCacheKey(
        songId = "song-a",
        fileUri = "music/song-a.flac",
        epoch = "epoch-a",
        libraryRevision = 12L,
        connectionGeneration = 4L,
    )
    private val otherKey = key.copy(songId = "song-b", fileUri = "music/song-b.flac")

    @Test
    fun miniArtworkReportsItsOwnBounds() {
        var rootOrigin = Offset.Zero
        var reportedOnScreen: Rect? = null
        val bitmap = artworkBitmap()

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                Box(
                    Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionOnScreen() },
                ) {
                    MiniPlayer(
                        presentation = miniPresentation(),
                        onOpen = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                        artwork = bitmap,
                        onArtworkBoundsChanged = { reportedOnScreen = it },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val reported = reportedOnScreen
        assertNotNull("Mini-player must report the measured artwork image bounds", reported)
        val expected = composeRule.onNodeWithTag("mini-player-artwork")
            .fetchSemanticsNode().boundsInRoot
        assertRectEquals(
            expected,
            reported!!.translate(Offset(-rootOrigin.x, -rootOrigin.y)),
        )
    }

    @Test
    fun fullScreenArtworkReportsItsOwnBounds() {
        var rootOrigin = Offset.Zero
        var reportedOnScreen: Rect? = null
        val bitmap = artworkBitmap()

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                Box(
                    Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionOnScreen() },
                ) {
                    ArtworkStage(
                        bitmap = bitmap,
                        placeholderLabel = "无封面",
                        modifier = Modifier.size(260.dp).align(Alignment.Center),
                        onArtworkBoundsChanged = { reportedOnScreen = it },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val reported = reportedOnScreen
        assertNotNull("Full-screen artwork must report the measured stage bounds", reported)
        val expected = composeRule.onNodeWithTag("player-artwork-stage")
            .fetchSemanticsNode().boundsInRoot
        assertRectEquals(
            expected,
            reported!!.translate(Offset(-rootOrigin.x, -rootOrigin.y)),
        )
    }

    @Test
    fun dragProgressMovesOverlayBetweenMeasuredBounds() {
        var progress by mutableFloatStateOf(0.5f)
        val source = Rect(20f, 30f, 68f, 78f)
        val target = Rect(180f, 260f, 420f, 500f)
        val bitmap = artworkBitmap()

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                Box(Modifier.fillMaxSize().testTag("artwork-transition-test-root")) {
                    ArtworkTransitionOverlay(
                        bitmap = bitmap,
                        bitmapKey = key,
                        currentKey = key,
                        sourceBounds = source,
                        targetBounds = target,
                        progress = progress,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.waitForIdle()
        assertRectEquals(
            Rect(100f, 145f, 244f, 289f),
            composeRule.onNodeWithTag("mini-player-artwork-transition-overlay")
                .fetchSemanticsNode().boundsInRoot,
        )

        composeRule.runOnIdle { progress = 0.75f }
        composeRule.waitForIdle()
        assertRectEquals(
            Rect(140f, 202.5f, 332f, 394.5f),
            composeRule.onNodeWithTag("mini-player-artwork-transition-overlay")
                .fetchSemanticsNode().boundsInRoot,
        )
    }

    @Test
    fun directTapUsesTheSameArtworkGeometry() {
        var progress by mutableFloatStateOf(0f)
        val source = Rect(12f, 18f, 60f, 66f)
        val target = Rect(160f, 220f, 400f, 460f)
        val bitmap = artworkBitmap()

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                Box(Modifier.fillMaxSize()) {
                    MiniPlayer(
                        presentation = miniPresentation(),
                        onOpen = { progress = 0.5f },
                        artwork = bitmap,
                    )
                    ArtworkTransitionOverlay(
                        bitmap = bitmap,
                        bitmapKey = key,
                        currentKey = key,
                        sourceBounds = source,
                        targetBounds = target,
                        progress = progress,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithTag("mini-player-body").performClick()
        composeRule.waitForIdle()
        assertRectEquals(
            Rect(86f, 119f, 230f, 263f),
            composeRule.onNodeWithTag("mini-player-artwork-transition-overlay")
                .fetchSemanticsNode().boundsInRoot,
        )
    }

    @Test
    fun earlyReleaseRestoresMiniArtwork() {
        var progress by mutableFloatStateOf(0f)
        var transitioning by mutableStateOf(false)
        val bitmap = artworkBitmap()
        val source = Rect(16f, 24f, 64f, 72f)
        val target = Rect(180f, 230f, 420f, 470f)

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                Box(Modifier.fillMaxSize()) {
                    MiniPlayer(
                        presentation = miniPresentation(),
                        onOpen = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                        artwork = bitmap,
                        artworkAlpha = if (transitioning) 0f else 1f,
                        onExpandDragDistanceChanged = { distance, _ ->
                            transitioning = true
                            progress = (distance / 200f).coerceIn(0f, 1f)
                        },
                        onExpandDragEnd = { _, _ ->
                            progress = 0f
                            transitioning = false
                        },
                        onExpandDragCancel = {
                            progress = 0f
                            transitioning = false
                        },
                    )
                    if (transitioning) {
                        ArtworkTransitionOverlay(
                            bitmap = bitmap,
                            bitmapKey = key,
                            currentKey = key,
                            sourceBounds = source,
                            targetBounds = target,
                            progress = progress,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("mini-player-body").performTouchInput {
            down(center)
            moveBy(Offset(0f, -with(composeRule.density) { 28.dp.toPx() }))
            up()
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithTag("mini-player-artwork-transition-overlay").assertCountEquals(0)
        composeRule.onNodeWithTag("mini-player-artwork-image").assertIsDisplayed()
    }

    @Test
    fun artworkIdentityChangeNeverShowsPreviousBitmap() {
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                Box(Modifier.fillMaxSize()) {
                    ArtworkTransitionOverlay(
                        bitmap = artworkBitmap(),
                        bitmapKey = key,
                        currentKey = otherKey,
                        sourceBounds = Rect(20f, 20f, 68f, 68f),
                        targetBounds = Rect(180f, 180f, 420f, 420f),
                        progress = 0.5f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("mini-player-artwork-transition-overlay").assertCountEquals(0)
    }

    @Test
    fun completedTransitionHandsOffWithoutAVisibleJump() {
        var transitioning by mutableStateOf(true)
        val bitmap = artworkBitmap()
        val source = Rect(18f, 26f, 66f, 74f)
        var rootOrigin by mutableStateOf(Offset.Zero)
        var targetBounds by mutableStateOf<Rect?>(null)

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                Box(
                    Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionOnScreen() },
                ) {
                    ArtworkStage(
                        bitmap = bitmap,
                        placeholderLabel = "无封面",
                        modifier = Modifier.size(260.dp).align(Alignment.Center),
                        artworkVisible = !transitioning,
                        onArtworkBoundsChanged = { targetBounds = it },
                    )
                    if (transitioning) {
                        val target = targetBounds?.translate(Offset(-rootOrigin.x, -rootOrigin.y))
                        if (target != null) {
                            ArtworkTransitionOverlay(
                                bitmap = bitmap,
                                bitmapKey = key,
                                currentKey = key,
                                sourceBounds = source,
                                targetBounds = target,
                                progress = 1f,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val before = composeRule.onNodeWithTag("player-artwork-stage")
            .fetchSemanticsNode().boundsInRoot
        val overlay = composeRule.onNodeWithTag("mini-player-artwork-transition-overlay")
            .fetchSemanticsNode().boundsInRoot
        assertRectEquals(before, overlay)

        composeRule.runOnIdle { transitioning = false }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("mini-player-artwork-transition-overlay").assertCountEquals(0)
        composeRule.onNodeWithTag("player-artwork-stage").assertIsDisplayed()
        val after = composeRule.onNodeWithTag("player-artwork-stage")
            .fetchSemanticsNode().boundsInRoot
        assertRectEquals(before, after)
    }

    private fun artworkBitmap() = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.MAGENTA)
    }.asImageBitmap()

    private fun miniPresentation() = MiniPlayerPresentation(
        title = "测试曲目",
        hasTrack = true,
        playbackStatus = ConfirmedPlaybackStatus.PLAYING,
        statusLabel = null,
    )

    private fun assertRectEquals(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 1.5f)
        assertEquals(expected.top, actual.top, 1.5f)
        assertEquals(expected.right, actual.right, 1.5f)
        assertEquals(expected.bottom, actual.bottom, 1.5f)
    }
}
