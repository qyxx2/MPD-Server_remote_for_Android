package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.compose.ui.geometry.Rect
import kotlin.math.hypot

internal data class QueueDragTarget(
    val queueItemId: String,
    val bounds: Rect,
)

internal data class QueueDragTargetSelection(
    val queueItemId: String,
    val overlapRatio: Float,
)

/** Pure geometry policy for choosing an insertion target from measured pending Queue rows. */
internal object QueueDragTargetPolicy {
    fun select(
        draggedBounds: Rect,
        candidates: List<QueueDragTarget>,
        draggedQueueItemId: String,
    ): QueueDragTargetSelection? {
        if (!draggedBounds.hasPositiveFiniteArea()) return null
        val draggedCenter = draggedBounds.center
        return candidates.asSequence()
            .filter { it.queueItemId != draggedQueueItemId && it.bounds.hasPositiveFiniteArea() }
            .mapNotNull { target ->
                val bounds = target.bounds
                val intersectionWidth = (
                    minOf(draggedBounds.right, bounds.right) - maxOf(draggedBounds.left, bounds.left)
                ).coerceAtLeast(0f)
                val intersectionHeight = (
                    minOf(draggedBounds.bottom, bounds.bottom) - maxOf(draggedBounds.top, bounds.top)
                ).coerceAtLeast(0f)
                val intersectionArea = intersectionWidth * intersectionHeight
                val targetArea = bounds.width * bounds.height
                val overlapRatio = intersectionArea / targetArea
                if (!overlapRatio.isFinite() || overlapRatio <= 0.5f) {
                    null
                } else {
                    val targetCenter = bounds.center
                    val centerDistance = hypot(
                        (targetCenter.x - draggedCenter.x).toDouble(),
                        (targetCenter.y - draggedCenter.y).toDouble(),
                    )
                    CandidateSelection(
                        selection = QueueDragTargetSelection(target.queueItemId, overlapRatio),
                        centerDistance = centerDistance,
                    )
                }
            }
            .sortedWith(
                compareByDescending<CandidateSelection> { it.selection.overlapRatio }
                    .thenBy { it.centerDistance },
            )
            .firstOrNull()
            ?.selection
    }

    private fun Rect.hasPositiveFiniteArea(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            width > 0f && height > 0f && (width * height).isFinite()

    private data class CandidateSelection(
        val selection: QueueDragTargetSelection,
        val centerDistance: Double,
    )
}
