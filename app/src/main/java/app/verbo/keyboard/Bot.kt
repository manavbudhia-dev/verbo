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
 * Verbo, the listening agent: a glossy blue droplet with a white face plate, big black eyes with a
 * white glint, a small smile and a swept tip. It bobs and blinks while attached to a window; each mood
 * has its own face. In [Mood.LISTEN] it breathes a halo and throws sound-wave arcs that grow with the
 * live mic [energy].
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
        val r = minOf(width, height) / 2f * 0.74f
        if (r <= 0f) return
        val cx = width / 2f
        val bob = Math.sin(phase * 2 * Math.PI).toFloat() * r * 0.04f
        val cy = height / 2f + r * 0.05f + bob
        val ink = Color.parseColor("#17181C")
        val eyeY = cy + r * 0.12f
        val exL = cx - r * 0.36f
        val exR = cx + r * 0.36f
        val rx = r * 0.17f
        val ry = r * 0.21f
        val pulse = 0.5f + 0.5f * Math.sin(phase * 2 * Math.PI).toFloat()

        // Ground shadow.
        paint.shader = null
        paint.color = Color.argb(26, 49, 46, 129)
        c.drawOval(RectF(cx - r * 0.60f, cy + r * 1.06f - bob, cx + r * 0.60f, cy + r * 1.18f - bob), paint)
        paint.alpha = 255

        // Listening halo: a breathing ring that swells with the live mic level.
        if (mood == Mood.LISTEN) {
            val grow = r * (0.06f + 0.16f * pulse * (0.45f + energy))
            stroke.color = Color.argb((60 + 150 * energy).toInt().coerceIn(0, 255), 34, 211, 238)
            stroke.strokeWidth = r * 0.05f
            c.drawOval(RectF(cx - r * 1.06f - grow, cy - r * 1.06f - grow, cx + r * 1.06f + grow, cy + r * 1.06f + grow), stroke)
        }

        // Glossy droplet body: cyan-blue on the left melting into periwinkle, with a soft swept tip.
        val body = Path()
        body.moveTo(cx - r, cy)
        body.cubicTo(cx - r * 1.02f, cy - r * 0.72f, cx - r * 0.55f, cy - r * 1.02f, cx + r * 0.05f, cy - r * 1.00f)
        body.cubicTo(cx + r * 0.42f, cy - r * 0.98f, cx + r * 0.55f, cy - r * 1.10f, cx + r * 0.62f, cy - r * 1.25f)
        body.cubicTo(cx + r * 0.72f, cy - r * 1.05f, cx + r * 0.92f, cy - r * 0.72f, cx + r * 0.98f, cy - r * 0.25f)
        body.cubicTo(cx + r * 1.04f, cy + r * 0.35f, cx + r * 0.66f, cy + r * 1.00f, cx, cy + r * 1.00f)
        body.cubicTo(cx - r * 0.66f, cy + r * 1.00f, cx - r * 1.02f, cy + r * 0.55f, cx - r, cy)
        body.close()
        paint.shader = LinearGradient(cx - r, cy - r * 0.4f, cx + r, cy + r * 0.6f,
            intArrayOf(Color.parseColor("#2FB1F0"), Color.parseColor("#5AA7F6"), Color.parseColor("#8B93F8")), null, Shader.TileMode.CLAMP)
        c.drawPath(body, paint)
        paint.shader = null
        paint.alpha = 255

        // Gloss streaks: a long one top-left, a short one by the tip.
        paint.color = Color.argb(210, 255, 255, 255)
        c.save(); c.translate(cx - r * 0.45f, cy - r * 0.60f); c.rotate(-35f)
        c.drawOval(RectF(-r * 0.30f, -r * 0.09f, r * 0.30f, r * 0.09f), paint); c.restore()
        c.save(); c.translate(cx + r * 0.55f, cy - r * 0.55f); c.rotate(20f)
        c.drawOval(RectF(-r * 0.16f, -r * 0.06f, r * 0.16f, r * 0.06f), paint); c.restore()
        paint.alpha = 255

        // White face plate.
        paint.shader = LinearGradient(cx, cy - r * 0.34f, cx, cy + r * 0.74f,
            intArrayOf(Color.WHITE, Color.parseColor("#ECEFFB")), null, Shader.TileMode.CLAMP)
        c.drawOval(RectF(cx - r * 0.74f, cy - r * 0.34f, cx + r * 0.74f, cy + r * 0.74f), paint)
        paint.shader = null
        paint.alpha = 255

        val blink = if (phase > 0.46f && phase < 0.52f) 0.15f else 1f
        fun eye(ex: Float, open: Float, dy: Float) {
            paint.color = ink
            c.drawOval(RectF(ex - rx, eyeY + dy - ry * open, ex + rx, eyeY + dy + ry * open), paint)
            if (open > 0.5f) {
                paint.color = Color.WHITE
                c.drawCircle(ex + rx * 0.35f, eyeY + dy - ry * open * 0.45f, r * 0.055f, paint)
            }
        }
        fun smile(wide: Float) {
            stroke.color = ink
            stroke.strokeWidth = r * 0.075f
            c.drawArc(RectF(cx - r * wide, cy + r * 0.30f, cx + r * wide, cy + r * 0.56f), 15f, 150f, false, stroke)
        }

        when (mood) {
            Mood.HELLO -> {
                // A happy wink on the left, open eye on the right.
                stroke.color = ink
                stroke.strokeWidth = r * 0.075f
                c.drawArc(RectF(exL - rx, eyeY - ry * 0.5f, exL + rx, eyeY + ry * 0.6f), 200f, 140f, false, stroke)
                eye(exR, 1f, 0f)
                smile(0.19f)
                star(c, cx + r * 0.82f, cy - r * 0.92f, r * 0.18f * (1f + 0.2f * Math.sin(phase * 4 * Math.PI).toFloat()), Color.parseColor("#FDE047"))
            }
            Mood.IDLE -> {
                eye(exL, blink, 0f)
                eye(exR, blink, 0f)
                smile(0.17f)
            }
            Mood.LISTEN -> {
                eye(exL, 1.1f, 0f)
                eye(exR, 1.1f, 0f)
                // A small attentive "o" mouth.
                stroke.color = ink
                stroke.strokeWidth = r * 0.06f
                c.drawCircle(cx, cy + r * 0.42f, r * 0.09f, stroke)
                // Sound-wave arcs from both sides, pulsing with phase and the live level.
                for (i in 0..2) {
                    val a = (110 + 110 * Math.sin(phase * 2 * Math.PI + i * 0.9)).toInt().coerceIn(40, 255)
                    stroke.color = Color.argb(a, 34, 211, 238)
                    stroke.strokeWidth = r * 0.055f
                    val off = r * (0.80f + 0.14f * i)
                    val span = r * (0.16f + 0.10f * i)
                    c.drawArc(RectF(cx + off, eyeY - span, cx + off + r * 0.18f, eyeY + span), -55f, 110f, false, stroke)
                    c.drawArc(RectF(cx - off - r * 0.18f, eyeY - span, cx - off, eyeY + span), 125f, 110f, false, stroke)
                }
            }
            Mood.WORK -> {
                eye(exL, blink, -r * 0.06f)
                eye(exR, blink, -r * 0.06f)
                stroke.color = ink
                stroke.strokeWidth = r * 0.06f
                c.drawLine(cx - r * 0.12f, cy + r * 0.42f, cx + r * 0.12f, cy + r * 0.42f, stroke)
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
                c.drawRoundRect(RectF(cx - r * 0.62f, eyeY - r * 0.20f, cx + r * 0.62f, eyeY - r * 0.04f), r * 0.05f, r * 0.05f, paint)
                c.drawRoundRect(RectF(exL - rx * 1.15f, eyeY - r * 0.16f, exL + rx * 1.15f, eyeY + r * 0.20f), r * 0.09f, r * 0.09f, paint)
                c.drawRoundRect(RectF(exR - rx * 1.15f, eyeY - r * 0.16f, exR + rx * 1.15f, eyeY + r * 0.20f), r * 0.09f, r * 0.09f, paint)
                stroke.color = ink
                stroke.strokeWidth = r * 0.06f
                c.drawArc(RectF(cx - r * 0.14f, cy + r * 0.32f, cx + r * 0.18f, cy + r * 0.56f), 10f, 120f, false, stroke)
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