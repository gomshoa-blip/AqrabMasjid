package com.aqrabmasjid.app

import android.content.Context

/** Fills the countdown ring from the prayer times. Shared by the main screen and driving mode. */
object RingFill {
    private const val WINDOW = 60 * 60_000L

    fun fill(c: Context, ring: CountdownRing, lat: Double, lng: Double, now: Long = System.currentTimeMillis()) {
        val st = PrayerCalc.status(c, lat, lng, now)
        val t = st.time
        val name = t.pname

        if (!st.isCurrent) {
            // Waiting for adhan: ring shrinks from the previous adhan to the next one
            val left = t.adhan - now
            if (left > WINDOW) {
                // More than 60 min: no ring, just the next prayer and its time
                ring.update(
                    L.t("الصلاة القادمة", "Next prayer"),
                    name,
                    L.t("الأذان ${PrayerCalc.fmtTime(t.adhan)}", "Adhan ${PrayerCalc.fmtTime(t.adhan)}"),
                    "", 1f, false, true
                )
                return
            }
            // Last 60 min: ring shrinks to zero at adhan
            val total = WINDOW
            ring.update(
                L.t("الصلاة القادمة", "Next prayer"),
                name,
                CountdownRing.clock(left),
                L.t("الأذان ${PrayerCalc.fmtTime(t.adhan)}", "Adhan ${PrayerCalc.fmtTime(t.adhan)}"),
                left.toFloat() / total,
                false
            )
        } else {
            // After adhan: counts down to the iqama, then "أقيمت الصلاة" for 10 min
            val toIq = t.iqama - now
            if (toIq > 0) {
                val span = (t.iqama - t.adhan).coerceAtLeast(1L)
                ring.update(L.t("دخل وقت صلاة", "It is now time for"), name, CountdownRing.clock(toIq),
                    if (t.isJumuah) L.t("خطبة الجمعة", "Jumuah khutbah") else L.t("تبقى على الإقامة", "to iqama"),
                    toIq.toFloat() / span, true)
            } else {
                ring.update("", name, L.t("أقيمت الصلاة", "Prayer started"),
                    L.t("الإقامة ${PrayerCalc.fmtTime(t.iqama)}", "Iqama ${PrayerCalc.fmtTime(t.iqama)}"), 1f, true)
            }
        }
    }
}
