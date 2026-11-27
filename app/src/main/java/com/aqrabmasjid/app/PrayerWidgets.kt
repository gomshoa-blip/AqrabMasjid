package com.aqrabmasjid.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/**
 * Home screen widgets (5 shapes and sizes):
 * - NextPrayerWidget: next prayer, adhan + iqama, live countdown, "nearest mosque" button.
 * - DayPrayerWidget: today's five prayers, the coming one highlighted.
 * - MiniPrayerWidget: small box, next prayer + big counter (1x1 to 3x2).
 * - RoundPrayerWidget: a circle with the next prayer + big counter.
 * - StripPrayerWidget: wide light strip, the five prayers in one row.
 * The countdown is a Chronometer, so it ticks by itself without waking the app.
 * The widgets are refreshed at each adhan / iqama (alarm below), and every 30 minutes by Android.
 */
object PrayerWidgets {
    const val ACTION_TICK = "com.aqrabmasjid.app.WIDGET_TICK"
    private const val MIN = 60_000L
    /** Friday: minutes before the Jumuah adhan when the khutbah counter starts. */
    private const val JUMUAH_MIN = 60L

    fun updateAll(c: Context) {
        val app = c.applicationContext
        try {
            L.init(app)
            val wm = AppWidgetManager.getInstance(app)
            val nextIds = wm.getAppWidgetIds(ComponentName(app, NextPrayerWidget::class.java))
            val dayIds = wm.getAppWidgetIds(ComponentName(app, DayPrayerWidget::class.java))
            val miniIds = wm.getAppWidgetIds(ComponentName(app, MiniPrayerWidget::class.java))
            val roundIds = wm.getAppWidgetIds(ComponentName(app, RoundPrayerWidget::class.java))
            val stripIds = wm.getAppWidgetIds(ComponentName(app, StripPrayerWidget::class.java))
            if (nextIds.isEmpty() && dayIds.isEmpty() && miniIds.isEmpty() && roundIds.isEmpty() && stripIds.isEmpty()) return
            if (nextIds.isNotEmpty()) wm.updateAppWidget(nextIds, nextViews(app))
            if (dayIds.isNotEmpty()) wm.updateAppWidget(dayIds, dayViews(app))
            if (miniIds.isNotEmpty()) wm.updateAppWidget(miniIds, counterViews(app, R.layout.widget_mini, R.id.mRoot, R.id.mLabel, R.id.mName, R.id.mChrono, R.id.mTime))
            if (roundIds.isNotEmpty()) wm.updateAppWidget(roundIds, counterViews(app, R.layout.widget_round, R.id.rRoot, R.id.rLabel, R.id.rName, R.id.rChrono, R.id.rTime))
            if (stripIds.isNotEmpty()) wm.updateAppWidget(stripIds, stripViews(app))
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
            // Friday: the khutbah starts with the Jumuah adhan
            Now(L.t("الصلاة القادمة", "Next prayer"), t.pname, t.adhan, t.iqama,
                t.adhan, if (t.isJumuah) L.t("متبقي للخطبة", "to khutbah") else L.t("متبقي للأذان", "to adhan"),
                t.prayer, t.adhan)
        } else if (t.iqama > now) {
            Now(L.t("حان وقت الصلاة", "Prayer time now"), t.pname, t.adhan, t.iqama,
                t.iqama, L.t("متبقي للإقامة", "to iqama"), t.prayer, t.iqama)
        } else {
            Now(L.t("أقيمت الصلاة", "Prayer has started"), t.pname, t.adhan, t.iqama,
                null, "", t.prayer, maxOf(t.adhan + 30 * MIN, t.iqama + 5 * MIN))
        }
    }

    private fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 401, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** One tap: Google Maps opens straight on the mosques around the user (search "مسجد"). */
    private fun openMosques(c: Context): PendingIntent {
        val geo = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode("مسجد")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val maps = Intent(geo).setPackage("com.google.android.apps.maps")
        val i = if (maps.resolveActivity(c.packageManager) != null) maps else geo
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
        // Day, Hijri and Gregorian dates (the clock is a TextClock and ticks by itself)
        val nowMs = System.currentTimeMillis()
        v.setTextViewText(R.id.wDay, java.text.SimpleDateFormat("EEEE", L.locale).format(java.util.Date(nowMs)))
        v.setTextViewText(R.id.wHijri, PrayerCalc.hijriText(nowMs))
        v.setTextViewText(R.id.wGreg, java.text.SimpleDateFormat("d MMMM yyyy", L.locale).format(java.util.Date(nowMs)))
        val n = now(c)
        if (n == null) {
            v.setTextViewText(R.id.wLabel, L.t("أقرب مسجد", "Aqrab Masjid"))
            v.setTextViewText(R.id.wName, L.t("أقرب مسجد", "Aqrab Masjid"))
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
        v.setTextViewText(R.id.dGo, L.t("🕌  خذني لأقرب مسجد", "🕌  Take me to nearest mosque"))
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
            v.setTextViewText(nameIds[i], p.pname)
            v.setTextViewText(adhanIds[i], PrayerCalc.fmtTime(p.adhan))
            v.setTextViewText(iqamaIds[i], PrayerCalc.fmtTime(p.iqama))
            v.setInt(rowIds[i], "setBackgroundResource",
                if (p.prayer == n.prayer) R.drawable.widget_row_on else R.drawable.widget_row_off)
        }
        return v
    }

