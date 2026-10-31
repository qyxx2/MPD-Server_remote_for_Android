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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@Composable
fun FavoritesScreen(
    runtime: AppRuntime,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    songActions: SongActions? = null,
    collectionActions: LibraryCollectionActions? = null,
) {
    var retryToken by remember { mutableIntStateOf(0) }
    val state = rememberLibraryLoadState(
        runtime,
        "library-favorites-members",
        retryToken,
        dependsOnPlaylistRevision = true,
    ) {
        readFavoriteSongs(runtime)
    }
    LibraryPage(modifier, "library-favorites-screen", onBack) {
        LibraryPageHeader("收藏")
        if (collectionActions != null) {
            val request = CollectionPlaybackRequest.forFavorites()
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
                tag = "favorites-collection-controls",
            )
        }
        LibraryResourceContent(
            state = state,
            emptyText = "暂无收藏歌曲",
            onRetry = { retryToken += 1 },
            isEmpty = { it.isEmpty() },
        ) { rows ->
            LazyColumn(modifier = Modifier.fillMaxSize().testTag("library-favorites-songs")) {
                items(rows, key = { it.key }) {
                    LibrarySongRow(
                        it,
                        songActions = songActions,
                        resourceFresh = state is LibraryLoadState.Ready,
                    )
                }
            }
        }
    }
}

/** Resolve IDs through RuntimeReadApi.getSong so Favorites and every other surface share Song entities. */
private suspend fun readFavoriteSongs(runtime: AppRuntime): ApiResult<List<LibrarySongRowModel>> {
    val idsResult = runtime.reads.getFavoriteMembers()
    if (idsResult !is ApiResult.Success) return idsResult.asFailure()
    val ids = idsResult.value.songIds
    val resolved = linkedMapOf<String, SongDto>()
    for (batch in ids.distinct().chunked(3)) {
        val results = coroutineScope {
            batch.map { id -> async { id to runtime.reads.getSong(id) } }.map { it.await() }
        }
        results.forEach { (id, result) ->
            if (result is ApiResult.Success && result.value.songId == id) resolved[id] = result.value
        }
    }
    return ApiResult.Success(LibraryProjection.songIds(ids, resolved), 200)
}
