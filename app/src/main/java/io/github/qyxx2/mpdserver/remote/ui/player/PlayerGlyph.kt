package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

internal enum class PlayerGlyphKind { ALBUM, OUTPUT, NOW_PLAYING, QUEUE, PREVIOUS, PLAY, PAUSE, NEXT }

/** Small native vector glyphs avoid an added icon dependency and keep navigation distinct from play controls. */
@Composable
internal fun PlayerGlyph(
    kind: PlayerGlyphKind,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = size.minDimension * 0.075f
        val line = Stroke(width = stroke, cap = StrokeCap.Round)
        when (kind) {
            PlayerGlyphKind.ALBUM -> {
                drawRoundRect(tint, Offset(w * .12f, h * .12f), Size(w * .76f, h * .76f), CornerRadius(w * .09f), style = line)
                drawCircle(tint, w * .22f, Offset(w * .5f, h * .5f), style = line)
                drawCircle(tint, w * .045f, Offset(w * .5f, h * .5f))
            }
            PlayerGlyphKind.OUTPUT -> drawOutputGlyph(tint, line, w, h)
            PlayerGlyphKind.NOW_PLAYING -> {
                val bars = listOf(.35f, .67f, .48f, .86f, .54f, .74f, .30f)
                bars.forEachIndexed { i, height ->
                    val x = w * (.12f + i * .13f)
                    drawLine(tint, Offset(x, h * (1f - height) / 2f), Offset(x, h * (1f + height) / 2f), stroke * 1.2f, StrokeCap.Round)
                }
            }
            PlayerGlyphKind.QUEUE -> {
                for (i in 0..2) {
                    val y = h * (.27f + i * .25f)
                    drawCircle(tint, stroke * .55f, Offset(w * .18f, y))
                    drawLine(tint, Offset(w * .34f, y), Offset(w * .86f, y), stroke, StrokeCap.Round)
                }
            }
            PlayerGlyphKind.PREVIOUS -> {
                drawLine(tint, Offset(w * .24f, h * .22f), Offset(w * .24f, h * .78f), stroke, StrokeCap.Round)
                drawTriangle(tint, Offset(w * .76f, h * .22f), Offset(w * .76f, h * .78f), Offset(w * .31f, h * .5f))
            }
            PlayerGlyphKind.PLAY -> drawTriangle(tint, Offset(w * .28f, h * .18f), Offset(w * .28f, h * .82f), Offset(w * .78f, h * .5f))
            PlayerGlyphKind.PAUSE -> {
                drawRoundRect(tint, Offset(w * .25f, h * .18f), Size(w * .16f, h * .64f), CornerRadius(stroke))
                drawRoundRect(tint, Offset(w * .59f, h * .18f), Size(w * .16f, h * .64f), CornerRadius(stroke))
            }
            PlayerGlyphKind.NEXT -> {
                drawTriangle(tint, Offset(w * .24f, h * .22f), Offset(w * .24f, h * .78f), Offset(w * .69f, h * .5f))
                drawLine(tint, Offset(w * .78f, h * .22f), Offset(w * .78f, h * .78f), stroke, StrokeCap.Round)
            }
        }
    }
}

private fun DrawScope.drawOutputGlyph(tint: Color, line: Stroke, w: Float, h: Float) {
    val body = Path().apply {
        moveTo(w * .15f, h * .4f)
        lineTo(w * .36f, h * .4f)
        lineTo(w * .62f, h * .2f)
        lineTo(w * .62f, h * .8f)
        lineTo(w * .36f, h * .6f)
        lineTo(w * .15f, h * .6f)
        close()
    }
    drawPath(body, tint, style = line)
    drawArc(tint, -42f, 84f, false, Offset(w * .49f, h * .29f), Size(w * .43f, h * .42f), style = line)
    drawArc(tint, -42f, 84f, false, Offset(w * .44f, h * .17f), Size(w * .55f, h * .66f), style = line)
}

private fun DrawScope.drawTriangle(tint: Color, a: Offset, b: Offset, c: Offset) {
    val triangle = Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(c.x, c.y)
        close()
    }
    drawPath(triangle, tint)
}
