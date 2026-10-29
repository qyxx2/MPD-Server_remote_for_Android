package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSnapshotDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSourceDto
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import kotlinx.serialization.json.decodeFromJsonElement

/** The identity-bearing fields that must remain unchanged for a reorder gesture to stay valid. */
internal data class QueueReorderOccurrence(
    val queueItemId: String,
    val songId: String,
    val position: Int,
    val source: QueueSourceDto,
    val playbackContextId: String?,
) {
    companion object {
        fun from(item: QueueItemDto) = QueueReorderOccurrence(
            queueItemId = item.queueItemId,
            songId = item.songId,
            position = item.position,
            source = item.source,
            playbackContextId = item.playbackContextId,
        )
    }
}

/** Immutable canonical baseline. connectionGeneration is the owning Runtime's connection generation. */
internal data class QueueReorderBaseline(
    val epoch: String,
    val connectionGeneration: Long,
    val queueRevision: Long,
    val currentOccurrence: QueueReorderOccurrence?,
    val pendingOccurrences: List<QueueReorderOccurrence>,
) {
    companion object {
        fun from(state: SnapshotStoreState): QueueReorderBaseline? {
            if (!state.controlsEligible) return null
            val snapshot = state.snapshot ?: return null
            val queue = runCatching {
                JsonConfiguration.json.decodeFromJsonElement<QueueSnapshotDto>(snapshot.wire.queue)
            }.getOrNull() ?: return null
            val items = queue.items
            if (items.any { it.queueItemId.isBlank() || it.songId.isBlank() }) return null
            if (items.map { it.queueItemId }.distinct().size != items.size) return null
            val currentItems = items.filter { it.position == 0 }
            if (currentItems.size > 1) return null
            val pendingItems = items.filter { it.position > 0 }.sortedBy { it.position }
            if (pendingItems.map { it.position }.distinct().size != pendingItems.size) return null
            return QueueReorderBaseline(
                epoch = snapshot.epoch,
                connectionGeneration = state.connectionGeneration,
                queueRevision = queue.revision,
                currentOccurrence = currentItems.singleOrNull()?.let(QueueReorderOccurrence::from),
                pendingOccurrences = pendingItems.map(QueueReorderOccurrence::from),
            )
        }
    }
}

internal data class QueueReorderCommit(
    val queueItemId: String,
    val beforeQueueItemId: String?,
)

/**
 * Transient UI-only reorder draft. It never mutates the canonical snapshot; a commit can only be
 * derived if the exact epoch/generation/revision/current/member baseline still matches on release.
 */
internal class QueueReorderDraft private constructor(
    val baseline: QueueReorderBaseline,
    val draggedQueueItemId: String,
    val originalVisiblePendingIds: List<String>,
    val orderedVisiblePendingIds: List<String>,
) {
    val changed: Boolean get() = orderedVisiblePendingIds != originalVisiblePendingIds

    /**
     * Resolve a drop point from the currently visible viewport without confusing its local row
     * count with the full draft index. Below the last visible row targets the next occurrence in
     * the full order, even when that occurrence is currently off-screen.
     */
    fun targetBefore(pointerY: Float, visibleCenters: Map<String, Float>): String? {
        val remaining = orderedVisiblePendingIds.filterNot { it == draggedQueueItemId }
        val visibleOrdered = visibleCenters.entries
            .filter { it.key in remaining }
            .sortedBy { it.value }
        if (visibleOrdered.isEmpty()) return null
        visibleOrdered.firstOrNull { it.value >= pointerY }?.let { return it.key }
        val lastVisibleIndex = remaining.indexOf(visibleOrdered.last().key)
        return remaining.getOrNull(lastVisibleIndex + 1)
    }

    /** Insert the dragged occurrence before a visible pending occurrence, or at the visible end. */
    fun moveBefore(targetQueueItemId: String?): QueueReorderDraft? {
        if (targetQueueItemId == draggedQueueItemId) return null
        val remaining = orderedVisiblePendingIds.filterNot { it == draggedQueueItemId }
        val targetIndex = if (targetQueueItemId == null) {
            remaining.size
        } else {
            remaining.indexOf(targetQueueItemId).takeIf { it >= 0 } ?: return null
        }
        val reordered = remaining.toMutableList().apply { add(targetIndex, draggedQueueItemId) }
        return QueueReorderDraft(
            baseline = baseline,
            draggedQueueItemId = draggedQueueItemId,
            originalVisiblePendingIds = originalVisiblePendingIds,
            orderedVisiblePendingIds = reordered,
        )
    }

    fun isCompatible(
        currentBaseline: QueueReorderBaseline?,
        controlsAvailable: Boolean,
        currentVisiblePendingIds: List<String>,
    ): Boolean = controlsAvailable && currentBaseline == baseline &&
        currentVisiblePendingIds == originalVisiblePendingIds &&
        orderedVisiblePendingIds.size == originalVisiblePendingIds.size &&
        orderedVisiblePendingIds.toSet() == originalVisiblePendingIds.toSet() &&
        orderedVisiblePendingIds.count { it == draggedQueueItemId } == 1 &&
        baseline.pendingOccurrences.any { it.queueItemId == draggedQueueItemId }

    fun finish(
        currentBaseline: QueueReorderBaseline?,
        controlsAvailable: Boolean,
        currentVisiblePendingIds: List<String>,
    ): QueueReorderCommit? {
        if (!isCompatible(currentBaseline, controlsAvailable, currentVisiblePendingIds) || !changed) return null
        val sourceIndex = orderedVisiblePendingIds.indexOf(draggedQueueItemId)
        if (sourceIndex < 0) return null
        val beforeQueueItemId = orderedVisiblePendingIds.getOrNull(sourceIndex + 1)
        if (beforeQueueItemId == draggedQueueItemId) return null
        return QueueReorderCommit(draggedQueueItemId, beforeQueueItemId)
    }

    companion object {
        fun begin(
            baseline: QueueReorderBaseline,
            queueItemId: String,
            visiblePendingIds: List<String>,
        ): QueueReorderDraft? {
            val canonicalPendingIds = baseline.pendingOccurrences.map { it.queueItemId }.toSet()
            if (visiblePendingIds.isEmpty() || visiblePendingIds.any { it.isBlank() }) return null
            if (visiblePendingIds.distinct().size != visiblePendingIds.size) return null
            if (visiblePendingIds.any { it !in canonicalPendingIds } || queueItemId !in visiblePendingIds) return null
            if (baseline.pendingOccurrences.none { it.queueItemId == queueItemId && it.position > 0 }) return null
            return QueueReorderDraft(
                baseline = baseline,
                draggedQueueItemId = queueItemId,
                originalVisiblePendingIds = visiblePendingIds.toList(),
                orderedVisiblePendingIds = visiblePendingIds.toList(),
            )
        }
    }
}
