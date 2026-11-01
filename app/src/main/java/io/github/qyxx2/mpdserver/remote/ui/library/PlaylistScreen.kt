package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.PlaylistDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.MutationStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private data class PlaylistDetailReadModel(
    val playlist: PlaylistDto,
    val songs: List<LibrarySongRowModel>,
    val membersConsistent: Boolean,
)

@Composable
fun PlaylistScreen(
    runtime: AppRuntime,
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    playlistId: String? = null,
    songActions: SongActions? = null,
    collectionActions: LibraryCollectionActions? = null,
    playlistActions: PlaylistActions? = null,
) {
    var retryToken by remember(playlistId) { mutableIntStateOf(0) }
    val lastTicketState = playlistActions?.lastAction?.collectAsStateWithLifecycle(initialValue = null)
    val lastTicket = lastTicketState?.value
    val activities = playlistActions?.activities()
        ?.collectAsStateWithLifecycle(initialValue = emptyList())
        ?.value.orEmpty()

    if (playlistId == null) {
        val refreshGeneration = playlistActions?.refreshGeneration
            ?.collectAsStateWithLifecycle(initialValue = 0L)?.value ?: 0L
        val listState = rememberLibraryLoadState(
            runtime,
            "library-playlists",
            retryToken + refreshGeneration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            dependsOnPlaylistRevision = true,
        ) { runtime.reads.getPlaylists() }
        var creating by remember { mutableStateOf(false) }
        var createName by remember { mutableStateOf("") }
        val unknownTicket = lastTicket?.takeIf { ticket ->
            activities.firstOrNull { it.intentId == ticket.intentId }?.status == MutationStatus.UNKNOWN
        }

        LibraryPage(modifier, "library-playlists-screen", onBack) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("播放列表", modifier = Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                TextButton(
                    enabled = playlistActions?.canWrite(listState is LibraryLoadState.Ready) == true &&
                        listState is LibraryLoadState.Ready,
                    onClick = { createName = ""; creating = true },
                    modifier = Modifier.testTag("playlist-create"),
                ) { Text("创建歌单") }
            }
            if (unknownTicket != null) {
                TextButton(
                    onClick = { playlistActions?.retryUnknown(unknownTicket) },
                    modifier = Modifier.testTag("playlist-retry-unknown"),
                ) { Text("上次操作结果未知 · 使用原操作重试") }
            }
            LibraryResourceContent(
                state = listState,
                emptyText = "暂无播放列表",
                onRetry = { retryToken++ },
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

        if (creating) {
            AlertDialog(
                onDismissRequest = { creating = false },
                title = { Text("创建歌单") },
                text = {
                    TextField(
                        value = createName,
                        onValueChange = { createName = it },
                        singleLine = true,
                        label = { Text("歌单名称") },
                        modifier = Modifier.testTag("playlist-create-name"),
                    )
                },
                confirmButton = {
                    Button(
                        enabled = createName.isNotBlank() &&
                            playlistActions?.canWrite(listState is LibraryLoadState.Ready) == true &&
                            listState is LibraryLoadState.Ready,
                        onClick = {
                            playlistActions?.create(createName, resourceFresh = listState is LibraryLoadState.Ready)
                            creating = false
                        },
                    ) { Text("创建") }
                },
                dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
            )
        }
        return
    }

    val refreshGeneration = playlistActions?.refreshGeneration
        ?.collectAsStateWithLifecycle(initialValue = 0L)?.value ?: 0L
    val detailState = rememberLibraryLoadState(
        runtime,
        "library-playlist-detail:$playlistId",
        retryToken + refreshGeneration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        dependsOnPlaylistRevision = true,
    ) { readPlaylistDetail(runtime, playlistId) }
    var addingSongs by remember(playlistId) { mutableStateOf(false) }
    val pickerState = if (addingSongs) rememberLibraryLoadState(
        runtime,
        "library-playlist-song-picker",
        retryToken,
    ) { runtime.reads.getSongs() } else LibraryLoadState.Loading

    val detail = (detailState as? LibraryLoadState.Ready)?.value
        ?: (detailState as? LibraryLoadState.Stale)?.value
    var editingName by remember(playlistId) { mutableStateOf(false) }
    var renameValue by remember(playlistId, detail?.playlist?.name) { mutableStateOf(detail?.playlist?.name.orEmpty()) }
    var deleteConfirmation by remember(playlistId) { mutableStateOf(false) }
    var reorderDraft by remember(playlistId, detail?.playlist?.songIds) { mutableStateOf<List<String>?>(null) }
    val baseline = detail?.let { playlistActions?.captureBaseline(it.playlist) }
    val resourceFresh = detailState is LibraryLoadState.Ready
    val canEdit = playlistActions?.canEdit(
        baseline = baseline,
        resourceFresh = resourceFresh,
        membersConsistent = detail?.membersConsistent == true,
    ) == true
    val unknownTicket = lastTicket?.takeIf { ticket ->
        activities.firstOrNull { it.intentId == ticket.intentId }?.status == MutationStatus.UNKNOWN
    }
    val readbackTicket = playlistActions?.lastReadback
        ?.collectAsStateWithLifecycle(initialValue = null)?.value
    LaunchedEffect(readbackTicket?.intentId, playlistId) {
        if (readbackTicket?.deleted == true && readbackTicket.playlistId == playlistId) onBack()
    }

    fun moveByKey(songId: String, direction: Int) {
        val current = detail ?: return
        val captured = baseline ?: return
        val ids = (reorderDraft ?: current.playlist.songIds).toMutableList()
        val index = ids.indexOf(songId)
        val destination = index + direction
        if (index < 0 || destination !in ids.indices || !canEdit) return
        val moved = ids.removeAt(index)
        ids.add(destination, moved)
        reorderDraft = ids
        playlistActions?.reorder(
            captured,
            ids,
            resourceFresh = resourceFresh,
            membersConsistent = current.membersConsistent,
        )
        reorderDraft = null
    }

    LibraryPage(modifier, "library-playlist-detail-screen", onBack) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("播放列表", modifier = Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (detail != null && !detail.playlist.isSystem) {
                TextButton(
                    enabled = canEdit,
                    onClick = { renameValue = detail.playlist.name; editingName = true },
                    modifier = Modifier.testTag("playlist-rename"),
                ) { Text("重命名") }
                TextButton(
                    enabled = canEdit,
                    onClick = { deleteConfirmation = true },
                    modifier = Modifier.testTag("playlist-delete"),
                ) { Text("删除") }
            }
        }
        if (unknownTicket != null) {
            TextButton(
                onClick = { playlistActions?.retryUnknown(unknownTicket) },
                modifier = Modifier.testTag("playlist-retry-unknown"),
            ) { Text("上次操作结果未知 · 使用原操作重试") }
        }
        LibraryResourceContent(
            state = detailState,
            emptyText = "此歌单暂无歌曲",
            onRetry = { retryToken++ },
            isEmpty = { it.songs.isEmpty() },
        ) { current ->
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = current.playlist.name + if (current.playlist.isSystem) " · 系统歌单" else "",
                            modifier = Modifier.testTag("library-playlist-name"),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!current.membersConsistent) {
                            Text(
                                "歌单列表、详情与成员数据不一致，编辑已停用",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    TextButton(
                        enabled = canEdit,
                        onClick = { addingSongs = true },
                        modifier = Modifier.testTag("playlist-add-songs"),
                    ) { Text("添加歌曲") }
                }
                if (collectionActions != null) {
                    val request = CollectionPlaybackRequest.forPlaylist(current.playlist.playlistId)
                    if (request != null) {
                        CollectionPlaybackControls(
                            enabled = detailState is LibraryLoadState.Ready && collectionActions.canPlay(),
                            onPlayAll = { collectionActions.play(request, resourceFresh = detailState is LibraryLoadState.Ready) },
                            onShuffle = {
                                collectionActions.play(
                                    request.withRandomize(true),
                                    resourceFresh = detailState is LibraryLoadState.Ready,
                                )
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                            tag = "playlist-collection-controls",
                        )
                    }
                }
                val displayIds = reorderDraft ?: current.playlist.songIds
                val songsById = current.songs.associateBy { it.songId }
                LazyColumn(modifier = Modifier.fillMaxSize().testTag("library-playlist-songs")) {
                    items(displayIds, key = { it }) { songId ->
                        val row = songsById[songId] ?: LibrarySongRowModel(
                            key = "song:$songId",
                            songId = songId,
                            song = null,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "≡",
                                modifier = Modifier
                                    .size(42.dp)
                                    .pointerInput(canEdit, songId, baseline) {
                                        if (canEdit && baseline != null) {
                                            var distance = 0f
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { reorderDraft = current.playlist.songIds.toList(); distance = 0f },
                                                onDragCancel = { reorderDraft = null },
                                                onDragEnd = {
                                                    val ordered = reorderDraft
                                                    reorderDraft = null
                                                    if (ordered != null && ordered != baseline.songIds) {
                                                        playlistActions?.reorder(
                                                            baseline, ordered, resourceFresh = resourceFresh,
                                                            membersConsistent = current.membersConsistent,
                                                        )
                                                    }
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    distance += amount.y
                                                    val threshold = 42.dp.toPx()
                                                    val ids = (reorderDraft ?: current.playlist.songIds).toMutableList()
                                                    var index = ids.indexOf(songId)
                                                    while (distance <= -threshold && index > 0) {
                                                        val previous = ids[index - 1]
                                                        ids[index - 1] = ids[index]
                                                        ids[index] = previous
                                                        index--
                                                        distance += threshold
                                                        reorderDraft = ids.toList()
                                                    }
                                                    while (distance >= threshold && index < ids.lastIndex) {
                                                        val next = ids[index + 1]
                                                        ids[index + 1] = ids[index]
                                                        ids[index] = next
                                                        index++
                                                        distance -= threshold
                                                        reorderDraft = ids.toList()
                                                    }
                                                },
                                            )
                                        }
                                    }
                                    .onKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) false
                                        else when (event.key) {
                                            Key.DirectionUp -> { moveByKey(songId, -1); true }
                                            Key.DirectionDown -> { moveByKey(songId, 1); true }
                                            else -> false
                                        }
                                    }
                                    .focusable()
                                    .testTag("playlist-reorder-$songId"),
                                color = if (canEdit) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.outlineVariant,
                                fontSize = 22.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                LibrarySongRow(
                                    row,
                                    songActions = songActions,
                                    resourceFresh = detailState is LibraryLoadState.Ready,
                                )
                            }
                            TextButton(
                                enabled = canEdit,
                                onClick = {
                                    val captured = baseline ?: return@TextButton
                                    playlistActions?.remove(
                                        captured, songId,
                                        resourceFresh = resourceFresh,
                                        membersConsistent = current.membersConsistent,
                                    )
                                },
                                modifier = Modifier.testTag("playlist-remove-$songId"),
                            ) { Text("移除") }
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

    if (editingName && detail != null) {
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("重命名歌单") },
            text = {
                TextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text("歌单名称") },
                    modifier = Modifier.testTag("playlist-rename-name"),
                )
            },
            confirmButton = {
                Button(
                    enabled = canEdit && renameValue.isNotBlank(),
                    onClick = {
                        baseline?.let {
                            playlistActions?.rename(it, renameValue, resourceFresh, detail.membersConsistent)
                        }
                        editingName = false
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text("取消") } },
        )
    }

    if (deleteConfirmation && detail != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirmation = false },
            title = { Text("删除歌单") },
            text = { Text("确定删除「${detail.playlist.name}」？") },
            confirmButton = {
                Button(
                    enabled = canEdit,
                    onClick = {
                        baseline?.let { playlistActions?.delete(it, resourceFresh, detail.membersConsistent) }
                        deleteConfirmation = false
                    },
                    modifier = Modifier.testTag("playlist-confirm-delete"),
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirmation = false }) { Text("取消") } },
        )
    }

    if (addingSongs && detail != null) {
        AlertDialog(
            onDismissRequest = { addingSongs = false },
            title = { Text("添加歌曲") },
            text = {
                Column {
                    when (val picker = pickerState) {
                        LibraryLoadState.Loading -> Text("正在加载歌曲")
                        LibraryLoadState.Empty -> Text("暂无可添加歌曲")
                        is LibraryLoadState.Failed -> Text(picker.message)
                        is LibraryLoadState.Stale -> Text("音乐库数据已过期，暂不可添加")
                        is LibraryLoadState.Ready -> LazyColumn(modifier = Modifier.size(300.dp, 380.dp)) {
                            items(picker.value.items, key = { it.songId ?: it.fileUri }) { song ->
                                val songId = song.songId
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(song.title, maxLines = 1)
                                        Text(
                                            song.availabilityStatus.name,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp,
                                        )
                                    }
                                    TextButton(
                                        enabled = canEdit && songId != null,
                                        onClick = {
                                            val captured = baseline ?: return@TextButton
                                            if (songId != null) {
                                                playlistActions?.add(
                                                    captured, songId, resourceFresh, detail.membersConsistent,
                                                )
                                            }
                                        },
                                        modifier = Modifier.testTag("playlist-add-song-${songId ?: "unindexed"}"),
                                    ) { Text("添加") }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addingSongs = false }) { Text("完成") } },
        )
    }
}

