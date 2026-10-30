package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsStageUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun fullScreenArtworkTapSwitchesToLyricsAndReturnKeepsLowerAnchorsFixed() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val presentation = playerPresentation(
            song(lyrics = "[00:37]第一行\n[00:39]第二行", format = "lrc"),
        )

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                FullScreenPlayerScreen(
                    runtime = application.runtime,
                    presentation = presentation,
                    mediaPresentation = presentation,
                    onExit = {},
                    onOpenQueue = {},
                )
            }
        }
        composeRule.waitForIdle()

        val progressBefore = composeRule.onNodeWithTag("player-progress-track")
            .fetchSemanticsNode().boundsInRoot.top
        val controlsBefore = composeRule.onNodeWithTag("player-playback-controls")
            .fetchSemanticsNode().boundsInRoot.top
        val navigationBefore = composeRule.onNodeWithTag("player-bottom-navigation")
            .fetchSemanticsNode().boundsInRoot.top

        composeRule.onNodeWithTag("player-artwork-stage").performClick()
        composeRule.onNodeWithTag("lyrics-stage").assertIsDisplayed()
        composeRule.onNodeWithTag("lyrics-return-artwork").assertIsDisplayed()
        val returnGlyphBounds = composeRule.onNodeWithTag("player-glyph-back", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("return-to-artwork glyph must have visible layout bounds", returnGlyphBounds.width > 0f && returnGlyphBounds.height > 0f)
        composeRule.onAllNodesWithTag("player-song-title").assertCountEquals(0)

        assertEquals(
            progressBefore,
            composeRule.onNodeWithTag("player-progress-track").fetchSemanticsNode().boundsInRoot.top,
            1.5f,
        )
        assertEquals(
            controlsBefore,
            composeRule.onNodeWithTag("player-playback-controls").fetchSemanticsNode().boundsInRoot.top,
            1.5f,
        )
        assertEquals(
            navigationBefore,
            composeRule.onNodeWithTag("player-bottom-navigation").fetchSemanticsNode().boundsInRoot.top,
            1.5f,
        )

        composeRule.onNodeWithTag("lyrics-return-artwork").performClick()
        composeRule.onNodeWithTag("player-artwork-stage").assertIsDisplayed()
        composeRule.onAllNodesWithTag("lyrics-stage").assertCountEquals(0)
    }

    @Test
    fun synchronizedCueTapUsesInverseOffsetOnceAndUnknownClockHasNoHighlight() {
        var sought: Double? = null
        var currentSong by mutableStateOf(
            song(
                lyrics = "[offset:500]\n[00:38]第一行\n[00:40]跳转目标",
                format = "lrc",
            ),
        )
        var currentPosition by mutableStateOf<Double?>(37.5)
        var canSeek by mutableStateOf(true)
        var resourceIdentity by mutableStateOf("epoch-1:item-1:lyrics-a")
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                LyricsStage(
                    song = currentSong,
                    resourceIdentity = resourceIdentity,
                    positionSeconds = currentPosition,
                    durationSeconds = 180.0,
                    seekEnabled = canSeek,
                    onSeek = { sought = it },
                    onShowArtwork = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("lyrics-cue-active").assertIsDisplayed()
        composeRule.onNodeWithTag("lyrics-cue-1").performClick()
        composeRule.runOnIdle { assertEquals(39.5, sought!!, 0.0001) }

        composeRule.runOnIdle {
            sought = null
            currentSong = song("[00:38]第一行\n[00:40]第二行", "lrc")
            currentPosition = null
            canSeek = false
            resourceIdentity = "epoch-1:item-2:unknown-clock"
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("lyrics-cue-active").assertCountEquals(0)
        composeRule.onNodeWithTag("lyrics-status").assertIsDisplayed()
        assertNull(sought)
    }

    @Test
    fun scrollingAndLongPressDoNotSeekAndPlainTextStaysPlain() {
        var seeks = 0
        var currentSong by mutableStateOf(
            song(
                lyrics = "[00:01]第一行\n[00:02]第二行\n[00:03]第三行\n[00:04]第四行",
                format = "lrc",
            ),
        )
        var resourceIdentity by mutableStateOf("timed-scroll")
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                LyricsStage(
                    song = currentSong,
                    resourceIdentity = resourceIdentity,
                    positionSeconds = 1.0,
                    durationSeconds = 120.0,
                    seekEnabled = true,
                    onSeek = { seeks += 1 },
                    onShowArtwork = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("lyrics-cue-1").performTouchInput {
            down(center)
            moveTo(center + Offset(0f, 70f))
            up()
        }
        composeRule.onNodeWithTag("lyrics-cue-2").performTouchInput { longClick() }
        composeRule.runOnIdle { assertEquals(0, seeks) }

        val plainRaw = (1..30).joinToString("\n") { "普通文本第" + it + "行，不含时间轴。" }
        composeRule.runOnIdle {
            currentSong = song(plainRaw, "text")
            resourceIdentity = "plain-text"
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lyrics-plain-text").assertIsDisplayed()
        composeRule.onNodeWithTag("lyrics-plain-scroll").performTouchInput { swipeUp() }
        composeRule.onNodeWithTag("lyrics-plain-text").performClick()
        composeRule.waitForIdle()
        assertTrue("plain text must not be treated as timed cues", composeRule.onAllNodesWithTag("lyrics-cue-0").fetchSemanticsNodes().isEmpty())
        composeRule.runOnIdle { assertEquals(0, seeks) }
    }

    @Test
    fun timedLyricsResumeFollowingTheLatestActiveCueAfterThreeIdleSeconds() {
        val timedLyrics = (1..24).joinToString("\n") {
            "[00:%02d]歌词第%d行".format(it, it)
        }
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                LyricsStage(
                    song = song(timedLyrics, "lrc"),
                    resourceIdentity = "timed-follow-resume",
                    positionSeconds = 1.0,
                    durationSeconds = 120.0,
                    seekEnabled = false,
                    onSeek = {},
                    onShowArtwork = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lyrics-cue-active").assertIsDisplayed()

        repeat(4) {
            composeRule.onNodeWithTag("lyrics-timed-content").performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }

        // Manual browsing should leave the current playback cue behind the viewport;
        // after the Web-compatible idle interval the current cue is brought back into view.
        Thread.sleep(3_250L)
        // Also advance the Compose test clock: its dispatcher can hold delay() until the test clock moves.
        composeRule.mainClock.advanceTimeBy(3_250L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lyrics-cue-active").assertIsDisplayed()
    }

    @Test
    fun lyricTextUsesLargeTypeAndLongCueNeverExceedsTwoVisibleLines() {
        val longCue = "超长歌词内容".repeat(45)
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                LyricsStage(
                    song = song("[00:10]短歌词\n[00:12]$longCue", "lrc"),
                    resourceIdentity = "large-lyrics-layout",
                    positionSeconds = null,
                    durationSeconds = 120.0,
                    seekEnabled = false,
                    onSeek = {},
                    onShowArtwork = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.activity.resources.displayMetrics.density
        val shortCueHeightDp = composeRule.onNodeWithTag("lyrics-cue-0")
            .fetchSemanticsNode().boundsInRoot.height / density
        val longCueHeightDp = composeRule.onNodeWithTag("lyrics-cue-1")
            .fetchSemanticsNode().boundsInRoot.height / density
        assertTrue("inactive lyric type should be large (at least 50dp row height)", shortCueHeightDp >= 50f)
        assertTrue("a long lyric row must stay at two lines or fewer", longCueHeightDp <= 100f)
    }

    @Test
    fun lyricsUseTheFullPlayerMediaWidth() {
        val presentation = playerPresentation(
            song("[00:01]沉浸式歌词内容", "lrc"),
        )
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                FullScreenPlayerScreen(
                    runtime = (composeRule.activity.application as MpdServerRemoteApplication).runtime,
                    presentation = presentation,
                    mediaPresentation = presentation,
                    onExit = {},
                    onOpenQueue = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("player-artwork-stage").performClick()
        composeRule.waitForIdle()

        val mediaBounds = composeRule.onNodeWithTag("full-screen-player-media-region")
            .fetchSemanticsNode().boundsInRoot
        val lyricsBounds = composeRule.onNodeWithTag("lyrics-stage")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "lyrics should occupy the complete media region rather than inherit the artwork side gutters",
            mediaBounds.width,
            lyricsBounds.width,
            1.5f,
        )
    }

    @Test
    fun longLyricCueRetainsItsCompleteTextWithinTheTwoLinePresentation() {
        val longCue = "这是不能被截断的歌词内容".repeat(30)
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                LyricsStage(
                    song = song("[00:01]$longCue", "lrc"),
                    resourceIdentity = "long-complete-lyric-cue",
                    positionSeconds = 1.5,
                    durationSeconds = 120.0,
                    seekEnabled = true,
                    onSeek = {},
                    onShowArtwork = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitForIdle()

        val cueNode = composeRule.onNodeWithTag("lyrics-cue-active").fetchSemanticsNode()
        val description = cueNode.config.getOrNull(SemanticsProperties.ContentDescription)
            .orEmpty().joinToString(" ")
        assertTrue("the full cue remains accessible while its second visual line scrolls", description.contains(longCue))
    }

    @Test
    fun readErrorFallbackPreservesTimedLyricsAndServerSource() {
        val fallbackSong = song(
            lyrics = "[00:37]服务器回退歌词",
            format = "text",
            source = "embedded",
            status = LyricsResourceStatus.READ_ERROR,
        )
        assertEquals(
            1,
            io.github.qyxx2.mpdserver.remote.ui.player.lyrics.presentLyrics(fallbackSong).document.cues.size,
        )
        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                LyricsStage(
                    song = fallbackSong,
                    resourceIdentity = "read-error-fallback",
                    positionSeconds = 37.0,
                    durationSeconds = 120.0,
                    seekEnabled = false,
                    onSeek = {},
                    onShowArtwork = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("lyrics-status").assertIsDisplayed()
        composeRule.onNodeWithTag("lyrics-status").assertTextEquals("歌词读取失败")
        composeRule.onAllNodesWithText("来源：embedded").assertCountEquals(0)
        composeRule.onNodeWithText("服务器回退歌词", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithTag("lyrics-plain-text").assertCountEquals(0)
    }

    private fun song(
        lyrics: String?,
        format: String? = "lrc",
        source: String? = "sidecar",
        status: LyricsResourceStatus = LyricsResourceStatus.AVAILABLE,
    ) = PlayerSongPresentation(
        songId = "song-lyrics-test",
        title = "歌词测试",
        fileUri = "file:///music/lyrics-test.flac",
        artists = listOf("测试艺术家"),
        albumId = "album-test",
        album = "测试专辑",
        albumArtists = emptyList(),
        trackNumber = 1,
        discNumber = 1,
        durationSeconds = 120.0,
        lyrics = lyrics,
        lyricsFormat = format,
        lyricsSource = source,
        lyricsStatus = status,
        rawLyricsStatus = when (status) {
            LyricsResourceStatus.AVAILABLE -> "available"
            LyricsResourceStatus.MISSING -> "missing"
            LyricsResourceStatus.READ_ERROR -> "read_error"
            LyricsResourceStatus.UNKNOWN -> "unknown"
        },
        artworkReferenceStatus = ArtworkReferenceStatus.MISSING,
    )

    private fun playerPresentation(song: PlayerSongPresentation): PlayerPresentation =
        PlayerPresentation(
            playbackStatus = ConfirmedPlaybackStatus.PAUSED,
            actualState = null,
            businessPlaybackState = null,
            playbackDiagnostics = emptyList(),
            currentOccurrenceId = "queue-item-test",
            songStatus = SongMetadataStatus.AVAILABLE,
            song = song,
            artworkReferenceStatus = ArtworkReferenceStatus.MISSING,
            sourceFormat = SourceFormatPresentation(
                status = SourceFormatStatus.UNKNOWN,
                codec = null,
                bitDepth = null,
                sampleRateHz = null,
                channelCount = null,
            ),
            output = OutputPresentation(
                observationStatus = OutputObservationStatus.UNKNOWN,
                dacStatus = DacOutputStatus.UNKNOWN,
                dacFormat = null,
                dacSampleRateHz = null,
                dacBitDepth = null,
                dacChannels = null,
                errorCode = null,
                errorMessage = null,
                diagnostics = emptyList(),
            ),
            controls = PlayerControlCapabilities(),
            resourceDiagnostics = emptyList(),
        )
}
