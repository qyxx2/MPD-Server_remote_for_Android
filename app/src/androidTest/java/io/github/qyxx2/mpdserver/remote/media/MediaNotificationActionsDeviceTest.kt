package io.github.qyxx2.mpdserver.remote.media

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MediaNotificationActionsDeviceTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun notificationPublishesCompactActionsMatchingCanonicalAvailability() {
        val session = MediaSession(context, "MediaNotificationActionsDeviceTest")
        try {
            val metadata = MediaSessionMetadata(
                mediaId = "queue-occurrence-1",
                title = "Test Track",
                artist = "Test Artist",
                album = "Test Album",
                durationMillis = 180_000L,
            )
            val identity = identity(metadata, "queue-occurrence-1", "song-1")
            val projection = MediaSessionProjection.Result(
                disposition = MediaSessionProjection.Disposition.CONFIRMED,
                metadata = metadata,
                playbackState = MediaSessionPlaybackState.PLAYING,
                positionMillis = 12_000L,
                actions = MediaSessionCommandAvailability(
                    pause = true,
                    resume = false,
                    previous = true,
                    next = true,
                ),
                confirmedIdentity = identity,
            )

            val notification = MediaNotificationFactory.build(
                context = context,
                projection = projection,
                sessionToken = session.sessionToken,
                artwork = null,
            )

            assertEquals(
                listOf("上一首", "暂停", "下一首"),
                notification.actions?.map { it.title.toString() }.orEmpty(),
            )
            assertEquals(
                listOf(0, 1, 2),
                notification.extras.getIntArray("android.compactActions")?.toList().orEmpty(),
            )
            assertEquals(
                "android.app.Notification\$MediaStyle",
                notification.extras.getString("android.template"),
            )
            assertEquals(session.sessionToken, notification.extras.getParcelable("android.mediaSession"))
        } finally {
            session.release()
        }
    }

    @Test
    fun unavailableTransportActionsAreNotAdvertised() {
        val session = MediaSession(context, "MediaNotificationActionsDeviceTestUnavailable")
        try {
            val metadata = MediaSessionMetadata(
                mediaId = "queue-occurrence-2",
                title = "Stale Track",
                artist = "Test Artist",
                album = "Test Album",
                durationMillis = null,
            )
            val projection = MediaSessionProjection.Result(
                disposition = MediaSessionProjection.Disposition.RETAINED,
                metadata = metadata,
                playbackState = MediaSessionPlaybackState.NONE,
                positionMillis = null,
                actions = MediaSessionCommandAvailability.NONE,
                confirmedIdentity = identity(metadata, "queue-occurrence-2", "song-2"),
            )

            val notification = MediaNotificationFactory.build(
                context = context,
                projection = projection,
                sessionToken = session.sessionToken,
                artwork = null,
            )

            assertEquals(0, notification.actions?.size ?: 0)
        } finally {
            session.release()
        }
    }

    @Test
    fun notificationActionReceiverDispatchesThroughNativeMediaSessionCallback() {
        val session = MediaSession(context, "MediaNotificationActionReceiverDeviceTest")
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val callbacks = CountDownLatch(4)
        try {
            session.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            session.setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = record(KeyEvent.KEYCODE_MEDIA_PLAY)
                    override fun onPause() = record(KeyEvent.KEYCODE_MEDIA_PAUSE)
                    override fun onSkipToPrevious() = record(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    override fun onSkipToNext() = record(KeyEvent.KEYCODE_MEDIA_NEXT)

                    private fun record(keyCode: Int) {
                        received.add(keyCode)
                        callbacks.countDown()
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT,
                    )
                    .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
                    .build(),
            )
            session.isActive = true

            val receiver = RemoteMediaButtonReceiver()
            listOf(
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_NEXT,
            ).forEach { keyCode ->
                receiver.onReceive(
                    context,
                    Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                        putExtra(
                            Intent.EXTRA_KEY_EVENT,
                            KeyEvent(KeyEvent.ACTION_DOWN, keyCode),
                        )
                        putExtra(
                            RemoteMediaButtonReceiver.EXTRA_MEDIA_SESSION_TOKEN,
                            session.sessionToken,
                        )
                    },
                )
            }

            assertTrue("MediaSession callback was not invoked for all transport actions", callbacks.await(3, TimeUnit.SECONDS))
            assertEquals(
                setOf(
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                ),
                received.toSet(),
            )
        } finally {
            session.release()
        }
    }

    private fun identity(
        metadata: MediaSessionMetadata,
        queueItemId: String,
        songId: String,
    ) = ConfirmedMediaSessionIdentity(
        originAddress = "http://music.example:18000",
        connectionGeneration = 1L,
        epoch = "epoch-1",
        libraryRevision = 1L,
        explicitStopGeneration = 0L,
        queueItemId = queueItemId,
        songId = songId,
        artworkId = null,
        metadata = metadata,
    )
}
