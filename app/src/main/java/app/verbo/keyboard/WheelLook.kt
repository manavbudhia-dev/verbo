package app.verbo.keyboard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/** The paper-and-protractor language shared by the shortcut wheel and the translate wheel. */
object WheelLook {
    val blue = Color.parseColor("#2E9BF9")
    val ink = Color.parseColor("#1B2739")
    val faint = Color.parseColor("#98A2B3")
    val label = Color.parseColor("#7A8698")

    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** Graph paper: a cool blue field with a fine white grid. */
    fun paper(c: Canvas, w: Float, h: Float, d: Float) {
        face.shader = null
        face.color = Color.parseColor("#E9F1F8")
        c.drawRect(0f, 0f, w, h, face)
        edge.color = Color.parseColor("#88FFFFFF")
        edge.strokeWidth = d
        var g = 12f * d
        while (g < w) {
            c.drawLine(g, 0f, g, h, edge)
            g += 12f * d
        }
        g = 12f * d
        while (g < h) {
            c.drawLine(0f, g, w, g, edge)
            g += 12f * d
        }
    }

    /** The semicircular fan with its soft radial wash and hairline rim. */
    fun fan(c: Canvas, cx: Float, cy: Float, r: Float, d: Float) {
        val arc = RectF(cx - r, cy - r, cx + r, cy + r)
        face.shader = RadialGradient(cx, cy, r, intArrayOf(Color.parseColor("#F5F3FA"), Color.parseColor("#E7E5F3"), Color.parseColor("#DEE9F4")), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawArc(arc, 180f, 180f, true, face)
        face.shader = null
        edge.color = Color.parseColor("#C9DCEC")
        edge.strokeWidth = d
        c.drawArc(arc, 180f, 180f, false, edge)
    }

    /** Protractor ticks every 4 degrees along the rim, shifted by [rot] degrees as the ring turns. */
    fun ticks(c: Canvas, cx: Float, cy: Float, r: Float, d: Float, rot: Float = 0f) {
        edge.color = Color.parseColor("#B7CDE2")
        edge.strokeWidth = d
        var a = ((rot % 4f) + 4f) % 4f
        while (a <= 180f) {
            val rad = Math.toRadians(a.toDouble())
            val cos = kotlin.math.cos(rad).toFloat()
            val sin = kotlin.math.sin(rad).toFloat()
            c.drawLine(cx + r * cos, cy - r * sin, cx + (r - 6f * d) * cos, cy - (r - 6f * d) * sin, edge)
            a += 4f
        }
    }

    /** A light beam from the centre to the hovered tile, its rim segment in blue, and a knob halfway. */
    fun beam(c: Canvas, cx: Float, cy: Float, fx: Float, fy: Float, arc: RectF, start: Float, sweep: Float, d: Float) {
        val dist = kotlin.math.hypot(fx - cx, fy - cy)
        val ux = (fx - cx) / dist
        val uy = (fy - cy) / dist
        val half = 22f * d
        val beam = Path().apply {
            moveTo(cx, cy)
            lineTo(fx - uy * half, fy + ux * half)
            lineTo(fx + uy * half, fy - ux * half)
            close()
        }
        face.color = Color.parseColor("#597EB6EF")
        c.drawPath(beam, face)
        edge.color = blue
        edge.strokeWidth = 4.5f * d
        c.drawArc(arc, start, sweep, false, edge)
        val rx = cx + (fx - cx) * 0.58f
        val ry = cy + (fy - cy) * 0.58f
        // A hollow ring on the beam, so the light shows through it.
        edge.color = blue
        edge.strokeWidth = 2.5f * d
        c.drawCircle(rx, ry, 12f * d, edge)
        edge.strokeWidth = d
    }

    /** A white circular tile with a soft drop shadow. */
    fun tile(c: Canvas, x: Float, y: Float, radius: Float, d: Float) {
        face.color = Color.parseColor("#22000000")
        c.drawCircle(x, y + 1.5f * d, radius, face)
        face.color = Color.WHITE
        c.drawCircle(x, y, radius, face)
    }

    fun ring(c: Canvas, x: Float, y: Float, radius: Float, color: Int, width: Float) {
        edge.color = color
        edge.strokeWidth = width
        c.drawCircle(x, y, radius, edge)
        edge.strokeWidth = 1f
    }

    /** The white glow over the centre where lifting the finger cancels. */
    fun glow(c: Canvas, cx: Float, cy: Float, d: Float) {
        face.shader = RadialGradient(cx, cy, 55f * d, intArrayOf(Color.parseColor("#CCFFFFFF"), Color.parseColor("#00FFFFFF")), null, Shader.TileMode.CLAMP)
        c.drawArc(RectF(cx - 55f * d, cy - 55f * d, cx + 55f * d, cy + 55f * d), 180f, 180f, true, face)
        face.shader = null
    }

    /** A small arrow at an end of the fan: the ring turns that way. Filled blue while it is turning. */
    fun chevron(c: Canvas, x: Float, y: Float, dir: Int, active: Boolean, d: Float) {
        face.color = if (active) blue else Color.WHITE
        c.drawCircle(x, y, 11f * d, face)
        edge.color = if (active) Color.WHITE else blue
        edge.strokeWidth = 2.2f * d
        edge.strokeCap = Paint.Cap.ROUND
        val w = 3.5f * d * dir
        c.drawLine(x - w, y - 5f * d, x + w, y, edge)
        c.drawLine(x + w, y, x - w, y + 5f * d, edge)
        edge.strokeCap = Paint.Cap.BUTT
        edge.strokeWidth = d
    }

    fun cancel(c: Canvas, cx: Float, cy: Float, d: Float, sp: Float) {
        drawText(c, "Cancel", cx, cy - 14f * d, 13f * sp, faint)
    }

    fun pillRect(cx: Float, text: String, d: Float, sp: Float): RectF {
        letters.typeface = Typeface.DEFAULT_BOLD
        letters.textSize = 14f * sp
        val w = letters.measureText(text) + 28f * d
        return RectF(cx - w / 2, 6f * d, cx + w / 2, 34f * d)
    }

    fun drawPill(c: Canvas, pill: RectF, text: String, color: Int, d: Float, sp: Float) {
        face.color = Color.parseColor("#14000000")
        c.drawRoundRect(RectF(pill.left, pill.top + d, pill.right, pill.bottom + d), 10f * d, 10f * d, face)
        face.color = Color.WHITE
        c.drawRoundRect(pill, 10f * d, 10f * d, face)
        drawText(c, text, pill.centerX(), pill.centerY(), 14f * sp, color, bold = true)
    }

    fun drawText(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        letters.color = color
        letters.textSize = size
        letters.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif", Typeface.NORMAL)
        c.drawText(s, x, y - (letters.descent() + letters.ascent()) / 2, letters)
    }

    // ---------- the compass: a full 360° dial spun by its blue rim ----------

    /** Visible slots around the compass; the catalog scrolls through them as the rim turns. */
    const val COMPASS_SLOTS = 10

    fun compassStep(k: Int) = 360f / k

    /** Which catalog entry sits under the top needle at this rotation. */
    fun compassCandidate(rot: Float, k: Int, size: Int): Int {
        if (size == 0) return -1
        val c = Math.round(rot / compassStep(k))
        return ((c % size) + size) % size
    }

    /** The catalog entry shown in visible slot [j] at this rotation. */
    fun compassIndex(j: Int, k: Int, rot: Float, size: Int): Int {
        val c = Math.round(rot / compassStep(k))
        return (((c + j - k / 2) % size) + size) % size
    }

    /** Where visible slot [j] sits: a ring that turns continuously with the rim. */
    fun compassPos(cx: Float, cy: Float, r: Float, j: Int, k: Int, rot: Float): Pair<Float, Float> {
        val step = compassStep(k)
        val frac = rot - Math.round(rot / step) * step
        val ang = Math.toRadians((90 + (j - k / 2f) * step - frac).toDouble())
        return Pair(cx + (r * 0.72f * kotlin.math.cos(ang)).toFloat(), cy - (r * 0.72f * kotlin.math.sin(ang)).toFloat())
    }

    /** The dial body: full-circle wash, the blue compass frame, and ticks that turn with the rim. */
    fun compassDial(c: Canvas, cx: Float, cy: Float, r: Float, rot: Float, d: Float) {
        face.shader = RadialGradient(cx, cy, r, intArrayOf(Color.parseColor("#F5F3FA"), Color.parseColor("#E7E5F3"), Color.parseColor("#DEE9F4")), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, face)
        face.shader = null
        edge.color = blue
        edge.strokeWidth = 3f * d
        c.drawCircle(cx, cy, r, edge)
        edge.color = Color.parseColor("#6FA8DC")
        edge.strokeWidth = d
        var a = 0f
        while (a < 360f) {
            val rad = Math.toRadians((a + rot).toDouble())
            val cos = kotlin.math.cos(rad).toFloat()
            val sin = kotlin.math.sin(rad).toFloat()
            c.drawLine(cx + r * cos, cy - r * sin, cx + (r - 6f * d) * cos, cy - (r - 6f * d) * sin, edge)
            a += 4f
        }
        edge.strokeWidth = d
    }

    /** Over the tiles: the fixed top needle, the blue selection arc, centre glow and the pill. */
    fun compassTop(c: Canvas, cx: Float, cy: Float, r: Float, k: Int, d: Float, sp: Float, pill: String, centerText: String) {
        val arc = RectF(cx - r, cy - r, cx + r, cy + r)
        val step = compassStep(k)
        edge.color = blue
        edge.strokeWidth = 4.5f * d
        c.drawArc(arc, -90f - step / 2, step, false, edge)
        face.color = blue
        val needle = Path().apply {
            moveTo(cx - 6f * d, cy - r - 3f * d)
            lineTo(cx + 6f * d, cy - r - 3f * d)
            lineTo(cx, cy - r + 11f * d)
            close()
        }
        c.drawPath(needle, face)
        face.shader = RadialGradient(cx, cy, 55f * d, intArrayOf(Color.parseColor("#CCFFFFFF"), Color.parseColor("#00FFFFFF")), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, 55f * d, face)
        face.shader = null
        drawText(c, centerText, cx, cy, 13f * sp, faint)
        drawPill(c, pillRect(cx, pill, d, sp), pill, blue, d, sp)
    }
}

/**
 * Detented rotation: half a slot of finger travel turns one icon with a slow eased spin that locks on it.
 * With [oneAtATime] the dial never runs ahead of the finger: a turn must finish and rest for [lockMs]
 * before the next one may start, so a fast flick still moves exactly one icon at a time.
 */
class CompassSpin(
    private val redraw: () -> Unit,
    private val onLock: () -> Unit = {},
    var step: Float = 360f / WheelLook.COMPASS_SLOTS,
    private val duration: Long = DURATION,
    private val oneAtATime: Boolean = false,
    private val lockMs: Long = 0L,
) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var rot = 0f
        private set
    private var from = 0f
    /** Where the dial is heading (or rests): always a whole number of steps. */
    var target = 0f
        private set
    private var start = 0L
    private var drag = 0f
    private var turning = false
    private var lockedUntil = 0L

