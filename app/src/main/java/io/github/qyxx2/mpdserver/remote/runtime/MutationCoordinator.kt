package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.network.HttpFailure
import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiErrorEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.decodeFromString
import java.io.Closeable
import java.io.IOException
import java.util.UUID

/** A caller-defined immutable user intent; retries never rebuild it from UI state. */
data class MutationIntent(val id: String, val method: String, val path: String, val payload: String?, val label: String? = null) {
    init {
        require(id.isNotBlank())
        require(method.uppercase() in setOf("POST", "PUT", "PATCH", "DELETE"))
        require(path.startsWith("/api/") && !path.startsWith("//"))
        require(method.uppercase() !in setOf("POST", "PUT", "PATCH") || payload != null)
    }
}

data class MutationRequest(val method: String, val path: String, val payload: String?, val idempotencyKey: String?, val origin: ServerOrigin, val label: String? = null)
enum class MutationStatus { PENDING, SUCCESS, FAILED, UNKNOWN, SYNCING, INFO }
data class OperationActivity(
    val intentId: String, val label: String, val status: MutationStatus,
    val message: String? = null, val syncError: String? = null,
    val idempotencyKey: String?, val origin: ServerOrigin,
    /** Narrow response metadata needed to establish a seek's authoritative readback watermark. */
    val receiptUpdatedAt: String? = null,
)

sealed interface MutationOutcome {
    data object Submitted : MutationOutcome
    data class Confirmed(val statusCode: Int?, val receiptUpdatedAt: String? = null) : MutationOutcome
    data class Failed(val statusCode: Int?, val message: String) : MutationOutcome
    data class Unknown(val message: String) : MutationOutcome
    data object DuplicatePending : MutationOutcome
    data object BlockedOriginChange : MutationOutcome
    data object NotRetryable : MutationOutcome
}

