package app.verbo.keyboard

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Saves events straight into the phone's calendar (which syncs to the user's Google Calendar), so an
 * "add to calendar" result is one tap instead of opening the Calendar app and pressing Save there.
 */
object PhoneCalendar {
    data class Event(val title: String, val start: Long, val end: Long, val allDay: Boolean, val location: String, val details: String)

    /** Reads a calendar.google.com/calendar/render?action=TEMPLATE link. Null if it isn't one or has no usable dates. */
    fun parseTemplate(link: String, zone: TimeZone = TimeZone.getDefault()): Event? {
        val uri = try { Uri.parse(link) } catch (e: Exception) { return null }
        if (uri.host?.endsWith("calendar.google.com") != true || uri.getQueryParameter("action")?.uppercase() != "TEMPLATE") return null
        val dates = uri.getQueryParameter("dates")?.split("/") ?: return null
        if (dates.size != 2) return null
        val tz = uri.getQueryParameter("ctz")?.let { TimeZone.getTimeZone(it) } ?: zone
        fun read(s: String): Pair<Long, Boolean>? {
            val t = s.trim()
            val (pattern, allDay, utc) = when {
                Regex("^\\d{8}$").matches(t) -> Triple("yyyyMMdd", true, false)
                Regex("^\\d{8}T\\d{6}Z$").matches(t) -> Triple("yyyyMMdd'T'HHmmss'Z'", false, true)
                Regex("^\\d{8}T\\d{6}$").matches(t) -> Triple("yyyyMMdd'T'HHmmss", false, false)
                Regex("^\\d{8}T\\d{4}$").matches(t) -> Triple("yyyyMMdd'T'HHmm", false, false)
                else -> return null
            }
            val f = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false; timeZone = if (utc || allDay) TimeZone.getTimeZone("UTC") else tz }
            return try { f.parse(t)!!.time to allDay } catch (e: Exception) { null }
        }
        val (start, allDay) = read(dates[0]) ?: return null
        var end = read(dates[1])?.first ?: return null
        if (end <= start) end = start + if (allDay) 86_400_000L else 3_600_000L
        val title = uri.getQueryParameter("text")?.trim().orEmpty().ifEmpty { "Event" }
        return Event(title, start, end, allDay, uri.getQueryParameter("location").orEmpty(), uri.getQueryParameter("details").orEmpty())
    }

    fun allowed(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** The calendar to write to: the primary Google one if there is one, else any calendar the user can edit. */
    private fun calendarId(context: Context): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_TYPE)
        val where = "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        var best: Pair<Long, Int>? = null
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, where, null, null)?.use { c ->
            while (c.moveToNext()) {
                val score = (if (c.getInt(1) == 1) 2 else 0) + (if (c.getString(2) == "com.google") 1 else 0)
                if (best == null || score > best!!.second) best = c.getLong(0) to score
            }
        }
        return best?.first
    }

    /** Saves the event with a 10-minute reminder. Returns its id. */
    fun save(context: Context, e: Event): Long {
        val calendar = calendarId(context) ?: throw IllegalStateException("No calendar on this phone. Add a Google account in Settings")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar)
            put(CalendarContract.Events.TITLE, e.title)
            put(CalendarContract.Events.DTSTART, e.start)
            put(CalendarContract.Events.DTEND, e.end)
            put(CalendarContract.Events.ALL_DAY, if (e.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (e.allDay) "UTC" else TimeZone.getDefault().id)
            if (e.location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, e.location)
            if (e.details.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, e.details)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: throw IllegalStateException("The calendar refused the event")
        val id = ContentUris.parseId(uri)
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, id)
            put(CalendarContract.Reminders.MINUTES, 10)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
        return id
    }

    fun remove(context: Context, id: Long) {
        context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
    }

    fun viewUri(id: Long): Uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)

    /** "Sat 14 Nov, 23:54" in the phone's time zone. */
    fun describe(e: Event): String =
        SimpleDateFormat(if (e.allDay) "EEE d MMM" else "EEE d MMM, HH:mm", Locale.getDefault())
            .apply { if (e.allDay) timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(e.start))
}
