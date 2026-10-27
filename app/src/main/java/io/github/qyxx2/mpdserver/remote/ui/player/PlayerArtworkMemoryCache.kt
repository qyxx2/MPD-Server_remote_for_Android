package io.github.qyxx2.mpdserver.remote.ui.player

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Resource identity only; this cache never becomes a source of playback or current-song truth. */
data class PlayerArtworkCacheKey(
    val songId: String,
    val fileUri: String,
    val epoch: String,
    val libraryRevision: Long,
    val connectionGeneration: Long,
) {
    fun matches(presentation: PlayerPresentation): Boolean {
        val song = presentation.song ?: return false
        return presentation.playbackStatus != ConfirmedPlaybackStatus.UNKNOWN &&
            presentation.songStatus == SongMetadataStatus.AVAILABLE &&
            presentation.artworkReferenceStatus == ArtworkReferenceStatus.REFERENCED &&
            song.artworkReferenceStatus == ArtworkReferenceStatus.REFERENCED &&
            song.songId == songId && song.fileUri == fileUri
    }

    companion object {
        fun from(
            presentation: PlayerPresentation,
            connectionGeneration: Long,
            epoch: String = "",
            libraryRevision: Long = 0L,
        ): PlayerArtworkCacheKey? {
            val song = presentation.song ?: return null
            if (presentation.playbackStatus == ConfirmedPlaybackStatus.UNKNOWN ||
                presentation.songStatus != SongMetadataStatus.AVAILABLE ||
                presentation.artworkReferenceStatus != ArtworkReferenceStatus.REFERENCED ||
                song.artworkReferenceStatus != ArtworkReferenceStatus.REFERENCED ||
                song.songId.isBlank() || song.fileUri.isBlank()
            ) return null

            return PlayerArtworkCacheKey(
                songId = song.songId,
                fileUri = song.fileUri,
                epoch = epoch,
                libraryRevision = libraryRevision,
                connectionGeneration = connectionGeneration,
            )
        }
    }
}

sealed interface PlayerArtworkCacheState {
    val key: PlayerArtworkCacheKey?

    data object Empty : PlayerArtworkCacheState {
        override val key: PlayerArtworkCacheKey? = null
    }

    data class Loading(override val key: PlayerArtworkCacheKey) : PlayerArtworkCacheState

    data class Ready(
        override val key: PlayerArtworkCacheKey,
        val foreground: ImageBitmap,
        val blurred: ImageBitmap,
    ) : PlayerArtworkCacheState

    data class Failed(override val key: PlayerArtworkCacheKey) : PlayerArtworkCacheState
}

/**
 * One-entry, process-memory image cache shared by the app shell and full-screen player. The app
 * shell warms it as soon as a fresh, confirmed current song exposes an artwork reference, rather
 * than starting an image request after the full-screen route has already appeared.
 */
class PlayerArtworkMemoryCache internal constructor(
    private val scope: CoroutineScope,
    private val artworkLoader: suspend (AppRuntime, String) -> HttpResult = { runtime, songId ->
        runtime.reads.getArtwork(songId)
    },
) {
    private val lock = Any()
    private val _state = MutableStateFlow<PlayerArtworkCacheState>(PlayerArtworkCacheState.Empty)
    val state: StateFlow<PlayerArtworkCacheState> = _state.asStateFlow()

    private var activeRequestToken = 0L
    private var loadJob: Job? = null

    fun preload(runtime: AppRuntime, key: PlayerArtworkCacheKey) {
        synchronized(lock) {
            val current = _state.value
            // Attempt at most once for a confirmed resource identity. A failed read keeps the
            // existing placeholder instead of delaying a tap with another potentially slow request;
            // a song/revision/connection identity change naturally permits a fresh attempt.
            if (current.key == key && current !is PlayerArtworkCacheState.Empty) return

            activeRequestToken += 1
            val requestToken = activeRequestToken
            loadJob?.cancel()
            _state.value = PlayerArtworkCacheState.Loading(key)
            loadJob = scope.launch {
                try {
                    val response = artworkLoader(runtime, key.songId)
                    val decoded = (response as? HttpResult.Body)?.let { body ->
                        withContext(Dispatchers.Default) {
                            val foreground = decodeArtworkBitmap(body.bytes) ?: return@withContext null
                            val blurred = createBlurredArtworkBitmap(foreground) ?: return@withContext null
                            PlayerArtworkCacheState.Ready(
                                key = key,
                                foreground = foreground,
                                blurred = blurred,
                            )
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        if (activeRequestToken == requestToken &&
                            (_state.value as? PlayerArtworkCacheState.Loading)?.key == key
                        ) {
                            _state.value = decoded ?: PlayerArtworkCacheState.Failed(key)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    synchronized(lock) {
                        if (activeRequestToken == requestToken &&
                            (_state.value as? PlayerArtworkCacheState.Loading)?.key == key
                        ) {
                            _state.value = PlayerArtworkCacheState.Empty
                        }
                    }
                    throw cancelled
                } catch (_: Exception) {
                    synchronized(lock) {
                        if (activeRequestToken == requestToken &&
                            (_state.value as? PlayerArtworkCacheState.Loading)?.key == key
                        ) {
                            _state.value = PlayerArtworkCacheState.Failed(key)
                        }
                    }
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            activeRequestToken += 1
            loadJob?.cancel()
            loadJob = null
            _state.value = PlayerArtworkCacheState.Empty
        }
    }

    /** Waits for this identity to finish, or returns promptly if the canonical identity changed. */
    suspend fun awaitSettled(key: PlayerArtworkCacheKey): PlayerArtworkCacheState =
        state.first { current -> current.key != key || current !is PlayerArtworkCacheState.Loading }
}

private fun decodeArtworkBitmap(bytes: ByteArray): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    val maxDimension = max(bounds.outWidth, bounds.outHeight)
    var sampleSize = 1
    while (maxDimension / sampleSize > 1600) sampleSize *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()
