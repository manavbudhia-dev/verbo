package app.verbo.keyboard

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The light look of the redesigned screens: soft lavender paper, white cards, pastel icon tiles, violet accents. */
class Look(val a: Activity) {
    val bg = Color.parseColor("#F5F4FA")
    val card = Color.WHITE
    val ink = Color.parseColor("#16161D")
    val violetInk = Color.parseColor("#4B4569")
    val muted = Color.parseColor("#7A7A88")
    val faint = Color.parseColor("#9A9AA6")
    val line = Color.parseColor("#ECECF2")
    val edge = Color.parseColor("#E7E7F0")
    val accent = Color.parseColor("#7C3AED")
    val accentSoft = Color.parseColor("#EFEBFD")
    val dark = Color.parseColor("#17171F")
    val green = Color.parseColor("#16A34A")
    val greenSoft = Color.parseColor("#D9F3E0")
    val purpleBg = Color.parseColor("#EFEAFE")
    val purpleTint = Color.parseColor("#8B5CF6")
    val pinkBg = Color.parseColor("#FDEAF3")
    val pinkTint = Color.parseColor("#EC4899")
    val amberBg = Color.parseColor("#FEF3D9")
    val amberTint = Color.parseColor("#F59E0B")
    val blueBg = Color.parseColor("#E0F2FE")
    val blueTint = Color.parseColor("#0EA5E9")
    val orangeBg = Color.parseColor("#FFEDE6")
    val orangeTint = Color.parseColor("#F97316")
    val greenBg = Color.parseColor("#E6F7EC")
    val greenTint = Color.parseColor("#22C55E")
    val indigoBg = Color.parseColor("#E8ECFD")
    val indigoTint = Color.parseColor("#6366F1")

    fun dp(v: Int) = (v * a.resources.displayMetrics.density + 0.5f).toInt()
    fun dp(v: Float) = (v * a.resources.displayMetrics.density + 0.5f).toInt()

