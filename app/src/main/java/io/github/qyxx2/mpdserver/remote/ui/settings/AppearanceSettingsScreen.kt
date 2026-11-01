package io.github.qyxx2.mpdserver.remote.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.qyxx2.mpdserver.remote.ui.preferences.StatusRingPreferences
import io.github.qyxx2.mpdserver.remote.ui.preferences.UiPreferencesRepository
import io.github.qyxx2.mpdserver.remote.ui.theme.ThemePreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun AppearanceSettingsScreen(
    preferencesRepository: UiPreferencesRepository,
    preferenceWriteScope: CoroutineScope,
    ringDraft: StatusRingPreferences,
    onRingDraftChange: (StatusRingPreferences) -> Unit,
) {
    val themePreference by preferencesRepository.themePreference.collectAsStateWithLifecycle(
        initialValue = ThemePreference.FOLLOW_SYSTEM,
    )
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp).testTag("appearance-settings-screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("主题", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ThemePreferenceOption("跟随系统", "theme-option-follow-system", ThemePreference.FOLLOW_SYSTEM,
            themePreference) { selected ->
            preferenceWriteScope.launch { preferencesRepository.setThemePreference(selected) }
        }
        ThemePreferenceOption("浅色", "theme-option-light", ThemePreference.LIGHT, themePreference) { selected ->
            preferenceWriteScope.launch { preferencesRepository.setThemePreference(selected) }
        }
        ThemePreferenceOption("深色", "theme-option-dark", ThemePreference.DARK, themePreference) { selected ->
            preferenceWriteScope.launch { preferencesRepository.setThemePreference(selected) }
        }

        Text("状态环", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("status-ring-settings-title"))
        Text(
            "调节屏幕顶部状态环的尺寸、线宽和位置。预览直接叠加在当前窗口的挖孔位置，不会产生真实活动。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            RingSettingSlider(
                modifier = Modifier.weight(1f), title = "尺寸缩放", currentValue = formatScale(ringDraft.sizeScale),
                description = "${formatScale(StatusRingPreferences.SIZE_SCALE_MIN)}–${formatScale(StatusRingPreferences.SIZE_SCALE_MAX)} · 步进 ${formatScale(StatusRingPreferences.SIZE_SCALE_STEP)}",
                value = ringDraft.sizeScale,
                range = StatusRingPreferences.SIZE_SCALE_MIN..StatusRingPreferences.SIZE_SCALE_MAX,
                steps = StatusRingPreferences.SIZE_SCALE_SLIDER_STEPS,
                tag = "status-ring-size-slider", valueTag = "status-ring-size-value", label = "尺寸缩放",
                onValueChange = { onRingDraftChange(ringDraft.copy(sizeScale = it).normalized()) },
            )
            RingSettingSlider(
                modifier = Modifier.weight(1f), title = "线宽", currentValue = formatLineWidth(ringDraft.lineWidthDp),
                description = "${formatLineWidth(StatusRingPreferences.LINE_WIDTH_MIN_DP)}–${formatLineWidth(StatusRingPreferences.LINE_WIDTH_MAX_DP)} · 步进 ${formatLineWidth(StatusRingPreferences.LINE_WIDTH_STEP_DP)}",
                value = ringDraft.lineWidthDp,
                range = StatusRingPreferences.LINE_WIDTH_MIN_DP..StatusRingPreferences.LINE_WIDTH_MAX_DP,
                steps = StatusRingPreferences.LINE_WIDTH_SLIDER_STEPS,
                tag = "status-ring-line-width-slider", valueTag = "status-ring-line-width-value", label = "线宽",
                onValueChange = { onRingDraftChange(ringDraft.copy(lineWidthDp = it).normalized()) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            RingSettingSlider(
                modifier = Modifier.weight(1f), title = "水平位置", currentValue = formatOffset(ringDraft.horizontalOffsetDp),
                description = "${formatOffset(StatusRingPreferences.HORIZONTAL_OFFSET_MIN_DP)}–${formatOffset(StatusRingPreferences.HORIZONTAL_OFFSET_MAX_DP)} · 步进 ${formatOffset(StatusRingPreferences.HORIZONTAL_OFFSET_STEP_DP)}",
                value = ringDraft.horizontalOffsetDp,
                range = StatusRingPreferences.HORIZONTAL_OFFSET_MIN_DP..StatusRingPreferences.HORIZONTAL_OFFSET_MAX_DP,
                steps = StatusRingPreferences.HORIZONTAL_OFFSET_SLIDER_STEPS,
                tag = "status-ring-horizontal-offset-slider", valueTag = "status-ring-horizontal-offset-value", label = "水平位置偏移",
                onValueChange = { onRingDraftChange(ringDraft.copy(horizontalOffsetDp = it).normalized()) },
            )
            RingSettingSlider(
                modifier = Modifier.weight(1f), title = "垂直位置", currentValue = formatOffset(ringDraft.verticalOffsetDp),
                description = "${formatOffset(StatusRingPreferences.VERTICAL_OFFSET_MIN_DP)}–${formatOffset(StatusRingPreferences.VERTICAL_OFFSET_MAX_DP)} · 步进 ${formatOffset(StatusRingPreferences.VERTICAL_OFFSET_STEP_DP)}",
                value = ringDraft.verticalOffsetDp,
                range = StatusRingPreferences.VERTICAL_OFFSET_MIN_DP..StatusRingPreferences.VERTICAL_OFFSET_MAX_DP,
                steps = StatusRingPreferences.VERTICAL_OFFSET_SLIDER_STEPS,
                tag = "status-ring-vertical-offset-slider", valueTag = "status-ring-vertical-offset-value", label = "垂直位置偏移",
                onValueChange = { onRingDraftChange(ringDraft.copy(verticalOffsetDp = it).normalized()) },
            )
        }
        Text("预览已叠加在屏幕顶部", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("status-ring-preview"))
        Button(
            onClick = {
                onRingDraftChange(StatusRingPreferences.DEFAULT)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("status-ring-reset-defaults"),
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
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = preference == selectedPreference, role = Role.RadioButton,
                onClick = { onSelected(preference) }).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = preference == selectedPreference, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (preference == selectedPreference) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun RingSettingSlider(
    modifier: Modifier,
    title: String,
    currentValue: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    tag: String,
    valueTag: String,
    label: String,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(currentValue, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag(valueTag))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }.testTag(tag),
        )
        Text(description, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


private fun formatScale(value: Float) = String.format(Locale.ROOT, "%.2f×", value)
private fun formatLineWidth(value: Float) = String.format(Locale.ROOT, "%.1fdp", value)
private fun formatOffset(value: Float) = "${value.toInt()}dp"
