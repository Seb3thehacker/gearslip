package app.seb3thehacker.gearslip.media

import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class LyricLine(val timeMs: Long, val text: String)

/** [synced] means every line carries a time, so the current one can be followed along. */
class LyricsResult(val lines: List<LyricLine>, val synced: Boolean)

/**
 * Looks lyrics up on LRCLIB (lrclib.net), a free community database that needs no key.
 *
 * Media apps almost never publish lyrics in their session metadata, so this is the only general
 * source. It sends the track's title, artist, album and length to lrclib.net, which is why the
 * lookup happens only when the driver opens the lyrics view and never in the background.
 */
object Lyrics {

    private val cache = HashMap<String, LyricsResult?>()

    suspend fun find(title: String, artist: String, album: String, durationMs: Long): LyricsResult? {
        if (title.isBlank()) return null
        val key = "$title|$artist|$album|$durationMs"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                exact(title, artist, album, durationMs) ?: search(title, artist)
            }.onFailure { GearslipLog.w("lyrics: lookup failed: ${it.message}") }.getOrNull()
        }
        synchronized(cache) { cache[key] = result }
        return result
    }

    private fun exact(title: String, artist: String, album: String, durationMs: Long): LyricsResult? {
        if (artist.isBlank()) return null
        val query = buildString {
            append("track_name=").append(enc(title)).append("&artist_name=").append(enc(artist))
            if (album.isNotBlank()) append("&album_name=").append(enc(album))
            if (durationMs > 0) append("&duration=").append(durationMs / 1000)
        }
        return get("https://lrclib.net/api/get?$query")?.let { parse(JSONObject(it)) }
    }

    private fun search(title: String, artist: String): LyricsResult? {
        val body = get("https://lrclib.net/api/search?q=" + enc("$artist $title".trim())) ?: return null
        val hits = JSONArray(body)
        for (i in 0 until hits.length()) parse(hits.getJSONObject(i))?.let { return it }
        return null
    }

    private fun parse(json: JSONObject): LyricsResult? {
        val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) return LyricsResult(lines, synced = true)
        }
        val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return null
        return LyricsResult(plain.lines().map { LyricLine(0, it) }, synced = false)
    }

    /** `[01:23.45] words` - a line may carry several stamps when a chorus repeats. */
    private fun parseLrc(text: String): List<LyricLine> {
        val stamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
        return text.lines().flatMap { raw ->
            val stamps = stamp.findAll(raw).toList()
            val words = raw.replace(stamp, "").trim()
            stamps.map {
                val ms = ((it.groupValues[1].toLong() * 60 + it.groupValues[2].toDouble()) * 1000).toLong()
                LyricLine(ms, words)
            }
        }.sortedBy { it.timeMs }
    }

    private fun get(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "Gearslip (car media host)")
        }
        return try {
            if (connection.responseCode != 200) null else connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
