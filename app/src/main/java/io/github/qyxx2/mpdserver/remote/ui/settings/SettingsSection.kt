package io.github.qyxx2.mpdserver.remote.ui.settings

/** Stable child routes for the Settings section; the root `settings` route remains a root tab. */
enum class SettingsSection(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: SettingsCategoryGlyph,
) {
    STATUS("settings/status", "状态", "连接状态、服务同步与操作活动", SettingsCategoryGlyph.STATUS),
    SERVER("settings/server", "服务器", "配置 MPD-Server 地址", SettingsCategoryGlyph.SERVER),
    APPEARANCE("settings/appearance", "外观", "主题与状态环显示", SettingsCategoryGlyph.APPEARANCE),
    ABOUT("settings/about", "关于", "应用信息、版本与开源项目", SettingsCategoryGlyph.ABOUT);

    companion object {
        fun fromRoute(route: String?): SettingsSection? = entries.firstOrNull { it.route == route }
    }
}

enum class SettingsCategoryGlyph { STATUS, SERVER, APPEARANCE, ABOUT }
