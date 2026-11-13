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
    private val preview by lazy { Feedback(this) }

    override fun onDestroy() {
        preview.release()
        super.onDestroy()
    }

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
            page == "skins" -> skinsPage(column)
            page == "feedback" -> feedbackPage(column)
            page == "battery" -> batteryPage(column)
            page == "templates" -> templatesPage(column)
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
        val key = ui.field("Hold key (optional): one letter, e.g. n", existing?.key ?: "")
        form.addView(emoji); form.addView(name); form.addView(instruction); form.addView(key)
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
                    val letter = key.text.toString().trim().lowercase().take(1).takeIf { it.isNotEmpty() && it[0] in 'a'..'z' } ?: ""
                    // A letter can run only one skill, so taking it frees it from any other skill.
                    if (letter.isNotEmpty()) for (j in skills.indices) if (j != index && skills[j].key == letter) skills[j] = skills[j].copy(key = "")
                    val skill = Skill(emoji.text.toString().trim(), n, i, chosen, letter)
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
            ui.row(R.drawable.ic_globe, "Places and maps", "Finds addresses and shows a map, from OpenStreetMap", ui.toggle(store.places) { store.places = it }),
        ))
        column.addView(ui.rows(ui.row(R.drawable.ic_pencil_line, "Gmail", "Writes the email and opens it as a ready draft in Gmail. No sign-in needed. Reading your inbox is not available yet", ui.icon(R.drawable.ic_check, 20, Color.parseColor("#4ADE80")))))
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
            ui.row(R.drawable.ic_sparkle, "Hold the wide bar", "Treats what you typed as a request and shows the answer on a card to insert or copy"),
            ui.row(R.drawable.ic_keyboard, "Hold a marked letter", "Runs the skill bound to that key; set the letter when editing a skill"),
            ui.row(R.drawable.ic_message_square, "Use copied text", "Copy a message, then tap this chip above the keys to give it to the assistant as context"),
            ui.row(R.drawable.ic_mic, "Tap the microphone", "Dictate; filler words and slips are tidied up"),
            ui.row(R.drawable.ic_mic, "Hold the microphone", "Speak a request instead of typing it"),
            ui.row(R.drawable.ic_keyboard, "Space twice", "Ends the sentence with a full stop"),
            ui.row(R.drawable.ic_delete, "Backspace after a correction", "Puts back the word you typed"),
        ))
        column.addView(ui.section(R.drawable.ic_square_terminal, "Slash commands"))
        note(column, "Type your text, then / and the start of a skill's name. The skill appears above the keys; tap it to run it on the text.")
        val rows = store.skills.map {
            ui.row(R.drawable.ic_zap, "/" + it.name.lowercase().replace(" ", ""), (it.emoji + " " + it.name).trim() + if (it.key.isEmpty()) "" else "  ·  hold ${it.key.uppercase()}")
        }
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
            ui.row(R.drawable.ic_sparkles, "Assistant actions", "Only when you trigger one: the text in the field goes to Anthropic, OpenAI or the gateway you chose"),
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
            runOnUiThread { if (!isFinishing && !isDestroyed) { ui.toast(message); render() } }
        }.start()
    }

    // ---------- skins ----------

    private fun choice(icon: Int, title: String, subtitle: String?, selected: Boolean, onPick: () -> Unit) =
        ui.row(icon, title, subtitle, if (selected) ui.icon(R.drawable.ic_check, 20, ui.iconTint) else null) { onPick(); render() }

    private fun skinsPage(column: LinearLayout) {
        column.addView(ui.topBar("Skins & key style", "Change how the keyboard looks"))
        column.addView(ui.section(R.drawable.ic_palette, "Skin"))
        val skins = listOf(
            "system" to "Follow phone (light or dark)", "dark" to "Dark", "light" to "Light", "amoled" to "Amoled black",
            "ocean" to "Ocean blue", "forest" to "Forest green", "sunset" to "Sunset", "rose" to "Rose (light)", "mint" to "Mint (light)",
            "custom" to "Custom accent colour",
        )
        column.addView(ui.rows(*skins.map { (id, name) ->
            val t = KeyTheme.of(this, id, store.customAccent)
            // A small preview: one key in the skin's colours on the skin's background.
            val swatch = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                background = ui.shape(t.bg, 8, ui.line)
                addView(ui.text("a", 13f, t.text).apply { gravity = Gravity.CENTER; background = ui.shape(t.key, 5); layoutParams = LinearLayout.LayoutParams(ui.dp(20), ui.dp(22)).apply { rightMargin = ui.dp(3) } })
                addView(android.view.View(this@PageActivity).apply { background = ui.shape(t.accent, 5); layoutParams = LinearLayout.LayoutParams(ui.dp(14), ui.dp(22)) })
            }
            val row = ui.row(R.drawable.ic_palette, name, null, if (store.theme == id) ui.icon(R.drawable.ic_check, 20, ui.iconTint) else null) { store.theme = id; render() }
            row.removeViewAt(0)
            row.addView(swatch, 0, LinearLayout.LayoutParams(ui.dp(52), ui.dp(34)))
            row
        }.toTypedArray()))

        column.addView(ui.section(R.drawable.ic_keyboard, "Key shape"))
        column.addView(ui.rows(
            choice(R.drawable.ic_keyboard, "Raised", "3D keys that sink when pressed", store.keyShape == "raised") { store.keyShape = "raised" },
            choice(R.drawable.ic_keyboard, "Rounded", "Soft corners, low profile", store.keyShape == "rounded") { store.keyShape = "rounded" },
            choice(R.drawable.ic_keyboard, "Square", "Sharp corners, widest keys", store.keyShape == "square") { store.keyShape = "square" },
            choice(R.drawable.ic_keyboard, "Bubble", "Fully round keys", store.keyShape == "bubble") { store.keyShape = "bubble" },
            choice(R.drawable.ic_keyboard, "Glass", "See-through keys with a light edge", store.keyShape == "glass") { store.keyShape = "glass" },
            choice(R.drawable.ic_keyboard, "Outline", "Thin outlines only", store.keyShape == "outline") { store.keyShape = "outline" },
            choice(R.drawable.ic_keyboard, "Flat", "Letters only, no key outlines", store.keyShape == "flat") { store.keyShape = "flat" },
        ))
        column.addView(ui.section(R.drawable.ic_sliders_horizontal, "Layout"))
        column.addView(ui.rows(ui.row(R.drawable.ic_keyboard, "Number row", "Show 1–0 above the letters", ui.toggle(store.numberRow) { store.numberRow = it })))
        note(column, "Key height is set on the main screen. Changes apply the next time the keyboard opens.")
    }

    // ---------- sound and vibration ----------

    private fun feedbackPage(column: LinearLayout) {
        column.addView(ui.topBar("Sound & vibration", "Tap an option to hear or feel it"))
        fun demo(type: KeyboardView.Type = KeyboardView.Type.CHAR) {
            preview.configure(store)
            // A freshly chosen sound needs a moment to load before it can play.
            window.decorView.postDelayed({ preview.press(type) }, 180)
        }
        column.addView(ui.section(R.drawable.ic_volume_2, "Sound effect"))
        column.addView(ui.rows(*Feedback.PACKS.map { (id, name) ->
            choice(R.drawable.ic_volume_2, name, if (id == "typewriter") "With a bell on enter" else null, store.soundPack == id) {
                store.soundPack = id
                store.sound = true
                demo()
            }
        }.toTypedArray()))
        column.addView(ui.text("Volume", 14f, ui.muted).apply { setPadding(ui.dp(2), ui.dp(8), 0, 0) })
        column.addView(android.widget.SeekBar(this).apply {
            max = 19
            progress = store.soundVolume / 5 - 1
            progressTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#5A5880"))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            contentDescription = "Key sound volume"
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, value: Int, fromUser: Boolean) { store.soundVolume = (value + 1) * 5 }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) { store.sound = true; demo() }
            })
        })
        column.addView(ui.section(R.drawable.ic_vibrate, "Vibration strength"))
        column.addView(ui.rows(*Feedback.STRENGTHS.map { (id, name) ->
            choice(R.drawable.ic_vibrate, name, null, store.hapticStrength == id) {
                store.hapticStrength = id
                store.haptic = true
                demo(KeyboardView.Type.SHIFT)
            }
        }.toTypedArray()))
        note(column, "If Strong still feels weak, raise Vibration intensity → Touch feedback in the phone's Sounds and vibration settings; Android caps every app at that level. Key sounds follow the phone's media or system volume and stay silent in silent mode on some phones.")
    }

    // ---------- battery ----------

    private fun batteryPage(column: LinearLayout) {
        column.addView(ui.topBar("Battery", "Battery Saver behaviour and power use"))
        val saverOn = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode
        column.addView(ui.rows(
            ui.row(R.drawable.ic_zap, "Follow Battery Saver", "While the phone's Battery Saver is on: no key sound or vibration, and no background dashboard sync",
                ui.toggle(store.batterySaver) { store.batterySaver = it }),
            ui.row(R.drawable.ic_shield, "Battery Saver right now", if (saverOn) "On" + (if (store.batterySaver) ": Verbo is in low-power mode" else "") else "Off"),
        ))
        column.addView(ui.section(R.drawable.ic_settings, "How Verbo uses power"))
        column.addView(ui.rows(
            ui.row(R.drawable.ic_keyboard, "Typing", "Nothing runs in the background. The keyboard only works while it is on screen"),
            ui.row(R.drawable.ic_mic, "Microphone and sounds", "Released as soon as the keyboard is hidden"),
            ui.row(R.drawable.ic_sparkles, "Assistant", "Uses the network only when you trigger an action"),
            ui.row(R.drawable.ic_cloud, "Dashboard sync", "At most once every 10 minutes, only while you type, and paused in Battery Saver"),
        ))
        column.addView(ui.rows(ui.row(R.drawable.ic_settings, "Phone's battery settings for Verbo", "Leave it on Optimised. A keyboard is started by Android when needed, so it does not need Unrestricted", ui.chevron()) {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        }))
    }

    // ---------- templates ----------

    private class Template(val icon: Int, val app: String, val example: String, val skill: Skill?)

    private val templates = listOf(
        Template(R.drawable.ic_pencil_line, "Gmail: write an email", "email priya@example.com that the invoice is attached and payment is due friday, polite",
            Skill("📧", "Email", "Turn the text into a complete email with a greeting and sign-off. Return the email body, and add a mailto link carrying a short subject and the body so it opens as a draft.", "write")),
        Template(R.drawable.ic_message_square, "WhatsApp: send a message", "whatsapp mom that i reached safely and will call at night",
            Skill("🟢", "WhatsApp", "Rewrite the text as a short, natural WhatsApp message and add a wa.me link carrying it.", "write")),
        Template(R.drawable.ic_list, "Calendar: add an event", "add dentist appointment tomorrow 5pm for 30 minutes to my calendar",
            Skill("📅", "Event", "The text describes an event. Return a one-line summary of it and add a Google Calendar link with the title, start and end time, and place.", "write")),
        Template(R.drawable.ic_globe, "Maps: find a place", "find a pharmacy open now near me", null),
        Template(R.drawable.ic_sun, "Weather", "will it rain in mumbai tomorrow evening", null),
        Template(R.drawable.ic_globe, "Web: quick answer", "what time does the india match start today", null),
        Template(R.drawable.ic_zap, "YouTube: search", "open youtube for 10 minute morning yoga", null),
        Template(R.drawable.ic_message_square_more, "X: post", "post on x: just shipped my first android keyboard", null),
        Template(R.drawable.ic_square_terminal, "Notion: save a note", "save this to my notion notes: buy milk, call the bank, renew insurance", null),
        Template(R.drawable.ic_list, "Calendly: share booking link", "send my 30 minute meeting link with a friendly line", null),
        Template(R.drawable.ic_pencil_line, "Reply to a message", "say yes to the invite but ask if i can come an hour late", null),
        Template(R.drawable.ic_languages, "Translate", "translate to hindi: can we meet tomorrow at 10", null),
        Template(R.drawable.ic_list, "Shopping or to-do list", "make a list: eggs bread 2 litre milk detergent and phone recharge", null),
        Template(R.drawable.ic_sliders_horizontal, "Leave or sick note", "write a short message to my manager that i'm unwell and taking today off", null),
    )

    private fun templatesPage(column: LinearLayout) {
        column.addView(ui.topBar("Templates", "Ready-made daily actions for your apps"))
        note(column, "Type something like these in any app, then hold the wide bar. Where an app is involved, a button appears above the keys to open it with everything filled in. Tap a template to try it.")
        val have = store.skills.map { it.name }
        for (t in templates) {
            val box = ui.rows(ui.row(t.icon, t.app, "“${t.example}”", ui.chevron()) {
                startActivity(android.content.Intent(this, PageActivity::class.java).putExtra("page", "try").putExtra("text", t.example))
            })
            val skill = t.skill
            if (skill != null && skill.name !in have) {
                box.addView(ui.divider())
                box.addView(ui.row(R.drawable.ic_plus, "Add “${skill.emoji} ${skill.name}” as a skill key", null) {
                    store.skills = store.skills + skill
                    ui.toast("${skill.name} key added")
                    render()
                })
            }
            column.addView(box)
        }
        note(column, "\nNotion and Calendly need a token under Automate. Gmail, WhatsApp, Calendar, Maps, YouTube and X open the app with a draft, so nothing is sent until you press send there.")
    }

    // ---------- try it ----------

    private fun tryPage(column: LinearLayout) {
        column.addView(ui.topBar("Try the keyboard"))
        note(column, "Type a request, then hold the wide bar. Or type a sentence and tap a skill key.")
        val box = ui.cardBox().apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(12)) }
        box.addView(ui.field("tell sam i'm running 15 min late, friendly", intent.getStringExtra("text") ?: "", lines = 4).apply {
            gravity = Gravity.TOP
            requestFocus()
            setSelection(text.length)
        })
        column.addView(box)
    }
}