    fun shape(fill: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(strokeWidth), stroke)
    }

    fun ripple(content: Drawable?, radius: Int = 16) =
        RippleDrawable(ColorStateList.valueOf(Color.parseColor("#14000000")), content, shape(Color.WHITE, radius))

    /** Paper background with two soft lavender bands curving in from the right, as in the design. */
    private inner class Waves : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val b = bounds
            paint.color = bg
            c.drawRect(b, paint)
            paint.color = Color.parseColor("#E7E4F6")
            c.drawCircle(b.width() * 1.12f, b.height() * 0.10f, b.width() * 0.72f, paint)
            c.drawCircle(b.width() * 1.16f, b.height() * 0.97f, b.width() * 0.78f, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(filter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Page scaffold: scrolling column on the lavender paper; [nav] floats at the bottom. Returns the column. */
    fun page(nav: View? = null): LinearLayout {
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(if (nav == null) 36 else 110))
        }
        val scroll = ScrollView(a).apply {
            isVerticalScrollBarEnabled = false
            background = Waves()
            addView(column)
        }
        if (nav == null) a.setContentView(scroll)
        else a.setContentView(FrameLayout(a).apply {
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
            addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(18) })
        })
        return column
    }

    fun text(s: String, sizeSp: Float, color: Int = ink, bold: Boolean = false) = TextView(a).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun icon(res: Int, sizeDp: Int = 24, tint: Int = muted) = ImageView(a).apply {
        setImageResource(res)
        setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Big page title with a muted line under it and an optional button at the top right. */
    fun header(title: String, subtitle: String, trailing: View? = null): LinearLayout {
        val bar = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(10), 0, dp(18)) }
        val titles = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text(title, 33f, bold = true))
        titles.addView(text(subtitle, 14.5f, muted).apply { setPadding(0, dp(3), 0, 0) })
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) bar.addView(trailing)
        return bar
    }

    fun circleButton(res: Int, tint: Int = ink, sizeDp: Int = 44, onTap: () -> Unit) = ImageView(a).apply {
        setImageResource(res)
        setColorFilter(tint)
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = ripple(shape(card, 22, line), 22)
        contentDescription = "More"
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** The gradient V mark on a white tile. */
    inner class Logo(sizeDp: Int) : View(a) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val box = RectF()
        private val path = Path()
        init { layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)) }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.shader = null
            paint.color = Color.WHITE
            box.set(0f, 0f, w, h)
            c.drawRoundRect(box, w * 0.24f, w * 0.24f, paint.apply { style = Paint.Style.FILL })
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w * 0.17f
            paint.shader = LinearGradient(0f, 0f, w, h, Color.parseColor("#8B5CF6"), Color.parseColor("#3B82F6"), Shader.TileMode.CLAMP)
            path.reset()
            path.moveTo(w * 0.30f, h * 0.28f)
            path.lineTo(w * 0.50f, h * 0.74f)
            path.lineTo(w * 0.70f, h * 0.28f)
            c.drawPath(path, paint)
        }
    }

    /** The app card's paper: white with a lavender wave rising from the right, clipped to the card radius. */
    private inner class CardWaves : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        override fun draw(c: Canvas) {
            val b = bounds
            val r = dp(24).toFloat()
            path.reset()
            path.addRoundRect(RectF(b), r, r, Path.Direction.CW)
            c.clipPath(path)
            paint.color = card
            c.drawRect(b, paint)
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            paint.color = Color.parseColor("#DCE6F8")
            c.drawCircle(w * 1.10f, h * 0.05f, w * 0.34f, paint)
            paint.color = Color.parseColor("#E7E4F6")
            path.reset()
            path.moveTo(w * 0.40f, h)
            path.cubicTo(w * 0.62f, h * 0.98f, w * 0.66f, h * 0.55f, w * 0.86f, h * 0.42f)
            path.cubicTo(w * 0.95f, h * 0.36f, w, h * 0.34f, w, h * 0.34f)
            path.lineTo(w, h)
            path.close()
            c.drawPath(path, paint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(filter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** White app card: logo, name and version, and the green "Up to date" pill. */
    fun appCard(version: String): LinearLayout {
        val row = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = CardWaves()
            setPadding(dp(16), dp(16), dp(16), dp(16))
            elevation = dp(2).toFloat()
        }
        row.addView(Logo(56))
        row.addView(View(a).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(dp(1), dp(38)).apply { setMargins(dp(14), 0, dp(14), 0) })
        val names = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text("Verbo Keyboard", 17f, bold = true))
        names.addView(text("v$version", 14f, muted).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        val pill = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = shape(greenSoft, 16)
            setPadding(dp(12), dp(7), dp(14), dp(7))
        }
        pill.addView(icon(R.drawable.ic_check, 15, green))
        pill.addView(text("Up to date", 13f, green, bold = true).apply { setPadding(dp(6), 0, 0, 0) })
        row.addView(pill)
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
        return row
    }

    /** Section heading: tinted icon, bold title, muted line indented under the title. */
    fun sectionHead(iconRes: Int, tint: Int, title: String, subtitle: String): LinearLayout {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(24), 0, dp(14)) }
        val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(icon(iconRes, 23, tint))
        row.addView(text(title, 20f, bold = true).apply { setPadding(dp(11), 0, 0, 0) })
        box.addView(row)
        box.addView(text(subtitle, 14f, muted).apply { setPadding(dp(34), dp(3), 0, 0) })
        return box
    }

    fun iconTile(res: Int, tileBg: Int, tint: Int, sizeDp: Int = 44, iconDp: Int = 22) = LinearLayout(a).apply {
        gravity = Gravity.CENTER
        background = shape(tileBg, 12)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        addView(icon(res, iconDp, tint))
    }

    /** Pastel card with an icon tile, a chevron at the top right, title and subtitle below. */
    fun tileCard(tileBg: Int, tint: Int, iconRes: Int, title: String, subtitle: String, onTap: () -> Unit): LinearLayout {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = ripple(shape(tileBg, 20), 20)
            setPadding(dp(12), dp(12), dp(12), dp(14))
            setOnClickListener { onTap() }
        }
        val top = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(iconTile(iconRes, Color.parseColor("#FFFFFF").let { blend(tileBg, it, 0.55f) }, tint, 42, 21))
        top.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(icon(R.drawable.ic_chevron_right, 18, muted))
        box.addView(top)
        box.addView(text(title, 15f, bold = true).apply { setPadding(0, dp(13), 0, 0) })
        box.addView(text(subtitle, 11f, muted).apply { setPadding(0, dp(3), 0, 0); maxLines = 2 })
        return box
    }

    private fun blend(c1: Int, c2: Int, by: Float) = Color.rgb(
        (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * by).toInt(),
        (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * by).toInt(),
        (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * by).toInt())

    /** One row inside a white card: pastel icon tile, title, subtitle, chevron. [iconText] replaces the icon ("Aa"). */
    fun rowTile(tileBg: Int, tint: Int, iconRes: Int, title: String, subtitle: String, iconText: String? = null, onTap: () -> Unit): LinearLayout {
        val row = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = ripple(null, 18)
            setOnClickListener { onTap() }
        }
        if (iconText == null) row.addView(iconTile(iconRes, tileBg, tint, 42, 21))
        else row.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER
            background = shape(tileBg, 12)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            addView(text(iconText, 16f, tint, bold = true))
        })
        val titles = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), 0, dp(8), 0) }
        titles.addView(text(title, 16.5f, bold = true))
        titles.addView(text(subtitle, 13.5f, muted).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(icon(R.drawable.ic_chevron_right, 20, ink))
        return row
    }

    fun rowsCard(vararg rows: View) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(card, 24)
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        rows.forEachIndexed { i, v ->
            if (i > 0) addView(View(a).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(69) })
            addView(v)
        }
    }

    /** Small white card for the 2x2 grid: icon tile, two lines, chevron. */
    fun gridTile(tileBg: Int, tint: Int, iconRes: Int, title: String, subtitle: String, onTap: () -> Unit): LinearLayout {
        val row = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(shape(card, 20), 20)
            setPadding(dp(8), dp(12), dp(4), dp(12))
            setOnClickListener { onTap() }
        }
        row.addView(iconTile(iconRes, tileBg, tint, 32, 17))
        val titles = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        titles.addView(text(title, 15f, bold = true))
        titles.addView(text(subtitle, 10f, muted).apply { setPadding(0, dp(2), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        row.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(icon(R.drawable.ic_chevron_right, 14, ink))
        return row
    }

    fun pair(first: View, second: View): LinearLayout {
        val row = LinearLayout(a).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } }
        row.addView(first, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(5) })
        row.addView(second, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(5) })
        return row
    }

    fun triple(first: View, second: View, third: View): LinearLayout {
        val row = LinearLayout(a).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } }
        row.addView(first, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(5) })
        row.addView(second, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(5), 0, dp(5), 0) })
        row.addView(third, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(5) })
        return row
    }

    /** White search bar with a filter button at the right. */
    fun searchBar(hint: String, onText: (String) -> Unit, onFilter: () -> Unit): LinearLayout {
        val bar = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = shape(card, 16, edge)
            isFocusableInTouchMode = true
            setPadding(dp(14), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        }
        bar.addView(icon(R.drawable.ic_search, 20, Color.parseColor("#55555F")))
        val field = EditText(a).apply {
            this.hint = hint
            setTextColor(ink)
            setHintTextColor(faint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = null
            setSingleLine()
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = onText(s?.toString() ?: "")
            })
        }
        bar.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(View(a).apply { setBackgroundColor(edge) }, LinearLayout.LayoutParams(dp(1), dp(24)))
        bar.addView(ImageView(a).apply {
            setImageResource(R.drawable.ic_sliders_horizontal)
            setColorFilter(ink)
            setPadding(dp(10), dp(10), dp(6), dp(10))
            contentDescription = "Filter"
            setOnClickListener { onFilter() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        return bar
    }

    /** Filter chips: the chosen one is a dark pill, the rest white. */
    fun chips(labels: List<String>, selected: String, onPick: (String) -> Unit): android.widget.HorizontalScrollView {
        val row = LinearLayout(a)
        labels.forEach { name ->
            val on = name == selected
            row.addView(text(name, 14f, if (on) Color.WHITE else muted, bold = on).apply {
                gravity = Gravity.CENTER
                background = ripple(if (on) shape(dark, 20) else shape(card, 20, edge), 20)
                setPadding(dp(18), dp(9), dp(18), dp(9))
                setOnClickListener { onPick(name) }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        return android.widget.HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        }
    }

    /** Photo card: rounded picture, glass icon and menu buttons on top, name and line over a dark scrim below. */
    fun photoCard(photo: Int, iconRes: Int, title: String, subtitle: String, onTap: () -> Unit, onMenu: () -> Unit): FrameLayout {
        val box = FrameLayout(a).apply {
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(20).toFloat())
                }
            }
            clipToOutline = true
            setOnClickListener { onTap() }
        }
        box.addView(ImageView(a).apply {
            setImageResource(photo)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(-1, dp(240)))
        box.addView(View(a).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.parseColor("#E6000000")))
        }, FrameLayout.LayoutParams(-1, dp(130), Gravity.BOTTOM))
        box.addView(ImageView(a).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = shape(Color.parseColor("#59FFFFFF"), 18)
        }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })
        box.addView(ImageView(a).apply {
            setImageResource(R.drawable.ic_more_horizontal)
            setColorFilter(Color.WHITE)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = shape(Color.parseColor("#59FFFFFF"), 18)
            contentDescription = "Options for $title"
            setOnClickListener { onMenu() }
        }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })
        val words = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), dp(14)) }
        words.addView(text(title, 17f, Color.WHITE, bold = true))
        words.addView(text(subtitle, 13f, Color.parseColor("#E4E4EC")).apply { setPadding(0, dp(3), 0, 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        box.addView(words, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return box
    }

    /** The wide dark pill button: "+ New Skill", "Save Skill"... */
    fun darkPill(label: String, iconRes: Int? = null, onTap: () -> Unit) = LinearLayout(a).apply {
        gravity = Gravity.CENTER
        background = ripple(shape(dark, 28), 28)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        if (iconRes != null) addView(icon(iconRes, 20, Color.WHITE).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(9) })
        addView(text(label, 15.5f, Color.WHITE, bold = true))
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    fun purplePill(label: String, iconRes: Int? = null, onTap: () -> Unit) = LinearLayout(a).apply {
        gravity = Gravity.CENTER
        background = ripple(shape(accent, 18), 18)
        setPadding(dp(18), dp(15), dp(18), dp(15))
        if (iconRes != null) addView(icon(iconRes, 20, Color.WHITE).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(9) })
        addView(text(label, 15.5f, Color.WHITE, bold = true))
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    fun softPill(label: String, onTap: () -> Unit) = LinearLayout(a).apply {
        gravity = Gravity.CENTER
        background = ripple(shape(Color.parseColor("#E9E6F4"), 18), 18)
        setPadding(dp(18), dp(15), dp(18), dp(15))
        addView(text(label, 15.5f, ink, bold = true))
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    /** Settings tab bar: Home, Activity, the round keyboard button, Settings (active gets an underline). */
    fun navSettings(onHome: () -> Unit, onActivity: () -> Unit, onKeyboard: () -> Unit): LinearLayout {
        val bar = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = shape(card, 28)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            elevation = dp(10).toFloat()
        }
        fun item(iconRes: Int, label: String, active: Boolean, onTap: () -> Unit) {
            val col = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(14), dp(6), dp(14), dp(4))
                background = ripple(null, 18)
                setOnClickListener { onTap() }
            }
            col.addView(icon(iconRes, 24, if (active) accent else Color.parseColor("#55555F")))
            col.addView(text(label, 11.5f, if (active) accent else muted, bold = active).apply { setPadding(0, dp(3), 0, 0) })
            col.addView(View(a).apply { background = shape(if (active) accent else Color.TRANSPARENT, 2) }, LinearLayout.LayoutParams(dp(44), dp(3)).apply { topMargin = dp(3) })
            bar.addView(col)
        }
        item(R.drawable.ic_home, "Home", false, onHome)
        item(R.drawable.ic_bar_chart, "Activity", false, onActivity)
        bar.addView(FrameLayout(a).apply {
            background = shape(accent, 28)
            elevation = dp(6).toFloat()
            addView(icon(R.drawable.ic_keyboard, 26, Color.WHITE), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            setOnClickListener { onKeyboard() }
            contentDescription = "Try the keyboard"
        }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { setMargins(dp(14), 0, dp(14), 0) })
        item(R.drawable.ic_settings, "Settings", true) {}
        return bar
    }

    /** Icon-only tab bar with a dot under the active tab, as on the Skills and Auto Actions screens. */
    fun navIcons(active: String, items: List<Triple<String, Int, () -> Unit>>): LinearLayout {
        val bar = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = shape(card, 28)
            setPadding(dp(12), dp(10), dp(12), dp(8))
            elevation = dp(10).toFloat()
        }
        items.forEach { (id, iconRes, onTap) ->
            val on = id == active
            val col = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(18), dp(6), dp(18), dp(4))
                background = ripple(null, 18)
                setOnClickListener { onTap() }
                contentDescription = id
            }
            col.addView(icon(iconRes, 24, if (on) accent else Color.parseColor("#3F3F46")))
            col.addView(View(a).apply { background = shape(if (on) accent else Color.TRANSPARENT, 2) }, LinearLayout.LayoutParams(dp(4), dp(4)).apply { topMargin = dp(5) })
            bar.addView(col)
        }
        return bar
    }

    // ---------- sheet pieces ----------

    /** The bottom sheet's paper: rounded on top only, square at the screen edge. */
    fun sheetShape() = GradientDrawable().apply {
        val r = dp(28).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        setColor(Color.parseColor("#F8F8FC"))
    }

    fun label(s: String, color: Int = ink) = text(s, 15f, color, bold = true).apply { setPadding(dp(2), dp(14), 0, dp(8)) }

    /** White rounded input row: leading icon, edit text, optional trailing view. */
    fun fieldRow(iconRes: Int, hint: String, value: String = "", lines: Int = 1, trailing: View? = null): LinearLayout {
        val row = LinearLayout(a).apply {
            gravity = if (lines > 1) Gravity.TOP else Gravity.CENTER_VERTICAL
            background = shape(card, 16, edge)
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        row.addView(icon(iconRes, 20, Color.parseColor("#55555F")).apply { setPadding(0, dp(8), 0, 0) })
        val field = EditText(a).apply {
            this.hint = hint
            setText(value)
            setTextColor(ink)
            setHintTextColor(faint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = null
            setPadding(dp(12), dp(10), dp(4), dp(10))
            if (lines > 1) {
                minLines = lines
                gravity = Gravity.TOP
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }
        row.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) row.addView(trailing)
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        return row
    }

    fun editText(row: LinearLayout): EditText = row.getChildAt(1) as EditText

    /** White rounded dropdown row: icon, current value, chevron down. */
    fun dropRow(iconRes: Int, value: String, onTap: () -> Unit): LinearLayout {
        val row = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(shape(card, 16, edge), 16)
            setPadding(dp(14), dp(15), dp(14), dp(15))
            setOnClickListener { onTap() }
        }
        row.addView(icon(iconRes, 20, ink))
        row.addView(text(value, 15f, ink).apply { setPadding(dp(12), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(icon(R.drawable.ic_chevron_down, 20, ink))
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        return row
    }

    fun dropText(row: LinearLayout): TextView = row.getChildAt(1) as TextView

    /** Lavender row with a square violet checkbox. */
    fun checkRow(checked: Boolean, label: String, onChange: (Boolean) -> Unit): LinearLayout {
        var on = checked
        val row = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = shape(accentSoft, 14)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val box = LinearLayout(a).apply {
            gravity = Gravity.CENTER
            background = shape(if (on) accent else card, 6, if (on) null else edge)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            addView(icon(R.drawable.ic_check, 14, Color.WHITE).apply { visibility = if (on) View.VISIBLE else View.GONE })
        }
        row.addView(box)
        row.addView(text(label, 15f, violetInk, bold = true).apply { setPadding(dp(12), 0, 0, 0) })
        row.setOnClickListener {
            on = !on
            box.background = shape(if (on) accent else card, 6, if (on) null else edge)
            box.getChildAt(0).visibility = if (on) View.VISIBLE else View.GONE
            onChange(on)
        }
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        return row
    }

    fun toast(message: String) = android.widget.Toast.makeText(a, message, android.widget.Toast.LENGTH_SHORT).show()
}