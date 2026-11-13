package app.verbo.keyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Setup and settings: turn the keyboard on, add an API key, and build skill keys. */
class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var skillsBox: LinearLayout
    private lateinit var apisBox: LinearLayout
    private lateinit var dashBox: LinearLayout
    private lateinit var micStatus: TextView
    private lateinit var stepOne: TextView
    private lateinit var stepTwo: TextView
    private var night = false

    private val ink get() = Color.parseColor(if (night) "#F1EEE8" else "#1D1F24")
    private val muted get() = Color.parseColor(if (night) "#9A9CA3" else "#6B6A66")
    private val card get() = Color.parseColor(if (night) "#23262E" else "#FFFFFF")
    private val accent get() = Color.parseColor(if (night) "#5AA9F5" else "#3B82F6")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        }
        setContentView(scroll)

        column.addView(label("Verbo", 34f, ink, bold = true))
        column.addView(label("A keyboard that does what you type.", 16f, muted).pad(bottom = 20))

        // --- setup ---
        column.addView(section("Turn it on"))
        val setup = cardBox()
        stepOne = label("", 15f, ink)
        setup.addView(stepOne)
        setup.addView(button("Open keyboard settings") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        stepTwo = label("", 15f, ink).pad(top = 14)
        setup.addView(stepTwo)
        setup.addView(button("Choose keyboard") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        column.addView(setup)

        // --- dashboard ---
        column.addView(section("Dashboard"))
        dashBox = cardBox()
        column.addView(dashBox)
        renderDashboard()

        // --- assistant ---
        column.addView(section("Assistant"))
        val ai = cardBox()
        ai.addView(label("Claude API key", 15f, ink, bold = true))
        ai.addView(label("Actions run on your own key from console.anthropic.com. It stays on this phone and is only sent to Anthropic.", 13f, muted).pad(bottom = 6))
        val keyField = EditText(this).apply {
            hint = "sk-ant-…"
            setText(store.apiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setTextColor(ink)
            setHintTextColor(muted)
        }
        ai.addView(keyField)
        ai.addView(button("Save key") {
            store.apiKey = keyField.text.toString()
            toast(if (store.apiKey.isEmpty()) "Key removed" else "Key saved")
        })

        ai.addView(label("Model", 15f, ink, bold = true).pad(top = 16))
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Ai.MODELS.map { it.second })
        spinner.setSelection(Ai.MODELS.indexOfFirst { it.first == store.model }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                store.model = Ai.MODELS[position].first
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        ai.addView(spinner)

        ai.addView(Switch(this).apply {
            text = "Search the web when an action needs fresh facts"
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isChecked = store.webSearch
            setPadding(0, dp(16), 0, 0)
            setOnCheckedChangeListener { _, on -> store.webSearch = on }
        })
        column.addView(ai)

        // --- voice ---
        column.addView(section("Voice"))
        val voice = cardBox()
        micStatus = label("", 15f, ink)
        voice.addView(micStatus)
        voice.addView(label("Tap 🎙 on the keyboard to dictate; Verbo tidies up filler words, self-corrections and punctuation. Hold 🎙 to speak a request instead.", 13f, muted).pad(top = 4))
        voice.addView(button("Allow microphone") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) })
        column.addView(voice)

        // --- connections ---
        column.addView(section("Connections"))
        column.addView(label("Apps and APIs the assistant can use while carrying out an action.", 13f, muted).pad(bottom = 8))
        val weather = cardBox()
        weather.addView(Switch(this).apply {
            text = "☀️  Weather"
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isChecked = store.weather
            setOnCheckedChangeListener { _, on -> store.weather = on }
        })
        weather.addView(label("Forecasts from Open-Meteo. No account needed.", 13f, muted))
        column.addView(weather)
        column.addView(tokenCard("📝  Notion", "Search, read and create pages. Paste an internal integration token from notion.so/my-integrations and share the pages you want with that integration.",
            { store.notionToken }, { store.notionToken = it }))
        column.addView(tokenCard("📅  Calendly", "Drop your booking links into a message. Paste a personal access token from Calendly → Integrations → API & webhooks.",
            { store.calendlyToken }, { store.calendlyToken = it }))
        apisBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(apisBox)
        column.addView(button("Connect an API") { editApi(-1) })

        // --- skills ---
        column.addView(section("Skill keys"))
        column.addView(label("Each skill is a key above the keyboard that applies one instruction to whatever you've typed.", 13f, muted).pad(bottom = 8))
        skillsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(skillsBox)
        val skillButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        skillButtons.addView(button("New skill") { editSkill(-1) })
        skillButtons.addView(button("Restore defaults") {
            AlertDialog.Builder(this).setMessage("Replace your skills with the original set?")
                .setPositiveButton("Restore") { _, _ -> store.resetSkills(); renderSkills() }
                .setNegativeButton("Cancel", null).show()
        })
        column.addView(skillButtons)

        // --- try it ---
        column.addView(section("Try it"))
        val tryBox = cardBox()
        tryBox.addView(label("Type a request, then hold the ✦ bar. Or type a sentence and tap a skill key.", 13f, muted))
        tryBox.addView(EditText(this).apply {
            hint = "tell sam i'm running 15 min late, friendly"
            minLines = 3
            gravity = Gravity.TOP
            setTextColor(ink)
            setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        })
        column.addView(tryBox)

        renderSkills()
        renderApis()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refreshSetup()
    }

    private fun renderDashboard() {
        dashBox.removeAllViews()
        if (store.deviceToken.isNotEmpty()) {
            val last = if (store.lastSync == 0L) "not yet" else android.text.format.DateUtils.getRelativeTimeSpanString(store.lastSync).toString()
            dashBox.addView(label("✓  Paired with " + store.serverUrl.removePrefix("https://"), 15f, ink, bold = true))
            dashBox.addView(label("Skills, connections and the assistant settings come from the dashboard, so changes made below are replaced at the next sync. Last sync: $last.", 13f, muted).pad(top = 4))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Sync now") { background({ Sync.pull(store) }, "Synced") })
            row.addView(button("Unpair") { store.unpair(); renderDashboard() })
            dashBox.addView(row)
            return
        }
        dashBox.addView(label("Manage skills and connections from the web. Sign in to your dashboard, open Keyboards and tap Pair a keyboard to get a code.", 13f, muted))
        val address = EditText(this).apply {
            hint = "Dashboard address, e.g. verbo.example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(); setTextColor(ink); setHintTextColor(muted)
        }
        val code = EditText(this).apply {
            hint = "Pairing code"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(); setTextColor(ink); setHintTextColor(muted)
        }
        dashBox.addView(address)
        dashBox.addView(code)
        dashBox.addView(button("Pair") {
            val a = address.text.toString()
            val c = code.text.toString()
            if (a.isBlank() || c.isBlank()) toast("Enter the address and the code")
            else background({ Sync.pair(store, a, c) }, "Paired")
        })
    }

    /** Runs a dashboard call off the main thread, then redraws the screen with whatever was synced. */
    private fun background(work: () -> Unit, done: String) {
        Thread {
            val message = try { work(); done } catch (e: SyncException) { e.message ?: "Failed" } catch (e: Exception) { "Something went wrong" }
            runOnUiThread {
                toast(message)
                if (message == done) recreate() else renderDashboard()
            }
        }.start()
    }

    private fun tokenCard(title: String, help: String, get: () -> String, set: (String) -> Unit): LinearLayout {
        val box = cardBox()
        val heading = label("", 16f, ink, bold = true)
        fun refresh() { heading.text = title + if (get().isEmpty()) "" else "   ✓ connected" }
        refresh()
        box.addView(heading)
        box.addView(label(help, 13f, muted).pad(bottom = 4))
        val field = EditText(this).apply {
            hint = "Token"
            setText(get())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setTextColor(ink)
            setHintTextColor(muted)
        }
        box.addView(field)
        box.addView(button("Save") {
            set(field.text.toString())
            refresh()
            toast(if (get().isEmpty()) "Disconnected" else "Connected")
        })
        return box
    }

    private fun renderApis() {
        apisBox.removeAllViews()
        store.customApis.forEachIndexed { index, api ->
            val row = cardBox().apply { setOnClickListener { editApi(index) } }
            row.addView(label("🔌  " + api.name, 16f, ink, bold = true))
            row.addView(label(api.description.ifBlank { api.url }, 13f, muted).apply { maxLines = 2 })
            apisBox.addView(row)
        }
    }

    private fun editApi(index: Int) {
        val apis = store.customApis.toMutableList()
        val existing = apis.getOrNull(index)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        fun field(hint: String, value: String?) = EditText(this).apply {
            this.hint = hint
            setText(value ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            form.addView(this)
        }
        val name = field("Name, e.g. NBA scores", existing?.name)
        val description = field("What it returns and when to use it", existing?.description)
        val url = field("https://… use {query} where the search term goes", existing?.url)
        val headerName = field("Header name (optional), e.g. X-Api-Key", existing?.headerName)
        val headerValue = field("Header value (optional)", existing?.headerValue)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Connect an API" else "Edit connection")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                val u = url.text.toString().trim()
                if (n.isEmpty() || !u.startsWith("https://")) {
                    toast("It needs a name and an https:// address")
                } else {
                    val api = CustomApi(n, description.text.toString().trim(), u, headerName.text.toString().trim(), headerValue.text.toString().trim())
                    if (existing == null) apis.add(api) else apis[index] = api
                    store.customApis = apis
                    renderApis()
                }
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) dialog.setNeutralButton("Remove") { _, _ ->
            apis.removeAt(index)
            store.customApis = apis
            renderApis()
        }
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshSetup() // the keyboard picker is a dialog, so onResume doesn't fire after it
    }

    private fun refreshSetup() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = current.startsWith("$packageName/")
        stepOne.text = (if (enabled) "✓  " else "1.  ") + "Enable Verbo Keyboard in system settings"
        stepTwo.text = (if (selected) "✓  " else "2.  ") + "Switch to Verbo as your keyboard"
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micStatus.text = if (mic) "✓  Microphone allowed" else "Microphone not allowed yet"
    }

    private fun renderSkills() {
        skillsBox.removeAllViews()
        store.skills.forEachIndexed { index, skill ->
            val row = cardBox().apply { setOnClickListener { editSkill(index) } }
            row.addView(label((skill.emoji + "  " + skill.name).trim(), 16f, ink, bold = true))
            row.addView(label(skill.instruction, 13f, muted).apply { maxLines = 2 })
            skillsBox.addView(row)
        }
    }

    private fun editSkill(index: Int) {
        val skills = store.skills.toMutableList()
        val existing = skills.getOrNull(index)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val emoji = EditText(this).apply { hint = "Emoji (optional)"; setText(existing?.emoji ?: ""); setSingleLine() }
        val name = EditText(this).apply {
            hint = "Name, e.g. Pirate"
            setText(existing?.name ?: "")
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val instruction = EditText(this).apply {
            hint = "What should it do with the text? e.g. Rewrite it the way a cheerful pirate would say it."
            setText(existing?.instruction ?: "")
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        form.addView(emoji); form.addView(name); form.addView(instruction)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New skill" else "Edit skill")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                val i = instruction.text.toString().trim()
                if (n.isEmpty() || i.isEmpty()) {
                    toast("A skill needs a name and an instruction")
                } else {
                    val skill = Skill(emoji.text.toString().trim(), n, i)
                    if (existing == null) skills.add(skill) else skills[index] = skill
                    store.skills = skills
                    renderSkills()
                }
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) dialog.setNeutralButton("Delete") { _, _ ->
            skills.removeAt(index)
            store.skills = skills
            renderSkills()
        }
        dialog.show()
    }

    // ---------- small view helpers ----------

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun TextView.pad(top: Int = 0, bottom: Int = 0) = apply { setPadding(0, dp(top), 0, dp(bottom)) }

    private fun section(title: String) = label(title.uppercase(), 12f, accent, bold = true).apply {
        letterSpacing = 0.1f
        setPadding(0, dp(26), 0, dp(8))
    }

    private fun cardBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(card); cornerRadius = dp(14).toFloat() }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    private fun button(text: String, onTap: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6); rightMargin = dp(8) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
