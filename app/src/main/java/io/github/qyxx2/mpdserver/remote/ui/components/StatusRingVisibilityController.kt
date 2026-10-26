package io.github.qyxx2.mpdserver.remote.ui.components

import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity

data class StatusRingVisibility(
    val visible: Boolean,
    val hideAtMillis: Long?,
)

/**
 * Pure lifecycle policy for the in-window status ring. UNKNOWN/FAILED remain visible while they
 * remain unresolved in the shared activity list; this controller never edits or retires activities.
 */
class StatusRingVisibilityController(
    private val resultVisibleMillis: Long = RESULT_VISIBLE_MILLIS,
) {
    private var initialized = false
    private var previousStatuses: Map<String, MutationStatus> = emptyMap()
    private var hideAtMillis: Long? = null
    private var latestHasActiveActivity = false
    private var latestHasUnresolvedActivity = false

    fun update(
        activities: List<OperationActivity>,
        nowMillis: Long,
    ): StatusRingVisibility {
        val currentStatuses = activities.associate { it.intentId to it.status }
        val hasActive = currentStatuses.values.any(::isActive)
        val hasUnresolved = currentStatuses.values.any(::isUnresolved)

        if (!initialized) {
            initialized = true
            previousStatuses = currentStatuses
            latestHasActiveActivity = hasActive
            latestHasUnresolvedActivity = hasUnresolved
            hideAtMillis = null
            return snapshot(nowMillis)
        }

        val newlyCompleted = currentStatuses.any { (id, status) ->
            status in TERMINAL_STATUSES && previousStatuses[id] != status
        }
        val activityStarted = currentStatuses.any { (id, status) ->
            isActive(status) && !isActive(previousStatuses[id])
        }
        val wasActive = previousStatuses.values.any(::isActive)
        val activityDisappeared = wasActive && currentStatuses.keys.none { it in previousStatuses && isActive(previousStatuses[it]) } &&
            currentStatuses.values.none(::isActive) && !hasUnresolved

        when {
            hasActive || hasUnresolved -> hideAtMillis = null
            newlyCompleted || activityDisappeared -> hideAtMillis = nowMillis + resultVisibleMillis
            activityStarted -> hideAtMillis = null
            currentStatuses != previousStatuses && currentStatuses.values.any(::isActive) -> hideAtMillis = null
        }

        previousStatuses = currentStatuses
        latestHasActiveActivity = hasActive
        latestHasUnresolvedActivity = hasUnresolved
        return snapshot(nowMillis)
    }

    fun onTimer(nowMillis: Long): StatusRingVisibility {
        if (hideAtMillis != null && nowMillis >= hideAtMillis!! &&
            !latestHasActiveActivity && !latestHasUnresolvedActivity
        ) {
            hideAtMillis = null
        }
        return snapshot(nowMillis)
    }

    fun current(nowMillis: Long): StatusRingVisibility = snapshot(nowMillis)

    private fun snapshot(nowMillis: Long): StatusRingVisibility {
        val pendingResult = hideAtMillis?.let { nowMillis < it } == true
        return StatusRingVisibility(
            visible = latestHasActiveActivity || latestHasUnresolvedActivity || pendingResult,
            hideAtMillis = hideAtMillis,
        )
    }

    private fun isActive(status: MutationStatus?): Boolean =
        status == MutationStatus.PENDING || status == MutationStatus.SYNCING

    private fun isUnresolved(status: MutationStatus?): Boolean =
        status == MutationStatus.UNKNOWN || status == MutationStatus.FAILED

    private companion object {
        const val RESULT_VISIBLE_MILLIS = 5_000L
        val TERMINAL_STATUSES = setOf(MutationStatus.SUCCESS, MutationStatus.INFO)
    }
}
