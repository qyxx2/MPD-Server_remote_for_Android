package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.ui.library.hasUnresolvedPlaybackMutation

/** AppNavHost reduces shared server-owned resource state once for every Song action surface. */
val LocalPlaybackActionsEnabled = staticCompositionLocalOf { true }
val LocalFavoritesActions = staticCompositionLocalOf<FavoritesActions?> { null }
val LocalFavoriteSongIds = staticCompositionLocalOf<Set<String>> { emptySet() }
val LocalFavoritesResourceFresh = staticCompositionLocalOf { false }

/**
 * A song's complete visual surface is the anchor for its Web-equivalent action menu.
 * Playback stays availability-gated; Favorite can still remove an indexed server member whose
 * Library metadata is missing, matching the Web unavailable-member path.
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
    val snapshotWritable = LocalPlaybackActionsEnabled.current
    val playbackEnabled = item.isPlayable && resourceFresh && snapshotWritable
    val favoriteActions = LocalFavoritesActions.current
    val favoritesFresh = LocalFavoritesResourceFresh.current
    val favoriteSongId = item.songId?.takeIf(String::isNotBlank)
    val isFavorite = favoriteSongId != null && favoriteSongId in LocalFavoriteSongIds.current
    val favoriteEnabled = favoriteSongId != null &&
        favoriteActions != null &&
        favoritesFresh &&
        snapshotWritable
    val favoriteCallback = onFavorite ?: favoriteSongId?.let { songId ->
        favoriteActions?.let { owner ->
            { owner.setFavorite(songId, isFavorite = isFavorite, resourceFresh = favoritesFresh); Unit }
        }
    }
    AnchoredSongActionMenu(
        menuKey = item.key.hashCode().toString(),
        enabled = playbackEnabled || favoriteEnabled,
        playbackEnabled = playbackEnabled,
        favoriteEnabled = favoriteEnabled,
        favoriteLabel = if (isFavorite) "取消收藏" else "收藏",
        modifier = modifier,
        onAction = { action -> actions.perform(item, action, resourceFresh) },
        onFavorite = favoriteCallback,
        content = content,
    )
}

/**
 * UI primitive separated from the write adapter. The popup is centered over the row that opened
 * it, while the menu measures to its longest label instead of stretching to a screen-wide width.
 */
