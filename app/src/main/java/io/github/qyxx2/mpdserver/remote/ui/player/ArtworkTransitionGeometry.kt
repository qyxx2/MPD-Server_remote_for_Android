package io.github.qyxx2.mpdserver.remote.ui.player

import androidx.compose.ui.geometry.Rect

/** Interpolates an artwork rectangle in a shared coordinate space. */
internal object ArtworkTransitionGeometry {
    fun interpolate(source: Rect, target: Rect, progress: Float): Rect {
        require(source.isFiniteNonEmpty()) { "Source artwork bounds must be finite and non-empty." }
        require(target.isFiniteNonEmpty()) { "Target artwork bounds must be finite and non-empty." }
        require(progress.isFinite()) { "Artwork transition progress must be finite." }

        val fraction = progress.coerceIn(0f, 1f)
        return Rect(
            left = source.left + (target.left - source.left) * fraction,
            top = source.top + (target.top - source.top) * fraction,
            right = source.right + (target.right - source.right) * fraction,
            bottom = source.bottom + (target.bottom - source.bottom) * fraction,
        )
    }

    private fun Rect.isFiniteNonEmpty(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            right > left && bottom > top
}
