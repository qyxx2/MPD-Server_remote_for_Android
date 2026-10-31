package io.github.qyxx2.mpdserver.remote.ui.library

import io.github.qyxx2.mpdserver.remote.protocol.dto.AvailabilityStatus
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryProjectionTest {
    @Test
    fun songsRetainEveryServerRowAndUseFileUriForRowsWithoutSongId() {
        val available = song("song-1", "music/one.flac")
        val missing = song("song-2", "music/two.flac").copy(availabilityStatus = AvailabilityStatus.MISSING)
        val noId = song(null, "music/unindexed.flac")

        val rows = LibraryProjection.songRows(listOf(available, missing, noId))

        assertEquals(listOf("song:song-1", "song:song-2", "file:music/unindexed.flac"), rows.map { it.key })
        assertEquals(3, rows.size)
        assertSame(available, rows[0].song)
        assertSame(missing, rows[1].song)
        assertEquals(AvailabilityStatus.MISSING, rows[1].song?.availabilityStatus)
        assertSame(noId, rows[2].song)
        assertFalse(rows[2].isPlayable)
    }

    @Test
    fun idBasedCollectionsKeepServerIdOrderAndRetainMissingSongPlaceholders() {
        val available = song("song-2", "music/two.flac")
        val rows = LibraryProjection.songIds(
            ids = listOf("song-3", "song-2", "song-1"),
            songsById = mapOf("song-2" to available),
        )

        assertEquals(listOf("song-3", "song-2", "song-1"), rows.map { it.songId })
        assertNull(rows[0].song)
        assertFalse(rows[0].isPlayable)
        assertSame(available, rows[1].song)
        assertNull(rows[2].song)
    }

    @Test
    fun idBasedCollectionsKeepRepeatedPlaylistOccurrencesWithUniqueStableKeys() {
        val repeated = song("song-2", "music/two.flac")
        val rows = LibraryProjection.songIds(
            ids = listOf("song-2", "song-1", "song-2"),
            songsById = mapOf("song-2" to repeated),
        )

        assertEquals(listOf("song-2", "song-1", "song-2"), rows.map { it.songId })
        assertEquals(listOf("song:song-2", "song:song-1", "song:song-2#2"), rows.map { it.key })
        assertEquals(3, rows.map { it.key }.distinct().size)
        assertSame(repeated, rows[0].song)
        assertSame(repeated, rows[2].song)
    }

    @Test
    fun categoryOrderHasSongsFirstAndRetainsSevenOtherCategories() {
        assertEquals(
            listOf(
                LibraryCategory.SONGS,
                LibraryCategory.PLAYLISTS,
                LibraryCategory.ARTISTS,
                LibraryCategory.ALBUMS,
                LibraryCategory.GENRES,
                LibraryCategory.YEARS,
                LibraryCategory.TAGS,
                LibraryCategory.FAVORITES,
            ),
            LibraryProjection.categories,
        )
        assertTrue(LibraryProjection.categories.distinct().size == 8)
    }

    private fun song(id: String?, uri: String) = SongDto(
        songId = id,
        title = "Title ${id ?: "without-id"}",
        fileUri = uri,
        artists = listOf("Artist"),
        albumId = null,
        album = null,
        albumArtists = emptyList(),
        trackNumber = null,
        discNumber = null,
        year = null,
        date = null,
        genres = emptyList(),
        tagNames = emptyList(),
        duration = null,
        lyrics = null,
        lyricsFormat = null,
        lyricsSource = null,
        bitDepth = null,
        sampleRateHz = null,
        channelCount = null,
        codec = null,
        metadataStatus = null,
        lastScannedAt = null,
        fileSize = null,
        fileMtimeNs = null,
        contentHash = null,
        availabilityStatus = AvailabilityStatus.AVAILABLE,
        lastSeenAt = null,
        artwork = null,
    )
}
