package com.aqrabmasjid.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Qibla with augmented reality: the camera shows what is in front of you,
 * and a Kaaba marker appears where the Qibla is. Off screen, an arrow shows which way to turn.
 * Without the camera it still works as a compass on a dark background.
 *
 * Location: only from the phone, or picked by the user on the map. Never a default city.
 * When something is wrong, a clear message tells the user what to do, with a button.
 */
class QiblaActivity : ComponentActivity(), SensorEventListener {

    private lateinit var preview: PreviewView
    private lateinit var overlay: Overlay
    private lateinit var card: LinearLayout
    private lateinit var cardTitle: TextView
    private lateinit var cardBody: TextView
    private lateinit var cardBtn1: Button
    private lateinit var cardBtn2: Button
    private lateinit var sensors: SensorManager
    private val main = Handler(Looper.getMainLooper())

    private var qibla: Float? = null   // bearing from the user to the Kaaba (degrees from true north)
    private var heading = 0f           // where the camera points (degrees from true north)
    private var hasHeading = false
    private var smooth = Float.NaN
    private var wasAligned = false
    private var declination = 0f       // magnetic north -> true north
    private var locating = false
    private var cameraOn = false
    private var cameraAsked = false
    private var compassMode = 0        // 1 rotation vector, 2 geomagnetic rotation vector, 3 accelerometer + magnetic

    // Messages, most important first
    private enum class Msg { NO_SENSOR, NO_LOCATION, NO_COMPASS, CAMERA_DENIED }
    private var shown: Msg? = null
    private val dismissed = mutableSetOf<Msg>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        L.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val d = resources.displayMetrics.density

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0B100D"))
        root.layoutDirection = if (L.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView mode: works on more phones (some Honor / Huawei show black with SurfaceView)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        overlay = Overlay(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        // Close button (tapping the screen no longer closes it by mistake)
        val close = pill("✕", 18f)
        close.setOnClickListener { finish() }
        root.addView(close, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins((12 * d).toInt(), (24 * d).toInt(), (12 * d).toInt(), 0)
        })
        // Change location (map)
        val loc = pill(L.t("📍 تغيير الموقع", "📍 Change location"), 13f)
        loc.setOnClickListener { openMap() }
        root.addView(loc, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            setMargins((12 * d).toInt(), (24 * d).toInt(), (12 * d).toInt(), 0)
        })

