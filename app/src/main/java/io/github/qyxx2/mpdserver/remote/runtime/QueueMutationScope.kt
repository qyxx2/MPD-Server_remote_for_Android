package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSnapshotDto
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Stable, process-local precondition for explicitly retrying a Queue mutation. A retry is valid
 * only against the same server epoch, connected Runtime generation, Queue revision and occurrence
 * ordering. A failed read may mark the last accepted snapshot stale; that alone does not invalidate
 * a retry if origin/generation/snapshot identity are still unchanged, matching Web's retained intent.
 */
internal object QueueMutationScope {
    fun fingerprint(state: SnapshotStoreState): String? {
        if (state.connectionStatus != RuntimeConnectionStatus.CONNECTED ||
            state.snapshotConnectionGeneration != state.connectionGeneration ||
            state.freshness == SnapshotFreshness.UNKNOWN
        ) return null
        val snapshot = state.snapshot ?: return null
        val queue = runCatching {
            JsonConfiguration.json.decodeFromJsonElement<QueueSnapshotDto>(snapshot.wire.queue)
        }.getOrNull() ?: return null
        return buildJsonArray {
            add(snapshot.epoch)
            add(state.connectionGeneration)
            add(snapshot.wire.explicitStopGeneration)
            add(queue.revision)
            add(buildJsonArray {
                queue.items.sortedBy { it.position }.forEach { item ->
                    add(buildJsonArray {
                        add(item.queueItemId)
                        add(item.songId)
                        add(item.position)
                        add(item.source.name)
                        add(item.playbackContextId)
                    })
                }
            })
        }.toString()
    }
}
