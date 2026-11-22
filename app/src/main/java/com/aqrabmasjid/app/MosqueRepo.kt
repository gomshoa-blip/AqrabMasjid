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
    private val exec = Executors.newSingleThreadExecutor()
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
            var osm: List<Mosque>? = null
            try {
                osm = queryOsm(lat, lng, 5000)
                if (osm.size < 3) osm = queryOsm(lat, lng, 15000)
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

    private fun queryOsm(lat: Double, lng: Double, radius: Int): List<Mosque> {
        val a = "(around:$radius,$lat,$lng)"
        val q = """
            [out:json][timeout:20];
            (
              nwr["amenity"="place_of_worship"]["religion"="muslim"]$a;
              nwr["building"="mosque"]$a;
              nwr["name"~"مسجد|جامع|مصلى|Masjid|Mosque|Musalla|Prayer room",i][!"highway"][!"place"][!"landuse"][!"shop"][!"office"][!"boundary"][!"railway"][!"public_transport"]$a;
            );
            out center 80;
        """.trimIndent()
        var lastError: Exception? = null
        for (url in Config.OVERPASS_URLS) {
            try {
                val body = "data=" + URLEncoder.encode(q, "UTF-8")
                val json = post(url, body)
                return parseOsm(JSONObject(json), lat, lng)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: Exception("no server")
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

    private fun post(url: String, body: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.requestMethod = "POST"
        con.connectTimeout = 15000
        con.readTimeout = 25000
        con.doOutput = true
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        con.setRequestProperty("User-Agent", "AqrabMasjid-Android/1.0")
        con.outputStream.use { it.write(body.toByteArray()) }
        try {
            if (con.responseCode != 200) throw Exception("HTTP ${con.responseCode}")
            return con.inputStream.bufferedReader().readText()
        } finally {
            con.disconnect()
        }
    }

    private fun get(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 10000
        con.readTimeout = 10000
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
