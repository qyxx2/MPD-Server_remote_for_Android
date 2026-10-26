package io.github.qyxx2.mpdserver.remote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityStatusSheet(
    presentation: ActivityPresentation,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("activity-status-sheet")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("连接与活动", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("activity-status-close")) { Text("关闭") }
            }
            Text("当前状态：${presentation.statusLabel}", modifier = Modifier.testTag("activity-status-summary"))
            Text("连接：${presentation.connectionLabel}")
            Text("快照：${presentation.freshnessLabel}")
            if (!presentation.isConfirmedAvailable) {
                Text("服务状态尚未确认；此页面不会发起连接或写入操作。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            presentation.readError?.let { error ->
                Text("读取错误：$error", color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider()
            Text("最近活动", style = MaterialTheme.typography.titleMedium)
            if (presentation.activities.isEmpty()) {
                Text("暂无操作活动", modifier = Modifier.testTag("activity-status-empty"))
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).testTag("activity-status-list"),
                ) {
                    items(presentation.activities.take(20), key = { it.intentId }) { activity ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(activity.title, style = MaterialTheme.typography.bodyLarge)
                            Text(activity.statusLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            activity.message?.let { Text(it) }
                            activity.syncError?.let { Text("同步错误：$it", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("activity-status-dismiss-button")) {
                Text("完成")
            }
        }
    }
}
