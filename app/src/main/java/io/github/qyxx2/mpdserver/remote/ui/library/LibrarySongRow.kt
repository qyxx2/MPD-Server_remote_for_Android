package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.qyxx2.mpdserver.remote.protocol.dto.AvailabilityStatus
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.ui.queue.QueueResourceIdentity

/** Read-only row used by Songs, category members, playlists, favorites and Search. */
@Composable
fun LibrarySongRow(
    item: LibrarySongRowModel,
    modifier: Modifier = Modifier,
    showArtwork: Boolean = false,
    runtime: AppRuntime? = null,
    artworkIdentity: QueueResourceIdentity? = null,
    songActions: SongActions? = null,
    resourceFresh: Boolean = true,
) {
    val rowContent: @Composable () -> Unit = {
        LibrarySongRowContent(
            item = item,
            showArtwork = showArtwork,
            runtime = runtime,
            artworkIdentity = artworkIdentity,
            modifier = if (songActions == null) modifier else Modifier,
        )
    }
    if (songActions != null) {
        SongActionMenu(
            item = item,
            actions = songActions,
            resourceFresh = resourceFresh,
            modifier = modifier.fillMaxWidth(),
            content = rowContent,
        )
    } else {
        rowContent()
    }
}

@Composable
private fun LibrarySongRowContent(
    item: LibrarySongRowModel,
    showArtwork: Boolean,
    runtime: AppRuntime?,
    artworkIdentity: QueueResourceIdentity?,
    modifier: Modifier,
) {
    val song = item.song
    val title = song?.title?.takeIf { it.isNotBlank() }
        ?: if (item.songId == null) "未索引的音乐文件" else "歌曲已不在音乐库"
    val subtitle = when {
        song == null -> item.songId?.let { "资源 ID：$it" } ?: "文件：${item.key.removePrefix("file:")}"
        song.artists.isNotEmpty() && !song.album.isNullOrBlank() ->
            "${song.artists.joinToString("、")} · ${song.album}"
        song.artists.isNotEmpty() -> song.artists.joinToString("、")
        !song.album.isNullOrBlank() -> song.album
        else -> song.fileUri
    }
    val availability = when {
        song == null -> "资源信息缺失"
        song.availabilityStatus == AvailabilityStatus.MISSING -> "文件缺失"
        song.availabilityStatus == AvailabilityStatus.UNREADABLE -> "无法读取"
        song.songId == null -> "未索引"
        else -> null
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag("library-song-row-${item.key.hashCode()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showArtwork) {
            LibrarySongArtwork(
                song = song,
                runtime = runtime,
                artworkIdentity = artworkIdentity,
                size = 48.dp,
                modifier = Modifier.testTag("library-row-artwork-${item.key.hashCode()}"),
                testTag = "library-row-artwork-${item.key.hashCode()}",
            )
        } else {
            Text(
                text = "♫",
                modifier = Modifier.size(28.dp),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 22.sp,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        availability?.let {
            Text(
                text = it,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
