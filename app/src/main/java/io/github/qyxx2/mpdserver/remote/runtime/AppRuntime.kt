package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.data.StateRepository
import io.github.qyxx2.mpdserver.remote.network.HttpTransport
import io.github.qyxx2.mpdserver.remote.network.OkHttpRealtimeSocketFactory
import io.github.qyxx2.mpdserver.remote.network.RealtimeSocketFactory
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.Closeable
import java.util.concurrent.TimeUnit

/** One process-local composition root. Screens and ViewModels receive this owner, never create their own. */
class AppRuntime private constructor(
    initialOrigin: ServerOrigin,
    private val httpClient: OkHttpClient,
    socketFactory: RealtimeSocketFactory,
    private val scope: CoroutineScope,
    stateReader: suspend (ServerOrigin) -> io.github.qyxx2.mpdserver.remote.protocol.ApiResult<io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto>,
) : Closeable {
    val snapshots = SnapshotStore()
    val realtimeController = RealtimeController(
        initialOrigin = initialOrigin,
        snapshots = snapshots,
        socketFactory = socketFactory,
        readState = stateReader,
        scope = scope,
    )
    /** Shared read-only resource access; origin is resolved from the live runtime for every request. */
    val reads: RuntimeReadApi by lazy { RuntimeReadApi(
        originProvider = { realtimeController.currentOrigin() },
        client = httpClient,
    ) }
    val mutations: MutationCoordinator by lazy { MutationCoordinator(
        origin = initialOrigin,
        send = { request ->
            val transport = HttpTransport(request.origin, httpClient)
            val httpRequest = transport.buildRequest(
                method = request.method,
                path = request.path,
                body = request.payload,
                headers = request.idempotencyKey?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap(),
            )
            transport.execute(httpRequest)
        },
        synchronize = { intentId ->
            val syncOrigin = realtimeController.currentOrigin()
            val syncGeneration = realtimeController.generation.value
            try {
                when (val result = stateReader(syncOrigin)) {
                    is io.github.qyxx2.mpdserver.remote.protocol.ApiResult.Success -> {
                        if (realtimeController.currentOrigin() == syncOrigin &&
                            realtimeController.generation.value == syncGeneration
                        ) {
                            snapshots.accept(result.value, syncGeneration, SnapshotSource.HTTP_READ)
                        } else {
                            mutations.recordSynchronizationFailure(intentId, "Server origin changed during post-write synchronization")
                        }
                    }
                    else -> mutations.recordSynchronizationFailure(intentId, "Authoritative snapshot read after mutation failed")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutations.recordSynchronizationFailure(intentId, error.message ?: "Authoritative snapshot read after mutation failed")
            }
        },
        parentScope = scope,
    ) }

    fun start() = realtimeController.start()

    suspend fun changeOrigin(origin: ServerOrigin) {
        mutations.changeOrigin(origin)
        realtimeController.changeOrigin(origin)
    }

    override fun close() {
        // Stop owners before cancelling their shared scope so socket and collector cleanup can finish.
        CoroutineScope(Dispatchers.IO).launch {
            mutations.close()
            realtimeController.stop()
            scope.cancel()
            httpClient.dispatcher.cancelAll()
            httpClient.connectionPool.evictAll()
        }
    }

    companion object {
        fun create(
            initialOrigin: ServerOrigin,
            httpClient: OkHttpClient = OkHttpClient.Builder()
                .callTimeout(15, TimeUnit.SECONDS)
                .build(),
            socketFactory: RealtimeSocketFactory = OkHttpRealtimeSocketFactory(httpClient),
        ): AppRuntime = AppRuntime(
            initialOrigin = initialOrigin,
            httpClient = httpClient,
            socketFactory = socketFactory,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            stateReader = { origin -> StateRepository(HttpTransport(origin, httpClient)).getState() },
        )

        internal fun createForTesting(
            origin: ServerOrigin,
            socketFactory: RealtimeSocketFactory,
            stateReader: suspend (ServerOrigin) -> io.github.qyxx2.mpdserver.remote.protocol.ApiResult<io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto> = {
                io.github.qyxx2.mpdserver.remote.protocol.ApiResult.ProtocolError("No test state reader configured")
            },
        ): AppRuntime {
            val client = OkHttpClient()
            return AppRuntime(
                initialOrigin = origin,
                httpClient = client,
                socketFactory = socketFactory,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                stateReader = stateReader,
            )
        }
    }
}
