package com.syntmusic.lyrics

import android.content.Context
import com.syntmusic.data.Track
import com.syntmusic.data.UNKNOWN_ARTIST
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Synced lyrics from LRCLIB (https://lrclib.net), a free open lyrics database.
 * Results are cached on disk; "not found" is cached for a day, network errors are not cached.
 * Must be called off the main thread.
 */
class OnlineLyrics(context: Context) {

    private val cacheDir = File(context.filesDir, "lyrics-online").apply { mkdirs() }

    fun find(track: Track): Lyrics? {
        val (artist, title) = searchTerms(track)
        val key = "${artist.lowercase()}|${title.lowercase()}|${track.duration / 1000}".hashCode().toUInt()
        val hit = File(cacheDir, "$key.lrc")
        val miss = File(cacheDir, "$key.miss")
        if (hit.exists()) return LrcParser.parse(hit.readText())
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < MISS_TTL_MS) return null

        val text = try {
            query(track, artist, title)
        } catch (e: Exception) {
            return null
        }
        if (text.isNullOrBlank()) {
            miss.writeText("")
            return null
        }
        hit.writeText(text)
        miss.delete()
        return LrcParser.parse(text)
    }

    /** Returns synced lyrics if any, otherwise plain lyrics, otherwise null (not found). */
    private fun query(track: Track, artist: String, title: String): String? {
        val seconds = track.duration / 1000
        if (seconds > 0 && !track.album.startsWith("Unknown")) {
            get("get", "artist_name" to artist, "track_name" to title, "album_name" to track.album, "duration" to "$seconds")
                ?.let { JSONObject(it) }
                ?.let { o -> (o.text("syncedLyrics") ?: o.text("plainLyrics"))?.let { return it } }
        }
        val results = get("search", "track_name" to title, "artist_name" to artist)?.let(::JSONArray) ?: return null
        var plain: String? = null
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)
            val duration = o.optDouble("duration", -1.0)
            if (seconds > 0 && duration > 0 && abs(duration - seconds) > DURATION_TOLERANCE_S) continue
            o.text("syncedLyrics")?.let { return it }
            if (plain == null) plain = o.text("plainLyrics")
        }
        return plain
    }

    /** Body of a successful request, null on 404; throws on network / server errors. */
    private fun get(endpoint: String, vararg params: Pair<String, String>): String? {
        val query = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val connection = URL("https://lrclib.net/api/$endpoint?$query").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "SyntMusic/1.0")
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> null
                in 200..299 -> connection.inputStream.bufferedReader().use { it.readText() }
                else -> throw IOException("HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun searchTerms(track: Track): Pair<String, String> {
        var artist = track.artist
        var title = track.title
        // Untagged files often carry "Artist - Title" as the title.
        if (artist == UNKNOWN_ARTIST && " - " in title) {
            artist = title.substringBefore(" - ")
            title = title.substringAfter(" - ")
        }
        title = title.replace(noise, "").trim()
        return artist.trim() to title
    }

    private companion object {
        val noise = Regex("""\s*[(\[](official|lyric|audio|video|visualizer|hd|hq|4k|remaster)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
        const val TIMEOUT_MS = 8_000
        const val DURATION_TOLERANCE_S = 4
        const val MISS_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
