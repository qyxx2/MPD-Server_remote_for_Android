package io.github.qyxx2.mpdserver.remote.media

import android.media.session.MediaSession
import io.github.qyxx2.mpdserver.remote.runtime.PlayerAction

/**
 * Translates only the contracted Android transport callbacks into the shared Runtime vocabulary.
 *
 * The injected dispatcher is AppRuntime.playerCommands.dispatch; this adapter deliberately has no
 * snapshot, network, mutation, queue, seek, or retry logic of its own.
 */
class RemotePlaybackMediaSessionCallback(
    private val dispatch: (PlayerAction) -> Unit,
) : MediaSession.Callback() {
    override fun onPlay() = dispatch(PlayerAction.RESUME)

    override fun onPause() = dispatch(PlayerAction.PAUSE)

    override fun onSkipToPrevious() = dispatch(PlayerAction.PREVIOUS)

    override fun onSkipToNext() = dispatch(PlayerAction.NEXT)
}
