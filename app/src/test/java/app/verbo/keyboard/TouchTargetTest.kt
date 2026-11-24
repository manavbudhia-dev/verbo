package app.verbo.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Touch Target Debug Mode, and the acceptance run it is for: every QWERTY key, the modifiers and the space bar
 * tapped on a small phone, a common phone and a tablet, at different densities.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TouchTargetTest {
    private val typed = StringBuilder()
    private var backspaces = 0
    private var enters = 0
    private lateinit var keys: KeyboardView

    @Before
    fun setUp() {
        TouchDebug.reset()
        TouchDebug.enabled = true
        val context = RuntimeEnvironment.getApplication()
        keys = KeyboardView(context, KeyTheme.of(context, "light", 0), 1f)
        keys.listener = object : KeyboardView.Listener {
            override fun onChar(text: String) { typed.append(text) }
            override fun onBackspace() { backspaces++ }
            override fun onEnter() { enters++ }
            override fun onShift() { keys.shift = if (keys.shift == 0) 1 else 0 }
            override fun onBarHeld() {}
            override fun onKeyHeld(letter: Char) {}
            override fun onWheel(index: Int) {}
            override fun onPress(type: KeyboardView.Type) {}
        }
        keys.touchDebug = true
        val width = context.resources.displayMetrics.widthPixels
        keys.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        keys.layout(0, 0, keys.measuredWidth, keys.measuredHeight)
    }

    @After
    fun tearDown() {
        TouchDebug.enabled = false
        TouchDebug.reset()
    }

    private fun event(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x, y, 0)

    private fun tap(x: Float, y: Float) {
        keys.onTouchEvent(event(MotionEvent.ACTION_DOWN, x, y))
        keys.onTouchEvent(event(MotionEvent.ACTION_UP, x, y))
        // Past the repeat window, so ordinary taps of the same key don't count as repeats.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(200))
    }

    private fun target(name: String) = keys.touchTargets().first { it.name == name }

    /** Taps every key of the letter layout at its face's centre; each must be correct and do its job. */
    private fun everyKeyIsCorrect() {
        val letters = "qwertyuiopasdfghjklzxcvbnm"
        for (c in letters) target(c.toString()).face.let { tap(it.centerX(), it.centerY()) }
        assertEquals("every letter types itself", letters, typed.toString())
        for (name in listOf(",", "Space", ".")) target(name).face.let { tap(it.centerX(), it.centerY()) }
        assertEquals("$letters, .", typed.toString())
        target("Backspace").face.let { tap(it.centerX(), it.centerY()) }
        assertEquals(1, backspaces)
        target("Enter").face.let { tap(it.centerX(), it.centerY()) }
        assertEquals(1, enters)
        target("Shift").face.let { tap(it.centerX(), it.centerY()) }
        assertEquals("shift turned on", 1, keys.shift)
        target("?123").face.let { tap(it.centerX(), it.centerY()) }
        assertEquals("?123 switched to symbols", KeyboardView.Mode.SYMBOLS, keys.mode)
        assertEquals("all of them were correct", TouchDebug.ALL_KEYS.size, TouchDebug.correct)
        assertEquals(0, TouchDebug.incorrect + TouchDebug.missed + TouchDebug.repeated)
        assertEquals("accuracy is 100%", 1.0, TouchDebug.accuracy, 1e-9)
        assertTrue("the summary covers every key", TouchDebug.summary(TouchDebug.ALL_KEYS).contains("Coverage: ${TouchDebug.ALL_KEYS.size} of ${TouchDebug.ALL_KEYS.size}"))
    }

    /** The touch areas tile each row with no holes or overlaps, each face sits inside its area, and the sizes are sane. */
    private fun targetsTileTheKeyboard(): Float {
        val d = keys.resources.displayMetrics.density
        val all = keys.touchTargets()
        val rows = all.groupBy { it.touch.top }
        for ((_, row) in rows) {
            val sorted = row.sortedBy { it.touch.left }
            assertEquals("a row starts at the left edge", 0f, sorted.first().touch.left, 0.5f)
            assertEquals("a row ends at the right edge", keys.width.toFloat(), sorted.last().touch.right, 0.5f)
            for (i in 1 until sorted.size) assertEquals("no hole or overlap between ${sorted[i - 1].name} and ${sorted[i].name}", sorted[i - 1].touch.right, sorted[i].touch.left, 0.5f)
        }
        for (t in all) assertTrue("${t.name}'s face is inside its touch area", t.touch.contains(t.face))
        val smallest = all.minOf { minOf(it.touch.width(), it.touch.height()) } / d
        assertTrue("every touch target is at least 28dp (smallest $smallest)", smallest >= 28f)
        return smallest
    }

    private fun mistakesAreSorted() {
        TouchDebug.reset()
        // In the gap between q and w: the nearest key takes it, and it counts as missed.
        val q = target("q").face
        val w = target("w").face
        tap((q.right + w.left) / 2, q.centerY())
        assertEquals(1, TouchDebug.missed)
        // Down on q's face, slid onto w before lifting: w receives it, so it counts as incorrect.
        keys.onTouchEvent(event(MotionEvent.ACTION_DOWN, q.centerX(), q.centerY()))
        keys.onTouchEvent(event(MotionEvent.ACTION_MOVE, w.centerX(), w.centerY()))
        keys.onTouchEvent(event(MotionEvent.ACTION_UP, w.centerX(), w.centerY()))
        assertEquals(1, TouchDebug.incorrect)
        assertEquals(TouchDebug.Outcome.INCORRECT, TouchDebug.last!!.outcome)
        assertEquals("w", TouchDebug.last!!.received)
        assertEquals("q", TouchDebug.last!!.aimed)
        // The same key twice within the repeat window: a repeat.
        val e = target("e").face
        keys.onTouchEvent(event(MotionEvent.ACTION_DOWN, e.centerX(), e.centerY()))
        keys.onTouchEvent(event(MotionEvent.ACTION_UP, e.centerX(), e.centerY()))
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(40))
        keys.onTouchEvent(event(MotionEvent.ACTION_DOWN, e.centerX(), e.centerY()))
        keys.onTouchEvent(event(MotionEvent.ACTION_UP, e.centerX(), e.centerY()))
        assertEquals(1, TouchDebug.repeated)
        assertTrue("the error rate counts all of them", TouchDebug.errorRate > 0.5)
    }

    private fun overlayDoesNotChangeTyping() {
        val withDebug = StringBuilder()
        val withoutDebug = StringBuilder()
        for ((on, out) in listOf(true to withDebug, false to withoutDebug)) {
            keys.touchDebug = on
            typed.setLength(0)
            keys.mode = KeyboardView.Mode.LETTERS
            keys.shift = 0
            for (c in "hello") target(c.toString()).face.let { tap(it.centerX(), it.centerY()) }
            // Drawing the overlay must not break anything either.
            val bitmap = Bitmap.createBitmap(keys.width, keys.height, Bitmap.Config.ARGB_8888)
            keys.draw(Canvas(bitmap))
            out.append(typed)
        }
        assertEquals("hello", withoutDebug.toString())
        assertEquals("the overlay leaves typing exactly as it is", withoutDebug.toString(), withDebug.toString())
        keys.touchDebug = true
    }

    private fun acceptance() {
        targetsTileTheKeyboard()
        everyKeyIsCorrect()
        keys.mode = KeyboardView.Mode.LETTERS
        keys.shift = 0
        mistakesAreSorted()
        overlayDoesNotChangeTyping()
    }

    @Test @Config(qualifiers = "w320dp-h568dp-mdpi")
    fun smallPhoneAtMdpi() = acceptance()

    @Test @Config(qualifiers = "w411dp-h891dp-420dpi")
    fun commonPhoneAt420dpi() = acceptance()

    @Test @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun tabletAtXhdpi() = acceptance()

    @Test @Config(qualifiers = "w360dp-h780dp-xxxhdpi")
    fun densePhoneAtXxxhdpi() = acceptance()

    @Test
    fun offMeansNothingIsCountedAndReleaseBuildsHaveNoDebugMode() {
        assertEquals("only debug builds have the tool", BuildConfig.DEBUG, TouchDebug.available)
        TouchDebug.enabled = false
        val q = target("q").face
        tap(q.centerX(), q.centerY())
        assertEquals("switched off, nothing is counted", 0, TouchDebug.keyPresses)
        assertEquals("q", typed.toString())
    }

    @Test @Config(qualifiers = "w411dp-h891dp-420dpi")
    fun picture() {
        for (c in "hello") target(c.toString()).face.let { tap(it.centerX(), it.centerY()) }
        val q = target("q").face
        val w = target("w").face
        tap((q.right + w.left) / 2, q.centerY())
        val bitmap = Bitmap.createBitmap(keys.width, keys.height, Bitmap.Config.ARGB_8888)
        keys.draw(Canvas(bitmap))
        val dir = java.io.File("build/shots").apply { mkdirs() }
        java.io.File(dir, "keyboard-touch-debug.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
