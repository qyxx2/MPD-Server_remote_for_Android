package io.github.qyxx2.mpdserver.remote.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.ui.queue.QueueResourceIdentity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect

@Composable
internal fun HomeSection(
    title: String,
    sectionTag: String,
    railTag: String,
    state: HomeSectionState<*>,
    scrollState: LazyListState,
    runtime: AppRuntime?,
    artworkIdentity: QueueResourceIdentity?,
    enableArtworkPrefetch: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cards = state.cardsForSection()
    val isStale = state is HomeSectionState.Stale<*>

    // Load the visible window plus at most two look-ahead cards. AppRuntime owns the single-flight
    // raw-byte cache; this screen only warms the shared bounded thumbnail adapter.
    LaunchedEffect(runtime, artworkIdentity, scrollState, cards, enableArtworkPrefetch) {
        if (runtime == null || artworkIdentity == null || !enableArtworkPrefetch ||
            state !is HomeSectionState.Ready<*>
        ) return@LaunchedEffect
        snapshotFlow {
            HomeArtworkPrefetchPolicy.indices(
                visibleItemIndexes = scrollState.layoutInfo.visibleItemsInfo.map { it.index },
                itemCount = cards.size,
            )
        }.distinctUntilChanged().collect { indexes ->
            indexes.forEach { index ->
                val card = cards.getOrNull(index) ?: return@forEach
                val songId = card.artworkSongId?.takeIf(String::isNotBlank) ?: return@forEach
                val artworkId = card.artwork?.artworkId?.takeIf(String::isNotBlank) ?: return@forEach
                val key = HomeArtworkCacheKey(artworkIdentity, artworkId)
                when (val response = runtime.reads.getArtwork(songId)) {
                    is HttpResult.Body -> HomeArtworkMemoryCache.preload(key, response.bytes)
                    else -> Unit
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag(sectionTag),
    ) {
        val coverSize = (maxHeight - 80.dp).coerceAtLeast(48.dp).coerceAtMost(176.dp)
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (isStale) {
                    Text(
                        text = "显示上次数据",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
            LazyRow(
                state = scrollState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag(railTag),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 18.dp,
                    vertical = 2.dp,
                ),
            ) {
                if (cards.isNotEmpty()) {
                    items(cards, key = { it.key }) { card ->
                        HomeArtworkCardItem(
                            card = card,
                            sectionTitle = title,
                            coverSize = coverSize,
                            artworkIdentity = artworkIdentity,
                        )
                    }
                } else {
                    item(key = "section-state") {
                        HomeSectionPlaceholder(
                            state = state,
                            onRefresh = onRefresh,
                        )
                    }
                }
            }
        }
    }
}

private fun HomeSectionState<*>.cardsForSection(): List<HomeArtworkCard> = when (this) {
    is HomeSectionState.Ready<*> -> when (val value = value) {
        is HomeRecentUpdates -> value.cards
        is HomePlaybackHistory -> value.cards
        is HomeAutoplay -> value.cards
        else -> emptyList()
    }
    is HomeSectionState.Stale<*> -> when (val value = value) {
        is HomeRecentUpdates -> value.cards
        is HomePlaybackHistory -> value.cards
        is HomeAutoplay -> value.cards
        else -> emptyList()
    }
    else -> emptyList()
}

@Composable
private fun HomeSectionPlaceholder(
    state: HomeSectionState<*>,
    onRefresh: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        when (state) {
            HomeSectionState.Loading -> Text(
                text = "正在加载",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            HomeSectionState.Empty -> Text(
                text = "暂无内容",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            is HomeSectionState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onRefresh) { Text("重试") }
            }
            is HomeSectionState.Ready<*>, is HomeSectionState.Stale<*> -> Text(
                text = "暂无内容",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}


/** The viewport window plus at most two look-ahead cards; empty/out-of-range indices are ignored. */
internal object HomeArtworkPrefetchPolicy {
    fun indices(
        visibleItemIndexes: List<Int>,
        itemCount: Int,
        lookAheadCount: Int = 2,
    ): List<Int> {
        if (itemCount <= 0 || lookAheadCount < 0) return emptyList()
        val visible = visibleItemIndexes.filter { it in 0 until itemCount }
        if (visible.isEmpty()) return emptyList()
        val first = visible.minOrNull() ?: return emptyList()
        val last = visible.maxOrNull() ?: return emptyList()
        return (first..minOf(itemCount - 1, last + lookAheadCount)).toList()
    }
}
