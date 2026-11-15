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
                "Signed in. Your skills, agents and keys are here"
            } catch (e: SyncException) {
                e.message ?: "Sign-in failed"
            } catch (e: Exception) {
                "Sign-in failed"
            }
            runOnUiThread { done(message) }
        }.start()
    }

    private fun done(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        val page = if (Store(this).onboarded) "dashboard" else "welcome"
        startActivity(Intent(this, PageActivity::class.java).putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
