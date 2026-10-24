package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class StateSnapshotDto(
    val epoch: String,
    val sequence: Long,
    @SerialName("captured_at") val capturedAt: String,
    @SerialName("explicit_stop_generation") val explicitStopGeneration: Long,
    val revisions: StateRevisionsDto,
    val playback: JsonElement,
    @SerialName("current_song") val currentSong: JsonElement?,
    val queue: JsonElement,
    val history: JsonElement,
    @SerialName("playback_observation") val playbackObservation: JsonElement,
    val output: JsonElement,
    @SerialName("output_observation") val outputObservation: JsonElement,
    @SerialName("library_sync") val librarySync: JsonElement,
)

@Serializable
data class StateRevisionsDto(
    val library: Long,
    val playlist: Long,
)
