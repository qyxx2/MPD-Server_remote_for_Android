package io.github.qyxx2.mpdserver.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomePlaceholderScreen() = RootPlaceholderScreen(
    description = "主页内容将在后续任务接入。",
)

@Composable
fun SearchPlaceholderScreen() = RootPlaceholderScreen(
    description = "搜索功能将在后续任务接入。",
)

@Composable
fun LibraryPlaceholderScreen() = RootPlaceholderScreen(
    description = "音乐库内容将在后续任务接入。",
)

@Composable
fun SettingsPlaceholderScreen() = RootPlaceholderScreen(
    description = "应用设置将在后续任务接入。",
)

@Composable
private fun RootPlaceholderScreen(
    description: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .testTag("root-placeholder-content"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun FullScreenPlayerPlaceholderScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            TextButton(onClick = onBack) {
                Text(text = "返回", fontSize = 16.sp)
            }
        }
        Text(
            text = "全屏播放器占位",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "播放器界面将在后续任务实现。",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
