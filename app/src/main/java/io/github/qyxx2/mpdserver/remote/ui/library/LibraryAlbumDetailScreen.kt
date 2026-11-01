package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.AlbumSummaryDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongListDto
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.ResourceCacheIdentityState
import io.github.qyxx2.mpdserver.remote.ui.home.HomeArtworkCacheKey
import io.github.qyxx2.mpdserver.remote.ui.home.HomeArtworkMemoryCache
import io.github.qyxx2.mpdserver.remote.ui.queue.QueueResourceIdentity

/** One shared Album detail route/content for Home, Search, Library and Player entry points. */
@Composable
internal fun LibraryAlbumDetailScreen(
    runtime: AppRuntime,
    sourceId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    artworkIdentity: QueueResourceIdentity? = null,
    songActions: SongActions? = null,
    collectionActions: LibraryCollectionActions? = null,
) {
    var summaryRetryToken by remember { mutableIntStateOf(0) }
    var songsRetryToken by remember(sourceId) { mutableIntStateOf(0) }
    val summaryState = rememberLibraryLoadState(
        runtime = runtime,
        resourceKey = "library-category:albums",
        retryToken = summaryRetryToken,
    ) {
        runtime.reads.getAlbums()
    }
    val songsState = rememberLibraryLoadState(
        runtime = runtime,
        resourceKey = "library-detail:albums:$sourceId",
        retryToken = songsRetryToken,
    ) {
        runtime.reads.getAlbumSongs(sourceId)
    }
    val summaryList = when (summaryState) {
        is LibraryLoadState.Ready -> summaryState.value
        is LibraryLoadState.Stale -> summaryState.value
        else -> null
    }
    val summary = remember(summaryList, sourceId) {
        summaryList?.items?.firstOrNull { it.albumId == sourceId }
    }

    LibraryAlbumDetailContent(
        sourceId = sourceId,
        summary = summary,
        songsState = songsState,
        runtime = runtime,
        artworkIdentity = artworkIdentity,
        onBack = onBack,
        onRetry = {
            songsRetryToken += 1
            if (summaryState is LibraryLoadState.Failed) summaryRetryToken += 1
        },
        songActions = songActions,
        collectionActions = collectionActions,
        modifier = modifier,
    )
}

