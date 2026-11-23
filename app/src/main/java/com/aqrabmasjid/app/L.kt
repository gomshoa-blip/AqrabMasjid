package com.aqrabmasjid.app

import android.content.Context
import java.util.Locale

/** App language: Arabic (default) or English. Saved on the phone. */
object L {
    @Volatile var en = false
        private set

    fun init(c: Context) {
        en = c.getSharedPreferences("aqrab", Context.MODE_PRIVATE).getString("lang", "ar") == "en"
    }

    fun setEnglish(c: Context, english: Boolean) {
        c.getSharedPreferences("aqrab", Context.MODE_PRIVATE).edit()
            .putString("lang", if (english) "en" else "ar").apply()
        en = english
    }

    /** Pick the Arabic or English text. */
    fun t(ar: String, english: String): String = if (en) english else ar

    val locale: Locale get() = if (en) Locale.ENGLISH else Locale("ar")
}