        root.addView(buildCard(d), FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            setMargins((24 * d).toInt(), 0, (24 * d).toInt(), 0)
        })
        setContentView(root)

        compassMode = when {
            sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null -> 1
            sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) != null -> 2
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null &&
                sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null -> 3
            else -> 0
        }

        Prefs.location(this)?.let { (lat, lng) -> setLocation(lat, lng) }

        // Ask for what we need, once
        val need = mutableListOf<String>()
        if (!granted(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
        if (!LocationHelper.hasPermission(this)) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION); need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 7)
        else { cameraAsked = true; startCamera(); locate() }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        cameraAsked = true
        if (granted(Manifest.permission.CAMERA)) startCamera() else show(Msg.CAMERA_DENIED)
        locate()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the map or from Settings
        Prefs.location(this)?.let { (lat, lng) -> setLocation(lat, lng) }
        if (cameraAsked && !cameraOn && granted(Manifest.permission.CAMERA)) { startCamera(); hide(Msg.CAMERA_DENIED) }
        startCompass()
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
        main.removeCallbacksAndMessages(null)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ----- Location -----
    private fun setLocation(lat: Double, lng: Double) {
        qibla = bearingToKaaba(lat, lng)
        // The compass points to magnetic north, the Qibla bearing is from true north
        declination = android.hardware.GeomagneticField(lat.toFloat(), lng.toFloat(), 0f, System.currentTimeMillis()).declination
        hide(Msg.NO_LOCATION)
        overlay.invalidate()
    }

    private fun locate() {
        if (!LocationHelper.hasPermission(this)) {
            if (qibla == null) show(Msg.NO_LOCATION)
            return
        }
        locating = true
        overlay.invalidate()
        LocationHelper.get(this) { loc ->
            locating = false
            if (isFinishing) return@get
            if (loc != null) setLocation(loc.first, loc.second)
            else if (qibla == null) show(Msg.NO_LOCATION)
            overlay.invalidate()
        }
    }

    private fun openMap() = startActivity(MapPickActivity.intent(this))

    // ----- Camera (CameraX) -----
    private fun startCamera() {
        if (cameraOn || !granted(Manifest.permission.CAMERA)) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val pv = Preview.Builder().build()
                pv.setSurfaceProvider(preview.surfaceProvider)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv)
                cameraOn = true
                overlay.noCamera = false
                preview.post { overlay.fov = visibleFov() }
            } catch (_: Exception) {
                cameraOn = false
                overlay.noCamera = true
            }
            overlay.invalidate()
        }, ContextCompat.getMainExecutor(this))
    }

    /** How many degrees of the world fit across the screen (phone upright, preview cropped to fill). */
    private fun visibleFov(): Float {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: break
                val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: break
                // Upright phone: screen width follows the short side of the sensor
                val half = atan(minOf(size.width, size.height) / (2f * f))
                // The 4:3 picture is cropped to fill a tall screen
                val r = overlay.width.toFloat() / overlay.height.coerceAtLeast(1)
                val crop = if (r < 0.75f) r / 0.75f else 1f
                val deg = Math.toDegrees(2.0 * atan(tan(half.toDouble()) * crop)).toFloat()
                return deg.takeIf { it in 15f..100f } ?: 45f
            }
        } catch (_: Exception) {}
        return 45f
    }

    // ----- Compass -----
    private val rot = FloatArray(9)
    private val remap = FloatArray(9)
    private val ori = FloatArray(3)
    private val grav = FloatArray(3)
    private val mag = FloatArray(3)
    private var hasGrav = false
    private var hasMag = false

    private fun startCompass() {
        sensors.unregisterListener(this)
        hasHeading = false
        when (compassMode) {
            0 -> { show(Msg.NO_SENSOR); return }
            1 -> sensors.registerListener(this, sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_GAME)
            2 -> sensors.registerListener(this, sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR), SensorManager.SENSOR_DELAY_GAME)
            3 -> {
                sensors.registerListener(this, sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
                sensors.registerListener(this, sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), SensorManager.SENSOR_DELAY_GAME)
            }
        }
        // No reading after 5 seconds: tell the user what to do
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ if (!hasHeading) show(Msg.NO_COMPASS) }, 5000)
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR ->
                SensorManager.getRotationMatrixFromVector(rot, e.values)
            Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) grav[i] = if (hasGrav) grav[i] * 0.8f + e.values[i] * 0.2f else e.values[i]
                hasGrav = true
                if (!hasMag || !SensorManager.getRotationMatrix(rot, null, grav, mag)) return
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(e.values, 0, mag, 0, 3); hasMag = true
                return
            }
            else -> return
        }
        // Phone held upright, camera looking forward
        SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remap)
        SensorManager.getOrientation(remap, ori)
        var az = Math.toDegrees(ori[0].toDouble()).toFloat() + declination
        az = (az + 720f) % 360f
        // Smooth the reading so the marker does not shake
        smooth = if (smooth.isNaN()) az else {
            val dd = ((az - smooth + 540f) % 360f) - 180f
            (smooth + dd * 0.15f + 360f) % 360f
        }
        heading = smooth
        if (!hasHeading) { hasHeading = true; hide(Msg.NO_COMPASS) }
        val q = qibla ?: run { overlay.invalidate(); return }
        val diff = ((q - heading + 540f) % 360f) - 180f   // -180..180, + means turn right
        overlay.diff = diff
        overlay.invalidate()
        val aligned = abs(diff) < 4f
        if (aligned && !wasAligned) buzz()
        wasAligned = aligned
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        overlay.lowAccuracy = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
    }

    private fun buzz() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun bearingToKaaba(lat: Double, lng: Double): Float {
        val kLat = Math.toRadians(21.422487); val kLng = Math.toRadians(39.826206)
        val p = Math.toRadians(lat); val dL = kLng - Math.toRadians(lng)
        val y = sin(dL); val x = cos(p) * tan(kLat) - sin(p) * cos(dL)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    // ----- Clear messages -----
    private fun show(m: Msg) {
        if (m in dismissed) return
        val cur = shown
        if (cur != null && cur.ordinal < m.ordinal) return   // a more important message is open
        shown = m
        val qText = qibla?.let { L.t("اتجاه القبلة ${it.toInt()}° من الشمال.", "The Qibla is ${it.toInt()}° from north.") } ?: ""
        when (m) {
            Msg.NO_SENSOR -> set(
                L.t("الجوال لا يدعم البوصلة", "This phone has no compass"),
                L.t("لا نستطيع معرفة اتجاه الجوال. $qText", "We cannot read the phone direction. $qText"),
                L.t("حسنًا", "OK") to { dismiss(m) }, null)
            Msg.NO_LOCATION -> set(
                L.t("لم نعرف موقعك", "We could not find your location"),
                L.t("نحتاج موقعك لنحدد اتجاه القبلة بدقة.\nتأكد أن الموقع (GPS) شغّال واضغط «حاول مرة ثانية»، أو حدد موقعك على الخريطة.",
                    "We need your location for an exact Qibla.\nTurn on location (GPS) and tap “Try again”, or choose your location on the map."),
                L.t("حاول مرة ثانية", "Try again") to { hide(m); retryLocation() },
                L.t("📍 حدد موقعك على الخريطة", "📍 Choose on the map") to { hide(m); openMap() })
            Msg.NO_COMPASS -> set(
                L.t("البوصلة لا تستجيب", "The compass is not responding"),
                L.t("حرّك الجوال على شكل رقم 8، وابتعد عن الحديد والمغناطيس.", "Move the phone in a figure 8, away from metal and magnets."),
                L.t("حاول مرة ثانية", "Try again") to { hide(m); startCompass() }, null)
            Msg.CAMERA_DENIED -> set(
                L.t("الكاميرا متوقفة", "The camera is off"),
                L.t("القبلة تعمل كبوصلة فقط.\nلتشغيل الكاميرا: الإعدادات ← الأذونات ← الكاميرا ← سماح.",
                    "The Qibla works as a compass only.\nTo turn on the camera: Settings ← Permissions ← Camera ← Allow."),
                L.t("فتح الإعدادات", "Open Settings") to { hide(m); openAppSettings() },
                L.t("متابعة بدون كاميرا", "Continue without camera") to { dismiss(m) })
        }
        card.visibility = View.VISIBLE
    }

    private fun set(title: String, body: String, b1: Pair<String, () -> Unit>, b2: Pair<String, () -> Unit>?) {
        cardTitle.text = title
        cardBody.text = body
        cardBtn1.text = b1.first; cardBtn1.setOnClickListener { b1.second() }
        if (b2 != null) { cardBtn2.text = b2.first; cardBtn2.setOnClickListener { b2.second() }; cardBtn2.visibility = View.VISIBLE }
        else cardBtn2.visibility = View.GONE
    }

    private fun hide(m: Msg) {
        if (shown == m) { shown = null; card.visibility = View.GONE }
    }

    private fun dismiss(m: Msg) { dismissed.add(m); hide(m) }

    private fun retryLocation() {
        if (LocationHelper.hasPermission(this)) locate()
        else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 8)
    }

    private fun openAppSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
    }

    // ----- Views -----
    private fun pill(text: String, size: Float): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(Color.parseColor("#80000000")) }
        }
    }

    private fun buildCard(d: Float): View {
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(Color.parseColor("#F2122019"))
                setStroke((1 * d).toInt(), Color.parseColor("#80F6D46B"))
            }
            visibility = View.GONE
            isClickable = true   // taps on the card do not reach the screen behind
        }
        cardTitle = TextView(this).apply {
            textSize = 20f; setTextColor(Color.parseColor("#F6D46B")); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        }
        cardBody = TextView(this).apply {
            textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f)
            setPadding(0, (8 * d).toInt(), 0, (14 * d).toInt())
        }
        cardBtn1 = button(d, gold = true)
        cardBtn2 = button(d, gold = false)
        card.addView(cardTitle)
        card.addView(cardBody)
        card.addView(cardBtn1, LinearLayout.LayoutParams(-1, (50 * d).toInt()))
        card.addView(cardBtn2, LinearLayout.LayoutParams(-1, (50 * d).toInt()).apply { topMargin = (8 * d).toInt() })
        return card
    }

    private fun button(d: Float, gold: Boolean) = Button(this).apply {
        isAllCaps = false
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        setTextColor(if (gold) Color.parseColor("#1B1606") else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = 12 * d
            setColor(Color.parseColor(if (gold) "#D9AE3A" else "#33FFFFFF"))
        }
        stateListAnimator = null
    }

    /** Draws the Kaaba marker (or a turn arrow), the angle, and short help text. */
    private inner class Overlay(c: Context) : View(c) {
        var diff = 0f
        var fov = 45f
        var noCamera = false
        var lowAccuracy = false
        private val d = resources.displayMetrics.density
        private val gold = Color.parseColor("#F6D46B")
        private val green = Color.parseColor("#2ECC8A")
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val shade = Paint().apply { color = Color.parseColor("#66000000") }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (cameraOn) fov = visibleFov()
        }

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val cx = w / 2; val cy = h * 0.45f
            val q = qibla
            val ready = q != null && hasHeading
            val aligned = ready && abs(diff) < 4f
            val col = if (aligned) green else gold

            // Top and bottom bars for readable text over the camera
            cv.drawRect(0f, 0f, w, 130 * d, shade)
            cv.drawRect(0f, h - 170 * d, w, h, shade)
            text.color = Color.WHITE
            text.textSize = 22 * d
            cv.drawText(L.t("القبلة", "Qibla"), cx, 92 * d, text)
            text.textSize = 14 * d
            cv.drawText(L.t("امسك الجوال واقفًا وأدر جسمك", "Hold the phone upright and turn"), cx, 118 * d, text)

            // Center guide line
            stroke.color = Color.parseColor("#99FFFFFF"); stroke.strokeWidth = 2 * d
            cv.drawLine(cx, cy - 120 * d, cx, cy + 120 * d, stroke)

            if (ready) {
                val half = fov / 2f
                if (abs(diff) < half) {
                    // Kaaba marker: placed where the Qibla is inside the camera view
                    val x = cx + (tan(Math.toRadians(diff.toDouble())) / tan(Math.toRadians(half.toDouble()))).toFloat() * (w / 2f)
                    drawKaaba(cv, x, cy, if (aligned) 64 * d else 52 * d, col)
                } else {
                    // Off screen: big arrow to the side you need to turn
                    drawArrow(cv, if (diff > 0) w - 60 * d else 60 * d, cy, diff > 0, gold)
                }
            }

            // Bottom: status and angle
            text.textSize = 26 * d
            text.color = col
            val msg = when {
                compassMode == 0 -> L.t("الجوال لا يدعم البوصلة", "This phone has no compass")
                q == null && locating -> L.t("جاري تحديد موقعك…", "Finding your location…")
                q == null -> L.t("حدد موقعك أولًا", "Choose your location first")
                !hasHeading -> L.t("جاري قراءة البوصلة…", "Reading the compass…")
                aligned -> L.t("أنت باتجاه القبلة ✓", "You are facing the Qibla ✓")
                diff > 0 -> L.t("استدر يمينًا", "Turn right")
                else -> L.t("استدر يسارًا", "Turn left")
            }
            cv.drawText(msg, cx, h - 110 * d, text)
            text.color = Color.WHITE
            text.textSize = 15 * d
            if (q != null) cv.drawText(L.t("اتجاه القبلة ${q.toInt()}° من الشمال", "Qibla ${q.toInt()}° from north"), cx, h - 76 * d, text)
            text.textSize = 13 * d
            val tip = when {
                lowAccuracy -> L.t("حرّك الجوال على شكل 8 لمعايرة البوصلة", "Move the phone in a figure 8 to calibrate")
                noCamera -> L.t("الكاميرا غير متاحة — يعمل كبوصلة", "Camera off — working as a compass")
                else -> L.t("ابتعد عن المعادن والمغناطيس", "Keep away from metal and magnets")
            }
            if (lowAccuracy) text.color = gold
            cv.drawText(tip, cx, h - 46 * d, text)
        }

        private fun drawKaaba(cv: Canvas, x: Float, y: Float, s: Float, col: Int) {
            // Glow ring
            stroke.color = col; stroke.strokeWidth = 4 * d
            cv.drawCircle(x, y, s * 1.05f, stroke)
            // Kaaba: black cube with gold band
            fill.color = Color.parseColor("#111111")
            cv.drawRoundRect(x - s * 0.55f, y - s * 0.55f, x + s * 0.55f, y + s * 0.55f, 6 * d, 6 * d, fill)
            fill.color = Color.parseColor("#D4AF37")
            cv.drawRect(x - s * 0.55f, y - s * 0.28f, x + s * 0.55f, y - s * 0.16f, fill)
            cv.drawRect(x - s * 0.12f, y + s * 0.05f, x + s * 0.1f, y + s * 0.55f, fill)
        }

        private fun drawArrow(cv: Canvas, x: Float, y: Float, right: Boolean, col: Int) {
            val s = 46 * d
            val p = Path()
            if (right) { p.moveTo(x + s * 0.6f, y); p.lineTo(x - s * 0.4f, y - s * 0.7f); p.lineTo(x - s * 0.4f, y + s * 0.7f) }
            else { p.moveTo(x - s * 0.6f, y); p.lineTo(x + s * 0.4f, y - s * 0.7f); p.lineTo(x + s * 0.4f, y + s * 0.7f) }
            p.close()
            fill.color = col
            cv.drawPath(p, fill)
        }
    }
}
