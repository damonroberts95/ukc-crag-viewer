package dr.ukccrags

import android.content.Context
import java.io.File
import org.json.JSONArray

/** A UKC ticklist: a named set of climbs, the reader's own or one they follow. */
data class Ticklist(
    val name: String,
    val url: String,
    val climbs: List<String>,
)

/**
 * The reader's ticklists, kept as one file rather than in preferences: a list
 * can hold hundreds of climbs and there can be a dozen lists.
 *
 * UKC owns them, so a sync replaces what is here rather than merging.
 */
object Lists {

    private fun file(context: Context): File = File(context.filesDir, "ticklists.json")

    fun load(context: Context): List<Ticklist> {
        val stored = file(context)
        if (!stored.exists()) return emptyList()

        return runCatching {
            val array = JSONArray(stored.readText())

            (0 until array.length()).mapNotNull { index ->
                val node = array.optJSONObject(index) ?: return@mapNotNull null
                val climbs = node.optJSONArray("climbs") ?: JSONArray()

                Ticklist(
                    name = node.optString("name"),
                    url = node.optString("url"),
                    climbs = (0 until climbs.length()).map { climbs.optString(it) },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun replaceWith(context: Context, json: String): Int = runCatching {
        // Parsed before writing, so a broken sync cannot destroy a good file.
        val parsed = JSONArray(json)
        file(context).writeText(parsed.toString())
        parsed.length()
    }.getOrDefault(0)

    /** Which lists hold this climb, by name. */
    fun holding(context: Context, climbUrl: String): List<String> =
        load(context).filter { it.climbs.contains(climbUrl) }.map { it.name }

    fun clear(context: Context) {
        file(context).delete()
    }

    /**
     * The crags a list touches, as {name, url} pairs ready for an import.
     *
     * A climb URL is the crag's own URL plus one segment, so the crags can be
     * worked out without asking UKC anything. The name is only for progress
     * text, so a tidied slug will do.
     */
    fun cragsIn(list: Ticklist): List<Pair<String, String>> {
        val seen = LinkedHashMap<String, String>()

        for (climb in list.climbs) {
            val cragUrl = climb.trimEnd('/').substringBeforeLast('/')
            if (!cragUrl.contains("/logbook/crags/")) continue

            val name = cragUrl.substringAfterLast('/')
                .replace(Regex("-\\d+$"), "")
                .replace('_', ' ')
                .replaceFirstChar { it.uppercase() }

            seen.putIfAbsent(cragUrl, name)
        }

        return seen.map { it.value to it.key }
    }

    /** The stored crag climbs a list points at, in the list's own order. */
    fun climbsIn(list: Ticklist, crags: List<Crag>): List<Pair<Crag, Climb>> {
        val byUrl = HashMap<String, Pair<Crag, Climb>>()

        for (crag in crags) {
            for (buttress in crag.buttresses) {
                for (climb in buttress.climbs) byUrl[climb.url] = crag to climb
            }
        }

        return list.climbs.mapNotNull { byUrl[it] }
    }
}
