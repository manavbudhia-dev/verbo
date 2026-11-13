package app.verbo.keyboard

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaWebSearchTool20250305
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration

/** What the keyboard should do with a finished action. */
data class ActionResult(val text: String, val openUri: String?)

class AiException(message: String) : Exception(message)

/** A connected app or API the assistant may call. All parameters are strings. */
class ToolSpec(
    val name: String,
    val description: String,
    val params: List<Pair<String, String>>,
    val required: List<String>,
    val run: (Map<String, String>) -> String,
)

/** Talks to Claude. No Android dependencies, so it can be exercised on a plain JVM. */
object Ai {
    const val DEFAULT_MODEL = "claude-opus-5-5"
    val MODELS = listOf(
        "claude-opus-5-5" to "Opus 5.5 — most capable",
        "claude-sonnet-5-5" to "Sonnet 5.5 — balanced",
        "claude-haiku-5-5" to "Haiku 5.5 — fastest",
    )

    /** OpenAI models offered in the picker; any other model ID can be typed in. */
    const val DEFAULT_OPENAI_MODEL = "gpt-5.5"
    val OPENAI_MODELS = listOf(
        "gpt-5.5" to "GPT-5.5",
        "gpt-6-astra" to "GPT-6 Astra",
    )

    private const val BASE_PROMPT = """You are the engine inside a phone keyboard. The user typed text into a text field in some other app (a chat, an email, a note, a search box) and then pressed an action key. Whatever you return is placed in that field, so it must be ready to send or use as it stands.

Return only the replacement text. No preamble, no explanation of what you did, no surrounding quotes, and no markdown, since the field shows plain text. Write in the language the user wrote in unless the task says otherwise. Keep it as short as the situation allows: this is usually a chat message, not a document.

If the request needs current or factual information you are not sure of (places, scores, schedules, prices, weather, news), use web search when it is available rather than guessing, and put the useful facts directly in the text. If you cannot do what was asked, return one short sentence saying why.

Sometimes the most helpful outcome is also opening something on the phone: a map search, a phone number, a new email, or a web page. In that case add one final line in exactly this form: [[open:URI]] using a geo:0,0?q=..., tel:, mailto: or https: URI. The keyboard strips that line from the text and offers the link to the user. Only add it when it clearly helps. To let the user post on X, use https://x.com/intent/post?text=... with the text URL-encoded.

You may also have tools for apps and APIs the user connected. Use them when the request is about that app or needs its data. When a tool changes something for the user (for example creating a page), say in the returned text what was done, in one short line."""

    private const val BAR_TASK = "Task: treat the user's text as an instruction or a statement of what they want, and carry it out. For example, \"tell mom I'll be 20 min late, sound sorry\" becomes the message to send to mom."

    const val DICTATION_TASK = "Task: the user's text is a raw speech-to-text transcript they dictated into the field. Turn it into what they meant to write. Add punctuation and capitalisation, drop filler words, repeats and false starts, and apply spoken self-corrections (\"at five, no, six\" becomes \"at six\"). Honour spoken formatting requests such as \"new line\" or \"make that a list\". Fix words the recogniser obviously misheard. Do not answer questions in it, do not add content, and keep their wording and language."

    fun skillTask(instruction: String) = "Task: apply this skill to the user's text. $instruction"

    fun systemPrompt(task: String?): String = BASE_PROMPT + "\n\n" + (task ?: BAR_TASK)

