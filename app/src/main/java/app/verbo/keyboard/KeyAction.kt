package app.verbo.keyboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Hold [key] to run [action], in the app with package [app] or in every app when [app] is empty.
 * [action] is "skill:Name", "act:Name" (a ready-made act), "tool:id" (a wheel tool) or "text:words" (types the words).
 * Kept in config.keymap on the dashboard; see cleanKeymap in dashboard/server.js.
 */
data class KeyAction(val key: Char, val app: String, val action: String) {
    companion object {
        private val TOOLS get() = Store.WHEEL_TOOLS.map { it.first }

        fun parse(arr: JSONArray): List<KeyAction> {
            val seen = HashSet<String>()
            return (0 until arr.length()).mapNotNull {
                val o = arr.optJSONObject(it) ?: return@mapNotNull null
                val key = o.optString("key").trim().lowercase().singleOrNull()?.takeIf { c -> c in 'a'..'z' } ?: return@mapNotNull null
                val app = o.optString("app").trim().takeIf { a -> Regex("[A-Za-z0-9._]{0,100}").matches(a) } ?: return@mapNotNull null
                val action = o.optString("action").take(2100)
                val type = action.substringBefore(':', "")
                val rest = action.substringAfter(':', "")
                val ok = rest.isNotBlank() && when (type) { "skill", "act" -> rest.length <= 40; "tool" -> rest in TOOLS; "text" -> true; else -> false }
                if (!ok || !seen.add("$key@$app")) null else KeyAction(key, app, action)
            }.take(80)
        }

        fun json(list: List<KeyAction>) = JSONArray().also { arr ->
            list.forEach { arr.put(JSONObject().put("key", it.key.toString()).put("app", it.app).put("action", it.action)) }
        }

        /** What holding [letter] does in the app [pkg]: that app's key action, then an every-app one, then the skill bound to the letter. */
        fun resolve(map: List<KeyAction>, skills: List<Skill>, letter: Char, pkg: String?): String? {
            val l = letter.lowercaseChar()
            if (!pkg.isNullOrEmpty()) map.firstOrNull { it.key == l && it.app == pkg }?.let { return it.action }
            map.firstOrNull { it.key == l && it.app.isEmpty() }?.let { return it.action }
            return skills.firstOrNull { it.key == l.toString() }?.let { "skill:" + it.name }
        }

        /** The letters that do something when held in [pkg]: these are lit on the keyboard. */
        fun bound(map: List<KeyAction>, skills: List<Skill>, pkg: String?): Set<Char> =
            ('a'..'z').filter { resolve(map, skills, it, pkg) != null }.toSet()

        /** A short name for an action, for lists and toasts. */
        fun label(action: String, skills: List<Skill>): String {
            val rest = action.substringAfter(':', "")
            return when (action.substringBefore(':', "")) {
                "skill" -> skills.firstOrNull { it.name == rest }?.let { (it.emoji + " " + it.name).trim() } ?: rest
                "act" -> (Acts.BUILTIN.firstOrNull { it.name == rest }?.emoji ?: "⚡") + " " + rest
                "tool" -> Store.WHEEL_TOOLS.firstOrNull { it.first == rest }?.second ?: rest
                else -> "“" + (if (rest.length > 24) rest.take(24) + "…" else rest) + "”"
            }
        }

        /** Apps offered when choosing one; any other app can be picked from the installed list. */
        val COMMON_APPS = listOf(
            "" to "All apps", "com.whatsapp" to "WhatsApp", "com.google.android.gm" to "Gmail", "com.android.chrome" to "Chrome",
            "com.instagram.android" to "Instagram", "org.telegram.messenger" to "Telegram", "com.Slack" to "Slack",
            "com.linkedin.android" to "LinkedIn", "com.twitter.android" to "X", "com.google.android.apps.messaging" to "Messages",
            "com.microsoft.office.outlook" to "Outlook", "com.discord" to "Discord",
        )
        fun appName(pkg: String) = COMMON_APPS.firstOrNull { it.first == pkg }?.second ?: pkg.substringAfterLast('.')
    }
}
