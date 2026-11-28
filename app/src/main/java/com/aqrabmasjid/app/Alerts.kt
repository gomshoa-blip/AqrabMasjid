package com.aqrabmasjid.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aqrabmasjid.app.car.MasjidCarAppService

/**
 * Two alerts per prayer: at adhan, and 10 minutes later for iqama (not for Jumuah).
 * Only the next alert is scheduled; when it fires, the one after it is scheduled.
 */
object Alerts {
    // New channel id: Android does not allow changing the sound of an existing channel
    const val CHANNEL = "prayer_soft_v4"
    const val PRE_CHANNEL = "prayer_pre_v1"
    private const val OLD_CHANNEL = "prayer_alerts"
    const val ACTION_SHOW_MOSQUES = "com.aqrabmasjid.app.SHOW_MOSQUES"
    private const val MIN = 60_000L
    /** Minutes before adhan when the countdown notification appears. */
    const val PRE_MIN = 30

    /** Alert sound the user picks. Android fixes the sound per channel, so each choice has its own channel. */
    enum class Tone(val channel: String, val ar: String, val en: String, val raw: Int?) {
        SOFT("prayer_soft_v4", "نغمة هادئة", "Soft chime", R.raw.prayer_chime),
        BELL("prayer_bell_v4", "جرس (نغمتين)", "Bell (two notes)", R.raw.prayer_bell),
        BELL1("prayer_bell1_v4", "جرس واحد", "Single bell", R.raw.tone_bell1),
        CHIME3("prayer_chime3_v4", "ثلاث نغمات صاعدة", "Three rising chimes", R.raw.tone_chime3),
        WOOD("prayer_wood_v4", "نغمة خشبية", "Wooden tones", R.raw.tone_wood),
        DIGITAL("prayer_digital_v4", "تنبيه رقمي", "Digital beep", R.raw.tone_digital),
        // Full adhan in a muezzin's voice (played by AdhanService; the alert itself only vibrates)
        ADHAN("prayer_adhan_v4", "🕌 الأذان بصوت المؤذن", "🕌 Full adhan (muezzin)", null),
        VIBRATE("prayer_vibrate_v4", "اهتزاز فقط", "Vibrate only", null),
        SILENT("prayer_silent_v4", "صامت (بدون صوت واهتزاز)", "Silent", null),
        // A sound the user picked from the phone. Its channel id changes with each new sound.
        CUSTOM("", "نغمة من جهازي", "From my phone", null);
        val label: String get() = L.t(ar, en)
    }

