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
        /** Short name for the favorite buttons on the radio card. */
        val short: String get() = when (id) {
            "quran" -> L.t("القرآن الكريم", "Holy Quran")
            "sunnah" -> L.t("السنة النبوية", "Prophet's Sunnah")
            "qasim" -> L.t("الشيخ القاسم", "Sheikh Al-Qasim")
            "sabah" -> L.t("أذكار الصباح", "Morning adhkar")
            "masa" -> L.t("أذكار المساء", "Evening adhkar")
            "urdu" -> L.t("ترجمة أوردو", "Urdu translation")
            "spanish" -> L.t("ترجمة إسبانية", "Spanish translation")
            "french" -> L.t("ترجمة فرنسية", "French translation")
            "siyra" -> L.t("في ظلال السيرة", "Seerah")
            "anbiya" -> L.t("قصص الأنبياء", "Prophets' stories")
            "mukhtasar" -> L.t("المختصر في التفسير", "Tafsir Al-Mukhtasar")
            else -> label
        }
    }

    /**
     * What the radio is doing now (shown on the main screen).
     * PLAYING only when ExoPlayer has audio and is really playing; LOST when the sound stopped
     * after it was playing (no data / no internet) and we are trying again.
     */
    enum class Status { OFF, LOADING, PLAYING, LOST, PAUSED, ERROR }

    companion object {
        const val CHANNEL = "radio_v2"
        private const val NOTIF_ID = 500
        const val ACTION_PLAY = "com.aqrabmasjid.app.RADIO_PLAY"
        const val ACTION_STOP = "com.aqrabmasjid.app.RADIO_STOP"
        const val ACTION_TOGGLE = "com.aqrabmasjid.app.RADIO_TOGGLE"
        /** Some stations refuse unknown players: introduce ourselves like a normal phone browser. */
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 AqrabMasjid"

        /** MP3Quran live radio: main address first, then the backup addresses. */
        private fun mq(slug: String) = listOf(
            "https://backup.qurango.net/radio/$slug", "https://qurango.net/radio/$slug",
            "http://backup.qurango.net/radio/$slug")

        /** Error shown on the radio card (helps us know why a station did not play). */
        @Volatile var lastError: String = ""

        val STATIONS = listOf(
            Station("quran", "إذاعة القرآن الكريم – السعودية", "Holy Quran Radio – Saudi Arabia",
                listOf("https://stream.radiojar.com/0tpy1h0kxtzuv", "https://stream.radiojar.com/4wqre23fytzuv", "http://stream.radiojar.com/0tpy1h0kxtzuv")),
            Station("sunnah", "إذاعة السنة – المملكة العربية السعودية", "Sunnah Radio – Saudi Arabia",
                listOf("https://radiosunna.radioca.st/stream", "http://radiosunna.radioca.st/stream")),
            Station("siyra", "في ظلال السيرة النبوية (٤٠٠ حلقة)", "In the shade of the Seerah (400 episodes)",
                mq("fi_zilal_alsiyra")),
            Station("anbiya", "قصص الأنبياء", "Stories of the Prophets", mq("alanbiya")),
            Station("mukhtasar", "المختصر في تفسير القرآن الكريم", "Al-Mukhtasar in Quran Tafsir", mq("mukhtasartafsir")),
            Station("qasim", "تلاوات الشيخ عبدالمحسن القاسم", "Sheikh Abdulmohsen Al-Qasim",
                listOf("https://backup.qurango.net/radio/abdulmohsen_alqasim", "https://qurango.net/radio/abdulmohsen_alqasim")),
            Station("sabah", "أذكار الصباح", "Morning adhkar",
                listOf("https://backup.qurango.net/radio/athkar_sabah", "https://qurango.net/radio/athkar_sabah")),
            Station("masa", "أذكار المساء", "Evening adhkar",
                listOf("https://backup.qurango.net/radio/athkar_masa", "https://qurango.net/radio/athkar_masa")),
            Station("urdu", "ترجمة معاني القرآن بالأوردية", "Quran translation in Urdu",
                listOf("https://backup.qurango.net/radio/translation_quran_urdu_basit", "https://qurango.net/radio/translation_quran_urdu_basit")),
            Station("spanish", "ترجمة معاني القرآن بالإسبانية", "Quran translation in Spanish",
                listOf("https://backup.qurango.net/radio/translation_quran_spanish_afs", "https://qurango.net/radio/translation_quran_spanish_afs")),
            Station("french", "ترجمة معاني القرآن بالفرنسية", "Quran translation in French",
                listOf("https://backup.qurango.net/radio/translation_quran_french", "https://qurango.net/radio/translation_quran_french"))
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
    /** Second way to play (Android's own player) when ExoPlayer can not open the station. */
    private var mp: android.media.MediaPlayer? = null
    private var triedMp = false
    private var urlIndex = 0
    private var everPlayed = false
    private var retries = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var retryJob: Runnable? = null

    private fun cancelRetry() { retryJob?.let { handler.removeCallbacks(it) }; retryJob = null }

    /** The stream stopped after it was playing: show "lost" and try again (2s, 4s, 8s … up to 6 times). */
    private fun lost(st: Station) {
        if (current == null) return
        status = Status.LOST
        notifyNow(st)
        cancelRetry()
        if (retries >= 6) { releasePlayer(); status = Status.ERROR; notifyNow(st); return }
        val wait = minOf(2000L shl retries, 15000L)
        retries++
        val job = Runnable { if (current == st.id && status == Status.LOST) { urlIndex = 0; startPlayer(st) } }
        retryJob = job
        handler.postDelayed(job, wait)
    }

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
                urlIndex = 0; everPlayed = false; retries = 0; triedMp = false; lastError = ""; cancelRetry()
                startPlayer(st)
            }
            ACTION_TOGGLE -> {
                val st = station(current) ?: run { stopAll(); return START_NOT_STICKY }
                if (paused) {
                    status = Status.LOADING
                    startForegroundNow(st)
                    urlIndex = 0; everPlayed = false; retries = 0; triedMp = false; cancelRetry()
                    startPlayer(st)
                } else {
                    cancelRetry()
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
            val http = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent(UA)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
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
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (player !== p) return
                    if (isPlaying) {
                        // Sound is really coming out
                        everPlayed = true; retries = 0; cancelRetry()
                        if (status != Status.PLAYING) { status = Status.PLAYING; notifyNow(st) }
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (player !== p) return
                    when (state) {
                        Player.STATE_BUFFERING -> {
                            // Data stopped while playing: the stream is cut (ExoPlayer keeps waiting)
                            if (status == Status.PLAYING) { status = Status.LOST; notifyNow(st) }
                            else if (status != Status.LOST) status = Status.LOADING
                        }
                        Player.STATE_ENDED -> if (current != null) lost(st)   // the station closed the stream
                        else -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (player !== p) return
                    // It was playing before: keep trying (internet may come back)
                    if (everPlayed) { releasePlayer(); lost(st); return }
                    // Try the next address of this station before giving up
                    if (urlIndex + 1 < st.urls.size) {
                        urlIndex++
                        startPlayer(st)
                    } else {
                        releasePlayer()
                        lastError = "E" + error.errorCode
                        if (!triedMp) { triedMp = true; startMediaPlayer(st, 0) }
                        else { status = Status.ERROR; notifyNow(st) }
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

    /** Android's own player: try each address of the station. */
    private fun startMediaPlayer(st: Station, idx: Int) {
        releaseMp()
        if (idx >= st.urls.size || current != st.id) { status = Status.ERROR; notifyNow(st); return }
        try {
            val m = android.media.MediaPlayer()
            m.setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            m.setWakeMode(this, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            m.setDataSource(this, android.net.Uri.parse(st.urls[idx]), mapOf("User-Agent" to UA))
            m.setOnPreparedListener {
                if (mp !== m || current != st.id) return@setOnPreparedListener
                it.start(); everPlayed = true; status = Status.PLAYING; lastError = ""; notifyNow(st)
            }
            m.setOnErrorListener { _, what, extra ->
                if (mp === m) {
                    lastError = "M$what/$extra"
                    if (everPlayed) { releaseMp(); lost(st) } else startMediaPlayer(st, idx + 1)
                }
                true
            }
            m.setOnCompletionListener { if (mp === m && current != null) { releaseMp(); lost(st) } }
            mp = m
            m.prepareAsync()
        } catch (e: Exception) {
            lastError = "M:" + (e.javaClass.simpleName)
            startMediaPlayer(st, idx + 1)
        }
    }

    private fun releaseMp() {
        try { mp?.release() } catch (_: Exception) {}
        mp = null
    }

    private fun statusText(): String = when (status) {
        Status.LOADING -> L.t("جاري التشغيل…", "Starting…")
        Status.PLAYING -> L.t("يعمل الآن", "Playing now")
        Status.LOST -> L.t("انقطع البث · نحاول من جديد", "Signal lost · retrying")
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
        releaseMp()
    }

    private fun stopAll() {
        cancelRetry()
        releasePlayer()
        current = null
        status = Status.OFF
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cancelRetry()
        releasePlayer()
        current = null
        status = Status.OFF
        super.onDestroy()
    }
}
