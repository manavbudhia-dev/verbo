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
        fun onPress(type: Type)
    }

    private class Key(val type: Type, val label: String, val weight: Float, val target: Mode? = null) {
        val rect = RectF()
        var row = 0
        var inset = false
    }

    private class Press(var key: Key) { var done = false }

    var listener: Listener? = null
    var mode = Mode.LETTERS
        set(value) { if (field != value) { field = value; build(); requestLayout(); invalidate() } }
    /** 0 off, 1 next letter, 2 caps lock. */
    var shift = 0
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Text on the wide bar; empty when the assistant can't be triggered from it. */
    var barLabel = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    private val d = resources.displayMetrics.density
    // Keys are kept wide: thin gaps between them and almost no margin at the screen edges.
    private val rowHeight = 51 * d * scale
    private val gapX = 1.75f * d
    private val gapY = 3.5f * d
    private val flat = shape == "flat"
    private val radius = when (shape) { "square" -> 3 * d; "bubble" -> rowHeight; else -> 8 * d }
    private val rows = ArrayList<List<Key>>()
    private val presses = HashMap<Int, Press>()
    private val handler = Handler(Looper.getMainLooper())

    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val icons = HashMap<Int, Drawable>()

    init { build() }

    fun setTheme(t: KeyTheme) { theme = t; invalidate() }

    private fun chars(s: String) = s.map { Key(Type.CHAR, it.toString(), 1f) }

    private fun build() {
        rows.clear()
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
        val side = 1.5f * d
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = place(w)

    // ---------- drawing ----------

    private fun icon(res: Int): Drawable = icons.getOrPut(res) { context.getDrawable(res)!!.mutate() }

    private fun drawIcon(canvas: Canvas, res: Int, key: Key, color: Int, sizeDp: Float, cx: Float = key.rect.centerX()) {
        val half = sizeDp * d / 2
        val cy = key.rect.centerY() - d * 0.75f
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
            // The flat skin shows letters only: a key gets a background just while pressed (and for enter).
            val bare = flat && !down && key.type != Type.ENTER && key.type != Type.BAR
            if (!flat) {
                face.color = theme.keyShadow
                canvas.drawRoundRect(r.left, r.top + 1.5f * d, r.right, r.bottom, radius, radius, face)
            }
            face.color = when {
                key.type == Type.ENTER -> if (down) theme.accentPressed else theme.accent
                down -> theme.keyPressed
                key.type == Type.CHAR || key.type == Type.BAR -> theme.key
                else -> theme.fnKey
            }
            if (!bare) canvas.drawRoundRect(r.left, r.top, r.right, r.bottom - 1.5f * d, radius, radius, face)

            when (key.type) {
                Type.CHAR -> {
                    letters.color = theme.text
                    letters.textSize = 24 * d * minOf(scale, 1.1f)
                    val label = if (mode == Mode.LETTERS && shift > 0) key.label.uppercase() else key.label
                    canvas.drawText(label, r.centerX(), r.centerY() - d * 0.75f - (letters.descent() + letters.ascent()) / 2, letters)
                }
                Type.MODE -> {
                    letters.color = theme.text
                    letters.textSize = 15 * d
                    canvas.drawText(key.label, r.centerX(), r.centerY() - d * 0.75f - (letters.descent() + letters.ascent()) / 2, letters)
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
                    canvas.drawText(barLabel, start + iconSize * d + 6 * d + textWidth / 2, r.centerY() - d * 0.75f - (letters.descent() + letters.ascent()) / 2, letters)
                }
            }
        }
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

    private val barHold = Runnable {
        val press = presses.values.firstOrNull { it.key.type == Type.BAR && !it.done } ?: return@Runnable
        press.done = true
        listener?.onBarHeld()
    }

    private fun down(id: Int, x: Float, y: Float) {
        val key = keyAt(x, y) ?: return
        // A second finger landing means the first key was meant: commit it now, in order.
        for (other in presses.values) if (other.key.type == Type.CHAR || other.key.type == Type.BAR) {
            if (other.key.type == Type.BAR) handler.removeCallbacks(barHold)
            commit(other)
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
            else -> {}
        }
    }

    private fun up(id: Int) {
        val press = presses.remove(id) ?: return
        if (press.key.type == Type.BAR) handler.removeCallbacks(barHold)
        if (press.key.type == Type.BACKSPACE) handler.removeCallbacks(repeatDelete)
        commit(press)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                down(e.getPointerId(e.actionIndex), e.getX(e.actionIndex), e.getY(e.actionIndex))
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                // Sliding off a letter onto its neighbour before lifting corrects a mis-hit.
                val press = presses[e.getPointerId(i)] ?: continue
                if (press.done || press.key.type != Type.CHAR) continue
                val now = keyAt(e.getX(i), e.getY(i))
                if (now != null && now !== press.key && now.type == Type.CHAR) press.key = now
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> {
                presses.clear()
                handler.removeCallbacks(barHold)
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
