package io.github.qyxx2.mpdserver.remote.protocol.dto

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Typed projections of resource-bearing fields inside the canonical snapshot wire document. */
data class StateSnapshotResources(
    val playback: PlaybackStateDto,
    val currentSong: SongDto?,
    val queue: QueueSnapshotDto,
    val playbackObservation: PlaybackObservationDto,
    val output: OutputSnapshotDto,
    val outputObservation: OutputObservationDto,
    val hardwareVolume: HardwareVolumeSnapshotDto?,
    val hardwareVolumeObservation: HardwareVolumeObservationDto?,
    val hardwareVolumeDiagnostic: String?,
    val hardwareVolumeObservationDiagnostic: String?,
)

fun StateSnapshotDto.decodeResources(): StateSnapshotResources {
    val hardwareVolume = decodeHardwareVolumeResources()
    return StateSnapshotResources(
        playback = JsonConfiguration.json.decodeFromJsonElement<PlaybackStateDto>(playback),
        currentSong = if (currentSong == null || currentSong == JsonNull) null else JsonConfiguration.json.decodeFromJsonElement<SongDto>(currentSong),
        queue = JsonConfiguration.json.decodeFromJsonElement<QueueSnapshotDto>(queue),
        playbackObservation = JsonConfiguration.json.decodeFromJsonElement<PlaybackObservationDto>(playbackObservation),
        output = JsonConfiguration.json.decodeFromJsonElement<OutputSnapshotDto>(output),
        outputObservation = JsonConfiguration.json.decodeFromJsonElement<OutputObservationDto>(outputObservation),
        hardwareVolume = hardwareVolume.hardwareVolume,
        hardwareVolumeObservation = hardwareVolume.hardwareVolumeObservation,
        hardwareVolumeDiagnostic = hardwareVolume.hardwareVolumeDiagnostic,
        hardwareVolumeObservationDiagnostic = hardwareVolume.hardwareVolumeObservationDiagnostic,
    )
}

/**
 * Decode the optional hardware-volume extension independently from all required snapshot domains.
 * A missing field means an older server; a null or malformed field is retained as a local
 * diagnostic and projected as UNKNOWN by consumers. Neither case rejects the canonical snapshot.
 */
fun StateSnapshotDto.decodeHardwareVolumeResources(): DecodedHardwareVolumeResources {
    val volume = decodeHardwareVolumeSnapshot(hardwareVolume)
    val observation = decodeOptionalResource<HardwareVolumeObservationDto>(
        hardwareVolumeObservation,
        "hardware_volume_observation",
    )
    return DecodedHardwareVolumeResources(
        hardwareVolume = volume.value,
        hardwareVolumeObservation = observation.value,
        hardwareVolumeDiagnostic = volume.diagnostic,
        hardwareVolumeObservationDiagnostic = observation.diagnostic,
    )
}

private data class OptionalResourceDecode<T>(val value: T?, val diagnostic: String?)

private fun decodeHardwareVolumeSnapshot(element: JsonElement?): OptionalResourceDecode<HardwareVolumeSnapshotDto> {
    if (element == null) return OptionalResourceDecode(value = null, diagnostic = "hardware_volume is missing")
    if (element == JsonNull) return OptionalResourceDecode(value = null, diagnostic = "hardware_volume is null")
    return runCatching {
        val root = element.jsonObject
        root.getValue("state").jsonObject.requireNullableStrictInt("volume_percent", "hardware_volume.state")
        (root["last_request"] as? JsonObject)?.requireStrictInt("requested_percent", "hardware_volume.last_request")
        JsonConfiguration.json.decodeFromJsonElement<HardwareVolumeSnapshotDto>(element)
    }.fold(
        onSuccess = { OptionalResourceDecode(value = it, diagnostic = null) },
        onFailure = {
            OptionalResourceDecode(
                value = null,
                diagnostic = "hardware_volume is invalid: ${it.message ?: it::class.simpleName}",
            )
        },
    )
}

private fun JsonObject.requireNullableStrictInt(field: String, owner: String) {
    val value = getValue(field)
    if (value == JsonNull) return
    require(value is JsonPrimitive && !value.isString && value.intOrNull != null) {
        "$owner.$field must be a JSON integer or null"
    }
}

private fun JsonObject.requireStrictInt(field: String, owner: String) {
    val value = getValue(field)
    require(value is JsonPrimitive && !value.isString && value.intOrNull != null) {
        "$owner.$field must be a JSON integer"
    }
}

private inline fun <reified T> decodeOptionalResource(element: JsonElement?, name: String): OptionalResourceDecode<T> {
    if (element == null) return OptionalResourceDecode(value = null, diagnostic = "$name is missing")
    if (element == JsonNull) return OptionalResourceDecode(value = null, diagnostic = "$name is null")
    return runCatching { JsonConfiguration.json.decodeFromJsonElement<T>(element) }
        .fold(
            onSuccess = { OptionalResourceDecode(value = it, diagnostic = null) },
            onFailure = {
                OptionalResourceDecode(
                    value = null,
                    diagnostic = "$name is invalid: ${it.message ?: it::class.simpleName}",
                )
            },
        )
}
