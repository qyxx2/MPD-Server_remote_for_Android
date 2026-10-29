package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSourceDto
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueReorderDraftTest {
    private val baseline = QueueReorderBaseline(
        epoch = "epoch-a",
        connectionGeneration = 4,
        queueRevision = 17,
        currentOccurrence = occurrence("current", "same-song", 0),
        pendingOccurrences = listOf(
            occurrence("pending-a", "same-song", 1),
            occurrence("pending-b", "same-song", 2),
            occurrence("pending-c", "different-song", 3, QueueSourceDto.AUTOPLAY),
        ),
    )
    private val visiblePending = listOf("pending-a", "pending-b", "pending-c")

    @Test
    fun pendingDropBeforeTargetBuildsOccurrenceBasedOrderAndCommit() {
        val draft = QueueReorderDraft.begin(baseline, "pending-b", visiblePending)
        assertNotNull(draft)
        val moved = draft!!.moveBefore("pending-a")
        assertNotNull(moved)
        assertEquals(listOf("pending-b", "pending-a", "pending-c"), moved!!.orderedVisiblePendingIds)
        assertEquals(
            QueueReorderCommit(queueItemId = "pending-b", beforeQueueItemId = "pending-a"),
            moved.finish(baseline, controlsAvailable = true, currentVisiblePendingIds = visiblePending),
        )
    }

    @Test
    fun movingToEndProducesNullBeforeIdAndKeepsDuplicateSongsDistinct() {
        val draft = QueueReorderDraft.begin(baseline, "pending-a", visiblePending)!!
        val moved = draft.moveBefore(null)!!
        assertEquals(listOf("pending-b", "pending-c", "pending-a"), moved.orderedVisiblePendingIds)
        assertEquals("pending-a", moved.draggedQueueItemId)
        assertEquals(null, moved.finish(baseline, true, visiblePending)?.beforeQueueItemId)
        assertEquals("same-song", baseline.pendingOccurrences[0].songId)
        assertEquals("same-song", baseline.pendingOccurrences[1].songId)
        assertTrue(baseline.pendingOccurrences[0].queueItemId != baseline.pendingOccurrences[1].queueItemId)
    }

    @Test
    fun dropTargetUsesViewportCentersButKeepsFullOrderIndexesForOffscreenRows() {
        val expandedBaseline = baseline.copy(
            pendingOccurrences = baseline.pendingOccurrences +
                occurrence("pending-d", "song-d", 4) +
                occurrence("pending-e", "song-e", 5) +
                occurrence("pending-f", "song-f", 6),
        )
        val draft = QueueReorderDraft.begin(
            expandedBaseline,
            queueItemId = "pending-b",
            visiblePendingIds = listOf("pending-a", "pending-b", "pending-c", "pending-d", "pending-e", "pending-f"),
        )!!
        // The viewport has been scrolled: A and B are off-screen, so viewport row count is not a global index.
        val visibleCenters = mapOf("pending-c" to 40f, "pending-d" to 120f, "pending-e" to 200f)
        assertEquals("pending-d", draft.targetBefore(80f, visibleCenters))
        assertEquals("pending-f", draft.targetBefore(230f, visibleCenters))
        assertEquals("pending-c", draft.targetBefore(10f, visibleCenters))
        assertNull(draft.targetBefore(300f, mapOf("pending-e" to 200f, "pending-f" to 280f)))
    }

    @Test
    fun currentAndPlayedOccurrencesCannotStartDrafts() {
        assertNull(QueueReorderDraft.begin(baseline, "current", visiblePending))
        assertNull(QueueReorderDraft.begin(baseline, "played", visiblePending))
        assertNull(QueueReorderDraft.begin(baseline, "missing", visiblePending))
    }

    @Test
    fun selfAsDropTargetIsRejectedAndInvalidTargetDoesNotChangeDraft() {
        val draft = QueueReorderDraft.begin(baseline, "pending-b", visiblePending)!!
        assertNull(draft.moveBefore("pending-b"))
        assertNull(draft.moveBefore("current"))
        assertNull(draft.moveBefore("played"))
        assertEquals(visiblePending, draft.orderedVisiblePendingIds)
    }

    @Test
    fun unchangedOrderAndCancelledOrUnavailableControlsNeverCommit() {
        val draft = QueueReorderDraft.begin(baseline, "pending-b", visiblePending)!!
        assertNull(draft.finish(baseline, controlsAvailable = true, currentVisiblePendingIds = visiblePending))
        val moved = draft.moveBefore("pending-a")!!
        assertNull(moved.finish(baseline, controlsAvailable = false, currentVisiblePendingIds = visiblePending))
        assertNull(moved.finish(baseline, controlsAvailable = true, currentVisiblePendingIds = visiblePending.reversed()))
        // A cancelled pointer gesture is represented by dropping the ephemeral draft rather than calling finish.
        val cancelled: QueueReorderDraft? = null
        assertNull(cancelled)
    }

    @Test
    fun anyEpochGenerationRevisionCurrentOrMembershipChangeCancelsTheDraft() {
        val moved = QueueReorderDraft.begin(baseline, "pending-b", visiblePending)!!.moveBefore("pending-a")!!
        val changedBaselines = listOf(
            baseline.copy(epoch = "epoch-b"),
            baseline.copy(connectionGeneration = 5),
            baseline.copy(queueRevision = 18),
            baseline.copy(currentOccurrence = occurrence("new-current", "song", 0)),
            baseline.copy(pendingOccurrences = baseline.pendingOccurrences.reversed()),
            baseline.copy(pendingOccurrences = baseline.pendingOccurrences.mapIndexed { index, item ->
                if (index == 0) item.copy(position = 8) else item
            }),
            baseline.copy(pendingOccurrences = baseline.pendingOccurrences.mapIndexed { index, item ->
                if (index == 0) item.copy(source = QueueSourceDto.AUTOPLAY) else item
            }),
            baseline.copy(pendingOccurrences = baseline.pendingOccurrences.dropLast(1)),
        )
        changedBaselines.forEach { changed ->
            assertNull("must cancel for changed baseline $changed", moved.finish(changed, true, visiblePending))
        }
    }

    @Test
    fun baselineRequiresFreshWritableSnapshotAndCapturesRuntimeQueueIdentity() {
        val freshState = PlayerTestFixtures.state(queue = queueJson(), connectionGeneration = 8, snapshotConnectionGeneration = 8)
        val extracted = QueueReorderBaseline.from(freshState)
        assertNotNull(extracted)
        assertEquals("epoch-1", extracted!!.epoch)
        assertEquals(8, extracted.connectionGeneration)
        assertEquals(17L, extracted.queueRevision)
        assertEquals("current", extracted.currentOccurrence?.queueItemId)
        assertEquals(listOf("pending-a", "pending-b", "pending-c"), extracted.pendingOccurrences.map { it.queueItemId })
        assertEquals(QueueSourceDto.AUTOPLAY, extracted.pendingOccurrences.last().source)

        assertNull(QueueReorderBaseline.from(freshState.copy(freshness = SnapshotFreshness.STALE)))
        assertNull(QueueReorderBaseline.from(freshState.copy(snapshotConnectionGeneration = 7)))
        assertNull(QueueReorderBaseline.from(freshState.copy(connectionGeneration = 9)))
    }

    @Test
    fun malformedOrAmbiguousQueueBaselineIsRejected() {
        val state = PlayerTestFixtures.state(queue = """{"revision":17,"items":[
            {"queue_item_id":"current-1","song_id":"song","position":0,"source":"MANUAL","playback_context_id":null},
            {"queue_item_id":"current-2","song_id":"song","position":0,"source":"MANUAL","playback_context_id":null}
        ]}""")
        assertNull(QueueReorderBaseline.from(state))
        assertFalse(QueueReorderBaseline.from(PlayerTestFixtures.state(freshness = SnapshotFreshness.UNKNOWN)) != null)
    }

    private fun occurrence(
        id: String,
        songId: String,
        position: Int,
        source: QueueSourceDto = QueueSourceDto.MANUAL,
    ) = QueueReorderOccurrence(id, songId, position, source, playbackContextId = "context-$id")

    private fun queueJson() = """{"revision":17,"items":[
        {"queue_item_id":"current","song_id":"same-song","position":0,"source":"MANUAL","playback_context_id":"context-current"},
        {"queue_item_id":"pending-a","song_id":"same-song","position":1,"source":"MANUAL","playback_context_id":"context-a"},
        {"queue_item_id":"pending-b","song_id":"same-song","position":2,"source":"MANUAL","playback_context_id":"context-b"},
        {"queue_item_id":"pending-c","song_id":"different-song","position":3,"source":"AUTOPLAY","playback_context_id":"context-c"}
    ]}"""
}
