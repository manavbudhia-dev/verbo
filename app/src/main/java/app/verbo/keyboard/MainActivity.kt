package app.verbo.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView

/** The main screen: Home, the skill library and a short Settings list. Detail screens live in [PageActivity]. */
class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var ui: Ui
    private lateinit var look: Look
    private lateinit var setupBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        ui = Ui(this)
        look = Look(this)
        build()
        // First run: the step-by-step guide (keyboard on, Google sign-in, a first try).
        if (savedInstanceState == null && !store.locked && !store.onboarded) open("welcome")
    }

    private var shownTheme = ""
    private var wentToSettings = false
    private var built = false

    private fun isEnabled() = (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.any { it.packageName == packageName }
    private fun isSelected() = (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "").startsWith("$packageName/")

    override fun onResume() {
        super.onResume()
        if (shownTheme.isNotEmpty() && shownTheme != store.theme) return recreate() // a skin was picked on another screen
        shownTheme = store.theme
        if (built) build() // skills, keys or settings may have been changed on another screen
        built = true
        refreshSetup()
        // Back from system settings with the keyboard enabled: go straight to the chooser, no extra tap.
        if (wentToSettings && isEnabled() && !isSelected()) {
            window.decorView.postDelayed({ if (!isFinishing) (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }, 400)
        }
        wentToSettings = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshSetup() // the keyboard picker is a dialog, so onResume doesn't fire after it
    }

    private fun open(page: String, extra: (Intent) -> Unit = {}) =
        startActivity(Intent(this, PageActivity::class.java).putExtra("page", page).also(extra))

    private fun build() {
        when (tab) {
            "library" -> buildLibrary(look.page(look.nav("skills",
                { tab = "home"; build() }, {}, { open("triggers") }, { open("usage") }, { tab = "settings"; build() })))
            "settings" -> buildSettings(look.page(look.nav("settings",
                { tab = "home"; build() }, { tab = "library"; build() }, { open("triggers") }, { open("usage") }, {})))
            else -> buildHome(ui.page(look.nav("home",
                {}, { tab = "library"; build() }, { open("triggers") }, { open("usage") }, { tab = "settings"; build() })))
        }
        refreshSetup()
    }

    private fun newSetupBox(column: LinearLayout) {
        setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(setupBox)
    }

    private fun bigTitle(column: LinearLayout, title: String, subtitle: String) {
        column.addView(ui.text(title, 32f, bold = true).apply { setPadding(ui.dp(2), ui.dp(18), 0, 0) })
        column.addView(ui.text(subtitle, 14.5f, ui.muted).apply { setPadding(ui.dp(2), ui.dp(2), 0, ui.dp(16)) })
    }

    // ---------- home ----------

    private fun buildHome(column: LinearLayout) {
        bigTitle(column, "Verbo", "Hold a lit key to run its skill. Hold the wide bar for the wheel.")
        newSetupBox(column)
        aiStatus(column)

        column.addView(ui.section(R.drawable.ic_keyboard, "Key map"))
        column.addView(ui.text("Tap a letter to choose the skill it runs when held.", 13.5f, ui.muted).apply { setPadding(ui.dp(2), 0, 0, ui.dp(10)) })
        column.addView(keyMap())

        val skills = store.skills
        column.addView(ui.section(R.drawable.ic_sparkles, "My skills"))
        skills.forEachIndexed { index, skill -> column.addView(skillCard(index, skill)) }
        if (skills.isEmpty()) column.addView(ui.text("No skills yet. Create one or add some from the library.", 14f, ui.muted).apply { setPadding(ui.dp(2), 0, 0, ui.dp(10)) })

        val buttons = LinearLayout(this).apply { setPadding(0, ui.dp(8), 0, 0) }
        buttons.addView(ui.button("Create", R.drawable.ic_plus, filled = true) { open("create") }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(6) })
        buttons.addView(ui.button("Try it", R.drawable.ic_keyboard) { open("try") }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(6) })
        column.addView(buttons)
    }

    /** "Ready · Haiku 5.5 · key ends 1234", or a button to add a key. Tapping opens the AI service page. */
    private fun aiStatus(column: LinearLayout) {
        val key = store.activeKey
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.ripple(ui.shape(if (key.isEmpty()) ui.accentSoft else ui.card, 16, if (key.isEmpty()) ui.accent else ui.line), 16)
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            setOnClickListener { open("ai") }
        }
        val ready = key.isNotEmpty() && store.assistantOn
        card.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = ui.shape(if (ready) Color.parseColor("#1E3A2B") else ui.accentSoft, 12)
            addView(ui.icon(if (ready) R.drawable.ic_check else R.drawable.ic_sparkles, 24, if (ready) Color.parseColor("#4ADE80") else ui.iconTint))
        }, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(14), 0, ui.dp(8), 0) }
        val hint = Store.keyHint(key)
        val title = when {
            key.isEmpty() -> "Add your ${store.providerName} key"
            !store.assistantOn -> "Assistant is off"
            else -> "Ready"
        }
        val detail = listOf(store.activeModelLabel, if (key.isEmpty()) "needed for AI actions" else if (hint.isEmpty()) "key saved" else "key ends $hint")
        texts.addView(ui.text(title, 17f, bold = true))
        texts.addView(ui.text(detail.joinToString(" · "), 13.5f, ui.muted))
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (key.isEmpty()) card.addView(ui.text("Add key", 14f, Color.WHITE, bold = true).apply {
            background = ui.shape(ui.accent, 14)
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
            setOnClickListener { open("ai") }
        }) else card.addView(ui.chevron())
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) })
    }

    /** A QWERTY grid: letters that run a skill are lit and show its emoji. */
    private fun keyMap(): View {
        val bound = store.skills.filter { it.key.isNotEmpty() }.associateBy { it.key }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.shape(ui.card, 16, ui.line)
            setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8))
        }
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { r, letters ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
            letters.forEach { c ->
                val letter = c.toString()
                val skill = bound[letter]
                val key = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = ui.ripple(ui.shape(if (skill != null) ui.accent else Color.parseColor("#232240"), 8), 8)
                    contentDescription = letter.uppercase() + (skill?.let { ", runs ${it.name}" } ?: ", no skill")
                    addView(ui.text(letter.uppercase(), 15f, if (skill != null) Color.WHITE else ui.muted, bold = true).apply { gravity = Gravity.CENTER })
                    addView(ui.text(skill?.emoji?.ifEmpty { "•" } ?: " ", 10f).apply { gravity = Gravity.CENTER })
                    setOnClickListener { pickSkillFor(letter) }
                }
                row.addView(key, LinearLayout.LayoutParams(0, ui.dp(50), 1f).apply { setMargins(ui.dp(2), ui.dp(3), ui.dp(2), ui.dp(3)) })
            }
            // Indent the shorter rows like a real keyboard.
            val inset = if (r == 0) 0 else r * 5
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(inset * 2), 0, ui.dp(inset * 2), 0) })
        }
        return grid
    }

    private fun pickSkillFor(letter: String) {
        val skills = store.skills
        if (skills.isEmpty()) return ui.toast("Create a skill first")
        val current = skills.firstOrNull { it.key == letter }
        val labels = skills.map { s -> (s.emoji + "  " + s.name).trim() + when {
            s.key == letter -> "   ✓"
            s.key.isNotEmpty() -> "   (now on ${s.key.uppercase()})"
            else -> ""
        } } + if (current != null) listOf("No skill") else emptyList()
        AlertDialog.Builder(this).setTitle("Hold ${letter.uppercase()} to run…").setItems(labels.toTypedArray()) { _, which ->
            val chosen = skills.getOrNull(which)
            // A letter runs one skill and a skill has one letter.
            store.skills = skills.map {
                when {
                    chosen != null && it.name == chosen.name -> it.copy(key = letter)
                    it.key == letter -> it.copy(key = "")
                    else -> it
                }
            }
            ui.toast(if (chosen == null) "${letter.uppercase()} is a plain letter again" else "Hold ${letter.uppercase()} to run ${chosen.name}")
            build()
        }.show()
    }

    private fun skillCard(index: Int, skill: Skill): View {
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.ripple(ui.shape(ui.card, 14, ui.line), 14)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(14), ui.dp(12))
            setOnClickListener { open("prompts") { it.putExtra("edit", index) } }
        }
        card.addView(ui.text(skill.emoji.ifEmpty { "✦" }, 20f).apply { gravity = Gravity.CENTER; background = ui.shape(ui.accentSoft, 12) },
            LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), 0, ui.dp(8), 0) }
        texts.addView(ui.text(skill.name, 16f, bold = true))
        texts.addView(ui.text(skill.instruction, 13f, ui.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(ui.text(skill.key.uppercase().ifEmpty { "·" }, 16f, if (skill.key.isEmpty()) ui.muted else Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = ui.shape(if (skill.key.isEmpty()) Color.parseColor("#1F1E36") else ui.accent, 9)
            contentDescription = if (skill.key.isEmpty()) "No hold key" else "Hold ${skill.key.uppercase()}"
        }, LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)))
        card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) }
        return card
    }

    // ---------- library ----------

    /** Ready-made skills, grouped on shelves, added with one tap. */
    private var skillQuery = ""
    private var skillChip = "All"
    private val skillChips = listOf("All", "Writing", "Translate", "Productivity")

    private fun buildLibrary(column: LinearLayout) {
        column.addView(look.header("Skills", "Your custom AI actions", look.circleButton(R.drawable.ic_more_horizontal) { skillsMenu() }))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun fill() {
            grid.removeAllViews()
            val q = skillQuery.trim().lowercase()
            val shown = store.skills.withIndex().filter { (_, s) ->
                (q.isEmpty() || s.name.lowercase().contains(q) || s.instruction.lowercase().contains(q)) && when (skillChip) {
                    "Writing" -> s.category == "write"
                    "Translate" -> s.category == "translate"
                    "Productivity" -> s.category == "edit" || s.category == "summarize"
                    else -> true
                }
            }
            if (shown.isEmpty()) grid.addView(look.text("Nothing here yet.", 14f, look.muted).apply { setPadding(look.dp(4), look.dp(6), 0, look.dp(10)) })
            shown.chunked(2).forEach { two ->
                grid.addView(look.pair(skillPhotoCard(two[0].index, two[0].value), if (two.size > 1) skillPhotoCard(two[1].index, two[1].value) else View(this)))
            }
        }
        column.addView(look.searchBar("Search skills", { skillQuery = it; fill() }, {
            AlertDialog.Builder(this).setItems(skillChips.toTypedArray()) { _, w -> skillChip = skillChips[w]; build() }.show()
        }))
        column.addView(look.chips(skillChips, skillChip) { skillChip = it; build() })
        fill()
        column.addView(grid)
        column.addView(look.darkPill("New Skill", R.drawable.ic_plus) { open("skill-new") }.apply {
            (layoutParams as LinearLayout.LayoutParams).setMargins(0, look.dp(6), 0, look.dp(6))
        })
    }

    private fun skillPhotoCard(index: Int, s: Skill): View {
        val photos = listOf(R.drawable.photo_sunset, R.drawable.photo_snow, R.drawable.photo_mist, R.drawable.photo_hills)
        val icon = when {
            s.name == "List" -> R.drawable.ic_list
            s.category == "translate" -> R.drawable.ic_globe
            s.category == "summarize" -> R.drawable.ic_file_text
            s.category == "write" -> R.drawable.ic_sparkles
            else -> R.drawable.ic_zap
        }
        val blurb = s.instruction.substringBefore(". ").take(90)
        return look.photoCard(photos[index % photos.size], icon, s.name, blurb,
            { open("skill-edit", { it.putExtra("index", index) }) }, { skillMenu(index) })
    }

    private fun skillsMenu() = AlertDialog.Builder(this)
        .setItems(arrayOf("Skill library", "Restore default skills")) { _, w ->
            if (w == 0) open("shelves")
            else AlertDialog.Builder(this).setMessage("Replace your skills with the original set?")
                .setPositiveButton("Restore") { _, _ -> store.resetSkills(); build() }
                .setNegativeButton("Cancel", null).show()
        }.show()

    private fun skillMenu(index: Int) {
        val skills = store.skills.toMutableList()
        val s = skills.getOrNull(index) ?: return
        AlertDialog.Builder(this).setTitle(s.name)
            .setItems(arrayOf("Edit", "Duplicate", "Remove")) { _, w ->
                when (w) {
                    0 -> open("skill-edit", { it.putExtra("index", index) })
                    1 -> { store.skills = skills + s.copy(name = s.name + " copy", key = ""); build() }
                    else -> AlertDialog.Builder(this).setMessage("Remove ${s.name}?")
                        .setPositiveButton("Remove") { _, _ -> skills.removeAt(index); store.skills = skills; build() }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    // ---------- settings ----------

    private fun buildSettings(column: LinearLayout) {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        column.addView(look.header("Settings", "Customize your keyboard experience",
            look.circleButton(R.drawable.ic_info) { settingsMenu() }))
        newSetupBox(column)
        column.addView(look.appCard(version ?: "0.20.0"))

        column.addView(look.sectionHead(R.drawable.ic_sparkles, look.accent, "AI & Personalization", "Make it smarter, your way"))
        column.addView(look.triple(
            look.tileCard(look.purpleBg, look.purpleTint, R.drawable.ic_sparkles, "AI Service",
                "${store.providerName} · ${store.activeModelLabel}") { open("ai") },
            look.tileCard(look.pinkBg, look.pinkTint, R.drawable.ic_brain, "Memory",
                if (store.memory.isEmpty()) "Teach Verbo about you" else "${store.memory.size} things remembered") { open("memory") },
            look.tileCard(look.amberBg, look.amberTint, R.drawable.ic_zap, "Auto Actions",
                "${store.triggers.size} actions configured") { open("triggers") },
        ))

        column.addView(look.sectionHead(R.drawable.ic_smile, look.accent, "Keyboard Experience", "Look, feel and type the way you like"))
        column.addView(look.rowsCard(
            look.rowTile(look.blueBg, look.blueTint, R.drawable.ic_palette, "Look & Feel", "Theme, key style, height") { open("look") },
            look.rowTile(look.purpleBg, look.purpleTint, 0, "Typing", "Auto-correct & suggestions", iconText = "Aa") { open("typing") },
            look.rowTile(look.orangeBg, look.orangeTint, R.drawable.ic_volume_2, "Sound & Vibration", "Feedback & haptics") { open("feedback") },
            look.rowTile(look.greenBg, look.greenTint, R.drawable.ic_keyboard, "Try the keyboard", "Type a test line with Verbo") { open("try") },
        ))

        column.addView(look.sectionHead(R.drawable.ic_layers, look.accent, "More Options", "Languages, keys, shortcuts and connections"))
        val connected = listOf(store.notionToken, store.calendlyToken).count { it.isNotEmpty() } + store.customApis.size + if (store.googleConnected) 1 else 0
        val langs = store.languages
        val langLine = if (langs.size <= 2) langs.joinToString(", ").ifEmpty { "None chosen" } else langs.take(2).joinToString(", ") + " + more"
        column.addView(look.pair(
            look.gridTile(look.blueBg, look.blueTint, R.drawable.ic_keyboard, "Keys", "Shortcuts & actions") { open("keys") },
            look.gridTile(look.blueBg, look.blueTint, R.drawable.ic_globe, "Languages", langLine) { open("languages") },
        ))
        column.addView(look.pair(
            look.gridTile(look.greenBg, look.greenTint, R.drawable.ic_plug, "Connections",
                if (connected == 0) "No apps connected yet" else "$connected connected apps") { open("automate") },
            look.gridTile(look.indigoBg, look.indigoTint, R.drawable.ic_shield, "Privacy", "What leaves your phone") { open("privacy") },
        ))
    }

    /** Everything the redesigned Settings page keeps out of sight: the info button opens this list. */
    private fun settingsMenu() {
        val entries = listOf(
            "Wheel shortcuts" to { open("wheel") },
            "Usage & on-device AI" to { open("usage") },
            "Web dashboard" to { open("dashboard") },
            "Battery" to { open("battery") },
            "Export / Import" to { exportImport() },
            "Setup guide" to { open("welcome") },
        )
        AlertDialog.Builder(this).setTitle("More settings").setItems(entries.map { it.first }.toTypedArray()) { _, i -> entries[i].second() }.show()
    }

    private fun exportImport() {
        AlertDialog.Builder(this).setTitle("Export / Import")
            .setMessage("Settings and skills are saved to a file. Keys and tokens are never included.")
            .setPositiveButton("Export") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "verbo-settings.json"), EXPORT)
            }
            .setNeutralButton("Import") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun refreshSetup() {
        if (!::setupBox.isInitialized) return // the Skills tab has no setup banner
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val selected = (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "").startsWith("$packageName/")
        setupBox.removeAllViews()
        if (enabled && selected) return
        val light = tab != "home"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = if (light) look.shape(look.accentSoft, 20, look.accent) else ui.shape(ui.accentSoft, 14, ui.accent)
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) }
        }
        val title = if (light) look.text("Make Verbo your keyboard", 17f, look.ink, bold = true) else ui.text("Make Verbo your keyboard", 17f, bold = true)
        val body = if (light) look.text("Android only lets you choose the default keyboard yourself. You do this once; Verbo then stays your keyboard, including after a restart.", 13.5f, look.muted)
        else ui.text("Android only lets you choose the default keyboard yourself. You do this once; Verbo then stays your keyboard, including after a restart.", 13.5f, ui.muted)
        box.addView(title)
        box.addView(body.apply { setPadding(0, ui.dp(4), 0, 0) })
        val stepColor = if (light) look.ink else Color.parseColor("#D8D6EA")
        box.addView((if (light) look.text((if (enabled) "✓  " else "1.  ") + "Enable Verbo Keyboard in system settings", 14.5f, stepColor)
        else ui.text((if (enabled) "✓  " else "1.  ") + "Enable Verbo Keyboard in system settings", 14.5f, stepColor)).apply { setPadding(0, ui.dp(8), 0, 0) })
        box.addView((if (light) look.text((if (selected) "✓  " else "2.  ") + "Switch to Verbo as your keyboard", 14.5f, stepColor)
        else ui.text((if (selected) "✓  " else "2.  ") + "Switch to Verbo as your keyboard", 14.5f, stepColor)).apply { setPadding(0, ui.dp(4), 0, ui.dp(12)) })
        val action = { if (!enabled) { wentToSettings = true; startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } else imm.showInputMethodPicker() }
        box.addView(
            if (light) look.purplePill(if (!enabled) "Open keyboard settings" else "Choose keyboard", null, action)
            else ui.button(if (!enabled) "Open keyboard settings" else "Choose keyboard", filled = true, onTap = action),
        )
        setupBox.addView(box)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            if (requestCode == EXPORT) {
                contentResolver.openOutputStream(uri)?.use { it.write(store.exportJson().toByteArray()) }
                ui.toast("Settings exported (keys and tokens are not included)")
            } else if (requestCode == IMPORT) {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (store.importJson(text)) { ui.toast("Settings imported"); recreate() } else ui.toast("That isn't a Verbo settings file")
            }
        } catch (e: Exception) {
            ui.toast("Couldn't read or write that file")
        }
    }

    companion object {
        /** Which tab is showing; kept across screen rebuilds. */
        internal var tab = "home"
        private const val EXPORT = 1
        private const val IMPORT = 2
    }
}
