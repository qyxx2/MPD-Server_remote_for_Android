package io.github.qyxx2.mpdserver.remote.ui.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.qyxx2.mpdserver.remote.ui.player.FullScreenBottomNavigation
import io.github.qyxx2.mpdserver.remote.ui.player.FullScreenExitGestureBand
import io.github.qyxx2.mpdserver.remote.ui.player.OutputDetailsDialog
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerPresentation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Navigation-only shell for B2. B5 replaces this body with authoritative Queue/Played projection;
 * no demo rows or local Queue copy are created here.
 */
@Composable
fun QueueDestinationPlaceholderScreen(
    onExit: () -> Unit,
    onOpenPlayer: () -> Unit,
    presentation: PlayerPresentation,
) {
    var showOutputDetails by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("queue-destination-placeholder"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FullScreenExitGestureBand(onExit = onExit) {
            Text(
                text = "队列",
                modifier = Modifier.padding(start = 24.dp).align(Alignment.CenterStart),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "队列数据将在后续批次接入",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FullScreenBottomNavigation(
            selectedDestination = FullScreenBottomNavigation.Destination.QUEUE,
            onOutputClick = { showOutputDetails = true },
            onNowPlayingClick = onOpenPlayer,
            onQueueClick = {},
        )
    }
    if (showOutputDetails) {
        OutputDetailsDialog(presentation = presentation, onDismiss = { showOutputDetails = false })
    }
}
