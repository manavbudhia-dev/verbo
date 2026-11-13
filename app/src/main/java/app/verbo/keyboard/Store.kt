package app.verbo.keyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Skill(val emoji: String, val name: String, val instruction: String)

/** Settings and skills, kept in app-private preferences shared by the keyboard and the settings screen. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("verbo", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", Ai.DEFAULT_MODEL) ?: Ai.DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v).apply()

    var webSearch: Boolean
        get() = prefs.getBoolean("web_search", true)
        set(v) = prefs.edit().putBoolean("web_search", v).apply()

    var skills: List<Skill>
        get() {
            val raw = prefs.getString("skills", null) ?: return DEFAULT_SKILLS
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Skill(o.optString("emoji"), o.getString("name"), o.getString("instruction"))
                }
            } catch (e: Exception) {
                DEFAULT_SKILLS
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("emoji", it.emoji).put("name", it.name).put("instruction", it.instruction)) }
            prefs.edit().putString("skills", arr.toString()).apply()
        }

    fun resetSkills() = prefs.edit().remove("skills").apply()

    companion object {
        val DEFAULT_SKILLS = listOf(
            Skill("✨", "Polish", "Fix spelling, grammar and punctuation and smooth out awkward phrasing. Keep the meaning, the tone and the length."),
            Skill("💬", "Reply", "The text is either a message the user received or rough notes for an answer. Write a natural reply the user can send as their own."),
            Skill("✂️", "Shorter", "Say the same thing in roughly half the words. Keep every fact and the tone."),
            Skill("👔", "Formal", "Rewrite it so it suits a work email or a message to someone the user doesn't know well: polite, clear, no slang."),
            Skill("😄", "Casual", "Rewrite it the way a relaxed, friendly person would text a friend. Contractions are fine; at most one emoji."),
            Skill("🌍", "English", "Translate the text into natural English. If it is already English, leave the wording alone and only fix mistakes."),
            Skill("🔎", "Look up", "Treat the text as a question. Find the answer, searching the web if needed, and give it in one to three short sentences."),
            Skill("📋", "List", "Turn the text into a tidy list, one item per line, each starting with a dash. Keep the user's wording where you can."),
        )
    }
}
