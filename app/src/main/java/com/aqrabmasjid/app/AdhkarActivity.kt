package com.aqrabmasjid.app

import android.content.Context
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Typeface
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
 * Adhkar: morning, evening, and after prayer (from Hisn Al-Muslim).
 * Tap a card to count down; it turns green when done.
 */
class AdhkarActivity : Activity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Look.wrap(base))   // day / night choice
    }


    private val morning get() = AdhkarData.morning
    private val evening get() = AdhkarData.evening
    private val afterPrayer get() = AdhkarData.afterPrayer

    private lateinit var list: LinearLayout
    private val tabs = mutableListOf<Button>()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        L.init(this)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(getColor(R.color.bg))
        scroll.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        root.setPadding(pad, pad, pad, pad)
        scroll.addView(root)

        // Header
        val head = TextView(this)
        head.text = "📿 الأذكار"
        head.textSize = 26f
        head.setTypeface(null, Typeface.BOLD)
        head.setTextColor(getColor(R.color.title_text))
        head.gravity = Gravity.CENTER
        root.addView(head)

        // Read the adhkar on Atheer Radio's site, and their app
        fun link(label: String, url: String, gold: Boolean) {
            val b = TextView(this)
            b.text = label
            b.textSize = if (gold) 17f else 14f
            b.setTypeface(null, Typeface.BOLD)
            b.gravity = Gravity.CENTER
            b.setTextColor(if (gold) 0xFF1B1606.toInt() else getColor(R.color.title_text))
            b.setPadding(dp(12), dp(12), dp(12), dp(12))
            b.setBackgroundResource(if (gold) R.drawable.bg_circle_gold_rect else R.drawable.bg_station_idle)
            b.isClickable = true
            b.setOnClickListener {
                try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (_: Exception) {}
            }
            root.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) })
        }
        link(L.t("📖 قراءة الأذكار", "📖 Read the adhkar"), Config.ATHEER_ADHKAR_URL, true)
        link(L.t("📲 تطبيق إذاعة أثير: قرآن وتلاوات ومحاضرات", "📲 Atheer Radio app: Quran, recitations, lectures"), Config.ATHEER_APP_URL, false)

        // Tabs
        val tabRow = LinearLayout(this)
        tabRow.orientation = LinearLayout.HORIZONTAL
        root.addView(tabRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        val sets = listOf("الصباح" to morning, "المساء" to evening, "بعد الصلاة" to afterPrayer)
        sets.forEachIndexed { i, (name, items) ->
            val b = Button(this)
            b.text = name
            b.isAllCaps = false
            b.textSize = 16f
            b.setTypeface(null, Typeface.BOLD)
            b.stateListAnimator = null
            b.setOnClickListener { select(i, items) }
            tabs += b
            tabRow.addView(b, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginStart = if (i == 0) 0 else dp(6)
            })
        }

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        root.addView(list)

        val src = TextView(this)
        src.text = "المصدر: حصن المسلم"
        src.textSize = 13f
        src.setTextColor(getColor(R.color.text_hint))
        src.gravity = Gravity.CENTER
        root.addView(src, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        setContentView(scroll)

        // Bars and insets
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            try { window.insetsController?.setSystemBarsAppearance(if (night) 0 else light, light) } catch (_: Exception) {}
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
            }
            insets
        }

        // Open the right set for the time of day
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (h in 3..11) select(0, morning) else select(1, evening)
    }

    private fun select(index: Int, items: List<Dhikr>) {
        tabs.forEachIndexed { i, b ->
            if (i == index) {
                b.setBackgroundResource(R.drawable.bg_btn_green)
                b.setTextColor(0xFFFFFFFF.toInt())
            } else {
                b.setBackgroundResource(R.drawable.bg_btn_outline)
                b.setTextColor(getColor(R.color.accent_text))
            }
        }
        list.removeAllViews()
        for (d in items) list.addView(card(d))
    }

    private fun card(d: Dhikr): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setBackgroundResource(R.drawable.bg_card_white)
        c.setPadding(dp(16), dp(14), dp(16), dp(12))
        c.lp(dp(10))

        val t = TextView(this)
        t.text = d.text
        t.textSize = 19f
        t.setLineSpacing(0f, 1.35f)
        t.setTextColor(getColor(R.color.text_primary))
        c.addView(t)

        val counter = TextView(this)
        counter.textSize = 16f
        counter.setTypeface(null, Typeface.BOLD)
        counter.gravity = Gravity.CENTER
        counter.setPadding(0, dp(8), 0, dp(8))
        counter.setBackgroundResource(R.drawable.bg_btn_outline)
        counter.setTextColor(getColor(R.color.accent_text))
        c.addView(counter, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        var left = d.count
        fun show() {
            if (left > 0) {
                counter.text = if (d.count == 1) "مرة واحدة · اضغط عند الانتهاء" else "المتبقي: $left من ${d.count} · اضغط للعد"
            } else {
                counter.text = "✓ تم"
                counter.setBackgroundResource(R.drawable.bg_btn_green)
                counter.setTextColor(0xFFFFFFFF.toInt())
            }
        }
        show()
        val tap = View.OnClickListener {
            if (left > 0) {
                left--
                show()
                if (left == 0) c.alpha = 0.75f
            }
        }
        c.setOnClickListener(tap)
        counter.setOnClickListener(tap)
        return c
    }

    private fun View.lp(top: Int) {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
    }
}
