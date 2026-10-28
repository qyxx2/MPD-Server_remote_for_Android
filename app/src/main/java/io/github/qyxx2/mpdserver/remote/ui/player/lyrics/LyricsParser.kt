package io.github.qyxx2.mpdserver.remote.ui.player.lyrics

import io.github.qyxx2.mpdserver.remote.ui.player.LyricsResourceStatus
import io.github.qyxx2.mpdserver.remote.ui.player.PlayerSongPresentation
import java.math.BigInteger
import java.util.Locale

data class LyricsCue(val seconds: Double, val text: String)

data class LyricsDocument(
    val text: String,
    val cues: List<LyricsCue>,
    val notes: List<String>,
    val offsetMs: Long,
    val synchronized: Boolean,
    val formatSupported: Boolean,
)

enum class LyricsDisplayStatus {
    SONG_UNCONFIRMED, MISSING, READ_ERROR, PLAIN_TEXT, NO_READABLE_CUES,
    UNSUPPORTED_FORMAT, STATUS_UNKNOWN, SYNCHRONIZED,
}

data class LyricsPresentation(
    val document: LyricsDocument,
    val source: String?,
    val resourceStatus: LyricsResourceStatus,
    val rawResourceStatus: String,
    val status: LyricsDisplayStatus,
)

private val offsetTag = Regex("""\[offset:([+-]?\d+)\]""", RegexOption.IGNORE_CASE)
private val timestampTag = Regex("""\[(\d+):([0-5]\d)(?:[.:](\d{1,3}))?\]""")
private val metadataNote = Regex(
    """\[(?:ar|al|ti|au|by|re|ve|length|offset):[^\]]*\]""",
    RegexOption.IGNORE_CASE,
)
private val maximumSafeInteger = BigInteger("9007199254740991")
private val acceptedFormats = setOf("lrc", "text")

/** Mirrors web/src/components/player/lyrics.ts from the frozen MPD-Server baseline. */
fun parseLyrics(text: String, format: String?): LyricsDocument {
    val supported = format?.lowercase(Locale.ROOT) in acceptedFormats
    if (!supported) {
        return LyricsDocument(text, emptyList(), emptyList(), 0L, false, false)
    }

    var offsetMs = 0L
    var synchronized = false
    val cues = mutableListOf<LyricsCue>()
    val notes = mutableListOf<String>()

    text.split(Regex("\\r?\\n")).forEach { line ->
        offsetTag.findAll(line).forEach { match ->
            val parsed = runCatching { BigInteger(match.groupValues[1]) }.getOrNull()
            if (parsed != null &&
                parsed >= maximumSafeInteger.negate() &&
                parsed <= maximumSafeInteger
            ) {
                offsetMs = parsed.toLong()
            }
        }

        val timestamps = timestampTag.findAll(line).toList()
        val readable = timestampTag.replace(line, "").trim()
        if (timestamps.isNotEmpty()) {
            timestamps.forEach timestampLoop@ { match ->
                val minutes = match.groupValues[1].toDoubleOrNull() ?: Double.NaN
                val secondsPart = match.groupValues[2].toIntOrNull() ?: return@timestampLoop
                val fractionDigits = match.groupValues.getOrNull(3).orEmpty()
                val fraction = ("0." + fractionDigits.ifEmpty { "0" }).toDoubleOrNull() ?: Double.NaN
                val seconds = minutes * 60.0 + secondsPart + fraction
                if (seconds.isFinite()) {
                    synchronized = true
                    if (readable.isNotEmpty()) cues += LyricsCue(seconds = seconds, text = readable)
                }
            }
        } else if (line.isNotBlank() && !metadataNote.matches(line.trim())) {
            notes += line
        }
    }

    return LyricsDocument(
        text = text,
        cues = cues.sortedBy { it.seconds },
        notes = notes,
        offsetMs = offsetMs,
        synchronized = synchronized,
        formatSupported = true,
    )
}

/** All cues at the latest timestamp not later than position + offset are active together. */
fun activeCueIndexes(document: LyricsDocument, positionSeconds: Double?): List<Int> {
    val position = positionSeconds?.takeIf { it.isFinite() } ?: return emptyList()
    if (!document.synchronized) return emptyList()
    val adjustedPosition = position + document.offsetMs / 1000.0
    val latestSeconds = document.cues.lastOrNull { it.seconds <= adjustedPosition }?.seconds
        ?: return emptyList()
    return document.cues.indices.filter { document.cues[it].seconds == latestSeconds }
}

/** Converts a timed cue to a valid server seek target, reversing the display offset exactly as Web does. */
fun LyricsCue.seekTargetSeconds(
    offsetMs: Long,
    durationSeconds: Double?,
    seekEnabled: Boolean,
): Double? {
    if (!seekEnabled) return null
    val duration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val target = seconds - offsetMs / 1000.0
    return target.takeIf { it.isFinite() && it >= 0.0 && it <= duration }
}

/** Presents only the lyrics/status/source returned by the current server-owned Song. */
fun presentLyrics(song: PlayerSongPresentation?): LyricsPresentation {
    if (song == null) {
        return LyricsPresentation(
            document = parseLyrics("", null),
            source = null,
            resourceStatus = LyricsResourceStatus.UNKNOWN,
            rawResourceStatus = "unknown",
            status = LyricsDisplayStatus.SONG_UNCONFIRMED,
        )
    }

    val rawText = song.lyrics.orEmpty()
    val document = parseLyrics(rawText, song.lyricsFormat)
    val hasText = rawText.isNotEmpty()
    val status = when {
        song.lyricsStatus == LyricsResourceStatus.READ_ERROR -> LyricsDisplayStatus.READ_ERROR
        song.lyricsStatus == LyricsResourceStatus.MISSING && !hasText -> LyricsDisplayStatus.MISSING
        song.lyricsStatus == LyricsResourceStatus.UNKNOWN -> LyricsDisplayStatus.STATUS_UNKNOWN
        !hasText && song.lyricsStatus == LyricsResourceStatus.MISSING -> LyricsDisplayStatus.MISSING
        !hasText -> LyricsDisplayStatus.STATUS_UNKNOWN
        !document.formatSupported -> LyricsDisplayStatus.UNSUPPORTED_FORMAT
        document.synchronized && document.cues.isNotEmpty() -> LyricsDisplayStatus.SYNCHRONIZED
        song.lyricsFormat.equals("lrc", ignoreCase = true) -> LyricsDisplayStatus.NO_READABLE_CUES
        else -> LyricsDisplayStatus.PLAIN_TEXT
    }
    return LyricsPresentation(
        document = document,
        source = song.lyricsSource?.takeIf { it.isNotBlank() },
        resourceStatus = song.lyricsStatus,
        rawResourceStatus = song.rawLyricsStatus,
        status = status,
    )
}
