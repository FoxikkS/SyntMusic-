package com.syntmusic.lyrics

enum class LyricsType { SYNCED, PLAIN }

/** [timestamp] is in milliseconds; null for plain lyrics. */
data class LyricsLine(val timestamp: Long?, val text: String)

data class Lyrics(val type: LyricsType, val lines: List<LyricsLine>) {

    /** Index of the last line whose timestamp is <= [positionMs], or -1 (also for plain lyrics). */
    fun activeIndex(positionMs: Long): Int {
        if (type != LyricsType.SYNCED) return -1
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timestamp!! <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}

object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val metaTag = Regex("""^\[[A-Za-z#]+:.*]$""")
    private val wordTag = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    /**
     * Parses LRC (including multiple timestamps per line, [offset:], enhanced word tags).
     * Text without any timestamps is returned as plain lyrics; blank input returns null.
     */
    fun parse(raw: String): Lyrics? {
        val text = raw.removePrefix("﻿")
        val offset = offsetTag.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val synced = ArrayList<LyricsLine>()
        val plain = ArrayList<String>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            val stamps = ArrayList<Long>(1)
            var rest = line
            while (true) {
                val match = timeTag.find(rest)?.takeIf { it.range.first == 0 } ?: break
                stamps += match.toMillis()
                rest = rest.substring(match.range.last + 1).trimStart()
            }
            if (stamps.isNotEmpty()) {
                val content = rest.replace(wordTag, "").trim()
                stamps.forEach { synced += LyricsLine((it - offset).coerceAtLeast(0), content) }
            } else if (!metaTag.matches(line)) {
                plain += line
            }
        }

        if (synced.isNotEmpty()) {
            return Lyrics(LyricsType.SYNCED, synced.sortedBy { it.timestamp })
        }
        val lines = plain.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        if (lines.isEmpty()) return null
        return Lyrics(LyricsType.PLAIN, lines.map { LyricsLine(null, it) })
    }

    private fun MatchResult.toMillis(): Long {
        val (min, sec, frac) = destructured
        val fraction = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').toLong()
        return min.toLong() * 60_000 + sec.toLong() * 1_000 + fraction
    }
}
