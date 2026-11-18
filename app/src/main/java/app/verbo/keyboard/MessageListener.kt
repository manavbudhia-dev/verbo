package app.verbo.keyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads incoming chat notifications (WhatsApp, Telegram, Messages...) so auto actions run without copying.
 *
 * Off until the user turns on "Act on incoming messages" and grants notification access. Every message is
 * checked on the phone; only one that matches an auto action set to run automatically (a French message
 * and "Understand any language", say) is sent to the user's own AI key. The result arrives as a
 * notification and is waiting in the keyboard when they open the chat to reply.
 */
class MessageListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private var lastKey = ""

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName !in CHAT_APPS || n.packageName == packageName) return
        val store = Store(this)
        if (!store.watchMessages || !store.triggersOn || !store.assistantOn) return
        val message = read(n.notification) ?: return
        // Chat apps re-post the same notification as messages pile up: act once per message.
        val key = n.packageName + "|" + message.first + "|" + message.second
        if (key == lastKey) return
        lastKey = key
        handle(store, n.packageName, message.first, message.second)
    }

    private fun handle(store: Store, app: String, from: String, text: String) {
        store.incomingText = text.take(4000)
        store.incomingFrom = from
        store.incomingApp = app
        store.incomingAt = System.currentTimeMillis()
        store.incomingResult = ""
        store.incomingSkill = ""
        // A keyboard that is already open shows the new message straight away.
        main.post { onIncoming?.invoke(false) }
        val trigger = store.triggers.firstOrNull { it.auto && Cards.matches(it.kind, it.keyword, text) } ?: return
        val skill = store.triggerSkill(trigger) ?: return
        if (store.activeKey.isBlank() || store.overLimit) return
        val stamp = store.incomingAt
        val engine = Engine(store)
        val title = Cards.chipLabel(trigger, text)
        Thread {
            val result = try {
                engine.run(Ai.skillTask(skill.instruction), Ai.userMessage("", text), web = false, tools = emptyList(), label = skill.name)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (result == null || result.text.isBlank()) return@post
                // A newer message may have arrived meanwhile; only the matching one keeps this result.
                if (store.incomingAt == stamp) {
                    store.incomingResult = result.text
                    store.incomingSkill = skill.name
                    onIncoming?.invoke(true)
                }
                store.recordUse(store.triggerId(trigger))
                store.recordUse("skill:" + skill.name)
                notify(this, from.ifBlank { appName(app) }, title, result.text, app)
            }
        }.start()
    }

    companion object {
        /**
         * Set by the keyboard while it runs (same process): called on the main thread when a message
         * arrives (false) and when its automatic result is ready (true).
         */
        @Volatile var onIncoming: ((Boolean) -> Unit)? = null

        /** Messaging apps whose notifications carry a chat message. */
        val CHAT_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thunderdog.challegram",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "org.thoughtcrime.securesms",
            "com.instagram.android", "com.facebook.orca", "com.Slack", "com.microsoft.teams", "com.discord",
            "com.linkedin.android", "com.google.android.gm",
        )
        private const val CHANNEL = "incoming"
        private val SUMMARY = Regex("""^\d+ (new )?messages?( from \d+ chats?)?$""", RegexOption.IGNORE_CASE)

        /** The sender and the newest message in a chat notification, or null for summaries, calls and media-only posts. */
        fun read(n: Notification): Pair<String, String>? {
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
            val extras = n.extras ?: return null
            var from = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            // Messaging-style notifications keep each message with its sender; the newest is last.
            val messages = try {
                @Suppress("DEPRECATION") extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            } catch (e: Exception) { null }
            val last = messages?.lastOrNull() as? android.os.Bundle
            if (last != null) {
                last.getCharSequence("text")?.toString()?.takeIf { it.isNotBlank() }?.let { text = it }
                last.getCharSequence("sender")?.toString()?.takeIf { it.isNotBlank() }?.let { from = it }
            }
            text = text.trim()
            if (text.isEmpty() || SUMMARY.matches(text)) return null
            return from.trim() to text
        }

        /** True when the user has granted Verbo notification access in system settings. */
        fun hasAccess(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(context, MessageListener::class.java)
            return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
        }

        fun accessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private fun appName(pkg: String) = when (pkg) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "org.telegram.messenger", "org.thunderdog.challegram" -> "Telegram"
            "com.google.android.apps.messaging", "com.samsung.android.messaging" -> "Messages"
            "org.thoughtcrime.securesms" -> "Signal"
            else -> "a chat"
        }

        /** Posts what the auto action made of a message; tapping opens the chat app to reply. */
        fun notify(context: Context, from: String, title: String, body: String, app: String) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Auto actions on messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "What Verbo's auto actions made of an incoming message, such as a translation"
                })
            }
            val open = context.packageManager.getLaunchIntentForPackage(app) ?: Intent(context, MainActivity::class.java)
            val tap = PendingIntent.getActivity(context, app.hashCode(), open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_languages)
                .setContentTitle("$title — $from")
                .setContentText(body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            try { nm.notify(CHANNEL.hashCode() + app.hashCode(), notification) } catch (e: SecurityException) { }
        }
    }
}