@Composable
internal fun AnchoredSongActionMenu(
    menuKey: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onAction: (SongPlaybackAction) -> Unit,
    onFavorite: (() -> Unit)? = null,
    playbackEnabled: Boolean = enabled,
    favoriteEnabled: Boolean = enabled,
    favoriteLabel: String = "收藏",
    content: @Composable () -> Unit,
) {
    var expanded by remember(menuKey) { mutableStateOf(false) }
    val density = LocalDensity.current
    val positionProvider = remember(density) {
        CenteredSongActionMenuPositionProvider(with(density) { 8.dp.roundToPx() })
    }

    Box(
        modifier = modifier
            .clickable(enabled = enabled) { expanded = true }
            .testTag("song-action-area-$menuKey"),
    ) {
        content()
        if (expanded) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                Surface(
                    modifier = Modifier
                        .width(IntrinsicSize.Max)
                        .testTag("song-action-menu-$menuKey"),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White.copy(alpha = 0.94f),
                    contentColor = SongMenuTextColor,
                    tonalElevation = 0.dp,
                    shadowElevation = 7.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        SongPlaybackAction.entries.forEach { action ->
                            SongActionMenuItem(
                                menuKey = menuKey,
                                tagAction = action.name.lowercase(),
                                label = action.label,
                                glyph = when (action) {
                                    SongPlaybackAction.PLAY_NOW -> SongActionGlyph.PLAY
                                    SongPlaybackAction.PLAY_NEXT -> SongActionGlyph.PLAY_NEXT
                                    SongPlaybackAction.ADD_TO_QUEUE -> SongActionGlyph.QUEUE_ADD
                                },
                                enabled = playbackEnabled,
                                onClick = {
                                    expanded = false
                                    onAction(action)
                                },
                            )
                        }
                        if (onFavorite != null) {
                            SongActionMenuItem(
                                menuKey = menuKey,
                                tagAction = "favorite",
                                label = favoriteLabel,
                                glyph = SongActionGlyph.FAVORITE,
                                enabled = favoriteEnabled,
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
    }
}

private val SongMenuTextColor = Color(0xFF252525)
private val SongMenuIconColor = Color(0xFF555555)

private enum class SongActionGlyph {
    PLAY,
    PLAY_NEXT,
    QUEUE_ADD,
    FAVORITE,
}

@Composable
private fun SongActionMenuItem(
    menuKey: String,
    tagAction: String,
    label: String,
    glyph: SongActionGlyph,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val itemAlpha = if (enabled) 1f else 0.38f
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { role = Role.Button }
            .testTag("song-action-$tagAction-$menuKey")
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            SongActionGlyphView(
                glyph = glyph,
                tint = SongMenuIconColor.copy(alpha = itemAlpha),
                modifier = Modifier
                    .size(19.dp)
                    .testTag("song-action-icon-$tagAction-$menuKey"),
            )
        }
        Spacer(Modifier.width(11.dp))
        Text(
            text = label,
            modifier = Modifier.testTag("song-action-label-$tagAction-$menuKey"),
            color = SongMenuTextColor.copy(alpha = itemAlpha),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun SongActionGlyphView(
    glyph: SongActionGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(
            width = 1.9.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        when (glyph) {
            SongActionGlyph.PLAY -> {
                val path = Path().apply {
                    moveTo(6f * sx, 3f * sy)
                    lineTo(20f * sx, 12f * sy)
                    lineTo(6f * sx, 21f * sy)
                    close()
                }
                drawPath(path, tint)
            }
            SongActionGlyph.PLAY_NEXT -> {
                val path = Path().apply {
                    moveTo(3f * sx, 3f * sy)
                    lineTo(16f * sx, 12f * sy)
                    lineTo(3f * sx, 21f * sy)
                    close()
                }
                drawPath(path, tint)
                drawLine(
                    color = tint,
                    start = Offset(21f * sx, 3f * sy),
                    end = Offset(21f * sx, 21f * sy),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }
            SongActionGlyph.QUEUE_ADD -> {
                listOf(5f, 11f, 17f).forEach { y ->
                    drawLine(
                        color = tint,
                        start = Offset(3f * sx, y * sy),
                        end = Offset(14f * sx, y * sy),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                }
                drawLine(
                    color = tint,
                    start = Offset(19f * sx, 12f * sy),
                    end = Offset(19f * sx, 22f * sy),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(14f * sx, 17f * sy),
                    end = Offset(24f * sx, 17f * sy),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }
            SongActionGlyph.FAVORITE -> {
                val path = Path().apply {
                    moveTo(12f * sx, 21f * sy)
                    cubicTo(10f * sx, 19f * sy, 3f * sx, 13f * sy, 3f * sx, 8f * sy)
                    cubicTo(3f * sx, 3f * sy, 8f * sx, 2f * sy, 12f * sx, 7f * sy)
                    cubicTo(16f * sx, 2f * sy, 21f * sx, 3f * sy, 21f * sx, 8f * sy)
                    cubicTo(21f * sx, 13f * sy, 14f * sx, 19f * sy, 12f * sx, 21f * sy)
                    close()
                }
                drawPath(path, tint, style = stroke)
            }
        }
    }
}

/** Keep the popup centered on the tapped row, but clamp it inside the visible window. */
private class CenteredSongActionMenuPositionProvider(
    private val edgeInsetPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        fun centeredAndClamped(
            anchorStart: Int,
            anchorExtent: Int,
            popupExtent: Int,
            windowExtent: Int,
        ): Int {
            val maximumStart = (windowExtent - popupExtent).coerceAtLeast(0)
            val inset = edgeInsetPx.coerceAtMost(maximumStart / 2)
            val preferredStart = anchorStart + (anchorExtent - popupExtent) / 2
            return preferredStart.coerceIn(inset, maximumStart - inset)
        }

        return IntOffset(
            x = centeredAndClamped(
                anchorStart = anchorBounds.left,
                anchorExtent = anchorBounds.width,
                popupExtent = popupContentSize.width,
                windowExtent = windowSize.width,
            ),
            y = centeredAndClamped(
                anchorStart = anchorBounds.top,
                anchorExtent = anchorBounds.height,
                popupExtent = popupContentSize.height,
                windowExtent = windowSize.height,
            ),
        )
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
    playAllLabel: String = "全部播放",
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
            Text(playAllLabel)
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
