package io.github.qyxx2.mpdserver.remote.ui.home

import io.github.qyxx2.mpdserver.remote.protocol.JsonConfiguration
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaybackStateDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSnapshotDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSourceDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import kotlinx.coroutines.flow.StateFlow
import io.github.qyxx2.mpdserver.remote.runtime.QueueMutationScope
import io.github.qyxx2.mpdserver.remote.ui.library.CollectionPlaybackRequest
import io.github.qyxx2.mpdserver.remote.ui.library.LibraryCollectionActions
import io.github.qyxx2.mpdserver.remote.ui.library.PlaybackMutationGateway
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl

private data class AutoplayRetryIntent(
    val identity: HomeAutoplayIdentity,
    val queueItemId: String,
    val songId: String,
)

/** Home-only actions layered on the same shared collection/song write gate. */
class HomeCollectionActions internal constructor(
    private val gateway: PlaybackMutationGateway,
) {
    private val collections = LibraryCollectionActions(gateway)
    private val autoplayRetryIntents = linkedMapOf<String, AutoplayRetryIntent>()

    constructor(runtime: AppRuntime) : this(PlaybackMutationGateway(runtime))

    fun canPlay(resourceFresh: Boolean = true): Boolean = gateway.canSubmit(resourceFresh)
    fun activities(): StateFlow<List<OperationActivity>> = gateway.activities()

    /** Plays the full Recent Updates collection; the visible horizontal rail is never the source. */
    fun playRecentUpdates(
        randomize: Boolean,
        resourceFresh: Boolean = true,
    ): MutationOutcome? {
        val request = CollectionPlaybackRequest.forSongs(
            sortBy = "modified_at",
            sortOrder = "desc",
            randomize = randomize,
        ) ?: return null
        return collections.play(request, resourceFresh)
    }

    /** Uses the complete persistent History projection, preserving first-seen server order. */
    fun playHistory(
        history: HomePlaybackHistory,
        randomize: Boolean,
        resourceFresh: Boolean = true,
    ): MutationOutcome? {
        val request = CollectionPlaybackRequest.forSongIds(history.songIds) ?: return null
        return collections.play(request.withRandomize(randomize), resourceFresh)
    }

    /**
     * Plays one current AutoPlay queue occurrence. All checks use the canonical accepted snapshot;
     * no fallback by Song ID is permitted because duplicate Queue occurrences are distinct actions.
     */
    fun playAutoplay(
        card: HomeArtworkCard,
        identity: HomeAutoplayIdentity?,
        resourceFresh: Boolean = true,
    ): MutationOutcome? {
        if (!resourceFresh || card.kind != HomeCardKind.AUTOPLAY_OCCURRENCE || !card.isPlayable) return null
        val queueItemId = card.queueItemId?.takeIf(String::isNotBlank) ?: return null
        val songId = card.songId?.takeIf(String::isNotBlank) ?: return null
        val expected = identity ?: return null
        val state = gateway.currentState()
        if (!isCurrentAutoplayOccurrence(state, expected, queueItemId, songId)) return null

        val path = "http://localhost/".toHttpUrl().newBuilder()
            .addPathSegments("api/playback/queue/items")
            .addPathSegment(queueItemId)
            .addPathSegment("play")
            .build().encodedPath
        val intentId = gateway.newIntentId("home-autoplay")
        val outcome = gateway.submitWithIntentId(
            intentId = intentId,
            path = path,
            payload = "{}",
            label = "播放推荐曲目 · ${card.song?.title ?: songId}",
            resourceFresh = resourceFresh,
            retryScope = expected.queueFingerprint,
            precondition = {
                isCurrentAutoplayOccurrence(
                    gateway.currentState(),
                    expected,
                    queueItemId,
                    songId,
                )
            },
        )
        if (outcome == MutationOutcome.Submitted || outcome == MutationOutcome.DuplicatePending) {
            synchronized(autoplayRetryIntents) {
                autoplayRetryIntents[intentId] = AutoplayRetryIntent(expected, queueItemId, songId)
            }
        }
        return outcome
    }

    /** Activity surfaces may expose an AutoPlay retry only while its exact occurrence is still current. */
    fun canRetryAutoplayUnknown(intentId: String): Boolean {
        val retry = synchronized(autoplayRetryIntents) { autoplayRetryIntents[intentId] } ?: return false
        return gateway.canRetry(intentId) && isCurrentAutoplayOccurrence(
            gateway.currentState(),
            retry.identity,
            retry.queueItemId,
            retry.songId,
        )
    }

    /** Retrying AutoPlay must retain its original Queue occurrence and playback context. */
    fun retryAutoplayUnknown(intentId: String): MutationOutcome? {
        val retry = synchronized(autoplayRetryIntents) { autoplayRetryIntents[intentId] } ?: return null
        return gateway.retry(intentId) {
            val current = isCurrentAutoplayOccurrence(
                gateway.currentState(),
                retry.identity,
                retry.queueItemId,
                retry.songId,
            )
            if (!current) {
                synchronized(autoplayRetryIntents) { autoplayRetryIntents.remove(intentId) }
            }
            current
        }
    }

    fun activity(intentId: String): OperationActivity? = gateway.activity(intentId)
    fun isReconciled(intentId: String): Boolean = gateway.isReconciled(intentId)
    val lastSubmittedIntentId: String? get() = gateway.lastSubmittedIntentId

    private fun isCurrentAutoplayOccurrence(
        state: io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState,
        expected: HomeAutoplayIdentity,
        queueItemId: String,
        songId: String,
    ): Boolean {
        if (!state.controlsEligible) return false
        val snapshot = state.snapshot ?: return false
        if (snapshot.epoch != expected.epoch ||
            state.connectionGeneration != expected.connectionGeneration ||
            snapshot.queueRevision != expected.queueRevision
        ) return false
        if (QueueMutationScope.fingerprint(state) != expected.queueFingerprint) return false

        val playback = runCatching {
            JsonConfiguration.json.decodeFromJsonElement<PlaybackStateDto>(snapshot.wire.playback)
        }.getOrNull() ?: return false
        if (!playback.autoplayEnabled || playback.playbackContextId.isNullOrBlank() ||
            playback.playbackContextId != expected.playbackContextId
        ) return false

        val queue = runCatching {
            JsonConfiguration.json.decodeFromJsonElement<QueueSnapshotDto>(snapshot.wire.queue)
        }.getOrNull() ?: return false
        val occurrence = queue.items.singleOrNull { it.queueItemId == queueItemId } ?: return false
        return occurrence.position > 0 &&
            occurrence.source == QueueSourceDto.AUTOPLAY &&
            occurrence.playbackContextId == expected.playbackContextId &&
            occurrence.songId == songId
    }
}
