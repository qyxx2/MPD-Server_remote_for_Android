package io.github.qyxx2.mpdserver.remote.protocol.dto

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StateSnapshotDtoTest {
    @Test fun explicitNullCurrentSongIsPreserved() {
        val dto = JsonConfiguration.json.decodeFromString<StateSnapshotDto>(completeSnapshot)
        assertNull(dto.currentSong)
    }

    @Test fun missingCurrentSongDoesNotBecomeNull() {
        val missing = completeSnapshot.replace("\"current_song\":null,", "")
        assertThrows(SerializationException::class.java) {
            JsonConfiguration.json.decodeFromString<StateSnapshotDto>(missing)
        }
    }

    private val completeSnapshot = """{
        "epoch":"epoch-a",
        "sequence":1,
        "captured_at":"2026-10-24T12:00:00Z",
        "explicit_stop_generation":0,
        "revisions":{"library":0,"playlist":0},
        "playback":{},
        "current_song":null,
        "queue":{},
        "history":{},
        "playback_observation":{},
        "output":{},
        "output_observation":{},
        "library_sync":{}
    }"""
}
