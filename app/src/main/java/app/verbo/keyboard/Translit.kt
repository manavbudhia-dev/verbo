package app.verbo.keyboard

/**
 * Hinglish (Hindi typed in Latin letters) to Devanagari: "kaise ho" -> "कैसे हो". Common words come from a
 * small table; everything else is spelled out phonetically. No Android dependencies.
 */
object Translit {
    private val WORDS = mapOf(
        "hai" to "है", "hain" to "हैं", "main" to "मैं", "mai" to "मैं", "nahi" to "नहीं", "nahin" to "नहीं", "nhi" to "नहीं", "kya" to "क्या",
        "aap" to "आप", "tum" to "तुम", "mein" to "में", "me" to "में", "kaise" to "कैसे", "kyun" to "क्यों", "kyu" to "क्यों", "haan" to "हाँ",
        "ha" to "हाँ", "theek" to "ठीक", "thik" to "ठीक", "accha" to "अच्छा", "acha" to "अच्छा", "achha" to "अच्छा", "bhai" to "भाई", "kal" to "कल",
        "aaj" to "आज", "hum" to "हम", "ho" to "हो", "ki" to "की", "ka" to "का", "ko" to "को", "se" to "से", "ye" to "ये", "yeh" to "यह",
        "wo" to "वो", "woh" to "वह", "kuch" to "कुछ", "sab" to "सब", "bahut" to "बहुत", "bohot" to "बहुत", "pyaar" to "प्यार", "pyar" to "प्यार",
        "dost" to "दोस्त", "ghar" to "घर", "khana" to "खाना", "paani" to "पानी", "pani" to "पानी", "raha" to "रहा", "rahi" to "रही",
        "gaya" to "गया", "karo" to "करो", "chalo" to "चलो", "namaste" to "नमस्ते", "dhanyavad" to "धन्यवाद", "shukriya" to "शुक्रिया", "ji" to "जी",
        "mera" to "मेरा", "meri" to "मेरी", "tera" to "तेरा", "teri" to "तेरी", "aur" to "और", "lekin" to "लेकिन", "par" to "पर", "abhi" to "अभी",
        "phir" to "फिर", "fir" to "फिर", "kab" to "कब", "kahan" to "कहाँ", "kaun" to "कौन", "hoon" to "हूँ", "hu" to "हूँ", "tha" to "था",
        "thi" to "थी", "the" to "थे", "sahi" to "सही", "yaar" to "यार", "bas" to "बस", "matlab" to "मतलब", "samajh" to "समझ", "pata" to "पता",
        "chahiye" to "चाहिए", "milte" to "मिलते", "baat" to "बात", "din" to "दिन", "raat" to "रात", "subah" to "सुबह", "shaam" to "शाम",
    )

    private val VOWELS = listOf(
        "aa" to ("आ" to "ा"), "ai" to ("ऐ" to "ै"), "au" to ("औ" to "ौ"), "ee" to ("ई" to "ी"), "ii" to ("ई" to "ी"), "oo" to ("ऊ" to "ू"),
        "uu" to ("ऊ" to "ू"), "a" to ("अ" to ""), "i" to ("इ" to "ि"), "u" to ("उ" to "ु"), "e" to ("ए" to "े"), "o" to ("ओ" to "ो"),
    )
    private val CONSONANTS = listOf(
        "chh" to "छ", "kh" to "ख", "gh" to "घ", "ch" to "च", "jh" to "झ", "th" to "थ", "dh" to "ध", "ph" to "फ", "bh" to "भ", "sh" to "श",
        "ng" to "ङ", "k" to "क", "g" to "ग", "c" to "क", "j" to "ज", "t" to "त", "d" to "द", "n" to "न", "p" to "प", "f" to "फ़", "b" to "ब",
        "m" to "म", "y" to "य", "r" to "र", "l" to "ल", "v" to "व", "w" to "व", "s" to "स", "h" to "ह", "z" to "ज़", "q" to "क़", "x" to "क्स",
    )
    private const val HALANT = "्"

    /** True for a word worth offering in Devanagari: only Latin letters, at least two. */
    fun applies(word: String) = word.length >= 2 && word.all { it in 'a'..'z' || it in 'A'..'Z' }

