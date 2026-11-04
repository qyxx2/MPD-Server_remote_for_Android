package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class HardwareVolumeStatusDto { AVAILABLE, UNAVAILABLE, UNSUPPORTED }

@Serializable
enum class HardwareVolumeControlSourceDto { MPD_HARDWARE_MIXER }

@Serializable
enum class HardwareVolumeRequestStatusDto { PREPARING, SUCCEEDED, FAILED }

/** The server-owned MPD instance hardware-mixer state from GET /api/state. */
@Serializable
data class HardwareVolumeStateDto(
    val status: HardwareVolumeStatusDto,
    @SerialName("volume_percent") val volumePercent: Int?,
    @SerialName("output_name") val outputName: String?,
    @SerialName("output_plugin") val outputPlugin: String?,
    @SerialName("output_enabled") val outputEnabled: Boolean?,
    @SerialName("control_source") val controlSource: HardwareVolumeControlSourceDto?,
    @SerialName("error_code") val errorCode: String?,
    @SerialName("error_message") val errorMessage: String?,
    @SerialName("updated_at") val updatedAt: String,
    val stale: Boolean,
) {
    init {
        require(volumePercent == null || volumePercent in 0..100) {
            "hardware_volume.state.volume_percent must be between 0 and 100"
        }
    }
}

@Serializable
data class HardwareVolumeLastRequestDto(
    @SerialName("requested_percent") val requestedPercent: Int,
    val status: HardwareVolumeRequestStatusDto,
    @SerialName("error_code") val errorCode: String?,
    @SerialName("error_message") val errorMessage: String?,
    @SerialName("updated_at") val updatedAt: String,
) {
    init {
        require(requestedPercent in 0..100) {
            "hardware_volume.last_request.requested_percent must be between 0 and 100"
        }
    }
}

@Serializable
data class HardwareVolumeSnapshotDto(
    val state: HardwareVolumeStateDto,
    @SerialName("last_request") val lastRequest: HardwareVolumeLastRequestDto?,
)

/** Independent observation freshness for the same hardware-volume sample. */
@Serializable
data class HardwareVolumeObservationDto(
    @SerialName("observed_at") val observedAt: String?,
    val freshness: ObservationFreshnessDto,
    @SerialName("error_code") val errorCode: String?,
    @SerialName("error_message") val errorMessage: String?,
)

/** A local decode result; malformed optional volume data must not poison the playback snapshot. */
data class DecodedHardwareVolumeResources(
    val hardwareVolume: HardwareVolumeSnapshotDto?,
    val hardwareVolumeObservation: HardwareVolumeObservationDto?,
    val hardwareVolumeDiagnostic: String?,
    val hardwareVolumeObservationDiagnostic: String?,
)
