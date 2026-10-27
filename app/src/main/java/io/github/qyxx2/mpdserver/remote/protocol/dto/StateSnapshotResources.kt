package io.github.qyxx2.mpdserver.remote.protocol.dto

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement

/** Typed projections of resource-bearing fields inside the canonical snapshot wire document. */
data class StateSnapshotResources(
    val currentSong: SongDto?,
    val queue: QueueSnapshotDto,
    val playbackObservation: PlaybackObservationDto,
    val output: OutputSnapshotDto,
    val outputObservation: OutputObservationDto,
)

fun StateSnapshotDto.decodeResources(): StateSnapshotResources = StateSnapshotResources(
    currentSong = if (currentSong == null || currentSong == JsonNull) null else JsonConfiguration.json.decodeFromJsonElement<SongDto>(currentSong),
    queue = JsonConfiguration.json.decodeFromJsonElement<QueueSnapshotDto>(queue),
    playbackObservation = JsonConfiguration.json.decodeFromJsonElement<PlaybackObservationDto>(playbackObservation),
    output = JsonConfiguration.json.decodeFromJsonElement<OutputSnapshotDto>(output),
    outputObservation = JsonConfiguration.json.decodeFromJsonElement<OutputObservationDto>(outputObservation),
)
