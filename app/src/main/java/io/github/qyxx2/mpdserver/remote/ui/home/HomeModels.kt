package io.github.qyxx2.mpdserver.remote.ui.home

import io.github.qyxx2.mpdserver.remote.protocol.dto.ArtworkDto
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto

/** Independent resource lifecycle for each Home rail. */
sealed interface HomeSectionState<out T> {
    data object Loading : HomeSectionState<Nothing>
    data object Empty : HomeSectionState<Nothing>
    data class Ready<T>(val value: T) : HomeSectionState<T>
    data class Stale<T>(val value: T) : HomeSectionState<T>
    data class Failed(val message: String) : HomeSectionState<Nothing>
}

enum class HomeCardKind {
    ALBUM,
    SINGLE_SONG,
    HISTORY_ORPHAN,
    AUTOPLAY_OCCURRENCE,
}

/**
 * One display card. [song] is the display representative (newest item for grouped History);
 * [artworkSongId] identifies the exact song that owns [artwork], never a neighboring fallback.
 */
data class HomeArtworkCard(
    val key: String,
    val kind: HomeCardKind,
    val song: SongDto?,
    val songId: String?,
    val albumId: String?,
    val artworkSongId: String?,
    val artwork: ArtworkDto?,
    val isPlayable: Boolean,
    val historyId: Long? = null,
    val queueItemId: String? = null,
)

data class HomeRecentUpdates(val cards: List<HomeArtworkCard>)

data class HomeAutoplayIdentity(
    val epoch: String,
    val connectionGeneration: Long,
    val queueRevision: Long,
    val playbackContextId: String,
    val queueFingerprint: String,
)

data class HomePlaybackHistory(
    val cards: List<HomeArtworkCard>,
    /** All unique persistent History song IDs in original server event order. */
    val songIds: List<String>,
)

data class HomeAutoplay(val cards: List<HomeArtworkCard>, val identity: HomeAutoplayIdentity? = null)

data class HomeUiState(
    val recentlyUpdated: HomeSectionState<HomeRecentUpdates> = HomeSectionState.Loading,
    val playbackHistory: HomeSectionState<HomePlaybackHistory> = HomeSectionState.Loading,
    val autoplay: HomeSectionState<HomeAutoplay> = HomeSectionState.Loading,
)
