package app.verbo.keyboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** Two-way sync against a stand-in dashboard: phone changes must survive the download. */
@RunWith(RobolectricTestRunner::class)
class SyncTest {
    private lateinit var server: ServerSocket
    private lateinit var store: Store
    /** The dashboard's copy of the config. */
    private var remote = JSONObject()
    private val puts = ArrayList<JSONObject>()
    private var refuse = false

    @Before
    fun setUp() {
        remote = JSONObject().put("apiKey", "sk-ant-dashboard-0000").put("model", Ai.DEFAULT_MODEL).put("provider", "anthropic")
            .put("skills", JSONArray()).put("keyboard", JSONObject().put("theme", "system"))
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                socket.use { serve(it) }
            }
        }.apply { isDaemon = true }.start()
        store = Store(RuntimeEnvironment.getApplication())
        store.serverUrl = "http://127.0.0.1:${server.localPort}"
        store.deviceToken = "token"
        Sync.pull(store)
    }

    @After
    fun tearDown() = server.close()

    /** A minimal HTTP/1.1 handler: GET returns the dashboard copy, PUT merges the body into it first. */
    private fun serve(socket: Socket) {
        val input = socket.getInputStream().bufferedReader()
        val method = input.readLine()?.substringBefore(' ') ?: return
        var length = 0
        while (true) {
            val line = input.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
        }
        val raw = CharArray(length).also { var read = 0; while (read < length) read += input.read(it, read, length - read) }
        var status = 200
        var out = ""
        synchronized(this) {
            if (method == "PUT") {
                val body = JSONObject(String(raw))
                puts += body
                if (refuse && body.has("model")) { status = 400; out = "{\"error\":\"Unknown model\"}" }
                else body.keys().forEach { k ->
                    if (k == "keyboard") body.getJSONObject(k).let { kb -> kb.keys().forEach { remote.getJSONObject("keyboard").put(it, kb.get(it)) } }
                    else remote.put(k, body.get(k))
                }
            }
            if (status == 200) out = remote.toString()
        }
        val bytes = out.toByteArray()
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    @Test
    fun keyTypedOnThePhoneIsUploadedNotOverwritten() {
        store.apiKey = "sk-ant-phone-1234"
        assertTrue("apiKey" in store.pending)
        Sync.pull(store)
        assertEquals("sk-ant-phone-1234", store.apiKey)
        assertEquals("sk-ant-phone-1234", remote.getString("apiKey"))
        assertEquals("sk-ant-phone-1234", puts.last().getString("apiKey"))
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun unchangedSavesAreNotPending() {
        store.apiKey = store.apiKey
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun pendingFieldsAreKeptWhenTheDashboardIsNewer() {
        store.customKey = "sk-gw-phone-5678"
        store.applyRemote(JSONObject(remote.toString()).put("customKey", "").put("apiKey", "sk-ant-new-9999"))
        assertEquals("sk-gw-phone-5678", store.customKey)
        assertEquals("sk-ant-new-9999", store.apiKey)
    }

    @Test
    fun keyboardSettingsFromTheDashboardAreApplied() {
        remote.put("keyboard", JSONObject().put("theme", "ocean").put("heightPercent", 110).put("numberRow", true)
            .put("keyShape", "nonsense").put("wheel", JSONArray(listOf("ai", "bogus", "voice", "skill:Polish"))))
        Sync.pull(store)
        assertEquals("ocean", store.theme)
        assertEquals(110, store.heightPercent)
        assertEquals(true, store.numberRow)
        assertEquals("raised", store.keyShape)
        assertEquals(listOf("ai", "voice", "skill:Polish"), store.wheel)
    }

    @Test
    fun keyboardChangesOnThePhoneGoUp() {
        store.theme = "rose"
        store.soundVolume = 30
        Sync.pull(store)
        assertEquals("rose", remote.getJSONObject("keyboard").getString("theme"))
        assertEquals(30, remote.getJSONObject("keyboard").getInt("soundVolume"))
        assertEquals("rose", store.theme)
    }

    @Test
    fun aRefusedSettingDoesNotBlockTheKey() {
        refuse = true
        store.model = "claude-something-old"
        store.apiKey = "sk-ant-phone-4321"
        Sync.pull(store)
        assertEquals("sk-ant-phone-4321", remote.getString("apiKey"))
        assertEquals("sk-ant-phone-4321", store.apiKey)
        assertEquals(Ai.DEFAULT_MODEL, store.model)
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun pairingUploadsKeysTheDashboardLacks() {
        store.unpair()
        remote.put("apiKey", "").put("customKey", "")
        store.provider = "custom"
        store.customKey = "sk-gw-local-2468"
        val missing = store.missingOnDashboard(remote)
        assertTrue("customKey" in missing && "provider" in missing && "customModel" in missing)
        store.serverUrl = "http://127.0.0.1:${server.localPort}"
        store.deviceToken = "token"
        store.markPending(missing)
        Sync.pull(store)
        assertEquals("sk-gw-local-2468", remote.getString("customKey"))
        assertEquals("custom", remote.getString("provider"))
        assertEquals("sk-gw-local-2468", store.customKey)
    }
}
