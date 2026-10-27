package io.github.qyxx2.mpdserver.remote

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class SongDtoTest {
    private fun fixture() = javaClass.classLoader!!.getResource("fixtures/task4/song-response.json")!!.readText()
    @Test fun decodesCompleteServerSongResponseWithoutFlatteningArtistsOrMetadata() {
        val song = JsonConfiguration.json.decodeFromString<SongDto>(fixture())
        assertEquals("song-1", song.songId); assertEquals(listOf("Artist A", "Artist B"), song.artists)
        assertEquals(3, song.trackNumber); assertEquals(1, song.discNumber); assertEquals(96000, song.sampleRateHz)
        assertEquals("embedded", song.lyricsSource); assertEquals("art-1", song.artwork?.artworkId)
    }
    @Test fun missingRequiredTitleFailsAndNullableMissingIsNotSilentlyNull() {
        assertThrows(SerializationException::class.java) { JsonConfiguration.json.decodeFromString<SongDto>(fixture().replace("\"title\":\"Example\",", "")) }
        assertThrows(SerializationException::class.java) { JsonConfiguration.json.decodeFromString<SongDto>(fixture().replace("\"album\":\"Album\",", "")) }
    }
    @Test fun explicitNullIsPreservedForNullableFields() {
        val song = JsonConfiguration.json.decodeFromString<SongDto>(fixture().replace("\"album\":\"Album\"", "\"album\":null"))
        assertNull(song.album)
    }
}
