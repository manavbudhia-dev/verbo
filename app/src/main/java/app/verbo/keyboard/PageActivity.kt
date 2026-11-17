package app.verbo.keyboard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Detail screens opened from the main settings screen. The "page" extra says which one. */
class PageActivity : Activity() {

    private lateinit var store: Store
    private lateinit var ui: Ui
    private val look by lazy { Look(this) }
    private lateinit var page: String
    private val preview by lazy { Feedback(this) }

    // "Create a skill" screen state
    private var wish = ""
    private var designing = false
    private var draft: Ai.SkillDraft? = null
    private var createdName: String? = null

    override fun onDestroy() {
        preview.release()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        ui = Ui(this)
        page = intent.getStringExtra("page") ?: "prompts"
        if (intent.getBooleanExtra("new", false)) page = "skill-new"
        // Opened from a skill card on Home: go straight to that skill.
        val edit = intent.getIntExtra("edit", -1)
        if (edit in store.skills.indices) page = "skill-edit:$edit"
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = render()

    // The guide shows live ticks: back from system settings, the keyboard picker or the browser, redraw it.
    private var resumed = false
    override fun onResume() {
        super.onResume()
        if (resumed && (page == "welcome" || page == "dashboard" || page == "keys")) { store = Store(this); render() }
        resumed = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && page == "welcome" && resumed) render() // the keyboard picker is a dialog, so onResume doesn't fire after it
    }