    /** Blocking; call from a background thread. */
    fun run(apiKey: String, model: String, webSearch: Boolean, task: String?, userText: String, tools: List<ToolSpec> = emptyList()): ActionResult {
        if (apiKey.isBlank()) throw AiException("Add your API key in the Verbo app first")
        val client = AnthropicOkHttpClient.builder()
            .apiKey(apiKey.trim())
            .timeout(Duration.ofSeconds(90))
            .maxRetries(1)
            .build()
        try {
            val builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(systemPrompt(task))
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                .addUserMessage(userText)
            // Haiku has no server-side refusal fallback; Opus and Sonnet route refusals to a fallback model.
            val isHaiku = model.contains("haiku")
            if (!isHaiku) builder.addBeta("server-side-fallback-2026-07-01").fallbacksDefault()
            if (webSearch) {
                if (isHaiku) builder.addTool(BetaWebSearchTool20250305.builder().maxUses(3L).build())
                else builder.addTool(BetaWebSearchTool20260209.builder().maxUses(3L).build())
            }

            for (tool in tools) builder.addTool(toolParam(tool))

            var response: BetaMessage = client.beta().messages().create(builder.build())
            var rounds = 0
            while (rounds < MAX_ROUNDS) {
                val stop = response.stopReason().orElse(null)
                if (stop == BetaStopReason.PAUSE_TURN) {
                    // A server tool paused a long turn; hand the partial turn back so it continues.
                    builder.addMessage(response)
                } else if (stop == BetaStopReason.TOOL_USE) {
                    builder.addMessage(response)
                    val results = response.content().filter { it.isToolUse() }.map { block ->
                        val call = block.asToolUse()
                        val result = BetaToolResultBlockParam.builder().toolUseId(call.id())
                        try {
                            val spec = tools.first { it.name == call.name() }
                            val args = call._input().asObject().orElse(emptyMap())
                                .mapValues { (_, v) -> v.asString().orElse(v.toString()) }
                            result.content(spec.run(args))
                        } catch (e: Exception) {
                            result.content("Error: ${e.message ?: e.javaClass.simpleName}").isError(true)
                        }
                        BetaContentBlockParam.ofToolResult(result.build())
                    }
                    builder.addUserMessageOfContentBlockParams(results)
                } else break
                response = client.beta().messages().create(builder.build())
                rounds++
            }
            if (response.stopReason().orElse(null) == BetaStopReason.REFUSAL)
                throw AiException("The assistant declined this request")
            return parse(finalText(response))
        } catch (e: UnauthorizedException) {
            throw AiException("API key was rejected. Check it in the Verbo app")
        } catch (e: PermissionDeniedException) {
            throw AiException("This API key can't use that model")
        } catch (e: RateLimitException) {
            throw AiException("Rate limit reached. Try again in a moment")
        } catch (e: BadRequestException) {
            throw AiException("Request was rejected: ${e.message?.take(120)}")
        } catch (e: AnthropicServiceException) {
            throw AiException("Service error (${e.statusCode()}). Try again")
        } catch (e: AnthropicIoException) {
            throw AiException("No connection")
        } finally {
            client.close()
        }
    }

    private const val MAX_ROUNDS = 6

    private fun toolParam(tool: ToolSpec): BetaTool {
        val props = BetaTool.InputSchema.Properties.builder()
        for ((name, description) in tool.params)
            props.putAdditionalProperty(name, JsonValue.from(mapOf("type" to "string", "description" to description)))
        return BetaTool.builder()
            .name(tool.name)
            .description(tool.description)
            .inputSchema(BetaTool.InputSchema.builder().properties(props.build()).required(tool.required).build())
            .build()
    }

    /** The answer is the text that follows the last tool activity; earlier text is "let me look that up" chatter. */
    private fun finalText(response: BetaMessage): String {
        val sb = StringBuilder()
        for (block in response.content()) {
            if (block.isText()) sb.append(block.asText().text())
            else if (block.isServerToolUse() || block.isWebSearchToolResult() || block.isToolUse()) sb.setLength(0)
        }
        return sb.toString()
    }

    private val OPEN = Regex("""\[\[open:([^\]\s]+)]]""")
    private val ALLOWED = listOf("geo:", "tel:", "mailto:", "https://")

    fun parse(raw: String): ActionResult {
        val uri = OPEN.findAll(raw).lastOrNull()?.groupValues?.get(1)
            ?.takeIf { u -> ALLOWED.any { u.startsWith(it, ignoreCase = true) } }
        val text = raw.replace(OPEN, "").trim()
        if (text.isEmpty() && uri == null) throw AiException("Got an empty answer. Try again")
        return ActionResult(text, uri)
    }
}
