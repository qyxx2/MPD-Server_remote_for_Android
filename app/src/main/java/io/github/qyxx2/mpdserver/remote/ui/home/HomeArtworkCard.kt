package io.github.qyxx2.mpdserver.remote.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.ui.queue.QueueResourceIdentity

@Composable
internal fun HomeArtworkCardItem(
    card: HomeArtworkCard,
    sectionTitle: String,
    coverSize: Dp,
    artworkIdentity: QueueResourceIdentity?,
    onClick: (() -> Unit)? = null,
) {
    val artworkId = card.artwork?.artworkId?.takeIf(String::isNotBlank)
    val artworkKey = remember(artworkIdentity, artworkId) {
        if (artworkIdentity != null && !artworkId.isNullOrBlank()) {
            HomeArtworkCacheKey(artworkIdentity, artworkId)
        } else null
    }
    val artworkState = remember(artworkKey) {
        artworkKey?.let(HomeArtworkMemoryCache::stateFor)
    }
    val bitmap = artworkState?.collectAsStateWithLifecycle()?.value

    val title = card.song?.album?.takeIf(String::isNotBlank)
        ?: card.song?.title?.takeIf(String::isNotBlank)
        ?: if (card.kind == HomeCardKind.HISTORY_ORPHAN) "曲目已不在曲库" else "未知专辑"
    val artists = card.song?.albumArtists?.takeIf { it.isNotEmpty() }
        ?: card.song?.artists.orEmpty()
    val subtitle = artists.joinToString("、").ifBlank { "未知艺人" }
    val identityText = card.queueItemId?.let { "，队列项 $it" }.orEmpty()
    val accessibilityLabel = "$sectionTitle，$title，$subtitle$identityText"
    val tileModifier = Modifier
        .width(coverSize)
        .semantics { contentDescription = accessibilityLabel }
        .testTag("home-card-${sectionTitle.hashCode()}-${card.key.hashCode()}")
    val interactiveModifier = if (onClick != null) {
        tileModifier.clickable(onClick = onClick)
    } else tileModifier

    Column(
        modifier = interactiveModifier,
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .size(coverSize)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = "♫",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
        Text(
            text = title,
            modifier = Modifier.width(coverSize),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            modifier = Modifier.width(coverSize),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
