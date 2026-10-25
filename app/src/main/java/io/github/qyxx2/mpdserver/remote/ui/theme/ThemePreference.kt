package io.github.qyxx2.mpdserver.remote.ui.theme

/** Local UI appearance preference; it is not server-owned business state. */
enum class ThemePreference {
    FOLLOW_SYSTEM,
    LIGHT,
    DARK,
}

internal fun resolveDarkTheme(
    preference: ThemePreference,
    systemIsDark: Boolean,
): Boolean = when (preference) {
    ThemePreference.FOLLOW_SYSTEM -> systemIsDark
    ThemePreference.LIGHT -> false
    ThemePreference.DARK -> true
}
