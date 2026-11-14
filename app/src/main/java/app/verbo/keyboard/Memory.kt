package app.verbo.keyboard

/**
 * What the keyboard knows about its user: facts they told it ("Mummy: +91 98…", "UPI: me@okicici")
 * and how they write, learned on the phone from messages they send. No Android dependencies.
 */
object Memory {
    data class Fact(val label: String, val value: String)

    /** "remember my UPI is me@okicici", "yaad rakho office address is …": the fact to save, or null if it isn't such a request. */
    fun parseRemember(text: String): Fact? {
        val t = text.trim().removeSuffix(".")
        val m = Regex("""^(?:please\s+)?(?:remember|yaad\s+rakh(?:o|na)?|note\s+down|save\s+this)\s*(?:that\s+|ki\s+)?(.+)$""", RegexOption.IGNORE_CASE).find(t) ?: return null
        val body = m.groupValues[1].trim()
        if (body.isEmpty()) return null
        val parts = Regex("""^(?:my\s+|mera\s+|meri\s+)?(.{1,60}?)\s+(?:is|=|:|hai)\s+(.+)$""", RegexOption.IGNORE_CASE).find(body)
            ?: Regex("""^(?:my\s+)?(.{1,60}?)\s*[:=]\s*(.+)$""").find(body)
        return if (parts != null) Fact(parts.groupValues[1].trim().replaceFirstChar { it.uppercase() }, parts.groupValues[2].trim())
        else Fact("Note", body)
    }

    /** "forget my UPI": the label to forget, or null. */
    fun parseForget(text: String): String? =
        Regex("""^(?:please\s+)?(?:forget|bhool\s+jao)\s+(?:my\s+|mera\s+|meri\s+)?(.+)$""", RegexOption.IGNORE_CASE).find(text.trim().removeSuffix("."))?.groupValues?.get(1)?.trim()

    private val HINGLISH = setOf(
        "hai", "hain", "nahi", "nhi", "kya", "kar", "karo", "kr", "ho", "bhai", "haan", "ha", "theek", "thik", "acha", "accha", "mein", "main", "ka", "ki", "ko",
        "tum", "aap", "kal", "aaj", "abhi", "chal", "yaar", "bhi", "toh", "to", "se", "hu", "hoon", "raha", "rahi", "gaya", "kaise", "kyu", "kyun", "matlab",
    )
    /** Share of words that are common Hinglish words (0..1). */
    fun hinglishShare(text: String): Double {
        val words = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        return if (words.isEmpty()) 0.0 else words.count { it in HINGLISH }.toDouble() / words.size
    }

    private val EMOJI = Regex("""[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}]""")

    /** One sentence on how the user writes, from messages they sent. Null until there are enough of them. */
    fun describe(samples: List<String>): String? {
        val msgs = samples.map { it.trim() }.filter { it.length in 2..600 }
        if (msgs.size < 5) return null
        val words = msgs.map { it.split(Regex("\\s+")).filter { w -> w.isNotBlank() } }
        val avg = words.sumOf { it.size }.toDouble() / msgs.size
        val all = words.flatten().map { it.lowercase().trim('.', ',', '!', '?') }
        val hinglish = all.count { it in HINGLISH }.toDouble() / all.size.coerceAtLeast(1)
        val emojiMsgs = msgs.count { EMOJI.containsMatchIn(it) }.toDouble() / msgs.size
        val topEmoji = msgs.flatMap { m -> EMOJI.findAll(m).map { it.value }.toList() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val lower = msgs.count { it == it.lowercase() && it.any(Char::isLetter) }.toDouble() / msgs.size
        val noFullStop = msgs.count { !it.endsWith(".") }.toDouble() / msgs.size
        val shortForms = all.count { it in setOf("u", "ur", "pls", "plz", "thx", "k", "ok", "okk", "btw", "tmrw", "msg") }.toDouble() / all.size.coerceAtLeast(1)

        val parts = ArrayList<String>()
        parts += when { avg < 6 -> "very short messages (about ${avg.toInt().coerceAtLeast(1)} words)"; avg < 14 -> "short messages (about ${avg.toInt()} words)"; else -> "longer messages (about ${avg.toInt()} words)" }
        parts += when { hinglish > 0.15 -> "in Hinglish (Hindi in Latin letters mixed with English)"; hinglish > 0.05 -> "in English with some Hindi words"; else -> "in English" }
        if (lower > 0.6) parts += "mostly lowercase"
        if (noFullStop > 0.8) parts += "without full stops"
        if (shortForms > 0.03) parts += "with short forms like u, pls, ok"
        parts += when { emojiMsgs > 0.4 -> "often with emoji" + (topEmoji?.let { " (especially $it)" } ?: ""); emojiMsgs > 0.1 -> "sometimes with an emoji" + (topEmoji?.let { " (usually $it)" } ?: ""); else -> "rarely with emoji" }
        return "Writes " + parts.joinToString(", ")
    }

    /** The block added to every request: facts and style. Empty when there is nothing to say. */
    fun profile(facts: List<Fact>, style: String?): String {
        if (facts.isEmpty() && style == null) return ""
        val sb = StringBuilder("\n\nAbout the user (use only when it helps the request; never mention that you were told this):")
        facts.take(60).forEach { sb.append("\n- ${it.label}: ${it.value}") }
        if (facts.any { Regex("""\+?\d[\d\s-]{8,}""").containsMatchIn(it.value) })
            sb.append("\nWhen the user asks to message one of these people on WhatsApp, use https://wa.me/NUMBER with their number (digits only, with country code).")
        if (style != null) sb.append("\nHow the user writes: $style. When you write a message for them to send, match this style.")
        return sb.toString()
    }
}
