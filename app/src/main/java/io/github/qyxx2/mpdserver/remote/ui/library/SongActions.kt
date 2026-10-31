package io.github.qyxx2.mpdserver.remote.ui.library

import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.MutationIntent
import io.github.qyxx2.mpdserver.remote.runtime.MutationOutcome
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.protocol.dto.AvailabilityStatus
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID

/** Small write adapter over the one process-owned MutationCoordinator. */
internal class PlaybackMutationGateway(
    private val stateProvider: () -> SnapshotStoreState,
    val activities: StateFlow<List<OperationActivity>>,
    private val submitter: (MutationIntent) -> MutationOutcome,
    private val retryUnknown: (String) -> MutationOutcome,
    private val canRetryUnknown: (String) -> Boolean,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val writeGate = Any()
    @Volatile
    var lastSubmittedIntentId: String? = null
        private set
    constructor(runtime: AppRuntime) : this(
        stateProvider = { runtime.snapshots.state.value },
        activities = runtime.mutations.activities,
        submitter = runtime.mutations::submit,
        retryUnknown = runtime.mutations::retryUnknown,
        canRetryUnknown = runtime.mutations::canRetryUnknown,
    )

    fun currentState(): SnapshotStoreState = stateProvider()

    fun canSubmit(resourceFresh: Boolean = true): Boolean {
        if (!resourceFresh) return false
        val state = stateProvider()
        if (!state.controlsEligible) return false
        return !activities.value.hasUnresolvedPlaybackMutation()
    }

    fun newIntentId(prefix: String): String = "$prefix:${idFactory()}"

    fun submit(
        intentPrefix: String,
        path: String,
        payload: String = "{}",
        label: String,
        resourceFresh: Boolean = true,
        retryScope: String? = null,
        precondition: () -> Boolean = { true },
    ): MutationOutcome? = submitWithIntentId(
        intentId = newIntentId(intentPrefix),
        path = path,
        payload = payload,
        label = label,
        resourceFresh = resourceFresh,
        retryScope = retryScope,
        precondition = precondition,
    )

    /** Atomic within the shared B5 gateway: simultaneous Home/Library/Search taps cannot pass the
     * global activity check and enqueue multiple playback writes before the first PENDING appears. */
    fun submitWithIntentId(
        intentId: String,
        path: String,
        payload: String = "{}",
        label: String,
        resourceFresh: Boolean = true,
        retryScope: String? = null,
        precondition: () -> Boolean = { true },
    ): MutationOutcome? = synchronized(writeGate) {
        if (!canSubmit(resourceFresh) || !precondition() || !canSubmit(resourceFresh)) return@synchronized null
        val outcome = submitter(
            MutationIntent(
                id = intentId,
                method = "POST",
                path = path,
                payload = payload,
                label = label,
                retryScope = retryScope,
            ),
        )
        if (outcome == MutationOutcome.Submitted || outcome == MutationOutcome.DuplicatePending) {
            lastSubmittedIntentId = intentId
        }
        outcome
    }

    fun activity(intentId: String): OperationActivity? =
        activities.value.firstOrNull { it.intentId == intentId }

    fun activities(): StateFlow<List<OperationActivity>> = activities

    fun isReconciled(intentId: String): Boolean = activity(intentId)?.let {
        it.status == MutationStatus.SUCCESS && it.syncError == null
    } == true

    fun canRetry(intentId: String): Boolean = canRetryUnknown(intentId)

    fun retry(intentId: String, precondition: () -> Boolean = { true }): MutationOutcome? =
        synchronized(writeGate) {
            if (!precondition() || !canRetryUnknown(intentId)) return@synchronized null
            retryUnknown(intentId)
        }
}

internal fun List<OperationActivity>.hasUnresolvedPlaybackMutation(): Boolean = any { activity ->
    activity.status in setOf(MutationStatus.PENDING, MutationStatus.SYNCING, MutationStatus.UNKNOWN) ||
        (activity.status == MutationStatus.SUCCESS && activity.syncError != null)
}

enum class SongPlaybackAction(val route: String, val label: String) {
    PLAY_NOW("play", "立即播放"),
    PLAY_NEXT("play-next", "下一首播放"),
    ADD_TO_QUEUE("queue", "添加到播放队列"),
}

/** Web-equivalent single-song playback actions; rows never mutate Queue or Playback locally. */
class SongActions internal constructor(
    internal val gateway: PlaybackMutationGateway,
) {
    constructor(runtime: AppRuntime) : this(PlaybackMutationGateway(runtime))

    fun canPerform(item: LibrarySongRowModel, resourceFresh: Boolean = true): Boolean =
        item.songId?.isNotBlank() == true && item.song?.songId == item.songId &&
            item.song.availabilityStatus == AvailabilityStatus.AVAILABLE &&
            gateway.canSubmit(resourceFresh)

    fun perform(
        item: LibrarySongRowModel,
        action: SongPlaybackAction,
        resourceFresh: Boolean = true,
    ): MutationOutcome? {
        val songId = item.songId?.takeIf(String::isNotBlank) ?: return null
        val song = item.song?.takeIf {
            it.songId == songId && it.availabilityStatus == AvailabilityStatus.AVAILABLE
        } ?: return null
        if (!gateway.canSubmit(resourceFresh)) return null
        val path = "http://localhost/".toHttpUrl().newBuilder()
            .addPathSegments("api/playback/songs")
            .addPathSegment(song.songId!!)
            .addPathSegment(action.route)
            .build().encodedPath
        return gateway.submit(
            intentPrefix = "song",
            path = path,
            label = "${action.label} · ${song.title}",
            resourceFresh = resourceFresh,
        )
    }

    fun activity(intentId: String): OperationActivity? = gateway.activity(intentId)
    fun isReconciled(intentId: String): Boolean = gateway.isReconciled(intentId)
    fun retryUnknown(intentId: String): MutationOutcome? = gateway.retry(intentId)
    fun activities(): StateFlow<List<OperationActivity>> = gateway.activities
    val lastSubmittedIntentId: String? get() = gateway.lastSubmittedIntentId
}
