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
import androidx.compose.ui.platform.testTag

internal enum class PlayerGlyphKind { ALBUM, OUTPUT, NOW_PLAYING, QUEUE, PREVIOUS, PLAY, PAUSE, NEXT }

/** Small native vector glyphs avoid an added icon dependency and keep navigation distinct from play controls. */
@Composable
internal fun PlayerGlyph(
    kind: PlayerGlyphKind,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.testTag("player-glyph-${kind.name.lowercase()}")) {
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
                val corner = size.minDimension * .10f
                drawRoundedTriangle(tint, Offset(w * .12f, h * .5f), Offset(w * .47f, h * .13f), Offset(w * .47f, h * .87f), corner)
                drawRoundedTriangle(tint, Offset(w * .44f, h * .5f), Offset(w * .80f, h * .13f), Offset(w * .80f, h * .87f), corner)
            }
            PlayerGlyphKind.PLAY -> drawRoundedTriangle(
                tint,
                Offset(w * .20f, h * .10f),
                Offset(w * .20f, h * .90f),
                Offset(w * .84f, h * .5f),
                size.minDimension * .10f,
            )
            PlayerGlyphKind.PAUSE -> {
                drawRoundRect(tint, Offset(w * .25f, h * .18f), Size(w * .16f, h * .64f), CornerRadius(stroke))
                drawRoundRect(tint, Offset(w * .59f, h * .18f), Size(w * .16f, h * .64f), CornerRadius(stroke))
            }
            PlayerGlyphKind.NEXT -> {
                val corner = size.minDimension * .10f
                drawRoundedTriangle(tint, Offset(w * .56f, h * .5f), Offset(w * .20f, h * .13f), Offset(w * .20f, h * .87f), corner)
                drawRoundedTriangle(tint, Offset(w * .88f, h * .5f), Offset(w * .53f, h * .13f), Offset(w * .53f, h * .87f), corner)
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

private fun DrawScope.drawRoundedTriangle(
    tint: Color,
    a: Offset,
    b: Offset,
    c: Offset,
    cornerRadius: Float,
) {
    val vertices = listOf(a, b, c)
    fun alongEdge(from: Offset, toward: Offset): Offset {
        val dx = toward.x - from.x
        val dy = toward.y - from.y
        val length = kotlin.math.hypot(dx, dy).coerceAtLeast(0.001f)
        val inset = cornerRadius.coerceAtMost(length * .25f)
        return Offset(from.x + dx / length * inset, from.y + dy / length * inset)
    }
    val beforeCorners = vertices.indices.map { index ->
        alongEdge(vertices[index], vertices[(index + 2) % vertices.size])
    }
    val afterCorners = vertices.indices.map { index ->
        alongEdge(vertices[index], vertices[(index + 1) % vertices.size])
    }
    val triangle = Path().apply {
        moveTo(beforeCorners[0].x, beforeCorners[0].y)
        quadraticTo(a.x, a.y, afterCorners[0].x, afterCorners[0].y)
        lineTo(beforeCorners[1].x, beforeCorners[1].y)
        quadraticTo(b.x, b.y, afterCorners[1].x, afterCorners[1].y)
        lineTo(beforeCorners[2].x, beforeCorners[2].y)
        quadraticTo(c.x, c.y, afterCorners[2].x, afterCorners[2].y)
        close()
    }
    drawPath(triangle, tint)
}
