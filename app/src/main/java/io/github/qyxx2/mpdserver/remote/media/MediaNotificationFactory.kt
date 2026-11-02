package io.github.qyxx2.mpdserver.remote.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.Build
import android.view.KeyEvent
import io.github.qyxx2.mpdserver.remote.R

/** Native-API notification factory. It binds the existing session token; it owns no playback actions. */
object MediaNotificationFactory {
    const val CHANNEL_ID = "mpd_server_remote_media"
    const val NOTIFICATION_ID = 7317
    private const val CONTENT_REQUEST_CODE = 7318
    private const val MEDIA_ACTION_REQUEST_CODE_BASE = 7400

    fun ensureChannel(context: Context): NotificationChannel {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.getNotificationChannel(CHANNEL_ID)?.let { return it }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "媒体播放",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示 MPD-Server 远端播放的媒体信息"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
        // Read back the platform-owned channel. This preserves any user choice made concurrently.
        return manager.getNotificationChannel(CHANNEL_ID) ?: channel
    }

    fun readAvailability(
        context: Context,
        publicationFailure: String? = null,
    ): MediaControlAvailability {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelImportance = manager.getNotificationChannel(CHANNEL_ID)?.importance
            ?: NotificationManager.IMPORTANCE_NONE
        val permissionGranted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        return MediaControlAvailability.fromPlatform(
            apiLevel = Build.VERSION.SDK_INT,
            hasPostNotificationsPermission = permissionGranted,
            appNotificationsEnabled = manager.areNotificationsEnabled(),
            channelImportance = channelImportance,
            publicationFailure = publicationFailure,
        )
    }

    fun build(
        context: Context,
        projection: MediaSessionProjection.Result,
        sessionToken: MediaSession.Token,
        artwork: Bitmap?,
    ): Notification {
        val metadata = requireNotNull(projection.metadata) {
            "A confirmed or retained MediaSession projection is required"
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: throw IllegalStateException("The app's existing launcher activity is unavailable")
        val contentIntent = PendingIntent.getActivity(
            context,
            CONTENT_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val appName = context.applicationInfo.loadLabel(context.packageManager).toString()
        val actions = buildTransportActions(context, projection, sessionToken)
        val mediaStyle = Notification.MediaStyle().setMediaSession(sessionToken)
        if (actions.isNotEmpty()) {
            mediaStyle.setShowActionsInCompactView(*actions.indices.toList().toIntArray())
        }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_media)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentTitle(metadata.title ?: appName)
            .setContentText(metadata.artist ?: metadata.album)
            .setSubText(metadata.album?.takeIf { metadata.artist != null && it != metadata.artist })
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setAutoCancel(false)
            .setOngoing(projection.playbackState == MediaSessionPlaybackState.PLAYING)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setStyle(mediaStyle)

        actions.forEach(builder::addAction)
        if (artwork != null) {
            builder.setLargeIcon(artwork)
        } else {
            // A stable app-brand placeholder is present before any artwork request completes.
            builder.setLargeIcon(Icon.createWithResource(context, R.drawable.ic_launcher))
        }
        return builder.build()
    }

    /**
     * Some vendor SystemUI versions only render transport controls from explicit notification
     * actions, even when the MediaStyle already carries a valid MediaSession token. Keep these
     * actions identical to the canonical session capabilities and route them back through the
     * MediaSession callback, rather than creating a second command path.
     */
    private fun buildTransportActions(
        context: Context,
        projection: MediaSessionProjection.Result,
        sessionToken: MediaSession.Token,
    ): List<Notification.Action> = buildList {
        if (projection.actions.previous) {
            add(
                transportAction(
                    context,
                    sessionToken,
                    title = "上一首",
                    icon = R.drawable.ic_media_previous,
                    keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                ),
            )
        }

        when {
            projection.actions.pause -> add(
                transportAction(
                    context,
                    sessionToken,
                    title = "暂停",
                    icon = R.drawable.ic_media_pause,
                    keyCode = KeyEvent.KEYCODE_MEDIA_PAUSE,
                ),
            )

            projection.actions.resume -> add(
                transportAction(
                    context,
                    sessionToken,
                    title = "播放",
                    icon = R.drawable.ic_media_play,
                    keyCode = KeyEvent.KEYCODE_MEDIA_PLAY,
                ),
            )
        }

        if (projection.actions.next) {
            add(
                transportAction(
                    context,
                    sessionToken,
                    title = "下一首",
                    icon = R.drawable.ic_media_next,
                    keyCode = KeyEvent.KEYCODE_MEDIA_NEXT,
                ),
            )
        }
    }

    private fun transportAction(
        context: Context,
        sessionToken: MediaSession.Token,
        title: String,
        icon: Int,
        keyCode: Int,
    ): Notification.Action {
        val requestCode = MEDIA_ACTION_REQUEST_CODE_BASE + keyCode
        val mediaButtonIntent = Intent(context, RemoteMediaButtonReceiver::class.java).apply {
            action = Intent.ACTION_MEDIA_BUTTON
            putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            putExtra(RemoteMediaButtonReceiver.EXTRA_MEDIA_SESSION_TOKEN, sessionToken)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            mediaButtonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(context, icon),
            title,
            pendingIntent,
        ).build()
    }
}