private suspend fun readPlaylistDetail(
    runtime: AppRuntime,
    playlistId: String,
): ApiResult<PlaylistDetailReadModel> = coroutineScope {
    val listRead = async { runtime.reads.getPlaylists() }
    val playlistRead = async { runtime.reads.getPlaylist(playlistId) }
    val songsRead = async { runtime.reads.getPlaylistSongs(playlistId) }
    val listResult = listRead.await()
    if (listResult !is ApiResult.Success) return@coroutineScope listResult.asFailure()
    val playlistResult = playlistRead.await()
    if (playlistResult !is ApiResult.Success) return@coroutineScope playlistResult.asFailure()
    val songsResult = songsRead.await()
    if (songsResult !is ApiResult.Success) return@coroutineScope songsResult.asFailure()

    val playlist = playlistResult.value
    if (playlist.playlistId != playlistId) {
        return@coroutineScope ApiResult.ProtocolError("Playlist response ID did not match the requested playlist")
    }
    val listed = listResult.value.items.firstOrNull { it.playlistId == playlistId }
    val listConsistent = listed != null &&
        listed.name == playlist.name &&
        listed.isSystem == playlist.isSystem &&
        listed.songIds == playlist.songIds
    val memberIds = songsResult.value.items.mapNotNull { it.songId }
    val membersConsistent = listConsistent &&
        songsResult.value.items.all { it.songId != null } &&
        playlist.songIds == memberIds
    val returnedSongs = songsResult.value.items.mapNotNull { song ->
        song.songId?.let { it to song }
    }.toMap()
    val models = LibraryProjection.songIds(playlist.songIds, returnedSongs)
    ApiResult.Success(PlaylistDetailReadModel(playlist, models, membersConsistent), 200)
}
