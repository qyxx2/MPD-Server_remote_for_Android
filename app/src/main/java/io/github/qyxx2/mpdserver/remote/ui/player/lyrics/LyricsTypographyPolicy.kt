package io.github.qyxx2.mpdserver.remote.ui.player.lyrics

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

data class LyricsCueTypography(
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
)

/** Typography/overflow values shared by timed-cue rendering and focused contract tests. */
object LyricsTypographyPolicy {
    val activeCue = LyricsCueTypography(fontSize = 32.sp, lineHeight = 40.sp)
    val inactiveCue = LyricsCueTypography(fontSize = 26.sp, lineHeight = 34.sp)
    const val secondLineMaxLines: Int = 1
    const val secondLineSoftWrap: Boolean = false
    val secondLineOverflow: TextOverflow = TextOverflow.Ellipsis
}
