package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.network.HttpFailure
import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.*
import org.junit.Test

class MutationCoordinatorTest {
    @Test fun retriesSameIntentWithSameKeyMethodPathAndPayload() = runBlocking {
        val sent = java.util.concurrent.CopyOnWriteArrayList<MutationRequest>()
        val coordinator = coordinator { request -> sent += request; HttpResult.Failed(HttpFailure.Transport(IOException("offline"))) }
        try {
            val intent = MutationIntent("playback-pause", "POST", "/api/playback/pause", """{"target":"current"}""")
            assertEquals(MutationOutcome.Submitted, coordinator.submit(intent))
            coordinator.activities.first { it.singleOrNull()?.status == MutationStatus.UNKNOWN }
            assertEquals(MutationOutcome.Submitted, coordinator.retryUnknown(intent.id))
            coordinator.activities.first { it.singleOrNull()?.status == MutationStatus.UNKNOWN && sent.size == 2 }
            assertEquals(2, sent.size)
            assertEquals(sent[0].idempotencyKey, sent[1].idempotencyKey)
            assertEquals(sent[0].method, sent[1].method)
            assertEquals(sent[0].path, sent[1].path)
            assertEquals(sent[0].payload, sent[1].payload)
        } finally { coordinator.close() }
    }

    @Test fun transportAmbiguityIsUnknownAndNeverAutomaticallyReplayed() = runBlocking {
        val calls = AtomicInteger()
        val coordinator = coordinator { calls.incrementAndGet(); HttpResult.Failed(HttpFailure.Transport(IOException("timeout"))) }
        try {
            assertEquals(MutationOutcome.Submitted, coordinator.submit(intent()))
            val activity = coordinator.activities.first { it.singleOrNull()?.status == MutationStatus.UNKNOWN }.single()
            assertEquals(1, calls.get())
            assertEquals(MutationStatus.UNKNOWN, activity.status)
        } finally { coordinator.close() }
    }

    @Test fun explicitBusinessHttpFailureIsFailedNotUnknown() = runBlocking {
        val coordinator = coordinator { HttpResult.HttpError(409, "application/json", """{"error":{"code":"conflict","message":"not allowed"}}""".toByteArray()) }
        try {
            coordinator.submit(intent())
            assertEquals(MutationStatus.FAILED, coordinator.activities.first { it.singleOrNull()?.status == MutationStatus.FAILED }.single().status)
        } finally { coordinator.close() }
    }

    @Test fun noContentIsSuccessWithoutAttemptingToDecodeBody() = runBlocking {
        val coordinator = coordinator { HttpResult.NoContent() }
        try {
            coordinator.submit(intent())
            assertEquals(MutationStatus.SUCCESS, coordinator.activities.first { it.singleOrNull()?.status == MutationStatus.SUCCESS }.single().status)
        } finally { coordinator.close() }
    }

    @Test fun collectionCreationUsesTheDocumentedIdempotencyException() = runBlocking {
        var captured: MutationRequest? = null
        val coordinator = coordinator { request -> captured = request; HttpResult.NoContent() }
        try {
            coordinator.submit(MutationIntent("collection-create", "POST", "/api/library/collections", "{}"))
            coordinator.activities.first { it.singleOrNull()?.status == MutationStatus.SUCCESS }
            assertNull(captured?.idempotencyKey)
        } finally { coordinator.close() }
    }

    @Test fun confirmedWriteRemainsConfirmedWhenFollowupReadFails() = runBlocking {
        val coordinator = MutationCoordinator(
            origin = ServerOrigin.parse("http://music.example:18000"),
            send = { HttpResult.NoContent() },
            synchronize = { throw IOException("read failed") },
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            keyFactory = { "stable-key" },
        )
        try {
            coordinator.submit(intent())
            val activity = coordinator.activities.first { it.singleOrNull()?.syncError == "read failed" }.single()
            assertEquals(MutationStatus.SUCCESS, activity.status)
        } finally { coordinator.close() }
    }

    @Test fun originChangeBlocksRetryAndDoesNotCreateNewKeyOrMigrateIntent() = runBlocking {
        val keys = mutableListOf<String>()
        val coordinator = MutationCoordinator(
            origin = ServerOrigin.parse("http://music.example:18000"),
            send = { HttpResult.Failed(HttpFailure.Transport(IOException("timeout"))) },
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            keyFactory = { "key-${keys.size}".also(keys::add) },
        )
        try {
            val id = intent()
            coordinator.submit(id)
            coordinator.activities.first { it.singleOrNull()?.status == MutationStatus.UNKNOWN }
            coordinator.changeOrigin(ServerOrigin.parse("https://other.example:18443"))
            assertEquals(MutationOutcome.BlockedOriginChange, coordinator.retryUnknown(id.id))
            assertEquals(listOf("key-0"), keys)
        } finally { coordinator.close() }
    }

    @Test fun unknownMutationIsNotPersistedOrReplayedByNewCoordinator() = runBlocking {
        val calls = AtomicInteger()
        val first = coordinator { calls.incrementAndGet(); HttpResult.Failed(HttpFailure.Transport(IOException("timeout"))) }
        val second = coordinator { calls.incrementAndGet(); HttpResult.NoContent() }
        try {
            first.submit(intent())
            first.activities.first { it.singleOrNull()?.status == MutationStatus.UNKNOWN }
            assertTrue(second.activities.value.isEmpty())
            assertEquals(1, calls.get())
        } finally { first.close(); second.close() }
    }

