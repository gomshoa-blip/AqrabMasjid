package com.aqrabmasjid.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale

/**
 * When the phone connects to the car (Bluetooth: car audio / hands-free, also used by Android Auto):
 * 1) The riding dua is shown and read.
 * 2) Morning adhkar (Fajr to Dhuhr) or evening adhkar (Asr to Isha) are read, once per day each.
 * Both can be turned off in the app.
 */
object CarVoice {
    private const val MIN = 60_000L
    const val CHANNEL = "car_voice_v1"
    const val ACTION_START = "com.aqrabmasjid.app.CAR_VOICE_START"
    const val ACTION_STOP = "com.aqrabmasjid.app.CAR_VOICE_STOP"

    private fun sp(c: Context) = c.getSharedPreferences("aqrab", Context.MODE_PRIVATE)

    fun duaOn(c: Context): Boolean = sp(c).getBoolean("car_dua", true)
    fun setDua(c: Context, on: Boolean) = sp(c).edit().putBoolean("car_dua", on).apply()
    fun adhkarOn(c: Context): Boolean = sp(c).getBoolean("car_adhkar", true)
    fun setAdhkar(c: Context, on: Boolean) = sp(c).edit().putBoolean("car_adhkar", on).apply()

    /** Android 12+: needs the "Nearby devices" permission to know the car connected. */
    fun hasBtPermission(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** "morning" / "evening" if now is in that time and it was not read today, else null. */
    fun adhkarNow(c: Context, now: Long = System.currentTimeMillis()): String? {
        val (lat, lng) = Prefs.location(c) ?: return null
        val day = PrayerCalc.day(c, lat, lng, now).list
        val t = day.associateBy { it.prayer }
        val kind = when {
            now >= t[Prayer.FAJR]!!.adhan && now < t[Prayer.DHUHR]!!.adhan -> "morning"
            now >= t[Prayer.ASR]!!.adhan && now < t[Prayer.ISHA]!!.adhan -> "evening"
            else -> return null
        }
        val key = "car_${kind}_" + java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date(now))
        return if (sp(c).getBoolean(key, false)) null else kind
    }

    fun markDone(c: Context, kind: String) {
        val key = "car_${kind}_" + java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date())
        sp(c).edit().putBoolean(key, true).apply()
    }

    /** Car connected: start a few seconds later (the car audio needs a moment to be ready). */
    fun onCarConnected(c: Context) {
        val app = c.applicationContext
        if (!duaOn(app) && !(adhkarOn(app) && adhkarNow(app) != null)) return
        // Same car connecting again within 10 minutes (short stop): do nothing
        val last = sp(app).getLong("car_last", 0L)
        val now = System.currentTimeMillis()
        if (now - last < 10 * MIN) return
        sp(app).edit().putLong("car_last", now).apply()

        val pi = PendingIntent.getBroadcast(
            app, 501, Intent(app, CarVoiceReceiver::class.java).setAction(ACTION_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = app.getSystemService(AlarmManager::class.java)
        // An exact alarm lets the app start the voice from the background (Android rule)
        try {
            if (Alerts.canExact(app)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 6_000, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 6_000, pi)
        } catch (_: Exception) {
            start(app)
        }
    }

    fun start(c: Context) {
        val i = Intent(c, CarVoiceService::class.java).setAction(ACTION_START)
        try {
            c.startForegroundService(i)
        } catch (_: Exception) {
            // Android did not allow it from the background: show the dua and a "listen" button
            showTapToListen(c)
        }
    }

    fun createChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, L.t("دعاء الركوب وأذكار السيارة", "Car dua and adhkar"),
            NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); enableVibration(false); setShowBadge(false) })
    }

    fun notification(c: Context, title: String, text: String, playing: Boolean): android.app.Notification {
        createChannel(c)
        val open = PendingIntent.getActivity(c, 502, Intent(c, AdhkarActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mosque)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .extend(CarAppExtender.Builder().setContentTitle(title).setContentText(text).build())
        if (playing) {
            val stop = PendingIntent.getService(c, 503, Intent(c, CarVoiceService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, L.t("⏹ إيقاف", "⏹ Stop"), stop).setOngoing(true)
        } else b.setAutoCancel(true)
        return b.build()
    }

    private fun showTapToListen(c: Context) {
        val listen = PendingIntent.getForegroundService(c, 504, Intent(c, CarVoiceService::class.java).setAction(ACTION_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, CHANNEL).apply { createChannel(c) }
            .setSmallIcon(R.drawable.ic_stat_mosque)
            .setContentTitle(L.t("🚗 دعاء الركوب", "🚗 Riding dua"))
            .setContentText(AdhkarData.RIDING_DUA)
            .setStyle(NotificationCompat.BigTextStyle().bigText(AdhkarData.RIDING_DUA))
            .addAction(0, L.t("▶ استمع", "▶ Listen"), listen)
            .setContentIntent(listen)
            .setAutoCancel(true)
            .setSilent(true)
            .extend(CarAppExtender.Builder().setContentTitle(L.t("🚗 دعاء الركوب", "🚗 Riding dua"))
                .setContentText(AdhkarData.RIDING_DUA).build())
            .build()
        try { NotificationManagerCompat.from(c).notify(510, n) } catch (_: SecurityException) {}
    }

    /** Is this Bluetooth device a car (car audio or hands-free)? */
    fun isCar(d: BluetoothDevice?): Boolean = try {
        val cls = d?.bluetoothClass
        cls != null && cls.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO &&
            (cls.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                cls.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE)
    } catch (_: SecurityException) { false }
}

/** Bluetooth connected (car), and the delayed start alarm. */
class CarVoiceReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        L.init(c)
        when (i.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                if (!CarVoice.hasBtPermission(c)) return
                @Suppress("DEPRECATION")
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (CarVoice.isCar(d)) CarVoice.onCarConnected(c)
            }
            CarVoice.ACTION_START -> CarVoice.start(c)
        }
    }
}

