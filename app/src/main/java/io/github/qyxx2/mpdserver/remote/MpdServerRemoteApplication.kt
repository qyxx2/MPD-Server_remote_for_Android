package io.github.qyxx2.mpdserver.remote

import android.app.Application
import android.content.Intent
import io.github.qyxx2.mpdserver.remote.media.ConfirmedMediaSessionIdentity
import io.github.qyxx2.mpdserver.remote.media.RemoteMediaSessionController
import io.github.qyxx2.mpdserver.remote.media.MediaNotificationController
import io.github.qyxx2.mpdserver.remote.media.RemotePlaybackService
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.ui.preferences.UiPreferencesRepository
import io.github.qyxx2.mpdserver.remote.ui.preferences.uiPreferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Process-wide owners for networking and local UI preferences. */
class MpdServerRemoteApplication : Application() {
    lateinit var runtime: AppRuntime
        private set

    lateinit var uiPreferences: UiPreferencesRepository
        private set

    /** One process-level native media session adapter; it shares the existing Runtime owner. */
    lateinit var mediaSessionController: RemoteMediaSessionController
        private set

    /** One process-level MediaStyle notification owner backed by the same native Session. */
    lateinit var mediaNotificationController: MediaNotificationController
        private set

    private val mediaSessionScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Keeps local preference writes alive when the Settings destination leaves composition. */
    val uiPreferencesWriteScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val serverOriginReady = CompletableDeferred<Unit>()
    private lateinit var playbackServiceIntent: Intent

    @Volatile
    var hasConfiguredServerOrigin: Boolean = false
        private set

    private var observedMediaIdentity: ConfirmedMediaSessionIdentity? = null
    private var mediaSessionObservationInitialized = false
    private var playbackServiceStartRequested = false
    private var playbackServiceStartFailureIdentity: ConfirmedMediaSessionIdentity? = null

    override fun onCreate() {
        super.onCreate()
        playbackServiceIntent = Intent(this, RemotePlaybackService::class.java)
        uiPreferences = UiPreferencesRepository(uiPreferencesDataStore)
        runtime = AppRuntime.create(ServerOrigin.parse(DEFAULT_ORIGIN))
        mediaSessionController = RemoteMediaSessionController(this, runtime, mediaSessionScope)
        mediaNotificationController = MediaNotificationController(
            context = this,
            runtime = runtime,
            sessionController = mediaSessionController,
            scope = mediaSessionScope,
        )
        observeMediaSessionServiceLifecycle()

        // Restore the configured local origin without probing the server or creating another owner.
        uiPreferencesWriteScope.launch {
            var shouldStartConfiguredRuntime = false
            try {
                val storedAddress = uiPreferences.serverOrigin.first()
                val restoredOrigin = runCatching {
                    ServerOrigin.parseAddress(storedAddress)
                }.getOrElse {
                    ServerOrigin.parseAddress(DEFAULT_ORIGIN)
                }
                runtime.changeOrigin(restoredOrigin)
                shouldStartConfiguredRuntime =
                    storedAddress == restoredOrigin.address && restoredOrigin.address != DEFAULT_ORIGIN
                if (storedAddress != restoredOrigin.address) {
                    uiPreferences.setServerOrigin(restoredOrigin)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Preferences may be temporarily unavailable; keep the valid local placeholder.
                shouldStartConfiguredRuntime = false
                runtime.changeOrigin(ServerOrigin.parse(DEFAULT_ORIGIN))
            } finally {
                // Reuse this one Runtime owner when an address was explicitly configured. A fresh
                // install with only the local placeholder does not initiate a network connection.
                hasConfiguredServerOrigin = shouldStartConfiguredRuntime
                if (shouldStartConfiguredRuntime) runtime.start()
                serverOriginReady.complete(Unit)
            }
        }
    }

    /** Wait until DataStore has restored the configured origin into the sole Runtime owner. */
    suspend fun awaitServerOriginReady() {
        serverOriginReady.await()
    }

    /** Called by Activity.onStart to retry a background start previously rejected by Android. */
    fun retryMediaServiceStartFromForeground() {
        val identity = mediaSessionController.projection.value?.confirmedIdentity ?: return
        observedMediaIdentity = identity
        requestPlaybackServiceStart(identity, retryAfterBackgroundDenial = true)
    }

    /** Called by the Service only after it has attached the callback to the process Session. */
    fun onPlaybackServiceAttached() {
        playbackServiceStartRequested = true
        mediaNotificationController.reportServiceStartFailure(null)
    }

    /** Called when an asynchronously recreated Service cannot finish its readiness handshake. */
    fun onPlaybackServiceStartFailure(message: String) {
        playbackServiceStartRequested = false
        playbackServiceStartFailureIdentity = observedMediaIdentity
        mediaNotificationController.reportServiceStartFailure(message)
    }

    /** Called on ordinary Service destruction; Android may later recreate a START_STICKY service. */
    fun onPlaybackServiceDestroyed() {
        playbackServiceStartRequested = false
    }

    private fun observeMediaSessionServiceLifecycle() {
        mediaSessionScope.launch {
            mediaSessionController.projection.collect { projection ->
                val identity = projection?.confirmedIdentity
                if (identity != null) {
                    val identityChanged = identity != observedMediaIdentity
                    observedMediaIdentity = identity
                    if (identityChanged || !mediaSessionObservationInitialized) {
                        requestPlaybackServiceStart(identity, retryAfterBackgroundDenial = false)
                    }
                } else if (mediaSessionObservationInitialized && observedMediaIdentity != null) {
                    // No current occurrence / explicit Stop / new origin: withdraw the adapter too.
                    observedMediaIdentity = null
                    playbackServiceStartFailureIdentity = null
                    mediaNotificationController.reportServiceStartFailure(null)
                    stopPlaybackService()
                }
                mediaSessionObservationInitialized = true
            }
        }
    }

    private fun requestPlaybackServiceStart(
        identity: ConfirmedMediaSessionIdentity,
        retryAfterBackgroundDenial: Boolean,
    ) {
        if (playbackServiceStartRequested) return
        if (!retryAfterBackgroundDenial && playbackServiceStartFailureIdentity == identity) return

        try {
            startService(playbackServiceIntent)
                ?: throw IllegalStateException("Android did not accept the media control service start")
            playbackServiceStartRequested = true
            playbackServiceStartFailureIdentity = null
        } catch (failure: Exception) {
            playbackServiceStartFailureIdentity = identity
            mediaNotificationController.reportServiceStartFailure(
                failure.message ?: failure.javaClass.simpleName,
            )
        }
    }

    private fun stopPlaybackService() {
        if (!playbackServiceStartRequested) return
        stopService(playbackServiceIntent)
        playbackServiceStartRequested = false
    }

    /** Persist and apply one validated origin through the existing app-scoped Runtime. */
    suspend fun saveAndApplyServerOrigin(origin: ServerOrigin) {
        awaitServerOriginReady()
        uiPreferences.setServerOrigin(origin)
        runtime.changeOrigin(origin)
        hasConfiguredServerOrigin = origin.address != DEFAULT_ORIGIN
        runtime.start()
    }

    companion object {
        /** A local placeholder until the user configures a server origin; it does not connect automatically. */
        private const val DEFAULT_ORIGIN = ServerOrigin.DEFAULT_ADDRESS
    }
}
