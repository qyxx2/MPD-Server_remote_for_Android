package io.github.qyxx2.mpdserver.remote.protocol.dto

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.runtime.FullStateSnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareVolumeDtoTest {
    @Test
    fun legacySnapshotWithoutHardwareVolumeFieldsRemainsReadable() {
        val dto = JsonConfiguration.json.decodeFromString<StateSnapshotDto>(snapshotJson())

        assertNull(dto.hardwareVolume)
        assertNull(dto.hardwareVolumeObservation)
        assertEquals(4L, FullStateSnapshot.from(dto).queueRevision)
    }

    @Test
    fun decodesCanonicalHardwareVolumeAndObservationWireShape() {
        val dto = JsonConfiguration.json.decodeFromString<StateSnapshotDto>(
            snapshotJson(hardwareVolume = availableVolume(), observation = freshObservation()),
        )
        val resources = dto.decodeHardwareVolumeResources()

        assertEquals(HardwareVolumeStatusDto.AVAILABLE, resources.hardwareVolume?.state?.status)
        assertEquals(80, resources.hardwareVolume?.state?.volumePercent)
        assertEquals("USB DAC", resources.hardwareVolume?.state?.outputName)
        assertEquals(ObservationFreshnessDto.fresh, resources.hardwareVolumeObservation?.freshness)
        assertNull(resources.hardwareVolumeDiagnostic)
        assertNull(resources.hardwareVolumeObservationDiagnostic)
    }

    @Test
    fun invalidHardwareVolumeStateIsIsolatedAndDiagnosed() {
        val invalidState = availableVolume().replace("AVAILABLE", "FUTURE_STATUS")
        val dto = JsonConfiguration.json.decodeFromString<StateSnapshotDto>(
            snapshotJson(hardwareVolume = invalidState, observation = freshObservation()),
        )

        assertEquals(4L, FullStateSnapshot.from(dto).queueRevision)
        val resources = dto.decodeHardwareVolumeResources()
        assertNull(resources.hardwareVolume)
        assertNotNull(resources.hardwareVolumeDiagnostic)
        assertEquals(ObservationFreshnessDto.fresh, resources.hardwareVolumeObservation?.freshness)
        assertNull(resources.hardwareVolumeObservationDiagnostic)
    }

    @Test
    fun unknownControlSourceEnumIsIsolatedAndDiagnosed() {
        val invalidState = availableVolume().replace("MPD_HARDWARE_MIXER", "FUTURE_MIXER")
        val dto = JsonConfiguration.json.decodeFromString<StateSnapshotDto>(
            snapshotJson(hardwareVolume = invalidState, observation = freshObservation()),
        )

        val resources = dto.decodeHardwareVolumeResources()
        assertNull(resources.hardwareVolume)
        assertNotNull(resources.hardwareVolumeDiagnostic)
        assertEquals(4L, FullStateSnapshot.from(dto).queueRevision)
    }

    @Test
    fun outOfRangeVolumeIsIsolatedAndDiagnosed() {
        val invalidState = availableVolume().replace("\"volume_percent\": 80", "\"volume_percent\": 101")
        val dto = JsonConfiguration.json.decodeFromString<StateSnapshotDto>(
            snapshotJson(hardwareVolume = invalidState, observation = freshObservation()),
        )

        val resources = dto.decodeHardwareVolumeResources()
        assertNull(resources.hardwareVolume)
        assertNotNull(resources.hardwareVolumeDiagnostic)
        assertNotNull(resources.hardwareVolumeObservation)
    }

    @Test
    fun invalidVolumeTypeIsIsolatedAndDiagnosed() {
        val invalidState = availableVolume().replace("\"volume_percent\": 80", "\"volume_percent\": \"80\"")
        val dto = JsonConfiguration.json.decodeFromString<StateSnapshotDto>(
            snapshotJson(hardwareVolume = invalidState, observation = freshObservation()),
        )

        val resources = dto.decodeHardwareVolumeResources()
        assertNull(resources.hardwareVolume)
        assertNotNull(resources.hardwareVolumeDiagnostic)
        assertEquals(4L, FullStateSnapshot.from(dto).queueRevision)
    }

    @Test
    fun invalidObservationDoesNotDiscardValidHardwareVolumeState() {
        val invalidObservation = freshObservation().replace("fresh", "future_freshness")
        val dto = JsonConfiguration.json.decodeFromString<StateSnapshotDto>(
            snapshotJson(hardwareVolume = availableVolume(), observation = invalidObservation),
        )

        val resources = dto.decodeHardwareVolumeResources()
        assertEquals(80, resources.hardwareVolume?.state?.volumePercent)
        assertNull(resources.hardwareVolumeObservation)
        assertNotNull(resources.hardwareVolumeObservationDiagnostic)
    }

    @Test
    fun nullableServerVolumeRemainsNullAndLastRequestIsTyped() {
        val volume = availableVolume()
            .replace("\"volume_percent\": 80", "\"volume_percent\": null")
            .replace("\"last_request\": null", "\"last_request\": {\"requested_percent\": 75, \"status\": \"FAILED\", \"error_code\": \"WRITE_FAILED\", \"error_message\": \"failed\", \"updated_at\": \"2026-11-04T10:00:00Z\"}")
        val dto = JsonConfiguration.json.decodeFromString<StateSnapshotDto>(
            snapshotJson(hardwareVolume = volume, observation = freshObservation()),
        )
        val decoded = dto.decodeHardwareVolumeResources().hardwareVolume

        assertNull(decoded?.state?.volumePercent)
        assertEquals(75, decoded?.lastRequest?.requestedPercent)
        assertEquals(HardwareVolumeRequestStatusDto.FAILED, decoded?.lastRequest?.status)
    }

    @Test
    fun malformedOptionalSubtreeDoesNotBreakExistingTypedSnapshotResources() {
        val fixture = javaClass.classLoader!!.getResource("fixtures/task4/web-player-facts-snapshot.json")!!.readText()
        val root = JsonConfiguration.json.parseToJsonElement(fixture).jsonObject.toMutableMap()
        root["hardware_volume"] = JsonConfiguration.json.parseToJsonElement(
            availableVolume().replace("AVAILABLE", "FUTURE_STATUS"),
        )
        root["hardware_volume_observation"] = JsonConfiguration.json.parseToJsonElement(freshObservation())

        val resources = JsonConfiguration.json.decodeFromJsonElement<StateSnapshotDto>(JsonObject(root)).decodeResources()

        assertNotNull(resources.queue.items)
        assertNotNull(resources.output.states)
        assertNull(resources.hardwareVolume)
        assertNotNull(resources.hardwareVolumeDiagnostic)
        assertEquals(ObservationFreshnessDto.fresh, resources.hardwareVolumeObservation?.freshness)
    }

    private fun availableVolume(): String = """{
        "state": {
            "status": "AVAILABLE",
            "volume_percent": 80,
            "output_name": "USB DAC",
            "output_plugin": "alsa",
            "output_enabled": true,
            "control_source": "MPD_HARDWARE_MIXER",
            "error_code": null,
            "error_message": null,
            "updated_at": "2026-11-04T10:00:00Z",
            "stale": false
        },
        "last_request": null
    }"""

    private fun freshObservation(): String = """{
        "observed_at": "2026-11-04T10:00:00Z",
        "freshness": "fresh",
        "error_code": null,
        "error_message": null
    }"""

    private fun snapshotJson(hardwareVolume: String? = null, observation: String? = null): String = buildString {
        append("""{
            "epoch":"epoch-a",
            "sequence":1,
            "captured_at":"2026-11-04T10:00:00Z",
            "explicit_stop_generation":0,
            "revisions":{"library":0,"playlist":0},
            "playback":{},
            "current_song":null,
            "queue":{"revision":4,"items":[]},
            "history":{},
            "playback_observation":{},
            "output":{},
            "output_observation":{},
            "library_sync":{"generation":2}""")
        hardwareVolume?.let { append(",\"hardware_volume\":$it") }
        observation?.let { append(",\"hardware_volume_observation\":$it") }
        append("}")
    }
}
