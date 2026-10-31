package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.ui.library.hasUnresolvedPlaybackMutation

/** AppNavHost reduces the shared activity list once; visible rows/cards consume this O(1) lock. */
val LocalPlaybackActionsEnabled = staticCompositionLocalOf { true }

/**
 * A song's complete visual surface is the anchor for its Web-equivalent action menu.
 * No separate overflow affordance is rendered: tapping any part of the row/card opens it.
 */
@Composable
fun SongActionMenu(
    item: LibrarySongRowModel,
    actions: SongActions,
    resourceFresh: Boolean = true,
    modifier: Modifier = Modifier,
    onFavorite: (() -> Unit)? = null,
    content: @Composable () -> Unit,
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
        content = content,
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
    content: @Composable () -> Unit,
) {
    var expanded by remember(menuKey) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clickable(enabled = enabled) { expanded = true }
            .testTag("song-action-area-$menuKey"),
    ) {
        content()
        // Keep the popup near the row's trailing edge (the old overflow affordance location),
        // while the actual click target remains the entire song surface.
        Box(modifier = Modifier.align(Alignment.CenterEnd).size(1.dp)) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .graphicsLayer(alpha = 0.90f)
                    .testTag("song-action-menu-$menuKey"),
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
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.CenterHorizontally),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.TextButton(
            onClick = onPlayAll,
            enabled = effectiveEnabled,
            modifier = Modifier.defaultMinSize(minWidth = 88.dp, minHeight = 48.dp)
                .testTag("$tag-play-all"),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp).testTag("$tag-play-icon"),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(5.dp))
            Text("全部播放")
        }
        androidx.compose.material3.TextButton(
            onClick = onShuffle,
            enabled = effectiveEnabled,
            modifier = Modifier.defaultMinSize(minWidth = 88.dp, minHeight = 48.dp)
                .testTag("$tag-shuffle"),
        ) {
            ShuffleGlyph(
                modifier = Modifier.size(18.dp).testTag("$tag-shuffle-icon"),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(5.dp))
            Text("随机播放")
        }
    }
}


/** Compact shuffle glyph drawn locally so the app can keep its small Material Icons dependency. */
@Composable
private fun ShuffleGlyph(
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color,
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(
            width = 1.9.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val upper = Path().apply {
            moveTo(3f * sx, 7f * sy)
            lineTo(7f * sx, 7f * sy)
            lineTo(17f * sx, 17f * sy)
            lineTo(21f * sx, 17f * sy)
        }
        val lower = Path().apply {
            moveTo(3f * sx, 17f * sy)
            lineTo(7f * sx, 17f * sy)
            lineTo(17f * sx, 7f * sy)
            lineTo(21f * sx, 7f * sy)
        }
        val upperArrow = Path().apply {
            moveTo(17f * sx, 13f * sy)
            lineTo(21f * sx, 17f * sy)
            lineTo(17f * sx, 21f * sy)
        }
        val lowerArrow = Path().apply {
            moveTo(17f * sx, 3f * sy)
            lineTo(21f * sx, 7f * sy)
            lineTo(17f * sx, 11f * sy)
        }
        drawPath(upper, tint, style = stroke)
        drawPath(lower, tint, style = stroke)
        drawPath(upperArrow, tint, style = stroke)
        drawPath(lowerArrow, tint, style = stroke)
    }
}
