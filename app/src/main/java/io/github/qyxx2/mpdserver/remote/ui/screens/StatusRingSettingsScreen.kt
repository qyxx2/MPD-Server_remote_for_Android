package io.github.qyxx2.mpdserver.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.network.ServerOrigin
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingSettingsPreview
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingWindowGeometry
import io.github.qyxx2.mpdserver.remote.ui.preferences.StatusRingPreferences
import io.github.qyxx2.mpdserver.remote.ui.preferences.UiPreferencesRepository
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun StatusRingSettingsScreen(
    preferencesRepository: UiPreferencesRepository,
    preferenceWriteScope: CoroutineScope,
    windowGeometry: StatusRingWindowGeometry,
    onApplyServerOrigin: suspend (ServerOrigin) -> Unit,
) {
    val savedServerOrigin by preferencesRepository.serverOrigin.collectAsStateWithLifecycle(
        initialValue = ServerOrigin.DEFAULT_ADDRESS,
    )
    var serverAddressDraft by remember { mutableStateOf(ServerOrigin.DEFAULT_ADDRESS) }
    var serverAddressEdited by remember { mutableStateOf(false) }
    var serverAddressError by remember { mutableStateOf<String?>(null) }
    var serverAddressStatus by remember { mutableStateOf<String?>(null) }
    var isSavingServerAddress by remember { mutableStateOf(false) }
    val screenScope = rememberCoroutineScope()
    LaunchedEffect(savedServerOrigin) {
        if (!serverAddressEdited) serverAddressDraft = savedServerOrigin
    }

    val themePreference by preferencesRepository.themePreference.collectAsStateWithLifecycle(
        initialValue = ThemePreference.FOLLOW_SYSTEM,
    )
    val savedPreferences by preferencesRepository.statusRingPreferences.collectAsStateWithLifecycle(
        initialValue = StatusRingPreferences.DEFAULT,
    )
    var draft by remember { mutableStateOf(StatusRingPreferences.DEFAULT) }
    LaunchedEffect(savedPreferences) {
        draft = savedPreferences.normalized()
    }
    fun persistDraft() {
        val snapshot = draft.normalized()
        preferenceWriteScope.launch { preferencesRepository.setStatusRingPreferences(snapshot) }
    }

    val previewDescription = "状态环预览；尺寸 ${formatScale(draft.sizeScale)}；线宽 ${formatLineWidth(draft.lineWidthDp)}；" +
        "水平偏移 ${formatOffset(draft.horizontalOffsetDp)}；垂直偏移 ${formatOffset(draft.verticalOffsetDp)}"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("status-ring-settings-content"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "MS 服务器",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("server-origin-settings-title"),
        )
        OutlinedTextField(
            value = serverAddressDraft,
            onValueChange = {
                serverAddressDraft = it
                serverAddressEdited = true
                serverAddressError = null
                serverAddressStatus = null
            },
            label = { Text("服务器地址") },
            placeholder = { Text("192.168.1.20:18000") },
            singleLine = true,
            isError = serverAddressError != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            supportingText = {
                Text(
                    serverAddressError
                        ?: "支持 IPv4、IPv6 和域名；可输入完整 http(s):// 地址。省略协议使用 HTTP，省略端口默认 18000；IPv6 自定义端口请写成 [地址]:端口。",
                    modifier = Modifier.testTag("server-origin-supporting-text"),
                )
            },
            modifier = Modifier.fillMaxWidth().testTag("server-origin-input"),
        )
        Button(
            onClick = {
                val parsed = try {
                    ServerOrigin.parseAddress(serverAddressDraft)
                } catch (error: IllegalArgumentException) {
                    serverAddressError = "地址格式不正确：请检查主机、端口、协议和路径。"
                    null
                }
                if (parsed != null) {
                    screenScope.launch {
                        isSavingServerAddress = true
                        serverAddressError = null
                        serverAddressStatus = null
                        try {
                            onApplyServerOrigin(parsed)
                            serverAddressDraft = parsed.address
                            serverAddressEdited = false
                            serverAddressStatus = "已保存并应用；尚未检测服务器连通性"
                        } catch (error: Exception) {
                            serverAddressStatus = "保存失败：${error.message ?: "请重试"}"
                        } finally {
                            isSavingServerAddress = false
                        }
                    }
                }
            },
            enabled = !isSavingServerAddress,
            modifier = Modifier.fillMaxWidth().testTag("server-origin-save"),
        ) {
            Text(if (isSavingServerAddress) "正在保存…" else "保存服务器地址")
        }
        Text(
            text = "当前配置：$savedServerOrigin",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("server-origin-current"),
        )
        serverAddressStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.startsWith("保存失败")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("server-origin-status"),
            )
        }

        Text(
            text = "主题",
            style = MaterialTheme.typography.titleMedium,
        )
        ThemePreferenceOption(
            label = "跟随系统",
            tag = "theme-option-follow-system",
            preference = ThemePreference.FOLLOW_SYSTEM,
            selectedPreference = themePreference,
            onSelected = { preference ->
                preferenceWriteScope.launch { preferencesRepository.setThemePreference(preference) }
            },
        )
        ThemePreferenceOption(
            label = "浅色",
            tag = "theme-option-light",
            preference = ThemePreference.LIGHT,
            selectedPreference = themePreference,
            onSelected = { preference ->
                preferenceWriteScope.launch { preferencesRepository.setThemePreference(preference) }
            },
        )
        ThemePreferenceOption(
            label = "深色",
            tag = "theme-option-dark",
            preference = ThemePreference.DARK,
            selectedPreference = themePreference,
            onSelected = { preference ->
                preferenceWriteScope.launch { preferencesRepository.setThemePreference(preference) }
            },
        )

        Text(
            text = "状态环",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("status-ring-settings-title"),
        )

        RingSettingSlider(
            title = "尺寸缩放",
            currentValue = formatScale(draft.sizeScale),
            description = "默认 ${formatScale(StatusRingPreferences.SIZE_SCALE_DEFAULT)} · 范围 ${formatScale(StatusRingPreferences.SIZE_SCALE_MIN)}–${formatScale(StatusRingPreferences.SIZE_SCALE_MAX)} · 步进 ${formatScale(StatusRingPreferences.SIZE_SCALE_STEP)}",
            value = draft.sizeScale,
            valueRange = StatusRingPreferences.SIZE_SCALE_MIN..StatusRingPreferences.SIZE_SCALE_MAX,
            steps = StatusRingPreferences.SIZE_SCALE_SLIDER_STEPS,
            tag = "status-ring-size-slider",
            valueTag = "status-ring-size-value",
            label = "尺寸缩放",
            onValueChange = { draft = draft.copy(sizeScale = it).normalized() },
            onValueChangeFinished = ::persistDraft,
        )

        RingSettingSlider(
            title = "线宽",
            currentValue = formatLineWidth(draft.lineWidthDp),
            description = "默认 ${formatLineWidth(StatusRingPreferences.LINE_WIDTH_DEFAULT_DP)} · 范围 ${formatLineWidth(StatusRingPreferences.LINE_WIDTH_MIN_DP)}–${formatLineWidth(StatusRingPreferences.LINE_WIDTH_MAX_DP)} · 步进 ${formatLineWidth(StatusRingPreferences.LINE_WIDTH_STEP_DP)}",
            value = draft.lineWidthDp,
            valueRange = StatusRingPreferences.LINE_WIDTH_MIN_DP..StatusRingPreferences.LINE_WIDTH_MAX_DP,
            steps = StatusRingPreferences.LINE_WIDTH_SLIDER_STEPS,
            tag = "status-ring-line-width-slider",
            valueTag = "status-ring-line-width-value",
            label = "线宽",
            onValueChange = { draft = draft.copy(lineWidthDp = it).normalized() },
            onValueChangeFinished = ::persistDraft,
        )

        RingSettingSlider(
            title = "水平位置偏移",
            currentValue = formatOffset(draft.horizontalOffsetDp),
            description = "默认 ${formatOffset(StatusRingPreferences.POSITION_OFFSET_DEFAULT_DP)} · 范围 ${formatOffset(StatusRingPreferences.POSITION_OFFSET_MIN_DP)} 至 ${formatOffset(StatusRingPreferences.POSITION_OFFSET_MAX_DP)} · 步进 ${formatOffset(StatusRingPreferences.POSITION_OFFSET_STEP_DP)}",
            value = draft.horizontalOffsetDp,
            valueRange = StatusRingPreferences.POSITION_OFFSET_MIN_DP..StatusRingPreferences.POSITION_OFFSET_MAX_DP,
            steps = StatusRingPreferences.POSITION_OFFSET_SLIDER_STEPS,
            tag = "status-ring-horizontal-offset-slider",
            valueTag = "status-ring-horizontal-offset-value",
            label = "水平位置偏移",
            onValueChange = { draft = draft.copy(horizontalOffsetDp = it).normalized() },
            onValueChangeFinished = ::persistDraft,
        )

        RingSettingSlider(
            title = "垂直位置偏移",
            currentValue = formatOffset(draft.verticalOffsetDp),
            description = "默认 ${formatOffset(StatusRingPreferences.POSITION_OFFSET_DEFAULT_DP)} · 范围 ${formatOffset(StatusRingPreferences.POSITION_OFFSET_MIN_DP)} 至 ${formatOffset(StatusRingPreferences.POSITION_OFFSET_MAX_DP)} · 步进 ${formatOffset(StatusRingPreferences.POSITION_OFFSET_STEP_DP)}",
            value = draft.verticalOffsetDp,
            valueRange = StatusRingPreferences.POSITION_OFFSET_MIN_DP..StatusRingPreferences.POSITION_OFFSET_MAX_DP,
            steps = StatusRingPreferences.POSITION_OFFSET_SLIDER_STEPS,
            tag = "status-ring-vertical-offset-slider",
            valueTag = "status-ring-vertical-offset-value",
            label = "垂直位置偏移",
            onValueChange = { draft = draft.copy(verticalOffsetDp = it).normalized() },
            onValueChangeFinished = ::persistDraft,
        )

        Text(
            text = "实时预览",
            style = MaterialTheme.typography.titleMedium,
        )
        StatusRingSettingsPreview(
            geometry = windowGeometry,
            preferences = draft,
            previewDescription = previewDescription,
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
        )
        Text(
            text = "仅预览固定示意状态，不会更改真实活动、连接状态或共享 Runtime。实际位置以系统挖孔几何为基准；无可靠几何时使用手动校准回退。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                draft = StatusRingPreferences.DEFAULT
                preferenceWriteScope.launch { preferencesRepository.resetStatusRingPreferences() }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("status-ring-reset-defaults"),
        ) {
            Text("恢复默认值")
        }
    }
}

@Composable
private fun ThemePreferenceOption(
    label: String,
    tag: String,
    preference: ThemePreference,
    selectedPreference: ThemePreference,
    onSelected: (ThemePreference) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = preference == selectedPreference,
                role = Role.RadioButton,
                onClick = { onSelected(preference) },
            )
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(
            selected = preference == selectedPreference,
            onClick = null,
        )
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RingSettingSlider(
    title: String,
    currentValue: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    tag: String,
    valueTag: String,
    label: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(text = currentValue, modifier = Modifier.testTag(valueTag), style = MaterialTheme.typography.bodyLarge)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label }
                .testTag(tag),
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatScale(value: Float): String = String.format(Locale.ROOT, "%.2f×", value)
private fun formatLineWidth(value: Float): String = String.format(Locale.ROOT, "%.1fdp", value)
private fun formatOffset(value: Float): String = "${value.toInt()}dp"
