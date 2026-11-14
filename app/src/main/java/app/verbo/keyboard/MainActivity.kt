package app.verbo.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView

/** The main settings screen. Detail screens live in [PageActivity]. */
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

    private fun isEnabled() = (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.any { it.packageName == packageName }
    private fun isSelected() = (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "").startsWith("$packageName/")

    override fun onResume() {
        super.onResume()
        if (shownTheme.isNotEmpty() && shownTheme != store.theme) return recreate() // a skin was picked on another screen
        shownTheme = store.theme
        if (built && tab != "settings") build() // skills, the wheel or languages may have been edited on another screen
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

    private fun open(page: String) = startActivity(Intent(this, PageActivity::class.java).putExtra("page", page))

    private fun build() {
        val column = ui.page(navBar())
        when (tab) {
            "keys" -> buildKeys(column)
            "library" -> buildLibrary(column)
            else -> buildSettings(column)
        }
        refreshSetup()
    }

    /** The floating bar at the bottom: skill keys, skill library, settings, and a shortcut to make a new skill. */
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
        item("keys", R.drawable.ic_keyboard, "Skill keys") { tab = "keys"; build() }
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

    /** Home: a picture of the keyboard with the shortcut keys lit, and what each one does. */
    private fun buildKeys(column: LinearLayout) {
        bigTitle(column, "Verbo", "Hold a lit key to run its skill. Hold the wide bar for the wheel.")
        newSetupBox(column)
        val skills = store.skills
        val theme = KeyTheme.of(this, store.theme, store.customAccent)
        val preview = KeyboardView(this, theme, 0.78f, store.keyShape, false).apply {
            bound = skills.mapNotNull { it.key.firstOrNull() }.toSet()
            barLabel = if (store.wheelOn) "hold for wheel" else "hold to act"
            setOnTouchListener { _, _ -> true } // a picture, not a keyboard
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        column.addView(LinearLayout(this).apply {
            background = ui.shape(theme.bg, 20, ui.line)
            setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(8))
            addView(preview, LinearLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })

        fun skillRow(skill: Skill): View {
            val row = ui.row(R.drawable.ic_zap, (skill.emoji + "  " + skill.name).trim(), skill.instruction, ui.chevron()) { open("prompts") }
            ((row.getChildAt(1) as LinearLayout).getChildAt(1) as TextView).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            row.removeViewAt(0)
            row.addView(ui.text(skill.key.uppercase().ifEmpty { "·" }, 19f, if (skill.key.isEmpty()) ui.muted else Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = ui.shape(if (skill.key.isEmpty()) Color.parseColor("#1F1E36") else ui.accent, 10)
            }, 0, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
            return row
        }
        val bound = skills.filter { it.key.isNotEmpty() }.sortedBy { it.key }
        val rest = skills.filter { it.key.isEmpty() }
        column.addView(ui.section(R.drawable.ic_keyboard, "Skill keys"))
        if (bound.isEmpty()) column.addView(ui.text("No skill has a key yet. Open a skill and set its hold key.", 14f, ui.muted).apply { setPadding(ui.dp(2), 0, 0, ui.dp(10)) })
        bound.forEach { column.addView(ui.rows(skillRow(it))) }
        if (rest.isNotEmpty()) {
            column.addView(ui.section(R.drawable.ic_sparkles, "Other skills"))
            column.addView(ui.rows(*rest.map { skillRow(it) }.toTypedArray()))
        }
        column.addView(ui.section(R.drawable.ic_circle_dashed, "Tools"))
        column.addView(ui.rows(
            ui.row(R.drawable.ic_circle_dashed, "Shortcut wheel", "Add tools and skills to the wheel", ui.chevron()) { open("wheel") },
            ui.row(R.drawable.ic_languages, "Translate languages", store.languages.joinToString(", ").ifEmpty { "None chosen" }, ui.chevron()) { open("translate") },
            ui.row(R.drawable.ic_zap, "Connected tools", "Notion, Calendly, weather, places, your APIs", ui.chevron()) { open("automate") },
        ))
    }

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
        if (store.deviceToken.isNotEmpty()) column.addView(ui.text("This keyboard is paired with a dashboard, so skills added here are replaced at the next sync. Add them on the dashboard to keep them.", 13.5f, ui.muted).apply { setPadding(ui.dp(2), ui.dp(8), 0, 0) })
    }

    private fun buildSettings(column: LinearLayout) {

        // ---- header ----
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(8), 0, ui.dp(14)) }
        header.addView(ui.icon(R.drawable.ic_arrow_left, 26, ui.ink).apply {
            background = ui.ripple(null, 20)
            contentDescription = "Back"
            setOnClickListener { tab = "keys"; build() }
        })
        header.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#9B7BFF"), Color.parseColor("#5B3FE0"))).apply {
                cornerRadius = ui.dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)).apply { leftMargin = ui.dp(14) }
            addView(ui.icon(R.drawable.ic_keyboard, 30, Color.WHITE))
        })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(14), 0, 0, 0) }
        val titleLine = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleLine.addView(ui.text("Verbo Keyboard", 21f, bold = true))
        titleLine.addView(ui.text("Beta", 12f, Color.parseColor("#B9A6FF")).apply {
            background = ui.shape(ui.accentSoft, 9, Color.parseColor("#5A45B8"))
            setPadding(ui.dp(9), ui.dp(2), ui.dp(9), ui.dp(2))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.dp(10) }
        })
        titles.addView(titleLine)
        titles.addView(ui.text("An AI keyboard that does more for you", 14f, ui.muted))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(ui.icon(R.drawable.ic_ellipsis_vertical, 24, ui.ink).apply {
            background = ui.ripple(null, 20)
            contentDescription = "More"
            setOnClickListener { v ->
                PopupMenu(this@MainActivity, v).apply {
                    menu.add("Try the keyboard").setOnMenuItemClickListener { open("try"); true }
                    menu.add("Web dashboard").setOnMenuItemClickListener { open("dashboard"); true }
                    menu.add("Connections").setOnMenuItemClickListener { open("automate"); true }
                    menu.add("Keyboard settings").setOnMenuItemClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)); true }
                }.show()
            }
        })
        column.addView(header)

        // ---- setup steps, shown only until the keyboard is on ----
        newSetupBox(column)

        // ---- assistant ----
        column.addView(ui.rows(ui.row(R.drawable.ic_sparkles, "Keyboard Assistant", "Use AI to write, edit, translate and automate",
            ui.toggle(store.assistantOn) { store.assistantOn = it }, tile = true)))

        // ---- model ----
        column.addView(ui.section(R.drawable.ic_box, "AI Model"))
        val modelName = ui.text(shortModel(), 15f, bold = true)
        val modelPill = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.ripple(ui.shape(Color.parseColor("#1F1E36"), 10, ui.line), 10)
            setPadding(ui.dp(14), ui.dp(9), ui.dp(10), ui.dp(9))
            addView(modelName)
            addView(ui.icon(R.drawable.ic_chevron_down, 18, ui.ink).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = ui.dp(8) })
            setOnClickListener { v ->
                PopupMenu(this@MainActivity, v).apply {
                    Ai.MODELS.forEach { (id, label) ->
                        menu.add("Claude " + label).setOnMenuItemClickListener { store.provider = "anthropic"; store.model = id; recreate(); true }
                    }
                    Ai.OPENAI_MODELS.forEach { (id, label) ->
                        menu.add("OpenAI $label").setOnMenuItemClickListener { store.provider = "openai"; store.openaiModel = id; recreate(); true }
                    }
                    menu.add("Other OpenAI model…").setOnMenuItemClickListener { customOpenAiModel(); true }
                    menu.add("AI Router · Claude Haiku 5.5").setOnMenuItemClickListener {
                        store.provider = "custom"; store.customBase = ChatCompat.DEFAULT_BASE; store.customModel = ChatCompat.DEFAULT_MODEL; recreate(); true
                    }
                    menu.add("Other gateway or model…").setOnMenuItemClickListener { customGateway(); true }
                }.show()
            }
        }
        val keyState = if (store.activeKey.isEmpty()) "Tap to add your ${store.providerName} API key" else "Choose the brain for your keyboard"
        column.addView(ui.rows(ui.row(R.drawable.ic_sparkles, "AI Model", keyState, modelPill, tile = true) { editApiKey() }))

        // ---- agent mode ----
        column.addView(ui.section(R.drawable.ic_layers, "Agent Mode"))
        val modes = LinearLayout(this).apply {
            background = ui.shape(ui.card, 14, ui.line)
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) }
        }
        val modeCells = ArrayList<Pair<String, LinearLayout>>()
        fun paintModes() = modeCells.forEach { (id, cell) ->
            val on = store.agentMode == id
            cell.background = ui.ripple(if (on) ui.shape(ui.accentSoft, 10, ui.accent, 2) else null, 10)
            (cell.getChildAt(1) as TextView).setTextColor(if (on) Color.parseColor("#C9BBFF") else ui.ink)
        }
        listOf(
            Triple("auto", R.drawable.ic_zap, "Auto (Recommended)" to "Picks the best action"),
            Triple("ask", R.drawable.ic_message_square, "Ask First" to "Confirm before acting"),
            Triple("manual", R.drawable.ic_settings, "Manual" to "Only when you choose"),
        ).forEach { (id, icon, labels) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12))
                addView(ui.icon(icon, 26, ui.ink))
                addView(ui.text(labels.first, 12.5f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(8), 0, ui.dp(2)) })
                addView(ui.text(labels.second, 12f, ui.muted).apply { gravity = Gravity.CENTER })
                setOnClickListener { store.agentMode = id; paintModes() }
            }
            modeCells.add(id to cell)
            modes.addView(cell, LinearLayout.LayoutParams(0, -1, if (id == "auto") 1.3f else 1f))
        }
        paintModes()
        column.addView(modes)

        // ---- appearance ----
        column.addView(ui.section(R.drawable.ic_palette, "Keyboard Appearance"))
        val themes = LinearLayout(this).apply {
            background = ui.shape(ui.card, 14, ui.line)
            setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) }
        }
        val themeTiles = ArrayList<Pair<String, FrameLayout>>()
        fun paintThemes() = themeTiles.forEach { (id, tile) ->
            val on = store.theme == id
            tile.foreground = if (on) ui.shape(Color.TRANSPARENT, 12, ui.accent, 2) else ui.shape(Color.TRANSPARENT, 12, ui.line)
            tile.getChildAt(tile.childCount - 1).visibility = if (on) View.VISIBLE else View.INVISIBLE
        }
        fun swatch(id: String): View = when (id) {
            "system" -> View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#1B1A2E"), Color.parseColor("#1B1A2E"), Color.parseColor("#F2F2F7"), Color.parseColor("#F2F2F7"))).apply { cornerRadius = ui.dp(12).toFloat() }
            }
            "dark" -> FrameLayout(this).apply {
                background = ui.shape(Color.parseColor("#1F1E33"), 12)
                addView(ui.icon(R.drawable.ic_moon, 24, Color.parseColor("#C9C7DD")), FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
            }
            "light" -> FrameLayout(this).apply {
                background = ui.shape(Color.parseColor("#F2F2F7"), 12)
                addView(ui.icon(R.drawable.ic_sun, 24, Color.parseColor("#2B2F38")), FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
            }
            "amoled" -> View(this).apply { background = ui.shape(Color.BLACK, 12) }
            else -> FrameLayout(this).apply {
                background = ui.shape(Color.parseColor("#1F1E33"), 12)
                addView(View(this@MainActivity).apply {
                    tag = "accent"
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(store.customAccent) }
                }, FrameLayout.LayoutParams(ui.dp(32), ui.dp(32), Gravity.CENTER))
            }
        }
        listOf("system" to "System", "dark" to "Dark", "light" to "Light", "amoled" to "Amoled", "custom" to "Custom").forEach { (id, label) ->
            val tile = FrameLayout(this)
            tile.addView(swatch(id), FrameLayout.LayoutParams(-1, -1))
            tile.addView(FrameLayout(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.accent); setStroke(ui.dp(2), Color.WHITE) }
                addView(ui.icon(R.drawable.ic_check, 12, Color.WHITE), FrameLayout.LayoutParams(ui.dp(12), ui.dp(12), Gravity.CENTER))
            }, FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.TOP or Gravity.END).apply { topMargin = ui.dp(4); rightMargin = ui.dp(4) })
            themeTiles.add(id to tile)
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(4), 0, ui.dp(4), 0)
                addView(tile, LinearLayout.LayoutParams(-1, ui.dp(50)))
                addView(ui.text(label, 13.5f, Color.parseColor("#D8D6EA")).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(8), 0, 0) })
                setOnClickListener {
                    store.theme = id
                    paintThemes()
                    if (id == "custom") pickAccent(tile.findViewWithTag("accent"))
                }
            }
            themes.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        paintThemes()
        column.addView(themes)
        column.addView(ui.rows(ui.row(R.drawable.ic_palette, "Skins & key style", "More colours, key shape, number row", ui.chevron()) { open("skins") }))

        // ---- height ----
        column.addView(ui.section(R.drawable.ic_keyboard, "Keyboard Height").apply { setPadding(ui.dp(2), ui.dp(22), 0, 0) })
        column.addView(ui.text("Adjust the keyboard size for comfortable typing", 14f, ui.muted).apply { setPadding(ui.dp(38), ui.dp(2), 0, ui.dp(8)) })
        val heightRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val heightLabel = ui.text("${store.heightPercent}%", 15f, bold = true).apply {
            gravity = Gravity.CENTER
            background = ui.shape(ui.card, 16, ui.line)
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
            minWidth = ui.dp(66)
        }
        heightRow.addView(SeekBar(this).apply {
            max = (Store.MAX_HEIGHT - Store.MIN_HEIGHT) / 5
            progress = (store.heightPercent - Store.MIN_HEIGHT) / 5
            progressTintList = android.content.res.ColorStateList.valueOf(ui.accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#5A5880"))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            contentDescription = "Keyboard height"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    store.heightPercent = Store.MIN_HEIGHT + value * 5
                    heightLabel.text = "${store.heightPercent}%"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        heightRow.addView(heightLabel)
        column.addView(heightRow)

        // ---- feedback ----
        column.addView(ui.section(R.drawable.ic_volume_2, "Key Feedback"))
        column.addView(ui.rows(
            ui.row(R.drawable.ic_volume_2, "Sound on key press", trailing = ui.toggle(store.sound) { store.sound = it }),
            ui.row(R.drawable.ic_vibrate, "Haptic (vibration)", trailing = ui.toggle(store.haptic) { store.haptic = it }),
            ui.row(R.drawable.ic_sliders_horizontal, "Sound effects & strength", "Typewriter and other sounds, volume, vibration strength", ui.chevron()) { open("feedback") },
        ))

        // ---- language ----
        column.addView(ui.section(R.drawable.ic_globe, "Language & Input"))
        val language = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.text("English (US)", 14f, Color.parseColor("#D8D6EA")).apply { setPadding(0, 0, ui.dp(8), 0) })
            addView(ui.chevron())
        }
        column.addView(ui.rows(
            ui.row(R.drawable.ic_languages, "Languages", trailing = language) { open("languages") },
            ui.row(R.drawable.ic_spell_check, "Auto-correct", "Fix typos automatically", ui.toggle(store.autoCorrect) { store.autoCorrect = it }),
            ui.row(R.drawable.ic_message_square_more, "Text suggestions", "Show smart next words", ui.toggle(store.suggestions) { store.suggestions = it }),
        ))

        // ---- actions ----
        column.addView(ui.section(R.drawable.ic_sparkles, "Agentic Actions"))
        column.addView(ui.rows(
            ui.row(R.drawable.ic_pencil_line, "Write", "Draft, reply, or expand text", ui.chevron()) { open("category:write") },
            ui.row(R.drawable.ic_sliders_horizontal, "Edit", "Improve, shorten, or change tone", ui.chevron()) { open("category:edit") },
            ui.row(R.drawable.ic_languages, "Translate", "Translate into other languages", ui.chevron()) { open("category:translate") },
            ui.row(R.drawable.ic_list, "Summarize", "Turn long text into key points", ui.chevron()) { open("category:summarize") },
            ui.row(R.drawable.ic_zap, "Automate", "Connect apps and APIs for actions", ui.chevron()) { open("automate") },
            ui.row(R.drawable.ic_layers, "Templates", "Ready-made daily actions for your apps", ui.chevron()) { open("templates") },
        ))

        // ---- advanced ----
        column.addView(ui.section(R.drawable.ic_settings, "Advanced"))
        column.addView(ui.rows(
            ui.row(R.drawable.ic_square_terminal, "Custom Prompts", "Add your own AI instructions", ui.chevron()) { open("prompts") },
            ui.row(R.drawable.ic_keyboard, "Shortcuts & Triggers", "Use / command to run actions", ui.chevron()) { open("shortcuts") },
            ui.row(R.drawable.ic_shield, "Privacy", "Data, permissions and usage", ui.chevron()) { open("privacy") },
            ui.row(R.drawable.ic_zap, "Battery", "Battery Saver behaviour and power use", ui.chevron()) { open("battery") },
        ))

        // ---- export / import ----
        val io = LinearLayout(this).apply { setPadding(0, ui.dp(12), 0, 0) }
        io.addView(ui.button("Export Settings", R.drawable.ic_upload) {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "verbo-settings.json"), EXPORT)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(6) })
        io.addView(ui.button("Import Settings", R.drawable.ic_download) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(6) })
        column.addView(io)

        refreshSetup()
    }

    private fun shortModel() =
        if (store.isCustom) store.customModel.substringAfterLast('/').take(18)
        else if (store.isOpenAi) Ai.OPENAI_MODELS.firstOrNull { it.first == store.openaiModel }?.second ?: store.openaiModel
        else (Ai.MODELS.firstOrNull { it.first == store.model } ?: Ai.MODELS[0]).second.substringBefore(" —")

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
                else { store.provider = "custom"; store.customBase = address; store.customModel = id; recreate() }
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
                if (id.matches(Regex("[A-Za-z0-9._:-]{1,80}"))) { store.provider = "openai"; store.openaiModel = id; recreate() }
                else ui.toast("That doesn't look like a model ID")
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

    private fun editApiKey() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0) }
        form.addView(ui.text("Actions run on your own key. Keys stay on this phone and are only sent to the service they belong to. The model you pick decides which key is used.", 13.5f, ui.muted))
        form.addView(ui.text("Claude (console.anthropic.com)", 13.5f, bold = true).apply { setPadding(0, ui.dp(14), 0, 0) })
        val claude = ui.field("sk-ant-…", store.apiKey, secret = true)
        form.addView(claude)
        form.addView(ui.text("OpenAI (platform.openai.com)", 13.5f, bold = true).apply { setPadding(0, ui.dp(10), 0, 0) })
        val openai = ui.field("sk-…", store.openaiKey, secret = true)
        form.addView(openai)
        form.addView(ui.text("AI Router or other gateway", 13.5f, bold = true).apply { setPadding(0, ui.dp(10), 0, 0) })
        val gateway = ui.field("sk-air-v1-…", store.customKey, secret = true)
        form.addView(gateway)
        AlertDialog.Builder(this).setTitle("API keys").setView(form)
            .setPositiveButton("Save") { _, _ ->
                store.apiKey = claude.text.toString()
                store.openaiKey = openai.text.toString()
                store.customKey = gateway.text.toString()
                ui.toast("Keys saved")
                recreate()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickAccent(dot: View?) {
        val colours = listOf("#7C5CFF", "#5AA9F5", "#22C55E", "#F59E0B", "#EF4444", "#EC4899", "#14B8A6", "#F97316")
        val grid = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(8)) }
        val dialog = AlertDialog.Builder(this).setTitle("Accent colour").setView(grid).setNegativeButton("Close", null).create()
        colours.forEach { hex ->
            grid.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(hex)) }
                contentDescription = hex
                setOnClickListener {
                    store.customAccent = Color.parseColor(hex)
                    (dot?.background as? GradientDrawable)?.setColor(store.customAccent)
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)).apply { setMargins(ui.dp(5), 0, ui.dp(5), 0) })
        }
        dialog.show()
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

    private var built = false

    companion object {
        /** Which tab is showing; kept across screen rebuilds. */
        internal var tab = "keys"
        private var shelf = "Featured"
        private const val EXPORT = 1
        private const val IMPORT = 2
    }
}
