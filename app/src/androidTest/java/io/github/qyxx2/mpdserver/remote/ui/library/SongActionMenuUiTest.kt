package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.dto.AvailabilityStatus
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.runtime.MutationIntent
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SongActionMenuUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun menuIsAnchoredToItsOwnSongButtonAndContainsNoCancelAction() {
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                AnchoredMenuFixture(onAction = {})
            }
        }

        val anchorNode = composeRule.onNodeWithTag("song-actions-song-a").fetchSemanticsNode()
        val anchorOrigin = anchorNode.layoutInfo.coordinates.localToScreen(Offset.Zero)
        val anchorCenterX = anchorOrigin.x + anchorNode.size.width / 2f
        composeRule.onNodeWithTag("song-actions-song-a").performClick()
        composeRule.onNodeWithTag("song-action-menu-song-a").assertIsDisplayed()

        val menuNode = composeRule.onNodeWithTag("song-action-menu-song-a").fetchSemanticsNode()
        val menuOrigin = menuNode.layoutInfo.coordinates.localToScreen(Offset.Zero)
        val menuCenterX = menuOrigin.x + menuNode.size.width / 2f
        val menuCenterY = menuOrigin.y + menuNode.size.height / 2f
        val display = composeRule.activity.resources.displayMetrics
        val horizontalDistance = kotlin.math.abs(menuCenterX - anchorCenterX)
        assertTrue(
            "Popup should follow its song button, not appear in the center of the screen: anchorX=" + anchorCenterX + " menuX=" + menuCenterX + " menuY=" + menuCenterY + " size=" + menuNode.size + " display=" + display.widthPixels + "x" + display.heightPixels,
            horizontalDistance < display.widthPixels * 0.34f,
        )
        assertTrue(
            "Popup should remain near the bottom-end song row that opened it",
            menuCenterY > display.heightPixels / 2f,
        )
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        composeRule.onNodeWithText("立即播放").assertIsDisplayed()
        composeRule.onNodeWithText("下一首播放").assertIsDisplayed()
        composeRule.onNodeWithText("添加到播放队列").assertIsDisplayed()
    }

    @Test
    fun outsideTapDismissesSongMenuAndChosenActionAlsoDismissesIt() {
        var selected: SongPlaybackAction? = null
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                AnchoredMenuFixture(onAction = { selected = it })
            }
        }

        composeRule.onNodeWithTag("song-actions-song-a").performClick()
        composeRule.onNodeWithTag("song-action-menu-song-a").assertIsDisplayed()
        // Compose semantics clicks bypass a focusable Popup window. Inject a real screen touch
        // so Android routes it to the Popup outside-dismiss handler.
        val decor = composeRule.activity.window.decorView
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        val x = location[0] + decor.width * 0.10f
        val y = location[1] + decor.height * 0.78f
        val downTime = SystemClock.uptimeMillis()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 60L, MotionEvent.ACTION_UP, x, y, 0)
        try {
            instrumentation.uiAutomation.injectInputEvent(down, true)
            instrumentation.uiAutomation.injectInputEvent(up, true)
        } finally {
            down.recycle()
            up.recycle()
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("song-action-menu-song-a").assertCountEquals(0)

        composeRule.onNodeWithTag("song-actions-song-a").performClick()
        composeRule.onNodeWithText("下一首播放").performClick()
        composeRule.runOnIdle { assertEquals(SongPlaybackAction.PLAY_NEXT, selected) }
        composeRule.onAllNodesWithTag("song-action-menu-song-a").assertCountEquals(0)
    }

    @Test
    fun menuAffordanceIsDisabledWhenTheCanonicalSnapshotIsNotWritable() {
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalPlaybackActionsEnabled provides false,
                ) {
                    SongActionMenu(
                        item = LibrarySongRowModel(
                            key = "song:song-a",
                            songId = "song-a",
                            song = playableSong(),
                        ),
                        actions = playbackActions(),
                    )
                }
            }
        }
        composeRule.onNodeWithTag("song-actions-374050734").assertIsNotEnabled()
    }

    @Test
    fun collectionControlsRespectTheSharedUnresolvedMutationLock() {
        val activities = MutableStateFlow(emptyList<OperationActivity>())
        val activity = OperationActivity(
            intentId = "collection:pending",
            label = "Collection playback",
            status = MutationStatus.PENDING,
            idempotencyKey = "stable-key",
            origin = ServerOrigin.parse("http://music.example:18000"),
        )
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                CollectionPlaybackControls(
                    enabled = true,
                    onPlayAll = {},
                    onShuffle = {},
                    activities = activities,
                    tag = "test-collection-controls",
                )
            }
        }
        composeRule.onNodeWithTag("test-collection-controls-play-all").assertIsEnabled()
        composeRule.runOnIdle { activities.value = listOf(activity) }
        composeRule.onNodeWithTag("test-collection-controls-play-all").assertIsNotEnabled()
        composeRule.runOnIdle {
            activities.value = listOf(activity.copy(status = MutationStatus.SUCCESS, syncError = "readback failed"))
        }
        composeRule.onNodeWithTag("test-collection-controls-shuffle").assertIsNotEnabled()
        composeRule.runOnIdle { activities.value = emptyList() }
        composeRule.onNodeWithTag("test-collection-controls-shuffle").assertIsEnabled()
    }

    private fun playbackActions(): SongActions {
        val activities = MutableStateFlow(emptyList<OperationActivity>())
        val gateway = PlaybackMutationGateway(
            stateProvider = { SnapshotStoreState() },
            activities = activities,
            submitter = { _: MutationIntent -> MutationOutcome.Submitted },
            retryUnknown = { _: String -> MutationOutcome.NotRetryable },
            canRetryUnknown = { _: String -> false },
        )
        return SongActions(gateway)
    }

    private fun playableSong() = SongDto(
        songId = "song-a", title = "Song A", fileUri = "music/song-a.flac", artists = emptyList(),
        albumId = null, album = null, albumArtists = emptyList(), trackNumber = null,
        discNumber = null, year = null, date = null, genres = emptyList(), tagNames = emptyList(),
        duration = null, lyrics = null, lyricsFormat = null, lyricsSource = null,
        bitDepth = null, sampleRateHz = null, channelCount = null, codec = null,
        metadataStatus = null, lastScannedAt = null, fileSize = null, fileMtimeNs = null,
        contentHash = null, availabilityStatus = AvailabilityStatus.AVAILABLE, lastSeenAt = null,
        artwork = null,
    )

    private fun displayWidth(): Float = composeRule.activity.resources.displayMetrics.widthPixels.toFloat()
    private fun displayHeight(): Float = composeRule.activity.resources.displayMetrics.heightPixels.toFloat()

    @Composable
    private fun AnchoredMenuFixture(onAction: (SongPlaybackAction) -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Text(
                "outside",
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(20.dp)
                    .clickable {}
                    .testTag("song-menu-outside-tap"),
            )
            Box(
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            ) {
                AnchoredSongActionMenu(
                    menuKey = "song-a",
                    enabled = true,
                    onAction = onAction,
                )
            }
        }
    }

}
