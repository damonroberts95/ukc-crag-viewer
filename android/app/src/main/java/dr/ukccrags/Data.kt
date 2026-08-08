package dr.ukccrags

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** A single climb. UKC's hierarchy is crag → buttress → climb. */
data class Climb(
    val name: String,
    val grade: String,
    val type: String,
    val stars: Int,
    val logs: Int,
    val url: String,
    /** UKC's numeric climb id, falling back to the one in the URL. */
    val climbId: Long = 0L,
    /** UKC's own words for the climb, empty when it has none. */
    val description: String = "",
    /** How many photos UKC holds for it. The photos themselves stay there. */
    val photos: Int = 0,
    /** UKC's numeric difficulty, comparable across grading systems. 0 if unknown. */
    val gradeScore: Double = 0.0,
    /** Metres, 0 when UKC has none. */
    val height: Int = 0,
    val pitches: Int = 0,
    /** What the crag page said about the reader's own ascents at import time. */
    val ticked: Boolean = false,
    val attempted: Boolean = false,
)

/** A buttress (UKC calls these sectors on the crag page). */
data class Buttress(
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
    val climbs: List<Climb>,
) {
    val hasPin: Boolean get() = latitude != null && longitude != null
}

/** One climb's line drawn over a topo photo, in the photo's own pixel space. */
data class TopoLine(
    val climbId: Long,
    val name: String,
    val points: List<Pair<Float, Float>>,
)

/** A photo of a buttress with the climbs drawn on it. */
data class Topo(
    val topoId: Long,
    val buttress: String,
    val width: Int,
    val height: Int,
    val lines: List<TopoLine>,
) {
}

data class Crag(
    val area: String,
    val sourceUrl: String,
    val latitude: Double?,
    val longitude: Double?,
    val climbCount: Int,
    /** The crag's features and approach notes, as UKC prints them. */
    val description: String = "",
    val buttresses: List<Buttress>,
    val topos: List<Topo> = emptyList(),
) {
    val hasPin: Boolean get() = latitude != null && longitude != null

    val locatedButtresses: Int get() = buttresses.count { it.hasPin }

    /** UKC's numeric crag id, falling back to the name if the URL lacks one. */
    val id: String
        get() = Regex("-(\\d+)/?$").find(sourceUrl)?.groupValues?.get(1)
            ?: area.lowercase().replace(Regex("[^a-z0-9]+"), "_").ifEmpty { "crag" }

    /** Straight-line metres from [from], or null when the crag has no pin. */
    fun metresFrom(from: Location?): Float? {
        if (from == null || !hasPin) return null

        val out = FloatArray(1)
        Location.distanceBetween(from.latitude, from.longitude, latitude!!, longitude!!, out)
        return out[0]
    }
}

/** The trailing number in a UKC URL, which is the thing's id. */
internal fun idInUrl(url: String): Long =
    Regex("-(\\d+)/?$").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

/** Reads either key, so files written before the climb/route rename still load. */
private fun JSONObject.arrayFor(vararg keys: String): JSONArray {
    for (key in keys) optJSONArray(key)?.let { return it }
    return JSONArray()
}

object CragStore {

    private var cache: List<Crag>? = null

    private fun storeDir(context: Context): File =
        File(context.filesDir, "crags").apply { mkdirs() }

    fun invalidate() {
        cache = null
    }

    fun load(context: Context): List<Crag> {
        cache?.let { return it }

        val byId = LinkedHashMap<String, Crag>()

        val assets = context.assets
        for (name in assets.list("crags").orEmpty().filter { it.endsWith(".json") }) {
            runCatching {
                val text = assets.open("crags/$name").bufferedReader().use { it.readText() }
                parse(JSONObject(text))
            }.getOrNull()?.let { byId[it.id] = it }
        }

        // Device imports win over anything bundled at build time.
        for (file in storeDir(context).listFiles().orEmpty().filter { it.extension == "json" }) {
            runCatching { parse(JSONObject(file.readText())) }
                .getOrNull()?.let { byId[it.id] = it }
        }

        val crags = byId.values.sortedBy { it.area.lowercase() }
        cache = crags
        return crags
    }

    fun has(context: Context, id: String): Boolean =
        File(storeDir(context), "$id.json").exists()

    /** Deletes every imported crag and its cached topo photos. Ticks survive. */
    fun clear(context: Context) {
        storeDir(context).listFiles().orEmpty().forEach { it.delete() }
        TopoCache.clear(context)
        invalidate()
    }

    /** Drops one crag and its topo photos, so a refresh starts from nothing. */
    fun forget(context: Context, crag: Crag) {
        File(storeDir(context), "${crag.id}.json").delete()
        crag.topos.forEach { TopoCache.file(context, it.topoId.toString()).delete() }
        invalidate()
    }

    /** Returns the crag it stored, or null when the JSON made no sense. */
    fun save(context: Context, json: String): Crag? = runCatching {
        val crag = parse(JSONObject(json))
        File(storeDir(context), "${crag.id}.json").writeText(json)
        invalidate()
        crag
    }.getOrNull()

