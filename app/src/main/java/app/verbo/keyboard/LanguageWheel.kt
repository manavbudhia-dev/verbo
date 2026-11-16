package app.verbo.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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

    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * d
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val blue = Color.parseColor("#2E9BF9")

    private val cx get() = width / 2f
    private val cy get() = height - dp(40f)
    private val r get() = minOf(width * 0.46f, cy - dp(36f))

    private fun pillText() = if (pick >= 0) languages[pick] else "Translate into"
    private fun pillRect(): RectF {
        letters.typeface = Typeface.DEFAULT_BOLD
        letters.textSize = 14 * sp
        val w = letters.measureText(pillText()) + dp(28f)
        return RectF(cx - w / 2, dp(6f), cx + w / 2, dp(34f))
    }
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> pick = sliceAt(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                val i = pick
                pick = -1
                when {
                    pillRect().contains(event.x, event.y) -> onMore()
                    spaceRect().contains(event.x, event.y) -> onSpace()
                    i >= 0 -> onPick(i)
                    else -> onCancel()
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val c = canvas
        face.shader = null
        face.color = Color.parseColor("#E9F1F8")
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), face)
        edge.color = Color.parseColor("#88FFFFFF")
        edge.strokeWidth = dp(1f)
        var g = dp(12f)
        while (g < width) {
            c.drawLine(g, 0f, g, height.toFloat(), edge)
            g += dp(12f)
        }
        g = dp(12f)
        while (g < height) {
            c.drawLine(0f, g, width.toFloat(), g, edge)
            g += dp(12f)
        }
        val n = languages.size
        if (n == 0) return
        val arc = RectF(cx - r, cy - r, cx + r, cy + r)
        face.shader = RadialGradient(cx, cy, r, intArrayOf(Color.parseColor("#F5F3FA"), Color.parseColor("#E7E5F3"), Color.parseColor("#DEE9F4")), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawArc(arc, 180f, 180f, true, face)
        face.shader = null
        edge.color = Color.parseColor("#C9DCEC")
        edge.strokeWidth = dp(1f)
        c.drawArc(arc, 180f, 180f, false, edge)
        edge.color = Color.parseColor("#B7CDE2")
        var a = 0f
        while (a <= 180f) {
            val rad = Math.toRadians(a.toDouble())
            val cos = kotlin.math.cos(rad).toFloat()
            val sin = kotlin.math.sin(rad).toFloat()
            c.drawLine(cx + r * cos, cy - r * sin, cx + (r - dp(6f)) * cos, cy - (r - dp(6f)) * sin, edge)
            a += 4f
        }
        val step = 180f / n
        if (pick >= 0) {
            val (fx, fy) = flagCenter(pick.toFloat())
            val dist = kotlin.math.hypot(fx - cx, fy - cy)
            val ux = (fx - cx) / dist
            val uy = (fy - cy) / dist
            val half = dp(22f)
            val beam = Path().apply {
                moveTo(cx, cy)
                lineTo(fx - uy * half, fy + ux * half)
                lineTo(fx + uy * half, fy - ux * half)
                close()
            }
            face.color = Color.parseColor("#597EB6EF")
            c.drawPath(beam, face)
            edge.color = blue
            edge.strokeWidth = dp(4.5f)
            c.drawArc(arc, 180f + pick * step + 1.5f, step - 3f, false, edge)
            val rx = cx + (fx - cx) * 0.58f
            val ry = cy + (fy - cy) * 0.58f
            face.color = Color.WHITE
            c.drawCircle(rx, ry, dp(15f), face)
            edge.strokeWidth = dp(2f)
            c.drawCircle(rx, ry, dp(15f), edge)
        }
        for (i in 0 until n) {
            val (x, y) = flagCenter(i.toFloat())
            face.color = Color.parseColor("#22000000")
            c.drawCircle(x, y + dp(1.5f), dp(24f), face)
            face.color = Color.WHITE
            c.drawCircle(x, y, dp(24f), face)
            Flags.draw(c, languages[i], x - dp(17f), y - dp(11.5f), dp(34f), dp(23f), face)
            edge.color = Color.parseColor("#14000000")
            edge.strokeWidth = dp(1f)
            c.drawRoundRect(RectF(x - dp(17f), y - dp(11.5f), x + dp(17f), y + dp(11.5f)), dp(3.2f), dp(3.2f), edge)
        }
        face.shader = RadialGradient(cx, cy, dp(55f), intArrayOf(Color.parseColor("#CCFFFFFF"), Color.parseColor("#00FFFFFF")), null, Shader.TileMode.CLAMP)
        c.drawArc(RectF(cx - dp(55f), cy - dp(55f), cx + dp(55f), cy + dp(55f)), 180f, 180f, true, face)
        face.shader = null
        letters.color = Color.parseColor("#98A2B3")
        letters.textSize = 13 * sp
        letters.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        c.drawText("Cancel", cx, cy - dp(14f) - (letters.descent() + letters.ascent()) / 2, letters)
        val pill = pillRect()
        face.color = Color.parseColor("#14000000")
        c.drawRoundRect(RectF(pill.left, pill.top + dp(1f), pill.right, pill.bottom + dp(1f)), dp(10f), dp(10f), face)
        face.color = Color.WHITE
        c.drawRoundRect(pill, dp(10f), dp(10f), face)
        letters.color = Color.parseColor("#1B2739")
        letters.textSize = 14 * sp
        letters.typeface = Typeface.DEFAULT_BOLD
        c.drawText(pillText(), cx, pill.centerY() - (letters.descent() + letters.ascent()) / 2, letters)
        val space = spaceRect()
        face.color = Color.parseColor("#CFE2F4")
        c.drawRoundRect(space, dp(8f), dp(8f), face)
        edge.color = Color.parseColor("#86B7E4")
        edge.strokeWidth = dp(1f)
        c.drawRoundRect(space, dp(8f), dp(8f), edge)
        letters.color = blue
        c.drawText("space", cx, space.centerY() - (letters.descent() + letters.ascent()) / 2, letters)
    }
}