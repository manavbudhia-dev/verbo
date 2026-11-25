package app.verbo.keyboard

import android.graphics.RectF

/**
 * Touch Target Debug Mode: a temporary tool for tuning the keyboard's touch targets. Debug builds only
 * ([available] is false in release builds, so nothing is drawn, logged or counted there).
 *
 * Each key has two areas: its **face** (the key you see) and its **touch area** (everything that sends a
 * touch to it: the whole row height, out to halfway across the gap to each neighbour). Every finished
 * press is sorted into one of:
 * - **correct**: it went down on the key's face and the same key received it; also an **edge hit**, a touch
 *   just outside the face where only that key can be meant (left of A, right of L, above or below a face);
 * - **incorrect**: it went down on one key's face but another key received it (a slide to a neighbour);
 * - **missed**: it went down in the gap *between* two key faces, where either could be meant;
 * - **lost**: the system cancelled the touch before the finger lifted, so no key received it;
 * - **repeated**: the same key went down again within [REPEAT_MS] (a double registration or a bounce);
 * plus **gestures** (swipe typing, holds, the wheel), which don't count towards accuracy.
 * Toolbar taps above the keys are counted too; one that comes within [TOOLBAR_AFTER_TYPING_MS] of a key
 * press, or lands within [TOOLBAR_EDGE_DP] of the keys, is flagged as a likely accidental activation.
 *
 * It only watches: nothing here changes which key a touch goes to.
 */
object TouchDebug {
    /** The tool exists only in debug builds. */
    val available: Boolean get() = BuildConfig.DEBUG

    /** On when the user switched it on (Settings › Typing) in a debug build. */
    @Volatile var enabled = false
        set(v) { field = v && available }

    /** The letter layout's keys: what the acceptance test should cover (QWERTY, modifiers, space). */
    val ALL_KEYS: List<String> = "qwertyuiopasdfghjklzxcvbnm".map { it.toString() } + listOf("Shift", "Backspace", "?123", ",", "Space", ".", "Enter")

    const val REPEAT_MS = 90L
    const val TOOLBAR_AFTER_TYPING_MS = 600L
    const val TOOLBAR_EDGE_DP = 8f
    private const val TAG = "VerboTouch"

    enum class Outcome { CORRECT, INCORRECT, MISSED, GESTURE }

    /** One finished press, kept for the overlay and the summary. */
    class Press(
        val received: String, val aimed: String?, val outcome: Outcome, val repeated: Boolean,
        val downX: Float, val downY: Float, val upX: Float, val upY: Float, val at: Long,
    )

    /** A key's two areas, in view pixels, for drawing and tests. */
    class Target(val name: String, val face: RectF, val touch: RectF)

    private val presses = ArrayList<Press>()
    private val lastDown = HashMap<String, Long>()
    var correct = 0; private set
    var incorrect = 0; private set
    var missed = 0; private set
    var repeated = 0; private set
    var gestures = 0; private set
    var edgeHits = 0; private set
    var lost = 0; private set
    var toolbarTaps = 0; private set
    var toolbarAccidental = 0; private set
    private var lastKeyAt = 0L
    /** Keys pressed correctly at least once this session: the acceptance-test checklist. */
    val tested = LinkedHashSet<String>()
    private val perKey = LinkedHashMap<String, IntArray>() // name -> [presses, errors]
    /** Where the finger lands against each key face's centre, summed in dp: the thumb's bias. */
    private val offsets = LinkedHashMap<String, FloatArray>() // name -> [sum dx, sum dy, count]
    private val toolbarHits = LinkedHashMap<String, Int>()

    /** The newest press, for the overlay's highlight. */
    val last: Press? get() = synchronized(this) { presses.lastOrNull() }
    /** The most recent presses, newest last, for the overlay's touch dots. */
    fun recent(n: Int): List<Press> = synchronized(this) { presses.takeLast(n) }

    @Synchronized
    fun reset() {
        presses.clear(); lastDown.clear(); tested.clear(); perKey.clear(); toolbarHits.clear()
        correct = 0; incorrect = 0; missed = 0; repeated = 0; gestures = 0; toolbarTaps = 0; toolbarAccidental = 0; lastKeyAt = 0L
        edgeHits = 0; lost = 0; offsets.clear()
    }

