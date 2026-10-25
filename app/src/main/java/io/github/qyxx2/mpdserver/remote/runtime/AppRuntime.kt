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

    fun start() = realtimeController.start()

    suspend fun changeOrigin(origin: ServerOrigin) = realtimeController.changeOrigin(origin)

    override fun close() {
        // Stop owners before cancelling their shared scope so socket and collector cleanup can finish.
        CoroutineScope(Dispatchers.IO).launch {
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
