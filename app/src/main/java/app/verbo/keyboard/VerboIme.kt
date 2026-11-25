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
    private var welcome: TextView? = null
    private var botEdit: android.widget.EditText? = null
    private var contextBox: LinearLayout? = null
    private var contextText: String? = null
    private var wheelIds: List<String> = emptyList()
    private var modeBox: LinearLayout? = null
    private var autoLang: String? = null // while set, what you type is translated into this language whenever you pause
    private var lastAuto = "" // the last text auto-translate produced, so it isn't translated again
    private var voiceCommand = false
    private var voiceForBot = false // true when the mic was tapped to speak a request to the bot, not to dictate
    private var levels: LevelView? = null
    internal var heardView: TextView? = null // the live transcript while listening
    internal var listenBot: BotView? = null // the mascot animating in the listening panel
    private var recognizer: SpeechRecognizer? = null
    // Long-pause endpointing: the service keeps cutting us off at short pauses, so we accumulate every
    // segment ourselves and only deliver once the user has genuinely stopped talking for LONG_PAUSE_MS.
    private var voiceSmart = false
    private val voiceSegments = StringBuilder() // finalized segments joined across restarts
    private var voicePartial = "" // the live tail of the current segment
    private var lastVoiceAt = 0L // last time we heard sound or words
    private var listenStartAt = 0L
    private var finalizeNext = false // a mode chip / stop asked us to wrap up on the next result
    private val voiceWatch = Runnable { tickVoice() }
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
        // Translation models for your languages download quietly on Wi-Fi, so translating is instant and free.
        try { Offline.prefetch(store.languages) } catch (e: Throwable) { }
        MessageListener.onIncoming = { ready -> onIncomingMessage(ready) }
        Thread { try { dictionary.load(assets.open("words.txt")) } catch (e: Exception) { } }.start()
        main.postDelayed(autoSync, Sync.EVERY_MS)
    }

    override fun onDestroy() {
        if (MessageListener.onIncoming != null) MessageListener.onIncoming = null
        main.removeCallbacks(autoSync)
        stopVoiceWatch()
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
        stopVoiceWatch()
        recognizer?.cancel()
        listening = false
        voiceForBot = false
        voiceSegments.setLength(0)
        voicePartial = ""
        p = KeyTheme.of(this, store.theme, store.customAccent)

        // The root also lets Touch Target Debug Mode see toolbar taps; it only watches, then passes every event on.
        val root = object : LinearLayout(this) {
            override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
                if (TouchDebug.enabled) watchToolbar(ev)
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
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
        // The suggestion row always keeps its height while typing (blank when there is nothing to suggest),
        // so the keyboard never grows and shrinks as words start and end.
        suggestRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.INVISIBLE }
        statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), 0, dp(2), 0)
        }
        strip.addView(chipsScroll, FrameLayout.LayoutParams(-1, -1))
        strip.addView(statusRow, FrameLayout.LayoutParams(-1, -1))

        welcome = TextView(this).apply {
            text = "Welcome to Verbo, the agentic keyboard"
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(p.key, 18)
            visibility = if (store.welcomed) View.GONE else View.VISIBLE
            setOnClickListener {
                feedback(KeyboardView.Type.MODE)
                store.welcomed = true
                visibility = View.GONE
                showBotIntro()
            }
        }
        root.addView(welcome, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(2), dp(10), dp(6)) })

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(strip, LinearLayout.LayoutParams(0, -1, 1f))
        // Soft style (the Type/Act mock-up): the bot and the mic sit in round buttons of their own.
        val roundButtons = store.keyShape == "soft"
        val bot = BotView(this).apply {
            mood = BotView.Mood.HELLO
            contentDescription = "Meet Verbo, the keyboard bot"
            // One tap and Verbo is listening: no second tap on the mascot. Without an AI key it asks for one first.
            setOnClickListener { feedback(KeyboardView.Type.MODE); openBot() }
        }
        if (roundButtons) {
            top.addView(View(this).apply { setBackgroundColor((p.muted and 0x00FFFFFF) or 0x55000000) }, LinearLayout.LayoutParams(dp(1), dp(22)).apply { setMargins(dp(4), 0, dp(6), 0) })
            top.addView(FrameLayout(this).apply {
                background = pressable(p.key, p.keyPressed, 20)
                setOnClickListener { feedback(KeyboardView.Type.MODE); openBot() }
                addView(bot.apply { isClickable = false }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(0, 0, dp(6), 0) })
        } else top.addView(bot, LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(dp(4), 0, dp(6), 0) })
        val voice = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            if (roundButtons) setPadding(0, 0, 0, 0) else setPadding(dp(12), 0, dp(14), 0)
            background = pressable(p.key, p.keyPressed, if (roundButtons) 20 else 18)
            contentDescription = "Voice. Tap to ask Verbo, hold to dictate"
            setOnClickListener { feedback(KeyboardView.Type.MODE); voiceForBot = true; startVoice(true) }
            setOnLongClickListener { feedback(KeyboardView.Type.MODE); voiceForBot = false; startVoice(false); true }
            addView(ImageView(this@VerboIme).apply {
                setImageResource(R.drawable.ic_mic)
                setColorFilter(p.text)
            }, if (roundButtons) LinearLayout.LayoutParams(dp(20), dp(20)) else LinearLayout.LayoutParams(dp(15), dp(15)).apply { rightMargin = dp(6) })
            if (!roundButtons) addView(TextView(this@VerboIme).apply {
                text = "Voice"
                setTextColor(p.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
            })
        }
        top.addView(voice, if (roundButtons) LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(0, 0, dp(8), 0) }
            else LinearLayout.LayoutParams(-2, dp(36)).apply { setMargins(0, 0, dp(6), 0) })
        root.addView(top, LinearLayout.LayoutParams(-1, dp(46)))
        toolbar = top
        root.addView(suggestRow, LinearLayout.LayoutParams(-1, dp(40)))

        keys = KeyboardView(this, p, store.heightPercent / 100f, store.keyShape, store.numberRow).also {
            it.listener = this
            root.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        typingViews = listOf(welcome!!, top, keys!!)
        loadSettings()
        buildChips()
        return root
    }

    /** The row above the keys (Type/Act pills, the bot, Voice), watched by Touch Target Debug Mode. */
    private var toolbar: View? = null

    /** Debug builds: logs toolbar touches and counts each tap, flagging ones that look accidental. */
    private fun watchToolbar(ev: android.view.MotionEvent) {
        val bar = toolbar ?: return
        val kb = keys ?: return
        if (bar.visibility != View.VISIBLE || ev.y < bar.top || ev.y > bar.bottom) return
        val action = when (ev.actionMasked) { android.view.MotionEvent.ACTION_DOWN -> "ACTION_DOWN"; android.view.MotionEvent.ACTION_UP -> "ACTION_UP"; else -> return }
        val density = resources.displayMetrics.density
        val name = toolbarItemAt(bar, ev.x, ev.y - bar.top)
        TouchDebug.log("$action toolbar", ev.getPointerId(0), ev.x, ev.y, density, name)
        if (action == "ACTION_UP") TouchDebug.toolbarTap(name, (kb.top - ev.y) / density, android.os.SystemClock.uptimeMillis())
    }

    /** What a toolbar touch is on: a pill's text, or the bot's or Voice's description. */
    private fun toolbarItemAt(v: View, x: Float, y: Float): String = nameAt(v, x, y) ?: "toolbar"

    /** The deepest named thing under a point; an unnamed icon takes the name of the pill around it. */
    private fun nameAt(v: View, x: Float, y: Float): String? {
        if (v is android.view.ViewGroup) {
            val sx = x + v.scrollX
            for (i in v.childCount - 1 downTo 0) {
                val c = v.getChildAt(i)
                if (c.visibility == View.VISIBLE && sx >= c.left && sx < c.right && y >= c.top && y < c.bottom) {
                    nameAt(c, sx - c.left, y - c.top)?.let { return it }
                    break
                }
            }
        }
        return (v as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }
            ?: v.contentDescription?.toString()?.substringBefore('.')?.takeIf { it.isNotBlank() }
            ?: (v as? android.view.ViewGroup)?.takeIf { it.childCount in 1..3 }?.let { g ->
                (0 until g.childCount).firstNotNullOfOrNull { (g.getChildAt(it) as? TextView)?.text?.toString()?.takeIf { t -> t.isNotBlank() } }
            }
    }

    private fun loadSettings() {
        TouchDebug.enabled = store.touchDebug
        keys?.touchDebug = TouchDebug.enabled
        fb.configure(store)
        correctOn = store.autoCorrect
        suggestOn = store.suggestions
        assistantOn = store.assistantOn
        agentMode = store.agentMode
        skills = store.skills
        keys?.swipeEnabled = store.swipeTyping
        hindiOn = store.hindiScript
        keys?.spaceLanguage = if (hindiOn) "English · हिन्दी" else "English (US)"
        smartOn = store.smartTouch
        // The user's Hinglish counts for smart touch too: common words and the spellings Verbo has learnt.
        smartWords = if (smartOn) (Memory.HINGLISH + Hinglish.words(store).keys).toList() else emptyList()
        val barActive = assistantOn && agentMode != "manual"
        baseWheel = if (barActive && store.wheelOn) store.wheel else emptyList()
        setWheel(baseWheel, emptyList())
        keys?.wheelTurn = store.wheelTurn
        keys?.barLabel = if (!barActive) "" else if (baseWheel.isEmpty()) "hold to act" else "hold for wheel"
        keymap = store.keymap
        keys?.bound = if (assistantOn && store.keysOn) KeyAction.bound(keymap, skills, currentInputEditorInfo?.packageName) else emptySet()
    }

    private var baseWheel: List<String> = emptyList()
    private var keymap: List<KeyAction> = emptyList()
    private var hindiOn = false
    private var smartOn = true
    private var smartWords: List<String> = emptyList()

    /** Smart touch: the odds of each next letter for the word being typed (null outside plain text typing). */
    override fun letterOdds(): FloatArray? {
        if (!smartOn || !textField || numericField || isPasswordField() || botEdit?.isFocused == true) return null
        val prefix = word.toString().lowercase()
        if (prefix.startsWith("/")) return null
        return dictionary.nextLetterOdds(prefix, smartWords)
    }

    // ---------- emoji ----------

    override fun onEmoji() { showEmoji(if (store.recentEmoji.isEmpty()) 1 else 0) }

    /** The emoji panel: recent ones first, then a few sets; tapping one types it, ABC goes back to the letters. */
    private fun showEmoji(tab: Int): Unit = showPanel {
        val header = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(chipView("ABC", p.fnKey, p.keyPressed, p.text).apply {
            setOnClickListener { feedback(KeyboardView.Type.MODE); hidePanel() }
        }, LinearLayout.LayoutParams(dp(56), dp(36)).apply { setMargins(dp(2), 0, dp(4), 0) })
        val tabs = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL }
        EMOJI_TABS.forEachIndexed { i, (icon, _) ->
            tabs.addView(TextView(this@VerboIme).apply {
                text = icon
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                background = if (i == tab) rounded(p.keyPressed, 14) else null
                setOnClickListener { feedback(KeyboardView.Type.MODE); showEmoji(i) }
            }, LinearLayout.LayoutParams(dp(38), dp(34)))
        }
        header.addView(HorizontalScrollView(this@VerboIme).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconButton(R.drawable.ic_delete, "Delete") { feedback(KeyboardView.Type.BACKSPACE); onBackspace() }, LinearLayout.LayoutParams(dp(44), dp(40)))
        addView(header)
        val list = if (tab == 0) store.recentEmoji else EMOJI_TABS[tab].second.split(' ')
        val grid = LinearLayout(this@VerboIme).apply { orientation = LinearLayout.VERTICAL }
        if (list.isEmpty()) grid.addView(TextView(this@VerboIme).apply {
            text = "Emoji you use show up here"
            setTextColor(p.muted)
            setPadding(dp(12), dp(16), dp(12), dp(16))
        })
        list.chunked(8).forEach { chunk ->
            val row = LinearLayout(this@VerboIme)
            for (e in chunk) row.addView(TextView(this@VerboIme).apply {
                text = e
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
                background = pressable(Color.TRANSPARENT, p.keyPressed, 12)
                setOnClickListener {
                    feedback(KeyboardView.Type.CHAR)
                    onChar(e)
                    store.recentEmoji = listOf(e) + store.recentEmoji.filter { it != e }
                }
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
            repeat(8 - chunk.size) { row.addView(View(this@VerboIme), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(row)
        }
        addView(android.widget.ScrollView(this@VerboIme).apply { addView(grid) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }
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
        setWheel(keep.take(mid) + fits + keep.drop(mid), fits)
    }

    /**
     * The wheel's ring: [front] fills the semicircle, and rolling the rim brings in more tools and apps,
     * Auto-translate, Notion and Slack first, then the other tools, connected apps and your skills.
     */
    private fun setWheel(front: List<String>, suggested: List<String>) {
        wheelIds = if (front.isEmpty()) emptyList() else front + wheelExtras().filter { it !in front }
        keys?.wheelSlots = maxOf(1, front.size)
        keys?.wheelItems = wheelIds.map { wheelItem(it, it in suggested) }
    }

    private fun wheelExtras(): List<String> {
        val apps = Apps.ALL.map { Apps.id(it) }
        // Apps with a token come before those still waiting to be connected.
        val (ready, later) = apps.partition { id -> Apps.byId(id)?.let { it.tokenField == null || Apps.token(it, store).isNotEmpty() } ?: false }
        return (listOf("auto", "app:notion", "app:slack") + Store.WHEEL_TOOLS.map { it.first } + ready + skills.map { "skill:" + it.name } + later).distinct()
    }

    private fun buildChips() {
        val row = chipsRow ?: return
        row.removeAllViews()
        if (!assistantOn) return
        row.addView(modeChip(R.drawable.ic_pencil_line, "Type", true) { hidePanel(); showTyping() }, chipParams())
        row.addView(modeChip(R.drawable.ic_sparkle, "Act", false) { showActs() }, chipParams())
        // A share from WhatsApp & co. is waiting: one tap to save it to Notion or turn it into a post.
        if (store.inboxFresh) {
            val inbox = chipView("📥 From " + store.inboxFrom, p.accent, p.accentPressed, p.onAccent)
            inbox.setOnClickListener { feedback(KeyboardView.Type.MODE); showInboxPanel() }
            row.addView(inbox, chipParams())
        }
        val fresh = contextText == null && hasFreshClip()
        // A message that just arrived in this chat (read from its notification, when the user allowed it)
        // counts like a copied one: no copying needed to translate or answer it.
        val incoming = if (contextText == null && !fresh && store.triggersOn && store.watchMessages && store.incomingFresh &&
            store.incomingApp == currentInputEditorInfo?.packageName) store.incomingText else null
        if (incoming != null && store.incomingAt != lastAttachedClip) {
            lastAttachedClip = store.incomingAt
            contextText = incoming
            contextFromIncoming = true
            refreshContext()
        }
        // What an automatic action already made of it (a translation): one tap shows it, nothing is asked again.
        if (incoming != null || (contextText != null && contextText == store.incomingText && store.incomingFresh)) {
            val done = store.incomingResult
            if (done.isNotBlank()) {
                val chip = chipView("⚡ " + store.incomingSkill.ifBlank { "Result" } + " · " + store.incomingFrom.ifBlank { "message" }.take(18),
                    p.accent, p.accentPressed, p.onAccent)
                chip.setOnClickListener { feedback(KeyboardView.Type.MODE); showIncomingResult() }
                row.addView(chip, chipParams())
            }
        }
        // Triggers: the copied message is matched on the phone; a chip offers each matching skill.
        val clip = if (store.triggersOn) contextText ?: (if (fresh) readClip() else null) else null
        val fromIncoming = clip != null && clip == store.incomingText && store.incomingFresh
        val matched = clip?.let { c -> store.triggers.filter { Cards.matches(it.kind, it.keyword, c) } }.orEmpty()
        val stamp = if (fresh) clipStamp() else 0L
        if (fresh && matched.isNotEmpty() && stamp != lastAttachedClip) {
            // Show the copied message above the keys, once per copy (✕ removes it for good).
            lastAttachedClip = stamp
            contextText = clip
            refreshContext()
            suggestRow?.visibility = View.INVISIBLE
            chipsScroll?.visibility = View.VISIBLE
        }
        // Quick replies to the copied message, fetched once per copy, one tap to put in the field.
        val replyStamp = if (fromIncoming) store.incomingAt else if (clip != null) clipStamp() else 0L
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
        // An automatic trigger runs once per copied message, or once per incoming one the notification didn't already handle.
        val auto = matched.firstOrNull { it.auto }
        // An incoming message is handled by the notification reader itself; running it here too would ask twice.
        val autoStamp = if (fromIncoming) 0L else stamp
        // Running by itself is Pro; on Free the chip above still offers the same skill in one tap.
        if (auto != null && clip != null && autoStamp != 0L && autoStamp != lastAutoClip && !busy && store.activeKey.isNotBlank() && store.isPro) {
            lastAutoClip = autoStamp
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

    /** The Type / Act mode pill: icon plus label, solid when it is the mode you are in. */
    private fun modeChip(iconRes: Int, label: String, selected: Boolean, accented: Boolean = false, onTap: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(14), 0)
        val solid = if (accented) Color.argb(255,
            (Color.red(p.key) * 0.84f + Color.red(p.accent) * 0.16f).toInt(),
            (Color.green(p.key) * 0.84f + Color.green(p.accent) * 0.16f).toInt(),
            (Color.blue(p.key) * 0.84f + Color.blue(p.accent) * 0.16f).toInt()) else p.key
        background = pressable(if (selected) solid else p.fnKey, p.keyPressed, 17)
        val tint = if (selected) (if (accented) p.accent else p.text) else p.muted
        addView(ImageView(this@VerboIme).apply {
            setImageResource(iconRes)
            setColorFilter(tint)
            layoutParams = LinearLayout.LayoutParams(dp(15), dp(15)).apply { rightMargin = dp(6) }
        })
        addView(TextView(this@VerboIme).apply {
            text = label
            setTextColor(tint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
        })
        setOnClickListener { feedback(KeyboardView.Type.MODE); onTap() }
    }

    /** The round orb an act wears on its card. */
    private fun actArt(name: String): android.graphics.drawable.Drawable {
        if (name == "Ask AI") return SkillArt(this, R.drawable.ic_sparkles, Color.parseColor("#7DD3FC"), Color.parseColor("#2563EB")).asOrb()
        val skill = skills.firstOrNull { it.name == name }
        if (skill != null) return Art.skill(this, skill.name, skill.category).asOrb()
        val act = Acts.BUILTIN.firstOrNull { it.name == name }
        return (if (act?.needsCopied == true) SkillArt(this, R.drawable.ic_file_text, Color.parseColor("#4ADE80"), Color.parseColor("#16A34A"))
        else SkillArt(this, R.drawable.ic_sparkles, Color.parseColor("#C084FC"), Color.parseColor("#9333EA"))).asOrb()
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
        botEdit?.takeIf { it.isFocused }?.let { it.text.append(text); return }
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
        botEdit?.takeIf { it.isFocused }?.let {
            if (it.text.isNotEmpty()) it.text.delete(it.text.length - 1, it.text.length)
            return
        }
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
        botEdit?.takeIf { it.isFocused }?.let { runBotPrompt(it.text.toString()); return }
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
    override fun onWheelTurned(turn: Int) {
        store.wheelTurn = turn
    }

    override fun onWheel(index: Int) {
        val id = wheelIds.getOrNull(index) ?: return
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
            "voice" -> { voiceForBot = true; startVoice(true) }
            "clips" -> showClips()
            "meet" -> makeMeetLink()
            else -> if (id.startsWith("text:")) currentInputConnection?.commitText(id.removePrefix("text:"), 1)
            else if (id.startsWith("tool:")) runShortcut(id.removePrefix("tool:"))
            else if (id.startsWith("app:")) Apps.byKey(id.removePrefix("app:"))?.let { runApp(it) } ?: showStatus("That app is no longer available")
            else if (id.startsWith("act:")) runAct(id.removePrefix("act:")) else skills.firstOrNull { it.name == id.removePrefix("skill:") }?.let { runAction(it) } ?: showStatus("That skill no longer exists")
        }
    }

    /** An app slot on the wheel: run its skill, share to it, open it filled in, or use its connected tools. */
    /** True with Pro; otherwise says the feature is part of Pro, with Upgrade. */
    private fun proOnly(feature: String): Boolean {
        if (store.isPro) return true
        showStatus("$feature is a Verbo Pro feature", "Upgrade" to { openPage("pro") })
        return false
    }

    internal fun runApp(app: Apps.App) {
        // Apps that act through a connection (Slack, Notion, Todoist...) are Pro; drafts and shares stay free.
        if (app.tokenField != null && !proOnly(app.name)) return
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
                if (app.sends) sendToApp(app) else runTask("${app.name}…", app.task, card = false, retry = { runApp(app) })
            }
        }
    }

    /**
     * Slack, Todoist, Linear, GitHub, Discord: one tap sends or saves the text there. The text that was sent
     * leaves the field (like sending a message), and a status line says what happened, with Open and Undo.
     */
    private fun sendToApp(app: Apps.App) {
        if (busy) return
        if (store.activeKey.isBlank()) { showStatus("Add your ${store.providerName} API key to use actions", "Set up" to { openApp() }); return }
        val target = readTarget() ?: return
        val context = contextText ?: if (hasFreshClip()) readClip() else null
        if (target.text.isBlank() && context == null) { showStatus("Type something first to send it to ${app.name}"); return }
        store.recordUse(Apps.id(app))
        launch("${app.name}…", app.task, Ai.userMessage(target.text, context), connected = true) { r ->
            val line = r.text.lines().firstOrNull { it.isNotBlank() }?.take(70) ?: "Done"
            val failed = Regex("""\b(fail|error|couldn|can't|cannot|not connected|unable)""", RegexOption.IGNORE_CASE).containsMatchIn(line)
            val open = r.openUri?.let { uri -> openLabel(uri) to { openLink(uri) } }
            if (!failed && target.text.isNotBlank()) {
                // It went to the app: clear what was sent, the way a sent message leaves the box.
                val now = readTarget()
                if (now != null && now.text == target.text) {
                    val ic = currentInputConnection
                    ic?.beginBatchEdit()
                    if (now.selection == null) ic?.deleteSurroundingText(now.before.length, now.after.length) else ic?.commitText("", 1)
                    ic?.endBatchEdit()
                    undo = target.text to ""
                }
            }
            contextText = null
            refreshContext()
            showStatus(line, *listOfNotNull(open, if (undo != null && !failed) "Put text back" to { undoLast() } else null).toTypedArray())
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
        sendAfter = skill.name.equals("Place", true)
        if (contextText == null && hasFreshClip()) readClip()?.let { contextText = it }
        refreshContext()
        val schedule = name.equals("Schedule it", true)
        contextText?.let { if (understandQuickly(skill.name, it)) return }
        if (Acts.isChain(skill.instruction)) {
            val steps = Acts.chainSteps(skill.instruction)
            val task = Acts.chainTask(steps) { step -> actSkill(step)?.takeIf { !Acts.isChain(it.instruction) }?.instruction }
            runTask("${skill.name}…", task, card = true, retry = { runAct(name) }, then = { r -> finishChain(r) })
            return
        }
        runTask("${skill.name}…", Ai.skillTask(skill.instruction), card = Acts.showsCard(skill.name), retry = { runAct(name) }, then = { r ->
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

    /**
     * "Understand any language" on a received message: translated into English on the phone in a moment when
     * the language's model is there, shown as a card. Returns false (and the AI service is used) otherwise.
     */
    private fun understandQuickly(name: String, message: String): Boolean {
        if (!name.equals("Understand any language", true) || busy) return false
        val from = Acts.foreignLanguage(message)?.takeIf { it != "another language" } ?: return false
        busy = true
        showStatus("Translating from $from…")
        val token = ++generation
        worker.execute {
            val done = Offline.quick(message, "English")
            main.post {
                if (token != generation) return@post
                busy = false
                if (done != null && done != message) showResult(ActionResult("From $from\n$done", null), { chosen -> insert(chosen.text.substringAfter('\n')) }, null)
                else {
                    val skill = actSkill(name) ?: return@post
                    runTask("${skill.name}…", Ai.skillTask(skill.instruction), card = true) { }
                }
            }
        }
        return true
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

    /** The acts, in place of the keys: Type/Act and the plan badge on top, then every act as a pinned card. */
    internal fun showActs(): Unit = showPanel(dp(380)) {
        steps = null
        val head = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), 0, 0, dp(8)) }
        head.addView(modeChip(R.drawable.ic_pencil_line, "Type", false) { hidePanel(); showTyping() }, LinearLayout.LayoutParams(-2, dp(34)))
        head.addView(modeChip(R.drawable.ic_sparkle, "Act", true, true) { }, LinearLayout.LayoutParams(-2, dp(34)).apply { leftMargin = dp(6) })
        head.addView(View(this@VerboIme), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(TextView(this@VerboIme).apply {
            text = if (store.overLimit) "Limit reached" else "∞  Unlimited"
            setTextColor(Color.parseColor(if (store.overLimit) "#EF4444" else "#F59E0B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(6), 0)
        })
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
        val title = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, 0, dp(6)) }
        title.addView(TextView(this@VerboIme).apply {
            text = "My Acts"
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(iconButton(R.drawable.ic_clock, "Recent copied messages", p.muted) { showClips() }, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(title)
        val fits = Acts.forContext(contextForActs())
        val all = (listOf(Triple("✨", "Ask AI", false)) +
            skills.map { Triple(it.emoji.ifEmpty { "⚡" }, it.name, Acts.needsCopied(it.instruction)) } +
            Acts.BUILTIN.filter { b -> skills.none { it.name.equals(b.name, true) } }.map { Triple(it.emoji, it.name, it.needsCopied) })
            .sortedByDescending { it.second in fits }
        val grid = LinearLayout(this@VerboIme).apply { orientation = LinearLayout.VERTICAL }
        all.chunked(2).forEach { pair ->
            val row = LinearLayout(this@VerboIme)
            pair.forEach { (emoji, name, copiedNeeded) ->
                val pinId = if (name == "Ask AI") "ai" else "skill:$name"
                val cell = LinearLayout(this@VerboIme).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(10), dp(10))
                    background = pressable(p.key, p.keyPressed, 14)
                    val top = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL }
                    top.addView(ImageView(this@VerboIme).apply {
                        setImageDrawable(actArt(name))
                        layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                    })
                    top.addView(View(this@VerboIme), LinearLayout.LayoutParams(0, 1, 1f))
                    val pinned = pinId in store.pins
                    top.addView(ImageView(this@VerboIme).apply {
                        setImageResource(R.drawable.ic_pin)
                        setColorFilter(if (pinned) p.accent else Color.parseColor("#B9B9C6"))
                        setPadding(dp(4), dp(4), dp(4), dp(4))
                        layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
                        contentDescription = if (pinned) "Unpin $name" else "Pin $name"
                        setOnClickListener {
                            feedback(KeyboardView.Type.MODE)
                            val now = store.pins
                            store.pins = if (pinned) now - pinId else now + pinId
                            showActs()
                        }
                    })
                    addView(top)
                    addView(TextView(this@VerboIme).apply {
                        text = name
                        setTextColor(p.text)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        typeface = Typeface.DEFAULT_BOLD
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(8), 0, 0)
                    })
                    addView(TextView(this@VerboIme).apply {
                        val chain = skills.firstOrNull { it.name == name }?.instruction?.takeIf { Acts.isChain(it) }
                        text = when { name in fits -> "Fits this text"; chain != null -> "Chain · ${Acts.chainSteps(chain).size} steps"; copiedNeeded -> "Needs copied context"; else -> "Needs text input" }
                        setTextColor(when { name in fits || chain != null -> p.accent; copiedNeeded -> Color.parseColor("#16A34A"); else -> Color.parseColor("#DB2777") })
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
        addView(android.widget.ScrollView(this@VerboIme).apply {
            isVerticalScrollBarEnabled = true
            addView(grid)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val foot = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(4), 0, 0) }
        foot.addView(iconButton(R.drawable.ic_globe, "Languages", p.muted) { showLanguages() }, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(foot)
    }

    // ---------- the bot: intro, prompt, working, result ----------

    private fun botTop(onClose: () -> Unit, retry: (() -> Unit)? = null) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(View(this@VerboIme), LinearLayout.LayoutParams(0, 1, 1f))
        if (retry != null) addView(iconButton(R.drawable.ic_refresh_cw, "Try again", p.muted, retry), LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(iconButton(R.drawable.ic_x, "Back to keyboard", p.text, onClose), LinearLayout.LayoutParams(dp(40), dp(40)))
    }

    private fun botGlobe() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(iconButton(R.drawable.ic_globe, "Languages", p.muted) { showLanguages() }, LinearLayout.LayoutParams(dp(36), dp(36)))
    }

    private fun paper(doodles: Boolean) = BotPaper(doodles, resources.displayMetrics.density)

    internal fun showBotIntro(): Unit = showPanel(dp(330)) {
        // Showing the intro counts as the one-time welcome, so the banner and intro never return.
        store.welcomed = true
        welcome?.visibility = View.GONE
        background = paper(true)
        addView(botTop({ hidePanel(); showTyping() }))
        addView(View(this@VerboIme), LinearLayout.LayoutParams(1, 0, 0.4f))
        val hello = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL }
        hello.addView(BotView(this@VerboIme).apply { mood = BotView.Mood.HELLO }, LinearLayout.LayoutParams(dp(96), dp(96)))
        hello.addView(TextView(this@VerboIme).apply {
            val bubble = android.text.SpannableStringBuilder("Hey,\n")
            val bold = android.text.SpannableStringBuilder("I'm Verbo 👋")
            bold.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, bold.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            bubble.append(bold)
            text = bubble
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(3).toFloat(), dp(3).toFloat())
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        addView(hello, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(View(this@VerboIme), LinearLayout.LayoutParams(1, 0, 0.5f))
        addView(TextView(this@VerboIme).apply {
            text = "Personal assistant on keyboard:"
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        addView(TextView(this@VerboIme).apply {
            text = "write, reply, and\nfind what you need."
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        addView(View(this@VerboIme), LinearLayout.LayoutParams(1, 0, 0.5f))
        addView(TextView(this@VerboIme).apply {
            text = "Let's Try It"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#22D3EE"), Color.parseColor("#4F46E5"))).apply { cornerRadius = dp(24).toFloat() }
            setOnClickListener { feedback(KeyboardView.Type.MODE); openBot() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(24), dp(10), dp(24), dp(6)) })
        addView(View(this@VerboIme), LinearLayout.LayoutParams(1, 0, 1f))
        addView(botGlobe())
    }

    /** The bot icon: listen for a request straight away, or ask for an AI key first when none is set. */
    internal fun openBot() {
        store.welcomed = true
        welcome?.visibility = View.GONE
        if (store.activeKey.isBlank()) { showKeyNeeded(); return }
        voiceForBot = true
        startVoice(true)
    }

    /** Shown instead of the bot until an AI key is saved: one tap to the AI settings. */
    internal fun showKeyNeeded(): Unit = showPanel(dp(300)) {
        background = paper(false)
        addView(botTop({ hidePanel(); showTyping() }))
        addView(View(this@VerboIme), LinearLayout.LayoutParams(1, 0, 0.5f))
        addView(BotView(this@VerboIme).apply { mood = BotView.Mood.HELLO }, LinearLayout.LayoutParams(dp(84), dp(84)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(TextView(this@VerboIme).apply {
            text = "Add your API key first"
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(TextView(this@VerboIme).apply {
            text = "Verbo answers with your own ${store.providerName} key. It stays on this phone."
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(28), dp(4), dp(28), 0) })
        addView(TextView(this@VerboIme).apply {
            text = "Add API key"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#8B5CF6"), Color.parseColor("#4F46E5"))).apply { cornerRadius = dp(24).toFloat() }
            setOnClickListener { feedback(KeyboardView.Type.MODE); hidePanel(); showTyping(); openPage("ai") }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(24), dp(14), dp(24), 0) })
        addView(TextView(this@VerboIme).apply {
            text = "Not now"
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setOnClickListener { feedback(KeyboardView.Type.MODE); hidePanel(); showTyping() }
        }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(4) })
        addView(View(this@VerboIme), LinearLayout.LayoutParams(1, 0, 1f))
    }

    internal fun showBotPrompt(initial: String = "", editing: Boolean = false): Unit =
        showPanel(if (editing) dp(240) else dp(300), editing) {
        background = paper(false)
        addView(botTop({ hidePanel(); showTyping() }))
        addView(View(this@VerboIme), LinearLayout.LayoutParams(1, 0, 0.7f))
        // Tapping the mascot starts listening; it hears the request instead of typing it.
        addView(BotView(this@VerboIme).apply {
            mood = BotView.Mood.IDLE
            contentDescription = "Tap Verbo to speak your request"
            setOnClickListener { feedback(KeyboardView.Type.MODE); voiceForBot = true; startVoice(true) }
        }, LinearLayout.LayoutParams(dp(if (editing) 56 else 88), dp(if (editing) 56 else 88)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val edit = android.widget.EditText(this@VerboIme).apply {
            setText(initial)
            hint = "Ask Verbo anything…"
            setHintTextColor(p.muted)
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (editing) 15f else 17f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = null
            maxLines = 4
            setPadding(dp(16), dp(if (editing) 4 else 10), dp(16), dp(4))
            imeOptions = EditorInfo.IME_ACTION_DONE
            setHorizontallyScrolling(false)
            // Tapping the request brings the keys up under the panel so it can be rewritten.
            setOnClickListener { if (!editing) showBotPrompt(text.toString(), true) }
        }
        addView(edit, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(18), dp(if (editing) 4 else 10), dp(18), 0) })
        addView(TextView(this@VerboIme).apply {
            text = "Tap Verbo to speak, or type and press Go"
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (editing) 11f else 12f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(24), dp(2), dp(24), 0) })
        // Go runs the written request; speaking is handled by tapping the mascot above.
        val actions = LinearLayout(this@VerboIme).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@VerboIme).apply {
                text = "Go"
                setTextColor(p.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(24).toFloat()
                    setStroke(dp(1), Color.parseColor("#C9C9D4"))
                }
                setOnClickListener { feedback(KeyboardView.Type.MODE); runBotPrompt(edit.text.toString()) }
            }, LinearLayout.LayoutParams(0, dp(if (editing) 40 else 46), 1f))
        }
        addView(actions, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(24), dp(if (editing) 6 else 12), dp(24), 0) })
        addView(View(this@VerboIme), LinearLayout.LayoutParams(1, 0, 1f))
        addView(botGlobe())
        botEdit = edit
        if (editing) {
            // The keys stay up under this panel and type into the request while it holds focus.
            window?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            main.post { if (botEdit === edit) edit.requestFocus() }
        }
    }

    private fun runBotPrompt(prompt: String) {
        if (busy) return
        if (prompt.isBlank()) {
            hidePanel()
            showStatus("Type a request for the bot first")
            return
        }
        if (store.activeKey.isBlank()) {
            hidePanel()
            showStatus("Add your ${store.providerName} API key to use actions", "Set up" to { openApp() })
            return
        }
        val task = "You are Verbo, the user's personal assistant living on their keyboard. Do exactly what the user asks: write, reply, find, explain or plan. Use your tools when they help. Reply with only the message or answer itself, no preamble and no quotes around it."
        launch("Working on it", task, prompt, connected = true, card = true) { result ->
            resultFromBot = true
            showResult(result, { chosen -> insert(chosen.text) }) { showBotPrompt(prompt) }
        }
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
        sendAfter = skill.name.equals("Place", true)
        if (understandQuickly(skill.name, clip)) return
        runTask("${skill.name}…", Ai.skillTask(skill.instruction), card = Acts.showsCard(skill.name)) { runTrigger(t, clip) }
    }

    /**
     * A chat message arrived (read from its notification) while the keyboard may already be open.
     * In that chat it becomes the context at once; when its automatic result (a translation) is ready
     * and nothing else is on screen, the card opens by itself.
     */
    internal fun onIncomingMessage(ready: Boolean) {
        if (!isInputViewShown || !store.watchMessages || !store.incomingFresh) return
        if (store.incomingApp != currentInputEditorInfo?.packageName) return
        // An older incoming message gives way to the new one; something the user copied on purpose stays.
        if (contextText != null && contextText != store.incomingText && contextFromIncoming) contextText = null
        buildChips()
        if (ready && store.incomingResult.isNotBlank() && !busy && panel?.visibility != View.VISIBLE) {
            val empty = try { readTarget()?.text.isNullOrBlank() } catch (e: Exception) { true }
            if (empty) showIncomingResult()
        }
    }

    /** True while [contextText] is a message that came in from a notification, not one the user copied. */
    private var contextFromIncoming = false

    /** The answer an automatic action already made of an incoming message, as a card with Copy / Insert. */
    private fun showIncomingResult() {
        val text = store.incomingResult
        if (text.isBlank()) return
        contextText = store.incomingText
        refreshContext()
        val target = readTarget()
        showResult(ActionResult(text, null), { chosen -> if (target != null) deliver(target, chosen) else insert(chosen.text) }, null)
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
        contextFromIncoming = false
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
        stopVoiceWatch()
        recognizer?.destroy()
        recognizer = null
        listening = false
        voiceForBot = false
        voiceSegments.setLength(0)
        voicePartial = ""
        listenBot = null
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

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Touch debug: the session so far goes to logcat each time the keyboard closes.
        if (TouchDebug.enabled && TouchDebug.keyPresses + TouchDebug.toolbarTaps > 0) TouchDebug.logSummary()
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
            contextFromIncoming = false
            buildChips()
            main.removeCallbacks(autoRun)
            autoLang = if (assistantOn) store.autoTranslate(info?.packageName) else null
            lastAuto = ""
            refreshMode()
            hidePanel()
            showTyping()
            stopVoiceWatch()
            recognizer?.cancel()
            listening = false
            voiceForBot = false
            voiceSegments.setLength(0)
            voicePartial = ""
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
        if (fb.saving) return // Battery Saver: no background network; opening the app still syncs
        Sync.auto(store) { loadSettings(); buildChips() }
    }

    private val autoSync = object : Runnable {
        override fun run() {
            syncIfStale()
            main.postDelayed(this, Sync.EVERY_MS)
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
        // A status line hides the Type / Act pills; bring them back whenever typing resumes.
        chipsScroll?.visibility = View.VISIBLE
        if (assistantOn && (chipsRow?.childCount ?: 0) == 0) buildChips()
        val row = suggestRow ?: return
        val labels = if (slashMatches.isNotEmpty()) slashMatches.map { (it.emoji + " " + it.name).trim() } else suggestions
        if (labels.isEmpty()) {
            // Blank, not gone: the row keeps its space so the keys don't move.
            for (i in 0 until row.childCount) (row.getChildAt(i) as TextView).apply { text = ""; isClickable = false }
            row.visibility = View.INVISIBLE
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
        sendAfter = skill?.name.equals("Place", true)
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
    /** The share inbox: what WhatsApp handed Verbo, one tap from Notion or a LinkedIn post. */
    internal fun showInboxPanel() = showPanel(dp(360)) {
        background = paper(false)
        addView(botTop({ hidePanel(); showTyping() }))
        addView(TextView(this@VerboIme).apply {
            text = "Shared to Verbo"
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        addView(TextView(this@VerboIme).apply {
            text = "From ${store.inboxFrom} · save it or post it"
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        val card = LinearLayout(this@VerboIme).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        if (store.inboxFile.isNotBlank()) {
            val f = java.io.File(store.inboxFile)
            val row = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(ImageView(this@VerboIme).apply {
                setImageResource(if (store.inboxMime.startsWith("image/")) R.drawable.ic_image else R.drawable.ic_file_text)
                setColorFilter(p.accent)
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            row.addView(TextView(this@VerboIme).apply {
                text = "${store.inboxName.ifBlank { "Shared file" }} · ${String.format("%.1f", f.length() / 1024.0 / 1024.0)} MB"
                setTextColor(p.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(row)
        }
        if (store.inboxText.isNotBlank()) {
            card.addView(TextView(this@VerboIme).apply {
                text = store.inboxText
                setTextColor(p.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, if (card.childCount > 0) dp(10) else 0, 0, 0)
            })
        }
        addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(20), dp(12), dp(20), 0) })
        addView(View(this@VerboIme), LinearLayout.LayoutParams(1, 0, 0.4f))
        val save = chipView("Save to Notion", p.accent, p.accentPressed, p.onAccent)
        save.typeface = Typeface.DEFAULT_BOLD
        save.setOnClickListener { feedback(KeyboardView.Type.MODE); saveInboxToNotion() }
        addView(save, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(20), dp(10), dp(20), 0) })
        val post = chipView("Draft a LinkedIn post", p.key, p.keyPressed, p.text)
        post.typeface = Typeface.DEFAULT_BOLD
        post.setOnClickListener { feedback(KeyboardView.Type.MODE); draftInboxLinkedIn() }
        addView(post, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(20), dp(8), dp(20), 0) })
        val foot = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        if (store.inboxText.isNotBlank()) {
            val put = chipView("Insert text", p.key, p.keyPressed, p.text)
            put.setOnClickListener { feedback(KeyboardView.Type.MODE); val t = store.inboxText; hidePanel(); insert(t) }
            foot.addView(put, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(8) })
        }
        val drop = chipView("Dismiss", Color.TRANSPARENT, p.keyPressed, p.muted)
        drop.setOnClickListener { feedback(KeyboardView.Type.MODE); store.clearInbox(); hidePanel(); showTyping(); buildChips() }
        foot.addView(drop, LinearLayout.LayoutParams(0, dp(40), 1f))
        addView(foot)
        addView(botGlobe())
    }

    /** Sends the shared message/file to the user's Notion destination; no AI key needed, only the Notion token. */
    private fun saveInboxToNotion() {
        if (busy) return
        if (store.notionToken.isBlank()) {
            hidePanel()
            showStatus("Connect Notion to save pages", "Set up" to { openPage("notion") })
            return
        }
        val token = generation
        val notionToken = store.notionToken
        val parent = store.notionParent
        val isDb = store.notionParentIsDb
        val text = store.inboxText
        val name = store.inboxName
        val from = store.inboxFrom
        val mime = store.inboxMime
        val file = store.inboxFile.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
        val title = (text.lines().firstOrNull { it.isNotBlank() } ?: name.ifBlank { "Shared from $from" }).take(70)
        val content = text.ifBlank { "Shared from $from: $name" }
        busy = true
        showLoading("Saving to Notion") { busy = false; hidePanel(); showTyping() }
        worker.execute {
            val outcome = try {
                Connections.notionSave(notionToken, parent, isDb, title, content, file, mime)
            } catch (e: Exception) {
                "Notion didn't accept it: ${e.message?.take(120)}"
            }
            main.post {
                if (token != generation) return@post
                busy = false
                hidePanel()
                if (outcome.startsWith("http")) {
                    store.clearInbox()
                    buildChips()
                    showStatus("Saved to Notion", "Open" to { openLink(outcome) })
                } else showStatus(outcome, "Set up" to { openPage("notion") })
            }
        }
    }

    /** Turns the share into a publishable LinkedIn post; the result bar offers Edit, Apply and the composer. */
    private fun draftInboxLinkedIn() {
        if (busy) return
        if (store.activeKey.isBlank()) {
            hidePanel()
            showStatus("Add your ${store.providerName} API key to draft posts", "Set up" to { openApp() })
            return
        }
        val token = generation
        val input = store.inboxText.ifBlank { "A file shared from ${store.inboxFrom}: ${store.inboxName}" }
        val engine = Engine(store)
        busy = true
        showLoading("Drafting your post") { busy = false; hidePanel(); showTyping() }
        worker.execute {
            val result = try {
                engine.run(Ai.LINKEDIN_TASK, input, web = false, tools = emptyList(), label = "LinkedIn post")
            } catch (e: Exception) {
                ActionResult("The draft failed: ${e.message?.take(120)}", null)
            }
            main.post {
                if (token != generation) return@post
                busy = false
                val share = "https://www.linkedin.com/feed/?shareActive=true&text=" + Uri.encode(result.text)
                showResult(result.copy(openUri = share), { chosen -> insert(chosen.text) }, null)
            }
        }
    }

    /** Set for a Place run: its link is sent right after it goes in (chat apps only). */
    private var sendAfter = false

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
        // Only requests that may need an app, a lookup or the web carry the tools: the rest are smaller and much faster.
        launch(label, task, Ai.userMessage(target.text, context), connected = task == null || Ai.needsTools(task), card = card) { raw ->
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
        if (!store.googleConnected || !store.isPro) {
            showChoices(if (store.deviceToken.isEmpty()) "Google Meet needs your dashboard" else if (!store.isPro) "Google Meet links are a Verbo Pro feature" else "Google isn't connected yet", listOf(
                "Instant link, no account" to {
                    hidePanel()
                    // A Jitsi room exists as soon as someone opens its address; a long random name keeps it private.
                    val room = (1..14).map { "abcdefghijkmnpqrstuvwxyz23456789".random() }.joinToString("")
                    insert("https://meet.jit.si/verbo-$room")
                },
                "Open Google Meet" to { hidePanel(); showTyping(); openLink("https://meet.google.com/new") },
                if (!store.isPro && store.deviceToken.isNotEmpty()) "Upgrade to Pro" to { hidePanel(); showTyping(); openPage("pro") }
                else "How to connect Google" to {
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
        val saved = store.languages
        val mine = (saved + Store.ALL_LANGUAGES.filter { it !in saved }).take(store.wheelLanguageCount)
        showPanel {
            addView(LanguageWheel(this@VerboIme).apply {
                languages = mine
                all = Store.ALL_LANGUAGES
                autoMode = auto
                // It reopens on the languages it was last turned to, not back at the first five.
                startTurn = store.langTurn
                onFanTurned = { store.langTurn = it }
                onPick = { i ->
                    // The index is into the turning ring: your languages first, then every other one.
                    val lang = ring.getOrNull(i)
                    if (lang != null) {
                        // The ring keeps its order (no reshuffle), so the turned view stays where the user left it.
                        if (auto) { hidePanel(); setAuto(lang) } else translateInto(listOf(lang))
                    }
                }
                onCompassPick = { i ->
                    val lang = Store.ALL_LANGUAGES[i]
                    rememberLanguage(lang)
                    store.langTurn = 0 // the saved language now leads the fan: open it at the start
                    if (auto) { hidePanel(); setAuto(lang) } else translateInto(listOf(lang))
                }
                onTurn = { feedback(KeyboardView.Type.MODE) }
                onCancel = { hidePanel(); showTyping() }
                onSpace = { currentInputConnection?.commitText(" ", 1) }
                onMore = { showLanguageChoices(auto) }
                if (compass) spin()
            }, LinearLayout.LayoutParams(-1, -1))
        }
    }

    /** A language picked on the wheel or compass joins the saved list, first, so it is there next time. */
    private fun rememberLanguage(language: String) {
        val now = store.languages
        if (language !in now) store.languages = listOf(language) + now
    }

    private fun translateInto(languages: List<String>) {
        hidePanel()
        val target = readTarget()
        val text = target?.text.orEmpty()
        val one = languages.singleOrNull()
        // Your own text into one language: instantly on the phone when its model is downloaded, straight into the field.
        if (one != null && target != null && text.isNotBlank() && text.length <= MAX_CHARS && !busy && !isPasswordField()) {
            busy = true
            showStatus("Into $one…")
            val token = ++generation
            worker.execute {
                val done = Offline.quick(text, one) ?: Hinglish.quick(store, text, one) { dictionary.contains(it) }
                main.post {
                    if (token != generation) return@post
                    busy = false
                    when {
                        // The AI service translates this one; Hinglish also teaches it the user's spellings for next time.
                        done == null -> runTask("Into $one…", Ai.translateTask(languages) + if (Hinglish.isHinglish(text, { dictionary.contains(it) })) Hinglish.LEARN else "",
                            card = false, then = { r -> Hinglish.learn(store, r) }) { showLanguages() }
                        done == text -> showStatus("Already in $one")
                        else -> deliver(target, ActionResult(done, null))
                    }
                }
            }
            return
        }
        // A received message (nothing typed) or several languages at once: a card to read.
        runTask(if (one != null) "Into $one…" else "Translating…", Ai.translateTask(languages), card = text.isBlank() || one == null) { showLanguages() }
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
        if (language != null) try { Offline.prefetch(listOf(language)) } catch (e: Throwable) { }
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
        val target = readTarget() ?: return
        val text = target.text
        // Nothing to do for an empty field, for text we just produced, or for a selection the user is working on.
        if (text.isBlank() || text == lastAuto || target.selection != null || text.length > AUTO_MAX_CHARS) return
        // On the phone first: instant and free once the language's model is downloaded.
        busy = true
        val token = ++generation
        worker.execute {
            val done = Offline.quick(text, language) ?: Hinglish.quick(store, text, language) { dictionary.contains(it) }
            main.post {
                if (token != generation) return@post
                busy = false
                val learn = if (Hinglish.isHinglish(text, { dictionary.contains(it) })) Hinglish.LEARN else ""
                if (done != null) applyAuto(target, text, ActionResult(done, null), language)
                else if (store.activeKey.isBlank()) showStatus("Auto-translate needs your API key", "Set up" to { openApp() })
                else launch("→ $language…", Ai.autoTranslateTask(language) + learn, text, connected = false) { result -> applyAuto(target, text, Hinglish.learn(store, result), language) }
            }
        }
    }

    /** Puts an auto-translation in place of the text, unless the user kept typing meanwhile. */
    private fun applyAuto(target: Target, text: String, result: ActionResult, language: String) {
        val now = readTarget()
        val unchanged = now != null && now.selection == null && now.before == target.before && now.after == target.after
        if (!unchanged || result.text.isEmpty()) {
            // The user kept typing: drop this translation and wait for the next pause.
            showTyping()
            scheduleAuto()
            return
        }
        lastAuto = result.text
        if (result.text == text) {
            showTyping() // it was already in the target language
            return
        }
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(target.before.length, target.after.length)
        ic.commitText(result.text, 1)
        ic.endBatchEdit()
        undo = text to result.text
        showStatus("Translated into $language", "Undo" to { lastAuto = text; undoLast() })
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
            hidePanel()
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

    private fun showPanel(minHeight: Int = dp(250), keepKeys: Boolean = false, fill: LinearLayout.() -> Unit) {
        val box = panel ?: return
        box.removeAllViews()
        box.background = null
        botEdit = null
        listenBot = null
        window?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        // Same height as the keys, strip and suggestion row it replaces, so the app above doesn't jump.
        val height = if (keepKeys) 0 else typingViews.filter { it.visibility == View.VISIBLE }.sumOf { it.height } +
            (suggestRow?.takeIf { it.visibility != View.GONE }?.height ?: 0)
        box.layoutParams = box.layoutParams.apply { this.height = maxOf(height, minHeight) }
        box.gravity = Gravity.TOP
        suggestRow?.visibility = View.GONE
        box.fill()
        if (!keepKeys) typingViews.forEach { it.visibility = View.GONE }
        else {
            typingViews.forEach { it.visibility = View.VISIBLE }
            welcome?.visibility = View.GONE
        }
        box.visibility = View.VISIBLE
        refreshContext()
    }

    internal fun hidePanel() {
        val box = panel ?: return
        if (box.visibility != View.VISIBLE) return
        box.removeAllViews()
        box.background = null
        box.visibility = View.GONE
        botEdit = null
        listenBot = null
        window?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        typingViews.forEach { it.visibility = View.VISIBLE }
        welcome?.visibility = if (store.welcomed) View.GONE else View.VISIBLE
        showTyping()
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

    internal fun showLoading(label: String, cancel: () -> Unit) = showPanel(dp(340)) {
        background = paper(false)
        addView(botTop(cancel))
        addView(View(this@VerboIme), LinearLayout.LayoutParams(1, 0, 0.5f))
        addView(TextView(this@VerboIme).apply {
            text = "Working on it…"
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        addView(LinearLayout(this@VerboIme).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#D9D9E2"))
            }
            contentDescription = "Stop"
            setOnClickListener { feedback(KeyboardView.Type.MODE); cancel() }
            addView(View(this@VerboIme).apply { background = rounded(Color.parseColor("#111827"), 3) }, LinearLayout.LayoutParams(dp(14), dp(14)))
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(18) })
        val card = LinearLayout(this@VerboIme).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        addView(android.widget.ScrollView(this@VerboIme).apply { addView(card) }, LinearLayout.LayoutParams(-1, 0, 1f))
        steps = card
        addStep(if (contextText != null) "Reading the copied message" else "Reading your text")
        addStep(label.removeSuffix("…"))
        addView(botGlobe())
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
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
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
    /** Set just before showing the bot's own answer: only then does the cool mascot sit on the result bar. */
    private var resultFromBot = false

    internal fun showResult(result: ActionResult, onInsert: (ActionResult) -> Unit, onRetry: (() -> Unit)?) = showPanel {
        val bot = resultFromBot
        resultFromBot = false
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
        // A plain answer sits on the bot paper: text above, Edit / Apply around the cool bot below.
        removeAllViews()
        background = paper(false)
        clipChildren = false
        addView(botTop({ hidePanel(); showTyping() }, onRetry))
        val body = LinearLayout(this@VerboIme).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), 0) }
        body.addView(TextView(this@VerboIme).apply {
            text = result.text
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(dp(4).toFloat(), 1f)
            android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
            setLinkTextColor(p.accent)
            linksClickable = false
            movementMethod = null
        })
        result.openUri?.let { uri ->
            val open = chipView(openLabel(uri), Color.WHITE, p.keyPressed, p.accent)
            open.typeface = Typeface.DEFAULT_BOLD
            open.setOnClickListener { feedback(KeyboardView.Type.MODE); openLink(uri) }
            body.addView(open, LinearLayout.LayoutParams(-2, dp(36)).apply { topMargin = dp(10) })
        }
        addView(android.widget.ScrollView(this@VerboIme).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val blue = Color.parseColor("#2563EB")
        val bar = LinearLayout(this@VerboIme).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.WHITE, 24)
            clipChildren = false
        }
        fun side(icon: Int, label: String, onTap: () -> Unit) = LinearLayout(this@VerboIme).apply {
            gravity = Gravity.CENTER
            setOnClickListener { feedback(KeyboardView.Type.MODE); onTap() }
            addView(ImageView(this@VerboIme).apply { setImageResource(icon); setColorFilter(blue) }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(6) })
            addView(TextView(this@VerboIme).apply {
                text = label
                setTextColor(blue)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.DEFAULT_BOLD
            })
        }
        val imeOpts = currentInputEditorInfo?.imeOptions ?: 0
        val sends = imeOpts and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEND && imeOpts and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0
        bar.addView(side(R.drawable.ic_pencil_line, "Edit") { hidePanel(); onInsert(result) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        bar.addView(View(this@VerboIme).apply { setBackgroundColor(Color.parseColor("#E5E7EB")) }, LinearLayout.LayoutParams(dp(1), dp(22)))
        // The cool bot sits in the middle only for the bot's own answers; an action's result gets Copy there.
        if (bot) bar.addView(LinearLayout(this@VerboIme).apply {
            gravity = Gravity.CENTER
            clipChildren = false
            addView(BotView(this@VerboIme).apply { mood = BotView.Mood.COOL; translationY = -dp(12).toFloat() }, LinearLayout.LayoutParams(dp(54), dp(54)))
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        else bar.addView(side(R.drawable.ic_clipboard, "Copy") {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Verbo", result.text))
            hidePanel(); showStatus("Copied")
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        bar.addView(View(this@VerboIme).apply { setBackgroundColor(Color.parseColor("#E5E7EB")) }, LinearLayout.LayoutParams(dp(1), dp(22)))
        bar.addView(side(R.drawable.ic_arrow_up, if (sends) "Send" else "Apply") {
            feedback(KeyboardView.Type.ENTER)
            hidePanel()
            onInsert(result)
            if (sends) currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEND)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        addView(bar, LinearLayout.LayoutParams(-1, dp(54)).apply { setMargins(dp(4), dp(10), dp(4), dp(2)) })
        addView(botGlobe())
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
        // A Notion save or lookup is done once it's in Notion: say so, don't paste its title into the field.
        if (result.openUri?.contains("notion.so") == true && Cards.notion(result.text, result.openUri) != null) {
            contextText = null
            refreshContext()
            showStatus(result.text.lines().firstOrNull()?.take(60)?.ifBlank { null } ?: "Done", *listOfNotNull(open).toTypedArray())
            return
        }
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
        // "Send my location": in a chat whose enter key sends, it goes out with the same tap.
        if (sendAfter) {
            sendAfter = false
            val opts = currentInputEditorInfo?.imeOptions ?: 0
            if (opts and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEND && opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) {
                ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
                undo = null
                showStatus("Location sent")
                return
            }
        }
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
        val parsed = Uri.parse(uri)
        if (parsed.host == "www.linkedin.com" && parsed.getQueryParameter("shareActive") == "true") {
            shareLinkedIn(parsed.getQueryParameter("text").orEmpty())
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            showStatus("No app can open that")
        }
    }

    /** A drafted post goes to the LinkedIn app when it is installed, otherwise to the web composer. */
    private fun shareLinkedIn(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val linked = packageManager.queryIntentActivities(send, 0).firstOrNull { it.activityInfo.packageName == "com.linkedin.android" }
        try {
            if (linked != null) startActivity(send.setPackage("com.linkedin.android").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                "https://www.linkedin.com/feed/?shareActive=true&text=" + Uri.encode(text))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            showStatus("No app can open LinkedIn")
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
        voiceSmart = smart
        voiceCommand = command && smart
        voiceSegments.setLength(0)
        voicePartial = ""
        finalizeNext = false
        val now = System.currentTimeMillis()
        lastVoiceAt = now
        listenStartAt = now
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partial: Bundle?) {
                val heard = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!heard.isNullOrBlank()) {
                    voicePartial = heard
                    lastVoiceAt = System.currentTimeMillis()
                    heardView?.text = spokenSoFar()
                }
            }
            override fun onResults(results: Bundle?) {
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                appendSegment(heard)
                // A short pause ended this segment; keep listening unless the user truly stopped.
                if (finalizeNext || voiceDone(voiceSegments.isNotEmpty(), silentFor(), elapsed())) deliverVoice()
                else restartSegment(rec)
            }
            override fun onError(error: Int) {
                val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                    error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_CLIENT
                if (fatal && voiceSegments.isEmpty() && voicePartial.isBlank()) {
                    stopVoiceWatch()
                    listening = false
                    voiceForBot = false
                    hidePanel()
                    showStatus(when (error) {
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No connection for speech"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing"
                        else -> "Voice input failed"
                    })
                    return
                }
                // NO_MATCH / SPEECH_TIMEOUT after a pause: deliver what we have, or continue listening.
                appendSegment(voicePartial)
                voicePartial = ""
                if (finalizeNext || voiceDone(voiceSegments.isNotEmpty(), silentFor(), elapsed())) deliverVoice()
                else restartSegment(rec)
            }
            override fun onRmsChanged(rmsdB: Float) {
                levels?.push(rmsdB)
                listenBot?.energy = (rmsdB + 2f) / 12f
                if (rmsdB > VOICE_RMS) lastVoiceAt = System.currentTimeMillis()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { lastVoiceAt = System.currentTimeMillis() }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        showListening(rec, smart)
        listening = true
        rec.startListening(voiceIntent())
        main.postDelayed(voiceWatch, WATCH_MS)
    }

    private fun voiceIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Tell the service to hold on through pauses; our own watchdog decides when a pause is long enough.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
    }

    private fun restartSegment(rec: SpeechRecognizer) {
        voicePartial = ""
        lastVoiceAt = System.currentTimeMillis()
        try { rec.startListening(voiceIntent()) } catch (_: Exception) { deliverVoice() }
    }

    internal fun appendSegment(text: String?) {
        if (text.isNullOrBlank()) return
        if (voiceSegments.isNotEmpty()) voiceSegments.append(' ')
        voiceSegments.append(text.trim())
        voicePartial = ""
        heardView?.text = spokenSoFar()
    }

    internal fun spokenSoFar(): String {
        val tail = voicePartial.trim()
        return if (voiceSegments.isEmpty()) tail
        else if (tail.isEmpty()) voiceSegments.toString()
        else "$voiceSegments $tail"
    }

    private fun silentFor() = System.currentTimeMillis() - lastVoiceAt
    private fun elapsed() = System.currentTimeMillis() - listenStartAt

    /** Pure endpointing rule: only finish after a genuine long pause, a hard ceiling, or silence from the start. */
    internal fun voiceDone(have: Boolean, silentForMs: Long, elapsedMs: Long): Boolean =
        (have && silentForMs >= LONG_PAUSE_MS) || elapsedMs >= MAX_LISTEN_MS || (!have && elapsedMs >= INITIAL_SILENCE_MS)

    /** Runs every WATCH_MS: ends the session once the user has paused long enough (or never spoke). */
    private fun tickVoice() {
        if (!listening) return
        val have = voiceSegments.isNotEmpty() || voicePartial.isNotBlank()
        if (voiceDone(have, silentFor(), elapsed())) {
            finalizeNext = true
            recognizer?.stopListening() // flushes the tail into onResults, which then delivers
            // Safety net in case the service never calls back.
            main.postDelayed({ if (listening && finalizeNext) deliverVoice() }, 900L)
        } else {
            main.postDelayed(voiceWatch, WATCH_MS)
        }
    }

    private fun stopVoiceWatch() { main.removeCallbacks(voiceWatch) }

    /** Delivers everything heard across all segments as one result. */
    private fun deliverVoice() {
        if (!listening) return
        stopVoiceWatch()
        listening = false
        val heard = spokenSoFar().trim().ifBlank { null }
        val forBot = voiceForBot
        val command = voiceCommand
        val smart = voiceSmart
        voiceForBot = false
        voicePartial = ""
        voiceSegments.setLength(0)
        hidePanel()
        onVoiceResult(heard, forBot, command, smart)
    }

    private fun showListening(rec: SpeechRecognizer, smart: Boolean) = showListeningPanel(rec, smart, voiceForBot)

    /** The listening panel: the mascot hears you, words appear live, and the mic level drives its halo and waves. */
    internal fun showListeningPanel(rec: SpeechRecognizer?, smart: Boolean, forBot: Boolean) = showPanel(dp(360)) {
        background = paper(false)
        // Speaking to the bot defaults to running the request; dictation defaults to transcribe.
        voiceCommand = forBot && smart
        val header = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this@VerboIme).apply {
            text = if (forBot) "Speak to Verbo" else "Listening…"
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (forBot) header.addView(iconButton(R.drawable.ic_keyboard, "Type the request instead", p.muted) {
            stopVoiceWatch()
            rec?.cancel()
            listening = false
            voiceForBot = false
            voiceSegments.setLength(0)
            voicePartial = ""
            showBotPrompt()
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(iconButton(R.drawable.ic_x, "Stop listening", p.muted) {
            stopVoiceWatch()
            rec?.cancel()
            listening = false
            voiceForBot = false
            voiceSegments.setLength(0)
            voicePartial = ""
            hidePanel()
            showTyping()
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(header)
        addView(View(this@VerboIme), LinearLayout.LayoutParams(1, 0, 0.3f))
        val bot = BotView(this@VerboIme).apply { mood = BotView.Mood.LISTEN }
        listenBot = bot
        addView(bot, LinearLayout.LayoutParams(dp(128), dp(128)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(TextView(this@VerboIme).apply {
            text = if (forBot) "Listening for your request…" else "Listening…"
            setTextColor(p.muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        heardView = TextView(this@VerboIme).apply {
            gravity = Gravity.CENTER
            setTextColor(p.text)
            setHintTextColor(p.muted)
            hint = "Your words appear here"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.START
            setPadding(dp(16), dp(6), dp(16), dp(2))
        }
        addView(heardView, LinearLayout.LayoutParams(-1, dp(56)))
        levels = LevelView(this@VerboIme, p.accent)
        addView(levels, LinearLayout.LayoutParams(-1, dp(26)))
        addView(View(this@VerboIme), LinearLayout.LayoutParams(1, 0, 0.4f))
        // Tapping a mode stops listening and decides what happens to the words.
        val footer = LinearLayout(this@VerboIme).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(8)) }
        fun mode(label: String, asCommand: Boolean, weight: Float) {
            val on = voiceCommand == asCommand
            val chip = chipView(label, if (on) p.accent else p.key, if (on) p.accentPressed else p.keyPressed, if (on) p.onAccent else p.text)
            chip.typeface = Typeface.DEFAULT_BOLD
            chip.setOnClickListener { feedback(KeyboardView.Type.MODE); voiceCommand = asCommand; finalizeNext = true; rec?.stopListening() }
            footer.addView(chip, LinearLayout.LayoutParams(0, dp(46), weight).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        when {
            smart && forBot -> { mode("Ask Verbo", true, 1f); mode("Just type", false, 1f) }
            smart -> { mode("Command", true, 1f); mode("Transcribe", false, 1.3f) }
            else -> mode("Done", false, 1f)
        }
        addView(footer)
    }

    /** Decides what happens to heard words: a spoken request runs the bot; "Just type" dictates; otherwise tidy/command. */
    internal fun onVoiceResult(heard: String?, forBot: Boolean, command: Boolean, smart: Boolean) {
        if (heard.isNullOrBlank()) {
            showStatus("Didn't catch that")
            return
        }
        when {
            forBot && command && smart -> runBotPrompt(heard)
            forBot -> insert(heard)
            else -> onHeard(heard, command, smart)
        }
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
        suggestRow?.visibility = View.INVISIBLE
        row.visibility = View.VISIBLE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        /** The emoji panel's tabs: an icon and the emoji in it (tab 0 is filled from the recently used ones). */
        val EMOJI_TABS: List<Pair<String, String>> = listOf(
            "🕘" to "",
            "😀" to "😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥳 😏 😒 😞 😔 😟 😕 🙁 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 😶 😐 😑 😬 🙄 😯 😮 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕",
            "👍" to "👍 👎 👌 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🤝 🙏 👏 🙌 👐 🤲 💪 ✍️ 💅 🤳 🙋 🙆 🙅 🤷 🤦 🙇",
            "❤️" to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 ✨ ⭐ 🌟 🔥 💯 ✅ ❌ ⚡ 🎉 🎊 🎁 🎂 🪔 🙏",
            "🐶" to "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🦆 🦉 🐝 🦋 🐌 🐢 🐍 🐙 🐬 🐳 🌸 🌹 🌻 🌴 🍀 🌈 ☀️ 🌙 ⛅ ☔ ❄️",
            "🍕" to "🍎 🍌 🍉 🍇 🍓 🥭 🍍 🥥 🥑 🍅 🌶️ 🌽 🥕 🍞 🧀 🍳 🍔 🍟 🍕 🌭 🥪 🌮 🍜 🍝 🍛 🍣 🍱 🥟 🍦 🍩 🍪 🍫 🍬 ☕ 🍵 🥤 🍺 🍷",
            "⚽" to "⚽ 🏏 🏀 🏈 🎾 🏸 🎮 🎯 🎲 🎵 🎶 🎤 🎧 🎬 📷 📱 💻 ⌚ 📞 💡 📚 ✏️ 📌 📎 🔒 🔑 💰 💳 🚗 🚕 🚌 🚆 ✈️ 🚀 🏠 🏢 🗺️ 📍",
        )
        private const val MAX_CHARS = 20000
        private const val AUTO_PAUSE_MS = 1600L
        private const val AUTO_MAX_CHARS = 2000
        // Voice endpointing: wait for a genuine long pause before delivering, and never cut a sentence short.
        private const val LONG_PAUSE_MS = 2200L // silence this long after speech means the user is done
        private const val INITIAL_SILENCE_MS = 6000L // give up if nothing was ever said
        private const val MAX_LISTEN_MS = 60000L // hard ceiling so a session can't run forever
        private const val VOICE_RMS = 2f // loudness above this counts as the user still talking
        private const val WATCH_MS = 150L
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
