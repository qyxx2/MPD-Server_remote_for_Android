package io.github.qyxx2.mpdserver.remote.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.qyxx2.mpdserver.remote.ui.components.ActivityPresentation
import io.github.qyxx2.mpdserver.remote.ui.components.ActivityStatusContent

/** Full-page view over the one canonical Runtime activity/status projection. */
@Composable
fun StatusSettingsScreen(
    presentation: ActivityPresentation,
    onRetryUnknown: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("status-settings-screen"),
    ) {
        ActivityStatusContent(
            presentation = presentation,
            onRetryUnknown = onRetryUnknown,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
