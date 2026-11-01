package io.github.qyxx2.mpdserver.remote.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import io.github.qyxx2.mpdserver.remote.runtime.AppRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * Process-scoped adapter from the one canonical AppRuntime into Android's native MediaSession.
 *
 * It owns no playback state, network client, polling loop, notification, or Activity reference.
 * System actions are only advertised here; callback dispatch is integrated in the later lifecycle
 * batch and will use the same Runtime PlayerCommandGateway.
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
                lastApplied = projection
                releaseSession()
            }

            MediaSessionProjection.Disposition.CONFIRMED -> {
                lastConfirmed = projection.confirmedIdentity
                publish(projection)
            }

            MediaSessionProjection.Disposition.RETAINED -> {
                // Retained metadata is only valid for a session previously published by this
                // process. A newly created process never revives a persisted/last-known track.
                if (session == null) {
                    lastConfirmed = null
                    lastApplied = null
                } else {
                    publish(projection)
                }
            }
        }
    }

    private fun publish(projection: MediaSessionProjection.Result) {
        if (lastApplied == projection && session?.isActive == true) return
        val metadata = projection.metadata ?: return
        val target = session ?: MediaSession(appContext, SESSION_TAG).also {
            it.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            session = it
        }

        if (lastApplied?.metadata != metadata || !target.isActive) {
            target.setMetadata(metadata.toPlatformMetadata())
        }
        if (lastApplied != projection || !target.isActive) {
            target.setPlaybackState(projection.toPlatformPlaybackState())
        }
        if (!target.isActive) target.setActive(true)
        lastApplied = projection
    }

    private fun releaseSession() {
        val old = session ?: return
        session = null
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
            releaseSession()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
        else mainHandler.post(cleanup)
    }

    private fun MediaSessionMetadata.toPlatformMetadata(): MediaMetadata =
        MediaMetadata.Builder().apply {
            putString(MediaMetadata.METADATA_KEY_MEDIA_ID, mediaId)
            title?.let { putString(MediaMetadata.METADATA_KEY_TITLE, it) }
            artist?.let { putString(MediaMetadata.METADATA_KEY_ARTIST, it) }
            album?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
            durationMillis?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
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
