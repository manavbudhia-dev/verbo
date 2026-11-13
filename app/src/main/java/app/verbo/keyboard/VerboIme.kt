package app.verbo.keyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * The keyboard service. Typing is handled by [KeyboardView]; this class keeps track of the word
 * being typed (for suggestions and auto-correct), and runs assistant actions:
 *  - holding the wide bar hands the text in the field to the assistant and replaces it with the result;
 *  - skill keys above the keys apply one saved instruction to the text;
 *  - the microphone dictates, or takes a spoken request when held.
 */
open class VerboIme : InputMethodService(), KeyboardView.Listener {

    private lateinit var store: Store
    private lateinit var p: KeyTheme
    private val main = Handler(Looper.getMainLooper())
    // One thread per action, so a cancelled or slow request (or a dashboard sync) never holds up the next one.
    private val worker = Executors.newCachedThreadPool()
    internal val dictionary = Dictionary()
    private lateinit var fb: Feedback

    internal var keys: KeyboardView? = null
    private var chipsRow: LinearLayout? = null
    private var chipsScroll: View? = null
    private var suggestRow: LinearLayout? = null
    private var statusRow: LinearLayout? = null
    private var panel: LinearLayout? = null // takes the place of the keys while an action runs and to show its result
    private var typingViews: List<View> = emptyList()
    private var contextBox: LinearLayout? = null
    private var contextText: String? = null
    private var wheelIds: List<String> = emptyList()
    private var voiceCommand = false
    private var levels: LevelView? = null
    private var heardView: TextView? = null // a copied message the user chose to give the assistant as context
    private var recognizer: SpeechRecognizer? = null
    private var builtWith = ""

    // settings read once per field so the typing path never touches storage
    private var correctOn = true
    private var suggestOn = true
    private var assistantOn = true
    private var agentMode = "auto"
    private var skills: List<Skill> = emptyList()

    // what we know about the text around the cursor, tracked locally to avoid asking the app on every key
    private val word = StringBuilder()
    private var wordAtSentenceStart = false
    private var sentenceStart = false
    private var pendingStop = false
    private var cursor = 0
    private val expected = ArrayDeque<Int>()
    private val expectedAt = ArrayDeque<Long>()
    private var midWord = false // the cursor sits inside a word, so it must not be "corrected" or completed
    private var lastTyped = ' '
    private var lastCorrection: Triple<String, String, String>? = null // typed, replaced with, separator
    private var spaceAfterWordAt = 0L
    private var textField = false
    private var numericField = false
    private var lastShiftTap = 0L
    internal var suggestions: List<String> = emptyList()
    private var slashMatches: List<Skill> = emptyList()
    private var highlighted = -1

    @Volatile private var generation = 0 // read by action threads to see whether they were cancelled
    private var listening = false
    private var busy = false
    private var undo: Pair<String, String>? = null // original text, text we put in its place

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        fb = Feedback(this)
        Thread { try { dictionary.load(assets.open("words.txt")) } catch (e: Exception) { } }.start()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        recognizer?.destroy()
        fb.release()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    // ---------- view construction ----------

    private fun signature() = "${store.theme}/${store.customAccent}/${store.heightPercent}/${store.keyShape}/${store.numberRow}/${resources.configuration.uiMode}"

