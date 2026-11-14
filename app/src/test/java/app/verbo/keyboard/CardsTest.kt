package app.verbo.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading answers as cards, and spotting what a copied message is about. */
class CardsTest {
    @Test
    fun rankedListInTheAskedForForm() {
        val card = Cards.list("Best Italian in SF\n1. Flour + Water | 4.8 | Sardinian · \$\$\$ | Mission District, SF | Authentic vibe; Spicy octopus pasta\n2. Acquerello | - | Fine dining | Nob Hill | Michelin-starred")!!
        assertEquals("Best Italian in SF", card.title)
        assertEquals(2, card.items.size)
        val first = card.items[0]
        assertEquals("Flour + Water", first.name)
        assertEquals(4.8, first.rating!!, 0.0)
        assertEquals("Sardinian · \$\$\$", first.kind)
        assertEquals("Mission District, SF", first.area)
        assertEquals(listOf("Authentic vibe", "Spicy octopus pasta"), first.tags)
        assertNull(card.items[1].rating)
        assertEquals("1. Flour + Water (★ 4.8, Sardinian · \$\$\$, Mission District, SF)", card.plain().lines()[1])
    }

    @Test
    fun plainListsAlsoWorkButProseDoesNot() {
        val card = Cards.list("Picks\n1. Itria · 4.6 · Mission District\n2. Acquerello · 4.7 · Nob Hill · Michelin-starred")!!
        assertEquals("Nob Hill", card.items[1].area)
        assertEquals(listOf("Michelin-starred"), card.items[1].tags)
        assertNull(Cards.list("Just one line of text"))
        assertNull(Cards.list("Steps\n1. Only one numbered line"))
    }

    @Test
    fun notionPageCard() {
        val n = Cards.notion("V2 design specs\nLast edited: Today at 10:42 AM", "https://www.notion.so/V2-design-specs-1a2b")!!
        assertEquals("V2 design specs", n.title)
        assertEquals("Today at 10:42 AM", n.edited)
        assertEquals("V2 design specs: https://www.notion.so/V2-design-specs-1a2b", n.plain())
        assertNull(Cards.notion("V2 design specs", "https://evil.example/notion.so/x"))
        assertNull(Cards.notion("V2 design specs", null))
    }

    @Test
    fun copiedMessageKinds() {
        val msg = "We're meeting with David Miller from Google Cloud tomorrow"
        assertEquals("David Miller", Cards.personName(msg))
        assertTrue(Cards.matches("time", "", msg))
        assertTrue(Cards.matches("time", "", "kal 5 baje milte hain"))
        assertFalse(Cards.matches("time", "", "sounds good"))
        assertNull(Cards.personName("See you in New York next week"))
        assertNull(Cards.personName("ok sounds good"))
        assertTrue(Cards.matches("email", "", "mail me at asha@x.co"))
        assertTrue(Cards.matches("phone", "", "call +91 98765 43210"))
        assertTrue(Cards.matches("link", "", "look https://example.com/a"))
        assertTrue(Cards.matches("place", "", "let's meet at Blue Tokai cafe"))
        assertTrue(Cards.matches("keyword", "invoice, bill", "Your Bill is ready"))
        assertFalse(Cards.matches("keyword", "", "anything"))
        assertEquals("Who is · David Miller", Cards.chipLabel(Trigger("person", "", "Who is", false), msg))
    }

    @Test
    fun replyOptionsAndContextActs() {
        assertEquals(listOf("Sure, see you then!", "Works for me."), Cards.options("1) \"Sure, see you then!\"\n2) Works for me."))
        assertNull(Cards.options("Picks\n1) a\n2) b")) // a title line means it is not a set of options
        assertTrue(Acts.isForeign("旅行用eSIMをお探しの場合は、渡航先の国と旅行日程をお知らせください。"))
        assertFalse(Acts.isForeign("kal milte hain, theek hai?"))
        assertFalse(Acts.isForeign("कल मिलते हैं"))
        assertEquals(listOf("Schedule it", "Who is"), Acts.forContext("We're meeting with David Miller from Google Cloud tomorrow"))
        assertEquals(listOf("Save to Notion"), Acts.forContext("watch this https://youtu.be/z4JRQ36H-3Y"))
        assertTrue(Acts.needsCopied(Acts.BUILTIN.first { it.name == "Save to Notion" }.instruction))
        assertTrue(Cards.matches("foreign", "", "Привет, как дела у тебя сегодня?"))
    }

    @Test
    fun chainsAndFind() {
        val c = Acts.chain(listOf("Schedule it", "Reply options", "Tell them I will bring cake"))
        assertTrue(Acts.isChain(c))
        assertEquals(listOf("Schedule it", "Reply options", "Tell them I will bring cake"), Acts.chainSteps(c))
        val task = Acts.chainTask(Acts.chainSteps(c)) { if (it == "Schedule it") "Make an event." else null }
        assertTrue(task.contains("Step 1: Schedule it. Make an event."))
        assertTrue(task.contains("Step 3: Tell them I will bring cake"))
        assertEquals("Find anything", Acts.forContext("find the invoice Asha sent").first())
        val r = Ai.parse("Done\n[[open:https://calendar.google.com/calendar/render?action=TEMPLATE&text=A&dates=20261120/20261121]]\n[[open:https://wa.me/?text=hi]]")
        assertEquals(2, r.links.size)
        assertEquals("https://wa.me/?text=hi", r.openUri)
    }
}
