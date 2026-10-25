package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.network.RealtimeSocketFactory
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.RealtimeWireEvent
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** Application-scoped bridge from one realtime connection to the canonical snapshot/read coordinator. */
class RealtimeController(
    initialOrigin: ServerOrigin,
    private val snapshots: SnapshotStore,
    private val socketFactory: RealtimeSocketFactory,
    private val readState: suspend (ServerOrigin) -> ApiResult<StateSnapshotDto>,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectionFactory: (ServerOrigin, CoroutineScope) -> RealtimeConnection = { origin, ownerScope ->
        RealtimeConnection(origin, socketFactory, scope = ownerScope)
    },
) : Closeable {
    private val lock = Any()
    private val nextGeneration = AtomicLong(0)
    private val _generation = MutableStateFlow(0L)
    val generation: StateFlow<Long> = _generation.asStateFlow()
    private var origin = initialOrigin
    private var connection: RealtimeConnection? = null
    private var connectionStateJob: Job? = null
    private var eventJob: Job? = null
    private var started = false
    private val readCoordinator = StateReadCoordinator(
        store = snapshots,
        scope = scope,
        connectionGeneration = { _generation.value },
        readState = { readState(synchronized(lock) { origin }) },
        dispatcher = dispatcher,
    )

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            attachConnectionLocked(origin)
        }
    }

    suspend fun stop() {
        val oldConnection: RealtimeConnection?
        val stateJob: Job?
        val eventsJob: Job?
        synchronized(lock) {
            if (!started && connection == null) return
            started = false
            _generation.value = nextGeneration.incrementAndGet()
            snapshots.onConnectionState(_generation.value, RuntimeConnectionStatus.DISCONNECTED)
            oldConnection = connection
            connection = null
            stateJob = connectionStateJob
            eventsJob = eventJob
            connectionStateJob = null
            eventJob = null
        }
        stateJob?.cancelAndJoin()
        eventsJob?.cancelAndJoin()
        oldConnection?.stop()
    }

    suspend fun changeOrigin(newOrigin: ServerOrigin) {
        val oldConnection: RealtimeConnection?
        val stateJob: Job?
        val eventsJob: Job?
        synchronized(lock) {
            if (origin == newOrigin) return
            origin = newOrigin
            _generation.value = nextGeneration.incrementAndGet()
            snapshots.onConnectionState(
                _generation.value,
                if (started) RuntimeConnectionStatus.CONNECTING else RuntimeConnectionStatus.DISCONNECTED,
            )
            oldConnection = connection
            connection = null
            stateJob = connectionStateJob
            eventsJob = eventJob
            connectionStateJob = null
            eventJob = null
            if (started) attachConnectionLocked(newOrigin)
        }
        stateJob?.cancelAndJoin()
        eventsJob?.cancelAndJoin()
        oldConnection?.stop()
    }

    override fun close() {
        // Cleanup must outlive cancellation of the application-owned runtime scope.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { stop() }
    }

    private fun attachConnectionLocked(targetOrigin: ServerOrigin) {
        val ownerGeneration = nextGeneration.incrementAndGet()
        _generation.value = ownerGeneration
        snapshots.onConnectionState(ownerGeneration, RuntimeConnectionStatus.CONNECTING)
        val newConnection = connectionFactory(targetOrigin, scope)
        connection = newConnection
        connectionStateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            newConnection.state.collect { state ->
                if (!isCurrent(ownerGeneration, newConnection)) return@collect
                val status = when (state.phase) {
                    RealtimeConnectionPhase.Ready -> RuntimeConnectionStatus.CONNECTED
                    RealtimeConnectionPhase.Stopped, is RealtimeConnectionPhase.Disconnected -> RuntimeConnectionStatus.DISCONNECTED
                    RealtimeConnectionPhase.Connecting, RealtimeConnectionPhase.AwaitingSnapshot -> RuntimeConnectionStatus.CONNECTING
                }
                snapshots.onConnectionState(ownerGeneration, status)
            }
        }
        eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            newConnection.events.collect { event ->
                if (!isCurrent(ownerGeneration, newConnection)) return@collect
                when (event) {
                    is RealtimeWireEvent.Snapshot -> snapshots.accept(event.state, ownerGeneration, SnapshotSource.REALTIME_INITIAL)
                    is RealtimeWireEvent.Invalidate -> {
                        readCoordinator.onInvalidate(
                            SnapshotWatermark(event.epoch, event.sequence, event.revisions),
                            ownerGeneration,
                        )
                    }
                }
            }
        }
        newConnection.start()
    }

    private fun isCurrent(ownerGeneration: Long, candidate: RealtimeConnection): Boolean = synchronized(lock) {
        started && _generation.value == ownerGeneration && connection === candidate
    }
}
