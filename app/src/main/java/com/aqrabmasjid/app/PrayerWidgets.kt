package com.aqrabmasjid.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/**
 * Two home screen widgets:
 * - NextPrayerWidget: next prayer, adhan + iqama, live countdown, "nearest mosque" button.
 * - DayPrayerWidget: today's five prayers, the coming one highlighted.
 * The countdown is a Chronometer, so it ticks by itself without waking the app.
 * The widgets are refreshed at each adhan / iqama (alarm below), and every 30 minutes by Android.
 */
object PrayerWidgets {
    const val ACTION_TICK = "com.aqrabmasjid.app.WIDGET_TICK"
    private const val MIN = 60_000L

    fun updateAll(c: Context) {
        val app = c.applicationContext
        try {
            L.init(app)
            val wm = AppWidgetManager.getInstance(app)
            val nextIds = wm.getAppWidgetIds(ComponentName(app, NextPrayerWidget::class.java))
            val dayIds = wm.getAppWidgetIds(ComponentName(app, DayPrayerWidget::class.java))
            if (nextIds.isEmpty() && dayIds.isEmpty()) return
            if (nextIds.isNotEmpty()) wm.updateAppWidget(nextIds, nextViews(app))
            if (dayIds.isNotEmpty()) wm.updateAppWidget(dayIds, dayViews(app))
            scheduleTick(app)
        } catch (_: Exception) {
        }
    }

    /** What the widgets show now. */
    private class Now(
        val label: String, val name: String, val adhan: Long, val iqama: Long,
        val countTo: Long?, val countLabel: String, val prayer: Prayer, val nextChange: Long
    )

    private fun now(c: Context): Now? {
        val (lat, lng) = Prefs.location(c) ?: return null
        val now = System.currentTimeMillis()
        val st = PrayerCalc.status(c, lat, lng, now)
        val t = st.time
        return if (!st.isCurrent) {
            Now(L.t("الصلاة القادمة", "Next prayer"), t.prayer.ar, t.adhan, t.iqama,
                t.adhan, L.t("متبقي للأذان", "to adhan"), t.prayer, t.adhan)
        } else if (t.iqama > now) {
            Now(L.t("حان وقت الصلاة", "Prayer time now"), t.prayer.ar, t.adhan, t.iqama,
                t.iqama, L.t("متبقي للإقامة", "to iqama"), t.prayer, t.iqama)
        } else {
            Now(L.t("أقيمت الصلاة", "Prayer has started"), t.prayer.ar, t.adhan, t.iqama,
                null, "", t.prayer, maxOf(t.adhan + 30 * MIN, t.iqama + 5 * MIN))
        }
    }

    private fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 401, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Google Maps search for mosques near the user (same as the gold button in the app). */
    private fun openMosques(c: Context): PendingIntent {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode("مسجد")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(c, 402, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setChrono(v: RemoteViews, id: Int, target: Long?) {
        if (target == null) { v.setViewVisibility(id, View.GONE); return }
        v.setViewVisibility(id, View.VISIBLE)
        val base = SystemClock.elapsedRealtime() + (target - System.currentTimeMillis())
        v.setChronometer(id, base, null, true)
        v.setChronometerCountDown(id, true)
    }

    private fun nextViews(c: Context): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_next)
        v.setOnClickPendingIntent(R.id.wRoot, openApp(c))
        v.setOnClickPendingIntent(R.id.wGo, openMosques(c))
        v.setTextViewText(R.id.wGo, L.t("🕌 أقرب مسجد", "🕌 Nearest mosque"))
        val n = now(c)
        if (n == null) {
            v.setTextViewText(R.id.wLabel, L.t("أقرب مسجد", "Nearest Mosque"))
            v.setTextViewText(R.id.wName, "🕌")
            v.setTextViewText(R.id.wTimes, L.t("افتح التطبيق لتحديد موقعك", "Open the app to set your location"))
            v.setViewVisibility(R.id.wLeftRow, View.GONE)
            return v
        }
        v.setTextViewText(R.id.wLabel, n.label)
        v.setTextViewText(R.id.wName, n.name)
        v.setTextViewText(R.id.wTimes, L.t(
            "الأذان ${PrayerCalc.fmtTime(n.adhan)} · الإقامة ${PrayerCalc.fmtTime(n.iqama)}",
            "Adhan ${PrayerCalc.fmtTime(n.adhan)} · Iqama ${PrayerCalc.fmtTime(n.iqama)}"))
        if (n.countTo != null) {
            v.setViewVisibility(R.id.wLeftRow, View.VISIBLE)
            v.setTextViewText(R.id.wLeftLabel, n.countLabel)
        } else v.setViewVisibility(R.id.wLeftRow, View.GONE)
        setChrono(v, R.id.wChrono, n.countTo)
        return v
    }

