package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import io.github.qyxx2.mpdserver.remote.ui.theme.MpdServerRemoteTheme
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullScreenPlayerPresentationUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rendersArtworkFirstThenTitleArtistAlbumAndSourceWithoutInventedArtwork() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        val presentation = testPresentation()

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                FullScreenPlayerScreen(
                    runtime = application.runtime,
                    presentation = presentation,
                    onExit = {},
                    onOpenQueue = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("player-artwork-stage").assertIsDisplayed()
        composeRule.onNodeWithText("无封面").assertIsDisplayed()
        composeRule.onNodeWithTag("player-song-title").assertIsDisplayed()
        composeRule.onNodeWithTag("player-artist-0").assertIsDisplayed()
        composeRule.onNodeWithTag("player-album-link").assertIsDisplayed()
        composeRule.onNodeWithTag("player-source-format").assertIsDisplayed()

        val titleTop = composeRule.onNodeWithTag("player-song-title").fetchSemanticsNode().boundsInRoot.top
        val artistTop = composeRule.onNodeWithTag("player-artist-0").fetchSemanticsNode().boundsInRoot.top
        val albumTop = composeRule.onNodeWithTag("player-album-link").fetchSemanticsNode().boundsInRoot.top
        val formatTop = composeRule.onNodeWithTag("player-source-format").fetchSemanticsNode().boundsInRoot.top
        assertTrue("title should precede artist", titleTop < artistTop)
        assertTrue("artist should precede album", artistTop < albumTop)
        assertTrue("album should precede source-file format", albumTop < formatTop)
    }

    @Test
    fun artistAndAlbumClicksEmitNamesAndTypedServerOwnedIds() {
        val application = composeRule.activity.application as MpdServerRemoteApplication
        var artistName: String? = null
        var entityRequest: PlayerEntityNavigationRequest? = null

        composeRule.setContent {
            MpdServerRemoteTheme(themePreference = ThemePreference.FOLLOW_SYSTEM) {
                FullScreenPlayerScreen(
                    runtime = application.runtime,
                    presentation = testPresentation(),
                    onExit = {},
                    onOpenQueue = {},
                    onArtistClick = { artistName = it },
                    onEntityNavigationRequest = { entityRequest = it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("player-artist-0").performClick()
        composeRule.onNodeWithTag("player-album-link").performClick()

        assertEquals("Test Artist", artistName)
        assertEquals(
            PlayerEntityNavigationRequest(
                type = PlayerEntityType.ALBUM,
                id = "album-server-42",
                displayName = "Test Album",
            ),
            entityRequest,
        )
    }

    private fun testPresentation(): PlayerPresentation {
        val song = PlayerSongPresentation(
            songId = "song-test-1",
            title = "Test Song",
            fileUri = "file:///music/test.flac",
            artists = listOf("Test Artist"),
            albumId = "album-server-42",
            album = "Test Album",
            albumArtists = emptyList(),
            trackNumber = 1,
            discNumber = 1,
            durationSeconds = 200.0,
            lyrics = null,
            lyricsFormat = null,
            lyricsSource = null,
            lyricsStatus = LyricsResourceStatus.MISSING,
            rawLyricsStatus = "missing",
            artworkReferenceStatus = ArtworkReferenceStatus.MISSING,
        )
        return PlayerPresentation(
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
}
