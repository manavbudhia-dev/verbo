package app.verbo.keyboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** A small street map centred on a point, built from OpenStreetMap tiles, with a pin. Blocking; returns null on any failure. */
object MapPreview {
    private const val ZOOM = 16
    private const val TILE = 256
    const val WIDTH = 512
    const val HEIGHT = 224

    fun load(lat: Double, lon: Double): Bitmap? = try {
        val n = 1 shl ZOOM
        // Position of the point in tile units (Web Mercator).
        val x = (lon + 180.0) / 360.0 * n
        val rad = lat * PI / 180
        val y = (1 - ln(tan(rad) + 1 / kotlin.math.cos(rad)) / PI) / 2 * n
        // The 2x2 block of tiles whose centre is nearest the point.
        val x0 = floor(x - 0.5).toInt()
        val y0 = floor(y - 0.5).toInt()
        val sheet = Bitmap.createBitmap(TILE * 2, TILE * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        for (dx in 0..1) for (dy in 0..1) {
            val tile = fetch("https://tile.openstreetmap.org/$ZOOM/${(x0 + dx).mod(n)}/${y0 + dy}.png") ?: return null
            canvas.drawBitmap(tile, (dx * TILE).toFloat(), (dy * TILE).toFloat(), null)
        }
        val px = ((x - x0) * TILE).toFloat()
        val py = ((y - y0) * TILE).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        canvas.drawCircle(px, py, 13f, paint)
        paint.color = Color.parseColor("#E5322D")
        canvas.drawCircle(px, py, 10f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(px, py, 3.5f, paint)
        // Crop a wide strip around the pin, staying inside the sheet.
        val top = (py - HEIGHT / 2).toInt().coerceIn(0, TILE * 2 - HEIGHT)
        val out = Bitmap.createBitmap(sheet, 0, top, WIDTH, HEIGHT)
        val credit = Canvas(out)
        paint.color = Color.parseColor("#B3FFFFFF")
        credit.drawRect(WIDTH - 150f, HEIGHT - 20f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.color = Color.parseColor("#333333")
        paint.textSize = 13f
        credit.drawText("© OpenStreetMap", WIDTH - 144f, HEIGHT - 6f, paint)
        out
    } catch (e: Exception) {
        null
    }

    private fun fetch(url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", Connections.USER_AGENT)
            if (conn.responseCode == 200) conn.inputStream.use { BitmapFactory.decodeStream(it) } else null
        } finally {
            conn.disconnect()
        }
    }
}
