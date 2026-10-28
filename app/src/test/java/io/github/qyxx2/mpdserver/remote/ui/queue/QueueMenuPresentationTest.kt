package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueSourceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueMenuPresentationTest {
    @Test fun currentAndPlayedRowsExposeOnlyTheWebRemoveActionWhilePendingRowsCanMove() {
        val current = QueueMenuPresentation.from(item("current", 0), writable = true)
        val played = QueueMenuPresentation.from(item("played", -1), writable = true)
        val pending = QueueMenuPresentation.from(item("pending", 2), writable = true)

        assertTrue(current.enabled)
        assertEquals(listOf(QueueMenuAction.REMOVE), current.actions)
        assertTrue(played.enabled)
        assertEquals(listOf(QueueMenuAction.REMOVE), played.actions)
        assertTrue(pending.enabled)
        assertEquals(
            listOf(QueueMenuAction.PLAY_NEXT, QueueMenuAction.ADD_TO_END, QueueMenuAction.REMOVE),
            pending.actions,
        )
    }

    @Test fun staleOrLockedSnapshotDisablesMenuWithoutInventingActions() {
        val menu = QueueMenuPresentation.from(item("pending", 1), writable = false)
        assertFalse(menu.enabled)
        assertTrue(menu.actions.isEmpty())
    }

    private fun item(id: String, position: Int) =
        QueueItemDto(id, "duplicate-song", position, QueueSourceDto.MANUAL, null)
}
