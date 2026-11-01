package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.protocol.dto.AlbumSummaryDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongListDto
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryAlbumDetailScreenUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun albumDetailUsesWebHeroActionsCountAndTitleArtistRowsWithoutTrackThumbnails() {
        val song = song("single-track", "Only Track", "Solo Artist").copy(
            albumId = "album-one-track",
            album = "One-track album",
            albumArtists = listOf("Album Artist"),
            year = 2024,
        )
        val summary = AlbumSummaryDto(
            albumId = "album-one-track",
            title = "One-track album",
            albumArtists = listOf("Album Artist"),
            year = 2024,
            date = null,
            songCount = 1,
            artwork = null,
        )
        composeRule.setContent {
            LibraryAlbumDetailContent(
                sourceId = "album-one-track",
                summary = summary,
                songsState = LibraryLoadState.Ready(SongListDto(listOf(song), count = 1)),
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("library-album-detail-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("library-album-cover-placeholder").assertIsDisplayed()
        composeRule.onNodeWithTag("library-album-title").assertIsDisplayed()
        composeRule.onNodeWithText("One-track album").assertIsDisplayed()
        composeRule.onNodeWithText("Album Artist").assertIsDisplayed()
        composeRule.onNodeWithText("2024 · 1 首").assertIsDisplayed()
        composeRule.onNodeWithTag("library-album-collection-controls").assertIsDisplayed()
        composeRule.onNodeWithText("播放全部").assertIsDisplayed()
        composeRule.onNodeWithText("随机播放").assertIsDisplayed()
        composeRule.onNodeWithText("可播放歌曲 · 1 首").assertIsDisplayed()
        composeRule.onNodeWithText("Only Track").assertIsDisplayed()
        composeRule.onNodeWithText("Solo Artist").assertIsDisplayed()
        composeRule.onAllNodesWithTag("library-row-artwork-single-track" ).assertCountEquals(0)
        composeRule.onAllNodesWithTag("library-song-artwork" ).assertCountEquals(0)
    }

    @Test
    fun albumMetadataFallsBackToTheFirstServerSongWhenSummaryIsUnavailable() {
        val song = song("fallback-song", "Fallback Track", "Fallback Artist").copy(
            albumId = "album-fallback",
            album = "Fallback Album",
            albumArtists = listOf("Fallback Album Artist"),
            year = 1999,
        )
        composeRule.setContent {
            LibraryAlbumDetailContent(
                sourceId = "album-fallback",
                summary = null,
                songsState = LibraryLoadState.Ready(SongListDto(listOf(song), count = 1)),
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Fallback Album").assertIsDisplayed()
        composeRule.onNodeWithText("Fallback Album Artist").assertIsDisplayed()
        composeRule.onNodeWithText("1999 · 1 首").assertIsDisplayed()
    }

    private fun song(id: String, title: String, artist: String) = SongDto(
        songId = id,
        title = title,
        fileUri = "file:///music/$id.flac",
        artists = listOf(artist),
        albumId = null,
        album = null,
        albumArtists = emptyList(),
        trackNumber = 1,
        discNumber = null,
        year = null,
        date = null,
        genres = emptyList(),
        tagNames = emptyList(),
        duration = 180.0,
        lyrics = null,
        lyricsFormat = null,
        lyricsSource = null,
        bitDepth = 16,
        sampleRateHz = 44100,
        channelCount = 2,
        codec = "flac",
        metadataStatus = "available",
        lastScannedAt = null,
        fileSize = 1L,
        fileMtimeNs = 1L,
        contentHash = null,
        availabilityStatus = io.github.qyxx2.mpdserver.remote.protocol.dto.AvailabilityStatus.AVAILABLE,
        lastSeenAt = null,
        artwork = null,
    )
}
