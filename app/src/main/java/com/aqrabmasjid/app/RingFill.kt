package com.aqrabmasjid.app

import android.content.Context

/** Fills the countdown ring from the prayer times. Shared by the main screen and driving mode. */
object RingFill {
    private const val WINDOW = 30 * 60_000L

    fun fill(c: Context, ring: CountdownRing, lat: Double, lng: Double, now: Long = System.currentTimeMillis()) {
        val st = PrayerCalc.status(c, lat, lng, now)
        val t = st.time
        val name = t.prayer.ar

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
                L.t("بقي على صلاة", "Time left to"),
                name,
                CountdownRing.clock(left),
                L.t("الأذان ${PrayerCalc.fmtTime(t.adhan)}", "Adhan ${PrayerCalc.fmtTime(t.adhan)}"),
                left.toFloat() / total,
                false
            )
        } else {
            val left = t.iqama - now
            if (left > 0) {
                // After adhan: green ring, counts down to iqama
                val total = (t.iqama - t.adhan).coerceAtLeast(1L)
                ring.update(
                    L.t("أذّن · بقي على الإقامة", "Adhan done · Iqama in"),
                    name,
                    CountdownRing.clock(left),
                    L.t("الإقامة ${PrayerCalc.fmtTime(t.iqama)}", "Iqama ${PrayerCalc.fmtTime(t.iqama)}"),
                    left.toFloat() / total,
                    true
                )
            } else {
                ring.update(
                    "",
                    name,
                    L.t("أقيمت الصلاة", "Prayer started"),
                    L.t("الإقامة ${PrayerCalc.fmtTime(t.iqama)}", "Iqama ${PrayerCalc.fmtTime(t.iqama)}"),
                    1f,
                    true
                )
            }
        }
    }
}
