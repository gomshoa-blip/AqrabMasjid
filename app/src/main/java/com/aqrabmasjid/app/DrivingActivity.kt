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
    private lateinit var tvLeftLabel: TextView
    private lateinit var tvLeft: TextView
    private lateinit var tvTimes: TextView
    private lateinit var tvMosque: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        L.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = if (L.en) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(Color.BLACK)
        root.gravity = Gravity.CENTER_HORIZONTAL
        val pad = dp(20)
        root.setPadding(pad, pad, pad, pad)

        // Exit button (top): a clear rounded pill, easy to tap
        val exit = Button(this)
        exit.text = L.t("✕   خروج", "✕   Exit")
        exit.isAllCaps = false
        exit.textSize = 18f
        exit.setTypeface(null, Typeface.BOLD)
        exit.setTextColor(Color.WHITE)
        exit.setBackgroundResource(R.drawable.bg_btn_exit)
        exit.stateListAnimator = null
        exit.minHeight = 0
        exit.minimumHeight = 0
        exit.setPadding(dp(26), 0, dp(26), 0)
        exit.contentDescription = L.t("خروج من وضع القيادة", "Exit driving mode")
        exit.setOnClickListener { finish() }
        root.addView(exit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(52)).apply { gravity = Gravity.START })

        // Big info area
        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.gravity = Gravity.CENTER

        tvPrayer = bigText(60f, Color.WHITE, bold = true)
        tvLeftLabel = bigText(26f, Color.WHITE, bold = false)
        tvLeft = bigText(80f, Color.parseColor("#C9A227"), bold = true)
        tvTimes = bigText(22f, Color.parseColor("#DDDDDD"), bold = false)
        tvMosque = bigText(20f, Color.parseColor("#7FD3AE"), bold = false)
        info.addView(tvPrayer)
        info.addView(tvLeftLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        info.addView(tvLeft)
        info.addView(tvTimes)
        info.addView(tvMosque, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        root.addView(info, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // One big button
        val go = Button(this)
        go.text = L.t("🕌  خذني لأقرب مسجد", "🕌  Take me to nearest mosque")
        go.isAllCaps = false
        go.textSize = 30f
        go.setTypeface(null, Typeface.BOLD)
        go.setTextColor(Color.parseColor("#111111"))
        go.setBackgroundResource(R.drawable.bg_btn_gold)
        go.stateListAnimator = null
        go.setOnClickListener { goToNearest() }
        root.addView(go, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150)))

        setContentView(root)

        // Light icons (time, battery) on the black top bar
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                window.insetsController?.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } catch (_: Exception) {
            }
        }

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
            tvPrayer.text = L.t("افتح التطبيق واسمح بالموقع", "Open the app and allow location")
            tvLeftLabel.text = ""
            tvLeft.text = ""
            tvTimes.text = ""
            tvMosque.text = ""
            return
        }
        val now = System.currentTimeMillis()
        val st = PrayerCalc.status(this, lat, lng, now)
        val t = st.time
        if (st.isCurrent) {
            tvPrayer.text = t.prayer.ar
            val left = t.iqama - now
            if (left > 0) {
                tvLeftLabel.text = L.t("تقام الصلاة بعد", "Iqama in")
                tvLeft.textSize = 80f
                tvLeft.text = PrayerCalc.fmtLeft(left)
                tvTimes.text = L.t("الإقامة ${PrayerCalc.fmtTime(t.iqama)}", "Iqama ${PrayerCalc.fmtTime(t.iqama)}")
            } else {
                tvLeftLabel.text = ""
                tvLeft.textSize = 44f
                tvLeft.text = L.t("أقيمت الصلاة", "Prayer started")
                tvTimes.text = ""
            }
        } else {
            tvPrayer.text = t.prayer.ar
            tvLeftLabel.text = L.t("يؤذن بعد", "Adhan in")
            tvLeft.textSize = 80f
            tvLeft.text = PrayerCalc.fmtLeft(t.adhan - now)
            tvTimes.text = L.t("الأذان ${PrayerCalc.fmtTime(t.adhan)} · الإقامة ${PrayerCalc.fmtTime(t.iqama)}", "Adhan ${PrayerCalc.fmtTime(t.adhan)} · Iqama ${PrayerCalc.fmtTime(t.iqama)}")
        }
        val m = Prefs.nearest(this)
        tvMosque.text = if (m != null && m.distKm <= 15) L.t("أقرب مسجد: ${m.name} · ${MosqueRepo.fmtDistance(m.distKm)}", "Nearest mosque: ${m.name} · ${MosqueRepo.fmtDistance(m.distKm)}") else ""
    }

    /** Opens Google Maps with nearby mosques (or along the route if navigating). */
    private fun goToNearest() {
        MapsSearch.mosques(this)
    }
}
