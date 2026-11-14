package app.verbo.keyboard

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.HttpURLConnection
import java.net.URL

/** Runs an action on OpenAI's Responses API. Same contract as the Claude path in [Ai]. No Android dependencies. */
object OpenAi {
    private val json = ObjectMapper()
    private const val URL_RESPONSES = "https://api.openai.com/v1/responses"
    private const val MAX_ROUNDS = 6

    fun run(apiKey: String, model: String, webSearch: Boolean, task: String?, userText: String, tools: List<ToolSpec>, cancelled: () -> Boolean = { false }): ActionResult {
        if (apiKey.isBlank()) throw AiException("Add your OpenAI API key in the Verbo app first")
        val toolDefs = ArrayList<Map<String, Any>>()
        if (webSearch) toolDefs += mapOf("type" to "web_search")
        for (tool in tools) toolDefs += mapOf(
            "type" to "function", "name" to tool.name, "description" to tool.description,
            "parameters" to mapOf(
                "type" to "object",
                "properties" to tool.params.associate { (name, description) -> name to mapOf("type" to "string", "description" to description) },
                "required" to tool.required,
            ),
        )

        var input: Any = userText
        var previous: String? = null
        var response: JsonNode
        var rounds = 0
        while (true) {
            if (cancelled()) throw AiException("Cancelled")
            val body = HashMap<String, Any>()
            body["model"] = model
            body["instructions"] = Ai.systemPrompt(task)
            body["input"] = input
            if (toolDefs.isNotEmpty()) body["tools"] = toolDefs
            if (previous != null) body["previous_response_id"] = previous
            response = post(apiKey.trim(), json.writeValueAsString(body))
            Meter.add(response.path("usage").path("input_tokens").asLong(), response.path("usage").path("output_tokens").asLong())

            val calls = response.path("output").filter { it.path("type").asText() == "function_call" }
            if (calls.isEmpty() || rounds++ >= MAX_ROUNDS) break
            // Run each requested tool and hand all the results back in one follow-up request.
            input = calls.map { call ->
                // Tools can change things (create a page), so never run one for an action the user cancelled.
                if (cancelled()) throw AiException("Cancelled")
                val output = try {
                    val spec = tools.first { it.name == call.path("name").asText() }
                    val args = HashMap<String, String>()
                    json.readTree(call.path("arguments").asText("{}")).fields().forEach { (k, v) -> args[k] = if (v.isTextual) v.asText() else v.toString() }
                    spec.run(args)
                } catch (e: Exception) {
                    "Error: ${e.message ?: e.javaClass.simpleName}"
                }
                mapOf("type" to "function_call_output", "call_id" to call.path("call_id").asText(), "output" to output)
            }
            previous = response.path("id").asText()
        }

        val text = StringBuilder()
        var refused = false
        for (item in response.path("output")) if (item.path("type").asText() == "message") {
            for (part in item.path("content")) when (part.path("type").asText()) {
                "output_text" -> text.append(part.path("text").asText())
                "refusal" -> refused = true
            }
        }
        if (text.isEmpty() && refused) throw AiException("The assistant declined this request")
        return Ai.parse(text.toString())
    }

    private fun post(apiKey: String, body: String): JsonNode {
        val conn = try {
            URL(URL_RESPONSES).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw AiException("No connection")
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 90000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) return json.readTree(text)
            val message = try { json.readTree(text).path("error").path("message").asText() } catch (e: Exception) { "" }
            throw AiException(when (code) {
                401 -> "OpenAI API key was rejected. Check it in the Verbo app"
                403 -> "This OpenAI key can't use that model"
                404 -> "OpenAI doesn't know that model. Check the model name in the Verbo app"
                429 -> "OpenAI rate limit or quota reached. Try again in a moment"
                400 -> "Request was rejected: ${message.take(120)}"
                else -> "OpenAI service error ($code). Try again"
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
