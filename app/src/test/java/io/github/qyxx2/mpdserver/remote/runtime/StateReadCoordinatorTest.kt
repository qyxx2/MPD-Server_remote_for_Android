package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateRevisionsDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class StateReadCoordinatorTest {
    @Test fun coalescesConcurrentReads() = runBlocking {
        val calls = AtomicInteger()
        val response = CompletableDeferred<ApiResult<StateSnapshotDto>>()
        val store = SnapshotStore().apply {
            onConnectionState(1, RuntimeConnectionStatus.CONNECTING)
            accept(snapshot(0), 1)
        }
        val coordinator = StateReadCoordinator(
            store, CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined),
            connectionGeneration = { 1L },
            readState = { calls.incrementAndGet(); response.await() },
        )
        val first = async { coordinator.read() }
        val second = async { coordinator.read() }
        while (calls.get() == 0) kotlinx.coroutines.yield()
        response.complete(ApiResult.Success(snapshot(1), 200))
        first.await()
        second.await()
        assertEquals(1, calls.get())
        assertEquals(1L, store.state.value.snapshot?.sequence)
    }

    @Test fun rereadsWhenWatermarkAdvancesDuringGet() = runBlocking {
        val calls = AtomicInteger()
        val firstResponse = CompletableDeferred<ApiResult<StateSnapshotDto>>()
        val store = SnapshotStore().apply {
            onConnectionState(1, RuntimeConnectionStatus.CONNECTING)
            accept(snapshot(0), 1)
        }
        val coordinator = StateReadCoordinator(
            store, CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined),
            connectionGeneration = { 1L },
            readState = {
                when (calls.incrementAndGet()) {
                    1 -> firstResponse.await()
                    else -> ApiResult.Success(snapshot(3), 200)
                }
            },
        )
        val read = async { coordinator.read() }
        while (calls.get() == 0) kotlinx.coroutines.yield()
        val invalidation = async {
            coordinator.onInvalidate(SnapshotWatermark("e1", 3, StateRevisionsDto(4, 6)), 1)
        }
        kotlinx.coroutines.yield()
        firstResponse.complete(ApiResult.Success(snapshot(1), 200))
        read.await()
        invalidation.await()
        assertEquals(2, calls.get())
        assertEquals(3L, store.state.value.snapshot?.sequence)
    }

    @Test fun ignoresOlderInvalidateWatermarkWithoutStartingAnotherRead() = runBlocking {
        val calls = AtomicInteger()
        val store = SnapshotStore().apply {
            onConnectionState(1, RuntimeConnectionStatus.CONNECTING)
            accept(snapshot(3), 1)
        }
        val coordinator = StateReadCoordinator(
            store, CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined),
            connectionGeneration = { 1L },
            readState = { calls.incrementAndGet(); ApiResult.Success(snapshot(4), 200) },
        )
        val result = coordinator.onInvalidate(SnapshotWatermark("e1", 2, StateRevisionsDto(4, 6)), 1)
        assertEquals(StateReadResult.IgnoredStaleConnection, result)
        assertEquals(0, calls.get())
    }

    private fun snapshot(sequence: Long): StateSnapshotDto = StateSnapshotDto(
        "e1", sequence, "2026-10-24T12:00:00Z", 3,
        StateRevisionsDto(4, 6), Json.parseToJsonElement("""{"state":"PLAYING"}"""), null,
        Json.parseToJsonElement("""{"revision":8,"items":[]}"""), Json.parseToJsonElement("""{}"""),
        Json.parseToJsonElement("""{"actual_state":null,"freshness":"unknown"}"""),
        Json.parseToJsonElement("""{}"""), Json.parseToJsonElement("""{}"""),
        Json.parseToJsonElement("""{"generation":5}"""),
    )
}