    /**
     * Small and round widgets: the same big counter as the app.
     * Red in the last 30 min before adhan (counts down), green for 30 min after adhan (counts up).
     */
    private fun counterViews(c: Context, layout: Int, root: Int, label: Int, name: Int, chrono: Int, time: Int): RemoteViews {
        val v = RemoteViews(c.packageName, layout)
        v.setOnClickPendingIntent(root, openApp(c))
        val loc = Prefs.location(c)
        if (loc == null) {
            v.setTextViewText(label, L.t("أقرب مسجد", "Aqrab Masjid"))
            v.setTextViewText(name, "🕌")
            v.setTextViewText(time, L.t("افتح التطبيق", "Open the app"))
            v.setViewVisibility(chrono, View.GONE)
            return v
        }
        val now = System.currentTimeMillis()
        val st = PrayerCalc.status(c, loc.first, loc.second, now)
        val t = st.time
        v.setTextViewText(name, t.pname)
        v.setViewVisibility(chrono, View.VISIBLE)
        val red = 0xFFFF6B6B.toInt(); val green = 0xFF3BE37F.toInt(); val gold = 0xFFF6D46B.toInt()
        val base = SystemClock.elapsedRealtime() + (t.adhan - now)
        if (!st.isCurrent) {
            // Friday: the red counter starts 1 hour before, and counts to the start of the khutbah
            val soon = t.adhan - now <= (if (t.isJumuah) JUMUAH_MIN else 30L) * MIN
            v.setTextViewText(label, when {
                soon && t.isJumuah -> L.t("باقي على الخطبة", "Time left to khutbah")
                soon -> L.t("باقي على دخول صلاة", "Time left to")
                else -> L.t("الصلاة القادمة", "Next prayer")
            })
            v.setChronometer(chrono, base, null, true)
            v.setChronometerCountDown(chrono, true)
            v.setTextColor(chrono, if (soon) red else gold)
            v.setTextColor(label, if (soon) red else gold)
            v.setTextViewText(time, L.t("الأذان ${PrayerCalc.fmtTime(t.adhan)}", "Adhan ${PrayerCalc.fmtTime(t.adhan)}"))
        } else if (now - t.adhan < 30 * MIN) {
            v.setTextViewText(label, if (t.isJumuah) L.t("بدأت الخطبة", "Khutbah started") else L.t("وقت صلاة", "It is now time for"))
            v.setChronometer(chrono, base, null, true)
            v.setChronometerCountDown(chrono, false)
            v.setTextColor(chrono, green)
            v.setTextColor(label, green)
            v.setTextViewText(time, if (t.isJumuah) L.t("خطبة الجمعة", "Jumuah khutbah")
                else L.t("الإقامة ${PrayerCalc.fmtTime(t.iqama)}", "Iqama ${PrayerCalc.fmtTime(t.iqama)}"))
        } else {
            v.setTextViewText(label, L.t("أقيمت الصلاة", "Prayer has started"))
            v.setTextColor(label, gold)
            v.setViewVisibility(chrono, View.GONE)
            v.setTextViewText(time, L.t("الإقامة ${PrayerCalc.fmtTime(t.iqama)}", "Iqama ${PrayerCalc.fmtTime(t.iqama)}"))
        }
        return v
    }

    private val sColIds = intArrayOf(R.id.sCol0, R.id.sCol1, R.id.sCol2, R.id.sCol3, R.id.sCol4)
    private val sNameIds = intArrayOf(R.id.sName0, R.id.sName1, R.id.sName2, R.id.sName3, R.id.sName4)
    private val sTimeIds = intArrayOf(R.id.sTime0, R.id.sTime1, R.id.sTime2, R.id.sTime3, R.id.sTime4)

    /** Wide light strip: the five prayers in one row, the coming one in green. */
    private fun stripViews(c: Context): RemoteViews {
        val v = RemoteViews(c.packageName, R.layout.widget_strip)
        v.setOnClickPendingIntent(R.id.sRoot, openApp(c))
        val loc = Prefs.location(c)
        val n = now(c)
        if (loc == null || n == null) {
            v.setTextViewText(sNameIds[2], L.t("افتح التطبيق", "Open the app"))
            return v
        }
        val list = PrayerCalc.day(c, loc.first, loc.second, n.adhan).list
        for ((i, p) in list.withIndex()) {
            if (i > 4) break
            val on = p.prayer == n.prayer
            v.setTextViewText(sNameIds[i], p.pname)
            v.setTextViewText(sTimeIds[i], PrayerCalc.fmtTime(p.adhan))
            v.setInt(sColIds[i], "setBackgroundResource", if (on) R.drawable.widget_strip_on else R.drawable.widget_row_off)
            v.setTextColor(sNameIds[i], if (on) 0xFFFFFFFF.toInt() else 0xFF1B1606.toInt())
            v.setTextColor(sTimeIds[i], if (on) 0xFFF6D46B.toInt() else 0xFF0F6B4F.toInt())
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
        val nowMs = System.currentTimeMillis()
        val jumuah = PrayerTime(n.prayer, n.adhan, n.iqama).isJumuah
        val red = n.adhan - (if (jumuah) JUMUAH_MIN else 30L) * MIN
        val change = if (red > nowMs && red < n.nextChange) red else n.nextChange
        val at = maxOf(change + 2_000, nowMs + 60_000)
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

class MiniPrayerWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, wm: AppWidgetManager, ids: IntArray) = PrayerWidgets.updateAll(c)
}

class RoundPrayerWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, wm: AppWidgetManager, ids: IntArray) = PrayerWidgets.updateAll(c)
}

class StripPrayerWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, wm: AppWidgetManager, ids: IntArray) = PrayerWidgets.updateAll(c)
}
