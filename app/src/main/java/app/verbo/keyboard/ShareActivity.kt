package app.verbo.keyboard

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Where WhatsApp (or any app) hands Verbo a message or a file via Share. The share is kept in the
 * inbox so the keyboard can offer "Save to Notion" while the user is still in WhatsApp, and this
 * screen can also save or post it straight away.
 */
class ShareActivity : Activity() {
    private lateinit var store: Store
    private lateinit var look: Look
    private var status: TextView? = null
    private var actions: LinearLayout? = null
    private var draftCard: LinearLayout? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        look = Look(this)
        takeShare(intent)
        build()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeShare(intent)
        build()
    }

    /** Copies the shared stream into app-private storage and remembers it for the keyboard's inbox chip. */
    private fun takeShare(intent: Intent) {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val stream: Uri? = when {
            intent.action == Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
            else -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        val from = referrerPackage(intent) ?: "another app"
        var name = ""
        var mime = intent.type.orEmpty()
        var path = ""
        if (stream != null) {
            name = displayName(stream) ?: stream.lastPathSegment?.substringAfterLast('/') ?: "shared-file"
            name = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
            if (mime.isBlank() || mime == "*/*") mime = contentResolver.getType(stream).orEmpty()
            val dir = File(filesDir, "inbox").apply { mkdirs() }
            val target = File(dir, name)
            try {
                contentResolver.openInputStream(stream)?.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_SHARE_BYTES) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                path = target.absolutePath
            } catch (e: Exception) {
                path = ""
            }
        }
        store.inboxText = text
        store.inboxFile = path
        store.inboxName = name
        store.inboxMime = mime
        store.inboxFrom = from
        store.inboxAt = System.currentTimeMillis()
    }

    private fun referrerPackage(intent: Intent): String? {
        val ref = intent.getParcelableExtra<Uri>(Intent.EXTRA_REFERRER)?.toString()
            ?: referrer?.toString()
        val pkg = ref?.removePrefix("android-app://")?.substringBefore('/')?.takeIf { it.isNotBlank() && '.' in it }
        return pkg?.let {
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() } catch (e: Exception) {
                KNOWN[it] ?: it
            }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun build() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(look.dp(20), look.dp(24), look.dp(20), look.dp(24))
        }
        column.addView(BotView(this).apply { mood = BotView.Mood.HELLO },
            LinearLayout.LayoutParams(look.dp(72), look.dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        column.addView(look.text("Shared to Verbo", 22f, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(0, look.dp(10), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        column.addView(look.text("From ${store.inboxFrom} · waiting in your keyboard", 13.5f, look.muted).apply {
            gravity = Gravity.CENTER; setPadding(0, look.dp(4), 0, look.dp(16))
        }, LinearLayout.LayoutParams(-1, -2))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = look.shape(look.card, 18)
            setPadding(look.dp(16), look.dp(14), look.dp(16), look.dp(14))
        }
        if (store.inboxFile.isNotBlank()) {
            val f = File(store.inboxFile)
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(look.iconTile(if (store.inboxMime.startsWith("image/")) R.drawable.ic_image else R.drawable.ic_file_text,
                look.blueBg, look.blueTint, 44, 22))
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(look.dp(12), 0, 0, 0)
                addView(look.text(store.inboxName.ifBlank { "Shared file" }, 15f, bold = true))
                addView(look.text("${String.format("%.1f", f.length() / 1024.0 / 1024.0)} MB · ${store.inboxMime.ifBlank { "file" }}", 12.5f, look.muted))
            })
            card.addView(row)
        }
        if (store.inboxText.isNotBlank()) {
            if (card.childCount > 0) card.addView(View(this).apply { setBackgroundColor(look.line) },
                LinearLayout.LayoutParams(-1, look.dp(1)).apply { topMargin = look.dp(12); bottomMargin = look.dp(12) })
            card.addView(look.text(store.inboxText.take(600), 14.5f, look.violetInk).apply {
                maxLines = 8; ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        if (card.childCount == 0) card.addView(look.text("Nothing came through with that share. Try sharing again from WhatsApp.", 14f, look.muted))
        column.addView(card, LinearLayout.LayoutParams(-1, -2))

        status = look.text("", 13.5f, look.muted).apply { setPadding(look.dp(4), look.dp(12), look.dp(4), 0) }
        column.addView(status, LinearLayout.LayoutParams(-1, -2))
        draftCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = look.shape(look.card, 18)
            setPadding(look.dp(16), look.dp(14), look.dp(16), look.dp(14))
            visibility = View.GONE
        }
        column.addView(draftCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.dp(12) })

        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, look.dp(16), 0, 0) }
        column.addView(actions, LinearLayout.LayoutParams(-1, -2))
        paintActions()

        val scroll = ScrollView(this).apply {
            background = PaperWaves(look.bg)
            addView(column)
        }
        setContentView(scroll)
    }

    private fun paintActions() {
        val box = actions ?: return
        box.removeAllViews()
        fun pill(label: String, icon: Int?, filled: Boolean, onTap: () -> Unit) {
            val v = if (filled) look.purplePill(label, icon, onTap) else look.softPill(label, onTap)
            box.addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = look.dp(10) })
        }
        pill("Save to Notion", R.drawable.ic_brand_notion, true) { saveToNotion() }
        pill("Draft a LinkedIn post", R.drawable.ic_brand_linkedin, false) { draftLinkedIn() }
        if (store.inboxText.isNotBlank()) pill("Copy the text", null, false) {
            copy(store.inboxText); say("Copied. Paste it anywhere.")
        }
        pill("Done — keep it for the keyboard", null, false) { finish() }
    }

    private fun say(message: String) {
        status?.text = message
    }

    private fun saveToNotion() {
        if (busy) return
        if (store.notionToken.isBlank()) {
            say("Connect Notion first: paste an integration token and pick where saves land.")
            actions?.addView(look.darkPill("Connect Notion", R.drawable.ic_plug) {
                startActivity(Intent(this, PageActivity::class.java).putExtra("page", "notion"))
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = look.dp(10) })
            return
        }
        busy = true
        say("Saving to Notion…")
        val token = store.notionToken
        val parent = store.notionParent
        val isDb = store.notionParentIsDb
        val text = store.inboxText
        val file = store.inboxFile.takeIf { it.isNotBlank() }?.let { File(it) }
        val mime = store.inboxMime
        val title = (text.lines().firstOrNull { it.isNotBlank() } ?: store.inboxName.ifBlank { "Shared from ${store.inboxFrom}" })
            .take(70)
        val content = text.ifBlank { "Shared from ${store.inboxFrom}: ${store.inboxName}" }
        Thread {
            val outcome = try {
                val url = Connections.notionSave(token, parent, isDb, title, content, file, mime)
                if (url.startsWith("http")) "Saved to Notion." to url else url to null
            } catch (e: Exception) {
                "Notion didn't accept it: ${e.message?.take(120)}" to null
            }
            runOnUiThread {
                busy = false
                if (isFinishing) return@runOnUiThread
                say(outcome.first)
                outcome.second?.let { url ->
                    store.clearInbox()
                    actions?.addView(look.darkPill("Open in Notion", R.drawable.ic_link) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = look.dp(10) })
                }
            }
        }.start()
    }

    private fun draftLinkedIn() {
        if (busy) return
        if (store.activeKey.isBlank()) {
            say("Add your ${store.providerName} API key in Verbo to draft posts.")
            return
        }
        busy = true
        say("Drafting your LinkedIn post…")
        val engine = Engine(store)
        val input = store.inboxText.ifBlank { "A file shared from ${store.inboxFrom}: ${store.inboxName}" }
        Thread {
            val result = try {
                engine.run(Ai.LINKEDIN_TASK, input, web = false, tools = emptyList(), label = "LinkedIn post")
            } catch (e: Exception) {
                ActionResult("The draft failed: ${e.message?.take(120)}", null)
            }
            runOnUiThread {
                busy = false
                if (isFinishing) return@runOnUiThread
                say("Draft ready. Share it or copy it.")
                val card = draftCard ?: return@runOnUiThread
                card.removeAllViews()
                card.visibility = View.VISIBLE
                card.addView(look.text(result.text, 14.5f, look.violetInk))
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, look.dp(12), 0, 0) }
                row.addView(look.purplePill("Share to LinkedIn", R.drawable.ic_brand_linkedin) { shareLinkedIn(result.text) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = look.dp(8) })
                row.addView(look.softPill("Copy") { copy(result.text); say("Draft copied.") }, LinearLayout.LayoutParams(0, -2, 1f))
                card.addView(row)
            }
        }.start()
    }

    private fun shareLinkedIn(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val linked = packageManager.queryIntentActivities(send, 0).firstOrNull { it.activityInfo.packageName == "com.linkedin.android" }
        if (linked != null) {
            startActivity(send.setPackage("com.linkedin.android").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                "https://www.linkedin.com/feed/?shareActive=true&text=" + Uri.encode(text))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Verbo", text))
    }

    companion object {
        private const val MAX_SHARE_BYTES = 20L * 1024 * 1024
        private val KNOWN = mapOf(
            "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram", "com.linkedin.android" to "LinkedIn",
            "com.instagram.android" to "Instagram", "com.google.android.gm" to "Gmail",
            "com.slack" to "Slack", "com.twitter.android" to "X",
        )
    }
}