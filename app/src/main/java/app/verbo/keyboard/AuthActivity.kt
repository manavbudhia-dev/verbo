package app.verbo.keyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible. The dashboard sends the browser back to verbo://auth?code=… after "Continue with Google";
 * this pairs the phone with that code (only this phone holds the matching verifier) and returns to the app.
 */
class AuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent?.data?.getQueryParameter("code")?.takeIf { Regex("[A-Z0-9]{6,12}").matches(it) }
        val store = Store(this)
        if (code == null) return done("That sign-in link is not complete. Try again")
        Thread {
            val message = try {
                Sync.finishGoogle(store, code)
                restored(Store(this))
            } catch (e: SyncException) {
                e.message ?: "Sign-in failed"
            } catch (e: Exception) {
                "Sign-in failed"
            }
            runOnUiThread { done(message) }
        }.start()
    }

    /** What came back from the dashboard, in one line: so the user sees nothing needs typing again. */
    private fun restored(store: Store): String {
        val parts = ArrayList<String>()
        if (store.activeKey.isNotEmpty()) parts += "your ${store.providerName} key"
        val skills = store.skills.size
        if (skills > 0) parts += "$skills skill" + if (skills == 1) "" else "s"
        parts += "your settings"
        return if (store.activeKey.isEmpty()) "Signed in. Add an AI key on the dashboard or here to start"
        else "Welcome back. Restored " + parts.joinToString(", ")
    }

    private fun done(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        val store = Store(this)
        // Not signed in after all (a failed or stale link): back to the sign-in screen when that is where we came from.
        val intent = if (SignInActivity.needed(store)) Intent(this, SignInActivity::class.java) else SignInActivity.next(this)
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
