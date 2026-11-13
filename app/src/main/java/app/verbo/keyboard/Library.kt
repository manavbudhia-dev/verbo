package app.verbo.keyboard

/** A ready-made skill the user can add from the library. [group] is the Skill category; [key] is a suggested hold key. */
class LibrarySkill(val shelf: String, val emoji: String, val name: String, val about: String, val instruction: String, val group: String, val key: String = "")

object Library {
    val SHELVES = listOf("Featured", "Work", "Social", "Fun", "Languages")

    val SKILLS = listOf(
        LibrarySkill("Featured", "🎯", "Bottom line first", "Leads with the conclusion, then the reasons", "Rewrite the text so the conclusion or request comes first in one sentence, followed by at most three short supporting points.", "edit", "b"),
        LibrarySkill("Featured", "🧩", "Expand", "Turns a few words into a full message", "The text is a short note. Expand it into a complete, natural message that says the same thing, without adding facts that are not implied.", "write", "e"),
        LibrarySkill("Featured", "🪄", "Fix and send-ready", "Cleans up a rushed message", "Fix typos, grammar and missing words, split run-on sentences, and keep the user's voice. Do not make it more formal.", "edit"),
        LibrarySkill("Featured", "🧭", "Understand this", "Explains a copied message in plain words", "Explain what the context message means in two or three plain sentences in the user's language, including any slang, abbreviations or implied request.", "write", "u"),
        LibrarySkill("Featured", "🙋", "Suggest replies", "Three ways to answer a copied message", "Offer three different short replies to the context message: one warm, one neutral, one brief. Put a title on the first line and one reply per line after it.", "write"),

        LibrarySkill("Work", "📧", "Email", "Full email with subject, opens as a draft", "Turn the text into a complete email with a greeting and sign-off. Return the email body, and add a mailto link carrying a short subject and the body so it opens as a draft.", "write"),
        LibrarySkill("Work", "🗓️", "Meeting notes", "Notes into decisions and actions", "Turn the text into tidy meeting notes: a one-line summary, then Decisions and Action items (with owners when named), one per line.", "summarize", "m"),
        LibrarySkill("Work", "📣", "Status update", "A clear update for a team chat", "Rewrite the text as a short status update: what is done, what is in progress, and what is blocked, each on its own line.", "edit"),
        LibrarySkill("Work", "🙏", "Polite no", "Declines without burning bridges", "Rewrite the text as a courteous refusal: thank them, decline clearly in one sentence, and offer an alternative if the text suggests one.", "edit"),
        LibrarySkill("Work", "💼", "LinkedIn post", "A post in a professional voice", "Turn the text into a LinkedIn post: a strong first line, short paragraphs, no hashtags unless the text has them, and no clichés.", "write"),
        LibrarySkill("Work", "📅", "Event", "Makes a calendar event from a sentence", "The text describes an event. Return a one-line summary of it and add a Google Calendar link with the title, start and end time, and place.", "write"),

        LibrarySkill("Social", "🟢", "WhatsApp", "A natural chat message with a send link", "Rewrite the text as a short, natural WhatsApp message and add a wa.me link carrying it.", "write"),
        LibrarySkill("Social", "🐦", "Post for X", "Fits a post into the limit", "Rewrite the text as a post of at most 280 characters with a clear hook, and add the X post link carrying it.", "write", "x"),
        LibrarySkill("Social", "🎉", "Wish them", "Birthday, festival or congratulations message", "Write a warm, personal greeting for the occasion the text mentions, two or three sentences, in the user's language.", "write"),
        LibrarySkill("Social", "💐", "Say sorry", "A sincere apology", "Rewrite the text as a sincere apology: own the mistake plainly, say what will change, and keep it short.", "edit"),
        LibrarySkill("Social", "😊", "Add emoji", "Sprinkles fitting emoji", "Add a few fitting emoji to the text without changing any words. At most one per sentence.", "edit"),

        LibrarySkill("Fun", "🏴‍☠️", "Pirate", "Arr", "Rewrite the text the way a cheerful pirate would say it, keeping the meaning.", "edit"),
        LibrarySkill("Fun", "🎭", "Shakespeare", "Verily", "Rewrite the text in mock Shakespearean English, keeping the meaning and the length.", "edit"),
        LibrarySkill("Fun", "✍️", "Two-line poem", "A couplet about anything", "Turn the text into a rhyming two-line poem in the same language.", "write"),
        LibrarySkill("Fun", "🔤", "Emoji only", "Says it with emoji", "Express the text using only emoji, no words.", "edit"),

        LibrarySkill("Languages", "🇮🇳", "Hinglish", "Hindi in Latin letters, the way people text", "Rewrite the text in casual Hinglish (Hindi written in Latin letters mixed with common English words), the way friends text in India.", "translate", "h"),
        LibrarySkill("Languages", "अ", "Hindi", "Into Hindi (Devanagari)", "Translate the text into natural Hindi written in Devanagari.", "translate"),
        LibrarySkill("Languages", "🌐", "Back to English", "Translates anything into English", "Translate the text into natural English. If it is already English, only fix mistakes.", "translate"),
        LibrarySkill("Languages", "🗣️", "Say it like a local", "Sounds native, not translated", "Rewrite the text so it sounds like a native speaker of its language wrote it: idiomatic and natural, same meaning.", "edit"),
    )
}
