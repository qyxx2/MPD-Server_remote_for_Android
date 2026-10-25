package io.github.qyxx2.mpdserver.remote.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ThemePreferenceTest {
    @Test
    fun followSystemTracksTheCurrentSystemTheme() {
        assertFalse(resolveDarkTheme(ThemePreference.FOLLOW_SYSTEM, systemIsDark = false))
        assertTrue(resolveDarkTheme(ThemePreference.FOLLOW_SYSTEM, systemIsDark = true))
    }

    @Test
    fun explicitLightOverridesDarkSystemTheme() {
        assertFalse(resolveDarkTheme(ThemePreference.LIGHT, systemIsDark = true))
        assertFalse(resolveDarkTheme(ThemePreference.LIGHT, systemIsDark = false))
    }

    @Test
    fun explicitDarkOverridesLightSystemTheme() {
        assertTrue(resolveDarkTheme(ThemePreference.DARK, systemIsDark = false))
        assertTrue(resolveDarkTheme(ThemePreference.DARK, systemIsDark = true))
    }

    @Test
    fun semanticSchemesKeepFrozenBrandAndNeutralTokens() {
        val light = appColorScheme(darkTheme = false)
        val dark = appColorScheme(darkTheme = true)

        assertEquals(Color(0xFFDE7622), light.secondary)
        assertEquals(Color(0xFFA84F0B), light.primary)
        assertEquals(Color(0xFFF7F7F5), light.background)
        assertEquals(Color(0xFF171717), light.onSurface)
        assertEquals(Color(0xFFBE7E4A), dark.primary)
        assertEquals(Color(0xFF171717), dark.background)
        assertEquals(Color(0xFFF4F4F4), dark.onSurface)
    }

    @Test
    fun importantTextAndOutlinesMeetContrastTargets() {
        val light = appColorScheme(darkTheme = false)
        val dark = appColorScheme(darkTheme = true)

        assertTrue(contrastRatio(light.primary, light.onPrimary) >= 4.5)
        assertTrue(contrastRatio(light.onSurfaceVariant, light.background) >= 4.5)
        assertTrue(contrastRatio(light.outline, light.surface) >= 3.0)
        assertTrue(contrastRatio(dark.primary, dark.onPrimary) >= 4.5)
        assertTrue(contrastRatio(dark.onSurfaceVariant, dark.background) >= 4.5)
        assertTrue(contrastRatio(dark.outline, dark.surface) >= 3.0)
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (min(firstLuminance, secondLuminance) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun linearize(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)
    }
}
