package app.verbo.keyboard

/**
 * Reads assistant answers that have a known shape, so the keyboard can show them as rich cards:
 * ranked lists (restaurants, options), Notion pages, and what a copied message is about (for triggers).
 * No Android dependencies.
 */
object Cards {

    // ---------- ranked lists ----------

    data class Item(val rank: Int, val name: String, val rating: Double?, val kind: String, val area: String, val tags: List<String>)
    data class ListCard(val title: String, val items: List<Item>, val rest: String) {
        /** Plain text for the chat when the user inserts it. */
        fun plain(): String = (listOf(title) + items.map { i ->
            "${i.rank}. ${i.name}" + listOfNotNull(i.rating?.let { "★ ${fmt(it)}" }, i.kind.ifBlank { null }, i.area.ifBlank { null }).joinToString(", ", " (", ")").takeIf { it != " ()" }.orEmpty()
        } + listOfNotNull(rest.ifBlank { null })).joinToString("\n")
    }

    private val NUMBERED = Regex("""^\s*(\d{1,2})[.)]\s+(.+)$""")
    private val RATING = Regex("""^(?:★\s*)?([0-5](?:\.\d)?)\s*(?:★|stars?|/\s*5)?$""", RegexOption.IGNORE_CASE)

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    /**
     * "Title" then lines like "1. Name | 4.8 | Sardinian · $$$ | Mission District, SF | Authentic vibe; Spicy octopus pasta".
     * Plain "1. Name · 4.8 · Area" also works. Needs at least two numbered lines.
     */
    fun list(text: String): ListCard? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 3 || NUMBERED.matches(lines[0])) return null
        val numbered = lines.drop(1).mapNotNull { NUMBERED.find(it) }
        if (numbered.size < 2) return null
        val items = numbered.map { m ->
            val body = m.groupValues[2]
            if ('|' in body) {
                // The asked-for form: name | rating | kind | area | tags. A "-" means not known.
                val f = body.split("|").map { it.trim().let { v -> if (v == "-") "" else v } }
                return@map Item(m.groupValues[1].toInt(), f[0].removeSurrounding("**"), f.getOrNull(1)?.let { RATING.find(it)?.groupValues?.get(1)?.toDoubleOrNull() },
                    f.getOrNull(2).orEmpty(), f.getOrNull(3).orEmpty(),
                    f.drop(4).flatMap { it.split(";") }.map { it.trim() }.filter { it.isNotEmpty() }.take(3))
            }
            val parts =(if ('|' in body) body.split("|") else body.split(" · ", " - ", " — ")).map { it.trim() }.filter { it.isNotEmpty() }
            var rating: Double? = null
            val others = ArrayList<String>()
            for (p in parts.drop(1)) {
                val r = RATING.find(p)
                if (rating == null && r != null) rating = r.groupValues[1].toDoubleOrNull() else others += p
            }
            val piped = '|' in body
            val kind = if (piped) others.getOrNull(0).orEmpty() else ""
            val area = if (piped) others.getOrNull(1).orEmpty() else others.firstOrNull().orEmpty()
            val tags = (if (piped) others.drop(2) else others.drop(1)).flatMap { it.split(";") }.map { it.trim() }.filter { it.isNotEmpty() }.take(3)
            Item(m.groupValues[1].toInt(), parts.firstOrNull().orEmpty().removeSurrounding("**"), rating, kind, area, tags)
        }
        val rest = lines.drop(1).filter { !NUMBERED.matches(it) }.joinToString("\n")
        return ListCard(lines[0].removeSuffix(":"), items.take(5), rest)
    }

    // ---------- reply options ----------

    /** Two to four versions, one per line, each starting "1)", "2)"...; the user picks one. */
    fun options(text: String): List<String>? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size !in 2..4) return null
        val m = lines.map { Regex("""^([1-4])\)\s+(.+)$""").find(it) ?: return null }
        return m.map { it.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("“", "”").trim() }
    }

    // ---------- Notion pages ----------

    data class NotionCard(val title: String, val edited: String, val url: String) {
        fun plain() = "$title: $url"
    }

    /** A Notion skill answer: the page title, an optional "Last edited: …" line, and a notion.so link. */
    fun notion(text: String, link: String?): NotionCard? {
        if (link == null || !Regex("""^https://([a-z0-9-]+\.)?notion\.(so|site)/""").containsMatchIn(link)) return null
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines.size > 3 || lines[0].length > 120) return null
        val edited = lines.drop(1).firstOrNull { it.startsWith("Last edited", ignoreCase = true) }
            ?.substringAfter(":", "")?.trim().orEmpty()
        return NotionCard(lines[0].removePrefix("📄").trim(), edited, link)
    }

    // ---------- what a copied message is about ----------

    /** Kinds a trigger can watch for, with the words shown in the editor. */
    val KINDS = listOf(
        "person" to "a person's name", "time" to "a date or time", "place" to "a place or address",
        "link" to "a link", "email" to "an email address", "phone" to "a phone number", "foreign" to "another language", "keyword" to "a word I choose", "any" to "anything",
    )

    private val TIME = Regex(
        """\b(today|tonight|tomorrow|tmrw|kal|parso|aaj|next week|this (morning|evening|afternoon|weekend)|(mon|tues|wednes|thurs|fri|satur|sun)day|""" +
            """\d{1,2}(:\d{2})?\s?(am|pm|baje)|\d{1,2}:\d{2}|\d{1,2}(st|nd|rd|th)?\s(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*|""" +
            """(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s\d{1,2})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val LINK = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")
    private val PHONE = Regex("""(?<![\w])\+?\d[\d\s-]{8,14}\d(?![\w])""")
    private val PLACE = Regex(
        """\b(at|near|in|to|from)\s+(the\s+)?([A-Z][\w'&]+(\s+[A-Z][\w'&]+){0,3})\s*(cafe|café|restaurant|hotel|mall|station|airport|park|street|road|st\b|rd\b|avenue|plaza|market|hospital|office|bar)""" +
            """|\b\d+\s+[A-Z][a-z]+\s+(Street|St|Road|Rd|Avenue|Ave|Lane|Blvd)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val NAME_AFTER = Regex("""\b(?:with|meet|meeting|met|call|calling|ask|tell|from|by|to|and|about|cc)\s+((?:[A-Z][a-z]+|[A-Z]\.)(?:\s+[A-Z][a-z'-]+){1,2})\b""")
    private val NAME_ANY = Regex("""(?<![.!?]\s)(?<!^)\b([A-Z][a-z]+\s+[A-Z][a-z'-]+)\b""")
    private val NOT_NAMES = setOf(
        "Google Cloud", "New York", "San Francisco", "Los Angeles", "United States", "Good Morning", "Happy Birthday", "Thank You", "Best Regards",
        "Next Week", "Last Week", "Monday Morning", "Friday Night", "Microsoft Teams", "Google Meet", "Google Calendar",
    )

    /** The first likely person's name in [text], e.g. "David Miller" in "meeting with David Miller from Google Cloud". */
    fun personName(text: String): String? {
        val cleaned = text.replace(LINK, " ").replace(EMAIL, " ")
        val candidates = NAME_AFTER.findAll(cleaned).map { it.groupValues[1] } + NAME_ANY.findAll(cleaned).map { it.groupValues[1] }
        return candidates.map { it.trim() }.firstOrNull { name ->
            name !in NOT_NAMES && name.split(" ").none { w -> w in WEEKDAYS_MONTHS }
        }
    }

    private val WEEKDAYS_MONTHS = setOf(
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday", "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December", "The", "This", "That", "We", "I", "Our", "Your",
    )

    /** True when [text] has something of [kind] in it. */
    fun matches(kind: String, keyword: String, text: String): Boolean = when (kind) {
        "person" -> personName(text) != null
        "time" -> TIME.containsMatchIn(text)
        "place" -> PLACE.containsMatchIn(text)
        "link" -> LINK.containsMatchIn(text)
        "email" -> EMAIL.containsMatchIn(text)
        "phone" -> PHONE.containsMatchIn(text)
        "foreign" -> Acts.isForeign(text)
        "keyword" -> keyword.isNotBlank() && keyword.split(",").map { it.trim() }.filter { it.isNotEmpty() }.any { text.contains(it, ignoreCase = true) }
        "any" -> text.isNotBlank()
        else -> false
    }

    /** A short label for a trigger chip: "Who is David Miller" rather than just "Who is". */
    fun chipLabel(trigger: Trigger, text: String): String = when (trigger.kind) {
        "person" -> personName(text)?.let { "${trigger.skill} · $it" } ?: trigger.skill
        "foreign" -> Acts.foreignLanguage(text)?.takeIf { it != "another language" }?.let { "${trigger.skill} · $it" } ?: trigger.skill
        else -> trigger.skill
    }
}

