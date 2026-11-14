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
    private lateinit var setupBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        ui = Ui(this)
        build()
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
        val column = ui.page(navBar())
        when (tab) {
            "library" -> buildLibrary(column)
            "settings" -> buildSettings(column)
            else -> buildHome(column)
        }
        refreshSetup()
    }

    /** The floating bar at the bottom: home, skill library, settings, and a shortcut to make a new skill. */
    private fun navBar(): View {
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.shape(Color.parseColor("#201F38"), 30, ui.line)
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
            elevation = ui.dp(8).toFloat()
        }
        fun item(id: String?, icon: Int, label: String, onTap: () -> Unit) {
            val on = id != null && id == tab
            bar.addView(ui.icon(icon, 26, if (on) Color.WHITE else ui.muted).apply {
                setPadding(ui.dp(13), ui.dp(11), ui.dp(13), ui.dp(11))
                background = ui.ripple(if (on) ui.shape(ui.accent, 22) else null, 22)
                contentDescription = label
                setOnClickListener { onTap() }
            }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(48)).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) })
        }
        item("home", R.drawable.ic_keyboard, "Home") { tab = "home"; build() }
        item("library", R.drawable.ic_wand_sparkles, "Skill library") { tab = "library"; build() }
        item("settings", R.drawable.ic_settings, "Settings") { tab = "settings"; build() }
        item(null, R.drawable.ic_plus, "Create a skill") { open("create") }
        return bar
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
    private fun buildLibrary(column: LinearLayout) {
        bigTitle(column, "Skills", "Ready-made skills. Add one and it appears above your keyboard.")
        newSetupBox(column)
        val chips = LinearLayout(this)
        Library.SHELVES.forEach { name ->
            val on = name == shelf
            chips.addView(ui.text(name, 15f, if (on) Color.WHITE else ui.muted, bold = true).apply {
                gravity = Gravity.CENTER
                background = ui.ripple(if (on) ui.shape(ui.accent, 20) else ui.shape(ui.card, 20, ui.line), 20)
                setPadding(ui.dp(18), ui.dp(9), ui.dp(18), ui.dp(9))
                setOnClickListener { shelf = name; build() }
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
                    build()
                }
            }
            val row = ui.row(R.drawable.ic_sparkles, item.name, item.about, button)
            row.removeViewAt(0)
            row.addView(ui.text(item.emoji, 22f).apply { gravity = Gravity.CENTER; background = ui.shape(ui.accentSoft, 12) }, 0, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
            column.addView(ui.rows(row))
        }
    }

    // ---------- settings ----------

    private fun buildSettings(column: LinearLayout) {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        bigTitle(column, "Settings", "Verbo Keyboard $version")
        newSetupBox(column)

        val on = { b: Boolean -> if (b) "on" else "off" }
        val theme = store.theme.replaceFirstChar { it.uppercase() }
        val connected = listOf(store.notionToken, store.calendlyToken).count { it.isNotEmpty() } + store.customApis.size + if (store.googleConnected) 1 else 0
        val hint = Store.keyHint(store.activeKey)
        column.addView(ui.rows(
            ui.row(R.drawable.ic_sparkles, "AI service", "${store.providerName} · ${store.activeModelLabel}" + if (store.activeKey.isEmpty()) " · no key" else if (hint.isNotEmpty()) " · key ends $hint" else "", ui.chevron()) { open("ai") },
        ))
        column.addView(ui.rows(
            ui.row(R.drawable.ic_palette, "Look", "$theme · ${store.keyShape} keys · ${store.heightPercent}% height", ui.chevron()) { open("look") },
            ui.row(R.drawable.ic_volume_2, "Sound & vibration", "Sound ${on(store.sound)} · vibration ${if (store.haptic) store.hapticStrength else "off"}", ui.chevron()) { open("feedback") },
            ui.row(R.drawable.ic_spell_check, "Typing", "Auto-correct ${on(store.autoCorrect)} · suggestions ${on(store.suggestions)}", ui.chevron()) { open("typing") },
            ui.row(R.drawable.ic_circle_dashed, "Wheel", if (store.wheelOn) "${store.wheel.size} shortcuts" else "Off", ui.chevron()) { open("wheel") },
            ui.row(R.drawable.ic_languages, "Languages", store.languages.joinToString(", ").ifEmpty { "None chosen" }, ui.chevron()) { open("languages") },
        ))
        column.addView(ui.rows(
            ui.row(R.drawable.ic_plug, "Connections", if (connected == 0) "Web search, weather, places" else "$connected connected", ui.chevron()) { open("automate") },
            ui.row(R.drawable.ic_cloud, "Web dashboard", if (store.deviceToken.isEmpty()) "Not paired" else "Paired with " + store.serverUrl.removePrefix("https://"), ui.chevron()) { open("dashboard") },
        ))
        column.addView(ui.rows(
            ui.row(R.drawable.ic_shield, "Privacy", "What leaves your phone", ui.chevron()) { open("privacy") },
            ui.row(R.drawable.ic_zap, "Battery", "Battery Saver ${if (store.batterySaver) "followed" else "ignored"}", ui.chevron()) { open("battery") },
            ui.row(R.drawable.ic_upload, "Export / Import", "Save settings and skills to a file", ui.chevron()) { exportImport() },
        ))
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
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val selected = (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "").startsWith("$packageName/")
        setupBox.removeAllViews()
        if (enabled && selected) return
        val box = ui.cardBox().apply {
            background = ui.shape(ui.accentSoft, 14, ui.accent)
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
        }
        box.addView(ui.text("Make Verbo your keyboard", 17f, bold = true))
        box.addView(ui.text("Android only lets you choose the default keyboard yourself. You do this once; Verbo then stays your keyboard, including after a restart.", 13.5f, ui.muted).apply { setPadding(0, ui.dp(4), 0, 0) })
        box.addView(ui.text((if (enabled) "✓  " else "1.  ") + "Enable Verbo Keyboard in system settings", 14.5f, Color.parseColor("#D8D6EA")).apply { setPadding(0, ui.dp(8), 0, 0) })
        box.addView(ui.text((if (selected) "✓  " else "2.  ") + "Switch to Verbo as your keyboard", 14.5f, Color.parseColor("#D8D6EA")).apply { setPadding(0, ui.dp(4), 0, ui.dp(12)) })
        box.addView(
            if (!enabled) ui.button("Open keyboard settings", filled = true) { wentToSettings = true; startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            else ui.button("Choose keyboard", filled = true) { imm.showInputMethodPicker() },
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
        private var shelf = "Featured"
        private const val EXPORT = 1
        private const val IMPORT = 2
    }
}
