package com.aqrabmasjid.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderTimes()
            handler.postDelayed(this, 30_000)
        }
    }

    private lateinit var tvHijri: TextView
    private lateinit var tvNextTitle: TextView
    private lateinit var tvNextLeft: TextView
    private lateinit var tvIqamaLeft: TextView
    private lateinit var tvMethod: TextView
    private lateinit var tvStatus: TextView
    private lateinit var layoutTimes: LinearLayout
    private lateinit var layoutMosques: LinearLayout
    private lateinit var btnExact: Button

    private var nearest: Mosque? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        matchSystemBars()
        fitSystemBars()
        Alerts.createChannel(this)

        tvHijri = findViewById(R.id.tvHijri)
        tvNextTitle = findViewById(R.id.tvNextTitle)
        tvNextLeft = findViewById(R.id.tvNextLeft)
        tvIqamaLeft = findViewById(R.id.tvIqamaLeft)
        tvMethod = findViewById(R.id.tvMethod)
        tvStatus = findViewById(R.id.tvStatus)
        layoutTimes = findViewById(R.id.layoutTimes)
        layoutMosques = findViewById(R.id.layoutMosques)
        btnExact = findViewById(R.id.btnExact)

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnDriving).setOnClickListener {
            startActivity(Intent(this, DrivingActivity::class.java))
        }
        findViewById<Button>(R.id.btnGo).setOnClickListener {
            val m = nearest
            if (m != null) openMaps(m) else openUrl("https://www.google.com/maps/search/?api=1&query=" + Uri.encode("مسجد"))
        }
        findViewById<Button>(R.id.btnShare).setOnClickListener { share() }
        findViewById<Button>(R.id.btnAddMosque).setOnClickListener { openUrl(Config.ADD_MOSQUE_URL) }
        findViewById<Button>(R.id.btnDonate).setOnClickListener { openUrl(Config.DONATE_URL) }
        findViewById<Button>(R.id.btnIqama).setOnClickListener { iqamaDialog() }

        val sw = findViewById<Switch>(R.id.switchAlerts)
        sw.isChecked = Prefs.alertsOn(this)
        sw.setOnCheckedChangeListener { _, on ->
            Prefs.setAlerts(this, on)
            Alerts.scheduleNext(this)
        }

        btnExact.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                )
            }
        }

        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        btnExact.visibility = if (Alerts.canExact(this)) View.GONE else View.VISIBLE
        handler.post(tick)
        Alerts.scheduleNext(this)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun askPermissions() {
        val need = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) refresh() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        tvStatus.text = "جاري تحديد موقعك..."
        LocationHelper.get(this) { loc ->
            if (loc == null) {
                tvStatus.text = "اسمح للتطبيق بالموقع من الإعدادات"
                return@get
            }
            tvStatus.text = ""
            renderTimes()
            Alerts.scheduleNext(this)
            loadMosques(loc.first, loc.second)
        }
    }

    private fun renderTimes() {
        val (lat, lng) = Prefs.location(this) ?: run {
            tvNextTitle.text = "حدّث موقعك لعرض المواقيت"
            return
        }
        val now = System.currentTimeMillis()
        val day = PrayerCalc.day(this, lat, lng, now)
        val st = PrayerCalc.status(this, lat, lng, now)

        tvHijri.text = PrayerCalc.hijriText(now) + if (day.ramadan) " · رمضان كريم" else ""
        if (st.isCurrent) {
            tvNextTitle.text = "الصلاة الحالية: ${st.time.prayer.ar}"
            val left = st.time.iqama - now
            tvNextLeft.text = if (left > 0) "الإقامة بعد ${PrayerCalc.fmtLeft(left)}" else "أقيمت الصلاة"
            tvIqamaLeft.text = "الإقامة ${PrayerCalc.fmtTime(st.time.iqama)}"
        } else {
            tvNextTitle.text = "الصلاة القادمة: ${st.time.prayer.ar}"
            tvNextLeft.text = PrayerCalc.fmtLeft(st.time.adhan - now)
            tvIqamaLeft.text = "الأذان ${PrayerCalc.fmtTime(st.time.adhan)} · الإقامة ${PrayerCalc.fmtTime(st.time.iqama)}"
        }
        tvMethod.text = "طريقة الحساب: ${day.methodName}"

        layoutTimes.removeAllViews()
        layoutTimes.addView(row("الصلاة", "الأذان", "الإقامة", header = true, highlight = false))
        for (p in day.list) {
            layoutTimes.addView(
                row(p.prayer.ar, PrayerCalc.fmtTime(p.adhan), PrayerCalc.fmtTime(p.iqama),
                    header = false, highlight = p.prayer == st.time.prayer)
            )
        }
    }

    private fun row(a: String, b: String, c: String, header: Boolean, highlight: Boolean): View {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        l.setPadding(dp(6), dp(10), dp(6), dp(10))
        l.gravity = Gravity.CENTER_VERTICAL
        if (highlight) l.setBackgroundResource(R.drawable.bg_row_on)
        for (t in listOf(a, b, c)) {
            val tv = TextView(this)
            tv.text = t
            tv.textSize = if (header) 13f else 17f
            tv.gravity = Gravity.CENTER
            if (header) tv.setTextColor(getColor(R.color.text_hint))
            if (highlight) tv.setTypeface(null, Typeface.BOLD)
            l.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return l
    }

    private fun loadMosques(lat: Double, lng: Double) {
        layoutMosques.removeAllViews()
        layoutMosques.addView(TextView(this).apply { text = "جاري البحث..."; setPadding(dp(8), dp(12), dp(8), dp(12)) })
        MosqueRepo.nearby(this, lat, lng) { list ->
            layoutMosques.removeAllViews()
            if (list == null || list.isEmpty()) {
                val msg = if (list == null) "ما قدرنا نجيب المساجد الحين. اضغط «حدّث موقعي» للمحاولة مرة ثانية."
                else "ما لقينا مساجد قريبة. اعرضها في خرائط Google، أو أضف مسجداً."
                layoutMosques.addView(TextView(this).apply { text = msg; setPadding(dp(8), dp(12), dp(8), dp(12)) })
                layoutMosques.addView(mapsSearchButton(lat, lng))
                return@nearby
            }
            nearest = list.firstOrNull()
            for (m in list.take(3)) {
                val item = LinearLayout(this)
                item.orientation = LinearLayout.HORIZONTAL
                item.gravity = Gravity.CENTER_VERTICAL
                item.setPadding(dp(14), dp(10), dp(14), dp(10))
                item.setBackgroundResource(R.drawable.bg_card_white)
                val tv = TextView(this)
                tv.text = "${m.name}\n${MosqueRepo.fmtDistance(m.distKm)}"
                tv.textSize = 15f
                item.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val go = Button(this)
                go.text = "خذني"
                go.isAllCaps = false
                go.setTextColor(Color.WHITE)
                go.setTypeface(null, Typeface.BOLD)
                go.setBackgroundResource(R.drawable.bg_btn_green)
                go.stateListAnimator = null
                go.minHeight = dp(42); go.minimumHeight = dp(42)
                go.setPadding(dp(18), 0, dp(18), 0)
                go.setOnClickListener { openMaps(m) }
                item.addView(go, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(8)
                layoutMosques.addView(item, lp)
            }
        }
    }

    private fun openMaps(m: Mosque) {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${m.lat},${m.lng}"))
        try {
            startActivity(nav)
        } catch (_: Exception) {
            openUrl("https://www.google.com/maps/dir/?api=1&destination=${m.lat},${m.lng}")
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, Config.SHARE_TEXT)
        startActivity(Intent.createChooser(i, "شارك التطبيق"))
    }

    /** User can change iqama minutes for each prayer. */
    private fun iqamaDialog() {
        val loc = Prefs.location(this) ?: (24.7136 to 46.6753)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(40, 20, 40, 0)
        val pickers = mutableMapOf<Prayer, NumberPicker>()
        for (p in Prayer.values()) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            val tv = TextView(this)
            tv.text = "${p.ar} (دقيقة)"
            tv.textSize = 16f
            r.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val np = NumberPicker(this)
            np.minValue = 0
            np.maxValue = 60
            np.value = PrayerCalc.iqamaMinutes(this, loc.first, loc.second, p)
            r.addView(np)
            pickers[p] = np
            box.addView(r)
        }
        AlertDialog.Builder(this)
            .setTitle("وقت الإقامة بعد الأذان")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                pickers.forEach { (p, np) -> Prefs.setIqama(this, p, np.value) }
                renderTimes(); Alerts.scheduleNext(this)
            }
            .setNeutralButton("الافتراضي") { _, _ ->
                Prayer.values().forEach { Prefs.setIqama(this, it, -1) }
                renderTimes(); Alerts.scheduleNext(this)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    /** Fallback: open Google Maps and search for mosques around the user. */
    private fun mapsSearchButton(lat: Double, lng: Double): View {
        val b = Button(this)
        b.text = "🔎 اعرض المساجد القريبة في خرائط Google"
        b.isAllCaps = false
        b.setTextColor(getColor(R.color.accent_text))
        b.setBackgroundResource(R.drawable.bg_btn_outline)
        b.stateListAnimator = null
        b.minHeight = dp(50); b.minimumHeight = dp(50)
        b.setOnClickListener {
            val q = Uri.encode("مسجد")
            val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$q"))
            try { startActivity(geo) } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/$q/@$lat,$lng,15z")))
            }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        b.layoutParams = lp
        return b
    }

    /**
     * Android 15 draws the app under the status bar and the navigation bar.
     * Add padding so nothing is hidden at the top or bottom.
     */
    private fun fitSystemBars() {
        val root = (findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) ?: return
        val start = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(start[0] + b.left, start[1] + b.top, start[2] + b.right, start[3] + b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(start[0] + insets.systemWindowInsetLeft, start[1] + insets.systemWindowInsetTop,
                    start[2] + insets.systemWindowInsetRight, start[3] + insets.systemWindowInsetBottom)
            }
            insets
        }
        root.requestApplyInsets()
    }

    /** Dark icons on the top bar in day mode, light icons in night mode. */
    private fun matchSystemBars() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (night) 0 else light, light)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

}
