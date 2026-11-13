package app.verbo.keyboard

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * The keyboard. A normal QWERTY layout with two additions:
 *  - the wide bar on the bottom row types a space on tap and, on long-press, hands the
 *    text in the field to the assistant and replaces it with the result;
 *  - the strip above the keys holds skill keys that apply one saved instruction to the text.
 */
class VerboIme : InputMethodService() {

    private class Palette(night: Boolean) {
        val bg = Color.parseColor(if (night) "#15171C" else "#ECE7DF")
        val key = Color.parseColor(if (night) "#2A2D35" else "#FFFFFF")
        val keyPressed = Color.parseColor(if (night) "#3A3E48" else "#D9D2C7")
        val fnKey = Color.parseColor(if (night) "#1F2229" else "#D9D2C7")
        val text = Color.parseColor(if (night) "#F1EEE8" else "#1D1F24")
        val muted = Color.parseColor(if (night) "#9A9CA3" else "#6B6A66")
        val accent = Color.parseColor(if (night) "#FF8A4C" else "#E8590C")
        val accentPressed = Color.parseColor(if (night) "#E67636" else "#C94C08")
        val onAccent = Color.parseColor(if (night) "#15171C" else "#FFFFFF")
    }

    private enum class Mode { LETTERS, SYMBOLS, MORE_SYMBOLS }

    private lateinit var store: Store
    private lateinit var p: Palette
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var keysBox: LinearLayout? = null
    private var chipsRow: LinearLayout? = null
    private var chipsScroll: View? = null
    private var statusRow: LinearLayout? = null
    private var bar: TextView? = null
    private val letterKeys = ArrayList<TextView>()
    private var shiftKey: TextView? = null

    private var mode = Mode.LETTERS
    private var shift = 0 // 0 off, 1 next letter, 2 caps lock
    private var lastShiftTap = 0L

    private var generation = 0
    private var busy = false
    private var undo: Pair<String, String>? = null // original text, text we put in its place

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    // ---------- view construction ----------

