package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.network.RealtimeSocket
import io.github.qyxx2.mpdserver.remote.network.RealtimeSocketFactory
import io.github.qyxx2.mpdserver.remote.network.RealtimeSocketListener
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateRevisionsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCompositionTest {
    @Test fun applicationRuntimeExposesOneCanonicalOwnerAcrossConsumers() {
        val runtime = AppRuntime.createForTesting(
            origin = ServerOrigin.parse("http://music.example:18000"),
            socketFactory = RecordingSocketFactory(),
        )
        try {
            val consumerOneStore = runtime.snapshots
            val consumerTwoStore = runtime.snapshots
            assertSame(consumerOneStore, consumerTwoStore)
            assertSame(runtime.realtimeController, runtime.realtimeController)
        } finally {
            runtime.close()
        }
    }

    @Test fun newRuntimeStartsWithUnknownSnapshotInsteadOfPriorRuntimeState() {
        val first = AppRuntime.createForTesting(ServerOrigin.parse("http://music.example:18000"), RecordingSocketFactory())
        val second = AppRuntime.createForTesting(ServerOrigin.parse("http://music.example:18000"), RecordingSocketFactory())
        try {
            assertTrue(first.snapshots !== second.snapshots)
            assertEquals(null, second.snapshots.state.value.snapshot)
            assertEquals(SnapshotFreshness.UNKNOWN, second.snapshots.state.value.freshness)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test fun repeatedStartDoesNotCreateAnotherWebSocketOrStore() = runBlocking {
        val sockets = RecordingSocketFactory()
        val runtime = AppRuntime.createForTesting(ServerOrigin.parse("http://music.example:18000"), sockets)
        try {
            runtime.start()
            sockets.awaitCount(1)
            val store = runtime.snapshots
            runtime.start()
            delay(30)
            assertEquals(1, sockets.urls.size)
            assertSame(store, runtime.snapshots)
            assertEquals(1L, runtime.realtimeController.generation.value)
        } finally {
            runtime.close()
        }
    }

    @Test fun initialSnapshotIsAcceptedIntoTheSingleCanonicalStore() = runBlocking {
        val sockets = RecordingSocketFactory()
        val runtime = AppRuntime.createForTesting(ServerOrigin.parse("http://music.example:18000"), sockets)
        try {
            runtime.start()
            val socket = sockets.awaitSocket(0)
            socket.open()
            socket.message(snapshot("e1", 1))
            runtime.snapshots.state.first { it.snapshot?.sequence == 1L }
            assertEquals("e1", runtime.snapshots.state.value.snapshot?.epoch)
            assertEquals(1L, runtime.snapshots.state.value.snapshot?.sequence)
            assertEquals(SnapshotFreshness.FRESH, runtime.snapshots.state.value.freshness)
            assertTrue(runtime.snapshots.state.value.controlsEligible)
        } finally {
            runtime.close()
        }
    }

    @Test fun invalidateTriggersAuthoritativeSnapshotReadIntoCanonicalStore() = runBlocking {
        val sockets = RecordingSocketFactory()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val runtime = AppRuntime.createForTesting(
            ServerOrigin.parse("http://music.example:18000"),
            sockets,
            stateReader = {
                reads.incrementAndGet()
                ApiResult.Success(snapshotDto("e1", 2), 200)
            },
        )
        try {
            runtime.start()
            val socket = sockets.awaitSocket(0)
            socket.open()
            socket.message(snapshot("e1", 1))
            runtime.snapshots.state.first { it.snapshot?.sequence == 1L }
            socket.message("""{"type":"invalidate","protocol_version":1,"epoch":"e1","sequence":2,"domains":["playback"],"revisions":{"library":0,"playlist":0}}""")
            runtime.snapshots.state.first { it.snapshot?.sequence == 2L }
            assertEquals(1, reads.get())
        } finally {
            runtime.close()
        }
    }

    @Test fun sameOriginReconnectAcceptsNewServerEpochAfterDisconnect() = runBlocking {
        val sockets = RecordingSocketFactory()
        val runtime = AppRuntime.createForTesting(ServerOrigin.parse("http://music.example:18000"), sockets)
        try {
            runtime.start()
            val firstSocket = sockets.awaitSocket(0)
            firstSocket.open()
            firstSocket.message(snapshot("e1", 8))
            runtime.snapshots.state.first { it.snapshot?.epoch == "e1" && it.controlsEligible }

            firstSocket.disconnect()
            runtime.snapshots.state.first {
                it.connectionStatus == RuntimeConnectionStatus.DISCONNECTED &&
                    it.freshness == SnapshotFreshness.STALE && !it.controlsEligible
            }

            val reconnectedSocket = sockets.awaitSocket(1)
            reconnectedSocket.open()
            reconnectedSocket.message(snapshot("e2", 1))
            val recovered = kotlinx.coroutines.withTimeout(2_000) {
                runtime.snapshots.state.first {
                    it.snapshot?.epoch == "e2" && it.freshness == SnapshotFreshness.FRESH && it.controlsEligible
                }
            }
            assertEquals(1L, recovered.snapshot?.sequence)
        } finally {
            runtime.close()
        }
    }

    @Test fun originSwitchInvalidatesOldConnectionAndUsesNewOriginGeneration() = runBlocking {
        val sockets = RecordingSocketFactory()
        val runtime = AppRuntime.createForTesting(ServerOrigin.parse("http://music.example:18000"), sockets)
        try {
            runtime.start()
            val oldSocket = sockets.awaitSocket(0)
            oldSocket.open()
            oldSocket.message(snapshot("e1", 8))
            runtime.snapshots.state.first { it.snapshot?.epoch == "e1" }
            val oldGeneration = runtime.realtimeController.generation.value

            runtime.changeOrigin(ServerOrigin.parse("https://other.example:18443"))
            val newSocket = sockets.awaitSocket(1)
            assertTrue(runtime.realtimeController.generation.value > oldGeneration)
            assertEquals("wss://other.example:18443/api/realtime", sockets.urls.last())
            assertEquals(RuntimeConnectionStatus.CONNECTING, runtime.snapshots.state.value.connectionStatus)
            assertEquals(runtime.realtimeController.generation.value, runtime.snapshots.state.value.connectionGeneration)

            oldSocket.message(snapshot("old-origin", 99))
            newSocket.open()
            newSocket.message(snapshot("e2", 1))
            runtime.snapshots.state.first { it.snapshot?.epoch == "e2" && it.controlsEligible }
            assertEquals("e2", runtime.snapshots.state.value.snapshot?.epoch)
            assertTrue(runtime.snapshots.state.value.controlsEligible)
        } finally {
            runtime.close()
        }
    }

    private fun snapshot(epoch: String, sequence: Long) = """{"type":"snapshot","protocol_version":1,"epoch":"$epoch","sequence":$sequence,"state":{"epoch":"$epoch","sequence":$sequence,"captured_at":"2026-10-24T12:00:00Z","explicit_stop_generation":0,"revisions":{"library":0,"playlist":0},"playback":{},"current_song":null,"queue":{"revision":0,"items":[]},"history":{},"playback_observation":{},"output":{},"output_observation":{},"library_sync":{"generation":0}}}"""
}

private class RecordingSocketFactory : RealtimeSocketFactory {
    val urls = mutableListOf<String>()
    val sockets = mutableListOf<RecordingSocket>()
    override fun connect(url: String, listener: RealtimeSocketListener): RealtimeSocket = synchronized(sockets) {
        urls += url
        RecordingSocket(listener).also { sockets += it }
    }
    suspend fun awaitSocket(index: Int): RecordingSocket {
        repeat(200) {
            synchronized(sockets) { if (sockets.size > index) return sockets[index] }
            delay(5)
        }
        error("socket $index was not created")
    }
    suspend fun awaitCount(count: Int) { awaitSocket(count - 1) }
}

private class RecordingSocket(private val listener: RealtimeSocketListener) : RealtimeSocket {
    override fun close() { listener.onClosed() }
    override fun cancel() = Unit
    fun open() = listener.onOpen()
    fun message(text: String) = listener.onMessage(text)
    fun disconnect() = listener.onClosed()
}

private fun snapshotDto(epoch: String, sequence: Long) = StateSnapshotDto(
    epoch, sequence, "2026-10-24T12:00:00Z", 0, StateRevisionsDto(0, 0),
    Json.parseToJsonElement("{}"), null,
    Json.parseToJsonElement("{\"revision\":0,\"items\":[]}"), Json.parseToJsonElement("{}"),
    Json.parseToJsonElement("{}"), Json.parseToJsonElement("{}"), Json.parseToJsonElement("{}"),
    Json.parseToJsonElement("{\"generation\":0}"),
)
