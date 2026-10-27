package io.github.qyxx2.mpdserver.remote.ui.player

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateRevisionsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import io.github.qyxx2.mpdserver.remote.runtime.FullStateSnapshot
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonObject

internal object PlayerTestFixtures {
    private fun fixture(name: String): String =
        PlayerTestFixtures::class.java.classLoader!!.getResource("fixtures/task4/$name")!!.readText()

    val songJson: String get() = fixture("song-response.json")
    val queueJson: String get() = fixture("queue-snapshot.json")
    val observationJson: String get() = fixture("playback-observation.json")
    val outputJson: String get() = fixture("output-snapshot.json")
    val outputObservationJson: String get() = fixture("output-observation.json")
    private val webSnapshotJson: String get() = fixture("web-player-facts-snapshot.json")
    private fun webField(name: String): String =
        JsonConfiguration.json.parseToJsonElement(webSnapshotJson).jsonObject.getValue(name).toString()

    fun webObservationJsonForTest(): String = webField("playback_observation")
    fun webOutputJsonForTest(): String = webField("output")
    fun webOutputObservationJsonForTest(): String = webField("output_observation")
    fun webOracleCasesJson(): String = fixture("web-player-facts-oracles.json")

    fun webStateFromSnapshot(
        snapshotJson: String,
        writable: Boolean,
    ): SnapshotStoreState {
        val dto = JsonConfiguration.json.decodeFromString<StateSnapshotDto>(snapshotJson)
        return SnapshotStoreState(
            snapshot = FullStateSnapshot.from(dto),
            connectionStatus = if (writable) RuntimeConnectionStatus.CONNECTED else RuntimeConnectionStatus.DISCONNECTED,
            connectionGeneration = 1,
            snapshotConnectionGeneration = 1,
            freshness = if (writable) SnapshotFreshness.FRESH else SnapshotFreshness.STALE,
        )
    }

    /** The exact canonical snapshot fixture used by the Web player-facts invariant suite. */
    fun webState(
        queue: String? = null,
        observation: String? = null,
        output: String? = null,
        outputObservation: String? = null,
        freshness: SnapshotFreshness = SnapshotFreshness.FRESH,
        connectionStatus: RuntimeConnectionStatus = RuntimeConnectionStatus.CONNECTED,
        connectionGeneration: Long = 1,
        snapshotConnectionGeneration: Long? = 1,
    ): SnapshotStoreState {
        val json = JsonConfiguration.json
        val root = json.parseToJsonElement(webSnapshotJson).jsonObject.toMutableMap()
        queue?.let { root["queue"] = json.parseToJsonElement(it) }
        observation?.let { root["playback_observation"] = json.parseToJsonElement(it) }
        output?.let { root["output"] = json.parseToJsonElement(it) }
        outputObservation?.let { root["output_observation"] = json.parseToJsonElement(it) }
        val dto = json.decodeFromJsonElement<StateSnapshotDto>(JsonObject(root))
        return SnapshotStoreState(
            snapshot = FullStateSnapshot.from(dto),
            connectionStatus = connectionStatus,
            connectionGeneration = connectionGeneration,
            snapshotConnectionGeneration = snapshotConnectionGeneration,
            freshness = freshness,
        )
    }

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
