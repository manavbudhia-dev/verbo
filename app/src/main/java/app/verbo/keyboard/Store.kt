package app.verbo.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import org.json.JSONArray
import org.json.JSONObject

data class Skill(val emoji: String, val name: String, val instruction: String, val category: String = "edit")

/** A user-defined API connection: a GET endpoint the assistant can call. */
data class CustomApi(val name: String, val description: String, val url: String, val headerName: String, val headerValue: String)

/**
 * Settings and skills, kept in app-private preferences shared by the keyboard and the settings screen.
 * Right after a reboot, before the phone is first unlocked, that storage is not readable yet; the
 * keyboard then runs on defaults (plain typing) and builds a fresh Store once the phone is unlocked.
 */
class Store(context: Context) {
    val locked: Boolean = !(context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true)
    private val prefs: SharedPreferences? =
        if (locked) null else context.applicationContext.getSharedPreferences("verbo", Context.MODE_PRIVATE)

    private fun str(key: String, default: String = "") = prefs?.getString(key, default) ?: default
    private fun bool(key: String, default: Boolean) = prefs?.getBoolean(key, default) ?: default
    private fun put(key: String, value: String) { prefs?.edit()?.putString(key, value)?.apply() }
    private fun put(key: String, value: Boolean) { prefs?.edit()?.putBoolean(key, value)?.apply() }

    var apiKey: String get() = str("api_key"); set(v) = put("api_key", v.trim())
    var model: String get() = str("model", Ai.DEFAULT_MODEL); set(v) = put("model", v)
    var webSearch: Boolean get() = bool("web_search", true); set(v) = put("web_search", v)

    /** "anthropic" or "openai": which service runs actions. */
    var provider: String get() = str("provider", "anthropic"); set(v) = put("provider", v)
    var openaiKey: String get() = str("openai_key"); set(v) = put("openai_key", v.trim())
    var openaiModel: String get() = str("openai_model", Ai.DEFAULT_OPENAI_MODEL); set(v) = put("openai_model", v.trim())
    val isOpenAi get() = provider == "openai"
    /** The key and model of whichever provider is selected. */
    val activeKey get() = if (isOpenAi) openaiKey else apiKey
    val activeModel get() = if (isOpenAi) openaiModel else model
    val providerName get() = if (isOpenAi) "OpenAI" else "Claude"

    // keyboard behaviour
    var assistantOn: Boolean get() = bool("assistant_on", true); set(v) = put("assistant_on", v)
    /** "auto" applies results straight away, "ask" shows them first, "manual" only runs skill keys. */
    var agentMode: String get() = str("agent_mode", "auto"); set(v) = put("agent_mode", v)
    /** "system", "dark", "light", "amoled" or "custom". */
    var theme: String get() = str("theme", "system"); set(v) = put("theme", v)
    var customAccent: Int
        get() = prefs?.getInt("custom_accent", 0xFF22C55E.toInt()) ?: 0xFF22C55E.toInt()
        set(v) { prefs?.edit()?.putInt("custom_accent", v)?.apply() }
    var heightPercent: Int
        get() = (prefs?.getInt("height_percent", 100) ?: 100).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        set(v) { prefs?.edit()?.putInt("height_percent", v.coerceIn(MIN_HEIGHT, MAX_HEIGHT))?.apply() }
    /** "rounded", "square", "bubble" or "flat" (letters only, no key outlines). */
    var keyShape: String get() = str("key_shape", "rounded"); set(v) = put("key_shape", v)
    var numberRow: Boolean get() = bool("number_row", false); set(v) = put("number_row", v)
    var sound: Boolean get() = bool("sound", true); set(v) = put("sound", v)
    var haptic: Boolean get() = bool("haptic", true); set(v) = put("haptic", v)
    /** "light", "medium" or "strong". */
    var hapticStrength: String get() = str("haptic_strength", "medium"); set(v) = put("haptic_strength", v)
    /** "system" or one of the built-in effect packs, see Feedback.PACKS. */
    var soundPack: String get() = str("sound_pack", "system"); set(v) = put("sound_pack", v)
    var soundVolume: Int
        get() = (prefs?.getInt("sound_volume", 60) ?: 60).coerceIn(5, 100)
        set(v) { prefs?.edit()?.putInt("sound_volume", v.coerceIn(5, 100))?.apply() }
    /** While the phone's Battery Saver is on: no key sound or vibration, and no background dashboard sync. */
    var batterySaver: Boolean get() = bool("battery_saver", true); set(v) = put("battery_saver", v)
    var autoCorrect: Boolean get() = bool("auto_correct", true); set(v) = put("auto_correct", v)
    var suggestions: Boolean get() = bool("suggestions", true); set(v) = put("suggestions", v)

