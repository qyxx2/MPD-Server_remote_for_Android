package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto

/**
 * Ephemeral pixel-space extent for the history region. Pointer release/cancel commits no business
 * state: it only freezes this exact fractional height so the next gesture continues from it.
 */
internal data class PlayedRevealState(
    val revealedHeightPx: Float = 0f,
    val gestureActive: Boolean = false,
) {
    fun beginGesture(): PlayedRevealState =
        if (gestureActive) this else copy(gestureActive = true)

    fun dragBy(deltaY: Float): PlayedRevealState {
        if (!gestureActive || !deltaY.isFinite()) return this
        return copy(revealedHeightPx = (revealedHeightPx + deltaY).coerceAtLeast(0f))
    }

    fun freeze(): PlayedRevealState =
        if (!gestureActive) this else copy(gestureActive = false)

    fun clampToMeasuredEnd(measuredHistoryHeightPx: Float): PlayedRevealState {
        val safeEnd = measuredHistoryHeightPx.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        return copy(revealedHeightPx = revealedHeightPx.coerceAtMost(safeEnd))
    }
}

/** Client-only reveal window over one already-read authoritative Played list; never fetches data. */
internal object PlayedRevealWindow {
    const val BATCH_SIZE = 100

    fun ordered(items: List<QueueItemDto>): List<QueueItemDto> =
        items.asSequence()
            .filter { it.position < 0 }
            .sortedWith(compareByDescending<QueueItemDto> { it.position }.thenBy { it.queueItemId })
            .toList()

    fun initialCount(totalCount: Int): Int =
        totalCount.coerceAtLeast(0).coerceAtMost(BATCH_SIZE)

    fun nextCount(currentCount: Int, totalCount: Int): Int {
        val total = totalCount.coerceAtLeast(0)
        if (currentCount >= total) return total
        return (currentCount.coerceAtLeast(0) + BATCH_SIZE).coerceAtMost(total)
    }
}


/** Identity of one permitted Played read; a delayed response is never applied to a newer snapshot. */
internal data class PlayedReadGeneration(
    val epoch: String?,
    val queueRevision: Long?,
    val connectionGeneration: Long,
    val explicitStopGeneration: Long?,
    val originAddress: String,
    val requestGeneration: Long,
) {
    fun stillMatches(
        currentEpoch: String?,
        currentQueueRevision: Long?,
        currentConnectionGeneration: Long,
        currentExplicitStopGeneration: Long?,
        currentOriginAddress: String,
        currentRequestGeneration: Long,
        currentSnapshotReady: Boolean,
    ): Boolean =
        epoch == currentEpoch &&
            queueRevision == currentQueueRevision &&
            connectionGeneration == currentConnectionGeneration &&
            explicitStopGeneration == currentExplicitStopGeneration &&
            originAddress == currentOriginAddress &&
            requestGeneration == currentRequestGeneration &&
            !currentSnapshotReady
}
