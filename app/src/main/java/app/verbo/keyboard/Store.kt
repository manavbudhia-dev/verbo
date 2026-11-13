package app.verbo.keyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Skill(val emoji: String, val name: String, val instruction: String)

/** A user-defined API connection: a GET endpoint the assistant can call. */
data class CustomApi(val name: String, val description: String, val url: String, val headerName: String, val headerValue: String)

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

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(v) = prefs.edit().putString("server_url", v).apply()

    var deviceToken: String
        get() = prefs.getString("device_token", "") ?: ""
        set(v) = prefs.edit().putString("device_token", v).apply()

    var lastSync: Long
        get() = prefs.getLong("last_sync", 0)
        set(v) = prefs.edit().putLong("last_sync", v).apply()

    /** Replaces local settings with the dashboard's copy. While paired, the dashboard is the source of truth. */
    fun applyRemote(o: JSONObject) {
        prefs.edit()
            .putString("api_key", o.optString("apiKey"))
            .putString("model", o.optString("model", Ai.DEFAULT_MODEL))
            .putBoolean("web_search", o.optBoolean("webSearch", true))
            .putBoolean("weather", o.optBoolean("weather", true))
            .putString("notion_token", o.optString("notionToken"))
            .putString("calendly_token", o.optString("calendlyToken"))
            .putString("custom_apis", (o.optJSONArray("customApis") ?: JSONArray()).toString())
            .putString("skills", (o.optJSONArray("skills") ?: JSONArray()).toString())
            .putLong("last_sync", System.currentTimeMillis())
            .apply()
    }

    fun unpair() = prefs.edit().remove("server_url").remove("device_token").remove("last_sync").apply()

    var weather: Boolean
        get() = prefs.getBoolean("weather", true)
        set(v) = prefs.edit().putBoolean("weather", v).apply()

    var notionToken: String
        get() = prefs.getString("notion_token", "") ?: ""
        set(v) = prefs.edit().putString("notion_token", v.trim()).apply()

    var calendlyToken: String
        get() = prefs.getString("calendly_token", "") ?: ""
        set(v) = prefs.edit().putString("calendly_token", v.trim()).apply()

    var customApis: List<CustomApi>
        get() = try {
            val arr = JSONArray(prefs.getString("custom_apis", "[]"))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                CustomApi(o.getString("name"), o.optString("description"), o.getString("url"), o.optString("headerName"), o.optString("headerValue"))
            }
        } catch (e: Exception) {
            emptyList()
        }
        set(v) {
            val arr = JSONArray()
            v.forEach {
                arr.put(JSONObject().put("name", it.name).put("description", it.description).put("url", it.url)
                    .put("headerName", it.headerName).put("headerValue", it.headerValue))
            }
            prefs.edit().putString("custom_apis", arr.toString()).apply()
        }

    fun tools(): List<ToolSpec> = Connections.tools(weather, notionToken, calendlyToken, customApis)

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
