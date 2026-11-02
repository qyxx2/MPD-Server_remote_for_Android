package io.github.qyxx2.mpdserver.remote.media

/** Runtime permission state is deliberately separate from native MediaSession availability. */
enum class NotificationPermissionStatus { NOT_REQUIRED, GRANTED, DENIED }

/** OS notification settings only; this model contains no playback or track state. */
data class MediaControlAvailability(
    val permission: NotificationPermissionStatus,
    val appNotificationsEnabled: Boolean,
    val channelImportance: Int,
    val publicationFailure: String? = null,
    val serviceStartFailure: String? = null,
) {
    val channelEnabled: Boolean
        get() = channelImportance > IMPORTANCE_NONE

    /** Whether an ordinary app notification is permitted, not whether a MediaSession exists. */
    val ordinaryNotificationsAllowed: Boolean
        get() = permission != NotificationPermissionStatus.DENIED &&
            appNotificationsEnabled && channelEnabled

    val openSettingsLabel: String
        get() = "打开媒体通知设置"

    private val notificationStatusMessage: String
        get() = when {
            publicationFailure != null ->
                "系统拒绝发布媒体通知：$publicationFailure。系统媒体会话状态由 Android 独立管理。"
            !channelEnabled ->
                "媒体通知渠道已在系统设置中关闭。应用不会创建新渠道绕过这一选择；系统媒体会话仍由 Android 独立管理。"
            permission == NotificationPermissionStatus.DENIED ->
                "通知权限未授予。Android 可能仍会独立显示 MediaSession 媒体卡片，普通通知会受到限制。"
            !appNotificationsEnabled ->
                "应用通知已在系统设置中关闭。系统媒体会话是否显示由 Android 独立管理。"
            else ->
                "媒体通知权限与渠道可用。锁屏卡片和按钮的最终呈现由 Android 系统决定。"
        }

    val statusMessage: String
        get() = buildString {
            serviceStartFailure?.let {
                append("后台系统媒体控制服务无法启动：$it。请将应用恢复到前台后重试。")
                append('\n')
            }
            append(notificationStatusMessage)
        }

    companion object {
        const val IMPORTANCE_NONE = 0

        fun fromPlatform(
            apiLevel: Int,
            hasPostNotificationsPermission: Boolean,
            appNotificationsEnabled: Boolean,
            channelImportance: Int,
            publicationFailure: String? = null,
            serviceStartFailure: String? = null,
        ): MediaControlAvailability {
            val permission = when {
                apiLevel < 33 -> NotificationPermissionStatus.NOT_REQUIRED
                hasPostNotificationsPermission -> NotificationPermissionStatus.GRANTED
                else -> NotificationPermissionStatus.DENIED
            }
            return MediaControlAvailability(
                permission = permission,
                appNotificationsEnabled = appNotificationsEnabled,
                channelImportance = channelImportance,
                publicationFailure = publicationFailure,
                serviceStartFailure = serviceStartFailure,
            )
        }
    }
}
