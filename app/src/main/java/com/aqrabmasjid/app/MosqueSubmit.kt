package com.aqrabmasjid.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Sends "add a mosque" to our email form (FormSubmit), same as the site does.
 * The request arrives by email; we review it, then add it to mosques.json.
 */
object MosqueSubmit {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** cb(true) when the form was received. Runs cb on the main thread. */
    fun send(c: Context, name: String, lat: Double, lng: Double, cb: (Boolean) -> Unit) {
        val la = String.format(Locale.US, "%.6f", lat)
        val lo = String.format(Locale.US, "%.6f", lng)
        val fields = linkedMapOf(
            "_subject" to "أضف مسجداً – add-mosque",
            "_template" to "table",
            "_captcha" to "false",
            "mosque_name" to name.ifBlank { "مسجد" },
            "type" to "مسجد",
            "lat" to la,
            "lng" to lo,
            "location_link" to "https://maps.google.com/?q=$la,$lo",
            "country" to (Prefs.country(c) ?: ""),
            "source" to "app",
            "page_lang" to L.lang
        )
        val body = fields.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        exec.execute {
            val ok = try {
                val con = URL(Config.FORM_URL).openConnection() as HttpURLConnection
                con.requestMethod = "POST"
                con.doOutput = true
                con.instanceFollowRedirects = false
                con.connectTimeout = 10_000
                con.readTimeout = 15_000
                con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                con.setRequestProperty("Accept", "application/json")
                con.setRequestProperty("User-Agent", "AqrabMasjid-Android/1.0")
                con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = con.responseCode
                con.disconnect()
                code in 200..399
            } catch (_: Exception) {
                false
            }
            main.post { cb(ok) }
        }
    }
}