    private fun parse(root: JSONObject): Crag {
        val buttressArray = root.arrayFor("buttresses", "sectors")

        val buttresses = (0 until buttressArray.length()).map { index ->
            val node = buttressArray.getJSONObject(index)
            val climbArray = node.arrayFor("climbs", "routes")

            val climbs = (0 until climbArray.length()).map { climbIndex ->
                val climb = climbArray.getJSONObject(climbIndex)
                Climb(
                    name = climb.optString("name"),
                    grade = climb.optString("grade"),
                    type = climb.optString("type"),
                    stars = climb.optInt("stars"),
                    logs = climb.optInt("logs"),
                    url = climb.optString("url"),
                    climbId = climb.optLong("climb_id").takeIf { it > 0L }
                        ?: idInUrl(climb.optString("url")),
                    description = climb.optString("description"),
                    photos = climb.optInt("photos"),
                    gradeScore = climb.optDouble("grade_score", 0.0),
                    height = climb.optInt("height"),
                    pitches = climb.optInt("pitches"),
                    ticked = climb.optBoolean("ticked"),
                    attempted = climb.optBoolean("attempted"),
                )
            }

            Buttress(
                name = node.optString("name"),
                latitude = node.optDoubleOrNull("latitude"),
                longitude = node.optDoubleOrNull("longitude"),
                climbs = climbs,
            )
        }

        val count = if (root.has("climb_count")) {
            root.optInt("climb_count")
        } else if (root.has("route_count")) {
            root.optInt("route_count")
        } else {
            buttresses.sumOf { it.climbs.size }
        }

        return Crag(
            area = root.optString("area"),
            sourceUrl = root.optString("source_url"),
            latitude = root.optDoubleOrNull("latitude"),
            longitude = root.optDoubleOrNull("longitude"),
            climbCount = count,
            description = root.optString("description"),
            buttresses = buttresses,
            topos = parseTopos(root.arrayFor("topos")),
        )
    }

    private fun parseTopos(array: JSONArray): List<Topo> =
        (0 until array.length()).mapNotNull { index ->
            val node = array.optJSONObject(index) ?: return@mapNotNull null
            val lineArray = node.arrayFor("lines")

            val lines = (0 until lineArray.length()).mapNotNull { lineIndex ->
                val line = lineArray.optJSONObject(lineIndex) ?: return@mapNotNull null
                val pointArray = line.arrayFor("points")

                val points = (0 until pointArray.length()).mapNotNull { pointIndex ->
                    val pair = pointArray.optJSONArray(pointIndex) ?: return@mapNotNull null
                    if (pair.length() < 2) null
                    else pair.optDouble(0).toFloat() to pair.optDouble(1).toFloat()
                }

                if (points.size < 2) null
                else TopoLine(
                    climbId = line.optLong("climb_id"),
                    name = line.optString("name"),
                    points = points,
                )
            }

            Topo(
                topoId = node.optLong("topo_id"),
                buttress = node.optString("buttress"),
                width = node.optInt("width"),
                height = node.optInt("height"),
                lines = lines,
            )
        }
}

/** Climbs on UKC's wishlist, keyed by URL like the ticks. */
class Wishlist(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wishlist", Context.MODE_PRIVATE)

    private val wanted: MutableSet<String> =
        prefs.getStringSet(KEY, emptySet())!!.toMutableSet()

    fun has(url: String): Boolean = wanted.contains(url)

    fun isEmpty(): Boolean = wanted.isEmpty()

    /** UKC owns this list, so a sync replaces it rather than adding to it. */
    fun replaceWith(urls: Collection<String>): Int {
        wanted.clear()
        wanted.addAll(urls)
        prefs.edit().putStringSet(KEY, wanted.toSet()).apply()
        return wanted.size
    }

    private companion object {
        const val KEY = "climb_urls"
    }
}

/** Climbs tried but not topped, so they read differently from untouched ones. */
class Attempts(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("attempts", Context.MODE_PRIVATE)

    private val tried: MutableSet<String> =
        prefs.getStringSet(KEY, emptySet())!!.toMutableSet()

    fun has(url: String): Boolean = tried.contains(url)

    fun addAll(urls: Collection<String>) {
        if (urls.isEmpty()) return
        tried.addAll(urls)
        prefs.edit().putStringSet(KEY, tried.toSet()).apply()
    }

    private companion object {
        const val KEY = "climb_urls"
    }
}

/** Ticked climbs, keyed by climb URL so they survive re-imports of a crag. */
class Ticks(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ticks", Context.MODE_PRIVATE)

    private val ticked: MutableSet<String> =
        prefs.getStringSet(KEY, emptySet())!!.toMutableSet()

    fun has(url: String): Boolean = ticked.contains(url)

    fun countIn(crag: Crag): Int =
        crag.buttresses.sumOf { buttress -> buttress.climbs.count { has(it.url) } }

    /** Folds in a logbook sync, keeping what earlier syncs already found. */
    fun addAll(urls: Collection<String>): Int {
        val added = urls.count { ticked.add(it) }
        prefs.edit().putStringSet(KEY, ticked.toSet()).apply()
        return added
    }

    /**
     * Folds in the CSV export, which names climbs rather than linking them.
     * Matching is on crag plus climb name, loosened to ignore case, accents
     * and punctuation, since a logbook entry and a crag page do not always
     * agree on an apostrophe.
     */
    fun addByName(crags: List<Crag>, entries: List<Pair<String, String>>): Int {
        val byCrag = HashMap<String, HashMap<String, String>>()

        for (crag in crags) {
            val climbs = byCrag.getOrPut(loosen(crag.area)) { HashMap() }
            for (buttress in crag.buttresses) {
                for (climb in buttress.climbs) climbs[loosen(climb.name)] = climb.url
            }
        }

        val found = mutableListOf<String>()

        for ((cragName, climbName) in entries) {
            byCrag[loosen(cragName)]?.get(loosen(climbName))?.let { found.add(it) }
        }

        return addAll(found)
    }

    private fun loosen(value: String): String = java.text.Normalizer
        .normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("[^a-z0-9]"), "")

    private companion object {
        const val KEY = "route_urls"
    }
}
