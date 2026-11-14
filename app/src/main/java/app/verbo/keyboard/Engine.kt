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

    /** Blocking. [web] asks for web search where the service has it; [tools] are the user's connections. */
    fun run(task: String?, input: String, web: Boolean, tools: List<ToolSpec>, cancelled: () -> Boolean = { false }, label: String = "Ask AI"): ActionResult {
        val full = if (profile.isEmpty()) task else (task ?: Ai.BAR_TASK) + profile
        Meter.begin()
        try {
            return when {
                custom -> ChatCompat.run(base, key, model, full, input, tools, cancelled)
                openAi -> OpenAi.run(key, model, web && webAllowed, full, input, tools, cancelled)
                else -> Ai.run(key, model, web && webAllowed, full, input, tools, cancelled)
            }
        } finally {
            val (i, o, s) = Meter.end().let { Triple(it[0], it[1], it[2]) }
            if (i + o > 0) store.recordUsage(label, model, i, o, s)
        }
    }
}
