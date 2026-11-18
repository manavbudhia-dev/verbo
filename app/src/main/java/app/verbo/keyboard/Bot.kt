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
 * Verbo, the keyboard agent: a violet speech-bubble robot with a dark visor, glowing cyan eyes, rosy
 * cheeks and a little antenna whose tip glows. It bobs and blinks while attached to a window; each mood
 * has its own face. In [Mood.LISTEN] the eyes become a sound meter that follows the live mic [energy]
 * and a halo breathes around it; in [Mood.WORK] the eyes become loading dots.
 */
class BotView(context: Context) : View(context) {
    enum class Mood { HELLO, IDLE, LISTEN, WORK, COOL }

    var mood = Mood.IDLE
        set(v) { field = v; invalidate() }

    /** Live microphone loudness, 0 (silence) to 1 (loud). Drives the listening meter and halo. */
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

    private val cyan = Color.parseColor("#67E8F9")
    private val cyanDeep = Color.parseColor("#22D3EE")

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

    /** A glowing cyan shape: a soft blurred copy under a crisp one. */
    private fun glowing(c: Canvas, draw: (Paint) -> Unit) {
        paint.shader = null
        paint.color = cyanDeep
        paint.alpha = 90
        paint.maskFilter = glow
        draw(paint)
        paint.maskFilter = null
        paint.alpha = 255
        paint.color = cyan
        draw(paint)
    }

    private val glow = android.graphics.BlurMaskFilter(5f, android.graphics.BlurMaskFilter.Blur.NORMAL)

