package io.github.qyxx2.mpdserver.remote.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/** Opens the most specific OS notification settings page, with safe platform fallbacks. */
object MediaNotificationSettings {
    fun open(context: Context): Boolean {
        val packageName = context.packageName
        val intents = listOf(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, MediaNotificationFactory.CHANNEL_ID)
            },
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            },
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:$packageName".toUri(),
            ),
        )
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // Try the next system-owned settings entry point.
            } catch (_: SecurityException) {
                // A restricted device may block a specific settings action.
            }
        }
        return false
    }
}
