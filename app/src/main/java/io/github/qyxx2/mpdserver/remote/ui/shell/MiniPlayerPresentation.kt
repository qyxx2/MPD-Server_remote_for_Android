package io.github.qyxx2.mpdserver.remote.ui.shell

import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotFreshness
import io.github.qyxx2.mpdserver.remote.runtime.SnapshotStoreState
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Read-only projection of the canonical app-scoped snapshot. It never invents song metadata. */
data class MiniPlayerPresentation(
    val title: String,
    val hasTrack: Boolean,
    val artworkUrl: String?,
    val statusLabel: String?,
    val isPlaying: Boolean,
    /** Playback is intentionally unavailable throughout Task 3. */
    val controlEnabled: Boolean = false,
) {
    companion object {
        private const val NO_TRACK = "暂无播放曲目"
        private const val UNCONFIRMED = "状态未确认"

        fun from(state: SnapshotStoreState): MiniPlayerPresentation {
            val currentSong = state.snapshot?.wire?.currentSong
            val songObject = currentSong?.let { runCatching { it.jsonObject }.getOrNull() }
            val title = songObject?.get("title")
                ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
            val confirmed = state.freshness == SnapshotFreshness.FRESH &&
                state.connectionStatus == RuntimeConnectionStatus.CONNECTED
            val observation = state.snapshot?.wire?.playbackObservation
                ?.let { runCatching { it.jsonObject }.getOrNull() }
            val actualState = observation?.get("actual_state")
                ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            val isPlaying = state.actualPlaybackConfirmed && actualState == "playing"

            return MiniPlayerPresentation(
                title = title ?: NO_TRACK,
                hasTrack = title != null,
                // The current-song DTO has no contracted artwork field; render the native placeholder.
                artworkUrl = null,
                statusLabel = if (confirmed) null else UNCONFIRMED,
                isPlaying = isPlaying,
                controlEnabled = false,
            )
        }
    }
}
