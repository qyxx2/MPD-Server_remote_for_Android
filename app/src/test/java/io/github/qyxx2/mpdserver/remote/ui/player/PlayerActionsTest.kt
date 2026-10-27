package io.github.qyxx2.mpdserver.remote.ui.player

import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.runtime.MutationIntent
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerActionsTest {
    private val origin = ServerOrigin.parse("http://music.example:18000")

    @Test
    fun pauseAndQueueNavigationUseExplicitEmptyJsonBodies() {
        var state = stateWithPreviousAndNext()
        val harness = Harness(stateProvider = { state })
        val actions = harness.actions()

        assertTrue(actions.isEnabled(PlayerAction.PAUSE))
        assertTrue(actions.isEnabled(PlayerAction.PREVIOUS))
        assertTrue(actions.isEnabled(PlayerAction.NEXT))
        actions.perform(PlayerAction.PAUSE)
        harness.completeLatest()
        actions.reconcile()
        actions.perform(PlayerAction.PREVIOUS)
        harness.completeLatest()
        actions.reconcile()
        actions.perform(PlayerAction.NEXT)

        assertEquals(listOf("/api/playback/pause", "/api/playback/previous", "/api/playback/next"),
            harness.submitted.map { it.path })
        assertEquals(listOf("{}", "{}", "{}"), harness.submitted.map { it.payload })
    }

    @Test
    fun resumeCopiesTheObservedServerTargetWithoutReconstructingIt() {
        val pausedObservation = PlayerTestFixtures.observationJson
            .replace("\"actual_state\":\"playing\"", "\"actual_state\":\"paused\"")
        val harness = Harness(stateProvider = { PlayerTestFixtures.state(observation = pausedObservation) })
        val actions = harness.actions()

        assertTrue(actions.isEnabled(PlayerAction.RESUME))
        actions.perform(PlayerAction.RESUME)

        val intent = harness.submitted.single()
        assertEquals("/api/playback/resume", intent.path)
        val body = JsonConfiguration.json.parseToJsonElement(intent.payload!!).jsonObject
        val target = body.getValue("target").jsonObject
        assertEquals("occurrence-1", target.getValue("queue_item_id").jsonPrimitive.content)
        assertEquals("opaque-token", target.getValue("token").jsonPrimitive.content)
    }

    @Test
    fun seekReleaseSendsExactlyOneOffsetFreeSecondsValueAndCapturedTarget() {
        val harness = Harness(stateProvider = { PlayerTestFixtures.state() })
        val actions = harness.actions()

        assertTrue(actions.beginSeek())
        actions.previewSeek(60.25)
        val outcome = actions.finishSeek()

        assertEquals(MutationOutcome.Submitted, outcome)
        assertEquals(1, harness.submitted.size)
        val intent = harness.submitted.single()
        assertEquals("/api/playback/seek", intent.path)
        val body = JsonConfiguration.json.parseToJsonElement(intent.payload!!).jsonObject
        assertEquals(60.25, body.getValue("seconds").jsonPrimitive.double, 0.0001)
        assertEquals("occurrence-1", body.getValue("target").jsonObject
            .getValue("queue_item_id").jsonPrimitive.content)
        assertNull(actions.state.value.seekDraftSeconds)
    }

    @Test
    fun confirmedSeekWaitsForFreshObservationThatCoversReceiptTimestamp() {
        var current = PlayerTestFixtures.state()
        val harness = Harness(stateProvider = { current })
        val actions = harness.actions()

        actions.perform(PlayerAction.SEEK, seconds = 60.25)
        assertEquals(1, harness.submitted.size)
        harness.activities[0] = harness.activities[0].copy(
            status = MutationStatus.SUCCESS,
            receiptUpdatedAt = "2026-10-27T10:00:02Z",
        )
        actions.reconcile()
        assertTrue(actions.state.value.syncing)
        assertEquals(60.25, actions.state.value.seekPreviewSeconds!!, 0.001)

        val newerObservation = PlayerTestFixtures.observationJson
            .replace("\"observed_at\":\"2026-10-27T10:00:01Z\"", "\"observed_at\":\"2026-10-27T10:00:03Z\"")
            .replace("\"position_seconds\":12.5", "\"position_seconds\":60.25")
        current = PlayerTestFixtures.state(observation = newerObservation)
        actions.reconcile()

        assertFalse(actions.state.value.syncing)
        assertNull(actions.state.value.seekPreviewSeconds)
        assertEquals(1, harness.submitted.size)
    }

    @Test
    fun seekDraftIsCancelledIfTargetChangesBeforeRelease() {
        var current = PlayerTestFixtures.state()
        val harness = Harness(stateProvider = { current })
        val actions = harness.actions()
        assertTrue(actions.beginSeek())
        actions.previewSeek(48.0)

        val changed = PlayerTestFixtures.observationJson.replace(
            "\"control_target\":{\"queue_item_id\":\"occurrence-1\",\"token\":\"opaque-token\"}",
            "\"control_target\":{\"queue_item_id\":\"occurrence-1\",\"token\":\"new-token\"}",
        )
        current = PlayerTestFixtures.state(observation = changed)
        actions.reconcile()

        assertNull(actions.state.value.seekDraftSeconds)
        assertNull(actions.finishSeek())
        assertTrue(harness.submitted.isEmpty())
    }

    @Test
    fun seekDraftIsCancelledWhenSnapshotBecomesStale() {
        var current = PlayerTestFixtures.state()
        val harness = Harness(stateProvider = { current })
        val actions = harness.actions()
        assertTrue(actions.beginSeek())
        actions.previewSeek(70.0)
        current = PlayerTestFixtures.state(freshness = SnapshotFreshness.STALE)
        actions.reconcile()

        assertNull(actions.state.value.seekDraftSeconds)
        assertNull(actions.finishSeek())
        assertTrue(harness.submitted.isEmpty())
    }

    @Test
    fun doubleTapCannotSubmitAnotherPlaybackMutationWhileOneIsPending() {
        val harness = Harness(stateProvider = { PlayerTestFixtures.state() }, recordPendingActivity = true)
        val actions = harness.actions()

        assertEquals(MutationOutcome.Submitted, actions.perform(PlayerAction.PAUSE))
        assertNull(actions.perform(PlayerAction.PAUSE))
        assertEquals(1, harness.submitted.size)
    }

    @Test
    fun unknownResultRequiresExplicitRetryOfTheSameIntentId() {
        val harness = Harness(stateProvider = { PlayerTestFixtures.state() }, initialSubmitStatus = MutationStatus.UNKNOWN)
        val actions = harness.actions()
        actions.perform(PlayerAction.PAUSE)
        val originalId = harness.submitted.single().id

        assertTrue(actions.canRetryUnknown())
        assertEquals(MutationOutcome.Submitted, actions.retryUnknown())
        assertEquals(listOf(originalId), harness.retriedIds)
        assertEquals(1, harness.submitted.size)
    }

    @Test
    fun unknownIntentRemainsRetryableAcrossReadStalenessWhenConnectionAndTargetAreUnchanged() {
        var current = PlayerTestFixtures.state()
        val harness = Harness(stateProvider = { current }, initialSubmitStatus = MutationStatus.UNKNOWN)
        val actions = harness.actions()
        actions.perform(PlayerAction.PAUSE)
        val originalId = harness.submitted.single().id
        assertTrue(actions.canRetryUnknown())

        // A failed authoritative read marks the snapshot stale without changing the connected
        // Runtime generation or server-issued playback control identity. Web retains this intent.
        current = current.copy(freshness = SnapshotFreshness.STALE, readError = "read timed out")
        actions.reconcile()
        assertFalse(actions.isEnabled(PlayerAction.PAUSE))
        assertTrue(actions.canRetryUnknown())

        assertEquals(MutationOutcome.Submitted, actions.retryUnknown())
        assertEquals(listOf(originalId), harness.retriedIds)
        assertEquals(1, harness.submitted.size)
    }

    @Test
    fun unknownIntentCannotBeRetriedAfterTheObservedControlIdentityChanges() {
        var current = PlayerTestFixtures.state()
        val harness = Harness(stateProvider = { current }, initialSubmitStatus = MutationStatus.UNKNOWN)
        val actions = harness.actions()
        actions.perform(PlayerAction.PAUSE)
        val originalId = harness.submitted.single().id
        assertTrue(actions.canRetryUnknown())

        val changed = PlayerTestFixtures.observationJson.replace(
            "\"control_target\":{\"queue_item_id\":\"occurrence-1\",\"token\":\"opaque-token\"}",
            "\"control_target\":{\"queue_item_id\":\"occurrence-1\",\"token\":\"rotated-token\"}",
        )
        current = PlayerTestFixtures.state(observation = changed)
        actions.reconcile()

        assertFalse(actions.canRetryUnknown())
        assertNull(actions.retryUnknown())
        assertTrue("an obsolete unknown intent must not lock controls for the new identity", actions.isEnabled(PlayerAction.PAUSE))
        assertEquals(listOf(originalId), harness.submitted.map { it.id })
        assertTrue(harness.retriedIds.isEmpty())
    }

    @Test
    fun writeControlsAreRecheckedAgainstCurrentFreshSnapshotAtDispatch() {
        var current = PlayerTestFixtures.state()
        val harness = Harness(stateProvider = { current })
        val actions = harness.actions()
        current = PlayerTestFixtures.state(freshness = SnapshotFreshness.STALE)

        assertNull(actions.perform(PlayerAction.PAUSE))
        assertTrue(harness.submitted.isEmpty())
        assertFalse(actions.isEnabled(PlayerAction.PAUSE))
    }

    private fun stateWithPreviousAndNext(): SnapshotStoreState {
        val queue = """{"revision":19,"items":[{"queue_item_id":"played-1","song_id":"song-0","position":-1,"source":"MANUAL"},{"queue_item_id":"occurrence-1","song_id":"song-1","position":0,"source":"MANUAL"},{"queue_item_id":"next-1","song_id":"song-2","position":1,"source":"MANUAL"}]}"""
        return PlayerTestFixtures.state(queue = queue)
    }

    private inner class Harness(
        private val stateProvider: () -> SnapshotStoreState,
        private val recordPendingActivity: Boolean = false,
        private val initialSubmitStatus: MutationStatus = MutationStatus.PENDING,
    ) {
        val submitted = mutableListOf<MutationIntent>()
        val retriedIds = mutableListOf<String>()
        val activities = mutableListOf<OperationActivity>()
        private var idNumber = 0

        fun completeLatest() {
            val last = activities.lastIndex
            if (last >= 0) activities[last] = activities[last].copy(status = MutationStatus.SUCCESS)
        }

        fun actions() = PlayerActions(
            stateProvider = stateProvider,
            activitiesProvider = { activities.toList() },
            submitter = { intent ->
                submitted += intent
                activities += OperationActivity(
                    intentId = intent.id,
                    label = intent.label ?: intent.id,
                    status = initialSubmitStatus,
                    idempotencyKey = "key-${submitted.size}",
                    origin = origin,
                )
                MutationOutcome.Submitted
            },
            retryUnknownSubmitter = { id ->
                retriedIds += id
                val index = activities.indexOfFirst { it.intentId == id }
                if (index >= 0) activities[index] = activities[index].copy(status = MutationStatus.PENDING)
                MutationOutcome.Submitted
            },
            idFactory = { "intent-${++idNumber}" },
        )
    }
}
