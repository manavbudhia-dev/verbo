package app.verbo.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/**
 * The translate wheel: a light semicircular fan of flags. Slide to a flag and release to translate into it.
 * Roll a finger along the blue rim (or rest it on an end arrow) and the fan turns one flag at a time,
 * gliding slowly and locking on each, through every language in [all].
 */
class LanguageWheel(a: Context) : View(a) {
    var languages: List<String> = emptyList()
    var pick = -1
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    /** Released on a flag: the index into [ring]. */
    var onPick: (Int) -> Unit = {}
    var onCancel: () -> Unit = {}
    var onSpace: () -> Unit = {}
    var onMore: () -> Unit = {}
    /** Choosing the language to keep translating into, rather than translating once. */
    var autoMode = false
    /** Living inside a settings page: no space bar, no Cancel, tighter bottom edge. */
    var embedded = false
    /** Every language the compass and the turning fan can reach (wider than [languages]). */
    var all: List<String> = emptyList()

    /** The fan's ring: your languages fill the semicircle, the rest turn into view. */
    val ring: List<String> get() = if (embedded) languages else languages + all.filter { it !in languages }

    /** True when the ring holds more languages than the fan shows, so it can turn. */
    val fanTurns: Boolean get() = !embedded && languages.isNotEmpty() && ring.size > languages.size

    /** How far the fan has turned, in whole flags once it locks. */
    val fanRotation: Float get() = fanSpin.rot / fanSpin.step

    /** The language releasing over visible slot [slot] would pick, at the fan's locked position. */
    fun ringIndexAt(slot: Int): Int {
        val size = ring.size
        if (size == 0 || slot < 0) return -1
        val base = Math.round(fanSpin.target / fanSpin.step)
        return (((base + slot) % size) + size) % size
    }
    var onCompassPick: (Int) -> Unit = {}
    /** Each icon the compass locks onto, for a soft tick of feedback. */
    var onTurn: () -> Unit = {}

    /** Open straight into the 360° compass (double-press space). */
    fun spin() {
        compass = true
        spin.reset()
        compassFresh = true
        pick = -1
        invalidate()
    }

    /** True while the 360° compass is showing instead of the fan. */
    val spinning: Boolean get() = compass

    /** Where the compass currently sits, in degrees; always a whole icon once it locks. */
    val compassRotation: Float get() = spin.rot

    private var compass = false
    private val spin = CompassSpin({ invalidate() }, { onTurn() })
    private var compassFresh = false
    private var compassLastAngle = 0f
    private val fanSpin = CompassSpin({ invalidate() }, { onTurn() },
        step = 36f, duration = CompassSpin.FAN_DURATION, oneAtATime = true, lockMs = CompassSpin.FAN_LOCK)
    private var rimLastAngle = Float.NaN
    private var edgeDir = 0
    private var wx = 0f
    private var wy = 0f

    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun dp(v: Float) = v * d
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val blue = Color.parseColor("#2E9BF9")

    private val cx get() = width / 2f
    private val cy get() = height - dp(if (embedded) 14f else 40f)
    private val r get() = minOf(width * 0.46f, cy - dp(36f))

    private fun pillText() = when {
        pick >= 0 && ringIndexAt(pick) >= 0 -> ring[ringIndexAt(pick)]
        embedded -> "Your languages"
        autoMode -> "Auto-translate into"
        else -> "Translate into"
    }
    private fun pillRect(): RectF = WheelLook.pillRect(cx, pillText(), d, sp)
    private fun spaceRect() = RectF(cx - dp(75f), height - dp(32f), cx + dp(75f), height - dp(6f))

    private fun flagCenter(i: Float): Pair<Float, Float> {
        val step = 180f / languages.size
        val angle = Math.toRadians((180 - (i + 0.5f) * step).toDouble())
        return Pair(cx + (r * 0.74f * kotlin.math.cos(angle)).toFloat(), cy - (r * 0.74f * kotlin.math.sin(angle)).toFloat())
    }

