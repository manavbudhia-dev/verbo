package app.verbo.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** A stand-in for an app's text field: holds the text and reports cursor moves like a real editor would. */
class FakeField(context: Context, private val ime: () -> VerboIme) {
    val text: Editable = SpannableStringBuilder()
    val info = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        initialSelStart = 0
        initialSelEnd = 0
    }
    /** When true, cursor reports are held back until [flush], the way a busy app delivers them late. */
    var delayed = false
    private val pending = ArrayList<Int>()
    private var batch = 0

    private fun report() {
        if (batch > 0) return
        val at = Selection.getSelectionEnd(text)
        if (delayed) pending.add(at) else ime().onUpdateSelection(0, 0, at, at, -1, -1)
    }

    /** An app that reports only the final cursor position after several quick edits. */
    fun flushLastOnly() {
        pending.lastOrNull()?.let { ime().onUpdateSelection(0, 0, it, it, -1, -1) }
        pending.clear()
    }

    fun flush() {
        pending.forEach { ime().onUpdateSelection(0, 0, it, it, -1, -1) }
        pending.clear()
    }

    fun moveCursor(to: Int) {
        Selection.setSelection(text, to)
        ime().onUpdateSelection(0, 0, to, to, -1, -1)
    }

    val ic: InputConnection = object : BaseInputConnection(View(context), true) {
        override fun getEditable() = text
        override fun commitText(t: CharSequence?, newCursorPosition: Int) = super.commitText(t, newCursorPosition).also { report() }
        override fun deleteSurroundingText(before: Int, after: Int) = super.deleteSurroundingText(before, after).also { report() }
        override fun beginBatchEdit(): Boolean { batch++; return true }
        override fun endBatchEdit(): Boolean { batch--; report(); return true }
    }

    init { Selection.setSelection(text, 0) }
}

class TestIme : VerboIme() {
    lateinit var field: FakeField
    override fun getCurrentInputConnection() = field.ic
    override fun getCurrentInputEditorInfo() = field.info
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h900dp-420dpi")
class KeyboardTest {
    private lateinit var ime: TestIme
    private lateinit var field: FakeField
    private lateinit var root: View

    @Before
    fun setUp() {
        ime = Robolectric.buildService(TestIme::class.java).create().get()
        field = FakeField(RuntimeEnvironment.getApplication()) { ime }
        ime.field = field
        root = ime.onCreateInputView()
        ime.onStartInputView(field.info, false)
        val deadline = System.currentTimeMillis() + 10000
        while (!ime.dictionary.ready && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("dictionary loaded", ime.dictionary.ready)
    }

    private fun type(s: String) = s.forEach { ime.onChar(it.toString()) }

    /** A finger path through the centres of [letters], with points in between like a real swipe. */
    private fun swipePath(letters: String): List<Gesture.P> {
        layout(root)
        val c = ime.keys!!.letterCenters()
        val pts = letters.map { c[it]!! }
        val out = ArrayList<Gesture.P>()
        for (i in 1 until pts.size) for (k in 0 until 8) {
            val t = k / 8f
            out += Gesture.P(pts[i - 1].x + (pts[i].x - pts[i - 1].x) * t, pts[i - 1].y + (pts[i].y - pts[i - 1].y) * t)
        }
        out += pts.last()
        return out
    }

    @Test
    fun swipeTypingWritesTheWordAndBackspaceTakesItBack() {
        ime.onSwipe(swipePath("helo"))
        assertEquals("the first word of a sentence gets a capital", "Hello ", field.text.toString())
        val started = System.currentTimeMillis()
        ime.onSwipe(swipePath("world"))
        assertEquals("Hello world ", field.text.toString())
        assertTrue("a swipe is decoded quickly", System.currentTimeMillis() - started < 1500)
        ime.onBackspace()
        assertEquals("a backspace after a swipe removes the whole word", "Hello ", field.text.toString())
    }

    @Test
    fun textShortcutsAndMemoryExpand() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.shortcuts = listOf("addr" to "Flat 4B, Green Park")
        store.remember(Memory.Fact("UPI", "me@okicici"))
        type("send to ;addr ")
        assertEquals("send to Flat 4B, Green Park ", field.text.toString())
        type("pay ;upi ")
        assertEquals("send to Flat 4B, Green Park pay me@okicici ", field.text.toString())
        type(";nothing ")
        assertTrue(field.text.toString().endsWith(";nothing "))
    }

