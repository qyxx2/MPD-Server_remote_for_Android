package io.github.qyxx2.mpdserver.remote.runtime

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackStateDto
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerFacts
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Stable control-scope identity shared by the Runtime command gateway and the UI intent adapter.
 *
 * Deliberately excludes read freshness: an UNKNOWN write remains attached to its original
 * connection/target while a same-origin read temporarily becomes stale.
 */
internal data class PlayerControlIdentity(
    val epoch: String?,
    val generation: Long,
    val boundQueueItemId: String?,
    val targetQueueItemId: String?,
    val targetToken: String?,
    val actualEntryId: Long?,
    val businessSongId: String?,
    val businessContextId: String?,
    val queueCurrentItemId: String?,
) {
    companion object {
        fun from(snapshot: SnapshotStoreState): PlayerControlIdentity? {
            val canonical = snapshot.snapshot ?: return null
            val facts = PlayerFacts.from(snapshot)
            val observation = facts.playbackObservation
            val wirePlayback = runCatching {
                JsonConfiguration.json.decodeFromJsonElement<PlaybackStateDto>(canonical.wire.playback)
            }.getOrNull()
            return PlayerControlIdentity(
                epoch = canonical.epoch,
                generation = snapshot.connectionGeneration,
                boundQueueItemId = observation?.boundQueueItemId,
                targetQueueItemId = observation?.controlTarget?.queueItemId,
                targetToken = observation?.controlTarget?.token,
                actualEntryId = observation?.actualCurrent?.entryId,
                businessSongId = wirePlayback?.songId,
                businessContextId = wirePlayback?.playbackContextId,
                queueCurrentItemId = facts.queue?.items?.firstOrNull { it.position == 0 }?.queueItemId,
            )
        }
    }
}
