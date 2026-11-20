package app.verbo.keyboard

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Remembers answers on the phone so a repeated request (the same Polish, the same translation, the same
 * reply, the same email draft) comes back at once and costs nothing.
 *
 * What is never kept: anything that used a tool or web search (sending to Slack, saving to Notion, the
 * weather, a lookup: those must run every time), and errors. A request that mentions a relative day
 * ("tomorrow", "kal", "Monday") is only reused on the same day. Asking again within two minutes of a
 * reused answer counts as "try again" and gets a fresh one. Entries last 30 days, at most [MAX] of them,
 * least recently used first out. The file stays on this phone and is not backed up or synced.
 */
object AnswerCache {
    const val MAX = 500
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val RETRY_MS = 2 * 60 * 1000L

    private var file: File? = null
    private var entries: LinkedHashMap<String, JSONObject>? = null
    private val lastServed = HashMap<String, Long>()

    private val RELATIVE_DAY = Regex(
        """\b(today|tonight|tomorrow|yesterday|now|this (morning|evening|week|weekend)|next (week|month)|monday|tuesday|wednesday|thursday|friday|saturday|sunday|aaj|kal|parso|abhi)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** The key for one request: the model, the full instructions and the text, with spacing tidied. */
    fun key(model: String, task: String?, input: String, web: Boolean, tools: List<String>): String {
        val text = input.trim().replace(Regex("""[ \t]+"""), " ")
        val day = if (RELATIVE_DAY.containsMatchIn(text) || RELATIVE_DAY.containsMatchIn(task.orEmpty()))
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) else ""
        val raw = listOf(model, task.orEmpty(), text, web.toString(), tools.sorted().joinToString(","), day).joinToString("\u0001")
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun get(store: Store, key: String, now: Long = System.currentTimeMillis()): ActionResult? {
        val map = load(store) ?: return null
        val e = map[key] ?: return null
        if (now - e.optLong("at") > TTL_MS) { map.remove(key); save(); return null }
        // The same request again right after a reused answer means "try again": drop it and ask afresh.
        val served = lastServed[key]
        if (served != null && now - served < RETRY_MS) { map.remove(key); lastServed.remove(key); save(); return null }
        lastServed[key] = now
        e.put("hits", e.optInt("hits") + 1)
        save()
        return result(e)
    }

    /** The money the cached answer would have cost again, for the savings counter. */
    @Synchronized
    fun costOf(store: Store, key: String): Double = load(store)?.get(key)?.optDouble("cost", 0.0) ?: 0.0

    @Synchronized
    fun put(store: Store, key: String, r: ActionResult, cost: Double, now: Long = System.currentTimeMillis()) {
        val map = load(store) ?: return
        if (r.text.isBlank()) return
        map[key] = JSONObject().put("at", now).put("cost", cost).put("t", r.text)
            .put("u", r.openUri ?: "").put("go", r.autoOpen)
            .put("links", JSONArray(r.links))
            .apply { r.map?.let { put("lat", it.first).put("lng", it.second) } }
        while (map.size > MAX) map.remove(map.keys.first())
        save()
    }

    @Synchronized
    fun size(store: Store): Int = load(store)?.size ?: 0

    @Synchronized
    fun clear(store: Store) {
        load(store)?.clear()
        lastServed.clear()
        save()
    }

    private fun result(e: JSONObject): ActionResult {
        val links = e.optJSONArray("links")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val map = if (e.has("lat")) e.optDouble("lat") to e.optDouble("lng") else null
        return ActionResult(e.optString("t"), e.optString("u").ifEmpty { null }, map, e.optBoolean("go"), links)
    }

    private fun load(store: Store): LinkedHashMap<String, JSONObject>? {
        entries?.let { return it }
        val dir = store.filesDir ?: return null
        val f = File(dir, "answer-cache.json")
        val map = LinkedHashMap<String, JSONObject>(64, 0.75f, true) // access order: least recently used first
        try {
            if (f.exists()) {
                val o = JSONObject(f.readText())
                o.keys().forEach { k -> o.optJSONObject(k)?.let { map[k] = it } }
            }
        } catch (e: Exception) { }
        file = f
        entries = map
        return map
    }

    private fun save() {
        val f = file ?: return
        val map = entries ?: return
        try {
            val o = JSONObject()
            map.forEach { (k, v) -> o.put(k, v) }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        } catch (e: Exception) { }
    }
}
