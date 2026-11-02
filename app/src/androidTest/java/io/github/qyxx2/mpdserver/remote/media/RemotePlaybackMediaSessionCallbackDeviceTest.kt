package io.github.qyxx2.mpdserver.remote.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.qyxx2.mpdserver.remote.runtime.PlayerAction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemotePlaybackMediaSessionCallbackDeviceTest {
    @Test
    fun dispatchesOnlyTheFourContractedPlayerActions() {
        val dispatched = mutableListOf<PlayerAction>()
        val callback = RemotePlaybackMediaSessionCallback(dispatched::add)

        callback.onPlay()
        callback.onPause()
        callback.onSkipToPrevious()
        callback.onSkipToNext()

        callback.onStop()
        callback.onSeekTo(30_000L)
        callback.onFastForward()
        callback.onRewind()

        assertEquals(
            listOf(
                PlayerAction.RESUME,
                PlayerAction.PAUSE,
                PlayerAction.PREVIOUS,
                PlayerAction.NEXT,
            ),
            dispatched,
        )
    }
}
