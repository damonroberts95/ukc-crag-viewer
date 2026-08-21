package dr.ukccrags

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own log, readable on the phone.
 *
 * A release build cannot be read with `adb logcat run-as`, and the interesting
 * runs — a region-wide read that stops after an hour, a sync that finds
 * nothing — happen away from a laptop. So the things worth knowing afterwards
 * are written here as well as to logcat: what started, how far it got, and why
 * it stopped.
 *
 * Kept small and boring on purpose: a text file, appended, trimmed when it gets
 * long, and never containing anything but crag names and counts.
 */
object AppLog {

    private const val FILE = "log.txt"

    /** Trimmed to roughly this, oldest first, so it cannot grow without end. */
    private const val MAX_BYTES = 256 * 1024
    private const val KEEP_BYTES = 192 * 1024

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.UK)

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** Safe from any thread: the import writes from the page's own. */
    @Synchronized
    fun add(context: Context, message: String) {
        Log.i("UKC", message)

        runCatching {
            val stored = file(context)
            stored.appendText(stamp.format(Date()) + "  " + message + "\n")

            if (stored.length() > MAX_BYTES) trim(stored)
        }
    }

    fun read(context: Context): String {
        val stored = file(context)
        if (!stored.exists()) return ""

        return runCatching { stored.readText() }.getOrDefault("")
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun trim(stored: File) {
        val text = stored.readText()
        val cut = (text.length - KEEP_BYTES).coerceAtLeast(0)

        // Cut on a line, not mid-sentence.
        val from = text.indexOf('\n', cut).let { if (it == -1) cut else it + 1 }
        stored.writeText(text.substring(from))
    }
}