    /** Logs a raw MotionEvent point: ACTION_DOWN, ACTION_MOVE or ACTION_UP, in px and dp, with the key under it. */
    fun log(action: String, pointer: Int, x: Float, y: Float, density: Float, key: String?) {
        if (!enabled) return
        log("$action p$pointer x=${"%.1f".format(x)} y=${"%.1f".format(y)} (${"%.1f".format(x / density)}dp, ${"%.1f".format(y / density)}dp) key=${key ?: "-"}")
    }

    /** A key went down: checks for a repeat of the same key a moment ago. Returns true when it is one. */
    @Synchronized
    fun keyDown(name: String, at: Long): Boolean {
        val before = lastDown[name]
        lastDown[name] = at
        return before != null && at - before < REPEAT_MS
    }

    /**
     * A press ended. [aimed] is the key whose face the finger went down on (null in a gap), [received] the key
     * the keyboard gave it to; [gesture] for swipes, holds and the wheel.
     */
    @Synchronized
    fun pressEnded(received: String, aimed: String?, gesture: Boolean, repeat: Boolean,
                   downX: Float, downY: Float, upX: Float, upY: Float, at: Long,
                   edge: Boolean = false, dxDp: Float = 0f, dyDp: Float = 0f): Press? {
        if (!enabled) return null
        val outcome = when {
            gesture -> Outcome.GESTURE
            aimed == null -> Outcome.MISSED
            aimed != received -> Outcome.INCORRECT
            else -> Outcome.CORRECT
        }
        when (outcome) {
            Outcome.CORRECT -> { correct++; tested += received; if (edge) edgeHits++ }
            Outcome.INCORRECT -> incorrect++
            Outcome.MISSED -> missed++
            Outcome.GESTURE -> gestures++
        }
        if (repeat) repeated++
        if (outcome != Outcome.GESTURE && aimed != null) {
            val o = offsets.getOrPut(aimed) { FloatArray(3) }
            o[0] += dxDp; o[1] += dyDp; o[2] += 1f
        }
        if (outcome != Outcome.GESTURE) {
            val k = perKey.getOrPut(received) { IntArray(2) }
            k[0]++
            if (outcome != Outcome.CORRECT || repeat) k[1]++
            lastKeyAt = at
        }
        val p = Press(received, aimed, outcome, repeat, downX, downY, upX, upY, at)
        presses += p
        if (presses.size > 400) presses.removeAt(0)
        log("PRESS $outcome${if (edge) " (edge)" else ""}${if (repeat) " +REPEATED" else ""} received=$received aimed=${aimed ?: "gap"} offset=${"%.1f".format(dxDp)}dp,${"%.1f".format(dyDp)}dp")
        return p
    }

    /** The system cancelled [count] presses before the finger lifted (ACTION_CANCEL): no key received them. */
    @Synchronized
    fun cancelled(count: Int, keys: List<String>) {
        if (!enabled || count == 0) return
        lost += count
        log("ACTION_CANCEL lost $count press(es): ${keys.joinToString(" ")}")
    }

    /**
     * A tap on the toolbar above the keys (Type/Act pills, the bot, Voice). [gapToKeysDp] is how far above the
     * first key row it landed. Returns true when it looks accidental.
     */
    @Synchronized
    fun toolbarTap(name: String, gapToKeysDp: Float, at: Long): Boolean {
        if (!enabled) return false
        toolbarTaps++
        toolbarHits[name] = (toolbarHits[name] ?: 0) + 1
        val accidental = (lastKeyAt != 0L && at - lastKeyAt < TOOLBAR_AFTER_TYPING_MS) || gapToKeysDp < TOOLBAR_EDGE_DP
        if (accidental) toolbarAccidental++
        log("TOOLBAR $name ${if (accidental) "ACCIDENTAL?" else "ok"} ${"%.1f".format(gapToKeysDp)}dp above the keys, ${if (lastKeyAt == 0L) "-" else (at - lastKeyAt).toString() + "ms"} after the last key")
        return accidental
    }

