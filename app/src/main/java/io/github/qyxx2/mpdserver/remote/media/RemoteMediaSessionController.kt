package io.github.qyxx2.mpdserver.remote.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import io.github.qyxx2.mpdserver.remote.runtime.RuntimeConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * Process-scoped adapter from the one canonical AppRuntime into Android's native MediaSession.
 *
 * It owns no playback state, network client, polling loop, notification, or Activity reference.
 * The Service attaches the native callback to this same owner. Playback facts and command
 * capabilities remain canonical Runtime projections; the Service only supplies callback lifetime.
 */
class RemoteMediaSessionController(
    context: Context,
    private val runtime: AppRuntime,
    scope: CoroutineScope,
) : Closeable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var session: MediaSession? = null

    private var lastConfirmed: ConfirmedMediaSessionIdentity? = null
    private var lastApplied: MediaSessionProjection.Result? = null
    private var appliedIdentity: ConfirmedMediaSessionIdentity? = null
    private var artworkBitmap: Bitmap? = null
    @Volatile
    private var serviceCallback: MediaSession.Callback? = null
    private var latestCanonicalProjection: MediaSessionProjection.Result? = null
    private val mutableProjection = MutableStateFlow<MediaSessionProjection.Result?>(null)
    private val mutableFreshSnapshotEvaluated = MutableStateFlow(false)

    /** The effective projection used by the native Session and its MediaStyle notification. */
    val projection: StateFlow<MediaSessionProjection.Result?> = mutableProjection.asStateFlow()

    /**
     * True only after this process has evaluated a complete fresh snapshot on the current
     * connection generation. A restored Service waits for this instead of trusting old metadata.
     */
    val freshSnapshotEvaluated: StateFlow<Boolean> = mutableFreshSnapshotEvaluated.asStateFlow()

    val isServiceCallbackAttached: Boolean
        get() = serviceCallback != null

    suspend fun awaitFreshSnapshotEvaluation() {
        freshSnapshotEvaluated.first { it }
    }

    private val collection: Job = scope.launch(Dispatchers.Main.immediate) {
        // Snapshot changes update state/metadata. Activity changes only affect available actions.
        combine(runtime.snapshots.state, runtime.mutations.activities) { _, _ -> Unit }
            .collect {
                val commandAvailability = runtime.playerCommands.availability()
                val actions = MediaSessionCommandAvailability(
                    pause = commandAvailability.pause,
                    resume = commandAvailability.resume,
                    previous = commandAvailability.previous,
                    next = commandAvailability.next,
                )
                val projection = MediaSessionProjection.project(
                    state = commandAvailability.snapshot,
                    originAddress = runtime.realtimeController.currentOrigin().address,
                    previousConfirmed = lastConfirmed,
                    commandActions = actions,
                )
                apply(projection)
                mutableFreshSnapshotEvaluated.value =
                    commandAvailability.snapshot.snapshot != null &&
                        commandAvailability.snapshot.connectionStatus == RuntimeConnectionStatus.CONNECTED &&
                        commandAvailability.snapshot.controlsEligible
            }
    }

    /** Native token for the next batch's MediaStyle notification; null until playback is confirmed. */
    val sessionToken: MediaSession.Token?
        get() = session?.sessionToken

    /** Diagnostic state for instrumentation and lifecycle verification. */
    val isSessionActive: Boolean
        get() = session?.isActive == true

    private fun apply(projection: MediaSessionProjection.Result) {
        when (projection.disposition) {
            MediaSessionProjection.Disposition.WITHDRAWN -> {
                lastConfirmed = null
                latestCanonicalProjection = null
                lastApplied = projection
                mutableProjection.value = null
                releaseSession()
            }

            MediaSessionProjection.Disposition.CONFIRMED -> {
                lastConfirmed = projection.confirmedIdentity
                latestCanonicalProjection = projection
                publishEffectiveProjection()
            }

            MediaSessionProjection.Disposition.RETAINED -> {
                // Retained metadata is only valid for a session previously published by this
                // process. A newly created process never revives a persisted/last-known track.
                if (session == null) {
                    lastConfirmed = null
                    latestCanonicalProjection = null
                    lastApplied = null
                    mutableProjection.value = null
                } else {
                    latestCanonicalProjection = projection
                    publishEffectiveProjection()
                }
            }
        }
    }

    /**
     * A native Session is not actionable until the process-level Service has attached its
     * callback. Metadata remains visible while the Service is starting; system action flags are
     * withheld so SystemUI cannot advertise controls that have no callback owner.
     */
    private fun publishEffectiveProjection() {
        val canonical = latestCanonicalProjection ?: return
        val effective = if (serviceCallback == null) {
            canonical.copy(actions = MediaSessionCommandAvailability.NONE)
        } else {
            canonical
        }
        publish(effective)
        mutableProjection.value = effective.takeIf { session?.isActive == true }
    }

    /** Attach/detach is idempotent and always updates the same process-owned native MediaSession. */
    fun attachServiceCallback(callback: MediaSession.Callback) {
        if (serviceCallback === callback) return
        serviceCallback = callback
        session?.setCallback(callback, mainHandler)
        publishEffectiveProjection()
    }

    fun detachServiceCallback(callback: MediaSession.Callback) {
        if (serviceCallback !== callback) return
        serviceCallback = null
        session?.setCallback(null, mainHandler)
        publishEffectiveProjection()
    }

    private fun publish(projection: MediaSessionProjection.Result) {
        if (lastApplied == projection && session?.isActive == true) return
        val metadata = projection.metadata ?: return
        val identity = projection.confirmedIdentity ?: return
        val target = session ?: MediaSession(appContext, SESSION_TAG).also {
            it.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            serviceCallback?.let { callback -> it.setCallback(callback, mainHandler) }
            session = it
        }

        if (lastApplied?.metadata != metadata || appliedIdentity != identity || !target.isActive) {
            // A new occurrence, server generation, Library revision or artwork reference must
            // never inherit the previous track's image, even when its text happens to be equal.
            if (appliedIdentity != identity) artworkBitmap = null
            target.setMetadata(metadata.toPlatformMetadata(artworkBitmap))
            appliedIdentity = identity
        }
        if (lastApplied != projection || !target.isActive) {
            target.setPlaybackState(projection.toPlatformPlaybackState())
        }
        if (!target.isActive) target.setActive(true)
        lastApplied = projection
    }

    /** Apply a decoded image only while the exact confirmed occurrence is still published. */
    fun updateArtwork(identity: ConfirmedMediaSessionIdentity, bitmap: Bitmap?): Boolean {
        val target = session ?: return false
        if (!target.isActive || lastConfirmed != identity || appliedIdentity != identity) return false
        artworkBitmap = bitmap
        target.setMetadata(identity.metadata.toPlatformMetadata(bitmap))
        return true
    }

    private fun releaseSession() {
        val old = session ?: return
        session = null
        artworkBitmap = null
        appliedIdentity = null
        runCatching {
            old.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(0L)
                    .setState(PlaybackState.STATE_NONE, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
                    .build(),
            )
            old.setMetadata(null)
            if (old.isActive) old.setActive(false)
        }
        old.release()
    }

    override fun close() {
        collection.cancel()
        val cleanup = Runnable {
            lastConfirmed = null
            lastApplied = null
            latestCanonicalProjection = null
            serviceCallback = null
            mutableFreshSnapshotEvaluated.value = false
            mutableProjection.value = null
            releaseSession()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
        else mainHandler.post(cleanup)
    }

    private fun MediaSessionMetadata.toPlatformMetadata(artwork: Bitmap?): MediaMetadata =
        MediaMetadata.Builder().apply {
            putString(MediaMetadata.METADATA_KEY_MEDIA_ID, mediaId)
            title?.let { putString(MediaMetadata.METADATA_KEY_TITLE, it) }
            artist?.let { putString(MediaMetadata.METADATA_KEY_ARTIST, it) }
            album?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
            durationMillis?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
            artwork?.let {
                putBitmap(MediaMetadata.METADATA_KEY_ART, it)
                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
            }
        }.build()

    private fun MediaSessionProjection.Result.toPlatformPlaybackState(): PlaybackState {
        var platformActions = 0L
        if (actions.pause) platformActions = platformActions or PlaybackState.ACTION_PAUSE
        if (actions.resume) platformActions = platformActions or PlaybackState.ACTION_PLAY
        if (actions.previous) platformActions = platformActions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (actions.next) platformActions = platformActions or PlaybackState.ACTION_SKIP_TO_NEXT

        val (stateCode, speed) = when (playbackState) {
            MediaSessionPlaybackState.PLAYING -> PlaybackState.STATE_PLAYING to 1f
            MediaSessionPlaybackState.PAUSED -> PlaybackState.STATE_PAUSED to 0f
            MediaSessionPlaybackState.NONE -> PlaybackState.STATE_NONE to 0f
        }
        return PlaybackState.Builder()
            .setActions(platformActions)
            .setState(
                stateCode,
                positionMillis ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                speed,
            )
            .build()
    }

    private companion object {
        const val SESSION_TAG = "MPD-Server Remote"
    }
}
