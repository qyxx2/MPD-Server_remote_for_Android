package io.github.qyxx2.mpdserver.remote.ui.settings

import io.github.qyxx2.mpdserver.remote.protocol.dto.MpdStatusDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val mpdLocalDateTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** UI-only projection. Connection here is strictly MS-server-to-MPD, not Android-to-MS. */
data class MpdStatusPresentation(
    val version: String,
    val versionSource: String?,
    val connectionLabel: String,
    val artists: String,
    val albums: String,
    val songs: String,
    val uptime: String,
    val databasePlaytime: String,
    val accumulatedPlaytime: String,
    val databaseUpdatedAt: String,
    val databaseUpdateLabel: String,
    val jobId: String?,
    val observedAt: String,
    val errors: List<String>?,
) {
    companion object {
        fun from(dto: MpdStatusDto): MpdStatusPresentation = MpdStatusPresentation(
            version = dto.version?.takeIf(String::isNotBlank) ?: "未知",
            versionSource = dto.versionSource?.takeIf(String::isNotBlank),
            connectionLabel = when (dto.connected) {
                true -> "已连接"
                false -> "未连接"
                null -> "未知"
            },
            artists = dto.stats?.artists?.toString() ?: "未知",
            albums = dto.stats?.albums?.toString() ?: "未知",
            songs = dto.stats?.songs?.toString() ?: "未知",
            uptime = formatDurationSeconds(dto.stats?.uptime),
            databasePlaytime = formatDurationSeconds(dto.stats?.dbPlaytime),
            accumulatedPlaytime = formatDurationSeconds(dto.stats?.playtime),
            databaseUpdatedAt = formatEpochSeconds(dto.stats?.dbUpdate),
            databaseUpdateLabel = when (dto.databaseUpdateStatus?.updating) {
                true -> "正在更新"
                false -> "未在更新"
                null -> "未知"
            },
            jobId = dto.databaseUpdateStatus?.jobId?.takeUnless { it == JsonNull }?.let { value ->
                if (value is JsonPrimitive && value.isString) value.content else value.toString()
            },
            observedAt = formatObservedAt(dto.observedAt),
            errors = dto.errors?.toSortedMap()?.map { (key, value) -> "$key: $value" },
        )
    }
}

/** Floors fractional seconds; null, negative, non-finite and unrepresentable values remain unknown. */
fun formatDurationSeconds(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds < 0.0 ||
        seconds >= Long.MAX_VALUE.toDouble()
    ) return "未知"

    var remaining = kotlin.math.floor(seconds).toLong()
    val days = remaining / 86_400
    remaining %= 86_400
    val hours = remaining / 3_600
    remaining %= 3_600
    val minutes = remaining / 60
    val wholeSeconds = remaining % 60
    return "${days}天 ${hours}小时 ${minutes}分 ${wholeSeconds}秒"
}

/** Formats the endpoint's Unix-epoch-seconds database timestamp as local date/time. */
fun formatEpochSeconds(seconds: Long?): String {
    if (seconds == null) return "未知"
    return runCatching {
        Instant.ofEpochSecond(seconds)
            .atZone(ZoneId.systemDefault())
            .format(mpdLocalDateTimeFormatter)
    }.getOrDefault("未知")
}

/** Formats ISO-8601 observation time locally; invalid input is preserved for diagnosis. */
fun formatObservedAt(value: String?): String {
    if (value.isNullOrBlank()) return "未知"
    return runCatching {
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(mpdLocalDateTimeFormatter)
    }.getOrElse { value }
}
