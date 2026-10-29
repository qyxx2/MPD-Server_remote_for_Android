package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import io.github.qyxx2.mpdserver.remote.protocol.dto.QueueItemDto
import io.github.qyxx2.mpdserver.remote.ui.components.dialogSurfaceColor

internal enum class QueueToolDialog { CLEAR, SAVE_PLAYLIST }

/** A compact menu hosted beside the row's three-dot button, rather than a window-centered dialog. */
@Composable
internal fun QueueRowMenu(
    item: QueueItemDto?,
    title: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onMoveNext: () -> Unit,
    onMoveToEnd: () -> Unit,
    onRemove: () -> Unit,
    expanded: Boolean = item != null,
) {
    if (item == null) return
    val presentation = QueueMenuPresentation.from(item, enabled)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(min = 216.dp, max = 264.dp)
            .testTag("queue-row-menu-dialog"),
        shape = RoundedCornerShape(22.dp),
        containerColor = dialogSurfaceColor(),
        tonalElevation = 0.dp,
        shadowElevation = 10.dp,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("queue-row-menu-panel"),
            shape = RoundedCornerShape(22.dp),
            color = dialogSurfaceColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title.ifBlank { item.songId },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .testTag("queue-menu-title"),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (QueueMenuAction.PLAY_NEXT in presentation.actions) {
                    TextButton(
                        onClick = onMoveNext,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("queue-menu-next"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text("移为下一首", modifier = Modifier.testTag("queue-menu-next-label"), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp))
                    }
                }
                if (QueueMenuAction.ADD_TO_END in presentation.actions) {
                    TextButton(
                        onClick = onMoveToEnd,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("queue-menu-add"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text("移到队尾", modifier = Modifier.testTag("queue-menu-add-label"), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp))
                    }
                }
                if (QueueMenuAction.REMOVE in presentation.actions) {
                    TextButton(
                        onClick = onRemove,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("queue-menu-remove"),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text("删除此条目", modifier = Modifier.testTag("queue-menu-remove-label"), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp))
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("queue-menu-cancel"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text("取消", modifier = Modifier.testTag("queue-menu-cancel-label"), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp))
                }
            }
        }
    }
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
            containerColor = dialogSurfaceColor(),
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
            containerColor = dialogSurfaceColor(),
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
                            color = MaterialTheme.colorScheme.error,
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
