package app.verbo.keyboard

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Shared look for the settings screens: deep navy background, bordered cards, purple accents. */
class Ui(val a: Activity) {
    val bg = Color.parseColor("#0C0B1C")
    val card = Color.parseColor("#16152A")
    val line = Color.parseColor("#2B2947")
    val ink = Color.WHITE
    val muted = Color.parseColor("#A9A7C4")
    val accent = Color.parseColor("#7C5CFF")
    val accentSoft = Color.parseColor("#2A2158")
    val iconTint = Color.parseColor("#9B82FF")

    fun dp(v: Int) = (v * a.resources.displayMetrics.density + 0.5f).toInt()

    fun shape(fill: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(strokeWidth), stroke)
    }

    fun ripple(content: android.graphics.drawable.Drawable?, radius: Int = 14) =
        RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), content, shape(Color.WHITE, radius))

    /** Page scaffold: a scroll view with a soft purple glow behind the top. Returns the column to fill. */
    fun page(): LinearLayout {
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(36))
        }
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(300).toFloat()
            setGradientCenter(0.75f, 0f)
            colors = intArrayOf(Color.parseColor("#55402C96"), Color.TRANSPARENT)
        }
        val scroll = ScrollView(a).apply {
            fitsSystemWindows = true
            background = LayerDrawable(arrayOf(shape(bg, 0), glow))
            addView(column)
        }
        a.setContentView(scroll)
        return column
    }

    fun text(s: String, sizeSp: Float, color: Int = ink, bold: Boolean = false) = TextView(a).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun icon(res: Int, sizeDp: Int = 24, tint: Int = iconTint) = ImageView(a).apply {
        setImageResource(res)
        setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Sub-page header: back arrow and title. */
    fun topBar(title: String, subtitle: String? = null): LinearLayout {
        val bar = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(10)) }
        bar.addView(icon(R.drawable.ic_arrow_left, 26, ink).apply {
            background = ripple(null, 20)
            contentDescription = "Back"
            setOnClickListener { a.finish() }
        })
        val titles = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        titles.addView(text(title, 22f, bold = true))
        if (subtitle != null) titles.addView(text(subtitle, 14f, muted))
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        return bar
    }

    fun section(iconRes: Int, title: String) = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(22), 0, dp(10))
        addView(icon(iconRes, 24))
        addView(text(title, 19f, bold = true).apply { setPadding(dp(12), 0, 0, 0) })
    }

    fun cardBox() = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(card, 14, line)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    fun toggle(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(a).apply {
        isChecked = checked
        // Drawn by hand so the "on" state is a solid purple pill with a white knob, as in the design.
        fun track(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat(); setSize(dp(50), dp(28)) }
        trackDrawable = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), track(accent))
            addState(intArrayOf(), track(Color.parseColor("#3A3856")))
        }
        thumbDrawable = android.graphics.drawable.InsetDrawable(
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize(dp(22), dp(22)) }, dp(3))
        trackTintList = null
        thumbTintList = null
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    fun chevron() = icon(R.drawable.ic_chevron_right, 20, muted)

    /** One settings row: icon, title, optional subtitle, and something on the right. */
    fun row(iconRes: Int, title: String, subtitle: String? = null, trailing: View? = null, tile: Boolean = false, onTap: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(if (subtitle == null) 15 else 12), dp(14), dp(if (subtitle == null) 15 else 12))
            minimumHeight = dp(56)
        }
        if (tile) {
            row.addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER
                background = shape(accentSoft, 12)
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
                addView(icon(iconRes, 26))
            })
        } else {
            row.addView(icon(iconRes, 24))
        }
        val titles = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(8), 0) }
        titles.addView(text(title, 16.5f, bold = true))
        if (subtitle != null) titles.addView(text(subtitle, 13.5f, muted))
        row.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) row.addView(trailing)
        if (onTap != null) {
            row.background = ripple(null)
            row.setOnClickListener { onTap() }
        }
        return row
    }

    fun divider() = View(a).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(56) }
    }

    /** A card holding several rows separated by hairlines. */
    fun rows(vararg items: View) = cardBox().apply {
        items.forEachIndexed { i, v -> if (i > 0) addView(divider()); addView(v) }
    }

    fun button(label: String, iconRes: Int? = null, filled: Boolean = false, onTap: () -> Unit) = LinearLayout(a).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(13), dp(18), dp(13))
        background = ripple(if (filled) shape(accent, 12) else shape(card, 12, Color.parseColor("#4A4770")), 12)
        if (iconRes != null) addView(icon(iconRes, 20, ink).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(10) })
        addView(text(label, 15.5f, bold = true))
        setOnClickListener { onTap() }
    }

    fun field(hint: String, value: String = "", secret: Boolean = false, lines: Int = 1) = EditText(a).apply {
        this.hint = hint
        setText(value)
        setTextColor(ink)
        setHintTextColor(Color.parseColor("#7C7A99"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4A4770"))
        if (lines > 1) {
            minLines = lines
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        } else {
            setSingleLine()
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
    }

    fun pad(v: View, left: Int = 16, top: Int = 0, right: Int = 16, bottom: Int = 0) = v.apply { setPadding(dp(left), dp(top), dp(right), dp(bottom)) }

    fun toast(message: String) = Toast.makeText(a, message, Toast.LENGTH_SHORT).show()
}