    // dashboard pairing
    var serverUrl: String get() = str("server_url"); set(v) = put("server_url", v)
    var deviceToken: String get() = str("device_token"); set(v) = put("device_token", v)
    var lastSync: Long
        get() = prefs?.getLong("last_sync", 0) ?: 0
        set(v) { prefs?.edit()?.putLong("last_sync", v)?.apply() }

    /** Replaces local settings with the dashboard's copy. While paired, the dashboard is the source of truth. */
    fun applyRemote(o: JSONObject) {
        prefs?.edit()
            ?.putString("api_key", o.optString("apiKey"))
            ?.putString("model", o.optString("model", Ai.DEFAULT_MODEL))
            ?.putString("provider", if (o.optString("provider") == "openai") "openai" else "anthropic")
            ?.putString("openai_key", o.optString("openaiKey"))
            ?.putString("openai_model", o.optString("openaiModel", Ai.DEFAULT_OPENAI_MODEL))
            ?.putBoolean("web_search", o.optBoolean("webSearch", true))
            ?.putBoolean("weather", o.optBoolean("weather", true))
            ?.putString("notion_token", o.optString("notionToken"))
            ?.putString("calendly_token", o.optString("calendlyToken"))
            ?.putString("custom_apis", (o.optJSONArray("customApis") ?: JSONArray()).toString())
            ?.putString("skills", (o.optJSONArray("skills") ?: JSONArray()).toString())
            ?.putLong("last_sync", System.currentTimeMillis())
            ?.apply()
    }

    fun unpair() { prefs?.edit()?.remove("server_url")?.remove("device_token")?.remove("last_sync")?.apply() }

    // connections
    var weather: Boolean get() = bool("weather", true); set(v) = put("weather", v)
    var notionToken: String get() = str("notion_token"); set(v) = put("notion_token", v.trim())
    var calendlyToken: String get() = str("calendly_token"); set(v) = put("calendly_token", v.trim())

    var customApis: List<CustomApi>
        get() = try {
            val arr = JSONArray(str("custom_apis", "[]"))
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
            put("custom_apis", arr.toString())
        }

    fun tools(): List<ToolSpec> = Connections.tools(weather, notionToken, calendlyToken, customApis)

    var skills: List<Skill>
        get() {
            val raw = prefs?.getString("skills", null) ?: return DEFAULT_SKILLS
            return try { parseSkills(JSONArray(raw)) } catch (e: Exception) { DEFAULT_SKILLS }
        }
        set(v) = put("skills", skillsJson(v).toString())

    fun resetSkills() { prefs?.edit()?.remove("skills")?.apply() }

    // ---------- export / import (no keys or tokens) ----------

    fun exportJson(): String = JSONObject()
        .put("verbo", 1)
        .put("model", model).put("provider", provider).put("openaiModel", openaiModel).put("webSearch", webSearch).put("assistantOn", assistantOn).put("agentMode", agentMode)
        .put("theme", theme).put("customAccent", customAccent).put("heightPercent", heightPercent)
        .put("keyShape", keyShape).put("numberRow", numberRow).put("sound", sound).put("haptic", haptic)
        .put("hapticStrength", hapticStrength).put("soundPack", soundPack).put("soundVolume", soundVolume).put("batterySaver", batterySaver).put("autoCorrect", autoCorrect).put("suggestions", suggestions)
        .put("weather", weather).put("skills", skillsJson(skills))
        .toString(2)

