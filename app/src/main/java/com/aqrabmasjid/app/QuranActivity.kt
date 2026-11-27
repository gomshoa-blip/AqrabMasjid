package com.aqrabmasjid.app

import android.content.Context
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** Full-screen mushaf: our simple Quran page (King Fahd Complex text and Hafs font). */
class QuranActivity : Activity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Look.wrap(base))   // day / night choice
    }


    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        web = WebView(this)
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        web.setBackgroundColor(Color.parseColor(if (night) "#111613" else "#FBF7EC"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true   // remembers the last page
        // Recitation goes from ayah to ayah by itself
        web.settings.mediaPlaybackRequiresUserGesture = false
        // Reading or listening: the screen stays on
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                // Stay on the mushaf page; any other link (like ✕) closes the screen
                if (req.url.path?.startsWith("/quran") == true) return false
                finish()
                return true
            }
        }
        setContentView(web)

        // Hide the top and bottom bars so the page fills the screen
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } catch (_: Exception) {
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else {
            // Opened from the Friday alert: go straight to a page (Al-Kahf = 293)
            val page = intent.getIntExtra("page", 0)
            web.loadUrl(if (page in 1..604) Config.QURAN_URL + "#p" + page else Config.QURAN_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
