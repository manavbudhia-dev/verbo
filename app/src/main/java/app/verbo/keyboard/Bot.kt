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
 * Verbo, the listening agent: a glossy rounded robot head with a glowing antenna, rosy cheeks and
 * big expressive eyes. It bobs and blinks while attached to a window; each mood has its own face.
 * In [Mood.LISTEN] it breathes a halo and throws sound-wave arcs that grow with the live mic [energy].
 */
class BotView(context: Context) : View(context) {
    enum class Mood { HELLO, IDLE, LISTEN, WORK, COOL }

    var mood = Mood.IDLE
        set(v) { field = v; invalidate() }

    /** Live microphone loudness, 0 (silence) to 1 (loud). Drives the listening halo and waves. */
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    private fun star(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        paint.shader = null
        paint.alpha = 255
        paint.color = color
        val p = Path()
        p.moveTo(cx, cy - s)
        p.quadTo(cx + s * 0.14f, cy - s * 0.14f, cx + s, cy)
        p.quadTo(cx + s * 0.14f, cy + s * 0.14f, cx, cy + s)
        p.quadTo(cx - s * 0.14f, cy + s * 0.14f, cx - s, cy)
        p.quadTo(cx - s * 0.14f, cy - s * 0.14f, cx, cy - s)
        c.drawPath(p, paint)
    }

