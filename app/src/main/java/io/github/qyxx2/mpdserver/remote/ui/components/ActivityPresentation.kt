package io.github.qyxx2.mpdserver.remote.ui.components

import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin

enum class ActivityVisualState { UNKNOWN, FAILED, PENDING, SYNCING, DISCONNECTED, CONNECTING, STALE, SUCCESS, INFO, IDLE }

data class ActivityRowPresentation(
    val intentId: String,
    val title: String,
    val statusLabel: String,
    val message: String?,
    val syncError: String?,
    val canRetry: Boolean = false,
)

data class ActivityPresentation(
    val visualState: ActivityVisualState,
    val statusLabel: String,
    val connectionLabel: String,
    val freshnessLabel: String,
    val readError: String?,
    val activities: List<ActivityRowPresentation>,
    val isConfirmedAvailable: Boolean,
) {
    companion object {
        fun from(
            state: SnapshotStoreState,
            activities: List<OperationActivity>,
            currentOrigin: ServerOrigin? = null,
            retryableIntentIds: Set<String> = emptySet(),
        ): ActivityPresentation {
            val priority = listOf(
                MutationStatus.UNKNOWN to ActivityVisualState.UNKNOWN,
                MutationStatus.FAILED to ActivityVisualState.FAILED,
                MutationStatus.PENDING to ActivityVisualState.PENDING,
                MutationStatus.SYNCING to ActivityVisualState.SYNCING,
            )
            val unresolved = priority.firstNotNullOfOrNull { (status, visual) ->
                activities.lastOrNull { it.status == status }?.let { visual }
            }
            val visual = unresolved ?: when {
                state.connectionStatus == RuntimeConnectionStatus.DISCONNECTED -> ActivityVisualState.DISCONNECTED
                state.connectionStatus == RuntimeConnectionStatus.CONNECTING -> ActivityVisualState.CONNECTING
                state.freshness == SnapshotFreshness.STALE -> ActivityVisualState.STALE
                state.freshness == SnapshotFreshness.UNKNOWN -> ActivityVisualState.UNKNOWN
                activities.lastOrNull()?.status == MutationStatus.SUCCESS -> ActivityVisualState.SUCCESS
                activities.lastOrNull()?.status == MutationStatus.INFO -> ActivityVisualState.INFO
                else -> ActivityVisualState.IDLE
            }
            return ActivityPresentation(
                visualState = visual,
                statusLabel = visual.label(),
                connectionLabel = when (state.connectionStatus) {
                    RuntimeConnectionStatus.DISCONNECTED -> "未连接"
                    RuntimeConnectionStatus.CONNECTING -> "正在连接"
                    RuntimeConnectionStatus.CONNECTED -> "已连接"
                },
                freshnessLabel = when (state.freshness) {
                    SnapshotFreshness.UNKNOWN -> "未确认"
                    SnapshotFreshness.FRESH -> "最新"
                    SnapshotFreshness.STALE -> "已过期"
                },
                readError = state.readError,
                activities = activities.asReversed().map { activity ->
                    ActivityRowPresentation(
                        intentId = activity.intentId,
                        title = activity.label.ifBlank { "操作" },
                        statusLabel = activity.status.label(),
                        message = activity.message?.takeIf(String::isNotBlank),
                        syncError = activity.syncError?.takeIf(String::isNotBlank),
                        canRetry = state.snapshot != null &&
                            state.connectionStatus == RuntimeConnectionStatus.CONNECTED &&
                            state.snapshotConnectionGeneration == state.connectionGeneration &&
                            activity.status == MutationStatus.UNKNOWN &&
                            currentOrigin != null && activity.origin == currentOrigin &&
                            when {
                                activity.intentId.startsWith("player:") -> activity.intentId in retryableIntentIds
                                activity.intentId.startsWith("queue:") -> activity.intentId in retryableIntentIds
                                else -> true
                            },
                    )
                },
                isConfirmedAvailable = state.controlsEligible,
            )
        }

        private fun MutationStatus.label(): String = when (this) {
            MutationStatus.PENDING -> "等待处理"
            MutationStatus.SYNCING -> "正在同步"
            MutationStatus.UNKNOWN -> "结果未知"
            MutationStatus.FAILED -> "操作失败"
            MutationStatus.SUCCESS -> "操作已确认"
            MutationStatus.INFO -> "提示"
        }

        private fun ActivityVisualState.label(): String = when (this) {
            ActivityVisualState.UNKNOWN -> "结果未知"
            ActivityVisualState.FAILED -> "操作失败"
            ActivityVisualState.PENDING -> "正在处理"
            ActivityVisualState.SYNCING -> "正在同步"
            ActivityVisualState.DISCONNECTED -> "未连接"
            ActivityVisualState.CONNECTING -> "正在连接"
            ActivityVisualState.STALE -> "状态已过期"
            ActivityVisualState.SUCCESS -> "操作已确认"
            ActivityVisualState.INFO -> "状态提示"
            ActivityVisualState.IDLE -> "状态正常"
        }
    }
}
