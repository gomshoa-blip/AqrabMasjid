package com.aqrabmasjid.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Live Quran radio. Plays in the background (screen off, in the car) with
 * Play/Pause and Close in the notification. Streams are the stations' own live audio.
 */
class RadioService : Service() {

    data class Station(val id: String, val ar: String, val en: String, val urls: List<String>) {
        val label: String get() = L.t(ar, en)
    }

    /** What the radio is doing now (shown on the main screen). */
    enum class Status { OFF, LOADING, PLAYING, PAUSED, ERROR }

    companion object {
        const val CHANNEL = "radio_v2"
        private const val NOTIF_ID = 500
        const val ACTION_PLAY = "com.aqrabmasjid.app.RADIO_PLAY"
        const val ACTION_STOP = "com.aqrabmasjid.app.RADIO_STOP"
        const val ACTION_TOGGLE = "com.aqrabmasjid.app.RADIO_TOGGLE"

        val STATIONS = listOf(
            Station("quran", "إذاعة القرآن الكريم – السعودية", "Holy Quran Radio – Saudi Arabia",
                listOf("https://stream.radiojar.com/0tpy1h0kxtzuv", "https://stream.radiojar.com/4wqre23fytzuv", "http://stream.radiojar.com/0tpy1h0kxtzuv")),
            Station("sunnah", "إذاعة السنة – المملكة العربية السعودية", "Sunnah Radio – Saudi Arabia",
                listOf("https://radiosunna.radioca.st/stream", "http://radiosunna.radioca.st/stream"))
        )

        /** Station chosen (playing, loading or paused); null when the radio is closed. */
        @Volatile var current: String? = null
            private set

        @Volatile var status = Status.OFF
            private set

        val paused: Boolean get() = status == Status.PAUSED || status == Status.ERROR

        fun station(id: String?): Station? = STATIONS.firstOrNull { it.id == id }

        fun play(c: Context, id: String) {
            // Phone radio starts: stop the car-screen radio so both don't play
            runCatching { RadioCarService.pauseCar() }
            current = id
            status = Status.LOADING
            val i = Intent(c, RadioService::class.java).setAction(ACTION_PLAY).putExtra("id", id)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        /** Pause, or play again (live: playing again starts from now). */
        fun toggle(c: Context) {
            val id = current ?: return
            if (paused) play(c, id)
            else c.startService(Intent(c, RadioService::class.java).setAction(ACTION_TOGGLE))
        }

        fun stop(c: Context) {
            current = null
            status = Status.OFF
            c.startService(Intent(c, RadioService::class.java).setAction(ACTION_STOP))
        }
    }

    private var player: ExoPlayer? = null
    private var urlIndex = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        L.init(this)
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
            ACTION_PLAY -> {
                val st = station(intent.getStringExtra("id")) ?: STATIONS[0]
                current = st.id
                status = Status.LOADING
                startForegroundNow(st)
                urlIndex = 0
                startPlayer(st)
            }
            ACTION_TOGGLE -> {
                val st = station(current) ?: run { stopAll(); return START_NOT_STICKY }
                if (paused) {
                    status = Status.LOADING
                    startForegroundNow(st)
                    urlIndex = 0
                    startPlayer(st)
                } else {
                    releasePlayer()
                    status = Status.PAUSED
                    notifyNow(st)
                }
            }
        }
        return START_NOT_STICKY
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun startPlayer(st: Station) {
        releasePlayer()
        RadioRecorder.begin(st.id)
        try {
            // The stream also goes to the recorder (last 3 minutes, for "Save")
            val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            val base = DefaultDataSource.Factory(this, http)
            val rec = DataSource.Factory { RecordingDataSource(base.createDataSource()) }
            val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(rec)).build()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            p.setWakeMode(C.WAKE_MODE_NETWORK)
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && p.playWhenReady) {
                        status = Status.PLAYING
                        notifyNow(st)
                    } else if (state == Player.STATE_BUFFERING && status != Status.PLAYING) {
                        status = Status.LOADING
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Try the next address of this station before giving up
                    if (urlIndex + 1 < st.urls.size) {
                        urlIndex++
                        startPlayer(st)
                    } else {
                        releasePlayer()
                        status = Status.ERROR
                        notifyNow(st)
                    }
                }
            })
            p.setMediaItem(MediaItem.fromUri(st.urls[urlIndex]))
            p.prepare()
            p.playWhenReady = true
            player = p
        } catch (_: Exception) {
            status = Status.ERROR
            notifyNow(st)
        }
    }

    private fun statusText(): String = when (status) {
        Status.LOADING -> L.t("جاري التشغيل…", "Starting…")
        Status.PLAYING -> L.t("يعمل الآن", "Playing now")
        Status.PAUSED -> L.t("متوقف مؤقتًا", "Paused")
        Status.ERROR -> L.t("تعذر التشغيل. تأكد من الإنترنت", "Could not play. Check the internet")
        Status.OFF -> ""
    }

    private fun buildNotification(st: Station): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, L.t("الإذاعة", "Radio"), NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null)
            ch.setShowBadge(false)
            nm.deleteNotificationChannel("radio_v1")
            nm.createNotificationChannel(ch)
        }
        fun svc(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, RadioService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 502, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playLabel = if (paused) L.t("▶ تشغيل", "▶ Play") else L.t("⏸ إيقاف مؤقت", "⏸ Pause")
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mosque)
            .setContentTitle("📻 " + st.label)
            .setContentText(statusText())
            .setContentIntent(openPi)
            .setOngoing(true)
            .setSilent(true)
            .addAction(if (paused) R.drawable.ic_play else R.drawable.ic_pause, playLabel, svc(ACTION_TOGGLE, 503))
            .addAction(R.drawable.ic_close, L.t("✕ إغلاق", "✕ Close"), svc(ACTION_STOP, 501))
            .build()
    }

    private fun startForegroundNow(st: Station) {
        val n = buildNotification(st)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
    }

    private fun notifyNow(st: Station) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(st)) } catch (_: Exception) {}
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    private fun stopAll() {
        releasePlayer()
        current = null
        status = Status.OFF
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releasePlayer()
        current = null
        status = Status.OFF
        super.onDestroy()
    }
}
