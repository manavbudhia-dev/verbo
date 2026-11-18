package app.verbo.keyboard

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** The app tools (Slack, GitHub, Todoist, Linear, Discord) against a stand-in server: the right requests, readable answers. */
@RunWith(RobolectricTestRunner::class)
class ConnectionsTest {
    private class Req(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private lateinit var server: ServerSocket
    private val requests = ArrayList<Req>()
    /** Path prefix (without the query) -> JSON reply. */
    private val replies = HashMap<String, String>()
    private lateinit var base: String

    @Before
    fun setUp() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                socket.use { serve(it) }
            }
        }.apply { isDaemon = true }.start()
        base = "http://127.0.0.1:${server.localPort}"
        Connections.slackBase = "$base/slack"
        Connections.githubBase = "$base/github"
        Connections.todoistBase = "$base/todoist"
        Connections.linearUrl = "$base/linear"
        Connections.notionBase = "$base/notion"
    }

    @After
    fun tearDown() {
        server.close()
        Connections.slackBase = "https://slack.com/api"
        Connections.githubBase = "https://api.github.com"
        Connections.todoistBase = "https://api.todoist.com/api/v1"
        Connections.linearUrl = "https://api.linear.app/graphql"
        Connections.notionBase = "https://api.notion.com/v1"
    }

    private fun serve(socket: Socket) {
        val input = java.io.BufferedInputStream(socket.getInputStream())
        fun line(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
            }
            return sb.toString()
        }
        val first = line() ?: return
        val headers = HashMap<String, String>()
        var length = 0
        while (true) {
            val line = line() ?: return
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
        }
        // Content-Length counts bytes, not characters: a body with non-ASCII text must be read as bytes.
        val raw = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(raw, read, length - read)
            if (n < 0) break
            read += n
        }
        val path = first.split(' ')[1]
        val req = Req(first.substringBefore(' '), path, headers, String(raw, Charsets.UTF_8))
        val out = synchronized(this) {
            requests += req
            // A Linear call is told apart by what it asks for.
            if (path.startsWith("/linear")) replies.entries.firstOrNull { req.body.contains(it.key) }?.value ?: "{}"
            else replies.entries.filter { path.startsWith(it.key) }.maxByOrNull { it.key.length }?.value ?: "{}"
        }
        val bytes = out.toByteArray()
        socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    private fun tools(vararg tokens: Pair<String, String>) =
        Connections.tools(weather = false, notionToken = "", calendlyToken = "", apis = emptyList(), places = false, tokens = tokens.toMap()).associateBy { it.name }

    @Test
    fun onlyConnectedAppsGiveTools() {
        assertTrue(tools().isEmpty())
        val names = tools("slackToken" to "xoxp-1", "githubToken" to "", "todoistToken" to "t").keys
        assertTrue("slack_send_message" in names && "todoist_add_task" in names)
        assertFalse(names.any { it.startsWith("github_") })
    }

    @Test
    fun slackSendFindsTheChannelByName() {
        replies["/slack/conversations.list"] = """{"ok":true,"channels":[{"id":"C0RANDOM1","name":"random"},{"id":"C0GENERAL","name":"general","is_member":true}]}"""
        replies["/slack/chat.postMessage"] = """{"ok":true,"channel":"C0GENERAL","ts":"1.2"}"""
        replies["/slack/chat.getPermalink"] = """{"ok":true,"permalink":"https://x.slack.com/archives/C0GENERAL/p12"}"""
        val result = tools("slackToken" to "xoxp-secret")["slack_send_message"]!!.run(mapOf("channel" to "#General", "text" to "Standup in 5"))
        val post = requests.first { it.path.startsWith("/slack/chat.postMessage") }
        assertEquals("POST", post.method)
        assertEquals("Bearer xoxp-secret", post.headers["authorization"])
        assertEquals("C0GENERAL", JSONObject(post.body).getString("channel"))
        assertEquals("Standup in 5", JSONObject(post.body).getString("text"))
        assertTrue(result, result.contains("https://x.slack.com/archives/C0GENERAL/p12"))
    }

    @Test
    fun slackErrorsBecomePlainAdvice() {
        replies["/slack/search.messages"] = """{"ok":false,"error":"not_allowed_token_type"}"""
        val e = runCatching { tools("slackToken" to "xoxb-bot")["slack_search"]!!.run(mapOf("query" to "budget")) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("user token"))
    }

    @Test
    fun githubIssueIsCreatedInTheRepo() {
        replies["/github/repos/acme/app/issues"] = """{"number":42,"html_url":"https://github.com/acme/app/issues/42"}"""
        val result = tools("githubToken" to "ghp_x")["github_create_issue"]!!.run(mapOf("repo" to "https://github.com/acme/app", "title" to "Login fails", "body" to "On Android 15"))
        val req = requests.single()
        assertEquals("POST", req.method)
        assertEquals("Bearer ghp_x", req.headers["authorization"])
        assertTrue(req.headers["user-agent"].orEmpty().startsWith("VerboKeyboard"))
        assertEquals("Login fails", JSONObject(req.body).getString("title"))
        assertTrue(result.contains("#42") && result.contains("issues/42"))
    }

    @Test
    fun githubSearchAddsTheRequiredQualifier() {
        replies["/github/search/issues"] = """{"items":[{"number":7,"title":"Crash","state":"open","html_url":"https://github.com/a/b/issues/7","repository_url":"https://api.github.com/repos/a/b"}]}"""
        val result = tools("githubToken" to "ghp_x")["github_search"]!!.run(mapOf("query" to "crash"))
        assertTrue(requests.single().path.contains("is%3Aissue"))
        assertEquals("a/b#7 | Crash | open | https://github.com/a/b/issues/7", result)
    }

    @Test
    fun todoistTaskGetsItsDueDate() {
        replies["/todoist/tasks"] = """{"id":"6X7","content":"Call the bank","due":{"string":"tomorrow 5pm"}}"""
        val result = tools("todoistToken" to "td")["todoist_add_task"]!!.run(mapOf("content" to "Call the bank", "due" to "tomorrow 5pm"))
        val body = JSONObject(requests.single().body)
        assertEquals("tomorrow 5pm", body.getString("due_string"))
        assertEquals("Bearer td", requests.single().headers["authorization"])
        assertTrue(result, result.startsWith("Added: Call the bank (due tomorrow 5pm)"))
    }

    @Test
    fun linearIssueGoesToTheNamedTeam() {
        replies["teams"] = """{"data":{"teams":{"nodes":[{"id":"t1","key":"ENG","name":"Engineering"},{"id":"t2","key":"DES","name":"Design"}]}}}"""
        replies["issueCreate"] = """{"data":{"issueCreate":{"success":true,"issue":{"identifier":"DES-9","url":"https://linear.app/x/issue/DES-9"}}}}"""
        val result = tools("linearToken" to "lin_api_x")["linear_create_issue"]!!.run(mapOf("title" to "New icon", "team" to "des"))
        val create = requests.last()
        assertEquals("lin_api_x", create.headers["authorization"]) // personal keys go without "Bearer"
        assertEquals("t2", JSONObject(create.body).getJSONObject("variables").getJSONObject("input").getString("teamId"))
        assertEquals("Created DES-9 in Design: https://linear.app/x/issue/DES-9", result)
    }

    @Test
    fun discordPostsThroughTheWebhook() {
        val result = tools("discordWebhook" to "$base/api/webhooks/1/abc")["discord_send_message"]!!.run(mapOf("text" to "Game night at 9"))
        val req = requests.single()
        assertEquals("/api/webhooks/1/abc?wait=true", req.path)
        assertEquals("Game night at 9", JSONObject(req.body).getString("content"))
        assertEquals("Posted to Discord", result)
    }

    @Test
    fun googleToolsFollowWhatTheSignInAllows() {
        val calls = ArrayList<Pair<String, Map<String, String>>>()
        val google: (String, Map<String, String>) -> String = { a, args -> calls += a to args; "ok:$a" }
        val meetOnly = Connections.tools(false, "", "", emptyList(), false, googleServices = setOf("meet"), google = google).map { it.name }
        assertEquals(listOf("google_meet_link"), meetOnly)
        val all = Connections.tools(false, "", "", emptyList(), false, googleServices = setOf("meet", "calendar", "gmail"), google = google).associateBy { it.name }
        assertTrue(all.keys.containsAll(listOf("google_calendar_create", "gmail_send", "gmail_create_draft", "gmail_search")))
        assertEquals("ok:gmail_draft", all["gmail_create_draft"]!!.run(mapOf("subject" to "Hi", "body" to "x")))
        assertEquals("calendar_create" to mapOf("title" to "Lunch"), all["google_calendar_create"]!!.run(mapOf("title" to "Lunch")).let { calls.last() })
    }

    @Test
    fun notionSaveLandsUnderTheChosenPage() {
        replies["/notion/pages"] = """{"url":"https://notion.so/My-Page-abc"}"""
        val url = Connections.notionSave("ntn_x", "parent-1", false, "Invoice from Acme", "Amount due: 500\nPay by Friday")
        assertEquals("https://notion.so/My-Page-abc", url)
        val req = requests.single()
        assertEquals("POST", req.method)
        assertEquals("/notion/pages", req.path)
        assertEquals("Bearer ntn_x", req.headers["authorization"])
        assertEquals("2022-06-28", req.headers["notion-version"])
        val body = JSONObject(req.body)
        assertEquals("parent-1", body.getJSONObject("parent").getString("page_id"))
        assertEquals("Invoice from Acme", body.getJSONObject("properties").getJSONObject("title").getJSONArray("title").getJSONObject(0).getJSONObject("text").getString("content"))
        assertEquals(2, body.getJSONArray("children").length())
    }

    @Test
    fun notionSaveCanTargetADatabase() {
        replies["/notion/pages"] = """{"url":"https://notion.so/db-row"}"""
        Connections.notionSave("ntn_x", "db-7", true, "New lead", "Met at the expo")
        assertEquals("db-7", JSONObject(requests.single().body).getJSONObject("parent").getString("database_id"))
    }

    @Test
    fun notionSaveFindsAnInboxPageWhenNoDestinationIsSet() {
        replies["/notion/search"] = """{"results":[{"object":"page","id":"inbox-1","properties":[{"type":"title","title":[{"plain_text":"Inbox"}]}],"url":"https://notion.so/inbox"}]}"""
        replies["/notion/pages"] = """{"url":"https://notion.so/saved"}"""
        Connections.notionSave("ntn_x", "", false, "Note", "body")
        assertEquals(listOf("/notion/search", "/notion/pages"), requests.map { it.path })
        assertEquals("inbox-1", JSONObject(requests[1].body).getJSONObject("parent").getString("page_id"))
    }

    @Test
    fun notionSaveUploadsTheSharedFile() {
        val file = java.io.File.createTempFile("verbo-share", ".pdf").apply { writeText("%PDF-1.4 test"); deleteOnExit() }
        replies["/notion/file_uploads"] = """{"id":"up-1","upload_url":"$base/notion/send/up-1"}"""
        replies["/notion/send/up-1"] = """{"id":"up-1","status":"uploaded"}"""
        replies["/notion/pages"] = """{"url":"https://notion.so/with-file"}"""
        val url = Connections.notionSave("ntn_x", "p1", false, "Report", "See attached", file, "application/pdf")
        assertEquals("https://notion.so/with-file", url)
        assertEquals(listOf("/notion/file_uploads", "/notion/send/up-1", "/notion/pages"), requests.map { it.path })
        assertTrue(requests[1].headers["content-type"]!!.startsWith("multipart/form-data"))
        assertTrue(requests[1].body.contains("filename=\""))
        val block = JSONObject(requests[2].body).getJSONArray("children").getJSONObject(1)
        assertEquals("file", block.getString("type"))
        assertEquals("up-1", block.getJSONObject("file").getJSONObject("file_upload").getString("id"))
    }

    @Test
    fun notionBigFileStaysOnThePhoneAndIsNamed() {
        val file = java.io.File.createTempFile("verbo-big", ".zip").apply {
            deleteOnExit()
            java.io.RandomAccessFile(this, "rw").use { it.setLength(6L * 1024 * 1024) }
        }
        replies["/notion/pages"] = """{"url":"https://notion.so/note"}"""
        Connections.notionSave("ntn_x", "p1", false, "Big zip", "body", file, "application/zip")
        assertEquals(listOf("/notion/pages"), requests.map { it.path })
        assertTrue(requests.single().body.contains("5 MB"))
    }

    @Test
    fun notionTestNamesTheIntegration() {
        replies["/notion/users/me"] = """{"name":"Verbo Bot"}"""
        assertEquals("Connected as Verbo Bot", Connections.notionTest("ntn_x"))
    }

    @Test
    fun notionToolSavesUnderTheStoredDestination() {
        replies["/notion/pages"] = """{"url":"https://notion.so/x"}"""
        val t = Connections.tools(false, "ntn_x", "", emptyList(), false, emptyMap(), emptySet(), "stored-9", false).associateBy { it.name }
        t["notion_create_page"]!!.run(mapOf("title" to "T", "content" to "c"))
        assertEquals("stored-9", JSONObject(requests.single().body).getJSONObject("parent").getString("page_id"))
    }
}
