package io.github.qyxx2.mpdserver.remote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.protocol.ApiResult
import io.github.qyxx2.mpdserver.remote.protocol.dto.MpdStatusDto
import io.github.qyxx2.mpdserver.remote.ui.preferences.UiPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class MpdStatusLoadState(
    val originAddress: String,
    val status: MpdStatusDto? = null,
    val loading: Boolean = false,
    val stale: Boolean = false,
    val error: String? = null,
)

@Composable
fun ServerSettingsScreen(
    preferencesRepository: UiPreferencesRepository,
    onApplyServerOrigin: suspend (ServerOrigin) -> Unit,
    readMpdStatus: suspend () -> ApiResult<MpdStatusDto>,
) {
    // Wait for the persisted value before reading MPD; the fallback must not trigger a
    // transient request to DEFAULT_ADDRESS when the user's configured origin is different.
    val savedOrigin by preferencesRepository.serverOrigin.collectAsStateWithLifecycle(
        initialValue = "",
    )
    var addressDraft by remember { mutableStateOf(ServerOrigin.DEFAULT_ADDRESS) }
    var hasEdited by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val screenScope = rememberCoroutineScope()

    var mpdLoadState by remember { mutableStateOf<MpdStatusLoadState?>(null) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    val latestSavedOrigin by rememberUpdatedState(savedOrigin)

    LaunchedEffect(savedOrigin, refreshGeneration) {
        if (savedOrigin.isBlank()) return@LaunchedEffect
        val requestOrigin = savedOrigin
        val previous = mpdLoadState?.takeIf {
            it.originAddress == requestOrigin && it.status != null
        }
        mpdLoadState = if (previous != null) {
            previous.copy(loading = true, stale = false, error = null)
        } else {
            MpdStatusLoadState(originAddress = requestOrigin, loading = true)
        }

        val result = try {
            readMpdStatus()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ApiResult.ProtocolError(failure.message ?: "读取 MPD 状态失败")
        }

        // Navigation and origin changes cancel this effect; this guard also protects against
        // repositories that complete synchronously while the preferences Flow is updating.
        if (latestSavedOrigin != requestOrigin) return@LaunchedEffect

        mpdLoadState = when (result) {
            is ApiResult.Success -> MpdStatusLoadState(
                originAddress = requestOrigin,
                status = result.value,
                loading = false,
                stale = false,
            )
            else -> {
                val message = result.messageForMpdStatus()
                if (previous?.status != null) {
                    previous.copy(loading = false, stale = true, error = message)
                } else {
                    MpdStatusLoadState(
                        originAddress = requestOrigin,
                        loading = false,
                        stale = false,
                        error = message,
                    )
                }
            }
        }
    }

    LaunchedEffect(savedOrigin) {
        if (savedOrigin.isNotBlank() && !hasEdited) addressDraft = savedOrigin
    }

    val currentOriginAddress = savedOrigin.takeIf(String::isNotBlank)
        ?: ServerOrigin.DEFAULT_ADDRESS
    val visibleMpdState = mpdLoadState?.takeIf { it.originAddress == currentOriginAddress }
        ?: MpdStatusLoadState(originAddress = currentOriginAddress, loading = true)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp).testTag("server-settings-screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "MPD-Server 地址",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("server-origin-settings-title"),
        )
        OutlinedTextField(
            value = addressDraft,
            onValueChange = {
                addressDraft = it
                hasEdited = true
                error = null
                status = null
            },
            modifier = Modifier.fillMaxWidth().testTag("server-origin-input"),
            label = { Text("服务器地址") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            trailingIcon = {
                IconButton(
                    onClick = { showHelp = !showHelp },
                    modifier = Modifier.testTag("server-origin-help-toggle"),
                ) {
                    Text("!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            },
            isError = error != null,
        )
        when {
            error != null -> Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("server-origin-supporting-text"),
            )
            showHelp -> Text(
                "支持 IPv4、IPv6 和域名；可输入完整 http(s):// 地址。省略协议使用 HTTP，省略端口默认 18000；IPv6 自定义端口请写成 [地址]:端口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("server-origin-supporting-text"),
            )
        }
        Button(
            onClick = {
                val parsed = runCatching { ServerOrigin.parseAddress(addressDraft) }
                    .getOrElse {
                        error = "地址格式不正确：请检查主机、端口、协议和路径。"
                        status = null
                        null
                    }
                if (parsed != null) {
                    error = null
                    status = "正在保存并应用服务器地址…"
                    saving = true
                    screenScope.launch {
                        try {
                            // Preserve the single Application-owned preference and Runtime flow.
                            // Saving an origin does not probe reachability or alter MPD connection.
                            onApplyServerOrigin(parsed)
                            addressDraft = parsed.address
                            hasEdited = false
                            status = "已保存并应用；尚未检测服务器连通性"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            status = "保存失败：${failure.message ?: "请重试"}"
                        } finally {
                            saving = false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("server-origin-save"),
            enabled = !saving,
        ) {
            Text(if (saving) "正在保存…" else "保存")
        }
        status?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("server-origin-status"))
        }

        Card(
            modifier = Modifier.fillMaxWidth().testTag("mpd-status-panel"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("MPD 状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Button(
                        onClick = { refreshGeneration += 1 },
                        modifier = Modifier.testTag("mpd-status-refresh"),
                    ) {
                        Text("刷新")
                    }
                }

                val current = visibleMpdState
                if (current.loading && current.status == null) {
                    Text("正在读取 MPD 状态…", modifier = Modifier.testTag("mpd-status-loading"))
                }
                if (current.stale) {
                    Text(
                        "数据已过期",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("mpd-status-stale"),
                    )
                } else if (current.loading && current.status != null) {
                    Text("正在刷新…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                current.error?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("mpd-status-error"),
                    )
                }

                val dto = current.status
                if (dto == null) {
                    if (!current.loading) {
                        Text("MPD 状态暂不可用", modifier = Modifier.testTag("mpd-status-unavailable"))
                    }
                } else {
                    val presentation = MpdStatusPresentation.from(dto)
                    MpdStatusRow("MPD 版本", presentation.version, "mpd-status-version")
                    MpdStatusRow("MPD 连接（MS 服务端 → MPD）", presentation.connectionLabel, "mpd-status-connection")
                    if (presentation.versionSource != null) {
                        MpdStatusRow("版本来源", presentation.versionSource)
                    }
                    MpdStatusRow("艺术家", presentation.artists)
                    MpdStatusRow("专辑", presentation.albums)
                    MpdStatusRow("歌曲", presentation.songs)
                    MpdStatusRow("MPD 运行时间", presentation.uptime)
                    MpdStatusRow("曲库总时长", presentation.databasePlaytime)
                    MpdStatusRow("累计播放时长", presentation.accumulatedPlaytime)
                    MpdStatusRow("曲库更新时间", presentation.databaseUpdatedAt)
                    MpdStatusRow("数据库更新", presentation.databaseUpdateLabel, "mpd-status-update-state")
                    presentation.jobId?.let { MpdStatusRow("更新任务", it) }
                    MpdStatusRow("状态观测时间", presentation.observedAt)
                    Text("MPD 错误", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    when {
                        presentation.errors == null -> Text("未知")
                        presentation.errors.isEmpty() -> Text("无服务端报告错误")
                        else -> presentation.errors.forEach { errorEntry ->
                            Text(
                                errorEntry,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("mpd-status-error-entry"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MpdStatusRow(label: String, value: String, valueTag: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = if (valueTag == null) Modifier else Modifier.testTag(valueTag))
    }
}

private fun ApiResult<MpdStatusDto>.messageForMpdStatus(): String = when (this) {
    is ApiResult.BusinessError -> error.message
    is ApiResult.HttpError -> message
    is ApiResult.TransportError -> failure.cause.message ?: "网络请求失败"
    is ApiResult.DecodeError -> message
    is ApiResult.ProtocolError -> message
    ApiResult.NoContent -> "服务器未返回 MPD 状态"
    is ApiResult.Accepted -> "MPD 状态读取返回了非预期的异步响应"
    is ApiResult.Success -> "MPD 状态读取失败"
}