    override fun onCreateInputView(): View {
        builtWith = signature()
        // The old views are gone (rotation, theme change); drop anything that was waiting to update them.
        generation++
        busy = false
        undo = null
        recognizer?.cancel()
        listening = false
        p = KeyTheme.of(this, store.theme, store.customAccent)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(0, dp(4), 0, dp(6))
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(0, dp(4), 0, dp(6) + insets.systemWindowInsetBottom)
                insets
            }
        }

        contextBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(2), dp(4), dp(4))
        }
        root.addView(contextBox, LinearLayout.LayoutParams(-1, -2))
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(2), dp(10), dp(4))
        }
        root.addView(panel, LinearLayout.LayoutParams(-1, dp(260)))

        val strip = FrameLayout(this)
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(3), 0, 0, 0)
            addView(chipsRow)
        }
        suggestRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), 0, dp(2), 0)
        }
        strip.addView(chipsScroll, FrameLayout.LayoutParams(-1, -1))
        strip.addView(suggestRow, FrameLayout.LayoutParams(-1, -1))
        strip.addView(statusRow, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(strip, LinearLayout.LayoutParams(0, -1, 1f))
        val mic = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(p.text)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = pressable(p.key, p.keyPressed, 18)
            contentDescription = "Voice. Hold for a voice command"
            setOnClickListener { feedback(KeyboardView.Type.MODE); startVoice(false) }
            setOnLongClickListener { feedback(KeyboardView.Type.MODE); startVoice(true); true }
        }
        top.addView(mic, LinearLayout.LayoutParams(dp(42), dp(36)).apply { setMargins(dp(4), 0, dp(6), 0) })
        root.addView(top, LinearLayout.LayoutParams(-1, dp(46)))

        keys = KeyboardView(this, p, store.heightPercent / 100f, store.keyShape, store.numberRow).also {
            it.listener = this
            root.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        typingViews = listOf(top, keys!!)
        loadSettings()
        buildChips()
        return root
    }

    private fun loadSettings() {
        fb.configure(store)
        correctOn = store.autoCorrect
        suggestOn = store.suggestions
        assistantOn = store.assistantOn
        agentMode = store.agentMode
        skills = store.skills
        val barActive = assistantOn && agentMode != "manual"
        wheelIds = if (barActive && store.wheelOn) store.wheel else emptyList()
        keys?.wheelItems = wheelIds.map { id ->
            when (id) {
                "ai" -> KeyboardView.WheelItem("Ask AI", R.drawable.ic_sparkles)
                "translate" -> KeyboardView.WheelItem("Translate", R.drawable.ic_languages)
                "rewrite" -> KeyboardView.WheelItem("Rewrite", R.drawable.ic_pencil_line)
                "voice" -> KeyboardView.WheelItem("Voice", R.drawable.ic_mic)
                "clips" -> KeyboardView.WheelItem("Clips", R.drawable.ic_clipboard)
                else -> {
                    val name = id.removePrefix("skill:")
                    val skill = skills.firstOrNull { it.name == name }
                    if (skill != null && skill.emoji.isNotEmpty()) KeyboardView.WheelItem(name, 0, skill.emoji) else KeyboardView.WheelItem(name, R.drawable.ic_zap)
                }
            }
        }
        keys?.barLabel = if (!barActive) "" else if (wheelIds.isEmpty()) "hold to act" else "hold for wheel"
        keys?.bound = if (assistantOn) skills.mapNotNull { it.key.firstOrNull() }.toSet() else emptySet()
    }

    private fun buildChips() {
        val row = chipsRow ?: return
        row.removeAllViews()
        if (!assistantOn) return
        if (contextText == null && hasFreshClip()) {
            val use = chipView("📋 Use copied text", p.accent, p.accentPressed, p.onAccent)
            use.setOnClickListener { feedback(KeyboardView.Type.MODE); useClipAsContext() }
            row.addView(use, chipParams())
        }
        for (skill in skills) {
            val chip = chipView((skill.emoji + " " + skill.name).trim(), p.key, p.keyPressed, p.text)
            chip.setOnClickListener { feedback(KeyboardView.Type.MODE); runAction(skill) }
            row.addView(chip, chipParams())
        }
        val add = chipView("＋ Skills", Color.TRANSPARENT, p.keyPressed, p.muted)
        add.setOnClickListener { openApp() }
        row.addView(add, chipParams())
    }

    private fun chipParams() = LinearLayout.LayoutParams(-2, dp(36)).apply { setMargins(dp(3), 0, dp(3), 0) }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun pressable(normal: Int, pressed: Int, radius: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, radius))
        addState(intArrayOf(), rounded(normal, radius))
    }

    private fun chipView(label: String, color: Int, pressed: Int, textColor: Int) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        maxLines = 1
        setPadding(dp(13), 0, dp(13), 0)
        background = pressable(color, pressed, 18)
    }

    // ---------- feedback ----------

    private fun feedback(type: KeyboardView.Type) = fb.press(type)

    override fun onPress(type: KeyboardView.Type) = feedback(type)

    // ---------- typing ----------

    /** Records where the cursor should be after one of our own edits, so we can tell them from the user's taps. */
    private fun expect(delta: Int) {
        cursor = (cursor + delta).coerceAtLeast(0)
        expected.addLast(cursor)
        expectedAt.addLast(android.os.SystemClock.uptimeMillis())
        if (expected.size > 48) { expected.removeFirst(); expectedAt.removeFirst() }
    }

    override fun onChar(text: String) {
        val ic = currentInputConnection ?: return
        clearResult()
        val c = text[0]
        val now = System.currentTimeMillis()

        if (c == ' ' && word.isEmpty() && textField && now - spaceAfterWordAt < 600) {
            // Double space after a word ends the sentence.
            spaceAfterWordAt = 0
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            ic.endBatchEdit()
            expect(1)
            sentenceStart = true
            lastCorrection = null
        } else if (c.isLetter() || (c == '\'' && word.isNotEmpty()) ||
            (c == '/' && word.isEmpty() && assistantOn && textField && lastTyped.isWhitespace())) { // "/" only starts a command after a space, not in 12/11
            if (word.isEmpty()) wordAtSentenceStart = sentenceStart
            word.append(text)
            ic.commitText(text, 1)
            expect(text.length)
            sentenceStart = false
            pendingStop = false
            lastCorrection = null
            if (c == '/') keys?.mode = KeyboardView.Mode.LETTERS
        } else {
            val typed = word.toString()
            val fix = if (correctOn && textField && !midWord && typed.isNotEmpty() && typed[0] != '/' && (c == ' ' || c in ".,!?;:"))
                dictionary.correction(typed, wordAtSentenceStart) else null
            if (fix != null) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(typed.length, 0)
                ic.commitText(fix + text, 1)
                ic.endBatchEdit()
                expect(fix.length - typed.length + text.length)
                lastCorrection = Triple(typed, fix, text)
            } else {
                ic.commitText(text, 1)
                expect(text.length)
                lastCorrection = null
            }
            spaceAfterWordAt = if (c == ' ' && typed.isNotEmpty()) now else 0
            word.setLength(0)
            midWord = false
            when {
                c == '.' || c == '!' || c == '?' -> pendingStop = true
                c == ' ' -> { if (pendingStop) sentenceStart = true; pendingStop = false }
                else -> pendingStop = false
            }
            if (c == ' ' && !numericField && keys?.mode != KeyboardView.Mode.LETTERS) keys?.mode = KeyboardView.Mode.LETTERS
        }
        lastTyped = text.last()
        applyCaps()
        updateSuggestions()
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        clearResult()
        val corrected = lastCorrection
        when {
            corrected != null -> {
                // Backspace straight after an auto-correction puts back what was typed.
                val (typed, fix, sep) = corrected
                ic.beginBatchEdit()
                ic.deleteSurroundingText(fix.length + sep.length, 0)
                ic.commitText(typed + sep, 1)
                ic.endBatchEdit()
                expect(typed.length - fix.length)
                lastCorrection = null
            }
            word.isNotEmpty() -> {
                ic.deleteSurroundingText(1, 0)
                word.setLength(word.length - 1)
                expect(-1)
            }
            else -> {
                // Outside the word we typed we don't know what's there; the app's selection update re-syncs us.
                if (!TextUtils.isEmpty(ic.getSelectedText(0))) {
                    ic.commitText("", 1)
                } else {
                    val before = ic.getTextBeforeCursor(2, 0)
                    val pair = before != null && before.length == 2 && Character.isSurrogatePair(before[0], before[1])
                    ic.deleteSurroundingText(if (pair) 2 else 1, 0)
                }
                expected.clear()
                expectedAt.clear()
                cursor = -1
            }
        }
        lastTyped = if (word.isNotEmpty()) word.last() else 'x' // unknown until the app reports back
        spaceAfterWordAt = 0
        applyCaps()
        updateSuggestions()
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        clearResult()
        val options = currentInputEditorInfo?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val wantsAction = options and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 &&
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (wantsAction) ic.performEditorAction(action) else ic.commitText("\n", 1)
        expected.clear()
        expectedAt.clear()
        cursor = -1
        word.setLength(0)
        midWord = false
        lastTyped = ' '
        lastCorrection = null
        sentenceStart = true
        applyCaps()
        updateSuggestions()
    }

    override fun onShift() {
        val view = keys ?: return
        val now = System.currentTimeMillis()
        view.shift = when {
            view.shift == 2 -> 0
            now - lastShiftTap < 350 -> 2
            view.shift == 1 -> 0
            else -> 1
        }
        lastShiftTap = now
    }

    override fun onBarHeld() {
        feedback(KeyboardView.Type.ENTER)
        runAction(null)
    }

    /** A shortcut on the wheel was chosen. */
    override fun onWheel(index: Int) {
        val id = wheelIds.getOrNull(index) ?: return
        feedback(KeyboardView.Type.ENTER)
        when (id) {
            "ai" -> runAction(null)
            "translate" -> showLanguages()
            "rewrite" -> showRewrite()
            "voice" -> startVoice(false)
            "clips" -> showClips()
            else -> skills.firstOrNull { it.name == id.removePrefix("skill:") }?.let { runAction(it) } ?: showStatus("That skill no longer exists")
        }
    }

    /** A letter with a skill bound to it was held: run that skill instead of typing the letter. */
    override fun onKeyHeld(letter: Char) {
        val skill = skills.firstOrNull { it.key == letter.lowercase() } ?: return
        feedback(KeyboardView.Type.ENTER)
        runAction(skill)
    }

    // ---------- context from a copied message ----------

    /** True when text was copied in the last few minutes. Looks only at the clip's description, not its content. */
    private fun hasFreshClip(): Boolean = try {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val description = clipboard.primaryClipDescription
        description != null && (description.hasMimeType("text/plain") || description.hasMimeType("text/html")) &&
            System.currentTimeMillis() - description.timestamp < 5 * 60 * 1000
    } catch (e: Exception) {
        false
    }

    private fun useClipAsContext() {
        val text = try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrEmpty()) {
            showStatus("Nothing copied to use")
            return
        }
        contextText = text.take(4000)
        refreshContext()
        buildChips()
    }

    private fun refreshContext() {
        val box = contextBox ?: return
        box.removeAllViews()
        val text = contextText
        if (text == null || panel?.visibility == View.VISIBLE) {
            box.visibility = View.GONE
            return
        }
        box.addView(View(this).apply { background = rounded(p.accent, 2) }, LinearLayout.LayoutParams(dp(3), dp(30)))
        box.addView(TextView(this).apply {
            this.text = "“$text”"
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(6), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val close = chipView("✕", Color.TRANSPARENT, p.keyPressed, p.muted)
        close.contentDescription = "Remove context"
        close.setOnClickListener { contextText = null; refreshContext(); buildChips() }
        box.addView(close, LinearLayout.LayoutParams(dp(40), dp(36)))
        box.visibility = View.VISIBLE
    }

    /** Capitalise the next letter when the field asks for it (start of a sentence, name fields). */
    private fun applyCaps() {
        val view = keys ?: return
        if (view.mode != KeyboardView.Mode.LETTERS || view.shift == 2) return
        val type = currentInputEditorInfo?.inputType ?: 0
        view.shift = when {
            type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT -> 0
            type and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0 -> 1
            word.isNotEmpty() -> 0
            type and InputType.TYPE_TEXT_FLAG_CAP_WORDS != 0 -> 1
            type and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0 && sentenceStart -> 1
            else -> 0
        }
    }

    /** Ask the app what is really around the cursor. Only done when the cursor moved without us. */
    private fun resync() {
        val ic = currentInputConnection
        word.setLength(0)
        lastCorrection = null
        pendingStop = false
        wordAtSentenceStart = false
        spaceAfterWordAt = 0
        val type = currentInputEditorInfo?.inputType ?: 0
        midWord = false
        lastTyped = ' '
        if (ic != null) {
            val before = ic.getTextBeforeCursor(40, 0)?.toString().orEmpty()
            lastTyped = before.lastOrNull() ?: ' '
            if (textField && TextUtils.isEmpty(ic.getSelectedText(0))) {
                word.append(before.takeLastWhile { it.isLetter() || it == '\'' }.trimStart('\''))
                midWord = ic.getTextAfterCursor(1, 0)?.firstOrNull()?.isLetter() == true
            }
            sentenceStart = type != InputType.TYPE_NULL && ic.getCursorCapsMode(type) != 0
        }
        applyCaps()
        updateSuggestions()
    }

    /** The keyboard was put away: give back the microphone service and the audio player until it is needed again. */
    override fun onWindowHidden() {
        super.onWindowHidden()
        recognizer?.destroy()
        recognizer = null
        listening = false
        fb.release()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        fb.configure(store)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Positions we predicted long ago were never going to be reported (some apps report only the
        // last of several quick edits). Forget them, or a later tap there would look like our own edit.
        val cutoff = android.os.SystemClock.uptimeMillis() - 1500
        while (expectedAt.isNotEmpty() && expectedAt.first() < cutoff) { expected.removeFirst(); expectedAt.removeFirst() }
        if (newSelStart == newSelEnd) {
            if (expected.contains(newSelStart)) {
                do { expectedAt.removeFirst() } while (expected.removeFirst() != newSelStart)
                return
            }
            if (expected.isEmpty() && newSelStart == cursor) return
        }
        expected.clear()
        expectedAt.clear()
        cursor = newSelStart
        resync()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) {
            if (store.locked && getSystemService(UserManager::class.java)?.isUserUnlocked == true) store = Store(this)
            if (signature() != builtWith) setInputView(onCreateInputView())
            keys?.mode = KeyboardView.Mode.LETTERS
            keys?.shift = 0
            generation++ // a result meant for another field must not land here
            busy = false
            undo = null
            loadSettings()
            buildChips()
            contextText = null // context belongs to the conversation it was copied for
            hidePanel()
            showTyping()
            recognizer?.cancel()
            listening = false
            syncIfStale()
        }
        val type = info?.inputType ?: 0
        val variation = type and InputType.TYPE_MASK_VARIATION
        textField = type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT && !isPasswordField() &&
            type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0 &&
            variation != InputType.TYPE_TEXT_VARIATION_URI && variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
            variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS && variation != InputType.TYPE_TEXT_VARIATION_FILTER
        // Number, phone and date fields open on the digits page and stay there.
        val cls = type and InputType.TYPE_MASK_CLASS
        numericField = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
        if (!restarting && numericField) keys?.mode = KeyboardView.Mode.SYMBOLS
        expected.clear()
        expectedAt.clear()
        cursor = info?.initialSelStart ?: -1
        resync()
    }

    /** While paired with a dashboard, refresh skills and connections every so often in the background. */
    private fun syncIfStale() {
        if (fb.saving) return // Battery Saver: no background network; "Sync now" in the app still works
        if (store.deviceToken.isEmpty() || System.currentTimeMillis() - store.lastSync < Sync.STALE_MS) return
        store.lastSync = System.currentTimeMillis() // don't retry on every field while offline
        worker.execute {
            try {
                Sync.pull(store)
                main.post { loadSettings(); buildChips() }
            } catch (e: Exception) {
                // Keep using the last synced settings.
            }
        }
    }

    // ---------- suggestions ----------

    private fun updateSuggestions() {
        suggestions = emptyList()
        slashMatches = emptyList()
        highlighted = -1
        val typed = word.toString()
        if (typed.isNotEmpty() && typed[0] == '/') {
            val query = typed.substring(1).lowercase()
            slashMatches = skills.filter { it.name.lowercase().replace(" ", "").startsWith(query) }.take(3)
        } else if (suggestOn && textField && !midWord && typed.isNotEmpty()) {
            val fix = if (correctOn) dictionary.correction(typed, wordAtSentenceStart) else null
            // Likely completions first; the word auto-correct would put in (if any) goes last and is highlighted.
            val completions = dictionary.completions(typed, 3)
            suggestions = if (fix == null || fix in completions) completions else completions.take(2) + fix
            highlighted = suggestions.indexOf(fix)
        }
        if (statusRow?.visibility != View.VISIBLE) showTyping()
    }

    /** The strip shows suggestions while a word is being typed, and the skill keys otherwise. */
    private fun showTyping() {
        statusRow?.visibility = View.GONE
        val row = suggestRow ?: return
        val labels = if (slashMatches.isNotEmpty()) slashMatches.map { (it.emoji + " " + it.name).trim() } else suggestions
        if (labels.isEmpty()) {
            row.visibility = View.GONE
            chipsScroll?.visibility = View.VISIBLE
            return
        }
        while (row.childCount < 3) {
            val index = row.childCount
            val cell = TextView(this).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                background = pressable(Color.TRANSPARENT, p.keyPressed, 10)
                setOnClickListener { feedback(KeyboardView.Type.MODE); pick(index) }
            }
            row.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })
        }
        for (i in 0 until 3) {
            val cell = row.getChildAt(i) as TextView
            cell.text = labels.getOrNull(i) ?: ""
            cell.isClickable = i < labels.size
            val strong = i == highlighted || (slashMatches.isNotEmpty() && i == 0)
            cell.setTextColor(if (strong) p.accent else p.text)
            cell.typeface = if (strong) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        chipsScroll?.visibility = View.GONE
        row.visibility = View.VISIBLE
    }

    internal fun pick(index: Int) {
        val ic = currentInputConnection ?: return
        val typed = word.length
        val skill = slashMatches.getOrNull(index)
        if (skill != null) {
            // "/polish" style command: remove the command, then run the skill on what's left.
            ic.deleteSurroundingText(typed, 0)
            expect(-typed)
            word.setLength(0)
            updateSuggestions()
            runAction(skill)
            return
        }
        val chosen = suggestions.getOrNull(index) ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(typed, 0)
        ic.commitText("$chosen ", 1)
        ic.endBatchEdit()
        expect(chosen.length + 1 - typed)
        word.setLength(0)
        lastTyped = ' '
        lastCorrection = null
        sentenceStart = false
        spaceAfterWordAt = 0
        applyCaps()
        updateSuggestions()
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
        // Requests typed for the bar are answers to look at first, so they open as a card with Copy and Insert.
        // Skill keys rewrite your own text, so they apply straight away unless Agent Mode is "Ask first".
        runTask(
            if (skill == null) "Working on it…" else "${skill.name}…",
            skill?.let { Ai.skillTask(it.instruction) }, card = skill == null || agentMode == "ask",
        ) { runAction(skill) }
    }

    /** Runs [task] (null = "do what the text says") on the field's text, with the copied context if one is attached. */
    private fun runTask(label: String, task: String?, card: Boolean, retry: () -> Unit) {
        if (busy) return
        if (isPasswordField()) {
            showStatus("Verbo doesn't read password fields")
            return
        }
        if (store.activeKey.isBlank()) {
            showStatus("Add your ${store.providerName} API key to use actions", "Set up" to { openApp() })
            return
        }
        val target = readTarget() ?: return
        val context = contextText
        if (target.text.isBlank() && context == null) {
            showStatus("Type something first, or copy a message and tap “Use copied text”")
            return
        }
        if (target.text.length >= MAX_CHARS) {
            showStatus("That's too much text for one action. Select a part of it")
            return
        }
        launch(label, task, Ai.userMessage(target.text, context), connected = true, card = card) { result ->
            if (card) showResult(result, { deliver(target, result) }) { hidePanel(); retry() }
            else deliver(target, result)
        }
    }

    // ---------- wheel tools: translate, rewrite, clips ----------

    /** A panel with a title and a two-column grid of choices. */
    private fun showChoices(title: String, choices: List<Pair<String, () -> Unit>>, highlightFirst: Boolean = false) = showPanel {
        val header = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(iconButton(R.drawable.ic_chevron_right, "Back to keyboard") { hidePanel(); showTyping() }.apply { rotation = 180f },
            LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(TextView(this@VerboIme).apply {
            text = title
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(header)
        val grid = LinearLayout(this@VerboIme).apply { orientation = LinearLayout.VERTICAL }
        choices.chunked(2).forEachIndexed { r, pair ->
            val row = LinearLayout(this@VerboIme)
            pair.forEachIndexed { c, (label, onTap) ->
                val strong = highlightFirst && r == 0 && c == 0
                val chip = chipView(label, if (strong) p.accent else p.key, if (strong) p.accentPressed else p.keyPressed, if (strong) p.onAccent else p.text)
                chip.ellipsize = TextUtils.TruncateAt.END
                chip.setOnClickListener { feedback(KeyboardView.Type.MODE); onTap() }
                row.addView(chip, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            if (pair.size == 1) row.addView(View(this@VerboIme), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
            grid.addView(row)
        }
        addView(android.widget.ScrollView(this@VerboIme).apply { addView(grid) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun showLanguages() {
        val mine = store.languages
        fun go(languages: List<String>) {
            hidePanel()
            runTask(if (languages.size == 1) "Into ${languages[0]}…" else "Translating…", Ai.translateTask(languages), card = true) { showLanguages() }
        }
        val choices = ArrayList<Pair<String, () -> Unit>>()
        if (mine.size > 1) choices += "All ${mine.size} of my languages" to { go(mine) }
        mine.forEach { choices += it to { go(listOf(it)) } }
        Store.ALL_LANGUAGES.filter { it !in mine }.forEach { choices += it to { go(listOf(it)) } }
        showChoices("Translate into", choices, highlightFirst = mine.size > 1)
    }

    private fun showRewrite() {
        val styles = skills.filter { it.category == "edit" }
        if (styles.isEmpty()) {
            showStatus("No rewrite skills yet. Add one in the Verbo app")
            return
        }
        showChoices("Rewrite as", styles.map { skill -> (skill.emoji + " " + skill.name).trim() to { hidePanel(); runAction(skill) } })
    }

    private fun showClips(): Unit = showPanel {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val current = try { clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@VerboIme)?.toString()?.trim().orEmpty() } catch (e: Exception) { "" }
        val saved = store.clips
        val header = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(iconButton(R.drawable.ic_chevron_right, "Back to keyboard") { hidePanel(); showTyping() }.apply { rotation = 180f },
            LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(TextView(this@VerboIme).apply {
            text = "Clips"
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(header)
        val list = LinearLayout(this@VerboIme).apply { orientation = LinearLayout.VERTICAL }
        fun row(text: String, action: String, onAction: () -> Unit) {
            val row = LinearLayout(this@VerboIme).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = pressable(p.key, p.keyPressed, 12)
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setOnClickListener {
                    feedback(KeyboardView.Type.MODE)
                    hidePanel()
                    currentInputConnection?.commitText(text, 1)
                    showTyping()
                }
            }
            row.addView(TextView(this@VerboIme).apply {
                this.text = text
                setTextColor(p.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val button = chipView(action, Color.TRANSPARENT, p.keyPressed, p.accent)
            button.typeface = Typeface.DEFAULT_BOLD
            button.setOnClickListener { feedback(KeyboardView.Type.MODE); onAction() }
            row.addView(button, LinearLayout.LayoutParams(-2, dp(36)))
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        if (current.isNotEmpty() && current !in saved) row(current.take(2000), "Save") { store.clips = listOf(current.take(2000)) + saved; showClips() }
        saved.forEach { clip -> row(clip, "Remove") { store.clips = saved - clip; showClips() } }
        if (current.isEmpty() && saved.isEmpty()) list.addView(TextView(this@VerboIme).apply {
            text = "Nothing copied yet. Copy some text, then save it here to paste it any time."
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(8), dp(16), dp(8), 0)
        })
        addView(android.widget.ScrollView(this@VerboIme).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** Runs one assistant request off the main thread and hands the result back, unless it was cancelled. */
    private fun launch(label: String, task: String?, input: String, connected: Boolean, card: Boolean = false, onResult: (ActionResult) -> Unit) {
        if (busy) return
        busy = true
        undo = null
        val token = ++generation
        val openAi = store.isOpenAi
        val custom = store.isCustom
        val base = store.customBase
        val key = store.activeKey
        val model = store.activeModel
        val web = connected && store.webSearch
        val tools = if (connected) store.tools() else emptyList()
        val cancel = {
            generation++
            busy = false
            hidePanel()
            showTyping()
        }
        if (card) showLoading(label, cancel) else showStatus(label, "Cancel" to cancel)
        worker.execute {
            val outcome = try {
                val cancelled = { token != generation }
                Result.success(when {
                    custom -> ChatCompat.run(base, key, model, task, input, tools, cancelled)
                    openAi -> OpenAi.run(key, model, web, task, input, tools, cancelled)
                    else -> Ai.run(key, model, web, task, input, tools, cancelled)
                })
            } catch (e: AiException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(AiException("Something went wrong. Try again"))
            }
            main.post {
                if (token != generation) return@post
                busy = false
                outcome.fold(onResult) { hidePanel(); showStatus(it.message ?: "Failed") }
            }
        }
    }

    // ---------- the panel that replaces the keys ----------

    private fun showPanel(fill: LinearLayout.() -> Unit) {
        val box = panel ?: return
        box.removeAllViews()
        // Same height as the keys and strip it replaces, so the app above doesn't jump.
        val height = typingViews.sumOf { it.height }
        box.layoutParams = box.layoutParams.apply { this.height = maxOf(height, dp(250)) }
        box.gravity = Gravity.TOP
        box.fill()
        typingViews.forEach { it.visibility = View.GONE }
        box.visibility = View.VISIBLE
        refreshContext()
    }

    private fun hidePanel() {
        val box = panel ?: return
        if (box.visibility != View.VISIBLE) return
        box.removeAllViews()
        box.visibility = View.GONE
        typingViews.forEach { it.visibility = View.VISIBLE }
        refreshContext()
    }

    private fun iconButton(res: Int, description: String, tint: Int = p.text, onTap: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint)
        setPadding(dp(9), dp(9), dp(9), dp(9))
        background = pressable(Color.TRANSPARENT, p.keyPressed, 20)
        contentDescription = description
        setOnClickListener { feedback(KeyboardView.Type.MODE); onTap() }
    }

    private fun showLoading(label: String, cancel: () -> Unit) = showPanel {
        gravity = Gravity.CENTER
        addView(ImageView(this@VerboIme).apply {
            setImageResource(R.drawable.ic_sparkles)
            setColorFilter(p.accent)
            // A slow pulse while waiting.
            android.animation.ObjectAnimator.ofFloat(this, "alpha", 1f, 0.3f).apply {
                duration = 700
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(TextView(this@VerboIme).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(12), 0, dp(18))
        })
        addView(iconButton(R.drawable.ic_x, "Cancel", p.muted, cancel).apply { background = pressable(p.key, p.keyPressed, 22) },
            LinearLayout.LayoutParams(dp(44), dp(44)))
    }

    /** The result as a card: back, try again, copy, and insert into the field (or send, where the app allows it). */
    internal fun showResult(result: ActionResult, onInsert: () -> Unit, onRetry: (() -> Unit)?) = showPanel {
        val header = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(iconButton(R.drawable.ic_chevron_right, "Back to keyboard") { hidePanel(); showTyping() }.apply { rotation = 180f },
            LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(TextView(this@VerboIme).apply {
            text = "Result"
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(dp(4), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (onRetry != null) header.addView(iconButton(R.drawable.ic_refresh_cw, "Try again", p.muted, onRetry), LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(header)

        val card = LinearLayout(this@VerboIme).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.key, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        result.map?.let { (lat, lon) ->
            val map = ImageView(this@VerboIme).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(p.fnKey, 12)
                clipToOutline = true
                contentDescription = "Map of the place"
            }
            card.addView(map, LinearLayout.LayoutParams(-1, dp(86)).apply { bottomMargin = dp(10) })
            val token = generation
            worker.execute {
                val bitmap = MapPreview.load(lat, lon)
                main.post { if (bitmap != null && token == generation && map.isAttachedToWindow) map.setImageBitmap(bitmap) }
            }
        }
        // A short first line reads as the title of the card.
        val lines = result.text.lines()
        val titled = lines.size >= 2 && lines[0].length <= 60
        if (titled) card.addView(TextView(this@VerboIme).apply {
            text = lines[0]
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        })
        val body = if (titled) lines.drop(1).joinToString("\n").trim() else result.text
        if (body.isNotEmpty()) card.addView(TextView(this@VerboIme).apply {
            text = body
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(3).toFloat(), 1f)
            android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
            setLinkTextColor(p.accent)
            linksClickable = false
            movementMethod = null
        })
        result.openUri?.let { uri ->
            val open = chipView(openLabel(uri), p.fnKey, p.keyPressed, p.accent)
            open.typeface = Typeface.DEFAULT_BOLD
            open.setOnClickListener { feedback(KeyboardView.Type.MODE); openLink(uri) }
            card.addView(open, LinearLayout.LayoutParams(-2, dp(36)).apply { topMargin = dp(10) })
        }
        addView(android.widget.ScrollView(this@VerboIme).apply { addView(card) }, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(2)) }
        val copy = chipView("Copy", p.key, p.keyPressed, p.text)
        copy.typeface = Typeface.DEFAULT_BOLD
        copy.setOnClickListener {
            feedback(KeyboardView.Type.MODE)
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("Verbo result", result.text))
            copy.text = "Copied"
        }
        footer.addView(copy, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
        // Chat apps whose enter key sends let us send in one tap; elsewhere the text is put in the field.
        val options = currentInputEditorInfo?.imeOptions ?: 0
        val sends = options and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEND && options and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0
        if (result.text.isNotEmpty()) {
            val insert = chipView(if (sends) "↑  Send" else "↑  Insert", p.accent, p.accentPressed, p.onAccent)
            insert.typeface = Typeface.DEFAULT_BOLD
            insert.setOnClickListener {
                feedback(KeyboardView.Type.ENTER)
                hidePanel()
                onInsert()
                if (sends) currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEND)
            }
            footer.addView(insert, LinearLayout.LayoutParams(0, dp(46), 1.7f))
        }
        addView(footer)
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
                contextText = null
                refreshContext()
                showTyping()
            }, open).toTypedArray())
            return
        }
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (target.selection == null) ic.deleteSurroundingText(target.before.length, target.after.length)
        ic.commitText(result.text, 1)
        ic.endBatchEdit()
        undo = target.text to result.text
        contextText = null
        refreshContext()
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
        showTyping()
    }

    /** Plain web links show where they lead, so a link the assistant picked up from a web page can't hide its destination. */
    private fun openLabel(uri: String) = when {
        uri.startsWith("geo:", true) -> "Open map"
        uri.startsWith("tel:", true) -> "Call"
        uri.startsWith("mailto:", true) -> "Open in Gmail"
        uri.startsWith("https://wa.me", true) -> "WhatsApp"
        uri.startsWith("https://calendar.google.com", true) -> "Add to calendar"
        uri.startsWith("https://www.youtube.com", true) -> "YouTube"
        uri.startsWith("https://x.com", true) -> "Post on X"
        else -> "Open " + (Uri.parse(uri).host?.removePrefix("www.")?.take(28) ?: "link")
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

    // ---------- voice ----------

    /**
     * Opens the listening panel. "Transcribe" turns speech into tidy text; "Command" treats it as a request.
     * [command] picks which of the two happens if the user just stops talking.
     */
    private fun startVoice(command: Boolean) {
        if (busy || listening) return
        if (isPasswordField()) {
            showStatus("Verbo doesn't dictate into password fields")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showStatus("Allow the microphone in the Verbo app", "Set up" to { openApp() })
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showStatus("This phone has no speech recognition service")
            return
        }
        val smart = assistantOn && store.activeKey.isNotBlank()
        voiceCommand = command && smart
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partial: Bundle?) {
                val heard = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!heard.isNullOrBlank()) heardView?.text = heard
            }
            override fun onResults(results: Bundle?) {
                listening = false
                hidePanel()
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (heard.isNullOrBlank()) showStatus("Didn't catch that") else onHeard(heard, voiceCommand, smart)
            }
            override fun onError(error: Int) {
                listening = false
                hidePanel()
                showStatus(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No connection for speech"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing"
                    else -> "Voice input failed"
                })
            }
            override fun onRmsChanged(rmsdB: Float) { levels?.push(rmsdB) }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        showListening(rec, smart)
        listening = true
        rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    private fun showListening(rec: SpeechRecognizer, smart: Boolean) = showPanel {
        val header = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this@VerboIme).apply {
            text = "Listening…"
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconButton(R.drawable.ic_x, "Stop listening", p.muted) {
            rec.cancel()
            listening = false
            hidePanel()
            showTyping()
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(header)
        levels = LevelView(this@VerboIme, p.accent)
        addView(levels, LinearLayout.LayoutParams(-1, 0, 1f))
        heardView = TextView(this@VerboIme).apply {
            gravity = Gravity.CENTER
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.START
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        addView(heardView, LinearLayout.LayoutParams(-1, dp(48)))
        // Tapping either button stops listening and decides what happens to the words.
        val footer = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(2)) }
        fun mode(label: String, asCommand: Boolean, weight: Float) {
            val on = voiceCommand == asCommand
            val chip = chipView(label, if (on) p.accent else p.key, if (on) p.accentPressed else p.keyPressed, if (on) p.onAccent else p.text)
            chip.typeface = Typeface.DEFAULT_BOLD
            chip.setOnClickListener { feedback(KeyboardView.Type.MODE); voiceCommand = asCommand; rec.stopListening() }
            footer.addView(chip, LinearLayout.LayoutParams(0, dp(46), weight).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        if (smart) mode("Command", true, 1f)
        mode(if (smart) "Transcribe" else "Done", false, 1.3f)
        addView(footer)
    }

    private fun onHeard(heard: String, command: Boolean, smart: Boolean) {
        if (!smart || busy) { // if an action started while we were listening, keep the words rather than drop them
            insert(heard) // plain dictation still works without the assistant
            return
        }
        launch(
            if (command) "Working on it…" else "Tidying up…",
            if (command) null else Ai.DICTATION_TASK, heard, connected = command,
        ) { result ->
            val open = result.openUri?.let { uri -> openLabel(uri) to { openLink(uri) } }
            if (result.text.isEmpty()) showStatus("Ready", *listOfNotNull(open).toTypedArray())
            else if (agentMode == "ask") showResult(result, { insert(result.text, open) }, null)
            else insert(result.text, open)
        }
    }

    private fun insert(text: String, extra: Pair<String, () -> Unit>? = null) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        undo = "" to text
        showStatus("Done", *listOfNotNull("Undo" to { undoLast() }, extra).toTypedArray())
    }

    // ---------- status strip ----------

    private fun clearResult() {
        if (undo != null) undo = null
        if (!busy && statusRow?.visibility == View.VISIBLE) showTyping()
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
            button.setOnClickListener { feedback(KeyboardView.Type.MODE); onTap() }
            row.addView(button, chipParams())
        }
        if (actions.none { it.first == "Cancel" || it.first == "Discard" }) {
            val close = chipView("✕", Color.TRANSPARENT, p.keyPressed, p.muted)
            close.setOnClickListener { showTyping() }
            row.addView(close, LinearLayout.LayoutParams(dp(40), dp(36)))
        }
        chipsScroll?.visibility = View.GONE
        suggestRow?.visibility = View.GONE
        row.visibility = View.VISIBLE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val MAX_CHARS = 20000
    }
}

/** A row of bars that rise and fall with the microphone level while listening. */
private class LevelView(context: android.content.Context, color: Int) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val bars = FloatArray(15) { 0.08f }

    /** [rms] is the recogniser's loudness reading, roughly -2 (silence) to 10 (loud). */
    fun push(rms: Float) {
        System.arraycopy(bars, 1, bars, 0, bars.size - 1)
        bars[bars.size - 1] = ((rms + 2f) / 12f).coerceIn(0.08f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val d = resources.displayMetrics.density
        val w = 6 * d
        val gap = 6 * d
        val total = bars.size * w + (bars.size - 1) * gap
        var x = (width - total) / 2
        val mid = height / 2f
        val max = height * 0.42f
        for (b in bars) {
            canvas.drawRoundRect(x, mid - b * max, x + w, mid + b * max, w / 2, w / 2, paint)
            x += w + gap
        }
    }
}
