package io.github.qyxx2.mpdserver.remote.media

import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.github.qyxx2.mpdserver.remote.MpdServerRemoteApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the existing process-scoped MediaSession callback connected while the Activity is absent.
 *
 * This app remotely controls NAS playback; it does not decode or output local audio. Therefore this
 * is a regular started Service, not a mediaPlayback foreground service. Android may reclaim it;
 * START_STICKY only requests best-effort recreation and does not bypass force-stop or OEM policy.
 */
class RemotePlaybackService : Service() {
    private lateinit var applicationOwner: MpdServerRemoteApplication
    private lateinit var serviceScope: CoroutineScope
    private lateinit var callback: RemotePlaybackMediaSessionCallback

    private var attachJob: Job? = null
    private var latestStartId: Int = 0

    override fun onCreate() {
        super.onCreate()
        applicationOwner = application as MpdServerRemoteApplication
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        callback = RemotePlaybackMediaSessionCallback { action ->
            // Gateway rechecks canonical freshness, origin transition and in-flight mutation state.
            applicationOwner.runtime.playerCommands.dispatch(action)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (attachJob?.isActive != true) {
            attachJob = serviceScope.launch {
                try {
                    // The Application must finish DataStore origin restoration before this Service
                    // can observe a connected snapshot or publish any system action.
                    applicationOwner.awaitServerOriginReady()
                    if (!applicationOwner.hasConfiguredServerOrigin) {
                        // A fresh install may still have only the local DEFAULT placeholder. Never
                        // keep a service alive waiting on, or dispatching through, that placeholder.
                        stopSelfResult(latestStartId)
                        return@launch
                    }
                    applicationOwner.mediaSessionController.awaitFreshSnapshotEvaluation()

                    val confirmedIdentity =
                        applicationOwner.mediaSessionController.projection.value?.confirmedIdentity
                    if (confirmedIdentity == null) {
                        stopSelfResult(latestStartId)
                        return@launch
                    }

                    applicationOwner.mediaSessionController.attachServiceCallback(callback)
                    applicationOwner.onPlaybackServiceAttached()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    applicationOwner.onPlaybackServiceStartFailure(
                        failure.message ?: failure.javaClass.simpleName,
                    )
                    stopSelfResult(latestStartId)
                }
            }
        }
        // The system may recreate this adapter after process reclaim; no playback fact is restored
        // from the Intent or Bundle. It will wait for a fresh canonical snapshot above.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing the launcher task is not a Stop command. Remote playback and its system
        // controls remain available while Android keeps this started Service alive.
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        attachJob?.cancel()
        if (::callback.isInitialized) {
            applicationOwner.mediaSessionController.detachServiceCallback(callback)
            applicationOwner.onPlaybackServiceDestroyed()
        }
        if (::serviceScope.isInitialized) serviceScope.cancel()
        super.onDestroy()
    }
}
