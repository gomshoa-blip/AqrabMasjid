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
            handler.postDelayed(this, 15_000)
        }
    }

    /** Updates only the ring every second, so the seconds count down smoothly. */
    private val ringTick = object : Runnable {
        override fun run() {
            Prefs.location(this@MainActivity)?.let { (lat, lng) -> RingFill.fill(this@MainActivity, ring, lat, lng) }
            handler.postDelayed(this, 1_000)
        }
    }

    private lateinit var tvHijri: TextView
    private lateinit var tvWeekday: TextView
    private lateinit var tvClock: TextView
    private lateinit var tvGregorian: TextView
    private lateinit var tvIqamaLeft: TextView
    private lateinit var ring: CountdownRing
    private lateinit var tvMethod: TextView
    private lateinit var tvStatus: TextView
    private lateinit var layoutTimes: LinearLayout
    private lateinit var layoutMosques: LinearLayout
    private lateinit var btnExact: Button

    private var nearest: Mosque? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        L.init(this)
        setContentView(R.layout.activity_main)
        applyLanguage()
        matchSystemBars()
        fitSystemBars()
        Alerts.createChannel(this)

        tvHijri = findViewById(R.id.tvHijri)
        tvWeekday = findViewById(R.id.tvWeekday)
        tvClock = findViewById(R.id.tvClock)
        tvGregorian = findViewById(R.id.tvGregorian)
        tvIqamaLeft = findViewById(R.id.tvIqamaLeft)
        ring = findViewById(R.id.ring)
        tvMethod = findViewById(R.id.tvMethod)
        tvStatus = findViewById(R.id.tvStatus)
        layoutTimes = findViewById(R.id.layoutTimes)
        layoutMosques = findViewById(R.id.layoutMosques)
        btnExact = findViewById(R.id.btnExact)

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnLang).setOnClickListener {
            L.setEnglish(this, !L.en)
            Alerts.createChannel(this)
            recreate()
        }
        findViewById<Button>(R.id.btnDriving).setOnClickListener {
            startActivity(Intent(this, DrivingActivity::class.java))
        }
        // Opens Google Maps searching "مسجد"; the user picks one
        findViewById<Button>(R.id.btnGo).setOnClickListener { MapsSearch.mosques(this) }
        findViewById<Button>(R.id.btnShare).setOnClickListener { share() }
        findViewById<Button>(R.id.btnAddMosque).setOnClickListener { openUrl(Config.ADD_MOSQUE_URL) }
        findViewById<Button>(R.id.btnDonate).setOnClickListener { openUrl(Config.DONATE_URL) }
        findViewById<Button>(R.id.btnQirat).setOnClickListener { openUrl(Config.QIRAT_URL) }
        findViewById<Button>(R.id.btnTarteel).setOnClickListener { openStore(Config.TARTEEL_PKG, null) }
        findViewById<Button>(R.id.btnWahy).setOnClickListener { openStore(Config.WAHY_PKG, null) }
        // Nearby mosques list removed: Google Maps is more accurate
        findViewById<View>(R.id.tvMosquesHeader).visibility = View.GONE
        layoutMosques.visibility = View.GONE
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
        CrashLog.showIfAny(this)
    }

    override fun onResume() {
        super.onResume()
        btnExact.visibility = if (Alerts.canExact(this)) View.GONE else View.VISIBLE
        handler.post(tick)
        handler.post(ringTick)
        Alerts.scheduleNext(this)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(ringTick)
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
        tvStatus.text = L.t("جاري تحديد موقعك...", "Finding your location...")
        LocationHelper.get(this) { loc ->
            if (loc == null) {
                tvStatus.text = L.t("اسمح للتطبيق بالموقع من الإعدادات", "Allow location for the app in Settings")
                return@get
            }
            tvStatus.text = ""
            renderTimes()
            Alerts.scheduleNext(this)
        }
    }

    private fun renderTimes() {
        val (lat, lng) = Prefs.location(this) ?: run {
            ring.update("", "", "--:--", L.t("حدّث موقعك لعرض المواقيت", "Update your location to see times"), 0f, false)
            return
        }
        val now = System.currentTimeMillis()
        val day = PrayerCalc.day(this, lat, lng, now)
        val st = PrayerCalc.status(this, lat, lng, now)

        // Hijri date without the weekday (the weekday has its own place in the middle)
        tvHijri.text = PrayerCalc.hijriText(now) + if (day.ramadan) L.t(" · رمضان", " · Ramadan") else ""
        tvWeekday.text = java.text.SimpleDateFormat("EEEE", L.locale).format(java.util.Date(now))
        tvClock.text = PrayerCalc.fmtTime(now)
        tvGregorian.text = java.text.SimpleDateFormat("d MMMM yyyy", L.locale).format(java.util.Date(now)) + L.t(" م", "")
        RingFill.fill(this, ring, lat, lng, now)
        // قيراط link: Riyadh only
        val riyadh = lat in 24.3..25.2 && lng in 46.2..47.3
        findViewById<View>(R.id.btnQirat).visibility = if (riyadh) View.VISIBLE else View.GONE
        tvIqamaLeft.text = L.t(
            "${st.time.prayer.ar}: الأذان ${PrayerCalc.fmtTime(st.time.adhan)} · الإقامة ${PrayerCalc.fmtTime(st.time.iqama)}",
            "${st.time.prayer.ar}: Adhan ${PrayerCalc.fmtTime(st.time.adhan)} · Iqama ${PrayerCalc.fmtTime(st.time.iqama)}"
        )
        tvMethod.text = L.t("طريقة الحساب: ${day.methodName}", "Method: ${day.methodName}")

        layoutTimes.removeAllViews()
        layoutTimes.addView(row(L.t("الصلاة", "Prayer"), L.t("الأذان", "Adhan"), L.t("الإقامة", "Iqama"), header = true, highlight = false))
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
        layoutMosques.addView(TextView(this).apply { text = L.t("جاري البحث...", "Searching..."); setPadding(dp(8), dp(12), dp(8), dp(12)) })
        MosqueRepo.nearby(this, lat, lng) { list ->
            layoutMosques.removeAllViews()
            if (list == null || list.isEmpty()) {
                val msg = if (list == null) L.t("ما قدرنا نجيب المساجد الحين. اضغط «حدّث موقعي» للمحاولة مرة ثانية.", "Could not load mosques now. Tap \"Update my location\" to try again.")
                else L.t("ما لقينا مساجد قريبة. اعرضها في خرائط Google، أو أضف مسجداً.", "No mosques found nearby. Show them in Google Maps, or add a mosque.")
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
                go.text = L.t("خذني", "Go")
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

    /** Opens an app page (or a search) in Google Play. */
    private fun openStore(pkg: String?, search: String?) {
        val q = Uri.encode(search ?: "")
        val market = if (pkg != null) "market://details?id=$pkg" else "market://search?q=$q&c=apps"
        val web = if (pkg != null) "https://play.google.com/store/apps/details?id=$pkg"
                  else "https://play.google.com/store/search?q=$q&c=apps"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(market)))
        } catch (_: Exception) {
            openUrl(web)
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
        startActivity(Intent.createChooser(i, L.t("شارك التطبيق", "Share the app")))
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
            tv.text = L.t("${p.ar} (دقيقة)", "${p.ar} (minutes)")
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
            .setTitle(L.t("وقت الإقامة بعد الأذان", "Iqama time after adhan"))
            .setView(box)
            .setPositiveButton(L.t("حفظ", "Save")) { _, _ ->
                pickers.forEach { (p, np) -> Prefs.setIqama(this, p, np.value) }
                renderTimes(); Alerts.scheduleNext(this)
            }
            .setNeutralButton(L.t("الافتراضي", "Default")) { _, _ ->
                Prayer.values().forEach { Prefs.setIqama(this, it, -1) }
                renderTimes(); Alerts.scheduleNext(this)
            }
            .setNegativeButton(L.t("إلغاء", "Cancel"), null)
            .show()
    }

    /** Fallback: open Google Maps and search for mosques around the user. */
    private fun mapsSearchButton(lat: Double, lng: Double): View {
        val b = Button(this)
        b.text = L.t("🔎 اعرض المساجد القريبة في خرائط Google", "🔎 Show nearby mosques in Google Maps")
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

    /** Set screen direction and all fixed texts in the chosen language. */
    private fun applyLanguage() {
        findViewById<View>(R.id.rootScroll).layoutDirection =
            if (L.en) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
        fun b(id: Int, ar: String, en: String) { findViewById<TextView>(id).text = L.t(ar, en) }
        b(R.id.btnLang, "🌐 English", "🌐 العربية")
        // Language button: top right in Arabic, top left in English
        findViewById<View>(R.id.btnLang).let { v ->
            (v.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.gravity = if (L.en) Gravity.LEFT else Gravity.RIGHT
                v.layoutParams = lp
            }
        }
        b(R.id.btnDriving, "🚗  وضع القيادة", "🚗  Driving mode")
        b(R.id.btnGo, "🕌  أقرب مسجد", "🕌  Nearest mosque")
        b(R.id.btnRefresh, "حدّث موقعي", "Update my location")
        b(R.id.tvMosquesHeader, "أقرب المساجد", "Nearest mosques")
        b(R.id.switchAlerts, "تنبيه عند الأذان، وبعده بـ 10 دقائق للإقامة", "Alert at adhan, and 10 minutes later for iqama")
        b(R.id.btnExact, "اسمح بالتنبيه في الوقت الدقيق", "Allow exact-time alerts")
        b(R.id.btnIqama, "وقت الإقامة لكل صلاة", "Iqama time for each prayer")
        b(R.id.btnShare, "شارك التطبيق - الدال على الخير كفاعله", "Share the app")
        b(R.id.btnAddMosque, "➕ أضف مسجداً", "➕ Add a mosque")
        b(R.id.btnDonate, "تبرع عبر منصة إحسان", "Donate via Ehsan")
        b(R.id.btnQirat, "قيراط - جنائز الرياض والمقابر", "Qirat - Riyadh funerals & cemeteries")
        b(R.id.tvQuranHeader, "تطبيقات قرآن ننصح بها", "Recommended Quran apps")
        b(R.id.btnTarteel, "📖 ترتيل", "📖 Tarteel")
        b(R.id.btnWahy, "📖 وحي", "📖 Wahy")
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
