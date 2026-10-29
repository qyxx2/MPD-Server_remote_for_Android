package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto

/**
 * Full-list display order matching Web: a newest-first Played prefix is reversed for rendering,
 * so older rows are above newer rows and position -1 sits immediately before current position 0.
 */
internal object PlayedHistoryOrder {
    fun inDisplayOrder(items: List<QueueItemDto>): List<QueueItemDto> =
        items.asSequence()
            .filter { it.position < 0 }
            .sortedWith(compareBy<QueueItemDto> { it.position }.thenBy { it.queueItemId })
            .toList()
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
