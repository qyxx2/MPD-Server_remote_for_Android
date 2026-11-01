package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerAction
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerActions
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerTestFixtures
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlayerCommandGatewayTest {
    private val origin = ServerOrigin.parse("http://music.example:18000")

    @Test
    fun systemAndInAppPlayerActionsShareTheSameSubmissionLock() {
        val snapshot = PlayerTestFixtures.state()
        val activities = mutableListOf<OperationActivity>()
        val submitted = mutableListOf<MutationIntent>()
        val gateway = PlayerCommandGateway(
            stateProvider = { snapshot },
            activitiesProvider = { activities.toList() },
            submitter = { intent ->
                submitted += intent
                activities += OperationActivity(
                    intentId = intent.id,
                    label = intent.label ?: intent.id,
                    status = MutationStatus.PENDING,
                    idempotencyKey = "stable-key",
                    origin = origin,
                )
                MutationOutcome.Submitted
            },
            retryUnknownSubmitter = { MutationOutcome.NotRetryable },
            idFactory = { "system-first" },
        )
        val inAppActions = PlayerActions(
            stateProvider = { snapshot },
            activitiesProvider = { activities.toList() },
            submitter = { error("the UI must use the shared Runtime gateway") },
            retryUnknownSubmitter = { error("the UI must use the shared Runtime gateway") },
            sharedCommandGateway = gateway,
        )

        assertEquals(MutationOutcome.Submitted, gateway.dispatch(PlayerAction.PAUSE)?.outcome)
        assertNull(inAppActions.perform(PlayerAction.PAUSE))
        assertEquals(1, submitted.size)
        assertEquals("/api/playback/pause", submitted.single().path)
    }

    @Test
    fun dispatchMapsPausePreviousAndNextToTheExistingApiCommands() {
        val queue = """{"revision":19,"items":[{"queue_item_id":"played-1","song_id":"song-0","position":-1,"source":"MANUAL"},{"queue_item_id":"occurrence-1","song_id":"song-1","position":0,"source":"MANUAL"},{"queue_item_id":"next-1","song_id":"song-2","position":1,"source":"MANUAL"}]}"""
        val harness = Harness(PlayerTestFixtures.state(queue = queue))

        listOf(
            PlayerAction.PAUSE to "/api/playback/pause",
            PlayerAction.PREVIOUS to "/api/playback/previous",
            PlayerAction.NEXT to "/api/playback/next",
        ).forEach { (action, path) ->
            assertTrue(harness.gateway.isEnabled(action))
            assertEquals(MutationOutcome.Submitted, harness.gateway.dispatch(action)?.outcome)
            assertEquals(path, harness.submitted.last().path)
            assertEquals("{}", harness.submitted.last().payload)
            harness.completeLatest()
        }

        assertEquals(3, harness.submitted.size)
    }

    @Test
    fun resumeUsesTheExactObservedCurrentOccurrenceAndOpaqueTarget() {
        val pausedObservation = PlayerTestFixtures.observationJson
            .replace("\"actual_state\":\"playing\"", "\"actual_state\":\"paused\"")
        val harness = Harness(PlayerTestFixtures.state(observation = pausedObservation))

        assertTrue(harness.gateway.isEnabled(PlayerAction.RESUME))
        harness.gateway.dispatch(PlayerAction.RESUME)

        val intent = harness.submitted.single()
        assertEquals("/api/playback/resume", intent.path)
        val target = JsonConfiguration.json.parseToJsonElement(intent.payload!!).jsonObject
            .getValue("target").jsonObject
        assertEquals("occurrence-1", target.getValue("queue_item_id").jsonPrimitive.content)
        assertEquals("opaque-token", target.getValue("token").jsonPrimitive.content)
    }

    @Test
    fun staleSnapshotCannotDispatchEvenWhenAPlayerActionWasPreviouslyAvailable() {
        val harness = Harness(PlayerTestFixtures.state())
        assertTrue(harness.gateway.isEnabled(PlayerAction.PAUSE))
        harness.snapshot = PlayerTestFixtures.state(freshness = SnapshotFreshness.STALE)

        assertFalse(harness.gateway.isEnabled(PlayerAction.PAUSE))
        assertNull(harness.gateway.dispatch(PlayerAction.PAUSE))
        assertTrue(harness.submitted.isEmpty())
    }

    @Test
    fun pendingAndUnknownPlayerIntentLockAllGatewayCallers() {
        val harness = Harness(PlayerTestFixtures.state())

        assertEquals(MutationOutcome.Submitted, harness.gateway.dispatch(PlayerAction.PAUSE)?.outcome)
        assertNull(harness.gateway.dispatch(PlayerAction.PAUSE))
        harness.setLatestStatus(MutationStatus.UNKNOWN)
        assertFalse(harness.gateway.isEnabled(PlayerAction.PAUSE))
        assertNull(harness.gateway.dispatch(PlayerAction.PAUSE))
        assertEquals(1, harness.submitted.size)
    }

    @Test
    fun syncingPlayerIntentLocksAllGatewayCallers() {
        val harness = Harness(PlayerTestFixtures.state(), initialStatus = MutationStatus.SYNCING)

        assertEquals(MutationOutcome.Submitted, harness.gateway.dispatch(PlayerAction.PAUSE)?.outcome)
        assertFalse(harness.gateway.isEnabled(PlayerAction.NEXT))
        assertNull(harness.gateway.dispatch(PlayerAction.NEXT))
        assertEquals(1, harness.submitted.size)
    }

    @Test
    fun gatewayDispatchWaitsForTheSharedOriginTransitionCriticalSection() {
        val commandLock = Any()
        val eligible = java.util.concurrent.atomic.AtomicBoolean(true)
        val harness = Harness(
            PlayerTestFixtures.state(),
            commandEligibilityProvider = { eligible.get() },
            commandLock = commandLock,
        )
        val transitionEntered = CountDownLatch(1)
        val releaseTransition = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val transition = executor.submit {
            synchronized(commandLock) {
                eligible.set(false)
                transitionEntered.countDown()
                releaseTransition.await()
            }
        }
        assertTrue(transitionEntered.await(2, TimeUnit.SECONDS))
        val dispatch = executor.submit { harness.gateway.dispatch(PlayerAction.PAUSE) }
        try {
            Thread.sleep(50)
            assertFalse("dispatch must wait for the same origin-switch monitor", dispatch.isDone)
        } finally {
            releaseTransition.countDown()
        }
        transition.get(2, TimeUnit.SECONDS)
        assertNull(dispatch.get(2, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertTrue(harness.submitted.isEmpty())
    }

    @Test
    fun originTransitionWindowBlocksDispatchAndUnknownRetry() {
        var commandEligible = true
        val harness = Harness(
            PlayerTestFixtures.state(),
            initialStatus = MutationStatus.UNKNOWN,
            commandEligibilityProvider = { commandEligible },
        )
        val unknownId = requireNotNull(harness.gateway.dispatch(PlayerAction.PAUSE)).intent.id

        commandEligible = false
        assertFalse(harness.gateway.isEnabled(PlayerAction.PAUSE))
        assertNull(harness.gateway.dispatch(PlayerAction.PAUSE))
        assertFalse(harness.gateway.canRetryUnknown(unknownId))
        assertEquals(MutationOutcome.NotRetryable, harness.gateway.retryUnknown(unknownId))
        assertEquals(1, harness.submitted.size)

        commandEligible = true
        assertTrue(harness.gateway.canRetryUnknown(unknownId))
        assertEquals(MutationOutcome.Submitted, harness.gateway.retryUnknown(unknownId))
        assertEquals(listOf(unknownId), harness.retriedIds)
    }

    @Test
    fun unknownIntentCanOnlyBeRetriedExplicitlyWithTheSameIntentId() {
        val harness = Harness(PlayerTestFixtures.state(), initialStatus = MutationStatus.UNKNOWN)
        val submission = requireNotNull(harness.gateway.dispatch(PlayerAction.PAUSE))
        val originalId = submission.intent.id

        assertTrue(harness.gateway.canRetryUnknown(originalId))
        assertEquals(MutationOutcome.Submitted, harness.gateway.retryUnknown(originalId))
        assertEquals(listOf(originalId), harness.retriedIds)
        assertEquals(1, harness.submitted.size)
        assertFalse(harness.gateway.canRetryUnknown(originalId))
    }

    @Test
    fun obsoleteUnknownIntentDoesNotBlockANewCanonicalControlIdentity() {
        val harness = Harness(PlayerTestFixtures.state(), initialStatus = MutationStatus.UNKNOWN)
        harness.gateway.dispatch(PlayerAction.PAUSE)
        val rotatedObservation = PlayerTestFixtures.observationJson.replace(
            "\"control_target\":{\"queue_item_id\":\"occurrence-1\",\"token\":\"opaque-token\"}",
            "\"control_target\":{\"queue_item_id\":\"occurrence-1\",\"token\":\"rotated-token\"}",
        )
        harness.snapshot = PlayerTestFixtures.state(observation = rotatedObservation)

        assertTrue(harness.gateway.isEnabled(PlayerAction.PAUSE))
        assertEquals(MutationOutcome.Submitted, harness.gateway.dispatch(PlayerAction.PAUSE)?.outcome)
        assertEquals(2, harness.submitted.size)
    }

    @Test
    fun previousAndNextAreDisabledWhenCanonicalQueueHasOnlyCurrentOccurrence() {
        val queue = """{"revision":20,"items":[{"queue_item_id":"occurrence-1","song_id":"song-1","position":0,"source":"MANUAL"}]}"""
        val harness = Harness(PlayerTestFixtures.state(queue = queue))

        assertFalse(harness.gateway.isEnabled(PlayerAction.PREVIOUS))
        assertFalse(harness.gateway.isEnabled(PlayerAction.NEXT))
        assertNull(harness.gateway.dispatch(PlayerAction.PREVIOUS))
        assertNull(harness.gateway.dispatch(PlayerAction.NEXT))
        assertTrue(harness.submitted.isEmpty())
    }

    @Test
    fun unknownIntentSurvivesReadStalenessButIsRetiredAfterConnectionGenerationChanges() {
        val harness = Harness(PlayerTestFixtures.state(), initialStatus = MutationStatus.UNKNOWN)
        val intentId = requireNotNull(harness.gateway.dispatch(PlayerAction.PAUSE)).intent.id

        harness.snapshot = PlayerTestFixtures.state(freshness = SnapshotFreshness.STALE)
        assertFalse(harness.gateway.isEnabled(PlayerAction.PAUSE))
        assertTrue(harness.gateway.canRetryUnknown(intentId))

        harness.snapshot = PlayerTestFixtures.state(connectionGeneration = 2, snapshotConnectionGeneration = 2)
        assertFalse(harness.gateway.canRetryUnknown(intentId))
        assertTrue(harness.gateway.isEnabled(PlayerAction.PAUSE))
        assertEquals(1, harness.submitted.size)
    }

    @Test
    fun resumeIsDisabledWhenCurrentObservationHasNoServerTarget() {
        val observation = PlayerTestFixtures.observationJson.replace(
            "\"control_target\":{\"queue_item_id\":\"occurrence-1\",\"token\":\"opaque-token\"}",
            "\"control_target\":null",
        )
        val harness = Harness(PlayerTestFixtures.state(
            observation = observation.replace("\"actual_state\":\"playing\"", "\"actual_state\":\"paused\"")
        ))

        assertFalse(harness.gateway.isEnabled(PlayerAction.RESUME))
        assertNull(harness.gateway.dispatch(PlayerAction.RESUME))
        assertTrue(harness.submitted.isEmpty())
    }

    @Test
    fun concurrentCallbacksAcrossCallersCreateOnlyOnePlayerIntent() {
        val snapshot = PlayerTestFixtures.state()
        val activities = mutableListOf<OperationActivity>()
        val submitCount = AtomicInteger()
        val gate = CountDownLatch(1)
        val ready = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        val gateway = PlayerCommandGateway(
            stateProvider = { snapshot },
            activitiesProvider = { synchronized(activities) { activities.toList() } },
            submitter = { intent ->
                submitCount.incrementAndGet()
                synchronized(activities) {
                    activities += OperationActivity(
                        intentId = intent.id,
                        label = intent.label ?: intent.id,
                        status = MutationStatus.PENDING,
                        idempotencyKey = "key",
                        origin = origin,
                    )
                }
                MutationOutcome.Submitted
            },
            retryUnknownSubmitter = { MutationOutcome.NotRetryable },
            idFactory = { "shared-race" },
        )

        val first = executor.submit {
            ready.countDown()
            gate.await()
            gateway.dispatch(PlayerAction.PAUSE)
        }
        val second = executor.submit {
            ready.countDown()
            gate.await()
            gateway.dispatch(PlayerAction.PAUSE)
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS))
        gate.countDown()
        first.get(2, TimeUnit.SECONDS)
        second.get(2, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertEquals(1, submitCount.get())
    }

    private inner class Harness(
        snapshot: SnapshotStoreState,
        private val initialStatus: MutationStatus = MutationStatus.PENDING,
        commandEligibilityProvider: () -> Boolean = { true },
        commandLock: Any = Any(),
    ) {
        var snapshot: SnapshotStoreState = snapshot
        val submitted = mutableListOf<MutationIntent>()
        val retriedIds = mutableListOf<String>()
        private val activities = mutableListOf<OperationActivity>()
        private var id = 0
        val gateway = PlayerCommandGateway(
            stateProvider = { this.snapshot },
            activitiesProvider = { activities.toList() },
            submitter = { intent ->
                submitted += intent
                activities += OperationActivity(
                    intentId = intent.id,
                    label = intent.label ?: intent.id,
                    status = initialStatus,
                    idempotencyKey = "key-${submitted.size}",
                    origin = origin,
                )
                MutationOutcome.Submitted
            },
            retryUnknownSubmitter = { intentId ->
                retriedIds += intentId
                val index = activities.indexOfFirst { it.intentId == intentId }
                if (index >= 0) activities[index] = activities[index].copy(status = MutationStatus.PENDING)
                MutationOutcome.Submitted
            },
            idFactory = { "gateway-${++id}" },
            commandEligibilityProvider = commandEligibilityProvider,
            commandLock = commandLock,
        )

        fun completeLatest() {
            val index = activities.lastIndex
            if (index >= 0) activities[index] = activities[index].copy(status = MutationStatus.SUCCESS)
        }

        fun setLatestStatus(status: MutationStatus) {
            val index = activities.lastIndex
            if (index >= 0) activities[index] = activities[index].copy(status = status)
        }
    }
}
