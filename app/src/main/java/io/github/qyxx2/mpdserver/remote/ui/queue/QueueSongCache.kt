package io.github.qyxx2.mpdserver.remote.ui.queue

import io.github.qyxx2.mpdserver.remote.network.HttpResult
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Resource-read identity. Any server session, library revision, connection or origin change invalidates work. */
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
enum class QueueArtworkStatus { NOT_REFERENCED, AVAILABLE, UNAVAILABLE }

data class QueueSongCacheEntry(
    val identity: QueueResourceIdentity,
    val status: QueueSongLoadStatus,
    val song: SongDto? = null,
    val artworkBytes: ByteArray? = null,
    val artworkStatus: QueueArtworkStatus = QueueArtworkStatus.NOT_REFERENCED,
    val error: String? = null,
)

/**
 * Bounded, in-memory metadata/artwork cache. It shares the caller's RuntimeReadApi through its loader;
 * it neither creates a transport nor stores data outside process memory.
 */
class QueueSongCache(
    private val scope: CoroutineScope,
    private val maxConcurrentRequests: Int = 3,
    private val maxEntries: Int = 512,
    private val maxArtworkBytesPerItem: Int = 8 * 1024 * 1024,
    private val maxArtworkBytesTotal: Long = 24L * 1024 * 1024,
    private val artworkPrefetcher: ((QueueResourceIdentity, String, ByteArray) -> Unit)? = null,
    private val loader: suspend (QueueResourceIdentity, String) -> QueueSongResource,
) {
    init {
        require(maxConcurrentRequests >= 1)
        require(maxEntries >= 1)
        require(maxArtworkBytesPerItem >= 0)
        require(maxArtworkBytesTotal >= maxArtworkBytesPerItem.toLong())
    }

    private val lock = Any()
    private val semaphore = Semaphore(maxConcurrentRequests)
    private val _state = MutableStateFlow<Map<String, QueueSongCacheEntry>>(emptyMap())
    val state: StateFlow<Map<String, QueueSongCacheEntry>> = _state.asStateFlow()

    private var activeIdentity: QueueResourceIdentity? = null
    private var visibleSongIds: Set<String> = emptySet()
    private var sequence = 0L
    private val jobs = mutableMapOf<String, Job>()
    private val requestTokens = mutableMapOf<String, Long>()
    private val lastAccess = mutableMapOf<String, Long>()

    fun updateScope(identity: QueueResourceIdentity, visibleIds: Set<String>) = synchronized(lock) {
        val normalizedVisible = visibleIds.filter(String::isNotBlank).toSet()
        if (activeIdentity != identity) {
            jobs.values.forEach(Job::cancel)
            jobs.clear()
            requestTokens.clear()
            lastAccess.clear()
            _state.value = emptyMap()
            activeIdentity = identity
        }
        // Prefetched work is intentionally allowed to finish after its row leaves the viewport.
        // Cancelling on every scroll caused request churn and guaranteed placeholders on fast flings.
        visibleSongIds = normalizedVisible
        normalizedVisible.forEach { id -> if (_state.value.containsKey(id)) lastAccess[id] = ++sequence }
    }

    /** Synchronous read for a row's initial state; ongoing updates are observed through [state]. */
    fun currentEntry(songId: String, identity: QueueResourceIdentity): QueueSongCacheEntry? =
        synchronized(lock) { _state.value[songId]?.takeIf { it.identity == identity } }

    fun load(songId: String) {
        val key: QueueResourceIdentity
        val token: Long
        synchronized(lock) {
            key = activeIdentity ?: return
            if (songId !in visibleSongIds || songId.isBlank()) return
            val existing = _state.value[songId]
            if (existing?.identity == key) return
            token = ++sequence
            requestTokens[songId] = token
            lastAccess[songId] = sequence
            putEntryLocked(
                songId,
                QueueSongCacheEntry(identity = key, status = QueueSongLoadStatus.LOADING),
                prune = false,
            )
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    val resource = semaphore.withPermit { loader(key, songId) }
                    currentCoroutineContext().ensureActive()
                    val requestedId = resource.song.songId
                    if (requestedId.isNullOrBlank() || requestedId != songId) {
                        complete(
                            songId,
                            key,
                            token,
                            QueueSongCacheEntry(
                                identity = key,
                                status = QueueSongLoadStatus.FAILED,
                                error = "Song metadata identity did not match the requested song",
                            ),
                        )
                        return@launch
                    }
                    val artwork = resolveArtwork(resource)
                    // Start decoding before artwork-byte LRU pruning can discard this response.
                    // The callback only warms a presentation cache and never changes queue state.
                    artwork.first?.let { bytes ->
                        try {
                            artworkPrefetcher?.invoke(key, songId, bytes)
                        } catch (_: Exception) {
                            // Artwork decode scheduling must not turn valid song metadata into a failure.
                        }
                    }
                    complete(
                        songId,
                        key,
                        token,
                        QueueSongCacheEntry(
                            identity = key,
                            status = QueueSongLoadStatus.READY,
                            song = resource.song,
                            artworkBytes = artwork.first,
                            artworkStatus = artwork.second,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    synchronized(lock) {
                        if (requestTokens[songId] == token) {
                            requestTokens.remove(songId)
                            jobs.remove(songId)
                            if (_state.value[songId]?.status == QueueSongLoadStatus.LOADING) removeEntryLocked(songId)
                        }
                    }
                    throw cancelled
                } catch (error: Exception) {
                    complete(
                        songId,
                        key,
                        token,
                        QueueSongCacheEntry(
                            identity = key,
                            status = QueueSongLoadStatus.FAILED,
                            error = error.message ?: "Song metadata read failed",
                        ),
                    )
                }
            }
            jobs[songId] = job
            job.start()
        }
    }

    fun clear() = synchronized(lock) {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        requestTokens.clear()
        lastAccess.clear()
        activeIdentity = null
        visibleSongIds = emptySet()
        _state.value = emptyMap()
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
    }

    private fun pruneCompletedEntriesLocked() {
        // Metadata is small and useful across all 400 queue occurrences. Never throw away song
        // metadata solely because artwork consumed its separate memory budget.
        while (_state.value.size > maxEntries) {
            val candidate = _state.value.entries
                .filter { it.value.status != QueueSongLoadStatus.LOADING }
                .minByOrNull { lastAccess[it.key] ?: Long.MIN_VALUE }
                ?.key ?: break
            removeEntryLocked(candidate)
        }

        while (retainedArtworkBytesLocked() > maxArtworkBytesTotal) {
            val candidate = _state.value.entries
                .filter { it.value.status != QueueSongLoadStatus.LOADING && it.value.artworkBytes != null }
                .minByOrNull { lastAccess[it.key] ?: Long.MIN_VALUE }
                ?.key ?: break
            val entry = _state.value[candidate] ?: continue
            _state.value = _state.value.toMutableMap().apply {
                put(candidate, entry.copy(artworkBytes = null, artworkStatus = QueueArtworkStatus.UNAVAILABLE))
            }
        }
    }

    private fun retainedArtworkBytesLocked(): Long = _state.value.values.sumOf { entry ->
        entry.artworkBytes?.size?.toLong() ?: 0L
    }

    private fun resolveArtwork(resource: QueueSongResource): Pair<ByteArray?, QueueArtworkStatus> {
        if (resource.song.artwork == null) return null to QueueArtworkStatus.NOT_REFERENCED
        val body = resource.artworkResponse as? HttpResult.Body ?: return null to QueueArtworkStatus.UNAVAILABLE
        if (!body.contentType.orEmpty().substringBefore(';').trim().startsWith("image/", ignoreCase = true)) {
            return null to QueueArtworkStatus.UNAVAILABLE
        }
        if (body.bytes.size > maxArtworkBytesPerItem) return null to QueueArtworkStatus.UNAVAILABLE
        if (!hasSupportedImageSignature(body.bytes)) return null to QueueArtworkStatus.UNAVAILABLE
        return body.bytes.copyOf() to QueueArtworkStatus.AVAILABLE
    }

    private fun hasSupportedImageSignature(bytes: ByteArray): Boolean {
        val png = bytes.size >= 8 && bytes.take(8).map { it.toInt() and 0xff } ==
            listOf(137, 80, 78, 71, 13, 10, 26, 10)
        val jpeg = bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        val gif = bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
        val webp = bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        return png || jpeg || gif || webp
    }
}
