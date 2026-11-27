package com.aqrabmasjid.app

import android.content.Context

/** Fills the countdown ring from the prayer times. Shared by the main screen and driving mode. */
object RingFill {
    private const val WINDOW = 30 * 60_000L

    fun fill(c: Context, ring: CountdownRing, lat: Double, lng: Double, now: Long = System.currentTimeMillis()) {
        val st = PrayerCalc.status(c, lat, lng, now)
        val t = st.time
        val name = t.pname

        if (!st.isCurrent) {
            // Waiting for adhan: ring shrinks from the previous adhan to the next one
            val left = t.adhan - now
            if (left > WINDOW) {
                // More than 30 min: no ring, just the next prayer and its time
                ring.update(
                    L.t("الصلاة القادمة", "Next prayer"),
                    name,
                    L.t("الأذان ${PrayerCalc.fmtTime(t.adhan)}", "Adhan ${PrayerCalc.fmtTime(t.adhan)}"),
                    "", 1f, false, true
                )
                return
            }
            // Last 30 min: ring shrinks to zero at adhan
            val total = WINDOW
            ring.update(
                L.t("باقي على دخول صلاة", "Time left to"),
                name,
                CountdownRing.clock(left),
                L.t("الأذان ${PrayerCalc.fmtTime(t.adhan)}", "Adhan ${PrayerCalc.fmtTime(t.adhan)}"),
                left.toFloat() / total,
                false
            )
        } else {
            // After adhan: green, counts up for 30 min from the adhan
            val since = now - t.adhan
            val iq = if (t.isJumuah) L.t("خطبة الجمعة", "Jumuah khutbah")
                else if (t.iqama > now) L.t("الإقامة بعد ${CountdownRing.clock(t.iqama - now)}", "Iqama in ${CountdownRing.clock(t.iqama - now)}")
                else L.t("أقيمت الصلاة", "Prayer started")
            if (since in 0 until WINDOW) {
                ring.update(L.t("وقت صلاة", "It is now time for"), name, CountdownRing.clock(since), iq,
                    since.toFloat() / WINDOW, true)
            } else {
                ring.update("", name, L.t("أقيمت الصلاة", "Prayer started"),
                    L.t("الإقامة ${PrayerCalc.fmtTime(t.iqama)}", "Iqama ${PrayerCalc.fmtTime(t.iqama)}"), 1f, true)
            }
        }
    }
}
