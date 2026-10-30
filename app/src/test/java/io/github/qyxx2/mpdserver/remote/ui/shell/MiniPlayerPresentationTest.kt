package io.github.qyxx2.mpdserver.remote.ui.shell

import io.github.qyxx2.mpdserver.remote.protocol.dto.StateRevisionsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import io.github.qyxx2.mpdserver.remote.runtime.FullStateSnapshot
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.ui.player.ConfirmedPlaybackStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MiniPlayerPresentationTest {
    @Test fun missingSnapshotUsesExplicitNoTrackPlaceholder() {
        val result = MiniPlayerPresentation.from(SnapshotStoreState())
        assertEquals("暂无播放曲目", result.title)
        assertFalse(result.hasTrack)
        assertEquals(ConfirmedPlaybackStatus.UNKNOWN, result.playbackStatus)
        assertEquals("状态未确认", result.statusLabel)
    }

    @Test fun missingCurrentSongNeverInventsTrackMetadata() {
        val result = MiniPlayerPresentation.from(state(null))
        assertEquals("暂无播放曲目", result.title)
        assertFalse(result.hasTrack)
        assertEquals(ConfirmedPlaybackStatus.UNKNOWN, result.playbackStatus)
    }

    @Test fun titleComesFromCanonicalSnapshotAndArtistIsNotProjected() {
        val result = MiniPlayerPresentation.from(state("""{"title":"真实曲名","artist":"不应显示的艺人","file_uri":"music/a.flac"}"""))
        assertEquals("真实曲名", result.title)
        assertTrue(result.hasTrack)
        assertEquals(ConfirmedPlaybackStatus.UNKNOWN, result.playbackStatus)
        assertEquals("状态未确认", result.statusLabel)
    }

    @Test fun blankOrMalformedTitleFallsBackToExplicitPlaceholder() {
        assertEquals("暂无播放曲目", MiniPlayerPresentation.from(state("""{"title":" "}""")).title)
        assertEquals("暂无播放曲目", MiniPlayerPresentation.from(state("not-json")).title)
    }

    @Test fun trackWithoutATitleStillRetainsItsArtworkSlot() {
        val result = MiniPlayerPresentation.from(state("""{"song_id":"song-1","file_uri":"music/a.flac"}"""))
        assertEquals("暂无播放曲目", result.title)
        assertTrue(result.hasTrack)
    }

    @Test fun staleSnapshotRetainsLastKnownTitleButMarksItUnconfirmed() {
        val result = MiniPlayerPresentation.from(state(
            """{"title":"最后已知曲目"}""",
            freshness = SnapshotFreshness.STALE,
            connection = RuntimeConnectionStatus.DISCONNECTED,
        ))
        assertEquals("最后已知曲目", result.title)
        assertEquals(ConfirmedPlaybackStatus.UNKNOWN, result.playbackStatus)
        assertEquals("状态未确认", result.statusLabel)
    }

    @Test fun unknownFreshnessIsNeverPresentedAsConfirmed() {
        val result = MiniPlayerPresentation.from(state(
            """{"title":"待确认曲目"}""",
            freshness = SnapshotFreshness.UNKNOWN,
        ))
        assertEquals(ConfirmedPlaybackStatus.UNKNOWN, result.playbackStatus)
        assertEquals("状态未确认", result.statusLabel)
    }

    @Test fun confirmedPlayingAndPausedObservationsProjectTheCorrectSingleActionIconState() {
        val playing = MiniPlayerPresentation.from(
            io.github.qyxx2.mpdserver.remote.ui.player.PlayerTestFixtures.state(),
        )
        val pausedObservation = io.github.qyxx2.mpdserver.remote.ui.player.PlayerTestFixtures.observationJson
            .replace("\"actual_state\":\"playing\"", "\"actual_state\":\"paused\"")
        val paused = MiniPlayerPresentation.from(
            io.github.qyxx2.mpdserver.remote.ui.player.PlayerTestFixtures.state(observation = pausedObservation),
        )
        assertEquals(ConfirmedPlaybackStatus.PLAYING, playing.playbackStatus)
        assertEquals(ConfirmedPlaybackStatus.PAUSED, paused.playbackStatus)
        assertNull(playing.statusLabel)
        assertNull(paused.statusLabel)
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
