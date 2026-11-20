package app.verbo.keyboard

import org.json.JSONObject

/**
 * Learns how the user types Hinglish ("h" for hai, "nhi", "kese", "kr") so translating it can run on the
 * phone: the text is written out in Devanagari with the user's own spellings, English words stay as they
 * are, and ML Kit's Hindi model translates it in a moment with no request.
 *
 * Every time the AI service has to translate Hinglish, it also returns each Hindi word as the user spelled
 * it with its Devanagari form; those are saved, so the next sentence with the same words stays on the
 * phone. The word list lives on this phone only (at most [MAX] words).
 */
object Hinglish {
    const val MAX = 4000
    private const val PREF_FIELD = "hinglish_words"

    /** The marker line the AI service adds after a Hinglish translation: [[words:kese=कैसे, h=है]]. */
    private val WORDS_LINE = Regex("""\[\[words:([^\]]*)]]""")

    /** Asked of the AI service whenever the text is Hinglish, so it can be learnt from. */
    const val LEARN = " The text is Hinglish (Hindi typed in Latin letters, mixed with English). After the translation, add one final line " +
        "in exactly this form: [[words:spelling=देवनागरी, spelling=देवनागरी]] listing each Hindi word of the text exactly as the user spelled it, " +
        "lower case, with its Devanagari form. Leave English words out."

    /**
     * True for Hindi typed in Latin letters. Besides the common words, a text where most words are neither
     * English nor another language's counts too ("mko kese pta h"), as do the user's learnt spellings.
     */
    fun isHinglish(text: String, isEnglish: ((String) -> Boolean)? = null, learnt: Map<String, String> = emptyMap()): Boolean {
        if (Memory.hinglishShare(text) >= 0.2) return true
        val lang = LangDetect.detect(text)
        if (lang == LangDetect.HINGLISH) return true
        if (lang != null && lang != "English") return false
        val words = Regex("""[A-Za-z]+""").findAll(text).map { it.value.lowercase() }.toList()
        if (words.isEmpty() || text.any { it.isLetter() && it.code > 0x24F }) return false
        if (words.count { it in learnt } * 2 >= words.size) return true
        if (isEnglish == null || lang == "English") return false
        return words.count { !isEnglish(it) } * 2 > words.size
    }

    fun words(store: Store): Map<String, String> = try {
        val o = JSONObject(store.text(PREF_FIELD, "{}"))
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) { emptyMap() }

    /** Takes the [[words:...]] line out of a result and saves the spellings in it. */
    fun learn(store: Store, r: ActionResult): ActionResult {
        val m = WORDS_LINE.find(r.text) ?: return r
        val learnt = m.groupValues[1].split(',').mapNotNull { pair ->
            val k = pair.substringBefore('=').trim().lowercase()
            val v = pair.substringAfter('=', "").trim()
            if (k.isEmpty() || v.isEmpty() || k.any { it !in 'a'..'z' } || v.none { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.DEVANAGARI }) null else k to v
        }
        if (learnt.isNotEmpty()) synchronized(this) {
            val all = LinkedHashMap(words(store))
            learnt.forEach { (k, v) -> all.remove(k); all[k] = v } // newest last, so the oldest go first when full
            while (all.size > MAX) all.remove(all.keys.first())
            store.setText(PREF_FIELD, JSONObject(all as Map<*, *>).toString())
        }
        return r.copy(text = r.text.replace(m.value, "").trimEnd())
    }

    /**
     * The text in Devanagari, or null when too many words are guesses (then the AI service is surer).
     * A word is known when the user's own list, the built-in table or the English dictionary has it.
     */
    fun toDevanagari(text: String, learnt: Map<String, String>, isEnglish: (String) -> Boolean): String? {
        val tokens = Regex("""[A-Za-z]+|[^A-Za-z]+""").findAll(text).map { it.value }.toList()
        var words = 0
        var guessed = 0
        val out = StringBuilder()
        for (t in tokens) {
            if (!t[0].isLetter()) { out.append(t); continue }
            words++
            val w = t.lowercase()
            val mine = learnt[w]
            when {
                mine != null -> out.append(mine)
                w in Memory.HINGLISH || Translit.known(w) -> out.append(Translit.toDevanagari(w))
                isEnglish(w) -> out.append(t)
                else -> { guessed++; out.append(Translit.toDevanagari(w)) }
            }
        }
        if (words == 0) return null
        return if (guessed * 5 > words) null else out.toString()
    }

    /**
     * Hinglish translated on the phone: written out in Devanagari, then Hindi → [target] with ML Kit.
     * Into Hindi it is just the Devanagari. Null when it can't be done confidently or the model isn't there.
     */
    fun quick(store: Store, text: String, target: String, isEnglish: (String) -> Boolean): String? {
        val learnt = words(store)
        if (!isHinglish(text, isEnglish, learnt)) return null
        val hindi = toDevanagari(text, learnt, isEnglish) ?: return null
        if (target.equals("Hindi", true)) return hindi
        return Offline.quickFrom(hindi, "hi", target)
    }
}
