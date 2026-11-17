package app.verbo.keyboard

import kotlin.math.roundToInt
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
    private var modeBox: LinearLayout? = null
    private var autoLang: String? = null // while set, what you type is translated into this language whenever you pause
    private var lastAuto = "" // the last text auto-translate produced, so it isn't translated again
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
        modeBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(2), dp(4), dp(2))
        }
        root.addView(modeBox, LinearLayout.LayoutParams(-1, -2))
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
        keys?.swipeEnabled = store.swipeTyping
        hindiOn = store.hindiScript
        val barActive = assistantOn && agentMode != "manual"
        wheelIds = if (barActive && store.wheelOn) store.wheel else emptyList()
        baseWheel = wheelIds
        keys?.wheelItems = wheelIds.map { wheelItem(it, false) }
        val catalogIds = Store.WHEEL_TOOLS.map { it.first } + skills.map { "skill:" + it.name } + Apps.ALL.map { Apps.id(it) }
        keys?.wheelCatalogIds = catalogIds
        keys?.wheelCatalog = catalogIds.map { wheelItem(it, false) }
        keys?.barLabel = if (!barActive) "" else if (wheelIds.isEmpty()) "hold to act" else "hold for wheel"
        keymap = store.keymap
        keys?.bound = if (assistantOn && store.keysOn) KeyAction.bound(keymap, skills, currentInputEditorInfo?.packageName) else emptySet()
    }

    private var baseWheel: List<String> = emptyList()
    private var keymap: List<KeyAction> = emptyList()
    private var hindiOn = false
    /** The word a swipe just put in (with its trailing space), so a suggestion or backspace can replace it. */
    private var swiped: String? = null

    override fun onSwipe(path: List<Gesture.P>) {
        val view = keys ?: return
        val ic = currentInputConnection ?: return
        clearResult()
        val seen = Gesture.visited(path) { view.letterAt(it) }
        val words = Gesture.decode(path, seen, view.letterCenters(), view.letterWidth, { dictionary.startingWith(it) })
        if (words.isEmpty()) return
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val space = if (before.isNotEmpty() && (before.last().isLetterOrDigit() || before.last() in ".,!?")) " " else ""
        val caps = view.shift > 0 || (sentenceStart && before.isBlank())
        val best = if (caps) words[0].replaceFirstChar { it.uppercase() } else words[0]
        val put = "$best "
        ic.commitText(space + put, 1)
        expect(space.length + put.length)
        word.setLength(0)
        lastTyped = ' '
        sentenceStart = false
        if (view.shift == 1) view.shift = 0
        swiped = put
        suggestions = words.map { if (caps) it.replaceFirstChar { c -> c.uppercase() } else it }
        slashMatches = emptyList()
        highlighted = 0
        applyCaps()
        showTyping()
        noteDraft()
    }

    /** ";addr" and a space: the saved text goes in instead. */
    private fun expandShortcut(): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(24, 0)?.toString() ?: return false
        val m = Regex("""(?:^|\s);([A-Za-z0-9_]{1,20})$""").find(before) ?: return false
        val value = store.shortcutFor(m.groupValues[1]) ?: return false
        val typed = m.groupValues[1].length + 1
        ic.beginBatchEdit()
        ic.deleteSurroundingText(typed, 0)
        ic.commitText("$value ", 1)
        ic.endBatchEdit()
        expect(value.length + 1 - typed)
        word.setLength(0)
        lastTyped = ' '
        updateSuggestions()
        return true
    }

    private fun wheelItem(id: String, suggested: Boolean): KeyboardView.WheelItem = when (id) {
        "ai" -> KeyboardView.WheelItem("Ask AI", R.drawable.ic_sparkles, suggested = suggested)
        "translate" -> KeyboardView.WheelItem("Translate", R.drawable.ic_languages, suggested = suggested)
        "auto" -> KeyboardView.WheelItem("Auto-translate", R.drawable.ic_auto_translate, suggested = suggested)
        "rewrite" -> KeyboardView.WheelItem("Rewrite", R.drawable.ic_pencil_line, suggested = suggested)
        "voice" -> KeyboardView.WheelItem("Voice", R.drawable.ic_mic, suggested = suggested)
        "clips" -> KeyboardView.WheelItem("Clips", R.drawable.ic_clipboard, suggested = suggested)
        "meet" -> KeyboardView.WheelItem("Meeting link", R.drawable.ic_video, suggested = suggested)
        else -> {
            val app = Apps.byId(id)
            val name = id.substringAfter(":")
            val emoji = skills.firstOrNull { it.name == name }?.emoji?.ifEmpty { null } ?: Acts.BUILTIN.firstOrNull { it.name == name }?.emoji
            when {
                app != null -> KeyboardView.WheelItem(app.name, app.brand.first, suggested = suggested, tint = app.brand.second)
                emoji != null -> KeyboardView.WheelItem(name, 0, emoji, suggested)
                else -> KeyboardView.WheelItem(name, R.drawable.ic_zap, suggested = suggested)
            }
        }
    }

    /** Text the wheel and the acts look at: what is in the field plus the copied message. */
    private fun contextForActs(): String {
        val typed = try { readTarget()?.text.orEmpty() } catch (e: Exception) { "" }
        val copied = contextText ?: if (hasFreshClip()) readClip().orEmpty() else ""
        return (typed + "\n" + copied).trim()
    }

    /** Before the wheel opens: acts that fit the text (a date: Schedule it) take the middle places, ringed. */
    override fun onWheelOpening() {
        if (baseWheel.isEmpty()) return
        val fits = Acts.forContext(contextForActs()).take(2).map { name ->
            if (skills.any { it.name.equals(name, true) }) "skill:" + skills.first { it.name.equals(name, true) }.name else "act:$name"
        }
        val rest = baseWheel.filter { it !in fits }
        val keep = rest.take(Store.MAX_WHEEL - fits.size)
        val mid = keep.size / 2
        wheelIds = keep.take(mid) + fits + keep.drop(mid)
        keys?.wheelItems = wheelIds.map { wheelItem(it, it in fits) }
    }

    private fun buildChips() {
        val row = chipsRow ?: return
        row.removeAllViews()
        if (!assistantOn) return
        val acts = chipView("⚡ Acts", p.fnKey, p.keyPressed, p.accent)
        acts.typeface = Typeface.DEFAULT_BOLD
        acts.setOnClickListener { feedback(KeyboardView.Type.MODE); showActs() }
        row.addView(acts, chipParams())
        val fresh = contextText == null && hasFreshClip()
        // Triggers: the copied message is matched on the phone; a chip offers each matching skill.
        val clip = if (store.triggersOn) contextText ?: (if (fresh) readClip() else null) else null
        val matched = clip?.let { c -> store.triggers.filter { Cards.matches(it.kind, it.keyword, c) } }.orEmpty()
        val stamp = if (fresh) clipStamp() else 0L
        if (fresh && matched.isNotEmpty() && stamp != lastAttachedClip) {
            // Show the copied message above the keys, once per copy (✕ removes it for good).
            lastAttachedClip = stamp
            contextText = clip
            refreshContext()
            suggestRow?.visibility = View.GONE
            chipsScroll?.visibility = View.VISIBLE
        }
        // Quick replies to the copied message, fetched once per copy, one tap to put in the field.
        val replyStamp = if (clip != null) clipStamp() else 0L
        if (clip != null && replyStamp != 0L && replyStamp != lastHistoryClip) { lastHistoryClip = replyStamp; store.addClipHistory(clip) }
        if (clip != null && store.smartReplies && replyStamp != 0L) {
            if (repliesFor == replyStamp) for (r in replies) {
                val chip = chipView("↩ $r", p.key, p.keyPressed, p.text)
                chip.setOnClickListener { feedback(KeyboardView.Type.MODE); insert(r) }
                row.addView(chip, chipParams())
            } else if (repliesAsked != replyStamp && looksLikeMessage(clip) && store.activeKey.isNotBlank() && !store.overLimit) fetchReplies(clip, replyStamp)
        }
        for (t in matched) {
            val chip = chipView("⚡ " + Cards.chipLabel(t, clip!!), p.accent, p.accentPressed, p.onAccent)
            chip.setOnClickListener { feedback(KeyboardView.Type.MODE); runTrigger(t, clip) }
            row.addView(chip, chipParams())
        }
        if (contextText == null && fresh) {
            val use = chipView("📋 Use copied text", p.accent, p.accentPressed, p.onAccent)
            use.setOnClickListener { feedback(KeyboardView.Type.MODE); useClipAsContext() }
            row.addView(use, chipParams())
        }
        // Something new was copied and the field is empty: offer the acts in place of the keys, once per copy.
        if (fresh && stamp != 0L && stamp != lastActsClip && store.actsOnCopy && store.activeKey.isNotBlank() && matched.none { it.auto }) {
            lastActsClip = stamp
            val empty = try { readTarget()?.text.isNullOrBlank() } catch (e: Exception) { false }
            if (empty) main.post { if (!busy) showActs() }
        }
        // An automatic trigger runs once per copied message.
        val auto = matched.firstOrNull { it.auto }
        if (auto != null && clip != null && stamp != 0L && stamp != lastAutoClip && !busy && store.activeKey.isNotBlank()) {
            lastAutoClip = stamp
            main.post { runTrigger(auto, clip) }
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
        lastWasBackspace = false
        swiped = null
        if (text == " " && expandShortcut()) return
        val ic = currentInputConnection ?: return
        if (text[0] == ' ' || text[0] in ".,!?") main.post { noteDraft() }
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
        scheduleAuto()
    }

    /** What the user has typed in this field, read at word ends, so a sent message can be learned from. */
    private var draft = ""
    private var lastWasBackspace = false

    private fun noteDraft() {
        if (!textField || isPasswordField()) return
        draft = currentInputConnection?.getTextBeforeCursor(600, 0)?.toString().orEmpty()
    }

    private fun learnDraft() {
        val d = draft.trim()
        draft = ""
        if (d.isEmpty() || isPasswordField()) return
        val fromAi = undo?.second?.let { it.isNotBlank() && d.contains(it.trim()) } == true
        if (!fromAi) store.addStyleSample(d)
    }

    override fun onBackspace() {
        lastWasBackspace = true
        val ic = currentInputConnection ?: return
        swiped?.let { w ->
            // Backspace right after a swipe takes the whole word back out.
            swiped = null
            if (ic.getTextBeforeCursor(w.length, 0)?.toString() == w) {
                ic.deleteSurroundingText(w.length, 0)
                expect(-w.length)
                suggestions = emptyList()
                showTyping()
                return
            }
        }
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
        scheduleAuto()
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        clearResult()
        val options = currentInputEditorInfo?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val wantsAction = options and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 &&
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (wantsAction && action == EditorInfo.IME_ACTION_SEND) { noteDraft(); learnDraft() }
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
        runShortcut(id)
    }

    /** The compass was spun and released: run whichever catalog entry landed under the needle. */
    override fun onWheelCatalog(index: Int) {
        val id = keys?.wheelCatalogIds?.getOrNull(index) ?: return
        feedback(KeyboardView.Type.ENTER)
        runShortcut(id)
    }

    /** Space tapped twice: the language compass, spun by its blue rim. */
    override fun onSpaceDouble() {
        feedback(KeyboardView.Type.ENTER)
        showLanguages(compass = true)
    }

    /** A wheel slot or a held key: a tool id, "act:Name", "skill:Name", "app:key" or "text:words". */
    private fun runShortcut(id: String) {
        when (id) {
            "ai" -> runAction(null)
            "translate" -> showLanguages()
            "auto" -> showLanguages(auto = true)
            "rewrite" -> showRewrite()
            "voice" -> startVoice(false)
            "clips" -> showClips()
            "meet" -> makeMeetLink()
            else -> if (id.startsWith("text:")) currentInputConnection?.commitText(id.removePrefix("text:"), 1)
            else if (id.startsWith("tool:")) runShortcut(id.removePrefix("tool:"))
            else if (id.startsWith("app:")) Apps.byKey(id.removePrefix("app:"))?.let { runApp(it) } ?: showStatus("That app is no longer available")
            else if (id.startsWith("act:")) runAct(id.removePrefix("act:")) else skills.firstOrNull { it.name == id.removePrefix("skill:") }?.let { runAction(it) } ?: showStatus("That skill no longer exists")
        }
    }

    /** An app slot on the wheel: run its skill, share to it, open it filled in, or use its connected tools. */
    internal fun runApp(app: Apps.App) {
        app.skill?.let { runAct(it); return }
        val text = contextForActs()
        when (app.key) {
            "whatsapp", "telegram", "x" -> {
                if (text.isBlank()) { showStatus("Type something first to send it to ${app.name}"); return }
                val pkg = when (app.key) { "whatsapp" -> "com.whatsapp"; "telegram" -> "org.telegram.messenger"; else -> "com.twitter.android" }
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(pkg)
                    .putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val installed = try { packageManager.resolveActivity(send, 0) != null } catch (e: Exception) { false }
                if (installed) { try { startActivity(send); return } catch (e: Exception) { } }
                val web = when (app.key) {
                    "whatsapp" -> "https://wa.me/?text=" + Uri.encode(text)
                    "telegram" -> "https://t.me/share/url?url=&text=" + Uri.encode(text)
                    else -> "https://twitter.com/intent/tweet?text=" + Uri.encode(text)
                }
                openLink(web)
            }
            "youtube" -> openLink("https://www.youtube.com/results?search_query=" + Uri.encode(text))
            "calendar" -> openLink("https://calendar.google.com/calendar/render?action=TEMPLATE&text=" + Uri.encode(text))
            else -> {
                if (Apps.token(app, store).isEmpty()) {
                    showStatus("Connect ${app.name} in Verbo to use it here", "Connect" to { openPage("automate") })
                    return
                }
                runTask("${app.name}…", app.task, card = true, retry = { runApp(app) })
            }
        }
    }

    /** A lit letter was held: run its key action for this app (or the skill bound to it) instead of typing it. */
    override fun onKeyHeld(letter: Char) {
        val action = KeyAction.resolve(keymap, skills, letter, currentInputEditorInfo?.packageName) ?: return
        feedback(KeyboardView.Type.ENTER)
        runShortcut(action)
    }

    // ---------- context from a copied message ----------

    /** Wall-clock time, as clips are stamped with it. A variable only so the tests can use their own clock. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** True when text was copied in the last few minutes. Looks only at the clip's description, not its content. */
    private fun hasFreshClip(): Boolean = try {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val description = clipboard.primaryClipDescription
        description != null && (description.hasMimeType("text/plain") || description.hasMimeType("text/html")) &&
            clock() - description.timestamp < 5 * 60 * 1000
    } catch (e: Exception) {
        false
    }

    /** When the current clip was copied; an automatic trigger runs once per clip. */
    private var lastAutoClip = 0L
    private var lastAttachedClip = 0L
    private var replies: List<String> = emptyList()
    private var repliesFor = 0L
    private var repliesAsked = 0L
    private var lastHistoryClip = 0L

    /** A chat message, not a link, a code or a whole article. */
    private fun looksLikeMessage(text: String) = text.length in 2..500 && text.count { it.isLetter() } >= 2 &&
        !Regex("""^\s*https?://\S+\s*$""").matches(text)

    private fun fetchReplies(clip: String, stamp: Long) {
        repliesAsked = stamp
        val engine = Engine(store)
        worker.execute {
            val got = try {
                val r = engine.run(Ai.SMART_REPLY_TASK, Ai.userMessage("", clip), false, emptyList(), label = "Quick replies")
                Cards.options(r.text) ?: r.text.lines().map { it.trim().removePrefix("-").trim() }.filter { it.isNotEmpty() && it.length < 80 }.take(3)
            } catch (e: Exception) { emptyList() }
            main.post {
                if (got.isEmpty() || clipStamp() != stamp) return@post
                replies = got.take(3)
                repliesFor = stamp
                buildChips()
            }
        }
    }
    private var lastActsClip = 0L

    private fun clipStamp(): Long = try {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClipDescription?.timestamp ?: 0L
    } catch (e: Exception) { 0L }

    private fun readClip(): String? = try {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()?.take(4000)?.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    /** The instruction for an act: the user's skill of that name first, then the ready-made acts and trigger skills. */
    private fun actSkill(name: String): Skill? = skills.firstOrNull { it.name.equals(name, true) }
        ?: Acts.BUILTIN.firstOrNull { it.name.equals(name, true) }?.let { Skill(it.emoji, it.name, it.instruction, "write") }
        ?: Trigger.BUILTIN[name]?.let { Skill("⚡", name, it, "write") }

    /** Runs an act on the copied message (if there is one) and the field's text, as a card. */
    internal fun runAct(name: String) {
        val skill = actSkill(name) ?: run { showStatus("No act called “$name”"); return }
        store.recordUse("skill:" + skill.name)
        if (contextText == null && hasFreshClip()) readClip()?.let { contextText = it }
        refreshContext()
        val schedule = name.equals("Schedule it", true)
        if (Acts.isChain(skill.instruction)) {
            val steps = Acts.chainSteps(skill.instruction)
            val task = Acts.chainTask(steps) { step -> actSkill(step)?.takeIf { !Acts.isChain(it.instruction) }?.instruction }
            runTask("${skill.name}…", task, card = true, retry = { runAct(name) }, then = { r -> finishChain(r) })
            return
        }
        runTask("${skill.name}…", Ai.skillTask(skill.instruction), card = true, retry = { runAct(name) }, then = { r ->
            val event = r.openUri?.let { PhoneCalendar.parseTemplate(it) }
            if (schedule && event != null) {
                // One tap: the event is saved now, and the message carries the link so friends can add it too.
                if (PhoneCalendar.allowed(this)) worker.execute {
                    val saved = runCatching { PhoneCalendar.save(this, event) }.isSuccess
                    main.post { android.widget.Toast.makeText(this, if (saved) "Saved to your calendar: ${event.title}, ${PhoneCalendar.describe(event)}" else "Couldn't save it to the calendar", android.widget.Toast.LENGTH_SHORT).show() }
                }
                r.copy(text = r.text + "\nAdd it to your calendar: " + r.openUri)
            } else r
        })
    }

    /** After a chain: every calendar link becomes a saved event; the last other link stays as the button. */
    private fun finishChain(r: ActionResult): ActionResult {
        val events = r.links.mapNotNull { PhoneCalendar.parseTemplate(it) }
        if (events.isNotEmpty() && PhoneCalendar.allowed(this)) worker.execute {
            val saved = events.count { e -> runCatching { PhoneCalendar.save(this, e) }.isSuccess }
            main.post { android.widget.Toast.makeText(this, "Saved $saved event${if (saved == 1) "" else "s"} to your calendar", android.widget.Toast.LENGTH_SHORT).show() }
        }
        val other = r.links.lastOrNull { PhoneCalendar.parseTemplate(it) == null }
        return r.copy(openUri = other ?: if (PhoneCalendar.allowed(this)) null else r.links.firstOrNull())
    }

    /** The acts, in place of the keys: the copied text on top, then every act as a card. "Type" goes back to the keys. */
    internal fun showActs() = showPanel {
        steps = null
        val head = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), 0, 0, dp(6)) }
        val type = chipView("⌨  Type", p.key, p.keyPressed, p.text)
        type.setOnClickListener { feedback(KeyboardView.Type.MODE); hidePanel(); showTyping() }
        head.addView(type, LinearLayout.LayoutParams(-2, dp(34)))
        val act = chipView("⚡ Act", p.accent, p.accentPressed, p.onAccent)
        act.typeface = Typeface.DEFAULT_BOLD
        head.addView(act, LinearLayout.LayoutParams(-2, dp(34)).apply { leftMargin = dp(6) })
        head.addView(View(this@VerboIme), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(iconButton(R.drawable.ic_x, "Back to keyboard", p.muted) { hidePanel(); showTyping() }, LinearLayout.LayoutParams(dp(38), dp(38)))
        addView(head)
        val copied = contextText ?: if (hasFreshClip()) readClip() else null
        if (copied != null) addView(TextView(this@VerboIme).apply {
            text = "📋  “${copied.replace('\n', ' ')}”"
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(p.key, 12)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        addView(TextView(this@VerboIme).apply { text = "My acts"; setTextColor(p.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.DEFAULT_BOLD; setPadding(dp(4), 0, 0, dp(6)) })
        val fits = Acts.forContext(contextForActs())
        val all = (listOf(Triple("✨", "Ask AI", false)) +
            skills.map { Triple(it.emoji.ifEmpty { "⚡" }, it.name, Acts.needsCopied(it.instruction)) } +
            Acts.BUILTIN.filter { b -> skills.none { it.name.equals(b.name, true) } }.map { Triple(it.emoji, it.name, it.needsCopied) })
            .sortedByDescending { it.second in fits }
        val grid = LinearLayout(this@VerboIme).apply { orientation = LinearLayout.VERTICAL }
        all.chunked(2).forEach { pair ->
            val row = LinearLayout(this@VerboIme)
            pair.forEach { (emoji, name, copiedNeeded) ->
                val cell = LinearLayout(this@VerboIme).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(10), dp(10))
                    background = if (name in fits) GradientDrawable().apply { setColor(p.key); cornerRadius = dp(14).toFloat(); setStroke(dp(2), p.accent) } else pressable(p.key, p.keyPressed, 14)
                    addView(TextView(this@VerboIme).apply {
                        text = "$emoji  $name"
                        setTextColor(p.text)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(TextView(this@VerboIme).apply {
                        val chain = skills.firstOrNull { it.name == name }?.instruction?.takeIf { Acts.isChain(it) }
                        text = when { name in fits -> "Fits this text"; chain != null -> "Chain · ${Acts.chainSteps(chain).size} steps"; name == "Ask AI" -> "Needs text input"; copiedNeeded -> "Needs copied text"; else -> "Needs text input" }
                        setTextColor(if (name in fits || copiedNeeded) p.accent else p.muted)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                        setPadding(0, dp(3), 0, 0)
                    })
                    setOnClickListener {
                        feedback(KeyboardView.Type.MODE)
                        hidePanel()
                        if (name == "Ask AI") runAction(null) else runAct(name)
                    }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            if (pair.size == 1) row.addView(View(this@VerboIme), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            grid.addView(row)
        }
        addView(android.widget.ScrollView(this@VerboIme).apply { addView(grid) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** Runs a trigger's skill on the copied message and shows the answer as a card. */
    private fun runTrigger(t: Trigger, clip: String) {
        val skill = store.triggerSkill(t)
        if (skill == null) {
            showStatus("No skill called “${t.skill}”. Edit the trigger in the Verbo app", "Set up" to { openApp() })
            return
        }
        contextText = clip
        refreshContext()
        buildChips()
        store.recordUse(store.triggerId(t))
        store.recordUse("skill:" + skill.name)
        runTask("${skill.name}…", Ai.skillTask(skill.instruction), card = true) { runTrigger(t, clip) }
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
        // The app emptied the field (the message was sent): learn the writing style from it, on the phone.
        if (newSelStart == 0 && newSelEnd == 0 && oldSelStart > 1 && !lastWasBackspace) learnDraft()
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
            contextText = null // context belongs to the conversation it was copied for
            buildChips()
            main.removeCallbacks(autoRun)
            autoLang = if (assistantOn) store.autoTranslate(info?.packageName) else null
            lastAuto = ""
            refreshMode()
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
        if (store.deviceToken.isEmpty()) return
        val age = System.currentTimeMillis() - store.lastSync
        if (age < 60_000 || (store.pending.isEmpty() && age < Sync.STALE_MS)) return // phone changes go up within a minute
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
            // Hinglish: the word in Devanagari goes first ("kaise" -> "कैसे").
            if (hindiOn && Translit.applies(typed)) suggestions = (listOf(Translit.toDevanagari(typed)) + suggestions).distinct().take(3)
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
        swiped?.let { w ->
            // Another reading of the swipe: swap the word that went in.
            swiped = null
            if (ic.getTextBeforeCursor(w.length, 0)?.toString() == w) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(w.length, 0)
                ic.commitText("$chosen ", 1)
                ic.endBatchEdit()
                expect(chosen.length + 1 - w.length)
            }
            suggestions = emptyList()
            showTyping()
            return
        }
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
        scheduleAuto()
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
        if (skill == null && rememberCommand()) return
        if (skill != null && Acts.isChain(skill.instruction)) { runAct(skill.name); return }
        if (skill != null) store.recordUse("skill:" + skill.name)
        // Requests typed for the bar are answers to look at first, so they open as a card with Copy and Insert.
        // Skill keys rewrite your own text, so they apply straight away unless Agent Mode is "Ask first".
        runTask(
            if (skill == null) "Working on it…" else "${skill.name}…",
            skill?.let { Ai.skillTask(it.instruction) }, card = skill == null || agentMode == "ask",
        ) { runAction(skill) }
    }

    /** "remember my UPI is me@okicici" or "forget my UPI", typed for the bar: handled on the phone, nothing is sent. */
    private fun rememberCommand(): Boolean {
        val target = readTarget() ?: return false
        val text = target.text.trim()
        val fact = Memory.parseRemember(text)
        val forget = if (fact == null) Memory.parseForget(text) else null
        if (fact == null && forget == null) return false
        val ic = currentInputConnection ?: return false
        ic.beginBatchEdit()
        if (target.selection == null) ic.deleteSurroundingText(target.before.length, target.after.length) else ic.commitText("", 1)
        ic.endBatchEdit()
        if (fact != null) {
            store.remember(fact)
            showStatus("Remembered · ${fact.label}: ${fact.value.take(40)}", "Memory" to { startActivity(Intent(this, PageActivity::class.java).putExtra("page", "memory").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        } else showStatus(if (store.forget(forget!!)) "Forgot $forget" else "Nothing saved as “$forget”")
        return true
    }

    /** Runs [task] (null = "do what the text says") on the field's text, with the copied context if one is attached. */
    private fun runTask(label: String, task: String?, card: Boolean, then: (ActionResult) -> ActionResult = { it }, retry: () -> Unit) {
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
        launch(label, task, Ai.userMessage(target.text, context), connected = true, card = card) { raw ->
            val result = then(raw)
            if (card) showResult(result, { chosen -> deliver(target, chosen) }) { hidePanel(); retry() }
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

    /**
     * Drops a new meeting link into the field. A Google Meet link needs the owner's Google account, which lives on
     * the dashboard; without it the user is offered an instant no-account link or the Meet app instead.
     */
    private fun makeMeetLink() {
        if (busy) return
        if (isPasswordField()) {
            showStatus("Verbo doesn't type into password fields")
            return
        }
        if (!store.googleConnected) {
            showChoices(if (store.deviceToken.isEmpty()) "Google Meet needs your dashboard" else "Google isn't connected yet", listOf(
                "Instant link, no account" to {
                    hidePanel()
                    // A Jitsi room exists as soon as someone opens its address; a long random name keeps it private.
                    val room = (1..14).map { "abcdefghijkmnpqrstuvwxyz23456789".random() }.joinToString("")
                    insert("https://meet.jit.si/verbo-$room")
                },
                "Open Google Meet" to { hidePanel(); showTyping(); openLink("https://meet.google.com/new") },
                "How to connect Google" to {
                    hidePanel()
                    showTyping()
                    startActivity(Intent(this, PageActivity::class.java).putExtra("page", "automate").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            ))
            return
        }
        busy = true
        undo = null
        val token = ++generation
        showLoading("Creating a Meet link…") {
            generation++
            busy = false
            hidePanel()
            showTyping()
        }
        worker.execute {
            val outcome = try {
                Result.success(Sync.meet(store))
            } catch (e: SyncException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(SyncException("Couldn't create the link. Try again"))
            }
            main.post {
                if (token != generation) return@post
                busy = false
                hidePanel()
                outcome.fold({ insert(it) }) { showStatus(it.message ?: "Failed") }
            }
        }
    }

    /** [auto] = choosing the language to keep translating into, rather than translating once.
     *  [compass] = open straight into the 360° dial of every language, spun by its blue rim. */
    private fun showLanguages(auto: Boolean = false, compass: Boolean = false) {
        val mine = store.languages.take(5)
        showPanel {
            addView(LanguageWheel(this@VerboIme).apply {
                languages = mine
                all = Store.ALL_LANGUAGES
                autoMode = auto
                onPick = { i ->
                    if (auto) { hidePanel(); setAuto(mine[i]) } else translateInto(listOf(mine[i]))
                }
                onCompassPick = { i ->
                    val lang = Store.ALL_LANGUAGES[i]
                    if (auto) { hidePanel(); setAuto(lang) } else translateInto(listOf(lang))
                }
                onCancel = { hidePanel(); showTyping() }
                onSpace = { currentInputConnection?.commitText(" ", 1) }
                onMore = { showLanguageChoices(auto) }
                if (compass) spin()
            }, LinearLayout.LayoutParams(-1, -1))
        }
    }

    private fun translateInto(languages: List<String>) {
        hidePanel()
        runTask(if (languages.size == 1) "Into ${languages[0]}…" else "Translating…", Ai.translateTask(languages), card = true) { showLanguages() }
    }

    /** The full list: every language, all of mine at once, and the auto-translate switch. */
    private fun showLanguageChoices(auto: Boolean) {
        val mine = store.languages
        fun go(languages: List<String>) {
            hidePanel()
            if (auto) return setAuto(languages[0])
            translateInto(languages)
        }
        val choices = ArrayList<Pair<String, () -> Unit>>()
        val current = autoLang
        if (auto) {
            if (current != null) choices += "Stop auto-translate ($current)" to { hidePanel(); setAuto(null) }
        } else {
            if (current != null) choices += "Stop auto-translate ($current)" to { hidePanel(); setAuto(null) }
            else choices += "Auto-translate every message…" to { showLanguages(auto = true) }
            if (mine.size > 1) choices += "All ${mine.size} of my languages" to { go(mine) }
        }
        mine.forEach { choices += it to { go(listOf(it)) } }
        Store.ALL_LANGUAGES.filter { it !in mine }.forEach { choices += it to { go(listOf(it)) } }
        showChoices(if (auto) "Keep translating into" else "Translate into", choices, highlightFirst = !auto)
    }

    // ---------- auto-translate ----------

    /** Turns auto-translate on (into [language]) or off for the app being typed in, and translates what is there now. */
    private fun setAuto(language: String?) {
        autoLang = language
        lastAuto = ""
        store.setAutoTranslate(currentInputEditorInfo?.packageName, language)
        main.removeCallbacks(autoRun)
        refreshMode()
        showTyping()
        if (language != null) main.postDelayed(autoRun, 300)
    }

    private fun refreshMode() {
        val box = modeBox ?: return
        box.removeAllViews()
        val language = autoLang
        if (language == null) {
            box.visibility = View.GONE
            return
        }
        box.addView(ImageView(this).apply { setImageResource(R.drawable.ic_languages); setColorFilter(p.accent) }, LinearLayout.LayoutParams(dp(18), dp(18)))
        box.addView(TextView(this).apply {
            text = "Auto-translating into $language when you pause"
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(6), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val stop = chipView("Stop", Color.TRANSPARENT, p.keyPressed, p.accent)
        stop.typeface = Typeface.DEFAULT_BOLD
        stop.setOnClickListener { feedback(KeyboardView.Type.MODE); setAuto(null) }
        box.addView(stop, LinearLayout.LayoutParams(-2, dp(32)))
        box.visibility = View.VISIBLE
    }

    /** Called after every edit: restarts the pause timer. */
    private fun scheduleAuto() {
        if (autoLang == null) return
        main.removeCallbacks(autoRun)
        main.postDelayed(autoRun, AUTO_PAUSE_MS)
    }

    private val autoRun = Runnable { autoTranslateNow() }

    private fun autoTranslateNow() {
        val language = autoLang ?: return
        if (busy || listening || panel?.visibility == View.VISIBLE || isPasswordField()) return
        if (store.activeKey.isBlank()) {
            showStatus("Auto-translate needs your API key", "Set up" to { openApp() })
            return
        }
        val target = readTarget() ?: return
        val text = target.text
        // Nothing to do for an empty field, for text we just produced, or for a selection the user is working on.
        if (text.isBlank() || text == lastAuto || target.selection != null || text.length > AUTO_MAX_CHARS) return
        launch("→ $language…", Ai.autoTranslateTask(language), text, connected = false) { result ->
            val now = readTarget()
            val unchanged = now != null && now.selection == null && now.before == target.before && now.after == target.after
            if (!unchanged || result.text.isEmpty()) {
                // The user kept typing: drop this translation and wait for the next pause.
                showTyping()
                scheduleAuto()
                return@launch
            }
            lastAuto = result.text
            if (result.text == text) {
                showTyping() // it was already in the target language
                return@launch
            }
            val ic = currentInputConnection ?: return@launch
            ic.beginBatchEdit()
            ic.deleteSurroundingText(target.before.length, target.after.length)
            ic.commitText(result.text, 1)
            ic.endBatchEdit()
            undo = text to result.text
            showStatus("Translated into $language", "Undo" to { lastAuto = text; undoLast() })
        }
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
        saved.forEach { clip -> row("📌 $clip", "Remove") { store.clips = saved - clip; showClips() } }
        val recent = store.clipHistory.filter { it != current && it !in saved }.take(10)
        if (recent.isNotEmpty()) list.addView(TextView(this@VerboIme).apply { text = "Recent"; setTextColor(p.muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); setPadding(dp(6), dp(8), 0, dp(4)) })
        recent.forEach { clip -> row(clip, "Pin") { store.clips = listOf(clip) + saved; showClips() } }
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
        val local = if (input.startsWith("Context (")) null else Offline.kindFor(task)
        val localLang = if (input.startsWith("Context (")) null else Offline.translateTarget(task)
        if (store.overLimit && local == null && localLang == null) {
            showStatus("This month's limit of $${"%.2f".format(store.monthlyLimit)} is reached", "Usage" to { openPage("usage") })
            return
        }
        val useDevice = store.onDevice
        busy = true
        undo = null
        val token = ++generation
        val engine = Engine(store)
        val cancel = {
            generation++
            busy = false
            hidePanel()
            showTyping()
        }
        steps = null
        // Each tool call shows up as a step, so the user can see what is happening.
        val tools = if (connected) store.tools().map { t ->
            ToolSpec(t.name, t.description, t.params, t.required) { args ->
                val step = toolStep(t.name, args)
                main.post { if (token == generation) { if (steps != null) addStep(step) else showStatus("$step…", "Cancel" to cancel) } }
                t.run(args)
            }
        } else emptyList()
        if (card) showLoading(label, cancel) else showStatus(label, "Cancel" to cancel)
        worker.execute {
            val outcome = try {
                val cancelled = { token != generation }
                // Small jobs run on the phone when they can (free, works offline); everything else goes to the AI service.
                val net = Offline.online(this)
                val done = if (useDevice || !net) when {
                    local != null -> Offline.run(this, local, input)
                    localLang != null -> Offline.translate(input, localLang, allowDownload = net)
                    else -> null
                } else null
                when {
                    done != null -> Result.success(ActionResult(done, null))
                    !net -> Result.failure(AiException("No connection. Spelling, tone and translation can work offline once their models are downloaded (Settings > Usage & on-device AI)"))
                    else -> Result.success(engine.run(task, input, connected, tools, cancelled, label))
                }
            } catch (e: AiException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(AiException("Something went wrong. Try again"))
            }
            main.post {
                if (token != generation) return@post
                busy = false
                outcome.fold({ result ->
                    val uri = result.openUri
                    // "Open my Notion page about X": open it now and leave the user's text alone.
                    if (result.autoOpen && uri != null) {
                        hidePanel()
                        showTyping()
                        openLink(uri)
                        showStatus(result.text.lines().firstOrNull()?.take(80)?.ifBlank { null } ?: "Opened", "Insert link" to { insert(uri) })
                    } else onResult(result)
                }) { hidePanel(); showStatus(it.message ?: "Failed") }
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

    internal fun hidePanel() {
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

    /** The steps of the action in progress, shown as a card while waiting. Null when the wait is shown as a status line. */
    private var steps: LinearLayout? = null

    internal fun showLoading(label: String, cancel: () -> Unit) = showPanel {
        val header = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, 0, dp(8)) }
        header.addView(ImageView(this@VerboIme).apply {
            setImageResource(R.drawable.ic_sparkles)
            setColorFilter(p.accent)
            // A slow pulse while waiting.
            android.animation.ObjectAnimator.ofFloat(this, "alpha", 1f, 0.3f).apply {
                duration = 700
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        header.addView(TextView(this@VerboIme).apply {
            text = label.removeSuffix("…")
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconButton(R.drawable.ic_x, "Cancel", p.muted, cancel), LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(header)
        val card = LinearLayout(this@VerboIme).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.key, 16)
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        addView(card, LinearLayout.LayoutParams(-1, -2))
        steps = card
        addStep(if (contextText != null) "Reading the copied message" else "Reading your text")
        addStep("Thinking")
    }

    /** Marks the current step done and shows the next one. */
    internal fun addStep(text: String) {
        val box = steps ?: return
        (box.getChildAt(box.childCount - 1) as? TextView)?.let { last ->
            last.text = "✓   ${last.tag}"
            last.setTextColor(p.muted)
        }
        box.addView(TextView(this).apply {
            tag = text
            this.text = "●   $text…"
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setPadding(0, dp(5), 0, dp(5))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
    }

    /** What the assistant is doing when it calls a tool, in words. */
    private fun toolStep(name: String, args: Map<String, String>): String {
        val q = (args["query"] ?: args["location"] ?: args["channel"] ?: args["title"] ?: args["content"] ?: "").take(40)
        val about = if (q.isNotBlank()) " “$q”" else ""
        return when {
            name == "find_place" -> "Finding$about on the map"
            name == "get_weather" -> "Checking the weather$about"
            name == "notion_search" -> "Searching Notion$about"
            name == "notion_read_page" -> "Reading the Notion page"
            name == "notion_create_page" -> "Creating a Notion page"
            name.startsWith("calendly") -> "Getting your booking links"
            name == "slack_send_message" -> "Sending to Slack #${args["channel"].orEmpty().removePrefix("#")}"
            name.startsWith("slack_") -> "Checking Slack$about"
            name == "github_create_issue" -> "Opening a GitHub issue"
            name.startsWith("github_") -> "Searching GitHub$about"
            name == "todoist_add_task" -> "Adding to Todoist$about"
            name.startsWith("todoist_") -> "Checking Todoist"
            name == "linear_create_issue" -> "Creating a Linear issue"
            name.startsWith("linear_") -> "Searching Linear$about"
            name.startsWith("discord_") -> "Posting to Discord"
            name == "google_meet_link" -> "Making a Meet link"
            name == "google_calendar_create" -> "Saving to your calendar$about"
            name.startsWith("google_calendar") -> "Checking your calendar"
            name == "gmail_send" -> "Sending the email"
            name == "gmail_create_draft" -> "Saving a Gmail draft"
            name.startsWith("gmail_") -> "Searching Gmail$about"
            name.startsWith("api_") -> "Calling ${name.removePrefix("api_").replace('_', ' ')}"
            else -> name.replace('_', ' ')
        }
    }

    /** The text that goes into the chat: cards are turned back into plain, readable text. */
    private fun chatText(r: ActionResult): String =
        Cards.notion(r.text, r.openUri)?.plain() ?: Cards.list(r.text)?.plain() ?: r.text

    /** The result as a card: back, try again, copy, and insert into the field (or send, where the app allows it). */
    internal fun showResult(result: ActionResult, onInsert: (ActionResult) -> Unit, onRetry: (() -> Unit)?) = showPanel {
        steps = null
        val person = Ai.personCard(result.text)
        val notion = if (person == null) Cards.notion(result.text, result.openUri) else null
        val options = if (person == null) Cards.options(result.text) else null
        val list = if (person == null && notion == null && options == null) Cards.list(result.text) else null
        val header = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(iconButton(R.drawable.ic_chevron_right, "Back to keyboard") { hidePanel(); showTyping() }.apply { rotation = 180f },
            LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(TextView(this@VerboIme).apply {
            text = when { person != null -> "in   LinkedIn HUD"; notion != null -> "N   Notion"; options != null -> "Pick one"; list != null -> list.title; else -> "Result" }
            setTextColor(if (person != null || notion != null || list != null) p.text else p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (list != null) 15f else 13.5f)
            if (person != null || notion != null || list != null) typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
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
        if (options != null) {
            // Suggestions: tap one to choose it, then Edit (into the field) or Apply (insert, or send in chat apps).
            var chosen = 0
            val column = LinearLayout(this@VerboIme).apply { orientation = LinearLayout.VERTICAL }
            val views = options.mapIndexed { i, text ->
                TextView(this@VerboIme).apply {
                    this.text = "“$text”"
                    setTextColor(p.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setLineSpacing(dp(2).toFloat(), 1f)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                }
            }
            fun paint() = views.forEachIndexed { i, v ->
                v.background = GradientDrawable().apply {
                    setColor(p.key)
                    cornerRadius = dp(16).toFloat()
                    if (i == chosen) setStroke(dp(2), p.accent)
                }
            }
            views.forEachIndexed { i, v ->
                v.setOnClickListener { feedback(KeyboardView.Type.MODE); chosen = i; paint() }
                column.addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            paint()
            addView(android.widget.ScrollView(this@VerboIme).apply { addView(column) }, LinearLayout.LayoutParams(-1, 0, 1f))
            val pick = { result.copy(text = options[chosen], openUri = null) }
            val footer = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(2)) }
            val edit = chipView("Edit", p.key, p.keyPressed, p.text)
            edit.typeface = Typeface.DEFAULT_BOLD
            edit.setOnClickListener { feedback(KeyboardView.Type.MODE); hidePanel(); onInsert(pick()) }
            footer.addView(edit, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
            val options2 = currentInputEditorInfo?.imeOptions ?: 0
            val sends = options2 and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEND && options2 and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0
            val apply = chipView(if (sends) "↑  Send" else "Apply", p.accent, p.accentPressed, p.onAccent)
            apply.typeface = Typeface.DEFAULT_BOLD
            apply.setOnClickListener {
                feedback(KeyboardView.Type.ENTER)
                hidePanel()
                onInsert(pick())
                if (sends) currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEND)
            }
            footer.addView(apply, LinearLayout.LayoutParams(0, dp(46), 1.7f))
            addView(footer)
            return@showPanel
        }
        if (person != null) {
            personCard(card, person)
            addView(android.widget.ScrollView(this@VerboIme).apply { addView(card) }, LinearLayout.LayoutParams(-1, 0, 1f))
            resultButtons(this, result, onInsert)
            return@showPanel
        }
        if (notion != null) {
            notionCard(card, notion)
            addView(android.widget.ScrollView(this@VerboIme).apply { addView(card) }, LinearLayout.LayoutParams(-1, 0, 1f))
            resultButtons(this, result, onInsert)
            return@showPanel
        }
        if (list != null) {
            // Each pick is its own card; the panel background shows between them.
            val column = LinearLayout(this@VerboIme).apply { orientation = LinearLayout.VERTICAL }
            list.items.forEach { column.addView(itemCard(it), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }
            if (list.rest.isNotEmpty()) column.addView(TextView(this@VerboIme).apply { text = list.rest; setTextColor(p.muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f); setPadding(dp(4), 0, dp(4), dp(4)) })
            addView(android.widget.ScrollView(this@VerboIme).apply { addView(column) }, LinearLayout.LayoutParams(-1, 0, 1f))
            resultButtons(this, result, onInsert)
            return@showPanel
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

        resultButtons(this, result, onInsert)
    }

    /** Copy and Insert (or Send) under a result card. */
    private fun resultButtons(panel: LinearLayout, result: ActionResult, onInsert: (ActionResult) -> Unit) {
        val footer = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(2)) }
        val copy = chipView("Copy", p.key, p.keyPressed, p.text)
        copy.typeface = Typeface.DEFAULT_BOLD
        copy.setOnClickListener {
            feedback(KeyboardView.Type.MODE)
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("Verbo result", chatText(result)))
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
                onInsert(result)
                if (sends) currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEND)
            }
            footer.addView(insert, LinearLayout.LayoutParams(0, dp(46), 1.7f))
        }
        panel.addView(footer)
    }

    /** A LinkedIn-style card: name, headline, then each "Current: …" style line as its own labelled sub-card. */
    private fun personCard(card: LinearLayout, person: Ai.PersonCard) {
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val initials = person.name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
        head.addView(TextView(this).apply {
            text = initials
            gravity = Gravity.CENTER
            setTextColor(p.onAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.accent) }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        names.addView(TextView(this).apply { text = person.name; setTextColor(p.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = Typeface.DEFAULT_BOLD })
        if (person.headline.isNotEmpty()) names.addView(TextView(this).apply { text = person.headline; setTextColor(p.accent); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f) })
        val location = person.facts.firstOrNull { it.first.equals("Location", true) }?.second
        if (location != null) names.addView(TextView(this).apply { text = location; setTextColor(p.muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f) })
        head.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val icons = mapOf("current" to "🏢", "previous" to "🕘", "education" to "🎓", "known for" to "⭐")
        person.facts.filter { !it.first.equals("Location", true) }.forEach { (label, value) ->
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(p.fnKey, 12)
                setPadding(dp(12), dp(9), dp(12), dp(10))
                addView(TextView(this@VerboIme).apply { text = "${icons[label.lowercase()] ?: "•"}  $label:"; setTextColor(p.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.DEFAULT_BOLD })
                addView(TextView(this@VerboIme).apply { text = value; setTextColor(p.muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(dp(26), dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        if (person.rest.isNotEmpty()) card.addView(TextView(this).apply { text = person.rest; setTextColor(p.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dp(8), 0, 0) })
        person.link?.let { url ->
            val open = chipView("in  Search on LinkedIn", p.fnKey, p.keyPressed, p.accent)
            open.typeface = Typeface.DEFAULT_BOLD
            open.setOnClickListener { feedback(KeyboardView.Type.MODE); openLink(url) }
            card.addView(open, LinearLayout.LayoutParams(-2, dp(36)).apply { topMargin = dp(10) })
        }
    }

    /** A Notion page: icon, title, when it was last edited, and Open. Insert puts "title: link" in the chat. */
    private fun notionCard(card: LinearLayout, page: Cards.NotionCard) {
        card.addView(TextView(this).apply { text = "Result"; setTextColor(p.muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f) })
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        row.addView(TextView(this).apply {
            text = "📄"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = rounded(p.fnKey, 8)
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        row.addView(TextView(this).apply {
            text = page.title
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnClickListener { feedback(KeyboardView.Type.MODE); openLink(page.url) }
        card.addView(row)
        card.addView(View(this).apply { setBackgroundColor(p.fnKey) }, LinearLayout.LayoutParams(-1, dp(1)))
        card.addView(TextView(this).apply {
            text = if (page.edited.isNotEmpty()) "Last edited: ${page.edited}" else Uri.parse(page.url).host.orEmpty()
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(8), 0, 0)
        })
        val open = chipView("Open in Notion", p.fnKey, p.keyPressed, p.accent)
        open.typeface = Typeface.DEFAULT_BOLD
        open.setOnClickListener { feedback(KeyboardView.Type.MODE); openLink(page.url) }
        card.addView(open, LinearLayout.LayoutParams(-2, dp(36)).apply { topMargin = dp(10) })
    }

    /** One pick in a ranked list: rank badge, name, rating, kind, area and up to three tags. Tapping searches for it. */
    private fun itemCard(item: Cards.Item) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = pressable(p.key, p.keyPressed, 16)
        setPadding(dp(14), dp(11), dp(14), dp(12))
        val top = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this@VerboIme).apply {
            text = "TOP ${item.rank}"
            setTextColor(p.onAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = rounded(p.accent, 6)
        })
        top.addView(TextView(this@VerboIme).apply {
            text = item.name
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(top)
        val meta = listOfNotNull(
            item.rating?.let { r -> "★".repeat(r.roundToInt().coerceIn(0, 5)) + "  " + (if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()) },
            item.kind.ifBlank { null },
        ).joinToString("  ·  ")
        if (meta.isNotEmpty()) addView(TextView(this@VerboIme).apply { text = meta; setTextColor(p.muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(0, dp(5), 0, 0) })
        if (item.area.isNotBlank()) addView(TextView(this@VerboIme).apply { text = "📍 ${item.area}"; setTextColor(p.muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(0, dp(2), 0, 0) })
        if (item.tags.isNotEmpty()) addView(LinearLayout(this@VerboIme).apply {
            setPadding(0, dp(7), 0, 0)
            item.tags.forEach { tag ->
                addView(TextView(this@VerboIme).apply {
                    text = tag
                    setTextColor(p.accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    maxLines = 1
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    background = rounded(p.fnKey, 6)
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
            }
        })
        setOnClickListener {
            feedback(KeyboardView.Type.MODE)
            openLink("https://www.google.com/search?q=" + Uri.encode(listOf(item.name, item.area).filter { it.isNotBlank() }.joinToString(" ")))
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
                currentInputConnection?.commitText(chatText(result), 1)
                contextText = null
                refreshContext()
                showTyping()
            }, open).toTypedArray())
            return
        }
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (target.selection == null) ic.deleteSurroundingText(target.before.length, target.after.length)
        ic.commitText(chatText(result), 1)
        ic.endBatchEdit()
        undo = target.text to chatText(result)
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
        PhoneCalendar.parseTemplate(uri) != null -> "Save to calendar"
        uri.startsWith("https://calendar.google.com", true) -> "Add to calendar"
        uri.contains("linkedin.com/", true) -> "Open LinkedIn"
        uri.contains("notion.so", true) || uri.contains("notion.site", true) -> "Open in Notion"
        uri.startsWith("https://www.youtube.com", true) -> "YouTube"
        uri.startsWith("https://x.com", true) -> "Post on X"
        else -> "Open " + (Uri.parse(uri).host?.removePrefix("www.")?.take(28) ?: "link")
    }

    private fun openLink(uri: String) {
        PhoneCalendar.parseTemplate(uri)?.let { saveEvent(uri, it); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            showStatus("No app can open that")
        }
    }

    /** One tap: the event goes straight into the phone's calendar. The first time, Android asks for calendar access. */
    private fun saveEvent(link: String, event: PhoneCalendar.Event) {
        if (!PhoneCalendar.allowed(this)) {
            hidePanel()
            showTyping()
            startActivity(Intent(this, CalendarPermissionActivity::class.java).putExtra(CalendarPermissionActivity.EXTRA_LINK, link)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        worker.execute {
            val outcome = runCatching { PhoneCalendar.save(this, event) }
            main.post {
                hidePanel()
                showTyping()
                outcome.fold({ id ->
                    showStatus("Saved: ${event.title}, ${PhoneCalendar.describe(event)}",
                        "Open" to {
                            try { startActivity(Intent(Intent.ACTION_VIEW, PhoneCalendar.viewUri(id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            catch (e: Exception) { showStatus("No calendar app to open it") }
                        },
                        "Undo" to { worker.execute { runCatching { PhoneCalendar.remove(this, id) } }; showStatus("Event removed") })
                }) { showStatus(it.message ?: "Couldn't save the event") }
            }
        }
    }

    private fun openPage(page: String) {
        startActivity(Intent(this, PageActivity::class.java).putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
            else if (agentMode == "ask") showResult(result, { chosen -> insert(chatText(chosen), open) }, null)
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

    /** The status line's text, for tests. */
    internal val statusMessage: String get() = (statusRow?.getChildAt(0) as? TextView)?.text?.toString().orEmpty()

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
        private const val AUTO_PAUSE_MS = 1600L
        private const val AUTO_MAX_CHARS = 2000
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