    @Test
    fun hinglishGetsADevanagariSuggestion() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.hindiScript = true
        ime.onStartInputView(field.info, false)
        type("kaise")
        ime.pick(0)
        assertEquals("कैसे ", field.text.toString())
    }

    @Test
    fun monthlyLimitStopsAiActions() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.apiKey = "sk-ant-test-0000"
        store.monthlyLimit = 0.01
        store.recordUsage("Polish", "claude-sonnet-5-5", 1000, 1000, 1) // 0.002 + 0.01 + 0.01 = 0.022
        assertTrue(store.overLimit)
        ime.onStartInputView(field.info, false)
        type("hello there")
        ime.onBarHeld()
        assertTrue(ime.statusMessage, ime.statusMessage.contains("limit"))
        assertEquals("hello there", field.text.toString())
    }

    @Test
    fun plainTyping() {
        type("hello world")
        assertEquals("hello world", field.text.toString())
    }

    @Test
    fun typingWhileTheAppReportsLate() {
        field.delayed = true
        type("hello there my friend")
        field.flush()
        type(" ok")
        field.flush()
        assertEquals("hello there my friend ok", field.text.toString())
    }

    @Test
    fun autoCorrectAndUndoWithBackspace() {
        type("teh ")
        assertEquals("the ", field.text.toString())
        ime.onBackspace()
        assertEquals("teh ", field.text.toString())
        type("runing.")
        assertEquals("teh running.", field.text.toString())
    }

    @Test
    fun namesAndShortWordsAreLeftAlone() {
        type("hi ")
        ime.keys!!.shift = 0
        type("Sam lol ok ")
        assertEquals("hi Sam lol ok ", field.text.toString())
    }

    @Test
    fun doubleSpaceEndsSentenceAndCapitalises() {
        assertEquals("capital at the start of the field", 1, ime.keys!!.shift)
        type("Hi")
        assertEquals(0, ime.keys!!.shift)
        type("  ")
        assertEquals("Hi. ", field.text.toString())
        assertEquals(1, ime.keys!!.shift)
    }

    @Test
    fun suggestionsCompleteTheWord() {
        type("see you tomor")
        assertTrue(ime.suggestions.toString(), "tomorrow" in ime.suggestions)
        ime.pick(ime.suggestions.indexOf("tomorrow"))
        assertEquals("see you tomorrow ", field.text.toString())
    }

    @Test
    fun cursorMovedByTheUserResetsTheWord() {
        type("helo")
        field.moveCursor(0)
        type("x ")
        assertEquals("x helo", field.text.toString())
    }

    @Test
    fun backspaceDeletesAcrossWords() {
        type("ab cd")
        repeat(4) { ime.onBackspace() }
        assertEquals("a", field.text.toString())
    }

    // ---------- touch handling ----------

    private fun layout(view: View, width: Int = 1080) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun touch(view: View, action: Int, pointers: List<Triple<Int, Float, Float>>, index: Int = 0) {
        val props = pointers.map { MotionEvent.PointerProperties().apply { id = it.first; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coords = pointers.map { MotionEvent.PointerCoords().apply { x = it.second; y = it.third; pressure = 1f; size = 1f } }.toTypedArray()
        val masked = if (pointers.size > 1 && action != MotionEvent.ACTION_MOVE) action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(now, now, masked, pointers.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        view.onTouchEvent(e)
        e.recycle()
    }

    @Test
    fun secondFingerLandingCommitsTheFirstKeyInOrder() {
        val keys = ime.keys!!
        layout(root)
        keys.shift = 0
        val rowH = keys.height / 4f
        val keyW = keys.width / 10f
        val q = Triple(0, keyW * 0.5f, rowH * 0.5f)
        val p = Triple(1, keyW * 9.5f, rowH * 0.5f)
        // q down, p down, p up, q up: the lift order is reversed, the letters must not be.
        touch(keys, MotionEvent.ACTION_DOWN, listOf(q))
        touch(keys, MotionEvent.ACTION_POINTER_DOWN, listOf(q, p), 1)
        touch(keys, MotionEvent.ACTION_POINTER_UP, listOf(q, p), 1)
        touch(keys, MotionEvent.ACTION_UP, listOf(q))
        assertEquals("qp", field.text.toString())
        // A tap in the gap between two keys still types the nearer one.
        touch(keys, MotionEvent.ACTION_DOWN, listOf(Triple(0, keyW * 1.1f, rowH * 0.98f)))
        touch(keys, MotionEvent.ACTION_UP, listOf(Triple(0, keyW * 1.1f, rowH * 0.98f)))
        assertEquals("qpw", field.text.toString())
    }

    // ---------- regressions ----------

    @Test
    fun thirdFingerLandingWhileLayoutSwitchesDoesNotCrash() {
        val keys = ime.keys!!
        keys.mode = KeyboardView.Mode.SYMBOLS
        layout(root)
        val rowH = keys.height / 4f
        val keyW = keys.width / 10f
        val two = Triple(1, keyW * 1.5f, rowH * 0.5f)
        val bar = Triple(0, keyW * 5f, rowH * 3.5f)
        val three = Triple(2, keyW * 2.5f, rowH * 0.5f)
        touch(keys, MotionEvent.ACTION_DOWN, listOf(two))
        touch(keys, MotionEvent.ACTION_POINTER_DOWN, listOf(bar, two), 0)
        // Committing the held space switches back to letters, which rebuilds the keys mid-touch.
        touch(keys, MotionEvent.ACTION_POINTER_DOWN, listOf(bar, two, three), 2)
        touch(keys, MotionEvent.ACTION_CANCEL, listOf(bar))
        assertTrue(field.text.toString(), field.text.startsWith("2 "))
    }

    @Test
    fun slashInsideNumbersIsNotACommand() {
        type("on ")
        ime.keys!!.mode = KeyboardView.Mode.SYMBOLS
        type("12/")
        assertEquals("a date must not flip the keyboard back to letters", KeyboardView.Mode.SYMBOLS, ime.keys!!.mode)
        type("11")
        assertEquals("on 12/11", field.text.toString())
    }

    @Test
    fun cursorInTheMiddleOfAWordIsNotAutoCorrected() {
        type("runing")
        field.moveCursor(4)
        type(" ")
        assertEquals("runi ng", field.text.toString())
    }

    @Test
    fun cursorMovesAreNotMistakenForOurOwnOldEdits() {
        field.delayed = true
        type("a")
        ime.onBackspace()
        type(" ")
        field.flushLastOnly() // the app skipped reporting the two earlier positions
        field.delayed = false
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(3))
        field.moveCursor(0) // the user taps at the start, where one of our old edits also left the cursor
        type("hel")
        assertTrue("suggestions should be for \"hel\", got ${ime.suggestions}", ime.suggestions.all { it.startsWith("hel") } && ime.suggestions.isNotEmpty())
    }

    // ---------- agent features ----------

    @Test
    fun holdingABoundLetterRunsItsSkillInsteadOfTyping() {
        type("hello")
        val keys = ime.keys!!
        layout(root)
        keys.shift = 0
        assertTrue("p is bound to Polish by default", 'p' in keys.bound)
        val rowH = keys.height / 4f
        val keyW = keys.width / 10f
        val p = Triple(0, keyW * 9.5f, rowH * 0.5f)
        touch(keys, MotionEvent.ACTION_DOWN, listOf(p))
        org.robolectric.shadows.ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        touch(keys, MotionEvent.ACTION_UP, listOf(p))
        assertEquals("the held letter must not be typed", "hello", field.text.toString())
        // A quick tap on the same key still types it.
        touch(keys, MotionEvent.ACTION_DOWN, listOf(p))
        touch(keys, MotionEvent.ACTION_UP, listOf(p))
        assertEquals("hellop", field.text.toString())
    }

    @Test
    fun resultMarkersAreParsedAndRemoved() {
        val r = Ai.parse("Starbucks\n1585 Broadway, New York\nhttps://www.google.com/maps/search/?api=1&query=Starbucks\n[[map:40.7596521,-73.9852254]]\n[[open:geo:0,0?q=Starbucks]]")
        assertEquals("Starbucks\n1585 Broadway, New York\nhttps://www.google.com/maps/search/?api=1&query=Starbucks", r.text)
        assertEquals(40.7596521, r.map!!.first, 1e-9)
        assertEquals("geo:0,0?q=Starbucks", r.openUri)
        assertEquals(null, Ai.parse("hi [[map:999,10]]").map)
    }

    @Test
    fun copiedMessageIsSentAsContext() {
        assertEquals("plain", Ai.userMessage("plain", null))
        val m = Ai.userMessage("who is he", "We're meeting David tomorrow")
        assertTrue(m, m.startsWith("Context") && m.contains("We're meeting David tomorrow") && m.endsWith("who is he"))
    }

    // ---------- the wheel ----------

    private fun wheelIn(v: View): LanguageWheel? = when {
        v is LanguageWheel -> v
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { wheelIn(v.getChildAt(it)) }
        else -> null
    }

    private fun holdBar(keys: KeyboardView): Triple<Int, Float, Float> {
        val bar = Triple(0, keys.width * 0.5f, keys.height * 0.88f)
        touch(keys, MotionEvent.ACTION_DOWN, listOf(bar))
        org.robolectric.shadows.ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        return bar
    }

    @Test
    fun wheelRunsTheShortcutTheFingerIsReleasedOn() {
        type("hi")
        val keys = ime.keys!!
        layout(root)
        assertEquals("default wheel has six shortcuts", 6, keys.wheelItems.size)
        holdBar(keys)
        // Slide to the far right of the wheel (Clips is the last shortcut) and let go.
        val clips = Triple(0, keys.width * 0.86f, keys.height * 0.80f)
        touch(keys, MotionEvent.ACTION_MOVE, listOf(clips))
        touch(keys, MotionEvent.ACTION_UP, listOf(clips))
        assertEquals("the clips panel replaces the keys", View.GONE, keys.visibility)
        assertEquals("holding the bar must not type a space", "hi", field.text.toString())
    }

    @Test
    fun wheelReleasedInTheMiddleDoesNothingAndATapStillTypesSpace() {
        type("hi")
        val keys = ime.keys!!
        layout(root)
        val bar = holdBar(keys)
        touch(keys, MotionEvent.ACTION_UP, listOf(bar))
        assertEquals(View.VISIBLE, keys.visibility)
        assertEquals("hi", field.text.toString())
        touch(keys, MotionEvent.ACTION_DOWN, listOf(bar))
        touch(keys, MotionEvent.ACTION_UP, listOf(bar))
        assertEquals("hi ", field.text.toString())
    }

    @Test
    fun languageWheelSpaceTypesAndCancelReturnsToKeys() {
        type("hi")
        val keys = ime.keys!!
        layout(root)
        holdBar(keys)
        val translate = Triple(0, keys.width * 0.14f, keys.height * 0.80f)
        touch(keys, MotionEvent.ACTION_MOVE, listOf(translate))
        touch(keys, MotionEvent.ACTION_UP, listOf(translate))
        val wheel = wheelIn(root)!!
        layout(root)
        val space = Triple(0, wheel.width / 2f, wheel.height - 19f)
        touch(wheel, MotionEvent.ACTION_DOWN, listOf(space))
        touch(wheel, MotionEvent.ACTION_UP, listOf(space))
        assertEquals("hi ", field.text.toString())
        assertEquals("the wheel stays up after space", wheel, wheelIn(root))
        val cancel = Triple(0, wheel.width / 2f, wheel.height - 140f)
        touch(wheel, MotionEvent.ACTION_DOWN, listOf(cancel))
        touch(wheel, MotionEvent.ACTION_UP, listOf(cancel))
        assertEquals(View.VISIBLE, keys.visibility)
    }

    @Test
    fun languageWheelReleaseOnAFlagRunsTheTranslation() {
        type("hello")
        val keys = ime.keys!!
        layout(root)
        holdBar(keys)
        val translate = Triple(0, keys.width * 0.14f, keys.height * 0.80f)
        touch(keys, MotionEvent.ACTION_MOVE, listOf(translate))
        touch(keys, MotionEvent.ACTION_UP, listOf(translate))
        val wheel = wheelIn(root)!!
        layout(root)
        assertEquals(Store.DEFAULT_LANGUAGES, wheel.languages)
        // The second flag's centre: 0.74 of the radius out, 126 degrees up from the right.
        val flag = Triple(0, wheel.width * 0.30f, wheel.height - 403f)
        touch(wheel, MotionEvent.ACTION_MOVE, listOf(flag))
        touch(wheel, MotionEvent.ACTION_UP, listOf(flag))
        assertEquals("the running task replaces the wheel", null, wheelIn(root))
    }

    @Test
    fun meetingLinkWithoutGoogleOffersAnInstantLink() {
        val keys = ime.keys!!
        layout(root)
        holdBar(keys)
        // Meeting link is the fourth of six shortcuts: its slice is centred 75 degrees up from the right.
        val r = minOf(keys.width * 0.47f, keys.height * 0.80f) * 0.68f
        val meet = Triple(0, keys.width / 2f + r * 0.2588f, keys.height - r * 0.9659f)
        touch(keys, MotionEvent.ACTION_MOVE, listOf(meet))
        touch(keys, MotionEvent.ACTION_UP, listOf(meet))
        assertEquals("with no dashboard paired, a panel of choices replaces the keys", View.GONE, keys.visibility)
        layout(root)
        val panel = (root as ViewGroup).let { g -> (0 until g.childCount).map { g.getChildAt(it) }.first { it.visibility == View.VISIBLE && it !== keys.parent && it is ViewGroup && (it as ViewGroup).childCount > 0 } }
        fun find(v: View): View? = if (v is android.widget.TextView && v.text.toString().startsWith("Instant link")) v
            else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        find(panel)!!.performClick()
        assertTrue(field.text.toString(), field.text.toString().matches(Regex("https://meet\\.jit\\.si/verbo-[a-z0-9]{14}")))
        assertEquals("the keys come back", View.VISIBLE, keys.visibility)
    }

    @Test
    fun personResultBecomesACard() {
        val c = Ai.personCard(PERSON)!!
        assertEquals("Ada Lovelace", c.name)
        assertEquals("Mathematician and writer", c.headline)
        assertEquals(listOf("Location", "Current", "Previous", "Education"), c.facts.map { it.first })
        assertEquals("Analytical Engine notes with Charles Babbage", c.facts[2].second)
        assertTrue(c.link!!.startsWith("https://www.linkedin.com/search/results/people/"))
        assertEquals("ordinary text is not a card", null, Ai.personCard("Polished text\nwith two lines"))
    }

    @Test
    fun skillDesignReplyIsParsed() {
        val reply = "NAME: Emoji Translator\nEMOJI: 😀\nDOES: Turns your text into emoji.\nINPUT: The text you typed\nOUTPUT: Emoji only\nGROUP: edit\nINSTRUCTION: Replace the text with emoji that carry the same meaning.\nUse no words at all."
        val d = Ai.parseDesign(reply)!!
        assertEquals("Emoji Translator", d.name)
        assertEquals("edit", d.group)
        assertEquals("Replace the text with emoji that carry the same meaning. Use no words at all.", d.instruction)
        assertEquals("a bad group falls back to edit", "edit", Ai.parseDesign("NAME: X\nGROUP: banana\nINSTRUCTION: Do it.")!!.group)
        assertEquals("no instruction means no skill", null, Ai.parseDesign("NAME: X\nDOES: something"))
    }

    @Test
    fun autoTranslateIsRememberedPerApp() {
        val store = Store(RuntimeEnvironment.getApplication())
        assertEquals(null, store.autoTranslate("com.chat"))
        store.setAutoTranslate("com.chat", "Hindi")
        assertEquals("Hindi", store.autoTranslate("com.chat"))
        assertEquals("another app is unaffected", null, store.autoTranslate("com.mail"))
        store.setAutoTranslate("com.chat", null)
        assertEquals(null, store.autoTranslate("com.chat"))
        assertTrue(Ai.autoTranslateTask("Hindi").contains("already in Hindi"))
    }

    @Test
    fun translateTaskNamesTheLanguages() {
        assertTrue(Ai.translateTask(listOf("Hindi")).contains("into Hindi"))
        assertTrue(Ai.translateTask(listOf("Hindi", "French")).contains("Hindi, French"))
    }

    // ---------- pictures for checking layout by eye ----------

    private fun snap(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun content(activity: android.app.Activity): View =
        (activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup).also { layout(it) }

    @Test
    fun pictures() {
        layout(root)
        snap(root, "keyboard-dark")
        type("see you tomor")
        layout(root)
        snap(root, "keyboard-suggest")

        val store = Store(RuntimeEnvironment.getApplication())
        store.theme = "light"
        store.heightPercent = 90
        ime.onStartInputView(field.info, false)
        val light = ime.keys!!.parent as View
        layout(light)
        snap(light, "keyboard-light")
        store.theme = "ocean"
        store.heightPercent = 100
        store.keyShape = "flat"
        store.numberRow = true
        ime.onStartInputView(field.info, false)
        val skin = ime.keys!!.parent as View
        layout(skin)
        snap(skin, "keyboard-ocean-flat")
        store.theme = "dark"
        store.keyShape = "rounded"
        store.numberRow = false
        ime.onStartInputView(field.info, false)
        (ime.keys!!.parent as View).let { layout(it); snap(it, "keyboard-dark-wide") }
        for ((skin, shape) in listOf("dark" to "raised", "light" to "raised", "sunset" to "glass", "forest" to "outline")) {
            store.theme = skin
            store.keyShape = shape
            ime.onStartInputView(field.info, false)
            (ime.keys!!.parent as View).let { layout(it); snap(it, "keyboard-$skin-$shape") }
        }
        store.keyShape = "raised"
        store.theme = "light"
        ime.onStartInputView(field.info, false)
        val agentRoot = ime.keys!!.parent as View
        layout(agentRoot)
        // The wheel, mid-gesture, with the finger over Translate (leftmost).
        val wheelKeys = ime.keys!!
        holdBar(wheelKeys)
        touch(wheelKeys, MotionEvent.ACTION_MOVE, listOf(Triple(0, wheelKeys.width * 0.5f, wheelKeys.height * 0.25f)))
        snap(agentRoot, "wheel")
        touch(wheelKeys, MotionEvent.ACTION_MOVE, listOf(Triple(0, wheelKeys.width * 0.14f, wheelKeys.height * 0.80f)))
        touch(wheelKeys, MotionEvent.ACTION_UP, listOf(Triple(0, wheelKeys.width * 0.14f, wheelKeys.height * 0.80f)))
        layout(agentRoot)
        snap(agentRoot, "panel-languages")
        // Mid-gesture on the light wheel: finger over the second flag, then release on Cancel.
        val langWheel = wheelIn(agentRoot)!!
        touch(langWheel, MotionEvent.ACTION_MOVE, listOf(Triple(0, langWheel.width * 0.30f, langWheel.height - 403f)))
        layout(agentRoot)
        snap(agentRoot, "panel-languages-pick")
        touch(langWheel, MotionEvent.ACTION_UP, listOf(Triple(0, langWheel.width / 2f, langWheel.height - 140f)))
        assertEquals("releasing on Cancel returns to the keys", View.VISIBLE, ime.keys!!.visibility)
        ime.showResult(ActionResult("Starbucks\n1585 Broadway, Times Square, New York, NY 10036\nhttps://www.google.com/maps/search/?api=1&query=Starbucks+1585+Broadway", "geo:0,0?q=Starbucks+1585+Broadway", 40.7596521 to -73.9852254), {}, {})
        layout(agentRoot)
        snap(agentRoot, "panel-place")
        ime.showResult(ActionResult("Best Italian in SF\n1. Flour + Water | 4.8 | Sardinian · $$$ | Mission District, SF | Authentic vibe; Spicy octopus pasta\n2. Acquerello | 4.7 | Fine dining · $$$$ | Nob Hill, SF | Michelin-starred; Hard to book\n3. Itria | 4.6 | Modern Italian · $$$ | Mission District, SF | Great cocktails; Neighborhood gem", null), {}, {})
        layout(agentRoot)
        snap(agentRoot, "panel-list")
        ime.showResult(ActionResult(PERSON, null), {}, {})
        layout(agentRoot)
        snap(agentRoot, "panel-person")
        ime.showResult(ActionResult("V2 design specs\nLast edited: Today at 10:42 AM", "https://www.notion.so/V2-design-specs-1a2b3c"), {}, {})
        layout(agentRoot)
        snap(agentRoot, "panel-notion")
        ime.showLoading("Who is…") {}
        ime.addStep("Searching Notion “David Miller”")
        layout(agentRoot)
        snap(agentRoot, "panel-loading")
        // A copied message that names a person and a time: the trigger chips.
        ime.hidePanel()
        val kept = field.text.toString()
        field.text.clear()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(android.content.ClipboardManager::class.java)
        val copied = android.content.ClipData.newPlainText("m", "We're meeting with David Miller from Google Cloud tomorrow")
        ime.clock = { org.robolectric.shadows.ShadowSystem.currentTimeMillis() } // the test clipboard stamps clips with its own clock
        clipboard.setPrimaryClip(copied)
        ime.onStartInputView(field.info, false)
        layout(agentRoot)
        snap(agentRoot, "keyboard-triggers")
        // The wheel with that message copied: Schedule it and Who is take the middle, ringed.
        val ctxKeys = ime.keys!!
        holdBar(ctxKeys)
        touch(ctxKeys, MotionEvent.ACTION_MOVE, listOf(Triple(0, ctxKeys.width * 0.5f, ctxKeys.height * 0.97f)))
        layout(agentRoot)
        snap(agentRoot, "wheel-context")
        touch(ctxKeys, MotionEvent.ACTION_UP, listOf(Triple(0, ctxKeys.width * 0.5f, ctxKeys.height * 0.97f)))
        ime.showActs()
        layout(agentRoot)
        snap(agentRoot, "panel-acts")
        ime.showResult(ActionResult("1) Thank you for your time, but I've decided not to go ahead with this purchase. I appreciate your help!\n2) Thanks for checking in, but I'm not interested in buying this right now.\n3) Thanks for the offer, but this isn't something I need at the moment.", null), {}, {})
        layout(agentRoot)
        snap(agentRoot, "panel-options")
        ime.hidePanel()
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("m", ""))
        field.text.append(kept)
        store.theme = "system"
        store.theme = "system"

        // Auto-translate switched on for this app: the banner above the keys.
        store.setAutoTranslate("com.chat", "Hindi")
        field.info.packageName = "com.chat"
        ime.onStartInputView(field.info, false)
        type("see you at 7")
        org.robolectric.shadows.ShadowLooper.idleMainLooper(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("with no API key nothing is replaced", "see you tomorsee you at 7", field.text.toString())
        (ime.keys!!.parent as View).let { layout(it); snap(it, "keyboard-auto-translate") }
        store.setAutoTranslate("com.chat", null)

        // The create screen with a plan ready for approval, then the connect step after creating it.
        run {
            val a = Robolectric.buildActivity(PageActivity::class.java, android.content.Intent().putExtra("page", "create")).setup().get()
            fun set(name: String, value: Any?) = PageActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(a, value)
            fun shot(name: String) {
                PageActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(a)
                val sc = content(a)
                sc.layout(0, 0, 1080, maxOf((sc as ViewGroup).getChildAt(0).measuredHeight, 900))
                snap(sc, name)
            }
            set("wish", "Replace my text with emojis")
            set("draft", Ai.parseDesign("NAME: Emoji Translator\nEMOJI: 😀\nDOES: Turns your message into emoji that carry the same meaning.\nINPUT: The text you typed\nOUTPUT: Emoji only, no words\nGROUP: edit\nINSTRUCTION: Replace the text with emoji that express the same meaning. Use no words. Keep the order of ideas."))
            shot("page-create-plan")
            store.skills = store.skills + Skill("😀", "Emoji Translator", "Replace the text with emoji.", "edit")
            set("draft", null)
            set("createdName", "Emoji Translator")
            shot("page-create-connect")
            store.resetSkills()
        }

        // Two connected apps, as on the design reference device.
        store.notionToken = "secret_fixture"
        store.calendlyToken = "fixture"
        // …and Verbo already enabled and chosen, so the setup banner stays hidden.
        val app = RuntimeEnvironment.getApplication()
        val imm = app.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        org.robolectric.Shadows.shadowOf(imm).setEnabledInputMethodInfoList(
            listOf(android.view.inputmethod.InputMethodInfo(app.packageName, "app.verbo.keyboard.VerboIme", "Verbo", null)))
        android.provider.Settings.Secure.putString(app.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD, "${app.packageName}/app.verbo.keyboard.VerboIme")
        for (tab in listOf("home", "library", "settings")) {
            MainActivity.tab = tab
            val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            val frame = content(main)
            val scroll = (frame as ViewGroup).getChildAt(0) as ViewGroup
            val h = maxOf(scroll.getChildAt(0).measuredHeight, 1400)
            frame.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, 1080, h)
            snap(frame, if (tab == "settings") "settings" else "home-$tab")
        }
        MainActivity.tab = "home"
        // A saved chain, as on the design reference device.
        store.skills = store.skills + Skill("⛓️", "Trip Planner", Acts.chain(listOf("Polish", "Reply", "Summarize")), "write")
        val chainPageName = "chain-edit:${store.skills.lastIndex}"
        for (page in listOf("ai", "triggers", "memory", "usage", "textshortcuts", "look", "typing", "languages", "create", "wheel", "feedback", "battery", "templates", "prompts", "automate", "shortcuts", "privacy", "dashboard", "welcome", "keys", "category:edit", "shelves", "skill-new", "skill-edit:0", chainPageName, "trigger-new", "trigger-edit:0", "chains", "copysettings")) {
            val a = Robolectric.buildActivity(PageActivity::class.java, android.content.Intent().putExtra("page", page)).setup().get()
            val s = content(a)
            val h = if (page.startsWith("skill-") || page.startsWith("trigger-")) 2000 else if (page.startsWith("chain-")) 2600 else maxOf((s as ViewGroup).getChildAt(0).measuredHeight, 900)
            s.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            s.layout(0, 0, 1080, h)
            snap(s, "page-" + page.replace(":", "-"))
        }
    }
}

private const val PERSON = "Ada Lovelace\nMathematician and writer\nLocation: London, England\nCurrent: Countess of Lovelace\nPrevious: Analytical Engine notes with Charles Babbage\nEducation: Private tutoring in mathematics and science\nhttps://www.linkedin.com/search/results/people/?keywords=Ada%20Lovelace"
