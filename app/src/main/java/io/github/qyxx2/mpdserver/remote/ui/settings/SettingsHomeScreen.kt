package io.github.qyxx2.mpdserver.remote.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsHomeScreen(
    onOpenSection: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp).testTag("settings-root-screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        SettingsSection.entries.forEach { section ->
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .clickable { onOpenSection(section) }
                    .testTag("settings-category-${section.name.lowercase()}"),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.54f),
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 17.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SettingsCategoryIcon(section.icon)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(section.title, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(section.subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryIcon(glyph: SettingsCategoryGlyph) {
    val tint = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            val c = tint
            when (glyph) {
                SettingsCategoryGlyph.STATUS -> {
                    drawArc(c, -90f, 285f, false, style = stroke)
                    drawCircle(c, radius = 2.1.dp.toPx())
                }
                SettingsCategoryGlyph.SERVER -> {
                    drawRoundRect(c, topLeft = androidx.compose.ui.geometry.Offset(2.dp.toPx(), 3.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(size.width - 4.dp.toPx(), size.height - 6.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = stroke)
                    listOf(8f, 16f).forEach { y ->
                        drawLine(c, androidx.compose.ui.geometry.Offset(8.dp.toPx(), y.dp.toPx()),
                            androidx.compose.ui.geometry.Offset(20.dp.toPx(), y.dp.toPx()), 1.8.dp.toPx(), StrokeCap.Round)
                        drawCircle(c, 1.25.dp.toPx(), androidx.compose.ui.geometry.Offset(5.dp.toPx(), y.dp.toPx()))
                    }
                }
                SettingsCategoryGlyph.APPEARANCE -> {
                    drawCircle(c, radius = 9.dp.toPx(), style = stroke)
                    drawCircle(c, 2.dp.toPx(), androidx.compose.ui.geometry.Offset(8.dp.toPx(), 8.dp.toPx()))
                    drawCircle(c, 2.dp.toPx(), androidx.compose.ui.geometry.Offset(16.dp.toPx(), 8.dp.toPx()))
                    drawCircle(c, 2.dp.toPx(), androidx.compose.ui.geometry.Offset(12.dp.toPx(), 16.dp.toPx()))
                }
                SettingsCategoryGlyph.ABOUT -> {
                    drawCircle(c, radius = 10.dp.toPx(), style = stroke)
                    drawCircle(c, radius = 1.3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(12.dp.toPx(), 7.dp.toPx()))
                    drawLine(c, androidx.compose.ui.geometry.Offset(12.dp.toPx(), 11.dp.toPx()),
                        androidx.compose.ui.geometry.Offset(12.dp.toPx(), 17.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}
