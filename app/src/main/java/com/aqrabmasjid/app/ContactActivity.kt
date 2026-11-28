package com.aqrabmasjid.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Contact us: kind of message, the message, phone (optional), and a photo or video (optional).
 * Text only: sent straight to our email (FormSubmit).
 * With a photo/video: the phone's share screen opens (Gmail or WhatsApp) with the file attached.
 */
class ContactActivity : Activity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Look.wrap(base))
    }

    companion object {
        private const val REQ_MEDIA = 61
        fun intent(c: Context, kind: Int = 0) = Intent(c, ContactActivity::class.java).putExtra("kind", kind)
    }

    private val kinds get() = listOf(
        L.t("💬 رسالة", "💬 Message"),
        L.t("💡 اقتراح ميزة", "💡 Suggest a feature"),
        L.t("⚠️ بلاغ عن خطأ", "⚠️ Report a problem"),
        L.t("🕌 ملاحظة عن مسجد", "🕌 Note about a mosque")
    )
    private val kindsAr = listOf("رسالة", "اقتراح ميزة", "بلاغ عن خطأ", "ملاحظة عن مسجد")
    private var kind = 0
    private var media: Uri? = null
    private val chips = mutableListOf<TextView>()
    private lateinit var msg: EditText
    private lateinit var phone: EditText
    private lateinit var attach: TextView
    private lateinit var send: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        L.init(this)
        kind = intent.getIntExtra("kind", 0).coerceIn(0, 3)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(getColor(R.color.bg))
        scroll.layoutDirection = if (L.lang == "ar" || L.lang == "ur") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = dp(16)
        root.setPadding(pad, pad, pad, pad)
        scroll.addView(root)

        fun title(t: String, size: Float = 16f, top: Int = 14) = TextView(this).apply {
            text = t; textSize = size; setTypeface(null, Typeface.BOLD); setTextColor(getColor(R.color.title_text))
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }) }

        title(L.t("✉️ تواصل معنا", "✉️ Contact us"), 26f, 0).gravity = Gravity.CENTER
        TextView(this).apply {
            text = L.t("نقرأ كل رسالة. اكتب ملاحظتك، ويمكنك إرفاق صورة أو فيديو.", "We read every message. Write your note; you can add a photo or video.")
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(getColor(R.color.text_secondary))
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }) }

        // Kind of message
        title(L.t("نوع الرسالة", "Kind of message"))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(grid)
        var row: LinearLayout? = null
        kinds.forEachIndexed { i, k ->
            if (i % 2 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; grid.addView(row) }
            val chip = TextView(this).apply {
                text = k; textSize = 15f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                setPadding(dp(8), dp(12), dp(8), dp(12)); isClickable = true
                setOnClickListener { kind = i; paintChips() }
            }
            chips += chip
            row!!.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(6), dp(3), 0) })
        }
        paintChips()

        // Message
        title(L.t("رسالتك", "Your message"))
        msg = EditText(this).apply {
            hint = L.t("اكتب هنا…", "Write here…"); minLines = 5; gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            textSize = 17f; setBackgroundResource(R.drawable.bg_surface); setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextColor(getColor(R.color.text_primary))
        }
        root.addView(msg, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // Phone (optional)
        title(L.t("رقم الجوال (اختياري، لنرد عليك)", "Phone number (optional, so we can reply)"))
        phone = EditText(this).apply {
            hint = "05xxxxxxxx"; inputType = InputType.TYPE_CLASS_PHONE; textSize = 17f
            setBackgroundResource(R.drawable.bg_surface); setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextColor(getColor(R.color.text_primary)); textDirection = View.TEXT_DIRECTION_LTR
        }
        root.addView(phone, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // Photo / video
        attach = TextView(this).apply {
            textSize = 16f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.title_text)); setBackgroundResource(R.drawable.bg_station_idle)
            setPadding(dp(12), dp(14), dp(12), dp(14)); isClickable = true
            setOnClickListener {
                if (media != null) { media = null; paintAttach(); return@setOnClickListener }
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                try { startActivityForResult(i, REQ_MEDIA) } catch (_: Exception) { toast(L.t("الجوال لا يدعم هذا", "Not supported on this phone")) }
            }
        }
        root.addView(attach, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        paintAttach()

        // Send
        send = TextView(this).apply {
            text = L.t("📨 إرسال", "📨 Send"); textSize = 19f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF1B1606.toInt()); setBackgroundResource(R.drawable.bg_circle_gold_rect)
            setPadding(dp(12), dp(16), dp(12), dp(16)); isClickable = true
            setOnClickListener { sendNow() }
        }
        root.addView(send, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })

        // Or WhatsApp
        TextView(this).apply {
            text = L.t("💬 أو راسلنا على واتساب", "💬 Or message us on WhatsApp"); textSize = 15f; gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD); setTextColor(getColor(R.color.title_text)); setPadding(dp(8), dp(14), dp(8), dp(14))
            isClickable = true
            setOnClickListener { open(Uri.parse(Config.WHATSAPP_URL + "?text=" + Uri.encode(msg.text.toString()))) }
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }) }

        setContentView(scroll)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            try { window.insetsController?.setSystemBarsAppearance(if (night) 0 else light, light) } catch (_: Exception) {}
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(pad + b.left, pad + b.top, pad + b.right, pad + b.bottom)
            }
            insets
        }
    }

    private fun paintChips() = chips.forEachIndexed { i, c ->
        val on = i == kind
        c.setBackgroundResource(if (on) R.drawable.bg_circle_gold_rect else R.drawable.bg_station_idle)
        c.setTextColor(if (on) 0xFF1B1606.toInt() else getColor(R.color.text_primary))
    }

    private fun paintAttach() {
        attach.text = if (media == null) L.t("📎 أرفق صورة أو فيديو (اختياري)", "📎 Add a photo or video (optional)")
            else L.t("✅ تم إرفاق ملف · اضغط لإزالته", "✅ File added · tap to remove")
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun open(u: Uri) { try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {} }

    private fun deviceInfo(): String = Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " · " +
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("")

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA && resultCode == RESULT_OK) {
            media = data?.data
            paintAttach()
        }
    }

    private fun sendNow() {
        val text = msg.text.toString().trim()
        if (text.length < 3) { msg.error = L.t("اكتب رسالتك أولًا", "Write your message first"); msg.requestFocus(); return }
        val ph = phone.text.toString().trim()
        val subject = "أقرب مسجد – " + kindsAr[kind]
        val body = text + (if (ph.isNotEmpty()) "\n\nالجوال: $ph" else "") + "\n\n(" + deviceInfo() + ")"

        // With a photo or video: share screen (Gmail, WhatsApp…) with the file attached
        media?.let { uri ->
            val i = Intent(Intent.ACTION_SEND)
                .setType(contentResolver.getType(uri) ?: "*/*")
                .putExtra(Intent.EXTRA_EMAIL, arrayOf(Config.CONTACT_EMAIL))
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, body)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(Intent.createChooser(i, L.t("أرسل الرسالة مع الملف عبر", "Send the message with the file using")))
                toast(L.t("اختر البريد (Gmail) أو واتساب لإرسال الملف", "Choose email (Gmail) or WhatsApp to send the file"))
            } catch (_: Exception) { toast(L.t("لا يوجد تطبيق للإرسال", "No app to send with")) }
            return
        }

        // Text only: send straight to our email
        send.isEnabled = false
        send.text = L.t("⏳ جاري الإرسال…", "⏳ Sending…")
        val fields = linkedMapOf(
            "_subject" to subject, "_template" to "table", "_captcha" to "false",
            "kind" to kindsAr[kind], "message" to text, "phone" to ph,
            "device" to deviceInfo(), "app_lang" to L.lang, "source" to "app"
        )
        val form = fields.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        Thread {
            val ok = try {
                val con = URL(Config.FORM_URL).openConnection() as HttpURLConnection
                con.requestMethod = "POST"; con.doOutput = true; con.instanceFollowRedirects = false
                con.connectTimeout = 10_000; con.readTimeout = 15_000
                con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                con.setRequestProperty("Accept", "application/json")
                con.setRequestProperty("User-Agent", "AqrabMasjid-Android/1.0")
                con.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                val code = con.responseCode
                con.disconnect()
                code in 200..399
            } catch (_: Exception) { false }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                send.isEnabled = true
                send.text = L.t("📨 إرسال", "📨 Send")
                if (ok) {
                    toast(L.t("✅ وصلت رسالتك، شكرًا لك", "✅ Your message was sent. Thank you"))
                    finish()
                } else toast(L.t("ما قدرنا نرسل الآن. تأكد من الإنترنت، أو راسلنا على واتساب", "Could not send now. Check the internet, or message us on WhatsApp"))
            }
        }.start()
    }
}
