package app.verbo.keyboard

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Turns the user's connected apps and APIs into tools the assistant can call. No Android dependencies. */
object Connections {
    private val json = ObjectMapper()
    private const val MAX_BODY = 12000
    private const val MAX_READ = 2_000_000

    fun tools(weather: Boolean, notionToken: String, calendlyToken: String, apis: List<CustomApi>): List<ToolSpec> {
        val out = ArrayList<ToolSpec>()
        if (weather) out += ToolSpec(
            "get_weather",
            "Current conditions and a 3-day forecast for a place. weather_code values are WMO codes. Temperatures are Celsius, wind km/h.",
            listOf("location" to "City or place name, e.g. \"Lisbon\""), listOf("location"),
        ) { weather(it["location"].orEmpty()) }

        if (notionToken.isNotBlank()) {
            out += ToolSpec(
                "notion_search", "Search the user's Notion workspace. Returns matching pages and databases with their ids, titles and links.",
                listOf("query" to "Words to search for; empty lists recent items"), emptyList(),
            ) { notionSearch(notionToken, it["query"].orEmpty()) }
            out += ToolSpec(
                "notion_read_page", "Read the text of a Notion page.",
                listOf("page_id" to "Id from notion_search"), listOf("page_id"),
            ) { notionRead(notionToken, it["page_id"].orEmpty()) }
            out += ToolSpec(
                "notion_create_page", "Create a new Notion page under an existing page. Find the parent with notion_search first.",
                listOf("parent_page_id" to "Id of the page to create it under", "title" to "Page title", "content" to "Body text; each line becomes a paragraph"),
                listOf("parent_page_id", "title"),
            ) { notionCreate(notionToken, it["parent_page_id"].orEmpty(), it["title"].orEmpty(), it["content"].orEmpty()) }
        }

        if (calendlyToken.isNotBlank()) out += ToolSpec(
            "calendly_scheduling_links", "List the user's Calendly event types with their booking links and durations.",
            emptyList(), emptyList(),
        ) { calendly(calendlyToken) }

        for (api in apis) {
            val slug = api.name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40)
            if (slug.isEmpty() || !api.url.startsWith("https://")) continue
            out += ToolSpec(
                "api_$slug",
                "${api.name}: ${api.description}".take(900) + " Returns the raw response.",
                listOf("query" to "Value for the request"), emptyList(),
            ) { args ->
                val url = api.url.replace("{query}", enc(args["query"].orEmpty()))
                val headers = if (api.headerName.isNotBlank()) mapOf(api.headerName to api.headerValue) else emptyMap()
                clip(http("GET", url, headers, null))
            }
        }
        return out
    }

    // ---------- weather (Open-Meteo, no key needed) ----------

    private fun weather(location: String): String {
        val geo = json.readTree(http("GET", "https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + enc(location), emptyMap(), null))
        val place = geo.path("results").path(0)
        if (place.isMissingNode) return "No place found called \"$location\""
        val forecast = http(
            "GET",
            "https://api.open-meteo.com/v1/forecast?latitude=${place.path("latitude").asDouble()}&longitude=${place.path("longitude").asDouble()}" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=3",
            emptyMap(), null,
        )
        return "Place: ${place.path("name").asText()}, ${place.path("country").asText()}\n$forecast"
    }

    // ---------- Notion ----------

    private fun notionHeaders(token: String) = mapOf("Authorization" to "Bearer $token", "Notion-Version" to "2022-06-28")

    private fun plain(rich: JsonNode) = rich.joinToString("") { it.path("plain_text").asText() }

    private fun notionSearch(token: String, query: String): String {
        val body = json.writeValueAsString(mapOf("query" to query, "page_size" to 10))
        val res = json.readTree(http("POST", "https://api.notion.com/v1/search", notionHeaders(token), body))
        val lines = res.path("results").map { item ->
            val title = if (item.path("object").asText() == "database") plain(item.path("title"))
            else item.path("properties").firstOrNull { it.path("type").asText() == "title" }?.let { plain(it.path("title")) } ?: ""
            "${item.path("object").asText()} | id ${item.path("id").asText()} | ${title.ifBlank { "(untitled)" }} | ${item.path("url").asText()}"
        }
        return if (lines.isEmpty()) "Nothing found. The page may not be shared with the integration." else lines.joinToString("\n")
    }

    private fun notionRead(token: String, pageId: String): String {
        val res = json.readTree(http("GET", "https://api.notion.com/v1/blocks/${enc(pageId)}/children?page_size=100", notionHeaders(token), null))
        val text = res.path("results").joinToString("\n") { block -> plain(block.path(block.path("type").asText()).path("rich_text")) }
        val more = if (res.path("has_more").asBoolean()) "\n[The page continues beyond the first 100 blocks.]" else ""
        return clip(text.ifBlank { "(The page has no text.)" }) + more
    }

    private fun notionCreate(token: String, parent: String, title: String, content: String): String {
        val children = content.lines().filter { it.isNotBlank() }.flatMap { it.chunked(1900) }.take(100).map { line ->
            mapOf("object" to "block", "type" to "paragraph",
                "paragraph" to mapOf("rich_text" to listOf(mapOf("type" to "text", "text" to mapOf("content" to line)))))
        }
        val body = json.writeValueAsString(mapOf(
            "parent" to mapOf("page_id" to parent),
            "properties" to mapOf("title" to mapOf("title" to listOf(mapOf("text" to mapOf("content" to title))))),
            "children" to children,
        ))
        val res = json.readTree(http("POST", "https://api.notion.com/v1/pages", notionHeaders(token), body))
        return "Created: ${res.path("url").asText()}"
    }

    // ---------- Calendly ----------

    private fun calendly(token: String): String {
        val auth = mapOf("Authorization" to "Bearer $token")
        val me = json.readTree(http("GET", "https://api.calendly.com/users/me", auth, null)).path("resource").path("uri").asText()
        val types = json.readTree(http("GET", "https://api.calendly.com/event_types?active=true&user=" + enc(me), auth, null))
        val lines = types.path("collection").map {
            "${it.path("name").asText()} | ${it.path("duration").asInt()} min | ${it.path("scheduling_url").asText()}"
        }
        return if (lines.isEmpty()) "No active event types." else lines.joinToString("\n")
    }

    // ---------- plumbing ----------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun clip(s: String) = if (s.length <= MAX_BODY) s
    else s.take(MAX_BODY) + "\n[Response cut off here: ${s.length - MAX_BODY} more characters were not shown.]"

    private fun http(method: String, url: String, headers: Map<String, String>, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Accept", "application/json")
            // A redirect would resend the user's token or API key header to whatever host it points at.
            if (headers.isNotEmpty()) conn.instanceFollowRedirects = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            // Read at most MAX_READ characters so a huge or endless response can't exhaust memory.
            val text = stream?.bufferedReader()?.use { reader ->
                val buffer = CharArray(8192)
                val sb = StringBuilder()
                while (sb.length < MAX_READ) {
                    val n = reader.read(buffer, 0, minOf(buffer.size, MAX_READ - sb.length))
                    if (n < 0) break
                    sb.append(buffer, 0, n)
                }
                sb.toString()
            } ?: ""
            if (code !in 200..299) throw RuntimeException("HTTP $code ${text.take(300)}")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
