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
    }

    private class Key(val type: Type, val label: String, val weight: Float, val target: Mode? = null) {
        val rect = RectF()
        var row = 0
        var inset = false
    }

    private class Press(var key: Key) { var done = false }

    /** One shortcut on the wheel: an icon, or an emoji when [icon] is 0. */
    class WheelItem(val label: String, val icon: Int, val emoji: String = "")

    var listener: Listener? = null
    var mode = Mode.LETTERS
        set(value) { if (field != value) { field = value; build(); requestLayout(); invalidate() } }
    /** 0 off, 1 next letter, 2 caps lock. */
    var shift = 0
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Shortcuts shown on the wheel when the bar is held. Empty means holding the bar runs the assistant directly. */
    var wheelItems: List<WheelItem> = emptyList()
    private var wheelOpen = false
    private var wheelPointer = -1
    private var wheelPick = -1

    /** Letters that run a skill when held. They are marked with a small glow. */
    var bound: Set<Char> = emptySet()
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Text on the wide bar; empty when the assistant can't be triggered from it. */
    var barLabel = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    private val d = resources.displayMetrics.density
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
        if (wheelItems.isEmpty()) {
            listener?.onBarHeld()
        } else {
            // Open the wheel under the finger that is still holding the bar; sliding picks, lifting runs.
            wheelOpen = true
            wheelPointer = entry.key
            wheelPick = -1
            listener?.onPress(Type.ENTER)
            invalidate()
        }
    }

    // ---------- the wheel ----------

    private val wheelCx get() = width / 2f
    private val wheelCy get() = height.toFloat()
    private val wheelR get() = minOf(width * 0.47f, height * 0.80f)

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
        val arc = RectF(cx - r, cy - r, cx + r, cy + r)
        face.shader = null
        face.color = (theme.bg and 0x00FFFFFF) or 0xF2000000.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), face)
        face.color = theme.key
        canvas.drawArc(arc, 180f, 180f, true, face)
        val step = 180f / n
        if (wheelPick >= 0) {
            face.color = (theme.accent and 0x00FFFFFF) or 0x33000000
            canvas.drawArc(arc, 180f + wheelPick * step, step, true, face)
            edge.color = theme.accent
            edge.strokeWidth = 3.5f * d
            canvas.drawArc(arc, 180f + wheelPick * step, step, false, edge)
            edge.strokeWidth = 1.2f * d
        }
        for (i in 0 until n) {
            val item = wheelItems[i]
            val angle = Math.toRadians((180 - (i + 0.5) * step))
            val x = cx + (r * 0.68f * kotlin.math.cos(angle)).toFloat()
            val y = cy - (r * 0.68f * kotlin.math.sin(angle)).toFloat()
            val size = (if (i == wheelPick) 25 else 21) * d
            face.color = if (i == wheelPick) theme.accent else theme.fnKey
            canvas.drawCircle(x, y, size, face)
            val tint = if (i == wheelPick) theme.onAccent else theme.text
            if (item.icon != 0) {
                val half = 11 * d
                icon(item.icon).apply {
                    setTint(tint)
                    setBounds((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt())
                    draw(canvas)
                }
            } else {
                letters.color = tint
                letters.textSize = 20 * d
                canvas.drawText(item.emoji.ifEmpty { item.label.take(1) }, x, y - (letters.descent() + letters.ascent()) / 2, letters)
            }
        }
        // The middle is where the finger starts: lifting there does nothing.
        face.color = theme.bg
        canvas.drawArc(RectF(cx - r * 0.34f, cy - r * 0.34f, cx + r * 0.34f, cy + r * 0.34f), 180f, 180f, true, face)
        letters.color = theme.muted
        letters.textSize = 13 * d
        canvas.drawText("Cancel", cx, cy - r * 0.12f, letters)
        // What lifting the finger will do, in a pill above the wheel.
        val label = if (wheelPick >= 0) wheelItems[wheelPick].label else "Slide to choose · release to cancel"
        letters.textSize = 14.5f * d
        letters.typeface = Typeface.DEFAULT_BOLD
        val w = letters.measureText(label) + 26 * d
        val top = (cy - r - 34 * d).coerceAtLeast(3 * d)
        face.color = if (wheelPick >= 0) theme.accent else theme.fnKey
        canvas.drawRoundRect(cx - w / 2, top, cx + w / 2, top + 28 * d, 14 * d, 14 * d, face)
        letters.color = if (wheelPick >= 0) theme.onAccent else theme.muted
        canvas.drawText(label, cx, top + 14 * d - (letters.descent() + letters.ascent()) / 2, letters)
        letters.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
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
            }
            else -> {}
        }
    }

    private fun up(id: Int) {
        if (wheelOpen && id == wheelPointer) {
            val pick = wheelPick
            wheelOpen = false
            wheelPick = -1
            presses.remove(id)
            if (pick >= 0) listener?.onWheel(pick)
            return
        }
        val press = presses.remove(id) ?: return
        if (press.key.type == Type.BAR) handler.removeCallbacks(barHold)
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
                    val pick = wheelAt(e.getX(i), e.getY(i))
                    if (pick != wheelPick) {
                        wheelPick = pick
                        if (pick >= 0) listener?.onPress(Type.MODE)
                    }
                }
            } else for (i in 0 until e.pointerCount) {
                // Sliding off a letter onto its neighbour before lifting corrects a mis-hit.
                val press = presses[e.getPointerId(i)] ?: continue
                if (press.done || press.key.type != Type.CHAR) continue
                val now = keyAt(e.getX(i), e.getY(i))
                if (now != null && now !== press.key && now.type == Type.CHAR) { press.key = now; handler.removeCallbacks(keyHold) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> {
                wheelOpen = false
                wheelPick = -1
                presses.clear()
                handler.removeCallbacks(barHold)
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
        presses.clear()
    }
}
