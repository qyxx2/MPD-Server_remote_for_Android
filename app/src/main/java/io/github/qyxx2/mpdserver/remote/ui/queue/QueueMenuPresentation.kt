package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto

internal enum class QueueMenuAction { PLAY_NEXT, ADD_TO_END, REMOVE }

internal data class QueueMenuPresentation(
    val enabled: Boolean,
    val actions: List<QueueMenuAction>,
) {
    companion object {
        fun from(item: QueueItemDto, writable: Boolean): QueueMenuPresentation {
            if (!writable) return QueueMenuPresentation(enabled = false, actions = emptyList())
            // Match QueueView.vue: Next/Add are pending-only; Remove remains exposed for current
            // and Played rows too. The server decides whether deleting that occurrence is valid.
            val actions = buildList {
                if (item.position > 0) {
                    add(QueueMenuAction.PLAY_NEXT)
                    add(QueueMenuAction.ADD_TO_END)
                }
                add(QueueMenuAction.REMOVE)
            }
            return QueueMenuPresentation(enabled = true, actions = actions)
        }
    }
}