    /** True while a turn is animating or resting in its lock. */
    val busy: Boolean get() = turning || android.os.SystemClock.uptimeMillis() < lockedUntil

    private val tick = object : Runnable {
        override fun run() {
            val t = ((android.os.SystemClock.uptimeMillis() - start).toFloat() / duration).coerceIn(0f, 1f)
            // Ease in and out: starts gently, glides, and settles softly into the detent.
            val e = if (oneAtATime) t * t * (3f - 2f * t) else 1f - (1f - t) * (1f - t) * (1f - t)
            rot = from + (target - from) * e
            redraw()
            if (t < 1f) handler.postDelayed(this, 16)
            else {
                turning = false
                rot = target
                lockedUntil = android.os.SystemClock.uptimeMillis() + lockMs
                onLock()
            }
        }
    }

    fun reset() {
        handler.removeCallbacks(tick)
        rot = 0f; from = 0f; target = 0f; drag = 0f; turning = false; lockedUntil = 0L
    }

    /** Feed a wrapped finger-angle delta in degrees; each half-slot of travel queues one icon's turn. */
    fun drag(delta: Float) {
        drag += delta
        if (oneAtATime) {
            val cap = step * 0.45f
            if (busy) { drag = drag.coerceIn(-cap, cap); return }
            val dir = if (drag >= step / 2) 1 else if (drag <= -step / 2) -1 else 0
            if (dir != 0) { drag = 0f; turn(dir) }
            return
        }
        var dir = 0
        while (drag >= step / 2) { drag -= step; dir++ }
        while (drag <= -step / 2) { drag += step; dir-- }
        if (dir != 0) {
            target += dir * step
            from = rot
            start = android.os.SystemClock.uptimeMillis()
            if (!turning) { turning = true; handler.post(tick) }
        }
    }

    /** Turn one icon in [dir] (+1 or -1). Refused while a one-at-a-time dial is still turning or locked. */
    fun turn(dir: Int): Boolean {
        if (dir == 0 || (oneAtATime && busy)) return false
        target += dir * step
        from = rot
        start = android.os.SystemClock.uptimeMillis()
        if (!turning) { turning = true; handler.post(tick) }
        return true
    }

    /** Release: lock onto the icon the spin was heading to. */
    fun settle() {
        handler.removeCallbacks(tick)
        if (turning) { rot = target; turning = false; redraw() }
    }

    companion object {
        const val DURATION = 500L
        /** The shortcut fan: a slow glide per icon, then a short rest before the next may start. */
        const val FAN_DURATION = 560L
        const val FAN_LOCK = 140L
    }
}
