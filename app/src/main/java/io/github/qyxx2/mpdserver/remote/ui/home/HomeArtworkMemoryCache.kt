package io.github.qyxx2.mpdserver.remote.ui.home

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.qyxx2.mpdserver.remote.ui.queue.QueueResourceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.LinkedHashMap
import java.util.WeakHashMap

internal data class HomeArtworkCacheKey(
    val identity: QueueResourceIdentity,
    val artworkId: String,
)

/**
 * Bounded high-resolution presentation cache for Home's large cover cards.
 *
 * This cache owns decoded Home-sized bitmaps only. Original compressed bytes remain in the
 * AppRuntime ResourceMemoryCache; Queue keeps using its separate 160px thumbnail cache.
 */
internal object HomeArtworkMemoryCache {
    private const val MIN_DECODE_LONG_EDGE = 768
    private const val MAX_RETAINED_BYTES = 64L * 1024L * 1024L

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val decoderSlots = Semaphore(2)
    private val bitmaps = LinkedHashMap<HomeArtworkCacheKey, ImageBitmap>(24, 0.75f, true)
    private val stateFlows =
        WeakHashMap<HomeArtworkCacheKey, WeakReference<MutableStateFlow<ImageBitmap?>>>()
    private val jobs = mutableMapOf<HomeArtworkCacheKey, Job>()
    private var activeIdentity: QueueResourceIdentity? = null
    private var retainedBytes = 0L

    fun updateIdentity(identity: QueueResourceIdentity) = synchronized(lock) {
        if (activeIdentity == identity) return@synchronized
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        bitmaps.clear()
        retainedBytes = 0L
        stateFlows.values.forEach { it.get()?.value = null }
        activeIdentity = identity
    }

    fun stateFor(key: HomeArtworkCacheKey): StateFlow<ImageBitmap?> = synchronized(lock) {
        val flow = stateFlows[key]?.get() ?: MutableStateFlow(bitmaps[key]).also { created ->
            stateFlows[key] = WeakReference(created)
        }
        flow.asStateFlow()
    }

    /** Decodes a shared original-byte response only once for the active Home resource identity. */
    fun preload(key: HomeArtworkCacheKey, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val job: Job
        synchronized(lock) {
            if (activeIdentity != key.identity || bitmaps.containsKey(key) || jobs.containsKey(key)) return
            lateinit var created: Job
            created = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                val bitmap = try {
                    decoderSlots.withPermit { decodeHighResolution(bytes) }
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

    internal suspend fun decodeHighResolution(bytes: ByteArray): ImageBitmap? =
        withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val options = BitmapFactory.Options().apply {
                inSampleSize = HomeArtworkDecodePolicy.inSampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    MIN_DECODE_LONG_EDGE,
                )
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }
}

/** Chooses a power-of-two sample while retaining at least the requested long edge where possible. */
internal object HomeArtworkDecodePolicy {
    fun inSampleSize(
        width: Int,
        height: Int,
        minimumLongEdge: Int = 768,
    ): Int {
        if (width <= 0 || height <= 0 || minimumLongEdge <= 0) return 1
        val sourceLongEdge = maxOf(width, height)
        var sampleSize = 1
        while (sourceLongEdge / (sampleSize * 2L) >= minimumLongEdge) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
