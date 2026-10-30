package io.github.qyxx2.mpdserver.remote.ui.shell

import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import io.github.qyxx2.mpdserver.remote.ui.player.ConfirmedPlaybackStatus
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerPresentation
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Read-only projection of the canonical app-scoped snapshot. It never invents song metadata. */
data class MiniPlayerPresentation(
    val title: String,
    val hasTrack: Boolean,
    val playbackStatus: ConfirmedPlaybackStatus,
    val statusLabel: String?,
) {
    companion object {
        private const val NO_TRACK = "暂无播放曲目"
        private const val UNCONFIRMED = "状态未确认"

        fun from(
            state: SnapshotStoreState,
            playerPresentation: PlayerPresentation = PlayerPresentation.from(state),
        ): MiniPlayerPresentation {
            val currentSong = state.snapshot?.wire?.currentSong
            val songObject = currentSong?.let { runCatching { it.jsonObject }.getOrNull() }
            val title = songObject?.get("title")
                ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
            return MiniPlayerPresentation(
                title = title ?: NO_TRACK,
                hasTrack = songObject != null,
                playbackStatus = playerPresentation.playbackStatus,
                statusLabel = if (playerPresentation.playbackStatus == ConfirmedPlaybackStatus.UNKNOWN) UNCONFIRMED else null,
            )
        }
    }
}
