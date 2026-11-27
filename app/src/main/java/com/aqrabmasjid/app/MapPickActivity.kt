package com.aqrabmasjid.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The user picks their place on a map (when the phone cannot find the location,
 * or the user wants to change it). Uses our site page /pick/ (OpenStreetMap, free).
 * The chosen place is saved for the whole app: Qibla, prayer times, nearest mosque.
 */
class MapPickActivity : Activity() {

    private lateinit var web: WebView

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"

        fun intent(c: Context): Intent = Intent(c, MapPickActivity::class.java)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        L.init(this)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0B100D"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setGeolocationEnabled(true)
        web.addJavascriptInterface(Bridge(), "AqrabApp")
        web.webChromeClient = object : WebChromeClient() {
            // "Use my phone location" button on the map: allowed when the app has location permission
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, LocationHelper.hasPermission(this@MapPickActivity), false)
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                // Stay on the map page; any other link closes the screen
                if (req.url.path?.startsWith("/pick") == true) return false
                finish()
                return true
            }
        }
        setContentView(web)

        val lang = if (L.en) "en" else "ar"
        var url = Config.SITE_URL + "/pick/?lang=" + lang
        Prefs.location(this)?.let { (lat, lng) -> url += "&lat=$lat&lng=$lng" }
        web.loadUrl(url)
    }

    /** Called from the map page. */
    inner class Bridge {
        @JavascriptInterface
        fun pick(lat: Double, lng: Double) {
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return
            runOnUiThread {
                LocationHelper.saveManual(this@MapPickActivity, lat, lng)
                setResult(RESULT_OK, Intent().putExtra(EXTRA_LAT, lat).putExtra(EXTRA_LNG, lng))
                finish()
            }
        }

        @JavascriptInterface
        fun cancel() {
            runOnUiThread { finish() }
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
