package io.github.qyxx2.mpdserver.remote.ui.player.lyrics

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsClockPresentationTest {
    @Test
    fun subSecondClockTicksDoNotEmitUntilActiveCueOrClockAvailabilityChanges() = runBlocking {
        val document = parseLyrics(
            "[00:01]第一行\n[00:02]第二行",
            "lrc",
        )
        val positions = MutableStateFlow<Double?>(1.1)
        val emissions = mutableListOf<LyricsClockPresentation>()
        val job = launch {
            lyricsClockPresentationFlow(document, positions).collect(emissions::add)
        }

        try {
            yield()
            positions.value = 1.2
            yield()
            positions.value = 1.999
            yield()

            assertEquals("sub-second samples inside one cue should be collapsed", 1, emissions.size)

            positions.value = 2.1
            yield()
            positions.value = 2.8
            yield()
            assertEquals("a new cue emits once, not once per clock tick", 2, emissions.size)

            positions.value = null
            withTimeout(5_000) {
                while (emissions.size < 3) delay(1)
            }

            assertEquals(
                listOf(
                    LyricsClockPresentation(listOf(0), true),
                    LyricsClockPresentation(listOf(1), true),
                    LyricsClockPresentation(emptyList(), false),
                ),
                emissions,
            )
        } finally {
            job.cancel()
        }
    }
}
