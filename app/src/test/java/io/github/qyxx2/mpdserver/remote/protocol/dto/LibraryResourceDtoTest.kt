package io.github.qyxx2.mpdserver.remote.protocol.dto

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryResourceDtoTest {
    @Test fun songListPreservesNullableIdentityAvailabilityAndArtwork() {
        val songJson = javaClass.classLoader!!.getResource("fixtures/task4/song-response.json")!!.readText()
        val result = JsonConfiguration.json.decodeFromString<SongListDto>("""{"items":[$songJson],"count":1}""")

        assertEquals(1, result.count)
        assertEquals("song-1", result.items.single().songId)
        assertEquals("music/example.flac", result.items.single().fileUri)
        assertEquals("album-1", result.items.single().albumId)
        assertEquals(AvailabilityStatus.AVAILABLE, result.items.single().availabilityStatus)
        assertEquals("art-1", result.items.single().artwork?.artworkId)

        val orphanSongJson = songJson
            .replace("\"song_id\":\"song-1\"", "\"song_id\":null")
            .replace("\"album_id\":\"album-1\"", "\"album_id\":null")
        val orphan = JsonConfiguration.json.decodeFromString<SongListDto>("""{"items":[$orphanSongJson],"count":1}""")
        assertNull(orphan.items.single().songId)
        assertNull(orphan.items.single().albumId)
    }

    @Test fun categoryListsDecodeUsingServerIdsAndNullableYear() {
        val albums = JsonConfiguration.json.decodeFromString<AlbumListDto>("""{"items":[{"album_id":"album-1","title":"Album","album_artists":["Artist"],"year":2024,"date":"2024-01-01","song_count":4,"artwork":null}],"count":1}""")
        val artists = JsonConfiguration.json.decodeFromString<ArtistListDto>("""{"items":[{"artist_id":"artist-1","name":"Artist","song_count":4}],"count":1}""")
        val genres = JsonConfiguration.json.decodeFromString<GenreListDto>("""{"items":[{"genre_id":"genre-1","name":"Jazz","song_count":4}],"count":1}""")
        val years = JsonConfiguration.json.decodeFromString<YearListDto>("""{"items":[{"source_id":"unknown","value":null,"song_count":2}],"count":1}""")
        val tags = JsonConfiguration.json.decodeFromString<TagListDto>("""{"items":[{"tag_id":"tag-1","name":"HiRes","song_count":2}],"count":1}""")

        assertEquals("album-1", albums.items.single().albumId)
        assertEquals("artist-1", artists.items.single().artistId)
        assertEquals("genre-1", genres.items.single().genreId)
        assertEquals("unknown", years.items.single().sourceId)
        assertNull(years.items.single().value)
        assertEquals("tag-1", tags.items.single().tagId)
    }

    @Test fun listEnvelopeAndSummaryRequiredFieldsCannotSilentlyDefault() {
        assertThrows(SerializationException::class.java) {
            JsonConfiguration.json.decodeFromString<SongListDto>("""{"items":[]}""")
        }
        assertThrows(SerializationException::class.java) {
            JsonConfiguration.json.decodeFromString<YearListDto>("""{"items":[{"source_id":"unknown","song_count":2}],"count":1}""")
        }
        assertThrows(SerializationException::class.java) {
            JsonConfiguration.json.decodeFromString<GenreListDto>("""{"items":[{"genre_id":"g","name":"Jazz"}],"count":1}""")
        }
    }
}
