package com.aqrabmasjid.app

import android.content.Context
import java.util.Locale

/**
 * App language, saved on the phone: Arabic (default), English, Urdu, Indonesian, Bengali, Turkish,
 * French, Italian, Spanish.
 * The code gives each text in Arabic and English: L.t(ar, en).
 * Urdu, Indonesian, Bengali and Turkish come from Tr.MAP; French, Italian and Spanish from Tr2.MAP
 * (both looked up by the English text).
 */
object L {
    /** Language codes and their names, in the order shown in the language list. */
    val LANGS = listOf(
        "ar" to "العربية",
        "en" to "English",
        "ur" to "اردو",
        "id" to "Bahasa Indonesia",
        "bn" to "বাংলা",
        "tr" to "Türkçe",
        "fr" to "Français",
        "it" to "Italiano",
        "es" to "Español"
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
    val systemFont: Boolean get() = lang == "ur" || lang == "bn" || lang == "tr" || lang == "fr" || lang == "it" || lang == "es"

    fun init(c: Context) {
        val v = c.getSharedPreferences("aqrab", Context.MODE_PRIVATE).getString("lang", "ar") ?: "ar"
        lang = if (LANGS.any { it.first == v }) v else "ar"
    }

    fun setLang(c: Context, code: String) {
        c.getSharedPreferences("aqrab", Context.MODE_PRIVATE).edit().putString("lang", code).apply()
        lang = code
    }

    fun name(code: String = lang): String = LANGS.firstOrNull { it.first == code }?.second ?: code

    private fun idx(): Int = when (lang) { "ur" -> 0; "id" -> 1; "bn" -> 2; "tr" -> 3; "fr" -> 0; "it" -> 1; "es" -> 2; else -> -1 }

    private val latin: Boolean get() = lang == "fr" || lang == "it" || lang == "es"

    /** Pick the text in the app language. */
    fun t(ar: String, english: String): String {
        if (lang == "ar") return ar
        val i = idx()
        if (i < 0) return english
        val map = if (latin) Tr2.MAP else Tr.MAP
        return map[english]?.get(i) ?: fromTemplate(english, i, if (latin) templates2 else templates) ?: english
    }

    private fun build(m: Map<String, Array<String>>): List<Pair<Regex, Array<String>>> =
        m.filterKeys { it.contains("{}") }
            .entries.sortedByDescending { it.key.length }
            .map { (k, v) ->
                val parts = k.split("{}").map { Regex.escape(it) }
                Regex("^" + parts.joinToString("(.+?)") + "$", RegexOption.DOT_MATCHES_ALL) to v
            }

    /** Templates like "Adhan {}": the English text has values filled in, so match it by pattern. */
    private val templates: List<Pair<Regex, Array<String>>> by lazy { build(Tr.MAP) }
    private val templates2: List<Pair<Regex, Array<String>>> by lazy { build(Tr2.MAP) }

    private fun fromTemplate(s: String, i: Int, list: List<Pair<Regex, Array<String>>>): String? {
        for ((re, v) in list) {
            val m = re.matchEntire(s) ?: continue
            var out = v[i]
            for (g in m.groupValues.drop(1)) out = out.replaceFirst("{}", g)
            return out
        }
        return null
    }

    @Suppress("DEPRECATION")   // Locale("in", "ID") keeps the old Indonesian code that Android resources use
    val locale: Locale get() = when (lang) {
        "en" -> Locale.ENGLISH
        "ur" -> Locale("ur")
        "id" -> Locale("in", "ID")
        "bn" -> Locale("bn")
        "tr" -> Locale("tr")
        "fr" -> Locale.FRENCH
        "it" -> Locale.ITALIAN
        "es" -> Locale("es")
        else -> Locale("ar")
    }

    /** ICU locale name for the Hijri date. */
    val icu: String get() = when (lang) {
        "ar" -> "ar_SA"
        "ur" -> "ur"
        "id" -> "id"
        "bn" -> "bn"
        "tr" -> "tr"
        "fr" -> "fr"
        "it" -> "it"
        "es" -> "es"
        else -> "en"
    }
}
