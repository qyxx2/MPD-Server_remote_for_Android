package io.github.qyxx2.mpdserver.remote.protocol.dto

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpdStatusDtoTest {
    @Test
    fun mpdStatusDtoDecodesCurrentSystemEndpointShape() {
        val json = javaClass.classLoader!!.getResource("fixtures/task6/mpd-status-response.json")!!.readText()
        val dto = JsonConfiguration.json.decodeFromString<MpdStatusDto>(json)

        assertEquals("0.23.5", dto.version)
        assertEquals("mpd --version", dto.versionSource)
        assertEquals(18L, dto.stats?.artists)
        assertEquals(42L, dto.stats?.albums)
        assertEquals(407L, dto.stats?.songs)
        assertEquals(183845.9, dto.stats?.uptime!!, 0.0)
        assertEquals(987654.75, dto.stats?.dbPlaytime!!, 0.0)
        assertEquals(1793520000L, dto.stats?.dbUpdate)
        assertEquals(54321.5, dto.stats?.playtime!!, 0.0)
        assertEquals(true, dto.databaseUpdateStatus?.updating)
        assertNull(dto.databaseUpdateStatus?.jobId)
        assertEquals(true, dto.connected)
        assertEquals(JsonPrimitive("permission denied"), dto.errors?.get("database"))
        val mpdError = dto.errors?.get("mpd") as JsonObject
        assertEquals(JsonPrimitive("READ_TIMEOUT"), mpdError["code"])
        assertEquals(JsonPrimitive(true), mpdError["retryable"])
        assertEquals("2026-11-01T06:30:00Z", dto.observedAt)
    }

    @Test
    fun mpdStatusDtoPreservesNullAndDynamicErrorFields() {
        val dto = JsonConfiguration.json.decodeFromString<MpdStatusDto>(
            """{"connected":null,"database_update_status":{"updating":null,"job_id":null},"errors":{"new_dynamic_key":{"detail":"disk full"},"other":null}}""",
        )
        assertNull(dto.version)
        assertNull(dto.versionSource)
        assertNull(dto.stats)
        assertNull(dto.connected)
        assertNull(dto.databaseUpdateStatus?.updating)
        assertNull(dto.databaseUpdateStatus?.jobId)
        val dynamicError = dto.errors?.get("new_dynamic_key") as JsonObject
        assertEquals(JsonPrimitive("disk full"), dynamicError["detail"])
        assertEquals(JsonNull, dto.errors?.get("other"))

        val absent = JsonConfiguration.json.decodeFromString<MpdStatusDto>("{}")
        assertNull(absent.version)
        assertNull(absent.stats)
        assertNull(absent.connected)
        assertTrue(absent.errors == null)
    }
}
