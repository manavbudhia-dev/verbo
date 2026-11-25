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
import android.widget.TextView
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
    override fun isInputViewShown() = true
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
        // Most tests look at the app itself, past the first-run sign-in screen.
        Store(RuntimeEnvironment.getApplication()).signInSkipped = true
        // Most tests were written for the classic key row (comma and full stop beside space); Soft has its own test.
        Store(RuntimeEnvironment.getApplication()).keyShape = "raised"
        // Most tests look at Pro features; the free plan has its own test.
        Store(RuntimeEnvironment.getApplication()).setPlan(Long.MAX_VALUE, "lifetime")
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
        assertEquals("default wheel shows six shortcuts", 6, keys.wheelSlots)
        assertTrue("more tools wait round the ring", keys.wheelItems.size > 6 && keys.wheelTurns)
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
    fun autoTranslateWheelKeepsTranslatingIntoTheReleasedFlag() {
        val store = Store(RuntimeEnvironment.getApplication())
        field.info.packageName = "com.chat"
        type("hello")
        layout(root)
        val show = VerboIme::class.java.getDeclaredMethod("showLanguages", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        show.isAccessible = true
        show.invoke(ime, true, false)
        val wheel = wheelIn(root)!!
        layout(root)
        assertTrue("the auto picker is the same wheel", wheel.autoMode)
        val flag = Triple(0, wheel.width * 0.30f, wheel.height - 403f)
        touch(wheel, MotionEvent.ACTION_MOVE, listOf(flag))
        touch(wheel, MotionEvent.ACTION_UP, listOf(flag))
        assertEquals(Store.DEFAULT_LANGUAGES[1], store.autoTranslate(field.info.packageName))
        assertEquals("choosing returns to the keys", null, wheelIn(root))
        store.setAutoTranslate(field.info.packageName, null)
    }

    @Test
    fun languagesPageWheelRemovesAndAddsBackALanguage() {
        val store = Store(RuntimeEnvironment.getApplication())
        val a = Robolectric.buildActivity(PageActivity::class.java, android.content.Intent().putExtra("page", "languages")).setup().get()
        fun page(): ViewGroup = (content(a) as ViewGroup).also { it.layout(0, 0, 1080, maxOf(it.getChildAt(0).measuredHeight, 900)) }
        fun flag(w: LanguageWheel, i: Int): Triple<Int, Float, Float> {
            val d = w.resources.displayMetrics.density
            val cx = w.width / 2f
            val cy = w.height - 14f * d
            val r = minOf(w.width * 0.46f, cy - 36f * d)
            val ang = Math.toRadians((180 - (i + 0.5f) * (180f / w.languages.size)).toDouble())
            return Triple(0, cx + (r * 0.74f * Math.cos(ang)).toFloat(), cy - (r * 0.74f * Math.sin(ang)).toFloat())
        }
        val wheel = wheelIn(page())!!
        assertTrue("the settings page carries the same wheel", wheel.embedded)
        assertTrue(wheel.languages.containsAll(Store.DEFAULT_LANGUAGES))
        val name = Store.DEFAULT_LANGUAGES[1]
        touch(wheel, MotionEvent.ACTION_MOVE, listOf(flag(wheel, 1)))
        touch(wheel, MotionEvent.ACTION_UP, listOf(flag(wheel, 1)))
        assertTrue("releasing on a flag of mine removes it", name !in store.languages)
        val wheel2 = wheelIn(page())!!
        val back = wheel2.languages.indexOf(name)
        assertTrue("the freed slot suggests the removed language", back >= 0)
        touch(wheel2, MotionEvent.ACTION_MOVE, listOf(flag(wheel2, back)))
        touch(wheel2, MotionEvent.ACTION_UP, listOf(flag(wheel2, back)))
        assertTrue("releasing on a suggestion adds it back", name in store.languages)
    }

    @Test
    fun skillCardsCountUsesAndPinnedSkillsSortFirst() {
        val store = Store(RuntimeEnvironment.getApplication())
        type("hello")
        ime.runAct("Polish")
        assertEquals("running a skill counts on its card", 1, store.useCount("skill:Polish"))
        store.pins = listOf("skill:Reply")
        MainActivity.tab = "library"
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val sc = content(main) as ViewGroup
        sc.layout(0, 0, 1080, maxOf(sc.getChildAt(0).measuredHeight, 900))
        val texts = ArrayList<String>()
        fun walk(v: View) {
            if (v is TextView) texts.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(sc)
        assertTrue("the pinned skill sorts above the used one", texts.indexOf("Reply") < texts.indexOf("Polish"))
        fun desc(v: View, s: String): View? = when {
            v.contentDescription == s -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { desc(v.getChildAt(it), s) }
            else -> null
        }
        desc(sc, "Pin Polish")!!.performClick()
        assertTrue("the heart pins the skill", "skill:Polish" in store.pins)
        MainActivity.tab = "home"
    }

    /** Click the pill/button whose label is [label] by walking up to its clickable parent. */
    private fun clickLabel(root: View, label: String) {
        fun find(v: View): TextView? = when {
            v is TextView && v.text.toString() == label -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        var v: View? = find(root)
        while (v != null && !v.isClickable) v = v.parent as? View
        v!!.performClick()
    }

    private fun sheetFields(sc: ViewGroup): List<android.widget.EditText> {
        val out = ArrayList<android.widget.EditText>()
        fun walk(v: View) {
            if (v is android.widget.EditText) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(sc)
        return out
    }

    private fun openSheet(page: String): ViewGroup {
        val a = Robolectric.buildActivity(PageActivity::class.java, android.content.Intent().putExtra("page", page)).setup().get()
        return (content(a) as ViewGroup).also { it.layout(0, 0, 1080, 2000) }
    }

    @Test
    fun connectApiSheetSavesANewConnection() {
        val store = Store(RuntimeEnvironment.getApplication())
        val sc = openSheet("api-new")
        val fields = sheetFields(sc)
        fields[0].setText("NBA scores")
        fields[2].setText("https://example.com/scores?q={query}")
        clickLabel(sc, "Save")
        assertEquals(1, store.customApis.size)
        assertEquals("NBA scores", store.customApis[0].name)
        assertEquals("https://example.com/scores?q={query}", store.customApis[0].url)
    }

    @Test
    fun connectApiSheetRejectsANonHttpsAddress() {
        val store = Store(RuntimeEnvironment.getApplication())
        val sc = openSheet("api-new")
        val fields = sheetFields(sc)
        fields[0].setText("Bad")
        fields[2].setText("http://example.com")
        clickLabel(sc, "Save")
        assertTrue("an http:// address is not saved", store.customApis.isEmpty())
    }

    @Test
    fun rememberSheetSavesAFact() {
        val store = Store(RuntimeEnvironment.getApplication())
        val sc = openSheet("memory-new")
        val fields = sheetFields(sc)
        fields[0].setText("Mummy")
        fields[1].setText("+91 98765 43210")
        clickLabel(sc, "Save")
        assertEquals(1, store.memory.size)
        assertEquals("Mummy", store.memory[0].label)
        assertEquals("+91 98765 43210", store.memory[0].value)
    }

    @Test
    fun keyActionSheetBindsAHoldKey() {
        val store = Store(RuntimeEnvironment.getApplication())
        val sc = openSheet("key-new")
        clickLabel(sc, "Save") // defaults: all apps, letter A, the first skill
        assertTrue("holding A now runs something", KeyAction.resolve(store.keymap, store.skills, 'a', null) != null)
    }

    // ---------- editing the wheel ----------

    private fun wheelPage(): PageActivity =
        Robolectric.buildActivity(PageActivity::class.java, android.content.Intent().putExtra("page", "wheel")).setup().get()

    private fun byDesc(v: View, s: String): View? = when {
        v.contentDescription == s && v.isClickable -> v
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { byDesc(v.getChildAt(it), s) }
        else -> null
    }

    private fun pickDialogItem(i: Int) {
        val d = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        d.listView.performItemClick(null, i, i.toLong())
    }

    @Test
    fun wheelSheetAddsAShortcutIntoAnEmptySlot() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.wheel = listOf("ai")
        val a = wheelPage()
        byDesc(content(a), "Add to the wheel")!!.performClick()
        val sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        clickLabel(sheet.findViewById(android.R.id.content), "Verbo tools")
        clickLabel(sheet.findViewById(android.R.id.content), "Voice")
        assertEquals(listOf("ai", "voice"), store.wheel)
    }

    @Test
    fun wheelSheetGreysOutShortcutsAlreadyOnTheWheel() {
        val store = Store(RuntimeEnvironment.getApplication())
        val a = wheelPage()
        byDesc(content(a), "Wheel slot 1: Translate")!!.performClick()
        val sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        clickLabel(sheet.findViewById(android.R.id.content), "Verbo tools")
        clickLabel(sheet.findViewById(android.R.id.content), "Translate")
        assertEquals("a greyed row changes nothing", Store.DEFAULT_WHEEL, store.wheel)
    }

    @Test
    fun wheelSlotTapOpensTheSheetAndChangesThatSlot() {
        val store = Store(RuntimeEnvironment.getApplication())
        val a = wheelPage()
        byDesc(content(a), "Wheel slot 1: Translate")!!.performClick()
        val sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        clickLabel(sheet.findViewById(android.R.id.content), "Verbo tools")
        clickLabel(sheet.findViewById(android.R.id.content), "Rewrite") // already on the wheel: the two swap places
        assertEquals(listOf("rewrite", "translate") + Store.DEFAULT_WHEEL.drop(2), store.wheel)
        byDesc(content(a), "Wheel slot 1: Rewrite")!!.performClick()
        val sheet2 = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        clickLabel(sheet2.findViewById(android.R.id.content), store.skills[0].name) // a skill from My Acts takes the slot
        assertEquals("skill:" + store.skills[0].name, store.wheel[0])
        assertEquals("translate", store.wheel[1])
    }

    @Test
    fun wheelHoldDragReordersAndThePinkBarRemoves() {
        val store = Store(RuntimeEnvironment.getApplication())
        val a = wheelPage()
        fun centre(v: View): Pair<Float, Float> {
            val at = IntArray(2)
            v.getLocationOnScreen(at)
            return Pair(at[0] + v.width / 2f, at[1] + v.height / 2f)
        }
        fun drag(from: View, to: View) {
            from.performLongClick()
            layout(content(a))
            val (tx, ty) = centre(to)
            val at = IntArray(2)
            from.getLocationOnScreen(at)
            touch(from, MotionEvent.ACTION_MOVE, listOf(Triple(0, tx - at[0], ty - at[1])))
            touch(from, MotionEvent.ACTION_UP, listOf(Triple(0, tx - at[0], ty - at[1])))
        }
        drag(byDesc(content(a), "Wheel slot 1: Translate")!!, byDesc(content(a), "Wheel slot 3: Ask AI")!!)
        assertEquals("held and dropped two slots along", listOf("rewrite", "ai", "translate") + Store.DEFAULT_WHEEL.drop(3), store.wheel)
        fun find(v: View): TextView? = when {
            v is TextView && v.text.toString() == "Drag here to remove" -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        val slot = byDesc(content(a), "Wheel slot 1: Rewrite")!!
        slot.performLongClick()
        layout(content(a))
        val bar = find(content(a))!!
        val (bx, by) = centre(bar)
        val at = IntArray(2)
        slot.getLocationOnScreen(at)
        touch(slot, MotionEvent.ACTION_MOVE, listOf(Triple(0, bx - at[0], by - at[1])))
        touch(slot, MotionEvent.ACTION_UP, listOf(Triple(0, bx - at[0], by - at[1])))
        assertEquals("dropped on the pink bar", listOf("ai", "translate", "meet", "voice", "clips"), store.wheel)
    }

    private fun reloadWheel() {
        val load = VerboIme::class.java.getDeclaredMethod("loadSettings")
        load.isAccessible = true
        load.invoke(ime)
    }

    @Test
    fun autoWheelSlotOpensTheAutoPicker() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.wheel = listOf("auto") + Store.DEFAULT_WHEEL.dropLast(1)
        reloadWheel()
        type("hello")
        val keys = ime.keys!!
        layout(root)
        holdBar(keys)
        val left = Triple(0, keys.width * 0.14f, keys.height * 0.80f)
        touch(keys, MotionEvent.ACTION_MOVE, listOf(left))
        touch(keys, MotionEvent.ACTION_UP, listOf(left))
        assertTrue("the auto slot opens the same flag picker in auto mode", wheelIn(root)!!.autoMode)
        store.wheel = Store.DEFAULT_WHEEL
    }

    @Test
    fun appWheelSlotSharesToWhatsApp() {
        val store = Store(RuntimeEnvironment.getApplication())
        val pm = RuntimeEnvironment.getApplication().packageManager
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").setPackage("com.whatsapp")
        val ri = android.content.pm.ResolveInfo().apply { activityInfo = android.content.pm.ActivityInfo().apply { packageName = "com.whatsapp"; name = "Share" } }
        org.robolectric.Shadows.shadowOf(pm).addResolveInfoForIntent(send, ri)
        store.wheel = listOf("app:whatsapp") + Store.DEFAULT_WHEEL.dropLast(1)
        reloadWheel()
        type("hello")
        val keys = ime.keys!!
        layout(root)
        holdBar(keys)
        val left = Triple(0, keys.width * 0.14f, keys.height * 0.80f)
        touch(keys, MotionEvent.ACTION_MOVE, listOf(left))
        touch(keys, MotionEvent.ACTION_UP, listOf(left))
        val sent = org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_SEND, sent.action)
        assertEquals("com.whatsapp", sent.`package`)
        assertEquals("hello", sent.getStringExtra(android.content.Intent.EXTRA_TEXT))
        store.wheel = Store.DEFAULT_WHEEL
    }

    @Test
    fun appWheelSlotWithoutTheAppFallsBackToTheWeb() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.wheel = listOf("app:whatsapp") + Store.DEFAULT_WHEEL.dropLast(1)
        reloadWheel()
        type("hello")
        val keys = ime.keys!!
        layout(root)
        holdBar(keys)
        val left = Triple(0, keys.width * 0.14f, keys.height * 0.80f)
        touch(keys, MotionEvent.ACTION_MOVE, listOf(left))
        touch(keys, MotionEvent.ACTION_UP, listOf(left))
        val sent = org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW, sent.action)
        assertTrue("opens WhatsApp on the web with the text", sent.dataString!!.startsWith("https://wa.me/?text=hello"))
        store.wheel = Store.DEFAULT_WHEEL
    }

    @Test
    fun wheelPageAddsAnAppWithItsBrandIcon() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.wheel = Store.DEFAULT_WHEEL.dropLast(1)
        val a = wheelPage()
        byDesc(content(a), "Add to the wheel")!!.performClick()
        val sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        clickLabel(sheet.findViewById(android.R.id.content), "Verbo tools")
        clickLabel(sheet.findViewById(android.R.id.content), "Notion")
        assertEquals("app:notion", store.wheel.last())
        assertEquals(6, store.wheel.size)
    }

    @Test
    fun actsPanelShowsPinnedCardsWithTheirNeeds() {
        val store = Store(RuntimeEnvironment.getApplication())
        ime.showActs()
        fun find(v: View, s: String): TextView? = when {
            v is TextView && v.text.toString() == s -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it), s) }
            else -> null
        }
        assertTrue("the panel is titled My Acts", find(root, "My Acts") != null)
        assertTrue("the plan badge sits on the strip", find(root, "∞  Unlimited") != null)
        assertTrue("copied-context acts say so", find(root, "Needs copied context") != null)
        assertTrue("typed-text acts say so", find(root, "Needs text input") != null)
        val name = store.skills[0].name
        byDesc(root, "Pin $name")!!.performClick()
        assertTrue("skill:$name" in store.pins)
        byDesc(root, "Unpin $name")!!.performClick()
        assertTrue("skill:$name" !in store.pins)
    }

    // ---------- the bot ----------

    private fun findText(v: View, s: String): TextView? = when {
        v is TextView && v.text.toString() == s -> v
        v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findText(v.getChildAt(it), s) }
        else -> null
    }

    @Test
    fun welcomeBannerOpensTheBotIntroThenThePrompt() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.welcomed = false
        layout(root)
        assertTrue("the banner greets a new user", findText(root, "Welcome to Verbo, the agentic keyboard") != null)
        clickLabel(root, "Welcome to Verbo, the agentic keyboard")
        assertTrue("tapping the banner remembers it", store.welcomed)
        assertTrue("the intro bubbles hello", findText(root, "Hey,\nI'm Verbo 👋") != null)
        store.apiKey = ""
        clickLabel(root, "Let's Try It")
        assertTrue("without a key the bot asks for one first", findText(root, "Add your API key first") != null)
        clickLabel(root, "Not now")
        ime.showBotPrompt()
        var edit = sheetFields(root as ViewGroup)[0]
        assertEquals("the prompt starts empty, not a fixed birthday task", "", edit.text.toString())
        assertEquals("at rest the prompt replaces the keys", View.GONE, ime.keys!!.visibility)
        edit.performClick()
        assertEquals("tapping the request brings the keys up", View.VISIBLE, ime.keys!!.visibility)
        edit = sheetFields(root as ViewGroup)[0]
        edit.requestFocus()
        ime.onChar("H")
        ime.onChar("i")
        assertEquals("key taps type into the request while it holds focus", "Hi", edit.text.toString())
        ime.onBackspace()
        assertEquals("H", edit.text.toString())
        clickLabel(root, "Go")
        assertTrue("without a key the bot asks to set up", findText(root, "Add your ${store.providerName} API key to use actions") != null)
        ime.hidePanel()
        assertEquals("the banner stays gone", View.GONE, findText(root, "Welcome to Verbo, the agentic keyboard")!!.visibility)
    }

    @Test
    fun theBotIconAsksForAKeyFirstThenListensInOneTap() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.welcomed = false
        store.apiKey = ""
        layout(root)
        byDesc(root, "Meet Verbo, the keyboard bot")!!.performClick()
        assertTrue("with no AI key the bot asks for one first", findText(root, "Add your API key first") != null)
        assertTrue("one tap to the AI settings", findText(root, "Add API key") != null)
        assertTrue("opening the bot counts as the welcome", store.welcomed)
        clickLabel(root, "Not now")
        assertEquals("not now returns to the keys", View.VISIBLE, ime.keys!!.visibility)
        assertEquals("the banner never returns", View.GONE, findText(root, "Welcome to Verbo, the agentic keyboard")!!.visibility)
        // With a key, the same single tap goes straight into listening: no panel asking to tap again.
        store.apiKey = "sk-ant-test-0000"
        ime.onStartInputView(field.info, false)
        layout(root)
        byDesc(root, "Meet Verbo, the keyboard bot")!!.performClick()
        assertEquals("no key prompt once a key is set", null, findText(root, "Add your API key first"))
        assertEquals("no tap-to-speak panel", null, findText(root, "Tap Verbo to speak, or type and press Go"))
        assertTrue("it went straight into the voice flow (here it asks for the mic permission)", findText(root, "Allow the microphone in the Verbo app") != null)
        store.apiKey = ""
    }

    @Test
    fun theShortcutWheelReopensWhereItWasTurned() {
        val keys = ime.keys!!
        layout(root)
        holdBar(keys)
        touch(keys, MotionEvent.ACTION_MOVE, listOf(rimAt(keys, 80.0)))
        touch(keys, MotionEvent.ACTION_MOVE, listOf(rimAt(keys, 100.0)))
        org.robolectric.shadows.ShadowLooper.idleMainLooper(800, java.util.concurrent.TimeUnit.MILLISECONDS)
        val centre = Triple(0, keys.width / 2f, keys.height * 0.97f)
        touch(keys, MotionEvent.ACTION_MOVE, listOf(centre))
        touch(keys, MotionEvent.ACTION_UP, listOf(centre))
        assertEquals("the turn is remembered", 1, Store(RuntimeEnvironment.getApplication()).wheelTurn)
        // Next hold, and after the keyboard comes back: the fan opens on the turned tools, not the first six.
        ime.onStartInputView(field.info, false)
        val again = ime.keys!!
        layout(root)
        holdBar(again)
        assertEquals("it reopens turned", 1f, again.wheelRotation, 0.001f)
        assertEquals("the right-most slot still shows Auto-translate", "Auto-translate", again.wheelItems[again.wheelEntryAt(5)].label)
        touch(again, MotionEvent.ACTION_MOVE, listOf(centre))
        touch(again, MotionEvent.ACTION_UP, listOf(centre))
    }

    @Test
    fun theListeningPanelAnimatesAndSpokenRequestsRunTheBot() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.welcomed = true
        ime.showListeningPanel(null, smart = true, forBot = true)
        layout(root)
        val bot = ime.listenBot
        assertTrue("the mascot is in its listening mood", bot != null && bot.mood == BotView.Mood.LISTEN)
        assertTrue("a live transcript is shown", ime.heardView != null)
        ime.heardView?.text = "Remind me to call mom tonight"
        bot!!.energy = 0.8f
        assertEquals("the mic level drives the animation", 0.8f, bot.energy, 0.001f)
        assertTrue("Ask Verbo is offered", findText(root, "Ask Verbo") != null)
        assertTrue("Just type is offered", findText(root, "Just type") != null)
        byDesc(root, "Stop listening")!!.performClick()
        assertEquals("stopping returns to the keys", View.VISIBLE, ime.keys!!.visibility)
        // A spoken request runs the bot; without a key it asks to set up.
        store.apiKey = ""
        ime.onVoiceResult("Write a thank you note", forBot = true, command = true, smart = true)
        assertTrue(findText(root, "Add your ${store.providerName} API key to use actions") != null)
        // "Just type" dictates the words straight into the field.
        field.text.clear()
        ime.onVoiceResult("hello there", forBot = true, command = false, smart = true)
        assertEquals("hello there", field.text.toString())
        // Blank speech is reported, not run.
        ime.onVoiceResult("   ", forBot = true, command = true, smart = true)
        assertTrue(findText(root, "Didn't catch that") != null)
    }

    @Test
    fun thePromptMascotStartsListeningAndTheMicButtonIsGone() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.welcomed = true
        ime.showBotPrompt()
        layout(root)
        assertTrue("the separate mic button is removed", byDesc(root, "Speak your request") == null)
        val mascot = byDesc(root, "Tap Verbo to speak your request")
        assertTrue("the mascot itself is the talk control", mascot != null && mascot.isClickable)
        // Tapping it routes into the voice flow (here it asks for the mic permission, proving it listens).
        mascot!!.performClick()
        assertTrue("tapping the mascot starts listening", findText(root, "Allow the microphone in the Verbo app") != null)
        assertTrue("Go is still offered for a typed request", findText(root, "Go") != null)
    }

    @Test
    fun voiceWaitsForALongPauseAndJoinsEverySegment() {
        // A short pause mid-sentence must NOT end the session; only a genuine long pause does.
        assertTrue("a brief pause keeps listening", !ime.voiceDone(have = true, silentForMs = 500, elapsedMs = 4000))
        assertTrue("just under the long pause keeps listening", !ime.voiceDone(true, 2199, 5000))
        assertTrue("a long pause delivers the result", ime.voiceDone(true, 2200, 5000))
        assertTrue("silence from the start is tolerated briefly", !ime.voiceDone(false, 9000, 5999))
        assertTrue("never speaking eventually gives up", ime.voiceDone(false, 0, 6000))
        assertTrue("a hard ceiling ends even continuous talk", ime.voiceDone(true, 0, 60000))
        // Words heard across restarts are joined into one request, not dropped at each pause.
        ime.appendSegment("Hello")
        ime.appendSegment("how are you today")
        ime.appendSegment(null)
        ime.appendSegment("   ")
        assertEquals("Hello how are you today", ime.spokenSoFar())
    }

    @Test
    fun aWhatsAppShareWaitsInTheKeyboardInbox() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.welcomed = true
        store.clearInbox()
        val file = File.createTempFile("invoice", ".pdf").apply { writeText("%PDF-1.4 invoice"); deleteOnExit() }
        val intent = android.content.Intent(RuntimeEnvironment.getApplication(), ShareActivity::class.java).apply {
            action = android.content.Intent.ACTION_SEND
            type = "application/pdf"
            putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.fromFile(file))
            putExtra(android.content.Intent.EXTRA_TEXT, "Invoice for March — please file")
            putExtra(android.content.Intent.EXTRA_REFERRER, android.net.Uri.parse("android-app://com.whatsapp"))
        }
        val activity = Robolectric.buildActivity(ShareActivity::class.java, intent).setup().get()
        assertEquals("the message came through", "Invoice for March — please file", store.inboxText)
        assertTrue("the file was copied into app storage", store.inboxFile.isNotBlank() && File(store.inboxFile).exists())
        assertTrue("the file keeps its name", store.inboxName.endsWith(".pdf"))
        assertEquals("WhatsApp", store.inboxFrom)
        assertTrue("the share is fresh", store.inboxFresh)
        val screen = activity.findViewById<View>(android.R.id.content)
        assertTrue("the share screen offers Notion", findText(screen, "Save to Notion") != null)
        assertTrue("the share screen offers LinkedIn", findText(screen, "Draft a LinkedIn post") != null)
        // Back in WhatsApp, the keyboard shows the inbox chip and opens the panel.
        ime.onStartInputView(field.info, false)
        layout(root)
        assertTrue("the inbox chip appears", findText(root, "📥 From WhatsApp") != null)
        clickLabel(root, "📥 From WhatsApp")
        assertTrue("the panel names the share", findText(root, "Shared to Verbo") != null)
        assertTrue("the file is previewed", findText(root, "Save to Notion") != null)
        store.notionToken = ""
        clickLabel(root, "Save to Notion")
        assertTrue("without a token it asks to connect", findText(root, "Connect Notion to save pages") != null)
        clickLabel(root, "📥 From WhatsApp")
        clickLabel(root, "Dismiss")
        assertTrue("dismissing empties the inbox", !store.inboxFresh)
        assertTrue("the chip goes away", findText(root, "📥 From WhatsApp") == null)
    }

    @Test
    fun savingTheInboxToNotionFilesItUnderTheChosenPage() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.welcomed = true
        store.notionToken = "ntn_x"
        store.notionParent = "page-42"
        store.inboxText = "Invoice for March — please file"
        store.inboxFile = ""
        store.inboxName = ""
        store.inboxFrom = "WhatsApp"
        store.inboxAt = System.currentTimeMillis()
        val paths = ArrayList<String>()
        val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                socket.use { sock ->
                    val input = java.io.BufferedInputStream(sock.getInputStream())
                    fun line(): String? {
                        val sb = StringBuilder()
                        while (true) {
                            val b = input.read()
                            if (b < 0) return null
                            if (b == '\n'.code) break
                            if (b != '\r'.code) sb.append(b.toChar())
                        }
                        return sb.toString()
                    }
                    val first = line() ?: return@use
                    var length = 0
                    while (true) {
                        val line = line() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val raw = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(raw, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                    val path = first.split(' ')[1]
                    synchronized(paths) { paths += path }
                    val out = when {
                        path.startsWith("/notion/pages") -> """{"url":"https://notion.so/saved-1"}"""
                        path.startsWith("/notion/file_uploads") -> """{"id":"up-9","upload_url":"$base/notion/send/up-9"}"""
                        else -> "{}"
                    }
                    val bytes = out.toByteArray()
                    sock.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        Connections.notionBase = "$base/notion"
        try {
            ime.showInboxPanel()
            layout(root)
            clickLabel(root, "Save to Notion")
            val deadline = System.currentTimeMillis() + 8000
            while (!ime.statusMessage.contains("Saved to Notion") && System.currentTimeMillis() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            assertTrue("the save is reported", ime.statusMessage.contains("Saved to Notion"))
            assertTrue("the page was created", paths.any { it.startsWith("/notion/pages") })
            assertTrue("the inbox is emptied once filed", !store.inboxFresh)
        } finally {
            server.close()
            Connections.notionBase = "https://api.notion.com/v1"
        }
    }

    @Test
    fun botWorkingPanelStopsAndResultBarApplies() {
        var stopped = false
        ime.showLoading("Thinking") { stopped = true }
        layout(root)
        assertTrue(findText(root, "Working on it…") != null)
        byDesc(root, "Stop")!!.performClick()
        assertTrue("the stop button cancels the wait", stopped)
        var inserted: String? = null
        val text = "Happy birthday! Hope your day is full of good laughs, great food, and all your favorite people."
        ime.showResult(ActionResult(text, null), { inserted = it.text }, null)
        layout(root)
        clickLabel(root, "Apply")
        assertEquals(text, inserted)
        ime.showResult(ActionResult("Second draft", null), { inserted = it.text }, null)
        clickLabel(root, "Edit")
        assertEquals("Edit hands the text over for editing", "Second draft", inserted)
    }

    // ---------- the 360° compass ----------

    private fun spaceTap(keys: KeyboardView) {
        val bar = Triple(0, keys.width * 0.5f, keys.height * 0.88f)
        touch(keys, MotionEvent.ACTION_DOWN, listOf(bar))
        touch(keys, MotionEvent.ACTION_UP, listOf(bar))
    }

    @Test
    fun doubleSpaceOpensTheLanguageCompassAndCentreCancels() {
        val keys = ime.keys!!
        layout(root)
        spaceTap(keys)
        assertEquals("one space is just a space", null, wheelIn(root))
        spaceTap(keys)
        val wheel = wheelIn(root)!!
        assertTrue("the second quick space opens the spinning compass", wheel.spinning)
        layout(root)
        val centre = Triple(0, wheel.width / 2f, wheel.height * 0.56f)
        touch(wheel, MotionEvent.ACTION_MOVE, listOf(centre))
        touch(wheel, MotionEvent.ACTION_UP, listOf(centre))
        assertEquals("releasing in the middle cancels back to the keys", View.VISIBLE, keys.visibility)
    }

    @Test
    fun translateFanRollsOneFlagAtATimeToReachEveryLanguage() {
        val store = Store(RuntimeEnvironment.getApplication())
        val keys = ime.keys!!
        layout(root)
        showLanguages(false, false)
        val wheel = wheelIn(root)!!
        layout(root)
        assertTrue("the fan starts flat, not as the compass", !wheel.spinning)
        assertTrue("more languages wait round the ring", wheel.fanTurns)
        val d = wheel.resources.displayMetrics.density
        val cx = wheel.width / 2f
        val cy = wheel.height - 40f * d
        val r = minOf(wheel.width * 0.46f, cy - 36f * d)
        fun at(deg: Double, k: Float): Triple<Int, Float, Float> {
            val a = Math.toRadians(deg)
            return Triple(0, cx + (r * k * Math.cos(a)).toFloat(), cy - (r * k * Math.sin(a)).toFloat())
        }
        touch(wheel, MotionEvent.ACTION_MOVE, listOf(at(80.0, 1.05f)))
        touch(wheel, MotionEvent.ACTION_MOVE, listOf(at(100.0, 1.05f)))
        // A fast extra flick while the flag is still gliding is ignored.
        touch(wheel, MotionEvent.ACTION_MOVE, listOf(at(160.0, 1.05f)))
        assertEquals("a roll queues a turn instead of jumping", 0f, wheel.fanRotation, 0.001f)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(150, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("the flags glide over slowly", wheel.fanRotation > 0f && wheel.fanRotation < 1f)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(700, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("one roll locks exactly one flag", 1f, wheel.fanRotation, 0.001f)
        // Lifting the finger after the roll keeps the wheel up on the new languages.
        touch(wheel, MotionEvent.ACTION_UP, listOf(at(160.0, 1.05f)))
        assertTrue("the wheel stays after a roll", wheelIn(root) === wheel)
        assertEquals("the turn is remembered for next time", 1, store.langTurn)
        // The right-most flag is now the first language beyond your five: a tap on it translates.
        val arabic = wheel.ring[wheel.ringIndexAt(4)]
        assertEquals("Arabic", arabic)
        touch(wheel, MotionEvent.ACTION_DOWN, listOf(at(18.0, 0.74f)))
        touch(wheel, MotionEvent.ACTION_UP, listOf(at(18.0, 0.74f)))
        assertEquals("the running task replaces the wheel", null, wheelIn(root))
        // Opened again, it shows the turned languages, not the first five.
        showLanguages(false, false)
        val again = wheelIn(root)!!
        layout(root)
        assertEquals("it reopens turned", 1f, again.fanRotation, 0.001f)
        store.langTurn = 0
    }

    /** A point on the shortcut fan's blue rim at [deg] degrees (0 = right, 180 = left). */
    private fun rimAt(keys: KeyboardView, deg: Double): Triple<Int, Float, Float> {
        val wr = minOf(keys.width * 0.47f, keys.height * 0.80f)
        val a = Math.toRadians(deg)
        return Triple(0, keys.width / 2f + (wr * 1.05f * Math.cos(a)).toFloat(), keys.height - (wr * 1.05f * Math.sin(a)).toFloat())
    }

    @Test
    fun rollingTheRimTurnsTheFanOneIconAtATimeAndReleaseRunsTheNewTool() {
        val keys = ime.keys!!
        layout(root)
        holdBar(keys)
        touch(keys, MotionEvent.ACTION_MOVE, listOf(rimAt(keys, 80.0)))
        touch(keys, MotionEvent.ACTION_MOVE, listOf(rimAt(keys, 100.0)))
        assertEquals("a roll queues a turn instead of jumping", 0f, keys.wheelRotation, 0.001f)
        // A fast second flick while the first icon is still gliding is ignored: no fast spin.
        touch(keys, MotionEvent.ACTION_MOVE, listOf(rimAt(keys, 150.0)))
        org.robolectric.shadows.ShadowLooper.idleMainLooper(150, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("the icon glides over slowly", keys.wheelRotation > 0f && keys.wheelRotation < 1f)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(700, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("one roll locks exactly one icon", 1f, keys.wheelRotation, 0.001f)
        // Slot 6 now holds the first extra tool, Auto-translate.
        val last = Triple(0, keys.width * 0.86f, keys.height * 0.80f)
        touch(keys, MotionEvent.ACTION_MOVE, listOf(Triple(0, keys.width / 2f, keys.height * 0.97f)))
        touch(keys, MotionEvent.ACTION_MOVE, listOf(last))
        touch(keys, MotionEvent.ACTION_UP, listOf(last))
        assertTrue("releasing runs the tool that turned into view: the auto-translate picker", wheelIn(root)!!.autoMode)
    }

    @Test
    fun restingAtTheFanEndKeepsTurningButSlowly() {
        val keys = ime.keys!!
        layout(root)
        holdBar(keys)
        val wr = minOf(keys.width * 0.47f, keys.height * 0.80f)
        val end = Triple(0, keys.width / 2f + wr * 0.7f, keys.height - wr * 0.06f)
        touch(keys, MotionEvent.ACTION_MOVE, listOf(end))
        org.robolectric.shadows.ShadowLooper.idleMainLooper(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("the first icon is on its way", keys.wheelRotation > 0f && keys.wheelRotation < 1f)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(2100, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("it keeps turning, one locked icon at a time (${keys.wheelRotation})", keys.wheelRotation >= 3f && keys.wheelRotation <= 4f)
        // Lifting at the end runs nothing.
        touch(keys, MotionEvent.ACTION_UP, listOf(end))
        assertEquals(View.VISIBLE, keys.visibility)
    }

    private fun chipTexts(): List<String> {
        val row = VerboIme::class.java.getDeclaredField("chipsRow").apply { isAccessible = true }.get(ime) as ViewGroup
        return (0 until row.childCount).mapNotNull { (row.getChildAt(it) as? android.widget.TextView)?.text?.toString() }
    }

    @Test
    fun aFrenchMessageThatArrivedIsWaitingAboveTheKeysWithItsTranslation() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.watchMessages = true
        store.incomingText = "Bonjour, est-ce que tu peux venir demain à la réunion ?"
        store.incomingFrom = "Camille"
        store.incomingApp = "com.chat"
        store.incomingAt = System.currentTimeMillis()
        store.incomingResult = "From French\nHi, can you come to the meeting tomorrow?\nTone: friendly, asking you to join"
        store.incomingSkill = "Understand any language"
        field.info.packageName = "com.chat"
        ime.onStartInputView(field.info, false)
        assertTrue("one tap shows the ready translation: ${chipTexts()}", chipTexts().any { it.contains("Understand any language") && it.contains("Camille") })
        // In another app the message stays out of the way.
        field.info.packageName = "com.other"
        ime.onStartInputView(field.info, false)
        assertTrue(chipTexts().none { it.contains("Camille") })
        store.clearIncoming()
        store.watchMessages = false
    }

    @Test
    fun aMessageArrivingWhileTheKeyboardIsOpenShowsItsTranslationByItself() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.watchMessages = true
        field.info.packageName = "com.whatsapp"
        ime.onStartInputView(field.info, false)
        layout(root)
        // The notification reader stores the message, then tells the open keyboard.
        store.incomingText = "Bonjour"
        store.incomingFrom = "Navya"
        store.incomingApp = "com.whatsapp"
        store.incomingAt = System.currentTimeMillis()
        store.incomingResult = ""
        store.incomingSkill = ""
        ime.onIncomingMessage(false)
        assertTrue("the message is offered straight away: ${chipTexts()}", chipTexts().any { it.contains("Understand any language") })
        // Its automatic translation arrives: the card opens without a tap.
        store.incomingResult = "From French\nHello\nTone: a friendly greeting"
        store.incomingSkill = "Understand any language"
        ime.onIncomingMessage(true)
        layout(root)
        fun has(v: View, s: String): Boolean = (v is TextView && v.text.toString().contains(s)) ||
            (v is ViewGroup && (0 until v.childCount).any { has(v.getChildAt(it), s) })
        assertTrue("the translation card is open", has(root, "Hello") && ime.keys!!.visibility == View.GONE)
        store.clearIncoming()
        store.watchMessages = false
    }

    @Test
    fun readsTheNewestMessageAndItsSenderFromAChatNotification() {
        val app = RuntimeEnvironment.getApplication()
        val msg = android.app.Notification.Builder(app, "x").setSmallIcon(R.drawable.ic_languages)
            .setContentTitle("Camille").setContentText("Bonjour, tu viens demain ?").build()
        assertEquals("Camille" to "Bonjour, tu viens demain ?", MessageListener.read(msg))
        val summary = android.app.Notification.Builder(app, "x").setSmallIcon(R.drawable.ic_languages)
            .setContentTitle("WhatsApp").setContentText("5 new messages").build()
        org.junit.Assert.assertNull("a summary is not a message", MessageListener.read(summary))
        val group = android.app.Notification.Builder(app, "x").setSmallIcon(R.drawable.ic_languages)
            .setContentTitle("Camille").setContentText("Salut").setGroup("g").setGroupSummary(true).build()
        org.junit.Assert.assertNull("a group summary is skipped", MessageListener.read(group))
    }

    @Test
    fun aFreshInstallOpensOnContinueWithGoogle() {
        val app = RuntimeEnvironment.getApplication()
        val store = Store(app)
        store.signInSkipped = false
        store.unpair()
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertTrue("the app hands over to the sign-in screen", main.isFinishing)
        val next = org.robolectric.Shadows.shadowOf(app).nextStartedActivity
        assertEquals(SignInActivity::class.java.name, next.component?.className)
        val signIn = Robolectric.buildActivity(SignInActivity::class.java).setup().get()
        val screen = signIn.findViewById<View>(android.R.id.content)
        assertTrue("Google sign-in is offered first", findText(screen, "Continue with Google") != null)
        clickLabel(screen, "Use without an account")
        assertTrue("going without an account is remembered", store.signInSkipped)
        assertTrue("and the sign-in screen steps aside", !SignInActivity.needed(store))
        // A paired phone never sees it.
        store.signInSkipped = false
        store.deviceToken = "tok"
        assertTrue(!SignInActivity.needed(store))
        store.unpair()
        store.signInSkipped = true
    }

    @Test
    fun theKeyboardKeepsOneHeightWhetherOrNotThereAreSuggestions() {
        val row = VerboIme::class.java.getDeclaredField("suggestRow").apply { isAccessible = true }.get(ime) as View
        layout(root)
        val idle = root.measuredHeight
        assertEquals("with nothing typed the row is blank but keeps its space", View.INVISIBLE, row.visibility)
        type("andr")
        layout(root)
        assertEquals("suggestions show while a word is typed", View.VISIBLE, row.visibility)
        assertEquals("the keyboard did not grow", idle, root.measuredHeight)
        type("oid ")
        layout(root)
        assertTrue("after the word the row is blank, not removed", row.visibility == View.INVISIBLE || row.visibility == View.VISIBLE)
        assertEquals("the keyboard did not shrink", idle, root.measuredHeight)
    }

    @Test
    fun theAssistantChatGreetsRemembersAndKeepsTheConversation() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.chat("[]")
        store.apiKey = ""
        var a = Robolectric.buildActivity(AssistantActivity::class.java).setup().resume().get()
        var screen = a.findViewById<View>(android.R.id.content)
        layout(screen)
        assertTrue("without a key the assistant asks for one", findText(screen, "Add your API key first") != null)
        store.apiKey = "sk-ant-test-0000"
        a = Robolectric.buildActivity(AssistantActivity::class.java).setup().resume().get()
        screen = a.findViewById(android.R.id.content)
        layout(screen)
        assertTrue("the mascot says hello", findText(screen, "Hi, I'm Verbo") != null)
        assertTrue("ideas are offered", findText(screen, AssistantActivity.IDEAS[0]) != null)
        // A "remember" command is handled on the phone and answered in the chat.
        a.send("remember my UPI is me@okicici")
        assertTrue("it remembers", store.memory.any { it.value == "me@okicici" })
        layout(screen)
        fun has(v: View, t: String): Boolean = (v is TextView && v.text.toString().contains(t)) ||
            (v is ViewGroup && (0 until v.childCount).any { has(v.getChildAt(it), t) })
        assertTrue("the reply is in the chat", has(screen, "I'll remember"))
        // The conversation is still there next time.
        val again = Robolectric.buildActivity(AssistantActivity::class.java).setup().resume().get()
        val screen2 = again.findViewById<View>(android.R.id.content)
        layout(screen2)
        assertTrue("the chat is kept", has(screen2, "remember my UPI is me@okicici"))
        store.chat("[]")
        store.apiKey = ""
    }

    @Test
    fun actionsWorkInOneTapAndOnlyLookupsOpenACard() {
        assertTrue(!Acts.showsCard("Place"))
        assertTrue(!Acts.showsCard("Schedule it"))
        assertTrue(!Acts.showsCard("LinkedIn post"))
        assertTrue(Acts.showsCard("Who is"))
        assertTrue(Acts.showsCard("Find anything"))
        assertTrue(Acts.showsCard("Reply options"))
        // Place now sends a maps link instead of describing the place, also for skills saved before.
        val store = Store(RuntimeEnvironment.getApplication())
        val old = "The text names a place. Find it with the place search and return its name, address and a Google Maps link, with the map marker line."
        store.skills = listOf(Skill("📍", "Place", old, "write", "l"))
        assertEquals(Store.PLACE_INSTRUCTION, store.skills.first().instruction)
        store.resetSkills()
    }

    @Test
    fun appShortcutsSendOrSaveInOneTap() {
        for (key in listOf("slack", "todoist", "linear", "github", "discord")) assertTrue("$key sends in one tap", Apps.byKey(key)!!.sends)
        assertEquals("Notion saves the message", "Save to Notion", Apps.byKey("notion")!!.skill)
        assertTrue("Notion's save is a one-tap act", !Acts.showsCard("Save to Notion"))
        assertTrue("Slack posts with slack_send_message", Apps.byKey("slack")!!.task!!.contains("slack_send_message"))
        // Email and LinkedIn open their ready draft at once, also for skills saved before.
        val store = Store(RuntimeEnvironment.getApplication())
        store.skills = listOf(Skill("📧", "Email", "Turn the text into a clear email with a short subject. Return the email text, then a final [[open:mailto:?subject=...&body=...]] line with both URL-encoded so it opens as a ready draft in Gmail.", "write"))
        assertEquals(Store.EMAIL_INSTRUCTION, store.skills.first().instruction)
        assertTrue(Store.EMAIL_INSTRUCTION.contains("[[go:"))
        store.resetSkills()
        // With nothing typed or copied, a send says so instead of sending an empty message.
        store.apiKey = "sk-ant-test-0000"
        store.setToken("slackToken", "xoxb-test")
        field.text.clear()
        ime.runApp(Apps.byKey("slack")!!)
        assertTrue("asks for text first: ${ime.statusMessage}", ime.statusMessage.contains("Type something first"))
        store.setToken("slackToken", "")
        store.apiKey = ""
    }

    @Test
    fun translationKnowsTheSourceLanguageOnThePhone() {
        assertEquals("fr", Offline.sourceCode("Bonjour, est-ce que tu peux venir demain ?"))
        assertEquals("es", Offline.sourceCode("Hola, ¿cómo estás? Nos vemos mañana"))
        assertEquals("en", Offline.sourceCode("Can you send me the file today please?"))
        assertEquals("hi", Offline.sourceCode("कल मिलते हैं"))
        assertEquals("Hinglish goes to the AI service", null, Offline.sourceCode("bhai kal office aa raha hai kya"))
        assertEquals("fr", Offline.languageCode("French"))
        // Without a downloaded model it never blocks: null, and the AI service takes this one.
        assertEquals(null, Offline.quick("Bonjour, tu viens demain ?", "English"))
    }

    @Test
    fun repeatedRequestsComeBackFromTheCacheForFree() {
        val store = Store(RuntimeEnvironment.getApplication())
        AnswerCache.clear(store)
        val k = AnswerCache.key("claude:claude-haiku-5-5", "Task: polish", "i reached late  sorry", false, emptyList())
        assertEquals("spacing doesn't matter", k, AnswerCache.key("claude:claude-haiku-5-5", "Task: polish", " i reached late sorry ", false, emptyList()))
        assertTrue("another model is another answer", k != AnswerCache.key("claude:claude-sonnet-5-5", "Task: polish", "i reached late sorry", false, emptyList()))
        assertEquals(null, AnswerCache.get(store, k))
        AnswerCache.put(store, k, ActionResult("I reached late, sorry.", null), 0.0004, now = 1_000_000L)
        val hit = AnswerCache.get(store, k, now = 2_000_000L)
        assertEquals("I reached late, sorry.", hit?.text)
        assertEquals(0.0004, AnswerCache.costOf(store, k), 1e-9)
        // Asking again within two minutes of a reused answer means "try again": a fresh answer.
        assertEquals(null, AnswerCache.get(store, k, now = 2_030_000L))
        // Entries expire after 30 days.
        AnswerCache.put(store, k, ActionResult("x", null), 0.0, now = 0L)
        assertEquals(null, AnswerCache.get(store, k, now = 31L * 24 * 60 * 60 * 1000))
        // "tomorrow" ties the answer to today's date.
        val a = AnswerCache.key("m", "t", "meet tomorrow at 5", false, emptyList())
        assertTrue(a.isNotEmpty())
        AnswerCache.clear(store)
        assertEquals(0, AnswerCache.size(store))
    }

    @Test
    fun hinglishIsLearntSoItCanBeTranslatedOnThePhone() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.setText("hinglish_words", "{}")
        val english = { w: String -> w in setOf("office", "meeting", "late") }
        assertTrue(Hinglish.isHinglish("bhai kal office aa raha hai kya"))
        assertTrue(!Hinglish.isHinglish("Can you send me the file today please?"))
        // Known words and English words: confident enough for the phone.
        assertEquals("भाई कल office आ रहा है क्या", Hinglish.toDevanagari("bhai kal office aa raha hai kya", emptyMap(), english))
        // The user's own spellings are guesses at first...
        assertEquals(null, Hinglish.toDevanagari("mko kese pta h", emptyMap(), english))
        // ...until the AI service teaches them once.
        val r = Hinglish.learn(store, ActionResult("How would I know?\n[[words:mko=मुझको, kese=कैसे, pta=पता, h=है]]", null))
        assertEquals("the marker line is removed", "How would I know?", r.text)
        assertEquals("मुझको कैसे पता है", Hinglish.toDevanagari("mko kese pta h", Hinglish.words(store), english))
        assertEquals("into Hindi it is just the Devanagari", "मुझको कैसे पता है", Hinglish.quick(store, "mko kese pta h", "Hindi", english))
        store.setText("hinglish_words", "{}")
    }

    @Test
    fun writingJobsGoWithoutToolsSoTheyAreFaster() {
        assertTrue(!Ai.needsTools(Ai.translateTask(listOf("French"))))
        assertTrue(!Ai.needsTools(Ai.skillTask(Store.DEFAULT_SKILLS.first { it.name == "Polish" }.instruction)))
        assertTrue(!Ai.needsTools(Ai.skillTask(Store.EMAIL_INSTRUCTION)))
        assertTrue(Ai.needsTools(Ai.skillTask(Store.PLACE_INSTRUCTION)))
        assertTrue(Ai.needsTools(Ai.skillTask(Store.NOTION_INSTRUCTION)))
        assertTrue(Ai.needsTools(Ai.skillTask(Trigger.BUILTIN.getValue("Who is"))))
    }

    @Test
    fun theFreePlanLocksProFeaturesWithAnUpgrade() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.setPlan(0L, "free")
        assertTrue(!store.isPro)
        store.apiKey = "sk-ant-test-0000"
        store.setToken("slackToken", "xoxb-test")
        type("ship it")
        ime.runApp(Apps.byKey("slack")!!)
        assertTrue("an app shortcut says it's Pro: ${ime.statusMessage}", ime.statusMessage.contains("Verbo Pro"))
        // Drafts and shares stay free.
        ime.runApp(Apps.byKey("youtube")!!)
        val opened = org.robolectric.Shadows.shadowOf(ime).nextStartedActivity
        assertTrue("YouTube still opens on Free", opened?.dataString?.contains("youtube.com") == true)
        // The assistant chat offers Pro instead.
        val chat = Robolectric.buildActivity(AssistantActivity::class.java).setup().resume().get()
        val v = chat.findViewById<View>(android.R.id.content)
        layout(v)
        assertTrue("the chat shows the Pro offer", findText(v, "See Verbo Pro") != null)
        // The Pro page lists what Pro adds.
        val page = Robolectric.buildActivity(PageActivity::class.java, android.content.Intent().putExtra("page", "pro")).setup().get()
        val pv = page.findViewById<View>(android.R.id.content)
        layout(pv)
        assertTrue(findText(pv, "You are on Free") != null && findText(pv, "Personal assistant") != null)
        // The dashboard's plan arrives with a sync.
        store.applyRemote(org.json.JSONObject().put("plan", org.json.JSONObject().put("pro", true).put("plan", "month").put("until", System.currentTimeMillis() + 86_400_000L).put("deviceLimit", 3)))
        assertTrue("a synced plan turns Pro on", store.isPro && store.proPlan == "month")
        store.setToken("slackToken", "")
        store.apiKey = ""
    }

    @Test
    fun theDashboardPageOpensMyDashboardInOneTap() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.serverUrl = "https://verbo.zealcoder.in"
        store.deviceToken = "tok"
        val page = Robolectric.buildActivity(PageActivity::class.java, android.content.Intent().putExtra("page", "dashboard")).setup().get()
        val v = page.findViewById<View>(android.R.id.content)
        layout(v)
        val button = findText(v, "Open my dashboard")
        assertTrue("the button is there when paired", button != null)
        (button!!.parent as View).performClick()
        val opened = org.robolectric.Shadows.shadowOf(page).nextStartedActivity
        assertEquals("it opens the account's dashboard", "https://verbo.zealcoder.in/", opened?.dataString)
        store.unpair()
    }

    @Test
    fun touchDebugCountsToolbarTapsAndFlagsAccidentalOnes() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.touchDebug = true
        TouchDebug.reset()
        ime.onStartInputView(field.info, false)
        layout(root)
        assertTrue("the debug build turns it on", TouchDebug.enabled && ime.keys!!.touchDebug)
        val bar = VerboIme::class.java.getDeclaredField("toolbar").apply { isAccessible = true }.get(ime) as View
        val d = root.resources.displayMetrics.density
        fun tapToolbar() {
            val x = 30 * d
            val y = bar.top + bar.height / 2f
            val t = SystemClock.uptimeMillis()
            root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, x, y, 0))
        }
        tapToolbar()
        assertEquals("a toolbar tap is counted", 1, TouchDebug.toolbarTaps)
        assertEquals("a calm tap isn't flagged", 0, TouchDebug.toolbarAccidental)
        assertTrue("it knows which pill: ${TouchDebug.summary(TouchDebug.ALL_KEYS)}", TouchDebug.summary(TouchDebug.ALL_KEYS).contains("Type 1"))
        // Typing, then the toolbar a moment later: likely an accidental activation.
        val keys = ime.keys!!
        val k = keys.touchTargets().first { it.name == "k" }.face
        touch(keys, MotionEvent.ACTION_DOWN, listOf(Triple(0, k.centerX(), k.centerY())))
        touch(keys, MotionEvent.ACTION_UP, listOf(Triple(0, k.centerX(), k.centerY())))
        tapToolbar()
        assertEquals("a tap right after typing is flagged", 1, TouchDebug.toolbarAccidental)
        store.touchDebug = false
        ime.onStartInputView(field.info, false)
        assertTrue("switched off again", !TouchDebug.enabled)
        TouchDebug.reset()
    }

    @Test
    fun typeAndActPillsComeBackAfterAStatusLine() {
        val show = VerboIme::class.java.getDeclaredMethod("showStatus", String::class.java, emptyArray<Pair<String, () -> Unit>>().javaClass)
        show.isAccessible = true
        show.invoke(ime, "Done", emptyArray<Pair<String, () -> Unit>>())
        type("h")
        val scroll = VerboIme::class.java.getDeclaredField("chipsScroll").apply { isAccessible = true }.get(ime) as View
        assertEquals("the Type / Act pills are back once typing resumes", View.VISIBLE, scroll.visibility)
    }

    /** A point on the compass rim at [deg] degrees, for flicking the language dial. */
    private fun compassAt(wheel: LanguageWheel, deg: Double): Triple<Int, Float, Float> {
        val ccx = wheel.width / 2f
        val ccy = wheel.height * 0.60f
        val cr = minOf(wheel.width * 0.44f, wheel.height * 0.40f)
        val a = Math.toRadians(deg)
        return Triple(0, ccx + (cr * 0.9f * Math.cos(a)).toFloat(), ccy + (cr * 0.9f * Math.sin(a)).toFloat())
    }

    private fun showLanguages(auto: Boolean, compass: Boolean) {
        val show = VerboIme::class.java.getDeclaredMethod("showLanguages", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        show.isAccessible = true
        show.invoke(ime, auto, compass)
    }

    @Test
    fun compassTurnsOneIconPerFlickAndLocksOnIt() {
        showLanguages(false, true)
        val wheel = wheelIn(root)!!
        layout(root)
        assertTrue(wheel.spinning)
        touch(wheel, MotionEvent.ACTION_MOVE, listOf(compassAt(wheel, 0.0)))
        touch(wheel, MotionEvent.ACTION_MOVE, listOf(compassAt(wheel, 40.0)))
        assertEquals("a flick queues a turn instead of jumping", 0f, wheel.compassRotation, 0.001f)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(120, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("the icon eases over slowly", wheel.compassRotation > 0f && wheel.compassRotation < 36f)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("one flick locks exactly one icon", 36f, wheel.compassRotation, 0.001f)
        touch(wheel, MotionEvent.ACTION_MOVE, listOf(compassAt(wheel, 80.0)))
        org.robolectric.shadows.ShadowLooper.idleMainLooper(700, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("a second flick locks the next icon", 72f, wheel.compassRotation, 0.001f)
        touch(wheel, MotionEvent.ACTION_UP, listOf(compassAt(wheel, 80.0)))
        assertTrue("lifting after a spin keeps the compass up", wheelIn(root) === wheel && wheel.spinning)
        touch(wheel, MotionEvent.ACTION_DOWN, listOf(compassAt(wheel, 80.0)))
        touch(wheel, MotionEvent.ACTION_UP, listOf(compassAt(wheel, 80.0)))
        assertEquals("a tap runs the locked language and closes the compass", null, wheelIn(root))
    }

    @Test
    fun compassPickSavesTheLanguageOntoTheTranslateWheel() {
        val store = Store(RuntimeEnvironment.getApplication())
        showLanguages(false, true)
        val wheel = wheelIn(root)!!
        layout(root)
        touch(wheel, MotionEvent.ACTION_MOVE, listOf(compassAt(wheel, 0.0)))
        touch(wheel, MotionEvent.ACTION_MOVE, listOf(compassAt(wheel, 180.0)))
        touch(wheel, MotionEvent.ACTION_UP, listOf(compassAt(wheel, 180.0)))
        touch(wheel, MotionEvent.ACTION_DOWN, listOf(compassAt(wheel, 180.0)))
        touch(wheel, MotionEvent.ACTION_UP, listOf(compassAt(wheel, 180.0)))
        assertEquals("the spun-to language is saved first", "Arabic", store.languages.first())
        assertTrue("the old languages stay", store.languages.containsAll(Store.DEFAULT_LANGUAGES))
        showLanguages(false, false)
        val fan = wheelIn(root)!!
        assertEquals("the translate wheel now leads with it", "Arabic", fan.languages.first())
        assertEquals(5, fan.languages.size)
    }

    @Test
    fun theWheelShowsFiveOrSixFlagsAsChosen() {
        val store = Store(RuntimeEnvironment.getApplication())
        val a = Robolectric.buildActivity(PageActivity::class.java, android.content.Intent().putExtra("page", "languages")).setup().get()
        byDesc(content(a), "Show 6 languages")!!.performClick()
        assertEquals(6, store.wheelLanguageCount)
        assertEquals("the settings wheel grows to six flags", 6, wheelIn(content(a))!!.languages.size)
        byDesc(content(a), "Show 5 languages")!!.performClick()
        assertEquals(5, store.wheelLanguageCount)
        // The keyboard's translate wheel follows the same choice, padding spare slots with suggestions.
        store.wheelLanguageCount = 6
        showLanguages(false, false)
        assertEquals("spare slots fill with suggestions up to six", 6, wheelIn(root)!!.languages.size)
        store.wheelLanguageCount = 5
    }

    @Test
    fun homeSearchJumpsStraightToAnyService() {
        MainActivity.tab = "home"
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        clickLabel(content(main), "Search skills, actions, apps, settings…")
        val intent = org.robolectric.Shadows.shadowOf(main).nextStartedActivity
        assertEquals("search", intent.getStringExtra("page"))
        val a = Robolectric.buildActivity(PageActivity::class.java, intent).setup().get()
        val field = sheetFields(content(a) as ViewGroup).first()
        field.setText("notion")
        clickLabel(content(a), "Notion")
        assertTrue("a skill of that name opens its editor", org.robolectric.Shadows.shadowOf(a).nextStartedActivity.getStringExtra("page")!!.startsWith("skill-edit:"))
        field.setText("slack")
        clickLabel(content(a), "Slack")
        assertEquals("automate", org.robolectric.Shadows.shadowOf(a).nextStartedActivity.getStringExtra("page"))
        field.setText("battery")
        clickLabel(content(a), "Battery")
        assertEquals("battery", org.robolectric.Shadows.shadowOf(a).nextStartedActivity.getStringExtra("page"))
        MainActivity.tab = "home"
    }

    @Test
    fun theKeyboardSyncsItselfWhileItIsUp() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.serverUrl = "http://127.0.0.1:1" // nothing listens: the refresh must fail quietly
        store.deviceToken = "token"
        store.lastSync = 0
        ime.onStartInputView(field.info, false)
        assertTrue("opening a field refreshes without a tap", store.lastSync > 0)
        val first = store.lastSync
        store.lastSync = System.currentTimeMillis() - Sync.EVERY_MS - 1
        org.robolectric.shadows.ShadowLooper.idleMainLooper(Sync.EVERY_MS + 1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("the minute tick refreshes again on its own", store.lastSync > first)
        store.unpair()
    }

    @Test
    fun settingsShowsAdvancedSettingsInline() {
        MainActivity.tab = "settings"
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val sc = content(main) as ViewGroup
        sc.layout(0, 0, 1080, maxOf(sc.getChildAt(0).measuredHeight, 900))
        val texts = ArrayList<String>()
        fun walk(v: View) {
            if (v is TextView) texts.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(sc)
        for (t in listOf("Advanced Settings", "Wheel Shortcuts", "Usage & On-device AI", "Web Dashboard", "Battery", "Export / Import", "Setup Guide"))
            assertTrue("settings lists $t inline, not behind the info button", t in texts)
        clickLabel(sc, "Wheel Shortcuts")
        assertEquals("wheel", org.robolectric.Shadows.shadowOf(main).nextStartedActivity.getStringExtra("page"))
        MainActivity.tab = "home"
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

    /** A full-screen activity at phone size (1080 x 2200). */
    private fun snapSized(view: View, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1080, 2200)
        snap(view, name)
    }

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
        // Points stay inside the tiles' band: sliding along the blue rim itself turns the ring.
        touch(wheelKeys, MotionEvent.ACTION_MOVE, listOf(Triple(0, wheelKeys.width * 0.5f, wheelKeys.height * 0.40f)))
        snap(agentRoot, "wheel")
        touch(wheelKeys, MotionEvent.ACTION_MOVE, listOf(Triple(0, wheelKeys.width * 0.20f, wheelKeys.height * 0.80f)))
        val turned = wheelKeys.wheelRotation
        val entry = wheelKeys.wheelEntryAt(0)
        touch(wheelKeys, MotionEvent.ACTION_UP, listOf(Triple(0, wheelKeys.width * 0.20f, wheelKeys.height * 0.80f)))
        layout(agentRoot)
        snap(agentRoot, "panel-languages")
        // Mid-gesture on the light wheel: finger over the second flag, then release on Cancel.
        val langWheel = wheelIn(agentRoot)
        org.junit.Assert.assertNotNull("releasing on the left-most slot opens Translate (size ${wheelKeys.width}x${wheelKeys.height}, " +
            "turned $turned, slot 0 = entry $entry of ${wheelKeys.wheelItems.map { it.label }}, keys ${wheelKeys.visibility}, status '${ime.statusMessage}')", langWheel)
        langWheel!!
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
        ime.showBotIntro()
        layout(agentRoot)
        snap(agentRoot, "bot-intro")
        ime.showKeyNeeded()
        layout(agentRoot)
        snap(agentRoot, "bot-key-needed")
        run {
            val st = Store(RuntimeEnvironment.getApplication())
            st.apiKey = "sk-ant-test-0000"
            st.chat("[]")
            val chat = Robolectric.buildActivity(AssistantActivity::class.java).setup().resume().get()
            val v = chat.findViewById<View>(android.R.id.content)
            layout(v); v.layout(0, 0, 1080, 2200); snapSized(v, "assistant-hello")
            st.chat(org.json.JSONArray()
                .put(org.json.JSONObject().put("u", true).put("t", "Remind me to call Mom tomorrow at 7pm"))
                .put(org.json.JSONObject().put("u", false).put("t", "Done! I've set it for tomorrow at 7:00 PM: Call Mom.")
                    .put("l", "https://calendar.google.com/calendar/render?action=TEMPLATE&text=Call%20Mom&dates=20261120T190000/20261120T193000"))
                .put(org.json.JSONObject().put("u", true).put("t", "And write a birthday wish for Rahul on WhatsApp"))
                .put(org.json.JSONObject().put("u", false).put("t", "Happy birthday Rahul! 🎉 Wishing you a year full of wins, laughs and great food. Have an amazing day!")
                    .put("l", "https://wa.me/?text=Happy%20birthday%20Rahul")).toString())
            val chat2 = Robolectric.buildActivity(AssistantActivity::class.java).setup().resume().get()
            val v2 = chat2.findViewById<View>(android.R.id.content)
            layout(v2); v2.layout(0, 0, 1080, 2200); snapSized(v2, "assistant-chat")
            st.chat("[]")
            st.apiKey = ""
        }
        ime.showBotPrompt()
        layout(agentRoot)
        snap(agentRoot, "bot-prompt")
        ime.showListeningPanel(null, smart = true, forBot = true)
        ime.heardView?.text = "Remind me to call mom tonight"
        ime.listenBot?.energy = 0.7f
        layout(agentRoot)
        snap(agentRoot, "bot-listening")
        ime.showLoading("Working on it") {}
        ime.addStep("Searching the web")
        layout(agentRoot)
        snap(agentRoot, "bot-working")
        ime.showResult(ActionResult("Thanks so much for the invite! I'd love to come — count me in for Saturday. Looking forward to it. 🎉", null), {}, null)
        layout(agentRoot)
        snap(agentRoot, "bot-result")
        // A WhatsApp share waiting in the inbox, and the screen the share itself lands on.
        store.inboxText = "Invoice for March — please file"
        store.inboxName = "invoice-march.pdf"
        store.inboxMime = "application/pdf"
        store.inboxFrom = "WhatsApp"
        store.inboxFile = ""
        store.inboxAt = System.currentTimeMillis()
        ime.showInboxPanel()
        layout(agentRoot)
        snap(agentRoot, "panel-inbox")
        val shareIntent = android.content.Intent(RuntimeEnvironment.getApplication(), ShareActivity::class.java).apply {
            action = android.content.Intent.ACTION_SEND
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, "Can we move Thursday's call to 3pm?")
            putExtra(android.content.Intent.EXTRA_REFERRER, android.net.Uri.parse("android-app://com.whatsapp"))
        }
        val share = Robolectric.buildActivity(ShareActivity::class.java, shareIntent).setup().get()
        val shareRoot = content(share)
        layout(shareRoot)
        snap(shareRoot, "share-screen")
        store.clearInbox()
        ime.hidePanel()
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("m", ""))
        field.text.append(kept)
        // A wheel carrying the auto-translate tool and daily apps in their own brand colours.
        store.wheel = listOf("auto", "app:notion", "app:linkedin", "app:whatsapp", "translate", "ai")
        ime.onStartInputView(field.info, false)
        val appRoot = ime.keys!!.parent as View
        val appKeys = ime.keys!!
        layout(appRoot)
        holdBar(appKeys)
        val rr = minOf(appKeys.width * 0.47f, appKeys.height * 0.80f) * 0.70f
        touch(appKeys, MotionEvent.ACTION_MOVE, listOf(Triple(0, appKeys.width / 2f + rr * 0.2588f, appKeys.height - rr * 0.9659f)))
        layout(appRoot)
        snap(appRoot, "wheel-apps")
        touch(appKeys, MotionEvent.ACTION_UP, listOf(Triple(0, appKeys.width * 0.5f, appKeys.height * 0.97f)))
        store.wheel = Store.DEFAULT_WHEEL
        ime.onStartInputView(field.info, false)
        // The shortcut fan rolled by its rim: mid-glide, then locked with Auto-translate, Notion and Slack in view.
        val cmpKeys = ime.keys!!
        val cmpRoot = cmpKeys.parent as View
        layout(cmpRoot)
        holdBar(cmpKeys)
        touch(cmpKeys, MotionEvent.ACTION_MOVE, listOf(rimAt(cmpKeys, 80.0)))
        touch(cmpKeys, MotionEvent.ACTION_MOVE, listOf(rimAt(cmpKeys, 100.0)))
        org.robolectric.shadows.ShadowLooper.idleMainLooper(280, java.util.concurrent.TimeUnit.MILLISECONDS)
        layout(cmpRoot)
        snap(cmpRoot, "wheel-turning")
        for (k in 0 until 2) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper(800, java.util.concurrent.TimeUnit.MILLISECONDS)
            touch(cmpKeys, MotionEvent.ACTION_MOVE, listOf(rimAt(cmpKeys, 100.0 + k * 20 + 20)))
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper(800, java.util.concurrent.TimeUnit.MILLISECONDS)
        layout(cmpRoot)
        snap(cmpRoot, "wheel-turned")
        val cmpCentre = Triple(0, cmpKeys.width / 2f, cmpKeys.height * 0.97f)
        touch(cmpKeys, MotionEvent.ACTION_MOVE, listOf(cmpCentre))
        touch(cmpKeys, MotionEvent.ACTION_UP, listOf(cmpCentre))
        // Double-press space: the language compass of every language.
        spaceTap(cmpKeys)
        spaceTap(cmpKeys)
        val lw = wheelIn(cmpRoot)!!
        layout(cmpRoot)
        snap(cmpRoot, "panel-languages-compass")
        val lwCentre = Triple(0, lw.width / 2f, lw.height * 0.56f)
        touch(lw, MotionEvent.ACTION_MOVE, listOf(lwCentre))
        touch(lw, MotionEvent.ACTION_UP, listOf(lwCentre))
        // The double-press typed one space; reset the field so the banner check below is exact.
        field.text.clear()
        field.text.append(kept)
        android.text.Selection.setSelection(field.text, kept.length)
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
        for (page in listOf("ai", "triggers", "memory", "usage", "textshortcuts", "look", "typing", "languages", "create", "wheel", "feedback", "battery", "templates", "prompts", "automate", "shortcuts", "privacy", "dashboard", "welcome", "keys", "category:edit", "shelves", "skill-new", "skill-edit:0", chainPageName, "trigger-new", "trigger-edit:0", "chains", "copysettings", "api-new", "key-new", "memory-new")) {
            val a = Robolectric.buildActivity(PageActivity::class.java, android.content.Intent().putExtra("page", page)).setup().get()
            val s = content(a)
            val h = if (page.startsWith("skill-") || page.startsWith("trigger-") || page.startsWith("api-") || page.startsWith("key-") || page.startsWith("memory-")) 2000 else if (page.startsWith("chain-")) 2600 else maxOf((s as ViewGroup).getChildAt(0).measuredHeight, 900)
            s.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            s.layout(0, 0, 1080, h)
            snap(s, "page-" + page.replace(":", "-"))
        }
        // The search page mid-query: one field that reaches apps, tools and settings alike.
        run {
            val a = Robolectric.buildActivity(PageActivity::class.java, android.content.Intent().putExtra("page", "search")).setup().get()
            val s = content(a) as ViewGroup
            sheetFields(s).first().setText("trans")
            s.getChildAt(0).measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val h = maxOf(s.getChildAt(0).measuredHeight, 900)
            s.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            s.layout(0, 0, 1080, h)
            snap(s, "page-search")
        }

        // The Add to Wheel sheet, and a hold-drag mid-flight with the pink remove bar.
        store.wheel = listOf("ai")
        val wa = Robolectric.buildActivity(PageActivity::class.java, android.content.Intent().putExtra("page", "wheel")).setup().get()
        byDesc(content(wa), "Add to the wheel")!!.performClick()
        val sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        layout(sheet.window!!.decorView)
        snap(sheet.window!!.decorView, "page-wheel-sheet")
        sheet.dismiss()
        store.wheel = Store.DEFAULT_WHEEL
        val wb = Robolectric.buildActivity(PageActivity::class.java, android.content.Intent().putExtra("page", "wheel")).setup().get()
        val slot = byDesc(content(wb), "Wheel slot 2: Rewrite")!!
        slot.performLongClick()
        layout(content(wb))
        val target = byDesc(content(wb), "Wheel slot 4: Meeting link")!!
        val at = IntArray(2)
        slot.getLocationOnScreen(at)
        val tt = IntArray(2)
        target.getLocationOnScreen(tt)
        val dx = tt[0] + target.width / 2f - at[0]
        val dy = tt[1] + target.height / 2f - at[1]
        touch(slot, MotionEvent.ACTION_MOVE, listOf(Triple(0, dx, dy)))
        layout(wb.window.decorView)
        snap(wb.window.decorView, "page-wheel-drag")
        touch(slot, MotionEvent.ACTION_UP, listOf(Triple(0, dx, dy)))
        store.wheel = Store.DEFAULT_WHEEL
    }

    @Test
    fun softLookHasRoundBotAndMicAnEmojiKeyAndSmartTouch() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.keyShape = "soft"
        store.theme = "light"
        ime.onStartInputView(field.info, false)
        val kb = ime.keys!!
        val softRoot = kb.parent as View
        layout(softRoot)
        snap(softRoot, "keyboard-soft-light")
        assertTrue("an emoji key beside ?123", kb.keyNames().containsAll(listOf("?123", "Emoji", ",", "Space", ".", "Enter")))
        assertTrue("the mic is a round button with no text", findText(softRoot, "Voice") == null)
        // Emoji: the key opens the panel, a tap types one and remembers it, ABC goes back.
        val face = kb.touchTargets().first { it.name == "Emoji" }.face
        touch(kb, MotionEvent.ACTION_DOWN, listOf(Triple(0, face.centerX(), face.centerY())))
        touch(kb, MotionEvent.ACTION_UP, listOf(Triple(0, face.centerX(), face.centerY())))
        val panelRoot = kb.parent as View
        layout(panelRoot)
        snap(panelRoot, "keyboard-soft-emoji")
        clickLabel(panelRoot, "😂")
        assertTrue("the emoji is typed: ${field.text}", field.text.toString().endsWith("😂"))
        assertEquals(listOf("😂"), store.recentEmoji)
        clickLabel(panelRoot, "ABC")
        assertEquals(View.VISIBLE, kb.visibility)

        store.keyShape = "iphone"
        ime.onStartInputView(field.info, false)
        (ime.keys!!.parent as View).let { layout(it); snap(it, "keyboard-iphone-light") }
        assertTrue("iPhone: 123, emoji, space, return", ime.keys!!.keyNames().takeLast(4) == listOf("123", "Emoji", "Space", "Enter"))

        // Smart touch with the real word list: after "th", a tap on R's edge next to E types E.
        store.keyShape = "raised"
        ime.onStartInputView(field.info, false)
        val keys = ime.keys!!
        layout(keys.parent as View)
        type(" th")
        val r = keys.touchTargets().first { it.name == "r" }
        val e = keys.touchTargets().first { it.name == "e" }
        val x = e.touch.right + r.touch.width() * 0.08f
        touch(keys, MotionEvent.ACTION_DOWN, listOf(Triple(0, x, r.face.centerY())))
        touch(keys, MotionEvent.ACTION_UP, listOf(Triple(0, x, r.face.centerY())))
        assertTrue("th + border tap = the: ${field.text}", field.text.toString().endsWith("the"))
        // Switched off, the same tap is R.
        store.smartTouch = false
        ime.onStartInputView(field.info, false)
        val plain = ime.keys!!
        layout(plain.parent as View)
        type(" th")
        touch(plain, MotionEvent.ACTION_DOWN, listOf(Triple(0, x, r.face.centerY())))
        touch(plain, MotionEvent.ACTION_UP, listOf(Triple(0, x, r.face.centerY())))
        assertTrue("off: ${field.text}", field.text.toString().endsWith("thr"))
        store.smartTouch = true
    }
}

private const val PERSON = "Ada Lovelace\nMathematician and writer\nLocation: London, England\nCurrent: Countess of Lovelace\nPrevious: Analytical Engine notes with Charles Babbage\nEducation: Private tutoring in mathematics and science\nhttps://www.linkedin.com/search/results/people/?keywords=Ada%20Lovelace"
