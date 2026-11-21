package com.lite.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Checks which channels really answer: HLS playlists are fetched and their first segment is requested,
 * other links must answer 200. Used by Settings -> "Check channels" to hide the dead ones.
 */
object Checker {
    private val UAS = listOf(Net.UA, "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")

    private class Resp(val code: Int, val body: String, val url: String)

    private fun get(u: String, hd: Map<String, String>, ua: String, maxBytes: Int, range: Boolean): Resp {
        var cur = u
        repeat(5) {
            val c = URL(cur).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 5000; c.readTimeout = 6000; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", ua)
                hd.forEach { (k, v) -> if (k != "User-Agent") c.setRequestProperty(k, v) }
                if (range) c.setRequestProperty("Range", "bytes=0-2047")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return Resp(code, "", cur)
                    cur = URL(URL(cur), loc).toString()
                    return@repeat
                }
                if (code !in 200..299) return Resp(code, "", cur)
                val buf = ByteArray(maxBytes)
                var n = 0
                c.inputStream.use { ins -> while (n < maxBytes) { val r = ins.read(buf, n, maxBytes - n); if (r <= 0) break; n += r } }
                return Resp(code, String(buf, 0, n, Charsets.ISO_8859_1), cur)
            } finally { c.disconnect() }
        }
        return Resp(310, "", cur)
    }

    private fun probe(u: String, hd: Map<String, String>, ua: String, depth: Int = 0): Boolean {
        val r = get(u, hd, ua, 65536, false)
        if (r.code !in 200..299) return false
        val isPl = r.body.trimStart().startsWith("#EXTM3U")
        if (!isPl) return !u.contains(".m3u8", true)          // TS / progressive link that answered 200
        if (depth > 2) return true
        val next = r.body.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return false
        val abs = URL(URL(r.url), next).toString()
        return if (r.body.contains("#EXT-X-STREAM-INF")) probe(abs, hd, ua, depth + 1)
        else get(abs, hd, ua, 2048, true).code in 200..299
    }

    private fun alive(c: Channel): Boolean {
        val (u, hd) = splitHeaders(c.url)
        for (ua in UAS) {
            repeat(2) { try { if (probe(u, hd, hd["User-Agent"] ?: ua)) return true } catch (_: Exception) {} }
        }
        return false
    }

    /** Returns the dead channels. onProgress(done, total) is called from worker threads. */
    suspend fun dead(chs: List<Channel>, onProgress: (Int, Int) -> Unit): List<Channel> = coroutineScope {
        val sem = Semaphore(20)
        val done = AtomicInteger(0)
        chs.map { c ->
            async(Dispatchers.IO) {
                val ok = sem.withPermit { alive(c) }
                onProgress(done.incrementAndGet(), chs.size)
                if (ok) null else c
            }
        }.awaitAll().filterNotNull()
    }
}
