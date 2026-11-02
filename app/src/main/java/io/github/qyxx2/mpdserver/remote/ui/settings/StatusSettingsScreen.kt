package io.github.qyxx2.mpdserver.remote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.qyxx2.mpdserver.remote.media.MediaControlAvailability
import io.github.qyxx2.mpdserver.remote.media.MediaNotificationSettings
import io.github.qyxx2.mpdserver.remote.ui.components.ActivityPresentation
import io.github.qyxx2.mpdserver.remote.ui.components.ActivityStatusContent

/** Full-page view over the one canonical Runtime activity/status projection. */
@Composable
fun StatusSettingsScreen(
    presentation: ActivityPresentation,
    mediaControlAvailability: MediaControlAvailability,
    onRetryUnknown: (String) -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("status-settings-screen"),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().testTag("media-control-availability"),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("系统媒体通知", style = MaterialTheme.typography.titleMedium)
                Text(
                    mediaControlAvailability.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("media-control-availability-message"),
                )
                OutlinedButton(
                    onClick = { MediaNotificationSettings.open(context) },
                    modifier = Modifier.testTag("media-control-open-system-settings"),
                ) {
                    Text(mediaControlAvailability.openSettingsLabel)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        ActivityStatusContent(
            presentation = presentation,
            onRetryUnknown = onRetryUnknown,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
