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
        ime.showResult(ActionResult("Starbucks\n1585 Broadway, Times Square, New York, NY 10036\nhttps://www.google.com/maps/search/?api=1&query=Starbucks+1585+Broadway", "geo:0,0?q=Starbucks+1585+Broadway", 40.7596521 to -73.9852254), {}, {})
        layout(agentRoot)
        snap(agentRoot, "panel-place")
        ime.showResult(ActionResult("Best Italian in SF\n1. Flour + Water · 4.8 · Mission District\n2. Acquerello · 4.7 · Nob Hill · Michelin-starred\n3. Itria · 4.6 · Mission District", null), {}, {})
        layout(agentRoot)
        snap(agentRoot, "panel-list")
        store.theme = "system"
        store.theme = "system"

        for (tab in listOf("keys", "library", "settings")) {
            MainActivity.tab = tab
            val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            val frame = content(main)
            val scroll = (frame as ViewGroup).getChildAt(0) as ViewGroup
            val h = maxOf(scroll.getChildAt(0).measuredHeight, 1400)
            frame.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, 1080, h)
            snap(frame, if (tab == "settings") "settings" else "home-$tab")
        }
        MainActivity.tab = "keys"
        for (page in listOf("wheel", "translate", "feedback", "battery", "skins", "templates", "prompts", "automate", "shortcuts", "privacy", "dashboard", "category:edit")) {
            val a = Robolectric.buildActivity(PageActivity::class.java, android.content.Intent().putExtra("page", page)).setup().get()
            val s = content(a)
            s.layout(0, 0, 1080, maxOf((s as ViewGroup).getChildAt(0).measuredHeight, 900))
            snap(s, "page-" + page.replace(":", "-"))
        }
    }
}
