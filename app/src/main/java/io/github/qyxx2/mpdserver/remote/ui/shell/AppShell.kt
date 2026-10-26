package io.github.qyxx2.mpdserver.remote.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Shared root-page geometry. The body gets only the space between the app bar and fixed nav. */
@Composable
fun AppShell(
    title: String,
    modifier: Modifier = Modifier,
    miniPlayer: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        AppBar(title = title)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("root-content"),
            content = content,
        )
        miniPlayer()
        bottomBar()
    }
}
