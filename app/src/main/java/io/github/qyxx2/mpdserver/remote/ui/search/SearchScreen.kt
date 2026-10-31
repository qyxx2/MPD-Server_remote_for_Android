package io.github.qyxx2.mpdserver.remote.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.protocol.dto.SearchGroupKindDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.ui.preferences.SearchHistoryPolicy
import io.github.qyxx2.mpdserver.remote.ui.library.LibrarySongArtwork
import io.github.qyxx2.mpdserver.remote.ui.library.LibrarySongRow
import io.github.qyxx2.mpdserver.remote.ui.queue.QueueResourceIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    controller: SearchController,
    onOpenCategory: (kind: SearchGroupKindDto, sourceId: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
    runtime: AppRuntime? = null,
    artworkIdentity: QueueResourceIdentity? = null,
    searchHistory: Flow<List<String>> = emptyFlow(),
    onClearSearchHistory: suspend () -> Unit = {},
    showArtwork: Boolean = true,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val history by searchHistory.collectAsStateWithLifecycle(initialValue = emptyList())
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier.fillMaxSize().testTag("search-screen"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { if (interactionEnabled) controller.setQuery(it) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("search-query-field"),
            enabled = interactionEnabled,
            readOnly = !interactionEnabled,
            singleLine = true,
            label = { Text("搜索音乐") },
            placeholder = { Text("歌曲、艺人、专辑、流派或标签") },
            trailingIcon = {
                if (interactionEnabled && state.query.isNotEmpty()) {
                    TextButton(
                        onClick = { controller.setQuery("") },
                        modifier = Modifier.testTag("search-clear-button"),
                    ) { Text("清除") }
                }
            },
        )

        if (state.query.isBlank() && history.isNotEmpty()) {
            SearchHistorySection(
                history = history.take(SearchHistoryPolicy.MAX_ENTRIES),
                interactionEnabled = interactionEnabled,
                onSelect = controller::setQuery,
                onClear = {
                    coroutineScope.launch { onClearSearchHistory() }
                },
            )
        } else {
            when (val content = state.content) {
                SearchContentState.Loading -> SearchMessage("正在搜索…")
                SearchContentState.Empty -> SearchMessage(
                    if (state.query.isBlank()) "输入关键词开始搜索" else "没有找到相关内容",
                )
                is SearchContentState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SearchMessage(content.message)
                    if (interactionEnabled) {
                        TextButton(onClick = controller::refresh, modifier = Modifier.testTag("search-retry")) {
                            Text("重试")
                        }
                    }
                }
                is SearchContentState.Ready -> SearchResults(
                    projection = content.projection,
                    onOpenCategory = onOpenCategory,
                    interactionEnabled = interactionEnabled,
                    stale = false,
                    runtime = runtime,
                    artworkIdentity = artworkIdentity,
                    showArtwork = showArtwork,
                )
                is SearchContentState.Stale -> SearchResults(
                    projection = content.projection,
                    onOpenCategory = onOpenCategory,
                    interactionEnabled = interactionEnabled,
                    stale = true,
                    runtime = runtime,
                    artworkIdentity = artworkIdentity,
                    showArtwork = showArtwork,
                )
            }
        }
    }
}

@Composable
private fun SearchHistorySection(
    history: List<String>,
    interactionEnabled: Boolean,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("search-history-section"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "搜索历史",
                modifier = Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (interactionEnabled) {
                TextButton(onClick = onClear, modifier = Modifier.testTag("search-history-clear")) {
                    Text("清空")
                }
            }
        }
        history.take(SearchHistoryPolicy.MAX_ENTRIES).forEachIndexed { index, query ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .then(if (interactionEnabled) Modifier.clickable { onSelect(query) } else Modifier)
                    .padding(horizontal = 20.dp, vertical = 5.dp)
                    .testTag("search-history-item-$index"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "◷",
                    modifier = Modifier.padding(end = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 17.sp,
                )
                Text(
                    query,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    projection: SearchProjection,
    onOpenCategory: (SearchGroupKindDto, String, String) -> Unit,
    interactionEnabled: Boolean,
    stale: Boolean,
    runtime: AppRuntime?,
    artworkIdentity: QueueResourceIdentity?,
    showArtwork: Boolean,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("search-results"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (stale) {
            item("search-stale-banner") {
                Text(
                    "离线缓存 · 搜索结果可能不是最新",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.tertiary,
                    fontSize = 12.sp,
                )
            }
        }
        projection.groups.forEachIndexed { index, group ->
            item("search-group-header-$index-${group.sourceId}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (interactionEnabled) {
                                Modifier.clickable {
                                    onOpenCategory(group.kind, group.sourceId, group.name)
                                }
                            } else Modifier,
                        )
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .testTag("search-group-${group.kind.name.lowercase()}-${group.sourceId}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showArtwork && group.kind == SearchGroupKindDto.ALBUMS) {
                        LibrarySongArtwork(
                            song = group.songs.firstNotNullOfOrNull { it.song },
                            runtime = runtime,
                            artworkIdentity = artworkIdentity,
                            size = 52.dp,
                            modifier = Modifier.testTag("search-album-artwork-${group.sourceId}"),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = group.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                        Text(
                            text = "${group.kind.displayName()} · ${group.songCount} 首歌曲",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 24.sp)
                }
            }
            items(group.songs, key = { "group-song-$index:${it.key}" }) { song ->
                LibrarySongRow(
                    item = song,
                    modifier = Modifier.padding(start = 16.dp),
                    showArtwork = showArtwork,
                    runtime = runtime,
                    artworkIdentity = artworkIdentity,
                )
            }
        }
        if (projection.songs.isNotEmpty()) {
            item("search-song-section") {
                Text(
                    "歌曲",
                    modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            items(projection.songs, key = { "search-song:${it.key}" }) { song ->
                LibrarySongRow(
                    item = song,
                    showArtwork = showArtwork,
                    runtime = runtime,
                    artworkIdentity = artworkIdentity,
                )
            }
        }
    }
}

@Composable
private fun SearchMessage(text: String) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

private fun SearchGroupKindDto.displayName(): String = when (this) {
    SearchGroupKindDto.ALBUMS -> "专辑"
    SearchGroupKindDto.ARTISTS -> "艺人"
    SearchGroupKindDto.GENRES -> "流派/风格"
    SearchGroupKindDto.YEARS -> "年代"
    SearchGroupKindDto.TAGS -> "标签"
}
