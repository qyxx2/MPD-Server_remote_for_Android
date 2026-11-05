package io.github.qyxx2.mpdserver.remote.ui.player.lyrics

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

data class LyricsCueTypography(
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
)

/** Typography/overflow values shared by timed-cue rendering and focused contract tests. */
object LyricsTypographyPolicy {
    const val maximumVisibleLines: Int = 8
    val textAlignment: TextAlign = TextAlign.Center
    val activeCue = LyricsCueTypography(fontSize = 32.sp, lineHeight = 40.sp)
    val inactiveCue = LyricsCueTypography(fontSize = 26.sp, lineHeight = 34.sp)
    const val secondLineMaxLines: Int = 1
    const val secondLineSoftWrap: Boolean = false
    val secondLineOverflow: TextOverflow = TextOverflow.Clip

    fun visibleLineCount(
        availableHeightDp: Float,
        minimumLineHeightDp: Float,
        activeCueLineCount: Int? = null,
    ): Int {
        if (!availableHeightDp.isFinite() || !minimumLineHeightDp.isFinite() ||
            availableHeightDp <= 0f || minimumLineHeightDp <= 0f
        ) return 1

        // An odd row count centers a single active line on a complete row boundary.
        // A two-line cue can use eight rows because its group center falls between its lines.
        val maximumForCenteredCue = if (activeCueLineCount == 1) {
            maximumVisibleLines - 1
        } else {
            maximumVisibleLines
        }
        return (availableHeightDp / minimumLineHeightDp)
            .toInt()
            .coerceIn(1, maximumForCenteredCue)
    }
}
