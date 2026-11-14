package app.verbo.keyboard

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/** Asks once for calendar access, then saves the event the keyboard passed in. Shows nothing but Android's prompt. */
class CalendarPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PhoneCalendar.allowed(this)) finishWithSave()
        else requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) finishWithSave()
        else {
            Toast.makeText(this, "Verbo needs calendar access to save events", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun finishWithSave() {
        val event = intent.getStringExtra(EXTRA_LINK)?.let { PhoneCalendar.parseTemplate(it) }
        if (event != null) {
            val message = try {
                PhoneCalendar.save(this, event)
                "Saved to calendar: ${event.title}, ${PhoneCalendar.describe(event)}"
            } catch (e: Exception) {
                e.message ?: "Couldn't save the event"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        const val EXTRA_LINK = "link"
    }
}
