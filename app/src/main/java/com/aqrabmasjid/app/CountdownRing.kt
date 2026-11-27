package com.aqrabmasjid.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Big countdown ring.
 * - Before adhan: gold ring that shrinks, "بقي على صلاة" + prayer name + time left.
 * - After adhan:  green ring, "أذّن" + time left to iqama.
 * - After iqama:  full green ring, "أقيمت الصلاة".
 */
class CountdownRing @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 0..1, how much of the ring is still left. */
    var progress = 1f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    var topLabel = ""
    var title = ""
    var bigText = ""
    var bottomLabel = ""

    /** false = waiting for adhan (gold), true = after adhan (green). */
    var afterAdhan = false

    /** true = more than 60 min to adhan: no ring, just "الصلاة القادمة". */
    var plain = false
        set(v) { if (field != v) { field = v; requestLayout() } }

    private val density = resources.displayMetrics.density

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#33FFFFFF")
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F2FFF8")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val titleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val box = RectF()

    fun update(top: String, name: String, big: String, bottom: String, prog: Float, after: Boolean, simple: Boolean = false) {
        topLabel = top; title = name; bigText = big; bottomLabel = bottom
        afterAdhan = after
        plain = simple
        progress = prog
        contentDescription = "$top $name $big $bottom"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val size = min(w, (300 * density).toInt())
        setMeasuredDimension(size, if (plain) (size * 0.62f).toInt() else size)
    }

    override fun onDraw(canvas: Canvas) {
        if (plain) { drawPlain(canvas); return }
        if (topLabel.isEmpty() && title.isEmpty()) { drawCompact(canvas); return }
        val size = min(width, height).toFloat()
        val stroke = size * 0.07f
        val pad = stroke / 2 + 4 * density
        box.set(pad, pad, size - pad, size - pad)

        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val color = Color.parseColor("#FFD43B")
        arc.color = color
        dot.color = color

        canvas.drawArc(box, 0f, 360f, false, track)
        val sweep = 360f * progress
        if (sweep > 0.5f) canvas.drawArc(box, -90f, sweep, false, arc)

        val cx = size / 2
        val cy = size / 2

        // Text sizes scale with the ring
        smallText.textSize = size * 0.065f
        titleText.textSize = size * 0.11f
        bigPaint.textSize = fitSize(bigText, size * 0.19f, size * 0.70f)
        bigPaint.color = color

        canvas.drawText(topLabel, cx, cy - size * 0.20f, smallText)
        canvas.drawText(title, cx, cy - size * 0.07f, titleText)
        canvas.drawText(bigText, cx, cy + size * 0.13f, bigPaint)
        canvas.drawText(bottomLabel, cx, cy + size * 0.27f, smallText)
    }

    /** Small ring for the main card: only the clock and one short line. */
    private fun drawCompact(canvas: Canvas) {
        val size = min(width, height).toFloat()
        val stroke = size * 0.06f
        val pad = stroke / 2 + 2 * density
        box.set(pad, pad, size - pad, size - pad)
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val color = Color.parseColor("#FFD43B")
        arc.color = color
        track.color = Color.parseColor("#26FFFFFF")
        canvas.drawArc(box, 0f, 360f, false, track)
        val sweep = 360f * progress
        if (sweep > 0.5f) canvas.drawArc(box, -90f, sweep, false, arc)
        val cx = size / 2
        val cy = size / 2
        bigPaint.textSize = fitSize(bigText, size * 0.27f, size * 0.78f)
        bigPaint.color = color
        smallText.textSize = size * 0.085f
        canvas.drawText(bigText, cx, cy + bigPaint.textSize * 0.32f, bigPaint)
        canvas.drawText(bottomLabel, cx, cy + size * 0.24f, smallText)
    }

    /** No ring: "الصلاة القادمة", prayer name, adhan time. */
    private fun drawPlain(canvas: Canvas) {
        val s = width.toFloat()
        val h = height.toFloat()
        val cx = s / 2
        smallText.textSize = s * 0.075f
        titleText.textSize = s * 0.15f
        bigPaint.textSize = fitSize(bigText, s * 0.17f, s * 0.95f)
        bigPaint.color = Color.parseColor("#FFD43B")
        canvas.drawText(topLabel, cx, h * 0.20f, smallText)
        canvas.drawText(title, cx, h * 0.52f, titleText)
        canvas.drawText(bigText, cx, h * 0.86f, bigPaint)
    }

    /** Shrink the big text if it is too wide for the ring. */
    private fun fitSize(text: String, start: Float, maxWidth: Float): Float {
        var s = start
        bigPaint.textSize = s
        while (s > 10f && bigPaint.measureText(text) > maxWidth) {
            s -= 2f
            bigPaint.textSize = s
        }
        return s
    }

    companion object {
        /** "1:05:09" or "05:09" (minutes:seconds) for the ring. */
        fun clock(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(java.util.Locale.US, "%02d:%02d", m, s)
        }
    }
}
