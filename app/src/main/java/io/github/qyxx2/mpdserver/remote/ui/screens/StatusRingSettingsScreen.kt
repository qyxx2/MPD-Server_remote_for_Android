package io.github.qyxx2.mpdserver.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingSettingsPreview
import io.github.qyxx2.mpdserver.remote.ui.components.StatusRingWindowGeometry
import io.github.qyxx2.mpdserver.remote.ui.preferences.StatusRingPreferences
import io.github.qyxx2.mpdserver.remote.ui.preferences.UiPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun StatusRingSettingsScreen(
    preferencesRepository: UiPreferencesRepository,
    preferenceWriteScope: CoroutineScope,
    windowGeometry: StatusRingWindowGeometry,
) {
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
