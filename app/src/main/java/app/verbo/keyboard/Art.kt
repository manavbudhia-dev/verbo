package app.verbo.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Card artwork that belongs to the skill itself: its own two-hue gradient with the skill's icon drawn large and soft. */
class SkillArt(a: Context, val iconRes: Int, from: Int, to: Int) : Drawable() {
    private val fromColor = from
    private val toColor = to
    private val glyph = a.getDrawable(iconRes)?.mutate()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0 || h <= 0) return
        paint.shader = LinearGradient(0f, 0f, w, h, fromColor, toColor, Shader.TileMode.CLAMP)
        c.drawRect(b, paint)
        paint.shader = null
        paint.color = Color.parseColor("#1FFFFFFF")
        c.drawCircle(w * 0.86f, h * 0.16f, w * 0.34f, paint)
        paint.color = Color.parseColor("#14FFFFFF")
        c.drawCircle(w * 0.10f, h * 0.72f, w * 0.26f, paint)
        paint.color = Color.parseColor("#26FFFFFF")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w * 0.045f
        c.drawCircle(w * 0.72f, h * 0.52f, w * 0.30f, paint)
        paint.style = Paint.Style.FILL
        glyph?.let {
            val size = (w * 0.46f).toInt()
            val left = ((w - size) / 2).toInt()
            val top = (h * 0.40f - size / 2).toInt()
            it.setTint(Color.parseColor("#66FFFFFF"))
            it.setBounds(left, top, left + size, top + size)
            it.draw(c)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** Which artwork each skill and each auto-action kind gets. */
object Art {
    fun skill(a: Context, name: String, category: String): SkillArt = when (name) {
        "Polish" -> SkillArt(a, R.drawable.ic_sparkles, Color.parseColor("#8B5CF6"), Color.parseColor("#6D28D9"))
        "Reply" -> SkillArt(a, R.drawable.ic_corner_up_left, Color.parseColor("#38BDF8"), Color.parseColor("#2563EB"))
        "Shorter" -> SkillArt(a, R.drawable.ic_scissors, Color.parseColor("#F472B6"), Color.parseColor("#DB2777"))
        "Formal" -> SkillArt(a, R.drawable.ic_briefcase, Color.parseColor("#FBBF24"), Color.parseColor("#D97706"))
        "Casual" -> SkillArt(a, R.drawable.ic_coffee, Color.parseColor("#34D399"), Color.parseColor("#059669"))
        "English" -> SkillArt(a, R.drawable.ic_globe, Color.parseColor("#22D3EE"), Color.parseColor("#0891B2"))
        "Summarize" -> SkillArt(a, R.drawable.ic_file_text, Color.parseColor("#A78BFA"), Color.parseColor("#7C3AED"))
        "Look up" -> SkillArt(a, R.drawable.ic_search, Color.parseColor("#60A5FA"), Color.parseColor("#4F46E5"))
        "Place" -> SkillArt(a, R.drawable.ic_globe, Color.parseColor("#2DD4BF"), Color.parseColor("#0D9488"))
        "Who is" -> SkillArt(a, R.drawable.ic_users, Color.parseColor("#FB7185"), Color.parseColor("#E11D48"))
        "Notion" -> SkillArt(a, R.drawable.ic_file_text, Color.parseColor("#94A3B8"), Color.parseColor("#475569"))
        "Email" -> SkillArt(a, R.drawable.ic_message_square, Color.parseColor("#38BDF8"), Color.parseColor("#0284C7"))
        "LinkedIn post" -> SkillArt(a, R.drawable.ic_briefcase, Color.parseColor("#60A5FA"), Color.parseColor("#1D4ED8"))
        "List" -> SkillArt(a, R.drawable.ic_list, Color.parseColor("#4ADE80"), Color.parseColor("#16A34A"))
        else -> when (category) {
            "translate" -> SkillArt(a, R.drawable.ic_globe, Color.parseColor("#22D3EE"), Color.parseColor("#0891B2"))
            "summarize" -> SkillArt(a, R.drawable.ic_file_text, Color.parseColor("#A78BFA"), Color.parseColor("#7C3AED"))
            "write" -> SkillArt(a, R.drawable.ic_zap, Color.parseColor("#C084FC"), Color.parseColor("#9333EA"))
            else -> SkillArt(a, R.drawable.ic_sparkles, Color.parseColor("#F472B6"), Color.parseColor("#DB2777"))
        }
    }

    fun kind(a: Context, kind: String): SkillArt = when (kind) {
        "person" -> SkillArt(a, R.drawable.ic_users, Color.parseColor("#FB7185"), Color.parseColor("#E11D48"))
        "time" -> SkillArt(a, R.drawable.ic_sun, Color.parseColor("#FBBF24"), Color.parseColor("#D97706"))
        "place" -> SkillArt(a, R.drawable.ic_globe, Color.parseColor("#2DD4BF"), Color.parseColor("#0D9488"))
        "link" -> SkillArt(a, R.drawable.ic_link, Color.parseColor("#38BDF8"), Color.parseColor("#2563EB"))
        "email" -> SkillArt(a, R.drawable.ic_message_square, Color.parseColor("#22D3EE"), Color.parseColor("#0891B2"))
        "phone" -> SkillArt(a, R.drawable.ic_message_square, Color.parseColor("#34D399"), Color.parseColor("#059669"))
        "keyword" -> SkillArt(a, R.drawable.ic_tag, Color.parseColor("#818CF8"), Color.parseColor("#4F46E5"))
        "foreign" -> SkillArt(a, R.drawable.ic_zap, Color.parseColor("#A78BFA"), Color.parseColor("#7C3AED"))
        else -> SkillArt(a, R.drawable.ic_sparkles, Color.parseColor("#C084FC"), Color.parseColor("#9333EA"))
    }
}