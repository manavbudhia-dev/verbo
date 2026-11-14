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
/** [map] is a latitude/longitude to preview when the result is one specific place. */
data class ActionResult(val text: String, val openUri: String?, val map: Pair<Double, Double>? = null)

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
    const val DEFAULT_MODEL = "claude-haiku-5-5" // the cheapest; a keyboard action costs a fraction of a cent
    val MODELS = listOf(
        "claude-haiku-5-5" to "Haiku 5.5 — cheapest, fast",
        "claude-sonnet-5-5" to "Sonnet 5.5 — balanced",
        "claude-opus-5-5" to "Opus 5.5 — most capable",
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

Sometimes the most helpful outcome is also opening something on the phone: a map search, a phone number, a new email, or a web page. In that case add one final line in exactly this form: [[open:URI]] using a geo:0,0?q=..., tel:, mailto: or https: URI. The keyboard strips that line from the text and offers the link to the user. Only add it when it clearly helps. Useful link forms, with every value URL-encoded:
- Email draft (opens Gmail or the user's mail app): mailto:ADDRESS?subject=...&body=... (leave ADDRESS empty if you don't know it)
- WhatsApp message: https://wa.me/?text=... (or https://wa.me/NUMBER?text=... when the user gave a phone number with country code)
- Calendar event: https://calendar.google.com/calendar/render?action=TEMPLATE&text=TITLE&dates=YYYYMMDDTHHMMSS/YYYYMMDDTHHMMSS&details=...&location=...
- Map search or directions: geo:0,0?q=...
- YouTube search: https://www.youtube.com/results?search_query=...
- Post on X: https://x.com/intent/post?text=...
When the link carries the whole message (an email or WhatsApp draft), still return the message text itself as well.

The user sees your result on a card above the keyboard and then inserts or copies it, so shape it to read well there and in a chat:
- Several results (places to eat, matches, options): a short title on the first line, then one result per line with the key facts (name, rating or time, area). At most five.
- One specific place: its name on the first line, its address on the second, and a Google Maps link on the third (https://www.google.com/maps/search/?api=1&query=NAME+ADDRESS, URL-encoded). If a place search tool gave you its coordinates, also add a final line [[map:LATITUDE,LONGITUDE]]; the keyboard removes that line and shows a small map.
- A person or a thing looked up: the name on the first line, then one short fact per line.

The user's message may start with a "Context" block: a message they received and copied. Use it to understand what they are replying to or asking about. Never repeat the context block itself in your answer.

You may also have tools for apps and APIs the user connected. Use them when the request is about that app or needs its data. When a tool changes something for the user (for example creating a page), say in the returned text what was done, in one short line."""

    private const val BAR_TASK = "Task: treat the user's text as an instruction or a statement of what they want, and carry it out. For example, \"tell mom I'll be 20 min late, sound sorry\" becomes the message to send to mom."

    const val DICTATION_TASK = "Task: the user's text is a raw speech-to-text transcript they dictated into the field. Turn it into what they meant to write. Add punctuation and capitalisation, drop filler words, repeats and false starts, and apply spoken self-corrections (\"at five, no, six\" becomes \"at six\"). Honour spoken formatting requests such as \"new line\" or \"make that a list\". Fix words the recogniser obviously misheard. Do not answer questions in it, do not add content, and keep their wording and language."

    fun translateTask(languages: List<String>) =
        if (languages.size == 1) "Task: translate the text into ${languages[0]}. Return only the translation, natural and idiomatic, keeping the tone, emoji and names."
        else "Task: translate the text into each of these languages: ${languages.joinToString(", ")}. Put a short title on the first line, then one line per language in the form \"Language: translation\"."

    /** Used while auto-translate is on: the field may already hold some translated text from a moment ago. */
    fun autoTranslateTask(language: String) = translateTask(listOf(language)) +
        " Any part that is already in $language stays exactly as it is. If the whole text is already in $language, return it unchanged."

    /** Turns a plain-language wish into a skill definition. The reply is read by [parseDesign], so the format is fixed. */
    const val DESIGN_TASK = """Task: the user's text describes a keyboard skill they want. A skill is one saved instruction that the assistant applies to whatever text is in the field (or to a copied message) when the user triggers it. Design that skill.

Reply with exactly these seven lines and nothing else, each starting with its label:
NAME: two or three words
EMOJI: one emoji that fits
DOES: one sentence saying what the skill does, written for the user
INPUT: what the skill works on, in a few words
OUTPUT: what comes back, in a few words
GROUP: one of write, edit, translate, summarize
INSTRUCTION: the instruction the assistant will follow each time, in two to four sentences. Address the assistant directly. Say what to do with the text, what to keep, and what the result should look like. If the wish involves an app (Gmail, WhatsApp, Calendar, Maps, YouTube, X, Notion, Calendly) or current facts, tell the assistant to use that app's link or tool or to search the web."""

    class SkillDraft(val name: String, val emoji: String, val does: String, val input: String, val output: String, val group: String, val instruction: String)

    /** Reads the seven labelled lines of a design reply. Null if the name or the instruction is missing. */
    fun parseDesign(text: String): SkillDraft? {
        val fields = HashMap<String, String>()
        var current: String? = null
        for (line in text.lines()) {
            val match = Regex("""^\s*\**(NAME|EMOJI|DOES|INPUT|OUTPUT|GROUP|INSTRUCTION)\**\s*:\s*(.*)$""").find(line)
            if (match != null) {
                current = match.groupValues[1]
                fields[current] = match.groupValues[2].trim()
            } else if (current == "INSTRUCTION" && line.isNotBlank()) {
                fields[current] = (fields[current].orEmpty() + " " + line.trim()).trim() // a long instruction may wrap
            }
        }
        val name = fields["NAME"].orEmpty().trim('"', ' ').take(40)
        val instruction = fields["INSTRUCTION"].orEmpty().take(2000)
        if (name.isEmpty() || instruction.isEmpty()) return null
        val group = fields["GROUP"].orEmpty().lowercase().takeIf { it in listOf("write", "edit", "translate", "summarize") } ?: "edit"
        return SkillDraft(name, fields["EMOJI"].orEmpty().take(8), fields["DOES"].orEmpty(), fields["INPUT"].orEmpty(), fields["OUTPUT"].orEmpty(), group, instruction)
    }

    fun skillTask(instruction: String) = "Task: apply this skill to the user's text. $instruction"

    /** The phone's date and time go in so requests like "tomorrow at 5" can be turned into real dates. */
    fun systemPrompt(task: String?): String = BASE_PROMPT + "\n\n" + (task ?: BAR_TASK) +
        "\n\nThe user's current date and time: " +
        java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm (xxx)", java.util.Locale.ENGLISH))

    /** Blocking; call from a background thread. */
    fun run(apiKey: String, model: String, webSearch: Boolean, task: String?, userText: String, tools: List<ToolSpec> = emptyList(), cancelled: () -> Boolean = { false }): ActionResult {
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
                        // Tools can change things (create a page), so never run one for an action the user cancelled.
                        if (cancelled()) throw AiException("Cancelled")
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
                if (cancelled()) throw AiException("Cancelled")
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
    private val MAP = Regex("""\[\[map:(-?\d{1,3}(?:\.\d+)?),\s*(-?\d{1,3}(?:\.\d+)?)]]""")

    /** What is sent as the user's turn: their text, preceded by the copied message they chose as context. */
    fun userMessage(text: String, context: String?): String = when {
        context.isNullOrBlank() -> text
        text.isBlank() -> "Context (a message the user received):\n$context\n\nThe user typed nothing. Do the task for the context above."
        else -> "Context (a message the user received):\n$context\n\nThe user's text:\n$text"
    }
    private val ALLOWED = listOf("geo:", "tel:", "mailto:", "https://")

    fun parse(raw: String): ActionResult {
        val uri = OPEN.findAll(raw).lastOrNull()?.groupValues?.get(1)
            ?.takeIf { u -> ALLOWED.any { u.startsWith(it, ignoreCase = true) } }
        val map = MAP.findAll(raw).lastOrNull()?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && lat in -85.0..85.0 && lon in -180.0..180.0) lat to lon else null
        }
        val text = raw.replace(OPEN, "").replace(MAP, "").trim()
        if (text.isEmpty() && uri == null) throw AiException("Got an empty answer. Try again")
        return ActionResult(text, uri, map)
    }

    /** A person looked up by the "Who is" skill: name, headline, labelled facts and a LinkedIn link. */
    data class PersonCard(val name: String, val headline: String, val facts: List<Pair<String, String>>, val link: String?, val rest: String)

    private val FACT = Regex("""^(Current|Previous|Education|Location|Known for)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

    /** Reads a result shaped like a profile ("Current: …", "Education: …"); null for any other text. */
    fun personCard(text: String): PersonCard? {
        val lines = text.lines().map { it.trim().removePrefix("- ").trim() }.filter { it.isNotEmpty() }
        val facts = lines.mapNotNull { l -> FACT.find(l)?.let { it.groupValues[1].replaceFirstChar { c -> c.uppercase() } to it.groupValues[2].trim() } }
        if (facts.isEmpty() || lines.size < 2 || FACT.matches(lines[0]) || lines[0].length > 60) return null
        val link = lines.firstOrNull { it.contains("linkedin.com/") }?.let { l -> Regex("""https://\S+""").find(l)?.value }
        val others = lines.drop(1).filter { !FACT.matches(it) && !it.contains("linkedin.com/") }
        val headline = others.firstOrNull { !it.startsWith("http") } ?: ""
        return PersonCard(lines[0], headline, facts, link, (others - headline).joinToString("\n"))
    }
}
