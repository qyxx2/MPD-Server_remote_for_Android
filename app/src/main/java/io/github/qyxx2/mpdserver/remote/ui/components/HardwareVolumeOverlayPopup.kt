package io.github.qyxx2.mpdserver.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.qyxx2.mpdserver.remote.runtime.HardwareVolumeOverlayState
import io.github.qyxx2.mpdserver.remote.ui.preferences.StatusRingPreferences

/**
 * App-window Popup for the volume hint. It is deliberately non-focusable and only occupies the
 * narrow bar bounds. Creating/recreating it after an app Popup keeps it above that Popup without
 * a cross-app overlay permission or a second window token tree.
 */
@Composable
internal fun HardwareVolumeOverlayPopup(
    state: HardwareVolumeOverlayState,
    geometry: StatusRingWindowGeometry,
    preferences: StatusRingPreferences,
    darkTheme: Boolean,
) {
    if (!state.visible) return

    key(state.windowOrderSerial) {
        val density = LocalDensity.current
        val positionProvider = remember(geometry, preferences, density) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: androidx.compose.ui.unit.IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val placement = HardwareVolumeOverlayPlacementCalculator.calculate(
                        windowWidthPx = windowSize.width,
                        windowHeightPx = windowSize.height,
                        geometry = geometry,
                        density = density.density,
                        preferences = StatusRingPlacementSettings(
                            sizeScale = preferences.sizeScale,
                            horizontalOffsetDp = preferences.horizontalOffsetDp,
                            verticalOffsetDp = preferences.verticalOffsetDp,
                        ),
                        barHeightPx = popupContentSize.height,
                    )
                    return IntOffset(
                        x = (placement.centerXpx - popupContentSize.width / 2f).toInt(),
                        y = placement.topPx.toInt(),
                    )
                }
            }
        }
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = {},
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                clippingEnabled = false,
            ),
        ) {
            val foreground = if (darkTheme) Color.White else Color(0xFF222222)
            val secondary = if (darkTheme) Color(0xFFD0D0D0) else Color(0xFF606060)
            val background = if (darkTheme) Color(0xE624282D) else Color(0xEFFFFFFF)
            val border = if (darkTheme) Color(0x44FFFFFF) else Color(0x22000000)
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(background)
                    .border(1.dp, border, RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_lock_silent_mode_off),
                    contentDescription = "远端硬件音量",
                    tint = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier.padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.content.valueText,
                        color = foreground,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 21.sp,
                    )
                    if (!state.content.statusText.isNullOrBlank()) {
                        Text(
                            modifier = Modifier.padding(start = 8.dp),
                            text = state.content.statusText.orEmpty(),
                            color = secondary,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                        )
                    }
                }
            }
        }
    }
}
