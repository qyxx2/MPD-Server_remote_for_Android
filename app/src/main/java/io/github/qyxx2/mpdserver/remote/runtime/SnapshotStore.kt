package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

enum class RuntimeConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }
enum class SnapshotFreshness { UNKNOWN, FRESH, STALE }
enum class SnapshotSource { REALTIME_INITIAL, HTTP_READ }

data class SnapshotStoreState(
    val snapshot: FullStateSnapshot? = null,
    val connectionStatus: RuntimeConnectionStatus = RuntimeConnectionStatus.DISCONNECTED,
    val connectionGeneration: Long = 0,
    val snapshotConnectionGeneration: Long? = null,
    val freshness: SnapshotFreshness = SnapshotFreshness.UNKNOWN,
    val readError: String? = null,
) {
    val controlsEligible: Boolean
        get() = snapshot != null && freshness == SnapshotFreshness.FRESH &&
            connectionStatus == RuntimeConnectionStatus.CONNECTED &&
            snapshotConnectionGeneration == connectionGeneration

    /** Never infer actual playback from playback.state; require explicit, fresh server observation. */
    val actualPlaybackConfirmed: Boolean
        get() {
            val observation = snapshot?.wire?.playbackObservation?.let { runCatching { it.jsonObject }.getOrNull() }
                ?: return false
            fun string(name: String) = observation[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            fun boolean(name: String) = observation[name]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
            val actualState = string("actual_state")
            return actualState in setOf("playing", "paused") &&
                string("freshness") == "fresh" &&
                string("actual_freshness") == "fresh" &&
                boolean("matches_current") == true &&
                !string("bound_queue_item_id").isNullOrBlank() &&
                string("sync_status") in setOf("CONFIRMED", "NO_CANDIDATES")
        }
}

class SnapshotStore(
    private val acceptancePolicy: SnapshotAcceptancePolicy = SnapshotAcceptancePolicy(),
) {
    private val lock = Any()
    private val _state = MutableStateFlow(SnapshotStoreState())
    val state: StateFlow<SnapshotStoreState> = _state.asStateFlow()

    fun onConnectionState(generation: Long, status: RuntimeConnectionStatus) = synchronized(lock) {
        val old = _state.value
        if (generation < old.connectionGeneration) return@synchronized
        val generationChanged = generation > old.connectionGeneration
        _state.value = old.copy(
            connectionStatus = status,
            connectionGeneration = generation,
            freshness = if (generationChanged || status != RuntimeConnectionStatus.CONNECTED) {
                if (old.snapshot == null) SnapshotFreshness.UNKNOWN else SnapshotFreshness.STALE
            } else old.freshness,
            readError = if (generationChanged) null else old.readError,
        )
    }

    fun accept(
        dto: StateSnapshotDto,
        connectionGeneration: Long,
        source: SnapshotSource = SnapshotSource.REALTIME_INITIAL,
    ): Boolean = synchronized(lock) {
        val old = _state.value
        if (connectionGeneration != old.connectionGeneration) return@synchronized false
        if (source == SnapshotSource.HTTP_READ && old.connectionStatus != RuntimeConnectionStatus.CONNECTED) {
            return@synchronized false
        }
        val candidate = try {
            FullStateSnapshot.from(dto)
        } catch (_: Exception) {
            return@synchronized false
        }
        val previous = old.snapshot
        val decision = acceptancePolicy.evaluate(
            previous,
            old.snapshotConnectionGeneration ?: old.connectionGeneration,
            candidate,
            connectionGeneration,
        )
        if (decision != SnapshotRejection.ACCEPTED) return@synchronized false
        _state.value = old.copy(
            snapshot = candidate,
            connectionStatus = if (source == SnapshotSource.REALTIME_INITIAL) {
                RuntimeConnectionStatus.CONNECTED
            } else old.connectionStatus,
            snapshotConnectionGeneration = connectionGeneration,
            freshness = SnapshotFreshness.FRESH,
            readError = null,
        )
        true
    }

    fun markReadFailure(message: String) = synchronized(lock) {
        val old = _state.value
        _state.value = old.copy(
            freshness = if (old.snapshot == null) SnapshotFreshness.UNKNOWN else SnapshotFreshness.STALE,
            readError = message.ifBlank { "State snapshot read failed" },
        )
    }
}
