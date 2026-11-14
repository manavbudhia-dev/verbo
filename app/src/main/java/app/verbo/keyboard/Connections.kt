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

    /** Sent to the open map services, which ask every app to identify itself. Names the app only, never the user. */
    const val USER_AGENT = "VerboKeyboard/1.0 (Android keyboard app)"

    // API addresses. Variables only so the tests can point them at a stand-in server.
    internal var slackBase = "https://slack.com/api"
    internal var githubBase = "https://api.github.com"
    internal var todoistBase = "https://api.todoist.com/api/v1"
    internal var linearUrl = "https://api.linear.app/graphql"

    /** [tokens] holds the other app tokens by dashboard field name: slackToken, githubToken, todoistToken, linearToken, discordWebhook. */
    fun tools(weather: Boolean, notionToken: String, calendlyToken: String, apis: List<CustomApi>, places: Boolean = true,
              tokens: Map<String, String> = emptyMap(), googleServices: Set<String> = emptySet(),
              google: ((String, Map<String, String>) -> String)? = null): List<ToolSpec> {
        val out = ArrayList<ToolSpec>()
        if (places) out += ToolSpec(
            "find_place",
            "Look up a real place (shop, restaurant, landmark, address) on OpenStreetMap. Returns up to three matches with name, full address and coordinates. Put the area in the query, e.g. \"Starbucks Times Square New York\".",
            listOf("query" to "Place name plus area or city"), listOf("query"),
        ) { findPlace(it["query"].orEmpty()) }
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

        if (google != null) {
            if ("meet" in googleServices) out += ToolSpec(
                "google_meet_link", "Create a new Google Meet room in the user's account and return its link. Use it when a message should include a meeting link.",
                emptyList(), emptyList(),
            ) { google("meet", emptyMap()) }
            if ("calendar" in googleServices) {
                out += ToolSpec(
                    "google_calendar_events", "List upcoming events in the user's Google Calendar.",
                    listOf("days" to "How many days ahead, 1 to 60; default 7", "query" to "Optional words to match"), emptyList(),
                ) { google("calendar_events", it) }
                out += ToolSpec(
                    "google_calendar_create",
                    "Save an event in the user's Google Calendar, optionally inviting people (they get an email) and adding a Google Meet link. Times are the user's local time.",
                    listOf("title" to "Event title", "start" to "Start, like 2026-11-20T13:00", "end" to "End, like 2026-11-20T14:00; one hour after start if not said",
                        "location" to "Place, optional", "description" to "Notes, optional", "attendees" to "Comma-separated email addresses to invite, optional",
                        "meet" to "true to add a Google Meet link"),
                    listOf("title", "start", "end"),
                ) { google("calendar_create", it) }
            }
            if ("gmail" in googleServices) {
                out += ToolSpec(
                    "gmail_search", "Search the user's Gmail. Uses Gmail search syntax, e.g. \"from:asha invoice newer_than:7d\". Returns ids, senders, subjects and snippets.",
                    listOf("query" to "Gmail search"), listOf("query"),
                ) { google("gmail_search", it) }
                out += ToolSpec(
                    "gmail_read", "Read one email in full.",
                    listOf("id" to "Id from gmail_search"), listOf("id"),
                ) { google("gmail_read", it) }
                out += ToolSpec(
                    "gmail_send", "Send an email from the user's Gmail right away. Only when the user clearly asked to send it and gave or implied the recipient's address; otherwise save a draft.",
                    listOf("to" to "Email addresses, comma-separated", "subject" to "Subject", "body" to "Plain-text body", "cc" to "Optional cc addresses"),
                    listOf("to", "subject", "body"),
                ) { google("gmail_send", it) }
                out += ToolSpec(
                    "gmail_create_draft", "Save an email as a draft in the user's Gmail without sending it.",
                    listOf("to" to "Email addresses, optional", "subject" to "Subject", "body" to "Plain-text body"),
                    listOf("subject", "body"),
                ) { google("gmail_draft", it) }
            }
        }

        tokens["slackToken"]?.takeIf { it.isNotBlank() }?.let { t ->
            out += ToolSpec(
                "slack_list_channels", "List the Slack channels the user can see, with their ids and whether the user is a member.",
                emptyList(), emptyList(),
            ) { slackChannels(t) }
            out += ToolSpec(
                "slack_send_message", "Send a message to a Slack channel as the user. Only when the user asked to send or post it.",
                listOf("channel" to "Channel name like general or #general, or a channel id", "text" to "The message"), listOf("channel", "text"),
            ) { slackSend(t, it["channel"].orEmpty(), it["text"].orEmpty()) }
            out += ToolSpec(
                "slack_read_channel", "Read the latest messages in a Slack channel, newest first.",
                listOf("channel" to "Channel name or id"), listOf("channel"),
            ) { slackRead(t, it["channel"].orEmpty()) }
            out += ToolSpec(
                "slack_search", "Search the user's Slack messages. Supports Slack search syntax such as in:#channel or from:@name.",
                listOf("query" to "What to search for"), listOf("query"),
            ) { slackSearch(t, it["query"].orEmpty()) }
        }

        tokens["githubToken"]?.takeIf { it.isNotBlank() }?.let { t ->
            out += ToolSpec(
                "github_my_repos", "List the user's GitHub repositories, most recently updated first.",
                emptyList(), emptyList(),
            ) { githubRepos(t) }
            out += ToolSpec(
                "github_search", "Search GitHub issues and pull requests. Uses GitHub search syntax, e.g. \"repo:owner/name is:open login bug\". Add is:pr for pull requests.",
                listOf("query" to "GitHub search query"), listOf("query"),
            ) { githubSearch(t, it["query"].orEmpty()) }
            out += ToolSpec(
                "github_create_issue", "Open a new issue in a GitHub repository.",
                listOf("repo" to "owner/name", "title" to "Issue title", "body" to "Issue description"), listOf("repo", "title"),
            ) { githubCreateIssue(t, it["repo"].orEmpty(), it["title"].orEmpty(), it["body"].orEmpty()) }
        }

        tokens["todoistToken"]?.takeIf { it.isNotBlank() }?.let { t ->
            out += ToolSpec(
                "todoist_add_task", "Add a task to the user's Todoist inbox.",
                listOf("content" to "The task", "due" to "Due date in plain words, e.g. \"tomorrow 5pm\" or \"every monday\"; empty for none"), listOf("content"),
            ) { todoistAdd(t, it["content"].orEmpty(), it["due"].orEmpty()) }
            out += ToolSpec(
                "todoist_list_tasks", "List the user's Todoist tasks that match a filter.",
                listOf("filter" to "Todoist filter such as \"today | overdue\", \"tomorrow\" or \"#Work\"; empty means today and overdue"), emptyList(),
            ) { todoistList(t, it["filter"].orEmpty()) }
        }

        tokens["linearToken"]?.takeIf { it.isNotBlank() }?.let { t ->
            out += ToolSpec(
                "linear_search_issues", "Find Linear issues whose title contains the words; empty lists recently updated issues.",
                listOf("query" to "Words from the title"), emptyList(),
            ) { linearSearch(t, it["query"].orEmpty()) }
            out += ToolSpec(
                "linear_create_issue", "Create a Linear issue.",
                listOf("title" to "Issue title", "description" to "Markdown description", "team" to "Team key or name; empty uses the first team"), listOf("title"),
            ) { linearCreate(t, it["title"].orEmpty(), it["description"].orEmpty(), it["team"].orEmpty()) }
        }

        tokens["discordWebhook"]?.takeIf { it.startsWith("https://") || it.startsWith("http://127.0.0.1") }?.let { hook ->
            out += ToolSpec(
                "discord_send_message", "Post a message to the user's Discord channel (the one their webhook belongs to). Only when the user asked to post it.",
                listOf("text" to "The message, at most 2000 characters"), listOf("text"),
            ) { discordSend(hook, it["text"].orEmpty()) }
        }

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

    // ---------- places (OpenStreetMap Nominatim, no key needed) ----------

    private fun findPlace(query: String): String {
        val res = json.readTree(http("GET", "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=0&limit=3&q=" + enc(query),
            mapOf("User-Agent" to USER_AGENT), null))
        val lines = res.map { "${it.path("name").asText().ifBlank { "(unnamed)" }} | ${it.path("display_name").asText()} | lat ${it.path("lat").asText()}, lon ${it.path("lon").asText()}" }
        return if (lines.isEmpty()) "No place found for \"$query\". Try a simpler query or add the city." else lines.joinToString("\n")
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

    // ---------- Slack (Web API; answers 200 with "ok": false on errors) ----------

    private fun slack(token: String, method: String, query: String = "", body: Map<String, Any>? = null): JsonNode {
        val auth = mapOf("Authorization" to "Bearer $token")
        val url = "$slackBase/$method" + if (query.isNotEmpty()) "?$query" else ""
        val res = json.readTree(if (body == null) http("GET", url, auth, null) else http("POST", url, auth + ("Content-Type" to "application/json; charset=utf-8"), json.writeValueAsString(body)))
        if (!res.path("ok").asBoolean(false)) {
            val error = res.path("error").asText("unknown_error")
            throw RuntimeException(when (error) {
                "not_allowed_token_type" -> "Slack: this needs a user token (xoxp-). Paste the User OAuth Token on the dashboard"
                "missing_scope" -> "Slack: the token lacks the scope ${res.path("needed").asText()}. Add it to the Slack app and reinstall"
                "invalid_auth", "token_revoked", "account_inactive" -> "Slack rejected the token. Connect Slack again on the dashboard"
                "not_in_channel" -> "Slack: the user is not in that channel"
                "channel_not_found" -> "Slack: no channel by that name"
                else -> "Slack error: $error"
            })
        }
        return res
    }

    private fun slackChannelList(token: String): List<JsonNode> =
        slack(token, "conversations.list", "types=public_channel,private_channel&exclude_archived=true&limit=500").path("channels").toList()

    private fun slackChannels(token: String): String {
        val lines = slackChannelList(token).map { "#${it.path("name").asText()} | id ${it.path("id").asText()}${if (it.path("is_member").asBoolean()) " | member" else ""}" }
        return if (lines.isEmpty()) "No channels." else clip(lines.joinToString("\n"))
    }

    /** A channel name ("general", "#general") or id to an id. */
    private fun slackChannelId(token: String, channel: String): String {
        val c = channel.trim().removePrefix("#")
        if (Regex("^[CGD][A-Z0-9]{6,}$").matches(c)) return c
        return slackChannelList(token).firstOrNull { it.path("name").asText().equals(c, ignoreCase = true) }?.path("id")?.asText()
            ?: throw RuntimeException("Slack: no channel called #$c. Use slack_list_channels to see the names")
    }

    private fun slackSend(token: String, channel: String, text: String): String {
        val res = slack(token, "chat.postMessage", body = mapOf("channel" to slackChannelId(token, channel), "text" to text))
        val link = try { slack(token, "chat.getPermalink", "channel=${enc(res.path("channel").asText())}&message_ts=${enc(res.path("ts").asText())}").path("permalink").asText() } catch (e: Exception) { "" }
        return "Sent to #${channel.trim().removePrefix("#")}" + if (link.isNotEmpty()) " | $link" else ""
    }

    private fun slackRead(token: String, channel: String): String {
        val res = slack(token, "conversations.history", "limit=15&channel=" + enc(slackChannelId(token, channel)))
        val lines = res.path("messages").map { "${it.path("user").asText(it.path("username").asText("bot"))}: ${it.path("text").asText()}" }
        return if (lines.isEmpty()) "No messages." else clip(lines.joinToString("\n"))
    }

    private fun slackSearch(token: String, query: String): String {
        val res = slack(token, "search.messages", "count=10&query=" + enc(query))
        val lines = res.path("messages").path("matches").map {
            "#${it.path("channel").path("name").asText()} | ${it.path("username").asText()}: ${it.path("text").asText().take(300)} | ${it.path("permalink").asText()}"
        }
        return if (lines.isEmpty()) "Nothing found in Slack for \"$query\"." else clip(lines.joinToString("\n"))
    }

    // ---------- GitHub (REST) ----------

    private fun githubHeaders(token: String) = mapOf(
        "Authorization" to "Bearer $token", "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28", "User-Agent" to USER_AGENT,
    )

    private fun githubRepos(token: String): String {
        val res = json.readTree(http("GET", "$githubBase/user/repos?sort=updated&per_page=30", githubHeaders(token), null))
        val lines = res.map { "${it.path("full_name").asText()} | ${it.path("description").asText("").take(100)} | ${it.path("html_url").asText()}" }
        return if (lines.isEmpty()) "No repositories." else lines.joinToString("\n")
    }

    private fun githubSearch(token: String, query: String): String {
        // GitHub's issue search needs is:issue or is:pr in the query.
        val q = if (Regex("""\bis:(issue|pr|pull-request)\b""").containsMatchIn(query)) query else "$query is:issue"
        val res = json.readTree(http("GET", "$githubBase/search/issues?per_page=10&q=" + enc(q), githubHeaders(token), null))
        val lines = res.path("items").map {
            "${it.path("repository_url").asText().substringAfter("/repos/")}#${it.path("number").asInt()} | ${it.path("title").asText()} | ${it.path("state").asText()} | ${it.path("html_url").asText()}"
        }
        return if (lines.isEmpty()) "No issues found." else lines.joinToString("\n")
    }

    private fun githubCreateIssue(token: String, repo: String, title: String, body: String): String {
        val r = repo.trim().removePrefix("https://github.com/").trim('/')
        if (!Regex("^[\\w.-]+/[\\w.-]+$").matches(r)) throw RuntimeException("repo must look like owner/name")
        val res = json.readTree(http("POST", "$githubBase/repos/$r/issues", githubHeaders(token), json.writeValueAsString(mapOf("title" to title, "body" to body))))
        return "Created issue #${res.path("number").asInt()}: ${res.path("html_url").asText()}"
    }

    // ---------- Todoist (API v1) ----------

    private fun todoistAdd(token: String, content: String, due: String): String {
        val body = HashMap<String, Any>().apply { put("content", content); if (due.isNotBlank()) put("due_string", due) }
        val res = json.readTree(http("POST", "$todoistBase/tasks", mapOf("Authorization" to "Bearer $token"), json.writeValueAsString(body)))
        val when_ = res.path("due").path("string").asText("")
        return "Added: ${res.path("content").asText()}" + (if (when_.isNotEmpty()) " (due $when_)" else "") + " | https://app.todoist.com/app/task/${res.path("id").asText()}"
    }

    private fun todoistList(token: String, filter: String): String {
        val res = json.readTree(http("GET", "$todoistBase/tasks/filter?limit=30&query=" + enc(filter.ifBlank { "today | overdue" }), mapOf("Authorization" to "Bearer $token"), null))
        val items = if (res.isArray) res else res.path("results")
        val lines = items.map { "${it.path("content").asText()}${it.path("due").path("string").asText("").let { d -> if (d.isNotEmpty()) " | due $d" else "" }}" }
        return if (lines.isEmpty()) "No tasks match." else lines.joinToString("\n")
    }

    // ---------- Linear (GraphQL; personal API keys go in Authorization without "Bearer") ----------

    private fun linear(token: String, query: String, variables: Map<String, Any> = emptyMap()): JsonNode {
        val res = json.readTree(http("POST", linearUrl, mapOf("Authorization" to token), json.writeValueAsString(mapOf("query" to query, "variables" to variables))))
        res.path("errors").firstOrNull()?.let { throw RuntimeException("Linear: ${it.path("message").asText()}") }
        return res.path("data")
    }

    private fun linearSearch(token: String, query: String): String {
        val fields = "nodes { identifier title url state { name } }"
        val data = if (query.isBlank()) linear(token, "query { issues(first: 10, orderBy: updatedAt) { $fields } }")
        else linear(token, "query(\$q: String!) { issues(first: 10, filter: { title: { containsIgnoreCase: \$q } }) { $fields } }", mapOf("q" to query))
        val lines = data.path("issues").path("nodes").map { "${it.path("identifier").asText()} | ${it.path("title").asText()} | ${it.path("state").path("name").asText()} | ${it.path("url").asText()}" }
        return if (lines.isEmpty()) "No issues found." else lines.joinToString("\n")
    }

    private fun linearCreate(token: String, title: String, description: String, team: String): String {
        val teams = linear(token, "query { teams { nodes { id key name } } }").path("teams").path("nodes").toList()
        if (teams.isEmpty()) throw RuntimeException("Linear: this account has no teams")
        val t = team.trim()
        val chosen = teams.firstOrNull { t.isNotEmpty() && (it.path("key").asText().equals(t, true) || it.path("name").asText().equals(t, true)) } ?: teams[0]
        val res = linear(token, "mutation(\$input: IssueCreateInput!) { issueCreate(input: \$input) { success issue { identifier url } } }",
            mapOf("input" to mapOf("teamId" to chosen.path("id").asText(), "title" to title, "description" to description)))
        val issue = res.path("issueCreate").path("issue")
        return "Created ${issue.path("identifier").asText()} in ${chosen.path("name").asText()}: ${issue.path("url").asText()}"
    }

    // ---------- Discord (incoming webhook) ----------

    private fun discordSend(hook: String, text: String): String {
        http("POST", hook + (if ('?' in hook) "&" else "?") + "wait=true", mapOf("User-Agent" to USER_AGENT), json.writeValueAsString(mapOf("content" to text.take(2000))))
        return "Posted to Discord"
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
