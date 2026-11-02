package io.github.qyxx2.mpdserver.remote.ui.navigation

/** Shared geometry contract for ordinary NavHost route changes. */
object NavigationMotionPolicy {
    const val durationMillis: Int = 200
    const val travelFraction: Float = 0.25f

    private fun quarterWidth(widthPx: Int): Int =
        (widthPx.coerceAtLeast(0) * travelFraction).toInt()

    fun forwardEnterOffset(widthPx: Int): Int = quarterWidth(widthPx)
    fun forwardExitOffset(widthPx: Int): Int = -quarterWidth(widthPx)
    fun popEnterOffset(widthPx: Int): Int = -quarterWidth(widthPx)
    fun popExitOffset(widthPx: Int): Int = quarterWidth(widthPx)
}