/** Presentation seam mirrors Web CollectionView.vue and is exercised with server DTO fixtures. */
@Composable
internal fun LibraryAlbumDetailContent(
    sourceId: String,
    summary: AlbumSummaryDto?,
    songsState: LibraryLoadState<SongListDto>,
    runtime: AppRuntime? = null,
    artworkIdentity: QueueResourceIdentity? = null,
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
    songActions: SongActions? = null,
    collectionActions: LibraryCollectionActions? = null,
    modifier: Modifier = Modifier,
) {
    val songs = when (songsState) {
        is LibraryLoadState.Ready -> songsState.value.items
        is LibraryLoadState.Stale -> songsState.value.items
        else -> emptyList()
    }
    val firstSong = songs.firstOrNull()
    val title = summary?.title?.takeIf(String::isNotBlank)
        ?: firstSong?.album?.takeIf(String::isNotBlank)
        ?: sourceId
    val artists = summary?.albumArtists?.takeIf { it.isNotEmpty() }
        ?: firstSong?.albumArtists?.takeIf { it.isNotEmpty() }
        ?: firstSong?.artists.orEmpty()
    val year = summary?.year
        ?: summary?.date?.take(4)?.toIntOrNull()
        ?: firstSong?.year
        ?: firstSong?.date?.take(4)?.toIntOrNull()
    val albumSongCount = summary?.songCount ?: when (songsState) {
        is LibraryLoadState.Ready -> songsState.value.count
        is LibraryLoadState.Stale -> songsState.value.count
        else -> null
    }
    val coverSong = remember(songs, summary?.artwork?.artworkId) {
        songs.firstOrNull { song ->
            !song.songId.isNullOrBlank() && song.artwork != null &&
                (summary?.artwork == null || song.artwork.artworkId == summary.artwork.artworkId)
        }
    }
    val isFresh = songsState is LibraryLoadState.Ready
    val collectionRequest = remember(sourceId) {
        CollectionPlaybackRequest.forCategory(LibraryCategory.ALBUMS, sourceId)
    }

    LibraryPage(
        modifier = modifier,
        tag = "library-album-detail-screen",
        onBack = onBack,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryAlbumArtwork(
                song = coverSong,
                runtime = runtime,
                artworkIdentity = artworkIdentity,
                modifier = Modifier.testTag("library-album-cover"),
            )
            Column(
                modifier = Modifier.weight(1f).heightIn(max = 104.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    modifier = Modifier.testTag("library-album-title"),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.6.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artists.joinToString(" / ").ifBlank { "未知艺术家" },
                    modifier = Modifier.testTag("library-album-artists"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metadata = buildString {
                    append(year?.toString() ?: "年代未知")
                    albumSongCount?.let { append(" · $it 首") }
                }
                Text(
                    text = metadata,
                    modifier = Modifier.testTag("library-album-year-count"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        CollectionPlaybackControls(
            enabled = isFresh && songs.isNotEmpty() && collectionActions?.canPlay() == true,
            onPlayAll = {
                if (collectionRequest != null) {
                    collectionActions?.play(collectionRequest, resourceFresh = isFresh)
                }
            },
            onShuffle = {
                if (collectionRequest != null) {
                    collectionActions?.play(
                        collectionRequest.withRandomize(true),
                        resourceFresh = isFresh,
                    )
                }
            },
            modifier = Modifier.padding(horizontal = 12.dp),
            tag = "library-album-collection-controls",
            playAllLabel = "播放全部",
        )

        when (songsState) {
            is LibraryLoadState.Ready, is LibraryLoadState.Stale -> {
                Text(
                    text = "可播放歌曲 · ${songs.size} 首",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                        .testTag("library-album-song-count"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.8.sp,
                )
                if (songsState is LibraryLoadState.Stale) {
                    Text(
                        text = "离线缓存 · 数据可能不是最新",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.tertiary,
                        fontSize = 12.sp,
                    )
                }
                if (songs.isEmpty()) {
                    LibraryMessage("暂无歌曲")
                } else {
                    val rows = remember(songs) { LibraryProjection.songRows(songs) }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("library-album-song-list"),
                    ) {
                        items(rows, key = { it.key }) { item ->
                            LibraryAlbumSongRow(
                                item = item,
                                songActions = songActions,
                                resourceFresh = isFresh,
                            )
                        }
                    }
                }
            }
            LibraryLoadState.Loading -> LibraryMessage("正在加载")
            LibraryLoadState.Empty -> LibraryMessage("暂无歌曲")
            is LibraryLoadState.Failed -> Column {
                LibraryMessage(songsState.message)
                androidx.compose.material3.TextButton(
                    onClick = onRetry,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) { Text("重试") }
            }
        }
    }
}

@Composable
private fun LibraryAlbumArtwork(
    song: SongDto?,
    runtime: AppRuntime?,
    artworkIdentity: QueueResourceIdentity?,
    modifier: Modifier = Modifier,
) {
    val artworkId = song?.artwork?.artworkId?.takeIf(String::isNotBlank)
    val songId = song?.songId?.takeIf(String::isNotBlank)
    val key = remember(artworkIdentity, artworkId) {
        if (artworkIdentity != null && artworkId != null) {
            HomeArtworkCacheKey(artworkIdentity, artworkId)
        } else null
    }
    val artworkState = remember(key) { key?.let(HomeArtworkMemoryCache::stateFor) }
    val bitmap = artworkState?.collectAsStateWithLifecycle()?.value
    val visibilityOwner = remember { Any() }
    SideEffect {
        HomeArtworkMemoryCache.updateVisibleKeys(visibilityOwner, key?.let(::setOf).orEmpty())
    }
    DisposableEffect(visibilityOwner) {
        onDispose { HomeArtworkMemoryCache.updateVisibleKeys(visibilityOwner, emptySet()) }
    }

    if (runtime != null && songId != null && key != null) {
        val identityState by runtime.resources.identityState.collectAsStateWithLifecycle()
        val identityMatches = key.identity.matches(identityState)
        LaunchedEffect(key, songId, identityState) {
            if (bitmap != null || !identityMatches) return@LaunchedEffect
            when (val response = runtime.reads.getArtwork(songId)) {
                is HttpResult.Body -> {
                    val contentType = response.contentType.orEmpty().substringBefore(';').trim()
                    val latest = runtime.resources.identityState.value
                    if (contentType.startsWith("image/", ignoreCase = true) && key.identity.matches(latest)) {
                        HomeArtworkMemoryCache.preload(key, response.bytes)
                    }
                }
                else -> Unit
            }
        }
    }

    Box(
        modifier = modifier
            .size(104.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "专辑封面",
                modifier = Modifier.fillMaxSize().testTag("library-album-cover-image"),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = "♫",
                modifier = Modifier.testTag("library-album-cover-placeholder"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.headlineMedium,
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
private fun LibraryAlbumSongRow(
    item: LibrarySongRowModel,
    songActions: SongActions?,
    resourceFresh: Boolean,
) {
    val song = item.song
    val title = song?.title?.takeIf(String::isNotBlank)
        ?: if (item.songId == null) "未索引的音乐文件" else "歌曲已不在音乐库"
    val artist = song?.artists?.joinToString(" / ")?.ifBlank { "—" } ?: "—"
    val availability = when {
        song == null -> "资源信息缺失"
        song.availabilityStatus == io.github.qyxx2.mpdserver.remote.protocol.dto.AvailabilityStatus.MISSING -> "文件缺失"
        song.availabilityStatus == io.github.qyxx2.mpdserver.remote.protocol.dto.AvailabilityStatus.UNREADABLE -> "无法读取"
        song.songId == null -> "未索引"
        else -> null
    }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("library-album-song-row-${item.key.hashCode()}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artist,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            availability?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }
        }
    }
    if (songActions != null) {
        SongActionMenu(
            item = item,
            actions = songActions,
            resourceFresh = resourceFresh,
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    } else {
        content()
    }
}
