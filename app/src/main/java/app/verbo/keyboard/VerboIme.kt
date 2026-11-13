package app.verbo.keyboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
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
    private val worker = Executors.newSingleThreadExecutor()
    internal val dictionary = Dictionary()
    private var audio: AudioManager? = null
    private var vibrator: Vibrator? = null

    internal var keys: KeyboardView? = null
    private var chipsRow: LinearLayout? = null
    private var chipsScroll: View? = null
    private var suggestRow: LinearLayout? = null
    private var statusRow: LinearLayout? = null
    private var previewBox: LinearLayout? = null
    private var recognizer: SpeechRecognizer? = null
    private var builtWith = ""

    // settings read once per field so the typing path never touches storage
    private var soundOn = true
    private var hapticOn = true
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
    private var lastCorrection: Triple<String, String, String>? = null // typed, replaced with, separator
    private var spaceAfterWordAt = 0L
    private var textField = false
    private var lastShiftTap = 0L
    internal var suggestions: List<String> = emptyList()
    private var slashMatches: List<Skill> = emptyList()
    private var highlighted = -1

    private var generation = 0
    private var busy = false
    private var undo: Pair<String, String>? = null // original text, text we put in its place

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        @Suppress("DEPRECATION")
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        Thread { try { dictionary.load(assets.open("words.txt")) } catch (e: Exception) { } }.start()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        recognizer?.destroy()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    // ---------- view construction ----------

    private fun signature() = "${store.theme}/${store.customAccent}/${store.heightPercent}/${resources.configuration.uiMode}"

    override fun onCreateInputView(): View {
        builtWith = signature()
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

        // Shown above the keys when a result needs confirming ("Ask first" mode).
        previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(6), dp(12), dp(4))
        }
        root.addView(previewBox, LinearLayout.LayoutParams(-1, -2))

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

        keys = KeyboardView(this, p, store.heightPercent / 100f).also {
            it.listener = this
            root.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        loadSettings()
        buildChips()
        return root
    }

    private fun loadSettings() {
        soundOn = store.sound
        hapticOn = store.haptic
        correctOn = store.autoCorrect
        suggestOn = store.suggestions
        assistantOn = store.assistantOn
        agentMode = store.agentMode
        skills = store.skills
        keys?.barLabel = if (assistantOn && agentMode != "manual") "hold to act" else ""
    }

    private fun buildChips() {
        val row = chipsRow ?: return
        row.removeAllViews()
        if (!assistantOn) return
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

    private fun feedback(type: KeyboardView.Type) {
        if (hapticOn) try {
            val v = vibrator
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                else v.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) { }
        if (soundOn) audio?.playSoundEffect(
            when (type) {
                KeyboardView.Type.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                KeyboardView.Type.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                KeyboardView.Type.BAR -> AudioManager.FX_KEYPRESS_SPACEBAR
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }, 0.6f,
        )
    }

    override fun onPress(type: KeyboardView.Type) = feedback(type)

    // ---------- typing ----------

    /** Records where the cursor should be after one of our own edits, so we can tell them from the user's taps. */
    private fun expect(delta: Int) {
        cursor = (cursor + delta).coerceAtLeast(0)
        expected.addLast(cursor)
        if (expected.size > 48) expected.removeFirst()
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
        } else if (c.isLetter() || (c == '\'' && word.isNotEmpty()) || (c == '/' && word.isEmpty() && assistantOn && textField)) {
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
            val fix = if (correctOn && textField && typed.isNotEmpty() && typed[0] != '/' && (c == ' ' || c in ".,!?;:"))
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
            when {
                c == '.' || c == '!' || c == '?' -> pendingStop = true
                c == ' ' -> { if (pendingStop) sentenceStart = true; pendingStop = false }
                else -> pendingStop = false
            }
            if (c == ' ' && keys?.mode != KeyboardView.Mode.LETTERS) keys?.mode = KeyboardView.Mode.LETTERS
        }
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
                cursor = -1
            }
        }
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
        cursor = -1
        word.setLength(0)
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
        if (ic != null) {
            if (textField && TextUtils.isEmpty(ic.getSelectedText(0))) {
                val before = ic.getTextBeforeCursor(40, 0)?.toString().orEmpty()
                word.append(before.takeLastWhile { it.isLetter() || it == '\'' }.trimStart('\''))
            }
            sentenceStart = type != InputType.TYPE_NULL && ic.getCursorCapsMode(type) != 0
        }
        applyCaps()
        updateSuggestions()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (newSelStart == newSelEnd) {
            if (expected.contains(newSelStart)) {
                while (expected.removeFirst() != newSelStart) { /* drop older positions */ }
                return
            }
            if (expected.isEmpty() && newSelStart == cursor) return
        }
        expected.clear()
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
            hidePreview()
            showTyping()
            recognizer?.cancel()
            syncIfStale()
        }
        val type = info?.inputType ?: 0
        val variation = type and InputType.TYPE_MASK_VARIATION
        textField = type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT && !isPasswordField() &&
            type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0 &&
            variation != InputType.TYPE_TEXT_VARIATION_URI && variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
            variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS && variation != InputType.TYPE_TEXT_VARIATION_FILTER
        expected.clear()
        cursor = info?.initialSelStart ?: -1
        resync()
    }

    /** While paired with a dashboard, refresh skills and connections every so often in the background. */
    private fun syncIfStale() {
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
        } else if (suggestOn && textField && typed.isNotEmpty()) {
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
        launch(
            if (skill == null) "Working on it…" else "${skill.name}…",
            skill?.let { Ai.skillTask(it.instruction) }, target.text, connected = true,
        ) { result -> confirmThen(result) { deliver(target, result) } }
    }

    /** Runs one assistant request off the main thread and hands the result back, unless it was cancelled. */
    private fun launch(label: String, task: String?, input: String, connected: Boolean, onResult: (ActionResult) -> Unit) {
        busy = true
        undo = null
        val token = ++generation
        val key = store.apiKey
        val model = store.model
        val web = connected && store.webSearch
        val tools = if (connected) store.tools() else emptyList()
        showStatus(label, "Cancel" to {
            generation++
            busy = false
            showTyping()
        })
        worker.execute {
            val outcome = try {
                Result.success(Ai.run(key, model, web, task, input, tools))
            } catch (e: AiException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(AiException("Something went wrong. Try again"))
            }
            main.post {
                if (token != generation) return@post
                busy = false
                outcome.fold(onResult) { showStatus(it.message ?: "Failed") }
            }
        }
    }

    /** In "Ask first" mode the result is shown above the keys and only applied once the user agrees. */
    private fun confirmThen(result: ActionResult, apply: () -> Unit) {
        val box = previewBox
        if (agentMode != "ask" || result.text.isEmpty() || box == null) return apply()
        box.removeAllViews()
        box.addView(TextView(this).apply {
            text = result.text
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 5
            movementMethod = ScrollingMovementMethod()
            background = rounded(p.key, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }, LinearLayout.LayoutParams(-1, -2))
        box.visibility = View.VISIBLE
        showStatus("Use this?", "Use" to { hidePreview(); apply() }, "Discard" to { hidePreview(); showTyping() })
    }

    private fun hidePreview() {
        previewBox?.apply { removeAllViews(); visibility = View.GONE }
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

    // ---------- voice ----------

    /** Tap: dictation, cleaned up by the assistant. Hold: speak a request and get the result. */
    private fun startVoice(command: Boolean) {
        if (busy) return
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
        val smart = assistantOn && store.apiKey.isNotBlank()
        if (command && !smart) {
            showStatus("Voice commands need the assistant and an API key", "Set up" to { openApp() })
            return
        }
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        val heading = if (command) "Say what you want…" else "Listening…"
        val buttons = arrayOf<Pair<String, () -> Unit>>(
            "Done" to { rec.stopListening() },
            "Cancel" to { rec.cancel(); showTyping() },
        )
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partial: Bundle?) {
                val heard = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!heard.isNullOrBlank()) showStatus(heard.takeLast(60), *buttons)
            }
            override fun onResults(results: Bundle?) {
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (heard.isNullOrBlank()) showStatus("Didn't catch that") else onHeard(heard, command, smart)
            }
            override fun onError(error: Int) {
                showStatus(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No connection for speech"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing"
                    else -> "Voice input failed"
                })
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        showStatus(heading, *buttons)
        rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    private fun onHeard(heard: String, command: Boolean, smart: Boolean) {
        if (!smart) {
            insert(heard) // plain dictation still works without the assistant
            return
        }
        launch(
            if (command) "Working on it…" else "Tidying up…",
            if (command) null else Ai.DICTATION_TASK, heard, connected = command,
        ) { result ->
            val open = result.openUri?.let { uri -> openLabel(uri) to { openLink(uri) } }
            if (result.text.isEmpty()) showStatus("Ready", *listOfNotNull(open).toTypedArray())
            else confirmThen(result) { insert(result.text, open) }
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
        if (!busy && statusRow?.visibility == View.VISIBLE && previewBox?.visibility != View.VISIBLE) showTyping()
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
