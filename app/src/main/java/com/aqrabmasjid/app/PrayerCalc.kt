package com.aqrabmasjid.app

import android.content.Context
import android.icu.util.IslamicCalendar
import android.icu.util.ULocale
import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.Madhab
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.data.DateComponents
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class Prayer(private val arName: String, private val enName: String) {
    FAJR("الفجر", "Fajr"), DHUHR("الظهر", "Dhuhr"), ASR("العصر", "Asr"),
    MAGHRIB("المغرب", "Maghrib"), ISHA("العشاء", "Isha");

    /** Name in the current app language (kept as "ar" so old code still works). */
    val ar: String get() = L.t(arName, enName)
}

data class PrayerTime(val prayer: Prayer, val adhan: Long, val iqama: Long) {
    /** Friday Dhuhr is Jumuah. */
    val isJumuah: Boolean get() = prayer == Prayer.DHUHR &&
        java.util.Calendar.getInstance().apply { timeInMillis = adhan }.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.FRIDAY
    /** Name to show: "الجمعة" on Friday instead of "الظهر". */
    val pname: String get() = if (isJumuah) L.t("الجمعة", "Jumuah") else prayer.ar
}

data class DayTimes(val list: List<PrayerTime>, val methodName: String, val ramadan: Boolean)

/** What to show now: the current prayer (30 min after adhan, or 5 min after iqama if later) or the next one. */
data class Status(val time: PrayerTime, val isCurrent: Boolean)

object PrayerCalc {
    private const val MIN = 60_000L
    private const val DAY = 24 * 60 * MIN

    /** Saudi fixed iqama (same as Al-Moazin app). */
    private val SAUDI_IQAMA = mapOf(
        Prayer.FAJR to 25, Prayer.DHUHR to 20, Prayer.ASR to 20,
        Prayer.MAGHRIB to 10, Prayer.ISHA to 20
    )
    private const val DEFAULT_IQAMA = 20

    fun isSaudi(c: Context, lat: Double, lng: Double): Boolean {
        val code = Prefs.country(c)
        if (code != null) return code == "SA"
        // Fallback: rough box around Saudi Arabia
        return lat in 16.0..32.5 && lng in 34.5..55.7
    }

    fun defaultIqama(c: Context, lat: Double, lng: Double, p: Prayer): Int =
        if (isSaudi(c, lat, lng)) SAUDI_IQAMA[p]!! else DEFAULT_IQAMA

    fun iqamaMinutes(c: Context, lat: Double, lng: Double, p: Prayer): Int {
        val user = Prefs.iqama(c, p)
        return if (user >= 0) user else defaultIqama(c, lat, lng, p)
    }

    fun isRamadan(millis: Long): Boolean {
        val cal = IslamicCalendar()
        cal.calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
        cal.timeInMillis = millis
        return cal.get(IslamicCalendar.MONTH) == IslamicCalendar.RAMADAN
    }

    fun hijriText(millis: Long): String {
        val f = android.icu.text.DateFormat.getDateInstance(
            android.icu.text.DateFormat.LONG,
            ULocale(L.icu + "@calendar=islamic-umalqura")
        )
        return f.format(Date(millis))
    }

    private fun method(c: Context, lat: Double, lng: Double): Pair<CalculationMethod, String> {
        if (isSaudi(c, lat, lng)) return CalculationMethod.UMM_AL_QURA to L.t("أم القرى", "Umm Al-Qura")
        return when (Prefs.country(c)) {
            "EG" -> CalculationMethod.EGYPTIAN to L.t("الهيئة المصرية العامة للمساحة", "Egyptian General Authority")
            "AE" -> CalculationMethod.DUBAI to L.t("دبي", "Dubai")
            "KW" -> CalculationMethod.KUWAIT to L.t("الكويت", "Kuwait")
            "QA" -> CalculationMethod.QATAR to L.t("قطر", "Qatar")
            "PK" -> CalculationMethod.KARACHI to L.t("كراتشي", "Karachi")
            "SG", "MY", "ID" -> CalculationMethod.SINGAPORE to L.t("سنغافورة", "Singapore")
            "US", "CA" -> CalculationMethod.NORTH_AMERICA to L.t("أمريكا الشمالية", "North America")
            else -> CalculationMethod.MUSLIM_WORLD_LEAGUE to L.t("رابطة العالم الإسلامي", "Muslim World League")
        }
    }

    /** Prayer times for the day that contains [dayMillis]. */
    fun day(c: Context, lat: Double, lng: Double, dayMillis: Long): DayTimes {
        val (m, name) = method(c, lat, lng)
        val params = m.parameters
        params.madhab = Madhab.SHAFI
        val ramadan = isRamadan(dayMillis)
        // Saudi: in Ramadan, Isha is 2 hours after Maghrib
        if (m == CalculationMethod.UMM_AL_QURA && ramadan) params.ishaInterval = 120

        val cal = Calendar.getInstance().apply { timeInMillis = dayMillis }
        val date = DateComponents(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
        val pt = PrayerTimes(Coordinates(lat, lng), date, params)
        val adhans = listOf(
            Prayer.FAJR to pt.fajr, Prayer.DHUHR to pt.dhuhr, Prayer.ASR to pt.asr,
            Prayer.MAGHRIB to pt.maghrib, Prayer.ISHA to pt.isha
        )
        val list = adhans.map { (p, d) ->
            PrayerTime(p, d.time, d.time + iqamaMinutes(c, lat, lng, p) * MIN)
        }
        return DayTimes(list, name, ramadan)
    }

    /** Today + tomorrow, sorted. */
    fun upcoming(c: Context, lat: Double, lng: Double, now: Long = System.currentTimeMillis()): List<PrayerTime> =
        day(c, lat, lng, now).list + day(c, lat, lng, now + DAY).list

    fun status(c: Context, lat: Double, lng: Double, now: Long = System.currentTimeMillis()): Status {
        val all = day(c, lat, lng, now - DAY).list + upcoming(c, lat, lng, now)
        // Stay on the current prayer until 10 min after iqama ("أقيمت الصلاة"), then the next prayer
        val current = all.lastOrNull { now >= it.adhan && now < maxOf(it.adhan, it.iqama) + 10 * MIN }
        if (current != null) return Status(current, true)
        return Status(all.first { it.adhan > now }, false)
    }

    fun fmtTime(millis: Long): String =
        SimpleDateFormat("h:mm a", L.locale).format(Date(millis))

    fun fmtLeft(ms: Long): String {
        val totalMin = ((ms + MIN - 1) / MIN).coerceAtLeast(0)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> L.t("$h س $m د", "${h}h ${m}m")
            h > 0 -> L.t("$h س", "${h}h")
            else -> L.t("$m د", "$m min")
        }
    }
}