    fun toDevanagari(word: String): String {
        val w = word.lowercase()
        WORDS[w]?.let { return it }
        val out = StringBuilder()
        var i = 0
        var afterConsonant = false
        while (i < w.length) {
            val c = CONSONANTS.firstOrNull { w.startsWith(it.first, i) }
            if (c != null) {
                if (afterConsonant) out.append(HALANT) // two consonants in a row join
                out.append(c.second)
                i += c.first.length
                afterConsonant = true
                continue
            }
            val v = VOWELS.firstOrNull { w.startsWith(it.first, i) }
            if (v != null) {
                val last = i + v.first.length == w.length
                var sign = v.second.second
                if (afterConsonant && last && v.first == "i") sign = "ी" // a word-final short i is usually long
                out.append(if (afterConsonant) sign else v.second.first)
                i += v.first.length
                afterConsonant = false
                continue
            }
            out.append(w[i])
            i++
            afterConsonant = false
        }
        return out.toString()
    }
}

/**
 * Swipe typing: turns the path a finger drew over the letters into likely words, by comparing the path's
 * shape with the shape each candidate word would make. No Android dependencies.
 */
object Gesture {
    data class P(val x: Float, val y: Float)

    /** The letters the path passed over, without repeats in a row ("hgfdertyuiklo"). */
    fun visited(path: List<P>, keyAt: (P) -> Char?): String {
        val sb = StringBuilder()
        for (p in path) { val c = keyAt(p) ?: continue; if (sb.isEmpty() || sb.last() != c) sb.append(c) }
        return sb.toString()
    }

    private fun resample(points: List<P>, n: Int): List<P> {
        if (points.size < 2) return List(n) { points.firstOrNull() ?: P(0f, 0f) }
        val lengths = FloatArray(points.size)
        for (i in 1 until points.size) lengths[i] = lengths[i - 1] + dist(points[i - 1], points[i])
        val total = lengths.last()
        if (total == 0f) return List(n) { points[0] }
        val out = ArrayList<P>(n)
        var j = 1
        for (k in 0 until n) {
            val target = total * k / (n - 1)
            while (j < points.size - 1 && lengths[j] < target) j++
            val seg = lengths[j] - lengths[j - 1]
            val t = if (seg == 0f) 0f else (target - lengths[j - 1]) / seg
            out += P(points[j - 1].x + (points[j].x - points[j - 1].x) * t, points[j - 1].y + (points[j].y - points[j - 1].y) * t)
        }
        return out
    }

    private fun dist(a: P, b: P) = kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    /** True when [word]'s letters (repeats dropped) appear in order within [seen]. */
    private fun fits(word: String, seen: String): Boolean {
        var j = 0
        var prev = ' '
        for (c in word) {
            if (c == prev) continue
            prev = c
            j = seen.indexOf(c, j)
            if (j < 0) return false
            j++
        }
        return true
    }

    /**
     * Best words for the path, best first. [candidates] gives words starting with a letter, most common first
     * (with a 0..10 commonness rank); [centers] are the letter keys' centres and [keyWidth] their width.
     */
    fun decode(path: List<P>, seen: String, centers: Map<Char, P>, keyWidth: Float, candidates: (Char) -> List<Pair<String, Int>>, max: Int = 3): List<String> {
        if (seen.length < 2 || path.size < 2) return emptyList()
        val user = resample(path, 32)
        val first = seen.first()
        val lastOk = setOf(seen.last()) + (seen.getOrNull(seen.length - 2)?.let { setOf(it) } ?: emptySet())
        val scored = ArrayList<Pair<String, Float>>()
        for ((word, rank) in candidates(first)) {
            if (word.length < 2 || word.length > seen.length + 3 || word.last() !in lastOk || !fits(word, seen)) continue
            val pts = word.mapNotNull { centers[it] }
            if (pts.size != word.length) continue
            val shape = resample(pts, 32)
            var d = 0f
            for (k in 0 until 32) d += dist(user[k], shape[k])
            val score = d / 32 / keyWidth + rank * 0.06f
            scored += word to score
        }
        return scored.sortedBy { it.second }.take(max).map { it.first }
    }
}
