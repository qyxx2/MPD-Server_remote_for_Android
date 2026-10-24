package io.github.qyxx2.mpdserver.remote.runtime

/** Pure decision function; it never mutates the canonical Store. */
class SnapshotAcceptancePolicy {
    fun evaluate(
        current: FullStateSnapshot?,
        currentConnectionGeneration: Long,
        incoming: FullStateSnapshot,
        incomingConnectionGeneration: Long,
    ): SnapshotRejection {
        if (incomingConnectionGeneration < currentConnectionGeneration) return SnapshotRejection.STALE_CONNECTION
        if (current == null) return SnapshotRejection.ACCEPTED
        if (incoming.epoch != current.epoch) {
            return if (incomingConnectionGeneration > currentConnectionGeneration) {
                SnapshotRejection.ACCEPTED
            } else {
                SnapshotRejection.EPOCH_CHANGE_WITHOUT_NEW_CONNECTION
            }
        }
        if (incoming.sequence < current.sequence) return SnapshotRejection.OLDER_SEQUENCE
        if (incoming.libraryRevision < current.libraryRevision ||
            incoming.playlistRevision < current.playlistRevision ||
            incoming.queueRevision < current.queueRevision
        ) return SnapshotRejection.REVISION_REGRESSION
        if (incoming.librarySyncGeneration < current.librarySyncGeneration ||
            incoming.explicitStopGeneration < current.explicitStopGeneration
        ) return SnapshotRejection.GENERATION_REGRESSION
        return SnapshotRejection.ACCEPTED
    }
}

enum class SnapshotRejection {
    ACCEPTED,
    STALE_CONNECTION,
    EPOCH_CHANGE_WITHOUT_NEW_CONNECTION,
    OLDER_SEQUENCE,
    REVISION_REGRESSION,
    GENERATION_REGRESSION,
}
