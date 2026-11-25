package app.verbo.keyboard

import java.io.InputStream

/**
 * English word list for suggestions and auto-correct. Words come in tiers (10 = most common);
 * the tier stands in for word frequency. Loaded once on a background thread.
 */
class Dictionary {
    private var lower: Array<String> = emptyArray() // sorted, lowercase
    private var shown: Array<String> = emptyArray() // same order, original capitalisation
    private var tier: ByteArray = ByteArray(0)
    @Volatile var ready = false
        private set

    fun load(input: InputStream) {
        val entries = ArrayList<Triple<String, String, Byte>>(62000)
        var current: Byte = 10
        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                if (line[0] == '#') current = line.substring(1).toInt().toByte()
                else entries.add(Triple(line.lowercase(), line, current))
            }
        }
        // A word can appear both capitalised and not ("may", "May"); keep the more common entry.
        entries.sortWith(compareBy<Triple<String, String, Byte>> { it.first }.thenBy { it.third })
        val unique = entries.distinctBy { it.first }
        lower = Array(unique.size) { unique[it].first }
        shown = Array(unique.size) { unique[it].second }
        tier = ByteArray(unique.size) { unique[it].third }
        ready = true
        for (c in 'a'..'z') startingWith(c) // ready the swipe lists now, on the loading thread
    }

    private fun indexOf(word: String): Int = lower.binarySearch(word)

    /** Plain lowercase words starting with [c], with a commonness rank (0 = most common), for swipe typing. */
    private val byLetter = java.util.concurrent.ConcurrentHashMap<Char, List<Pair<String, Int>>>()

    fun startingWith(c: Char): List<Pair<String, Int>> {
        if (!ready) return emptyList()
        byLetter[c]?.let { return it }
        val prefix = c.toString()
        var i = lower.binarySearch(prefix).let { if (it < 0) -it - 1 else it }
        val out = ArrayList<Pair<String, Int>>()
        while (i < lower.size && lower[i].startsWith(prefix)) {
            val w = lower[i]
            if (w.all { it in 'a'..'z' }) out += w to (tier[i] / 10 - 1).coerceIn(0, 4)
            i++
        }
        byLetter[c] = out
        return out
    }

    fun contains(word: String) = ready && indexOf(word.lowercase()) >= 0

    private val oddsCache = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 48
    }

    /**
     * How likely each letter a..z is to come next after [prefix] (lower case, 0..1, summing to 1), from the
     * word list weighted by commonness, plus [extra] words (the user's Hinglish) counted as very common.
     * Null when nothing known starts that way, so smart touch stays out of the way.
     */
    @Synchronized
    fun nextLetterOdds(prefix: String, extra: Collection<String> = emptyList()): FloatArray? {
        if (!ready || prefix.length > 24 || prefix.any { it !in 'a'..'z' }) return null
        val key = prefix + "|" + extra.size
        oddsCache[key]?.let { return it.takeIf { o -> o.isNotEmpty() } }
        val odds = FloatArray(26)
        var i = if (prefix.isEmpty()) 0 else lower.binarySearch(prefix).let { if (it < 0) -it - 1 else it }
        val n = prefix.length
        while (i < lower.size && lower[i].startsWith(prefix)) {
            val w = lower[i]
            if (w.length > n) {
                val c = w[n]
                if (c in 'a'..'z') odds[c - 'a'] += TIER_WEIGHT[(tier[i] / 10).coerceIn(0, TIER_WEIGHT.size - 1)]
            }
            i++
        }
        // The list's tiers say little about how often a word is typed ("the" weighs as much as "throat"), so the
        // words people type all day count many times over, and so does the user's own Hinglish.
        for (w in TOP) if (w.length > n && w.startsWith(prefix)) odds[w[n] - 'a'] += TOP_WEIGHT
        for (w in extra) if (w.length > n && w.startsWith(prefix)) {
            val c = w[n]
            if (c in 'a'..'z') odds[c - 'a'] += EXTRA_WEIGHT
        }
        val total = odds.sum()
        if (total <= 0f) { oddsCache[key] = FloatArray(0); return null }
        for (k in odds.indices) odds[k] /= total
        oddsCache[key] = odds
        return odds
    }

    /** Up to [max] likely words starting with [typed], most common first. */
    fun completions(typed: String, max: Int): List<String> {
        if (!ready || typed.isEmpty()) return emptyList()
        val prefix = typed.lowercase()
        var i = lower.binarySearch(prefix).let { if (it < 0) -it - 1 else it }
        val best = ArrayList<Int>()
        var scanned = 0
        while (i < lower.size && lower[i].startsWith(prefix) && scanned < 6000) {
            if (lower[i].length > prefix.length) best.add(i)
            i++
            scanned++
        }
        best.sortWith(compareBy<Int> { tier[it] }.thenBy { lower[it].length })
        return best.take(max).map { matchCase(shown[it], typed) }
    }

    /** What [typed] was probably meant to be, or null if it looks fine or nothing is close. */
    fun correction(typed: String, sentenceStart: Boolean = false): String? {
        if (!ready) return null
        val word = typed.lowercase()
        FIXED[word]?.let { return it }
        // Short words, names and chat shorthand are too easy to "fix" wrongly, so they are left alone.
        if (typed.length < 4 || typed.any { it.isUpperCase() && !sentenceStart } || typed.drop(1).any { it.isUpperCase() }) return null
        if (typed.any { !it.isLetter() } || word in SLANG || indexOf(word) >= 0) return null

        var bestIndex = -1
        var bestScore = Int.MAX_VALUE
        fun consider(candidate: String, penalty: Int) {
            val at = indexOf(candidate)
            if (at < 0 || tier[at] > 40) return
            val score = tier[at] * 4 + penalty + if (candidate[0] != word[0]) 30 else 0
            if (score < bestScore) { bestScore = score; bestIndex = at }
        }
        val chars = word.toCharArray()
        for (i in 0 until chars.size - 1) { // swapped neighbours
            val swapped = chars.copyOf()
            swapped[i] = chars[i + 1]; swapped[i + 1] = chars[i]
            consider(String(swapped), 0)
        }
        for (i in chars.indices) { // wrong letter, preferring keys next to the one typed
            val near = NEIGHBOURS[chars[i]] ?: ""
            for (c in 'a'..'z') if (c != chars[i]) {
                val replaced = chars.copyOf()
                replaced[i] = c
                consider(String(replaced), if (c in near) 1 else 12)
            }
        }
        for (i in chars.indices) consider(word.removeRange(i, i + 1), 6) // extra letter
        for (i in 0..chars.size) for (c in 'a'..'z') consider(word.substring(0, i) + c + word.substring(i), 8) // missing letter
        return if (bestIndex < 0) null else matchCase(shown[bestIndex], typed)
    }

    private fun matchCase(word: String, typed: String) =
        if (typed[0].isUpperCase()) word.replaceFirstChar { it.uppercase() } else word

    companion object {
        /** Weight of one word by its tier / 10: the most common words count far more than rare ones. */
        private const val TOP_WEIGHT = 25f
        private const val EXTRA_WEIGHT = 20f
        /** The words typed most every day: they steer smart touch far more than their tier alone would. */
        private val TOP = listOf(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "i", "it", "for", "not", "on", "with", "he", "as",
            "you", "do", "at", "this", "but", "his", "by", "from", "they", "we", "say", "her", "she", "or", "an", "will",
            "my", "one", "all", "would", "there", "their", "what", "so", "up", "out", "if", "about", "who", "get",
            "which", "go", "me", "when", "make", "can", "like", "time", "no", "just", "him", "know", "take", "people",
            "into", "year", "your", "good", "some", "could", "them", "see", "other", "than", "then", "now", "look",
            "only", "come", "its", "over", "think", "also", "back", "after", "use", "two", "how", "our", "work", "first",
            "well", "way", "even", "new", "want", "because", "any", "these", "give", "day", "most", "us", "is", "was",
            "are", "been", "has", "had", "were", "said", "did", "am", "does", "got", "going", "went", "made", "very",
            "much", "more", "many", "such", "here", "why", "where", "while", "should", "may", "might", "must", "let",
            "tell", "call", "need", "feel", "try", "ask", "yes", "okay", "ok", "thanks", "thank", "please", "hi",
            "hello", "love", "today", "tomorrow"
        )
        private val TIER_WEIGHT = floatArrayOf(1f, 1f, 0.3f, 0.09f, 0.03f, 0.01f, 0.004f, 0.002f, 0.001f, 0.001f, 0.001f)
        private val FIXED = mapOf(
            "i" to "I", "im" to "I'm", "ive" to "I've", "dont" to "don't", "cant" to "can't", "wont" to "won't",
            "didnt" to "didn't", "doesnt" to "doesn't", "isnt" to "isn't", "wasnt" to "wasn't", "arent" to "aren't",
            "werent" to "weren't", "havent" to "haven't", "hasnt" to "hasn't", "couldnt" to "couldn't",
            "shouldnt" to "shouldn't", "wouldnt" to "wouldn't", "youre" to "you're", "theyre" to "they're",
            "thats" to "that's", "whats" to "what's", "theres" to "there's", "teh" to "the",
        )
        private val SLANG = setOf("haha", "hahaha", "hehe", "yeah", "yeap", "nope", "okay", "hmmm", "lmao", "gonna", "wanna", "gotta", "kinda", "dunno", "thanx")
        private val NEIGHBOURS: Map<Char, String> = run {
            val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            val map = HashMap<Char, String>()
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, ch ->
                    val sb = StringBuilder()
                    if (c > 0) sb.append(row[c - 1])
                    if (c < row.length - 1) sb.append(row[c + 1])
                    for (other in listOf(r - 1, r + 1)) if (other in rows.indices) {
                        for (d in -1..1) rows[other].getOrNull(c + d)?.let { sb.append(it) }
                    }
                    map[ch] = sb.toString()
                }
            }
            map
        }
    }
}
