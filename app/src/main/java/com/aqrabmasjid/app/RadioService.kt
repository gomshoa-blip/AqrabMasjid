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
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Live Quran radio. Plays in the background (screen off, in the car) with a "Stop" button
 * in the notification. Streams are the stations' own live audio, not YouTube.
 */
class RadioService : Service() {

    data class Station(val id: String, val ar: String, val en: String, val url: String) {
        val label: String get() = L.t(ar, en)
    }

    companion object {
        const val CHANNEL = "radio_v1"
        private const val NOTIF_ID = 500
        const val ACTION_PLAY = "com.aqrabmasjid.app.RADIO_PLAY"
        const val ACTION_STOP = "com.aqrabmasjid.app.RADIO_STOP"
        const val ACTION_TOGGLE = "com.aqrabmasjid.app.RADIO_TOGGLE"

        val STATIONS = listOf(
            Station("quran", "إذاعة القرآن الكريم – السعودية", "Holy Quran Radio – Saudi Arabia", "https://stream.radiojar.com/0tpy1h0kxtzuv"),
            Station("sunnah", "إذاعة السنة", "Sunnah Radio", "https://radiosunna.radioca.st/stream")
        )


        /** Station playing now (null = stopped). */
        @Volatile var playing: String? = null
            private set

        /** Station chosen (playing or paused); null when the radio is closed. */
        @Volatile var current: String? = null
            private set

        @Volatile var paused = false
            private set

        fun station(id: String?): Station? = STATIONS.firstOrNull { it.id == id }

        /** Pause or play again (live: playing again starts from now). */
        fun toggle(c: Context) {
            c.startService(Intent(c, RadioService::class.java).setAction(ACTION_TOGGLE))
        }

        fun play(c: Context, id: String) {
            val i = Intent(c, RadioService::class.java).setAction(ACTION_PLAY).putExtra("id", id)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.startService(Intent(c, RadioService::class.java).setAction(ACTION_STOP))
        }
    }

    private var player: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        L.init(this)
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
            ACTION_TOGGLE -> {
                val st = station(current) ?: run { stopAll(); return START_NOT_STICKY }
                if (paused) {
                    paused = false
                    notify(st, L.t("جاري التشغيل…", "Starting…"))
                    startPlayer(st)
                } else {
                    releasePlayer()
                    paused = true
                    playing = null
                    notify(st, L.t("متوقف مؤقتًا", "Paused"))
                }
            }
            ACTION_PLAY -> {
                val st = STATIONS.firstOrNull { it.id == intent.getStringExtra("id") } ?: STATIONS[0]
                current = st.id
                paused = false
                startForegroundNow(st, L.t("جاري التشغيل…", "Starting…"))
                startPlayer(st)
            }
        }
        return START_NOT_STICKY
    }

    private fun startPlayer(st: Station) {
        releasePlayer()
        playing = st.id
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setWakeMode(this, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(st.url)
            mp.setOnPreparedListener {
                it.start()
                notify(st, L.t("يعمل الآن", "Playing now"))
            }
            mp.setOnErrorListener { _, _, _ ->
                notify(st, L.t("تعذر التشغيل. تأكد من الإنترنت", "Could not play. Check the internet"))
                playing = null
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (_: Exception) {
            notify(st, L.t("تعذر التشغيل", "Could not play"))
            playing = null
        }
    }

    private fun buildNotification(st: Station, text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, L.t("الإذاعة", "Radio"), NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }
        val stopPi = PendingIntent.getService(
            this, 501, Intent(this, RadioService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val togglePi = PendingIntent.getService(
            this, 503, Intent(this, RadioService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 502, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mosque)
            .setContentTitle("📻 " + st.label)
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_stat_mosque, if (paused) L.t("▶ تشغيل", "▶ Play") else L.t("⏸ إيقاف مؤقت", "⏸ Pause"), togglePi)
            .addAction(R.drawable.ic_stat_mosque, L.t("✕ إغلاق", "✕ Close"), stopPi)
            .build()
    }

    private fun startForegroundNow(st: Station, text: String) {
        val n = buildNotification(st, text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
    }

    private fun notify(st: Station, text: String) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(st, text)) } catch (_: Exception) {}
    }

    private fun releasePlayer() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    private fun stopAll() {
        releasePlayer()
        playing = null
        current = null
        paused = false
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        releasePlayer()
        playing = null
        current = null
        paused = false
        super.onDestroy()
    }
}
