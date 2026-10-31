package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.network.HttpResult
import io.github.qyxx2.mpdserver.remote.runtime.isExplicitArtworkMissing
import io.github.qyxx2.mpdserver.remote.protocol.dto.SongDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Queue presentation identity mirrors the server session and Library revision. */
data class QueueResourceIdentity(
    val epoch: String,
    val libraryRevision: Long,
    val connectionGeneration: Long,
    val originAddress: String,
)

data class QueueSongResource(
    val song: SongDto,
    val artworkResponse: HttpResult? = null,
)

enum class QueueSongLoadStatus { LOADING, READY, FAILED }
enum class QueueArtworkStatus { NOT_REFERENCED, AVAILABLE, MISSING, RETRYABLE_FAILURE }

/** Lightweight Queue adapter state; canonical Song DTO and compressed artwork live in AppRuntime.resources. */
data class QueueSongCacheEntry(
    val identity: QueueResourceIdentity,
    val status: QueueSongLoadStatus,
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val codec: String? = null,
    val bitDepth: Int? = null,
    val sampleRateHz: Int? = null,
    val availabilityStatus: String? = null,
    val artworkId: String? = null,
    val artworkStatus: QueueArtworkStatus = QueueArtworkStatus.NOT_REFERENCED,
    val error: String? = null,
)

/**
 * Retains only Queue visibility/prefetch state. Metadata/artwork transport, single-flight, memory
 * budgets and raw resource lifetime are owned by the app-scoped ResourceMemoryCache.
 */
