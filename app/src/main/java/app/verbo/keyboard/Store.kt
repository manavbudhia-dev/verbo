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
    /** A short name for the model in use, e.g. "Haiku 5.5". */
    val activeModelLabel get() = when {
        isCustom -> customModel.substringAfterLast('/')
        isOpenAi -> Ai.OPENAI_MODELS.firstOrNull { it.first == openaiModel }?.second ?: openaiModel
        else -> (Ai.MODELS.firstOrNull { it.first == model } ?: Ai.MODELS[0]).second.substringBefore(" —")
    }

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
    /** How far the shortcut wheel was last turned, in icons; it reopens there. */
    var wheelTurn: Int get() = prefs?.getInt("wheel_turn", 0) ?: 0
        set(v) { prefs?.edit()?.putInt("wheel_turn", v)?.apply() }
    /** How far the translate fan was last turned, in flags; it reopens there. */
    var langTurn: Int get() = prefs?.getInt("lang_turn", 0) ?: 0
        set(v) { prefs?.edit()?.putInt("lang_turn", v)?.apply() }
    var wheelOn: Boolean get() = bool("wheel_on", true); set(v) = put("wheel_on", v)
    /** Up to MAX_WHEEL shortcut ids: a built-in tool from WHEEL_TOOLS, or "skill:" followed by a skill's name. */
    var wheel: List<String>
        get() = str("wheel", DEFAULT_WHEEL.joinToString("\n")).split("\n").filter { it.isNotBlank() }.take(MAX_WHEEL)
        set(v) = put("wheel", v.take(MAX_WHEEL).joinToString("\n"))
    var languages: List<String>
        get() = str("languages", DEFAULT_LANGUAGES.joinToString("\n")).split("\n").filter { it in ALL_LANGUAGES }
        set(v) = put("languages", v.joinToString("\n"))
    /** How many flags the translate wheel shows: 5 or 6, chosen on the Languages page. */
    var wheelLanguageCount: Int
        get() = (prefs?.getInt("wheel_language_count", 5) ?: 5).coerceIn(5, 6)
        set(v) = putInt("wheel_language_count", v.coerceIn(5, 6))
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
    var serverUrl: String get() = moved(str("server_url")); set(v) = put("server_url", v)
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
        in APP_TOKENS -> token(field)
        "model" -> model; "provider" -> provider; "openaiModel" -> openaiModel
        "customBase" -> customBase; "customModel" -> customModel
        "webSearch" -> webSearch; "weather" -> weather
        "skills" -> skillsJson(skills)
        "triggers" -> triggersJson(triggers)
        "memory" -> memoryJson(memory)
        "shortcuts" -> shortcutsJson(shortcuts)
        "usage" -> usage
        "monthlyLimit" -> monthlyLimit
        "clips" -> JSONArray(clips)
        "keymap" -> KeyAction.json(keymap)
        "customApis" -> JSONArray().also { arr ->
            customApis.forEach { arr.put(JSONObject().put("name", it.name).put("description", it.description).put("url", it.url).put("headerName", it.headerName).put("headerValue", it.headerValue)) }
        }
        else -> when (field.removePrefix("keyboard.")) {
            "assistantOn" -> assistantOn; "numberRow" -> numberRow; "sound" -> sound; "haptic" -> haptic
            "autoCorrect" -> autoCorrect; "suggestions" -> suggestions; "wheelOn" -> wheelOn; "batterySaver" -> batterySaver; "keysOn" -> keysOn
            "agentMode" -> agentMode; "theme" -> theme; "keyShape" -> keyShape; "soundPack" -> soundPack; "hapticStrength" -> hapticStrength
            "heightPercent" -> heightPercent; "soundVolume" -> soundVolume; "customAccent" -> customAccent
            "wheel" -> JSONArray(wheel); "languages" -> JSONArray(languages); "wheelLanguageCount" -> wheelLanguageCount
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
        for ((field, pref) in APP_TOKENS) s(field, pref, o.optString(field))
        o.optJSONArray("customApis")?.let { s("customApis", "custom_apis", it.toString()) }
        o.optJSONArray("skills")?.let { s("skills", "skills", it.toString()) }
        o.optJSONArray("triggers")?.let { s("triggers", "triggers", it.toString()) }
        o.optJSONArray("memory")?.let { s("memory", "memory", it.toString()) }
        o.optJSONArray("shortcuts")?.let { s("shortcuts", "shortcuts", it.toString()) }
        o.optJSONArray("clips")?.let { s("clips", "clips", it.toString()) }
        o.optJSONArray("keymap")?.let { s("keymap", "keymap", it.toString()) }
        if (o.has("monthlyLimit") && "monthlyLimit" !in keep) e.putFloat("monthly_limit", o.optDouble("monthlyLimit", 0.0).toFloat().coerceIn(0f, 1000f))
        o.optJSONObject("keyboard")?.let { applyKeyboard(e, it, keep) }
        e.putBoolean("google_connected", o.optBoolean("googleConnected", false))
        // Older dashboards don't list services; their Google sign-in covers Meet only.
        val services = o.optJSONArray("googleServices")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            ?: if (o.optBoolean("googleConnected", false)) listOf("meet") else emptyList()
        e.putString("google_services", services.joinToString(","))
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
                    var items = (0 until v.length()).map { v.optString(it) }.filter { it in ok || (f.name == "wheel" && (it.startsWith("skill:") || it.startsWith("app:"))) }
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

    /** What the dashboard's Google sign-in allows: some of meet, calendar, gmail. */
    val googleServices: Set<String> get() = if (googleConnected) str("google_services").split(",").filter { it.isNotBlank() }.toSet() else emptySet()

    fun unpair() { prefs?.edit()?.remove("server_url")?.remove("device_token")?.remove("last_sync")?.remove("google_connected")?.remove("google_services")?.remove("pending")?.apply() }

    // connections
    var weather: Boolean get() = bool("weather", true); set(v) = put("weather", v)
    var notionToken: String get() = str("notion_token"); set(v) = put("notion_token", v.trim())
    /** Where saves land: a page or database id shared with the integration. Chosen on this phone, not synced. */
    var notionParent: String get() = str("notion_parent"); set(v) = put("notion_parent", v.trim())
    var notionParentIsDb: Boolean get() = bool("notion_parent_db", false); set(v) = put("notion_parent_db", v)
    /** The last thing shared into Verbo (WhatsApp & co.), waiting for the keyboard to act on it. */
    var inboxText: String get() = str("inbox_text"); set(v) = put("inbox_text", v)
    var inboxFile: String get() = str("inbox_file"); set(v) = put("inbox_file", v)
    var inboxName: String get() = str("inbox_name"); set(v) = put("inbox_name", v)
    var inboxMime: String get() = str("inbox_mime"); set(v) = put("inbox_mime", v)
    var inboxFrom: String get() = str("inbox_from"); set(v) = put("inbox_from", v)
    var inboxAt: Long get() = prefs?.getLong("inbox_at", 0L) ?: 0L
        set(v) { prefs?.edit()?.putLong("inbox_at", v)?.apply() }
    val inboxFresh: Boolean get() = inboxAt > 0 && System.currentTimeMillis() - inboxAt < 30 * 60 * 1000L &&
        (inboxText.isNotBlank() || inboxFile.isNotBlank())
    fun clearInbox() {
        prefs?.edit()?.remove("inbox_text")?.remove("inbox_file")?.remove("inbox_name")?.remove("inbox_mime")
            ?.remove("inbox_from")?.remove("inbox_at")?.apply()
    }
    /** Read incoming chat notifications so auto actions can run without copying (off until the user turns it on). */
    var watchMessages: Boolean get() = bool("watch_messages", false); set(v) = put("watch_messages", v)
    /** The last chat message a notification brought in, kept on the phone for the keyboard to act on. */
    var incomingText: String get() = str("incoming_text"); set(v) = put("incoming_text", v)
    var incomingFrom: String get() = str("incoming_from"); set(v) = put("incoming_from", v)
    var incomingApp: String get() = str("incoming_app"); set(v) = put("incoming_app", v)
    var incomingAt: Long get() = prefs?.getLong("incoming_at", 0L) ?: 0L
        set(v) { prefs?.edit()?.putLong("incoming_at", v)?.apply() }
    /** What an automatic action already made of [incomingText], so the keyboard can show it without asking again. */
    var incomingResult: String get() = str("incoming_result"); set(v) = put("incoming_result", v)
    var incomingSkill: String get() = str("incoming_skill"); set(v) = put("incoming_skill", v)
    /** Fresh for ten minutes: long enough to open the chat and answer. */
    val incomingFresh: Boolean get() = incomingAt > 0 && System.currentTimeMillis() - incomingAt < 10 * 60 * 1000L && incomingText.isNotBlank()
    fun clearIncoming() {
        prefs?.edit()?.remove("incoming_text")?.remove("incoming_from")?.remove("incoming_app")?.remove("incoming_at")
            ?.remove("incoming_result")?.remove("incoming_skill")?.apply()
    }
    var calendlyToken: String get() = str("calendly_token"); set(v) = put("calendly_token", v.trim())
    /** Slack, GitHub, Todoist, Linear and Discord, by dashboard field name (see [APP_TOKENS]). */
    fun token(field: String): String = APP_TOKENS[field]?.let { str(it) } ?: ""
    fun setToken(field: String, value: String) { APP_TOKENS[field]?.let { put(it, value.trim()) } }

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

    /** Hold a letter to run something, per app. Synced with the dashboard (config.keymap). */
    var keymap: List<KeyAction>
        get() = try { KeyAction.parse(JSONArray(str("keymap", "[]"))) } catch (e: Exception) { emptyList() }
        set(v) = put("keymap", KeyAction.json(v).toString())
    /** Off pauses every held-key action (skill keys too); held letters then just type. */
    var keysOn: Boolean get() = bool("keys_on", true); set(v) = put("keys_on", v)
    /** The first-run guide was finished or skipped. Kept on this phone only. */
    /** The user chose "Use without an account" on the sign-in screen, so it isn't shown again. */
    var signInSkipped: Boolean get() = bool("sign_in_skipped", false); set(v) { prefs?.edit()?.putBoolean("sign_in_skipped", v)?.apply() }
    var onboarded: Boolean get() = bool("onboarded", false); set(v) { prefs?.edit()?.putBoolean("onboarded", v)?.apply() }
    /** The keyboard's welcome banner was tapped; the bot intro has been offered once. */
    var welcomed: Boolean get() = bool("welcomed", false); set(v) { prefs?.edit()?.putBoolean("welcomed", v)?.apply() }
    /** Secret for the Google sign-in in progress: the dashboard only pairs the phone that holds it. */
    var authVerifier: String get() = str("auth_verifier"); set(v) { prefs?.edit()?.putString("auth_verifier", v)?.apply() }
    /** The dashboard that sign-in was started on. */
    var authServer: String get() = moved(str("auth_server")); set(v) { prefs?.edit()?.putString("auth_server", v)?.apply() }

    /** Suggest (or run) skills when a copied message matches a trigger. The matching happens on the phone; nothing is sent until a skill runs. */
    var triggersOn: Boolean get() = bool("triggers_on", true); set(v) = put("triggers_on", v)
    /** Facts the user told the keyboard. Synced with the dashboard. */
    var memory: List<Memory.Fact>
        get() = try { parseMemory(JSONArray(str("memory", "[]"))) } catch (e: Exception) { emptyList() }
        set(v) = put("memory", memoryJson(v).toString())

    /** The assistant chat, as a JSON array of turns; kept on this phone only. */
    fun chat(): String = str("assistant_chat", "[]")
    fun chat(json: String) { prefs?.edit()?.putString("assistant_chat", json)?.apply() }

    fun remember(fact: Memory.Fact) { memory = memory.filter { !it.label.equals(fact.label, true) || fact.label == "Note" } + fact }
    fun forget(label: String): Boolean {
        val before = memory
        memory = before.filter { !it.label.equals(label, true) && !it.label.equals(label.removePrefix("my ").trim(), true) }
        return memory.size != before.size
    }

    /** Text shortcuts: typing ";addr" and a space puts in the saved text. Synced with the dashboard. */
    var shortcuts: List<Pair<String, String>>
        get() = try { parseShortcuts(JSONArray(str("shortcuts", "[]"))) } catch (e: Exception) { emptyList() }
        set(v) = put("shortcuts", shortcutsJson(v).toString())

    /** The text for ";key": a shortcut first, then a remembered fact whose name matches (";upi" for "UPI"). */
    fun shortcutFor(key: String): String? {
        val k = key.lowercase()
        shortcuts.firstOrNull { it.first.equals(k, true) }?.let { return it.second }
        return memory.firstOrNull { f -> f.label.lowercase().filter { it.isLetterOrDigit() } == k }?.value
    }

    /** This month's requests, tokens and estimated cost, overall and per skill. Sent to the dashboard to show there. */
    val usage: JSONObject get() {
        val month = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date())
        val o = try { JSONObject(str("usage", "{}")) } catch (e: Exception) { JSONObject() }
        return if (o.optString("month") == month) o else JSONObject().put("month", month).put("requests", 0).put("inTokens", 0L)
            .put("outTokens", 0L).put("searches", 0L).put("cost", 0.0).put("skills", JSONObject())
    }

    @Synchronized
    fun recordUsage(label: String, model: String, input: Long, output: Long, searches: Long) {
        val u = usage
        val cost = Meter.cost(model, input, output, searches)
        u.put("requests", u.optInt("requests") + 1).put("inTokens", u.optLong("inTokens") + input).put("outTokens", u.optLong("outTokens") + output)
            .put("searches", u.optLong("searches") + searches).put("cost", u.optDouble("cost", 0.0) + cost)
        if (Meter.price(model) == null) u.put("unpriced", true)
        val name = label.removeSuffix("…").trim().take(40).ifEmpty { "Ask AI" }
        val skills = u.optJSONObject("skills") ?: JSONObject()
        val s = skills.optJSONObject(name) ?: JSONObject()
        skills.put(name, s.put("n", s.optInt("n") + 1).put("cost", s.optDouble("cost", 0.0) + cost))
        u.put("skills", skills)
        put("usage", u.toString())
    }

    /** Monthly spending cap in US dollars; 0 means no cap. Synced with the dashboard. */
    var monthlyLimit: Double
        get() = (prefs?.getFloat("monthly_limit", 0f) ?: 0f).toDouble()
        set(v) { val f = v.coerceIn(0.0, 1000.0).toFloat(); if ((prefs?.getFloat("monthly_limit", -1f) ?: -1f) != f) { prefs?.edit()?.putFloat("monthly_limit", f)?.apply(); changed("monthly_limit") } }

    val overLimit: Boolean get() = monthlyLimit > 0 && usage.optDouble("cost", 0.0) >= monthlyLimit

    /** Do small jobs (spelling, tone, one-language translation) on the phone when it can. */
    var onDevice: Boolean get() = bool("on_device", true); set(v) = put("on_device", v)

    /** Swipe typing, and Devanagari suggestions for Hinglish words. */
    var swipeTyping: Boolean get() = bool("swipe_typing", true); set(v) = put("swipe_typing", v)
    var hindiScript: Boolean get() = bool("hindi_script", false); set(v) = put("hindi_script", v)

    /** Recently copied texts, newest first, kept on the phone only. */
    val clipHistory: List<String> get() = str("clip_history").split("\u0001").filter { it.isNotBlank() }
    fun addClipHistory(text: String) {
        val t = text.trim().take(2000)
        if (t.isEmpty()) return
        prefs?.edit()?.putString("clip_history", (listOf(t) + clipHistory.filter { it != t }).take(20).joinToString("\u0001"))?.apply()
    }

    /** Learn how the user writes from messages they send. The messages stay on the phone; only a one-line summary is used. */
    var learnStyle: Boolean get() = bool("learn_style", true); set(v) = put("learn_style", v)
    val styleSamples: List<String> get() = str("style_samples").split("\u0001").filter { it.isNotBlank() }
    fun addStyleSample(text: String) {
        if (!learnStyle) return
        val t = text.trim()
        if (t.length < 2 || t.length > 600) return
        prefs?.edit()?.putString("style_samples", (styleSamples + t).takeLast(40).joinToString("\u0001"))?.apply()
    }
    fun clearStyle() { prefs?.edit()?.remove("style_samples")?.apply() }
    val styleLine: String? get() = if (learnStyle) Memory.describe(styleSamples) else null

    /** Offer three quick replies as chips when a message is copied (one small request per copy). */
    var smartReplies: Boolean get() = bool("smart_replies", true); set(v) = put("smart_replies", v)

    /** Open the acts in place of the keys when something new was copied and the field is empty. */
    var actsOnCopy: Boolean get() = bool("acts_on_copy", true); set(v) = put("acts_on_copy", v)
    var triggers: List<Trigger>
        get() = prefs?.getString("triggers", null)?.let { raw -> try { parseTriggers(JSONArray(raw)) } catch (e: Exception) { null } } ?: Trigger.DEFAULTS
        set(v) = put("triggers", triggersJson(v).toString())

    /** The instruction a trigger runs: the user's skill of that name, else a built-in one. */
    fun triggerSkill(t: Trigger): Skill? = skills.firstOrNull { it.name.equals(t.skill, ignoreCase = true) }
        ?: Trigger.BUILTIN[t.skill]?.let { Skill("⚡", t.skill, it, "write") }
        ?: Acts.BUILTIN.firstOrNull { it.name.equals(t.skill, ignoreCase = true) }?.let { Skill(it.emoji, it.name, it.instruction, "write") }

    /** Hearted cards, by id ("skill:Polish" or a trigger id); they sort to the top of their page. */
    var pins: List<String>
        get() = str("pins").split("\n").filter { it.isNotEmpty() }
        set(v) { put("pins", v.joinToString("\n")) }

    fun triggerId(t: Trigger) = "trigger:${t.kind}|${t.keyword}|${t.skill}"

    /** How many times a skill or auto action has run, shown on its card. */
    fun useCount(id: String): Int = try { JSONObject(str("use_counts", "{}")).optInt(id, 0) } catch (e: Exception) { 0 }
    fun recordUse(id: String) {
        val o = try { JSONObject(str("use_counts", "{}")) } catch (e: Exception) { JSONObject() }
        o.put(id, o.optInt(id, 0) + 1)
        put("use_counts", o.toString())
    }

    fun tools(): List<ToolSpec> = Connections.tools(weather, notionToken, calendlyToken, customApis, places, APP_TOKENS.keys.associateWith { token(it) },
        googleServices, notionParent, notionParentIsDb) { action, args -> Sync.google(this, action, args) }

    var skills: List<Skill>
        get() {
            val raw = prefs?.getString("skills", null) ?: return DEFAULT_SKILLS
            return try { parseSkills(JSONArray(raw)).map { when (it.instruction) {
                OLD_NOTION -> it.copy(instruction = NOTION_INSTRUCTION)
                OLD_PLACE -> it.copy(instruction = PLACE_INSTRUCTION) // used to describe the place; now it's a link to send
                else -> it
            } } } catch (e: Exception) { DEFAULT_SKILLS }
        }
        set(v) = put("skills", skillsJson(v).toString())

    fun resetSkills() { prefs?.edit()?.remove("skills")?.apply(); changed("skills") }

    // ---------- export / import (no keys or tokens) ----------

    fun exportJson(): String = JSONObject()
        .put("verbo", 1)
        .put("model", model).put("provider", provider).put("openaiModel", openaiModel).put("customBase", customBase).put("customModel", customModel).put("webSearch", webSearch).put("assistantOn", assistantOn).put("agentMode", agentMode)
        .put("theme", theme).put("customAccent", customAccent).put("heightPercent", heightPercent).put("wheelLanguageCount", wheelLanguageCount)
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
        if (o.has("wheelLanguageCount")) wheelLanguageCount = o.optInt("wheelLanguageCount", 5)
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
            KeyboardField("keysOn", "keys_on", 'b'),
            KeyboardField("agentMode", "agent_mode", 's') { listOf("auto", "ask", "manual") },
            KeyboardField("theme", "theme", 's') { THEMES }, KeyboardField("keyShape", "key_shape", 's') { SHAPES },
            KeyboardField("soundPack", "sound_pack", 's') { Feedback.PACKS.map { it.first } },
            KeyboardField("hapticStrength", "haptic_strength", 's') { Feedback.STRENGTHS.map { it.first } },
            KeyboardField("heightPercent", "height_percent", 'i', MIN_HEIGHT..MAX_HEIGHT),
            KeyboardField("soundVolume", "sound_volume", 'i', 5..100), KeyboardField("customAccent", "custom_accent", 'i'),
            KeyboardField("wheel", "wheel", 'l') { WHEEL_TOOLS.map { it.first } },
            KeyboardField("languages", "languages", 'l') { ALL_LANGUAGES },
            KeyboardField("wheelLanguageCount", "wheel_language_count", 'i', 5..6),
        )
        /** App tokens beyond Notion and Calendly: dashboard field -> local preference key. */
        val APP_TOKENS = linkedMapOf(
            "slackToken" to "slack_token", "githubToken" to "github_token", "todoistToken" to "todoist_token",
            "linearToken" to "linear_token", "discordWebhook" to "discord_webhook",
        )
        val SECRETS = setOf("apiKey", "openaiKey", "customKey", "notionToken", "calendlyToken") + APP_TOKENS.keys
        /** Local preference key -> dashboard field name. Keyboard settings are "keyboard.<name>". */
        val SYNCED: Map<String, String> by lazy {
            mapOf(
                "api_key" to "apiKey", "openai_key" to "openaiKey", "custom_key" to "customKey", "notion_token" to "notionToken", "calendly_token" to "calendlyToken",
                "model" to "model", "provider" to "provider", "openai_model" to "openaiModel", "custom_base" to "customBase", "custom_model" to "customModel",
                "web_search" to "webSearch", "weather" to "weather", "skills" to "skills", "custom_apis" to "customApis", "triggers" to "triggers", "memory" to "memory", "shortcuts" to "shortcuts", "clips" to "clips", "keymap" to "keymap", "usage" to "usage", "monthly_limit" to "monthlyLimit",
            ) + APP_TOKENS.entries.associate { (field, pref) -> pref to field } + KEYBOARD_FIELDS.associate { it.pref to "keyboard." + it.name }
        }

        /** The last four characters of a saved key, so it can be recognised without being shown (as on the dashboard). */
        fun keyHint(key: String) = if (key.length >= 8) key.takeLast(4) else ""

        /** The hosted dashboard, used by "Continue with Google" until the user pairs with another one. */
        const val DEFAULT_SERVER = "https://verbo.zealcoder.in"
        /** Where the dashboard used to live; a phone paired there follows it to [DEFAULT_SERVER]. */
        private val OLD_SERVERS = listOf("https://verbo-env-fpemontcg1.ap-south-1a.lb.nimbuz.tech")

        /** A saved address on the old host reads as the new one. */
        fun moved(url: String): String = if (OLD_SERVERS.any { url.trimEnd('/').equals(it, ignoreCase = true) }) DEFAULT_SERVER else url

        const val MIN_HEIGHT = 80
        const val MAX_HEIGHT = 130
        const val MAX_WHEEL = 6
        val WHEEL_TOOLS = listOf(
            "ai" to "Ask AI", "translate" to "Translate", "auto" to "Auto-translate", "rewrite" to "Rewrite", "meet" to "Meeting link", "voice" to "Voice", "clips" to "Clips",
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

        private val TRIGGER_KINDS = Cards.KINDS.map { it.first }
        fun parseTriggers(arr: JSONArray) = (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val kind = o.optString("kind").takeIf { k -> k in TRIGGER_KINDS } ?: return@mapNotNull null
            val skill = o.optString("skill").trim().takeIf { s -> s.isNotEmpty() } ?: return@mapNotNull null
            Trigger(kind, o.optString("keyword").trim().take(100), skill.take(40), o.optBoolean("auto", false))
        }.take(20)

        fun parseMemory(arr: JSONArray) = (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val label = o.optString("label").trim().take(60)
            val value = o.optString("value").trim().take(500)
            if (label.isEmpty() || value.isEmpty()) null else Memory.Fact(label, value)
        }.take(100)

        fun parseShortcuts(arr: JSONArray) = (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val key = o.optString("key").trim().removePrefix(";").lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(20)
            val text = o.optString("text").take(2000)
            if (key.isEmpty() || text.isBlank()) null else key to text
        }.take(100)

        fun shortcutsJson(list: List<Pair<String, String>>) = JSONArray().also { arr -> list.forEach { arr.put(JSONObject().put("key", it.first).put("text", it.second)) } }

        fun memoryJson(list: List<Memory.Fact>) = JSONArray().also { arr -> list.forEach { arr.put(JSONObject().put("label", it.label).put("value", it.value)) } }

        fun triggersJson(list: List<Trigger>) = JSONArray().also { arr ->
            list.forEach { arr.put(JSONObject().put("kind", it.kind).put("keyword", it.keyword).put("skill", it.skill).put("auto", it.auto)) }
        }

        private fun skillsJson(skills: List<Skill>) = JSONArray().also { arr ->
            skills.forEach {
                arr.put(JSONObject().put("emoji", it.emoji).put("name", it.name).put("instruction", it.instruction).put("category", it.category).put("key", it.key))
            }
        }

        /** The Notion skill before it opened the page itself; saved copies of it are upgraded. */
        private const val OLD_PLACE = "The text names a place. Find it with the place search and return its name, address and a Google Maps link, with the map marker line."
        /** Place: one tap turns a typed place into its name and a maps link, ready to send. */
        const val PLACE_INSTRUCTION = "The text names a place. Find it with the place search and reply with exactly two lines, ready to send in a chat: the place name with its area or city, then its Google Maps link https://www.google.com/maps/search/?api=1&query=NAME+ADDRESS (URL-encoded). Nothing else."
        private const val OLD_NOTION = "Search the user's Notion for the page the text describes and return its title and link. If Notion is not connected, say so in one line."
        const val NOTION_INSTRUCTION = "Find the Notion page that best matches the text, or the copied message if there is one, with notion_search: try the key names and topics, and search again with fewer words if nothing matches. Reply with exactly two lines: the page title, then Last edited: and when it was last edited in plain words (like Today at 10:42 AM), and end with [[open:PAGE_URL]]. If Notion is not connected, reply: Connect Notion in Verbo to search your pages, and end with [[open:https://www.notion.so]]."

        val DEFAULT_SKILLS = listOf(
            Skill("✨", "Polish", "Fix spelling, grammar and punctuation and smooth out awkward phrasing. Keep the meaning, the tone and the length.", "edit", "p"),
            Skill("💬", "Reply", "The text is either a message the user received or rough notes for an answer. Write a natural reply the user can send as their own.", "write", "r"),
            Skill("✂️", "Shorter", "Say the same thing in roughly half the words. Keep every fact and the tone.", "edit"),
            Skill("👔", "Formal", "Rewrite it so it suits a work email or a message to someone the user doesn't know well: polite, clear, no slang.", "edit"),
            Skill("😄", "Casual", "Rewrite it the way a relaxed, friendly person would text a friend. Contractions are fine; at most one emoji.", "edit"),
            Skill("🌍", "English", "Translate the text into natural English. If it is already English, leave the wording alone and only fix mistakes.", "translate", "t"),
            Skill("🧾", "Summarize", "Summarise the text as a few short key points, one per line. Keep names, numbers and dates.", "summarize", "s"),
            Skill("🔎", "Look up", "Treat the text as a question. Find the answer, searching the web if needed, and give it in one to three short sentences.", "write", "g"),
            Skill("📍", "Place", PLACE_INSTRUCTION, "write", "l"),
            Skill("👤", "Who is", "The text or the context names a person. Look them up on the web and reply with one item per line: their full name; a one-line headline with their role and organisation; then Current: their current role, Previous: their previous role, Education: where they studied; and last a LinkedIn people search link, https://www.linkedin.com/search/results/people/?keywords= followed by the name with spaces written as %20. Leave out any line you cannot verify. If you cannot tell who they are, say so in one line instead of guessing.", "write", "w"),
            Skill("🗒️", "Notion", NOTION_INSTRUCTION, "write", "n"),
            Skill("📧", "Email", "Turn the text into a clear email with a short subject. Return the email text, then a final [[open:mailto:?subject=...&body=...]] line with both URL-encoded so it opens as a ready draft in Gmail.", "write"),
            Skill("💼", "LinkedIn post", "Turn the text into a LinkedIn post: a strong first line, short paragraphs, no more than three hashtags at the end. Return the post, then a final [[open:https://www.linkedin.com/feed/?shareActive=true&text=...]] line with the post URL-encoded.", "write"),
            Skill("📋", "List", "Turn the text into a tidy list, one item per line, each starting with a dash. Keep the user's wording where you can.", "edit"),
        )
    }
}