/** "When a copied message has [kind] (or [keyword]), offer [skill]; with [auto], run it straight away." */
data class Trigger(val kind: String, val keyword: String, val skill: String, val auto: Boolean) {
    companion object {
        /** Skills a default trigger can use even if the user deleted or never had them. */
        val BUILTIN = mapOf(
            "Who is" to "The text or the context names a person. Look them up on the web and reply with one item per line: their full name; a one-line headline with their role and organisation; then Current: their current role, Previous: their previous role, Education: where they studied, Location: where they are based; and last a LinkedIn people search link, https://www.linkedin.com/search/results/people/?keywords= followed by the name with spaces written as %20. Leave out any line you cannot verify. If you cannot tell who they are, say so in one line instead of guessing.",
            "Save event" to "The text describes an event or a meeting. Work out its title, date, start time and place, resolving words like tomorrow from today's date. Reply with one line describing it, then a final [[open:https://calendar.google.com/calendar/render?action=TEMPLATE&text=TITLE&dates=YYYYMMDDTHHMMSS/YYYYMMDDTHHMMSS&location=PLACE]] line in local time, one hour long if no end is given. If Google Calendar tools are available, save it with them instead.",
            "Place" to "The text names a place. Find it with the place search and return its name, address and a Google Maps link, with the map marker line.",
        )
        val DEFAULTS = listOf(Trigger("person", "", "Who is", false), Trigger("time", "", "Schedule it", false), Trigger("place", "", "Place", false), Trigger("foreign", "", "Understand any language", false))
    }
}

