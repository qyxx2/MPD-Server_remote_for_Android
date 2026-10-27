package io.github.qyxx2.mpdserver.remote.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Non-interactive, full-player-only art backdrop. The image is a downsampled software Gaussian blur
 * so API 26+ has the same baseline effect without RenderScript or a platform-specific dependency.
 */
@Composable
internal fun PlayerArtworkBackdrop(
    blurredArtwork: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val maskAlpha = PlayerArtworkBackdropPolicy.maskAlpha(scheme.background.luminance())

    Box(modifier = modifier.fillMaxSize().testTag("player-artwork-backdrop")) {
        if (blurredArtwork != null) {
            Image(
                bitmap = blurredArtwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().testTag("player-artwork-blurred-backdrop"),
            )
        } else {
            // App-owned fallback artwork: deliberately soft, ambient gradients, not a fake song cover.
            Canvas(
                modifier = Modifier.fillMaxSize().testTag("player-artwork-backdrop-placeholder"),
            ) {
                val extent = max(size.width, size.height)
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(scheme.background, scheme.surfaceVariant, scheme.background),
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to scheme.primary.copy(alpha = 0.34f),
                            0.42f to scheme.primary.copy(alpha = 0.16f),
                            1f to Color.Transparent,
                        ),
                        center = Offset(size.width * 0.24f, size.height * 0.27f),
                        radius = extent * 0.72f,
                    ),
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to scheme.secondary.copy(alpha = 0.25f),
                            0.48f to scheme.secondary.copy(alpha = 0.10f),
                            1f to Color.Transparent,
                        ),
                        center = Offset(size.width * 0.82f, size.height * 0.72f),
                        radius = extent * 0.62f,
                    ),
                )
            }
        }

        // Theme-specific veil keeps foreground text readable while retaining recognizable art color.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.background.copy(alpha = maskAlpha))
                .testTag("player-artwork-backdrop-theme-mask"),
        )
    }
}

internal object PlayerArtworkBackdropPolicy {
    /** Light theme gets a bright veil; dark theme suppresses bright art highlights more strongly. */
    fun maskAlpha(backgroundLuminance: Float): Float {
        if (!backgroundLuminance.isFinite()) return 0.72f
        return if (backgroundLuminance >= 0.5f) 0.72f else 0.76f
    }

    fun downsampledSize(width: Int, height: Int, maxDimension: Int = 360): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1 to 1
        val scale = min(1f, maxDimension.toFloat() / max(width, height).toFloat())
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }
}

/** Build a low-resolution Gaussian-blurred bitmap once per confirmed art identity on Dispatchers.Default. */
internal fun createBlurredArtworkBitmap(source: ImageBitmap): ImageBitmap? = runCatching {
    val sourceBitmap = source.asAndroidBitmap()
    val (width, height) = PlayerArtworkBackdropPolicy.downsampledSize(
        sourceBitmap.width,
        sourceBitmap.height,
    )
    val scaled = Bitmap.createScaledBitmap(sourceBitmap, width, height, true)
    try {
        gaussianBlur(scaled, radius = 14).asImageBitmap()
    } finally {
        if (scaled !== sourceBitmap && !scaled.isRecycled) scaled.recycle()
    }
}.getOrNull()

/** Separable Gaussian convolution; its small, downsampled input keeps the API-26 fallback inexpensive. */
private fun gaussianBlur(source: Bitmap, radius: Int): Bitmap {
    val width = source.width
    val height = source.height
    val count = width * height
    val input = IntArray(count)
    val horizontal = IntArray(count)
    val output = IntArray(count)
    source.getPixels(input, 0, width, 0, 0, width, height)

    val sigma = max(1f, radius / 2.25f)
    val kernel = FloatArray(radius * 2 + 1) { index ->
        val distance = (index - radius).toFloat()
        exp(-(distance * distance) / (2f * sigma * sigma))
    }
    val kernelSum = kernel.sum().coerceAtLeast(0.0001f)
    for (index in kernel.indices) kernel[index] /= kernelSum

    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            var alpha = 0f
            var red = 0f
            var green = 0f
            var blue = 0f
            for (k in -radius..radius) {
                val pixel = input[row + (x + k).coerceIn(0, width - 1)]
                val weight = kernel[k + radius]
                alpha += ((pixel ushr 24) and 0xFF) * weight
                red += ((pixel ushr 16) and 0xFF) * weight
                green += ((pixel ushr 8) and 0xFF) * weight
                blue += (pixel and 0xFF) * weight
            }
            horizontal[row + x] = packArgb(alpha, red, green, blue)
        }
    }

    for (y in 0 until height) {
        for (x in 0 until width) {
            var alpha = 0f
            var red = 0f
            var green = 0f
            var blue = 0f
            for (k in -radius..radius) {
                val pixel = horizontal[(y + k).coerceIn(0, height - 1) * width + x]
                val weight = kernel[k + radius]
                alpha += ((pixel ushr 24) and 0xFF) * weight
                red += ((pixel ushr 16) and 0xFF) * weight
                green += ((pixel ushr 8) and 0xFF) * weight
                blue += (pixel and 0xFF) * weight
            }
            output[y * width + x] = packArgb(alpha, red, green, blue)
        }
    }

    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(output, 0, width, 0, 0, width, height)
    }
}

private fun packArgb(alpha: Float, red: Float, green: Float, blue: Float): Int =
    ((alpha.roundToInt().coerceIn(0, 255)) shl 24) or
        ((red.roundToInt().coerceIn(0, 255)) shl 16) or
        ((green.roundToInt().coerceIn(0, 255)) shl 8) or
        blue.roundToInt().coerceIn(0, 255)
