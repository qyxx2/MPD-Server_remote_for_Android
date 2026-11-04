package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.network.HttpFailure
import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.StateSnapshotDto
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareVolumeCommandGatewayTest {
    @Test fun serializesKeyAndAbsoluteInputsAgainstConfirmedReadback() = runBlocking {
        val snapshots = freshStore(50)
        val origin = ServerOrigin.parse("http://music.example:18000")
        val sent = java.util.concurrent.CopyOnWriteArrayList<MutationRequest>()
        val enteredFirstSend = CountDownLatch(1)
        val releaseFirstSend = CountDownLatch(1)
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val firstReadbackStarted = CountDownLatch(1)
        val releaseFirstReadback = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var coordinator: MutationCoordinator
        coordinator = MutationCoordinator(
            origin = origin,
            send = { request ->
                sent += request
                val now = inFlight.incrementAndGet()
                maxInFlight.updateAndGet { maxOf(it, now) }
                if (sent.size == 1) {
                    enteredFirstSend.countDown()
                    releaseFirstSend.await(2, TimeUnit.SECONDS)
                }
                inFlight.decrementAndGet()
                HttpResult.Body(200, "application/json", "{}".toByteArray())
            },
            synchronize = {
                val target = sent.last().payloadTarget()
                if (target == 51) {
                    firstReadbackStarted.countDown()
                    releaseFirstReadback.await(2, TimeUnit.SECONDS)
                }
                acceptReadback(snapshots, target)
            },
            parentScope = scope,
            keyFactory = { "volume-key-${sent.size}" },
        )
        val gateway = gateway(snapshots, origin, coordinator, scope)
        try {
            assertEquals(HardwareVolumeEnqueueDisposition.STARTED, gateway.step(1).disposition)
            assertTrue(enteredFirstSend.await(2, TimeUnit.SECONDS))
            assertEquals(50, currentVolume(snapshots)) // no optimistic SnapshotStore patch

            assertEquals(HardwareVolumeEnqueueDisposition.QUEUED, gateway.step(1).disposition)
            assertEquals(HardwareVolumeEnqueueDisposition.QUEUED, gateway.step(1).disposition)
            assertEquals(HardwareVolumeEnqueueDisposition.QUEUED, gateway.setAbsolute(60).disposition)
            assertEquals(HardwareVolumeEnqueueDisposition.QUEUED, gateway.step(1).disposition)
            assertEquals(3, gateway.state.value.queuedInputCount) // two adjacent key steps coalesce
            releaseFirstSend.countDown()
            assertTrue(firstReadbackStarted.await(2, TimeUnit.SECONDS))
            assertEquals("next PUT must wait until full-state readback has completed", 1, sent.size)
            releaseFirstReadback.countDown()

            withTimeout(4_000) {
                gateway.state.first { it.phase == HardwareVolumeCommandPhase.IDLE && sent.size == 4 }
            }
            assertEquals(listOf(51, 53, 60, 61), sent.map { it.payloadTarget() })
            assertEquals(1, maxInFlight.get())
            assertEquals(61, currentVolume(snapshots))
            assertTrue(sent.all { it.method == "PUT" && it.path == "/api/system/hardware-volume" })
            assertTrue(sent.all { it.idempotencyKey?.isNotBlank() == true })
            assertEquals(4, sent.map { it.idempotencyKey }.toSet().size)
            assertTrue(sent.all { it.payload == "{\"volumePercent\":${it.payloadTarget()}}" })
        } finally {
            releaseFirstSend.countDown()
            releaseFirstReadback.countDown()
            gateway.close()
            coordinator.close()
            scope.cancelForTest()
        }
    }

    @Test fun unknownOutcomeDropsQueuedInputsAndExplicitRetryReusesOriginalRequest() = runBlocking {
        val snapshots = freshStore(24)
        val origin = ServerOrigin.parse("http://music.example:18000")
        val sent = java.util.concurrent.CopyOnWriteArrayList<MutationRequest>()
        val enteredFirstSend = CountDownLatch(1)
        val releaseFirstSend = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = MutationCoordinator(
            origin = origin,
            send = { request ->
                sent += request
                if (sent.size == 1) {
                    enteredFirstSend.countDown()
                    releaseFirstSend.await(2, TimeUnit.SECONDS)
                    HttpResult.Failed(HttpFailure.Transport(IOException("timeout")))
                } else HttpResult.Body(200, "application/json", "{}".toByteArray())
            },
            synchronize = { acceptReadback(snapshots, sent.last().payloadTarget()) },
            parentScope = scope,
            keyFactory = { "same-logical-key" },
        )
        val gateway = gateway(snapshots, origin, coordinator, scope)
        try {
            assertEquals(HardwareVolumeEnqueueDisposition.STARTED, gateway.setAbsolute(30).disposition)
            assertTrue(enteredFirstSend.await(2, TimeUnit.SECONDS))
            assertEquals(HardwareVolumeEnqueueDisposition.QUEUED, gateway.step(1).disposition)
            releaseFirstSend.countDown()

            withTimeout(2_000) {
                gateway.state.first { it.phase == HardwareVolumeCommandPhase.UNKNOWN }
            }
            assertEquals(1, sent.size)
            assertEquals(0, gateway.state.value.queuedInputCount)
            assertNotNull(gateway.state.value.retryableIntentId)

            assertEquals(MutationOutcome.Submitted, gateway.retryUnknown())
            withTimeout(3_000) {
                gateway.state.first { it.phase == HardwareVolumeCommandPhase.IDLE && sent.size == 2 }
            }
            assertEquals(sent[0].method, sent[1].method)
            assertEquals(sent[0].path, sent[1].path)
            assertEquals(sent[0].payload, sent[1].payload)
            assertEquals(sent[0].idempotencyKey, sent[1].idempotencyKey)
            assertEquals(30, currentVolume(snapshots))
            assertEquals(2, sent.size) // queued +1 was discarded, never replayed after retry
        } finally {
            releaseFirstSend.countDown()
            gateway.close()
            coordinator.close()
            scope.cancelForTest()
        }
    }

    @Test fun oppositeKeyStepsAreNotCollapsedAcrossAClampedBoundary() = runBlocking {
        val snapshots = freshStore(50)
        val origin = ServerOrigin.parse("http://music.example:18000")
        val sent = java.util.concurrent.CopyOnWriteArrayList<MutationRequest>()
        val enteredFirstSend = CountDownLatch(1)
        val releaseFirstSend = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = MutationCoordinator(
            origin = origin,
            send = { request ->
                sent += request
                if (sent.size == 1) {
                    enteredFirstSend.countDown()
                    releaseFirstSend.await(2, TimeUnit.SECONDS)
                }
                HttpResult.NoContent()
            },
            synchronize = { acceptReadback(snapshots, sent.last().payloadTarget()) },
            parentScope = scope,
        )
        val gateway = gateway(snapshots, origin, coordinator, scope)
        try {
            gateway.setAbsolute(0)
            assertTrue(enteredFirstSend.await(2, TimeUnit.SECONDS))
            gateway.step(-1)
            gateway.step(1)
            releaseFirstSend.countDown()
            withTimeout(3_000) {
                gateway.state.first { it.phase == HardwareVolumeCommandPhase.IDLE && currentVolume(snapshots) == 1 }
            }
            assertEquals(listOf(0, 1), sent.map { it.payloadTarget() })
        } finally {
            releaseFirstSend.countDown()
            gateway.close()
            coordinator.close()
            scope.cancelForTest()
        }
    }

    @Test fun explicitBusinessFailureDropsQueuedInputAndAllowsANewIntent() = runBlocking {
        val snapshots = freshStore(50)
        val origin = ServerOrigin.parse("http://music.example:18000")
        val sent = java.util.concurrent.CopyOnWriteArrayList<MutationRequest>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = MutationCoordinator(
            origin = origin,
            send = { request ->
                sent += request
                if (sent.size == 1) {
                    HttpResult.HttpError(409, "application/json", """{"error":{"code":"rejected","message":"rejected"}}""".toByteArray())
                } else {
                    HttpResult.Body(200, "application/json", "{}".toByteArray())
                }
            },
            synchronize = { acceptReadback(snapshots, sent.last().payloadTarget()) },
            parentScope = scope,
            keyFactory = { "business-key-${sent.size}" },
        )
        val gateway = gateway(snapshots, origin, coordinator, scope)
        try {
            gateway.setAbsolute(55)
            gateway.step(1)
            withTimeout(2_000) {
                gateway.state.first { it.phase == HardwareVolumeCommandPhase.FAILED }
            }
            assertEquals(1, sent.size) // the queued +1 is discarded after a definitive 4xx
            assertEquals(50, currentVolume(snapshots))

            assertEquals(HardwareVolumeEnqueueDisposition.STARTED, gateway.setAbsolute(60).disposition)
            withTimeout(2_000) {
                gateway.state.first { it.phase == HardwareVolumeCommandPhase.IDLE && currentVolume(snapshots) == 60 }
            }
            assertEquals(2, sent.size)
            assertNotNull(sent[0].idempotencyKey)
            assertNotNull(sent[1].idempotencyKey)
            assertTrue(sent[0].idempotencyKey != sent[1].idempotencyKey)
        } finally {
            gateway.close()
            coordinator.close()
            scope.cancelForTest()
        }
    }

    @Test fun freshnessLossOrGenerationChangeDiscardsQueuedInputsPermanently() = runBlocking {
        val snapshots = freshStore(40)
        val origin = ServerOrigin.parse("http://music.example:18000")
        val sent = java.util.concurrent.CopyOnWriteArrayList<MutationRequest>()
        val enteredFirstSend = CountDownLatch(1)
        val releaseFirstSend = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = MutationCoordinator(
            origin = origin,
            send = { request ->
                sent += request
                enteredFirstSend.countDown()
                releaseFirstSend.await(2, TimeUnit.SECONDS)
                HttpResult.NoContent()
            },
            synchronize = {
                val state = snapshots.state.value
                val target = sent.last().payloadTarget()
                snapshots.accept(dto("epoch-2", 2, target), state.connectionGeneration, SnapshotSource.HTTP_READ)
            },
            parentScope = scope,
        )
        val gateway = gateway(snapshots, origin, coordinator, scope)
        try {
            gateway.setAbsolute(45)
            assertTrue(enteredFirstSend.await(2, TimeUnit.SECONDS))
            gateway.step(1)
            snapshots.onConnectionState(2, RuntimeConnectionStatus.CONNECTING)
            withTimeout(1_000) {
                while (gateway.state.value.queuedInputCount != 0) delay(5)
            }
            snapshots.onConnectionState(2, RuntimeConnectionStatus.CONNECTED)
            snapshots.accept(dto("epoch-2", 1, 45), 2, SnapshotSource.REALTIME_INITIAL)
            releaseFirstSend.countDown()

            withTimeout(3_000) {
                gateway.state.first { it.phase == HardwareVolumeCommandPhase.FAILED }
            }
            delay(100)
            assertEquals(1, sent.size)
            assertEquals(45, currentVolume(snapshots))
            assertFalse(gateway.state.value.phase == HardwareVolumeCommandPhase.QUEUED)
        } finally {
            releaseFirstSend.countDown()
            gateway.close()
            coordinator.close()
            scope.cancelForTest()
        }
    }


    @Test fun explicitHttpFailureStopsTheInputFifoWithoutSendingTheNextKeyStep() = runBlocking {
        val snapshots = freshStore(40)
        val origin = ServerOrigin.parse("http://music.example:18000")
        val sent = java.util.concurrent.CopyOnWriteArrayList<MutationRequest>()
        val enteredSend = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = MutationCoordinator(
            origin = origin,
            send = { request ->
                sent += request
                enteredSend.countDown()
                releaseSend.await(2, TimeUnit.SECONDS)
                HttpResult.HttpError(503, "application/json", """{"error":{"code":"HARDWARE_VOLUME_UNAVAILABLE","message":"unavailable"}}""".toByteArray())
            },
            synchronize = { error("failed HTTP write must not run readback") },
            parentScope = scope,
        )
        val gateway = gateway(snapshots, origin, coordinator, scope)
        try {
            gateway.step(1)
            assertTrue(enteredSend.await(2, TimeUnit.SECONDS))
            gateway.step(1)
            releaseSend.countDown()
            withTimeout(2_000) {
                gateway.state.first { it.phase == HardwareVolumeCommandPhase.UNKNOWN }
            }
            assertEquals(1, sent.size)
            assertEquals(0, gateway.state.value.queuedInputCount)
            assertNotNull("5xx is ambiguous and must retain the exact intent for explicit retry", gateway.state.value.retryableIntentId)
        } finally {
            releaseSend.countDown()
            gateway.close()
            coordinator.close()
            scope.cancelForTest()
        }
    }

    @Test fun successfulHttpResponseWithMismatchedCanonicalReadbackStopsQueue() = runBlocking {
        val snapshots = freshStore(40)
        val origin = ServerOrigin.parse("http://music.example:18000")
        val sent = java.util.concurrent.CopyOnWriteArrayList<MutationRequest>()
        val enteredSend = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = MutationCoordinator(
            origin = origin,
            send = { request ->
                sent += request
                enteredSend.countDown()
                releaseSend.await(2, TimeUnit.SECONDS)
                HttpResult.Body(200, "application/json", "{}".toByteArray())
            },
            synchronize = { acceptReadback(snapshots, 49) },
            parentScope = scope,
        )
        val gateway = gateway(snapshots, origin, coordinator, scope)
        try {
            gateway.setAbsolute(50)
            assertTrue(enteredSend.await(2, TimeUnit.SECONDS))
            gateway.step(1)
            releaseSend.countDown()
            withTimeout(2_000) {
                gateway.state.first { it.phase == HardwareVolumeCommandPhase.FAILED }
            }
            assertEquals(1, sent.size)
            assertEquals(49, currentVolume(snapshots))
            assertEquals(0, gateway.state.value.queuedInputCount)
        } finally {
            releaseSend.countDown()
            gateway.close()
            coordinator.close()
            scope.cancelForTest()
        }
    }

    @Test fun originChangeDiscardsQueuedInputsAndCannotRetryUnknownOnNewOrigin() = runBlocking {
        val snapshots = freshStore(50)
        val origin = ServerOrigin.parse("http://music.example:18000")
        val newOrigin = ServerOrigin.parse("https://other.example:18443")
        val sent = java.util.concurrent.CopyOnWriteArrayList<MutationRequest>()
        val enteredSend = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = MutationCoordinator(
            origin = origin,
            send = { request ->
                sent += request
                enteredSend.countDown()
                releaseSend.await(2, TimeUnit.SECONDS)
                HttpResult.Failed(HttpFailure.Transport(IOException("unknown on old endpoint")))
            },
            parentScope = scope,
        )
        val currentOrigin = java.util.concurrent.atomic.AtomicReference(origin)
        val gateway = HardwareVolumeCommandGateway(
            stateProvider = { snapshots.state.value },
            snapshots = snapshots.state,
            activitiesProvider = coordinator.activities,
            originProvider = { currentOrigin.get() },
            submitter = coordinator::submit,
            retryUnknownSubmitter = coordinator::retryUnknown,
            parentScope = scope,
        )
        try {
            gateway.step(1)
            assertTrue(enteredSend.await(2, TimeUnit.SECONDS))
            gateway.step(1)
            gateway.onOriginChanging(newOrigin)
            currentOrigin.set(newOrigin)
            coordinator.changeOrigin(newOrigin)
            releaseSend.countDown()
            withTimeout(2_000) {
                gateway.state.first { it.phase == HardwareVolumeCommandPhase.FAILED }
            }
            assertEquals(1, sent.size)
            assertEquals(0, gateway.state.value.queuedInputCount)
            assertEquals(null, gateway.state.value.retryableIntentId)
            assertEquals(MutationOutcome.NotRetryable, gateway.retryUnknown())
        } finally {
            releaseSend.countDown()
            gateway.close()
            coordinator.close()
            scope.cancelForTest()
        }
    }

    private fun gateway(
        snapshots: SnapshotStore,
        origin: ServerOrigin,
        coordinator: MutationCoordinator,
        scope: CoroutineScope,
    ) = HardwareVolumeCommandGateway(
        stateProvider = { snapshots.state.value },
        snapshots = snapshots.state,
        activitiesProvider = coordinator.activities,
        originProvider = { origin },
        submitter = coordinator::submit,
        retryUnknownSubmitter = coordinator::retryUnknown,
        parentScope = scope,
    )

    private fun freshStore(volume: Int): SnapshotStore = SnapshotStore().apply {
        onConnectionState(1, RuntimeConnectionStatus.CONNECTED)
        assertTrue(accept(dto("epoch-1", 1, volume), 1, SnapshotSource.REALTIME_INITIAL))
    }

    private fun acceptReadback(store: SnapshotStore, target: Int) {
        val state = store.state.value
        val old = state.snapshot ?: error("missing test snapshot")
        check(store.accept(dto(old.epoch, old.sequence + 1, target), state.connectionGeneration, SnapshotSource.HTTP_READ))
    }

    private fun currentVolume(store: SnapshotStore): Int = HardwareVolumeCapabilityPolicy
        .evaluate(store.state.value).controllableVolumePercent ?: error("test volume is not controllable")

    private fun MutationRequest.payloadTarget(): Int {
        val root = JsonConfiguration.json.parseToJsonElement(payload ?: error("payload missing")).jsonObject
        return root.getValue("volumePercent").jsonPrimitive.int
    }

    private fun dto(epoch: String, sequence: Long, volume: Int): StateSnapshotDto =
        JsonConfiguration.json.decodeFromString(
            """{"epoch":"$epoch","sequence":$sequence,"captured_at":"2026-11-04T12:00:00Z","explicit_stop_generation":0,"revisions":{"library":0,"playlist":0},"playback":{},"current_song":null,"queue":{"revision":0,"items":[]},"history":{},"playback_observation":{},"output":{},"output_observation":{},"hardware_volume":{"state":{"status":"AVAILABLE","volume_percent":$volume,"output_name":"USB DAC","output_plugin":"alsa","output_enabled":true,"control_source":"MPD_HARDWARE_MIXER","error_code":null,"error_message":null,"updated_at":"2026-11-04T12:00:00Z","stale":false},"last_request":null},"hardware_volume_observation":{"observed_at":"2026-11-04T12:00:00Z","freshness":"fresh","error_code":null,"error_message":null},"library_sync":{"generation":0}}""",
        )
}

private fun CoroutineScope.cancelForTest() = coroutineContext[kotlinx.coroutines.Job]?.cancel()
