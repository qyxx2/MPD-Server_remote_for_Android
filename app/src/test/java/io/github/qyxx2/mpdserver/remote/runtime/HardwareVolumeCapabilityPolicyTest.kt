package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareVolumeCapabilityPolicyTest {
    @Test
    fun onlyCompleteFreshAvailableSnapshotIsWritableAcrossEligibilityMatrix() {
        val statuses = listOf("AVAILABLE", "UNAVAILABLE", "UNSUPPORTED")
        val observationFreshness = listOf("fresh", "stale", "unknown")
        val snapshotFreshness = SnapshotFreshness.values().toList()
        val connectionStatuses = RuntimeConnectionStatus.values().toList()
        val generations = listOf(1L to 1L, 1L to 2L)
        val stateStaleValues = listOf(false, true)
        val volumeValues = listOf(80, null)

        for (status in statuses) for (observation in observationFreshness)
            for (snapshotFreshnessValue in snapshotFreshness) for (connectionStatus in connectionStatuses)
                for ((connectionGeneration, snapshotGeneration) in generations)
                    for (stateStale in stateStaleValues) for (volume in volumeValues) {
                        val state = state(
                            status = status,
                            observationFreshness = observation,
                            stateStale = stateStale,
                            volumePercent = volume,
                            snapshotFreshness = snapshotFreshnessValue,
                            connectionStatus = connectionStatus,
                            connectionGeneration = connectionGeneration,
                            snapshotConnectionGeneration = snapshotGeneration,
                        )
                        val result = HardwareVolumeCapabilityPolicy.evaluate(state)
                        val expected = status == "AVAILABLE" &&
                            observation == "fresh" &&
                            !stateStale &&
                            volume != null &&
                            snapshotFreshnessValue == SnapshotFreshness.FRESH &&
                            connectionStatus == RuntimeConnectionStatus.CONNECTED &&
                            connectionGeneration == snapshotGeneration
                        assertEquals(
                            "Unexpected eligibility: status=$status observation=$observation snapshot=$snapshotFreshnessValue " +
                                "connection=$connectionStatus generations=$connectionGeneration/$snapshotGeneration " +
                                "stale=$stateStale volume=$volume",
                            expected,
                            result.canWrite,
                        )
                    }
    }

    @Test
    fun lastKnownValueRemainsDisplayOnlyWhenSnapshotIsStale() {
        val result = HardwareVolumeCapabilityPolicy.evaluate(
            state(snapshotFreshness = SnapshotFreshness.STALE, stateStale = true),
        )

        assertEquals(80, result.displayVolumePercent)
        assertNull(result.controllableVolumePercent)
        assertFalse(result.canWrite)
    }

    @Test
    fun hardwareVolumeEligibilityDoesNotDependOnCurrentSongOrPlaybackState() {
        val result = HardwareVolumeCapabilityPolicy.evaluate(
            state(currentSong = null, playback = """{"state":"STOPPED"}""", queueItems = "[]"),
        )

        assertTrue(result.canWrite)
        assertEquals(80, result.controllableVolumePercent)
    }

    @Test
    fun unknownFutureStatusFailsClosedWithoutBreakingSnapshot() {
        val state = state(status = "FUTURE_STATUS")
        val result = HardwareVolumeCapabilityPolicy.evaluate(state)

        assertEquals(HardwareVolumeCapabilityStatus.UNKNOWN, result.status)
        assertNull(result.displayVolumePercent)
        assertFalse(result.canWrite)
        assertTrue(result.diagnostics.isNotEmpty())
        assertTrue(result.decodeDiagnostics.isNotEmpty())
        assertEquals(4L, state.snapshot?.queueRevision)
    }

    @Test
    fun missingOptionalHardwareVolumeFieldsProjectToUnknown() {
        val dto = JsonConfiguration.json.decodeFromString<StateSnapshotDto>(snapshotJson())
        val state = SnapshotStoreState(
            snapshot = FullStateSnapshot.from(dto),
            connectionStatus = RuntimeConnectionStatus.CONNECTED,
            connectionGeneration = 1,
            snapshotConnectionGeneration = 1,
            freshness = SnapshotFreshness.FRESH,
        )

        val result = HardwareVolumeCapabilityPolicy.evaluate(state)
        assertEquals(HardwareVolumeCapabilityStatus.UNKNOWN, result.status)
        assertFalse(result.canWrite)
        assertNull(result.displayVolumePercent)
    }

    private fun state(
        status: String = "AVAILABLE",
        observationFreshness: String = "fresh",
        stateStale: Boolean = false,
        volumePercent: Int? = 80,
        snapshotFreshness: SnapshotFreshness = SnapshotFreshness.FRESH,
        connectionStatus: RuntimeConnectionStatus = RuntimeConnectionStatus.CONNECTED,
        connectionGeneration: Long = 1,
        snapshotConnectionGeneration: Long = 1,
        currentSong: String? = "null",
        playback: String = "{}",
        queueItems: String = "[]",
    ): SnapshotStoreState {
        val volume = """{
            "state": {
                "status": "$status",
                "volume_percent": ${volumePercent ?: "null"},
                "output_name": "USB DAC",
                "output_plugin": "alsa",
                "output_enabled": true,
                "control_source": "MPD_HARDWARE_MIXER",
                "error_code": null,
                "error_message": null,
                "updated_at": "2026-11-04T10:00:00Z",
                "stale": $stateStale
            },
            "last_request": null
        }"""
        val observation = """{
            "observed_at": "2026-11-04T10:00:00Z",
            "freshness": "$observationFreshness",
            "error_code": null,
            "error_message": null
        }"""
        val dto = JsonConfiguration.json.decodeFromString<StateSnapshotDto>(
            snapshotJson(volume, observation, currentSong, playback, queueItems),
        )
        return SnapshotStoreState(
            snapshot = FullStateSnapshot.from(dto),
            connectionStatus = connectionStatus,
            connectionGeneration = connectionGeneration,
            snapshotConnectionGeneration = snapshotConnectionGeneration,
            freshness = snapshotFreshness,
        )
    }

    private fun snapshotJson(
        volume: String? = null,
        observation: String? = null,
        currentSong: String? = "null",
        playback: String = "{}",
        queueItems: String = "[]",
    ): String = buildString {
        append("""{
            "epoch":"epoch-a",
            "sequence":1,
            "captured_at":"2026-11-04T10:00:00Z",
            "explicit_stop_generation":0,
            "revisions":{"library":0,"playlist":0},
            "playback":$playback,
            "current_song":$currentSong,
            "queue":{"revision":4,"items":$queueItems},
            "history":{},
            "playback_observation":{},
            "output":{},
            "output_observation":{},
            "library_sync":{"generation":2}""")
        volume?.let { append(",\"hardware_volume\":$it") }
        observation?.let { append(",\"hardware_volume_observation\":$it") }
        append("}")
    }
}
