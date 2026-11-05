package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareVolumeOverlayPresentationTest {
    @Test
    fun queuedKeyPreviewIsShownAsPendingWithoutChangingTheCanonicalSnapshot() {
        val store = freshStore(72)
        val command = HardwareVolumeCommandState(
            phase = HardwareVolumeCommandPhase.IN_FLIGHT,
            activeTargetPercent = 73,
            previewVolumePercent = 74,
        )

        val presentation = HardwareVolumeOverlayPresentation.from(store.state.value, command)

        assertEquals("74", presentation.valueText)
        assertTrue(presentation.pending)
        assertEquals("调整中", presentation.statusText)
        assertEquals(72, HardwareVolumeCapabilityPolicy.evaluate(store.state.value).controllableVolumePercent)
    }

    @Test
    fun disconnectedStateFallsBackToLastKnownValueAndNeverDisplaysAnUnconfirmedTarget() {
        val store = freshStore(72)
        store.onConnectionState(2, RuntimeConnectionStatus.CONNECTING)
        val command = HardwareVolumeCommandState(
            phase = HardwareVolumeCommandPhase.IN_FLIGHT,
            activeTargetPercent = 73,
            previewVolumePercent = 74,
        )

        val presentation = HardwareVolumeOverlayPresentation.from(store.state.value, command)

        assertEquals("72", presentation.valueText)
        assertFalse(presentation.pending)
        assertEquals("未连接", presentation.statusText)
    }

    @Test
    fun unknownVolumeValueUsesDashAndNeverConvertsNullToZero() {
        val store = SnapshotStore().apply {
            onConnectionState(1, RuntimeConnectionStatus.CONNECTED)
            accept(dto(epoch = "epoch-null", sequence = 1, volume = "null"), 1, SnapshotSource.REALTIME_INITIAL)
        }

        val presentation = HardwareVolumeOverlayPresentation.from(
            store.state.value,
            HardwareVolumeCommandState(phase = HardwareVolumeCommandPhase.IDLE),
        )

        assertEquals("—", presentation.valueText)
        assertFalse(presentation.pending)
        assertEquals("未知", presentation.statusText)
    }

    private fun freshStore(volume: Int): SnapshotStore = SnapshotStore().apply {
        onConnectionState(1, RuntimeConnectionStatus.CONNECTED)
        assertTrue(accept(dto("epoch-1", 1, volume.toString()), 1, SnapshotSource.REALTIME_INITIAL))
    }

    private fun dto(epoch: String, sequence: Long, volume: String): StateSnapshotDto =
        JsonConfiguration.json.decodeFromString(
            """{"epoch":"$epoch","sequence":$sequence,"captured_at":"2026-11-04T12:00:00Z","explicit_stop_generation":0,"revisions":{"library":0,"playlist":0},"playback":{},"current_song":null,"queue":{"revision":0,"items":[]},"history":{},"playback_observation":{},"output":{},"output_observation":{},"hardware_volume":{"state":{"status":"AVAILABLE","volume_percent":$volume,"output_name":"USB DAC","output_plugin":"alsa","output_enabled":true,"control_source":"MPD_HARDWARE_MIXER","error_code":null,"error_message":null,"updated_at":"2026-11-04T12:00:00Z","stale":false},"last_request":null},"hardware_volume_observation":{"observed_at":"2026-11-04T12:00:00Z","freshness":"fresh","error_code":null,"error_message":null},"library_sync":{"generation":0}}""",
        )
}
