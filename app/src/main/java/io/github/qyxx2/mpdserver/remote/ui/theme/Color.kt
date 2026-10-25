package io.github.qyxx2.mpdserver.remote.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Brand and semantic color tokens shared by light and dark themes. */
internal val BrandOrange = Color(0xFFDE7622)
private val LightAccessibleOrange = Color(0xFFA84F0B)
private val LightBackground = Color(0xFFF7F7F5)
private val LightSurface = Color(0xFFFFFFFF)
private val LightText = Color(0xFF171717)
private val LightSecondaryText = Color(0xFF707070)
private val LightOutline = Color(0xFF8A8A8A)
private val LightOutlineVariant = Color(0xFFD8D8D8)

private val DarkBackground = Color(0xFF171717)
private val DarkSurface = Color(0xFF202020)
private val DarkText = Color(0xFFF4F4F4)
private val DarkSecondaryText = Color(0xFFB8B8B8)
private val DarkOutline = Color(0xFF777777)
private val DarkOutlineVariant = Color(0xFF3A3A3A)

internal fun appColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFFBE7E4A),
            onPrimary = DarkBackground,
            primaryContainer = Color(0xFF6D3A18),
            onPrimaryContainer = Color(0xFFFFDBBD),
            secondary = Color(0xFFBE7E4A),
            onSecondary = DarkBackground,
            background = DarkBackground,
            surface = DarkSurface,
            surfaceVariant = Color(0xFF2B2B2B),
            onSurface = DarkText,
            onSurfaceVariant = DarkSecondaryText,
            outline = DarkOutline,
            outlineVariant = DarkOutlineVariant,
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
        )
    } else {
        lightColorScheme(
            primary = LightAccessibleOrange,
            onPrimary = Color.White,
            primaryContainer = BrandOrange,
            onPrimaryContainer = LightText,
            secondary = BrandOrange,
            onSecondary = LightText,
            background = LightBackground,
            surface = LightSurface,
            surfaceVariant = Color(0xFFEFEFEC),
            onSurface = LightText,
            onSurfaceVariant = LightSecondaryText,
            outline = LightOutline,
            outlineVariant = LightOutlineVariant,
            error = Color(0xFFBA1A1A),
            onError = Color.White,
        )
    }
