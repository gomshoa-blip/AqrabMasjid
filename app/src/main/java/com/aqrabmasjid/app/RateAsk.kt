package com.aqrabmasjid.app

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Asks the user once to rate the app and send notes on WhatsApp.
 * First ask: 45 minutes after a prayer, 3 days after first use.
 * "Later" (or no answer): ask again after 7 days. Rated or "No thanks": never ask again.
 */
object RateAsk {
    private const val CHANNEL = "rate_v1"
    private const val NOTIF_ID = 400
    private const val DAY = 24 * 60 * 60_000L
    private const val AFTER_PRAYER = 45 * 60_000L
    const val ACTION_SHOW = "com.aqrabmasjid.app.RATE_SHOW"
    const val ACTION_LATER = "com.aqrabmasjid.app.RATE_LATER"
    const val ACTION_NEVER = "com.aqrabmasjid.app.RATE_NEVER"

    private fun sp(c: Context) = c.getSharedPreferences("aqrab", Context.MODE_PRIVATE)

    /** Called when the adhan alert fires. */
    fun onAdhan(c: Context) {
        val p = sp(c)
        if (p.getBoolean("rate_done", false)) return
        val now = System.currentTimeMillis()
        val first = p.getLong("rate_first", 0L)
        if (first == 0L) { p.edit().putLong("rate_first", now).apply(); return }
        val due = maxOf(first + 3 * DAY, p.getLong("rate_next", 0L))
        if (now < due) return
        // Do not ask again until this one is answered or a week passes
        p.edit().putLong("rate_next", now + 7 * DAY).apply()
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            c, 401, Intent(c, RateReceiver::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + AFTER_PRAYER, pi)
    }

    fun show(c: Context) {
        if (sp(c).getBoolean("rate_done", false)) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, L.t("تقييم التطبيق", "Rate the app"),
            NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); setShowBadge(false) })
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val rate = PendingIntent.getActivity(c, 402, Intent(c, RateActivity::class.java), flags)
        val later = PendingIntent.getBroadcast(c, 403, Intent(c, RateReceiver::class.java).setAction(ACTION_LATER), flags)
        val never = PendingIntent.getBroadcast(c, 404, Intent(c, RateReceiver::class.java).setAction(ACTION_NEVER), flags)
        val text = L.t("هل أعجبك «أقرب مسجد»؟ قيّمنا وأرسل ملاحظاتك على واتساب.",
            "Do you like Aqrab Masjid? Rate us and send your notes on WhatsApp.")
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mosque)
            .setContentTitle(L.t("رأيك يهمنا ⭐", "Your opinion matters ⭐"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(rate)
            .addAction(0, L.t("⭐ قيّمنا", "⭐ Rate us"), rate)
            .addAction(0, L.t("لاحقاً", "Later"), later)
            .addAction(0, L.t("لا، شكراً", "No, thanks"), never)
            .setAutoCancel(true)
            .setSilent(true)
            .setNumber(0)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .build()
        try { NotificationManagerCompat.from(c).notify(NOTIF_ID, n) } catch (_: SecurityException) {}
    }

    fun done(c: Context) {
        sp(c).edit().putBoolean("rate_done", true).apply()
        NotificationManagerCompat.from(c).cancel(NOTIF_ID)
    }

    fun later(c: Context) {
        sp(c).edit().putLong("rate_next", System.currentTimeMillis() + 7 * DAY).apply()
        NotificationManagerCompat.from(c).cancel(NOTIF_ID)
    }

    fun whatsappUrl(): String {
        val msg = L.t("السلام عليكم، تقييمي لتطبيق «أقرب مسجد»: ⭐⭐⭐⭐⭐\nملاحظاتي: ",
            "Hello, my rating for Aqrab Masjid: ⭐⭐⭐⭐⭐\nMy notes: ")
        return Config.WHATSAPP_URL + "?text=" + Uri.encode(msg)
    }
}

class RateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            RateAsk.ACTION_SHOW -> RateAsk.show(c)
            RateAsk.ACTION_LATER -> RateAsk.later(c)
            RateAsk.ACTION_NEVER -> RateAsk.done(c)
        }
    }
}

/** Opens WhatsApp from the notification, then closes. */
class RateActivity : Activity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Look.wrap(base))   // day / night choice
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        RateAsk.done(this)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RateAsk.whatsappUrl()))) }
        finish()
    }
}
