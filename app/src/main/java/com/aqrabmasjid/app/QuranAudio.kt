package com.aqrabmasjid.app

import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import android.webkit.WebView
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Quran recitation saved on the phone, to listen without internet.
 * Files: filesDir/quran_audio/<reciter>/<sss><aaa>.mp3 (same names as everyayah.com).
 * The mushaf page asks for downloads through the "AqrabAudio" bridge, and the WebView
 * plays saved files instead of the internet ones (see [local]).
 */
class QuranAudio(private val act: Activity, private val web: WebView) {
    companion object {
        private val SAFE = Regex("^[A-Za-z0-9_.\\-]{3,60}$")
        fun root(act: Activity) = File(act.filesDir, "quran_audio")

        /** A saved file for this everyayah.com address, or null. */
        fun local(act: Activity, url: String): WebResourceResponse? {
            val m = Regex("^https://(?:www\\.)?everyayah\\.com/data/([^/]+)/(\\d{6})\\.mp3$").find(url) ?: return null
            val (rec, name) = m.destructured
            if (!SAFE.matches(rec)) return null
            val f = File(File(root(act), rec), "$name.mp3")
            if (!f.isFile || f.length() < 512) return null
            return try {
                WebResourceResponse("audio/mpeg", null, 200, "OK",
                    mapOf("Content-Length" to f.length().toString(), "Accept-Ranges" to "none", "Cache-Control" to "no-cache"),
                    FileInputStream(f))
            } catch (_: Exception) { null }
        }
    }

    @Volatile private var running = false
    @Volatile private var stop = false

    private fun dir(rec: String) = File(root(act), rec)
    private fun name(s: Int, a: Int) = String.format(java.util.Locale.US, "%03d%03d.mp3", s, a)

    /** Download surahs. list = JSON [[surah, ayahCount], ...]. Returns false if a download is already running. */
    @JavascriptInterface
    fun download(rec: String, list: String): Boolean {
        if (!SAFE.matches(rec) || running) return false
        val jobs = mutableListOf<Pair<Int, Int>>()
        try {
            val a = JSONArray(list)
            for (i in 0 until a.length()) {
                val x = a.getJSONArray(i); val s = x.getInt(0); val n = x.getInt(1)
                if (s in 1..114 && n in 1..286) jobs += s to n
            }
        } catch (_: Exception) { return false }
        if (jobs.isEmpty()) return false
        running = true; stop = false
        Thread {
            val d = dir(rec); d.mkdirs()
            val files = mutableListOf(1 to 1)   // "Bismillah" is played from Al-Fatiha 1
            jobs.forEach { (s, n) -> for (a in 1..n) files += s to a }
            val todo = files.distinct().filter { (s, a) -> !File(d, name(s, a)).let { it.isFile && it.length() > 512 } }
            var done = 0; var failed = 0
            for ((s, a) in todo) {
                if (stop) break
                if (!get("https://everyayah.com/data/$rec/${name(s, a)}", File(d, name(s, a)))) failed++
                done++
                if (done % 3 == 0 || done == todo.size) js("window.dlProgress && dlProgress($done,${todo.size},$failed)")
            }
            running = false
            js("window.dlDone && dlDone(${if (stop) 1 else 0},$failed)")
        }.start()
        return true
    }

    @JavascriptInterface fun cancel() { stop = true }
    @JavascriptInterface fun busy(): Boolean = running

    /** How many ayahs of this surah are saved. */
    @JavascriptInterface
    fun count(rec: String, s: Int): Int {
        if (!SAFE.matches(rec)) return 0
        val p = String.format(java.util.Locale.US, "%03d", s)
        return dir(rec).listFiles { f -> f.name.startsWith(p) && f.length() > 512 }?.size ?: 0
    }

    /** Saved size of this reciter in MB. */
    @JavascriptInterface
    fun sizeMB(rec: String): Int {
        if (!SAFE.matches(rec)) return 0
        return ((dir(rec).listFiles()?.sumOf { it.length() } ?: 0L) / (1024 * 1024)).toInt()
    }

    /** Delete one surah (s > 0) or all saved files of this reciter (s = 0). */
    @JavascriptInterface
    fun remove(rec: String, s: Int) {
        if (!SAFE.matches(rec) || running) return
        val p = String.format(java.util.Locale.US, "%03d", s)
        dir(rec).listFiles()?.forEach { if (s == 0 || it.name.startsWith(p)) it.delete() }
    }

    private fun get(url: String, out: File): Boolean {
        val tmp = File(out.path + ".part")
        for (attempt in 1..2) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 20000
                if (c.responseCode != 200) { c.disconnect(); return false }
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                c.disconnect()
                if (tmp.length() > 512 && tmp.renameTo(out)) return true
            } catch (_: Exception) { tmp.delete() }
        }
        return false
    }

    private fun js(code: String) = act.runOnUiThread { try { web.evaluateJavascript(code, null) } catch (_: Exception) {} }
}
