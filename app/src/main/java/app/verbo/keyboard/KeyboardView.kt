package app.verbo.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View

/** Colours for the keyboard. */
class KeyTheme(
    val bg: Int, val key: Int, val keyShadow: Int, val keyPressed: Int, val fnKey: Int,
    val text: Int, val muted: Int, val accent: Int, val onAccent: Int,
) {
    val accentPressed: Int = Color.rgb((Color.red(accent) * 0.8f).toInt(), (Color.green(accent) * 0.8f).toInt(), (Color.blue(accent) * 0.8f).toInt())

    companion object {
        const val PURPLE = 0xFF7C5CFF.toInt()

        fun of(context: Context, name: String, customAccent: Int): KeyTheme {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            fun c(hex: String) = Color.parseColor(hex)
            return when (if (name == "system") (if (night) "dark" else "light") else name) {
                "light" -> KeyTheme(c("#E6E9EF"), c("#FFFFFF"), c("#C6CBD6"), c("#DDD6FE"), c("#D5DAE3"), c("#2B2F38"), c("#7A8090"), PURPLE, Color.WHITE)
                "amoled" -> KeyTheme(Color.BLACK, c("#17181C"), Color.BLACK, c("#2E2A45"), c("#0D0E11"), c("#ECEEF2"), c("#8E94A3"), PURPLE, Color.WHITE)
                "custom" -> KeyTheme(c("#16181D"), c("#2B2E36"), c("#0C0D10"), c("#3A3D48"), c("#20232A"), c("#ECEEF2"), c("#8E94A3"), customAccent, Color.WHITE)
                "ocean" -> KeyTheme(c("#0B1E2D"), c("#16344A"), c("#061521"), c("#1F5A7A"), c("#0F293B"), c("#E6F4FF"), c("#7FA6BF"), c("#38BDF8"), c("#062033"))
                "rose" -> KeyTheme(c("#FBE9EE"), c("#FFFFFF"), c("#E8C3CD"), c("#FBCFE0"), c("#F3D3DB"), c("#4A2030"), c("#9A6A78"), c("#E11D74"), Color.WHITE)
                "forest" -> KeyTheme(c("#0F1F17"), c("#1D3528"), c("#07130D"), c("#2C5A40"), c("#152A1F"), c("#E6F5EC"), c("#86A996"), c("#34D399"), c("#06281A"))
                "sunset" -> KeyTheme(c("#2A1420"), c("#45233A"), c("#1A0B14"), c("#6A3350"), c("#351A2A"), c("#FFEDE2"), c("#C39AA8"), c("#FB923C"), c("#3A1500"))
                "mint" -> KeyTheme(c("#E6F4EF"), c("#FFFFFF"), c("#BFDCD1"), c("#C8EEDF"), c("#D1E7DF"), c("#17352B"), c("#5F8577"), c("#10B981"), Color.WHITE)
                else -> KeyTheme(c("#16181D"), c("#2B2E36"), c("#0C0D10"), c("#3B3560"), c("#20232A"), c("#ECEEF2"), c("#8E94A3"), PURPLE, Color.WHITE)
            }
        }
    }
}

