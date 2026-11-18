package app.verbo.keyboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Verbo, the keyboard mascot: a glossy gumdrop that runs from orange through pink into teal, with a
 * two-lobed tuft on top, a cream face, big sparkly eyes, rosy cheeks and a little "!" of excitement.
 * It bobs and blinks while attached to a window; each mood has its own face. In [Mood.LISTEN] a halo
 * breathes and the excitement marks on both sides pulse with the live mic [energy]; in [Mood.WORK]
 * three dots take turns above its head.
 */
class BotView(context: Context) : View(context) {
    enum class Mood { HELLO, IDLE, LISTEN, WORK, COOL }

    var mood = Mood.IDLE
        set(v) { field = v; invalidate() }

    /** Live microphone loudness, 0 (silence) to 1 (loud). Drives the listening halo and marks. */
    var energy = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedFraction; invalidate() }
    }

    private fun col(hex: String) = Color.parseColor(hex)
    private val body = Path().apply {
        moveTo(98f, 166f)
        cubicTo(46f, 166f, 24f, 150f, 26f, 116f)
        cubicTo(28f, 76f, 62f, 42f, 98f, 42f)
        cubicTo(134f, 42f, 168f, 76f, 170f, 116f)
        cubicTo(172f, 150f, 150f, 166f, 98f, 166f)
        close()
    }
    private val sideColors = intArrayOf(col("#FF5F55"), col("#FF4A7E"), col("#EE4583"), col("#2BE0C8"), col("#0DB2AC"))
    private val sideStops = floatArrayOf(0f, 0.45f, 0.70f, 0.78f, 1f)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    /** A four-pointed sparkle. */
    private fun star(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        paint.shader = null
        paint.color = color
        val p = Path()
        p.moveTo(cx, cy - s)
        p.quadTo(cx + s * 0.14f, cy - s * 0.14f, cx + s, cy)
        p.quadTo(cx + s * 0.14f, cy + s * 0.14f, cx, cy + s)
        p.quadTo(cx - s * 0.14f, cy + s * 0.14f, cx - s, cy)
        p.quadTo(cx - s * 0.14f, cy - s * 0.14f, cx, cy - s)
        c.drawPath(p, paint)
    }

    private fun oval(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, deg: Float = 0f) {
        if (deg != 0f) { c.save(); c.rotate(deg, cx, cy) }
        c.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), paint)
        if (deg != 0f) c.restore()
    }

    override fun onDraw(c: Canvas) {
        // Drawn on a 200-unit design grid: the figure spans about 155 x 165 units around (100, 92).
        val k = minOf(width, height) / 170f
        if (k <= 0f) return
        val pulse = 0.5f + 0.5f * Math.sin(phase * 2 * Math.PI).toFloat()
        val bob = Math.sin(phase * 2 * Math.PI).toFloat() * 3f
        c.save()
        c.translate(width / 2f - 100f * k, height / 2f - 92f * k)
        c.scale(k, k)

        // Ground shadow, shrinking a little as it rises.
        paint.shader = null
        paint.color = Color.argb(40, 122, 42, 85)
        c.drawOval(RectF(46f + bob, 166f, 150f - bob, 176f), paint)

        c.translate(0f, bob)

        // Listening halo: soft pink and teal rings that swell with the voice.
        if (mood == Mood.LISTEN) {
            val grow = 4f + 14f * pulse * (0.4f + energy)
            stroke.strokeWidth = 4f
            stroke.color = Color.argb((70 + 150 * energy).toInt().coerceIn(0, 255), 255, 74, 126)
            c.drawCircle(98f, 104f, 82f + grow, stroke)
            stroke.color = Color.argb((40 + 100 * energy).toInt().coerceIn(0, 255), 43, 224, 200)
            c.drawCircle(98f, 104f, 94f + grow * 1.4f, stroke)
        }

        // The tuft: a tall glossy lobe and a rounder pink one, behind the body.
        paint.color = Color.WHITE
        paint.shader = LinearGradient(96f, 8f, 128f, 60f, intArrayOf(col("#FFB03A"), col("#FF6A55"), col("#FF3F7F")), null, Shader.TileMode.CLAMP)
        oval(c, 112f, 34f, 17f, 27f, 40f)
        paint.shader = null
        paint.color = col("#FF4F86")
        oval(c, 136f, 46f, 17f, 11f, 8f)

        // Body: orange on top, pink round the left and bottom, teal down the right.
        paint.color = Color.WHITE
        paint.shader = LinearGradient(26f, 0f, 170f, 0f, sideColors, sideStops, Shader.TileMode.CLAMP)
        c.drawPath(body, paint)
        paint.shader = LinearGradient(0f, 42f, 0f, 166f, intArrayOf(col("#FFA03A"), Color.argb(215, 255, 138, 64), Color.argb(0, 255, 127, 69)),
            floatArrayOf(0f, 0.22f, 0.5f), Shader.TileMode.CLAMP)
        c.drawPath(body, paint)
        paint.shader = LinearGradient(0f, 42f, 0f, 166f, intArrayOf(Color.argb(0, 194, 31, 107), Color.argb(0, 194, 31, 107), Color.argb(140, 194, 31, 107)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(body, paint)
        paint.shader = null
        // Where the tuft melts into the body.
        paint.color = Color.argb(220, 255, 90, 130)
        c.drawPath(Path().apply { moveTo(84f, 52f); cubicTo(96f, 40f, 130f, 40f, 148f, 54f); cubicTo(138f, 62f, 104f, 64f, 84f, 52f); close() }, paint)

        // Gloss.
        paint.color = Color.argb(190, 255, 255, 255)
        oval(c, 104f, 25f, 4.5f, 11f, 40f)
        oval(c, 62f, 64f, 13f, 5f, -38f)
        paint.color = Color.argb(170, 255, 255, 255)
        oval(c, 50f, 79f, 4f, 3f, -38f)

        // Face: a soft rim, then the cream plate.
        paint.color = Color.argb(90, 214, 58, 110)
        oval(c, 97f, 113f, 60f, 42f)
        paint.color = Color.WHITE
        paint.shader = RadialGradient(97f, 100f, 62f, intArrayOf(col("#FFFBF8"), col("#FCEDE8"), col("#F6D9D6")), floatArrayOf(0f, 0.8f, 1f), Shader.TileMode.CLAMP)
        oval(c, 97f, 112f, 58f, 40f)
        paint.shader = null

        // Cheeks with two little blush strokes.
        paint.color = col("#FF8FA6")
        oval(c, 58f, 123f, 11f, 7.5f)
        oval(c, 138f, 123f, 11f, 7.5f)
        stroke.color = Color.argb(230, 255, 255, 255)
        stroke.strokeWidth = 1.6f
        for (x in floatArrayOf(54f, 58f, 135f, 139f)) c.drawLine(x, 125f, x + 2f, 120f, stroke)

        val ink = col("#2A1220")
        val blink = if (phase > 0.46f && phase < 0.51f) 0.1f else 1f
        fun eye(cx: Float, open: Float, lookUp: Float = 0f, sparkle: Boolean = true) {
            if (open < 0.3f) {
                stroke.color = ink
                stroke.strokeWidth = 4f
                c.drawArc(RectF(cx - 12f, 99f, cx + 12f, 111f), 20f, 140f, false, stroke)
                return
            }
            paint.color = Color.WHITE
            paint.shader = RadialGradient(cx, 110f, 18f, intArrayOf(col("#5A2E3E"), col("#2A1220"), col("#1A0A14")), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            oval(c, cx, 105f, 16f, 16f * open)
            paint.shader = null
            paint.color = Color.WHITE
            c.drawCircle(cx + 5f, 97f - lookUp, 6.5f, paint)
            if (sparkle) star(c, cx - 6f, 110f - lookUp, 3.5f + 0.8f * pulse, Color.WHITE)
        }
        fun openMouth(scale: Float) {
            c.save(); c.scale(scale, scale, 98f, 112f)
            paint.color = col("#8E2238")
            c.drawPath(Path().apply { moveTo(88f, 110f); quadTo(98f, 107f, 108f, 110f); quadTo(107f, 128f, 98f, 128f); quadTo(89f, 128f, 88f, 110f); close() }, paint)
            paint.color = col("#FF6B86")
            c.drawPath(Path().apply { moveTo(91f, 119f); quadTo(98f, 114f, 105f, 119f); quadTo(103.5f, 127f, 98f, 127f); quadTo(92.5f, 127f, 91f, 119f); close() }, paint)
            c.restore()
        }
        fun marks(right: Boolean, alpha: Int, len: Float) {
            stroke.color = Color.argb(alpha, 255, 200, 26)
            stroke.strokeWidth = 7f
            val sx = if (right) 1f else -1f
            val ox = if (right) 0f else 196f
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                val mx = (x1 + x2) / 2; val my = (y1 + y2) / 2
                c.drawLine(ox + sx * (mx + (x1 - mx) * len), my + (y1 - my) * len, ox + sx * (mx + (x2 - mx) * len), my + (y2 - my) * len, stroke)
            }
            line(164f, 54f, 158f, 66f)
            line(174f, 65f, 164f, 72f)
            line(176f, 78f, 167f, 80f)
        }

        when (mood) {
            Mood.HELLO -> {
                eye(72f, 1f); eye(126f, 1f)
                openMouth(1f)
                marks(true, 255, 0.85f + 0.15f * pulse)
            }
            Mood.IDLE -> {
                eye(72f, blink); eye(126f, blink)
                openMouth(0.7f)
            }
            Mood.LISTEN -> {
                eye(72f, 1.05f); eye(126f, 1.05f)
                // A small round "o": listening closely.
                paint.color = col("#8E2238")
                c.drawOval(RectF(92f, 113f, 104f, 126f), paint)
                paint.color = col("#FF6B86")
                c.drawOval(RectF(94.5f, 119f, 101.5f, 125f), paint)
                val a = (120 + 135 * energy).toInt().coerceIn(0, 255)
                val len = 0.6f + 0.4f * (0.5f * pulse + 0.5f * energy)
                marks(true, a, len)
                marks(false, a, len)
            }
            Mood.WORK -> {
                eye(72f, 1f, lookUp = 3f, sparkle = false); eye(126f, 1f, lookUp = 3f, sparkle = false)
                stroke.color = col("#8E2238")
                stroke.strokeWidth = 4f
                c.drawLine(92f, 120f, 104f, 120f, stroke)
                for (i in 0..2) {
                    val t = ((phase * 3f - i) % 3f + 3f) % 3f
                    val lit = if (t < 1f) 1f - kotlin.math.abs(t - 0.5f) * 2f else 0f
                    paint.color = if (i == 1) col("#FF4A7E") else col("#2BE0C8")
                    paint.alpha = (120 + 135 * lit).toInt()
                    c.drawCircle(150f + i * 11f, 30f - 5f * lit, 4f + 1.5f * lit, paint)
                }
                paint.alpha = 255
            }
            Mood.COOL -> {
                // Shades on, with a glint, and a sideways grin.
                paint.color = col("#1A0A14")
                c.drawRoundRect(RectF(54f, 94f, 90f, 116f), 10f, 10f, paint)
                c.drawRoundRect(RectF(108f, 94f, 144f, 116f), 10f, 10f, paint)
                stroke.color = col("#1A0A14")
                stroke.strokeWidth = 4f
                c.drawLine(90f, 100f, 108f, 100f, stroke)
                stroke.color = Color.argb(160, 255, 255, 255)
                stroke.strokeWidth = 3f
                c.drawLine(60f, 110f, 68f, 99f, stroke)
                c.drawLine(114f, 110f, 122f, 99f, stroke)
                stroke.color = col("#8E2238")
                stroke.strokeWidth = 4f
                c.drawArc(RectF(94f, 114f, 112f, 128f), 20f, 110f, false, stroke)
                star(c, 168f, 56f, 9f * (1f + 0.25f * Math.sin(phase * 4 * Math.PI).toFloat()), col("#FFC81A"))
            }
        }
        c.restore()
    }
}

/** The bot panels' paper: a soft blue-lavender wash, with pastel doodles for the intro. */
class BotPaper(private val doodles: Boolean, private val d: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    private class D(val x: Float, val y: Float, val kind: Int, val color: Int, val rot: Float, val s: Float)

    private val scatter = listOf(
        D(0.14f, 0.07f, 0, 0xFFC9B8E8.toInt(), 0f, 1.1f),
        D(0.30f, 0.04f, 0, 0xFFA9C7E8.toInt(), 0f, 0.8f),
        D(0.44f, 0.09f, 2, 0xFFA9C7E8.toInt(), -20f, 1f),
        D(0.62f, 0.06f, 1, 0xFFF2A9C4.toInt(), 15f, 0.8f),
        D(0.08f, 0.22f, 1, 0xFFF2A9C4.toInt(), -10f, 1.2f),
        D(0.20f, 0.16f, 2, 0xFFC9B8E8.toInt(), 40f, 0.9f),
        D(0.86f, 0.30f, 3, 0xFFB5E3C0.toInt(), 8f, 1.4f),
        D(0.80f, 0.42f, 2, 0xFFB5E3C0.toInt(), -30f, 1f),
        D(0.10f, 0.52f, 6, 0xFFA9C7E8.toInt(), 0f, 0.9f),
        D(0.16f, 0.60f, 0, 0xFFC9B8E8.toInt(), 0f, 1f),
        D(0.12f, 0.68f, 4, 0xFFF7D774.toInt(), 0f, 0.9f),
        D(0.88f, 0.55f, 4, 0xFFB5E3C0.toInt(), 0f, 0.7f),
        D(0.75f, 0.12f, 5, 0xFFF7D774.toInt(), 0f, 0.8f),
        D(0.30f, 0.86f, 2, 0xFFF2A9C4.toInt(), 10f, 0.8f),
    )

    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val r = 18f * d
        val shape = Path()
        shape.moveTo(0f, r)
        shape.arcTo(RectF(0f, 0f, r * 2, r * 2), 180f, 90f)
        shape.lineTo(w - r, 0f)
        shape.arcTo(RectF(w - r * 2, 0f, w, r * 2), 270f, 90f)
        shape.lineTo(w, h)
        shape.lineTo(0f, h)
        shape.close()
        paint.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.parseColor("#EAF6FB"), Color.parseColor("#EFE9F7"), Color.parseColor("#E4F1FA")), null, Shader.TileMode.CLAMP)
        c.drawPath(shape, paint)
        paint.shader = RadialGradient(w * 0.08f, h * 0.88f, w * 0.55f, Color.argb(70, 249, 200, 216), Color.argb(0, 249, 200, 216), Shader.TileMode.CLAMP)
        c.drawPath(shape, paint)
        paint.shader = RadialGradient(w * 0.95f, h * 0.25f, w * 0.5f, Color.argb(60, 191, 232, 245), Color.argb(0, 191, 232, 245), Shader.TileMode.CLAMP)
        c.drawPath(shape, paint)
        paint.shader = null
        if (!doodles) return
        paint.alpha = 150
        stroke.alpha = 150
        for (item in scatter) {
            val s = w * 0.05f * item.s
            c.save()
            c.translate(w * item.x, h * item.y)
            c.rotate(item.rot)
            paint.color = item.color
            stroke.color = item.color
            when (item.kind) {
                0 -> {
                    c.drawCircle(0f, 0f, s * 0.55f, paint)
                    paint.color = Color.argb(200, 255, 255, 255)
                    c.drawCircle(-s * 0.15f, -s * 0.18f, s * 0.09f, paint)
                    c.drawCircle(s * 0.12f, -s * 0.10f, s * 0.09f, paint)
                    c.drawCircle(-s * 0.02f, s * 0.12f, s * 0.09f, paint)
                }
                1 -> {
                    val p = Path()
                    p.moveTo(0f, s * 0.35f)
                    p.cubicTo(-s * 0.55f, -s * 0.05f, -s * 0.30f, -s * 0.50f, 0f, -s * 0.18f)
                    p.cubicTo(s * 0.30f, -s * 0.50f, s * 0.55f, -s * 0.05f, 0f, s * 0.35f)
                    c.drawPath(p, paint)
                }
                2 -> {
                    stroke.strokeWidth = s * 0.16f
                    val p = Path()
                    p.moveTo(-s * 0.4f, 0f)
                    p.cubicTo(-s * 0.2f, -s * 0.3f, 0f, s * 0.3f, s * 0.2f, 0f)
                    p.cubicTo(s * 0.3f, -s * 0.2f, s * 0.4f, s * 0.1f, s * 0.45f, 0f)
                    c.drawPath(p, stroke)
                }
                3 -> {
                    val p = Path()
                    p.moveTo(s * 0.1f, -s * 0.5f)
                    p.lineTo(-s * 0.15f, s * 0.05f)
                    p.lineTo(0f, s * 0.05f)
                    p.lineTo(-s * 0.1f, s * 0.5f)
                    p.lineTo(s * 0.2f, -s * 0.05f)
                    p.lineTo(s * 0.05f, -s * 0.05f)
                    p.close()
                    c.drawPath(p, paint)
                }
                4 -> {
                    val p = Path()
                    p.moveTo(0f, -s * 0.5f)
                    p.quadTo(s * 0.07f, -s * 0.07f, s * 0.5f, 0f)
                    p.quadTo(s * 0.07f, s * 0.07f, 0f, s * 0.5f)
                    p.quadTo(-s * 0.07f, s * 0.07f, -s * 0.5f, 0f)
                    p.quadTo(-s * 0.07f, -s * 0.07f, 0f, -s * 0.5f)
                    c.drawPath(p, paint)
                }
                5 -> {
                    stroke.strokeWidth = s * 0.14f
                    c.drawCircle(0f, 0f, s * 0.4f, stroke)
                }
                else -> {
                    stroke.strokeWidth = s * 0.14f
                    c.drawLine(-s * 0.3f, 0f, s * 0.3f, 0f, stroke)
                    c.drawLine(0f, -s * 0.3f, 0f, s * 0.3f, stroke)
                }
            }
            c.restore()
        }
        paint.alpha = 255
        stroke.alpha = 255
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}