class QueueSongCache(
    private val scope: CoroutineScope,
    private val maxEntries: Int = 512,
    private val artworkPrefetcher: ((QueueResourceIdentity, String, ByteArray) -> Unit)? = null,
    private val artworkRehydrator: (suspend (QueueResourceIdentity, String, String) -> HttpResult?)? = null,
    private val artworkRetryIntervalMillis: Long = 5_000L,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val loader: suspend (QueueResourceIdentity, String) -> QueueSongResource,
) {
    init { require(maxEntries > 0 && artworkRetryIntervalMillis >= 0L) }

    private val lock = Any()
    private val _state = MutableStateFlow<Map<String, QueueSongCacheEntry>>(emptyMap())
    val state: StateFlow<Map<String, QueueSongCacheEntry>> = _state.asStateFlow()
    private var activeIdentity: QueueResourceIdentity? = null
    private var visibleSongIds: Set<String> = emptySet()
    private var sequence = 0L
    private val jobs = mutableMapOf<String, Job>()
    private val artworkJobs = mutableMapOf<String, Job>()
    private val requestTokens = mutableMapOf<String, Long>()
    private val lastAccess = mutableMapOf<String, Long>()
    private val lastArtworkAttempt = mutableMapOf<String, Long>()

    fun updateScope(identity: QueueResourceIdentity, visibleIds: Set<String>) = synchronized(lock) {
        val normalizedVisible = visibleIds.filter(String::isNotBlank).toSet()
        if (activeIdentity != identity) {
            jobs.values.forEach(Job::cancel)
            artworkJobs.values.forEach(Job::cancel)
            jobs.clear()
            artworkJobs.clear()
            requestTokens.clear()
            lastAccess.clear()
            lastArtworkAttempt.clear()
            _state.value = emptyMap()
            activeIdentity = identity
        }
        visibleSongIds = normalizedVisible
        normalizedVisible.forEach { songId ->
            if (_state.value.containsKey(songId)) lastAccess[songId] = ++sequence
        }
    }

    fun currentEntry(songId: String, identity: QueueResourceIdentity): QueueSongCacheEntry? =
        synchronized(lock) { _state.value[songId]?.takeIf { it.identity == identity } }

    fun load(songId: String) {
        val identity: QueueResourceIdentity
        val token: Long
        synchronized(lock) {
            identity = activeIdentity ?: return
            if (songId !in visibleSongIds || songId.isBlank()) return
            val existing = _state.value[songId]
            if (existing?.identity == identity &&
                existing.status != QueueSongLoadStatus.FAILED
            ) return
            token = ++sequence
            requestTokens[songId] = token
            lastAccess[songId] = sequence
            putEntryLocked(
                songId,
                QueueSongCacheEntry(identity, QueueSongLoadStatus.LOADING),
                prune = false,
            )
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    val resource = loader(identity, songId)
                    currentCoroutineContext().ensureActive()
                    val song = resource.song
                    if (song.songId.isNullOrBlank() || song.songId != songId) {
                        complete(
                            songId, identity, token,
                            QueueSongCacheEntry(
                                identity,
                                QueueSongLoadStatus.FAILED,
                                error = "Song metadata identity did not match the requested song",
                            ),
                        )
                        return@launch
                    }
                    val artworkId = song.artwork?.artworkId?.takeIf(String::isNotBlank)
                    val artworkStatus = when {
                        artworkId == null -> QueueArtworkStatus.NOT_REFERENCED
                        isUsableArtwork(resource.artworkResponse) -> QueueArtworkStatus.AVAILABLE
                        resource.artworkResponse.isExplicitArtworkMissing() -> QueueArtworkStatus.MISSING
                        else -> QueueArtworkStatus.RETRYABLE_FAILURE
                    }
                    if (artworkId != null && artworkStatus == QueueArtworkStatus.AVAILABLE) {
                        val bytes = (resource.artworkResponse as? HttpResult.Body)?.bytes
                        if (bytes != null) {
                            try {
                                artworkPrefetcher?.invoke(identity, artworkId, bytes)
                            } catch (_: Exception) {
                                // Thumbnail warming cannot turn valid metadata into a Queue failure.
                            }
                        }
                    }
                    if (artworkStatus == QueueArtworkStatus.RETRYABLE_FAILURE) {
                        synchronized(lock) { lastArtworkAttempt[songId] = clockMillis() }
                    }
                    complete(
                        songId, identity, token,
                        QueueSongCacheEntry(
                            identity = identity,
                            status = QueueSongLoadStatus.READY,
                            title = song.title,
                            artists = song.artists,
                            album = song.album,
                            codec = song.codec,
                            bitDepth = song.bitDepth,
                            sampleRateHz = song.sampleRateHz,
                            availabilityStatus = song.availabilityStatus.name,
                            artworkId = artworkId,
                            artworkStatus = artworkStatus,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    synchronized(lock) {
                        if (requestTokens[songId] == token) {
                            requestTokens.remove(songId)
                            jobs.remove(songId)
                            if (_state.value[songId]?.status == QueueSongLoadStatus.LOADING) {
                                removeEntryLocked(songId)
                            }
                        }
                    }
                    throw cancelled
                } catch (error: Exception) {
                    complete(
                        songId, identity, token,
                        QueueSongCacheEntry(
                            identity,
                            QueueSongLoadStatus.FAILED,
                            error = error.message ?: "Song metadata read failed",
                        ),
                    )
                }
            }
            jobs[songId] = job
            job.start()
        }
    }

    /** Rehydrates an evicted decoded thumbnail from the app-wide raw-artwork cache on viewport re-entry. */
    fun preloadArtwork(songId: String) {
        val identity: QueueResourceIdentity
        val artworkId: String
        val rehydrate = artworkRehydrator ?: return
        val shouldRetry: Boolean
        synchronized(lock) {
            identity = activeIdentity ?: return
            val entry = _state.value[songId]?.takeIf {
                it.identity == identity &&
                    it.status == QueueSongLoadStatus.READY &&
                    (it.artworkStatus == QueueArtworkStatus.AVAILABLE ||
                        it.artworkStatus == QueueArtworkStatus.RETRYABLE_FAILURE)
            } ?: return
            shouldRetry = entry.artworkStatus == QueueArtworkStatus.RETRYABLE_FAILURE
            if (shouldRetry) {
                val lastAttempt = lastArtworkAttempt[songId] ?: 0L
                if (clockMillis() - lastAttempt < artworkRetryIntervalMillis) return
                lastArtworkAttempt[songId] = clockMillis()
            }
            artworkId = entry.artworkId ?: return
            if (artworkJobs[songId]?.isActive == true) return
            lateinit var created: Job
            created = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    val response = rehydrate(identity, songId, artworkId)
                    currentCoroutineContext().ensureActive()
                    val status = when {
                        isUsableArtwork(response) -> {
                            val bytes = (response as? HttpResult.Body)?.bytes
                            if (bytes != null) artworkPrefetcher?.invoke(identity, artworkId, bytes)
                            QueueArtworkStatus.AVAILABLE
                        }
                        response.isExplicitArtworkMissing() -> QueueArtworkStatus.MISSING
                        else -> QueueArtworkStatus.RETRYABLE_FAILURE
                    }
                    synchronized(lock) {
                        if (activeIdentity == identity) {
                            val current = _state.value[songId]?.takeIf { it.identity == identity }
                            if (current != null) putEntryLocked(songId, current.copy(artworkStatus = status), prune = false)
                            if (status == QueueArtworkStatus.RETRYABLE_FAILURE) {
                                lastArtworkAttempt[songId] = clockMillis()
                            } else {
                                lastArtworkAttempt.remove(songId)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } finally {
                    synchronized(lock) {
                        if (artworkJobs[songId] === created) artworkJobs.remove(songId)
                    }
                }
            }
            artworkJobs[songId] = created
            created.start()
        }
    }

    fun clear() = synchronized(lock) {
        jobs.values.forEach(Job::cancel)
        artworkJobs.values.forEach(Job::cancel)
        jobs.clear()
        artworkJobs.clear()
        requestTokens.clear()
        lastAccess.clear()
        lastArtworkAttempt.clear()
        activeIdentity = null
        visibleSongIds = emptySet()
        _state.value = emptyMap()
    }

    private fun isUsableArtwork(response: HttpResult?): Boolean {
        val body = response as? HttpResult.Body ?: return false
        if (body.bytes.isEmpty() || !body.contentType.orEmpty().substringBefore(';')
                .trim().startsWith("image/", ignoreCase = true)
        ) return false
        val bytes = body.bytes
        val png = bytes.size >= 8 && bytes.take(8).map { it.toInt() and 0xff } ==
            listOf(137, 80, 78, 71, 13, 10, 26, 10)
        val jpeg = bytes.size >= 3 && bytes[0] == 0xff.toByte() &&
            bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        val gif = bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
        val webp = bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        return png || jpeg || gif || webp
    }

    private fun complete(
        songId: String,
        identity: QueueResourceIdentity,
        token: Long,
        entry: QueueSongCacheEntry,
    ) = synchronized(lock) {
        if (activeIdentity != identity || requestTokens[songId] != token) return@synchronized
        jobs.remove(songId)
        requestTokens.remove(songId)
        putEntryLocked(songId, entry, prune = true)
    }

    private fun putEntryLocked(songId: String, entry: QueueSongCacheEntry, prune: Boolean) {
        _state.value = _state.value.toMutableMap().apply { put(songId, entry) }
        lastAccess[songId] = ++sequence
        if (prune) pruneCompletedEntriesLocked()
    }

    private fun removeEntryLocked(songId: String) {
        _state.value = _state.value.toMutableMap().apply { remove(songId) }
        lastAccess.remove(songId)
        lastArtworkAttempt.remove(songId)
    }

    private fun pruneCompletedEntriesLocked() {
        while (_state.value.size > maxEntries) {
            val candidate = _state.value.entries
                .filter { it.value.status != QueueSongLoadStatus.LOADING }
                .minByOrNull { lastAccess[it.key] ?: Long.MIN_VALUE }
                ?.key ?: break
            removeEntryLocked(candidate)
        }
    }
}
