package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.network.RealtimeSocket
import io.github.qyxx2.mpdserver.remote.network.RealtimeSocketFactory
import io.github.qyxx2.mpdserver.remote.network.RealtimeSocketListener
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.dto.RealtimeFrameDecoder
import io.github.qyxx2.mpdserver.remote.protocol.dto.RealtimeWireEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import kotlin.math.min
import kotlin.random.Random

sealed interface RealtimeConnectionPhase {
    data object Stopped : RealtimeConnectionPhase
    data object Connecting : RealtimeConnectionPhase
    data object AwaitingSnapshot : RealtimeConnectionPhase
    data object Ready : RealtimeConnectionPhase
    data class Disconnected(val reason: String) : RealtimeConnectionPhase
}

data class RealtimeConnectionState(val generation: Long, val phase: RealtimeConnectionPhase) {
    val isReady: Boolean get() = phase is RealtimeConnectionPhase.Ready
}

/** Owns one reconnect loop. It emits wire events only; canonical state remains another layer's responsibility. */
class RealtimeConnection(
    private val origin: ServerOrigin,
    private val socketFactory: RealtimeSocketFactory,
    private val decoder: RealtimeFrameDecoder = RealtimeFrameDecoder(),
    private val initialSnapshotTimeoutMillis: Long = 10_000,
    private val backoff: (attempt: Int) -> Long = { attempt -> min(30_000L, 500L * (1L shl attempt.coerceIn(0, 6))) },
    private val jitter: (Long) -> Long = { upper -> if (upper <= 1) upper else Random.nextLong(upper / 2, upper + 1) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable {
    private val lock = Any()
    private var generation = 0L
    private var socketGeneration = 0L
    private var currentSocket: RealtimeSocket? = null
    private var loop: Job? = null
    private val _state = MutableStateFlow(RealtimeConnectionState(0, RealtimeConnectionPhase.Stopped))
    val state: StateFlow<RealtimeConnectionState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<RealtimeWireEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<RealtimeWireEvent> = _events.asSharedFlow()

    fun start() {
        synchronized(lock) {
            if (loop?.isActive == true) return
            generation++
            val ownerGeneration = generation
            _state.value = RealtimeConnectionState(ownerGeneration, RealtimeConnectionPhase.Connecting)
            loop = scope.launch { runLoop(ownerGeneration) }
        }
    }

    suspend fun stop() {
        val oldLoop: Job?
        synchronized(lock) {
            generation++
            oldLoop = loop
            loop = null
            currentSocket?.cancel()
            currentSocket = null
            _state.value = RealtimeConnectionState(generation, RealtimeConnectionPhase.Stopped)
        }
        oldLoop?.cancelAndJoin()
    }

    override fun close() {
        scope.launch { stop() }
    }

    private suspend fun runLoop(ownerGeneration: Long) {
        var attempt = 0
        while (scope.coroutineContext[Job]?.isActive == true && isCurrent(ownerGeneration)) {
            val opened = CompletableDeferred<Unit>()
            val snapshot = CompletableDeferred<RealtimeWireEvent.Snapshot>()
            val ended = CompletableDeferred<String>()
            var acceptedFirstFrame = false
            val socketToken = synchronized(lock) { ++socketGeneration }
            var socket: RealtimeSocket? = null
            setPhase(ownerGeneration, RealtimeConnectionPhase.Connecting)
            try {
                socket = socketFactory.connect(origin.webSocketUrl("/api/realtime"), object : RealtimeSocketListener {
                    override fun onOpen() { if (isCurrent(ownerGeneration) && isCurrentSocket(socketToken)) opened.complete(Unit) }
                    override fun onMessage(text: String) {
                        if (!isCurrent(ownerGeneration) || !isCurrentSocket(socketToken)) return
                        try {
                            val event = decoder.decode(text)
                            if (!acceptedFirstFrame) {
                                if (event !is RealtimeWireEvent.Snapshot) throw IllegalStateException("First realtime frame must be a complete snapshot")
                                acceptedFirstFrame = true
                                snapshot.complete(event)
                            } else if (event is RealtimeWireEvent.Snapshot) {
                                throw IllegalStateException("Snapshot is only permitted as the first frame on a connection")
                            }
                            if (acceptedFirstFrame) _events.tryEmit(event)
                        } catch (error: Throwable) {
                            invalidateSocket(socketToken)
                            ended.complete("protocol error: ${error.message ?: error::class.simpleName}")
                        }
                    }
                    override fun onClosed() { if (isCurrent(ownerGeneration) && isCurrentSocket(socketToken)) { invalidateSocket(socketToken); ended.complete("socket closed") } }
                    override fun onFailure(error: Throwable) { if (isCurrent(ownerGeneration) && isCurrentSocket(socketToken)) { invalidateSocket(socketToken); ended.complete(error.message ?: "socket failure") } }
                })
                synchronized(lock) {
                    if (isCurrent(ownerGeneration) && isCurrentSocket(socketToken)) currentSocket = socket else socket.cancel()
                }
                val first = withTimeout(initialSnapshotTimeoutMillis) {
                    opened.await()
                    setPhase(ownerGeneration, RealtimeConnectionPhase.AwaitingSnapshot)
                    kotlinx.coroutines.selects.select<RealtimeWireEvent.Snapshot> {
                        snapshot.onAwait { it }
                        ended.onAwait { throw IllegalStateException(it) }
                    }
                }
                if (first.epoch.isBlank()) throw IllegalStateException("snapshot epoch is blank")
                setPhase(ownerGeneration, RealtimeConnectionPhase.Ready)
                attempt = 0
                val reason = ended.await()
                setPhase(ownerGeneration, RealtimeConnectionPhase.Disconnected(reason))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isCurrent(ownerGeneration)) setPhase(ownerGeneration, RealtimeConnectionPhase.Disconnected(error.message ?: "connection failed"))
            } finally {
                synchronized(lock) {
                    if (socketGeneration == socketToken && currentSocket === socket) currentSocket = null
                }
                socket?.cancel()
            }
            if (isCurrent(ownerGeneration)) {
                val wait = jitter(backoff(attempt.coerceAtMost(10))).coerceAtLeast(0)
                attempt = (attempt + 1).coerceAtMost(10)
                delay(wait)
            }
        }
    }

    private fun isCurrent(ownerGeneration: Long): Boolean = synchronized(lock) { generation == ownerGeneration }

    private fun isCurrentSocket(token: Long): Boolean = synchronized(lock) { socketGeneration == token }

    private fun invalidateSocket(token: Long) {
        synchronized(lock) {
            if (socketGeneration == token) {
                socketGeneration++
                currentSocket?.cancel()
                currentSocket = null
            }
        }
    }

    private fun setPhase(ownerGeneration: Long, phase: RealtimeConnectionPhase) {
        synchronized(lock) {
            if (generation == ownerGeneration) _state.value = RealtimeConnectionState(ownerGeneration, phase)
        }
    }
}
