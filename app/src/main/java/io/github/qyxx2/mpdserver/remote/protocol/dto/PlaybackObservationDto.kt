package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ActualPlaybackStateDto { playing, paused, stopped }
@Serializable
enum class ObservationFreshnessDto { fresh, stale, unknown }
@Serializable
enum class PlaybackSyncStatusDto { CONFIRMED, UNBOUND, EXTERNAL_DRIFT, UNCONFIRMED_STOP, SYNC_FAILED, NO_CANDIDATES }

@Serializable
data class ActualCurrentDto(@SerialName("entry_id") val entryId: Long? = null, val uri: String? = null, val position: Int? = null)

@Serializable
data class PlaybackControlTargetDto(
    @SerialName("queue_item_id") val queueItemId: String,
    val token: String,
)

@Serializable
data class PlaybackObservationDto(
    @SerialName("actual_state") val actualState: ActualPlaybackStateDto?,
    @SerialName("actual_current") val actualCurrent: ActualCurrentDto? = null,
    @SerialName("actual_freshness") val actualFreshness: ObservationFreshnessDto = ObservationFreshnessDto.unknown,
    @SerialName("bound_queue_item_id") val boundQueueItemId: String? = null,
    @SerialName("control_target") val controlTarget: PlaybackControlTargetDto? = null,
    @SerialName("sync_status") val syncStatus: PlaybackSyncStatusDto = PlaybackSyncStatusDto.UNBOUND,
    @SerialName("matches_current") val matchesCurrent: Boolean?,
    @SerialName("position_seconds") val positionSeconds: Double?,
    @SerialName("duration_seconds") val durationSeconds: Double?,
    @SerialName("covered_playback_updated_at") val coveredPlaybackUpdatedAt: String? = null,
    @SerialName("observed_at") val observedAt: String?,
    val freshness: ObservationFreshnessDto,
    @SerialName("reconciliation_required") val reconciliationRequired: Boolean,
    @SerialName("error_code") val errorCode: String?,
    @SerialName("error_message") val errorMessage: String?,
)
