package app.verbo.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/** The little keyboard picture inside the wheel page's "How it works" card: strip, keys and a frosted space bar. */
class KeyboardPreview(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun box(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        paint.shader = null
        paint.color = color
        c.drawRoundRect(RectF(l, t, r, b), radius, radius, paint)
    }

    private fun glyph(c: Canvas, res: Int, cx: Float, cy: Float, size: Float, tint: Int) {
        context.getDrawable(res)?.mutate()?.apply {
            setTint(tint)
            val s = size.toInt()
            setBounds((cx - s / 2f).toInt(), (cy - s / 2f).toInt(), (cx + s / 2f).toInt(), (cy + s / 2f).toInt())
            draw(c)
        }
    }

    private fun label(c: Canvas, s: String, cx: Float, cy: Float, sp: Float, color: Int, bold: Boolean = false) {
        text.color = color
        text.textSize = sp * d
        text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif", Typeface.NORMAL)
        c.drawText(s, cx, cy - (text.descent() + text.ascent()) / 2, text)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val ink = Color.parseColor("#3C3C43")
        val grey = Color.parseColor("#8E8E98")
        box(c, 0f, 0f, w, h, 12f * d, Color.parseColor("#E3E3E9"))
        // The strip: Type / Act on the left, clipboard and Voice on the right.
        val sy = 8f * d
        val sh = 26f * d
        box(c, 8f * d, sy, 126f * d, sy + sh, 13f * d, Color.WHITE)
        glyph(c, R.drawable.ic_pencil_line, 22f * d, sy + sh / 2, 12f * d, ink)
        label(c, "Type", 45f * d, sy + sh / 2, 11f, ink, true)
        glyph(c, R.drawable.ic_sparkle, 76f * d, sy + sh / 2, 12f * d, grey)
        label(c, "Act", 98f * d, sy + sh / 2, 11f, grey)
        glyph(c, R.drawable.ic_clipboard, w - 76f * d, sy + sh / 2, 14f * d, grey)
        box(c, w - 64f * d, sy, w - 8f * d, sy + sh, 13f * d, Color.WHITE)
        glyph(c, R.drawable.ic_mic, w - 50f * d, sy + sh / 2, 12f * d, ink)
        label(c, "Voice", w - 27f * d, sy + sh / 2, 11f, ink, true)
        // Four rows of keys.
        val top = sy + sh + 8f * d
        val gap = 4f * d
        val rowH = (h - 8f * d - top - 3 * gap) / 4f
        fun key(l: Float, t: Float, r: Float, b: Float, letter: String) {
            box(c, l, t, r, b, 6f * d, Color.WHITE)
            label(c, letter, (l + r) / 2, (t + b) / 2, 12f, ink)
        }
        fun row(t: Float, letters: String, inset: Float, wide: Float, pitch: Float = 0f) {
            val items = letters.length + if (wide > 0f) 2 else 0
            val widths = letters.length + 2 * wide
            val kw = if (pitch > 0f) pitch else (w - 16f * d - (items - 1) * gap) / widths
            var x = 8f * d + inset
            if (wide > 0f) {
                box(c, x, t, x + kw * wide, t + rowH, 6f * d, Color.WHITE)
                glyph(c, R.drawable.ic_arrow_big_up, x + kw * wide / 2, t + rowH / 2, 15f * d, ink)
                x += kw * wide + gap
            }
            letters.forEach { ch ->
                key(x, t, x + kw, t + rowH, ch.toString())
                x += kw + gap
            }
            if (wide > 0f) {
                box(c, x, t, x + kw * wide, t + rowH, 6f * d, Color.WHITE)
                glyph(c, R.drawable.ic_delete, x + kw * wide / 2, t + rowH / 2, 16f * d, ink)
            }
        }
        val kw = (w - 16f * d - 9 * gap) / 10f
        var t = top
        row(t, "qwertyuiop", 0f, 0f)
        t += rowH + gap
        row(t, "asdfghjkl", (w - 16f * d - 9 * kw - 8 * gap) / 2f, 0f, kw)
        t += rowH + gap
        row(t, "zxcvbnm", (w - 16f * d - 10 * kw - 8 * gap) / 2f, 1.5f, kw)
        t += rowH + gap
        // Bottom row: ABC, the frosted space bar, enter.
        val bw = (w - 16f * d - 2 * gap) / 4.6f
        box(c, 8f * d, t, 8f * d + bw, t + rowH, 6f * d, Color.WHITE)
        label(c, "ABC", 8f * d + bw / 2, t + rowH / 2, 11f, ink, true)
        val sl = 8f * d + bw + gap
        val sr = w - 8f * d - bw - gap
        box(c, sl, t, sr, t + rowH, 6f * d, Color.parseColor("#D9D9E0"))
        paint.shader = LinearGradient(sl, t, sr, t, intArrayOf(Color.parseColor("#66FFFFFF"), Color.parseColor("#00FFFFFF"), Color.parseColor("#4DFFFFFF")), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(sl, t, sr, t + rowH), 6f * d, 6f * d, paint)
        paint.shader = null
        label(c, "en", sr - 12f * d, t + rowH / 2, 9f, grey)
        box(c, sr + gap, t, w - 8f * d, t + rowH, 6f * d, Color.WHITE)
        glyph(c, R.drawable.ic_corner_down_left, sr + gap + bw / 2, t + rowH / 2, 14f * d, ink)
    }
}