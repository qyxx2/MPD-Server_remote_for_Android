package io.github.qyxx2.mpdserver.remote.protocol.dto

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Assert.assertThrows

class SongDtoTest {
    @Test fun requiredNullableSongIdAndMetadataRemainExplicitlyNull() {
        val dto = JsonConfiguration.json.decodeFromString<SongDto>(completeSong)
        assertNull(dto.songId)
        assertNull(dto.duration)
        assertNull(dto.artist)
        assertNull(dto.album)
    }

    @Test fun missingNullableMetadataIsNotSilentlyConvertedToNull() {
        val missing = completeSong.replace("\"artist\":null,", "")
        assertThrows(SerializationException::class.java) {
            JsonConfiguration.json.decodeFromString<SongDto>(missing)
        }
    }

    private val completeSong = """{
        "song_id":null,
        "title":"Unknown track",
        "file_uri":"music/unknown.flac",
        "availability_status":"AVAILABLE",
        "duration":null,
        "artist":null,
        "album":null
    }"""
}
