package io.github.qyxx2.mpdserver.remote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.ui.preferences.UiPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ServerSettingsScreen(
    preferencesRepository: UiPreferencesRepository,
    onApplyServerOrigin: suspend (ServerOrigin) -> Unit,
) {
    val savedOrigin by preferencesRepository.serverOrigin.collectAsStateWithLifecycle(
        initialValue = ServerOrigin.DEFAULT_ADDRESS,
    )
    var addressDraft by remember { mutableStateOf(ServerOrigin.DEFAULT_ADDRESS) }
    var hasEdited by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val screenScope = rememberCoroutineScope()

    LaunchedEffect(savedOrigin) {
        if (!hasEdited) addressDraft = savedOrigin
    }

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
                            // The repository and application continue using the existing preference
                            // and single application Runtime; this action does not probe reachability.
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
    }
}
