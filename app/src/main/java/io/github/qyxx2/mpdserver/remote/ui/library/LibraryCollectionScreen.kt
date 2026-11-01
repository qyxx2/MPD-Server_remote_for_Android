package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    artworkIdentity: io.github.qyxx2.mpdserver.remote.ui.queue.QueueResourceIdentity? = null,
    songActions: SongActions? = null,
    collectionActions: LibraryCollectionActions? = null,
) {
    if (category == LibraryCategory.ALBUMS) {
        LibraryAlbumDetailScreen(
            runtime = runtime,
            sourceId = sourceId,
            onBack = onBack,
            modifier = modifier,
            artworkIdentity = artworkIdentity,
            songActions = songActions,
            collectionActions = collectionActions,
        )
        return
    }

    if (category == LibraryCategory.PLAYLISTS) {
        PlaylistScreen(runtime, onOpenDetail = {}, onBack = onBack, playlistId = sourceId, modifier = modifier,
            songActions = songActions, collectionActions = collectionActions)
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

    LibraryPage(modifier = modifier, tag = "library-collection-screen", onBack = onBack) {
        LibraryPageHeader(category.label)
        if (collectionActions != null) {
            val request = CollectionPlaybackRequest.forCategory(category, sourceId)
            if (request != null) {
                CollectionPlaybackControls(
                    enabled = state is LibraryLoadState.Ready && collectionActions.canPlay(),
                    onPlayAll = { collectionActions.play(request, resourceFresh = state is LibraryLoadState.Ready) },
                    onShuffle = {
                    collectionActions.play(
                        request.withRandomize(true),
                        resourceFresh = state is LibraryLoadState.Ready,
                    )
                },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    tag = "library-detail-collection-controls",
                )
            }
        }
        LibraryResourceContent(
            state = state,
            emptyText = "此集合暂无歌曲",
            onRetry = { retryToken += 1 },
            isEmpty = { it.items.isEmpty() },
        ) { list: SongListDto ->
            val rows = remember(list) { LibraryProjection.songRows(list.items) }
            LazyColumn(modifier = Modifier.fillMaxSize().testTag("library-collection-songs")) {
                items(rows, key = { it.key }) {
                    LibrarySongRow(
                        it,
                        showArtwork = true,
                        runtime = runtime,
                        artworkIdentity = artworkIdentity,
                        songActions = songActions,
                        resourceFresh = state is LibraryLoadState.Ready,
                    )
                }
            }
        }
    }
}
