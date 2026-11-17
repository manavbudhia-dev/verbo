package app.verbo.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View

/** The translate wheel: a light semicircular fan of flags. Slide to a flag and release to translate into it. */
class LanguageWheel(a: Context) : View(a) {
    var languages: List<String> = emptyList()
    var pick = -1
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var onPick: (Int) -> Unit = {}
    var onCancel: () -> Unit = {}
    var onSpace: () -> Unit = {}
    var onMore: () -> Unit = {}
    /** Choosing the language to keep translating into, rather than translating once. */
    var autoMode = false
    /** Living inside a settings page: no space bar, no Cancel, tighter bottom edge. */
    var embedded = false
    /** Every language the compass can spin through (wider than [languages]). */
    var all: List<String> = emptyList()
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
        pick >= 0 -> languages[pick]
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
        if (dy < 0 || dist < r * 0.30f || dist > r * 1.12f) return -1
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
        return dist >= r * 0.95f && dist <= r * 1.2f
    }

    private fun screenAngle(x: Float, y: Float) =
        Math.toDegrees(kotlin.math.atan2((y - ccy).toDouble(), (x - ccx).toDouble())).toFloat()

    private val bezelHold = Runnable {
        if (compass || embedded || !onBezel(wx, wy)) return@Runnable
        compass = true
        spin.reset()
        compassLastAngle = screenAngle(wx, wy)
        pick = -1
        invalidate()
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
                    handler.removeCallbacks(bezelHold)
                    if (!embedded && onBezel(wx, wy)) handler.postDelayed(bezelHold, 300)
                    pick = sliceAt(wx, wy)
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(bezelHold)
                if (compass) {
                    spin.settle()
                    val cand = WheelLook.compassCandidate(spin.rot, WheelLook.COMPASS_SLOTS, all.size)
                    val dist = kotlin.math.hypot(wx - ccx, wy - ccy)
                    compass = false
                    pick = -1
                    if (dist >= cr * 0.30f && cand >= 0) onCompassPick(cand) else onCancel()
                    return true
                }
                val i = pick
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
        WheelLook.ticks(c, cx, cy, r, d)
        val step = 180f / n
        if (pick >= 0) {
            val (fx, fy) = flagCenter(pick.toFloat())
            WheelLook.beam(c, cx, cy, fx, fy, arc, 180f + pick * step + 1.5f, step - 3f, d)
        }
        for (i in 0 until n) {
            val (x, y) = flagCenter(i.toFloat())
            WheelLook.tile(c, x, y, dp(24f), d)
            Flags.draw(c, languages[i], x - dp(17f), y - dp(11.5f), dp(34f), dp(23f), face)
            edge.color = Color.parseColor("#14000000")
            edge.strokeWidth = dp(1f)
            c.drawRoundRect(RectF(x - dp(17f), y - dp(11.5f), x + dp(17f), y + dp(11.5f)), dp(3.2f), dp(3.2f), edge)
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