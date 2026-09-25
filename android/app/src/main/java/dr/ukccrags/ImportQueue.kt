package dr.ukccrags

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * A crag waiting to be read, as UKC's search hands it over. [tries] counts the
 * reads that failed, so a crag lost to a passing network fault gets another
 * chance without a crag that cannot be read going round for ever.
 */
data class Queued(val name: String, val url: String, val tries: Int = 0)

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
 *
 * Written from the page's bridge thread as crags land and from the main thread
 * as searches queue more, so every change is one locked read-modify-write. The
 * list is held in memory as well: the queue's info window asks its size every
 * second, and reparsing a few thousand entries each time was the whole cost.
 */
object ImportQueue {

    private const val KEY_PAUSED = "paused"

    private fun file(context: Context): File = File(context.filesDir, "queue.json")

    private fun prefs(context: Context) =
        context.getSharedPreferences("queue", Context.MODE_PRIVATE)

    /** What is on disk, once read. Only touched under the object's lock. */
    private var cached: List<Queued>? = null

    /** True while the reader has asked for the queue to sit still. */
    var Context.queuePaused: Boolean
        get() = prefs(this).getBoolean(KEY_PAUSED, false)
        set(value) {
            prefs(this).edit().putBoolean(KEY_PAUSED, value).apply()
        }

    @Synchronized
    fun all(context: Context): List<Queued> = cached ?: read(context).also { cached = it }

    @Synchronized
    fun size(context: Context): Int = all(context).size

    /**
     * Adds what is not already queued. [skipHeld] leaves out crags the library
     * already holds, which is what makes re-running a search cheap; a refresh
     * passes false, since the whole point there is to read them again.
     */
    @Synchronized
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

    /** Puts failed crags back, at the end, with their count of tries raised. */
    @Synchronized
    fun requeue(context: Context, items: List<Queued>) {
        if (items.isEmpty()) return
        write(context, all(context) + items)
    }

    /** The next few to read. Batches are small so little is lost to a kill. */
    @Synchronized
    fun next(context: Context, count: Int): List<Queued> = all(context).take(count)

    @Synchronized
    fun drop(context: Context, done: List<Queued>) {
        dropAndRequeue(context, done.map { it.url }.toSet(), emptyList())
    }

    /** Strikes one crag off as soon as it has landed, rather than per batch. */
    @Synchronized
    fun strike(context: Context, url: String) {
        val queued = all(context)
        if (queued.none { it.url == url }) return
        write(context, queued.filterNot { it.url == url })
    }

    /**
     * Takes [gone] off and puts [again] on the end in one write, so a kill in
     * between cannot lose the crags that were meant to go round again.
     */
    @Synchronized
    fun dropAndRequeue(context: Context, gone: Set<String>, again: List<Queued>) {
        if (gone.isEmpty() && again.isEmpty()) return
        val back = again.map { it.url }.toSet()
        write(context, all(context).filterNot { it.url in gone || it.url in back } + again)
    }

    @Synchronized
    fun clear(context: Context) {
        cached = emptyList()
        AtomicFile(file(context)).delete()
    }

    private fun read(context: Context): List<Queued> {
        val stored = AtomicFile(file(context))
        if (!stored.baseFile.exists()) return emptyList()

        return runCatching {
            val array = JSONArray(stored.readFully().decodeToString())

            (0 until array.length()).mapNotNull { index ->
                val node = array.optJSONObject(index) ?: return@mapNotNull null
                val url = node.optString("url")
                if (url.isBlank()) null
                else Queued(node.optString("name"), url, node.optInt("tries", 0))
            }
        }.getOrElse {
            // Unreadable is not the same as empty: the next write would have
            // replaced a region's worth of crags with nothing. Kept aside so it
            // can be looked at, and said.
            val aside = File(context.filesDir, "queue.json.bad-${System.currentTimeMillis()}")
            stored.baseFile.renameTo(aside)
            AppLog.add(context, "queue: could not read queue.json ($it), moved to ${aside.name}")
            emptyList()
        }
    }

    /** Written whole through AtomicFile, so a kill mid-write cannot leave half a list. */
    private fun write(context: Context, items: List<Queued>) {
        val stored = AtomicFile(file(context))
        val bytes = asJson(items).toByteArray()

        val out = runCatching { stored.startWrite() }.getOrElse {
            AppLog.add(context, "queue: could not write queue.json — $it")
            return
        }

        runCatching {
            out.write(bytes)
            stored.finishWrite(out)
            cached = items
        }.onFailure {
            stored.failWrite(out)
            AppLog.add(context, "queue: could not write queue.json — $it")
        }
    }

    /** The JSON the page's own importer expects. */
    fun asJson(items: List<Queued>): String {
        val array = JSONArray()

        for (item in items) {
            array.put(
                JSONObject()
                    .put("name", item.name)
                    .put("url", item.url)
                    .put("tries", item.tries)
            )
        }

        return array.toString()
    }

    private fun cragIdIn(url: String): String =
        Regex("-(\\d+)/?$").find(url)?.groupValues?.get(1).orEmpty()
}
