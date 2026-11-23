package com.aqrabmasjid.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Driving mode: one simple dark screen with big text and one big button,
 * easy to read and tap in the car. The screen stays on.
 */
class DrivingActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 15_000)
        }
    }

    private lateinit var tvPrayer: TextView
    private lateinit var tvLeft: TextView
    private lateinit var tvTimes: TextView
    private lateinit var tvMosque: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= 30) {
            // Light icons (time, battery) on the black top bar
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(Color.BLACK)
        root.gravity = Gravity.CENTER_HORIZONTAL
        val pad = dp(20)
        root.setPadding(pad, pad, pad, pad)

        // Exit button (top)
        val exit = Button(this)
        exit.text = "✕  خروج من وضع القيادة"
        exit.isAllCaps = false
        exit.textSize = 16f
        exit.setTextColor(Color.parseColor("#BBBBBB"))
        exit.setBackgroundColor(Color.TRANSPARENT)
        exit.stateListAnimator = null
        exit.setOnClickListener { finish() }
        root.addView(exit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply { gravity = Gravity.START })

        // Big info area
        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.gravity = Gravity.CENTER

        tvPrayer = bigText(34f, Color.WHITE, bold = true)
        tvLeft = bigText(76f, Color.parseColor("#C9A227"), bold = true)
        tvTimes = bigText(22f, Color.parseColor("#DDDDDD"), bold = false)
        tvMosque = bigText(20f, Color.parseColor("#7FD3AE"), bold = false)
        info.addView(tvPrayer)
        info.addView(tvLeft)
        info.addView(tvTimes)
        info.addView(tvMosque, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        root.addView(info, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // One big button
        val go = Button(this)
        go.text = "🕌  خذني لأقرب مسجد"
        go.isAllCaps = false
        go.textSize = 30f
        go.setTypeface(null, Typeface.BOLD)
        go.setTextColor(Color.parseColor("#111111"))
        go.setBackgroundResource(R.drawable.bg_btn_gold)
        go.stateListAnimator = null
        go.setOnClickListener { goToNearest() }
        root.addView(go, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)))

        setContentView(root)

        // Keep content clear of the top and bottom bars (Android 15+)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
            }
            insets
        }

        // Refresh location and nearest mosque in the background
        LocationHelper.get(this) { loc ->
            if (loc != null) MosqueRepo.nearby(this, loc.first, loc.second) { render() }
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun bigText(size: Float, color: Int, bold: Boolean): TextView {
        val tv = TextView(this)
        tv.textSize = size
        tv.setTextColor(color)
        tv.gravity = Gravity.CENTER
        if (bold) tv.setTypeface(null, Typeface.BOLD)
        return tv
    }

    private fun render() {
        val (lat, lng) = Prefs.location(this) ?: run {
            tvPrayer.text = "افتح التطبيق واسمح بالموقع"
            tvLeft.text = ""
            tvTimes.text = ""
            tvMosque.text = ""
            return
        }
        val now = System.currentTimeMillis()
        val st = PrayerCalc.status(this, lat, lng, now)
        val t = st.time
        if (st.isCurrent) {
            tvPrayer.text = "الصلاة الحالية: ${t.prayer.ar}"
            val left = t.iqama - now
            tvLeft.text = if (left > 0) PrayerCalc.fmtLeft(left) else "أقيمت"
            tvTimes.text = if (left > 0) "على الإقامة · ${PrayerCalc.fmtTime(t.iqama)}" else ""
        } else {
            tvPrayer.text = t.prayer.ar
            tvLeft.text = PrayerCalc.fmtLeft(t.adhan - now)
            tvTimes.text = "الأذان ${PrayerCalc.fmtTime(t.adhan)} · الإقامة ${PrayerCalc.fmtTime(t.iqama)}"
        }
        val m = Prefs.nearest(this)
        tvMosque.text = if (m != null && m.distKm <= 15) "أقرب مسجد: ${m.name} · ${MosqueRepo.fmtDistance(m.distKm)}" else ""
    }

    private fun goToNearest() {
        val m = Prefs.nearest(this)?.takeIf { it.distKm <= 15 }
        val uri = if (m != null) Uri.parse("google.navigation:q=${m.lat},${m.lng}")
        else Uri.parse("geo:0,0?q=" + Uri.encode("مسجد"))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            val web = if (m != null) "https://www.google.com/maps/dir/?api=1&destination=${m.lat},${m.lng}"
            else "https://www.google.com/maps/search/?api=1&query=" + Uri.encode("مسجد")
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web))) } catch (_: Exception) {}
        }
    }
}
