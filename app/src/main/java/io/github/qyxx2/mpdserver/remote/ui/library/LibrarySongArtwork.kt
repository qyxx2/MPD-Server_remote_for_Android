package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.ResourceCacheIdentityState
import io.github.qyxx2.mpdserver.remote.ui.queue.QueueArtworkThumbnailCache
import io.github.qyxx2.mpdserver.remote.ui.queue.QueueArtworkThumbnailKey
import io.github.qyxx2.mpdserver.remote.ui.queue.QueueResourceIdentity

/**
 * Search/category thumbnail surface. It deliberately reuses Queue's 160px decoded cache and
 * AppRuntime's compressed-artwork resource; it never touches Home's 768px artwork cache.
 */
@Composable
internal fun LibrarySongArtwork(
    song: SongDto?,
    runtime: AppRuntime?,
    artworkIdentity: QueueResourceIdentity?,
    size: Dp,
    modifier: Modifier = Modifier,
    testTag: String = "library-song-artwork",
) {
    val artworkId = song?.artwork?.artworkId?.takeIf(String::isNotBlank)
    val songId = song?.songId?.takeIf(String::isNotBlank)
    val key = remember(artworkIdentity, artworkId) {
        if (artworkIdentity != null && artworkId != null) {
            QueueArtworkThumbnailKey(artworkIdentity, artworkId)
        } else null
    }
    val thumbnailState = remember(key) { key?.let(QueueArtworkThumbnailCache::stateFor) }
    val bitmap = thumbnailState?.collectAsStateWithLifecycle()?.value
    val resourceIdentityState = rememberLibraryResourceIdentity(runtime)

    LaunchedEffect(key, songId, bitmap, resourceIdentityState) {
        if (key == null || songId == null || bitmap != null || runtime == null) return@LaunchedEffect
        val activeIdentity = resourceIdentityState ?: return@LaunchedEffect
        if (!key.identity.matches(activeIdentity)) return@LaunchedEffect
        when (val response = runtime.reads.getArtwork(songId)) {
            is HttpResult.Body -> {
                val contentType = response.contentType.orEmpty().substringBefore(';').trim()
                if (contentType.startsWith("image/", ignoreCase = true) &&
                    key.identity.matches(runtime.resources.identityState.value)
                ) {
                    QueueArtworkThumbnailCache.preload(key, response.bytes)
                }
            }
            else -> Unit
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = if (bitmap != null) "专辑封面" else "专辑封面加载中" }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().testTag("$testTag-image"),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = "♫",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("$testTag-placeholder"),
            )
        }
    }
}

private fun QueueResourceIdentity.matches(state: ResourceCacheIdentityState): Boolean {
    val session = state.session ?: return false
    return state.isFresh && !state.isClosed &&
        session.originAddress == originAddress &&
        session.connectionGeneration == connectionGeneration &&
        session.epoch == epoch &&
        state.watermarks?.libraryRevision == libraryRevision
}


@Composable
private fun rememberLibraryResourceIdentity(
    runtime: AppRuntime?,
): ResourceCacheIdentityState? {
    if (runtime == null) return null
    val identity by runtime.resources.identityState.collectAsStateWithLifecycle()
    return identity
}
