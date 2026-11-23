package com.aqrabmasjid.app

import android.content.Context

/**
 * Short reminder for each prayer (authentic hadith or Quran, with source).
 * Shown on the main screen button and in the adhan alert. A different one each time.
 */
object PrayerReminders {

    private val AR = mapOf(
        Prayer.FAJR to listOf(
            "«من صلى الصبح فهو في ذمة الله» رواه مسلم",
            "«من صلى البردين دخل الجنة» متفق عليه",
            "«ركعتا الفجر خير من الدنيا وما فيها» رواه مسلم",
            "«بشّر المشّائين في الظُّلَم إلى المساجد بالنور التام يوم القيامة» أبو داود والترمذي"
        ),
        Prayer.DHUHR to listOf(
            "«إنها ساعة تُفتح فيها أبواب السماء، وأحب أن يصعد لي فيها عمل صالح» الترمذي",
            "«من حافظ على أربع ركعات قبل الظهر وأربع بعدها حرّمه الله على النار» أبو داود والترمذي",
            "«أحب الأعمال إلى الله: الصلاة على وقتها» متفق عليه"
        ),
        Prayer.ASR to listOf(
            "«الذي تفوته صلاة العصر كأنما وُتِر أهله وماله» متفق عليه",
            "«من ترك صلاة العصر فقد حبط عمله» رواه البخاري",
            "﴿حافظوا على الصلوات والصلاة الوسطى﴾ البقرة: 238",
            "«من صلى البردين دخل الجنة» متفق عليه"
        ),
        Prayer.MAGHRIB to listOf(
            "«صلاة الجماعة أفضل من صلاة الفذّ بسبع وعشرين درجة» متفق عليه",
            "«أحب الأعمال إلى الله: الصلاة على وقتها» متفق عليه",
            "وقت المغرب قصير، بادر إليها في أول وقتها"
        ),
        Prayer.ISHA to listOf(
            "«من صلى العشاء في جماعة فكأنما قام نصف الليل» رواه مسلم",
            "«لو يعلمون ما في العتمة والصبح لأتوهما ولو حبوًا» متفق عليه",
            "«بشّر المشّائين في الظُّلَم إلى المساجد بالنور التام يوم القيامة» أبو داود والترمذي",
            "اختم يومك بصلاة العشاء في جماعة"
        )
    )

    private val EN = mapOf(
        Prayer.FAJR to listOf(
            "\"Whoever prays Fajr is under the protection of Allah.\" (Muslim)",
            "\"Whoever prays the two cool prayers (Fajr and Asr) will enter Paradise.\" (Bukhari, Muslim)"
        ),
        Prayer.DHUHR to listOf(
            "\"The most beloved deed to Allah is prayer on time.\" (Bukhari, Muslim)"
        ),
        Prayer.ASR to listOf(
            "\"Whoever misses Asr is like one who lost his family and wealth.\" (Bukhari, Muslim)",
            "\"Guard the prayers, and the middle prayer.\" (Quran 2:238)"
        ),
        Prayer.MAGHRIB to listOf(
            "\"Prayer in congregation is 27 times better than praying alone.\" (Bukhari, Muslim)"
        ),
        Prayer.ISHA to listOf(
            "\"Whoever prays Isha in congregation, it is as if he prayed half the night.\" (Muslim)"
        )
    )

    private fun list(p: Prayer) = (if (L.en) EN else AR)[p] ?: emptyList()

    /** Current reminder for this prayer (same until next()). */
    fun current(c: Context, p: Prayer): String {
        val l = list(p)
        if (l.isEmpty()) return ""
        val i = c.getSharedPreferences("reminders", Context.MODE_PRIVATE).getInt(p.name, 0)
        return l[Math.floorMod(i, l.size)]
    }

    /** Move to the next reminder for this prayer and return it. */
    fun next(c: Context, p: Prayer): String {
        val sp = c.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        sp.edit().putInt(p.name, sp.getInt(p.name, 0) + 1).apply()
        return current(c, p)
    }
}
