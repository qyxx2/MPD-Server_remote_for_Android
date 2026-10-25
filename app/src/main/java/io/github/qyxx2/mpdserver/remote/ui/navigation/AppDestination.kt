package io.github.qyxx2.mpdserver.remote.ui.navigation

/** Routes owned by the native app shell. The full-screen player is a destination, not a root tab. */
enum class AppDestination(
    val route: String,
    val label: String,
) {
    HOME(route = "home", label = "主页"),
    SEARCH(route = "search", label = "搜索"),
    LIBRARY(route = "library", label = "音乐库"),
    SETTINGS(route = "settings", label = "设置"),
    FULL_SCREEN_PLAYER(route = "full-screen-player", label = "正在播放");

    companion object {
        /** Frozen left-to-right root navigation order. Do not include the full-screen player here. */
        val rootDestinations: List<AppDestination> = listOf(HOME, SEARCH, LIBRARY, SETTINGS)
    }
}
