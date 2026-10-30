package io.github.qyxx2.mpdserver.remote.ui.player.lyrics

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The only lyric state which changes with playback time.
 *
 * Sub-second clock values are intentionally collapsed: the lyrics screen updates only when the
 * highlighted cue set changes or when a valid clock first becomes available/disappears.
 */
internal data class LyricsClockPresentation(
    val activeCueIndexes: List<Int>,
    val positionKnown: Boolean,
)

internal fun lyricsClockPresentationFlow(
    document: LyricsDocument,
    positions: Flow<Double?>,
): Flow<LyricsClockPresentation> = positions
    .map { position ->
        LyricsClockPresentation(
            activeCueIndexes = activeCueIndexes(document, position),
            positionKnown = position?.isFinite() == true && position >= 0.0,
        )
    }
    .distinctUntilChanged()
