package io.github.qyxx2.mpdserver.remote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Read-only projection shared by the Status settings page and the legacy modal host. */
@Composable
fun ActivityStatusContent(
    presentation: ActivityPresentation,
    onRetryUnknown: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag("activity-status-content"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "当前状态：${presentation.statusLabel}",
                modifier = Modifier.testTag("activity-status-summary"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("连接：${presentation.connectionLabel}", modifier = Modifier.testTag("activity-status-connection"))
                Text("快照：${presentation.freshnessLabel}", modifier = Modifier.testTag("activity-status-freshness"))
            }
        }
        if (!presentation.isConfirmedAvailable) {
            item {
                Text(
                    "服务状态尚未确认；此页面不会发起连接或写入操作。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        presentation.readError?.let { error ->
            item { Text("读取错误：$error", color = MaterialTheme.colorScheme.error) }
        }
        item {
            HorizontalDivider()
            Text("最近活动", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        if (presentation.activities.isEmpty()) {
            item { Text("暂无操作活动", modifier = Modifier.testTag("activity-status-empty")) }
        } else {
            items(presentation.activities.take(20), key = { it.intentId }) { activity ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .testTag("activity-row-${activity.intentId}"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(activity.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(activity.statusLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    activity.message?.let { Text(it) }
                    activity.syncError?.let { Text("同步错误：$it", color = MaterialTheme.colorScheme.error) }
                    if (activity.canRetry) {
                        OutlinedButton(
                            onClick = { onRetryUnknown(activity.intentId) },
                            modifier = Modifier.testTag("activity-retry-${activity.intentId}"),
                        ) {
                            Text("使用原操作重试")
                        }
                    }
                }
            }
        }
    }
}
