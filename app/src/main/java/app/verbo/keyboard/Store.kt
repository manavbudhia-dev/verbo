package app.verbo.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import org.json.JSONArray
import org.json.JSONObject

/** [key] is an optional letter: holding that key on the keyboard runs the skill. */
data class Skill(val emoji: String, val name: String, val instruction: String, val category: String = "edit", val key: String = "")

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
    // Each write that really changes a value also marks the matching dashboard field as pending.
    private fun put(key: String, value: String) { if (prefs?.contains(key) != true || str(key) != value) { prefs?.edit()?.putString(key, value)?.apply(); changed(key) } }
    private fun put(key: String, value: Boolean) { if (prefs?.contains(key) != true || bool(key, !value) != value) { prefs?.edit()?.putBoolean(key, value)?.apply(); changed(key) } }
    private fun putInt(key: String, value: Int) { if (prefs?.contains(key) != true || prefs.getInt(key, value.inv()) != value) { prefs?.edit()?.putInt(key, value)?.apply(); changed(key) } }

    var apiKey: String get() = str("api_key"); set(v) = put("api_key", v.trim())
    var model: String get() = str("model", Ai.DEFAULT_MODEL); set(v) = put("model", v)
    var webSearch: Boolean get() = bool("web_search", true); set(v) = put("web_search", v)

    /** "anthropic" or "openai": which service runs actions. */
    var provider: String get() = str("provider", "anthropic"); set(v) = put("provider", v)
    var openaiKey: String get() = str("openai_key"); set(v) = put("openai_key", v.trim())
    var openaiModel: String get() = str("openai_model", Ai.DEFAULT_OPENAI_MODEL); set(v) = put("openai_model", v.trim())
    val isOpenAi get() = provider == "openai"
    /** "custom": any gateway that speaks the OpenAI chat format, such as AI Router. */
    val isCustom get() = provider == "custom"
    var customBase: String get() = str("custom_base", ChatCompat.DEFAULT_BASE); set(v) = put("custom_base", v.trim())
    var customKey: String get() = str("custom_key"); set(v) = put("custom_key", v.trim())
    var customModel: String get() = str("custom_model", ChatCompat.DEFAULT_MODEL); set(v) = put("custom_model", v.trim())
    /** The key and model of whichever provider is selected. */
    val activeKey get() = when { isOpenAi -> openaiKey; isCustom -> customKey; else -> apiKey }
    val activeModel get() = when { isOpenAi -> openaiModel; isCustom -> customModel; else -> model }
    val providerName get() = when { isOpenAi -> "OpenAI"; isCustom -> "gateway"; else -> "Claude" }

    // keyboard behaviour
    var assistantOn: Boolean get() = bool("assistant_on", true); set(v) = put("assistant_on", v)
    /** "auto" applies results straight away, "ask" shows them first, "manual" only runs skill keys. */
    var agentMode: String get() = str("agent_mode", "auto"); set(v) = put("agent_mode", v)
    /** "system", "dark", "light", "amoled" or "custom". */
    var theme: String get() = str("theme", "system"); set(v) = put("theme", v)
    var customAccent: Int
        get() = prefs?.getInt("custom_accent", 0xFF22C55E.toInt()) ?: 0xFF22C55E.toInt()
        set(v) = putInt("custom_accent", v)
    var heightPercent: Int
        get() = (prefs?.getInt("height_percent", 100) ?: 100).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        set(v) = putInt("height_percent", v.coerceIn(MIN_HEIGHT, MAX_HEIGHT))
    /** Key style, one of SHAPES. "raised" is a 3D key that sinks when pressed; "flat" shows letters only. */
    var keyShape: String get() = str("key_shape", "raised"); set(v) = put("key_shape", v)
    var numberRow: Boolean get() = bool("number_row", false); set(v) = put("number_row", v)
    var sound: Boolean get() = bool("sound", true); set(v) = put("sound", v)
    var haptic: Boolean get() = bool("haptic", true); set(v) = put("haptic", v)
    /** "light", "medium" or "strong". */
    var hapticStrength: String get() = str("haptic_strength", "medium"); set(v) = put("haptic_strength", v)
    /** "system" or one of the built-in effect packs, see Feedback.PACKS. */
    var soundPack: String get() = str("sound_pack", "system"); set(v) = put("sound_pack", v)
    var soundVolume: Int
        get() = (prefs?.getInt("sound_volume", 60) ?: 60).coerceIn(5, 100)
        set(v) = putInt("sound_volume", v.coerceIn(5, 100))
    /** While the phone's Battery Saver is on: no key sound or vibration, and no background dashboard sync. */
    var batterySaver: Boolean get() = bool("battery_saver", true); set(v) = put("battery_saver", v)
    var autoCorrect: Boolean get() = bool("auto_correct", true); set(v) = put("auto_correct", v)
    var suggestions: Boolean get() = bool("suggestions", true); set(v) = put("suggestions", v)

    // the wheel, translate languages and saved clips
    /** Holding the bar opens the shortcut wheel; when off it runs the assistant straight away. */
    var wheelOn: Boolean get() = bool("wheel_on", true); set(v) = put("wheel_on", v)
    /** Up to MAX_WHEEL shortcut ids: a built-in tool from WHEEL_TOOLS, or "skill:" followed by a skill's name. */
    var wheel: List<String>
        get() = str("wheel", DEFAULT_WHEEL.joinToString("\n")).split("\n").filter { it.isNotBlank() }.take(MAX_WHEEL)
        set(v) = put("wheel", v.take(MAX_WHEEL).joinToString("\n"))
    var languages: List<String>
        get() = str("languages", DEFAULT_LANGUAGES.joinToString("\n")).split("\n").filter { it in ALL_LANGUAGES }
        set(v) = put("languages", v.joinToString("\n"))
    var clips: List<String>
        get() = try { JSONArray(str("clips", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (e: Exception) { emptyList() }
        set(v) = put("clips", JSONArray(v.take(30)).toString())

    /** Auto-translate is remembered per app, so it stays on in the chat app where it was switched on. */
    fun autoTranslate(app: String?): String? {
        if (app.isNullOrEmpty()) return null
        return try { JSONObject(str("auto_translate", "{}")).optString(app).takeIf { it in ALL_LANGUAGES } } catch (e: Exception) { null }
    }

    fun setAutoTranslate(app: String?, language: String?) {
        if (app.isNullOrEmpty()) return
        val all = try { JSONObject(str("auto_translate", "{}")) } catch (e: Exception) { JSONObject() }
        if (language == null) all.remove(app) else all.put(app, language)
        put("auto_translate", all.toString())
    }

    // dashboard pairing
    var serverUrl: String get() = str("server_url"); set(v) = put("server_url", v)
    var deviceToken: String get() = str("device_token"); set(v) = put("device_token", v)
    var lastSync: Long
        get() = prefs?.getLong("last_sync", 0) ?: 0
        set(v) { prefs?.edit()?.putLong("last_sync", v)?.apply() }

    // ---------- two-way sync ----------
    // A change made on the phone while paired is recorded as pending. The next sync sends pending fields
    // up first (PUT /api/device/config) and the download never overwrites a field that is still pending,
    // so a key typed on the phone can no longer be replaced by the dashboard's older copy.

    /** Dashboard field names changed on this phone and not yet accepted by the dashboard. */
    val pending: Set<String> get() = str("pending").split("\n").filter { it.isNotBlank() }.toSet()

    private fun setPending(fields: Set<String>) { prefs?.edit()?.putString("pending", fields.joinToString("\n"))?.apply() }

    private fun changed(prefKey: String) {
        val field = SYNCED[prefKey] ?: return
        if (deviceToken.isEmpty()) return
        markPending(setOf(field))
        if (field in SECRETS) Sync.soon(this) // a key saved on the phone goes up straight away
    }

    fun markPending(fields: Set<String>) { if (fields.isNotEmpty()) setPending(pending + fields) }

    /** The current value of a dashboard field, in the form the dashboard expects. */
    fun fieldValue(field: String): Any = when (field) {
        "apiKey" -> apiKey; "openaiKey" -> openaiKey; "customKey" -> customKey
        "notionToken" -> notionToken; "calendlyToken" -> calendlyToken
        "model" -> model; "provider" -> provider; "openaiModel" -> openaiModel
        "customBase" -> customBase; "customModel" -> customModel
        "webSearch" -> webSearch; "weather" -> weather
        "skills" -> skillsJson(skills)
        "customApis" -> JSONArray().also { arr ->
            customApis.forEach { arr.put(JSONObject().put("name", it.name).put("description", it.description).put("url", it.url).put("headerName", it.headerName).put("headerValue", it.headerValue)) }
        }
        else -> when (field.removePrefix("keyboard.")) {
            "assistantOn" -> assistantOn; "numberRow" -> numberRow; "sound" -> sound; "haptic" -> haptic
            "autoCorrect" -> autoCorrect; "suggestions" -> suggestions; "wheelOn" -> wheelOn; "batterySaver" -> batterySaver
            "agentMode" -> agentMode; "theme" -> theme; "keyShape" -> keyShape; "soundPack" -> soundPack; "hapticStrength" -> hapticStrength
            "heightPercent" -> heightPercent; "soundVolume" -> soundVolume; "customAccent" -> customAccent
            "wheel" -> JSONArray(wheel); "languages" -> JSONArray(languages)
            else -> JSONObject.NULL
        }
    }

    /** The body for PUT /api/device/config with every pending field, and a snapshot of what was sent. */
    fun pendingUpload(): Pair<JSONObject, Map<String, String>> {
        val body = JSONObject()
        val sent = HashMap<String, String>()
        for (field in pending) {
            val value = fieldValue(field)
            if (value == JSONObject.NULL) continue
            sent[field] = value.toString()
            if (field.startsWith("keyboard.")) {
                val kb = body.optJSONObject("keyboard") ?: JSONObject().also { body.put("keyboard", it) }
                kb.put(field.removePrefix("keyboard."), value)
            } else body.put(field, value)
        }
        return body to sent
    }

    /** After an upload: clears the fields the dashboard accepted, unless they were changed again meanwhile. */
    fun settle(sent: Map<String, String>) {
        setPending(pending.filter { f -> f in SYNCED.values && sent[f] != fieldValue(f).toString() }.toSet())
    }

    /** Takes the dashboard's copy of every synced field except those in [keep]: changes the dashboard hasn't accepted yet. */
    fun applyRemote(o: JSONObject, keep: Set<String> = pending) {
        val e = prefs?.edit() ?: return
        fun s(field: String, pref: String, value: String) { if (field !in keep) e.putString(pref, value) }
        fun b(field: String, pref: String, value: Boolean) { if (field !in keep) e.putBoolean(pref, value) }
        s("apiKey", "api_key", o.optString("apiKey"))
        s("model", "model", o.optString("model", Ai.DEFAULT_MODEL))
        s("provider", "provider", o.optString("provider").takeIf { it == "openai" || it == "custom" } ?: "anthropic")
        s("customBase", "custom_base", o.optString("customBase", ChatCompat.DEFAULT_BASE))
        s("customKey", "custom_key", o.optString("customKey"))
        s("customModel", "custom_model", o.optString("customModel", ChatCompat.DEFAULT_MODEL))
        s("openaiKey", "openai_key", o.optString("openaiKey"))
        s("openaiModel", "openai_model", o.optString("openaiModel", Ai.DEFAULT_OPENAI_MODEL))
        b("webSearch", "web_search", o.optBoolean("webSearch", true))
        b("weather", "weather", o.optBoolean("weather", true))
        s("notionToken", "notion_token", o.optString("notionToken"))
        s("calendlyToken", "calendly_token", o.optString("calendlyToken"))
        o.optJSONArray("customApis")?.let { s("customApis", "custom_apis", it.toString()) }
        o.optJSONArray("skills")?.let { s("skills", "skills", it.toString()) }
        o.optJSONObject("keyboard")?.let { applyKeyboard(e, it, keep) }
        e.putBoolean("google_connected", o.optBoolean("googleConnected", false))
        e.putLong("last_sync", System.currentTimeMillis())
        e.apply()
    }

    /** Keyboard settings from the dashboard (config.keyboard), checked the same way as an imported file. */
    private fun applyKeyboard(e: SharedPreferences.Editor, kb: JSONObject, keep: Set<String>) {
        for (f in KEYBOARD_FIELDS) {
            if (!kb.has(f.name) || "keyboard.${f.name}" in keep) continue
            val v = kb.get(f.name)
            when (f.kind) {
                'b' -> if (v is Boolean) e.putBoolean(f.pref, v)
                's' -> if (v is String && v in f.allowed()) e.putString(f.pref, v)
                'i' -> if (v is Number) e.putInt(f.pref, v.toInt().let { n -> f.range?.let { n.coerceIn(it) } ?: n })
                'l' -> if (v is JSONArray) {
                    val ok = f.allowed()
                    var items = (0 until v.length()).map { v.optString(it) }.filter { it in ok || (f.name == "wheel" && it.startsWith("skill:")) }
                    if (f.name == "wheel") items = items.take(MAX_WHEEL)
                    e.putString(f.pref, items.joinToString("\n"))
                }
            }
        }
    }

    /** Fields to send when pairing: keys the phone has that the dashboard lacks, and the phone's service if the dashboard's has no key. */
    fun missingOnDashboard(remote: JSONObject): Set<String> {
        val out = SECRETS.filter { (fieldValue(it) as String).isNotEmpty() && remote.optString(it).isEmpty() }.toMutableSet()
        val remoteActiveKey = when (remote.optString("provider")) { "openai" -> "openaiKey"; "custom" -> "customKey"; else -> "apiKey" }
        val localActiveKey = when { isOpenAi -> "openaiKey"; isCustom -> "customKey"; else -> "apiKey" }
        if (remote.optString(remoteActiveKey).isEmpty() && localActiveKey in out) {
            out += "provider"
            out += when { isOpenAi -> "openaiModel"; isCustom -> "customModel"; else -> "model" }
            if (isCustom) out += "customBase"
        }
        return out
    }

    /** True when the paired dashboard has a Google account connected, so Meet links can be made. */
    val googleConnected get() = deviceToken.isNotEmpty() && bool("google_connected", false)

    fun unpair() { prefs?.edit()?.remove("server_url")?.remove("device_token")?.remove("last_sync")?.remove("google_connected")?.remove("pending")?.apply() }

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

    var places: Boolean get() = bool("places", true); set(v) = put("places", v)

    fun tools(): List<ToolSpec> = Connections.tools(weather, notionToken, calendlyToken, customApis, places)

    var skills: List<Skill>
        get() {
            val raw = prefs?.getString("skills", null) ?: return DEFAULT_SKILLS
            return try { parseSkills(JSONArray(raw)) } catch (e: Exception) { DEFAULT_SKILLS }
        }
        set(v) = put("skills", skillsJson(v).toString())

    fun resetSkills() { prefs?.edit()?.remove("skills")?.apply(); changed("skills") }

    // ---------- export / import (no keys or tokens) ----------

    fun exportJson(): String = JSONObject()
        .put("verbo", 1)
        .put("model", model).put("provider", provider).put("openaiModel", openaiModel).put("customBase", customBase).put("customModel", customModel).put("webSearch", webSearch).put("assistantOn", assistantOn).put("agentMode", agentMode)
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
        if (o.optString("provider") in listOf("anthropic", "openai", "custom")) provider = o.getString("provider")
        if (o.optString("customBase").startsWith("https://")) customBase = o.getString("customBase")
        if (o.optString("customModel").matches(Regex("[A-Za-z0-9._:/-]{1,100}"))) customModel = o.getString("customModel")
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

    /** A keyboard setting kept in config.keyboard on the dashboard; see KEYBOARD_FIELDS in dashboard/server.js. */
    class KeyboardField(val name: String, val pref: String, val kind: Char, val range: IntRange? = null, val allowed: () -> List<String> = { emptyList() })

    companion object {
        val KEYBOARD_FIELDS = listOf(
            KeyboardField("assistantOn", "assistant_on", 'b'), KeyboardField("numberRow", "number_row", 'b'),
            KeyboardField("sound", "sound", 'b'), KeyboardField("haptic", "haptic", 'b'),
            KeyboardField("autoCorrect", "auto_correct", 'b'), KeyboardField("suggestions", "suggestions", 'b'),
            KeyboardField("wheelOn", "wheel_on", 'b'), KeyboardField("batterySaver", "battery_saver", 'b'),
            KeyboardField("agentMode", "agent_mode", 's') { listOf("auto", "ask", "manual") },
            KeyboardField("theme", "theme", 's') { THEMES }, KeyboardField("keyShape", "key_shape", 's') { SHAPES },
            KeyboardField("soundPack", "sound_pack", 's') { Feedback.PACKS.map { it.first } },
            KeyboardField("hapticStrength", "haptic_strength", 's') { Feedback.STRENGTHS.map { it.first } },
            KeyboardField("heightPercent", "height_percent", 'i', MIN_HEIGHT..MAX_HEIGHT),
            KeyboardField("soundVolume", "sound_volume", 'i', 5..100), KeyboardField("customAccent", "custom_accent", 'i'),
            KeyboardField("wheel", "wheel", 'l') { WHEEL_TOOLS.map { it.first } },
            KeyboardField("languages", "languages", 'l') { ALL_LANGUAGES },
        )
        val SECRETS = setOf("apiKey", "openaiKey", "customKey", "notionToken", "calendlyToken")
        /** Local preference key -> dashboard field name. Keyboard settings are "keyboard.<name>". */
        val SYNCED: Map<String, String> by lazy {
            mapOf(
                "api_key" to "apiKey", "openai_key" to "openaiKey", "custom_key" to "customKey", "notion_token" to "notionToken", "calendly_token" to "calendlyToken",
                "model" to "model", "provider" to "provider", "openai_model" to "openaiModel", "custom_base" to "customBase", "custom_model" to "customModel",
                "web_search" to "webSearch", "weather" to "weather", "skills" to "skills", "custom_apis" to "customApis",
            ) + KEYBOARD_FIELDS.associate { it.pref to "keyboard." + it.name }
        }

        const val MIN_HEIGHT = 80
        const val MAX_HEIGHT = 130
        const val MAX_WHEEL = 6
        val WHEEL_TOOLS = listOf(
            "ai" to "Ask AI", "translate" to "Translate", "rewrite" to "Rewrite", "meet" to "Meeting link", "voice" to "Voice", "clips" to "Clips",
        )
        val DEFAULT_WHEEL = listOf("translate", "rewrite", "ai", "meet", "voice", "clips")
        val DEFAULT_LANGUAGES = listOf("English", "Hindi", "Spanish", "French", "Chinese (Simplified)")
        val ALL_LANGUAGES = listOf(
            "English", "Hindi", "Spanish", "French", "Chinese (Simplified)", "Arabic", "Bengali", "Portuguese", "Russian", "Japanese",
            "German", "Korean", "Italian", "Turkish", "Indonesian", "Urdu", "Marathi", "Tamil", "Telugu", "Gujarati", "Punjabi", "Kannada", "Malayalam",
            "Vietnamese", "Thai", "Dutch", "Polish", "Swahili",
        )
        val THEMES = listOf("system", "dark", "light", "amoled", "custom", "ocean", "rose", "forest", "sunset", "mint")
        val SHAPES = listOf("raised", "rounded", "square", "bubble", "glass", "outline", "flat")
        val CATEGORIES = listOf("write", "edit", "translate", "summarize")

        private fun parseSkills(arr: JSONArray) = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val category = o.optString("category").takeIf { c -> c in CATEGORIES } ?: "edit"
            val key = o.optString("key").lowercase().takeIf { k -> k.length == 1 && k[0] in 'a'..'z' } ?: ""
            Skill(o.optString("emoji"), o.getString("name"), o.getString("instruction"), category, key)
        }

        private fun skillsJson(skills: List<Skill>) = JSONArray().also { arr ->
            skills.forEach {
                arr.put(JSONObject().put("emoji", it.emoji).put("name", it.name).put("instruction", it.instruction).put("category", it.category).put("key", it.key))
            }
        }

        val DEFAULT_SKILLS = listOf(
            Skill("✨", "Polish", "Fix spelling, grammar and punctuation and smooth out awkward phrasing. Keep the meaning, the tone and the length.", "edit", "p"),
            Skill("💬", "Reply", "The text is either a message the user received or rough notes for an answer. Write a natural reply the user can send as their own.", "write", "r"),
            Skill("✂️", "Shorter", "Say the same thing in roughly half the words. Keep every fact and the tone.", "edit"),
            Skill("👔", "Formal", "Rewrite it so it suits a work email or a message to someone the user doesn't know well: polite, clear, no slang.", "edit"),
            Skill("😄", "Casual", "Rewrite it the way a relaxed, friendly person would text a friend. Contractions are fine; at most one emoji.", "edit"),
            Skill("🌍", "English", "Translate the text into natural English. If it is already English, leave the wording alone and only fix mistakes.", "translate", "t"),
            Skill("🧾", "Summarize", "Summarise the text as a few short key points, one per line. Keep names, numbers and dates.", "summarize", "s"),
            Skill("🔎", "Look up", "Treat the text as a question. Find the answer, searching the web if needed, and give it in one to three short sentences.", "write", "g"),
            Skill("📍", "Place", "The text names a place. Find it with the place search and return its name, address and a Google Maps link, with the map marker line.", "write", "l"),
            Skill("👤", "Who is", "The text or the context names a person. Look them up on the web and return their name, then current role, previous role and education, one per line. If you cannot verify who they are, say so instead of guessing.", "write", "w"),
            Skill("🗒️", "Notion", "Search the user's Notion for the page the text describes and return its title and link. If Notion is not connected, say so in one line.", "write", "n"),
            Skill("📋", "List", "Turn the text into a tidy list, one item per line, each starting with a dash. Keep the user's wording where you can.", "edit"),
        )
    }
}
