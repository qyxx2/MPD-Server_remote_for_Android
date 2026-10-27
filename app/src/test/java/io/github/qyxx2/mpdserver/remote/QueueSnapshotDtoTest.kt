package io.github.qyxx2.mpdserver.remote

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.*
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class QueueSnapshotDtoTest {
    @Test fun snapshotResourceProjectionKeepsQueueRevisionAndSeparateOutputObservation() {
        val state = """{"epoch":"e","sequence":1,"captured_at":"2026-10-27T10:00:00Z","explicit_stop_generation":0,"revisions":{"library":1,"playlist":1},"playback":{},"current_song":null,"queue":{"revision":17,"items":[{"queue_item_id":"occurrence-1","song_id":"song-1","position":0,"source":"MANUAL","playback_context_id":null}]},"history":{},"playback_observation":{"actual_state":null,"actual_current":null,"actual_freshness":"unknown","bound_queue_item_id":null,"control_target":null,"sync_status":"UNBOUND","matches_current":null,"position_seconds":null,"duration_seconds":null,"covered_playback_updated_at":null,"observed_at":null,"freshness":"unknown","reconciliation_required":false,"error_code":null,"error_message":null},"output":{"states":[],"last_request":null},"output_observation":{"observed_at":null,"freshness":"unknown","error_code":null,"error_message":null},"library_sync":{"generation":0}}"""
        val dto = JsonConfiguration.json.decodeFromString<StateSnapshotDto>(state).decodeResources()
        assertNull(dto.currentSong); assertEquals(17, dto.queue.revision)
        assertEquals("unknown", dto.playbackObservation.freshness.name); assertEquals("unknown", dto.outputObservation.freshness.name)
    }
}
