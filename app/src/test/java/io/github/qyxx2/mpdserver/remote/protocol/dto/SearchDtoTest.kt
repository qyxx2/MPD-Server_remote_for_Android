package io.github.qyxx2.mpdserver.remote.protocol.dto

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SearchDtoTest {
    @Test fun searchResultsPreserveServerGroupOrderAndIdentity() {
        val dto = JsonConfiguration.json.decodeFromString<SearchResultsDto>("""{"groups":[{"kind":"albums","source_id":"album-1","name":"Album","song_ids":["song-1"]},{"kind":"artists","source_id":"artist-1","name":"Artist","song_ids":["song-1","song-2"]}],"song_ids":["song-1","song-2"]}""")
        assertEquals(listOf(SearchGroupKindDto.ALBUMS, SearchGroupKindDto.ARTISTS), dto.groups.map { it.kind })
        assertEquals("album-1", dto.groups.first().sourceId)
        assertEquals(listOf("song-1", "song-2"), dto.songIds)
    }

    @Test fun unknownSearchGroupKindAndMissingRequiredFieldsAreRejected() {
        assertThrows(SerializationException::class.java) {
            JsonConfiguration.json.decodeFromString<SearchResultsDto>("""{"groups":[{"kind":"playlists","source_id":"p1","name":"Playlist","song_ids":[]}],"song_ids":[]}""")
        }
        assertThrows(SerializationException::class.java) {
            JsonConfiguration.json.decodeFromString<SearchGroupDto>("""{"kind":"albums","source_id":"a1","name":"Album"}""")
        }
    }
}
