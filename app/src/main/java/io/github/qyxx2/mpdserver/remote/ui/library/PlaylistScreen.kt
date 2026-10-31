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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaylistDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private data class PlaylistDetailReadModel(
    val playlist: PlaylistDto,
    val songs: List<LibrarySongRowModel>,
)

@Composable
fun PlaylistScreen(
    runtime: AppRuntime,
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    playlistId: String? = null,
) {
    if (playlistId == null) {
        var retryToken by remember { mutableIntStateOf(0) }
        val state = rememberLibraryLoadState(
            runtime,
            "library-playlists",
            retryToken,
            dependsOnPlaylistRevision = true,
        ) {
            runtime.reads.getPlaylists()
        }
        LibraryPage(modifier, "library-playlists-screen") {
            LibraryPageHeader("播放列表", onBack)
            LibraryResourceContent(
                state = state,
                emptyText = "暂无播放列表",
                onRetry = { retryToken += 1 },
                isEmpty = { it.items.isEmpty() },
            ) { playlists ->
                LazyColumn(modifier = Modifier.fillMaxSize().testTag("library-playlist-list")) {
                    items(playlists.items, key = { it.playlistId }) { playlist ->
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { onOpenDetail(LibraryRoutes.detail(LibraryCategory.PLAYLISTS, playlist.playlistId)) }
                                    .padding(horizontal = 20.dp, vertical = 14.dp)
                                    .testTag("library-playlist-${playlist.playlistId}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(playlist.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        buildString {
                                            append("${playlist.songIds.size} 首歌曲")
                                            if (playlist.isSystem) append(" · 系统歌单")
                                        },
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }
    } else {
        var retryToken by remember(playlistId) { mutableIntStateOf(0) }
        val state = rememberLibraryLoadState(
            runtime,
            "library-playlist-detail:$playlistId",
            retryToken,
            dependsOnPlaylistRevision = true,
        ) {
            readPlaylistDetail(runtime, playlistId)
        }
        LibraryPage(modifier, "library-playlist-detail-screen") {
            LibraryPageHeader("播放列表", onBack)
            LibraryResourceContent(
                state = state,
                emptyText = "此歌单暂无歌曲",
                onRetry = { retryToken += 1 },
                isEmpty = { it.songs.isEmpty() },
            ) { detail ->
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = buildString {
                            append(detail.playlist.name)
                            if (detail.playlist.isSystem) append(" · 系统歌单")
                        },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                            .testTag("library-playlist-name"),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize().testTag("library-playlist-songs")) {
                        items(detail.songs, key = { it.key }) { LibrarySongRow(it) }
                    }
                }
            }
        }
    }
}

private suspend fun readPlaylistDetail(
    runtime: AppRuntime,
    playlistId: String,
): ApiResult<PlaylistDetailReadModel> = coroutineScope {
    val playlistRead = async { runtime.reads.getPlaylist(playlistId) }
    val songsRead = async { runtime.reads.getPlaylistSongs(playlistId) }
    val playlistResult = playlistRead.await()
    if (playlistResult !is ApiResult.Success) return@coroutineScope playlistResult.asFailure()
    val songsResult = songsRead.await()
    if (songsResult !is ApiResult.Success) return@coroutineScope songsResult.asFailure()

    val playlist = playlistResult.value
    if (playlist.playlistId != playlistId) {
        return@coroutineScope ApiResult.ProtocolError("Playlist response ID did not match the requested playlist")
    }
    val returnedSongs = songsResult.value.items.filter { it.songId != null }.associateBy { it.songId!! }
    val responseOrder = songsResult.value.items.mapNotNull { it.songId }
    val expectedReturnedOrder = playlist.songIds.filter { returnedSongs.containsKey(it) }
    if (responseOrder != expectedReturnedOrder) {
        return@coroutineScope ApiResult.ProtocolError("Playlist member order did not match the server playlist metadata")
    }
    val models = LibraryProjection.songIds(playlist.songIds, returnedSongs)
    ApiResult.Success(PlaylistDetailReadModel(playlist, models), 200)
}