/** Process-local owner for idempotent writes and activity. Network work runs in its own scope, not on caller/UI threads. */
class MutationCoordinator(
    origin: ServerOrigin,
    private val send: (MutationRequest) -> HttpResult,
    private val synchronize: (suspend (String) -> Unit)? = null,
    parentScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
) : Closeable {
    private val lock = Any()
    private var origin = origin
    private val requests = linkedMapOf<String, MutationRequest>()
    private val outcomes = linkedMapOf<String, MutationOutcome>()
    private val _activities = MutableStateFlow<List<OperationActivity>>(emptyList())
    val activities: StateFlow<List<OperationActivity>> = _activities.asStateFlow()
    private val operationJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val operationScope = CoroutineScope(parentScope.coroutineContext + operationJob)

    fun submit(intent: MutationIntent): MutationOutcome {
        val decision = synchronized(lock) {
            val existing = requests[intent.id]
            if (existing != null) {
                if (existing.method != intent.method.uppercase() || existing.path != intent.path || existing.payload != intent.payload) {
                    return@synchronized Decision.Immediate(MutationOutcome.Failed(null, "Intent id reused with a different request"))
                }
                val status = _activities.value.firstOrNull { it.intentId == intent.id }?.status
                if (status == MutationStatus.PENDING || status == MutationStatus.SYNCING) {
                    return@synchronized Decision.Immediate(MutationOutcome.DuplicatePending)
                }
                return@synchronized Decision.Immediate(outcomes[intent.id] ?: MutationOutcome.NotRetryable)
            }
            val request = MutationRequest(
                method = intent.method.uppercase(),
                path = intent.path,
                payload = intent.payload,
                idempotencyKey = if (intent.method.uppercase() == "POST" && intent.path == "/api/library/collections") null else keyFactory(),
                origin = origin,
                label = intent.label,
            )
            requests[intent.id] = request
            updateActivityLocked(request, intent.id, MutationStatus.PENDING)
            Decision.Execute(request)
        }
        return when (decision) {
            is Decision.Immediate -> decision.outcome
            is Decision.Execute -> {
                operationScope.launch(dispatcher) { execute(decision.request, intent.id) }
                MutationOutcome.Submitted
            }
        }
    }

    /** Explicit user-driven retry of the same immutable intent, key and origin. */
    fun retryUnknown(intentId: String): MutationOutcome {
        val request = synchronized(lock) {
            val existing = requests[intentId] ?: return MutationOutcome.NotRetryable
            if (existing.origin != origin) return MutationOutcome.BlockedOriginChange
            if (_activities.value.firstOrNull { it.intentId == intentId }?.status != MutationStatus.UNKNOWN) {
                return MutationOutcome.NotRetryable
            }
            updateActivityLocked(existing, intentId, MutationStatus.PENDING, "Retrying the same intent")
            existing
        }
        operationScope.launch(dispatcher) { execute(request, intentId) }
        return MutationOutcome.Submitted
    }

    fun changeOrigin(newOrigin: ServerOrigin) = synchronized(lock) {
        if (newOrigin == origin) return@synchronized
        origin = newOrigin
        _activities.value = _activities.value.map { activity ->
            if (activity.origin != newOrigin && activity.status in setOf(MutationStatus.PENDING, MutationStatus.UNKNOWN, MutationStatus.SYNCING)) {
                activity.copy(status = MutationStatus.UNKNOWN, message = "Unresolved operation belongs to the previous server origin")
            } else activity
        }
    }

    override fun close() {
        operationScope.cancel()
    }

    private suspend fun execute(request: MutationRequest, intentId: String) {
        val originStillCurrent = synchronized(lock) {
            if (request.origin != origin) {
                outcomes[intentId] = MutationOutcome.Unknown("Server origin changed before the request was sent")
                updateActivityLocked(request, intentId, MutationStatus.UNKNOWN, "Request was not sent because the server origin changed")
                false
            } else {
                true
            }
        }
        if (!originStillCurrent) return

        val result = try {
            send(request)
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                outcomes[intentId] = MutationOutcome.Unknown("Operation was cancelled before its server outcome could be confirmed")
                updateActivityLocked(request, intentId, MutationStatus.UNKNOWN, "Operation was cancelled; server outcome is unknown")
            }
            throw cancelled
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            HttpResult.Failed(HttpFailure.Transport(IOException(error.message ?: "Transport outcome unknown", error)))
        }
        val outcome = when (result) {
            is HttpResult.NoContent -> MutationOutcome.Confirmed(result.statusCode)
            is HttpResult.Body -> if (result.statusCode in 200..299) {
                MutationOutcome.Confirmed(result.statusCode, extractReceiptUpdatedAt(result.bytes))
            } else MutationOutcome.Unknown("Unexpected response status ${result.statusCode}")
            is HttpResult.HttpError -> decodeHttpError(result)
            is HttpResult.Failed -> when (result.failure) {
                is HttpFailure.Transport, is HttpFailure.Decode, is HttpFailure.Protocol -> MutationOutcome.Unknown("The server outcome could not be confirmed")
            }
        }
        val originStillCurrentAfterSend = synchronized(lock) {
            if (request.origin != origin) {
                when (outcome) {
                    is MutationOutcome.Confirmed -> {
                        // Preserve the write receipt; its readback belongs to the old origin and must not be applied to the new one.
                        outcomes[intentId] = outcome
                        updateActivityLocked(
                            request,
                            intentId,
                            MutationStatus.SUCCESS,
                            "Write confirmed on previous server; synchronization was not applied to the new server",
                        )
                    }
                    is MutationOutcome.Failed -> {
                        outcomes[intentId] = outcome
                        updateActivityLocked(
                            request,
                            intentId,
                            MutationStatus.FAILED,
                            "Operation failed on previous server: ${outcome.message}",
                        )
                    }
                    is MutationOutcome.Unknown -> {
                        outcomes[intentId] = outcome
                        updateActivityLocked(
                            request,
                            intentId,
                            MutationStatus.UNKNOWN,
                            "Server origin changed while the write was in flight; server outcome remains unknown",
                        )
                    }
                    else -> Unit
                }
                false
            } else {
                outcomes[intentId] = outcome
                when (outcome) {
                    is MutationOutcome.Confirmed -> updateActivityLocked(
                        request, intentId, MutationStatus.SUCCESS, "Write confirmed",
                        receiptUpdatedAt = outcome.receiptUpdatedAt,
                    )
                    is MutationOutcome.Failed -> updateActivityLocked(request, intentId, MutationStatus.FAILED, outcome.message)
                    is MutationOutcome.Unknown -> updateActivityLocked(request, intentId, MutationStatus.UNKNOWN, outcome.message)
                    else -> Unit
                }
                true
            }
        }
        if (originStillCurrentAfterSend && outcome is MutationOutcome.Confirmed) {
            try {
                synchronize?.invoke(intentId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                recordSynchronizationFailure(intentId, error.message ?: "Follow-up state synchronization failed")
            }
        }
    }

    private fun decodeHttpError(result: HttpResult.HttpError): MutationOutcome {
        if (result.statusCode >= 500) {
            return MutationOutcome.Unknown("HTTP ${result.statusCode} does not establish whether the write committed")
        }
        if (result.contentType.orEmpty().contains("json", ignoreCase = true)) {
            try {
                val envelope = io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration.json.decodeFromString<ApiErrorEnvelope>(result.bytes.toString(Charsets.UTF_8))
                return MutationOutcome.Failed(result.statusCode, "${envelope.error.code}: ${envelope.error.message}")
            } catch (_: SerializationException) {
                return MutationOutcome.Failed(result.statusCode, "HTTP ${result.statusCode} rejected the request with an invalid error envelope")
            }
        }
        return MutationOutcome.Failed(result.statusCode, "HTTP ${result.statusCode} rejected the request")
    }

    private fun updateActivityLocked(
        request: MutationRequest,
        id: String,
        status: MutationStatus,
        message: String? = null,
        receiptUpdatedAt: String? = null,
    ) {
        replaceActivityLocked(id) {
            OperationActivity(
                intentId = id,
                label = request.label?.takeIf(String::isNotBlank) ?: id,
                status = status,
                message = message,
                syncError = it.syncError,
                idempotencyKey = request.idempotencyKey,
                origin = request.origin,
                receiptUpdatedAt = receiptUpdatedAt ?: it.receiptUpdatedAt,
            )
        }
    }

    private fun extractReceiptUpdatedAt(bytes: ByteArray): String? = runCatching {
        val root = io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration.json
            .parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        root["updated_at"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun replaceActivityLocked(id: String, transform: (OperationActivity) -> OperationActivity) {
        val old = _activities.value
        _activities.value = if (old.any { it.intentId == id }) old.map { if (it.intentId == id) transform(it) else it }
        else old + transform(OperationActivity(id, id, MutationStatus.PENDING, idempotencyKey = null, origin = origin))
    }

    fun recordSynchronizationFailure(intentId: String, message: String) = synchronized(lock) {
        replaceActivityLocked(intentId) { it.copy(syncError = message.ifBlank { "Follow-up state synchronization failed" }) }
    }

    private sealed interface Decision {
        data class Execute(val request: MutationRequest) : Decision
        data class Immediate(val outcome: MutationOutcome) : Decision
    }
}
