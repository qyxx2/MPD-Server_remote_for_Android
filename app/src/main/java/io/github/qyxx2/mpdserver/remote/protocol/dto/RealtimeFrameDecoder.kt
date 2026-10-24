package io.github.qyxx2.mpdserver.remote.protocol.dto

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

sealed interface RealtimeWireEvent {
    val epoch: String
    val sequence: Long

    data class Snapshot(
        override val epoch: String,
        override val sequence: Long,
        val state: StateSnapshotDto,
    ) : RealtimeWireEvent

    data class Invalidate(
        override val epoch: String,
        override val sequence: Long,
        val domains: List<String>,
        val revisions: StateRevisionsDto,
    ) : RealtimeWireEvent
}

/** Strictly decodes protocol-v1 wire frames; invalidate is a read watermark, never a patch. */
class RealtimeFrameDecoder(private val json: kotlinx.serialization.json.Json = JsonConfiguration.json) {
    fun decode(raw: String): RealtimeWireEvent {
        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (error: Exception) {
            throw SerializationException("Malformed realtime JSON", error)
        }
        val type = root.required<String>("type")
        val epoch = root.required<String>("epoch").also { if (it.isBlank()) throw SerializationException("epoch must not be blank") }
        val sequence = root.required<Long>("sequence").also { if (it < 0) throw SerializationException("sequence must not be negative") }
        return when (type) {
            "snapshot" -> {
                val version = root.required<Int>("protocol_version")
                if (version != 1) throw SerializationException("Unsupported realtime protocol version: $version")
                val stateElement = root["state"] ?: throw SerializationException("Snapshot state is required")
                val state = try { json.decodeFromJsonElement(StateSnapshotDto.serializer(), stateElement) }
                catch (error: Exception) { throw SerializationException("Snapshot state is incomplete or invalid", error) }
                if (state.epoch != epoch || state.sequence != sequence) {
                    throw SerializationException("Outer and inner snapshot watermarks do not match")
                }
                RealtimeWireEvent.Snapshot(epoch, sequence, state)
            }
            "invalidate" -> {
                if (root.containsKey("protocol_version")) throw SerializationException("protocol_version is only valid on the initial snapshot")
                val domains = root.required<List<String>>("domains")
                if (domains.isEmpty() || domains.any { it.isBlank() }) throw SerializationException("Invalidate domains must be non-empty")
                val revisionsElement = root["revisions"] ?: throw SerializationException("Invalidate revisions are required")
                val revisions = try { json.decodeFromJsonElement(StateRevisionsDto.serializer(), revisionsElement) }
                catch (error: Exception) { throw SerializationException("Invalidate revisions are incomplete or invalid", error) }
                RealtimeWireEvent.Invalidate(epoch, sequence, domains, revisions)
            }
            else -> throw SerializationException("Unsupported realtime frame type: $type")
        }
    }

    private inline fun <reified T> kotlinx.serialization.json.JsonObject.required(name: String): T {
        val value: JsonElement = this[name] ?: throw SerializationException("Missing required field: $name")
        return try { json.decodeFromJsonElement(value) }
        catch (error: Exception) { throw SerializationException("Invalid required field: $name", error) }
    }
}
