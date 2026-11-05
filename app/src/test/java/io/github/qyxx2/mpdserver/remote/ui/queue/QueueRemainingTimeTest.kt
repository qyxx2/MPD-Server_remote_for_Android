package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSourceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueRemainingTimeTest {
    @Test
    fun addsCurrentSongRemainderAndFullDurationsOfFollowingVisibleRows() {
        val rows = listOf(
            item("current", "song-a", 0),
            item("next-a", "song-b", 1),
            item("next-b", "song-c", 2),
        )

        val result = QueueRemainingTime.remainingSeconds(
            rows,
            mapOf("song-a" to 240.0, "song-b" to 180.0, "song-c" to 90.0),
            currentPositionSeconds = 60.0,
        )

        assertEquals(450.0, result!!, 0.001)
    }

    @Test
    fun countsDuplicateSongOccurrencesIndependently() {
        val rows = listOf(
            item("current", "same-song", 0),
            item("next", "same-song", 1),
        )

        val result = QueueRemainingTime.remainingSeconds(
            rows,
            mapOf("same-song" to 100.0),
            currentPositionSeconds = 50.0,
        )

        assertEquals(150.0, result!!, 0.001)
    }

    @Test
    fun returnsUnknownWhenCurrentPositionOrAnyVisibleDurationIsUnavailableOrInvalid() {
        val rows = listOf(item("current", "song-a", 0), item("next", "song-b", 1))
        val durations: Map<String, Double?> = mapOf("song-a" to 120.0, "song-b" to 180.0)

        assertNull(QueueRemainingTime.remainingSeconds(rows, durations, currentPositionSeconds = null))
        assertNull(QueueRemainingTime.remainingSeconds(rows, mapOf("song-a" to 120.0), 10.0))
        assertNull(QueueRemainingTime.remainingSeconds(rows, durations + ("song-b" to null), 10.0))
        assertNull(QueueRemainingTime.remainingSeconds(rows, durations + ("song-b" to 0.0), 10.0))
        assertNull(QueueRemainingTime.remainingSeconds(rows, durations + ("song-b" to -1.0), 10.0))
        assertNull(QueueRemainingTime.remainingSeconds(rows, durations + ("song-b" to Double.NaN), 10.0))
        assertNull(QueueRemainingTime.remainingSeconds(rows, durations + ("song-b" to Double.POSITIVE_INFINITY), 10.0))
        assertNull(QueueRemainingTime.remainingSeconds(rows, durations, currentPositionSeconds = -1.0))
    }

    @Test
    fun returnsZeroForEmptyRowsAndFormatsLikeThePlayer() {
        assertEquals(0.0, QueueRemainingTime.remainingSeconds(emptyList(), emptyMap(), null)!!, 0.001)
        assertEquals("0:00", QueueRemainingTime.format(0.0))
        assertEquals("12:05", QueueRemainingTime.format(725.0))
        assertEquals("60:01", QueueRemainingTime.format(3_601.0))
        assertEquals("--:--", QueueRemainingTime.format(null))
        assertEquals("--:--", QueueRemainingTime.format(Double.NaN))
    }

    private fun item(queueItemId: String, songId: String, position: Int) = QueueItemDto(
        queueItemId = queueItemId,
        songId = songId,
        position = position,
        source = QueueSourceDto.MANUAL,
    )
}