    private fun render() {
        if (page == "skill-new" || page.startsWith("skill-edit:")) { skillPage(); return }
        if (page == "chain-new" || page.startsWith("chain-edit:")) { chainPage(); return }
        if (page == "trigger-new" || page.startsWith("trigger-edit:")) { triggerPage(); return }
        if (page == "triggers") { triggersPage(); return }
        val column = ui.page(mainNav(navActive(page)))
        when {
            page.startsWith("category:") -> skillsPage(column, page.substringAfter(":"))
            page == "prompts" -> skillsPage(column, null)
            page == "shelves" -> shelvesPage(column)
            page == "automate" -> automatePage(column)
            page == "shortcuts" -> shortcutsPage(column)
            page == "privacy" -> privacyPage(column)
            page == "languages" || page == "translate" -> languagesPage(column)
            page == "ai" -> aiPage(column)
            page == "typing" -> typingPage(column)
            page == "dashboard" -> dashboardPage(column)
            page == "look" || page == "skins" -> skinsPage(column)
            page == "wheel" -> wheelPage(column)
            page == "create" -> createPage(column)
            page == "feedback" -> feedbackPage(column)
            page == "battery" -> batteryPage(column)
            page == "templates" -> templatesPage(column)
            page == "copysettings" -> copySettingsPage(column)
            page == "chains" -> chainsPage(column)
            page == "memory" -> memoryPage(column)
            page == "textshortcuts" -> textShortcutsPage(column)
            page == "usage" -> usagePage(column)
            page == "welcome" -> welcomePage(column)
            page == "keys" -> keysPage(column)
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
        if (store.deviceToken.isNotEmpty()) note(column, "\nThis keyboard is paired with a dashboard: skills edited here are sent to it at the next sync.")
    }

    private fun editSkill(index: Int, category: String?) {
        val i = android.content.Intent(this, PageActivity::class.java)
            .putExtra("page", if (index < 0) "skill-new" else "skill-edit:$index")
        if (category != null) i.putExtra("cat", category)
        startActivity(i)
    }

    /** The New Skill sheet: a photo header with the form on a rounded paper over it. */
    private fun skillPage() {
        val index = page.substringAfter("skill-edit:", "").toIntOrNull() ?: -1
        val skills = store.skills.toMutableList()
        val existing = skills.getOrNull(index)
        var chosen = existing?.category ?: intent.getStringExtra("cat") ?: "edit"

        val root = android.widget.FrameLayout(this)
        val header = View(this).apply { background = Art.skill(this@PageActivity, existing?.name ?: "", chosen) }
        root.addView(header, android.widget.FrameLayout.LayoutParams(-1, look.dp(230)))
        root.addView(look.circleButton(R.drawable.ic_chevron_left, look.ink) { finish() },
            android.widget.FrameLayout.LayoutParams(look.dp(40), look.dp(40)).apply { setMargins(look.dp(16), look.dp(16), 0, 0) })

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(look.dp(20), look.dp(10), look.dp(20), look.dp(22))
        }
        column.addView(View(this).apply { background = look.shape(Color.parseColor("#C9C9D4"), 2) },
            LinearLayout.LayoutParams(look.dp(40), look.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = look.dp(18) })
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, look.dp(20)) }
        head.addView(look.iconTile(R.drawable.ic_zap, look.accentSoft, look.accent, 56, 26))
        head.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(look.dp(14), 0, 0, 0)
            addView(look.text(if (existing == null) "New Skill" else "Edit Skill", 22f, bold = true))
            addView(look.text("Create a custom skill for your keyboard", 13.5f, look.muted).apply { setPadding(0, look.dp(3), 0, 0) })
        })
        column.addView(head)

        val emojiSmile = look.icon(R.drawable.ic_smile, 20, Color.parseColor("#55555F"))
        val emoji = look.fieldRow(R.drawable.ic_smile, "Emoji (optional)", existing?.emoji ?: "", trailing = emojiSmile)
        emojiSmile.setOnClickListener { pickEmoji(emoji) }
        val name = look.fieldRow(R.drawable.ic_tag, "Name, e.g. Pirate", existing?.name ?: "")
        val instruction = look.fieldRow(R.drawable.ic_file_text,
            "What should it do with the text? e.g. Rewrite it the way a cheerful pirate would say it.", existing?.instruction ?: "", lines = 3)
        val key = look.fieldRow(R.drawable.ic_keyboard, "Hold key (optional): one letter, e.g. n", existing?.key ?: "")
        val group = look.dropRow(R.drawable.ic_layers, "Group: ${categoryInfo[chosen]?.first}") {}
        group.setOnClickListener {
            AlertDialog.Builder(this).setItems(Store.CATEGORIES.map { categoryInfo[it]?.first ?: it }.toTypedArray()) { _, w ->
                chosen = Store.CATEGORIES[w]
                look.dropText(group).text = "Group: ${categoryInfo[chosen]?.first}"
                header.background = Art.skill(this@PageActivity, look.editText(name).text.toString().trim(), chosen)
            }.show()
        }
        group.layoutParams = LinearLayout.LayoutParams(look.dp(250), -2).apply { bottomMargin = look.dp(12) }
        listOf(emoji, name, instruction, key, group).forEach { column.addView(it) }

        column.addView(look.darkPill("Save Skill", R.drawable.ic_check) {
            val n = look.editText(name).text.toString().trim()
            val i = look.editText(instruction).text.toString().trim()
            if (n.isEmpty() || i.isEmpty()) {
                look.toast("A skill needs a name and an instruction")
            } else {
                val letter = look.editText(key).text.toString().trim().lowercase().take(1).takeIf { it.isNotEmpty() && it[0] in 'a'..'z' } ?: ""
                // A letter can run only one skill, so taking it frees it from any other skill.
                if (letter.isNotEmpty()) for (j in skills.indices) if (j != index && skills[j].key == letter) skills[j] = skills[j].copy(key = "")
                val skill = Skill(look.editText(emoji).text.toString().trim(), n, i, chosen, letter)
                if (existing == null) skills.add(skill) else skills[index] = skill
                store.skills = skills
                finish()
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = look.dp(8) })
        if (existing != null) column.addView(look.text("Remove skill", 14f, Color.parseColor("#DC2626"), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, look.dp(14), 0, 0)
            setOnClickListener {
                AlertDialog.Builder(this@PageActivity).setMessage("Remove ${existing.name}?")
                    .setPositiveButton("Remove") { _, _ -> skills.removeAt(index); store.skills = skills; finish() }
                    .setNegativeButton("Cancel", null).show()
            }
        }, LinearLayout.LayoutParams(-1, -2))

        val sheet = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = look.sheetShape()
            addView(column)
        }
        root.addView(sheet, android.widget.FrameLayout.LayoutParams(-1, -1).apply { topMargin = look.dp(190) })
        setContentView(root)
    }

    private fun pickEmoji(row: LinearLayout) {
        val emojis = arrayOf("✨", "💬", "✂️", "👔", "😄", "🌍", "🧾", "", "", "👤", "🗒️", "📧", "💼", "📋", "🏴‍☠️", "🤖", "", "", "🔥", "💡", "📝", "", "😊", "")
        AlertDialog.Builder(this).setTitle("Emoji")
            .setItems(emojis + "None") { _, w -> look.editText(row).setText(if (w < emojis.size) emojis[w] else "") }.show()
    }

    /** The ready-made shelf list, kept for adding skills the keyboard ships with. */
    private var shelf = "Featured"

    private fun shelvesPage(column: LinearLayout) {
        column.addView(ui.topBar("Skill library", "Ready-made skills. Add one and it appears above your keyboard."))
        val chips = LinearLayout(this)
        Library.SHELVES.forEach { name ->
            val on = name == shelf
            chips.addView(ui.text(name, 15f, if (on) Color.WHITE else ui.muted, bold = true).apply {
                gravity = Gravity.CENTER
                background = ui.ripple(if (on) ui.shape(ui.accent, 20) else ui.shape(ui.card, 20, ui.line), 20)
                setPadding(ui.dp(18), ui.dp(9), ui.dp(18), ui.dp(9))
                setOnClickListener { shelf = name; render() }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.dp(8) })
        }
        column.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
        val mine = store.skills
        Library.SKILLS.filter { it.shelf == shelf }.forEach { item ->
            val added = mine.any { it.name == item.name }
            val button = ui.text(if (added) "Added" else "Add", 14f, if (added) ui.muted else Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = if (added) ui.shape(Color.TRANSPARENT, 16, ui.line) else ui.ripple(ui.shape(ui.accent, 16), 16)
                setPadding(ui.dp(16), ui.dp(7), ui.dp(16), ui.dp(7))
                if (!added) setOnClickListener {
                    // Give it its suggested hold key only if no other skill is using that letter.
                    val key = item.key.takeIf { k -> k.isNotEmpty() && mine.none { it.key == k } } ?: ""
                    store.skills = mine + Skill(item.emoji, item.name, item.instruction, item.group, key)
                    ui.toast(item.name + " added" + if (key.isEmpty()) "" else ", hold ${key.uppercase()} to run it")
                    render()
                }
            }
            val row = ui.row(R.drawable.ic_sparkles, item.name, item.about, button)
            row.removeViewAt(0)
            row.addView(ui.text(item.emoji, 22f).apply { gravity = Gravity.CENTER; background = ui.shape(ui.accentSoft, 12) }, 0, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
            column.addView(ui.rows(row))
        }
    }

    // ---------- usage and on-device AI ----------

    private fun usagePage(column: LinearLayout) {
        column.addView(ui.topBar("Usage & on-device AI", "What Verbo spends, and what runs free on the phone"))
        val u = store.usage
        val cost = u.optDouble("cost", 0.0)
        val month = try { java.text.SimpleDateFormat("MMMM", java.util.Locale.getDefault()).format(java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).parse(u.optString("month"))!!) } catch (e: Exception) { "This month" }
        column.addView(ui.rows(
            ui.row(R.drawable.ic_zap, "$month: about $${"%.3f".format(cost)}", "${u.optInt("requests")} requests · ${"%,d".format(u.optLong("inTokens"))} tokens in, ${"%,d".format(u.optLong("outTokens"))} out" +
                (if (u.optLong("searches") > 0) " · ${u.optLong("searches")} web searches" else "")),
            ui.row(R.drawable.ic_shield, "Monthly limit", if (store.monthlyLimit > 0) "$${"%.2f".format(store.monthlyLimit)} · AI actions stop when it is reached" else "No limit", ui.chevron()) { editLimit() },
        ))
        note(column, "An estimate from the token counts your AI service reports, at its list prices (Haiku 5.5 $0.10 / $0.50, Sonnet 5.5 $2 / $10, Opus 5.5 $4 / $20 per million tokens in / out; web search $10 per 1,000)." +
            if (u.optBoolean("unpriced")) " Some requests used a model without a known price, so they count as $0." else "")
        val skills = u.optJSONObject("skills")
        if (skills != null && skills.length() > 0) {
            column.addView(ui.text("Where it went", 16f, bold = true).apply { setPadding(ui.dp(2), ui.dp(6), 0, ui.dp(6)) })
            val rows = skills.keys().asSequence().map { k -> k to skills.getJSONObject(k) }.sortedByDescending { it.second.optDouble("cost", 0.0) }.take(10)
                .map { (k, o) -> ui.row(R.drawable.ic_sparkles, k, "${o.optInt("n")} times · $${"%.3f".format(o.optDouble("cost", 0.0))}") }.toList()
            column.addView(ui.rows(*rows.toTypedArray()))
        }
        column.addView(ui.text("\nOn this phone", 16f, bold = true).apply { setPadding(ui.dp(2), ui.dp(10), 0, ui.dp(6)) })
        note(column, "Polish, Shorter, Formal and Casual can run on Gemini Nano on phones that have it, and translation into one language runs on downloaded models. That is free, private and works offline. Anything else, or anything with copied text, still uses your AI service.")
        val status = ui.text("Checking…", 14f, ui.muted).apply { setPadding(ui.dp(2), 0, 0, ui.dp(8)) }
        column.addView(ui.rows(ui.row(R.drawable.ic_zap, "Use on-device AI for small jobs", "Falls back to your AI service when it can't", ui.toggle(store.onDevice) { store.onDevice = it })))
        column.addView(status)
        Thread { val s = Offline.status(this); runOnUiThread { status.text = s } }.start()
        column.addView(ui.button("Download offline models", R.drawable.ic_upload) {
            status.text = "Downloading… this can take a few minutes"
            Offline.download(this, store.languages) { msg -> runOnUiThread { status.text = msg } }
        })
    }

    private fun editLimit() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0) }
        form.addView(ui.text("Dollars per month. 0 means no limit.", 13.5f, ui.muted))
        val field = ui.field("e.g. 2", if (store.monthlyLimit > 0) "%.2f".format(store.monthlyLimit) else "").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
        form.addView(field)
        AlertDialog.Builder(this).setTitle("Monthly limit").setView(form)
            .setPositiveButton("Save") { _, _ -> store.monthlyLimit = field.text.toString().toDoubleOrNull() ?: 0.0; render() }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- text shortcuts ----------

    private fun textShortcutsPage(column: LinearLayout) {
        column.addView(ui.topBar("Text shortcuts", "Type ;key and a space"))
        note(column, "Type a semicolon, a short key and a space, and the saved text goes in: ;addr becomes your full address. " +
            "Anything in Memory works too: ;upi puts in what you saved as UPI. These sync with your dashboard.")
        val list = store.shortcuts
        if (list.isNotEmpty()) column.addView(ui.rows(*list.mapIndexed { i, (k, t) -> ui.row(R.drawable.ic_keyboard, ";$k", t, ui.chevron()) { editShortcut(i) } }.toTypedArray()))
        column.addView(ui.button("New shortcut", R.drawable.ic_plus, filled = true) { editShortcut(-1) })
        val facts = store.memory
        if (facts.isNotEmpty()) {
            column.addView(ui.text("\nFrom Memory", 16f, bold = true).apply { setPadding(ui.dp(2), ui.dp(10), 0, ui.dp(6)) })
            column.addView(ui.rows(*facts.map { f -> ui.row(R.drawable.ic_sparkles, ";" + f.label.lowercase().filter { it.isLetterOrDigit() }, f.value) }.toTypedArray()))
        }
    }

    private fun editShortcut(index: Int) {
        val list = store.shortcuts.toMutableList()
        val existing = list.getOrNull(index)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0) }
        val key = ui.field("Key, e.g. addr", existing?.first ?: "")
        val text = ui.field("Text it becomes", existing?.second ?: "", lines = 4)
        form.addView(key)
        form.addView(text)
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "New shortcut" else "Edit shortcut").setView(form)
            .setPositiveButton("Save") { _, _ ->
                val k = key.text.toString().trim().removePrefix(";").lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(20)
                val t = text.text.toString()
                if (k.isEmpty() || t.isBlank()) { ui.toast("It needs a key and the text"); return@setPositiveButton }
                if (existing == null) list.add(k to t) else list[index] = k to t
                store.shortcuts = list
                render()
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) dialog.setNeutralButton("Delete") { _, _ -> list.removeAt(index); store.shortcuts = list; render() }
        dialog.show()
    }

    // ---------- memory ----------

    private fun memoryPage(column: LinearLayout) {
        column.addView(ui.topBar("Memory", "What Verbo knows about you"))
        note(column, "Tell Verbo things once and it uses them when they help: your address, UPI ID, booking link, who \"mummy\" or \"boss\" is. " +
            "You can also type \"remember my UPI is me@okicici\" in the keyboard and hold the space bar, or \"forget my UPI\". These sync with your dashboard.")
        val facts = store.memory
        if (facts.isNotEmpty()) column.addView(ui.rows(*facts.mapIndexed { i, f ->
            ui.row(R.drawable.ic_sparkles, f.label, f.value, ui.chevron()) { editFact(i) }
        }.toTypedArray()))
        column.addView(ui.button("Add something to remember", R.drawable.ic_plus, filled = true) { editFact(-1) })
        column.addView(ui.text("\nYour writing style", 16f, bold = true).apply { setPadding(ui.dp(2), ui.dp(10), 0, ui.dp(6)) })
        val style = store.styleLine
        note(column, (style?.let { "Learned so far: $it." } ?: "Verbo learns how you write from messages you send with it (${store.styleSamples.size} of 5 needed).") +
            " The messages stay on this phone; only this one-line summary goes with requests, so replies sound like you.")
        column.addView(ui.rows(ui.row(R.drawable.ic_pencil_line, "Learn my style", "From messages you send, on this phone only", ui.toggle(store.learnStyle) { store.learnStyle = it; render() })))
        if (store.styleSamples.isNotEmpty()) column.addView(ui.button("Forget what it learned", null) { store.clearStyle(); render() })
    }

    private fun editFact(index: Int) {
        val list = store.memory.toMutableList()
        val existing = list.getOrNull(index)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0) }
        val label = ui.field("What it is, e.g. Mummy, Home address, UPI", existing?.label ?: "")
        val value = ui.field("The value, e.g. +91 98765 43210", existing?.value ?: "", lines = 3)
        form.addView(label)
        form.addView(value)
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "Remember" else "Edit").setView(form)
            .setPositiveButton("Save") { _, _ ->
                val l = label.text.toString().trim()
                val v = value.text.toString().trim()
                if (l.isEmpty() || v.isEmpty()) { ui.toast("It needs both a name and a value"); return@setPositiveButton }
                val f = Memory.Fact(l.take(60), v.take(500))
                if (existing == null) list.add(f) else list[index] = f
                store.memory = list
                render()
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) dialog.setNeutralButton("Forget") { _, _ -> list.removeAt(index); store.memory = list; render() }
        dialog.show()
    }

    // ---------- triggers ----------

    private var trigQuery = ""
    private var trigChip = "All"
    private val trigChips = listOf("All", "Text", "People", "Links", "Custom")

    private fun trigChipOf(t: Trigger) = when (t.kind) {
        "person" -> "People"
        "link", "email", "phone" -> "Links"
        "keyword" -> "Custom"
        else -> "Text"
    }

    private fun trigLine(t: Trigger) = when (t.kind) {
        "link" -> "When text is a URL"
        "foreign" -> "When text is not in English"
        "keyword" -> "When text has “${t.keyword}”"
        else -> "When text has " + (Cards.KINDS.firstOrNull { it.first == t.kind }?.second ?: t.kind)
    }

    private fun kindIcon(kind: String) = when (kind) {
        "person" -> R.drawable.ic_users
        "link" -> R.drawable.ic_link
        "email", "phone" -> R.drawable.ic_message_square
        "foreign" -> R.drawable.ic_zap
        "time" -> R.drawable.ic_sun
        "place" -> R.drawable.ic_globe
        "keyword" -> R.drawable.ic_tag
        else -> R.drawable.ic_sparkles
    }

    private fun triggersPage() {
        val column = look.page(mainNav("actions"))
        column.addView(look.header("Auto Actions", "Run skills automatically based on copied text",
            look.circleButton(R.drawable.ic_more_horizontal) { trigMenu() }))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun fill() {
            grid.removeAllViews()
            val q = trigQuery.trim().lowercase()
            val shown = store.triggers.withIndex().filter { (_, t) ->
                (q.isEmpty() || t.skill.lowercase().contains(q) || trigLine(t).lowercase().contains(q)) &&
                    (trigChip == "All" || trigChipOf(t) == trigChip)
            }
            if (shown.isEmpty()) grid.addView(look.text("Nothing here yet.", 14f, look.muted).apply { setPadding(look.dp(4), look.dp(6), 0, look.dp(10)) })
            val pins = store.pins
            shown.sortedByDescending { store.triggerId(it.value) in pins }.forEach { (i, t) ->
                val id = store.triggerId(t)
                grid.addView(look.itemCard(Art.kind(this, t.kind), t.skill, trigLine(t), store.useCount(id), id in pins,
                    { val now = store.pins; store.pins = if (id in now) now - id else now + id; render() },
                    { go("trigger-edit:$i") }, { trigCardMenu(i) }))
            }
        }
        column.addView(look.searchBar("Search auto actions", { trigQuery = it; fill() }, {
            AlertDialog.Builder(this).setItems(trigChips.toTypedArray()) { _, w -> trigChip = trigChips[w]; render() }.show()
        }))
        column.addView(look.chips(trigChips, trigChip) { trigChip = it; render() })
        fill()
        column.addView(grid)
        column.addView(look.darkPill("New Auto Action", R.drawable.ic_plus) { go("trigger-new") }.apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, look.dp(6), 0, look.dp(6))
        })
    }

    private fun trigMenu() = AlertDialog.Builder(this)
        .setItems(arrayOf("Copy settings", "Chains", "Reset to defaults")) { _, w ->
            when (w) {
                0 -> go("copysettings")
                1 -> go("chains")
                else -> AlertDialog.Builder(this).setMessage("Replace your auto actions with the original set?")
                    .setPositiveButton("Reset") { _, _ -> store.triggers = Trigger.DEFAULTS; render() }
                    .setNegativeButton("Cancel", null).show()
            }
        }.show()

    private fun trigCardMenu(index: Int) {
        val list = store.triggers.toMutableList()
        val t = list.getOrNull(index) ?: return
        AlertDialog.Builder(this).setTitle(t.skill)
            .setItems(arrayOf("Edit", "Duplicate", "Remove")) { _, w ->
                when (w) {
                    0 -> go("trigger-edit:$index")
                    1 -> { store.triggers = list + t; render() }
                    else -> { list.removeAt(index); store.triggers = list; render() }
                }
            }.show()
    }

    private fun copySettingsPage(column: LinearLayout) {
        column.addView(ui.topBar("Copy settings", "What happens when you copy a message"))
        note(column, "When you copy a message, Verbo checks it on your phone for the things your auto actions look for and shows a ⚡ chip above the keys. " +
            "Example: copy \"We're meeting David Miller tomorrow\" and get \"Who is · David Miller\" and \"Save event\". Nothing is sent until a skill runs.")
        column.addView(ui.rows(
            ui.row(R.drawable.ic_zap, "Suggest actions for copied text", "Checked on the phone only", ui.toggle(store.triggersOn) { store.triggersOn = it; render() }),
            ui.row(R.drawable.ic_pencil_line, "Quick replies when I copy", "Three short replies as chips, one small request per copy", ui.toggle(store.smartReplies) { store.smartReplies = it }),
            ui.row(R.drawable.ic_sparkles, "Show my acts when I copy", "Opens the acts in place of the keys, once per copy", ui.toggle(store.actsOnCopy) { store.actsOnCopy = it }),
        ))
    }

    private fun chainsPage(column: LinearLayout) {
        column.addView(ui.topBar("Chains", "Several acts in a row, as one act"))
        note(column, "A chain runs several acts one after another, as one act: for example Schedule it → Save to Notion → Reply options. Chains appear with your acts and can be used by an auto action.")
        val chains = store.skills.withIndex().filter { Acts.isChain(it.value.instruction) }
        if (chains.isNotEmpty()) column.addView(ui.rows(*chains.map { (i, sk) ->
            ui.row(R.drawable.ic_zap, sk.name, Acts.chainSteps(sk.instruction).joinToString(" → "), ui.chevron()) { editChain(i) }
        }.toTypedArray()))
        column.addView(ui.button("New chain", R.drawable.ic_plus) { editChain(-1) })
    }

    private fun editChain(index: Int) {
        startActivity(android.content.Intent(this, PageActivity::class.java)
            .putExtra("page", if (index < 0) "chain-new" else "chain-edit:$index"))
    }

    private fun actLook(name: String): Triple<Int, Int, Int> = when (name) {
        "Polish" -> Triple(look.purpleBg, look.purpleTint, R.drawable.ic_sparkles)
        "Reply" -> Triple(look.blueBg, look.blueTint, R.drawable.ic_corner_up_left)
        "Shorter" -> Triple(look.pinkBg, look.pinkTint, R.drawable.ic_scissors)
        "Formal" -> Triple(look.amberBg, look.amberTint, R.drawable.ic_briefcase)
        "Casual" -> Triple(look.greenBg, look.greenTint, R.drawable.ic_coffee)
        "English" -> Triple(look.blueBg, look.blueTint, R.drawable.ic_globe)
        "Summarize" -> Triple(look.purpleBg, look.purpleTint, R.drawable.ic_file_text)
        else -> Triple(look.indigoBg, look.indigoTint, R.drawable.ic_sparkles)
    }

    private fun actBlurb(name: String, instruction: String) = when (name) {
        "Polish" -> "Improve and clean the text"
        "Reply" -> "Generate a suitable reply"
        "Summarize" -> "Convert to key points"
        else -> instruction.substringBefore(". ").take(38)
    }

    /** The New Chain sheet: numbered steps on a rail, an act carousel below, and a test run. */
    private fun chainPage() {
        val index = page.substringAfter("chain-edit:", "").toIntOrNull() ?: -1
        val skills = store.skills.toMutableList()
        val existing = skills.getOrNull(index)
        val acts = (skills.filter { !Acts.isChain(it.instruction) }.map { it.name to it.instruction } +
            Acts.BUILTIN.map { it.name to it.instruction }).distinctBy { it.first }
        val names = acts.map { it.first }
        val lookup = { n: String -> acts.firstOrNull { it.first == n }?.second }
        val order = existing?.let { Acts.chainSteps(it.instruction).filter { s -> s in names }.toMutableList() } ?: mutableListOf()
        val undo = ArrayDeque<List<String>>()
        val redo = ArrayDeque<List<String>>()

        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(look.bg) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(look.dp(20), look.dp(10), look.dp(20), look.dp(20))
        }
        column.addView(View(this).apply { background = look.shape(look.accent, 2) },
            LinearLayout.LayoutParams(look.dp(48), look.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = look.dp(16) })
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, look.dp(14)) }
        head.addView(look.iconTile(R.drawable.ic_layers, look.accentSoft, look.accent, 56, 26))
        head.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(look.dp(14), 0, look.dp(8), 0)
            addView(look.text(if (existing == null) "New Chain" else "Edit Chain", 22f, bold = true))
            addView(look.text("Run multiple acts in a sequence", 13.5f, look.muted).apply { setPadding(0, look.dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(look.circleButton(R.drawable.ic_x, look.ink, 40) { finish() })
        column.addView(head)

        val nameRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = look.shape(look.card, 16, look.edge)
            setPadding(look.dp(14), look.dp(10), look.dp(10), look.dp(10))
        }
        nameRow.addView(look.icon(R.drawable.ic_tag, 20, Color.parseColor("#55555F")))
        val nameField = android.widget.EditText(this).apply {
            setText(existing?.name ?: "")
            hint = "Trip Planner"
            setTextColor(look.ink)
            setHintTextColor(look.faint)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = null
            setSingleLine()
            setPadding(look.dp(12), look.dp(2), look.dp(4), look.dp(2))
        }
        nameRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(look.text("Chain name", 12f, look.muted))
            addView(nameField)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        nameRow.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = look.shape(Color.parseColor("#E4E4EC"), 12)
            addView(look.icon(R.drawable.ic_x, 12, Color.parseColor("#55555F")))
            setOnClickListener { nameField.setText("") }
        }, LinearLayout.LayoutParams(look.dp(24), look.dp(24)))
        column.addView(nameRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = look.dp(14) })

        // Workflow / Settings tabs and the test run.
        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val tabBox = LinearLayout(this).apply { background = look.shape(look.card, 16, look.edge); setPadding(look.dp(4), look.dp(4), look.dp(4), look.dp(4)) }
        fun tab(label: String, iconRes: Int) = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(look.dp(14), look.dp(10), look.dp(14), look.dp(10))
            addView(look.icon(iconRes, 18, look.accent).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = look.dp(8) })
            addView(look.text(label, 15f, look.accent, bold = true))
        }
        val tabWorkflow = tab("Workflow", R.drawable.ic_workflow)
        val tabSettings = tab("Settings", R.drawable.ic_settings)
        tabBox.addView(tabWorkflow, LinearLayout.LayoutParams(0, -2, 1f))
        tabBox.addView(tabSettings, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(tabBox, LinearLayout.LayoutParams(0, -2, 1f))
        val testRun = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = look.ripple(look.shape(look.card, 16, look.edge), 16)
            setPadding(look.dp(16), look.dp(12), look.dp(16), look.dp(12))
            addView(look.icon(R.drawable.ic_play, 16, look.accent).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = look.dp(8) })
            addView(look.text("Test Run", 15f, look.accent, bold = true))
        }
        tabs.addView(testRun, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = look.dp(10) })
        column.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = look.dp(14) })

        val workflow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fun showTab(workflowOn: Boolean) {
            workflow.visibility = if (workflowOn) View.VISIBLE else View.GONE
            settings.visibility = if (workflowOn) View.GONE else View.VISIBLE
            tabWorkflow.background = look.shape(if (workflowOn) look.accentSoft else Color.TRANSPARENT, 12)
            tabSettings.background = look.shape(if (workflowOn) Color.TRANSPARENT else look.accentSoft, 12)
            (tabWorkflow.getChildAt(0) as android.widget.ImageView).setColorFilter(if (workflowOn) look.accent else look.muted)
            (tabWorkflow.getChildAt(1) as android.widget.TextView).setTextColor(if (workflowOn) look.accent else look.muted)
            (tabSettings.getChildAt(0) as android.widget.ImageView).setColorFilter(if (workflowOn) look.muted else look.accent)
            (tabSettings.getChildAt(1) as android.widget.TextView).setTextColor(if (workflowOn) look.muted else look.accent)
        }
        tabWorkflow.setOnClickListener { showTab(true) }
        tabSettings.setOnClickListener { showTab(false) }
        showTab(true)

        val wfHead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, look.dp(12)) }
        wfHead.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(look.text("Workflow", 20f, bold = true))
            addView(look.text("Arrange acts in the order you want them to run.", 13f, look.muted).apply { setPadding(0, look.dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        fun smallButton(iconRes: Int) = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = look.shape(look.card, 12, look.edge)
            addView(look.icon(iconRes, 18, look.ink))
        }
        val undoBtn = smallButton(R.drawable.ic_undo_2)
        val redoBtn = smallButton(R.drawable.ic_redo_2)
        wfHead.addView(undoBtn, LinearLayout.LayoutParams(look.dp(44), look.dp(36)).apply { rightMargin = look.dp(8) })
        wfHead.addView(redoBtn, LinearLayout.LayoutParams(look.dp(44), look.dp(36)))
        workflow.addView(wfHead)

        val stepsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        workflow.addView(stepsBox)
        fun snapshot() { undo.addLast(order.toList()); redo.clear() }
        fun refreshSteps() {
            stepsBox.removeAllViews()
            order.forEachIndexed { i, n ->
                stepsBox.addView(stepRow(i, n, order, ::snapshot, ::refreshSteps))
                if (i < order.lastIndex) stepsBox.addView(stepGap())
            }
            (undoBtn.getChildAt(0) as android.widget.ImageView).setColorFilter(if (undo.isEmpty()) look.faint else look.ink)
            (redoBtn.getChildAt(0) as android.widget.ImageView).setColorFilter(if (redo.isEmpty()) look.faint else look.ink)
        }
        undoBtn.setOnClickListener {
            if (undo.isNotEmpty()) { redo.addLast(order.toList()); order.clear(); order.addAll(undo.removeLast()); refreshSteps() }
        }
        redoBtn.setOnClickListener {
            if (redo.isNotEmpty()) { undo.addLast(order.toList()); order.clear(); order.addAll(redo.removeLast()); refreshSteps() }
        }
        refreshSteps()

        val dashed = GradientDrawable().apply {
            cornerRadius = look.dp(16).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(look.dp(2), Color.parseColor("#C4B5FD"), look.dp(6).toFloat(), look.dp(6).toFloat())
        }
        val addAct = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = dashed
            setPadding(look.dp(16), look.dp(14), look.dp(16), look.dp(14))
            addView(look.icon(R.drawable.ic_plus, 18, look.accent).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = look.dp(8) })
            addView(look.text("Add an act", 15f, look.accent, bold = true))
        }
        workflow.addView(addAct, LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.dp(12); bottomMargin = look.dp(16) })

        val addHead = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, look.dp(10)) }
        addHead.addView(look.text("Add an Act", 18f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        val searchAct = look.circleButton(R.drawable.ic_search, look.ink, 36) {}
        addHead.addView(searchAct, LinearLayout.LayoutParams(look.dp(36), look.dp(36)).apply { rightMargin = look.dp(8) })
        val viewAll = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = look.ripple(look.shape(look.accentSoft, 16), 16)
            setPadding(look.dp(14), look.dp(9), look.dp(10), look.dp(9))
            addView(look.text("View All", 14f, look.accent, bold = true))
            addView(look.icon(R.drawable.ic_chevron_right, 14, look.accent))
        }
        addHead.addView(viewAll)
        workflow.addView(addHead)
        val actSearch = look.fieldRow(R.drawable.ic_search, "Search acts", "")
        actSearch.visibility = View.GONE
        workflow.addView(actSearch)
        searchAct.setOnClickListener {
            actSearch.visibility = if (actSearch.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        val carousel = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val pages = LinearLayout(this)
        carousel.addView(pages)
        val dots = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, look.dp(10), 0, look.dp(12)) }
        workflow.addView(carousel)
        workflow.addView(dots)
        val pageWidth = resources.displayMetrics.widthPixels - look.dp(40)
        fun buildPages() {
            pages.removeAllViews()
            dots.removeAllViews()
            val q = look.editText(actSearch).text.toString().trim().lowercase()
            val shown = names.filter { q.isEmpty() || it.lowercase().contains(q) }
            val chunks = shown.chunked(6).ifEmpty { listOf(emptyList()) }
            chunks.forEach { six ->
                val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                six.chunked(3).forEach { three ->
                    val row = LinearLayout(this)
                    three.forEach { n ->
                        val (bg, tint, iconRes) = actLook(n)
                        val tile = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            background = look.ripple(look.shape(look.card, 16, look.edge), 16)
                            setPadding(look.dp(10), look.dp(10), look.dp(10), look.dp(10))
                            val top = LinearLayout(this@PageActivity).apply { gravity = Gravity.CENTER_VERTICAL }
                            top.addView(look.iconTile(iconRes, bg, tint, 34, 17))
                            top.addView(View(this@PageActivity), LinearLayout.LayoutParams(0, 0, 1f))
                            top.addView(LinearLayout(this@PageActivity).apply {
                                gravity = Gravity.CENTER
                                background = look.shape(look.accentSoft, 14)
                                addView(look.icon(R.drawable.ic_plus, 14, look.accent))
                            }, LinearLayout.LayoutParams(look.dp(28), look.dp(28)))
                            addView(top)
                            addView(look.text(n, 15f, bold = true).apply { setPadding(0, look.dp(8), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                            setOnClickListener { snapshot(); order.add(n); refreshSteps() }
                        }
                        row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(look.dp(4), 0, look.dp(4), look.dp(8)) })
                    }
                    grid.addView(row, LinearLayout.LayoutParams(-1, -2))
                }
                pages.addView(grid, LinearLayout.LayoutParams(pageWidth, -2))
            }
            chunks.indices.forEach { i ->
                dots.addView(View(this).apply { background = look.shape(if (i == 0) look.accent else Color.parseColor("#D9D4EE"), 4) },
                    LinearLayout.LayoutParams(look.dp(8), look.dp(8)).apply { setMargins(look.dp(4), 0, look.dp(4), 0) })
            }
            carousel.setOnScrollChangeListener { _, x, _, _, _ ->
                val current = if (pageWidth > 0) (x + pageWidth / 2) / pageWidth else 0
                for (i in 0 until dots.childCount) dots.getChildAt(i).background =
                    look.shape(if (i == current) look.accent else Color.parseColor("#D9D4EE"), 4)
            }
        }
        buildPages()
        look.editText(actSearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = buildPages()
        })
        addAct.setOnClickListener {
            var v: View? = carousel
            while (v != null && v !is android.widget.ScrollView) v = v.parent as? View
            (v as? android.widget.ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
        viewAll.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Add an act")
                .setItems(names.toTypedArray()) { _, w -> snapshot(); order.add(names[w]); refreshSteps() }.show()
        }

        val keyRow = look.fieldRow(R.drawable.ic_keyboard, "Hold key (optional): one letter, e.g. c", existing?.key ?: "")
        settings.addView(keyRow)
        settings.addView(look.text("Chains appear with your acts and can be used by an auto action.", 13f, look.muted).apply {
            setPadding(look.dp(2), 0, look.dp(2), look.dp(12))
        })
        if (existing != null) settings.addView(look.text("Remove chain", 14f, Color.parseColor("#DC2626"), bold = true).apply {
            setOnClickListener {
                AlertDialog.Builder(this@PageActivity).setMessage("Remove ${existing.name}?")
                    .setPositiveButton("Remove") { _, _ -> skills.removeAt(index); store.skills = skills; finish() }
                    .setNegativeButton("Cancel", null).show()
            }
        })
        column.addView(workflow)
        column.addView(settings)

        val buttons = LinearLayout(this).apply { setPadding(0, look.dp(12), 0, 0) }
        buttons.addView(look.softPill("Cancel") { finish() }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(look.purplePill("Save Chain", R.drawable.ic_check) {
            val n = nameField.text.toString().trim().take(40)
            if (n.isEmpty() || order.size < 2) {
                look.toast("Give it a name and at least two steps")
            } else {
                val letter = look.editText(keyRow).text.toString().trim().lowercase().take(1).takeIf { it.isNotEmpty() && it[0] in 'a'..'z' } ?: ""
                if (letter.isNotEmpty()) for (j in skills.indices) if (j != index && skills[j].key == letter) skills[j] = skills[j].copy(key = "")
                val sk = Skill("⛓️", n, Acts.chain(order), "write", letter)
                if (existing == null) skills.add(sk) else skills[index] = sk
                store.skills = skills
                finish()
            }
        }, LinearLayout.LayoutParams(0, -2, 1.3f).apply { leftMargin = look.dp(10) })
        column.addView(buttons)

        testRun.setOnClickListener {
            if (order.size < 2) { look.toast("Add at least two steps first"); return@setOnClickListener }
            look.toast("Running the chain on a sample message…")
            val steps = order.toList()
            Thread {
                val outcome = try {
                    Engine(store).run(Acts.chainTask(steps, lookup), "tell sam i'm running 15 min late, friendly",
                        web = false, tools = emptyList(), cancelled = { isDestroyed }, label = "Test run").text
                } catch (e: Exception) {
                    e.message ?: "The test run failed"
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    AlertDialog.Builder(this).setTitle("Test run").setMessage(outcome).setPositiveButton("Close", null).show()
                }
            }.start()
        }

        val sheet = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = look.sheetShape()
            addView(column)
        }
        root.addView(sheet, android.widget.FrameLayout.LayoutParams(-1, -1).apply { topMargin = look.dp(36) })
        setContentView(root)
    }

    /** One numbered step on the rail: grip, pastel icon, name and line, move buttons and a menu. */
    private fun stepRow(i: Int, name: String, order: MutableList<String>, snapshot: () -> Unit, refresh: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        rail.addView(look.text("${i + 1}", 13f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = look.shape(look.accent, 14)
        }, LinearLayout.LayoutParams(look.dp(28), look.dp(28)))
        rail.addView(View(this).apply { setBackgroundColor(Color.parseColor("#D9D4EE")) },
            LinearLayout.LayoutParams(look.dp(2), 0, 1f).apply { topMargin = look.dp(4) })
        row.addView(rail, LinearLayout.LayoutParams(look.dp(28), -1))
        val (bg, tint, iconRes) = actLook(name)
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = look.shape(look.card, 16)
            setPadding(look.dp(8), look.dp(10), look.dp(8), look.dp(10))
            elevation = look.dp(1).toFloat()
        }
        card.addView(look.icon(R.drawable.ic_grip_vertical, 16, Color.parseColor("#B9B9C6")))
        card.addView(look.iconTile(iconRes, bg, tint, 38, 19).apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = look.dp(8)
        })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(look.dp(8), 0, look.dp(4), 0)
            addView(look.text(name, 16f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(look.text(actBlurb(name, Acts.BUILTIN.firstOrNull { it.name == name }?.instruction
                ?: store.skills.firstOrNull { it.name == name }?.instruction ?: ""), 12f, look.muted).apply {
                setPadding(0, look.dp(2), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        fun move(iconRes: Int, enabled: Boolean, onTap: () -> Unit) = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = look.shape(if (enabled) look.accentSoft else Color.parseColor("#ECECF2"), 16)
            addView(look.icon(iconRes, 16, if (enabled) look.accent else look.faint))
            setOnClickListener { if (enabled) onTap() }
        }
        card.addView(move(R.drawable.ic_arrow_up, i > 0) {
            snapshot(); order[i] = order[i - 1].also { order[i - 1] = order[i] }; refresh()
        }, LinearLayout.LayoutParams(look.dp(30), look.dp(30)).apply { rightMargin = look.dp(6) })
        card.addView(move(R.drawable.ic_arrow_down, i < order.lastIndex) {
            snapshot(); order[i] = order[i + 1].also { order[i + 1] = order[i] }; refresh()
        }, LinearLayout.LayoutParams(look.dp(30), look.dp(30)))
        val menu = look.icon(R.drawable.ic_more_horizontal, 20, look.ink).apply {
            setPadding(look.dp(4), look.dp(4), 0, look.dp(4))
            setOnClickListener { stepMenu(this, i, order, snapshot, refresh) }
        }
        card.addView(menu)
        row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = look.dp(10) })
        return row
    }

    /** The little down-arrow circle between two steps, with the rail running behind it. */
    private fun stepGap(): LinearLayout {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(View(this).apply { setBackgroundColor(Color.parseColor("#D9D4EE")) },
            LinearLayout.LayoutParams(look.dp(2), -1).apply { leftMargin = look.dp(13) })
        row.addView(android.widget.FrameLayout(this).apply {
            addView(LinearLayout(this@PageActivity).apply {
                gravity = Gravity.CENTER
                background = look.shape(look.card, 14)
                elevation = look.dp(2).toFloat()
                addView(look.icon(R.drawable.ic_arrow_down, 14, look.accent))
            }, android.widget.FrameLayout.LayoutParams(look.dp(28), look.dp(28), Gravity.CENTER))
        }, LinearLayout.LayoutParams(0, look.dp(28), 1f).apply { leftMargin = look.dp(10) })
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = -look.dp(6); bottomMargin = -look.dp(6) }
        return row
    }

    private fun stepMenu(anchor: View, i: Int, order: MutableList<String>, snapshot: () -> Unit, refresh: () -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = look.shape(look.card, 16)
            elevation = look.dp(8).toFloat()
            setPadding(look.dp(6), look.dp(6), look.dp(6), look.dp(6))
        }
        fun item(iconRes: Int, label: String, tint: Int, onTap: () -> Unit) {
            box.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(look.dp(10), look.dp(10), look.dp(14), look.dp(10))
                background = look.ripple(null, 10)
                addView(look.icon(iconRes, 16, tint).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = look.dp(10) })
                addView(look.text(label, 14f, tint))
                setOnClickListener { onTap() }
            })
        }
        val popup = android.widget.PopupWindow(box, look.dp(180), -2)
        item(R.drawable.ic_pencil_line, "Edit act", look.ink) {
            popup.dismiss()
            val current = order[i]
            val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(look.dp(22), look.dp(8), look.dp(22), 0) }
            val field = look.fieldRow(R.drawable.ic_file_text, "What this step should do", current, lines = 3)
            form.addView(field)
            AlertDialog.Builder(this).setTitle("Edit act").setView(form)
                .setPositiveButton("Save") { _, _ ->
                    val t = look.editText(field).text.toString().trim()
                    if (t.isNotEmpty()) { snapshot(); order[i] = t; refresh() }
                }.setNegativeButton("Cancel", null).show()
        }
        item(R.drawable.ic_copy, "Duplicate", look.ink) { popup.dismiss(); snapshot(); order.add(i + 1, order[i]); refresh() }
        item(R.drawable.ic_trash_2, "Remove", Color.parseColor("#DC2626")) { popup.dismiss(); snapshot(); order.removeAt(i); refresh() }
        popup.showAsDropDown(anchor, -look.dp(150), look.dp(4))
    }

    private fun editTrigger(index: Int) {
        startActivity(android.content.Intent(this, PageActivity::class.java)
            .putExtra("page", if (index < 0) "trigger-new" else "trigger-edit:$index"))
    }

    /** The New Auto Action sheet: condition, skill, and the automatic checkbox. */
    private fun triggerPage() {
        val index = page.substringAfter("trigger-edit:", "").toIntOrNull() ?: -1
        val list = store.triggers.toMutableList()
        val existing = list.getOrNull(index)
        var kind = existing?.kind ?: "person"
        var skill = existing?.skill ?: "Who is"
        var auto = existing?.auto ?: true
        val names = (store.skills.map { it.name } + Trigger.BUILTIN.keys + Acts.BUILTIN.map { it.name }).distinct()

        val root = android.widget.FrameLayout(this)
        val header = View(this).apply { background = Art.kind(this@PageActivity, kind) }
        root.addView(header, android.widget.FrameLayout.LayoutParams(-1, look.dp(230)))
        root.addView(look.circleButton(R.drawable.ic_chevron_left, look.ink) { finish() },
            android.widget.FrameLayout.LayoutParams(look.dp(40), look.dp(40)).apply { setMargins(look.dp(16), look.dp(16), 0, 0) })

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(look.dp(20), look.dp(10), look.dp(20), look.dp(22))
        }
        column.addView(View(this).apply { background = look.shape(Color.parseColor("#C9C9D4"), 2) },
            LinearLayout.LayoutParams(look.dp(40), look.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = look.dp(18) })
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, look.dp(16)) }
        head.addView(look.iconTile(R.drawable.ic_zap, look.accentSoft, look.accent, 56, 26))
        head.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(look.dp(14), 0, 0, 0)
            addView(look.text(if (existing == null) "New Auto Action" else "Edit Auto Action", 22f, bold = true))
            addView(look.text("Run a skill automatically based on copied text", 13.5f, look.muted).apply { setPadding(0, look.dp(3), 0, 0) })
        })
        column.addView(head)

        fun kindLabel(k: String) = (Cards.KINDS.firstOrNull { it.first == k }?.second ?: k).replaceFirstChar { it.uppercase() }
        column.addView(look.label("When the copied text has", look.violetInk))
        val keyword = look.fieldRow(R.drawable.ic_tag, "Words to look for, separated by commas", existing?.keyword ?: "")
        keyword.visibility = if (kind == "keyword") View.VISIBLE else View.GONE
        val kindRow = look.dropRow(kindIcon(kind), kindLabel(kind)) {}
        kindRow.setOnClickListener {
            AlertDialog.Builder(this).setItems(Cards.KINDS.map { it.second.replaceFirstChar { c -> c.uppercase() } }.toTypedArray()) { _, w ->
                kind = Cards.KINDS[w].first
                look.dropText(kindRow).text = kindLabel(kind)
                (kindRow.getChildAt(0) as android.widget.ImageView).setImageResource(kindIcon(kind))
                header.background = Art.kind(this@PageActivity, kind)
                keyword.visibility = if (kind == "keyword") View.VISIBLE else View.GONE
            }.show()
        }
        column.addView(kindRow)
        column.addView(keyword)
        column.addView(look.label("Run this skill", look.violetInk))
        val skillRow = look.dropRow(R.drawable.ic_zap, skill) {}
        skillRow.setOnClickListener {
            AlertDialog.Builder(this).setItems(names.toTypedArray()) { _, w ->
                skill = names[w]
                look.dropText(skillRow).text = skill
            }.show()
        }
        column.addView(skillRow)
        column.addView(look.checkRow(auto, "Automatic: run it without asking") { auto = it })
        column.addView(look.darkPill("Save", R.drawable.ic_check) {
            if (kind == "keyword" && look.editText(keyword).text.isBlank()) {
                look.toast("Type the words to look for")
            } else {
                val t = Trigger(kind, look.editText(keyword).text.toString().trim(), skill, auto)
                if (existing == null) list.add(t) else list[index] = t
                store.triggers = list
                finish()
            }
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = look.dp(8) })
        if (existing != null) column.addView(look.text("Remove auto action", 14f, Color.parseColor("#DC2626"), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, look.dp(14), 0, 0)
            setOnClickListener {
                AlertDialog.Builder(this@PageActivity).setMessage("Remove this auto action?")
                    .setPositiveButton("Remove") { _, _ -> list.removeAt(index); store.triggers = list; finish() }
                    .setNegativeButton("Cancel", null).show()
            }
        }, LinearLayout.LayoutParams(-1, -2))

        val sheet = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = look.sheetShape()
            addView(column)
        }
        root.addView(sheet, android.widget.FrameLayout.LayoutParams(-1, -1).apply { topMargin = look.dp(190) })
        setContentView(root)
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
        val ready = { ui.icon(R.drawable.ic_check, 20, Color.parseColor("#16A34A")) }
        column.addView(ui.rows(
            ui.row(R.drawable.ic_pencil_line, "Gmail and Outlook", if ("gmail" in store.googleServices) "Gmail is connected: emails are sent or drafted for you" else "Writes the email and opens it as a ready draft. Connect Google to send by itself", ready()),
            ui.row(R.drawable.ic_pencil_line, "LinkedIn", "Writes a post and opens LinkedIn with it filled in. \"Who is\" finds people", ready()),
            ui.row(R.drawable.ic_pencil_line, "WhatsApp and Telegram", "Writes the message and opens the app to pick a chat", ready()),
            ui.row(R.drawable.ic_list, "Calendar, YouTube and X", "Opens an event, a search or a post with your text filled in", ready()),
        ))
        val services = store.googleServices
        val all = services.containsAll(listOf("meet", "calendar", "gmail"))
        val meetState = when {
            all -> "Connected: makes Meet links, saves Calendar events and sends or drafts Gmail by itself"
            store.googleConnected -> "Connected for ${services.joinToString(", ").ifEmpty { "Meet" }} only. Reconnect Google on the dashboard to add Calendar and Gmail"
            store.deviceToken.isEmpty() -> "Needs the web dashboard: pair this keyboard, then connect Google there once. Until then the shortcut offers an instant no-account link"
            else -> "Open your dashboard → Connections → Connect Google, then Sync now"
        }
        column.addView(ui.rows(ui.row(R.drawable.ic_video, "Google: Meet, Calendar, Gmail", meetState,
            if (all) ready() else ui.chevron()) { if (!all) startActivity(android.content.Intent(this, PageActivity::class.java).putExtra("page", "dashboard")) }))
        column.addView(ui.rows(
            ui.row(R.drawable.ic_square_terminal, "Notion", if (store.notionToken.isEmpty()) "Not connected" else "Connected", ui.chevron()) {
                editToken("Notion", "Paste an internal integration token from notion.so/my-integrations, then share the pages you want with that integration.", store.notionToken) { store.notionToken = it }
            },
            ui.row(R.drawable.ic_list, "Calendly", if (store.calendlyToken.isEmpty()) "Not connected" else "Connected", ui.chevron()) {
                editToken("Calendly", "Paste a personal access token from Calendly → Integrations → API & webhooks.", store.calendlyToken) { store.calendlyToken = it }
            },
            *TOKEN_APPS.map { (field, title, help) ->
                val token = store.token(field)
                ui.row(R.drawable.ic_square_terminal, title, if (token.isEmpty()) "Not connected" else "Connected", ui.chevron()) {
                    editToken(title, help, token) { store.setToken(field, it) }
                }
            }.toTypedArray(),
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

    // ---------- AI service ----------

    private fun aiPage(column: LinearLayout) {
        column.addView(ui.topBar("AI service", "Which AI runs your skills, and your keys"))
        column.addView(ui.rows(ui.row(R.drawable.ic_sparkles, "Keyboard assistant", "Skills, the wheel and Ask AI", ui.toggle(store.assistantOn) { store.assistantOn = it })))

        column.addView(ui.section(R.drawable.ic_box, "Provider"))
        column.addView(ui.rows(
            choice(R.drawable.ic_sparkles, "Claude", "Anthropic · recommended", store.provider == "anthropic") { store.provider = "anthropic" },
            choice(R.drawable.ic_sparkles, "OpenAI", "GPT models", store.isOpenAi) { store.provider = "openai" },
            choice(R.drawable.ic_plug, "AI Router or other gateway", "Any service that speaks the OpenAI chat format", store.isCustom) { store.provider = "custom" },
        ))

        column.addView(ui.section(R.drawable.ic_layers, "Model"))
        when {
            store.isOpenAi -> column.addView(ui.rows(*(Ai.OPENAI_MODELS.map { (id, label) ->
                choice(R.drawable.ic_box, label, null, store.openaiModel == id) { store.openaiModel = id }
            } + ui.row(R.drawable.ic_pencil_line, "Other model", if (Ai.OPENAI_MODELS.none { it.first == store.openaiModel }) store.openaiModel else "Type any model ID", ui.chevron()) { customOpenAiModel() }).toTypedArray()))
            store.isCustom -> column.addView(ui.rows(
                ui.row(R.drawable.ic_box, "Model", store.customModel, ui.chevron()) { customGateway() },
                ui.row(R.drawable.ic_globe, "Address", store.customBase, ui.chevron()) { customGateway() },
                ui.row(R.drawable.ic_refresh_cw, "Use AI Router · Claude Haiku 5.5", "The default gateway set-up") {
                    store.customBase = ChatCompat.DEFAULT_BASE; store.customModel = ChatCompat.DEFAULT_MODEL; render()
                },
            ))
            else -> column.addView(ui.rows(*Ai.MODELS.map { (id, label) ->
                choice(R.drawable.ic_box, label.substringBefore(" —"), label.substringAfter("— ", ""), store.model == id) { store.model = id }
            }.toTypedArray()))
        }

        column.addView(ui.section(R.drawable.ic_shield, "Keys"))
        note(column, "Saved keys are never shown again, only their last four characters. Each key is sent only to the service it belongs to" +
            if (store.deviceToken.isNotEmpty()) ", and to your own dashboard so it stays in sync." else ".")
        fun keyRow(title: String, where: String, hint: String, current: String, save: (String) -> Unit): LinearLayout {
            val tail = Store.keyHint(current)
            val state = if (current.isEmpty()) "Not set" else if (tail.isEmpty()) "Saved" else "Saved · ends in $tail"
            return ui.row(R.drawable.ic_shield, title, state, if (current.isEmpty()) ui.text("Add", 14f, ui.iconTint, bold = true) else ui.chevron()) {
                editKey(title, where, hint, tail, current.isNotEmpty(), save)
            }
        }
        column.addView(ui.rows(
            keyRow("Claude key", "console.anthropic.com → API keys", "sk-ant-…", store.apiKey) { store.apiKey = it },
            keyRow("OpenAI key", "platform.openai.com → API keys", "sk-…", store.openaiKey) { store.openaiKey = it },
            keyRow("Gateway key", "Your AI Router or gateway account", "sk-air-v1-…", store.customKey) { store.customKey = it },
        ))

        column.addView(ui.section(R.drawable.ic_zap, "Agent mode"))
        column.addView(ui.rows(
            choice(R.drawable.ic_zap, "Auto", "Results go straight into the text field", store.agentMode == "auto") { store.agentMode = "auto" },
            choice(R.drawable.ic_message_square, "Ask first", "Show the result card before anything changes", store.agentMode == "ask") { store.agentMode = "ask" },
            choice(R.drawable.ic_settings, "Manual", "Only runs the skills you tap or hold", store.agentMode == "manual") { store.agentMode = "manual" },
        ))
    }

    /** A saved key is never put back into the field: the field starts empty and shows the last four characters. */
    private fun editKey(title: String, where: String, hint: String, tail: String, saved: Boolean, save: (String) -> Unit) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0) }
        form.addView(ui.text("Get one at $where." + if (saved) "\nSaved key" + (if (tail.isEmpty()) "" else " ends in $tail") + ". Paste a new one to replace it." else "", 13.5f, ui.muted))
        val field = ui.field(hint, "", secret = true)
        form.addView(field)
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(form)
            .setPositiveButton("Save") { _, _ ->
                val key = field.text.toString().trim()
                if (key.isEmpty()) ui.toast("Nothing changed") else { save(key); ui.toast("Key saved" + if (store.deviceToken.isNotEmpty()) " and sent to your dashboard" else "") }
                render()
            }
            .setNegativeButton("Cancel", null)
        if (saved) dialog.setNeutralButton("Remove") { _, _ -> save(""); render() }
        dialog.show()
    }

    /** Any service that speaks the OpenAI chat format: its address and the model name it expects. */
    private fun customGateway() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0) }
        form.addView(ui.text("For AI Router, OpenRouter and other OpenAI-compatible gateways. Your text and key go to this address, so only use a service you trust.", 13.5f, ui.muted))
        val base = ui.field("https://api.airouter.in/v1", store.customBase)
        val model = ui.field("anthropic/claude-haiku-5.5", store.customModel)
        form.addView(ui.text("Address", 13.5f, bold = true).apply { setPadding(0, ui.dp(12), 0, 0) })
        form.addView(base)
        form.addView(ui.text("Model", 13.5f, bold = true).apply { setPadding(0, ui.dp(8), 0, 0) })
        form.addView(model)
        AlertDialog.Builder(this).setTitle("Gateway").setView(form)
            .setPositiveButton("Use") { _, _ ->
                val id = model.text.toString().trim()
                val address = try { ChatCompat.normaliseBase(base.text.toString()) } catch (e: AiException) { null }
                if (address == null || !id.matches(Regex("[A-Za-z0-9._:/-]{1,100}"))) ui.toast("Check the address (https://…) and the model name")
                else { store.provider = "custom"; store.customBase = address; store.customModel = id; render() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun customOpenAiModel() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0) }
        form.addView(ui.text("Type the model ID exactly as OpenAI lists it.", 13.5f, ui.muted))
        val field = ui.field("e.g. gpt-5.5", if (store.isOpenAi) store.openaiModel else "")
        form.addView(field)
        AlertDialog.Builder(this).setTitle("OpenAI model").setView(form)
            .setPositiveButton("Use") { _, _ ->
                val id = field.text.toString().trim()
                if (id.matches(Regex("[A-Za-z0-9._:-]{1,80}"))) { store.provider = "openai"; store.openaiModel = id; render() }
                else ui.toast("That doesn't look like a model ID")
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- typing ----------

    private fun typingPage(column: LinearLayout) {
        column.addView(ui.topBar("Typing", "Corrections, suggestions and shortcuts"))
        column.addView(ui.rows(
            ui.row(R.drawable.ic_spell_check, "Auto-correct", "Fix typos as you type; backspace puts your word back", ui.toggle(store.autoCorrect) { store.autoCorrect = it }),
            ui.row(R.drawable.ic_message_square_more, "Suggestions", "Word completions above the keys", ui.toggle(store.suggestions) { store.suggestions = it }),
            ui.row(R.drawable.ic_keyboard, "Swipe typing", "Slide across the letters to write a word", ui.toggle(store.swipeTyping) { store.swipeTyping = it }),
            ui.row(R.drawable.ic_languages, "Hindi script suggestions", "Type Hinglish, tap the word in Devanagari: kaise → कैसे", ui.toggle(store.hindiScript) { store.hindiScript = it }),
        ))
        column.addView(ui.rows(ui.row(R.drawable.ic_keyboard, "Text shortcuts", if (store.shortcuts.isEmpty()) "Type ;addr to put in your address" else "${store.shortcuts.size} saved", ui.chevron()) { go("textshortcuts") }))
        column.addView(ui.rows(
            ui.row(R.drawable.ic_keyboard, "Shortcuts & slash commands", "Hold gestures and / commands", ui.chevron()) { go("shortcuts") },
            ui.row(R.drawable.ic_layers, "Templates", "Ready-made daily actions for your apps", ui.chevron()) { go("templates") },
            ui.row(R.drawable.ic_settings, "Android keyboard settings", "Turn Verbo on or switch keyboards", ui.chevron()) {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
        ))
    }

    private fun navActive(page: String) = when {
        page == "usage" || page == "dashboard" -> "activity"
        page == "triggers" || page == "chains" || page == "copysettings" -> "actions"
        page == "shelves" || page == "prompts" || page.startsWith("category:") || page.startsWith("skill-") -> "skills"
        else -> "settings"
    }

    /** The shared bottom bar; tab taps jump back to the main activity on the right tab. */
    private fun mainNav(active: String) = look.nav(active,
        { MainActivity.tab = "home"; startActivity(android.content.Intent(this, MainActivity::class.java)); finish() },
        { MainActivity.tab = "library"; startActivity(android.content.Intent(this, MainActivity::class.java)); finish() },
        { if (page != "triggers") go("triggers") },
        { if (page != "usage") go("usage") },
        { MainActivity.tab = "settings"; startActivity(android.content.Intent(this, MainActivity::class.java)); finish() })

    private fun go(page: String) = startActivity(android.content.Intent(this, PageActivity::class.java).putExtra("page", page))

    // ---------- shortcuts ----------

    private fun shortcutsPage(column: LinearLayout) {
        column.addView(ui.topBar("Shortcuts & Triggers", "Use / command to run actions"))
        column.addView(ui.rows(
            ui.row(R.drawable.ic_sparkle, "Hold the wide bar", "Opens the wheel: slide to Ask AI, Translate, Rewrite, Voice or Clips and let go"),
            ui.row(R.drawable.ic_keyboard, "Hold a marked letter", "Runs the skill bound to that key; set the letter when editing a skill"),
            ui.row(R.drawable.ic_message_square, "Use copied text", "Copy a message, then tap this chip above the keys to give it to the assistant as context"),
            ui.row(R.drawable.ic_mic, "Tap the microphone", "Opens the listening panel. Transcribe tidies what you said; Command treats it as a request"),
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
                if (mic) ui.icon(R.drawable.ic_check, 20, Color.parseColor("#16A34A")) else ui.chevron()) {
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
        column.addView(ui.topBar("Languages", "Typing layout and translate languages"))
        column.addView(ui.rows(ui.row(R.drawable.ic_keyboard, "English (US)", "Typing layout · QWERTY", ui.icon(R.drawable.ic_check, 20, ui.iconTint))))
        note(column, "English is the only typing layout so far. Skills and voice still work in other languages: the assistant answers in the language you write in.")
        column.addView(ui.section(R.drawable.ic_languages, "Translate into"))
        note(column, "Slide to a flag and let go to add or remove that language. Your languages come first, just like the keyboard's Translate wheel; spare slots suggest new ones.")
        val shown = (store.languages + Store.ALL_LANGUAGES.filter { it !in store.languages }).take(5)
        val wheelBox = android.widget.FrameLayout(this).apply {
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, ui.dp(20).toFloat())
                }
            }
            clipToOutline = true
        }
        wheelBox.addView(LanguageWheel(this).apply {
            embedded = true
            languages = shown
            onPick = { i ->
                val name = shown[i]
                val now = store.languages
                store.languages = if (name in now) now - name else (now + name).distinct()
                render()
            }
        }, android.widget.FrameLayout.LayoutParams(-1, ui.dp(250)))
        column.addView(wheelBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
        val mine = store.languages
        column.addView(ui.rows(*Store.ALL_LANGUAGES.map { name ->
            ui.row(R.drawable.ic_languages, name, trailing = ui.toggle(name in mine) { on ->
                val now = store.languages
                store.languages = if (on) (now + name).distinct() else now - name
            })
        }.toTypedArray()))
    }

    // ---------- first-run guide ----------

    private fun imm() = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
    private fun keyboardEnabled() = imm().enabledInputMethodList.any { it.packageName == packageName }
    private fun keyboardSelected() = (android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) ?: "").startsWith("$packageName/")

    private fun signInWithGoogle() {
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(Sync.googleSignInUrl(store))))
        } catch (e: SyncException) {
            ui.toast(e.message ?: "Couldn't start the sign-in")
        } catch (e: Exception) {
            ui.toast("No browser found to sign in with")
        }
    }

    private fun welcomePage(column: LinearLayout) {
        column.addView(ui.topBar("Welcome to Verbo", "A few quick steps, then your keyboard does the rest"))
        val enabled = keyboardEnabled()
        val selected = keyboardSelected()
        val signedIn = store.deviceToken.isNotEmpty()
        val hasKey = store.activeKey.isNotEmpty()
        val calendar = checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val done = listOf(enabled, selected, signedIn || hasKey).count { it }
        note(column, "$done of 3 steps done. The ticks update by themselves when you come back here.")
        fun tick(ok: Boolean) = if (ok) ui.icon(R.drawable.ic_check, 22, Color.parseColor("#16A34A")) else ui.chevron()
        column.addView(ui.rows(
            ui.row(R.drawable.ic_keyboard, "1. Turn on Verbo", if (enabled) "Done" else "Switch on Verbo Keyboard in the list that opens", tick(enabled)) {
                if (!enabled) startActivity(android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
            ui.row(R.drawable.ic_check, "2. Choose Verbo", if (selected) "Done: Verbo is your keyboard" else "Pick Verbo in the keyboard chooser", tick(selected)) {
                if (!enabled) ui.toast("Turn on Verbo first") else if (!selected) imm().showInputMethodPicker()
            },
            ui.row(R.drawable.ic_cloud, "3. Continue with Google", if (signedIn) "Signed in. Skills, agents and keys sync with " + store.serverUrl.removePrefix("https://") else "One account for this phone and the web dashboard", tick(signedIn)) {
                if (!signedIn) signInWithGoogle()
            },
            ui.row(R.drawable.ic_sparkles, "Or use my own AI key", if (hasKey) "Key saved for ${store.providerName}" else "Skip sign-in and paste a Claude, OpenAI or gateway key", tick(hasKey)) { go("ai") },
            ui.row(R.drawable.ic_box, "Allow calendar (optional)", if (calendar) "Allowed: events save in one tap" else "So Schedule it can save events straight away", tick(calendar)) {
                if (!calendar) requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 2)
            },
        ))
        column.addView(ui.section(R.drawable.ic_wand_sparkles, "Try it"))
        note(column, "Type a rough sentence below with Verbo, then hold the lit P key to polish it.")
        val box = ui.cardBox().apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(12)) }
        box.addView(ui.field("i reached late sorry, lets meet tmrw 5pm?", lines = 3))
        column.addView(box)
        column.addView(ui.rows(
            ui.row(R.drawable.ic_keyboard, "Hold a lit letter", "Runs its skill or agent. Set keys per app under Settings, Keys"),
            ui.row(R.drawable.ic_circle_dashed, "Hold the space bar", "The wheel: translate, rewrite, ask AI, voice, clips"),
            ui.row(R.drawable.ic_layers, "Copy a message", "Acts and auto actions appear for it, like Reply options or Schedule it"),
            ui.row(R.drawable.ic_cloud, "Build agents on the web", "Drag blocks on the dashboard; they arrive here after a sync"),
        ))
        column.addView(ui.button(if (done == 3) "Start using Verbo" else "Finish later", R.drawable.ic_check, filled = done == 3) {
            store.onboarded = true
            finish()
        })
    }

    // ---------- keys: hold a letter, per app ----------

    private class KeyRow(val action: KeyAction, val fromSkill: Boolean)

    private fun keyRows(): List<KeyRow> =
        (store.skills.filter { it.key.length == 1 }.map { KeyRow(KeyAction(it.key[0], "", "skill:" + it.name), true) } + store.keymap.map { KeyRow(it, false) })
            .sortedWith(compareBy({ it.action.app.isNotEmpty() }, { it.action.app }, { it.action.key }))

    private fun keysPage(column: LinearLayout) {
        column.addView(ui.topBar("Keys", "Hold a letter to run anything, per app"))
        note(column, "A tap still types the letter; holding it runs the action. A key set for one app wins over the same key in all apps. You can set these on the web dashboard too.")
        column.addView(ui.rows(ui.row(R.drawable.ic_keyboard, "Held-key actions", if (store.keysOn) "On" else "Paused: letters just type",
            ui.toggle(store.keysOn) { store.keysOn = it; if (store.deviceToken.isNotEmpty()) Sync.soon(store); render() })))
        val rows = keyRows()
        val skills = store.skills
        if (rows.isNotEmpty()) column.addView(ui.rows(*rows.map { r ->
            ui.row(R.drawable.ic_zap, "Hold ${r.action.key.uppercaseChar()}  ·  ${KeyAction.appName(r.action.app)}", KeyAction.label(r.action.action, skills), ui.chevron()) { keyActionMenu(r) }
        }.toTypedArray()))
        else note(column, "No key actions yet.")
        column.addView(ui.button("Add key action", R.drawable.ic_plus, filled = true) { pickApp(null) })
    }

    private fun keyActionMenu(r: KeyRow) {
        AlertDialog.Builder(this).setTitle("Hold ${r.action.key.uppercaseChar()} in ${KeyAction.appName(r.action.app)}")
            .setMessage(KeyAction.label(r.action.action, store.skills))
            .setPositiveButton("Change") { _, _ -> pickApp(r) }
            .setNeutralButton("Delete") { _, _ -> saveKeyAction(r, null) }
            .setNegativeButton("Close", null).show()
    }

    /** Step 1 of 3: which app. Common apps first, then any installed app. */
    private fun pickApp(prev: KeyRow?) {
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val installed = try {
            packageManager.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
                .filter { (pkg, _) -> pkg != packageName && KeyAction.COMMON_APPS.none { it.first == pkg } }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        } catch (e: Exception) { emptyList() }
        val apps = KeyAction.COMMON_APPS + installed
        AlertDialog.Builder(this).setTitle("In which app?")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i -> pickLetter(prev, apps[i].first) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Step 2 of 3: which letter. Letters already used in that app say what they run now. */
    private fun pickLetter(prev: KeyRow?, app: String) {
        val used = keyRows().filter { it.action.app == app && it.action != prev?.action }
        val letters = ('a'..'z').toList()
        val labels = letters.map { l ->
            used.firstOrNull { it.action.key == l }?.let { "${l.uppercaseChar()}   (now ${KeyAction.label(it.action.action, store.skills)})" } ?: l.uppercaseChar().toString()
        }
        AlertDialog.Builder(this).setTitle("Hold which key?")
            .setItems(labels.toTypedArray()) { _, i -> pickAction(prev, app, letters[i]) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Step 3 of 3: what it runs. */
    private fun pickAction(prev: KeyRow?, app: String, letter: Char) {
        val options = store.skills.map { "skill:" + it.name } + Acts.BUILTIN.map { "act:" + it.name } + Store.WHEEL_TOOLS.map { "tool:" + it.first } + "text:"
        val labels = options.map { if (it == "text:") "✍️ Type a saved text…" else KeyAction.label(it, store.skills) }
        AlertDialog.Builder(this).setTitle("Hold ${letter.uppercaseChar()} to…")
            .setItems(labels.toTypedArray()) { _, i ->
                if (options[i] != "text:") saveKeyAction(prev, KeyAction(letter, app, options[i]))
                else {
                    val field = ui.field("On my way!", lines = 3)
                    AlertDialog.Builder(this).setTitle("Text to type").setView(ui.pad(field, top = 8))
                        .setPositiveButton("Save") { _, _ ->
                            val text = field.text.toString()
                            if (text.isBlank()) ui.toast("Type the text first") else saveKeyAction(prev, KeyAction(letter, app, "text:$text"))
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Same rules as the dashboard: one action per letter per app; a skill in all apps uses the skill's own hold key. */
    private fun saveKeyAction(prev: KeyRow?, next: KeyAction?) {
        var skills = store.skills
        var map = store.keymap
        if (prev != null) {
            if (prev.fromSkill) skills = skills.map { if (it.key == prev.action.key.toString() && "skill:" + it.name == prev.action.action) it.copy(key = "") else it }
            else map = map.filterNot { it.key == prev.action.key && it.app == prev.action.app }
        }
        if (next != null) {
            map = map.filterNot { it.key == next.key && it.app == next.app }
            if (next.app.isEmpty()) skills = skills.map { if (it.key == next.key.toString()) it.copy(key = "") else it }
            val name = if (next.action.startsWith("skill:")) next.action.removePrefix("skill:") else null
            if (name != null && next.app.isEmpty() && skills.any { it.name == name }) skills = skills.map { if (it.name == name) it.copy(key = next.key.toString()) else it }
            else map = map + next
        }
        store.skills = skills
        store.keymap = map
        if (store.deviceToken.isNotEmpty()) Sync.soon(store)
        ui.toast(if (next == null) "Key action removed" else "Hold ${next.key.uppercaseChar()}: ${KeyAction.label(next.action, skills)}")
        render()
    }

    // ---------- dashboard ----------

    private fun dashboardPage(column: LinearLayout) {
        column.addView(ui.topBar("Web dashboard", "Manage skills and connections in a browser"))
        if (store.deviceToken.isNotEmpty()) {
            val last = if (store.lastSync == 0L) "not yet" else DateUtils.getRelativeTimeSpanString(store.lastSync).toString()
            column.addView(ui.rows(ui.row(R.drawable.ic_cloud, "Paired with " + store.serverUrl.removePrefix("https://"), "Last sync: $last")))
            note(column, "Skills, keys, connections and keyboard settings stay in step both ways. A change made here is sent to the dashboard first, so it is never replaced by an older copy.")
            val buttons = LinearLayout(this)
            buttons.addView(ui.button("Sync now", R.drawable.ic_refresh_cw, filled = true) { background({ Sync.pull(store) }, "Synced") }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(6) })
            buttons.addView(ui.button("Unpair", R.drawable.ic_x) { store.unpair(); render() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(6) })
            column.addView(buttons)
            return
        }
        note(column, "Sign in with Google: this phone joins your account on the Verbo dashboard, and skills, agents, keys and connections sync both ways.")
        column.addView(ui.button("Continue with Google", R.drawable.ic_cloud, filled = true) { signInWithGoogle() })
        column.addView(ui.section(R.drawable.ic_keyboard, "Or pair with a code"))
        note(column, "For your own server, or without Google: on the dashboard open Settings → Phones → Pair a phone.")
        val box = ui.cardBox().apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(12)) }
        val address = ui.field("Dashboard address, e.g. verbo.example.com")
        val code = ui.field("Pairing code")
        box.addView(address); box.addView(code)
        column.addView(box)
        column.addView(ui.button("Pair", R.drawable.ic_cloud) {
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

    // ---------- create a skill by describing it ----------

    private fun labelled(box: LinearLayout, label: String, value: String) {
        if (value.isBlank()) return
        box.addView(ui.text(label, 11.5f, ui.muted, bold = true).apply { letterSpacing = 0.08f; setPadding(0, ui.dp(12), 0, ui.dp(4)) })
        box.addView(ui.text(value, 14.5f).apply {
            background = ui.shape(ui.accentSoft, 10)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            setLineSpacing(0f, 1.15f)
        })
    }

    private fun createPage(column: LinearLayout) {
        column.addView(ui.topBar("Create a skill", "Describe it and the assistant designs it"))
        val made = createdName?.let { name -> store.skills.firstOrNull { it.name == name } }
        if (made != null) return connectSkill(column, made)

        val box = ui.cardBox().apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(12)) }
        val field = ui.field("e.g. Replace my text with emojis", wish, lines = 3)
        box.addView(field)
        column.addView(box)
        column.addView(ui.button(if (designing) "Designing…" else if (draft == null) "Design it" else "Design it again", R.drawable.ic_wand_sparkles, filled = true) {
            val text = field.text.toString().trim()
            when {
                designing -> {}
                text.isEmpty() -> ui.toast("Say what the skill should do")
                store.activeKey.isBlank() -> ui.toast("Add your ${store.providerName} API key first (Settings → AI service)")
                else -> design(text)
            }
        })

        val plan = draft
        if (plan == null) {
            column.addView(ui.section(R.drawable.ic_sparkles, "Ideas"))
            column.addView(ui.rows(*listOf(
                "Turn my notes into a polite email to my landlord",
                "Reply to the copied message in Hindi, friendly and short",
                "Find this place and share its address with a map link",
                "Make my message sound confident, not apologetic",
            ).map { idea -> ui.row(R.drawable.ic_zap, idea, null, ui.chevron()) { wish = idea; render() } }.toTypedArray()))
            column.addView(ui.button("Write it myself", R.drawable.ic_pencil_line) { editSkill(-1, null) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
            return
        }
        // The plan, shown for approval before anything is saved.
        column.addView(ui.section(R.drawable.ic_layers, "The plan"))
        val card = ui.cardBox().apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(16)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ui.text(plan.emoji.ifEmpty { "✦" }, 22f).apply { gravity = Gravity.CENTER; background = ui.shape(ui.accentSoft, 12) }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        head.addView(ui.text(plan.name, 18f, bold = true).apply { setPadding(ui.dp(12), 0, 0, 0) })
        card.addView(head)
        labelled(card, "WHAT IT DOES", plan.does)
        labelled(card, "INPUT", plan.input)
        labelled(card, "OUTPUT", plan.output)
        labelled(card, "INSTRUCTION THE ASSISTANT FOLLOWS", plan.instruction)
        column.addView(card)
        column.addView(ui.button("Create this skill", R.drawable.ic_check, filled = true) {
            val skills = store.skills
            // Names must be unique: skills are found by name on the wheel and in slash commands.
            var name = plan.name
            var n = 2
            while (skills.any { it.name.equals(name, ignoreCase = true) }) name = plan.name + " " + n++
            store.skills = skills + Skill(plan.emoji, name, plan.instruction, plan.group)
            createdName = name
            draft = null
            render()
        })
    }

    private fun design(text: String) {
        wish = text
        designing = true
        render()
        val engine = Engine(store)
        Thread {
            val outcome = try {
                Ai.parseDesign(engine.run(Ai.DESIGN_TASK, text, web = false, tools = emptyList(), cancelled = { isDestroyed }, label = "Create a skill").text)
                    ?: throw AiException("The assistant's plan came back in a form Verbo couldn't read. Try again")
            } catch (e: AiException) {
                e
            } catch (e: Exception) {
                AiException("Something went wrong. Try again")
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                designing = false
                if (outcome is Ai.SkillDraft) draft = outcome else ui.toast((outcome as Exception).message ?: "Failed")
                render()
            }
        }.start()
    }

    /** Shown right after a skill is created: where it should live and what it can use. */
    private fun connectSkill(column: LinearLayout, skill: Skill) {
        val id = "skill:" + skill.name
        column.addView(ui.rows(ui.row(R.drawable.ic_check, (skill.emoji + "  " + skill.name).trim(), "Created. It is now a skill key above your keyboard", ui.icon(R.drawable.ic_check, 20, Color.parseColor("#16A34A")))))
        column.addView(ui.section(R.drawable.ic_plug, "Connect it"))
        val wheel = store.wheel
        column.addView(ui.rows(
            ui.row(R.drawable.ic_circle_dashed, "On the shortcut wheel", if (id in wheel) "Slide to it after holding the bar" else "Hold the bar, slide, release", ui.toggle(id in wheel) { on ->
                val now = store.wheel
                when {
                    !on -> store.wheel = now - id
                    now.size >= Store.MAX_WHEEL -> { ui.toast("The wheel holds ${Store.MAX_WHEEL}. Remove one on the wheel page first"); render() }
                    else -> store.wheel = now + id
                }
            }),
            ui.row(R.drawable.ic_keyboard, "Hold key", if (skill.key.isEmpty()) "None. Pick a letter to run it by holding that key" else "Hold ${skill.key.uppercase()} to run it", ui.chevron()) { pickKey(skill) },
            ui.row(R.drawable.ic_zap, "Apps and APIs it can use", "Web search, places, weather, Notion, Calendly, Google Meet, your own APIs", ui.chevron()) {
                startActivity(android.content.Intent(this, PageActivity::class.java).putExtra("page", "automate"))
            },
        ))
        note(column, "Every skill can use everything you have connected under Apps and APIs. Apps like Gmail, WhatsApp, Calendar and Maps need no connection: the skill opens them with a draft filled in.")
        val buttons = LinearLayout(this)
        buttons.addView(ui.button("Try it", R.drawable.ic_keyboard, filled = true) {
            startActivity(android.content.Intent(this, PageActivity::class.java).putExtra("page", "try"))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(6) })
        buttons.addView(ui.button("Create another", R.drawable.ic_plus) { createdName = null; wish = ""; render() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(6) })
        column.addView(buttons)
    }

    private fun pickKey(skill: Skill) {
        val skills = store.skills
        val taken = skills.filter { it.name != skill.name && it.key.isNotEmpty() }.associate { it.key to it.name }
        val letters = ('a'..'z').map { it.toString() }
        val labels = listOf("No key") + letters.map { l -> l.uppercase() + (taken[l]?.let { "   (now runs $it)" } ?: "") }
        AlertDialog.Builder(this).setTitle("Hold key for ${skill.name}").setItems(labels.toTypedArray()) { _, which ->
            val letter = if (which == 0) "" else letters[which - 1]
            // A letter runs one skill, so taking it frees it from whichever skill had it.
            store.skills = skills.map { if (it.name == skill.name) it.copy(key = letter) else if (letter.isNotEmpty() && it.key == letter) it.copy(key = "") else it }
            render()
        }.show()
    }

    // ---------- wheel ----------

    private fun wheelLabel(id: String) = Store.WHEEL_TOOLS.firstOrNull { it.first == id }?.second ?: id.removePrefix("skill:")

    private fun wheelIcon(id: String) = when (id) {
        "ai" -> R.drawable.ic_sparkles
        "translate" -> R.drawable.ic_languages
        "rewrite" -> R.drawable.ic_pencil_line
        "meet" -> R.drawable.ic_video
        "voice" -> R.drawable.ic_mic
        "clips" -> R.drawable.ic_clipboard
        else -> R.drawable.ic_zap
    }

    private fun smallButton(icon: Int, description: String, enabled: Boolean = true, onTap: () -> Unit) =
        ui.icon(icon, 20, if (enabled) ui.ink else Color.parseColor("#B9B9C6")).apply {
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            layoutParams = LinearLayout.LayoutParams(ui.dp(38), ui.dp(38))
            contentDescription = description
            if (enabled) { background = ui.ripple(null, 19); setOnClickListener { onTap() } }
        }

    private fun wheelPage(column: LinearLayout) {
        column.addView(ui.topBar("Shortcut wheel", "Your favourite tools, one slide away"))
        note(column, "Hold the wide bar and the wheel opens under your finger. Slide to a shortcut and let go to run it; let go in the middle to cancel.")
        column.addView(ui.rows(ui.row(R.drawable.ic_circle_dashed, "Hold the bar for the wheel", "When off, holding the bar asks the assistant straight away", ui.toggle(store.wheelOn) { store.wheelOn = it })))

        val skills = store.skills
        // Skills that were renamed or deleted drop off the wheel.
        val items = store.wheel.filter { id -> Store.WHEEL_TOOLS.any { it.first == id } || skills.any { "skill:" + it.name == id } }
        fun title(id: String) = skills.firstOrNull { "skill:" + it.name == id }?.let { (it.emoji + "  " + it.name).trim() } ?: wheelLabel(id)
        fun set(next: List<String>) { store.wheel = next; render() }

        column.addView(ui.section(R.drawable.ic_layers, "On the wheel (${items.size} of ${Store.MAX_WHEEL})"))
        if (items.isEmpty()) note(column, "Nothing yet. Add shortcuts from the lists below.")
        else column.addView(ui.rows(*items.mapIndexed { i, id ->
            val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            controls.addView(smallButton(R.drawable.ic_arrow_big_up, "Move left on the wheel", i > 0) {
                set(items.toMutableList().apply { add(i - 1, removeAt(i)) })
            })
            controls.addView(smallButton(R.drawable.ic_x, "Remove from the wheel") { set(items - id) })
            ui.row(wheelIcon(id), title(id), if (i == 0) "Leftmost" else if (i == items.size - 1) "Rightmost" else null, controls)
        }.toTypedArray()))

        fun addRows(ids: List<String>, subtitle: (String) -> String?) = ids.filter { it !in items }.map { id ->
            ui.row(wheelIcon(id), title(id), subtitle(id), smallButton(R.drawable.ic_plus, "Add to the wheel") {
                if (items.size >= Store.MAX_WHEEL) ui.toast("The wheel holds ${Store.MAX_WHEEL}. Remove one first") else set(items + id)
            })
        }
        val tools = addRows(Store.WHEEL_TOOLS.map { it.first }) { id ->
            when (id) {
                "ai" -> "Treats what you typed as a request"
                "translate" -> "Pick a language, or all of yours at once"
                "rewrite" -> "Pick a style from your Edit skills"
                "meet" -> "Drops a new meeting link into the message"
                "voice" -> "Dictate or speak a request"
                else -> "Paste saved snippets"
            }
        }
        val mine = addRows(skills.map { "skill:" + it.name }) { id -> skills.firstOrNull { "skill:" + it.name == id }?.instruction }
        mine.forEach { row -> ((row.getChildAt(1) as LinearLayout).getChildAt(1) as? android.widget.TextView)?.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END } }
        if (tools.isNotEmpty()) {
            column.addView(ui.section(R.drawable.ic_circle_dashed, "Add a tool"))
            column.addView(ui.rows(*tools.toTypedArray()))
        }
        if (mine.isNotEmpty()) {
            column.addView(ui.section(R.drawable.ic_sparkles, "Add one of my skills"))
            column.addView(ui.rows(*mine.toTypedArray()))
        }
        column.addView(ui.button("New skill", R.drawable.ic_plus) { editSkill(-1, null) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
    }

    // ---------- skins ----------

    private fun choice(icon: Int, title: String, subtitle: String?, selected: Boolean, onPick: () -> Unit) =
        ui.row(icon, title, subtitle, if (selected) ui.icon(R.drawable.ic_check, 20, ui.iconTint) else null) { onPick(); render() }

    private fun skinsPage(column: LinearLayout) {
        column.addView(ui.topBar("Look", "Skin, key style and size"))
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
        column.addView(ui.section(R.drawable.ic_keyboard, "Height"))
        val heightLabel = ui.text("${store.heightPercent}%", 15f, bold = true).apply {
            gravity = Gravity.CENTER
            background = ui.shape(ui.card, 16, ui.line)
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
            minWidth = ui.dp(66)
        }
        val heightRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heightRow.addView(android.widget.SeekBar(this).apply {
            max = (Store.MAX_HEIGHT - Store.MIN_HEIGHT) / 5
            progress = (store.heightPercent - Store.MIN_HEIGHT) / 5
            progressTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E4E4EC"))
            thumbTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            contentDescription = "Keyboard height"
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    store.heightPercent = Store.MIN_HEIGHT + value * 5
                    heightLabel.text = "${store.heightPercent}%"
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        heightRow.addView(heightLabel)
        column.addView(heightRow)
        note(column, "\nChanges apply the next time the keyboard opens.")
    }

    // ---------- sound and vibration ----------

    private fun feedbackPage(column: LinearLayout) {
        column.addView(ui.topBar("Sound & vibration", "Tap an option to hear or feel it"))
        column.addView(ui.rows(
            ui.row(R.drawable.ic_volume_2, "Sound on key press", trailing = ui.toggle(store.sound) { store.sound = it }),
            ui.row(R.drawable.ic_vibrate, "Vibration", trailing = ui.toggle(store.haptic) { store.haptic = it }),
        ))
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
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E4E4EC"))
            thumbTintList = android.content.res.ColorStateList.valueOf(ui.accent)
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
        note(column, "\nNotion, Calendly, Slack, GitHub, Todoist, Linear and Discord need a token under Automate. Gmail, LinkedIn, WhatsApp, Telegram, Outlook, Calendar, Maps, YouTube and X open the app with a draft, so nothing is sent until you press send there.")
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

    private companion object {
        /** Token apps beyond Notion and Calendly: dashboard field, name, where to get the token. */
        val TOKEN_APPS = listOf(
            Triple("slackToken", "Slack", "Create an app at api.slack.com/apps with the user scopes channels:read, groups:read, channels:history, groups:history, chat:write and search:read, install it, and paste the User OAuth Token (xoxp-…)."),
            Triple("githubToken", "GitHub", "Create a fine-grained token at github.com/settings/personal-access-tokens with read and write access to Issues, and paste it here."),
            Triple("todoistToken", "Todoist", "In Todoist open Settings → Integrations → Developer and copy your API token."),
            Triple("linearToken", "Linear", "In Linear open Settings → Security & access → Personal API keys and create a key."),
            Triple("discordWebhook", "Discord", "In Discord open a channel's settings → Integrations → Webhooks → New Webhook, and paste its webhook URL."),
        )
    }
}
