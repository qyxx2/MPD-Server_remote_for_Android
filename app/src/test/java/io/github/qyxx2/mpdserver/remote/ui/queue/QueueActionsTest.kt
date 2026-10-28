package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.runtime.MutationIntent
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerTestFixtures
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class QueueActionsTest {
    private val origin = ServerOrigin.parse("http://music.example:18000")

    @Test fun playUsesClickedOccurrenceAndEscapesItsPathSegment() {
        val h = Harness()
        val actions = h.actions()
        assertEquals(MutationOutcome.Submitted, actions.play("pending/a!*'()", "重复标题"))
        val intent = h.submitted.single()
        assertEquals("POST", intent.method)
        assertEquals("/api/playback/queue/items/pending%2Fa!*'()/play", intent.path)
        assertEquals("{}", intent.payload)
        assertTrue(intent.label!!.contains("pending/a"))
    }

    @Test fun playCanTargetPlayedOccurrenceWithoutSubstitutingDuplicateSong() {
        val h = Harness()
        assertEquals(MutationOutcome.Submitted, h.actions().play("played", "Same title"))
        assertEquals("/api/playback/queue/items/played/play", h.submitted.single().path)
    }

    @Test fun moveNextAndAddUseWebBeforeOccurrenceSemantics() {
        val h = Harness()
        val actions = h.actions()
        actions.move("pending-b", QueueMove.NEXT, "B")
        actions.move("pending-a", QueueMove.ADD, "A")
        assertEquals("/api/playback/queue/items/pending-b", h.submitted[0].path)
        assertEquals("PUT", h.submitted[0].method)
        assertEquals("pending-a", JsonConfiguration.json.parseToJsonElement(h.submitted[0].payload!!).jsonObject
            .getValue("before_queue_item_id").jsonPrimitive.content)
        assertEquals("/api/playback/queue/items/pending-a", h.submitted[1].path)
        assertEquals(JsonNull, JsonConfiguration.json.parseToJsonElement(h.submitted[1].payload!!).jsonObject
            .getValue("before_queue_item_id"))
    }

    @Test fun everyVisibleRowKeepsWebMenuAvailabilityButOnlyPendingRowsCanMove() {
        val h = Harness()
        val actions = h.actions()
        assertTrue(actions.canShowMenu("current"))
        assertTrue(actions.canShowMenu("played"))
        actions.move("current", QueueMove.NEXT, "Current")
        actions.move("played", QueueMove.ADD, "Played")
        actions.move("pending-a", QueueMove.NEXT, "A")
        actions.move("pending/a!*'()", QueueMove.ADD, "A")
        assertTrue(h.submitted.isEmpty())
    }

    @Test fun removeUsesClickedCurrentOccurrenceWithoutSongIdSubstitution() {
        val h = Harness()
        h.actions().remove("current", "Duplicate title")
        assertEquals("DELETE", h.submitted.single().method)
        assertEquals("/api/playback/queue/items/current", h.submitted.single().path)
        assertTrue(h.submitted.single().label!!.contains("current"))
    }

    @Test fun removeAndClearUseOnlyQueueDeleteRoutes() {
        val h = Harness()
        val actions = h.actions()
        actions.remove("pending-b", "B")
        actions.clear()
        assertEquals(listOf("DELETE", "DELETE"), h.submitted.map { it.method })
        assertEquals(listOf("/api/playback/queue/items/pending-b", "/api/playback/queue"), h.submitted.map { it.path })
        assertNotEquals("independent user actions must receive independent intent identities",
            h.submitted[0].id, h.submitted[1].id)
        assertTrue(h.submitted.none { it.path.contains("/stop") })
    }

    @Test fun savePreservesWebNameAndLeavesHiddenQueueMembershipToServer() {
        val h = Harness()
        h.actions().save("  Road Trip  ")
        val intent = h.submitted.single()
        assertEquals("POST", intent.method)
        assertEquals("/api/playback/queue/save-as-playlist", intent.path)
        assertEquals("  Road Trip  ", JsonConfiguration.json.parseToJsonElement(intent.payload!!).jsonObject
            .getValue("name").jsonPrimitive.content)
    }

    @Test fun staleSnapshotAndAnyUnresolvedMutationBlockEveryWriteAndMenu() {
        val stale = Harness(stateProvider = { PlayerTestFixtures.state(freshness = SnapshotFreshness.STALE) })
        val staleActions = stale.actions()
        assertFalse(staleActions.isEnabled)
        assertFalse(staleActions.canShowMenu("pending-a"))
        assertNull(staleActions.play("pending-a", "A"))
        assertNull(staleActions.move("pending-b", QueueMove.NEXT, "B"))
        assertNull(staleActions.remove("pending-b", "B"))
        assertNull(staleActions.clear())
        assertNull(staleActions.save("Road Trip"))
        assertTrue(stale.submitted.isEmpty())

        val unknown = Harness(activities = mutableListOf(activity("unknown", MutationStatus.UNKNOWN)))
        val lockedActions = unknown.actions()
        assertFalse(lockedActions.isEnabled)
        assertFalse(lockedActions.canShowMenu("pending-a"))
        assertNull(lockedActions.play("pending-a", "A"))
        assertNull(lockedActions.move("pending-b", QueueMove.NEXT, "B"))
        assertNull(lockedActions.remove("pending-b", "B"))
        assertNull(lockedActions.clear())
        assertNull(lockedActions.save("Road Trip"))
        assertTrue(unknown.submitted.isEmpty())
    }

    private fun activity(id: String, status: MutationStatus) = OperationActivity(
        intentId = id, label = "Queue action", status = status, idempotencyKey = "key", origin = origin,
    )

    private class Harness(
        val activities: MutableList<OperationActivity> = mutableListOf(),
        private val stateProvider: () -> io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState = {
            val queue = """{"revision":17,"items":[
                {"queue_item_id":"current","song_id":"same-song","position":0,"source":"MANUAL","playback_context_id":null},
                {"queue_item_id":"pending-a","song_id":"same-song","position":1,"source":"MANUAL","playback_context_id":null},
                {"queue_item_id":"pending-b","song_id":"same-song","position":2,"source":"MANUAL","playback_context_id":null},
                {"queue_item_id":"pending/a!*'()","song_id":"same-song","position":3,"source":"MANUAL","playback_context_id":null},
                {"queue_item_id":"played","song_id":"same-song","position":-1,"source":"MANUAL","playback_context_id":null}
            ]}"""
            PlayerTestFixtures.state(queue = queue)
        },
    ) {
        val submitted = mutableListOf<MutationIntent>()
        fun actions() = QueueActions(
            stateProvider = stateProvider,
            activitiesProvider = { activities.toList() },
            submitter = { submitted += it; MutationOutcome.Submitted },
            idFactory = { "fixed-${submitted.size}" },
        )
    }
}