    override fun onDraw(c: Canvas) {
        val r = minOf(width, height) / 2f * 0.70f
        if (r <= 0f) return
        val cx = width / 2f
        val bob = Math.sin(phase * 2 * Math.PI).toFloat() * r * 0.04f
        val cy = height / 2f + r * 0.08f + bob
        val ink = Color.parseColor("#3B3663")
        val browColor = Color.parseColor("#5B4B8A")
        val headL = cx - r * 0.82f
        val headR = cx + r * 0.82f
        val headTop = cy - r * 0.70f
        val headBot = cy + r * 0.82f
        val corner = r * 0.46f
        val eyeY = cy - r * 0.06f
        val exL = cx - r * 0.34f
        val exR = cx + r * 0.34f
        val pulse = 0.5f + 0.5f * Math.sin(phase * 2 * Math.PI).toFloat()

        // Ground shadow.
        paint.shader = null
        paint.color = Color.argb(26, 49, 46, 129)
        c.drawOval(RectF(cx - r * 0.60f, cy + r * 1.04f - bob, cx + r * 0.60f, cy + r * 1.16f - bob), paint)
        paint.alpha = 255

        // Antenna stem + glowing orb (colour follows the mood).
        val orbColor = when (mood) {
            Mood.HELLO -> Color.parseColor("#FDE047")
            Mood.COOL -> Color.parseColor("#4ADE80")
            else -> Color.parseColor("#22D3EE")
        }
        paint.color = Color.parseColor("#6D5BD0")
        c.drawRoundRect(RectF(cx - r * 0.045f, cy - r * 1.00f, cx + r * 0.045f, cy - r * 0.66f), r * 0.045f, r * 0.045f, paint)
        val ox = cx
        val oy = cy - r * 1.02f
        val orbR = r * 0.13f * (1f + 0.18f * Math.sin(phase * 4 * Math.PI).toFloat())
        val glowA = if (mood == Mood.LISTEN) (120 + 120 * energy).toInt().coerceIn(0, 255) else 90
        paint.shader = RadialGradient(ox, oy, r * 0.34f, Color.argb(glowA, Color.red(orbColor), Color.green(orbColor), Color.blue(orbColor)),
            Color.argb(0, Color.red(orbColor), Color.green(orbColor), Color.blue(orbColor)), Shader.TileMode.CLAMP)
        c.drawCircle(ox, oy, r * 0.34f, paint)
        paint.shader = null
        paint.alpha = 255
        paint.color = orbColor
        c.drawCircle(ox, oy, orbR, paint)
        paint.color = Color.argb(200, 255, 255, 255)
        c.drawCircle(ox - orbR * 0.3f, oy - orbR * 0.3f, orbR * 0.28f, paint)
        paint.alpha = 255

        // Side fins ("ears"); they light up cyan while listening.
        val earColor = if (mood == Mood.LISTEN) Color.parseColor("#22D3EE") else Color.parseColor("#6D5BD0")
        paint.color = earColor
        c.drawRoundRect(RectF(headL - r * 0.16f, cy - r * 0.16f, headL + r * 0.08f, cy + r * 0.34f), r * 0.11f, r * 0.11f, paint)
        c.drawRoundRect(RectF(headR - r * 0.08f, cy - r * 0.16f, headR + r * 0.16f, cy + r * 0.34f), r * 0.11f, r * 0.11f, paint)

        // Head: one clean rounded square sharing a violet gradient, with a top gloss.
        paint.shader = LinearGradient(headL, headTop, headR, headBot,
            intArrayOf(Color.parseColor("#C3B0FF"), Color.parseColor("#8B7CF6"), Color.parseColor("#7C6CF0")), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(headL, headTop, headR, headBot), corner, corner, paint)
        paint.shader = null
        paint.alpha = 255
        paint.color = Color.argb(46, 255, 255, 255)
        c.drawOval(RectF(cx - r * 0.52f, headTop + r * 0.10f, cx + r * 0.30f, headTop + r * 0.40f), paint)
        paint.alpha = 255

        // Listening halo: a breathing ring around the head that swells with the mic level.
        if (mood == Mood.LISTEN) {
            val grow = r * (0.10f + 0.18f * pulse * (0.45f + energy))
            stroke.color = Color.argb((60 + 150 * energy).toInt().coerceIn(0, 255), 34, 211, 238)
            stroke.strokeWidth = r * 0.05f
            c.drawRoundRect(RectF(headL - grow, headTop - grow, headR + grow, headBot + grow), corner + grow, corner + grow, stroke)
        }

        val blink = if (phase > 0.46f && phase < 0.52f) 0.15f else 1f
        fun eye(ex: Float, open: Float, pupilDy: Float) {
            paint.color = Color.WHITE
            c.drawOval(RectF(ex - r * 0.22f, eyeY - r * 0.24f * open, ex + r * 0.22f, eyeY + r * 0.24f * open), paint)
            if (open > 0.5f) {
                paint.color = ink
                c.drawCircle(ex, eyeY + r * pupilDy, r * 0.105f, paint)
                paint.color = Color.WHITE
                c.drawCircle(ex + r * 0.04f, eyeY + r * pupilDy - r * 0.04f, r * 0.035f, paint)
            }
        }
        fun brow(ex: Float, y: Float, deg: Float, color: Int) {
            paint.color = color
            c.save()
            c.rotate(deg, ex, y)
            c.drawRoundRect(RectF(ex - r * 0.17f, y - r * 0.045f, ex + r * 0.17f, y + r * 0.045f), r * 0.045f, r * 0.045f, paint)
            c.restore()
        }
        fun cheeks(alpha: Int) {
            paint.color = Color.argb(alpha, 249, 168, 212)
            c.drawOval(RectF(exL - r * 0.30f, cy + r * 0.20f, exL - r * 0.06f, cy + r * 0.34f), paint)
            c.drawOval(RectF(exR + r * 0.06f, cy + r * 0.20f, exR + r * 0.30f, cy + r * 0.34f), paint)
            paint.alpha = 255
        }
        fun smile() {
            stroke.color = ink
            stroke.strokeWidth = r * 0.06f
            c.drawArc(RectF(cx - r * 0.16f, cy + r * 0.16f, cx + r * 0.16f, cy + r * 0.42f), 20f, 140f, false, stroke)
        }

        when (mood) {
            Mood.HELLO -> {
                stroke.color = ink
                stroke.strokeWidth = r * 0.07f
                c.drawArc(RectF(exL - r * 0.18f, eyeY - r * 0.14f, exL + r * 0.18f, eyeY + r * 0.18f), 200f, 140f, false, stroke)
                eye(exR, 1f, 0.02f)
                brow(exL, eyeY - r * 0.40f, -18f, browColor)
                brow(exR, eyeY - r * 0.36f, 10f, browColor)
                cheeks(90)
                smile()
                star(c, cx + r * 0.86f, cy - r * 0.92f, r * 0.20f * (1f + 0.2f * Math.sin(phase * 4 * Math.PI).toFloat()), Color.parseColor("#FDE047"))
            }
            Mood.IDLE -> {
                eye(exL, blink, 0.05f)
                eye(exR, blink, 0.05f)
                brow(exL, eyeY - r * 0.38f, -8f, browColor)
                brow(exR, eyeY - r * 0.38f, 8f, browColor)
                cheeks(80)
                smile()
            }
            Mood.LISTEN -> {
                eye(exL, 1.12f, 0f)
                eye(exR, 1.12f, 0f)
                brow(exL, eyeY - r * 0.44f, -10f, browColor)
                brow(exR, eyeY - r * 0.44f, 10f, browColor)
                cheeks(110)
                // A small attentive "o" mouth.
                stroke.color = ink
                stroke.strokeWidth = r * 0.055f
                c.drawCircle(cx, cy + r * 0.30f, r * 0.09f, stroke)
                // Sound-wave arcs thrown from both ears, pulsing with phase and the live level.
                for (i in 0..2) {
                    val a = (110 + 110 * Math.sin(phase * 2 * Math.PI + i * 0.9)).toInt().coerceIn(40, 255)
                    stroke.color = Color.argb(a, 34, 211, 238)
                    stroke.strokeWidth = r * 0.055f
                    val off = r * (0.86f + 0.16f * i)
                    val span = r * (0.18f + 0.12f * i)
                    c.drawArc(RectF(cx + off, eyeY - span, cx + off + r * 0.22f, eyeY + span), -55f, 110f, false, stroke)
                    c.drawArc(RectF(cx - off - r * 0.22f, eyeY - span, cx - off, eyeY + span), 125f, 110f, false, stroke)
                }
            }
            Mood.WORK -> {
                eye(exL, blink, -0.06f)
                eye(exR, blink, -0.06f)
                brow(exL, eyeY - r * 0.42f, -6f, browColor)
                brow(exR, eyeY - r * 0.42f, 6f, browColor)
                cheeks(70)
                stroke.color = ink
                stroke.strokeWidth = r * 0.06f
                c.drawLine(cx - r * 0.12f, cy + r * 0.30f, cx + r * 0.12f, cy + r * 0.30f, stroke)
                for (i in 0..2) {
                    val a = phase * 2 * Math.PI + i * 2.1
                    paint.color = Color.argb((120 + 100 * Math.sin(a + i)).toInt().coerceIn(0, 255), 34, 211, 238)
                    c.drawCircle(cx + r * (0.60f + 0.16f * i) + Math.cos(a).toFloat() * r * 0.06f,
                        cy - r * (0.92f - 0.12f * i) + Math.sin(a).toFloat() * r * 0.06f, r * (0.05f + 0.02f * i), paint)
                }
                paint.alpha = 255
            }
            Mood.COOL -> {
                paint.color = Color.parseColor("#1F2937")
                c.drawRoundRect(RectF(cx - r * 0.62f, eyeY - r * 0.16f, cx + r * 0.62f, eyeY + r * 0.02f), r * 0.05f, r * 0.05f, paint)
                c.drawRoundRect(RectF(exL - r * 0.21f, eyeY - r * 0.12f, exL + r * 0.21f, eyeY + r * 0.20f), r * 0.09f, r * 0.09f, paint)
                c.drawRoundRect(RectF(exR - r * 0.21f, eyeY - r * 0.12f, exR + r * 0.21f, eyeY + r * 0.20f), r * 0.09f, r * 0.09f, paint)
                brow(exL, eyeY - r * 0.34f, -8f, Color.parseColor("#64748B"))
                brow(exR, eyeY - r * 0.34f, 8f, Color.parseColor("#64748B"))
                stroke.color = ink
                stroke.strokeWidth = r * 0.06f
                c.drawArc(RectF(cx - r * 0.14f, cy + r * 0.18f, cx + r * 0.18f, cy + r * 0.42f), 10f, 120f, false, stroke)
                star(c, cx - r * 0.80f, cy - r * 0.92f, r * 0.16f * (1f + 0.2f * Math.sin(phase * 4 * Math.PI).toFloat()), Color.parseColor("#4ADE80"))
            }
        }
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