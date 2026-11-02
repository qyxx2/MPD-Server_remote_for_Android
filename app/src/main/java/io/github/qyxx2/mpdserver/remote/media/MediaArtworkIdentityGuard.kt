package io.github.qyxx2.mpdserver.remote.media

import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerFacts

/** Revalidates the full canonical identity immediately before a decoded image is published. */
object MediaArtworkIdentityGuard {
    fun isCurrent(
        state: SnapshotStoreState,
        currentOriginAddress: String,
        expected: ConfirmedMediaSessionIdentity,
    ): Boolean {
        if (expected.artworkId.isNullOrBlank() || !state.controlsEligible) return false
        if (currentOriginAddress != expected.originAddress ||
            state.connectionGeneration != expected.connectionGeneration ||
            state.snapshotConnectionGeneration != expected.connectionGeneration
        ) return false

        val snapshot = state.snapshot ?: return false
        if (snapshot.epoch != expected.epoch ||
            snapshot.libraryRevision != expected.libraryRevision ||
            snapshot.explicitStopGeneration != expected.explicitStopGeneration
        ) return false

        val facts = PlayerFacts.from(state)
        val occurrence = facts.currentOccurrence ?: return false
        if (facts.queueDecodeFailed || facts.currentOccurrenceAmbiguous ||
            occurrence.queueItemId != expected.queueItemId ||
            occurrence.songId != expected.songId
        ) return false

        val song = facts.currentSong ?: return false
        return song.songId == expected.songId &&
            song.artwork?.artworkId?.takeIf(String::isNotBlank) == expected.artworkId
    }
}
