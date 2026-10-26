package io.github.qyxx2.mpdserver.remote.ui.shell

import io.github.qyxx2.mpdserver.remote.protocol.dto.StateRevisionsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import io.github.qyxx2.mpdserver.remote.runtime.FullStateSnapshot
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MiniPlayerPresentationTest {
    @Test fun missingSnapshotUsesExplicitNoTrackPlaceholder() {
        val result = MiniPlayerPresentation.from(SnapshotStoreState())
        assertEquals("暂无播放曲目", result.title)
        assertFalse(result.hasTrack)
        assertEquals("状态未确认", result.statusLabel)
        assertNull(result.artworkUrl)
        assertFalse(result.controlEnabled)
    }

    @Test fun missingCurrentSongNeverInventsTrackMetadata() {
        val result = MiniPlayerPresentation.from(state(null))
        assertEquals("暂无播放曲目", result.title)
        assertFalse(result.hasTrack)
        assertNull(result.artworkUrl)
    }

    @Test fun titleComesFromCanonicalSnapshotAndArtistIsNotProjected() {
        val result = MiniPlayerPresentation.from(state("""{"title":"真实曲名","artist":"不应显示的艺人","file_uri":"music/a.flac"}"""))
        assertEquals("真实曲名", result.title)
        assertTrue(result.hasTrack)
        assertNull(result.artworkUrl)
        assertNull(result.statusLabel)
        assertFalse(result.controlEnabled)
    }

    @Test fun blankOrMalformedTitleFallsBackToExplicitPlaceholder() {
        assertEquals("暂无播放曲目", MiniPlayerPresentation.from(state("""{"title":" "}""")).title)
        assertEquals("暂无播放曲目", MiniPlayerPresentation.from(state("not-json")).title)
    }

    @Test fun staleSnapshotRetainsLastKnownTitleButMarksItUnconfirmed() {
        val result = MiniPlayerPresentation.from(state(
            """{"title":"最后已知曲目"}""",
            freshness = SnapshotFreshness.STALE,
            connection = RuntimeConnectionStatus.DISCONNECTED,
        ))
        assertEquals("最后已知曲目", result.title)
        assertEquals("状态未确认", result.statusLabel)
        assertFalse(result.controlEnabled)
    }

    @Test fun unknownFreshnessIsNeverPresentedAsConfirmed() {
        val result = MiniPlayerPresentation.from(state(
            """{"title":"待确认曲目"}""",
            freshness = SnapshotFreshness.UNKNOWN,
        ))
        assertEquals("状态未确认", result.statusLabel)
        assertFalse(result.controlEnabled)
    }

    @Test fun confirmedPlayingAndPausedObservationsProjectTheCorrectSingleActionIconState() {
        val playing = MiniPlayerPresentation.from(state(
            """{"title":"播放中"}""",
            observation = """{"actual_state":"playing","freshness":"fresh","actual_freshness":"fresh","matches_current":true,"bound_queue_item_id":"q1","sync_status":"CONFIRMED"}""",
        ))
        val paused = MiniPlayerPresentation.from(state(
            """{"title":"已暂停"}""",
            observation = """{"actual_state":"paused","freshness":"fresh","actual_freshness":"fresh","matches_current":true,"bound_queue_item_id":"q2","sync_status":"CONFIRMED"}""",
        ))
        assertTrue(playing.isPlaying)
        assertFalse(paused.isPlaying)
        assertFalse(playing.controlEnabled)
        assertFalse(paused.controlEnabled)
    }

    private fun state(
        currentSong: String?,
        freshness: SnapshotFreshness = SnapshotFreshness.FRESH,
        connection: RuntimeConnectionStatus = RuntimeConnectionStatus.CONNECTED,
        observation: String = "{}",
    ): SnapshotStoreState {
        val dto = StateSnapshotDto(
            epoch = "epoch-1", sequence = 1, capturedAt = "2026-10-26T00:00:00Z",
            explicitStopGeneration = 0, revisions = StateRevisionsDto(library = 0, playlist = 0),
            playback = Json.parseToJsonElement("{}"),
            currentSong = currentSong?.let { Json.parseToJsonElement(it) },
            queue = Json.parseToJsonElement("""{"revision":0}"""),
            history = Json.parseToJsonElement("{}"), playbackObservation = Json.parseToJsonElement(observation),
            output = Json.parseToJsonElement("{}"), outputObservation = Json.parseToJsonElement("{}"),
            librarySync = Json.parseToJsonElement("""{"generation":0}"""),
        )
        return SnapshotStoreState(
            snapshot = FullStateSnapshot.from(dto), connectionStatus = connection,
            connectionGeneration = 1, snapshotConnectionGeneration = 1, freshness = freshness,
        )
    }
}
