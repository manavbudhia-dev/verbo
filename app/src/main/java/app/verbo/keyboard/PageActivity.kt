package app.verbo.keyboard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.LinearLayout

/** Detail screens opened from the main settings screen. The "page" extra says which one. */
class PageActivity : Activity() {

    private lateinit var store: Store
    private lateinit var ui: Ui
    private lateinit var page: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        ui = Ui(this)
        page = intent.getStringExtra("page") ?: "prompts"
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = render()

    private fun render() {
        val column = ui.page()
        when {
            page.startsWith("category:") -> skillsPage(column, page.substringAfter(":"))
            page == "prompts" -> skillsPage(column, null)
            page == "automate" -> automatePage(column)
            page == "shortcuts" -> shortcutsPage(column)
            page == "privacy" -> privacyPage(column)
            page == "languages" -> languagesPage(column)
            page == "dashboard" -> dashboardPage(column)
            else -> tryPage(column)
        }
    }

    private fun note(column: LinearLayout, text: String) =
        column.addView(ui.text(text, 14f, ui.muted).apply { setPadding(ui.dp(2), 0, ui.dp(2), ui.dp(14)); setLineSpacing(0f, 1.15f) })

    // ---------- skills ----------

    private val categoryInfo = mapOf(
        "write" to Triple("Write", "Draft, reply, or expand text", R.drawable.ic_pencil_line),
        "edit" to Triple("Edit", "Improve, shorten, or change tone", R.drawable.ic_sliders_horizontal),
        "translate" to Triple("Translate", "Translate into other languages", R.drawable.ic_languages),
        "summarize" to Triple("Summarize", "Turn long text into key points", R.drawable.ic_list),
    )

