package io.github.qyxx2.mpdserver.remote.ui.search

import io.github.qyxx2.mpdserver.remote.protocol.dto.SearchGroupDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SearchGroupKindDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SearchResultsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchProjectionTest {
    @Test
    fun groupsAndSongsPreserveServerOrderAndDeduplicateOnFirstAppearance() {
        val song2 = song("song-2")
        val song1 = song("song-1")
        val song3 = song("song-3")
        val song4 = song("song-4")
        val response = SearchResultsDto(
            groups = listOf(
                SearchGroupDto(SearchGroupKindDto.ALBUMS, "album-id", "Album", listOf("song-2", "song-1", "song-2")),
                SearchGroupDto(SearchGroupKindDto.ARTISTS, "artist/id", "Artist", listOf("song-1", "song-3")),
            ),
            songIds = listOf("song-3", "song-4", "song-4"),
        )

        val projection = SearchProjection.from(
            response,
            mapOf("song-1" to song1, "song-2" to song2, "song-3" to song3, "song-4" to song4),
        )

        assertEquals(listOf("album-id", "artist/id"), projection.groups.map { it.sourceId })
        assertEquals(listOf("song-2", "song-1"), projection.groups[0].songs.map { it.songId })
        assertEquals(listOf("song-3"), projection.groups[1].songs.map { it.songId })
        assertEquals(listOf("song-4"), projection.songs.map { it.songId })
        assertSame(song2, projection.groups[0].songs[0].song)
        assertSame(song3, projection.groups[1].songs.single().song)
    }

    @Test
    fun missingSongIdsRemainVisibleAsNonPlayablePlaceholders() {
        val projection = SearchProjection.from(
            SearchResultsDto(
                groups = listOf(
                    SearchGroupDto(SearchGroupKindDto.TAGS, "tag-id", "Tag", listOf("removed-song")),
                ),
                songIds = listOf("another-removed-song"),
            ),
            emptyMap(),
        )

        val groupSong = projection.groups.single().songs.single()
        val topLevelSong = projection.songs.single()
        assertEquals("removed-song", groupSong.songId)
        assertNull(groupSong.song)
        assertFalse(groupSong.isPlayable)
        assertEquals("another-removed-song", topLevelSong.songId)
        assertNull(topLevelSong.song)
    }

    @Test
    fun groupKindMappingUsesTheExactServerKindAndOpaqueSourceId() {
        val kinds = SearchGroupKindDto.entries
        assertEquals(
            listOf(
                "library/albums",
                "library/artists",
                "library/genres",
                "library/years",
                "library/tags",
            ),
            kinds.map { SearchProjection.categoryRoute(it, "ignored").substringBeforeLast('/') },
        )
        assertEquals(
            "library/albums/id%20with%20spaces",
            SearchProjection.categoryRoute(SearchGroupKindDto.ALBUMS, "id with spaces"),
        )
    }

    @Test
    fun responseProjectionDoesNotInventAGroupForTopLevelSongIds() {
        val projection = SearchProjection.from(
            SearchResultsDto(groups = emptyList(), songIds = listOf("song-1")),
            mapOf("song-1" to song("song-1")),
        )
        assertTrue(projection.groups.isEmpty())
        assertEquals(listOf("song-1"), projection.songs.map { it.songId })
    }

    private fun song(id: String) = SongDto(
        songId = id,
        title = id,
        fileUri = "music/$id.flac",
        artists = emptyList(),
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
        lastSeenAt = null,
        artwork = null,
    )
}
