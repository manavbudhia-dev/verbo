package app.verbo.keyboard

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaWebSearchTool20250305
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration

/** What the keyboard should do with a finished action. */
data class ActionResult(val text: String, val openUri: String?)

class AiException(message: String) : Exception(message)

/** Talks to Claude. No Android dependencies, so it can be exercised on a plain JVM. */
object Ai {
    const val DEFAULT_MODEL = "claude-opus-5-5"
    val MODELS = listOf(
        "claude-opus-5-5" to "Opus 5.5 — most capable",
        "claude-sonnet-5-5" to "Sonnet 5.5 — balanced",
        "claude-haiku-5-5" to "Haiku 5.5 — fastest",
    )

    private const val BASE_PROMPT = """You are the engine inside a phone keyboard. The user typed text into a text field in some other app (a chat, an email, a note, a search box) and then pressed an action key. Whatever you return replaces their text in that field, so it must be ready to send or use as it stands.

Return only the replacement text. No preamble, no explanation of what you did, no surrounding quotes, and no markdown, since the field shows plain text. Write in the language the user wrote in unless the task says otherwise. Keep it as short as the situation allows: this is usually a chat message, not a document.

If the request needs current or factual information you are not sure of (places, scores, schedules, prices, weather, news), use web search when it is available rather than guessing, and put the useful facts directly in the text. If you cannot do what was asked, return one short sentence saying why.

Sometimes the most helpful outcome is also opening something on the phone: a map search, a phone number, a new email, or a web page. In that case add one final line in exactly this form: [[open:URI]] using a geo:0,0?q=..., tel:, mailto: or https: URI. The keyboard strips that line from the text and offers the link to the user. Only add it when it clearly helps."""

    private const val BAR_TASK = "Task: treat the user's text as an instruction or a statement of what they want, and carry it out. For example, \"tell mom I'll be 20 min late, sound sorry\" becomes the message to send to mom."

    fun systemPrompt(skillInstruction: String?): String =
        BASE_PROMPT + "\n\n" + if (skillInstruction.isNullOrBlank()) BAR_TASK
        else "Task: apply this skill to the user's text. $skillInstruction"

    /** Blocking; call from a background thread. */
    fun run(apiKey: String, model: String, webSearch: Boolean, skillInstruction: String?, userText: String): ActionResult {
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
                .system(systemPrompt(skillInstruction))
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                .addUserMessage(userText)
            // Haiku has no server-side refusal fallback; Opus and Sonnet route refusals to a fallback model.
            val isHaiku = model.contains("haiku")
            if (!isHaiku) builder.addBeta("server-side-fallback-2026-07-01").fallbacksDefault()
            if (webSearch) {
                if (isHaiku) builder.addTool(BetaWebSearchTool20250305.builder().maxUses(3L).build())
                else builder.addTool(BetaWebSearchTool20260209.builder().maxUses(3L).build())
            }

            var response: BetaMessage = client.beta().messages().create(builder.build())
            // Server tools can pause a long turn; hand the partial turn back so it continues.
            var continues = 0
            while (response.stopReason().orElse(null) == BetaStopReason.PAUSE_TURN && continues < 4) {
                builder.addMessage(response)
                response = client.beta().messages().create(builder.build())
                continues++
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

    /** The answer is the text that follows the last tool activity; earlier text is "let me look that up" chatter. */
    private fun finalText(response: BetaMessage): String {
        val sb = StringBuilder()
        for (block in response.content()) {
            if (block.isText()) sb.append(block.asText().text())
            else if (block.isServerToolUse() || block.isWebSearchToolResult()) sb.setLength(0)
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
