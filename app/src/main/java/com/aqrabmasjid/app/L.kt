package com.aqrabmasjid.app

import android.content.Context
import java.util.Locale

/**
 * App language, saved on the phone: Arabic (default), English, Urdu, Indonesian, Bengali, Turkish.
 * The code gives each text in Arabic and English: L.t(ar, en).
 * Urdu, Indonesian, Bengali and Turkish come from Tr.MAP (looked up by the English text).
 */
object L {
    /** Language codes and their names, in the order shown in the language list. */
    val LANGS = listOf(
        "ar" to "العربية",
        "en" to "English",
        "ur" to "اردو",
        "id" to "Bahasa Indonesia",
        "bn" to "বাংলা",
        "tr" to "Türkçe"
    )

    /** Short code shown on the language button. */
    fun code(): String = lang.uppercase(Locale.ROOT)

    @Volatile var lang = "ar"
        private set

    /** English text is used (English, or a language that falls back to English). */
    val en: Boolean get() = lang != "ar"

    /** Right-to-left languages. */
    val rtl: Boolean get() = lang == "ar" || lang == "ur"

    /** Urdu and Bengali letters are not in our Arabic font, so those use the phone's font. */
    val systemFont: Boolean get() = lang == "ur" || lang == "bn" || lang == "tr"

    fun init(c: Context) {
        val v = c.getSharedPreferences("aqrab", Context.MODE_PRIVATE).getString("lang", "ar") ?: "ar"
        lang = if (LANGS.any { it.first == v }) v else "ar"
    }

    fun setLang(c: Context, code: String) {
        c.getSharedPreferences("aqrab", Context.MODE_PRIVATE).edit().putString("lang", code).apply()
        lang = code
    }

    fun name(code: String = lang): String = LANGS.firstOrNull { it.first == code }?.second ?: code

    private fun idx(): Int = when (lang) { "ur" -> 0; "id" -> 1; "bn" -> 2; "tr" -> 3; else -> -1 }

    /** Pick the text in the app language. */
    fun t(ar: String, english: String): String {
        if (lang == "ar") return ar
        val i = idx()
        if (i < 0) return english
        return Tr.MAP[english]?.get(i) ?: fromTemplate(english, i) ?: english
    }

    /** Templates like "Adhan {}": the English text has values filled in, so match it by pattern. */
    private val templates: List<Pair<Regex, Array<String>>> by lazy {
        Tr.MAP.filterKeys { it.contains("{}") }
            .entries.sortedByDescending { it.key.length }
            .map { (k, v) ->
                val parts = k.split("{}").map { Regex.escape(it) }
                Regex("^" + parts.joinToString("(.+?)") + "$", RegexOption.DOT_MATCHES_ALL) to v
            }
    }

    private fun fromTemplate(s: String, i: Int): String? {
        for ((re, v) in templates) {
            val m = re.matchEntire(s) ?: continue
            var out = v[i]
            for (g in m.groupValues.drop(1)) out = out.replaceFirst("{}", g)
            return out
        }
        return null
    }

    val locale: Locale get() = when (lang) {
        "en" -> Locale.ENGLISH
        "ur" -> Locale("ur")
        "id" -> Locale("in", "ID")
        "bn" -> Locale("bn")
        "tr" -> Locale("tr")
        else -> Locale("ar")
    }

    /** ICU locale name for the Hijri date. */
    val icu: String get() = when (lang) {
        "ar" -> "ar_SA"
        "ur" -> "ur"
        "id" -> "id"
        "bn" -> "bn"
        "tr" -> "tr"
        else -> "en"
    }
}