/** Ready-made acts offered in the Acts panel next to the user's own skills. */
object Acts {
    class Act(val emoji: String, val name: String, val instruction: String, val needsCopied: Boolean)

    val BUILTIN = listOf(
        Act("💬", "Reply options", "Write three different versions of the reply or message the user wants, using their text and the copied message if there is one: one warm, one brief, one formal. Return exactly three lines starting with 1), 2) and 3), and nothing else.", false),
        Act("📅", "Schedule it", "The text or the copied message describes an event (a meeting, a birthday, a reminder). Work out its title, date, time and place, using today's date for words like tomorrow; a birthday or holiday is a whole-day event. Reply with a short friendly message the user can send to invite someone, with the event details, then a final [[open:https://calendar.google.com/calendar/render?action=TEMPLATE&text=TITLE&dates=DATES&details=DETAILS&location=PLACE]] line, where DATES is YYYYMMDD/YYYYMMDD (end is the next day) for a whole-day event, otherwise local YYYYMMDDTHHMMSS/YYYYMMDDTHHMMSS, one hour long if no end is given.", false),
        Act("🌐", "Understand any language", "The copied message, or the text, may be in any language. Reply with: on the first line From LANGUAGE (the language it is written in); then what it says, translated naturally into English (into Hindi if the user's own text is in Hindi); then a last line starting Tone: with one short phrase on the tone and what the sender wants.", true),
        Act("📝", "Save to Notion", "Save the copied message, or the text, to the user's Notion with notion_create_page: it lands under their chosen destination automatically, so only pass parent_page_id if they name a different page. The title is a short summary of under eight words and the content is the full copied text, then a line with a one-sentence summary. Reply with exactly two lines, the new page title and then Last edited: Just now, and end with [[open:NEW_PAGE_URL]]. If Notion is not connected, reply: Connect Notion in Verbo to save pages, and end with [[open:https://www.notion.so]].", true),
        Act("🔎", "Find anything", "Search everything the user has connected for what the text or the copied message asks about: use gmail_search, notion_search, google_calendar_events, slack_search, github_search, linear_search_issues and todoist_list_tasks where you have them (try several if unsure), and the web only if nothing matches. Return a short title line, then up to five numbered lines, most relevant first, in exactly this form: 1. Title | - | Source (Gmail, Notion, Calendar, Slack...) | date or who | one short tag. If one result is clearly the answer, end with its link on a final [[open:URL]] line. If nothing is connected or found, say so in one line.", false),
        Act("🎯", "Extract what matters", "From the copied message or the text, pull out only what matters: decisions, dates and times, amounts, names and things the user must do. One short item per line, each starting with a dash. Nothing else.", true),
        Act("🧠", "Explain simply", "Explain the copied message, or the text, in plain words a 14-year-old would understand, in two to four short sentences. Keep names and numbers.", true),
        Act("✅", "Fact check", "Check the claims in the copied message, or the text, searching the web where needed. First line: True, False, Misleading or Unclear. Then one or two short lines with what is actually true and the source name.", true),
        Act("🗣️", "Sound like a native", "Rewrite the text so it sounds like a native speaker of its language wrote it: natural, idiomatic, same meaning and tone, similar length.", false),
    )

