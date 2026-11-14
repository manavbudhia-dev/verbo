package app.verbo.keyboard

import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** [status] is the dashboard's HTTP status, or 0 when it could not be reached. */
class SyncException(message: String, val status: Int = 0) : Exception(message)

/** Pairs this keyboard with a Verbo dashboard and pulls skills, connections and settings from it. Blocking. */
object Sync {
    const val STALE_MS = 10 * 60 * 1000L

    fun normalise(address: String): String {
        var url = address.trim().trimEnd('/')
        if (!url.contains("://")) url = "https://$url"
        if (!url.startsWith("https://")) throw SyncException("The dashboard address must start with https://")
        return url
    }

    fun pair(store: Store, address: String, code: String) {
        val url = normalise(address)
        val body = JSONObject().put("code", code.trim()).put("name", Build.MODEL ?: "Phone").toString()
        val res = request("POST", "$url/api/device/pair", null, body)
        store.serverUrl = url
        store.deviceToken = res.getString("token")
        // Keys typed on the phone before pairing go up instead of being wiped by an empty dashboard.
        val remote = request("GET", "$url/api/device/config", store.deviceToken, null)
        store.markPending(store.missingOnDashboard(remote))
        pull(store)
    }

    /**
     * Sends changes made on the phone first, then takes the dashboard's copy of everything else.
     * Fields still pending (changed again during the upload, or refused) are never overwritten.
     */
    fun pull(store: Store) {
        if (store.deviceToken.isEmpty()) return
        val config = store.serverUrl + "/api/device/config"
        if (store.pending.isNotEmpty()) {
            var (body, sent) = store.pendingUpload()
            val res = try {
                try {
                    request("PUT", config, store.deviceToken, body.toString())
                } catch (e: SyncException) {
                    if (e.status != 400) throw e
                    // The dashboard refused a setting (an older server, say). Keep the keys, let the rest go.
                    store.settle(sent.filterKeys { it !in Store.SECRETS })
                    store.pendingUpload().also { body = it.first; sent = it.second }
                    if (sent.isEmpty()) request("GET", config, store.deviceToken, null)
                    else request("PUT", config, store.deviceToken, body.toString())
                }
            } catch (e: SyncException) {
                // Still refresh what the phone did not change, then report the problem.
                store.applyRemote(request("GET", config, store.deviceToken, null))
                throw e
            }
            store.settle(sent)
            store.applyRemote(res)
        } else {
            store.applyRemote(request("GET", config, store.deviceToken, null))
        }
    }

    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "verbo-sync").apply { isDaemon = true } }
    private var queued: java.util.concurrent.ScheduledFuture<*>? = null

    /** Syncs in the background shortly after a change (several saves in a row make one request). */
    @Synchronized
    fun soon(store: Store) {
        queued?.cancel(false)
        queued = timer.schedule({ try { pull(store) } catch (e: Exception) { } }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Asks the dashboard to create a Google Meet room in the owner's Google account. Returns the meeting link. */
    fun meet(store: Store): String {
        if (store.deviceToken.isEmpty()) throw SyncException("Pair with your dashboard first")
        val url = request("POST", store.serverUrl + "/api/device/meet", store.deviceToken, "{}").optString("url")
        if (!url.startsWith("https://meet.google.com/")) throw SyncException("The dashboard didn't return a Meet link")
        return url
    }

    /** One Calendar or Gmail action done by the dashboard on the owner's Google sign-in. Returns text for the assistant. */
    fun google(store: Store, action: String, args: Map<String, String>): String {
        if (store.deviceToken.isEmpty()) throw SyncException("Pair with your dashboard first")
        val body = JSONObject().put("action", action).put("timeZone", java.util.TimeZone.getDefault().id)
        args.forEach { (k, v) -> body.put(k, v) }
        return request("POST", store.serverUrl + "/api/device/google", store.deviceToken, body.toString()).optString("result")
    }

    private fun request(method: String, url: String, token: String?, body: String?): JSONObject {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw SyncException("That address doesn't look right")
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            if (code !in 200..299) throw SyncException(json.optString("error").ifEmpty { "The dashboard answered with error $code" }, code)
            return json
        } catch (e: SyncException) {
            throw e
        } catch (e: Exception) {
            throw SyncException("Couldn't reach the dashboard")
        } finally {
            conn.disconnect()
        }
    }
}