    /** Returns false if the text is not a Verbo settings file. */
    fun importJson(text: String): Boolean {
        val o = try { JSONObject(text) } catch (e: Exception) { return false }
        if (!o.has("verbo")) return false
        if (o.has("model") && Ai.MODELS.any { it.first == o.optString("model") }) model = o.getString("model")
        if (o.optString("provider") in listOf("anthropic", "openai")) provider = o.getString("provider")
        if (o.optString("openaiModel").matches(Regex("[A-Za-z0-9._:-]{1,80}"))) openaiModel = o.getString("openaiModel")
        if (o.has("webSearch")) webSearch = o.optBoolean("webSearch")
        if (o.has("assistantOn")) assistantOn = o.optBoolean("assistantOn")
        if (o.optString("agentMode") in listOf("auto", "ask", "manual")) agentMode = o.getString("agentMode")
        if (o.optString("theme") in THEMES) theme = o.getString("theme")
        if (o.has("customAccent")) customAccent = o.optInt("customAccent") or 0xFF000000.toInt()
        if (o.has("heightPercent")) heightPercent = o.optInt("heightPercent", 100)
        if (o.optString("keyShape") in SHAPES) keyShape = o.getString("keyShape")
        if (o.has("numberRow")) numberRow = o.optBoolean("numberRow")
        if (o.has("sound")) sound = o.optBoolean("sound")
        if (o.has("haptic")) haptic = o.optBoolean("haptic")
        if (Feedback.STRENGTHS.any { it.first == o.optString("hapticStrength") }) hapticStrength = o.getString("hapticStrength")
        if (Feedback.PACKS.any { it.first == o.optString("soundPack") }) soundPack = o.getString("soundPack")
        if (o.has("soundVolume")) soundVolume = o.optInt("soundVolume", 60)
        if (o.has("batterySaver")) batterySaver = o.optBoolean("batterySaver")
        if (o.has("autoCorrect")) autoCorrect = o.optBoolean("autoCorrect")
        if (o.has("suggestions")) suggestions = o.optBoolean("suggestions")
        if (o.has("weather")) weather = o.optBoolean("weather")
        o.optJSONArray("skills")?.let { arr -> try { skills = parseSkills(arr) } catch (e: Exception) { } }
        return true
    }

    companion object {
        const val MIN_HEIGHT = 80
        const val MAX_HEIGHT = 130
        val THEMES = listOf("system", "dark", "light", "amoled", "custom", "ocean", "rose", "forest", "sunset", "mint")
        val SHAPES = listOf("rounded", "square", "bubble", "flat")
        val CATEGORIES = listOf("write", "edit", "translate", "summarize")

        private fun parseSkills(arr: JSONArray) = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val category = o.optString("category").takeIf { c -> c in CATEGORIES } ?: "edit"
            Skill(o.optString("emoji"), o.getString("name"), o.getString("instruction"), category)
        }

        private fun skillsJson(skills: List<Skill>) = JSONArray().also { arr ->
            skills.forEach {
                arr.put(JSONObject().put("emoji", it.emoji).put("name", it.name).put("instruction", it.instruction).put("category", it.category))
            }
        }

        val DEFAULT_SKILLS = listOf(
            Skill("✨", "Polish", "Fix spelling, grammar and punctuation and smooth out awkward phrasing. Keep the meaning, the tone and the length.", "edit"),
            Skill("💬", "Reply", "The text is either a message the user received or rough notes for an answer. Write a natural reply the user can send as their own.", "write"),
            Skill("✂️", "Shorter", "Say the same thing in roughly half the words. Keep every fact and the tone.", "edit"),
            Skill("👔", "Formal", "Rewrite it so it suits a work email or a message to someone the user doesn't know well: polite, clear, no slang.", "edit"),
            Skill("😄", "Casual", "Rewrite it the way a relaxed, friendly person would text a friend. Contractions are fine; at most one emoji.", "edit"),
            Skill("🌍", "English", "Translate the text into natural English. If it is already English, leave the wording alone and only fix mistakes.", "translate"),
            Skill("🧾", "Summarize", "Summarise the text as a few short key points, one per line. Keep names, numbers and dates.", "summarize"),
            Skill("🔎", "Look up", "Treat the text as a question. Find the answer, searching the web if needed, and give it in one to three short sentences.", "write"),
            Skill("📋", "List", "Turn the text into a tidy list, one item per line, each starting with a dash. Keep the user's wording where you can.", "edit"),
        )
    }
}
