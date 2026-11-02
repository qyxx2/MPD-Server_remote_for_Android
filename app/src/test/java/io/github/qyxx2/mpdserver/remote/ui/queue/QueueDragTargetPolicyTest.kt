package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueDragTargetPolicyTest {
    @Test
    fun overlapBelowHalfIsRejected() {
        val result = QueueDragTargetPolicy.select(
            draggedBounds = Rect(0f, 0f, 40f, 100f),
            candidates = listOf(QueueDragTarget("target", Rect(0f, 0f, 100f, 100f))),
            draggedQueueItemId = "dragged",
        )
        assertNull(result)
    }

    @Test
    fun overlapExactlyHalfIsRejected() {
        val result = QueueDragTargetPolicy.select(
            draggedBounds = Rect(0f, 0f, 50f, 100f),
            candidates = listOf(QueueDragTarget("target", Rect(0f, 0f, 100f, 100f))),
            draggedQueueItemId = "dragged",
        )
        assertNull(result)
    }

    @Test
    fun overlapAboveHalfIsAccepted() {
        val result = QueueDragTargetPolicy.select(
            draggedBounds = Rect(0f, 0f, 51f, 100f),
            candidates = listOf(QueueDragTarget("target", Rect(0f, 0f, 100f, 100f))),
            draggedQueueItemId = "dragged",
        )
        assertEquals(QueueDragTargetSelection("target", 0.51f), result)
    }

    @Test
    fun greatestOverlapWins() {
        val result = QueueDragTargetPolicy.select(
            draggedBounds = Rect(25f, 0f, 100f, 100f),
            candidates = listOf(
                QueueDragTarget("lower", Rect(0f, 0f, 100f, 100f)),
                QueueDragTarget("higher", Rect(25f, 0f, 100f, 100f)),
            ),
            draggedQueueItemId = "dragged",
        )
        assertEquals("higher", result?.queueItemId)
        assertEquals(1f, result?.overlapRatio ?: 0f)
    }

    @Test
    fun equalOverlapChoosesNearestCenter() {
        val result = QueueDragTargetPolicy.select(
            draggedBounds = Rect(0f, 0f, 100f, 100f),
            candidates = listOf(
                QueueDragTarget("farther", Rect(20f, 0f, 120f, 100f)),
                QueueDragTarget("nearer", Rect(0f, -12.5f, 100f, 112.5f)),
            ),
            draggedQueueItemId = "dragged",
        )
        assertEquals("nearer", result?.queueItemId)
        assertEquals(0.8f, result?.overlapRatio ?: 0f)
    }

    @Test
    fun draggedOccurrenceIsNeverItsOwnTarget() {
        val result = QueueDragTargetPolicy.select(
            draggedBounds = Rect(0f, 0f, 100f, 100f),
            candidates = listOf(
                QueueDragTarget("dragged", Rect(0f, 0f, 100f, 100f)),
                QueueDragTarget("other", Rect(80f, 0f, 180f, 100f)),
            ),
            draggedQueueItemId = "dragged",
        )
        assertNull(result)
    }

    @Test
    fun emptyAndZeroAreaCandidatesAreIgnored() {
        assertNull(
            QueueDragTargetPolicy.select(
                draggedBounds = Rect(0f, 0f, 100f, 100f),
                candidates = emptyList(),
                draggedQueueItemId = "dragged",
            ),
        )
        assertNull(
            QueueDragTargetPolicy.select(
                draggedBounds = Rect(0f, 0f, 100f, 100f),
                candidates = listOf(
                    QueueDragTarget("zero-width", Rect(10f, 10f, 10f, 90f)),
                    QueueDragTarget("non-finite", Rect(Float.NaN, 0f, 100f, 100f)),
                ),
                draggedQueueItemId = "dragged",
            ),
        )
    }
}
