package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto

internal object QueueRemainingTime {
    fun remainingSeconds(
        visibleRows: List<QueueItemDto>,
        durationsBySongId: Map<String, Double?>,
        currentPositionSeconds: Double?,
    ): Double? {
        if (visibleRows.isEmpty()) return 0.0

        val currentIndex = visibleRows.indexOfFirst { it.position == 0 }
        if (currentIndex < 0) return null

        val position = currentPositionSeconds?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        var total = 0.0
        visibleRows.forEachIndexed { index, row ->
            val duration = durationsBySongId[row.songId]
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: return null
            total += if (index == currentIndex) {
                (duration - position).coerceAtLeast(0.0)
            } else {
                duration
            }
        }
        return total.takeIf { it.isFinite() }
    }

    fun format(seconds: Double?): String {
        val totalSeconds = seconds?.takeIf { it.isFinite() && it >= 0.0 }?.toLong()
            ?: return "--:--"
        return (totalSeconds / 60).toString() + ":" + (totalSeconds % 60).toString().padStart(2, '0')
    }
}