    @Test fun concurrentDuplicateIsRejectedWhileFirstRequestIsPending() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val coordinator = coordinator {
            calls.incrementAndGet()
            started.countDown()
            release.await(2, TimeUnit.SECONDS)
            HttpResult.NoContent()
        }
        try {
            coordinator.submit(intent())
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertEquals(MutationOutcome.DuplicatePending, coordinator.submit(intent()))
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            coordinator.close()
        }
    }

    @Test fun conflictingPayloadCannotHideBehindPendingDuplicateDetection() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = coordinator {
            started.countDown()
            release.await(2, TimeUnit.SECONDS)
            HttpResult.NoContent()
        }
        try {
            coordinator.submit(intent())
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertTrue(coordinator.submit(intent().copy(payload = """{"different":true}""")) is MutationOutcome.Failed)
        } finally { release.countDown(); coordinator.close() }
    }

    @Test fun originChangeBeforeQueuedSendPreventsSendingToOldOrigin() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val calls = AtomicInteger()
        val coordinator = MutationCoordinator(
            origin = ServerOrigin.parse("http://music.example:18000"),
            send = { calls.incrementAndGet(); HttpResult.NoContent() },
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            dispatcher = dispatcher,
        )
        try {
            coordinator.submit(intent())
            coordinator.changeOrigin(ServerOrigin.parse("https://other.example:18443"))
            dispatcher.runNext()
            assertEquals(0, calls.get())
            assertEquals(MutationStatus.UNKNOWN, coordinator.activities.value.single().status)
        } finally { coordinator.close() }
    }

    @Test fun originChangeWhileWriteInFlightPreservesReceiptWithoutReadbackOnNewOrigin() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val syncCalls = AtomicInteger()
        val coordinator = MutationCoordinator(
            origin = ServerOrigin.parse("http://music.example:18000"),
            send = {
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
                HttpResult.NoContent()
            },
            synchronize = { syncCalls.incrementAndGet() },
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        try {
            coordinator.submit(intent())
            assertTrue(started.await(2, TimeUnit.SECONDS))
            coordinator.changeOrigin(ServerOrigin.parse("https://other.example:18443"))
            release.countDown()
            val activity = coordinator.activities.first {
                it.singleOrNull()?.status == MutationStatus.SUCCESS &&
                    it.single().message?.contains("previous server") == true
            }.single()
            assertEquals(MutationStatus.SUCCESS, activity.status)
            assertEquals(0, syncCalls.get())
        } finally {
            release.countDown()
            coordinator.close()
        }
    }

    @Test fun explicitFailureRemainsFailedWhenOriginChangesWhileWriteIsInFlight() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = MutationCoordinator(
            origin = ServerOrigin.parse("http://music.example:18000"),
            send = {
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
                HttpResult.HttpError(400, "application/json", """{"error":{"code":"invalid","message":"rejected"}}""".toByteArray())
            },
            parentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        try {
            coordinator.submit(intent())
            assertTrue(started.await(2, TimeUnit.SECONDS))
            coordinator.changeOrigin(ServerOrigin.parse("https://other.example:18443"))
            release.countDown()
            val activity = withTimeout(2_000) {
                coordinator.activities.first {
                    it.singleOrNull()?.status == MutationStatus.FAILED &&
                        it.single().message?.contains("Operation failed on previous server") == true
                }.single()
            }
            assertEquals(MutationStatus.FAILED, activity.status)
        } finally {
            release.countDown()
            coordinator.close()
        }
    }

    @Test fun serverErrorIsUnknownBecauseCommitOutcomeIsAmbiguous() = runBlocking {
        val coordinator = coordinator { HttpResult.HttpError(502, "text/plain", "upstream unavailable".toByteArray()) }
        try {
            coordinator.submit(intent())
            assertEquals(MutationStatus.UNKNOWN, coordinator.activities.first { it.singleOrNull()?.status == MutationStatus.UNKNOWN }.single().status)
        } finally { coordinator.close() }
    }

    @Test fun malformedClientErrorRemainsAnExplicitFailure() = runBlocking {
        val coordinator = coordinator { HttpResult.HttpError(400, "application/json", "not-json".toByteArray()) }
        try {
            coordinator.submit(intent())
            assertEquals(MutationStatus.FAILED, coordinator.activities.first { it.singleOrNull()?.status == MutationStatus.FAILED }.single().status)
        } finally { coordinator.close() }
    }

    private fun coordinator(send: (MutationRequest) -> HttpResult) = MutationCoordinator(
        origin = ServerOrigin.parse("http://music.example:18000"),
        send = send,
        parentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        keyFactory = { "stable-key" },
    )

    private fun intent(id: String = "intent-1") = MutationIntent(id, "POST", "/api/playback/pause", "{}")

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.add(block) }
        fun runNext() { checkNotNull(queued.poll()) { "No coroutine was queued" }.run() }
    }
}