    fun createChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL)
        // Old channels (they showed a number on the app icon)
        for (old in listOf("prayer_gong_v1", "prayer_alerts_v2", "prayer_bell_v1", "prayer_bell1_v1", "prayer_chime3_v1",
                "prayer_wood_v1", "prayer_digital_v1", "prayer_vibrate_v1", "prayer_silent_v1",
                "prayer_soft_v3", "prayer_bell_v3", "prayer_bell1_v3", "prayer_chime3_v3", "prayer_wood_v3",
                "prayer_digital_v3", "prayer_vibrate_v3", "prayer_silent_v3"))
            nm.deleteNotificationChannel(old)
        // Quiet channel for the "adhan soon" countdown (30 minutes before adhan)
        nm.createNotificationChannel(NotificationChannel(PRE_CHANNEL, L.t("العد التنازلي للأذان", "Countdown to adhan"),
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false); setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC })
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // The user's own sound
        val cu = customUri(c)
        var cid = sp(c).getString("custom_ch", null)
        if (cid != null && !cid.endsWith("_ls")) {   // made before badges were off / before lock screen was public
            nm.deleteNotificationChannel(cid)
            cid = "prayer_custom_" + System.currentTimeMillis() + "_ls"
            sp(c).edit().putString("custom_ch", cid).apply()
        }
        if (cu != null && cid != null) {
            val ch = NotificationChannel(cid, L.t("تنبيه الصلاة - نغمة من جهازي", "Prayer alerts - my sound"), NotificationManager.IMPORTANCE_HIGH)
            ch.setShowBadge(false)
            ch.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            ch.setSound(cu, attrs)
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 250, 150, 250)
            nm.createNotificationChannel(ch)
        }
        for (t in Tone.values()) {
            if (t == Tone.CUSTOM) continue
            val ch = NotificationChannel(t.channel, L.t("تنبيه الصلاة - ${t.ar}", "Prayer alerts - ${t.en}"),
                if (t == Tone.SILENT) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_HIGH)
            ch.description = L.t("تنبيه قبل الأذان، وعند الأذان، وبعده بـ 7 دقائق، وعند الإقامة", "Alert before adhan, at adhan, 7 minutes later, and at iqama")
            ch.setShowBadge(false)
            // Show the whole alert on the lock screen
            ch.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            ch.setBypassDnd(false)
            if (t.raw != null) ch.setSound(soundUri(c, t.raw), attrs) else ch.setSound(null, null)
            ch.enableVibration(t != Tone.SILENT)
            if (t != Tone.SILENT) ch.vibrationPattern = longArrayOf(0, 250, 150, 250)
            nm.createNotificationChannel(ch)
        }
    }

    private fun sp(c: Context) = c.getSharedPreferences("aqrab", Context.MODE_PRIVATE)

    fun customUri(c: Context): Uri? = sp(c).getString("custom_uri", null)?.let { Uri.parse(it) }
    fun customName(c: Context): String? = sp(c).getString("custom_name", null)

    /** Save a sound the user picked, and make a new channel for it (Android can't change a channel's sound). */
    fun setCustom(c: Context, uri: Uri, name: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        sp(c).getString("custom_ch", null)?.let { nm.deleteNotificationChannel(it) }
        sp(c).edit()
            .putString("custom_uri", uri.toString())
            .putString("custom_name", name)
            .putString("custom_ch", "prayer_custom_" + System.currentTimeMillis() + "_ls")
            .putString("tone", Tone.CUSTOM.name)
            .apply()
        createChannel(c)
    }

    /** Channel to post the alert on, for the sound the user picked. */
    fun channelFor(c: Context, atAdhan: Boolean = false): String {
        val t = tone(c)
        // Full adhan: only the adhan alert uses it; the other alerts ring the soft chime
        if (t == Tone.ADHAN) return if (atAdhan) Tone.ADHAN.channel else Tone.SOFT.channel
        if (t == Tone.CUSTOM) {
            val id = sp(c).getString("custom_ch", null)
            if (id != null && customUri(c) != null) return id
            return Tone.SOFT.channel
        }
        return t.channel
    }

    fun soundUri(c: Context, raw: Int): Uri = Uri.parse("android.resource://${c.packageName}/$raw")

    fun tone(c: Context): Tone =
        runCatching { Tone.valueOf(c.getSharedPreferences("aqrab", Context.MODE_PRIVATE).getString("tone", "SOFT")!!) }.getOrDefault(Tone.SOFT)

    fun setTone(c: Context, t: Tone) =
        c.getSharedPreferences("aqrab", Context.MODE_PRIVATE).edit().putString("tone", t.name).apply()

    /**
     * Notification body like the "Al-Moazin" app: Hijri date on top, a big live countdown
     * next to the prayer and its adhan time, and the iqama (or a note) under it.
     * [target] is the time the countdown goes to; [left] says what it counts to.
     */
    private fun countdownViews(c: Context, prayer: Prayer, adhan: Long, target: Long, left: String, sub: String): android.widget.RemoteViews {
        val v = android.widget.RemoteViews(c.packageName, R.layout.notif_countdown)
        val now = System.currentTimeMillis()
        val day = java.text.SimpleDateFormat("EEEE", L.locale).format(java.util.Date(now))
        v.setTextViewText(R.id.nDate, "$day ${PrayerCalc.hijriText(now)}  ·  $left")
        v.setTextViewText(R.id.nPrayer, L.t("${pn(prayer, adhan)} ${PrayerCalc.fmtTime(adhan)}", "${pn(prayer, adhan)} ${PrayerCalc.fmtTime(adhan)}"))
        v.setTextViewText(R.id.nSub, sub)
        val base = android.os.SystemClock.elapsedRealtime() + (target - now)
        v.setChronometer(R.id.nChrono, base, null, true)
        v.setChronometerCountDown(R.id.nChrono, true)
        return v
    }

    /**
     * 30 minutes before adhan: a quiet notification with a live countdown to the adhan.
     * At adhan it is replaced by the adhan alert, whose countdown goes on to the iqama.
     */
    private fun showPre(c: Context, prayer: Prayer, adhan: Long, iqama: Long, sound: Boolean = true) {
        val now = System.currentTimeMillis()
        if (adhan <= now) return
        val open = PendingIntent.getActivity(
            c, 202, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // At the chosen time it rings once with the user's tone; opened later (app start) it stays quiet
        val n = NotificationCompat.Builder(c, if (sound) channelFor(c) else PRE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mosque)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentTitle(L.t("${pn(prayer, adhan)} · الأذان ${PrayerCalc.fmtTime(adhan)}", "${pn(prayer, adhan)} · Adhan ${PrayerCalc.fmtTime(adhan)}"))
            .setContentText(L.t("الإقامة ${PrayerCalc.fmtTime(iqama)}", "Iqama ${PrayerCalc.fmtTime(iqama)}"))
            .setSubText(L.t("متبقي للأذان", "to adhan"))
            .setWhen(adhan)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOnlyAlertOnce(true)
            .setSilent(!sound)
            .setNumber(0)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setTimeoutAfter(adhan - now + 60_000)
            .apply {
                val v = countdownViews(c, prayer, adhan, adhan, L.t("متبقي للأذان", "to adhan"),
                    L.t("الإقامة ${PrayerCalc.fmtTime(iqama)}", "Iqama ${PrayerCalc.fmtTime(iqama)}"))
                setStyle(NotificationCompat.DecoratedCustomViewStyle())
                setCustomContentView(v)
                setCustomBigContentView(v)
            }
            .build()
        try { NotificationManagerCompat.from(c).notify(300, n) } catch (_: SecurityException) {}
    }

    /** Prayer name for alerts: "الجمعة" on Friday instead of "الظهر". */
    private fun pn(p: Prayer, adhan: Long): String = PrayerTime(p, adhan, adhan).pname

    /** Friday Dhuhr = Jumuah: there is a khutbah, so no iqama alert. */
    private fun isJumuah(t: PrayerTime): Boolean {
        if (t.prayer != Prayer.DHUHR) return false
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = t.adhan }
        return cal.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.FRIDAY
    }

    fun canExact(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun scheduleNext(c: Context) {
        val app = c.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            app, 100, Intent(app, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        if (!Prefs.alertsOn(app)) return
        val (lat, lng) = Prefs.location(app) ?: return

        val now = System.currentTimeMillis()
        data class Ev(val at: Long, val type: String, val t: PrayerTime)
        val events = PrayerCalc.upcoming(app, lat, lng, now).flatMap {
            val evs = mutableListOf(Ev(it.adhan - Prefs.preMin(app) * MIN, "pre", it), Ev(it.adhan, "adhan", it))
            // 7 min after adhan: "iqama in X min"; then at the iqama time
            if (!isJumuah(it)) {
                evs += Ev(it.adhan + Config.IQAMA_ALERT_AFTER_MIN * MIN, "iqama", it)
                if (it.iqama > it.adhan + (Config.IQAMA_ALERT_AFTER_MIN + 1) * MIN) evs += Ev(it.iqama, "iqama", it)
            }
            // Ramadan: suhoor alert 45 minutes before Fajr
            if (it.prayer == Prayer.FAJR && Prefs.suhoorOn(app) && PrayerCalc.isRamadan(it.adhan + 3 * 3600_000L))
                evs += Ev(it.adhan - 45 * MIN, "suhoor", it)
            // Friday: read Surat Al-Kahf (9:00 AM), and go early for the khutbah (1 hour before adhan)
            if (isJumuah(it)) {
                val nine = java.util.Calendar.getInstance().apply {
                    timeInMillis = it.adhan
                    set(java.util.Calendar.HOUR_OF_DAY, 9); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (nine < it.adhan - 60 * MIN) evs += Ev(nine, "kahf", it)
                evs += Ev(it.adhan - 60 * MIN, "early", it)
            }
            evs
        }
        // Already inside the 30 minutes before adhan (app just opened): show the countdown now
        events.firstOrNull { it.type == "pre" && now >= it.at && now < it.t.adhan }?.let {
            L.init(app); createChannel(app); showPre(app, it.t.prayer, it.t.adhan, it.t.iqama, sound = false)
        }
        val next = events.filter { it.at > now + 5_000 }.minByOrNull { it.at } ?: return

        val intent = Intent(app, AlarmReceiver::class.java)
            .putExtra("type", next.type)
            .putExtra("prayer", next.t.prayer.name)
            .putExtra("adhan", next.t.adhan)
            .putExtra("iqama", next.t.iqama)
        val pending = PendingIntent.getBroadcast(
            app, 100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (canExact(app)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pending)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pending)
    }

    /** Friday reminders: Surat Al-Kahf in the morning, and going early to the mosque before Jumuah. */
    private fun showFriday(c: Context, type: String, adhan: Long) {
        val kahf = type == "kahf"
        val title = if (kahf) L.t("📖 الجمعة: لا تنسَ سورة الكهف", "📖 Friday: don't forget Surat Al-Kahf")
                    else L.t("🕌 الجمعة: بكّر إلى المسجد", "🕌 Friday: go early to the mosque")
        val text = if (kahf)
            L.t("«من قرأ سورة الكهف في يوم الجمعة أضاء له من النور ما بين الجمعتين» رواه الحاكم والبيهقي\nاضغط لفتح سورة الكهف",
                "Reading Surat Al-Kahf on Friday gives light until the next Friday (Al-Hakim, Al-Bayhaqi).\nTap to open Surat Al-Kahf")
        else
            L.t("«إذا كان يوم الجمعة وقفت الملائكة على باب المسجد يكتبون الأول فالأول» متفق عليه\nأذان الجمعة ${PrayerCalc.fmtTime(adhan)}",
                "On Friday, the angels stand at the mosque door writing who comes first (Bukhari, Muslim).\nJumuah adhan ${PrayerCalc.fmtTime(adhan)}")
        val open = if (kahf) Intent(c, QuranActivity::class.java).putExtra("page", 293)
                   else Intent(c, MainActivity::class.java)
        val pending = PendingIntent.getActivity(c, if (kahf) 203 else 204, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, channelFor(c))
            .setSmallIcon(R.drawable.ic_stat_mosque)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setTimeoutAfter(if (kahf) 6 * 60 * MIN else 60 * MIN)
            .build()
        try { NotificationManagerCompat.from(c).notify(if (kahf) 310 else 311, n) } catch (_: SecurityException) {}
    }

    /** Ramadan: time for suhoor, Fajr adhan at … */
    private fun showSuhoor(c: Context, fajr: Long) {
        val title = L.t("🌙 السحور: أذان الفجر ${PrayerCalc.fmtTime(fajr)}", "🌙 Suhoor: Fajr adhan at ${PrayerCalc.fmtTime(fajr)}")
        val text = L.t("«تسحّروا فإن في السحور بركة» متفق عليه", "“Eat suhoor, for in suhoor there is blessing” (Bukhari, Muslim)")
        val open = PendingIntent.getActivity(c, 205, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, channelFor(c))
            .setSmallIcon(R.drawable.ic_stat_mosque)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(fajr).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            .setTimeoutAfter(fajr - System.currentTimeMillis() + 60_000)
            .build()
        try { NotificationManagerCompat.from(c).notify(312, n) } catch (_: SecurityException) {}
    }

    fun show(c: Context, type: String, prayer: Prayer, adhan: Long, iqama: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        L.init(c)
        createChannel(c)
        if (type == "pre") { showPre(c, prayer, adhan, iqama); return }
        if (type == "kahf" || type == "early") { showFriday(c, type, adhan); return }
        if (type == "suhoor") { showSuhoor(c, adhan); return }

        val title = if (type == "iqama") {
            val left = ((iqama - System.currentTimeMillis()) / MIN).toInt()
            if (left > 0) L.t("إقامة ${pn(prayer, adhan)} بعد $left د", "${pn(prayer, adhan)} iqama in $left min") else L.t("حان وقت إقامة ${pn(prayer, adhan)}", "Time for ${pn(prayer, adhan)} iqama")
        } else L.t("حان الآن وقت أذان ${pn(prayer, adhan)}", "It is time for ${pn(prayer, adhan)} adhan")
        // Saved mosque may be old (user moved to another city) -> show it only if still close
        val nearest = Prefs.nearest(c)?.takeIf { it.distKm <= 5.0 }
        // Phone alert: a short reminder for this prayer + iqama time
        // Ramadan Maghrib: iftar dua first
        val iftar = type == "adhan" && prayer == Prayer.MAGHRIB && PrayerCalc.isRamadan(adhan)
        val phoneText = if (type == "iqama") null else
            (if (iftar) L.t("🌙 دعاء الإفطار: «ذهب الظمأ، وابتلّت العروق، وثبت الأجر إن شاء الله» رواه أبو داود\n",
                "🌙 Iftar dua: “The thirst is gone, the veins are moist, and the reward is sure, if Allah wills” (Abu Dawud)\n") else "") +
            PrayerReminders.next(c, prayer) + L.t("\nالإقامة ${PrayerCalc.fmtTime(iqama)}", "\nIqama ${PrayerCalc.fmtTime(iqama)}")
        val text = buildString {
            if (nearest != null) append(L.t("أقرب مسجد: ${nearest.name} على بعد ${MosqueRepo.fmtDistance(nearest.distKm)}", "Nearest mosque: ${nearest.name}, ${MosqueRepo.fmtDistance(nearest.distKm)} away"))
            else append(L.t("اضغط للذهاب لأقرب مسجد", "Tap to go to the nearest mosque"))
            append(L.t(" · الإقامة ${PrayerCalc.fmtTime(iqama)}", " · Iqama ${PrayerCalc.fmtTime(iqama)}"))
        }

        // Tap on the car screen -> opens the mosques list in Android Auto
        val carIntent = Intent(ACTION_SHOW_MOSQUES)
            .setComponent(ComponentName(c, MasjidCarAppService::class.java))
        val carPending = CarPendingIntent.getCarApp(
            c, 200, carIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Tap on the phone -> opens the app
        val phonePending = PendingIntent.getActivity(
            c, 201, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val car = CarAppExtender.Builder()
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_mosque)
            .setContentIntent(carPending)
            .setImportance(NotificationManager.IMPORTANCE_HIGH)
            .addAction(R.drawable.ic_stat_mosque, L.t("خذني لأقرب مسجد", "Take me to nearest mosque"), carPending)
            .build()

        // Full adhan voice: start it; if it can not play (no saved voice, phone on silent) use the soft chime
        val adhanVoice = type == "adhan" && tone(c) == Tone.ADHAN &&
            Adhan.start(c, prayer, L.t("أذان ${pn(prayer, adhan)}", "Adhan: ${pn(prayer, adhan)}"))
        val ch = if (type == "adhan" && tone(c) == Tone.ADHAN) (if (adhanVoice) Tone.ADHAN.channel else Tone.SOFT.channel) else channelFor(c)
        val nb = NotificationCompat.Builder(c, ch)
            .setSmallIcon(R.drawable.ic_stat_mosque)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentTitle(title)
            .setContentText(phoneText ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(phoneText ?: text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(phonePending)
            .setAutoCancel(true)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .extend(car)

        // Live countdown to iqama (the clock in the alert counts down by itself).
        // Not on Friday Dhuhr: Jumuah has a khutbah, no fixed iqama.
        val now = System.currentTimeMillis()
        val jumuah = prayer == Prayer.DHUHR &&
            java.util.Calendar.getInstance().apply { timeInMillis = adhan }.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.FRIDAY
        if (!jumuah && iqama > now) {
            nb.setWhen(iqama)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setSubText(L.t("متبقي للإقامة", "to iqama"))
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .also {
                    val v = countdownViews(c, prayer, adhan, iqama, L.t("متبقي للإقامة", "to iqama"),
                        L.t("الإقامة ${PrayerCalc.fmtTime(iqama)}", "Iqama ${PrayerCalc.fmtTime(iqama)}") +
                            (if (nearest != null) "  ·  🕌 " + nearest.name else ""))
                    it.setCustomContentView(v)
                    // Expanded: same countdown, with the short reminder for this prayer under it
                    val big = countdownViews(c, prayer, adhan, iqama, L.t("متبقي للإقامة", "to iqama"), phoneText ?: text)
                    big.setInt(R.id.nSub, "setMaxLines", 6)
                    it.setCustomBigContentView(big)
                }
                // Remove the alert a few minutes after iqama
                .setTimeoutAfter(iqama - now + 5 * MIN)
        } else {
            nb.setTimeoutAfter(40 * MIN)
        }
        val n = nb.build()

        try {
            NotificationManagerCompat.from(c).notify(300, n)
        } catch (_: SecurityException) {
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val type = i.getStringExtra("type")
        val prayer = i.getStringExtra("prayer")?.let { runCatching { Prayer.valueOf(it) }.getOrNull() }
        if (type != null && prayer != null) {
            Alerts.show(c, type, prayer, i.getLongExtra("adhan", 0), i.getLongExtra("iqama", 0))
            if (type == "adhan") runCatching { RateAsk.onAdhan(c) }
        }
        Alerts.scheduleNext(c)
        PrayerWidgets.updateAll(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Alerts.scheduleNext(c)
        PrayerWidgets.updateAll(c)
    }
}
