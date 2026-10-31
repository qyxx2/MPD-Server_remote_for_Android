package io.github.qyxx2.mpdserver.remote

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackStateDto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackDtoTest {
    @Test fun decodesCanonicalPlaybackContextAndPositionFields() {
        val dto = JsonConfiguration.json.decodeFromString<PlaybackStateDto>("""{"song_id":"song-1","state":"PLAYING","playback_context_id":"context-1","position_seconds":12.5,"autoplay_enabled":true,"updated_at":"2026-10-31T10:00:00Z"}""")
        assertEquals("song-1", dto.songId)
        assertEquals("context-1", dto.playbackContextId)
        assertEquals(12.5, dto.positionSeconds!!, 0.0)
        assertEquals(true, dto.autoplayEnabled)
    }

    @Test fun nullableCanonicalPlaybackFieldsMustBePresentEvenWhenNull() {
        val dto = JsonConfiguration.json.decodeFromString<PlaybackStateDto>("""{"song_id":null,"state":"PAUSED","playback_context_id":null,"position_seconds":null,"autoplay_enabled":false,"updated_at":"2026-10-24T12:00:00Z"}""")
        assertNull(dto.songId)
        assertNull(dto.playbackContextId)
        assertNull(dto.positionSeconds)
    }

    @Test fun rejectsLegacyPlaybackShapeAndMissingRequiredFields() {
        assertThrows(SerializationException::class.java) {
            JsonConfiguration.json.decodeFromString<PlaybackStateDto>("""{"song_id":null,"state":"PAUSED","context":null,"position":0,"autoplay_enabled":false,"updated_at":"2026-10-24T12:00:00Z"}""")
        }
        assertThrows(SerializationException::class.java) {
            JsonConfiguration.json.decodeFromString<PlaybackStateDto>("""{"song_id":null,"state":"PAUSED","playback_context_id":null,"autoplay_enabled":false,"updated_at":"2026-10-24T12:00:00Z"}""")
        }
    }

    @Test fun rejectsUnknownPlaybackEnum() {
        assertThrows(SerializationException::class.java) {
            JsonConfiguration.json.decodeFromString<PlaybackStateDto>("""{"song_id":null,"state":"BUFFERING","playback_context_id":null,"position_seconds":null,"autoplay_enabled":false,"updated_at":"2026-10-24T12:00:00Z"}""")
        }
    }
}
