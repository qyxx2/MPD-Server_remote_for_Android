package io.github.qyxx2.mpdserver.remote.media

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.session.MediaSession
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaNotificationFactoryDeviceTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun mediaChannelUsesStableLowImportanceAndSilentConfigurationIdempotently() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(MediaNotificationFactory.CHANNEL_ID)
        val first = MediaNotificationFactory.ensureChannel(context)
        assertEquals(MediaNotificationFactory.CHANNEL_ID, first.id)
        if (existing == null) {
            assertEquals(NotificationManager.IMPORTANCE_LOW, first.importance)
            assertNull(first.sound)
            assertFalse(first.shouldVibrate())
        }

        val second = MediaNotificationFactory.ensureChannel(context)
        assertEquals(first.id, second.id)
        // Existing channel settings belong to the user: repeated creation never changes them,
        // including IMPORTANCE_NONE when the user has blocked this channel in Android Settings.
        assertEquals(first.importance, second.importance)
        assertEquals(first.sound, second.sound)
        assertEquals(first.shouldVibrate(), second.shouldVibrate())
        assertEquals(
            MediaNotificationFactory.CHANNEL_ID,
            manager.getNotificationChannel(MediaNotificationFactory.CHANNEL_ID)?.id,
        )
    }

    @Test
    fun mediaStyleNotificationUsesTheExistingNativeSessionTokenAndLauncherIntent() {
        val session = MediaSession(context, "batch2-notification-test")
        try {
            session.setActive(true)
            val metadata = MediaSessionMetadata(
                mediaId = "queue-occurrence-1",
                title = "Confirmed title",
                artist = "Confirmed artist",
                album = "Confirmed album",
                durationMillis = 180_000L,
            )
            val identity = ConfirmedMediaSessionIdentity(
                originAddress = "http://music.example:18000",
                connectionGeneration = 4L,
                epoch = "epoch-4",
                libraryRevision = 17L,
                explicitStopGeneration = 2L,
                queueItemId = "queue-occurrence-1",
                songId = "song-1",
                artworkId = "artwork-1",
                metadata = metadata,
            )
            val projection = MediaSessionProjection.Result(
                disposition = MediaSessionProjection.Disposition.CONFIRMED,
                metadata = metadata,
                playbackState = MediaSessionPlaybackState.PLAYING,
                positionMillis = 12_000L,
                actions = MediaSessionCommandAvailability(pause = true, next = true),
                confirmedIdentity = identity,
            )

            val notification = MediaNotificationFactory.build(
                context = context,
                projection = projection,
                sessionToken = session.sessionToken,
                artwork = null,
            )

            assertEquals(Notification.CATEGORY_TRANSPORT, notification.category)
            assertEquals("Confirmed title", notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("Confirmed artist", notification.extras.getString(Notification.EXTRA_TEXT))
            assertEquals("Confirmed album", notification.extras.getString(Notification.EXTRA_SUB_TEXT))
            assertNotNull(notification.smallIcon)
            assertNotNull(notification.getLargeIcon())
            assertNotNull(notification.contentIntent)
            assertEquals(context.packageName, notification.contentIntent?.creatorPackage)
            val token = if (Build.VERSION.SDK_INT >= 33) {
                notification.extras.getParcelable(
                    Notification.EXTRA_MEDIA_SESSION,
                    MediaSession.Token::class.java,
                )
            } else {
                @Suppress("DEPRECATION")
                notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            }
            assertEquals(session.sessionToken, token)
            // Batch 2 publishes the native session token; callback-backed action buttons arrive in Batch 3.
            assertEquals(0, notification.actions?.size ?: 0)
        } finally {
            session.setActive(false)
            session.release()
        }
    }

    @Test
    fun mediaStyleNotificationCanBeRegisteredWithThePlatformWhenPermissionIsDenied() {
        val manager = context.getSystemService(NotificationManager::class.java)
        MediaNotificationFactory.ensureChannel(context)
        val session = MediaSession(context, "batch2-media-notification-post-test")
        val testNotificationId = MediaNotificationFactory.NOTIFICATION_ID + 100
        try {
            session.setActive(true)
            val metadata = MediaSessionMetadata(
                mediaId = "test-occurrence",
                title = "Notification registration test",
                artist = "Test artist",
                album = null,
                durationMillis = 30_000L,
            )
            val identity = ConfirmedMediaSessionIdentity(
                originAddress = "http://test.invalid",
                connectionGeneration = 1L,
                epoch = "test-epoch",
                libraryRevision = 1L,
                explicitStopGeneration = 0L,
                queueItemId = "test-occurrence",
                songId = "test-song",
                artworkId = null,
                metadata = metadata,
            )
            val projection = MediaSessionProjection.Result(
                disposition = MediaSessionProjection.Disposition.CONFIRMED,
                metadata = metadata,
                playbackState = MediaSessionPlaybackState.PLAYING,
                positionMillis = 0L,
                actions = MediaSessionCommandAvailability(pause = true),
                confirmedIdentity = identity,
            )
            val notification = MediaNotificationFactory.build(
                context, projection, session.sessionToken, null,
            )
            manager.notify(testNotificationId, notification)
            assertNotNull(
                "MediaStyle notifications with a live MediaSession token should be registered; " +
                    "ordinary notification permission is reported separately",
                manager.activeNotifications.firstOrNull { it.id == testNotificationId },
            )
        } finally {
            manager.cancel(testNotificationId)
            session.setActive(false)
            session.release()
        }
    }

    @Test
    fun availabilityReadsRealPlatformPermissionAndDoesNotRequestIt() {
        MediaNotificationFactory.ensureChannel(context)
        val result = MediaNotificationFactory.readAvailability(context)
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            assertEquals(
                if (granted) NotificationPermissionStatus.GRANTED else NotificationPermissionStatus.DENIED,
                result.permission,
            )
        } else {
            assertEquals(NotificationPermissionStatus.NOT_REQUIRED, result.permission)
        }
    }
}
