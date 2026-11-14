package app.verbo.keyboard

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs an action through any gateway that speaks the OpenAI Chat Completions format
 * (AI Router, OpenRouter and similar). Same contract as [Ai]. No Android dependencies.
 * These gateways have no built-in web search, so only the user's connections are offered as tools.
 */
object ChatCompat {
    private val json = ObjectMapper()
    private const val MAX_ROUNDS = 6
    const val DEFAULT_BASE = "https://api.airouter.in/v1"
    const val DEFAULT_MODEL = "anthropic/claude-haiku-5.5"

    fun normaliseBase(address: String): String {
        var url = address.trim().trimEnd('/')
        if (!url.contains("://")) url = "https://$url"
        if (!url.startsWith("https://")) throw AiException("The gateway address must start with https://")
        return url.removeSuffix("/chat/completions")
    }

    fun run(
        baseUrl: String, apiKey: String, model: String, task: String?, userText: String,
        tools: List<ToolSpec>, cancelled: () -> Boolean = { false },
    ): ActionResult {
        if (apiKey.isBlank()) throw AiException("Add your gateway API key in the Verbo app first")
        val endpoint = normaliseBase(baseUrl) + "/chat/completions"
        val toolDefs = tools.map { tool ->
            mapOf("type" to "function", "function" to mapOf(
                "name" to tool.name, "description" to tool.description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to tool.params.associate { (name, description) -> name to mapOf("type" to "string", "description" to description) },
                    "required" to tool.required,
                ),
            ))
        }
        val messages = ArrayList<Any>()
        messages += mapOf("role" to "system", "content" to Ai.systemPrompt(task))
        messages += mapOf("role" to "user", "content" to userText)

        var rounds = 0
        while (true) {
            if (cancelled()) throw AiException("Cancelled")
            val body = HashMap<String, Any>()
            body["model"] = model
            body["messages"] = messages
            if (toolDefs.isNotEmpty()) body["tools"] = toolDefs
            val reply = post(endpoint, apiKey.trim(), json.writeValueAsString(body))
            Meter.add(reply.path("usage").path("prompt_tokens").asLong(), reply.path("usage").path("completion_tokens").asLong())
            val message = reply.path("choices").path(0).path("message")
            val calls = message.path("tool_calls")
            if (!calls.isArray || calls.size() == 0 || rounds++ >= MAX_ROUNDS) {
                return Ai.parse(message.path("content").asText(""))
            }
            // Echo the assistant's tool request back, then one result message per call.
            messages += json.convertValue(message, Map::class.java)
            for (call in calls) {
                // Tools can change things (create a page), so never run one for an action the user cancelled.
                if (cancelled()) throw AiException("Cancelled")
                val output = try {
                    val spec = tools.first { it.name == call.path("function").path("name").asText() }
                    val args = HashMap<String, String>()
                    json.readTree(call.path("function").path("arguments").asText("{}")).fields()
                        .forEach { (k, v) -> args[k] = if (v.isTextual) v.asText() else v.toString() }
                    spec.run(args)
                } catch (e: Exception) {
                    "Error: ${e.message ?: e.javaClass.simpleName}"
                }
                messages += mapOf("role" to "tool", "tool_call_id" to call.path("id").asText(), "content" to output)
            }
        }
    }

    private fun post(endpoint: String, apiKey: String, body: String): JsonNode {
        val conn = try {
            URL(endpoint).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw AiException("That gateway address doesn't look right")
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 90000
            conn.doOutput = true
            conn.instanceFollowRedirects = false // never forward the key to another host
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) return try { json.readTree(text) } catch (e: Exception) { throw AiException("The gateway sent an answer Verbo couldn't read") }
            val message = try { json.readTree(text).path("error").path("message").asText() } catch (e: Exception) { "" }
            throw AiException(when (code) {
                401, 403 -> "The gateway rejected the API key. Check it in the Verbo app"
                402 -> "The gateway account is out of credit"
                404 -> "The gateway doesn't know that model or address"
                429 -> "Gateway rate limit reached. Try again in a moment"
                400 -> "Request was rejected: ${message.take(120)}"
                else -> "Gateway error ($code). Try again"
            })
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException("No connection")
        } finally {
            conn.disconnect()
        }
    }
}
