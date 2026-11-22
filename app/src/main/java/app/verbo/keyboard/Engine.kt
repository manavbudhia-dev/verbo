package app.verbo.keyboard

/**
 * Runs one assistant request on whichever service the user picked (Claude, OpenAI, or a compatible gateway).
 * Reads the settings once when created, on the main thread, so [run] can be called from a worker thread.
 */
class Engine(private val store: Store) {
    private val openAi = store.isOpenAi
    private val custom = store.isCustom
    private val base = store.customBase
    private val key = store.activeKey
    private val model = store.activeModel
    private val webAllowed = store.webSearch
    /** Facts and writing style, added to every request. */
    private val profile = Memory.profile(store.memory, store.styleLine)

    private val cacheOn = store.cacheOn

    /**
     * Blocking. [web] asks for web search where the service has it; [tools] are the user's connections.
     * A request asked before comes back from the answer cache at no cost; a new answer is kept for next
     * time unless it used a tool or a web search (those must run every time).
     */
    fun run(task: String?, input: String, web: Boolean, tools: List<ToolSpec>, cancelled: () -> Boolean = { false }, label: String = "Ask AI"): ActionResult {
        val full = if (profile.isEmpty()) task else (task ?: Ai.BAR_TASK) + profile
        val cacheKey = if (cacheOn) AnswerCache.key((if (custom) "custom:$base:" else if (openAi) "openai:" else "claude:") + model, full, input, web && webAllowed, tools.map { it.name }) else null
        if (cacheKey != null) {
            val saved = AnswerCache.costOf(store, cacheKey)
            AnswerCache.get(store, cacheKey)?.let { store.recordCacheHit(saved); return it }
        }
        var usedTool = false
        val watched = tools.map { t -> ToolSpec(t.name, t.description, t.params, t.required) { args -> usedTool = true; t.run(args) } }
        Meter.begin()
        var searches = 0L
        var cost = 0.0
        try {
            val result = when {
                custom -> ChatCompat.run(base, key, model, full, input, watched, cancelled)
                openAi -> OpenAi.run(key, model, web && webAllowed, full, input, watched, cancelled)
                else -> Ai.run(key, model, web && webAllowed, full, input, watched, cancelled)
            }
            val (i, o, s) = Meter.end().let { Triple(it[0], it[1], it[2]) }
            searches = s
            cost = Meter.cost(model, i, o, s)
            if (i + o > 0) store.recordUsage(label, model, i, o, s)
            // Only self-contained answers are kept: nothing that sent, saved or looked something up.
            if (cacheKey != null && !usedTool && searches == 0L && !cancelled()) AnswerCache.put(store, cacheKey, result, cost)
            return result
        } catch (e: Exception) {
            val (i, o, s) = Meter.end().let { Triple(it[0], it[1], it[2]) }
            if (i + o > 0) store.recordUsage(label, model, i, o, s)
            throw e
        }
    }
}
