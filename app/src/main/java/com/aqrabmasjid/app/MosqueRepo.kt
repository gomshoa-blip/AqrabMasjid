package com.aqrabmasjid.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Mosque(val name: String, val lat: Double, val lng: Double, val distKm: Double)

/**
 * Finds mosques from OpenStreetMap by place type (not only by name),
 * so a place shows even if it is named مصلى or دار عبادة.
 * Also adds your own list (Config.CUSTOM_MOSQUES_URL).
 */
object MosqueRepo {
    // Several threads, so a slow request never blocks the next search
    private val exec = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var cache: Triple<Double, Double, List<Mosque>>? = null

    /** Callback runs on the main thread. Null list = network error. */
    fun nearby(c: Context, lat: Double, lng: Double, cb: (List<Mosque>?) -> Unit) {
        val cached = cache
        if (cached != null && distanceKm(lat, lng, cached.first, cached.second) < 0.3) {
            cb(cached.third.map { it.copy(distKm = distanceKm(lat, lng, it.lat, it.lng)) }.sortedBy { it.distKm })
            return
        }
        val app = c.applicationContext
        exec.execute {
            // Whole search must finish in about 20 seconds
            val deadline = System.currentTimeMillis() + SEARCH_BUDGET_MS
            var osm: List<Mosque>? = null
            try {
                osm = queryOsm(lat, lng, 3000, deadline)
                if (osm.size < 3 && System.currentTimeMillis() < deadline - 4000) {
                    osm = queryOsm(lat, lng, 10000, deadline)
                }
            } catch (_: Exception) {
            }
            val custom = try { queryCustom(lat, lng) } catch (_: Exception) { emptyList() }

            val result: List<Mosque>? =
                if (osm == null && custom.isEmpty()) null
                else dedupe((osm ?: emptyList()) + custom).sortedBy { it.distKm }.take(10)

            if (!result.isNullOrEmpty()) {
                cache = Triple(lat, lng, result)
                Prefs.saveNearest(app, result[0])
            }
            main.post { cb(result) }
        }
    }

    private const val SEARCH_BUDGET_MS = 20_000L

    /**
     * Same method as the website (it works for thousands of users):
     * ask all map servers at the same time and take the first good answer.
     * Search by place type only, so مصلى and دار عبادة still show.
     */
    private fun queryOsm(lat: Double, lng: Double, radius: Int, deadline: Long): List<Mosque> {
        val a = "(around:$radius,$lat,$lng)"
        val q = "[out:json][timeout:12];(" +
            "node[\"amenity\"=\"place_of_worship\"][\"religion\"=\"muslim\"]$a;" +
            "way[\"amenity\"=\"place_of_worship\"][\"religion\"=\"muslim\"]$a;" +
            "way[\"building\"=\"mosque\"]$a;" +
            ");out center 80;"
        val query = "?data=" + URLEncoder.encode(q, "UTF-8")
        val left = (deadline - System.currentTimeMillis()).coerceAtLeast(3000L)
        val timeout = minOf(left, 12_000L).toInt()

        val pool = Executors.newFixedThreadPool(Config.OVERPASS_URLS.size)
        val done = java.util.concurrent.ExecutorCompletionService<List<Mosque>>(pool)
        try {
            for (url in Config.OVERPASS_URLS) {
                done.submit { parseOsm(JSONObject(get(url + query, timeout)), lat, lng) }
            }
            var lastError: Exception? = null
            var gotEmpty = false
            repeat(Config.OVERPASS_URLS.size) {
                val f = done.poll(timeout + 1000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                    ?: return if (gotEmpty) emptyList() else throw (lastError ?: Exception("timeout"))
                try {
                    val list = f.get()
                    // A server sometimes answers with nothing: wait for the others first
                    if (list.isNotEmpty()) return list
                    gotEmpty = true
                } catch (e: Exception) {
                    lastError = e
                }
            }
            if (gotEmpty) return emptyList()
            throw lastError ?: Exception("no server")
        } finally {
            pool.shutdownNow()
        }
    }

    private fun parseOsm(root: JSONObject, lat: Double, lng: Double): List<Mosque> {
        val out = ArrayList<Mosque>()
        val els = root.optJSONArray("elements") ?: return out
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val center = e.optJSONObject("center")
            val la = if (e.has("lat")) e.optDouble("lat") else center?.optDouble("lat") ?: Double.NaN
            val lo = if (e.has("lon")) e.optDouble("lon") else center?.optDouble("lon") ?: Double.NaN
            if (la.isNaN() || lo.isNaN()) continue
            val tags = e.optJSONObject("tags")
            val name = tags?.optString("name:ar")?.takeIf { it.isNotBlank() }
                ?: tags?.optString("name")?.takeIf { it.isNotBlank() }
                ?: "مسجد"
            out += Mosque(name, la, lo, distanceKm(lat, lng, la, lo))
        }
        return out
    }

    private fun queryCustom(lat: Double, lng: Double): List<Mosque> {
        val text = get(Config.CUSTOM_MOSQUES_URL)
        val arr = JSONArray(text)
        val out = ArrayList<Mosque>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val la = o.getDouble("lat")
            val lo = o.getDouble("lng")
            val d = distanceKm(lat, lng, la, lo)
            val name = o.optString("name").takeIf { !o.isNull("name") && it.isNotBlank() } ?: "مسجد"
            if (d <= 15) out += Mosque(name, la, lo, d)
        }
        return out
    }

    /** Same place listed twice (within ~40 m) -> keep one, prefer a real name. */
    private fun dedupe(list: List<Mosque>): List<Mosque> {
        val out = ArrayList<Mosque>()
        for (m in list.sortedBy { if (it.name == "مسجد") 1 else 0 }) {
            if (out.none { distanceKm(it.lat, it.lng, m.lat, m.lng) < 0.04 }) out += m
        }
        return out
    }

    private fun get(url: String, timeoutMs: Int = 6000): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = minOf(timeoutMs, 6000)
        con.readTimeout = timeoutMs
        con.setRequestProperty("User-Agent", "AqrabMasjid-Android/1.0")
        try {
            if (con.responseCode != 200) throw Exception("HTTP ${con.responseCode}")
            return con.inputStream.bufferedReader().readText()
        } finally {
            con.disconnect()
        }
    }

    fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun fmtDistance(km: Double): String =
        if (km < 1) "${(km * 1000).toInt()} م" else String.format(java.util.Locale.US, "%.1f كم", km)
}
