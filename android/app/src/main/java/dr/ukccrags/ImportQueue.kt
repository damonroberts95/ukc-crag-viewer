package dr.ukccrags

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** A crag waiting to be read, as UKC's search hands it over. */
data class Queued(val name: String, val url: String)

/**
 * Crags still to fetch, kept on disk.
 *
 * A search of a whole region names thousands of crags in one page, and reading
 * them all in a single run was the wrong shape: it took a quarter of an hour of
 * held-open screen, and anything that interrupted it — a stall, a lock screen,
 * the app being swapped out — left no record of where it had got to.
 *
 * A search now costs one request: it takes the names and URLs and writes them
 * here. The reading happens afterwards, in batches, whenever the app is open,
 * and this file is what makes that survive being interrupted. Stop it half way
 * and the rest is still listed.
 *
 * Refreshing works the same way, which is why a refresh of a large library no
 * longer has to be one long sitting either.
 */
object ImportQueue {

    private const val KEY_PAUSED = "paused"

    private fun file(context: Context): File = File(context.filesDir, "queue.json")

    private fun prefs(context: Context) =
        context.getSharedPreferences("queue", Context.MODE_PRIVATE)

    /** True while the reader has asked for the queue to sit still. */
    var Context.queuePaused: Boolean
        get() = prefs(this).getBoolean(KEY_PAUSED, false)
        set(value) {
            prefs(this).edit().putBoolean(KEY_PAUSED, value).apply()
        }

    fun all(context: Context): List<Queued> {
        val stored = file(context)
        if (!stored.exists()) return emptyList()

        return runCatching {
            val array = JSONArray(stored.readText())

            (0 until array.length()).mapNotNull { index ->
                val node = array.optJSONObject(index) ?: return@mapNotNull null
                val url = node.optString("url")
                if (url.isBlank()) null else Queued(node.optString("name"), url)
            }
        }.getOrDefault(emptyList())
    }

    fun size(context: Context): Int = all(context).size

    /**
     * Adds what is not already queued. [skipHeld] leaves out crags the library
     * already holds, which is what makes re-running a search cheap; a refresh
     * passes false, since the whole point there is to read them again.
     */
    fun add(context: Context, items: List<Queued>, skipHeld: Boolean = true): Int {
        val queued = all(context)
        val known = queued.map { it.url }.toMutableSet()

        val fresh = items.filter { item ->
            item.url.isNotBlank() &&
                known.add(item.url) &&
                (!skipHeld || !CragStore.has(context, cragIdIn(item.url)))
        }

        if (fresh.isEmpty()) return 0

        write(context, queued + fresh)
        return fresh.size
    }

    /** The next few to read. Batches are small so little is lost to a kill. */
    fun next(context: Context, count: Int): List<Queued> = all(context).take(count)

    fun drop(context: Context, done: List<Queued>) {
        val gone = done.map { it.url }.toSet()
        write(context, all(context).filterNot { it.url in gone })
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun write(context: Context, items: List<Queued>) {
        val array = JSONArray()

        for (item in items) {
            array.put(JSONObject().put("name", item.name).put("url", item.url))
        }

        // Written whole, so a kill mid-write cannot leave half a list.
        val stored = file(context)
        val temporary = File(stored.parentFile, "queue.json.part")

        temporary.writeText(array.toString())
        temporary.renameTo(stored)
    }

    /** The JSON the page's own importer expects. */
    fun asJson(items: List<Queued>): String {
        val array = JSONArray()

        for (item in items) {
            array.put(JSONObject().put("name", item.name).put("url", item.url))
        }

        return array.toString()
    }

    private fun cragIdIn(url: String): String =
        Regex("-(\\d+)/?$").find(url)?.groupValues?.get(1).orEmpty()
}
