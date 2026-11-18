package app.verbo.keyboard

/**
 * Tells which language a short message is in, on the phone, with no network.
 *
 * Scripts settle most languages outright (Japanese, Arabic, Tamil...). Languages written in Latin letters
 * (French, Spanish, German...) are told apart by their most common little words and their accents, which
 * is reliable even for a one-line chat message. Names match [Store.ALL_LANGUAGES]; romanised Hindi comes
 * back as [HINGLISH].
 */
object LangDetect {
    const val HINGLISH = "Hinglish"

    /** Languages the user reads without help: a message in one of these never counts as foreign. */
    val HOME = setOf("English", "Hindi", HINGLISH)

    private fun words(vararg s: String) = s.joinToString(" ").split(" ").filter { it.isNotEmpty() }.toSet()

    // The most frequent short words of each language; words two languages share are left out where it matters.
    private val LATIN: Map<String, Set<String>> = mapOf(
        "English" to words("the and is are was were you your i it this that what with for have has not but",
            "be to of in on my me we they he she do does did will would can could just so if at from how why",
            "when there here been an or all our about please thanks thank hello hi yes no ok okay"),
        "French" to words("le la les un une des du est et je tu il elle nous vous ils elles ce cette ces que qui",
            "pas ne pour avec dans sur mais ou où au aux mon ma mes ton ta tes son sa ses leur être avoir fait",
            "très bien merci bonjour salut oui non c'est j'ai suis êtes sont avez peux demain aujourd'hui",
            "quand comment pourquoi aussi alors donc rien tout toujours ça déjà était"),
        "Spanish" to words("el la los las un una unos unas es y que de en por para con como pero muy",
            "yo tú él ella nosotros ustedes ellos mi mis tu tus su sus del al lo le les está están estoy",
            "eres soy son hola gracias sí bueno buenos días mañana hoy dónde cuándo qué cómo porque también",
            "hay tengo tienes quiero puedo nada todo siempre ya"),
        "Portuguese" to words("o a os as um uma é e que de em por para com como mas muito eu você ele",
            "ela nós eles meu minha seu sua do da dos das no na nos nas está estão estou sou são olá obrigado",
            "obrigada sim não bom dia amanhã hoje onde quando porque também tenho quero posso nada tudo já"),
        "German" to words("der die das ein eine ist und ich du er sie es wir ihr nicht mit für auf aber",
            "oder wie was wer wo warum mein dein sein unser bin bist sind habe hast hat haben danke bitte",
            "hallo ja nein gut morgen heute auch noch schon dass den dem des zu im vom zum kann"),
        "Italian" to words("il lo la i gli le un una è e che di in per con come ma molto io tu lui lei",
            "noi voi loro mio mia tuo tua suo sua del della dei sono sei siamo ciao grazie sì buongiorno",
            "domani oggi dove quando perché anche ho hai abbiamo voglio posso niente tutto già non"),
        "Dutch" to words("de het een is en ik jij je hij zij wij jullie niet met voor op maar of hoe wat",
            "wie waar waarom mijn jouw zijn onze ben bent hebben heb heeft dank bedankt hallo ja nee goed",
            "morgen vandaag ook nog al dat van te kan wil"),
        "Indonesian" to words("yang dan di ke dari ini itu saya aku kamu anda dia kami kita mereka tidak",
            "bukan dengan untuk pada ada akan sudah belum bisa mau apa siapa mana kapan kenapa terima kasih",
            "selamat pagi besok hari juga atau tapi sangat"),
        "Turkish" to words("ve bir bu şu o ben sen biz siz onlar değil ile için ama çok ne nasıl neden",
            "nerede kim evet hayır merhaba teşekkür ederim yarın bugün de da mi mı mu mü var yok",
            "benim senin bizim gibi daha"),
        "Polish" to words("i w na z że jest nie to się ja ty on ona my wy oni jak co gdzie kiedy dlaczego",
            "mój moja twój twoja jestem jesteś są mam masz dziękuję cześć dzień dobry tak jutro dzisiaj",
            "też ale bardzo już"),
        "Swahili" to words("na ya wa za kwa ni si mimi wewe yeye sisi ninyi wao hii hiyo hapa pale nini",
            "nani wapi lini kwa nini asante sana habari jambo ndiyo hapana kesho leo pia lakini"),
        "Vietnamese" to words("và là của có không tôi bạn anh chị em chúng ta họ này đó được những các",
            "một với cho trong khi thì đã sẽ đang rất cảm ơn xin chào vâng ngày mai hôm nay"),
        HINGLISH to words("hai hain ho hoon tha thi kya kyu kyun kaise kaisa kab kahan kaun nahi nahin",
            "mat haan han ji acha accha theek thik mein mujhe mera meri tum tumhara tera teri aap apka",
            "aapka hum humara ka ki ke ko se pe aur bhi toh wala wali kal aaj abhi yaar bhai karo",
            "karna kar kiya raha rahe rahi hoga hogi chalo dekho bolo batao"),
    )

