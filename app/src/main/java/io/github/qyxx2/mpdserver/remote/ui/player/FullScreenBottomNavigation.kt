package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal object FullScreenBottomNavigation {
    enum class Destination { NOW_PLAYING, QUEUE }
}

@Composable
internal fun FullScreenBottomNavigation(
    selectedDestination: FullScreenBottomNavigation.Destination,
    onOutputClick: () -> Unit,
    onNowPlayingClick: () -> Unit,
    onQueueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .testTag("player-bottom-navigation"),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onOutputClick,
            modifier = Modifier
                .size(56.dp)
                .semantics { contentDescription = "DAC 输出状态" }
                .testTag("player-bottom-output"),
        ) {
            PlayerGlyph(
                kind = PlayerGlyphKind.OUTPUT,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(25.dp),
            )
        }
        IconButton(
            onClick = onNowPlayingClick,
            modifier = Modifier
                .size(56.dp)
                .semantics {
                    contentDescription = "正在播放"
                    selected = selectedDestination == FullScreenBottomNavigation.Destination.NOW_PLAYING
                }
                .testTag("player-bottom-now-playing"),
        ) {
            PlayerGlyph(
                kind = PlayerGlyphKind.NOW_PLAYING,
                tint = if (selectedDestination == FullScreenBottomNavigation.Destination.NOW_PLAYING) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(25.dp),
            )
        }
        IconButton(
            onClick = onQueueClick,
            modifier = Modifier
                .size(56.dp)
                .semantics {
                    contentDescription = "播放队列"
                    selected = selectedDestination == FullScreenBottomNavigation.Destination.QUEUE
                }
                .testTag("player-bottom-queue"),
        ) {
            PlayerGlyph(
                kind = PlayerGlyphKind.QUEUE,
                tint = if (selectedDestination == FullScreenBottomNavigation.Destination.QUEUE) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(25.dp),
            )
        }
    }
}
