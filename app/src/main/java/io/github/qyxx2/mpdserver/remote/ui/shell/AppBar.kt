package io.github.qyxx2.mpdserver.remote.ui.shell

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.qyxx2.mpdserver.remote.ui.components.ActivityPresentation
import io.github.qyxx2.mpdserver.remote.ui.components.PlaybackStatusRing

/** Compact shared app bar with a read-only projection of the app-scoped runtime status. */
@Composable
fun AppBar(
    title: String,
    statusPresentation: ActivityPresentation,
    onStatusClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp).testTag("app-bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.testTag("app-bar-title"),
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.weight(1f))
        PlaybackStatusRing(presentation = statusPresentation, onClick = onStatusClick, modifier = Modifier.size(48.dp))
    }
}
