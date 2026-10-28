package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto

internal enum class QueueToolDialog { CLEAR, SAVE_PLAYLIST }

@Composable
internal fun QueueRowMenu(
    item: QueueItemDto?,
    title: String,
    artist: String,
    album: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onMoveNext: () -> Unit,
    onMoveToEnd: () -> Unit,
    onRemove: () -> Unit,
) {
    if (item == null) return
    val presentation = QueueMenuPresentation.from(item, enabled)
    AlertDialog(
        modifier = Modifier.testTag("queue-row-menu-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(title.ifBlank { item.songId }) },
        text = {
            Column {
                Text(artist.ifBlank { "艺术家未知" })
                Text(album.ifBlank { "专辑未知" })
                if (!presentation.enabled) Text("当前条目不可执行待播操作")
            }
        },
        confirmButton = {
            Column {
                if (QueueMenuAction.PLAY_NEXT in presentation.actions) {
                    TextButton(onClick = onMoveNext, modifier = Modifier.fillMaxWidth().testTag("queue-menu-next")) {
                        Text("移为下一首")
                    }
                }
                if (QueueMenuAction.ADD_TO_END in presentation.actions) {
                    TextButton(onClick = onMoveToEnd, modifier = Modifier.fillMaxWidth().testTag("queue-menu-add")) {
                        Text("移到队尾")
                    }
                }
                if (QueueMenuAction.REMOVE in presentation.actions) {
                    TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth().testTag("queue-menu-remove")) {
                        Text("删除此条目")
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("queue-menu-cancel")) {
                    Text("取消")
                }
            }
        },
        dismissButton = {},
    )
}

@Composable
internal fun QueueToolsDialog(
    dialog: QueueToolDialog?,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onSave: (String) -> Unit,
    saveFeedback: String? = null,
    saveAlreadyConfirmed: Boolean = false,
    saveReadbackOnlyRetry: Boolean = false,
    onRetrySaveReadback: () -> Unit = {},
) {
    if (dialog == null) return
    var name by remember(dialog) { mutableStateOf("") }
    when (dialog) {
        QueueToolDialog.CLEAR -> AlertDialog(
            modifier = Modifier.testTag("queue-clear-dialog"),
            onDismissRequest = onDismiss,
            title = { Text("清空待播") },
            text = { Text("清空服务器定义的待播项目，保留当前与已播放项目。AutoPlay 之后可能补充候选。此操作不会停止播放。") },
            confirmButton = {
                TextButton(
                    enabled = enabled,
                    onClick = onClear,
                    modifier = Modifier.testTag("queue-clear-confirm"),
                ) { Text("确认清空待播") }
            },
            dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("queue-clear-cancel")) { Text("取消") } },
        )
        QueueToolDialog.SAVE_PLAYLIST -> AlertDialog(
            modifier = Modifier.testTag("queue-save-dialog"),
            onDismissRequest = onDismiss,
            title = { Text("保存为 Playlist") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Playlist 名称") },
                        singleLine = true,
                        enabled = enabled && !saveAlreadyConfirmed,
                        modifier = Modifier.fillMaxWidth().testTag("queue-playlist-name"),
                    )
                    Text(
                        "保存时由服务器读取完整待播集合，包括未显示的候选；不包含当前与已播放项目。重复歌曲由服务器校验。",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    saveFeedback?.let { message ->
                        Text(
                            text = message,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp).testTag("queue-save-feedback"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = if (saveAlreadyConfirmed) saveReadbackOnlyRetry else enabled && name.isNotBlank(),
                    onClick = {
                        if (saveAlreadyConfirmed) {
                            if (saveReadbackOnlyRetry) onRetrySaveReadback()
                        } else onSave(name)
                    },
                    modifier = Modifier.testTag("queue-save-confirm"),
                ) {
                    Text(
                        when {
                            saveAlreadyConfirmed && saveReadbackOnlyRetry -> "重试读取"
                            saveAlreadyConfirmed -> "等待原服务器"
                            else -> "保存"
                        },
                    )
                }
            },
            dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("queue-save-cancel")) { Text("取消") } },
        )
    }
}
