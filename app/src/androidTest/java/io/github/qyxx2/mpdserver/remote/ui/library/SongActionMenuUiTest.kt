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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
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
    fun menuIsCenteredOnItsSongRowAndShowsAnIconForEveryAction() {
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                AnchoredMenuFixture(onAction = {})
            }
        }

        val anchorNode = composeRule.onNodeWithTag("song-action-area-song-a").fetchSemanticsNode()
        val anchorOrigin = anchorNode.layoutInfo.coordinates.localToScreen(Offset.Zero)
        val anchorCenterX = anchorOrigin.x + anchorNode.size.width / 2f
        val anchorCenterY = anchorOrigin.y + anchorNode.size.height / 2f
        composeRule.onNodeWithTag("song-action-area-song-a").performClick()
        composeRule.onNodeWithTag("song-action-menu-song-a").assertIsDisplayed()

        val menuNode = composeRule.onNodeWithTag("song-action-menu-song-a").fetchSemanticsNode()
        val menuOrigin = menuNode.layoutInfo.coordinates.localToScreen(Offset.Zero)
        val menuCenterX = menuOrigin.x + menuNode.size.width / 2f
        val menuCenterY = menuOrigin.y + menuNode.size.height / 2f
        val display = composeRule.activity.resources.displayMetrics
        val tolerancePx = 16f * display.density
        assertTrue(
            "Popup must be horizontally centered on the song row: anchorX=" + anchorCenterX + " menuX=" + menuCenterX,
            kotlin.math.abs(menuCenterX - anchorCenterX) < tolerancePx,
        )
        assertTrue(
            "Popup must vertically follow the song row: anchorY=" + anchorCenterY + " menuY=" + menuCenterY,
            kotlin.math.abs(menuCenterY - anchorCenterY) < tolerancePx,
        )
        composeRule.onAllNodesWithText("取消").assertCountEquals(0)
        composeRule.onNodeWithText("立即播放").assertIsDisplayed()
        composeRule.onNodeWithText("下一首播放").assertIsDisplayed()
        composeRule.onNodeWithText("添加到播放队列").assertIsDisplayed()
        composeRule.onNodeWithText("收藏").assertIsDisplayed()
        listOf("play_now", "play_next", "add_to_queue", "favorite").forEach { action ->
            composeRule.onNodeWithTag("song-action-icon-$action-song-a", useUnmergedTree = true)
                .assertIsDisplayed()
        }
        val labels = listOf("play_now", "play_next", "add_to_queue", "favorite").map { action ->
            composeRule.onNodeWithTag("song-action-label-$action-song-a").fetchSemanticsNode().boundsInRoot
        }
        val labelLefts = labels.map { it.left }
        assertTrue("All menu labels must share one left edge", labelLefts.maxOrNull()!! - labelLefts.minOrNull()!! < 1f)
        val longestLabel = labels[2]
        assertTrue("The longest option must define the menu's right content edge", labels.all { it.right <= longestLabel.right + 1f })
        val menuBounds = composeRule.onNodeWithTag("song-action-menu-song-a").fetchSemanticsNode().boundsInRoot
        val rightInsetDp = (menuBounds.right - longestLabel.right) / display.density
        assertTrue("Menu right edge should closely follow the longest label, inset=$rightInsetDp dp", rightInsetDp in 4f..24f)
    }

    @Test
    fun tappingSongTitleAreaOpensItsMenuWithoutAThreeDotButton() {
        val item = LibrarySongRowModel(
            key = "song:song-a",
            songId = "song-a",
            song = playableSong(),
        )
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                LibrarySongRow(item = item, songActions = playbackActions())
            }
        }

        val actionAreaTag = "song-action-area-${item.key.hashCode()}"
        composeRule.onNodeWithTag(actionAreaTag).performTouchInput {
            click(Offset(260f, 35f))
        }
        composeRule.onNodeWithTag("song-action-menu-${item.key.hashCode()}").assertIsDisplayed()
        composeRule.onAllNodesWithText("⋮").assertCountEquals(0)
    }

    @Test
    fun collectionPlaybackControlsCenterThePairAndShowSimpleActionIcons() {
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    CollectionPlaybackControls(
                        enabled = true,
                        onPlayAll = {},
                        onShuffle = {},
                        modifier = Modifier.fillMaxWidth(),
                        tag = "centered-collection-controls",
                    )
                }
            }
        }

        composeRule.onNodeWithTag("centered-collection-controls-play-icon", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("centered-collection-controls-shuffle-icon", useUnmergedTree = true).assertIsDisplayed()
        val container = composeRule.onNodeWithTag("centered-collection-controls").fetchSemanticsNode().boundsInRoot
        val play = composeRule.onNodeWithTag("centered-collection-controls-play-all").fetchSemanticsNode().boundsInRoot
        val shuffle = composeRule.onNodeWithTag("centered-collection-controls-shuffle").fetchSemanticsNode().boundsInRoot
        val pairCenter = (play.left + shuffle.right) / 2f
        assertTrue("Play All and Shuffle must be centered as one group", kotlin.math.abs(pairCenter - container.center.x) < 2f)
    }

    @Test
    fun outsideTapDismissesSongMenuAndChosenActionAlsoDismissesIt() {
        var selected: SongPlaybackAction? = null
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                AnchoredMenuFixture(onAction = { selected = it })
            }
        }

        composeRule.onNodeWithTag("song-action-area-song-a").performClick()
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

        composeRule.onNodeWithTag("song-action-area-song-a").performClick()
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
                        content = { Text("Song A") },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("song-action-area-374050734").assertIsNotEnabled()
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
    private fun AnchoredMenuFixture(
        onAction: (SongPlaybackAction) -> Unit,
        onFavorite: () -> Unit = {},
    ) {
        Box(Modifier.fillMaxSize()) {
            Text(
                "outside",
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(20.dp)
                    .clickable {}
                    .testTag("song-menu-outside-tap"),
            )
            Box(modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp)) {
                AnchoredSongActionMenu(
                    menuKey = "song-a",
                    enabled = true,
                    onAction = onAction,
                    onFavorite = onFavorite,
                    content = { Text("Song A") },
                )
            }
        }
    }

}
