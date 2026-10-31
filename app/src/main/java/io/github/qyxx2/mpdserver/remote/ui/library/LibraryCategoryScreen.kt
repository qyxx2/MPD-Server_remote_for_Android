package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime

@Composable
fun LibraryCategoryScreen(
    runtime: AppRuntime,
    category: LibraryCategory,
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (category) {
        LibraryCategory.SONGS -> SongsLibraryScreen(runtime, onBack, modifier)
        LibraryCategory.PLAYLISTS -> PlaylistScreen(runtime, onOpenDetail, onBack, modifier = modifier)
        LibraryCategory.FAVORITES -> FavoritesScreen(runtime, onBack, modifier)
        else -> SummaryLibraryCategoryScreen(runtime, category, onOpenDetail, onBack, modifier)
    }
}

private data class LibrarySummaryItem(
    val key: String,
    val sourceId: String,
    val title: String,
    val subtitle: String,
)

@Composable
private fun SummaryLibraryCategoryScreen(
    runtime: AppRuntime,
    category: LibraryCategory,
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    var retryToken by remember(category) { mutableIntStateOf(0) }
    val state = rememberLibraryLoadState(
        runtime = runtime,
        resourceKey = "library-category:${category.pathSegment}",
        retryToken = retryToken,
    ) {
        when (category) {
            LibraryCategory.ARTISTS -> runtime.reads.getArtists().mapSuccess { result ->
                result.items.map { LibrarySummaryItem(it.artistId, it.artistId, it.name, "${it.songCount} 首歌曲") }
            }
            LibraryCategory.ALBUMS -> runtime.reads.getAlbums().mapSuccess { result ->
                result.items.map {
                    LibrarySummaryItem(
                        key = it.albumId,
                        sourceId = it.albumId,
                        title = it.title,
                        subtitle = listOfNotNull(it.albumArtists.takeIf { names -> names.isNotEmpty() }?.joinToString("、"),
                            it.year?.toString(), "${it.songCount} 首歌曲").joinToString(" · "),
                    )
                }
            }
            LibraryCategory.GENRES -> runtime.reads.getGenres().mapSuccess { result ->
                result.items.map { LibrarySummaryItem(it.genreId, it.genreId, it.name, "${it.songCount} 首歌曲") }
            }
            LibraryCategory.YEARS -> runtime.reads.getYears().mapSuccess { result ->
                result.items.map {
                    LibrarySummaryItem(it.sourceId, it.sourceId, it.value?.toString() ?: "未知年代", "${it.songCount} 首歌曲")
                }
            }
            LibraryCategory.TAGS -> runtime.reads.getTags().mapSuccess { result ->
                result.items.map { LibrarySummaryItem(it.tagId, it.tagId, it.name, "${it.songCount} 首歌曲") }
            }
            else -> ApiResult.ProtocolError("Unsupported Library summary category")
        }
    }

    LibraryPage(
        modifier = modifier,
        tag = "library-summary-screen-${category.pathSegment}",
        onBack = onBack,
    ) {
        LibraryPageHeader(category.label)
        LibraryResourceContent(
            state = state,
            emptyText = "暂无${category.label}内容",
            onRetry = { retryToken += 1 },
            isEmpty = { it.isEmpty() },
        ) { rows ->
            LazyColumn(modifier = Modifier.fillMaxSize().testTag("library-summary-list")) {
                items(rows, key = { it.key }) { row ->
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onOpenDetail(LibraryRoutes.detail(category, row.sourceId)) }
                                .padding(horizontal = 20.dp, vertical = 14.dp)
                                .testTag("library-summary-${row.key}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text(row.subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

private data class SongSortOption(val sortBy: String, val sortOrder: String, val label: String) {
    companion object {
        val all = listOf(
            SongSortOption("title", "asc", "标题升序"),
            SongSortOption("title", "desc", "标题降序"),
            SongSortOption("modified_at", "asc", "修改时间升序"),
            SongSortOption("modified_at", "desc", "修改时间降序"),
        )
    }
}

@Composable
private fun SongsLibraryScreen(
    runtime: AppRuntime,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    var selectedSort by remember { mutableStateOf(SongSortOption.all.first()) }
    var menuExpanded by remember { mutableStateOf(false) }
    var retryToken by remember { mutableStateOf(0) }
    val state = rememberLibraryLoadState(
        runtime = runtime,
        resourceKey = "library-songs:${selectedSort.sortBy}:${selectedSort.sortOrder}",
        retryToken = retryToken,
    ) {
        runtime.reads.getSongs(sortBy = selectedSort.sortBy, sortOrder = selectedSort.sortOrder)
    }

    LibraryPage(modifier = modifier, tag = "library-songs-screen", onBack = onBack) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "歌曲",
                modifier = Modifier.weight(1f),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            TextButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.testTag("library-song-sort-button"),
            ) {
                Text("排序：${selectedSort.label}  ⇅", fontSize = 13.sp)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                SongSortOption.all.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            selectedSort = option
                            menuExpanded = false
                        },
                        modifier = Modifier.testTag("library-sort-${option.sortBy}-${option.sortOrder}"),
                    )
                }
            }
        }
        LibraryResourceContent(
            state = state,
            emptyText = "暂无歌曲",
            onRetry = { retryToken += 1 },
            isEmpty = { it.items.isEmpty() },
        ) { list ->
            val rows = remember(list) { LibraryProjection.songRows(list.items) }
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("library-song-list"),
            ) {
                items(rows, key = { it.key }) { row -> LibrarySongRow(row) }
            }
        }
    }
}

@Composable
internal fun LibraryPage(
    modifier: Modifier = Modifier,
    tag: String,
    onBack: () -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().libraryBackGesture(onBack).testTag(tag),
        content = content,
    )
}

@Composable
internal fun LibraryPageHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun <T : Any> LibraryResourceContent(
    state: LibraryLoadState<T>,
    emptyText: String,
    onRetry: () -> Unit,
    isEmpty: (T) -> Boolean = { false },
    content: @Composable (T) -> Unit,
) {
    when (state) {
        LibraryLoadState.Loading -> LibraryMessage("正在加载")
        LibraryLoadState.Empty -> LibraryMessage(emptyText)
        is LibraryLoadState.Ready -> if (isEmpty(state.value)) LibraryMessage(emptyText) else content(state.value)
        is LibraryLoadState.Stale -> Column {
            Text(
                "离线缓存 · 数据可能不是最新",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.tertiary,
                fontSize = 12.sp,
            )
            if (isEmpty(state.value)) LibraryMessage(emptyText) else content(state.value)
        }
        is LibraryLoadState.Failed -> Column {
            LibraryMessage(state.message)
            TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("重试")
            }
        }
    }
}

@Composable
internal fun LibraryMessage(text: String) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

private fun <T, R> ApiResult<T>.mapSuccess(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value), statusCode)
    else -> asFailure()
}
