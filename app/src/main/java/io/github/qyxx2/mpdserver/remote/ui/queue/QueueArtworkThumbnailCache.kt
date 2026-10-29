package io.github.qyxx2.mpdserver.remote.ui.queue

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.LinkedHashMap
import java.util.WeakHashMap

internal data class QueueArtworkThumbnailKey(
    val identity: QueueResourceIdentity,
    val songId: String,
)

/**
 * Small decoded-thumbnail LRU shared by Queue rows and route re-entry.
 *
 * This cache owns only presentation bitmaps. It uses no transport and cannot become a playback
 * or queue-state owner. Resource identity changes invalidate all pending and retained thumbnails.
 */
internal object QueueArtworkThumbnailCache {
    private const val MAX_DIMENSION = 160
    private const val MAX_RETAINED_BYTES = 40L * 1024L * 1024L

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val decoderSlots = Semaphore(2)
    private val bitmaps = LinkedHashMap<QueueArtworkThumbnailKey, ImageBitmap>(32, 0.75f, true)
    private val stateFlows = WeakHashMap<QueueArtworkThumbnailKey, WeakReference<MutableStateFlow<ImageBitmap?>>>()
    private val jobs = mutableMapOf<QueueArtworkThumbnailKey, Job>()
    private val prefetchedKeys = mutableSetOf<QueueArtworkThumbnailKey>()
    private val failedKeys = mutableSetOf<QueueArtworkThumbnailKey>()
    private var activeIdentity: QueueResourceIdentity? = null
    private var retainedBytes = 0L

    fun updateIdentity(identity: QueueResourceIdentity) = synchronized(lock) {
        if (activeIdentity == identity) return@synchronized
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        prefetchedKeys.clear()
        failedKeys.clear()
        bitmaps.clear()
        retainedBytes = 0L
        stateFlows.values.forEach { it.get()?.value = null }
        activeIdentity = identity
    }

    fun stateFor(key: QueueArtworkThumbnailKey): StateFlow<ImageBitmap?> = synchronized(lock) {
        val flow = stateFlows[key]?.get() ?: MutableStateFlow(bitmaps[key]).also { created ->
            stateFlows[key] = WeakReference(created)
        }
        flow.asStateFlow()
    }

    /** Warm each resource once per identity; route re-entry must not churn an already-warmed LRU. */
    fun preloadPrefetchOnce(key: QueueArtworkThumbnailKey, bytes: ByteArray) {
        synchronized(lock) {
            if (activeIdentity != key.identity || !prefetchedKeys.add(key)) return
        }
        preload(key, bytes)
    }

    /** On-demand decode for a visible row whose thumbnail was evicted from the bounded LRU. */
    fun preload(key: QueueArtworkThumbnailKey, bytes: ByteArray) {
        val job: Job
        synchronized(lock) {
            if (activeIdentity != key.identity || bitmaps.containsKey(key) || jobs.containsKey(key) || key in failedKeys) return
            lateinit var created: Job
            created = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                val bitmap = try {
                    decoderSlots.withPermit { decodeThumbnail(bytes) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                synchronized(lock) {
                    if (jobs[key] !== created) return@synchronized
                    jobs.remove(key)
                    if (activeIdentity != key.identity) return@synchronized
                    if (bitmap != null) {
                        bitmaps[key] = bitmap
                        retainedBytes += bitmap.width.toLong() * bitmap.height.toLong() * 4L
                    } else {
                        failedKeys.add(key)
                    }
                    stateFlows[key]?.get()?.value = bitmap
                    trimLocked()
                }
            }
            jobs[key] = created
            job = created
        }
        job.start()
    }

    private fun trimLocked() {
        val iterator = bitmaps.entries.iterator()
        while (retainedBytes > MAX_RETAINED_BYTES && iterator.hasNext()) {
            val eldest = iterator.next()
            retainedBytes -= eldest.value.width.toLong() * eldest.value.height.toLong() * 4L
            stateFlows[eldest.key]?.get()?.value = null
            iterator.remove()
        }
    }

    private suspend fun decodeThumbnail(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DIMENSION) {
            sampleSize *= 2
        }
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )?.asImageBitmap()
    }
}
