package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Responsive artwork surface. Its size is bounded by the parent’s real available space. */
@Composable
fun ArtworkStage(
    bitmap: ImageBitmap?,
    placeholderLabel: String,
    modifier: Modifier = Modifier,
    dismissGestureModifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    artworkVisible: Boolean = true,
    onArtworkBoundsChanged: (boundsOnScreen: Rect) -> Unit = {},
) {
    var artworkCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentArtworkCoordinates = artworkCoordinates
    SideEffect {
        currentArtworkCoordinates?.let { coordinates ->
            val origin = coordinates.positionOnScreen()
            onArtworkBoundsChanged(
                Rect(
                    left = origin.x,
                    top = origin.y,
                    right = origin.x + coordinates.size.width,
                    bottom = origin.y + coordinates.size.height,
                ),
            )
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag("player-artwork-bounds")) {
        val artworkSide = calculateArtworkSideDp(
            availableWidthDp = maxWidth.value,
            availableHeightDp = maxHeight.value * 0.94f,
        ).dp
        Box(
            modifier = Modifier
                .size(artworkSide)
                .align(Alignment.Center)
                .then(dismissGestureModifier)
                // The visibility layer must enclose the background as well as the artwork image.
                .graphicsLayer { alpha = if (artworkVisible) 1f else 0f }
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .onGloballyPositioned { artworkCoordinates = it }
                .clickable(enabled = onTap != null) { onTap?.invoke() }
                .testTag("player-artwork-stage"),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "当前曲目专辑封面",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PlayerGlyph(
                        kind = PlayerGlyphKind.ALBUM,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(52.dp),
                    )
                    Text(
                        text = placeholderLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
