package app.verbo.keyboard

/**
 * Runs one assistant request on whichever service the user picked (Claude, OpenAI, or a compatible gateway).
 * Reads the settings once when created, on the main thread, so [run] can be called from a worker thread.
 */
class Engine(store: Store) {
    private val openAi = store.isOpenAi
    private val custom = store.isCustom
    private val base = store.customBase
    private val key = store.activeKey
    private val model = store.activeModel
    private val webAllowed = store.webSearch

    /** Blocking. [web] asks for web search where the service has it; [tools] are the user's connections. */
    fun run(task: String?, input: String, web: Boolean, tools: List<ToolSpec>, cancelled: () -> Boolean = { false }): ActionResult = when {
        custom -> ChatCompat.run(base, key, model, task, input, tools, cancelled)
        openAi -> OpenAi.run(key, model, web && webAllowed, task, input, tools, cancelled)
        else -> Ai.run(key, model, web && webAllowed, task, input, tools, cancelled)
    }
}
