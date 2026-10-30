package io.github.qyxx2.mpdserver.remote.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Shared theme-aware lavender-tinted surface for every modal/dialog surface in the app. */
@Composable
internal fun dialogSurfaceColor(): Color {
    val surface = MaterialTheme.colorScheme.surface
    val lavenderTarget = if (surface.luminance() >= 0.5f) {
        Color(0xFFF4EDFF)
    } else {
        Color(0xFF312C38)
    }
    return lerp(surface, lavenderTarget, 0.18f)
}


/** Queue row actions use a subtle blend of the active theme accent and the base surface. */
internal fun queueMenuAccentSurfaceColor(surface: Color, accent: Color): Color =
    lerp(surface, accent, 0.22f)

internal fun queueRowMenuSurfaceColor(): Color = Color(0xFFF0DECA)
