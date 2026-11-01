package io.github.qyxx2.mpdserver.remote.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class OutputMode { NAS_DAC, CLIENT_STREAM }

@Serializable
enum class OutputState { UNAVAILABLE, INACTIVE, ACTIVE }

@Serializable
data class OutputRequestDto(val mode: OutputMode, val enabled: Boolean)

@Serializable
data class SystemOutputDto(
    val states: Map<String, OutputState>,
    @SerialName("last_request") val lastRequest: JsonElement?,
)

/** Read-only projection of GET /api/system/mpd. Missing values remain unknown. */
@Serializable
data class MpdStatusDto(
    val version: String? = null,
    @SerialName("version_source") val versionSource: String? = null,
    val stats: MpdStatsDto? = null,
    @SerialName("database_update_status")
    val databaseUpdateStatus: MpdDatabaseUpdateStatusDto? = null,
    val connected: Boolean? = null,
    val errors: Map<String, JsonElement>? = null,
    @SerialName("observed_at") val observedAt: String? = null,
)

@Serializable
data class MpdStatsDto(
    val artists: Long? = null,
    val albums: Long? = null,
    val songs: Long? = null,
    val uptime: Double? = null,
    @SerialName("db_playtime") val dbPlaytime: Double? = null,
    @SerialName("db_update") val dbUpdate: Long? = null,
    val playtime: Double? = null,
)

@Serializable
data class MpdDatabaseUpdateStatusDto(
    val updating: Boolean? = null,
    @SerialName("job_id") val jobId: JsonElement? = null,
)
