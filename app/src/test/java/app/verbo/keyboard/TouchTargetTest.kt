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
    private var odds: FloatArray? = null
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
            override fun letterOdds(): FloatArray? = odds
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

    @Test @Config(qualifiers = "w411dp-h891dp-420dpi")
    fun edgeHitsCountAsCorrectAndCancelledTouchesAsLost() {
        val d = keys.resources.displayMetrics.density
        // Left of A's face, in the margin of the inset row: only A can be meant, and A receives it.
        val a = target("a")
        tap(a.touch.left + 4 * d, a.face.centerY())
        assertEquals("a", typed.toString())
        assertEquals("an edge hit is correct", 1, TouchDebug.correct)
        assertEquals(1, TouchDebug.edgeHits)
        assertEquals(0, TouchDebug.missed)
        // Right of L's face, and just above Q's face: the same.
        val l = target("l")
        tap(l.touch.right - 4 * d, l.face.centerY())
        val q = target("q")
        tap(q.face.centerX(), q.touch.top + 1 * d)
        assertEquals("al" + "q", typed.toString())
        assertEquals(3, TouchDebug.edgeHits)
        // Between two faces it is still a miss.
        val s2 = target("s").face
        val d2 = target("d").face
        tap((s2.right + d2.left) / 2, s2.centerY())
        assertEquals(1, TouchDebug.missed)
        // The system takes a touch back before the finger lifts: nothing is typed, and it is counted as lost.
        val before = typed.length
        keys.onTouchEvent(event(MotionEvent.ACTION_DOWN, q.face.centerX(), q.face.centerY()))
        keys.onTouchEvent(event(MotionEvent.ACTION_CANCEL, q.face.centerX(), q.face.centerY()))
        assertEquals("a cancelled touch types nothing", before, typed.length)
        assertEquals(1, TouchDebug.lost)
        // Taps landing to the left of A's centre show up as the thumb's bias.
        repeat(6) { tap(a.face.left + 3 * d, a.face.centerY()) }
        val summary = TouchDebug.summary(TouchDebug.ALL_KEYS)
        assertTrue("the summary names the bias: $summary", summary.contains("Thumb lands off centre: a ") && summary.contains("left"))
        assertTrue("and the lost touch: $summary", summary.contains("lost 1"))
    }

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

    // ---------- smart touch and the A/L inset ----------

    private fun likely(c: Char, vararg others: Pair<Char, Float>) = FloatArray(26).also { o ->
        o[c - 'a'] = 0.9f
        for ((k, v) in others) o[k - 'a'] = v
    }

    @Test
    fun smartTouchGivesABorderTapToTheLikelierLetter() {
        val e = target("e")
        val r = target("r")
        val border = e.touch.right
        val pitch = r.touch.width()
        // "th|": E is far likelier than R. A tap just inside R, near E, goes to E.
        odds = likely('e', 'r' to 0.02f)
        tap(border + pitch * 0.1f, r.face.centerY())
        assertEquals("near the border the likelier letter wins", "e", typed.toString())
        // The middle of R stays R, however unlikely.
        tap(r.face.centerX(), r.face.centerY())
        tap(r.face.centerX() + pitch * 0.15f, r.face.centerY())
        assertEquals("taps on the middle of a key are never moved", "err", typed.toString())
        // Without odds (symbols, another language, smart touch off) nothing moves.
        odds = null
        tap(border + pitch * 0.1f, r.face.centerY())
        assertEquals("errr", typed.toString())
        // A slight preference is not enough deep into the band.
        odds = likely('r', 'e' to 0.5f)
        typed.setLength(0)
        tap(border + pitch * 0.25f, r.face.centerY())
        assertEquals("a near tie keeps the key under the finger", "r", typed.toString())
        // Up and down too: between rows, near the border, the likelier letter in the other row.
        typed.setLength(0)
        val s = target("s")
        val w = target("w")
        odds = likely('w', 's' to 0.01f)
        tap(s.face.centerX(), s.touch.top + 2f)
        assertEquals("w", typed.toString())
        // Debug mode counts these as smart picks, not as errors.
        assertEquals(0, TouchDebug.incorrect)
        assertTrue("smart picks are counted: ${TouchDebug.summary(TouchDebug.layoutKeys)}", TouchDebug.smartPicks >= 2)
        assertTrue(TouchDebug.summary(TouchDebug.layoutKeys).contains("smart touch"))
    }

    @Test
    fun smartTouchNeverTakesATapFromShiftSpaceOrBackspace() {
        odds = likely('z', 'm' to 0.9f)
        val shift = target("Shift")
        tap(shift.touch.right - 2f, shift.face.centerY())
        assertEquals("" , typed.toString())
        assertEquals(0, backspaces)
        val bs = target("Backspace")
        tap(bs.touch.left + 2f, bs.face.centerY())
        assertEquals(1, backspaces)
    }

    @Test
    fun aAndLSitALittleNearerTheEdgesThanHalfAKey() {
        val q = target("q").face
        val pitch = target("w").touch.width()
        val a = target("a").face
        val l = target("l").face
        val inA = (a.centerX() - q.centerX()) / pitch
        val inL = (target("p").face.centerX() - l.centerX()) / pitch
        assertTrue("A is a little under half a key in: $inA", inA in 0.38f..0.47f)
        assertTrue("L is a little under half a key in: $inL", inL in 0.38f..0.47f)
        assertEquals("and the row still fills the width", 0f, target("a").touch.left, 0.5f)
    }

    @Test
    fun softAndIphoneStylesHaveAnEmojiKey() {
        val context = RuntimeEnvironment.getApplication()
        val width = context.resources.displayMetrics.widthPixels
        for ((shape, names) in listOf("soft" to listOf("?123", "Emoji", ",", "Space", ".", "Enter"), "iphone" to listOf("123", "Emoji", "Space", "Enter"))) {
            val v = KeyboardView(context, KeyTheme.of(context, "light", 0), 1f, shape)
            var emoji = 0
            v.listener = object : KeyboardView.Listener {
                override fun onChar(text: String) { typed.append(text) }
                override fun onBackspace() {}
                override fun onEnter() {}
                override fun onShift() {}
                override fun onBarHeld() {}
                override fun onKeyHeld(letter: Char) {}
                override fun onWheel(index: Int) {}
                override fun onPress(type: KeyboardView.Type) {}
                override fun onEmoji() { emoji++ }
            }
            v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            assertEquals("$shape bottom row", names, v.keyNames().takeLast(names.size))
            assertEquals("the layout's keys are what debug coverage checks", v.keyNames(), TouchDebug.layoutKeys)
            val face = v.touchTargets().first { it.name == "Emoji" }.face
            v.onTouchEvent(event(MotionEvent.ACTION_DOWN, face.centerX(), face.centerY()))
            v.onTouchEvent(event(MotionEvent.ACTION_UP, face.centerX(), face.centerY()))
            assertEquals("$shape emoji key opens emoji", 1, emoji)
            val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bitmap))
            val dir = java.io.File("build/shots").apply { mkdirs() }
            java.io.File(dir, "keys-$shape.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
