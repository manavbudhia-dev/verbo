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
}
