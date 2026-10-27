package io.github.qyxx2.mpdserver.remote.ui.player

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateRevisionsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import io.github.qyxx2.mpdserver.remote.runtime.FullStateSnapshot
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState

internal object PlayerTestFixtures {
    private fun fixture(name: String): String =
        PlayerTestFixtures::class.java.classLoader!!.getResource("fixtures/task4/$name")!!.readText()

    val songJson: String get() = fixture("song-response.json")
    val queueJson: String get() = fixture("queue-snapshot.json")
    val observationJson: String get() = fixture("playback-observation.json")
    val outputJson: String get() = fixture("output-snapshot.json")
    val outputObservationJson: String get() = fixture("output-observation.json")

    fun state(
        song: String? = songJson,
        queue: String = queueJson,
        observation: String = observationJson,
        playback: String = """{"song_id":"song-1","state":"PLAYING","context":null,"position":12.5,"autoplay_enabled":false,"updated_at":"2026-10-27T10:00:00Z"}""",
        output: String = outputJson,
        outputObservation: String = outputObservationJson,
        freshness: SnapshotFreshness = SnapshotFreshness.FRESH,
        connectionStatus: RuntimeConnectionStatus = RuntimeConnectionStatus.CONNECTED,
        connectionGeneration: Long = 1,
        snapshotConnectionGeneration: Long? = 1,
    ): SnapshotStoreState {
        val json = JsonConfiguration.json
        val dto = StateSnapshotDto(
            epoch = "epoch-1",
            sequence = 10,
            capturedAt = "2026-10-27T10:00:01Z",
            explicitStopGeneration = 0,
            revisions = StateRevisionsDto(library = 7, playlist = 3),
            playback = json.parseToJsonElement(playback),
            currentSong = song?.let(json::parseToJsonElement),
            queue = json.parseToJsonElement(queue),
            history = json.parseToJsonElement("{}"),
            playbackObservation = json.parseToJsonElement(observation),
            output = json.parseToJsonElement(output),
            outputObservation = json.parseToJsonElement(outputObservation),
            librarySync = json.parseToJsonElement("""{"generation":2}"""),
        )
        return SnapshotStoreState(
            snapshot = FullStateSnapshot.from(dto),
            connectionStatus = connectionStatus,
            connectionGeneration = connectionGeneration,
            snapshotConnectionGeneration = snapshotConnectionGeneration,
            freshness = freshness,
        )
    }
}
