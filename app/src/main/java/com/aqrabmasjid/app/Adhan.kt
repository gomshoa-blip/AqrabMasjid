package com.aqrabmasjid.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Full adhan sound at adhan time.
 * The list of adhan voices is on our site (adhan/list.json), so new voices can be added without a new app.
 * The chosen voice is saved on the phone once, then it plays without internet.
 */
object Adhan {
    data class Voice(val id: String, val ar: String, val en: String, val url: String, val fajrUrl: String?) {
        val label: String get() = L.t(ar, en)
    }

    private fun sp(c: Context) = c.getSharedPreferences("aqrab", Context.MODE_PRIVATE)

    fun voiceId(c: Context): String? = sp(c).getString("adhan_voice", null)
    fun voiceName(c: Context): String? = sp(c).getString("adhan_voice_name", null)

    private fun dir(c: Context) = File(c.filesDir, "adhan").apply { mkdirs() }
    fun file(c: Context, id: String, fajr: Boolean = false) = File(dir(c), id + (if (fajr) "_fajr" else "") + ".mp3")

    /** Saved sound for this prayer (Fajr has its own when there is one). */
    fun soundFor(c: Context, prayer: Prayer): File? {
        val id = voiceId(c) ?: return null
        if (prayer == Prayer.FAJR) file(c, id, true).takeIf { it.length() > 10_000 }?.let { return it }
        return file(c, id).takeIf { it.length() > 10_000 }
    }

    private fun abs(u: String) = if (u.startsWith("http")) u else Config.SITE_URL + (if (u.startsWith("/")) u else "/adhan/$u")

    /** Read the voices list from the site (runs off the main thread). */
    fun loadVoices(): List<Voice> {
        val txt = get(Config.SITE_URL + "/adhan/list.json")?.toString(Charsets.UTF_8) ?: return emptyList()
        return try {
            val a = JSONArray(txt)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Voice(o.getString("id"), o.optString("ar", o.getString("id")), o.optString("en", o.optString("ar")),
                    abs(o.getString("url")), o.optString("fajr", "").takeIf { it.isNotBlank() }?.let { abs(it) })
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Save a voice on the phone (runs off the main thread). Returns true when it is ready. */
    fun download(c: Context, v: Voice): Boolean {
        val main = get(v.url) ?: return false
        if (main.size < 10_000) return false
        file(c, v.id).writeBytes(main)
        v.fajrUrl?.let { u -> get(u)?.takeIf { it.size > 10_000 }?.let { file(c, v.id, true).writeBytes(it) } }
        // Keep only the chosen voice (saves space)
        dir(c).listFiles()?.filter { !it.name.startsWith(v.id + ".") && !it.name.startsWith(v.id + "_fajr") }?.forEach { it.delete() }
        sp(c).edit().putString("adhan_voice", v.id).putString("adhan_voice_name", v.label).apply()
        return true
    }

    private fun get(u: String): ByteArray? = try {
        val con = URL(u).openConnection() as HttpURLConnection
        con.connectTimeout = 15_000; con.readTimeout = 30_000
        con.instanceFollowRedirects = true
        con.setRequestProperty("User-Agent", RadioService.UA)
        if (con.responseCode in 200..299) con.inputStream.use { it.readBytes() } else null
    } catch (_: Exception) { null }

    /** Start the adhan now. Returns false when it could not start (then the normal alert sound is used). */
    fun start(c: Context, prayer: Prayer, title: String): Boolean {
        val f = soundFor(c, prayer) ?: return false
        // Phone on silent or vibrate: no adhan sound
        val am = c.getSystemService(AudioManager::class.java)
        if (am != null && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        return try {
            val i = Intent(c, AdhanService::class.java).putExtra("file", f.absolutePath).putExtra("title", title)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            true
        } catch (_: Exception) { false }
    }

    fun stop(c: Context) {
        try { c.startService(Intent(c, AdhanService::class.java).setAction(AdhanService.ACTION_STOP)) } catch (_: Exception) {}
    }

    /** Play a few seconds to try the voice in the settings. */
    private var preview: MediaPlayer? = null
    fun preview(c: Context, f: File?) {
        try { preview?.release() } catch (_: Exception) {}
        preview = null
        if (f == null || !f.exists()) return
        try {
            preview = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start() }
        } catch (_: Exception) {}
    }
}

/** Plays the adhan with a "Stop" button in the notification. Stops by itself at the end. */
class AdhanService : Service() {
    companion object {
        const val ACTION_STOP = "com.aqrabmasjid.app.ADHAN_STOP"
        private const val CH = "adhan_player_v1"
        private const val ID = 320
    }

    private var mp: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        L.init(this)
        if (intent?.action == ACTION_STOP) { end(); return START_NOT_STICKY }
        val path = intent?.getStringExtra("file") ?: run { end(); return START_NOT_STICKY }
        val title = intent.getStringExtra("title") ?: L.t("الأذان", "Adhan")
        val n = note(title)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(ID, n)
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        try {
            mp?.release()
            mp = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(path)
                setOnCompletionListener { end() }
                setOnErrorListener { _, _, _ -> end(); true }
                prepare()
                start()
            }
        } catch (_: Exception) { end() }
        return START_NOT_STICKY
    }

    private fun note(title: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, L.t("صوت الأذان", "Adhan sound"), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null); setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        val stop = PendingIntent.getService(this, 321, Intent(this, AdhanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_mosque)
            .setContentTitle("🕌 " + title)
            .setContentText(L.t("اضغط «إيقاف» لإيقاف الأذان", "Tap Stop to stop the adhan"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(stop)
            .setDeleteIntent(stop)
            .addAction(R.drawable.ic_stop, L.t("⏹ إيقاف الأذان", "⏹ Stop adhan"), stop)
            .build()
    }

    private fun end() {
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        super.onDestroy()
    }
}
