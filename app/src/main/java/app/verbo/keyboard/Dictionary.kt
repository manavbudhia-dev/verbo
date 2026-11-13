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
    }

    private fun indexOf(word: String): Int = lower.binarySearch(word)

    fun contains(word: String) = ready && indexOf(word.lowercase()) >= 0

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
