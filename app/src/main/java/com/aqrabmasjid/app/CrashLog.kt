package com.aqrabmasjid.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Saves the error when the app crashes, and shows it the next time
 * the app opens, so the user can copy or share it.
 */
object CrashLog {
    private const val PREFS = "crash"
    private const val KEY = "last"

    fun install(c: Context) {
        val app = c.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        if (old is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, old))
    }

    private class Handler(
        val app: Context,
        val old: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = "Version ${versionName(app)}\n${sw.toString().take(6000)}"
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, text).commit()
            } catch (_: Throwable) {
            }
            old?.uncaughtException(t, e)
        }
    }

    private fun versionName(c: Context): String = try {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        "${pi.versionName} ($code)"
    } catch (_: Exception) { "?" }

    /** Show the saved error (if any) once, with Copy and Share buttons. */
    fun showIfAny(a: Activity) {
        val sp = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = sp.getString(KEY, null) ?: return
        sp.edit().remove(KEY).apply()
        AlertDialog.Builder(a)
            .setTitle("حصل خطأ في التطبيق")
            .setMessage(text.take(1500))
            .setPositiveButton("نسخ") { _, _ ->
                val cm = a.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("crash", text))
            }
            .setNeutralButton("مشاركة") { _, _ ->
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                a.startActivity(Intent.createChooser(i, "مشاركة الخطأ"))
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }
}
