package io.github.qyxx2.mpdserver.remote.protocol.dto

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HistoryDtoTest {
    @Test fun historyListDecodesPersistentEventsAndNullableFields() {
        val dto = JsonConfiguration.json.decodeFromString<HistoryListDto>("""{"items":[{"history_id":null,"song_id":"song-1","started_at":"2026-10-31T10:00:00Z","ended_at":null,"reason":null,"session_id":"session-1"}],"count":1}""")
        val event = dto.items.single()
        assertEquals(1, dto.count)
        assertNull(event.historyId)
        assertEquals("song-1", event.songId)
        assertEquals("2026-10-31T10:00:00Z", event.startedAt)
        assertNull(event.endedAt)
        assertNull(event.reason)
    }

    @Test fun historyEnvelopeRequiresItemsAndCountAndEventIdentity() {
        assertThrows(SerializationException::class.java) {
            JsonConfiguration.json.decodeFromString<HistoryListDto>("""{"items":[]}""")
        }
        assertThrows(SerializationException::class.java) {
            JsonConfiguration.json.decodeFromString<HistoryEventDto>("""{"history_id":1,"started_at":"2026-10-31T10:00:00Z","ended_at":null,"reason":null,"session_id":null}""")
        }
    }
}
