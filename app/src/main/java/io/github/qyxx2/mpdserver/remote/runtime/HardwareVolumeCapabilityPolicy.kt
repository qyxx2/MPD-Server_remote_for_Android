package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.protocol.dto.HardwareVolumeStatusDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.ObservationFreshnessDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.decodeHardwareVolumeResources

enum class HardwareVolumeCapabilityStatus { UNKNOWN, AVAILABLE, UNAVAILABLE, UNSUPPORTED }

enum class HardwareVolumeCapabilityDiagnostic {
    NO_SNAPSHOT,
    SNAPSHOT_NOT_FRESH,
    CONNECTION_NOT_CONNECTED,
    SNAPSHOT_GENERATION_MISMATCH,
    HARDWARE_VOLUME_MISSING_OR_INVALID,
    HARDWARE_VOLUME_NOT_AVAILABLE,
    HARDWARE_VOLUME_STATE_STALE,
    HARDWARE_VOLUME_VALUE_UNKNOWN,
    HARDWARE_VOLUME_OBSERVATION_MISSING_OR_INVALID,
    HARDWARE_VOLUME_OBSERVATION_NOT_FRESH,
}

/**
 * UI-safe projection of the server-owned volume sample. displayVolumePercent may be last-known;
 * controllableVolumePercent is populated only when the whole shared snapshot is currently writable.
 */
data class HardwareVolumeCanonicalTuple(
    val epoch: String,
    val connectionGeneration: Long,
    val snapshotConnectionGeneration: Long?,
    val connectionStatus: RuntimeConnectionStatus,
    val snapshotFreshness: SnapshotFreshness,
    val status: HardwareVolumeCapabilityStatus,
    val volumePercent: Int?,
    val stale: Boolean?,
    val observationFreshness: ObservationFreshnessDto?,
)

data class HardwareVolumeCapability(
    val status: HardwareVolumeCapabilityStatus,
    val displayVolumePercent: Int?,
    val controllableVolumePercent: Int?,
    val canWrite: Boolean,
    val diagnostics: Set<HardwareVolumeCapabilityDiagnostic>,
    /** Decoder details are diagnostic only and never become a control or display value. */
    val decodeDiagnostics: List<String>,
    /** Volume-only identity used to invalidate an in-progress local Slider gesture. */
    val canonicalTuple: HardwareVolumeCanonicalTuple? = null,
)

object HardwareVolumeCapabilityPolicy {
    fun evaluate(state: SnapshotStoreState): HardwareVolumeCapability {
        val diagnostics = linkedSetOf<HardwareVolumeCapabilityDiagnostic>()
        val snapshot = state.snapshot
        if (snapshot == null) {
            diagnostics += HardwareVolumeCapabilityDiagnostic.NO_SNAPSHOT
            diagnostics += HardwareVolumeCapabilityDiagnostic.HARDWARE_VOLUME_MISSING_OR_INVALID
            diagnostics += HardwareVolumeCapabilityDiagnostic.HARDWARE_VOLUME_OBSERVATION_MISSING_OR_INVALID
            return HardwareVolumeCapability(
                status = HardwareVolumeCapabilityStatus.UNKNOWN,
                displayVolumePercent = null,
                controllableVolumePercent = null,
                canWrite = false,
                diagnostics = diagnostics,
                decodeDiagnostics = emptyList(),
            )
        }

        if (state.freshness != SnapshotFreshness.FRESH) {
            diagnostics += HardwareVolumeCapabilityDiagnostic.SNAPSHOT_NOT_FRESH
        }
        if (state.connectionStatus != RuntimeConnectionStatus.CONNECTED) {
            diagnostics += HardwareVolumeCapabilityDiagnostic.CONNECTION_NOT_CONNECTED
        }
        if (state.snapshotConnectionGeneration != state.connectionGeneration) {
            diagnostics += HardwareVolumeCapabilityDiagnostic.SNAPSHOT_GENERATION_MISMATCH
        }

        val resources = snapshot.wire.decodeHardwareVolumeResources()
        val hardwareState = resources.hardwareVolume?.state
        val observation = resources.hardwareVolumeObservation
        val status = when (hardwareState?.status) {
            HardwareVolumeStatusDto.AVAILABLE -> HardwareVolumeCapabilityStatus.AVAILABLE
            HardwareVolumeStatusDto.UNAVAILABLE -> HardwareVolumeCapabilityStatus.UNAVAILABLE
            HardwareVolumeStatusDto.UNSUPPORTED -> HardwareVolumeCapabilityStatus.UNSUPPORTED
            null -> HardwareVolumeCapabilityStatus.UNKNOWN
        }
        if (hardwareState == null) {
            diagnostics += HardwareVolumeCapabilityDiagnostic.HARDWARE_VOLUME_MISSING_OR_INVALID
        }
        if (status != HardwareVolumeCapabilityStatus.AVAILABLE) {
            diagnostics += HardwareVolumeCapabilityDiagnostic.HARDWARE_VOLUME_NOT_AVAILABLE
        }
        if (hardwareState?.stale == true) {
            diagnostics += HardwareVolumeCapabilityDiagnostic.HARDWARE_VOLUME_STATE_STALE
        }
        val volumePercent = hardwareState?.volumePercent
        if (volumePercent == null || volumePercent !in 0..100) {
            diagnostics += HardwareVolumeCapabilityDiagnostic.HARDWARE_VOLUME_VALUE_UNKNOWN
        }
        if (observation == null) {
            diagnostics += HardwareVolumeCapabilityDiagnostic.HARDWARE_VOLUME_OBSERVATION_MISSING_OR_INVALID
        }
        if (observation?.freshness != ObservationFreshnessDto.fresh) {
            diagnostics += HardwareVolumeCapabilityDiagnostic.HARDWARE_VOLUME_OBSERVATION_NOT_FRESH
        }

        val canWrite = state.connectionStatus == RuntimeConnectionStatus.CONNECTED &&
            state.freshness == SnapshotFreshness.FRESH &&
            state.snapshotConnectionGeneration == state.connectionGeneration &&
            status == HardwareVolumeCapabilityStatus.AVAILABLE &&
            hardwareState?.stale == false &&
            observation?.freshness == ObservationFreshnessDto.fresh &&
            volumePercent != null && volumePercent in 0..100

        return HardwareVolumeCapability(
            status = status,
            displayVolumePercent = volumePercent,
            controllableVolumePercent = volumePercent.takeIf { canWrite },
            canWrite = canWrite,
            diagnostics = diagnostics,
            decodeDiagnostics = listOfNotNull(
                resources.hardwareVolumeDiagnostic,
                resources.hardwareVolumeObservationDiagnostic,
            ),
            canonicalTuple = HardwareVolumeCanonicalTuple(
                epoch = snapshot.epoch,
                connectionGeneration = state.connectionGeneration,
                snapshotConnectionGeneration = state.snapshotConnectionGeneration,
                connectionStatus = state.connectionStatus,
                snapshotFreshness = state.freshness,
                status = status,
                volumePercent = volumePercent,
                stale = hardwareState?.stale,
                observationFreshness = observation?.freshness,
            ),
        )
    }
}
