package io.github.qyxx2.mpdserver.remote.protocol

import io.github.qyxx2.mpdserver.remote.protocol.dto.RealtimeFrameDecoder
import io.github.qyxx2.mpdserver.remote.protocol.dto.RealtimeWireEvent
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeFrameDecoderTest {
    private val decoder = RealtimeFrameDecoder()

    @Test fun requiresCompleteV1SnapshotFirst() {
        assertThrows(SerializationException::class.java) { decoder.decode(snapshot.replace(",\"state\":$state", "")) }
        val decoded = decoder.decode(snapshot)
        assertTrue(decoded is RealtimeWireEvent.Snapshot)
    }

    @Test fun rejectsUnsupportedVersion() {
        assertThrows(SerializationException::class.java) { decoder.decode(snapshot.replace("\"protocol_version\":1", "\"protocol_version\":2")) }
    }

    @Test fun rejectsOuterInnerWatermarkMismatch() {
        assertThrows(SerializationException::class.java) { decoder.decode(snapshot.replaceFirst("\"sequence\":7", "\"sequence\":8")) }
        assertThrows(SerializationException::class.java) { decoder.decode(snapshot.replaceFirst("\"epoch\":\"e1\"", "\"epoch\":\"e2\"")) }
    }

    @Test fun decodedVersionedInvalidateIsNotPatch() {
        val decoded = decoder.decode("""{"type":"invalidate","protocol_version":1,"epoch":"e1","sequence":8,"domains":["playback"],"revisions":{"library":2,"playlist":3}}""")
        assertTrue(decoded is RealtimeWireEvent.Invalidate)
        assertEquals(8L, (decoded as RealtimeWireEvent.Invalidate).sequence)
        assertEquals(listOf("playback"), decoded.domains)
    }

    @Test fun rejectsUnsupportedInvalidateVersion() {
        assertThrows(SerializationException::class.java) {
            decoder.decode("""{"type":"invalidate","protocol_version":2,"epoch":"e1","sequence":8,"domains":["playback"],"revisions":{"library":2,"playlist":3}}""")
        }
    }

    @Test fun requiresInvalidateProtocolVersion() {
        assertThrows(SerializationException::class.java) {
            decoder.decode("""{"type":"invalidate","epoch":"e1","sequence":8,"domains":["playback"],"revisions":{"library":2,"playlist":3}}""")
        }
    }

    @Test fun rejectsMissingRequiredInvalidateFields() {
        assertThrows(SerializationException::class.java) {
            decoder.decode("""{"type":"invalidate","protocol_version":1,"epoch":"e1","sequence":8,"domains":["playback"]}""")
        }
    }

    private val state = """{"epoch":"e1","sequence":7,"captured_at":"2026-10-24T12:00:00Z","explicit_stop_generation":0,"revisions":{"library":2,"playlist":3},"playback":{},"current_song":null,"queue":{},"history":{},"playback_observation":{},"output":{},"output_observation":{},"library_sync":{}}"""
    private val snapshot = """{"type":"snapshot","protocol_version":1,"epoch":"e1","sequence":7,"state":$state}"""
}
