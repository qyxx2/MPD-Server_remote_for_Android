package io.github.qyxx2.mpdserver.remote.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Compatibility modal host; presentation and retry rows are shared with the Status page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityStatusSheet(
    presentation: ActivityPresentation,
    onDismiss: () -> Unit,
    onRetryUnknown: (String) -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("activity-status-sheet"),
        containerColor = dialogSurfaceColor(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("连接与活动", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("activity-status-close")) {
                    Text("关闭")
                }
            }
            ActivityStatusContent(
                presentation = presentation,
                onRetryUnknown = onRetryUnknown,
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            )
        }
    }
}
