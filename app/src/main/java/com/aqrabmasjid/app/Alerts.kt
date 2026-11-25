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
    const val CHANNEL = "prayer_alerts_v2"
    private const val OLD_CHANNEL = "prayer_alerts"
    const val ACTION_SHOW_MOSQUES = "com.aqrabmasjid.app.SHOW_MOSQUES"
    private const val MIN = 60_000L

    fun createChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL)
        val ch = NotificationChannel(CHANNEL, L.t("تنبيه الصلاة", "Prayer alerts"), NotificationManager.IMPORTANCE_HIGH)
        ch.description = L.t("تنبيه عند الأذان، وبعده بـ 10 دقائق للإقامة", "Alert at adhan, and 10 minutes later for iqama")
        // Soft chime from res/raw/prayer_chime.ogg
        val sound = Uri.parse("android.resource://${c.packageName}/${R.raw.prayer_chime}")
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ch.setSound(sound, attrs)
        ch.enableVibration(true)
        ch.vibrationPattern = longArrayOf(0, 250, 150, 250)
        nm.createNotificationChannel(ch)
    }

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
            val evs = mutableListOf(Ev(it.adhan, "adhan", it))
            if (!isJumuah(it)) evs += Ev(it.adhan + Config.IQAMA_ALERT_AFTER_MIN * MIN, "iqama", it)
            evs
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

    fun show(c: Context, type: String, prayer: Prayer, adhan: Long, iqama: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        L.init(c)
        createChannel(c)

        val title = if (type == "iqama") {
            val left = ((iqama - System.currentTimeMillis()) / MIN).toInt()
            if (left > 0) L.t("إقامة ${prayer.ar} بعد $left د", "${prayer.ar} iqama in $left min") else L.t("حان وقت إقامة ${prayer.ar}", "Time for ${prayer.ar} iqama")
        } else L.t("حان الآن وقت أذان ${prayer.ar}", "It is time for ${prayer.ar} adhan")
        // Saved mosque may be old (user moved to another city) -> show it only if still close
        val nearest = Prefs.nearest(c)?.takeIf { it.distKm <= 5.0 }
        // Phone alert: a short reminder for this prayer + iqama time
        val phoneText = if (type == "iqama") null else
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

        val nb = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mosque)
            .setContentTitle(title)
            .setContentText(phoneText ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(phoneText ?: text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(phonePending)
            .setAutoCancel(true)
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
        }
        Alerts.scheduleNext(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Alerts.scheduleNext(c)
    }
}
