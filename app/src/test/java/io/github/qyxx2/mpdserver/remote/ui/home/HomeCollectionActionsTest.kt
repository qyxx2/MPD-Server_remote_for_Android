package io.github.qyxx2.mpdserver.remote.ui.home

import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.runtime.MutationIntent
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import io.github.qyxx2.mpdserver.remote.runtime.QueueMutationScope
import io.github.qyxx2.mpdserver.remote.ui.library.PlaybackMutationGateway
import io.github.qyxx2.mpdserver.remote.ui.library.LibraryCollectionActions
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerTestFixtures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCollectionActionsTest {
    @Test
    fun recentUpdatesUsesWholeLibraryAndServerModifiedTimeDescendingOrder() {
        val h = Harness()
        assertEquals(MutationOutcome.Submitted, h.actions().playRecentUpdates(randomize = false))
        val body = JsonConfiguration.json.parseToJsonElement(h.submitted.single().payload!!).jsonObject
        assertEquals("LIBRARY", body.getValue("source_type").jsonPrimitive.content)
        assertEquals("modified_at", body.getValue("sort_by").jsonPrimitive.content)
        assertEquals("desc", body.getValue("sort_order").jsonPrimitive.content)
        assertFalse(body.getValue("randomize").jsonPrimitive.content.toBoolean())
        assertFalse(body.containsKey("song_ids") && body.getValue("song_ids").toString() != "[]")
    }

    @Test
    fun recentShuffleDelegatesRandomizationToServerWithoutLocalSongOrder() {
        val h = Harness()
        h.actions().playRecentUpdates(randomize = true)
        val body = JsonConfiguration.json.parseToJsonElement(h.submitted.single().payload!!).jsonObject
        assertTrue(body.getValue("randomize").jsonPrimitive.content.toBoolean())
        assertEquals("LIBRARY", body.getValue("source_type").jsonPrimitive.content)
        assertEquals("modified_at", body.getValue("sort_by").jsonPrimitive.content)
        assertFalse(body.containsKey("song_ids"))
        assertEquals("desc", body.getValue("sort_order").jsonPrimitive.content)
    }

    @Test
    fun historyUsesEveryUniquePersistentHistorySongIdInFirstSeenOrder() {
        val h = Harness()
        val history = HomePlaybackHistory(
            cards = emptyList(),
            songIds = listOf("history-first", "history-second", "history-first", "history-third"),
        )
        h.actions().playHistory(history, randomize = false)
        val body = JsonConfiguration.json.parseToJsonElement(h.submitted.single().payload!!).jsonObject
        assertEquals("SONGS", body.getValue("source_type").jsonPrimitive.content)
        assertEquals(
            listOf("history-first", "history-second", "history-third"),
            body.getValue("song_ids").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun historyShuffleKeepsServerHistoryOrderAndOnlySetsRandomizeFlag() {
        val h = Harness()
        h.actions().playHistory(
            HomePlaybackHistory(emptyList(), listOf("z", "a", "m", "z")),
            randomize = true,
        )
        val body = JsonConfiguration.json.parseToJsonElement(h.submitted.single().payload!!).jsonObject
        assertEquals("[\"z\",\"a\",\"m\"]", body.getValue("song_ids").toString())
        assertTrue(body.getValue("randomize").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun autoplayUsesExactQueueOccurrenceAndExplicitEmptyBody() {
        val h = Harness()
        val state = autoplayState()
        h.state = state
        val identity = identity(state)
        val card = autoplayCard("occurrence-second", "same-song")

        assertEquals(MutationOutcome.Submitted, h.actions().playAutoplay(card, identity))
        val intent = h.submitted.single()
        assertEquals("POST", intent.method)
        assertEquals("/api/playback/queue/items/occurrence-second/play", intent.path)
        assertEquals("{}", intent.payload)
        assertEquals(QueueMutationScope.fingerprint(state), intent.retryScope)
    }

    @Test
    fun autoplayUnknownRetryReusesTheOriginalIntentAndRejectsChangedPlaybackContext() {
        val original = autoplayState()
        val h = Harness(original)
        val actions = h.actions()
        val card = autoplayCard("occurrence-second", "same-song")
        assertEquals(MutationOutcome.Submitted, actions.playAutoplay(card, identity(original)))
        val intent = h.submitted.single()
        assertEquals(intent.id, actions.lastSubmittedIntentId)
        h.activities.value = listOf(activity(intent.id, MutationStatus.UNKNOWN))

        assertEquals(MutationOutcome.Submitted, actions.retryAutoplayUnknown(intent.id))
        assertEquals(listOf(intent.id), h.retried)
        assertEquals(1, h.submitted.size)

        val secondState = autoplayState()
        val staleRetry = Harness(secondState)
        val staleActions = staleRetry.actions()
        staleActions.playAutoplay(card, identity(secondState))
        val staleIntent = staleRetry.submitted.single()
        staleRetry.activities.value = listOf(activity(staleIntent.id, MutationStatus.UNKNOWN))
        // Queue revision and occurrence fingerprint remain unchanged; only Playback context changed.
        staleRetry.state = autoplayState(playbackContext = "ctx-b")
        assertNull(staleActions.retryAutoplayUnknown(staleIntent.id))
        assertTrue(staleRetry.retried.isEmpty())
        assertEquals(1, staleRetry.submitted.size)
    }

    @Test
    fun activityRetryPermissionRequiresSameCurrentAutoplayOccurrence() {
        val original = autoplayState()
        val h = Harness(original)
        val actions = h.actions()
        val card = autoplayCard("occurrence-second", "same-song")
        actions.playAutoplay(card, identity(original))
        val intentId = h.submitted.single().id
        h.activities.value = listOf(activity(intentId, MutationStatus.UNKNOWN))

        assertTrue(actions.canRetryAutoplayUnknown(intentId))
        h.state = autoplayState(playbackContext = "ctx-b")
        assertFalse(actions.canRetryAutoplayUnknown(intentId))
    }

    @Test
    fun autoplayRejectsRemovedReorderedOrRecontextualizedOccurrence() {
        val original = autoplayState()
        val identity = identity(original)
        val clicked = autoplayCard("occurrence-second", "same-song")

        val removed = Harness(autoplayState(includeSecond = false))
        assertNull(removed.actions().playAutoplay(clicked, identity))
        assertTrue(removed.submitted.isEmpty())

        val reordered = Harness(autoplayState(queueRevision = 19, swapPositions = true))
        assertNull(reordered.actions().playAutoplay(clicked, identity))
        assertTrue(reordered.submitted.isEmpty())

        val changedContext = Harness(autoplayState(context = "ctx-b"))
        assertNull(changedContext.actions().playAutoplay(clicked, identity))
        assertTrue(changedContext.submitted.isEmpty())
    }

    @Test
    fun autoplayRejectsChangedEpochGenerationDisabledAutoplayAndStaleSnapshot() {
        val original = autoplayState()
        val identity = identity(original)
        val card = autoplayCard("occurrence-first", "same-song")

        val newEpoch = Harness(original.copy(snapshot = io.github.qyxx2.mpdserver.remote.runtime.FullStateSnapshot.from(
            original.snapshot!!.wire.copy(epoch = "new-epoch"),
        )))
        assertNull(newEpoch.actions().playAutoplay(card, identity))

        val newGeneration = Harness(original.copy(connectionGeneration = 2))
        assertNull(newGeneration.actions().playAutoplay(card, identity))

        val disabled = Harness(autoplayState(autoplayEnabled = false))
        assertNull(disabled.actions().playAutoplay(card, identity(disabled.state)))

        val stale = Harness(original.copy(freshness = io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness.STALE))
        assertNull(stale.actions().playAutoplay(card, identity))
    }

    @Test
    fun autoplayNeverFindsDuplicateBySongIdAndBlocksOtherUnresolvedMutations() {
        val state = autoplayState()
        val h = Harness(state, listOf(activity("queue:pending", MutationStatus.PENDING)))
        assertNull(h.actions().playAutoplay(autoplayCard("occurrence-second", "same-song"), identity(state)))
        assertTrue(h.submitted.isEmpty())

        val available = Harness(state)
        assertNull(available.actions().playAutoplay(autoplayCard("same-song", "same-song"), identity(state)))
        assertTrue(available.submitted.isEmpty())
    }

    private class Harness(
        var state: io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState = PlayerTestFixtures.state(),
        activities: List<OperationActivity> = emptyList(),
    ) {
        private var nextId = 0
        val submitted = mutableListOf<MutationIntent>()
        val retried = mutableListOf<String>()
        val activities = MutableStateFlow(activities)

        fun actions(): HomeCollectionActions {
            val gateway = PlaybackMutationGateway(
                stateProvider = { state },
                activities = activities,
                submitter = { intent -> submitted += intent; MutationOutcome.Submitted },
                retryUnknown = { intentId -> retried += intentId; MutationOutcome.Submitted },
                canRetryUnknown = { intentId -> activities.value.any { it.intentId == intentId && it.status == MutationStatus.UNKNOWN } },
                idFactory = { "home-${++nextId}" },
            )
            return HomeCollectionActions(gateway)
        }
    }

    private fun activity(id: String, status: MutationStatus) = OperationActivity(
        intentId = id, label = "other", status = status, idempotencyKey = "key",
        origin = ServerOrigin.parse("http://music.example:18000"),
    )

    private fun identity(state: io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState): HomeAutoplayIdentity {
        val snapshot = state.snapshot!!
        val playback = JsonConfiguration.json.decodeFromJsonElement<io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackStateDto>(snapshot.wire.playback)
        return HomeAutoplayIdentity(
            epoch = snapshot.epoch,
            connectionGeneration = state.connectionGeneration,
            queueRevision = snapshot.queueRevision,
            playbackContextId = playback.playbackContextId!!,
            queueFingerprint = QueueMutationScope.fingerprint(state)!!,
        )
    }

    private fun autoplayCard(queueItemId: String, songId: String) = HomeArtworkCard(
        key = "queue:$queueItemId", kind = HomeCardKind.AUTOPLAY_OCCURRENCE,
        song = null, songId = songId, albumId = null, artworkSongId = null,
        artwork = null, isPlayable = true, queueItemId = queueItemId,
    )

    private fun autoplayState(
        queueRevision: Long = 18,
        context: String = "ctx-a",
        autoplayEnabled: Boolean = true,
        playbackContext: String = context,
        swapPositions: Boolean = false,
        includeSecond: Boolean = true,
    ): io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState {
        val secondPosition = if (swapPositions) 1 else 2
        val firstPosition = if (swapPositions) 2 else 1
        val secondItem = if (includeSecond) ",\n            {\"queue_item_id\":\"occurrence-second\",\"song_id\":\"same-song\",\"position\":$secondPosition,\"source\":\"AUTOPLAY\",\"playback_context_id\":\"$context\"}" else ""
        val queue = """{"revision":$queueRevision,"items":[
            {"queue_item_id":"occurrence-first","song_id":"same-song","position":$firstPosition,"source":"AUTOPLAY","playback_context_id":"$context"}$secondItem,
            {"queue_item_id":"manual-item","song_id":"other-song","position":3,"source":"MANUAL","playback_context_id":null}
        ]}"""
        val playback = """{"song_id":"current","state":"PLAYING","playback_context_id":"$playbackContext","position_seconds":8.0,"autoplay_enabled":$autoplayEnabled,"updated_at":"2026-10-31T10:00:00Z"}"""
        return PlayerTestFixtures.state(queue = queue, playback = playback)
    }
}
