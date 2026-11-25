package com.aqrabmasjid.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.hardware.Camera
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.min

/**
 * Qibla with augmented reality: the camera shows what is in front of you,
 * and a Kaaba marker appears where the Qibla is. Off screen, an arrow shows which way to turn.
 * Without camera permission it still works as a compass on a dark background.
 */
@Suppress("DEPRECATION")
class QiblaActivity : Activity(), SensorEventListener, SurfaceHolder.Callback {

    private var camera: Camera? = null
    private lateinit var surface: SurfaceView
    private lateinit var overlay: Overlay
    private lateinit var sensors: SensorManager
    private var qibla = 0f          // bearing from the user to the Kaaba (degrees from north)
    private var heading = 0f        // where the camera points (degrees from north)
    private var smooth = Float.NaN
    private var wasAligned = false
    private var declination = 0f    // magnetic north -> true north (about +3° in Riyadh)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        L.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val (lat, lng) = Prefs.location(this) ?: (24.7136 to 46.6753)
        qibla = bearingToKaaba(lat, lng)
        // The compass points to magnetic north, the Qibla bearing is from true north
        declination = android.hardware.GeomagneticField(lat.toFloat(), lng.toFloat(), 0f, System.currentTimeMillis()).declination

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0B100D"))
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        overlay = Overlay(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.setOnClickListener { finish() }
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            surface.holder.addCallback(this)
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            surface.holder.addCallback(this)
            if (surface.holder.surface?.isValid == true) startCamera(surface.holder)
        } else overlay.noCamera = true
    }

    override fun onResume() {
        super.onResume()
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: run { overlay.noSensor = true; overlay.invalidate() }
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
        stopCamera()
    }

    // ----- Camera -----
    override fun surfaceCreated(holder: SurfaceHolder) = startCamera(holder)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) = stopCamera()

    private fun startCamera(holder: SurfaceHolder) {
        if (camera != null) return
        try {
            val c = Camera.open()
            c.setDisplayOrientation(90)
            c.parameters = c.parameters.also { p ->
                if (p.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true)
                    p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                overlay.fov = (p.verticalViewAngle.takeIf { it in 30f..100f } ?: 50f)
            }
            c.setPreviewDisplay(holder)
            c.startPreview()
            camera = c
        } catch (_: Exception) {
            overlay.noCamera = true
        }
    }

    private fun stopCamera() {
        try { camera?.stopPreview(); camera?.release() } catch (_: Exception) {}
        camera = null
    }

    // ----- Compass -----
    private val rot = FloatArray(9)
    private val remap = FloatArray(9)
    private val ori = FloatArray(3)

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Phone held upright, camera looking forward
        SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remap)
        SensorManager.getOrientation(remap, ori)
        var az = Math.toDegrees(ori[0].toDouble()).toFloat() + declination
        az = (az + 720f) % 360f
        // Smooth the reading so the marker does not shake
        smooth = if (smooth.isNaN()) az else {
            val d = ((az - smooth + 540f) % 360f) - 180f
            (smooth + d * 0.15f + 360f) % 360f
        }
        heading = smooth
        val diff = ((qibla - heading + 540f) % 360f) - 180f   // -180..180, + means turn right
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
        val y = sin(dL); val x = cos(p) * kotlin.math.tan(kLat) - sin(p) * cos(dL)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    /** Draws the Kaaba marker (or a turn arrow), the angle, and short help text. */
    private inner class Overlay(c: Context) : View(c) {
        var diff = 0f
        var fov = 50f
        var noCamera = false
        var noSensor = false
        var lowAccuracy = false
        private val d = resources.displayMetrics.density
        private val gold = Color.parseColor("#F6D46B")
        private val green = Color.parseColor("#2ECC8A")
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val shade = Paint().apply { color = Color.parseColor("#66000000") }

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val cx = w / 2; val cy = h * 0.45f
            val aligned = abs(diff) < 4f
            val col = if (aligned) green else gold

            // Top and bottom bars for readable text over the camera
            cv.drawRect(0f, 0f, w, 120 * d, shade)
            cv.drawRect(0f, h - 170 * d, w, h, shade)
            text.textSize = 22 * d
            cv.drawText(L.t("القبلة", "Qibla"), cx, 52 * d, text)
            text.textSize = 14 * d
            cv.drawText(L.t("امسك الجوال واقفًا وأدر جسمك", "Hold the phone upright and turn"), cx, 84 * d, text)

            // Center guide line
            stroke.color = Color.parseColor("#99FFFFFF"); stroke.strokeWidth = 2 * d
            cv.drawLine(cx, cy - 120 * d, cx, cy + 120 * d, stroke)

            // Kaaba marker: placed where the Qibla is, if it is inside the camera view
            val half = fov / 2f
            if (abs(diff) <= half) {
                val x = cx + (diff / half) * (w / 2f)
                drawKaaba(cv, x, cy, if (aligned) 64 * d else 52 * d, col)
            } else {
                // Off screen: big arrow to the side you need to turn
                drawArrow(cv, if (diff > 0) w - 60 * d else 60 * d, cy, diff > 0, gold)
            }

            // Bottom: status and angle
            text.textSize = 26 * d
            text.color = col
            val msg = when {
                noSensor -> L.t("الجوال لا يدعم البوصلة", "This phone has no compass")
                aligned -> L.t("أنت باتجاه القبلة ✓", "You are facing the Qibla ✓")
                diff > 0 -> L.t("استدر يمينًا", "Turn right")
                else -> L.t("استدر يسارًا", "Turn left")
            }
            cv.drawText(msg, cx, h - 110 * d, text)
            text.color = Color.WHITE
            text.textSize = 15 * d
            cv.drawText(L.t("اتجاه القبلة ${qibla.toInt()}° من الشمال", "Qibla ${qibla.toInt()}° from north"), cx, h - 76 * d, text)
            text.textSize = 13 * d
            val tip = when {
                lowAccuracy -> L.t("حرّك الجوال على شكل 8 لمعايرة البوصلة", "Move the phone in a figure 8 to calibrate")
                noCamera -> L.t("الكاميرا غير متاحة — يعمل كبوصلة", "Camera off — working as a compass")
                else -> L.t("ابتعد عن المعادن والمغناطيس · اضغط للإغلاق", "Keep away from metal · tap to close")
            }
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
            fill.color = Color.parseColor("#D4AF37")
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

    @Suppress("unused")
    private fun clamp(v: Float, a: Float, b: Float) = min(b, maxOf(a, v))
}
