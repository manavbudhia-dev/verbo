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
        /** The wheel was released on ring entry [index] (an index into [wheelItems]). */
        fun onWheel(index: Int)
        fun onPress(type: Type)
        /** The wheel is about to open: a chance to fit its shortcuts to what is being written. */
        fun onWheelOpening() {}
        /** A finger drew a path across the letters (swipe typing). */
        fun onSwipe(path: List<Gesture.P>) {}
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
    /**
     * The wheel's ring when the bar is held: the user's own shortcuts first, then more tools and apps
     * (Auto-translate, Notion, Slack...) that turn into view as the fan rotates. Empty means holding
     * the bar runs the assistant directly.
     */
    var wheelItems: List<WheelItem> = emptyList()
    /** How many ring entries the semicircle shows at once (the user's own shortcuts). */
    var wheelSlots = 6
    private var wheelOpen = false
    private var wheelPointer = -1
    /** The visible slot under the finger (0 = far left), or -1. */
    private var wheelPick = -1
    private val spin = CompassSpin({ invalidate() }, { listener?.onPress(Type.MODE) },
        step = 30f, duration = CompassSpin.FAN_DURATION, oneAtATime = true, lockMs = CompassSpin.FAN_LOCK)
    private var rimLastAngle = Float.NaN
    private var edgeDir = 0
    private var lastSpaceTap = 0L
    private var wheelX = 0f
    private var wheelY = 0f

    /** Slots actually shown: never more than the ring holds. */
    private val visibleSlots: Int get() = wheelSlots.coerceIn(1, maxOf(1, wheelItems.size))

    /** True when the ring holds more than the fan shows, so it can turn. */
    val wheelTurns: Boolean get() = wheelItems.size > visibleSlots

    /** How far the fan has turned, in whole icons once it locks (positive brings in entries from the right). */
    val wheelRotation: Float get() = spin.rot / spin.step

    /** The ring entry that releasing over visible slot [slot] would run, at the dial's locked position. */
    fun wheelEntryAt(slot: Int): Int {
        val size = wheelItems.size
        if (size == 0 || slot < 0) return -1
        val base = Math.round(spin.target / spin.step)
        return (((base + slot) % size) + size) % size
    }

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
            spin.step = 180f / visibleSlots
            spin.reset()
            rimLastAngle = Float.NaN
            stopEdge()
            listener?.onPress(Type.ENTER)
            invalidate()
        }
    }

    // ---------- the wheel ----------

    private val wheelCx get() = width / 2f
    private val wheelCy get() = height.toFloat()
    private val wheelR get() = minOf(width * 0.47f, height * 0.80f)

    /** True when a point rests on the fan's outer blue rim: the grip that turns the ring. */
    private fun onBezel(x: Float, y: Float): Boolean {
        val dy = wheelCy - y
        if (dy < 0) return false
        val dist = kotlin.math.hypot(x - wheelCx, dy)
        return dist >= wheelR * 0.90f && dist <= wheelR * 1.25f
    }

    /** -1 / +1 when the finger rests low at the fan's left / right end: the ring keeps turning that way. */
    private fun edgeAt(x: Float, y: Float): Int {
        if (!wheelTurns) return 0
        val dx = x - wheelCx
        val dy = wheelCy - y
        val dist = kotlin.math.hypot(dx, dy)
        if (dy < 0 || dy > wheelR * 0.13f || dist < wheelR * 0.42f || dist > wheelR * 1.25f) return 0
        return if (dx < 0) -1 else 1
    }

    /** The finger's angle around the fan's centre: 0 on the right, 180 on the left. */
    private fun fanAngle(x: Float, y: Float) =
        Math.toDegrees(kotlin.math.atan2((wheelCy - y).toDouble(), (x - wheelCx).toDouble())).toFloat()

    /** Holding at an end of the fan turns it one icon, rests on it, then turns the next: never a fast spin. */
    private val edgeTurn = object : Runnable {
        override fun run() {
            if (!wheelOpen || edgeDir == 0) return
            if (spin.turn(edgeDir)) wheelPick = -1
            handler.postDelayed(this, 90)
        }
    }

    private fun stopEdge() {
        edgeDir = 0
        handler.removeCallbacks(edgeTurn)
    }

    /** Which visible slot a point is over, or -1 for the cancel zone in the middle, the turning ends and outside. */
    private fun wheelAt(x: Float, y: Float): Int {
        val dx = x - wheelCx
        val dy = wheelCy - y
        val dist = kotlin.math.hypot(dx, dy)
        if (dist < wheelR * 0.36f || dist > wheelR * 1.25f || dy < 0) return -1
        if (edgeAt(x, y) != 0) return -1
        val n = visibleSlots
        val angle = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()) // 0 = right, 180 = left
        return ((180 - angle) / (180.0 / n)).toInt().coerceIn(0, n - 1)
    }

    private fun drawWheel(canvas: Canvas) {
        val size = wheelItems.size
        val cx = wheelCx
        val cy = wheelCy
        val r = wheelR
        WheelLook.paper(canvas, width.toFloat(), height.toFloat(), d)
        if (size == 0) return
        val n = visibleSlots
        val turns = wheelTurns
        val arc = RectF(cx - r, cy - r, cx + r, cy + r)
        WheelLook.fan(canvas, cx, cy, r, d)
        val step = 180f / n
        val rot = if (turns) spin.rot else 0f
        // The ticks turn with the ring, so the rim reads as something you can grab and roll.
        WheelLook.ticks(canvas, cx, cy, r, d, rot)
        if (wheelPick >= 0) {
            val (fx, fy) = slotCenter(wheelPick.toFloat())
            WheelLook.beam(canvas, cx, cy, fx, fy, arc, 180f + wheelPick * step + 1.5f, step - 3f, d)
        }
        val picked = if (wheelPick >= 0) Math.round(spin.target / step) + wheelPick else Int.MIN_VALUE
        val chord = 2 * r * 0.74f * kotlin.math.sin(Math.toRadians((step / 2).toDouble())).toFloat()
        val base = kotlin.math.floor(rot / step).toInt()
        val frac = rot - base * step
        for (j in -1..n) {
            val i = base + j
            // Slot position minus the part of a step the ring has turned: tiles glide along the arc.
            val pos = j - frac / step
            val theta = 180f - (pos + 0.5f) * step
            if (theta < -step * 0.5f || theta > 180f + step * 0.5f) continue
            val alpha = (minOf(theta, 180f - theta) / (step * 0.5f)).coerceIn(0f, 1f)
            if (alpha <= 0.02f) continue
            if (!turns && (j < 0 || j >= n)) continue
            val item = wheelItems[((i % size) + size) % size]
            val (x, y) = slotCenter(pos)
            val hot = i == picked
            val layer = if (alpha < 1f) canvas.saveLayerAlpha(x - 40 * d, y - 40 * d, x + 40 * d, minOf(y + 50 * d, height.toFloat()), (alpha * 255).toInt()) else -1
            WheelLook.tile(canvas, x, y, (if (hot) 26f else 24f) * d, d)
            if (item.suggested && !hot) WheelLook.ring(canvas, x, y, 29f * d, WheelLook.blue, 2.5f * d)
            val tint = if (item.tint != 0) item.tint else if (hot) WheelLook.blue else WheelLook.ink
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
            if (!hot) {
                var size2 = 10.5f * sp
                letters.textSize = size2
                val tw = letters.measureText(item.label)
                if (tw > chord * 0.92f) size2 *= chord * 0.92f / tw
                WheelLook.drawText(canvas, item.label, x, minOf(y + 37 * d, height - 8 * d), size2, WheelLook.label)
            }
            if (layer >= 0) canvas.restoreToCount(layer)
        }
        // Little arrows at both ends say the ring turns: slide along the rim, or rest a finger there.
        if (turns) {
            WheelLook.chevron(canvas, cx - r * 0.86f, cy - 10f * d, -1, edgeDir == -1, d)
            WheelLook.chevron(canvas, cx + r * 0.86f, cy - 10f * d, 1, edgeDir == 1, d)
        }
        // The middle is where the finger starts: lifting there does nothing.
        WheelLook.glow(canvas, cx, cy, d)
        WheelLook.cancel(canvas, cx, cy, d, sp)
        // What lifting the finger will do, in a pill above the wheel.
        val entry = wheelEntryAt(wheelPick)
        val hint = wheelItems.take(n).firstOrNull { it.suggested }?.let { "Try: ${it.label}" }
        val label = when {
            entry >= 0 -> wheelItems[entry].label
            hint != null -> hint
            turns -> "Roll the rim for more tools"
            else -> "Slide to choose · release to cancel"
        }
        val color = if (entry >= 0 || hint != null) WheelLook.blue else WheelLook.ink
        WheelLook.drawPill(canvas, WheelLook.pillRect(cx, label, d, sp), label, color, d, sp)
    }

    /** Centre of a (possibly fractional) slot along the fan: 0 is the far-left slot. */
    private fun slotCenter(slot: Float): Pair<Float, Float> {
        val step = 180f / visibleSlots
        val angle = Math.toRadians((180 - (slot + 0.5f) * step).toDouble())
        return Pair(wheelCx + (wheelR * 0.74f * kotlin.math.cos(angle)).toFloat(), wheelCy - (wheelR * 0.74f * kotlin.math.sin(angle)).toFloat())
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
            // Release: the ring locks where it was heading and the entry under the finger runs.
            spin.settle()
            val entry = wheelEntryAt(wheelPick)
            wheelOpen = false
            wheelPick = -1
            rimLastAngle = Float.NaN
            stopEdge()
            presses.remove(id)
            if (entry >= 0) listener?.onWheel(entry)
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
                    // Rolling the rim turns the ring one icon at a time, following the finger's direction.
                    if (wheelTurns && onBezel(wheelX, wheelY)) {
                        val a = fanAngle(wheelX, wheelY)
                        if (!rimLastAngle.isNaN()) spin.drag(a - rimLastAngle)
                        rimLastAngle = a
                    } else rimLastAngle = Float.NaN
                    // Resting low at either end keeps turning that way, one locked icon after another.
                    val edge = edgeAt(wheelX, wheelY)
                    if (edge != edgeDir) {
                        stopEdge()
                        edgeDir = edge
                        if (edge != 0) handler.post(edgeTurn)
                    }
                    val pick = wheelAt(wheelX, wheelY)
                    if (pick != wheelPick) {
                        wheelPick = pick
                        if (pick >= 0) listener?.onPress(Type.MODE)
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
                spin.reset()
                rimLastAngle = Float.NaN
                stopEdge()
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
        spin.reset()
        presses.clear()
    }
}
