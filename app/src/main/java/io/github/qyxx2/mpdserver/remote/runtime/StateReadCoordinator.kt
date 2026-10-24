package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateRevisionsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.StateFlow

data class SnapshotWatermark(
    val epoch: String,
    val sequence: Long,
    val revisions: StateRevisionsDto,
) {
    init {
        require(epoch.isNotBlank() && sequence >= 0)
        require(revisions.library >= 0 && revisions.playlist >= 0)
    }
}

sealed interface StateReadResult {
    data class Accepted(val snapshot: FullStateSnapshot) : StateReadResult
    data class Rejected(val reason: String) : StateReadResult
    data class Failed(val reason: String) : StateReadResult
    data object IgnoredStaleConnection : StateReadResult
}

/** Coalesces reads and repeats the authoritative GET if an invalidate watermark advances in flight. */
class StateReadCoordinator(
    private val store: SnapshotStore,
    private val scope: CoroutineScope,
    private val connectionGeneration: () -> Long,
    private val readState: suspend () -> ApiResult<StateSnapshotDto>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private var inFlight: Deferred<StateReadResult>? = null
    private var requiredWatermark: SnapshotWatermark? = null
    private var requiredWatermarkGeneration: Long? = null

    val state: StateFlow<SnapshotStoreState> get() = store.state

    suspend fun read(): StateReadResult = sharedRead().await()

    suspend fun onInvalidate(watermark: SnapshotWatermark, eventConnectionGeneration: Long): StateReadResult {
        synchronized(lock) {
            if (eventConnectionGeneration != connectionGeneration()) return StateReadResult.IgnoredStaleConnection
            val current = store.state.value.snapshot
            if (current != null && current.epoch != watermark.epoch) return StateReadResult.IgnoredStaleConnection
            if (current != null && watermark.sequence < current.sequence) return StateReadResult.IgnoredStaleConnection
            if (current != null && watermark.sequence == current.sequence &&
                current.libraryRevision >= watermark.revisions.library &&
                current.playlistRevision >= watermark.revisions.playlist
            ) return StateReadResult.IgnoredStaleConnection
            val old = requiredWatermark
            requiredWatermark = when {
                requiredWatermarkGeneration != eventConnectionGeneration || old == null || old.epoch != watermark.epoch -> watermark
                else -> SnapshotWatermark(
                    old.epoch,
                    maxOf(old.sequence, watermark.sequence),
                    StateRevisionsDto(
                        maxOf(old.revisions.library, watermark.revisions.library),
                        maxOf(old.revisions.playlist, watermark.revisions.playlist),
                    ),
                )
            }
            requiredWatermarkGeneration = eventConnectionGeneration
        }
        while (true) {
            val result = sharedRead().await()
            if (result !is StateReadResult.Accepted || result.snapshot.covers(watermark) ||
                eventConnectionGeneration != connectionGeneration()
            ) return result
        }
    }

    private fun sharedRead(): Deferred<StateReadResult> = synchronized(lock) {
        inFlight?.takeIf { !it.isCompleted } ?: scope.async(dispatcher, start = CoroutineStart.LAZY) {
            readUntilCovered()
        }.also { created ->
            inFlight = created
            created.invokeOnCompletion {
                synchronized(lock) { if (inFlight === created) inFlight = null }
            }
            created.start()
        }
    }

    private suspend fun readUntilCovered(): StateReadResult {
        while (true) {
            val generation = connectionGeneration()
            synchronized(lock) {
                if (requiredWatermarkGeneration != null && requiredWatermarkGeneration != generation) {
                    requiredWatermark = null
                    requiredWatermarkGeneration = null
                }
            }
            val result = try {
                readState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                store.markReadFailure(error.message ?: "State snapshot read failed")
                return StateReadResult.Failed(error.message ?: "State snapshot read failed")
            }
            if (generation != connectionGeneration()) continue
            when (result) {
                is ApiResult.Success -> {
                    try {
                        FullStateSnapshot.from(result.value)
                    } catch (error: Exception) {
                        val reason = error.message ?: "State snapshot is incomplete or invalid"
                        store.markReadFailure(reason)
                        return StateReadResult.Rejected(reason)
                    }
                    val accepted = store.accept(result.value, generation, SnapshotSource.HTTP_READ)
                    val current = store.state.value.snapshot
                    if (current == null) {
                        val reason = "No valid canonical snapshot has been accepted"
                        store.markReadFailure(reason)
                        return StateReadResult.Rejected(reason)
                    }
                    val watermark = synchronized(lock) { requiredWatermark }
                    if (watermark != null && current.covers(watermark)) {
                        return StateReadResult.Accepted(current)
                    }
                    if (watermark != null) {
                        if (!accepted && current.epoch != watermark.epoch) {
                            val reason = "Snapshot epoch does not match the active invalidate watermark"
                            store.markReadFailure(reason)
                            return StateReadResult.Rejected(reason)
                        }
                        continue
                    }
                    return if (accepted) {
                        StateReadResult.Accepted(current)
                    } else {
                        StateReadResult.Rejected("Snapshot was rejected by the canonical acceptance policy")
                    }
                }
                else -> {
                    val reason = result.failureDescription()
                    store.markReadFailure(reason)
                    return StateReadResult.Failed(reason)
                }
            }
        }
    }

    private fun ApiResult<*>.failureDescription(): String = when (this) {
        is ApiResult.BusinessError -> "HTTP $statusCode: ${error.code}"
        is ApiResult.HttpError -> "HTTP $statusCode: $message"
        is ApiResult.TransportError -> failure.cause.message ?: "State snapshot transport failed"
        is ApiResult.DecodeError -> message
        is ApiResult.ProtocolError -> message
        ApiResult.NoContent -> "State snapshot endpoint returned 204 without a snapshot"
        is ApiResult.Accepted -> "State snapshot endpoint returned 202 without a snapshot"
        is ApiResult.Success<*> -> "Unexpected state snapshot result"
    }
}
