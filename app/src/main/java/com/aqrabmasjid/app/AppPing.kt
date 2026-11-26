package com.aqrabmasjid.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Counts app users in Google Analytics (the same account as the website), once a day.
 * A hidden web page is opened in the background; it sends only the app language and version.
 * No location, no name, no phone number.
 */
object AppPing {
    private const val DAY = 24 * 60 * 60 * 1000L

    @SuppressLint("SetJavaScriptEnabled")
    fun maybeSend(c: Context) {
        val sp = c.getSharedPreferences("aqrab", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("ping_at", 0L) < DAY) return
        sp.edit().putLong("ping_at", now).apply()
        try {
            val ver = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
            val w = WebView(c.applicationContext)
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            w.loadUrl(Config.SITE_URL + "/app/ping.html?lang=" + L.lang + "&v=" + ver)
            // Close it after the page had time to send
            Handler(Looper.getMainLooper()).postDelayed({ try { w.destroy() } catch (_: Exception) {} }, 20_000)
        } catch (_: Exception) {
        }
    }
}
