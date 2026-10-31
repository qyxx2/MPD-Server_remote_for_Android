package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import io.github.qyxx2.mpdserver.remote.runtime.OperationActivity
import io.github.qyxx2.mpdserver.remote.ui.library.hasUnresolvedPlaybackMutation

/** AppNavHost reduces the shared activity list once; visible rows/cards consume this O(1) lock. */
val LocalPlaybackActionsEnabled = staticCompositionLocalOf { true }

/**
 * Menu is anchored to this song's trailing IconButton. It is not a dialog, has no Cancel row,
 * and outside taps are handled by DropdownMenu.onDismissRequest.
 */
@Composable
fun SongActionMenu(
    item: LibrarySongRowModel,
    actions: SongActions,
    resourceFresh: Boolean = true,
    modifier: Modifier = Modifier,
    onFavorite: (() -> Unit)? = null,
) {
    // Keep row composition O(1): the authoritative snapshot/write gate is checked on tap by
    // SongActions.perform; the shared activity projection disables all row affordances immediately.
    val enabled = item.isPlayable && resourceFresh && LocalPlaybackActionsEnabled.current
    AnchoredSongActionMenu(
        menuKey = item.key.hashCode().toString(),
        enabled = enabled,
        modifier = modifier,
        onAction = { action -> actions.perform(item, action, resourceFresh) },
        onFavorite = onFavorite,
    )
}

/** UI primitive separated from the write adapter so the anchored/outside-dismiss contract is testable. */
@Composable
internal fun AnchoredSongActionMenu(
    menuKey: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onAction: (SongPlaybackAction) -> Unit,
    onFavorite: (() -> Unit)? = null,
) {
    var expanded by remember(menuKey) { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .testTag("song-actions-$menuKey"),
        ) {
            Text("⋮", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag("song-action-menu-$menuKey"),
        ) {
            SongPlaybackAction.entries.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    enabled = enabled,
                    modifier = Modifier.testTag("song-action-${action.name.lowercase()}-$menuKey"),
                    onClick = {
                        expanded = false
                        onAction(action)
                    },
                )
            }
            if (onFavorite != null) {
                DropdownMenuItem(
                    text = { Text("收藏") },
                    modifier = Modifier.testTag("song-action-favorite-$menuKey"),
                    onClick = {
                        expanded = false
                        onFavorite()
                    },
                )
            }
        }
    }
}

/** Header-level collection operations; never attach these actions to individual song rows. */
@Composable
fun CollectionPlaybackControls(
    enabled: Boolean,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "collection-playback-controls",
    activities: kotlinx.coroutines.flow.StateFlow<List<io.github.qyxx2.mpdserver.remote.runtime.OperationActivity>>? = null,
) {
    val activityState = activities?.collectAsStateWithLifecycle(initialValue = emptyList())
    val activityLock = activityState?.value?.hasUnresolvedPlaybackMutation()
        ?: !LocalPlaybackActionsEnabled.current
    val effectiveEnabled = enabled && !activityLock
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .testTag(tag),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.TextButton(
            onClick = onPlayAll,
            enabled = effectiveEnabled,
            modifier = Modifier.defaultMinSize(minWidth = 88.dp, minHeight = 48.dp)
                .testTag("$tag-play-all"),
        ) { Text("全部播放") }
        androidx.compose.material3.TextButton(
            onClick = onShuffle,
            enabled = effectiveEnabled,
            modifier = Modifier.defaultMinSize(minWidth = 88.dp, minHeight = 48.dp)
                .testTag("$tag-shuffle"),
        ) { Text("随机播放") }
    }
}