/** Reads the riding dua and the adhkar with the phone voice (Arabic), through the car speakers. */
class CarVoiceService : Service(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var focus: AudioFocusRequest? = null
    private val queue = mutableListOf<Pair<String, String>>()  // id to text
    private var adhkarKind: String? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        L.init(this)
        if (i?.action == CarVoice.ACTION_STOP) { finish(); return START_NOT_STICKY }
        if (tts != null) return START_NOT_STICKY   // already reading

        val dua = CarVoice.duaOn(this)
        adhkarKind = if (CarVoice.adhkarOn(this)) CarVoice.adhkarNow(this) else null
        val title = when {
            dua -> L.t("🚗 دعاء الركوب", "🚗 Riding dua")
            adhkarKind == "morning" -> L.t("🌅 أذكار الصباح", "🌅 Morning adhkar")
            else -> L.t("🌇 أذكار المساء", "🌇 Evening adhkar")
        }
        val n = CarVoice.notification(this, title, if (dua) AdhkarData.RIDING_DUA else L.t("يُقرأ الآن", "Reading now"), true)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(511, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(511, n)
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }

        if (!dua && adhkarKind == null) { finish(); return START_NOT_STICKY }
        if (dua) queue += "dua" to AdhkarData.RIDING_DUA
        adhkarKind?.let { kind ->
            val list = if (kind == "morning") AdhkarData.morning else AdhkarData.evening
            queue += "intro" to if (kind == "morning") "أذكار الصباح" else "أذكار المساء"
            // Quran (Ayat Al-Kursi and the 3 surahs) is not read by the machine voice
            list.filter { !it.text.startsWith("آية الكرسي") && !it.text.startsWith("قراءة") }
                .forEachIndexed { n2, d ->
                    val text = d.text.substringAfter(":\n")
                    val times = if (d.count in 2..3) d.count else 1
                    repeat(times) { k -> queue += "a${n2}_$k" to text }
                    if (d.count > 3) queue += "c$n2" to "${d.count} مرة"
                }
        }
        tts = TextToSpeech(this, this)
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) { finish(); return }
        val r = t.setLanguage(Locale("ar", "SA"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            // No Arabic voice on this phone: keep the dua on screen only
            stopForeground(STOP_FOREGROUND_REMOVE)
            try {
                NotificationManagerCompat.from(this).notify(510, CarVoice.notification(this,
                    L.t("🚗 دعاء الركوب", "🚗 Riding dua"), AdhkarData.RIDING_DUA, false))
            } catch (_: SecurityException) {}
            stopSelf(); return
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        t.setAudioAttributes(attrs)
        t.setSpeechRate(0.9f)
        val am = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs).build()
        am.requestAudioFocus(focus!!)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { if (id == "end") finish() }
            override fun onDone(id: String?) {
                if (id == "end") { adhkarKind?.let { CarVoice.markDone(this@CarVoiceService, it) }; finish() }
            }
        })
        for ((id, text) in queue) {
            t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
            t.playSilentUtterance(700, TextToSpeech.QUEUE_ADD, "p_$id")
        }
        t.playSilentUtterance(100, TextToSpeech.QUEUE_ADD, "end")
    }

    private fun finish() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { tts?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}