    private fun sliceAt(x: Float, y: Float): Int {
        if (languages.isEmpty()) return -1
        val dx = x - cx
        val dy = cy - y
        val dist = kotlin.math.hypot(dx, dy)
        if (dy < 0 || dist < r * 0.30f || dist > r * 1.2f) return -1
        if (edgeAt(x, y) != 0) return -1
        val angle = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble())
        return ((180 - angle) / (180.0 / languages.size)).toInt().coerceIn(0, languages.size - 1)
    }

    // The compass sits centred so the whole 360° dial fits.
    private val ccx get() = width / 2f
    private val ccy get() = height * 0.60f
    private val cr get() = minOf(width * 0.44f, height * 0.40f)

    private fun onBezel(x: Float, y: Float): Boolean {
        val dy = cy - y
        if (dy < 0) return false
        val dist = kotlin.math.hypot(x - cx, dy)
        return dist >= r * 0.90f && dist <= r * 1.2f
    }

    private fun screenAngle(x: Float, y: Float) =
        Math.toDegrees(kotlin.math.atan2((y - ccy).toDouble(), (x - ccx).toDouble())).toFloat()

    /** The finger's angle around the fan's centre: 0 on the right, 180 on the left. */
    private fun fanAngle(x: Float, y: Float) =
        Math.toDegrees(kotlin.math.atan2((cy - y).toDouble(), (x - cx).toDouble())).toFloat()

    /** -1 / +1 when the finger rests low at the fan's left / right end: the flags keep turning that way. */
    private fun edgeAt(x: Float, y: Float): Int {
        if (!fanTurns) return 0
        val dx = x - cx
        val dy = cy - y
        val dist = kotlin.math.hypot(dx, dy)
        if (dy < 0 || dy > r * 0.13f || dist < r * 0.42f || dist > r * 1.2f) return 0
        return if (dx < 0) -1 else 1
    }

    private val edgeTurn = object : Runnable {
        override fun run() {
            if (edgeDir == 0) return
            if (fanSpin.turn(edgeDir)) pick = -1
            handler.postDelayed(this, 90)
        }
    }

    private fun stopEdge() {
        edgeDir = 0
        handler.removeCallbacks(edgeTurn)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (languages.isNotEmpty()) fanSpin.step = 180f / languages.size
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopEdge()
        fanSpin.reset()
        spin.reset()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                wx = event.x
                wy = event.y
                if (compass) {
                    val a = screenAngle(wx, wy)
                    if (compassFresh) {
                        compassLastAngle = a
                        compassFresh = false
                    } else {
                        var delta = a - compassLastAngle
                        if (delta > 180) delta -= 360 else if (delta < -180) delta += 360
                        spin.drag(delta)
                        compassLastAngle = a
                    }
                } else {
                    if (languages.isNotEmpty()) fanSpin.step = 180f / languages.size
                    // Rolling the rim turns the flags one at a time, following the finger.
                    if (fanTurns && onBezel(wx, wy)) {
                        val a = fanAngle(wx, wy)
                        if (!rimLastAngle.isNaN()) fanSpin.drag(a - rimLastAngle)
                        rimLastAngle = a
                    } else rimLastAngle = Float.NaN
                    val edge = edgeAt(wx, wy)
                    if (edge != edgeDir) {
                        stopEdge()
                        edgeDir = edge
                        if (edge != 0) handler.post(edgeTurn)
                    }
                    pick = sliceAt(wx, wy)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                stopEdge()
                rimLastAngle = Float.NaN
                fanSpin.settle()
                pick = -1
            }
            MotionEvent.ACTION_UP -> {
                stopEdge()
                rimLastAngle = Float.NaN
                if (compass) {
                    spin.settle()
                    val cand = WheelLook.compassCandidate(spin.rot, WheelLook.COMPASS_SLOTS, all.size)
                    val dist = kotlin.math.hypot(wx - ccx, wy - ccy)
                    compass = false
                    pick = -1
                    if (dist >= cr * 0.30f && cand >= 0) onCompassPick(cand) else onCancel()
                    return true
                }
                fanSpin.settle()
                val i = ringIndexAt(pick)
                pick = -1
                when {
                    pillRect().contains(event.x, event.y) -> onMore()
                    !embedded && spaceRect().contains(event.x, event.y) -> onSpace()
                    i >= 0 -> onPick(i)
                    !embedded -> onCancel()
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val c = canvas
        WheelLook.paper(c, width.toFloat(), height.toFloat(), d)
        if (compass) { drawCompass(c); return }
        val n = languages.size
        if (n == 0) return
        val arc = RectF(cx - r, cy - r, cx + r, cy + r)
        WheelLook.fan(c, cx, cy, r, d)
        val step = 180f / n
        val turns = fanTurns
        val rot = if (turns) fanSpin.rot else 0f
        WheelLook.ticks(c, cx, cy, r, d, rot)
        if (pick >= 0) {
            val (fx, fy) = flagCenter(pick.toFloat())
            WheelLook.beam(c, cx, cy, fx, fy, arc, 180f + pick * step + 1.5f, step - 3f, d)
        }
        val list = ring
        val size = list.size
        val base = kotlin.math.floor(rot / step).toInt()
        val frac = rot - base * step
        for (j in -1..n) {
            if (!turns && (j < 0 || j >= n)) continue
            // Flags glide along the arc as the ring turns, fading in and out at the ends.
            val pos = j - frac / step
            val theta = 180f - (pos + 0.5f) * step
            val alpha = (minOf(theta, 180f - theta) / (step * 0.5f)).coerceIn(0f, 1f)
            if (alpha <= 0.02f) continue
            val lang = list[(((base + j) % size) + size) % size]
            val (x, y) = flagCenter(pos)
            val layer = if (alpha < 1f) c.saveLayerAlpha(x - dp(40f), y - dp(40f), x + dp(40f), y + dp(40f), (alpha * 255).toInt()) else -1
            WheelLook.tile(c, x, y, dp(24f), d)
            Flags.draw(c, lang, x - dp(17f), y - dp(11.5f), dp(34f), dp(23f), face)
            edge.color = Color.parseColor("#14000000")
            edge.strokeWidth = dp(1f)
            c.drawRoundRect(RectF(x - dp(17f), y - dp(11.5f), x + dp(17f), y + dp(11.5f)), dp(3.2f), dp(3.2f), edge)
            if (layer >= 0) c.restoreToCount(layer)
        }
        if (turns) {
            WheelLook.chevron(c, cx - r * 0.86f, cy - dp(10f), -1, edgeDir == -1, d)
            WheelLook.chevron(c, cx + r * 0.86f, cy - dp(10f), 1, edgeDir == 1, d)
        }
        WheelLook.glow(c, cx, cy, d)
        if (!embedded) WheelLook.cancel(c, cx, cy, d, sp)
        WheelLook.drawPill(c, pillRect(), pillText(), WheelLook.ink, d, sp)
        if (!embedded) {
            val space = spaceRect()
            face.color = Color.parseColor("#CFE2F4")
            c.drawRoundRect(space, dp(8f), dp(8f), face)
            edge.color = Color.parseColor("#86B7E4")
            edge.strokeWidth = dp(1f)
            c.drawRoundRect(space, dp(8f), dp(8f), edge)
            letters.color = blue
            letters.textSize = 14 * sp
            letters.typeface = Typeface.DEFAULT_BOLD
            c.drawText("space", cx, space.centerY() - (letters.descent() + letters.ascent()) / 2, letters)
        }
    }

    /** The 360° compass of every language: spin the blue rim, release under the needle to translate. */
    private fun drawCompass(c: Canvas) {
        val k = WheelLook.COMPASS_SLOTS
        val size = all.size
        val rot = spin.rot
        WheelLook.compassDial(c, ccx, ccy, cr, rot, d)
        val cand = WheelLook.compassCandidate(rot, k, size)
        if (size > 0) for (j in 0 until k) {
            val lang = all[WheelLook.compassIndex(j, k, rot, size)]
            val (x, y) = WheelLook.compassPos(ccx, ccy, cr, j, k, rot)
            val isCand = j == k / 2
            WheelLook.tile(c, x, y, dp(if (isCand) 19f else 17f), d)
            if (isCand) WheelLook.ring(c, x, y, dp(22f), blue, dp(2.5f))
            Flags.draw(c, lang, x - dp(15f), y - dp(10f), dp(30f), dp(20f), face)
            edge.color = Color.parseColor("#14000000")
            edge.strokeWidth = dp(1f)
            c.drawRoundRect(RectF(x - dp(15f), y - dp(10f), x + dp(15f), y + dp(10f)), dp(3.2f), dp(3.2f), edge)
        }
        val label = if (cand >= 0) all[cand] else "No languages"
        WheelLook.compassTop(c, ccx, ccy, cr, k, d, sp, label, "Cancel")
    }
}