package app.verbo.keyboard

import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** [status] is the dashboard's HTTP status, or 0 when it could not be reached. */
class SyncException(message: String, val status: Int = 0) : Exception(message)

/** Pairs this keyboard with a Verbo dashboard and pulls skills, connections and settings from it. Blocking. */
object Sync {
    /** How often the automatic two-way refresh may run: once a minute. */
    const val EVERY_MS = 60_000L

    fun normalise(address: String): String {
        var url = address.trim().trimEnd('/')
        if (!url.contains("://")) url = "https://$url"
        if (!url.startsWith("https://")) throw SyncException("The dashboard address must start with https://")
        return url
    }

    /**
     * The page that starts "Continue with Google". The phone keeps a random verifier and sends only its hash;
     * the dashboard sends back a one-time code (to verbo://auth) that pairs only with the verifier.
     */
    fun googleSignInUrl(store: Store, address: String = store.serverUrl.ifEmpty { Store.DEFAULT_SERVER }): String {
        val url = normalise(address)
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val verifier = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        store.authVerifier = verifier
        store.authServer = url
        return "$url/api/auth/google?app=1&challenge=" + challenge(verifier)
    }

    fun challenge(verifier: String): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return android.util.Base64.encodeToString(hash, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    /** Finishes "Continue with Google": the code from verbo://auth pairs this phone with the account. */
    fun finishGoogle(store: Store, code: String) {
        val verifier = store.authVerifier
        if (verifier.isEmpty()) throw SyncException("Start the sign-in from the Verbo app")
        if (store.deviceToken.isNotEmpty()) store.unpair()
        pair(store, store.authServer.ifEmpty { Store.DEFAULT_SERVER }, code, verifier)
        store.authVerifier = ""
    }

    fun pair(store: Store, address: String, code: String, verifier: String? = null) {
        val url = normalise(address)
        val body = JSONObject().put("code", code.trim()).put("name", Build.MODEL ?: "Phone")
            .also { if (verifier != null) it.put("verifier", verifier) }
            .also { if (store.deviceId.isNotEmpty()) it.put("deviceId", store.deviceId) }.toString()
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
        store.lastSync = System.currentTimeMillis()
    }

    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "verbo-sync").apply { isDaemon = true } }
    private var queued: java.util.concurrent.ScheduledFuture<*>? = null

    /** Syncs in the background shortly after a change (several saves in a row make one request). */
    @Synchronized
    fun soon(store: Store) {
        queued?.cancel(false)
        queued = timer.schedule({ try { pull(store) } catch (e: Exception) { } }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** True when paired and a minute has passed since the last sync, so dashboard changes arrive on their own. */
    fun due(store: Store): Boolean = store.deviceToken.isNotEmpty() && System.currentTimeMillis() - store.lastSync >= EVERY_MS

    /** The automatic refresh: push phone changes, take the dashboard's copy, then call [done] on the main thread. */
    fun auto(store: Store, done: () -> Unit = {}) {
        if (!due(store)) return
        store.lastSync = System.currentTimeMillis() // don't retry on every call while offline
        timer.execute {
            try {
                pull(store)
            } catch (e: Exception) {
                // Keep using the last synced settings.
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post(done)
        }
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
