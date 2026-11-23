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
 * Two alerts per prayer: 10 minutes before adhan, and at adhan.
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
        val ch = NotificationChannel(CHANNEL, "تنبيه الصلاة", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "تنبيه قبل الأذان وعند الأذان"
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
            listOf(Ev(it.adhan - Config.PRE_ALERT_MIN * MIN, "pre", it), Ev(it.adhan, "adhan", it))
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
        createChannel(c)

        val title = if (type == "pre") "${prayer.ar} بعد ${Config.PRE_ALERT_MIN} دقائق"
        else "حان الآن وقت أذان ${prayer.ar}"
        // Saved mosque may be old (user moved to another city) -> show it only if still close
        val nearest = Prefs.nearest(c)?.takeIf { it.distKm <= 5.0 }
        val text = buildString {
            if (nearest != null) append("أقرب مسجد: ${nearest.name} على بعد ${MosqueRepo.fmtDistance(nearest.distKm)}")
            else append("اضغط للذهاب لأقرب مسجد")
            append(" · الإقامة ${PrayerCalc.fmtTime(iqama)}")
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
            .addAction(R.drawable.ic_stat_mosque, "خذني لأقرب مسجد", carPending)
            .build()

        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mosque)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(phonePending)
            .setAutoCancel(true)
            .setTimeoutAfter(40 * MIN)
            .extend(car)
            .build()

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
