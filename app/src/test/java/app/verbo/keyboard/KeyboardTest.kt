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
        store.theme = "system"

        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val scroll = content(main)
        val column = (scroll as ViewGroup).getChildAt(0)
        scroll.layout(0, 0, 1080, column.measuredHeight)
        snap(scroll, "settings")
        for (page in listOf("skins", "templates", "prompts", "automate", "shortcuts", "privacy", "dashboard", "category:edit")) {
            val a = Robolectric.buildActivity(PageActivity::class.java, android.content.Intent().putExtra("page", page)).setup().get()
            val s = content(a)
            s.layout(0, 0, 1080, maxOf((s as ViewGroup).getChildAt(0).measuredHeight, 900))
            snap(s, "page-" + page.replace(":", "-"))
        }
    }
}
