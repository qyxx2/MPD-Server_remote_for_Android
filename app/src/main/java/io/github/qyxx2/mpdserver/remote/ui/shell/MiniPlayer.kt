package io.github.qyxx2.mpdserver.remote.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fixed-height mini-player. The content opens full-screen; the disabled action cannot trigger navigation. */
@Composable
fun MiniPlayer(
    presentation: MiniPlayerPresentation,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag("mini-player"),
        verticalArrangement = Arrangement.Top,
    ) {
        Surface(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.fillMaxWidth().height(1.dp)) {}
        Row(
            modifier = Modifier.fillMaxWidth().height(63.dp).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(onClick = onOpen)
                    .testTag("mini-player-body"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(48.dp).testTag("mini-player-artwork"),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("♫", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 25.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        text = presentation.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mini-player-title")
                            .semantics { contentDescription = presentation.title },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    presentation.statusLabel?.let { label ->
                        Text(
                            text = label,
                            modifier = Modifier.testTag("mini-player-status"),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            IconButton(
                onClick = {},
                enabled = false,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("mini-player-play-pause")
                    .semantics { contentDescription = "播放/暂停（暂未接入）" },
            ) {
                Text(
                    text = if (presentation.isPlaying) "Ⅱ" else "▶",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 21.sp,
                )
            }
        }
    }
}
