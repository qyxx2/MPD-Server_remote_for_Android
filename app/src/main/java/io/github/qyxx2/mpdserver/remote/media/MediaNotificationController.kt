package io.github.qyxx2.mpdserver.remote.media

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Publishes the current native MediaSession projection as one MediaStyle notification.
 *
 * It reuses AppRuntime.reads and ResourceMemoryCache for images. It does not own playback state,
 * a player, a network client, action callbacks, a Service or any persistence.
 */
class MediaNotificationController(
    context: Context,
    private val runtime: AppRuntime,
    private val sessionController: RemoteMediaSessionController,
    private val scope: CoroutineScope,
) : Closeable {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    private val mutableAvailability = MutableStateFlow(
        MediaNotificationFactory.readAvailability(appContext),
    )
    val availability: StateFlow<MediaControlAvailability> = mutableAvailability.asStateFlow()

    private var currentProjection: MediaSessionProjection.Result? = null
    private var currentIdentity: ConfirmedMediaSessionIdentity? = null
    private var currentArtwork: Bitmap? = null
    private var artworkJob: Job? = null
    private var lastNotificationSignature: NotificationSignature? = null

    private data class NotificationSignature(
        val identity: ConfirmedMediaSessionIdentity,
        val playbackState: MediaSessionPlaybackState,
        val actions: MediaSessionCommandAvailability,
        val hasArtwork: Boolean,
    )

    private val collection: Job

    init {
        MediaNotificationFactory.ensureChannel(appContext)
        refreshAvailability()
        collection = scope.launch(Dispatchers.Main.immediate) {
            sessionController.projection.collect(::applyProjection)
        }
    }

    /** Re-read permission/channel state after the user returns from Android Settings. */
    fun refreshAvailability() {
        val previous = mutableAvailability.value
        val refreshed = MediaNotificationFactory.readAvailability(appContext).copy(
            serviceStartFailure = previous.serviceStartFailure,
        )
        mutableAvailability.value = refreshed
        val settingsChanged =
            refreshed.permission != previous.permission ||
                refreshed.appNotificationsEnabled != previous.appNotificationsEnabled ||
                refreshed.channelImportance != previous.channelImportance ||
                previous.publicationFailure != null
        if (settingsChanged) {
            // Returning from Android Settings is the only retry trigger after a platform denial.
            // A blocked channel is never recreated or replaced with a new identifier.
            lastNotificationSignature = null
            currentProjection?.let(::publish)
        }
    }

    /** Surface background-start restrictions without conflating them with POST_NOTIFICATIONS. */
    fun reportServiceStartFailure(message: String?) {
        mutableAvailability.value = mutableAvailability.value.copy(serviceStartFailure = message)
    }

    private fun applyProjection(projection: MediaSessionProjection.Result?) {
        val identity = projection?.confirmedIdentity
        val token = sessionController.sessionToken
        if (projection == null || projection.metadata == null || identity == null || token == null) {
            artworkJob?.cancel()
            artworkJob = null
            currentProjection = null
            currentIdentity = null
            currentArtwork = null
            lastNotificationSignature = null
            notificationManager.cancel(MediaNotificationFactory.NOTIFICATION_ID)
            refreshAvailability()
            return
        }

        val identityChanged = identity != currentIdentity
        currentProjection = projection
        if (identityChanged) {
            artworkJob?.cancel()
            artworkJob = null
            currentIdentity = identity
            currentArtwork = null
            lastNotificationSignature = null
        }

        publish(projection)
        if (identityChanged && !identity.artworkId.isNullOrBlank()) {
            loadArtwork(identity)
        }
    }

    private fun publish(projection: MediaSessionProjection.Result) {
        val identity = projection.confirmedIdentity ?: return
        val token = sessionController.sessionToken ?: return
        val signature = NotificationSignature(
            identity = identity,
            playbackState = projection.playbackState,
            actions = projection.actions,
            hasArtwork = currentArtwork != null,
        )
        if (signature == lastNotificationSignature) return

        try {
            val notification = MediaNotificationFactory.build(
                context = appContext,
                projection = projection,
                sessionToken = token,
                artwork = currentArtwork,
            )
            notificationManager.notify(MediaNotificationFactory.NOTIFICATION_ID, notification)
            lastNotificationSignature = signature
            mutableAvailability.value = MediaNotificationFactory.readAvailability(appContext).copy(
                serviceStartFailure = mutableAvailability.value.serviceStartFailure,
            )
        } catch (denied: SecurityException) {
            // Do not retry on every playback-position snapshot. Retry only after returning from
            // Android Settings or when notification permission/channel state actually changes.
            lastNotificationSignature = signature
            mutableAvailability.value = MediaNotificationFactory.readAvailability(
                appContext,
                publicationFailure = denied.message ?: "通知发布权限受限",
            ).copy(serviceStartFailure = mutableAvailability.value.serviceStartFailure)
        } catch (failure: RuntimeException) {
            lastNotificationSignature = signature
            mutableAvailability.value = MediaNotificationFactory.readAvailability(
                appContext,
                publicationFailure = failure.message ?: "媒体通知发布失败",
            ).copy(serviceStartFailure = mutableAvailability.value.serviceStartFailure)
        }
    }

    private fun loadArtwork(identity: ConfirmedMediaSessionIdentity) {
        artworkJob = scope.launch(Dispatchers.IO) {
            try {
                if (!isIdentityCurrent(identity)) return@launch
                val response = runtime.reads.getArtwork(identity.songId)
                val bytes = (response as? HttpResult.Body)?.bytes ?: return@launch
                val bitmap = withContext(Dispatchers.Default) { decodeArtwork(bytes) } ?: return@launch
                currentCoroutineContext().ensureActive()

                val accepted = withContext(Dispatchers.Main.immediate) {
                    if (currentIdentity != identity || !isIdentityCurrent(identity)) {
                        false
                    } else {
                        val applied = sessionController.updateArtwork(identity, bitmap)
                        if (applied) {
                            currentArtwork = bitmap
                            currentProjection?.let(::publish)
                        }
                        applied
                    }
                }
                if (!accepted) {
                    // The bitmap is not referenced by MediaSession/Notification when rejected.
                    bitmap.recycle()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Cover failures are non-fatal. The already-published text metadata stays visible.
            }
        }
    }

    private fun isIdentityCurrent(identity: ConfirmedMediaSessionIdentity): Boolean =
        MediaArtworkIdentityGuard.isCurrent(
            state = runtime.snapshots.state.value,
            currentOriginAddress = runtime.realtimeController.currentOrigin().address,
            expected = identity,
        )

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (
                bounds.outWidth / sampleSize > MAX_ARTWORK_DIMENSION ||
                bounds.outHeight / sampleSize > MAX_ARTWORK_DIMENSION
            ) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull()
    }

    override fun close() {
        collection.cancel()
        artworkJob?.cancel()
        artworkJob = null
        currentProjection = null
        currentIdentity = null
        currentArtwork = null
        lastNotificationSignature = null
        runCatching { notificationManager.cancel(MediaNotificationFactory.NOTIFICATION_ID) }
    }

    private companion object {
        const val MAX_ARTWORK_DIMENSION = 512
    }
}
