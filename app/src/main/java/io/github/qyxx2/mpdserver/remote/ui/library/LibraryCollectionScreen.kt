package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongListDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime

@Composable
fun LibraryCollectionScreen(
    runtime: AppRuntime,
    category: LibraryCategory,
    sourceId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (category == LibraryCategory.PLAYLISTS) {
        PlaylistScreen(runtime, onOpenDetail = {}, onBack = onBack, playlistId = sourceId, modifier = modifier)
        return
    }

    var retryToken by remember(category, sourceId) { mutableIntStateOf(0) }
    val state = rememberLibraryLoadState(
        runtime = runtime,
        resourceKey = "library-detail:${category.pathSegment}:$sourceId",
        retryToken = retryToken,
    ) {
        when (category) {
            LibraryCategory.ALBUMS -> runtime.reads.getAlbumSongs(sourceId)
            LibraryCategory.ARTISTS -> runtime.reads.getArtistSongs(sourceId)
            LibraryCategory.GENRES -> runtime.reads.getGenreSongs(sourceId)
            LibraryCategory.YEARS -> runtime.reads.getYearSongs(sourceId)
            LibraryCategory.TAGS -> runtime.reads.getTagSongs(sourceId)
            else -> ApiResult.ProtocolError("This Library category has no entity detail endpoint")
        }
    }

    LibraryPage(modifier = modifier, tag = "library-collection-screen") {
        LibraryPageHeader(category.label, onBack)
        LibraryResourceContent(
            state = state,
            emptyText = "此集合暂无歌曲",
            onRetry = { retryToken += 1 },
            isEmpty = { it.items.isEmpty() },
        ) { list: SongListDto ->
            val rows = remember(list) { LibraryProjection.songRows(list.items) }
            LazyColumn(modifier = Modifier.fillMaxSize().testTag("library-collection-songs")) {
                items(rows, key = { it.key }) { LibrarySongRow(it) }
            }
        }
    }
}
