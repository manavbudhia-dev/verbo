package app.verbo.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Facts from "remember ..." and the writing style learned from sent messages. */
class MemoryTest {
    @Test
    fun rememberAndForget() {
        assertEquals(Memory.Fact("UPI", "me@okicici"), Memory.parseRemember("remember my UPI is me@okicici"))
        assertEquals(Memory.Fact("Mummy", "+91 98765 43210"), Memory.parseRemember("yaad rakho mummy = +91 98765 43210"))
        assertEquals(Memory.Fact("Note", "I am vegetarian"), Memory.parseRemember("remember that I am vegetarian"))
        assertNull(Memory.parseRemember("I remember you"))
        assertEquals("UPI", Memory.parseForget("forget my UPI"))
        assertNull(Memory.parseForget("don't forget the milk"))
    }

    @Test
    fun styleNeedsFiveMessagesThenDescribesThem() {
        assertNull(Memory.describe(listOf("hi", "ok")))
        val style = Memory.describe(listOf("haan bhai kal milte hai 😂", "theek hai yaar", "kya kar raha hai", "ok done 😂", "abhi aata hu", "acha thik hai"))!!
        assertTrue(style, style.contains("Hinglish"))
        assertTrue(style, style.contains("very short"))
        assertTrue(style, style.contains("lowercase"))
        assertTrue(style, style.contains("😂"))
    }

    @Test
    fun profileCarriesFactsAndStyle() {
        assertEquals("", Memory.profile(emptyList(), null))
        val p = Memory.profile(listOf(Memory.Fact("Mummy", "+91 98765 43210")), "Writes short messages")
        assertTrue(p.contains("- Mummy: +91 98765 43210"))
        assertTrue(p.contains("wa.me"))
        assertTrue(p.contains("match this style"))
    }

    @Test
    fun hinglishToDevanagari() {
        assertEquals("कैसे", Translit.toDevanagari("kaise"))
        assertEquals("नहीं", Translit.toDevanagari("nahi"))
        assertEquals("क्या", Translit.toDevanagari("kya"))
        assertEquals("घर", Translit.toDevanagari("ghar"))
        assertEquals("खाना", Translit.toDevanagari("khana"))
        assertEquals("दिल्ली", Translit.toDevanagari("dilli"))
        assertEquals("प्रेम", Translit.toDevanagari("prem"))
        assertTrue(Translit.applies("kal"))
        assertTrue(!Translit.applies("k") && !Translit.applies("ok!"))
    }

    @Test
    fun gestureDecodesByShape() {
        // A toy QWERTY row layout, 10 units per key.
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val centers = HashMap<Char, Gesture.P>()
        rows.forEachIndexed { r, row -> row.forEachIndexed { i, c -> centers[c] = Gesture.P(i * 10f + 5 + r * 5, r * 10f + 5) } }
        fun path(w: String) = w.map { centers[it]!! }.zipWithNext { a, b -> (0 until 6).map { k -> Gesture.P(a.x + (b.x - a.x) * k / 6, a.y + (b.y - a.y) * k / 6) } }.flatten() + centers[w.last()]!!
        val keyAt = { p: Gesture.P -> centers.minByOrNull { (_, c) -> kotlin.math.hypot((c.x - p.x).toDouble(), (c.y - p.y).toDouble()) }!!.key }
        val words = listOf("hello" to 0, "hero" to 1, "ho" to 0, "help" to 0)
        val p = path("helo")
        val got = Gesture.decode(p, Gesture.visited(p, keyAt), centers, 10f, { c -> words.filter { it.first[0] == c } })
        assertEquals("hello", got.first())
    }
}