    /** A chain is stored as a skill whose instruction is "Chain: step | step | step"; each step is an act's name or a plain instruction. */
    fun isChain(instruction: String) = instruction.startsWith("Chain:")
    fun chainSteps(instruction: String) = instruction.removePrefix("Chain:").split("|").map { it.trim() }.filter { it.isNotEmpty() }
    fun chain(steps: List<String>) = "Chain: " + steps.joinToString(" | ")

    /** The task for a chain, with each step's full instruction looked up by [lookup] (null: the step is its own instruction). */
    fun chainTask(steps: List<String>, lookup: (String) -> String?): String {
        val sb = StringBuilder("Task: do these steps in order for the user's text and the copied message, carrying what each step produces into the next. Use the tools you have for each step. ")
        sb.append("A step that needs an app you have no tool for ends with that app's [[open:...]] link; calendar event links (https://calendar.google.com/calendar/render?action=TEMPLATE...) are saved for the user automatically, so give one for every event. ")
        sb.append("Finish with the message the user should send, if a step wrote one, then one short line per step starting with ✓ saying what was done.")
        steps.forEachIndexed { i, step -> sb.append("\nStep ${i + 1}: ").append(lookup(step)?.let { "$step. $it" } ?: step) }
        return sb.toString()
    }

    /** True when a skill works on a copied message rather than on what the user typed. */
    fun needsCopied(instruction: String) = Regex("""copied|the context|message the user received|they received|names a person""", RegexOption.IGNORE_CASE).containsMatchIn(instruction)

    /**
     * In a language the user doesn't read without help: French, Spanish or German as much as Japanese or
     * Arabic. English, Hindi and Hinglish are home. Checked on the phone, nothing is sent.
     */
    fun isForeign(text: String): Boolean = foreignLanguage(text) != null

    /** The foreign language [text] is in ("French"), "another language" for an unknown script, or null. */
    fun foreignLanguage(text: String): String? {
        LangDetect.detect(text)?.let { return if (it in LangDetect.HOME) null else it }
        // A script the detector doesn't name: still foreign when it is most of the letters.
        val letters = text.filter { it.isLetter() }
        if (letters.length < 4) return null
        val other = letters.count { c ->
            val block = Character.UnicodeBlock.of(c)
            block != Character.UnicodeBlock.BASIC_LATIN && block != Character.UnicodeBlock.LATIN_1_SUPPLEMENT &&
                block != Character.UnicodeBlock.LATIN_EXTENDED_A && block != Character.UnicodeBlock.LATIN_EXTENDED_B && block != Character.UnicodeBlock.DEVANAGARI
        }
        return if (other * 10 >= letters.length * 4) "another language" else null
    }

    /** Acts that fit [text] best, most useful first: a date suggests Schedule it, a foreign message Understand, a link Save to Notion. */
    fun forContext(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        if (isForeign(text)) out += "Understand any language"
        if (Cards.matches("time", "", text)) out += "Schedule it"
        if (Cards.matches("link", "", text)) out += "Save to Notion"
        if (Cards.personName(text) != null) out += "Who is"
        if (Regex("""^\s*(find|search|where is|look for|dhoondo|kahan)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) out.add(0, "Find anything")
        return out
    }
}