    override fun onDraw(c: Canvas) {
        // Drawn on a 200-unit design grid: the figure spans about 140 x 170 units around (100, 97).
        val k = minOf(width, height) / 180f
        if (k <= 0f) return
        val pulse = 0.5f + 0.5f * Math.sin(phase * 2 * Math.PI).toFloat()
        val bob = Math.sin(phase * 2 * Math.PI).toFloat() * 3f
        c.save()
        c.translate(width / 2f - 100f * k, height / 2f - 97f * k)
        c.scale(k, k)

        // Ground shadow, shrinking a little as the bot rises.
        paint.shader = null
        paint.color = Color.argb(34, 49, 46, 129)
        c.drawOval(RectF(52f + bob, 176f, 148f - bob, 188f), paint)

        c.translate(0f, bob)

        // Listening halo: a breathing ring that swells with the live mic level.
        if (mood == Mood.LISTEN) {
            val grow = 4f + 14f * pulse * (0.4f + energy)
            stroke.color = Color.argb((70 + 150 * energy).toInt().coerceIn(0, 255), 34, 211, 238)
            stroke.strokeWidth = 4f
            c.drawCircle(100f, 98f, 84f + grow, stroke)
            stroke.color = Color.argb((30 + 80 * energy).toInt().coerceIn(0, 255), 34, 211, 238)
            c.drawCircle(100f, 98f, 96f + grow * 1.4f, stroke)
        }

        // Antenna with a glowing tip.
        stroke.color = Color.parseColor("#7C3AED")
        stroke.strokeWidth = 5f
        c.drawLine(100f, 44f, 100f, 24f, stroke)
        val tipGlow = if (mood == Mood.LISTEN || mood == Mood.WORK) 0.6f + 0.4f * pulse else 0.35f + 0.25f * pulse
        paint.color = Color.argb((tipGlow * 120).toInt(), 250, 204, 21)
        c.drawCircle(100f, 20f, 13f, paint)
        paint.shader = RadialGradient(97f, 17f, 9f, Color.parseColor("#FEF9C3"), Color.parseColor("#FACC15"), Shader.TileMode.CLAMP)
        c.drawCircle(100f, 20f, 8f, paint)
        paint.shader = null

        // Speech-bubble body with a tail at the bottom left.
        val body = Path().apply {
            moveTo(76f, 40f)
            lineTo(124f, 40f)
            arcTo(RectF(78f, 40f, 170f, 132f), 270f, 90f)
            lineTo(170f, 112f)
            arcTo(RectF(78f, 66f, 170f, 158f), 0f, 90f)
            lineTo(86f, 158f)
            lineTo(62f, 176f)
            quadTo(59f, 167f, 64f, 155f)
            arcTo(RectF(30f, 66f, 122f, 158f), 108f, 72f)
            lineTo(30f, 86f)
            arcTo(RectF(30f, 40f, 122f, 132f), 180f, 90f)
            close()
        }
        paint.shader = LinearGradient(30f, 40f, 170f, 176f,
            intArrayOf(Color.parseColor("#A78BFA"), Color.parseColor("#7C3AED"), Color.parseColor("#4F46E5")), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(body, paint)
        paint.shader = null

        // Gloss on the top left of the bubble.
        paint.color = Color.argb(90, 255, 255, 255)
        c.save(); c.rotate(-18f, 70f, 54f)
        c.drawOval(RectF(44f, 46f, 96f, 62f), paint); c.restore()

        // The visor, with a faint reflection band.
        paint.shader = LinearGradient(0f, 64f, 0f, 128f, Color.parseColor("#2E2A6B"), Color.parseColor("#151236"), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(46f, 64f, 154f, 128f), 32f, 32f, paint)
        paint.shader = null
        paint.color = Color.argb(18, 255, 255, 255)
        c.drawRoundRect(RectF(52f, 67f, 148f, 77f), 5f, 5f, paint)

        // Rosy cheeks.
        paint.color = Color.argb(140, 249, 168, 212)
        c.drawOval(RectF(51f, 114f, 65f, 122f), paint)
        c.drawOval(RectF(135f, 114f, 149f, 122f), paint)

        val blink = if (phase > 0.46f && phase < 0.51f) 0.12f else 1f
        fun pillEyes(open: Float) = glowing(c) { pt ->
            val h = 28f * open
            c.drawRoundRect(RectF(70f, 94f - h / 2, 88f, 94f + h / 2), 9f, 9f, pt)
            c.drawRoundRect(RectF(112f, 94f - h / 2, 130f, 94f + h / 2), 9f, 9f, pt)
        }
        fun smile() {
            stroke.color = cyan
            stroke.strokeWidth = 4f
            c.drawArc(RectF(92f, 106f, 108f, 120f), 25f, 130f, false, stroke)
        }

        when (mood) {
            Mood.HELLO -> {
                // Happy closed eyes and a big smile, with a twinkling sparkle.
                glowing(c) { pt ->
                    stroke.color = pt.color
                    stroke.alpha = pt.alpha
                    stroke.maskFilter = pt.maskFilter
                    stroke.strokeWidth = 7f
                    c.drawArc(RectF(68f, 86f, 90f, 108f), 200f, 140f, false, stroke)
                    c.drawArc(RectF(110f, 86f, 132f, 108f), 200f, 140f, false, stroke)
                    stroke.maskFilter = null
                    stroke.alpha = 255
                }
                smile()
                star(c, 168f, 42f, 13f * (1f + 0.25f * Math.sin(phase * 4 * Math.PI).toFloat()), Color.parseColor("#FDE047"))
            }
            Mood.IDLE -> {
                pillEyes(blink)
                smile()
            }
            Mood.LISTEN -> {
                // The eyes become a five-bar sound meter that dances with the voice.
                glowing(c) { pt ->
                    for (i in 0..4) {
                        val base = floatArrayOf(12f, 26f, 38f, 26f, 12f)[i]
                        val wave = 0.55f + 0.45f * Math.sin(phase * 6 * Math.PI + i * 1.3).toFloat()
                        val h = (base * (0.45f + 0.55f * energy) * (0.6f + 0.4f * wave)).coerceAtLeast(8f)
                        val x = 66f + i * 14f
                        c.drawRoundRect(RectF(x, 96f - h / 2, x + 8f, 96f + h / 2), 4f, 4f, pt)
                    }
                }
            }
            Mood.WORK -> {
                // Three loading dots taking turns to glow.
                for (i in 0..2) {
                    val t = ((phase * 3f - i) % 3f + 3f) % 3f
                    val lit = if (t < 1f) 1f - kotlin.math.abs(t - 0.5f) * 2f else 0f
                    paint.color = cyan
                    paint.alpha = (110 + 145 * lit).toInt()
                    c.drawCircle(78f + i * 22f, 96f - 4f * lit, 6f + 2f * lit, paint)
                }
                paint.alpha = 255
            }
            Mood.COOL -> {
                // Shades on: two dark lenses across the visor with a glint, and a sideways smirk.
                paint.color = Color.parseColor("#0B0A1F")
                c.drawRoundRect(RectF(62f, 82f, 96f, 104f), 10f, 10f, paint)
                c.drawRoundRect(RectF(104f, 82f, 138f, 104f), 10f, 10f, paint)
                stroke.color = cyan
                stroke.strokeWidth = 3f
                c.drawLine(96f, 88f, 104f, 88f, stroke)
                stroke.color = Color.argb(150, 255, 255, 255)
                c.drawLine(68f, 98f, 76f, 88f, stroke)
                c.drawLine(110f, 98f, 118f, 88f, stroke)
                stroke.color = cyan
                stroke.strokeWidth = 4f
                c.drawArc(RectF(96f, 106f, 114f, 120f), 20f, 110f, false, stroke)
                star(c, 34f, 44f, 11f * (1f + 0.25f * Math.sin(phase * 4 * Math.PI).toFloat()), Color.parseColor("#4ADE80"))
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