    private fun skillsPage(column: LinearLayout, category: String?) {
        val info = categoryInfo[category]
        column.addView(ui.topBar(info?.first ?: "Custom Prompts", info?.second ?: "Add your own AI instructions"))
        note(column, "Each one is a skill key above the keyboard. Tap it to apply the instruction to what you typed, or type / and its name.")
        val all = store.skills
        val shown = all.withIndex().filter { category == null || it.value.category == category }
        if (shown.isEmpty()) note(column, "Nothing here yet.")
        shown.forEach { (index, skill) ->
            val icon = categoryInfo[skill.category]?.third ?: R.drawable.ic_sparkles
            column.addView(ui.rows(ui.row(icon, (skill.emoji + "  " + skill.name).trim(), skill.instruction, ui.chevron()) { editSkill(index, category) }.apply {
                (getChildAt(1) as LinearLayout).getChildAt(1).let { (it as android.widget.TextView).maxLines = 2 }
            }))
        }
        val buttons = LinearLayout(this).apply { setPadding(0, ui.dp(8), 0, 0) }
        buttons.addView(ui.button("New", R.drawable.ic_plus, filled = true) { editSkill(-1, category) }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(6) })
        if (category == null) buttons.addView(ui.button("Restore defaults", R.drawable.ic_refresh_cw) {
            AlertDialog.Builder(this).setMessage("Replace your skills with the original set?")
                .setPositiveButton("Restore") { _, _ -> store.resetSkills(); render() }
                .setNegativeButton("Cancel", null).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(6) })
        column.addView(buttons)
        if (store.deviceToken.isNotEmpty()) note(column, "\nThis keyboard is paired with a dashboard, so skills edited here are replaced at the next sync. Edit them on the dashboard instead.")
    }

    private fun editSkill(index: Int, category: String?) {
        val skills = store.skills.toMutableList()
        val existing = skills.getOrNull(index)
        var chosen = existing?.category ?: category ?: "edit"
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0) }
        val emoji = ui.field("Emoji (optional)", existing?.emoji ?: "")
        val name = ui.field("Name, e.g. Pirate", existing?.name ?: "")
        val instruction = ui.field("What should it do with the text? e.g. Rewrite it the way a cheerful pirate would say it.", existing?.instruction ?: "", lines = 3)
        val group = ui.button("Group: ${categoryInfo[chosen]?.first}") {}
        group.setOnClickListener {
            chosen = Store.CATEGORIES[(Store.CATEGORIES.indexOf(chosen) + 1) % Store.CATEGORIES.size]
            (group.getChildAt(0) as android.widget.TextView).text = "Group: ${categoryInfo[chosen]?.first}"
        }
        form.addView(emoji); form.addView(name); form.addView(instruction)
        form.addView(group, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(8) })

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New skill" else "Edit skill")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                val i = instruction.text.toString().trim()
                if (n.isEmpty() || i.isEmpty()) {
                    ui.toast("A skill needs a name and an instruction")
                } else {
                    val skill = Skill(emoji.text.toString().trim(), n, i, chosen)
                    if (existing == null) skills.add(skill) else skills[index] = skill
                    store.skills = skills
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) dialog.setNeutralButton("Delete") { _, _ ->
            skills.removeAt(index)
            store.skills = skills
            render()
        }
        dialog.show()
    }

    // ---------- connections ----------

    private fun automatePage(column: LinearLayout) {
        column.addView(ui.topBar("Automate", "Connect apps and APIs for actions"))
        note(column, "The assistant can use these while carrying out an action, for example \"add this to my Notion notes\" or \"what's the weather in Pune tomorrow\".")
        column.addView(ui.rows(
            ui.row(R.drawable.ic_globe, "Web search", "Look up fresh facts when needed", ui.toggle(store.webSearch) { store.webSearch = it }),
            ui.row(R.drawable.ic_sun, "Weather", "Forecasts from Open-Meteo, no account", ui.toggle(store.weather) { store.weather = it }),
        ))
        column.addView(ui.rows(
            ui.row(R.drawable.ic_square_terminal, "Notion", if (store.notionToken.isEmpty()) "Not connected" else "Connected", ui.chevron()) {
                editToken("Notion", "Paste an internal integration token from notion.so/my-integrations, then share the pages you want with that integration.", store.notionToken) { store.notionToken = it }
            },
            ui.row(R.drawable.ic_list, "Calendly", if (store.calendlyToken.isEmpty()) "Not connected" else "Connected", ui.chevron()) {
                editToken("Calendly", "Paste a personal access token from Calendly → Integrations → API & webhooks.", store.calendlyToken) { store.calendlyToken = it }
            },
        ))
        store.customApis.forEachIndexed { index, api ->
            column.addView(ui.rows(ui.row(R.drawable.ic_plug, api.name, api.description.ifBlank { api.url }, ui.chevron()) { editApi(index) }))
        }
        column.addView(ui.button("Connect an API", R.drawable.ic_plus, filled = true) { editApi(-1) })
    }

    private fun editToken(title: String, help: String, current: String, save: (String) -> Unit) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0) }
        form.addView(ui.text(help, 13.5f, ui.muted))
        val field = ui.field("Token", current, secret = true)
        form.addView(field)
        AlertDialog.Builder(this).setTitle(title).setView(form)
            .setPositiveButton("Save") { _, _ -> save(field.text.toString()); render() }
            .setNeutralButton("Disconnect") { _, _ -> save(""); render() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun editApi(index: Int) {
        val apis = store.customApis.toMutableList()
        val existing = apis.getOrNull(index)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0) }
        val name = ui.field("Name, e.g. NBA scores", existing?.name ?: "")
        val description = ui.field("What it returns and when to use it", existing?.description ?: "")
        val url = ui.field("https://… use {query} where the search term goes", existing?.url ?: "")
        val headerName = ui.field("Header name (optional), e.g. X-Api-Key", existing?.headerName ?: "")
        val headerValue = ui.field("Header value (optional)", existing?.headerValue ?: "", secret = true)
        listOf(name, description, url, headerName, headerValue).forEach { form.addView(it) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Connect an API" else "Edit connection")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                val u = url.text.toString().trim()
                if (n.isEmpty() || !u.startsWith("https://")) {
                    ui.toast("It needs a name and an https:// address")
                } else {
                    val api = CustomApi(n, description.text.toString().trim(), u, headerName.text.toString().trim(), headerValue.text.toString().trim())
                    if (existing == null) apis.add(api) else apis[index] = api
                    store.customApis = apis
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) dialog.setNeutralButton("Remove") { _, _ ->
            apis.removeAt(index)
            store.customApis = apis
            render()
        }
        dialog.show()
    }

    // ---------- shortcuts ----------

    private fun shortcutsPage(column: LinearLayout) {
        column.addView(ui.topBar("Shortcuts & Triggers", "Use / command to run actions"))
        column.addView(ui.rows(
            ui.row(R.drawable.ic_sparkle, "Hold the wide bar", "Treats what you typed as a request and replaces it with the result"),
            ui.row(R.drawable.ic_mic, "Tap the microphone", "Dictate; filler words and slips are tidied up"),
            ui.row(R.drawable.ic_mic, "Hold the microphone", "Speak a request instead of typing it"),
            ui.row(R.drawable.ic_keyboard, "Space twice", "Ends the sentence with a full stop"),
            ui.row(R.drawable.ic_delete, "Backspace after a correction", "Puts back the word you typed"),
        ))
        column.addView(ui.section(R.drawable.ic_square_terminal, "Slash commands"))
        note(column, "Type your text, then / and the start of a skill's name. The skill appears above the keys; tap it to run it on the text.")
        val rows = store.skills.map { ui.row(R.drawable.ic_zap, "/" + it.name.lowercase().replace(" ", ""), (it.emoji + " " + it.name).trim()) }
        if (rows.isNotEmpty()) column.addView(ui.rows(*rows.toTypedArray()))
    }

    // ---------- privacy ----------

    private fun privacyPage(column: LinearLayout) {
        column.addView(ui.topBar("Privacy", "Data, permissions and usage"))
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        column.addView(ui.rows(
            ui.row(R.drawable.ic_mic, "Microphone", if (mic) "Allowed, used only while you dictate" else "Not allowed. Tap to allow voice typing",
                if (mic) ui.icon(R.drawable.ic_check, 20, Color.parseColor("#4ADE80")) else ui.chevron()) {
                if (!mic) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            },
        ))
        column.addView(ui.section(R.drawable.ic_shield, "What leaves your phone"))
        column.addView(ui.rows(
            ui.row(R.drawable.ic_keyboard, "Typing", "Never sent anywhere. Suggestions and auto-correct run on the phone"),
            ui.row(R.drawable.ic_sparkles, "Assistant actions", "Only when you trigger one: the text in the field goes to Anthropic with your API key"),
            ui.row(R.drawable.ic_plug, "Connections", "Called directly from the phone with the tokens you saved, only during an action"),
            ui.row(R.drawable.ic_shield, "Password fields", "Never read, dictated into or sent"),
            ui.row(R.drawable.ic_cloud, "Dashboard", if (store.deviceToken.isEmpty()) "Not paired" else "Paired: settings sync from your own server"),
        ))
    }

    // ---------- languages ----------

    private fun languagesPage(column: LinearLayout) {
        column.addView(ui.topBar("Languages"))
        column.addView(ui.rows(ui.row(R.drawable.ic_languages, "English (US)", "QWERTY", ui.icon(R.drawable.ic_check, 20, ui.iconTint))))
        note(column, "English is the only typing layout so far. Skills and voice still work in other languages: the assistant answers in the language you write in.")
    }

    // ---------- dashboard ----------

    private fun dashboardPage(column: LinearLayout) {
        column.addView(ui.topBar("Web dashboard", "Manage skills and connections in a browser"))
        if (store.deviceToken.isNotEmpty()) {
            val last = if (store.lastSync == 0L) "not yet" else DateUtils.getRelativeTimeSpanString(store.lastSync).toString()
            column.addView(ui.rows(ui.row(R.drawable.ic_cloud, "Paired with " + store.serverUrl.removePrefix("https://"), "Last sync: $last")))
            note(column, "Skills, connections and the API key come from the dashboard, so changes made in this app are replaced at the next sync.")
            val buttons = LinearLayout(this)
            buttons.addView(ui.button("Sync now", R.drawable.ic_refresh_cw, filled = true) { background({ Sync.pull(store) }, "Synced") }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(6) })
            buttons.addView(ui.button("Unpair", R.drawable.ic_x) { store.unpair(); render() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(6) })
            column.addView(buttons)
            return
        }
        note(column, "Sign in to your dashboard, open Keyboards and choose Pair a keyboard to get a code.")
        val box = ui.cardBox().apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(12)) }
        val address = ui.field("Dashboard address, e.g. verbo.example.com")
        val code = ui.field("Pairing code")
        box.addView(address); box.addView(code)
        column.addView(box)
        column.addView(ui.button("Pair", R.drawable.ic_cloud, filled = true) {
            val a = address.text.toString()
            val c = code.text.toString()
            if (a.isBlank() || c.isBlank()) ui.toast("Enter the address and the code")
            else background({ Sync.pair(store, a, c) }, "Paired")
        })
    }

    /** Runs a dashboard call off the main thread, then redraws with whatever was synced. */
    private fun background(work: () -> Unit, done: String) {
        Thread {
            val message = try { work(); done } catch (e: SyncException) { e.message ?: "Failed" } catch (e: Exception) { "Something went wrong" }
            runOnUiThread { ui.toast(message); render() }
        }.start()
    }

    // ---------- try it ----------

    private fun tryPage(column: LinearLayout) {
        column.addView(ui.topBar("Try the keyboard"))
        note(column, "Type a request, then hold the wide bar. Or type a sentence and tap a skill key.")
        val box = ui.cardBox().apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(12)) }
        box.addView(ui.field("tell sam i'm running 15 min late, friendly", lines = 4).apply { gravity = Gravity.TOP; requestFocus() })
        column.addView(box)
    }
}
