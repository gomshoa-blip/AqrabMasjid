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
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_RINGTONE = 51
        private const val REQ_AUDIO = 52
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderTimes()
            handler.postDelayed(this, 15_000)
        }
    }

    /**
     * Updates the prayer card. Every second only while the countdown ring is showing
     * (last 30 min before adhan, and adhan to iqama) or the radio is on; otherwise every
     * 30 seconds, to save battery.
     */
    private val ringTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            var delay = 30_000L
            Prefs.location(this@MainActivity)?.let { (lat, lng) ->
                fillHero(lat, lng, now)
                if (ring.visibility == View.VISIBLE) delay = 1_000L
                else heroStatus?.let { st ->
                    // Wake up just when the 30-minute countdown should start
                    if (!st.isCurrent) {
                        val untilRing = st.time.adhan - 60 * 60_000L - now
                        if (untilRing in 1 until delay) delay = untilRing.coerceAtLeast(1_000L)
                    }
                }
            }
            if (RadioService.current != null) { showRadioBar(); delay = 1_000L }
            handler.postDelayed(this, delay)
        }
    }

    // Saved prayer status, so the times are not calculated again every second
    private var heroStatus: Status? = null
    private var heroStatusAt = 0L
    private var heroStatusLoc: Pair<Double, Double>? = null

    private fun statusNow(lat: Double, lng: Double, now: Long): Status {
        val c = heroStatus
        val stale = c == null || heroStatusLoc != (lat to lng) || now - heroStatusAt > 60_000L ||
            (!c.isCurrent && now >= c.time.adhan) || (c.isCurrent && now >= c.time.iqama)
        if (!stale) return c!!
        val st = PrayerCalc.status(this, lat, lng, now)
        heroStatus = st; heroStatusAt = now; heroStatusLoc = lat to lng
        return st
    }

    /** Sets text only when it changed (avoids redrawing the screen for nothing). */
    private fun setIf(tv: TextView, s: String) { if (tv.text.toString() != s) tv.text = s }

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
        // Urdu and Bengali letters are not in the Arabic font: use the phone's font
        if (L.systemFont) setTheme(R.style.AppTheme_SystemFont)
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
            // Pick the app language
            val names = L.LANGS.map { it.second }.toTypedArray()
            val cur = L.LANGS.indexOfFirst { it.first == L.lang }
            AlertDialog.Builder(this)
                .setTitle("🌐 " + L.t("اللغة", "Language"))
                .setSingleChoiceItems(names, cur) { d, i ->
                    d.dismiss()
                    if (i != cur) {
                        L.setLang(this, L.LANGS[i].first)
                        Alerts.createChannel(this)
                        PrayerWidgets.updateAll(this)
                        recreate()
                    }
                }
                .show()
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
        findViewById<View>(R.id.tileAdd).setOnClickListener { addMosqueDialog() }
        findViewById<View>(R.id.btnPrivacy).setOnClickListener { openUrl(Config.PRIVACY_URL) }
        // Radio card: Quran (right) and Sunnah (left) select at once; the middle button plays / pauses
        findViewById<View>(R.id.radioStQuran).setOnClickListener { radioSelect("quran") }
        findViewById<View>(R.id.radioStSunnah).setOnClickListener { radioSelect("sunnah") }
        findViewById<View>(R.id.radioMore)?.setOnClickListener { radioDialog() }
        findViewById<View>(R.id.radioSave).setOnClickListener { saveRadioClip() }
        findViewById<View>(R.id.radioRecs).setOnClickListener { radioClipsDialog() }
        findViewById<View>(R.id.radioPlay).setOnClickListener {
            if (RadioService.current != null && !RadioService.paused) RadioService.stop(this)
            else RadioService.play(this, radioSel())
            showRadioBar()
            handler.removeCallbacks(ringTick); handler.post(ringTick)   // refresh the status every second
        }
        findViewById<View>(R.id.btnAbout).setOnClickListener { openUrl(Config.ABOUT_URL) }
        findViewById<View>(R.id.tileNear).setOnClickListener { startActivity(Intent(this, MosquesActivity::class.java)) }
        findViewById<View>(R.id.btnTarteel).setOnClickListener { openStore(Config.TARTEEL_PKG, null) }
        findViewById<View>(R.id.btnWahy).setOnClickListener { openStore(Config.WAHY_PKG, null) }
        val sw = findViewById<Switch>(R.id.switchAlerts)
        sw.isChecked = Prefs.alertsOn(this)
        sw.setOnCheckedChangeListener { _, on ->
            Prefs.setAlerts(this, on)
            Alerts.scheduleNext(this)
            updateAlertBanner()
            if (on) {
                // Turned on again: explain what is missing (if anything) right away
                getSharedPreferences("ui", MODE_PRIVATE).edit().remove("notifAsked").remove("exactAsked").apply()
                checkAlerts()
            }
        }

        showTone()
        findViewById<View>(R.id.btnTone).setOnClickListener { toneDialog() }

        // Car: riding dua and morning/evening adhkar when the phone connects to the car (Bluetooth)
        val swDua = findViewById<Switch>(R.id.switchCarDua)
        val swAdhkar = findViewById<Switch>(R.id.switchCarAdhkar)
        swDua.text = L.t("🚗 دعاء الركوب عند الاتصال بالسيارة", "🚗 Riding dua when the car connects")
        swAdhkar.text = L.t("🌅 أذكار الصباح والمساء عند الاتصال ببلوتوث السيارة", "🌅 Morning/evening adhkar when car Bluetooth connects")
        swDua.isChecked = CarVoice.duaOn(this) && CarVoice.hasBtPermission(this)
        swAdhkar.isChecked = CarVoice.adhkarOn(this) && CarVoice.hasBtPermission(this)
        val carListener = android.widget.CompoundButton.OnCheckedChangeListener { b, on ->
            if (b.id == R.id.switchCarDua) CarVoice.setDua(this, on) else CarVoice.setAdhkar(this, on)
            if (on && !CarVoice.hasBtPermission(this) && Build.VERSION.SDK_INT >= 31)
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
        }
        swDua.setOnCheckedChangeListener(carListener)
        swAdhkar.setOnCheckedChangeListener(carListener)

        // Warning button: opens the settings page for what is missing
        btnExact.setOnClickListener {
            if (btnExact.tag == "notif") openNotifSettings() else openExactSettings()
        }

        askPermissions()
        CrashLog.showIfAny(this)
    }

    override fun onResume() {
        super.onResume()
        updateAlertBanner()
        // Count app users once a day (Google Analytics, nothing personal)
        handler.postDelayed({ if (!isFinishing) AppPing.maybeSend(this) }, 3_000)
        // Back from Settings (or the app opened again): check the alerts can work
        if (resumedOnce) checkAlerts()
        handler.post(tick)
        handler.post(ringTick)
        Alerts.scheduleNext(this)
        PrayerWidgets.updateAll(this)
        // Update the location automatically when the app comes back
        if (resumedOnce && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) refresh()
        resumedOnce = true
    }

    private var resumedOnce = false

    override fun onDestroy() {
        stopClip()
        super.onDestroy()
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
        if (missing.isEmpty()) { refresh(); checkAlerts() } else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            // "Nearby devices" refused: the car features can't work, so show them off
            if (!CarVoice.hasBtPermission(this)) {
                findViewById<Switch>(R.id.switchCarDua).isChecked = false
                findViewById<Switch>(R.id.switchCarAdhkar).isChecked = false
                Toast.makeText(this, L.t("نحتاج إذن «الأجهزة القريبة» لنعرف أن الجوال اتصل بالسيارة",
                    "We need the \"Nearby devices\" permission to know the phone connected to the car"), Toast.LENGTH_LONG).show()
            }
            return
        }
        refresh()
        updateAlertBanner()
        checkAlerts()
    }

    // ---- Alerts need: notifications allowed + exact time allowed (Android 12 and newer) ----

    private fun notifOk(): Boolean =
        try { androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled() } catch (_: Exception) { true }

    /** The button on the main screen shows what is missing, and hides when all is fine. */
    private fun updateAlertBanner() {
        val tv = btnExact as? TextView ?: return
        val on = Prefs.alertsOn(this)
        when {
            on && !notifOk() -> {
                tv.tag = "notif"
                tv.text = L.t("⚠️ الإشعارات مغلقة، فلن يصلك تنبيه الأذان. اضغط هنا لفتحها",
                    "⚠️ Notifications are off, so you will not get adhan alerts. Tap here to turn them on")
                tv.visibility = View.VISIBLE
            }
            on && !Alerts.canExact(this) -> {
                tv.tag = "exact"
                tv.text = L.t("اسمح بالتنبيه في الوقت الدقيق", "Allow exact-time alerts")
                tv.visibility = View.VISIBLE
            }
            else -> tv.visibility = View.GONE
        }
    }

    /** Explain in a short message what is missing. Asks again only after 3 days. */
    private fun checkAlerts() {
        if (!Prefs.alertsOn(this) || isFinishing) return
        val sp = getSharedPreferences("ui", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val wait = 3L * 24 * 60 * 60 * 1000
        if (!notifOk()) {
            if (now - sp.getLong("notifAsked", 0) < wait) return
            sp.edit().putLong("notifAsked", now).apply()
            AlertDialog.Builder(this)
                .setTitle(L.t("الإشعارات مغلقة", "Notifications are off"))
                .setMessage(L.t(
                    "بدون الإشعارات لن يصلك تنبيه الأذان ولا تنبيه الإقامة.\n\nافتح الإعدادات واسمح بالإشعارات لتطبيق أقرب مسجد.",
                    "Without notifications you will not get the adhan and iqama alerts.\n\nOpen Settings and allow notifications for Aqrab Masjid."))
                .setPositiveButton(L.t("افتح الإعدادات", "Open Settings")) { _, _ -> openNotifSettings() }
                .setNegativeButton(L.t("لاحقاً", "Later"), null)
                .show()
            return
        }
        if (!Alerts.canExact(this)) {
            if (now - sp.getLong("exactAsked", 0) < wait) return
            sp.edit().putLong("exactAsked", now).apply()
            AlertDialog.Builder(this)
                .setTitle(L.t("التنبيه في وقته", "Alerts on time"))
                .setMessage(L.t(
                    "حتى يصل تنبيه الأذان في وقته بالضبط بدون تأخير، اسمح للتطبيق بـ «المنبّهات والتذكيرات».\n\nفي الصفحة التالية: فعّل الزر، ثم ارجع للتطبيق.",
                    "So the adhan alert comes exactly on time, with no delay, allow the app to use \"Alarms & reminders\".\n\nOn the next page: turn the switch on, then come back to the app."))
                .setPositiveButton(L.t("اسمح", "Allow")) { _, _ -> openExactSettings() }
                .setNegativeButton(L.t("لاحقاً", "Later"), null)
                .show()
        }
    }

    private fun openNotifSettings() {
        val i = if (Build.VERSION.SDK_INT >= 26)
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        try { startActivity(i) } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
    }

    private fun openExactSettings() {
        if (Build.VERSION.SDK_INT < 31) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
    }

    private fun refresh() {
        tvStatus.text = L.t("جاري تحديد موقعك...", "Finding your location...")
        LocationHelper.get(this) { loc ->
            if (loc == null) {
                // No default city: the user picks the place on the map
                tvStatus.text = L.t("ما قدرنا نحدد موقعك. فعّل الموقع (GPS)، أو اضغط هنا لتحديده على الخريطة 📍",
                    "We could not get your location. Turn on location (GPS), or tap here to choose it on the map 📍")
                tvStatus.setOnClickListener { startActivity(MapPickActivity.intent(this)) }
                return@get
            }
            tvStatus.text = ""
            tvStatus.setOnClickListener(null)
            renderTimes()
            Alerts.scheduleNext(this)
            PrayerWidgets.updateAll(this)
        }
    }

    private fun renderTimes() {
        val (lat, lng) = Prefs.location(this) ?: run {
            tvHeroLabel.text = L.t("حدد موقعك لعرض المواقيت 📍", "Choose your location to see prayer times 📍")
            tvHeroLabel.setOnClickListener { startActivity(MapPickActivity.intent(this)) }
            ring.visibility = View.GONE
            return
        }
        tvHeroLabel.setOnClickListener(null)
        val now = System.currentTimeMillis()
        val day = PrayerCalc.day(this, lat, lng, now)
        val st = PrayerCalc.status(this, lat, lng, now)

        tvHijri.text = PrayerCalc.hijriText(now) + if (day.ramadan) L.t(" · رمضان", " · Ramadan") else ""
        tvWeekday.text = java.text.SimpleDateFormat("EEEE", L.locale).format(java.util.Date(now))
        tvClock.text = PrayerCalc.fmtTime(now)
        tvGregorian.text = java.text.SimpleDateFormat("d MMMM yyyy", L.locale).format(java.util.Date(now)) + L.t(" م", "")
        fillHero(lat, lng, now)
        reminderBtn.text = PrayerReminders.ayah(st.time.prayer)
        tvMethod.text = L.t("طريقة الحساب: ${day.methodName}", "Method: ${day.methodName}")

        // Rebuild the table only when something in it changed (saves battery)
        val city = Prefs.city(this, lat, lng)
        val tableKey = L.lang + "|" + city + "|" + st.time.prayer + "|" + day.list.joinToString { "${p(it)}" }
        if (tableKey == lastTableKey && layoutTimes.childCount > 0) return
        lastTableKey = tableKey
        layoutTimes.removeAllViews()
        // Title: the city the times are for
        if (city != null) layoutTimes.addView(TextView(this).apply {
            text = "📍 " + city
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.title_text))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })
        layoutTimes.addView(row(L.t("الصلاة", "Prayer"), L.t("الأذان", "Adhan"), L.t("الإقامة", "Iqama"), header = true, highlight = false))
        for (p in day.list) {
            layoutTimes.addView(
                row(p.pname, PrayerCalc.fmtTime(p.adhan), PrayerCalc.fmtTime(p.iqama),
                    header = false, highlight = p.prayer == st.time.prayer)
            )
        }
    }

    private var lastTableKey = ""
    private fun p(t: PrayerTime) = "${t.prayer}:${t.adhan}:${t.iqama}"

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
        val st = statusNow(lat, lng, now)
        val t = st.time
        val nameBefore = tvHeroName.text.toString()
        val ringBefore = ring.visibility
        // Left side: next prayer, then adhan and iqama times (always)
        setIf(tvHeroName, t.pname)
        heroNameBig = false
        setIf(tvHeroAdhan, L.t("الأذان ${PrayerCalc.fmtTime(t.adhan)}", "Adhan ${PrayerCalc.fmtTime(t.adhan)}"))
        if (!st.isCurrent) setIf(tvIqamaLeft, if (t.isJumuah) L.t("خطبة الجمعة · بكّر بالحضور", "Jumuah khutbah · go early")
            else L.t("الإقامة ${PrayerCalc.fmtTime(t.iqama)}", "Iqama ${PrayerCalc.fmtTime(t.iqama)}"))
        // Big yellow counter: 60 min before adhan it counts down to the adhan,
        // after adhan it counts down to the iqama, then "أقيمت الصلاة" for 10 min
        val w = 60 * 60_000L
        if (!st.isCurrent) {
            val left = t.adhan - now
            setIf(tvHeroLabel, L.t("الصلاة القادمة", "Next prayer"))
            if (left in 1..w) {
                ring.visibility = View.VISIBLE
                ring.update("", "", CountdownRing.clock(left), L.t("تبقى على الأذان", "to adhan"), left.toFloat() / w, false)
            } else ring.visibility = View.GONE
        } else {
            val toIq = t.iqama - now
            if (toIq > 0) {
                setIf(tvHeroLabel, L.t("دخل وقت صلاة", "It is now time for"))
                ring.visibility = View.VISIBLE
                val span = (t.iqama - t.adhan).coerceAtLeast(1L)
                ring.update("", "", CountdownRing.clock(toIq),
                    if (t.isJumuah) L.t("خطبة الجمعة", "Jumuah khutbah") else L.t("تبقى على الإقامة", "to iqama"),
                    toIq.toFloat() / span, true)
            } else {
                setIf(tvHeroLabel, L.t("أقيمت الصلاة", "Prayer has started"))
                ring.visibility = View.GONE
            }
            // Under the name: live time left to iqama (not for Jumuah: there is a khutbah)
            if (t.isJumuah) setIf(tvIqamaLeft, L.t("خطبة الجمعة", "Jumuah khutbah"))
            else if (toIq > 0) setIf(tvIqamaLeft, L.t("تبقى على الإقامة ${CountdownRing.clock(toIq)}", "Iqama in ${CountdownRing.clock(toIq)}"))
            else setIf(tvIqamaLeft, L.t("أقيمت الصلاة", "Prayer has started"))
        }
        // Measure the big name again only when the name or the ring changed
        if (nameBefore != tvHeroName.text.toString() || ringBefore != ring.visibility || tvHeroName.tag != "sized") {
            tvHeroName.tag = "sized"
            sizeHeroName()
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

    /**
     * Add a mosque, no account needed:
     * - "I am at the mosque now": sends the phone location (name is optional).
     * - "Pick on the map": opens the website form with a map.
     * We review each request, then the mosque shows in "Nearby mosques" in the app.
     */
    private fun addMosqueDialog() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(8), dp(22), 0)
        val msg = TextView(this)
        msg.text = L.t("هل أنت عند المسجد الآن؟ نأخذ موقعك ونرسله لنا، ونضيفه بعد المراجعة. لا تحتاج حساب.",
            "Are you at the mosque now? We send your location to us and add it after review. No account needed.")
        msg.textSize = 16f
        msg.setTextColor(getColor(R.color.text_primary))
        box.addView(msg)
        val name = android.widget.EditText(this)
        name.hint = L.t("اسم المسجد (اختياري)", "Mosque name (optional)")
        name.isSingleLine = true
        box.addView(name, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(12) })
        // Women's prayer area and parking (we show them in the mosques list after review)
        val women = android.widget.CheckBox(this)
        women.text = L.t("👩 يوجد مصلى نساء", "👩 Has a women's prayer area")
        women.textSize = 15f
        women.setTextColor(getColor(R.color.text_primary))
        box.addView(women, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        val parking = android.widget.CheckBox(this)
        parking.text = L.t("🅿️ يوجد مواقف سيارات", "🅿️ Has car parking")
        parking.textSize = 15f
        parking.setTextColor(getColor(R.color.text_primary))
        box.addView(parking, LinearLayout.LayoutParams(-1, -2))

        AlertDialog.Builder(this)
            .setTitle(L.t("أضف مسجدًا", "Add a mosque"))
            .setView(box)
            .setPositiveButton(L.t("📍 أنا عند المسجد", "📍 I am at the mosque")) { _, _ ->
                sendMosqueHere(name.text.toString().trim(),
                    if (women.isChecked) "نعم" else "غير محدد", if (parking.isChecked) "نعم" else "غير محدد")
            }
            .setNeutralButton(L.t("حدد على الخريطة", "Pick on the map")) { _, _ -> openUrl(Config.ADD_MOSQUE_URL) }
            .setNegativeButton(L.t("إلغاء", "Cancel"), null)
            .show()
    }

    private fun sendMosqueHere(name: String, women: String = "غير محدد", parking: String = "غير محدد") {
        Toast.makeText(this, L.t("جاري أخذ موقعك…", "Getting your location…"), Toast.LENGTH_SHORT).show()
        LocationHelper.get(this) { loc ->
            if (loc == null) {
                Toast.makeText(this, L.t("ما قدرنا نحدد موقعك. حدد المسجد على الخريطة.", "Could not get your location. Pick it on the map."), Toast.LENGTH_LONG).show()
                openUrl(Config.ADD_MOSQUE_URL)
                return@get
            }
            MosqueSubmit.send(this, name, loc.first, loc.second, women, parking) { ok ->
                Toast.makeText(this,
                    if (ok) L.t("جزاك الله خيرًا! وصلنا المسجد، ونضيفه بعد المراجعة.", "Thank you! We received it and will add it after review.")
                    else L.t("ما قدرنا نرسل الآن. تأكد من الإنترنت وحاول مرة ثانية.", "Could not send now. Check the internet and try again."),
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showTone() {
        val t = Alerts.tone(this)
        val name = if (t == Alerts.Tone.CUSTOM) (Alerts.customName(this) ?: t.label) else t.label
        findViewById<TextView>(R.id.btnTone).text = L.t("🔔 نغمة التنبيه: ", "🔔 Alert sound: ") + name + "  ›"
    }

    private var playing: android.media.Ringtone? = null

    private fun playSound(uri: Uri?) {
        try { playing?.stop() } catch (_: Exception) {}
        playing = null
        if (uri == null) return
        try {
            playing = android.media.RingtoneManager.getRingtone(this, uri)
            playing?.play()
        } catch (_: Exception) {}
    }

    /** Pick the alert sound. Tapping a choice plays it once. The last choice opens the phone's own sounds. */
    private fun toneDialog() {
        val tones = Alerts.Tone.values().filter { it != Alerts.Tone.CUSTOM }
        val customName = Alerts.customName(this)
        val labels = tones.map { it.label } +
            (if (customName != null) L.t("📁 من جهازي: $customName", "📁 My phone: $customName") else L.t("📁 نغمة من جهازي…", "📁 Sound from my phone…"))
        val cur = Alerts.tone(this)
        var pick = if (cur == Alerts.Tone.CUSTOM) tones.size else tones.indexOf(cur)
        lateinit var dlg: AlertDialog
        dlg = AlertDialog.Builder(this)
            .setTitle(L.t("نغمة التنبيه", "Alert sound"))
            .setSingleChoiceItems(labels.toTypedArray(), pick) { _, i ->
                pick = i
                if (i == tones.size) {
                    // Own sound: play it if there is one, else open the phone's sounds
                    if (customName != null) playSound(Alerts.customUri(this))
                    else { dlg.dismiss(); pickPhoneSound() }
                    return@setSingleChoiceItems
                }
                val t = tones[i]
                if (t.raw != null) playSound(Alerts.soundUri(this, t.raw))
                else {
                    playSound(null)
                    if (t == Alerts.Tone.VIBRATE) try {
                        getSystemService(android.os.Vibrator::class.java)
                            .vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250), -1))
                    } catch (_: Exception) {}
                }
            }
            .setPositiveButton(L.t("حفظ", "Save")) { _, _ ->
                playSound(null)
                if (pick == tones.size) Alerts.setTone(this, Alerts.Tone.CUSTOM) else Alerts.setTone(this, tones[pick])
                showTone()
            }
            .setNeutralButton(L.t("تغيير نغمتي", "Change my sound")) { _, _ -> playSound(null); pickPhoneSound() }
            .setNegativeButton(L.t("إلغاء", "Cancel")) { _, _ -> playSound(null) }
            .setOnDismissListener { playSound(null) }
            .create()
        dlg.show()
    }

    /** Choose: one of the phone's sounds, or an audio file (Android 10+). */
    private fun pickPhoneSound() {
        val items = mutableListOf(L.t("🔔 نغمات الجوال", "🔔 Phone sounds"))
        if (Build.VERSION.SDK_INT >= 29) items += L.t("🎵 ملف صوتي (مثل أذان أو تلاوة)", "🎵 Audio file (like an adhan)")
        AlertDialog.Builder(this)
            .setTitle(L.t("نغمة من جهازي", "Sound from my phone"))
            .setItems(items.toTypedArray()) { _, i ->
                if (i == 0) {
                    val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                            android.media.RingtoneManager.TYPE_NOTIFICATION or android.media.RingtoneManager.TYPE_RINGTONE or android.media.RingtoneManager.TYPE_ALARM)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, L.t("اختر النغمة", "Pick a sound"))
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Alerts.customUri(this))
                    try { startActivityForResult(intent, REQ_RINGTONE) } catch (_: Exception) { toast(L.t("الجوال لا يدعم هذا", "Not supported on this phone")) }
                } else {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
                    try { startActivityForResult(intent, REQ_AUDIO) } catch (_: Exception) { toast(L.t("الجوال لا يدعم هذا", "Not supported on this phone")) }
                }
            }
            .setNegativeButton(L.t("إلغاء", "Cancel"), null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQ_RINGTONE) {
            @Suppress("DEPRECATION")
            val uri = data.getParcelableExtra<Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI) ?: return
            val name = try { android.media.RingtoneManager.getRingtone(this, uri).getTitle(this) } catch (_: Exception) { null }
            saveCustom(uri, name ?: L.t("نغمة الجوال", "Phone sound"))
        } else if (requestCode == REQ_AUDIO) {
            val src = data.data ?: return
            toast(L.t("جاري حفظ النغمة…", "Saving the sound…"))
            Thread {
                val res = copyToNotifications(src)
                runOnUiThread {
                    if (res == null) toast(L.t("ما قدرنا نستخدم هذا الملف", "Could not use this file"))
                    else saveCustom(res.first, res.second)
                }
            }.start()
        }
    }

    private fun saveCustom(uri: Uri, name: String) {
        Alerts.setCustom(this, uri, name)
        showTone()
        playSound(uri)
        toast(L.t("تم: $name", "Saved: $name"))
    }

    /**
     * The alert is played by the system, which can't read files picked from other apps.
     * So we copy the audio into the phone's "Notifications" sounds folder and use that copy.
     */
    private fun copyToNotifications(src: Uri): Pair<Uri, String>? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            var name = "Aqrab Masjid sound"
            contentResolver.query(src, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0) ?: name
            }
            val mime = contentResolver.getType(src) ?: "audio/mpeg"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "aqrab_" + name)
                put(android.provider.MediaStore.Audio.Media.MIME_TYPE, mime)
                put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Notifications/AqrabMasjid")
                put(android.provider.MediaStore.Audio.Media.IS_NOTIFICATION, 1)
                put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val col = android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val dst = contentResolver.insert(col, values) ?: return null
            contentResolver.openInputStream(src).use { inp ->
                contentResolver.openOutputStream(dst).use { out -> if (inp == null || out == null) return null; inp.copyTo(out) }
            }
            values.clear()
            values.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(dst, values, null, null)
            dst to name.substringBeforeLast('.')
        } catch (_: Exception) { null }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /** Radio bar on the main screen: only while the radio is on. */
    /** Station chosen in the player bar (saved). */
    private fun radioSel(): String = getSharedPreferences("aqrab", MODE_PRIVATE).getString("radio_sel", "quran") ?: "quran"

    /** Previous / next station; if the radio is on, switch to it right away. */
    private fun radioStep(d: Int) {
        val list = RadioService.STATIONS
        val cur = list.indexOfFirst { it.id == (RadioService.current ?: radioSel()) }.coerceAtLeast(0)
        radioSelect(list[(cur + d + list.size) % list.size].id)
    }

    /** Select a station; if the radio is on, switch to it right away. */
    private fun radioSelect(id: String) {
        getSharedPreferences("aqrab", MODE_PRIVATE).edit().putString("radio_sel", id).apply()
        if (RadioService.current != null && RadioService.current != id) RadioService.play(this, id)
        radioLastShown = ""
        showRadioBar()
    }

    private var radioLastShown = ""
    private var dotAnim: android.animation.ObjectAnimator? = null

    /**
     * Radio card. The look follows the REAL player state from RadioService:
     * ready (grey) · connecting (gold, spinner) · live (green dot + moving wave on the playing station)
     * · signal lost (red, spinner, retrying) · error (red).
     */
    private fun showRadioBar() {
        showSaveButton()
        val cur = RadioService.current
        val sel = cur ?: radioSel()
        val st = if (cur == null) {
            if (RadioService.status == RadioService.Status.ERROR) RadioService.Status.ERROR else RadioService.Status.OFF
        } else RadioService.status
        val key = "$sel|$cur|$st|${L.lang}"
        if (key == radioLastShown) return      // nothing changed (this runs every second)
        radioLastShown = key
        val live = st == RadioService.Status.PLAYING
        val wantSound = cur != null && !RadioService.paused

        // Stations: the selected one looks pressed, the other is lighter
        fun station(id: String, box: Int, ic: Int, tx: Int, wave: Int, dot: Int) {
            val on = id == sel
            findViewById<View>(box)?.setBackgroundResource(if (on) R.drawable.bg_station_sel else R.drawable.bg_station_idle)
            findViewById<View>(box)?.alpha = if (on) 1f else 0.75f
            findViewById<TextView>(tx)?.setTextColor(getColor(if (on) R.color.title_text else R.color.text_secondary))
            findViewById<android.widget.ImageView>(ic)?.imageTintList =
                android.content.res.ColorStateList.valueOf(getColor(if (on) R.color.icon else R.color.text_hint))
            val w = findViewById<android.widget.ImageView>(wave)
            val ad = w?.drawable as? android.graphics.drawable.AnimationDrawable
            if (on && live && cur == id) { w?.visibility = View.VISIBLE; ad?.start() }
            else { ad?.stop(); w?.visibility = View.INVISIBLE }
            findViewById<View>(dot)?.visibility = if (on && live && cur == id) View.VISIBLE else View.GONE
        }
        station("quran", R.id.radioStQuran, R.id.radioIcQuran, R.id.radioTxQuran, R.id.radioWaveQuran, R.id.radioLiveQuran)
        station("sunnah", R.id.radioStSunnah, R.id.radioIcSunnah, R.id.radioTxSunnah, R.id.radioWaveSunnah, R.id.radioLiveSunnah)
        findViewById<TextView>(R.id.radioTxQuran)?.text = L.t("القرآن الكريم", "Holy Quran")
        findViewById<TextView>(R.id.radioTxSunnah)?.text = L.t("السنة النبوية", "Prophet's Sunnah")
        findViewById<TextView>(R.id.radioMore)?.text = "📻  " + L.t("إذاعات أخرى", "More stations")

        // Middle button: pause while sound is wanted, play otherwise; spinner while connecting / retrying
        findViewById<android.widget.ImageView>(R.id.radioPlayIcon)
            ?.setImageResource(if (wantSound) R.drawable.ic_pause else R.drawable.ic_play)
        findViewById<View>(R.id.radioPlay)?.contentDescription = if (wantSound) L.t("إيقاف", "Pause") else L.t("تشغيل", "Play")
        val spin = findViewById<android.widget.ProgressBar>(R.id.radioSpin)
        spin?.visibility = if (st == RadioService.Status.LOADING || st == RadioService.Status.LOST) View.VISIBLE else View.GONE
        spin?.indeterminateTintList = android.content.res.ColorStateList.valueOf(
            if (st == RadioService.Status.LOST) 0xFFEF4444.toInt() else 0xFFE0B84A.toInt())

        // Status pill and line
        val dot = findViewById<View>(R.id.radioDot)
        val txt = findViewById<TextView>(R.id.radioStatus)
        val name = RadioService.station(sel)?.label ?: ""
        dotAnim?.cancel(); dotAnim = null; dot?.alpha = 1f
        when (st) {
            RadioService.Status.PLAYING -> {
                dot?.setBackgroundResource(R.drawable.dot_green)
                txt?.text = L.t("مباشر – البث يعمل", "Live – on air")
                dotAnim = android.animation.ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.25f).apply {
                    duration = 800; repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE; start()
                }
            }
            RadioService.Status.LOADING -> { dot?.setBackgroundResource(R.drawable.dot_amber); txt?.text = L.t("جاري الاتصال…", "Connecting…") }
            RadioService.Status.LOST -> { dot?.setBackgroundResource(R.drawable.dot_red); txt?.text = L.t("انقطع البث · نحاول من جديد", "Signal lost · retrying") }
            RadioService.Status.ERROR -> { dot?.setBackgroundResource(R.drawable.dot_red); txt?.text = L.t("تعذر التشغيل · اضغط للمحاولة", "Could not play · tap to retry") }
            else -> { dot?.setBackgroundResource(R.drawable.dot_grey); txt?.text = L.t("جاهز للتشغيل", "Ready to play") }
        }
        findViewById<TextView>(R.id.radioName)?.text = when (st) {
            RadioService.Status.PLAYING -> L.t("$name تعمل الآن", "$name is playing now")
            else -> name
        }
    }

    // ---- Radio clips: save the last 3 minutes, keep the last 5 ----

    private var clipPlayer: android.media.MediaPlayer? = null

    private fun mmss(sec: Long) = String.format(L.locale, "%d:%02d", sec / 60, sec % 60)

    private fun showSaveButton() {
        val v = findViewById<TextView>(R.id.radioSave) ?: return
        val ms = RadioRecorder.bufferedMs()
        v.alpha = if (ms > 0) 1f else 0.55f
        v.text = if (ms >= RadioRecorder.KEEP_MS - 1000 || ms == 0L) L.t("⏺ احفظ آخر ٣ دقائق", "⏺ Save last 3 min")
            else L.t("⏺ احفظ آخر ", "⏺ Save last ") + mmss(ms / 1000)
    }

    private fun saveRadioClip() {
        val id = RadioRecorder.station
        if (RadioRecorder.bufferedMs() < 2000 || id == null) {
            toast(L.t("شغّل الإذاعة أولاً، ثم اضغط حفظ", "Play the radio first, then press Save")); return
        }
        val f = try { RadioRecorder.save(this, id) } catch (_: Exception) { null }
        if (f == null) { toast(L.t("تعذر الحفظ", "Could not save")); return }
        toast(L.t("تم الحفظ ✅ في «تسجيلاتي» (", "Saved ✅ in My clips (") + mmss(RadioRecorder.seconds(f)) + ")")
    }

    private var clipPlaying: java.io.File? = null

    /** "My clips": dark green panel like the radio card; each clip has Play, Share, Delete. */
    private fun radioClipsDialog() {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                colors = intArrayOf(Color.parseColor("#2B3A67"), Color.parseColor("#151D3B"))  // night blue: the clips page
                setStroke(dp(1), Color.parseColor("#66F6D46B"))
            }
        }
        val title = TextView(this).apply {
            text = L.t("🎧 تسجيلاتي", "🎧 My clips")
            setTextColor(Color.parseColor("#F6D46B")); textSize = 20f; setTypeface(typeface, Typeface.BOLD)
        }
        val sub = TextView(this).apply {
            text = L.t("آخر ٥ تسجيلات – محفوظة في جوالك فقط", "Last 5 clips – saved on your phone only")
            setTextColor(Color.parseColor("#C3CBE6")); textSize = 13f
        }
        root.addView(title); root.addView(sub)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(12) })
        val close = TextView(this).apply {
            text = L.t("إغلاق", "Close"); gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#151D3B")); textSize = 16f; setTypeface(typeface, Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.parseColor("#F6D46B")) }
            setOnClickListener { d.dismiss() }
        }
        root.addView(close, LinearLayout.LayoutParams(-1, dp(48)).also { it.topMargin = dp(14) })

        fun fill() {
            list.removeAllViews()
            val files = RadioRecorder.list(this)
            if (files.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = L.t("لا توجد تسجيلات.\nأثناء تشغيل الإذاعة اضغط «⏺ احفظ آخر ٣ دقائق» ليُحفظ ما سمعته الآن.",
                        "No clips yet.\nWhile the radio plays, press \"⏺ Save last 3 min\" to keep what you just heard.")
                    setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; setPadding(dp(8), dp(24), dp(8), dp(24))
                })
                return
            }
            val fmt = java.text.SimpleDateFormat("EEEE d MMM · h:mm a", L.locale)
            for (f in files) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(10), dp(10))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(Color.parseColor(if (f == clipPlaying) "#40F6D46B" else "#1AFFFFFF"))
                    }
                }
                val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                txt.addView(TextView(this).apply {
                    text = RadioService.station(RadioRecorder.stationId(f))?.label ?: L.t("إذاعة", "Radio")
                    setTextColor(Color.WHITE); textSize = 15f; setTypeface(typeface, Typeface.BOLD); maxLines = 2
                })
                txt.addView(TextView(this).apply {
                    text = fmt.format(java.util.Date(f.lastModified()))
                    setTextColor(Color.parseColor("#C3CBE6")); textSize = 13f
                })
                txt.addView(TextView(this).apply {
                    text = L.t("المدة ", "Length ") + mmss(RadioRecorder.seconds(f))
                    setTextColor(Color.parseColor("#F6D46B")); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
                })
                row.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
                fun btn(label: String, gold: Boolean, onClick: () -> Unit) = TextView(this).apply {
                    text = label; gravity = Gravity.CENTER; textSize = 18f
                    setTextColor(Color.parseColor(if (gold) "#151D3B" else "#FFFFFF"))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor(if (gold) "#F6D46B" else "#2EFFFFFF"))
                    }
                    setOnClickListener { onClick() }
                }.also { row.addView(it, LinearLayout.LayoutParams(dp(44), dp(44)).also { lp -> lp.marginStart = dp(6) }) }
                btn(if (f == clipPlaying) "⏹" else "▶", true) {
                    if (f == clipPlaying) stopClip() else playClip(f) { fill() }
                    fill()
                }
                btn("📤", false) { shareClip(f) }
                btn("🗑", false) {
                    AlertDialog.Builder(this)
                        .setMessage(L.t("حذف هذا التسجيل؟", "Delete this clip?"))
                        .setPositiveButton(L.t("حذف", "Delete")) { _, _ -> if (f == clipPlaying) stopClip(); f.delete(); fill() }
                        .setNegativeButton(L.t("إلغاء", "Cancel"), null).show()
                }
                list.addView(row, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) })
            }
        }
        fill()
        d.setContentView(root)
        d.setOnDismissListener { stopClip() }
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        d.show()
        d.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun stopClip() {
        try { clipPlayer?.release() } catch (_: Exception) {}
        clipPlayer = null
        clipPlaying = null
    }

    private fun playClip(f: java.io.File, onEnd: () -> Unit) {
        stopClip()
        // The live radio stops while the clip plays
        if (RadioService.current != null && !RadioService.paused) RadioService.toggle(this)
        val mp = android.media.MediaPlayer()
        try {
            mp.setDataSource(f.absolutePath)
            mp.prepare()
            mp.start()
        } catch (_: Exception) {
            mp.release(); toast(L.t("تعذر تشغيل التسجيل", "Could not play the clip")); return
        }
        clipPlayer = mp
        clipPlaying = f
        mp.setOnCompletionListener { stopClip(); onEnd() }
    }

    private fun shareClip(f: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
            val i = Intent(Intent.ACTION_SEND)
                .setType(if (f.extension == "aac") "audio/aac" else "audio/mpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, L.t("مشاركة التسجيل", "Share clip")))
        } catch (_: Exception) { toast(L.t("تعذرت المشاركة", "Could not share")) }
    }

    /** Live Quran radio: pick a station; it keeps playing with the screen off. */
    private fun radioDialog() {
        val st = RadioService.STATIONS
        val now = RadioService.current
        val items = st.map { (if (it.id == now) "▶️ " else "📻 ") + it.label }.toMutableList()
        val b = AlertDialog.Builder(this)
            .setTitle(L.t("إذاعة", "Radio"))
            .setItems(items.toTypedArray()) { _, i ->
                RadioService.play(this, st[i].id)
                toast(L.t("جاري تشغيل: ", "Playing: ") + st[i].label)
                showRadioBar()
            }
            .setNegativeButton(L.t("إغلاق", "Close"), null)
        if (now != null) b.setPositiveButton(L.t("⏹ إيقاف", "⏹ Stop")) { _, _ -> RadioService.stop(this) }
        b.show()
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, Config.SHARE_TEXT)
        startActivity(Intent.createChooser(i, L.t("شارك التطبيق", "Share the app")))
    }

    /** User can change iqama minutes for each prayer. */
    private fun iqamaDialog() {
        val loc = Prefs.location(this) ?: run {
            // Iqama times depend on the country: we need the user's place first
            Toast.makeText(this, L.t("حدد موقعك أولًا", "Choose your location first"), Toast.LENGTH_LONG).show()
            startActivity(MapPickActivity.intent(this))
            return
        }
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
            View.LAYOUT_DIRECTION_RTL  // same layout in every language
        fun b(id: Int, ar: String, en: String) { findViewById<TextView>(id).text = L.t(ar, en) }
        findViewById<TextView>(R.id.btnLang).text = "🌐 " + L.code()
        b(R.id.btnGoText, "أقرب مسجد", "Nearest mosque")
        b(R.id.btnJamiText, "أقرب جامع", "Nearest Jami")
        b(R.id.tvServices, "القرآن والذكر", "Quran & Dhikr")
        b(R.id.tvGrpMosques, "المساجد", "Mosques")
        b(R.id.tvGrpContact, "تواصل", "Contact")
        b(R.id.tvRecommended, "تطبيقات ومواقع ننصح بها", "Recommended apps and sites")
        b(R.id.btnPrivacy, "سياسة الخصوصية", "Privacy policy")
        b(R.id.btnAbout, "عن أقرب مسجد", "About Aqrab Masjid")
        b(R.id.radioLabel, "اختر البث", "Choose a station")
        b(R.id.radioRecs, "🎧 تسجيلاتي", "🎧 My clips")
        b(R.id.tileQuranText, "القرآن", "Quran")
        b(R.id.tileAdhkarText, "الأذكار", "Adhkar")
        b(R.id.tileQiratText, "قيراط", "Qirat")
        b(R.id.btnQibla, "القبلة", "Qibla")
        b(R.id.tileContactText, "تواصل معنا", "Contact us")
        b(R.id.tileShareText, "شارك", "Share")
        b(R.id.tileAddText, "أضف مسجد", "Add a mosque")
        b(R.id.tileNearText, "مساجد قريبة", "Nearby mosques")
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
        // The card size changes: measure the prayer name again
        tvHeroName.tag = null
        tvHeroName.post { sizeHeroName() }
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
            row.addView(ring, LinearLayout.LayoutParams(dp(160), dp(160)).also { it.marginEnd = dp(10) })
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
