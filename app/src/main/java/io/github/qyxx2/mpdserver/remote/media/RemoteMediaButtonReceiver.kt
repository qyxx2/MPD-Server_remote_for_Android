package io.github.qyxx2.mpdserver.remote.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Build
import android.os.Parcelable
import android.view.KeyEvent

/**
 * Sends notification transport actions through the same native MediaSession callback used by
 * SystemUI and hardware media buttons. It owns no playback command or Runtime state.
 */
class RemoteMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return

        val event = intent.readParcelableExtraCompat<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return

        val token = intent.readParcelableExtraCompat<MediaSession.Token>(EXTRA_MEDIA_SESSION_TOKEN)
            ?: return

        runCatching {
            MediaController(context.applicationContext, token).dispatchMediaButtonEvent(event)
        }
    }

    companion object {
        const val EXTRA_MEDIA_SESSION_TOKEN =
            "io.github.qyxx2.mpdserver.remote.extra.MEDIA_SESSION_TOKEN"
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : Parcelable> Intent.readParcelableExtraCompat(name: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, T::class.java)
    else getParcelableExtra(name) as? T
