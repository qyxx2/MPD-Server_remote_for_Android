package io.github.qyxx2.mpdserver.remote.protocol.dto

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CollectionResourceDtoTest {
    @Test fun collectionResponseRetainsSourceAndUnavailableSongIds() {
        val dto = JsonConfiguration.json.decodeFromString<CollectionDto>("""{"source_type":"FAVORITES","source_id":null,"song_ids":["song-1","song-2"],"unavailable_song_ids":["song-2"],"random_seed":17}""")
        assertEquals(CollectionSourceTypeDto.FAVORITES, dto.sourceType)
        assertNull(dto.sourceId)
        assertEquals(listOf("song-1", "song-2"), dto.songIds)
        assertEquals(listOf("song-2"), dto.unavailableSongIds)
        assertEquals(17L, dto.randomSeed)
    }

    @Test fun favoriteMembersAreTypedAndCollectionEnumsAreStrict() {
        val favorites = JsonConfiguration.json.decodeFromString<FavoriteMembersDto>("""{"song_ids":["song-1"]}""")
        assertEquals(listOf("song-1"), favorites.songIds)
        assertThrows(SerializationException::class.java) {
            JsonConfiguration.json.decodeFromString<CollectionDto>("""{"source_type":"UNKNOWN","source_id":null,"song_ids":[],"unavailable_song_ids":[],"random_seed":null}""")
        }
        assertThrows(SerializationException::class.java) {
            JsonConfiguration.json.decodeFromString<FavoriteMembersDto>("{}")
        }
    }
}