    val keyPresses: Int get() = correct + incorrect + missed + lost
    /** Share of key presses that landed on the key that received them. */
    val accuracy: Double get() = if (keyPresses == 0) 1.0 else correct.toDouble() / keyPresses
    /** Every kind of error over every tap (keys and toolbar). */
    val errorRate: Double get() {
        val all = keyPresses + toolbarTaps
        return if (all == 0) 0.0 else (incorrect + missed + lost + repeated + toolbarAccidental).toDouble() / all
    }

    /** The session summary: accuracy, error rate, each count, keys still untested, and the worst keys. */
    @Synchronized
    fun summary(allKeys: List<String>): String {
        val pct = { v: Double -> "%.1f%%".format(v * 100) }
        val untested = allKeys.filter { it !in tested }
        val worst = perKey.filter { it.value[1] > 0 }.entries.sortedByDescending { it.value[1].toDouble() / it.value[0] }.take(5)
        return buildString {
            appendLine("Touch accuracy: ${pct(accuracy)}  ·  Error rate: ${pct(errorRate)}")
            appendLine("Key presses: $keyPresses  (correct $correct of which edge hits $edgeHits, incorrect $incorrect, missed $missed, lost $lost)")
            appendLine("Repeated: $repeated  ·  Gestures (not counted): $gestures")
            appendLine("Toolbar taps: $toolbarTaps  (likely accidental: $toolbarAccidental)" +
                if (toolbarHits.isNotEmpty()) "  " + toolbarHits.entries.joinToString(", ") { "${it.key} ${it.value}" } else "")
            appendLine("Coverage: ${allKeys.size - untested.size} of ${allKeys.size} keys pressed correctly" +
                if (untested.isEmpty()) "" else "; untested: " + untested.joinToString(" "))
            if (worst.isNotEmpty()) appendLine("Most errors: " + worst.joinToString(", ") { "${it.key} ${it.value[1]}/${it.value[0]}" })
            val bias = offsets.filter { it.value[2] >= 5f }.mapValues { floatArrayOf(it.value[0] / it.value[2], it.value[1] / it.value[2]) }
                .filter { kotlin.math.hypot(it.value[0], it.value[1]) >= 4f }.entries.sortedByDescending { kotlin.math.hypot(it.value[0], it.value[1]) }.take(5)
            if (bias.isNotEmpty()) appendLine("Thumb lands off centre: " + bias.joinToString(", ") { (k, v) ->
                listOfNotNull(
                    if (kotlin.math.abs(v[0]) >= 2f) "%.0fdp %s".format(kotlin.math.abs(v[0]), if (v[0] < 0) "left" else "right") else null,
                    if (kotlin.math.abs(v[1]) >= 2f) "%.0fdp %s".format(kotlin.math.abs(v[1]), if (v[1] < 0) "up" else "down") else null,
                ).joinToString(" ").let { "$k $it" }
            })
            appendLine("Diagnosis: " + diagnosis())
            appendLine("Toolbar tested: ${if (toolbarTaps - toolbarAccidental > 0) "yes" else "no (tap Type, Act, the bot or Voice)"}")
        }.trimEnd()
    }

    /** One line on what the numbers point to: lost events, gaps that are too wide, or thumbs reaching the edge. */
    private fun diagnosis(): String {
        if (keyPresses < 20) return "type a bit more for a reliable reading"
        val notes = ArrayList<String>()
        if (lost > 0) notes += "$lost touch(es) were cancelled by the system before lifting (lost events)"
        if (missed.toDouble() / keyPresses > 0.04) notes += "many touches fall between keys: the gaps or the keys are too small"
        if (incorrect.toDouble() / keyPresses > 0.02) notes += "fingers slide onto the next key before lifting"
        if (edgeHits.toDouble() / keyPresses > 0.03) notes += "thumbs often land just outside a key face (usually at the screen edge); the keys still receive them"
        if (repeated > 0) notes += "$repeated double registration(s)"
        return if (notes.isEmpty()) "no layout problem found; touch targets and coordinates are working" else notes.joinToString("; ")
    }

    /** Writes the session summary to logcat, one line per item. */
    fun logSummary() { if (enabled) summary(ALL_KEYS).lines().forEach { log("SUMMARY $it") } }

    private fun log(message: String) {
        try { android.util.Log.d(TAG, message) } catch (e: Throwable) { }
    }
}
