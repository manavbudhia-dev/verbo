package app.verbo.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.TimeZone

/** One-tap results: calendar links become events, [[go:]] links open at once, person lookups become cards. */
@RunWith(RobolectricTestRunner::class)
class OneTapTest {
    private val india = TimeZone.getTimeZone("Asia/Kolkata")

    @Test
    fun calendarLinkBecomesAnEventInLocalTime() {
        val e = PhoneCalendar.parseTemplate("https://calendar.google.com/calendar/render?action=TEMPLATE&text=Movie&dates=20261114T235400/20261115T015400&location=PVR%20Jalandhar", india)!!
        assertEquals("Movie", e.title)
        assertEquals("PVR Jalandhar", e.location)
        assertEquals(1_794_680_640_000L, e.start) // 23:54 IST = 18:24 UTC
        assertEquals(2 * 3_600_000L, e.end - e.start)
        assertFalse(e.allDay)
    }

    @Test
    fun utcAllDayAndBrokenLinks() {
        val utc = PhoneCalendar.parseTemplate("https://calendar.google.com/calendar/render?action=TEMPLATE&text=Call&dates=20261120T083000Z/20261120T090000Z", india)!!
        assertEquals(1_795_163_400_000L, utc.start)
        val day = PhoneCalendar.parseTemplate("https://calendar.google.com/calendar/render?action=TEMPLATE&text=Trip&dates=20261201/20261203", india)!!
        assertTrue(day.allDay)
        // An end before the start becomes one hour.
        val swapped = PhoneCalendar.parseTemplate("https://calendar.google.com/calendar/render?action=TEMPLATE&dates=20261120T100000/20261120T090000", india)!!
        assertEquals("Event", swapped.title)
        assertEquals(3_600_000L, swapped.end - swapped.start)
        assertNull(PhoneCalendar.parseTemplate("https://calendar.google.com/calendar/render?action=TEMPLATE&text=x&dates=tonight"))
        assertNull(PhoneCalendar.parseTemplate("https://evil.example/render?action=TEMPLATE&dates=20261120T100000/20261120T110000"))
    }

    @Test
    fun goLinksOpenAtOnceOpenLinksWait() {
        val go = Ai.parse("Q4 plan\n[[go:https://www.notion.so/Q4-plan-abc123]]")
        assertTrue(go.autoOpen)
        assertEquals("https://www.notion.so/Q4-plan-abc123", go.openUri)
        assertEquals("Q4 plan", go.text)
        assertFalse(Ai.parse("Hi\n[[open:https://wa.me/?text=hi]]").autoOpen)
        assertFalse(Ai.parse("x\n[[go:javascript:alert(1)]]").autoOpen) // only the allowed link kinds
    }

    @Test
    fun oldNotionSkillIsUpgraded() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.skills = listOf(Skill("🗒️", "Notion", "Search the user's Notion for the page the text describes and return its title and link. If Notion is not connected, say so in one line.", "write", "n"))
        assertEquals(Store.NOTION_INSTRUCTION, store.skills.single().instruction)
        assertTrue(Store.NOTION_INSTRUCTION.contains("[[go:"))
    }

    @Test
    fun personFormatGivesACard() {
        val card = Ai.personCard("Ada Lovelace\nMathematician and writer\nCurrent: Analyst at Babbage & Co\nEducation: Home-schooled\nhttps://www.linkedin.com/search/results/people/?keywords=Ada%20Lovelace")
        assertNotNull(card)
        assertEquals(2, card!!.facts.size)
        assertTrue(card.link!!.contains("keywords=Ada%20Lovelace"))
    }
}