    override fun onCreateInputView(): View {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        p = Palette(night)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(dp(3), dp(4), dp(3), dp(6))
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(dp(3), dp(4), dp(3), dp(6) + insets.systemWindowInsetBottom)
                insets
            }
        }

        val strip = FrameLayout(this)
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        }
        statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), 0, dp(2), 0)
        }
        strip.addView(chipsScroll, FrameLayout.LayoutParams(-1, -1))
        strip.addView(statusRow, FrameLayout.LayoutParams(-1, -1))
        root.addView(strip, LinearLayout.LayoutParams(-1, dp(44)))

        keysBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(keysBox, LinearLayout.LayoutParams(-1, -2))

        buildKeys()
        buildChips()
        return root
    }

    private fun buildKeys() {
        val box = keysBox ?: return
        box.removeAllViews()
        letterKeys.clear()
        shiftKey = null

        val rows: List<String> = when (mode) {
            Mode.LETTERS -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            Mode.SYMBOLS -> listOf("1234567890", "@#$&-+()/_", "*\"':;!?")
            Mode.MORE_SYMBOLS -> listOf("~`|•√π÷×¶∆", "£¢€¥^°={}\\", "%<>[]✓™")
        }

        rows.forEachIndexed { index, chars ->
            val row = newRow()
            if (index == 1 && mode == Mode.LETTERS) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 0.5f))
            if (index == 2) {
                when (mode) {
                    Mode.LETTERS -> shiftKey = fnKey(row, "⇧", 1.5f) { onShift() }
                    Mode.SYMBOLS -> fnKey(row, "=\\<", 1.5f) { setMode(Mode.MORE_SYMBOLS) }
                    Mode.MORE_SYMBOLS -> fnKey(row, "?123", 1.5f) { setMode(Mode.SYMBOLS) }
                }
            }
            for (c in chars) {
                val key = keyView(c.toString(), p.key, p.keyPressed, p.text, 22f)
                key.setOnClickListener { tick(it); type(key.text.toString()) }
                row.addView(key, keyParams(1f))
                if (mode == Mode.LETTERS) letterKeys.add(key)
            }
            if (index == 1 && mode == Mode.LETTERS) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 0.5f))
            if (index == 2) addBackspace(row)
            box.addView(row, LinearLayout.LayoutParams(-1, dp(52)))
        }

        val bottom = newRow()
        fnKey(bottom, if (mode == Mode.LETTERS) "?123" else "ABC", 1.5f) {
            setMode(if (mode == Mode.LETTERS) Mode.SYMBOLS else Mode.LETTERS)
        }
        val comma = keyView(",", p.key, p.keyPressed, p.text, 22f)
        comma.setOnClickListener { tick(it); type(",") }
        bottom.addView(comma, keyParams(1f))

        bar = keyView("", p.accent, p.accentPressed, p.onAccent, 13f).apply {
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            setOnClickListener { tick(it); type(" ") }
            setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                runAction(null)
                true
            }
        }
        bottom.addView(bar, keyParams(4.5f))
        refreshBar()

        val dot = keyView(".", p.key, p.keyPressed, p.text, 22f)
        dot.setOnClickListener { tick(it); type(".") }
        bottom.addView(dot, keyParams(1f))
        fnKey(bottom, "⏎", 1.5f) { onEnter() }
        box.addView(bottom, LinearLayout.LayoutParams(-1, dp(52)))

        refreshShift()
    }

    private fun buildChips() {
        val row = chipsRow ?: return
        row.removeAllViews()
        for (skill in store.skills) {
            val chip = chipView((skill.emoji + " " + skill.name).trim(), p.key, p.keyPressed, p.text)
            chip.setOnClickListener { tick(it); runAction(skill) }
            row.addView(chip, chipParams())
        }
        val add = chipView("＋ Skills", Color.TRANSPARENT, p.keyPressed, p.muted)
        add.setOnClickListener { openApp() }
        row.addView(add, chipParams())
    }

    private fun newRow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun keyParams(weight: Float) = LinearLayout.LayoutParams(0, -1, weight).apply {
        setMargins(dp(3), dp(4), dp(3), dp(4))
    }

    private fun chipParams() = LinearLayout.LayoutParams(-2, dp(34)).apply { setMargins(dp(3), 0, dp(3), 0) }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun pressable(normal: Int, pressed: Int, radius: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, radius))
        addState(intArrayOf(), rounded(normal, radius))
    }

    private fun keyView(label: String, color: Int, pressed: Int, textColor: Int, sizeSp: Float) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        maxLines = 1
        background = pressable(color, pressed, 8)
        isHapticFeedbackEnabled = true
    }

    private fun chipView(label: String, color: Int, pressed: Int, textColor: Int) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        maxLines = 1
        setPadding(dp(13), 0, dp(13), 0)
        background = pressable(color, pressed, 17)
    }

    private fun fnKey(row: LinearLayout, label: String, weight: Float, onTap: () -> Unit): TextView {
        val key = keyView(label, p.fnKey, p.keyPressed, p.text, if (label.length > 1) 14f else 20f)
        key.setOnClickListener { tick(it); onTap() }
        row.addView(key, keyParams(weight))
        return key
    }

    /** Backspace deletes on touch-down and repeats while held. */
    @SuppressLint("ClickableViewAccessibility")
    private fun addBackspace(row: LinearLayout) {
        val key = keyView("⌫", p.fnKey, p.keyPressed, p.text, 20f)
        val repeat = object : Runnable {
            override fun run() {
                backspace()
                main.postDelayed(this, 50)
            }
        }
        key.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    tick(v)
                    backspace()
                    main.postDelayed(repeat, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    main.removeCallbacks(repeat)
                }
            }
            true
        }
        row.addView(key, keyParams(1.5f))
    }

    // ---------- typing ----------

    private fun tick(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun type(s: String) {
        val ic = currentInputConnection ?: return
        clearResult()
        ic.commitText(s, 1)
        if (mode == Mode.LETTERS && shift == 1 && s != " ") {
            shift = 0
            refreshShift()
        }
        if (s == " " && mode != Mode.LETTERS) setMode(Mode.LETTERS)
        autoCaps()
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        clearResult()
        if (!TextUtils.isEmpty(ic.getSelectedText(0))) {
            ic.commitText("", 1)
        } else {
            // Remove a whole surrogate pair so emoji aren't left half-deleted.
            val before = ic.getTextBeforeCursor(2, 0)
            val pair = before != null && before.length == 2 &&
                Character.isSurrogatePair(before[0], before[1])
            ic.deleteSurroundingText(if (pair) 2 else 1, 0)
        }
        autoCaps()
    }

    private fun onEnter() {
        val ic = currentInputConnection ?: return
        clearResult()
        val options = currentInputEditorInfo?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val wantsAction = options and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 &&
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (wantsAction) ic.performEditorAction(action) else ic.commitText("\n", 1)
        autoCaps()
    }

    private fun onShift() {
        val now = System.currentTimeMillis()
        shift = when {
            shift == 2 -> 0
            now - lastShiftTap < 350 -> 2
            shift == 1 -> 0
            else -> 1
        }
        lastShiftTap = now
        refreshShift()
    }

    private fun refreshShift() {
        for (key in letterKeys) {
            val c = key.text.toString()
            key.text = if (shift > 0) c.uppercase() else c.lowercase()
        }
        shiftKey?.apply {
            text = if (shift == 2) "⇪" else "⇧"
            setTextColor(if (shift > 0) p.accent else p.text)
        }
    }

    private fun setMode(m: Mode) {
        mode = m
        buildKeys()
    }

    /** Capitalise the next letter when the field asks for it (start of a sentence, name fields). */
    private fun autoCaps() {
        if (mode != Mode.LETTERS || shift == 2) return
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val want = if (info.inputType != InputType.TYPE_NULL && ic.getCursorCapsMode(info.inputType) != 0) 1 else 0
        if (want != shift) {
            shift = want
            refreshShift()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) {
            mode = Mode.LETTERS
            shift = 0
            generation++ // a result meant for another field must not land here
            busy = false
            undo = null
            buildKeys()
            buildChips()
            showChips()
        }
        autoCaps()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        autoCaps()
    }

    // ---------- the action ----------

    private class Target(val before: String, val after: String, val selection: String?) {
        val text get() = selection ?: (before + after)
    }

    private fun isPasswordField(): Boolean {
        val type = currentInputEditorInfo?.inputType ?: return false
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** The selection if there is one, otherwise everything in the field. */
    private fun readTarget(): Target? {
        val ic = currentInputConnection ?: return null
        val selected = ic.getSelectedText(0)
        if (!TextUtils.isEmpty(selected)) return Target("", "", selected.toString())
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val full = extracted?.text
        if (full != null && extracted.selectionStart in 0..full.length) {
            return Target(full.substring(0, extracted.selectionStart), full.substring(extracted.selectionStart), null)
        }
        val before = ic.getTextBeforeCursor(MAX_CHARS, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(MAX_CHARS, 0)?.toString() ?: ""
        return Target(before, after, null)
    }

    private fun runAction(skill: Skill?) {
        if (busy) return
        if (isPasswordField()) {
            showStatus("Verbo doesn't read password fields")
            return
        }
        if (store.apiKey.isBlank()) {
            showStatus("Add your API key to use actions", "Set up" to { openApp() })
            return
        }
        val target = readTarget() ?: return
        if (target.text.isBlank()) {
            showStatus(if (skill == null) "Type what you want, then hold the bar" else "Type something first")
            return
        }
        if (target.text.length >= MAX_CHARS) {
            showStatus("That's too much text for one action. Select a part of it")
            return
        }

        busy = true
        undo = null
        val token = ++generation
        val key = store.apiKey
        val model = store.model
        val web = store.webSearch
        showStatus(if (skill == null) "Working on it…" else "${skill.name}…", "Cancel" to {
            generation++
            busy = false
            showChips()
        })

        worker.execute {
            val outcome = try {
                Result.success(Ai.run(key, model, web, skill?.instruction, target.text))
            } catch (e: AiException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(AiException("Something went wrong. Try again"))
            }
            main.post {
                if (token != generation) return@post
                busy = false
                outcome.fold({ deliver(target, it) }, { showStatus(it.message ?: "Failed") })
            }
        }
    }

    private fun deliver(target: Target, result: ActionResult) {
        val open = result.openUri?.let { uri -> openLabel(uri) to { openLink(uri) } }
        if (result.text.isEmpty()) {
            showStatus("Ready", *listOfNotNull(open).toTypedArray())
            return
        }
        // The user may have kept typing while we waited. Only overwrite text we actually read.
        val now = readTarget()
        val unchanged = now != null && now.selection == target.selection &&
            now.before == target.before && now.after == target.after
        if (!unchanged) {
            showStatus("Text changed while working", *listOfNotNull("Insert" to {
                currentInputConnection?.commitText(result.text, 1)
                showChips()
            }, open).toTypedArray())
            return
        }
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (target.selection == null) ic.deleteSurroundingText(target.before.length, target.after.length)
        ic.commitText(result.text, 1)
        ic.endBatchEdit()
        undo = target.text to result.text
        showStatus("Done", *listOfNotNull("Undo" to { undoLast() }, open).toTypedArray())
    }

    private fun undoLast() {
        val (original, replaced) = undo ?: return
        val ic = currentInputConnection ?: return
        if (ic.getTextBeforeCursor(replaced.length, 0)?.toString() == replaced) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(replaced.length, 0)
            ic.commitText(original, 1)
            ic.endBatchEdit()
        }
        undo = null
        showChips()
    }

    private fun openLabel(uri: String) = when {
        uri.startsWith("geo:", true) -> "Open map"
        uri.startsWith("tel:", true) -> "Call"
        uri.startsWith("mailto:", true) -> "Email"
        else -> "Open link"
    }

    private fun openLink(uri: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            showStatus("No app can open that")
        }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------- strip ----------

    private fun refreshBar() {
        bar?.text = "VERBO  ·  hold to act"
    }

    private fun clearResult() {
        if (!busy && statusRow?.visibility == View.VISIBLE) showChips()
        undo = null
    }

    private fun showChips() {
        statusRow?.visibility = View.GONE
        chipsScroll?.visibility = View.VISIBLE
    }

    private fun showStatus(message: String, vararg actions: Pair<String, () -> Unit>) {
        val row = statusRow ?: return
        row.removeAllViews()
        row.addView(TextView(this).apply {
            text = message
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        for ((label, onTap) in actions) {
            val button = chipView(label, p.accent, p.accentPressed, p.onAccent)
            button.typeface = Typeface.DEFAULT_BOLD
            button.setOnClickListener { tick(it); onTap() }
            row.addView(button, chipParams())
        }
        if (actions.none { it.first == "Cancel" }) {
            val close = chipView("✕", Color.TRANSPARENT, p.keyPressed, p.muted)
            close.setOnClickListener { showChips() }
            row.addView(close, LinearLayout.LayoutParams(dp(40), dp(34)))
        }
        chipsScroll?.visibility = View.GONE
        row.visibility = View.VISIBLE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val MAX_CHARS = 20000
    }
}
