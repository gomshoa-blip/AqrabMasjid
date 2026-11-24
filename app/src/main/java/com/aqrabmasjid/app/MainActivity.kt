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
            Prefs.location(this@MainActivity)?.let { (lat, lng) -> fillHero(lat, lng, System.currentTimeMillis()) }
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
    private lateinit var btnExact: View
    private lateinit var tvHeroLabel: TextView
    private lateinit var tvHeroName: TextView
    private lateinit var tvHeroAdhan: TextView

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
        tvHeroLabel = findViewById(R.id.tvHeroLabel)
        tvHeroName = findViewById(R.id.tvHeroName)
        tvHeroAdhan = findViewById(R.id.tvHeroAdhan)
        ring = findViewById(R.id.ring)
        tvMethod = findViewById(R.id.tvMethod)
        tvStatus = findViewById(R.id.tvStatus)
        layoutTimes = findViewById(R.id.layoutTimes)
        btnExact = findViewById(R.id.btnExact)
        reminderBtn = findViewById(R.id.btnTasbih)
        // Round corners also cut the star pattern inside the prayer card
        findViewById<View>(R.id.hero).clipToOutline = true

        findViewById<View>(R.id.btnLang).setOnClickListener {
            L.setEnglish(this, !L.en)
            Alerts.createChannel(this)
            recreate()
        }
        // Mode: driving shows only the car part; home shows the full app
        findViewById<View>(R.id.btnMode).setOnClickListener {
            val open = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("drive", true)
            setMode(!open)
            // Opening "More": scroll down to the rest of the app
            if (open) findViewById<android.widget.ScrollView>(R.id.rootScroll).post {
                findViewById<android.widget.ScrollView>(R.id.rootScroll).smoothScrollTo(0, findViewById<View>(R.id.homeSection).top)
            }
        }
        setMode(getSharedPreferences("ui", MODE_PRIVATE).getBoolean("drive", true))
        // Nearest mosque / Jami: open Google Maps search
        findViewById<View>(R.id.btnGo).setOnClickListener { MapsSearch.mosques(this) }
        findViewById<View>(R.id.btnJami).setOnClickListener { MapsSearch.jami(this) }
        // Services
        findViewById<View>(R.id.tileQuran).setOnClickListener { startActivity(Intent(this, QuranActivity::class.java)) }
        findViewById<View>(R.id.tileAdhkar).setOnClickListener { startActivity(Intent(this, AdhkarActivity::class.java)) }
        findViewById<View>(R.id.tileQirat).setOnClickListener { openUrl(Config.QIRAT_URL) }
        findViewById<View>(R.id.btnQibla).setOnClickListener { startActivity(Intent(this, QiblaActivity::class.java)) }
        findViewById<View>(R.id.tileContact).setOnClickListener { openUrl(Config.WHATSAPP_URL) }
        findViewById<View>(R.id.tileShare).setOnClickListener { share() }
        findViewById<View>(R.id.tileAdd).setOnClickListener { openUrl(Config.ADD_MOSQUE_URL) }
        findViewById<View>(R.id.btnTarteel).setOnClickListener { openStore(Config.TARTEEL_PKG, null) }
        findViewById<View>(R.id.btnWahy).setOnClickListener { openStore(Config.WAHY_PKG, null) }
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
        // Update the location automatically when the app comes back
        if (resumedOnce && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) refresh()
        resumedOnce = true
    }

    private var resumedOnce = false

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
            tvHeroLabel.text = L.t("حدّث موقعك لعرض المواقيت", "Allow location to see prayer times")
            ring.visibility = View.GONE
            return
        }
        val now = System.currentTimeMillis()
        val day = PrayerCalc.day(this, lat, lng, now)
        val st = PrayerCalc.status(this, lat, lng, now)

        tvHijri.text = PrayerCalc.hijriText(now) + if (day.ramadan) L.t(" · رمضان", " · Ramadan") else ""
        tvWeekday.text = java.text.SimpleDateFormat("EEEE", L.locale).format(java.util.Date(now))
        tvClock.text = PrayerCalc.fmtTime(now)
        tvGregorian.text = java.text.SimpleDateFormat("d MMMM yyyy", L.locale).format(java.util.Date(now)) + L.t(" م", "")
        fillHero(lat, lng, now)
        reminderBtn.text = PrayerReminders.ayah(st.time.prayer)
        // قيراط: Riyadh only
        val riyadh = lat in 24.3..25.2 && lng in 46.2..47.3
        findViewById<View>(R.id.tileQirat).visibility = if (riyadh) View.VISIBLE else View.GONE
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

    /**
     * Prayer card: name, adhan and iqama times.
     * The ring shows only in the last 30 min before adhan (gold) and between adhan and iqama (green).
     */
    private var heroNameBig = false

    /** Prayer name size: fills the card width when there is no ring, normal size with the ring. */
    private fun sizeHeroName() {
        val drive = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("drive", true)
        val ringOn = ring.visibility == View.VISIBLE
        val info = findViewById<View>(R.id.heroInfo)
        val avail = info.width.toFloat()
        if (avail <= 0) { tvHeroName.post { sizeHeroName() }; return }
        // Biggest size that fits on one line; smaller cap while the ring is showing
        val cap = dp(if (!ringOn) (if (drive) 130 else 80) else (if (drive) 64 else 46)).toFloat()
        val p = android.graphics.Paint(tvHeroName.paint)
        p.textSize = 100f
        val w = p.measureText(tvHeroName.text.toString()).coerceAtLeast(1f)
        val px = (100f * avail / w * 0.95f).coerceAtMost(cap)
        if (kotlin.math.abs(tvHeroName.textSize - px) > 1f)
            tvHeroName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px)
    }

    private fun fillHero(lat: Double, lng: Double, now: Long) {
        val st = PrayerCalc.status(this, lat, lng, now)
        val t = st.time
        // Left side: next prayer, then adhan and iqama times (always)
        tvHeroName.text = t.prayer.ar
        heroNameBig = false
        tvHeroAdhan.text = L.t("الأذان ${PrayerCalc.fmtTime(t.adhan)}", "Adhan ${PrayerCalc.fmtTime(t.adhan)}")
        tvIqamaLeft.text = L.t("الإقامة ${PrayerCalc.fmtTime(t.iqama)}", "Iqama ${PrayerCalc.fmtTime(t.iqama)}")
        // Right side: countdown ring only in the last 30 min before adhan, and from adhan to iqama
        val w = 30 * 60_000L
        if (!st.isCurrent) {
            tvHeroLabel.text = L.t("الصلاة القادمة", "Next prayer")
            val left = t.adhan - now
            if (left in 1..w) {
                ring.visibility = View.VISIBLE
                ring.update("", "", CountdownRing.clock(left), L.t("متبقية للأذان", "to adhan"), left.toFloat() / w, false)
            } else ring.visibility = View.GONE
        } else {
            val left = t.iqama - now
            if (left > 0) {
                tvHeroLabel.text = L.t("حان وقت الصلاة", "Prayer time now")
                ring.visibility = View.VISIBLE
                ring.update("", "", CountdownRing.clock(left), L.t("متبقية للإقامة", "to iqama"),
                    left.toFloat() / (t.iqama - t.adhan).coerceAtLeast(1L), true)
            } else {
                tvHeroLabel.text = L.t("أقيمت الصلاة", "Prayer has started")
                ring.visibility = View.GONE
            }
        }
        sizeHeroName()
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
            tv.setTextColor(getColor(when {
                header -> R.color.text_hint
                highlight -> R.color.title_text
                else -> R.color.text_primary
            }))
            if (header || highlight) tv.setTypeface(null, Typeface.BOLD)
            l.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return l
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
        findViewById<View>(R.id.rootFrame).layoutDirection =
            if (L.en) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
        fun b(id: Int, ar: String, en: String) { findViewById<TextView>(id).text = L.t(ar, en) }
        b(R.id.btnLang, "English", "العربية")
        b(R.id.btnGoText, "أقرب مسجد", "Nearest mosque")
        b(R.id.btnJamiText, "أقرب جامع", "Nearest Jami")
        b(R.id.tvServices, "الخدمات", "Services")
        b(R.id.tileQuranText, "القرآن", "Quran")
        b(R.id.tileAdhkarText, "الأذكار", "Adhkar")
        b(R.id.tileQiratText, "قيراط", "Qirat")
        b(R.id.btnQibla, "القبلة", "Qibla")
        b(R.id.tileContactText, "تواصل معنا", "Contact us")
        b(R.id.tileShareText, "شارك", "Share")
        b(R.id.tileAddText, "أضف مسجد", "Add a mosque")
        b(R.id.tvMore, "المزيد", "More")
        b(R.id.switchAlerts, "تنبيه عند الأذان، وبعده بـ 10 دقائق للإقامة", "Alert at adhan, and 10 minutes later for iqama")
        b(R.id.btnExact, "اسمح بالتنبيه في الوقت الدقيق", "Allow exact-time alerts")
        b(R.id.btnTarteelText, "ترتيل", "Tarteel")
        b(R.id.btnWahyText, "وحي", "Wahy")
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

    private lateinit var reminderBtn: TextView

    private fun tvHeroNameView(): TextView = findViewById(R.id.tvHeroName)

    private fun setMode(drive: Boolean) {
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("drive", drive).apply()
        findViewById<View>(R.id.homeSection).visibility = if (drive) View.GONE else View.VISIBLE
        // The button shows where it will take you: wheel -> driving, house -> full app
        // Main screen = car part only. "More" opens the rest; "Hide" closes it.
        findViewById<android.widget.ImageView>(R.id.icMode).setImageResource(if (drive) R.drawable.ic_apps else R.drawable.ic_up)
        findViewById<TextView>(R.id.tvMode).text = if (drive) L.t("المزيد", "More") else L.t("إخفاء", "Hide")
        // Driving: bigger card and buttons so they are easy to read and tap in the car
        val big = drive
        // Driving: the car part fills the whole screen (card, buttons, ayah, switch spread out)
        val car = findViewById<LinearLayout>(R.id.carSection)
        car.layoutParams = car.layoutParams.also { it.height = if (big) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT }
        fun weigh(id: Int, w: Float) {
            val v = findViewById<View>(id)
            val lp = v.layoutParams as LinearLayout.LayoutParams
            lp.height = if (big) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
            lp.weight = if (big) w else 0f
            v.layoutParams = lp
        }
        weigh(R.id.hero, 1f)
        // Buttons: fixed height, icon above the words in driving mode
        findViewById<View>(R.id.ctaRow).layoutParams = findViewById<View>(R.id.ctaRow).layoutParams.also {
            it.height = if (big) dp(150) else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        for (id in listOf(R.id.btnGo, R.id.btnJami)) {
            val b = findViewById<LinearLayout>(id)
            b.orientation = if (big) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            b.gravity = if (big) android.view.Gravity.CENTER else android.view.Gravity.CENTER_VERTICAL
        }
        findViewById<LinearLayout>(R.id.btnGoTexts).gravity = if (big) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START
        // Card: in driving mode the info is on top and a bigger ring sits under it
        val row = findViewById<LinearLayout>(R.id.heroRow)
        val infoV = findViewById<View>(R.id.heroInfo)
        // Date + time: at the bottom of the card (big) in the short view, under the times in the full view
        val dateV = findViewById<LinearLayout>(R.id.heroDate)
        row.removeAllViews()
        (dateV.parent as? ViewGroup)?.removeView(dateV)
        for ((tv, bigSz, smallSz) in listOf(Triple(tvWeekday, 24f, 15f), Triple(tvClock, 24f, 15f),
                Triple(tvHijri, 20f, 13f), Triple(tvGregorian, 20f, 13f)))
            tv.textSize = if (big) bigSz else smallSz
        if (big) {
            row.orientation = LinearLayout.VERTICAL
            row.gravity = android.view.Gravity.CENTER
            row.addView(infoV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(ring, LinearLayout.LayoutParams(dp(210), dp(210)).also { it.topMargin = dp(16); it.gravity = android.view.Gravity.CENTER_HORIZONTAL })
            row.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
            row.addView(dateV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.gravity = android.view.Gravity.CENTER_HORIZONTAL })
        } else {
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.addView(ring, LinearLayout.LayoutParams(dp(140), dp(140)).also { it.marginEnd = dp(10) })
            row.addView(infoV, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            (infoV as LinearLayout).addView(dateV)
        }
        findViewById<View>(R.id.heroRow).layoutParams = findViewById<View>(R.id.heroRow).layoutParams.also {
            it.height = if (big) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        findViewById<TextView>(R.id.btnTasbih).textSize = if (big) 24f else 21f
        findViewById<TextView>(R.id.tvHeroAdhan).textSize = if (big) 24f else 17f
        findViewById<TextView>(R.id.tvIqamaLeft).textSize = if (big) 24f else 17f
        for (id in listOf(R.id.btnGo, R.id.btnJami)) {
            val v = findViewById<View>(id)
            v.layoutParams = v.layoutParams.also { it.height = if (big) ViewGroup.LayoutParams.MATCH_PARENT else dp(76) }
        }
        findViewById<TextView>(R.id.btnGoText).textSize = if (big) 24f else 18f
        findViewById<TextView>(R.id.btnJamiText).textSize = if (big) 24f else 17f
        findViewById<View>(R.id.ring).requestLayout()
        tvHeroName.post { sizeHeroName() }
        // Keep the screen on while the short (car) view is shown
        if (drive) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

}
