package com.aqrabmasjid.app

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

    private class Dhikr(val text: String, val count: Int)

    private val morning = listOf(
        Dhikr("آية الكرسي:\nالله لا إله إلا هو الحي القيوم لا تأخذه سنة ولا نوم له ما في السماوات وما في الأرض من ذا الذي يشفع عنده إلا بإذنه يعلم ما بين أيديهم وما خلفهم ولا يحيطون بشيء من علمه إلا بما شاء وسع كرسيه السماوات والأرض ولا يئوده حفظهما وهو العلي العظيم", 1),
        Dhikr("قراءة سورة الإخلاص والفلق والناس", 3),
        Dhikr("أصبحنا وأصبح الملك لله، والحمد لله، لا إله إلا الله وحده لا شريك له، له الملك وله الحمد وهو على كل شيء قدير، رب أسألك خير ما في هذا اليوم وخير ما بعده، وأعوذ بك من شر ما في هذا اليوم وشر ما بعده، رب أعوذ بك من الكسل وسوء الكبر، رب أعوذ بك من عذاب في النار وعذاب في القبر", 1),
        Dhikr("اللهم بك أصبحنا، وبك أمسينا، وبك نحيا، وبك نموت، وإليك النشور", 1),
        Dhikr("سيد الاستغفار:\nاللهم أنت ربي لا إله إلا أنت، خلقتني وأنا عبدك، وأنا على عهدك ووعدك ما استطعت، أعوذ بك من شر ما صنعت، أبوء لك بنعمتك علي، وأبوء بذنبي فاغفر لي فإنه لا يغفر الذنوب إلا أنت", 1),
        Dhikr("بسم الله الذي لا يضر مع اسمه شيء في الأرض ولا في السماء وهو السميع العليم", 3),
        Dhikr("رضيت بالله ربًا، وبالإسلام دينًا، وبمحمد صلى الله عليه وسلم نبيًا", 3),
        Dhikr("يا حي يا قيوم برحمتك أستغيث، أصلح لي شأني كله، ولا تكلني إلى نفسي طرفة عين", 1),
        Dhikr("لا إله إلا الله وحده لا شريك له، له الملك وله الحمد وهو على كل شيء قدير", 10),
        Dhikr("سبحان الله وبحمده", 100)
    )

    private val evening = listOf(
        Dhikr("آية الكرسي:\nالله لا إله إلا هو الحي القيوم لا تأخذه سنة ولا نوم له ما في السماوات وما في الأرض من ذا الذي يشفع عنده إلا بإذنه يعلم ما بين أيديهم وما خلفهم ولا يحيطون بشيء من علمه إلا بما شاء وسع كرسيه السماوات والأرض ولا يئوده حفظهما وهو العلي العظيم", 1),
        Dhikr("قراءة سورة الإخلاص والفلق والناس", 3),
        Dhikr("أمسينا وأمسى الملك لله، والحمد لله، لا إله إلا الله وحده لا شريك له، له الملك وله الحمد وهو على كل شيء قدير، رب أسألك خير ما في هذه الليلة وخير ما بعدها، وأعوذ بك من شر ما في هذه الليلة وشر ما بعدها، رب أعوذ بك من الكسل وسوء الكبر، رب أعوذ بك من عذاب في النار وعذاب في القبر", 1),
        Dhikr("اللهم بك أمسينا، وبك أصبحنا، وبك نحيا، وبك نموت، وإليك المصير", 1),
        Dhikr("سيد الاستغفار:\nاللهم أنت ربي لا إله إلا أنت، خلقتني وأنا عبدك، وأنا على عهدك ووعدك ما استطعت، أعوذ بك من شر ما صنعت، أبوء لك بنعمتك علي، وأبوء بذنبي فاغفر لي فإنه لا يغفر الذنوب إلا أنت", 1),
        Dhikr("أعوذ بكلمات الله التامات من شر ما خلق", 3),
        Dhikr("بسم الله الذي لا يضر مع اسمه شيء في الأرض ولا في السماء وهو السميع العليم", 3),
        Dhikr("رضيت بالله ربًا، وبالإسلام دينًا، وبمحمد صلى الله عليه وسلم نبيًا", 3),
        Dhikr("يا حي يا قيوم برحمتك أستغيث، أصلح لي شأني كله، ولا تكلني إلى نفسي طرفة عين", 1),
        Dhikr("سبحان الله وبحمده", 100)
    )

    private val afterPrayer = listOf(
        Dhikr("أستغفر الله", 3),
        Dhikr("اللهم أنت السلام ومنك السلام، تباركت يا ذا الجلال والإكرام", 1),
        Dhikr("لا إله إلا الله وحده لا شريك له، له الملك وله الحمد وهو على كل شيء قدير، اللهم لا مانع لما أعطيت، ولا معطي لما منعت، ولا ينفع ذا الجد منك الجد", 1),
        Dhikr("سبحان الله", 33),
        Dhikr("الحمد لله", 33),
        Dhikr("الله أكبر", 33),
        Dhikr("لا إله إلا الله وحده لا شريك له، له الملك وله الحمد وهو على كل شيء قدير", 1),
        Dhikr("آية الكرسي:\nالله لا إله إلا هو الحي القيوم لا تأخذه سنة ولا نوم له ما في السماوات وما في الأرض من ذا الذي يشفع عنده إلا بإذنه يعلم ما بين أيديهم وما خلفهم ولا يحيطون بشيء من علمه إلا بما شاء وسع كرسيه السماوات والأرض ولا يئوده حفظهما وهو العلي العظيم", 1),
        Dhikr("قراءة سورة الإخلاص والفلق والناس (ثلاث مرات بعد الفجر والمغرب)", 1)
    )

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
