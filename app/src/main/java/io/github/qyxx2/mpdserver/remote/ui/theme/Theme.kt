package io.github.qyxx2.mpdserver.remote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun MpdServerRemoteTheme(
    themePreference: ThemePreference,
    content: @Composable () -> Unit,
) {
    val darkTheme = resolveDarkTheme(
        preference = themePreference,
        systemIsDark = isSystemInDarkTheme(),
    )
    MaterialTheme(
        colorScheme = appColorScheme(darkTheme),
        content = content,
    )
}
