package app.verbo.keyboard

/**
 * Three ways to answer a message that just came in (WhatsApp and other chats): Polite, Dodge and Savage.
 * One request writes all three; the keyboard shows them as a card to pick from, edit, and apply.
 */
object Replies {
    class Style(val emoji: String, val name: String, val brief: String)

    val STYLES = listOf(
        Style("😇", "Polite", "warm, kind and direct"),
        Style("🙃", "Dodge", "playfully sidesteps the question without answering it"),
        Style("💀", "Savage", "a witty, cheeky comeback that stays friendly, never insulting or rude"),
    )

    class Option(val style: Style, val text: String)

    const val TASK = "Task: the context is a chat message the user just received. Write three short replies the user could send, " +
        "in the same language and script as the message (Hinglish stays Hinglish) and in the user's own style if you know it. " +
        "Polite: warm, kind and direct. Dodge: playfully sidesteps the question without answering it. " +
        "Savage: a witty, cheeky comeback that stays friendly, never insulting or rude. " +
        "Each under 18 words; one fitting emoji is fine. Return exactly three lines and nothing else, in this form:\n" +
        "Polite: ...\nDodge: ...\nSavage: ..."

    private val LINE = Regex("""^\W*(polite|dodge|savage)\W*\s*[:\-–—]\s*(.+)$""", RegexOption.IGNORE_CASE)

    /** The three replies from the AI's answer, in style order; null when the answer isn't in that form. */
    fun parse(text: String): List<Option>? {
        val found = HashMap<String, String>()
        for (line in text.lines()) {
            val m = LINE.find(line.trim()) ?: continue
            val body = m.groupValues[2].trim().removeSurrounding("\"").removeSurrounding("“", "”").trim()
            if (body.isNotEmpty()) found.putIfAbsent(m.groupValues[1].lowercase(), body)
        }
        val out = STYLES.mapNotNull { s -> found[s.name.lowercase()]?.let { Option(s, it) } }
        if (out.size == STYLES.size) return out
        // Three plain lines (or 1) 2) 3)) are taken in order.
        val plain = Cards.options(text) ?: text.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.size == 3 }
        return plain?.take(3)?.mapIndexed { i, t -> Option(STYLES[i], t) }
    }
}