    private val rowIds = intArrayOf(R.id.dRow0, R.id.dRow1, R.id.dRow2, R.id.dRow3, R.id.dRow4)
    private val nameIds = intArrayOf(R.id.dName0, R.id.dName1, R.id.dName2, R.id.dName3, R.id.dName4)
    private val adhanIds = intArrayOf(R.id.dAdhan0, R.id.dAdhan1, R.id.dAdhan2, R.id.dAdhan3, R.id.dAdhan4)
    private val iqamaIds = intArrayOf(R.id.dIqama0, R.id.dIqama1, R.id.dIqama2, R.id.dIqama3, R.id.dIqama4)

    private fun dayViews(c: Context): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_day)
        v.setOnClickPendingIntent(R.id.dRoot, openApp(c))
        v.setOnClickPendingIntent(R.id.dGo, openMosques(c))
        v.setTextViewText(R.id.dGo, L.t("🕌 أقرب مسجد", "🕌 Nearest mosque"))
        val loc = Prefs.location(c)
        val n = now(c)
        if (loc == null || n == null) {
            v.setTextViewText(R.id.dHead, L.t("افتح التطبيق لتحديد موقعك", "Open the app to set your location"))
            v.setViewVisibility(R.id.dChrono, View.GONE)
            return v
        }
        v.setTextViewText(R.id.dHead, if (n.countTo != null) "${n.name} · ${n.countLabel}" else "${n.name} · ${n.label}")
        setChrono(v, R.id.dChrono, n.countTo)
        val nowMs = System.currentTimeMillis()
        v.setTextViewText(R.id.dSub, PrayerCalc.hijriText(nowMs))
        // The day of the shown prayer (after Isha it is tomorrow's Fajr, so tomorrow's times)
        val list = PrayerCalc.day(c, loc.first, loc.second, n.adhan).list
        for ((i, p) in list.withIndex()) {
            if (i > 4) break
            v.setTextViewText(nameIds[i], p.prayer.ar)
            v.setTextViewText(adhanIds[i], PrayerCalc.fmtTime(p.adhan))
            v.setTextViewText(iqamaIds[i], PrayerCalc.fmtTime(p.iqama))
            v.setInt(rowIds[i], "setBackgroundResource",
                if (p.prayer == n.prayer) R.drawable.widget_row_on else R.drawable.widget_row_off)
        }
        return v
    }

    /** Refresh the widgets at the next change (adhan, iqama, end of prayer). */
    private fun scheduleTick(c: Context) {
        val n = now(c) ?: return
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            c, 403, Intent(c, NextPrayerWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = maxOf(n.nextChange + 2_000, System.currentTimeMillis() + 60_000)
        try {
            if (Alerts.canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi)
        } catch (_: Exception) {
        }
    }
}

class NextPrayerWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, wm: AppWidgetManager, ids: IntArray) = PrayerWidgets.updateAll(c)
    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action == PrayerWidgets.ACTION_TICK) PrayerWidgets.updateAll(c)
    }
}

class DayPrayerWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, wm: AppWidgetManager, ids: IntArray) = PrayerWidgets.updateAll(c)
}
