package app.verbo.keyboard

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The first screen on a fresh install (or after signing out): "Continue with Google". Signing in pairs the
 * phone with the web dashboard and brings back everything saved there (AI keys, skills, auto actions,
 * connections, keyboard settings), so nothing has to be typed on the phone again.
 * "Use without an account" stays for people who keep everything on the phone.
 */
class SignInActivity : Activity() {
    private lateinit var store: Store
    private lateinit var look: Look

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        look = Look(this)
        build()
    }

    override fun onResume() {
        super.onResume()
        // Back from the browser with the sign-in done: nothing left to do here.
        if (Store(this).deviceToken.isNotEmpty()) { startActivity(next(this)); finish() }
    }

    private fun build() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(look.dp(24), look.dp(56), look.dp(24), look.dp(32))
        }
        column.addView(BotView(this).apply { mood = BotView.Mood.HELLO },
            LinearLayout.LayoutParams(look.dp(150), look.dp(150)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        column.addView(look.text("Welcome to Verbo", 26f, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(0, look.dp(18), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        column.addView(look.text("The keyboard that acts on what you type.", 15f, look.violetInk).apply {
            gravity = Gravity.CENTER; setPadding(0, look.dp(6), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = look.shape(look.card, 20)
            setPadding(look.dp(18), look.dp(16), look.dp(18), look.dp(16))
        }
        for ((icon, line) in listOf(
            R.drawable.ic_sparkles to "Your AI keys come back by themselves",
            R.drawable.ic_layers to "Skills, auto actions and connected apps restored",
            R.drawable.ic_cloud to "Paired with your web dashboard, kept in sync",
        )) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, look.dp(6), 0, look.dp(6)) }
            row.addView(look.icon(icon, 20, look.accent))
            row.addView(look.text(line, 14.5f, look.ink).apply { setPadding(look.dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(row)
        }
        column.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.dp(28) })

        column.addView(look.purplePill("Continue with Google", R.drawable.ic_brand_google) { signIn() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = look.dp(28)
        })
        column.addView(look.text("Use without an account", 14f, look.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, look.dp(16), 0, look.dp(8))
            setOnClickListener {
                store.signInSkipped = true
                startActivity(next(this@SignInActivity))
                finish()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        column.addView(look.text("Without an account you add your AI key on the phone, and nothing is backed up.", 12.5f, look.muted).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = PaperWaves(look.bg)
            addView(column, FrameLayout.LayoutParams(-1, -2))
        })
    }

    private fun signIn() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Sync.googleSignInUrl(store))))
        } catch (e: SyncException) {
            look.toast(e.message ?: "Couldn't start the sign-in")
        } catch (e: Exception) {
            look.toast("No browser found to sign in with")
        }
    }

    companion object {
        /** True when the app should open on this screen: not paired and the user hasn't chosen to go without. */
        fun needed(store: Store) = !store.locked && store.deviceToken.isEmpty() && !store.signInSkipped

        /** Where to go once signed in (or skipped): the setup steps until the keyboard is on, then the app. */
        fun next(a: Activity): Intent {
            val store = Store(a)
            val imm = a.getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            val enabled = try { imm.enabledInputMethodList.any { it.packageName == a.packageName } } catch (e: Exception) { false }
            val selected = (android.provider.Settings.Secure.getString(a.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD) ?: "").startsWith(a.packageName + "/")
            // Already set up on this phone and the settings came back: straight into the app.
            if (enabled && selected && store.activeKey.isNotEmpty()) store.onboarded = true
            return if (store.onboarded) Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            else Intent(a, PageActivity::class.java).putExtra("page", "welcome").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}
