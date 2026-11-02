package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Draws the already-loaded current artwork in the AppNavHost coordinate space.
 * It owns no loading or playback state; a mismatched cache identity renders nothing.
 */
@Composable
internal fun ArtworkTransitionOverlay(
    bitmap: ImageBitmap?,
    bitmapKey: PlayerArtworkCacheKey?,
    currentKey: PlayerArtworkCacheKey?,
    sourceBounds: Rect?,
    targetBounds: Rect?,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    if (bitmap == null || bitmapKey == null || bitmapKey != currentKey) return
    val source = sourceBounds ?: return
    val target = targetBounds ?: return
    if (!progress.isFinite()) return
    val bounds = runCatching {
        ArtworkTransitionGeometry.interpolate(source, target, progress)
    }.getOrNull() ?: return
    val density = LocalDensity.current
    val width = with(density) { bounds.width.toDp() }
    val height = with(density) { bounds.height.toDp() }
    val cornerRadius = (8f + 10f * progress.coerceIn(0f, 1f)).dp

    Box(modifier = modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                .size(width, height)
                .clip(RoundedCornerShape(cornerRadius))
                .testTag("mini-player-artwork-transition-overlay"),
        )
    }
}
