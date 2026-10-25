package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.network.HttpFailure
import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiErrorEnvelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import java.io.Closeable
import java.io.IOException
import java.util.UUID

/** A caller-defined immutable user intent; retries never rebuild it from UI state. */
data class MutationIntent(val id: String, val method: String, val path: String, val payload: String?) {
    init {
        require(id.isNotBlank())
        require(method.uppercase() in setOf("POST", "PUT", "PATCH", "DELETE"))
        require(path.startsWith("/api/") && !path.startsWith("//"))
        require(method.uppercase() !in setOf("POST", "PUT", "PATCH") || payload != null)
    }
}

data class MutationRequest(val method: String, val path: String, val payload: String?, val idempotencyKey: String?, val origin: ServerOrigin)
enum class MutationStatus { PENDING, SUCCESS, FAILED, UNKNOWN, SYNCING, INFO }
data class OperationActivity(
    val intentId: String, val label: String, val status: MutationStatus,
    val message: String? = null, val syncError: String? = null,
    val idempotencyKey: String?, val origin: ServerOrigin,
)

sealed interface MutationOutcome {
    data class Confirmed(val statusCode: Int?) : MutationOutcome
    data class Failed(val statusCode: Int?, val message: String) : MutationOutcome
    data class Unknown(val message: String) : MutationOutcome
    data object DuplicatePending : MutationOutcome
    data object BlockedOriginChange : MutationOutcome
    data object NotRetryable : MutationOutcome
}

/** Process-local single owner for mutation identity, retries and shared activity. */
class MutationCoordinator(
    origin: ServerOrigin,
    private val send: (MutationRequest) -> HttpResult,
    private val synchronize: ((String) -> Unit)? = null,
    private val keyFactory: () -> String = { UUID.randomUUID().toString() },
) : Closeable {
    private val lock = Any()
    private var origin = origin
    private val requests = linkedMapOf<String, MutationRequest>()
    private val outcomes = linkedMapOf<String, MutationOutcome>()
    private val _activities = MutableStateFlow<List<OperationActivity>>(emptyList())
    val activities: StateFlow<List<OperationActivity>> = _activities.asStateFlow()

    fun submit(intent: MutationIntent): MutationOutcome = synchronized(lock) {
        val existing = requests[intent.id]
        if (existing != null) {
            if (existing.method != intent.method.uppercase() || existing.path != intent.path || existing.payload != intent.payload) {
                return@synchronized MutationOutcome.Failed(null, "Intent id reused with a different request")
            }
            val activity = _activities.value.firstOrNull { it.intentId == intent.id }
            if (activity?.status == MutationStatus.PENDING || activity?.status == MutationStatus.SYNCING) return@synchronized MutationOutcome.DuplicatePending
            return@synchronized outcomes[intent.id] ?: MutationOutcome.NotRetryable
        }
        val request = MutationRequest(
            method = intent.method.uppercase(),
            path = intent.path,
            payload = intent.payload,
            idempotencyKey = if (intent.method.uppercase() == "POST" && intent.path == "/api/library/collections") null else keyFactory(),
            origin = origin,
        )
        requests[intent.id] = request
        updateActivity(request, intent.id, MutationStatus.PENDING)
        execute(request, intent.id)
    }

    /** Explicit user-driven retry of the same immutable intent, key and origin. */
    fun retryUnknown(intentId: String): MutationOutcome = synchronized(lock) {
        val request = requests[intentId] ?: return@synchronized MutationOutcome.NotRetryable
        if (request.origin != origin) return@synchronized MutationOutcome.BlockedOriginChange
        if (_activities.value.firstOrNull { it.intentId == intentId }?.status != MutationStatus.UNKNOWN) return@synchronized MutationOutcome.NotRetryable
        updateActivity(request, intentId, MutationStatus.PENDING, "Retrying the same intent")
        execute(request, intentId)
    }

    fun changeOrigin(newOrigin: ServerOrigin) = synchronized(lock) {
        if (newOrigin == origin) return@synchronized
        origin = newOrigin
        _activities.value = _activities.value.map { activity ->
            if (activity.origin != newOrigin && activity.status in setOf(MutationStatus.PENDING, MutationStatus.UNKNOWN, MutationStatus.SYNCING))
                activity.copy(status = MutationStatus.UNKNOWN, message = "Unresolved operation belongs to the previous server origin")
            else activity
        }
    }

    override fun close() = Unit // Deliberately no persistence; a new process starts with no old mutation intents.

    private fun execute(request: MutationRequest, intentId: String): MutationOutcome {
        val result = try { send(request) } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            HttpResult.Failed(HttpFailure.Transport(IOException(error.message ?: "Transport outcome unknown", error)))
        }
        val outcome = when (result) {
            is HttpResult.NoContent -> MutationOutcome.Confirmed(result.statusCode)
            is HttpResult.Body -> if (result.statusCode in 200..299) MutationOutcome.Confirmed(result.statusCode)
                else MutationOutcome.Unknown("Unexpected response status ${result.statusCode}")
            is HttpResult.HttpError -> decodeHttpError(result)
            is HttpResult.Failed -> when (result.failure) {
                is HttpFailure.Transport, is HttpFailure.Decode, is HttpFailure.Protocol -> MutationOutcome.Unknown("The server outcome could not be confirmed")
            }
        }
        outcomes[intentId] = outcome
        when (outcome) {
            is MutationOutcome.Confirmed -> {
                updateActivity(request, intentId, MutationStatus.SUCCESS, "Write confirmed")
                try { synchronize?.invoke(intentId) } catch (error: Exception) {
                    recordSynchronizationFailure(intentId, error.message ?: "Follow-up state synchronization failed")
                }
            }
            is MutationOutcome.Failed -> updateActivity(request, intentId, MutationStatus.FAILED, outcome.message)
            is MutationOutcome.Unknown -> updateActivity(request, intentId, MutationStatus.UNKNOWN, outcome.message)
            else -> Unit
        }
        return outcome
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

    private fun updateActivity(request: MutationRequest, id: String, status: MutationStatus, message: String? = null) {
        replaceActivity(id) { OperationActivity(id, id, status, message, it.syncError, request.idempotencyKey, request.origin) }
    }

    private fun replaceActivity(id: String, transform: (OperationActivity) -> OperationActivity) {
        val old = _activities.value
        _activities.value = if (old.any { it.intentId == id }) old.map { if (it.intentId == id) transform(it) else it }
        else old + transform(OperationActivity(id, id, MutationStatus.PENDING, idempotencyKey = null, origin = origin))
    }

    fun recordSynchronizationFailure(intentId: String, message: String) = synchronized(lock) {
        replaceActivity(intentId) { it.copy(syncError = message.ifBlank { "Follow-up state synchronization failed" }) }
    }

    internal fun beginPendingForTesting(intent: MutationIntent) = synchronized(lock) {
        val request = MutationRequest(intent.method.uppercase(), intent.path, intent.payload, keyFactory(), origin)
        requests[intent.id] = request
        updateActivity(request, intent.id, MutationStatus.PENDING)
    }
}
