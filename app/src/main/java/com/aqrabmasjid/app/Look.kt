package com.aqrabmasjid.app

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

/** Day / night look chosen by the user: "auto" follows the phone. */
object Look {
    /** Android 12+: the system keeps the choice for the whole app. */
    fun apply(c: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        val mode = when (Prefs.theme(c)) {
            "day" -> UiModeManager.MODE_NIGHT_NO
            "night" -> UiModeManager.MODE_NIGHT_YES
            else -> UiModeManager.MODE_NIGHT_AUTO
        }
        try { c.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode) } catch (_: Exception) {}
    }

    /** Older Android: change the night flag for the screen that asks. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 31) return base
        val flag = when (Prefs.theme(base)) {
            "day" -> Configuration.UI_MODE_NIGHT_NO
            "night" -> Configuration.UI_MODE_NIGHT_YES
            else -> return base
        }
        val cfg = Configuration(base.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or flag
        return base.createConfigurationContext(cfg)
    }

    fun label(t: String): String = when (t) {
        "day" -> L.t("☀️ نهاري", "☀️ Day")
        "night" -> L.t("🌙 ليلي", "🌙 Night")
        else -> L.t("📱 تلقائي (حسب الجوال)", "📱 Auto (like the phone)")
    }
}