/**
 * The key area, drawn and touch-handled as one view so typing stays fast:
 * no per-key views, every finger is tracked separately, and a key is committed as soon as
 * the next finger lands (so quick two-thumb typing never drops or reorders letters).
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context, private var theme: KeyTheme, private val scale: Float,
    private val shape: String = "rounded", private val numberRow: Boolean = false,
) : View(context) {

    enum class Mode { LETTERS, SYMBOLS, MORE_SYMBOLS }
    enum class Type { CHAR, SHIFT, BACKSPACE, MODE, BAR, ENTER }

    interface Listener {
        fun onChar(text: String)
        fun onBackspace()
        fun onEnter()
        fun onShift()
        fun onBarHeld()
        fun onKeyHeld(letter: Char)
        fun onWheel(index: Int)
        fun onPress(type: Type)
        /** The wheel is about to open: a chance to fit its shortcuts to what is being written. */
        fun onWheelOpening() {}
        /** A finger drew a path across the letters (swipe typing). */
        fun onSwipe(path: List<Gesture.P>) {}
        /** The compass was spun and released on catalog entry [index]. */
        fun onWheelCatalog(index: Int) {}
        /** The space bar was tapped twice quickly: open the language compass. */
        fun onSpaceDouble() {}
    }

    private class Key(val type: Type, val label: String, val weight: Float, val target: Mode? = null) {
        val rect = RectF()
        var row = 0
        var inset = false
    }

    private class Press(var key: Key) {
        var done = false
        /** Points the finger passed through, for swipe typing; null when this press can't become a swipe. */
        var path: ArrayList<Gesture.P>? = null
        var swiping = false
    }

    /** Swipe typing on or off. */
    var swipeEnabled = true

    /** The letter key under a point, for swipe typing. */
    fun letterAt(p: Gesture.P): Char? = keyAt(p.x, p.y)?.takeIf { it.type == Type.CHAR && it.label.length == 1 && it.label[0].isLetter() }?.label?.get(0)?.lowercaseChar()

    /** Centres of the letter keys. */
    fun letterCenters(): Map<Char, Gesture.P> = rows.flatten().filter { it.type == Type.CHAR && it.label.length == 1 && it.label[0].isLetter() }
        .associate { it.label[0].lowercaseChar() to Gesture.P(it.rect.centerX(), it.rect.centerY()) }

    val letterWidth: Float get() = rows.flatten().firstOrNull { it.type == Type.CHAR }?.rect?.width() ?: (width / 10f)

    /** One shortcut on the wheel: an icon, or an emoji when [icon] is 0. */
    class WheelItem(val label: String, val icon: Int, val emoji: String = "", val suggested: Boolean = false, val tint: Int = 0)

    var listener: Listener? = null
    var mode = Mode.LETTERS
        set(value) { if (field != value) { field = value; build(); requestLayout(); invalidate() } }
    /** 0 off, 1 next letter, 2 caps lock. */
    var shift = 0
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Shortcuts shown on the wheel when the bar is held. Empty means holding the bar runs the assistant directly. */
    var wheelItems: List<WheelItem> = emptyList()
    /** Everything the compass can spin through: tools, skills and apps, in the same order as [wheelCatalogIds]. */
    var wheelCatalog: List<WheelItem> = emptyList()
    var wheelCatalogIds: List<String> = emptyList()
    private var wheelOpen = false
    private var wheelPointer = -1
    private var wheelPick = -1
    private var compass = false
    private val spin = CompassSpin({ invalidate() }, { listener?.onPress(Type.MODE) })
    private var compassLastAngle = 0f
    private var bezelSince = 0L
    private var lastSpaceTap = 0L
    private var wheelX = 0f
    private var wheelY = 0f

    /** True while the wheel has become the 360° compass (rim held and spun). */
    val compassOpen: Boolean get() = compass

    /** Where the compass currently sits, in degrees; always a whole icon once it locks. */
    val compassRotation: Float get() = spin.rot

    /** Letters that run a skill when held. They are marked with a small glow. */
    var bound: Set<Char> = emptySet()
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Text on the wide bar; empty when the assistant can't be triggered from it. */
    var barLabel = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    // Keys are kept wide: hairline gaps between them and almost no margin at the screen edges.
    private val rowHeight = 51 * d * scale
    private val gapX = 1.25f * d
    private val gapY = 3.25f * d
    private val flat = shape == "flat"
    private val raised = shape == "raised"
    private val glass = shape == "glass"
    private val outline = shape == "outline"
    private val radius = when (shape) { "square" -> 3 * d; "bubble" -> rowHeight; "raised" -> 9 * d; else -> 8 * d }
    /** How far the key face stands above its base. Raised keys are tall and sink by this much when pressed. */
    private val depth = when { raised -> 4 * d; flat || glass || outline -> 0f; else -> 1.5f * d }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    private val shaders = HashMap<Long, android.graphics.Shader>()
    private var rows = ArrayList<List<Key>>()
    private val presses = HashMap<Int, Press>()
    private val handler = Handler(Looper.getMainLooper())

    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val icons = HashMap<Int, Drawable>()
    private var lift = 0f

    private fun lighten(c: Int, by: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * by).toInt(), (Color.green(c) + (255 - Color.green(c)) * by).toInt(), (Color.blue(c) + (255 - Color.blue(c)) * by).toInt())
    private fun darken(c: Int, to: Float) = Color.rgb((Color.red(c) * to).toInt(), (Color.green(c) * to).toInt(), (Color.blue(c) * to).toInt())

    init { build() }

    fun setTheme(t: KeyTheme) { theme = t; shaders.clear(); invalidate() }

    private fun chars(s: String) = s.map { Key(Type.CHAR, it.toString(), 1f) }

    private fun build() {
        rows = ArrayList() // a new list each time, so code holding the old layout can tell it changed
        when (mode) {
            Mode.LETTERS -> {
                if (numberRow) rows += chars("1234567890")
                rows += chars("qwertyuiop")
                rows += chars("asdfghjkl").onEach { it.inset = true }
                rows += listOf(Key(Type.SHIFT, "", 1.5f)) + chars("zxcvbnm") + Key(Type.BACKSPACE, "", 1.5f)
            }
            Mode.SYMBOLS -> {
                rows += chars("1234567890")
                rows += chars("@#$&-+()/_")
                rows += listOf(Key(Type.MODE, "=\\<", 1.5f, Mode.MORE_SYMBOLS)) + chars("*\"':;!?") + Key(Type.BACKSPACE, "", 1.5f)
            }
            Mode.MORE_SYMBOLS -> {
                rows += chars("~`|•√π÷×¶∆")
                rows += chars("£¢€¥^°={}\\")
                rows += listOf(Key(Type.MODE, "?123", 1.5f, Mode.SYMBOLS)) + chars("%<>[]✓™") + Key(Type.BACKSPACE, "", 1.5f)
            }
        }
        rows += listOf(
            Key(Type.MODE, if (mode == Mode.LETTERS) "?123" else "ABC", 1.5f, if (mode == Mode.LETTERS) Mode.SYMBOLS else Mode.LETTERS),
            Key(Type.CHAR, ",", 1f), Key(Type.BAR, "", 4.5f), Key(Type.CHAR, ".", 1f), Key(Type.ENTER, "", 1.5f),
        )
        rows.forEachIndexed { i, row -> row.forEach { it.row = i } }
        presses.clear()
        place(width)
    }

    private fun place(width: Int) {
        if (width == 0) return
        val side = 1f * d
        rows.forEachIndexed { r, row ->
            // The second letter row is one key narrower, so it sits half a key in from each side.
            val inset = if (row[0].inset) (width - 2 * side) / 20f else 0f
            val unit = (width - 2 * side - 2 * inset) / row.sumOf { it.weight.toDouble() }.toFloat()
            var x = side + inset
            val top = r * rowHeight
            for (key in row) {
                val w = unit * key.weight
                key.rect.set(x + gapX, top + gapY, x + w - gapX, top + rowHeight - gapY)
                x += w
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (rows.size * rowHeight).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { shaders.clear(); place(w) }

    // ---------- drawing ----------

    private fun icon(res: Int): Drawable = icons.getOrPut(res) { context.getDrawable(res)!!.mutate() }

    private fun drawIcon(canvas: Canvas, res: Int, key: Key, color: Int, sizeDp: Float, cx: Float = key.rect.centerX()) {
        val half = sizeDp * d / 2
        val cy = key.rect.centerY() + lift
        icon(res).apply {
            setTint(color)
            setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            draw(canvas)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val pressed = presses.values.map { it.key }
        for (row in rows) for (key in row) {
            val down = key in pressed
            val r = key.rect
            val color = when {
                key.type == Type.ENTER -> if (down) theme.accentPressed else theme.accent
                down -> theme.keyPressed
                key.type == Type.CHAR || key.type == Type.BAR -> theme.key
                else -> theme.fnKey
            }
            // A pressed key sinks onto its base, which is what makes raised keys feel physical.
            val sink = if (down) depth * 0.75f else 0f
            val top = r.top + sink
            val bottom = r.bottom - depth + sink
            // Offset for labels and icons so they move with the key face.
            lift = sink - depth / 2
            when {
                flat -> if (down || key.type == Type.ENTER || key.type == Type.BAR) {
                    face.shader = null
                    face.color = color
                    canvas.drawRoundRect(r.left, top, r.right, bottom, radius, radius, face)
                }
                glass || outline -> {
                    face.shader = null
                    face.color = if (key.type == Type.ENTER || down) color
                        else if (glass) (color and 0x00FFFFFF) or 0x59000000 else Color.TRANSPARENT
                    canvas.drawRoundRect(r.left, top, r.right, bottom, radius, radius, face)
                    edge.color = if (key.type == Type.ENTER) theme.accent else (theme.text and 0x00FFFFFF) or (if (outline) 0x66000000 else 0x33000000)
                    val h = edge.strokeWidth / 2
                    canvas.drawRoundRect(r.left + h, top + h, r.right - h, bottom - h, radius, radius, edge)
                }
                else -> {
                    face.shader = null
                    face.color = if (raised) darken(if (key.type == Type.ENTER) theme.accent else theme.keyShadow, if (key.type == Type.ENTER) 0.6f else 1f) else theme.keyShadow
                    canvas.drawRoundRect(r.left, r.top + depth * 0.5f, r.right, r.bottom, radius, radius, face)
                    if (raised) {
                        // Lit from above: the face is a little lighter at the top than at the bottom.
                        val id = (color.toLong() shl 20) or (top.toLong() and 0xFFFFF)
                        face.shader = shaders.getOrPut(id) {
                            android.graphics.LinearGradient(0f, top, 0f, bottom, lighten(color, 0.10f), darken(color, 0.94f), android.graphics.Shader.TileMode.CLAMP)
                        }
                    } else face.color = color
                    canvas.drawRoundRect(r.left, top, r.right, bottom, radius, radius, face)
                    face.shader = null
                }
            }

            when (key.type) {
                Type.CHAR -> {
                    letters.color = theme.text
                    letters.textSize = 24 * d * minOf(scale, 1.1f)
                    val label = if (mode == Mode.LETTERS && shift > 0) key.label.uppercase() else key.label
                    canvas.drawText(label, r.centerX(), r.centerY() + lift - (letters.descent() + letters.ascent()) / 2, letters)
                    if (mode == Mode.LETTERS && key.label[0] in bound) {
                        face.color = (theme.accent and 0x00FFFFFF) or 0xCC000000.toInt()
                        canvas.drawRoundRect(r.centerX() - 7 * d, bottom - 5.5f * d, r.centerX() + 7 * d, bottom - 3 * d, 2 * d, 2 * d, face)
                    }
                }
                Type.MODE -> {
                    letters.color = theme.text
                    letters.textSize = 15 * d
                    canvas.drawText(key.label, r.centerX(), r.centerY() + lift - (letters.descent() + letters.ascent()) / 2, letters)
                }
                Type.SHIFT -> drawIcon(canvas, if (shift == 2) R.drawable.ic_shift_lock else R.drawable.ic_arrow_big_up, key, if (shift > 0) theme.accent else theme.text, 24f)
                Type.BACKSPACE -> drawIcon(canvas, R.drawable.ic_delete, key, theme.text, 24f)
                Type.ENTER -> drawIcon(canvas, R.drawable.ic_corner_down_left, key, theme.onAccent, 22f)
                Type.BAR -> if (barLabel.isNotEmpty()) {
                    letters.color = theme.muted
                    letters.textSize = 13 * d
                    val textWidth = letters.measureText(barLabel)
                    val iconSize = 14f
                    val total = iconSize * d + 6 * d + textWidth
                    val start = r.centerX() - total / 2
                    drawIcon(canvas, R.drawable.ic_sparkle, key, theme.accent, iconSize, start + iconSize * d / 2)
                    canvas.drawText(barLabel, start + iconSize * d + 6 * d + textWidth / 2, r.centerY() + lift - (letters.descent() + letters.ascent()) / 2, letters)
                }
            }
        }
        presses.values.firstOrNull { it.swiping }?.path?.let { path ->
            // The swipe trail.
            edge.color = theme.accent
            edge.strokeWidth = 5f * d
            edge.strokeCap = Paint.Cap.ROUND
            for (k in 1 until path.size) canvas.drawLine(path[k - 1].x, path[k - 1].y, path[k].x, path[k].y, edge)
            edge.strokeWidth = 1.2f * d
            edge.strokeCap = Paint.Cap.BUTT
        }
        if (wheelOpen) drawWheel(canvas)
    }

    // ---------- touch ----------

    /** The key under a point; touches that land in the gaps go to the closest key in that row. */
    private fun keyAt(x: Float, y: Float): Key? {
        val row = rows.getOrNull((y / rowHeight).toInt().coerceIn(0, rows.size - 1)) ?: return null
        return row.minByOrNull { k -> if (x < k.rect.left) k.rect.left - x else if (x > k.rect.right) x - k.rect.right else 0f }
    }

    private fun commit(press: Press) {
        if (press.done) return
        press.done = true
        val key = press.key
        when (key.type) {
            Type.CHAR -> listener?.onChar(if (mode == Mode.LETTERS && shift > 0) key.label.uppercase() else key.label)
            Type.BAR -> listener?.onChar(" ")
            Type.ENTER -> listener?.onEnter()
            Type.MODE -> key.target?.let { mode = it }
            else -> {}
        }
    }

    private val repeatDelete = object : Runnable {
        override fun run() {
            if (presses.values.none { it.key.type == Type.BACKSPACE }) return
            listener?.onBackspace()
            handler.postDelayed(this, 45)
        }
    }

    private val keyHold = Runnable {
        val press = presses.values.firstOrNull { it.key.type == Type.CHAR && !it.done && mode == Mode.LETTERS && it.key.label[0] in bound } ?: return@Runnable
        press.done = true
        listener?.onKeyHeld(press.key.label[0])
        invalidate()
    }

    private val barHold = Runnable {
        val entry = presses.entries.firstOrNull { it.value.key.type == Type.BAR && !it.value.done } ?: return@Runnable
        entry.value.done = true
        listener?.onWheelOpening()
        if (wheelItems.isEmpty()) {
            listener?.onBarHeld()
        } else {
            // Open the wheel under the finger that is still holding the bar; sliding picks, lifting runs.
            wheelOpen = true
            wheelPointer = entry.key
            wheelPick = -1
            compass = false
            spin.reset()
            handler.removeCallbacks(bezelHold)
            listener?.onPress(Type.ENTER)
            invalidate()
        }
    }

    // ---------- the wheel ----------

    private val wheelCx get() = width / 2f
    private val wheelCy get() = height.toFloat()
    private val wheelR get() = minOf(width * 0.47f, height * 0.80f)

    // The compass sits centred in the view so the whole 360° dial fits on screen.
    private val compassCx get() = width / 2f
    private val compassCy get() = height * 0.60f
    private val compassR get() = minOf(width * 0.44f, height * 0.40f)

    /** True when a point rests on the fan's outer blue rim: the grip that spins the compass. */
    private fun onBezel(x: Float, y: Float): Boolean {
        val dy = wheelCy - y
        if (dy < 0) return false
        val dist = kotlin.math.hypot(x - wheelCx, dy)
        return dist >= wheelR * 0.92f && dist <= wheelR * 1.25f
    }

    private fun screenAngle(x: Float, y: Float) =
        Math.toDegrees(kotlin.math.atan2((y - compassCy).toDouble(), (x - compassCx).toDouble())).toFloat()

    private val bezelHold = Runnable {
        if (!wheelOpen || compass || !onBezel(wheelX, wheelY)) return@Runnable
        compass = true
        spin.reset()
        compassLastAngle = screenAngle(wheelX, wheelY)
        wheelPick = -1
        listener?.onPress(Type.MODE)
        invalidate()
    }

    private fun enterCompassCheck() {
        handler.removeCallbacks(bezelHold)
        if (onBezel(wheelX, wheelY)) handler.postDelayed(bezelHold, 300)
    }

    /** Which shortcut a point is over, or -1 for the cancel zone in the middle (and anything outside the wheel). */
    private fun wheelAt(x: Float, y: Float): Int {
        val dx = x - wheelCx
        val dy = wheelCy - y
        val dist = kotlin.math.hypot(dx, dy)
        if (dist < wheelR * 0.36f || dist > wheelR * 1.25f || dy < 0) return -1
        val angle = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()) // 0 = right, 180 = left
        return ((180 - angle) / (180.0 / wheelItems.size)).toInt().coerceIn(0, wheelItems.size - 1)
    }

    private fun drawWheel(canvas: Canvas) {
        val n = wheelItems.size
        val cx = wheelCx
        val cy = wheelCy
        val r = wheelR
        WheelLook.paper(canvas, width.toFloat(), height.toFloat(), d)
        if (compass) { drawCompass(canvas); return }
        if (n == 0) return
        val arc = RectF(cx - r, cy - r, cx + r, cy + r)
        WheelLook.fan(canvas, cx, cy, r, d)
        WheelLook.ticks(canvas, cx, cy, r, d)
        val step = 180f / n
        if (wheelPick >= 0) {
            val (fx, fy) = wheelCenter(wheelPick)
            WheelLook.beam(canvas, cx, cy, fx, fy, arc, 180f + wheelPick * step + 1.5f, step - 3f, d)
        }
        val chord = 2 * r * 0.74f * kotlin.math.sin(Math.toRadians((step / 2).toDouble())).toFloat()
        for (i in 0 until n) {
            val item = wheelItems[i]
            val (x, y) = wheelCenter(i)
            WheelLook.tile(canvas, x, y, (if (i == wheelPick) 26f else 24f) * d, d)
            if (item.suggested && i != wheelPick) WheelLook.ring(canvas, x, y, 29f * d, WheelLook.blue, 2.5f * d)
            val tint = if (item.tint != 0) item.tint else if (i == wheelPick) WheelLook.blue else WheelLook.ink
            if (item.icon != 0) {
                val half = 11 * d
                icon(item.icon).apply {
                    setTint(tint)
                    setBounds((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt())
                    draw(canvas)
                }
            } else {
                WheelLook.drawText(canvas, item.emoji.ifEmpty { item.label.take(1) }, x, y, 19 * d, tint)
            }
            // The hovered item's name is in the pill; a label here would sit on the beam knob.
            if (i != wheelPick) {
                var size = 10.5f * sp
                letters.textSize = size
                val tw = letters.measureText(item.label)
                if (tw > chord * 0.92f) size *= chord * 0.92f / tw
                WheelLook.drawText(canvas, item.label, x, minOf(y + 37 * d, height - 8 * d), size, WheelLook.label)
            }
        }
        // The middle is where the finger starts: lifting there does nothing.
        WheelLook.glow(canvas, cx, cy, d)
        WheelLook.cancel(canvas, cx, cy, d, sp)
        // What lifting the finger will do, in a pill above the wheel.
        val hint = wheelItems.firstOrNull { it.suggested }?.let { "Try: ${it.label}" }
        val label = if (wheelPick >= 0) wheelItems[wheelPick].label else hint ?: "Slide to choose · release to cancel"
        val color = if (wheelPick >= 0 || hint != null) WheelLook.blue else WheelLook.ink
        WheelLook.drawPill(canvas, WheelLook.pillRect(cx, label, d, sp), label, color, d, sp)
    }

    private fun wheelCenter(i: Int): Pair<Float, Float> {
        val step = 180f / wheelItems.size
        val angle = Math.toRadians((180 - (i + 0.5f) * step).toDouble())
        return Pair(wheelCx + (wheelR * 0.74f * kotlin.math.cos(angle)).toFloat(), wheelCy - (wheelR * 0.74f * kotlin.math.sin(angle)).toFloat())
    }

    /** The 360° compass: spin the blue rim to bring any tool, skill or app under the needle. */
    private fun drawCompass(canvas: Canvas) {
        val k = WheelLook.COMPASS_SLOTS
        val cx = compassCx
        val cy = compassCy
        val r = compassR
        val size = wheelCatalog.size
        val rot = spin.rot
        WheelLook.compassDial(canvas, cx, cy, r, rot, d)
        val cand = WheelLook.compassCandidate(rot, k, size)
        if (size > 0) for (j in 0 until k) {
            val item = wheelCatalog[WheelLook.compassIndex(j, k, rot, size)]
            val (x, y) = WheelLook.compassPos(cx, cy, r, j, k, rot)
            val isCand = j == k / 2
            WheelLook.tile(canvas, x, y, (if (isCand) 19f else 17f) * d, d)
            if (isCand) WheelLook.ring(canvas, x, y, 22f * d, WheelLook.blue, 2.5f * d)
            val tint = if (item.tint != 0) item.tint else if (isCand) WheelLook.blue else WheelLook.ink
            if (item.icon != 0) {
                val half = 8.5f * d
                icon(item.icon).apply {
                    setTint(tint)
                    setBounds((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt())
                    draw(canvas)
                }
            } else {
                WheelLook.drawText(canvas, item.emoji.ifEmpty { item.label.take(1) }, x, y, 16 * d, tint)
            }
        }
        val label = if (cand >= 0) wheelCatalog[cand].label else "Nothing to spin"
        WheelLook.compassTop(canvas, cx, cy, r, k, d, sp, label, "Cancel")
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (wheelOpen) return // other fingers are ignored while the wheel is up
        val key = keyAt(x, y) ?: return
        // A second finger landing means the first key was meant: commit it now, in order.
        // Committing can switch the layout (space in the symbols page), which rebuilds the keys and
        // clears the press map, so walk a copy and stop as soon as the layout has changed under us.
        val layout = rows
        for (other in presses.values.toList()) if (other.key.type == Type.CHAR || other.key.type == Type.BAR) {
            if (other.key.type == Type.BAR) handler.removeCallbacks(barHold)
            commit(other)
            if (rows !== layout) break
        }
        val press = Press(key)
        presses[id] = press
        listener?.onPress(key.type)
        when (key.type) {
            Type.BACKSPACE -> {
                press.done = true
                listener?.onBackspace()
                handler.removeCallbacks(repeatDelete)
                handler.postDelayed(repeatDelete, 380)
            }
            Type.SHIFT -> { press.done = true; listener?.onShift() }
            Type.BAR -> if (barLabel.isNotEmpty()) handler.postDelayed(barHold, 380)
            Type.CHAR -> {
                handler.removeCallbacks(keyHold)
                if (mode == Mode.LETTERS && key.label[0] in bound) handler.postDelayed(keyHold, 450)
                if (swipeEnabled && mode == Mode.LETTERS && presses.size == 1 && key.label[0].isLetter()) press.path = arrayListOf(Gesture.P(x, y))
            }
            else -> {}
        }
    }

    private fun up(id: Int) {
        if (wheelOpen && id == wheelPointer) {
            val wasCompass = compass
            val pick = wheelPick
            spin.settle()
            val cand = WheelLook.compassCandidate(spin.rot, WheelLook.COMPASS_SLOTS, wheelCatalogIds.size)
            val dist = kotlin.math.hypot(wheelX - compassCx, wheelY - compassCy)
            wheelOpen = false
            wheelPick = -1
            compass = false
            handler.removeCallbacks(bezelHold)
            presses.remove(id)
            if (wasCompass) {
                if (dist >= compassR * 0.30f && cand >= 0) listener?.onWheelCatalog(cand)
            } else if (pick >= 0) listener?.onWheel(pick)
            return
        }
        val press = presses.remove(id) ?: return
        if (press.swiping) {
            press.done = true
            val path = press.path.orEmpty()
            invalidate()
            if (path.size >= 2) listener?.onSwipe(path)
            return
        }
        if (press.key.type == Type.BAR) {
            handler.removeCallbacks(barHold)
            if (!press.done) {
                val now = System.currentTimeMillis()
                if (now - lastSpaceTap < 300) {
                    lastSpaceTap = 0
                    listener?.onSpaceDouble()
                    return
                }
                lastSpaceTap = now
            }
        }
        if (press.key.type == Type.CHAR) handler.removeCallbacks(keyHold)
        if (press.key.type == Type.BACKSPACE) handler.removeCallbacks(repeatDelete)
        commit(press)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                down(e.getPointerId(e.actionIndex), e.getX(e.actionIndex), e.getY(e.actionIndex))
            MotionEvent.ACTION_MOVE -> if (wheelOpen) {
                val i = e.findPointerIndex(wheelPointer)
                if (i >= 0) {
                    wheelX = e.getX(i)
                    wheelY = e.getY(i)
                    if (compass) {
                        val a = screenAngle(wheelX, wheelY)
                        var delta = a - compassLastAngle
                        if (delta > 180) delta -= 360 else if (delta < -180) delta += 360
                        spin.drag(delta)
                        compassLastAngle = a
                    } else {
                        enterCompassCheck()
                        val pick = wheelAt(wheelX, wheelY)
                        if (pick != wheelPick) {
                            wheelPick = pick
                            if (pick >= 0) listener?.onPress(Type.MODE)
                        }
                    }
                }
            } else for (i in 0 until e.pointerCount) {
                // Sliding off a letter onto its neighbour before lifting corrects a mis-hit.
                val press = presses[e.getPointerId(i)] ?: continue
                if (press.done || press.key.type != Type.CHAR) continue
                val path = press.path
                if (path != null) {
                    path += Gesture.P(e.getX(i), e.getY(i))
                    // Far enough from where it started: this is a swipe, not a tap or a slide to the next key.
                    if (!press.swiping && kotlin.math.hypot((e.getX(i) - path[0].x).toDouble(), (e.getY(i) - path[0].y).toDouble()) > letterWidth * 1.4) {
                        press.swiping = true
                        handler.removeCallbacks(keyHold)
                    }
                    if (press.swiping) { invalidate(); continue }
                }
                val now = keyAt(e.getX(i), e.getY(i))
                if (now != null && now !== press.key && now.type == Type.CHAR) { press.key = now; handler.removeCallbacks(keyHold) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> {
                wheelOpen = false
                wheelPick = -1
                compass = false
                spin.reset()
                presses.clear()
                handler.removeCallbacks(barHold)
                handler.removeCallbacks(bezelHold)
                handler.removeCallbacks(keyHold)
                handler.removeCallbacks(repeatDelete)
            }
        }
        invalidate()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        spin.reset()
        presses.clear()
    }
}
