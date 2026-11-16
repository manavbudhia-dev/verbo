package app.verbo.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Small flat flags for the language wheel, drawn with plain shapes so no image assets are needed. */
object Flags {
    fun draw(c: Canvas, language: String, l: Float, t: Float, w: Float, h: Float, p: Paint) {
        val save = c.save()
        c.clipPath(Path().apply { addRoundRect(RectF(l, t, l + w, t + h), h * 0.14f, h * 0.14f, Path.Direction.CW) })
        val r = RectF(l, t, l + w, t + h)
        fun fill(color: String, rect: RectF = r) {
            p.color = android.graphics.Color.parseColor(color)
            c.drawRect(rect, p)
        }
        fun bandsH(vararg colors: String) {
            val bh = h / colors.size
            colors.forEachIndexed { i, color -> fill(color, RectF(l, t + i * bh, l + w, t + (i + 1) * bh + 0.5f)) }
        }
        fun bandsV(vararg colors: String) {
            val bw = w / colors.size
            colors.forEachIndexed { i, color -> fill(color, RectF(l + i * bw, t, l + (i + 1) * bw + 0.5f, t + h)) }
        }
        fun circle(color: String, cx: Float, cy: Float, rad: Float) {
            p.color = android.graphics.Color.parseColor(color)
            c.drawCircle(cx, cy, rad, p)
        }
        fun star(color: String, cx: Float, cy: Float, rad: Float) {
            val path = Path()
            for (i in 0 until 10) {
                val rr = if (i % 2 == 0) rad else rad * 0.42f
                val a = Math.toRadians((-90 + i * 36).toDouble())
                val x = cx + (rr * kotlin.math.cos(a)).toFloat()
                val y = cy + (rr * kotlin.math.sin(a)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            p.color = android.graphics.Color.parseColor(color)
            c.drawPath(path, p)
        }
        fun crescent(color: String, cx: Float, cy: Float, rad: Float, field: String) {
            circle(color, cx, cy, rad)
            circle(field, cx + rad * 0.38f, cy - rad * 0.1f, rad * 0.82f)
        }
        val cx = l + w / 2
        val cy = t + h / 2
        when (language) {
            "English" -> {
                fill("#1F3B8C")
                p.color = android.graphics.Color.WHITE
                p.strokeWidth = h * 0.3f
                c.drawLine(l, t, l + w, t + h, p)
                c.drawLine(l, t + h, l + w, t, p)
                p.strokeWidth = h * 0.12f
                p.color = android.graphics.Color.parseColor("#D22B2B")
                c.drawLine(l, t, l + w, t + h, p)
                c.drawLine(l, t + h, l + w, t, p)
                p.strokeWidth = 0f
                fill("#FFFFFF", RectF(cx - w * 0.1f, t, cx + w * 0.1f, t + h))
                fill("#FFFFFF", RectF(l, cy - h * 0.17f, l + w, cy + h * 0.17f))
                fill("#D22B2B", RectF(cx - w * 0.06f, t, cx + w * 0.06f, t + h))
                fill("#D22B2B", RectF(l, cy - h * 0.1f, l + w, cy + h * 0.1f))
            }
            "Hindi", "Marathi", "Tamil", "Telugu", "Gujarati", "Punjabi", "Kannada", "Malayalam" -> {
                bandsH("#FF9933", "#FFFFFF", "#138808")
                p.color = android.graphics.Color.parseColor("#000080")
                p.style = Paint.Style.STROKE
                p.strokeWidth = h * 0.05f
                c.drawCircle(cx, cy, h * 0.14f, p)
                p.style = Paint.Style.FILL
                p.strokeWidth = 0f
            }
            "Spanish" -> {
                fill("#AA151B")
                fill("#F1BF00", RectF(l, t + h * 0.25f, l + w, t + h * 0.75f))
            }
            "French" -> bandsV("#0055A4", "#FFFFFF", "#EF4135")
            "Chinese (Simplified)" -> {
                fill("#DE2910")
                star("#FFDE00", l + w * 0.22f, t + h * 0.3f, h * 0.2f)
            }
            "Arabic" -> {
                fill("#165d31")
                fill("#FFFFFF", RectF(l + w * 0.15f, cy - h * 0.08f, l + w * 0.85f, cy + h * 0.02f))
                fill("#FFFFFF", RectF(l + w * 0.2f, t + h * 0.22f, l + w * 0.8f, t + h * 0.32f))
            }
            "Bengali" -> {
                fill("#006A4E")
                circle("#F42A41", cx - w * 0.06f, cy, h * 0.3f)
            }
            "Portuguese" -> {
                fill("#009C3B")
                val diamond = Path().apply {
                    moveTo(cx, t + h * 0.12f)
                    lineTo(l + w * 0.88f, cy)
                    lineTo(cx, t + h * 0.88f)
                    lineTo(l + w * 0.12f, cy)
                    close()
                }
                p.color = android.graphics.Color.parseColor("#FFDF00")
                c.drawPath(diamond, p)
                circle("#002776", cx, cy, h * 0.22f)
            }
            "Russian" -> bandsH("#FFFFFF", "#0039A6", "#D52B1E")
            "Japanese" -> {
                fill("#FFFFFF")
                circle("#BC002D", cx, cy, h * 0.3f)
            }
            "German" -> bandsH("#000000", "#DD0000", "#FFCE00")
            "Korean" -> {
                fill("#FFFFFF")
                circle("#CD2E3A", cx, cy, h * 0.26f)
                p.color = android.graphics.Color.parseColor("#0047A0")
                c.drawArc(RectF(cx - h * 0.26f, cy - h * 0.26f, cx + h * 0.26f, cy + h * 0.26f), 0f, 180f, true, p)
            }
            "Italian" -> bandsV("#009246", "#FFFFFF", "#CE2B37")
            "Turkish" -> {
                fill("#E30A17")
                crescent("#FFFFFF", cx - w * 0.06f, cy, h * 0.28f, "#E30A17")
                star("#FFFFFF", cx + w * 0.2f, cy, h * 0.12f)
            }
            "Indonesian" -> bandsH("#CE1126", "#FFFFFF")
            "Urdu" -> {
                fill("#01411C")
                fill("#FFFFFF", RectF(l, t, l + w * 0.25f, t + h))
                crescent("#FFFFFF", cx + w * 0.1f, cy, h * 0.26f, "#01411C")
                star("#FFFFFF", cx + w * 0.24f, cy - h * 0.16f, h * 0.1f)
            }
            "Vietnamese" -> {
                fill("#DA251D")
                star("#FFFF00", cx, cy, h * 0.3f)
            }
            "Thai" -> bandsH("#A51931", "#F4F5F8", "#2D2A4A", "#2D2A4A", "#F4F5F8", "#A51931")
            "Dutch" -> bandsH("#AE1C28", "#FFFFFF", "#21468B")
            "Polish" -> bandsH("#FFFFFF", "#DC143C")
            "Swahili" -> {
                bandsH("#000000", "#FFFFFF", "#BB0000", "#FFFFFF", "#006600")
                p.color = android.graphics.Color.parseColor("#BB0000")
                c.drawOval(RectF(cx - w * 0.08f, cy - h * 0.3f, cx + w * 0.08f, cy + h * 0.3f), p)
            }
            else -> {
                fill("#DCE6F2")
                circle("#FFFFFF", cx, cy, h * 0.3f)
                p.color = android.graphics.Color.parseColor("#9FB6CE")
                p.style = Paint.Style.STROKE
                p.strokeWidth = h * 0.05f
                c.drawCircle(cx, cy, h * 0.3f, p)
                c.drawLine(cx - h * 0.3f, cy, cx + h * 0.3f, cy, p)
                p.style = Paint.Style.FILL
                p.strokeWidth = 0f
            }
        }
        c.restoreToCount(save)
    }
}