    // Letters that only (or mostly) one Latin-script language uses.
    private val MARKS: Map<String, String> = mapOf(
        "French" to "àâæçèêëîïôœùûÿ", "Spanish" to "ñ¿¡", "Portuguese" to "ãõ", "German" to "äöüß",
        "Italian" to "ìò", "Turkish" to "ğışİ", "Polish" to "ąćęłńśźż", "Vietnamese" to "ăđơưạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ",
    )

    /** The language of [text], or null when it is too short or unclear to say. */
    fun detect(text: String): String? {
        val letters = text.filter { it.isLetter() }
        if (letters.length < 4) return null
        byScript(letters)?.let { return it }
        val lower = text.lowercase()
        val tokens = Regex("""[\p{L}']+""").findAll(lower).map { it.value.trim('\'') }.filter { it.isNotEmpty() }.toList()
        if (tokens.isEmpty()) return null
        val score = HashMap<String, Float>()
        for ((lang, set) in LATIN) score[lang] = tokens.count { it in set }.toFloat()
        for ((lang, marks) in MARKS) {
            val hits = lower.count { it in marks }
            if (hits > 0) score[lang] = (score[lang] ?: 0f) + minOf(hits, 3) * 0.8f
        }
        // "c'est", "j'ai", "l'heure": French elisions are a strong sign on their own.
        if (Regex("""\b[cdjlmnst]'\p{L}""").containsMatchIn(lower)) score["French"] = (score["French"] ?: 0f) + 1.5f
        val ranked = score.entries.sortedByDescending { it.value }
        val best = ranked[0]
        val second = ranked.getOrNull(1)?.value ?: 0f
        val needed = if (tokens.size <= 3) 1f else 2f
        if (best.value < needed || best.value <= second) return null
        return best.key
    }

    private fun byScript(letters: String): String? {
        val counts = HashMap<String, Int>()
        for (c in letters) {
            val s = scriptOf(c) ?: continue
            counts[s] = (counts[s] ?: 0) + 1
        }
        val (script, n) = counts.maxByOrNull { it.value } ?: return null
        if (n * 10 < letters.length * 4) return null
        return when (script) {
            // Kana anywhere means Japanese, even when most of the letters are kanji.
            "Han" -> if (letters.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.HIRAGANA || Character.UnicodeBlock.of(it) == Character.UnicodeBlock.KATAKANA }) "Japanese" else "Chinese (Simplified)"
            "Arabic" -> if (letters.any { it in "ٹڈڑںےہ" }) "Urdu" else "Arabic"
            else -> script
        }
    }

    private fun scriptOf(c: Char): String? = when (Character.UnicodeBlock.of(c)) {
        // Kana and kanji count together; kana present then decides Japanese over Chinese.
        Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS, Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A -> "Han"
        Character.UnicodeBlock.HANGUL_SYLLABLES, Character.UnicodeBlock.HANGUL_JAMO -> "Korean"
        Character.UnicodeBlock.ARABIC -> "Arabic"
        Character.UnicodeBlock.CYRILLIC -> "Russian"
        Character.UnicodeBlock.DEVANAGARI -> "Hindi"
        Character.UnicodeBlock.BENGALI -> "Bengali"
        Character.UnicodeBlock.GURMUKHI -> "Punjabi"
        Character.UnicodeBlock.GUJARATI -> "Gujarati"
        Character.UnicodeBlock.TAMIL -> "Tamil"
        Character.UnicodeBlock.TELUGU -> "Telugu"
        Character.UnicodeBlock.KANNADA -> "Kannada"
        Character.UnicodeBlock.MALAYALAM -> "Malayalam"
        Character.UnicodeBlock.THAI -> "Thai"
        Character.UnicodeBlock.GREEK -> "Greek"
        Character.UnicodeBlock.HEBREW -> "Hebrew"
        else -> null
    }
}
