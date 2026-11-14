package app.verbo.keyboard

import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SyncException(message: String) : Exception(message)

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
        pull(store)
    }

    fun pull(store: Store) {
        if (store.deviceToken.isEmpty()) return
        val res = request("GET", store.serverUrl + "/api/device/config", store.deviceToken, null)
        store.applyRemote(res)
    }

    /** Asks the dashboard to create a Google Meet room in the owner's Google account. Returns the meeting link. */
    fun meet(store: Store): String {
        if (store.deviceToken.isEmpty()) throw SyncException("Pair with your dashboard first")
        val url = request("POST", store.serverUrl + "/api/device/meet", store.deviceToken, "{}").optString("url")
        if (!url.startsWith("https://meet.google.com/")) throw SyncException("The dashboard didn't return a Meet link")
        return url
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
            if (code !in 200..299) throw SyncException(json.optString("error").ifEmpty { "The dashboard answered with error $code" })
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
