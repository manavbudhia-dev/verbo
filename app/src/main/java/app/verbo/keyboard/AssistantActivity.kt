package app.verbo.keyboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Verbo, the personal assistant: a chat with the mascot. It follows commands with the same tools as the
 * keyboard (web search, weather, places, Notion, Calendar, Gmail, Slack and the rest the user connected),
 * remembers the conversation, and does things on the phone: a calendar event is saved, a WhatsApp or email
 * draft, a map or a page opens with one tap (or at once when that is the whole point of the request).
 */
class AssistantActivity : Activity() {
    private lateinit var store: Store
    private lateinit var look: Look
    private val main = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var header: BotView
    private lateinit var status: TextView
    private var busy = false
    private var generation = 0

    /** One line of the conversation; [link] is a phone action the reply offers (calendar, draft, map...). */
    class Msg(val fromUser: Boolean, val text: String, val link: String? = null)

    private val history = ArrayList<Msg>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        look = Look(this)
        history.addAll(load(store))
        build()
        intent?.getStringExtra(EXTRA_ASK)?.takeIf { it.isNotBlank() }?.let { main.post { send(it) } }
    }

    override fun onResume() {
        super.onResume()
        store = Store(this)
        render()
    }

    // ---------- layout ----------

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = PaperWaves(look.bg)
            fitsSystemWindows = true
        }
        // Header: back, the mascot (its mood follows what it is doing), name and status, clear.
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(look.dp(8), look.dp(8), look.dp(8), look.dp(8))
            background = look.shape(look.card, 0)
            elevation = look.dp(2).toFloat()
        }
        top.addView(iconButton(R.drawable.ic_chevron_right, "Back") { finish() }.apply { rotation = 180f }, LinearLayout.LayoutParams(look.dp(44), look.dp(44)))
        header = BotView(this).apply { mood = BotView.Mood.IDLE }
        top.addView(header, LinearLayout.LayoutParams(look.dp(46), look.dp(46)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(look.dp(8), 0, 0, 0) }
        names.addView(TextView(this).apply { text = "Verbo"; setTextColor(look.ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = Typeface.DEFAULT_BOLD })
        status = TextView(this).apply { setTextColor(look.muted); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f) }
        names.addView(status)
        top.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(iconButton(R.drawable.ic_refresh_cw, "New chat") { newChat() }, LinearLayout.LayoutParams(look.dp(44), look.dp(44)))
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(look.dp(14), look.dp(12), look.dp(14), look.dp(12))
        }
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Input bar: what to ask, the mic, send.
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(look.dp(10), look.dp(8), look.dp(10), look.dp(10))
        }
        val field = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = look.shape(look.card, 24, Color.parseColor("#E4E0F2"))
            setPadding(look.dp(16), look.dp(2), look.dp(4), look.dp(2))
        }
        input = EditText(this).apply {
            hint = "Ask Verbo to do anything…"
            setHintTextColor(look.muted)
            setTextColor(look.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            background = null
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        }
        field.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        field.addView(iconButton(R.drawable.ic_mic, "Speak to Verbo") { listen() }, LinearLayout.LayoutParams(look.dp(42), look.dp(42)))
        bar.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_up)
            setColorFilter(Color.WHITE)
            setPadding(look.dp(12), look.dp(12), look.dp(12), look.dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#FF5F55"), Color.parseColor("#FF4A7E"), Color.parseColor("#2BE0C8"))).apply { shape = GradientDrawable.OVAL }
            contentDescription = "Send"
            setOnClickListener { sendTyped() }
        }, LinearLayout.LayoutParams(look.dp(48), look.dp(48)).apply { leftMargin = look.dp(8) })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        render()
    }

    private fun iconButton(res: Int, description: String, onTap: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(look.violetInk)
        setPadding(look.dp(11), look.dp(11), look.dp(11), look.dp(11))
        background = look.ripple(null, 22)
        contentDescription = description
        setOnClickListener { onTap() }
    }

    /** Redraws the conversation; with nothing said yet, the mascot says hello and offers ideas. */
    private fun render() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        setMood(if (busy) BotView.Mood.WORK else BotView.Mood.IDLE)
        if (store.activeKey.isBlank()) { keyNeeded(); return }
        if (!store.isPro) { proNeeded(); return }
        if (history.isEmpty()) emptyState()
        for (m in history) list.addView(bubble(m))
        if (busy) list.addView(thinking())
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setMood(m: BotView.Mood) {
        header.mood = m
        status.text = when (m) {
            BotView.Mood.WORK -> "Working on it…"
            BotView.Mood.LISTEN -> "Listening…"
            else -> if (store.activeKey.isBlank()) "Needs an AI key" else "Your personal assistant"
        }
    }

    private fun keyNeeded() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(look.dp(10), look.dp(40), look.dp(10), 0) }
        box.addView(BotView(this).apply { mood = BotView.Mood.HELLO }, LinearLayout.LayoutParams(look.dp(130), look.dp(130)))
        box.addView(look.text("Add your API key first", 20f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, look.dp(14), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        box.addView(look.text("Verbo thinks with your own ${store.providerName} key. Sign in to bring it back from your dashboard, or paste one.", 14f, look.muted).apply {
            gravity = Gravity.CENTER; setPadding(look.dp(8), look.dp(6), look.dp(8), look.dp(18))
        }, LinearLayout.LayoutParams(-1, -2))
        box.addView(look.purplePill("Add API key", R.drawable.ic_sparkles) {
            startActivity(Intent(this, PageActivity::class.java).putExtra("page", "ai"))
        })
        list.addView(box, LinearLayout.LayoutParams(-1, -2))
    }

    private fun proNeeded() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(look.dp(10), look.dp(40), look.dp(10), 0) }
        box.addView(BotView(this).apply { mood = BotView.Mood.COOL }, LinearLayout.LayoutParams(look.dp(130), look.dp(130)))
        box.addView(look.text("Verbo Pro", 22f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, look.dp(14), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        box.addView(look.text("Your personal assistant is part of Verbo Pro, with one-tap app shortcuts and auto actions.", 14.5f, look.muted).apply {
            gravity = Gravity.CENTER; setPadding(look.dp(8), look.dp(6), look.dp(8), look.dp(18))
        }, LinearLayout.LayoutParams(-1, -2))
        box.addView(look.purplePill("See Verbo Pro", R.drawable.ic_sparkles) {
            startActivity(Intent(this, PageActivity::class.java).putExtra("page", "pro"))
        })
        list.addView(box, LinearLayout.LayoutParams(-1, -2))
    }

    private fun emptyState() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, look.dp(24), 0, look.dp(8)) }
        box.addView(BotView(this).apply { mood = BotView.Mood.HELLO }, LinearLayout.LayoutParams(look.dp(140), look.dp(140)))
        box.addView(look.text("Hi, I'm Verbo", 22f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, look.dp(12), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        box.addView(look.text("Tell me what to do. I can look things up, write for you, plan your day and act in your apps.", 14.5f, look.violetInk).apply {
            gravity = Gravity.CENTER; setPadding(look.dp(16), look.dp(6), look.dp(16), look.dp(18))
        }, LinearLayout.LayoutParams(-1, -2))
        for (idea in IDEAS) {
            box.addView(look.text(idea, 14.5f, look.ink).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                background = look.ripple(look.shape(look.card, 16, Color.parseColor("#ECE8F6")), 16)
                setPadding(look.dp(16), look.dp(13), look.dp(16), look.dp(13))
                setOnClickListener { send(idea) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.dp(8) })
        }
        list.addView(box, LinearLayout.LayoutParams(-1, -2))
    }

    private fun bubble(m: Msg): View {
        val row = LinearLayout(this).apply {
            gravity = if (m.fromUser) Gravity.END else Gravity.START
            setPadding(0, look.dp(5), 0, look.dp(5))
        }
        if (!m.fromUser) row.addView(BotView(this).apply { mood = BotView.Mood.IDLE }, LinearLayout.LayoutParams(look.dp(34), look.dp(34)).apply { gravity = Gravity.BOTTOM; rightMargin = look.dp(6) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val text = TextView(this).apply {
            text = m.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
            setPadding(look.dp(14), look.dp(10), look.dp(14), look.dp(10))
            if (m.fromUser) {
                setTextColor(Color.WHITE)
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#8B5CF6"), Color.parseColor("#6D28D9"))).apply {
                    cornerRadii = floatArrayOf(r(18), r(18), r(18), r(18), r(4), r(4), r(18), r(18))
                }
            } else {
                setTextColor(look.ink)
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadii = floatArrayOf(r(18), r(18), r(18), r(18), r(18), r(18), r(4), r(4))
                }
            }
            setOnLongClickListener { copy(m.text); true }
        }
        col.addView(text, LinearLayout.LayoutParams(-2, -2).apply { gravity = if (m.fromUser) Gravity.END else Gravity.START })
        if (!m.fromUser) {
            val actions = LinearLayout(this).apply { setPadding(0, look.dp(6), 0, 0) }
            m.link?.let { link -> actions.addView(action(linkLabel(link), true) { act(link) }) }
            actions.addView(action("Copy", false) { copy(m.text) })
            actions.addView(action("Share", false) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, m.text), "Share"))
            })
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) })
        }
        val maxW = (resources.displayMetrics.widthPixels * if (m.fromUser) 0.78f else 0.74f).toInt()
        text.maxWidth = maxW
        row.addView(col, LinearLayout.LayoutParams(-2, -2))
        return row
    }

    private fun r(v: Int) = look.dp(v).toFloat()

    private fun action(label: String, strong: Boolean, onTap: () -> Unit) = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (strong) Color.WHITE else look.violetInk)
        background = look.ripple(look.shape(if (strong) look.accent else Color.parseColor("#ECE8F6"), 14), 14)
        setPadding(look.dp(12), look.dp(7), look.dp(12), look.dp(7))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = look.dp(6) }
        setOnClickListener { onTap() }
    }

    private fun thinking() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, look.dp(6), 0, look.dp(6))
        addView(BotView(this@AssistantActivity).apply { mood = BotView.Mood.WORK }, LinearLayout.LayoutParams(look.dp(40), look.dp(40)))
        addView(look.text("Working on it…", 14f, look.muted).apply { setPadding(look.dp(8), 0, 0, 0) })
        addView(look.text("Stop", 13f, look.accent, bold = true).apply {
            setPadding(look.dp(14), look.dp(6), look.dp(14), look.dp(6))
            setOnClickListener { generation++; busy = false; render() }
        })
    }

    // ---------- talking ----------

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        send(text)
    }

    internal fun send(text: String) {
        if (busy) return
        if (store.activeKey.isBlank() || !store.isPro) { render(); return }
        history += Msg(true, text)
        // "remember my UPI is me@okicici" / "forget my UPI": handled on the phone, nothing is sent.
        val fact = Memory.parseRemember(text)
        val forget = if (fact == null) Memory.parseForget(text) else null
        if (fact != null || forget != null) {
            val reply = if (fact != null) { store.remember(fact); "Got it, I'll remember ${fact.label.lowercase()}: ${fact.value}" }
            else if (store.forget(forget!!)) "Done, I've forgotten your ${forget.lowercase()}." else "I didn't have anything saved for ${forget.lowercase()}."
            history += Msg(false, reply)
            save(); render(); return
        }
        if (store.overLimit) {
            history += Msg(false, "This month's spending limit of $${"%.2f".format(store.monthlyLimit)} is reached. You can raise it under Settings › Usage.")
            save(); render(); return
        }
        busy = true
        save()
        render()
        val token = ++generation
        val engine = Engine(store)
        val tools = store.tools()
        val prompt = transcript()
        Thread {
            val result = try {
                Result.success(engine.run(TASK, prompt, web = true, tools = tools, cancelled = { token != generation }, label = "Assistant"))
            } catch (e: AiException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(AiException("Something went wrong. Try again"))
            }
            main.post {
                if (token != generation || isFinishing) return@post
                busy = false
                result.fold({ r ->
                    val reply = r.text.trim().ifEmpty { "Done." }
                    history += Msg(false, reply, r.openUri)
                    // When opening something is the whole request ("open my Notion page about X"), do it now.
                    if (r.autoOpen && r.openUri != null) act(r.openUri)
                    else r.openUri?.let { link -> if (PhoneCalendar.parseTemplate(link) != null && PhoneCalendar.allowed(this)) saveEvent(link) }
                }) { e -> history += Msg(false, e.message ?: "That didn't work. Try again") }
                save()
                render()
            }
        }.start()
    }

    /** The last turns of the chat plus the new message, so follow-ups ("make it shorter") have context. */
    private fun transcript(): String {
        val recent = history.takeLast(MAX_TURNS)
        val last = recent.last()
        val before = recent.dropLast(1)
        if (before.isEmpty()) return last.text
        val sb = StringBuilder("Conversation so far:\n")
        for (m in before) sb.append(if (m.fromUser) "User: " else "Verbo: ").append(m.text.take(1500)).append('\n')
        sb.append("\nThe user's new message:\n").append(last.text)
        return sb.toString()
    }

    private fun newChat() {
        generation++
        busy = false
        history.clear()
        save()
        render()
    }

    // ---------- doing things on the phone ----------

    private fun linkLabel(link: String): String = when {
        PhoneCalendar.parseTemplate(link) != null -> "Add to calendar"
        link.startsWith("mailto:") || link.contains("outlook.office.com") -> "Open email draft"
        link.contains("wa.me/") -> "Send on WhatsApp"
        link.contains("t.me/") -> "Send on Telegram"
        link.contains("linkedin.com/feed") -> "Post on LinkedIn"
        link.contains("linkedin.com/") -> "Open LinkedIn"
        link.startsWith("geo:") || link.contains("google.com/maps") -> "Open map"
        link.startsWith("tel:") -> "Call"
        link.contains("notion.so") -> "Open in Notion"
        link.contains("youtube.com") -> "Open YouTube"
        link.contains("x.com/") || link.contains("twitter.com/") -> "Post on X"
        else -> "Open"
    }

    private fun act(link: String) {
        if (PhoneCalendar.parseTemplate(link) != null) { saveEvent(link); return }
        val uri = Uri.parse(link)
        if (uri.host == "www.linkedin.com" && uri.getQueryParameter("shareActive") == "true") {
            val text = uri.getQueryParameter("text").orEmpty()
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage("com.linkedin.android")
            if (packageManager.resolveActivity(send, 0) != null) { startActivity(send); return }
        }
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) { toast("No app can open that") }
    }

    /** Straight into the phone's calendar; the first time, Android asks for calendar access. */
    private fun saveEvent(link: String) {
        val event = PhoneCalendar.parseTemplate(link) ?: return
        if (!PhoneCalendar.allowed(this)) {
            startActivity(Intent(this, CalendarPermissionActivity::class.java).putExtra(CalendarPermissionActivity.EXTRA_LINK, link))
            return
        }
        Thread {
            val ok = runCatching { PhoneCalendar.save(this, event) }.isSuccess
            main.post { toast(if (ok) "Saved to your calendar: ${event.title}, ${PhoneCalendar.describe(event)}" else "Couldn't save it to the calendar") }
        }.start()
    }

    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Verbo", text))
        toast("Copied")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- voice ----------

    private fun listen() {
        val speak = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell Verbo what to do")
        try {
            setMood(BotView.Mood.LISTEN)
            @Suppress("DEPRECATION") startActivityForResult(speak, SPEAK)
        } catch (e: Exception) {
            setMood(BotView.Mood.IDLE)
            toast("This phone has no speech recognition")
        }
    }

    @Deprecated("Activity result API is not used in this app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SPEAK) return
        setMood(BotView.Mood.IDLE)
        val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (resultCode == RESULT_OK && !heard.isNullOrEmpty()) send(heard)
    }

    // ---------- keeping the chat ----------

    private fun save() = store.chat(history.takeLast(MAX_KEPT).joinToString(prefix = "[", postfix = "]", separator = ",") { m ->
        JSONObject().put("u", m.fromUser).put("t", m.text).apply { m.link?.let { put("l", it) } }.toString()
    })

    companion object {
        const val EXTRA_ASK = "ask"
        private const val SPEAK = 7
        private const val MAX_TURNS = 16
        private const val MAX_KEPT = 80

        val IDEAS = listOf(
            "Plan my day around a 3pm meeting and the gym",
            "What's the weather like this weekend?",
            "Remind me to call Mom tomorrow at 7pm",
            "Write a WhatsApp message wishing Rahul happy birthday",
            "Find a good cafe near me to work from",
        )

        const val TASK = "Task: you are Verbo, the user's personal assistant, talking with them in the Verbo app's chat screen (not inside a text field). " +
            "Answer the user's new message directly and warmly, as a helpful assistant would, in the language they wrote in. Follow their commands: " +
            "look things up with your tools and the web, write what they ask for, plan, remind, and act on the phone through the link forms you know " +
            "(a calendar event link saves the event; WhatsApp, email and LinkedIn links open a ready draft; map links open the map). " +
            "Use the conversation so far for follow-ups like \"make it shorter\" or \"send that to Priya\". Keep replies short and easy to read on a phone; " +
            "plain text, short lines, a numbered list only when listing options. When you write a message for them to send, give the message itself, ready to send."

        fun load(store: Store): List<Msg> = try {
            val arr = JSONArray(store.chat())
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { Msg(it.optBoolean("u"), it.optString("t"), it.optString("l").ifEmpty { null }) }
        } catch (e: Exception) { emptyList() }
    }
}
