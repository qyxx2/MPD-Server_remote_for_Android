package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration

class PlaybackDtoTest {
    @Test fun nullableSongIdMustBePresentEvenWhenNull() {
        val dto = JsonConfiguration.json.decodeFromString<PlaybackStateDto>("""{"song_id":null,"state":"PAUSED","context":null,"position":0,"autoplay_enabled":false,"updated_at":"2026-10-24T12:00:00Z"}""")
        assertEquals(null, dto.songId)
    }

    @Test fun rejectsMissingRequiredSongIdField() {
        assertThrows(SerializationException::class.java) { JsonConfiguration.json.decodeFromString<PlaybackStateDto>("""{"state":"PAUSED","context":null,"position":0,"autoplay_enabled":false,"updated_at":"2026-10-24T12:00:00Z"}""") }
    }

    @Test fun rejectsUnknownPlaybackEnum() {
        assertThrows(SerializationException::class.java) { JsonConfiguration.json.decodeFromString<PlaybackStateDto>("""{"song_id":null,"state":"BUFFERING","context":null,"position":0,"autoplay_enabled":false,"updated_at":"2026-10-24T12:00:00Z"}""") }
    }
}
