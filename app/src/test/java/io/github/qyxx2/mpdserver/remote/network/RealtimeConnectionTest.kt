package io.github.qyxx2.mpdserver.remote.network

import io.github.qyxx2.mpdserver.remote.protocol.dto.RealtimeWireEvent
import io.github.qyxx2.mpdserver.remote.runtime.RealtimeConnection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeConnectionTest {
    @Test fun connectionIsNotReadyUntilInitialSnapshotAndOldConnectionEventsAreIgnored() = runBlocking {
        val factory = FakeSocketFactory()
        val connection = RealtimeConnection(
            origin = ServerOrigin.parse("http://music.example:18000"),
            socketFactory = factory,
            initialSnapshotTimeoutMillis = 1_000,
            backoff = { 5L },
            jitter = { it },
        )
        connection.start()
        try {
            val firstSocket = factory.awaitSocket(0)
            assertFalse(connection.state.value.isReady)
            firstSocket.open()
            val firstEvent = async(start = CoroutineStart.UNDISPATCHED) { connection.events.first { it is RealtimeWireEvent.Snapshot } }
            firstSocket.message(snapshot("e1", 1))
            firstEvent.await()
            connection.state.first { it.isReady }
            assertTrue(connection.state.value.isReady)
            firstSocket.fail()
            val secondSocket = factory.awaitSocket(1)
            assertFalse(connection.state.value.isReady)
            val lateOldEvent = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(50) { connection.events.first { it is RealtimeWireEvent.Snapshot && it.epoch == "old" } } }
            firstSocket.message(snapshot("old", 99))
            assertNull(lateOldEvent.await())
            assertFalse(connection.state.value.isReady)
            secondSocket.open()
            val secondEvent = async(start = CoroutineStart.UNDISPATCHED) { connection.events.first { it is RealtimeWireEvent.Snapshot && it.epoch == "e2" } }
            secondSocket.message(snapshot("e2", 1))
            secondEvent.await()
            connection.state.first { it.isReady }
            assertTrue(connection.state.value.isReady)
            assertEquals(2, factory.sockets.size)
        } finally {
            connection.stop()
        }
    }

    @Test fun serverVersionedInvalidateKeepsTheRealtimeConnectionReady() = runBlocking {
        val factory = FakeSocketFactory()
        val connection = RealtimeConnection(
            origin = ServerOrigin.parse("http://music.example:18000"),
            socketFactory = factory,
            initialSnapshotTimeoutMillis = 1_000,
            backoff = { 5L },
            jitter = { it },
        )
        connection.start()
        try {
            val socket = factory.awaitSocket(0)
            socket.open()
            val initialSnapshot = async(start = CoroutineStart.UNDISPATCHED) {
                connection.events.first { it is RealtimeWireEvent.Snapshot }
            }
            socket.message(snapshot("e1", 1))
            initialSnapshot.await()
            connection.state.first { it.isReady }

            val invalidate = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(500) {
                    connection.events.first { it is RealtimeWireEvent.Invalidate }
                }
            }
            // This is the exact subsequent frame emitted by the current MS server.
            socket.message("""{"type":"invalidate","protocol_version":1,"epoch":"e1","sequence":2,"domains":["playback"],"revisions":{"library":0,"playlist":0}}""")

            val event = invalidate.await()
            assertTrue("versioned invalidate must be decoded, not treated as a protocol error", event is RealtimeWireEvent.Invalidate)
            assertTrue("valid invalidate must not trigger a WebSocket reconnect", connection.state.value.isReady)
            assertEquals(1, factory.sockets.size)
        } finally {
            connection.stop()
        }
    }

    @Test fun invalidateCannotBeTheFirstFrame() = runBlocking {
        val factory = FakeSocketFactory()
        val connection = RealtimeConnection(ServerOrigin.parse("http://music.example:18000"), factory, initialSnapshotTimeoutMillis = 1_000, backoff = { 5L }, jitter = { it })
        connection.start()
        try {
            val firstSocket = factory.awaitSocket(0)
            firstSocket.open()
            firstSocket.message("""{"type":"invalidate","protocol_version":1,"epoch":"e1","sequence":1,"domains":["playback"],"revisions":{"library":0,"playlist":0}}""")
            val secondSocket = factory.awaitSocket(1)
            assertFalse(connection.state.value.isReady)
            assertTrue(secondSocket !== firstSocket)
        } finally {
            connection.stop()
        }
    }

    @Test fun cancellationClosesActiveSocket() = runBlocking {
        val factory = FakeSocketFactory()
        val connection = RealtimeConnection(ServerOrigin.parse("https://music.example"), factory, backoff = { 5L }, jitter = { it })
        connection.start()
        val socket = factory.awaitSocket(0)
        connection.stop()
        assertTrue(socket.closed)
    }

    private fun snapshot(epoch: String, sequence: Long) = """{"type":"snapshot","protocol_version":1,"epoch":"$epoch","sequence":$sequence,"state":{"epoch":"$epoch","sequence":$sequence,"captured_at":"2026-10-24T12:00:00Z","explicit_stop_generation":0,"revisions":{"library":0,"playlist":0},"playback":{},"current_song":null,"queue":{},"history":{},"playback_observation":{},"output":{},"output_observation":{},"library_sync":{}}}"""
}

private class FakeSocketFactory : RealtimeSocketFactory {
    val sockets = mutableListOf<FakeSocket>()
    override fun connect(url: String, listener: RealtimeSocketListener): RealtimeSocket {
        val socket = FakeSocket(listener)
        synchronized(sockets) { sockets += socket }
        return socket
    }
    suspend fun awaitSocket(index: Int): FakeSocket {
        repeat(100) {
            synchronized(sockets) { if (sockets.size > index) return sockets[index] }
            delay(5)
        }
        error("socket $index was not created")
    }
}

private class FakeSocket(private val listener: RealtimeSocketListener) : RealtimeSocket {
    var closed = false
    override fun close() { closed = true; listener.onClosed() }
    override fun cancel() { closed = true }
    fun open() = listener.onOpen()
    fun message(value: String) = listener.onMessage(value)
    fun fail() = listener.onFailure(IllegalStateException("disconnected"))
}
