package io.github.qyxx2.mpdserver.remote.ui.queue

/**
 * Chooses the client-side metadata-prefetch window over an already-authoritative Queue snapshot.
 * This does not paginate or fetch Queue data; it only stages song resources before rows are reached.
 */
internal object QueuePrefetchWindow {
    const val BATCH_SIZE = 100
    const val PREFETCH_THRESHOLD = 20

    fun endExclusive(
        lastVisibleIndex: Int,
        totalItems: Int,
        batchSize: Int = BATCH_SIZE,
        prefetchThreshold: Int = PREFETCH_THRESHOLD,
    ): Int {
        require(totalItems >= 0)
        require(batchSize >= 1)
        require(prefetchThreshold >= 0)
        if (totalItems == 0) return 0

        val lastVisible = lastVisibleIndex.coerceAtLeast(0)
        val batchesNeeded = ((lastVisible + prefetchThreshold) / batchSize + 1).coerceAtLeast(1)
        return minOf(totalItems, batchesNeeded * batchSize)
    }
}
