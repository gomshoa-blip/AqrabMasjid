package com.aqrabmasjid.app

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * List of nearby mosques inside the app. The user picks one, then
 * navigation starts. Nothing is sent to Google Maps until the user picks.
 */
class MosquesActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        L.init(this)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(getColor(R.color.bg))
        scroll.layoutDirection = if (L.en) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        root.setPadding(pad, pad, pad, pad)
        scroll.addView(root)

        // Top: back button + title
        val back = Button(this)
        back.text = L.t("→  رجوع", "←  Back")
        back.isAllCaps = false
        back.textSize = 16f
        back.setTextColor(getColor(R.color.accent_text))
        back.setBackgroundResource(R.drawable.bg_btn_outline)
        back.stateListAnimator = null
        back.minHeight = 0; back.minimumHeight = 0
        back.setPadding(dp(20), 0, dp(20), 0)
        back.setOnClickListener { finish() }
        root.addView(back, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46)))

        val title = TextView(this)
        title.text = L.t("🕌 أقرب المساجد", "🕌 Nearest mosques")
        title.textSize = 24f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(getColor(R.color.title_text))
        root.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        val hint = TextView(this)
        hint.text = L.t("اختر المسجد، ثم تبدأ الملاحة.", "Pick a mosque to start navigation.")
        hint.textSize = 14f
        hint.setTextColor(getColor(R.color.text_secondary))
        root.addView(hint)

        status = TextView(this)
        status.textSize = 16f
        status.setTextColor(getColor(R.color.text_primary))
        status.setPadding(0, dp(16), 0, dp(8))
        root.addView(status)

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        root.addView(list)

        setContentView(scroll)

        // Status bar icons and safe area (Android 15+)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.insetsController?.setSystemBarsAppearance(if (night) 0 else light, light)
            } catch (_: Exception) {
            }
        }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            }
            insets
        }

        load()
    }

    private fun load() {
        status.text = L.t("جاري البحث عن المساجد القريبة...", "Finding nearby mosques...")
        list.removeAllViews()
        LocationHelper.get(this) { loc ->
            if (loc == null) {
                status.text = L.t("اسمح للتطبيق بالموقع من الإعدادات", "Allow location for the app in Settings")
                return@get
            }
            MosqueRepo.nearby(this, loc.first, loc.second) { result ->
                if (isFinishing) return@nearby
                list.removeAllViews()
                if (result.isNullOrEmpty()) {
                    status.text = if (result == null)
                        L.t("ما قدرنا نجيب المساجد الحين.", "Could not load mosques now.")
                    else L.t("ما لقينا مساجد قريبة.", "No mosques found nearby.")
                    list.addView(retryButton())
                    list.addView(mapsButton())
                    return@nearby
                }
                status.text = ""
                for (m in result.take(10)) list.addView(row(m))
                list.addView(mapsButton())
            }
        }
    }

    private fun row(m: Mosque): View {
        val item = LinearLayout(this)
        item.orientation = LinearLayout.HORIZONTAL
        item.gravity = Gravity.CENTER_VERTICAL
        item.setBackgroundResource(R.drawable.bg_card_white)
        item.setPadding(dp(16), dp(12), dp(12), dp(12))
        item.setOnClickListener { navigate(m) }

        val text = LinearLayout(this)
        text.orientation = LinearLayout.VERTICAL
        val name = TextView(this)
        name.text = m.name
        name.textSize = 19f
        name.setTypeface(null, Typeface.BOLD)
        name.setTextColor(getColor(R.color.text_primary))
        val dist = TextView(this)
        dist.text = MosqueRepo.fmtDistance(m.distKm)
        dist.textSize = 15f
        dist.setTextColor(getColor(R.color.text_secondary))
        text.addView(name)
        text.addView(dist)
        item.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val go = Button(this)
        go.text = L.t("خذني", "Go")
        go.isAllCaps = false
        go.textSize = 17f
        go.setTypeface(null, Typeface.BOLD)
        go.setTextColor(getColor(android.R.color.white))
        go.setBackgroundResource(R.drawable.bg_btn_green)
        go.stateListAnimator = null
        go.minHeight = 0; go.minimumHeight = 0
        go.setPadding(dp(22), 0, dp(22), 0)
        go.setOnClickListener { navigate(m) }
        item.addView(go, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(50)))

        item.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        return item
    }

    private fun retryButton(): View = outline(L.t("↻ حاول مرة ثانية", "↻ Try again")) { load() }

    /** Optional: let Google Maps search, only if the user taps this. */
    private fun mapsButton(): View =
        outline(L.t("🔎 ابحث في خرائط Google", "🔎 Search in Google Maps")) { MapsSearch.mosques(this) }

    private fun outline(label: String, onClick: () -> Unit): View {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 16f
        b.setTextColor(getColor(R.color.accent_text))
        b.setBackgroundResource(R.drawable.bg_btn_outline)
        b.stateListAnimator = null
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) }
        return b
    }

    private fun navigate(m: Mosque) {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${m.lat},${m.lng}"))
        try {
            startActivity(nav)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${m.lat},${m.lng}")))
            } catch (_: Exception) {
            }
        }
    }
}
