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

/** Where UKC says to leave the car. A big crag may have one for each end. */
data class Parking(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

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
    /** Empty for crags imported before parking was read, until refreshed. */
    val parking: List<Parking> = emptyList(),
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

/**
 * Where crags live.
 *
 * Two halves, on purpose. The JSON files under `files/crags/` are the record of
 * what was scraped and are never thrown away — re-reading four thousand pages
 * to rebuild something derived would be rude to UKC. [CragDb] is the index over
 * them: it answers the questions a list, a map or a search actually asks
 * without any of them holding a library in memory. Opening a crag reads its
 * file; nothing else does.
 */
object CragStore {

    /**
     * Held while a file and its rows are written together, so a rebuild reading
     * files cannot put an older copy of a crag over one a queue batch has just
     * saved.
     */
    internal val writeLock = Any()

    /**
     * True once [open] has run in this process. The first open after an
     * update may be a database upgrade, which is too slow for the main thread,
     * so screens reached first wait for this rather than opening it themselves.
     */
    @Volatile
    var ready: Boolean = false
        private set

    private fun storeDir(context: Context): File =
        File(context.filesDir, "crags").apply { mkdirs() }

    private fun fileFor(context: Context, id: String): File = File(storeDir(context), "$id.json")

    /** Kept for callers that still say it; the database needs no invalidating. */
    fun invalidate() = Unit

    /**
     * Opens the library, upgrading the database if this build needs to, and
     * brings in any crag file the tables do not hold yet. Slow the first time
     * after an update, so never on the main thread.
     */
    fun open(context: Context) {
        CragDb.prepare(context)
        CragDb.migrateIfNeeded(context, storeDir(context))
        ready = true
    }

    /**
     * Finishes whatever an upgrade left to do: re-deriving every row from the
     * files, then compacting. Long, resumable, and harmless alongside
     * everything else, so it runs after [open] rather than holding it up.
     */
    fun finishUpgrade(context: Context) {
        CragDb.rebuildIfPending(context, storeDir(context))
    }

    /** Rows for a list or pins for a map: names, counts and positions. */
    fun cards(context: Context): List<CragCard> = CragDb.cards(context)

    fun count(context: Context): Int = CragDb.count(context)

    fun has(context: Context, id: String): Boolean = CragDb.has(context, id)

    /**
     * One whole crag by name, for callers that only have the name. Names are
     * not unique; [byId] is the one to use wherever the id is known.
     */
    fun byArea(context: Context, area: String): Crag? =
        CragDb.idForArea(context, area)?.let { byId(context, it) }

    /**
     * One whole crag, climbs and topos and all, read from its own file. The
     * database used to hold a second copy, which doubled the storage and could
     * not be read back at all for a crag past two megabytes.
     */
    fun byId(context: Context, id: String): Crag? {
        val file = fileFor(context, id)
        if (!file.exists()) return null

        return runCatching { file.readText() }.getOrNull()?.let { parseJson(it) }
    }

    /** When the crag's file last changed, so a screen can tell whether a refresh landed. */
    fun stamp(context: Context, id: String): Long = fileFor(context, id).lastModified()

    fun parseJson(json: String): Crag? = runCatching { parse(JSONObject(json)) }.getOrNull()

    /** Deletes every imported crag, its topos and any saved photos. Ticks survive. */
    fun clear(context: Context) {
        synchronized(writeLock) {
            storeDir(context).listFiles().orEmpty().forEach { it.delete() }
            CragDb.clear(context)
        }
        TopoCache.clear(context)
        PhotoCache.clearAll(context)
    }

    /** Drops one crag and its topo photos, so a refresh starts from nothing. */
    fun forget(context: Context, crag: Crag) {
        synchronized(writeLock) {
            fileFor(context, crag.id).delete()
            CragDb.forget(context, crag.id)
        }
        crag.topos.forEach { TopoCache.file(context, it.topoId.toString()).delete() }
    }

    /** Returns the crag it stored, or null when the JSON made no sense. */
    fun save(context: Context, json: String): Crag? = runCatching {
        val crag = parse(JSONObject(json))

        synchronized(writeLock) {
            writeFile(fileFor(context, crag.id), json)
            CragDb.put(context, crag)
        }
        crag
    }.getOrNull()

    /**
     * Written beside and then moved over, so a crash mid-write cannot leave a
     * half file as the only record of a crag.
     */
    internal fun writeFile(file: File, text: String) {
        val partial = File(file.parentFile, file.name + ".part")
        partial.writeText(text)
        if (!partial.renameTo(file)) {
            file.writeText(text)
            partial.delete()
        }
    }

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
            parking = parseParking(root.arrayFor("parking")),
        )
    }

    private fun parseParking(array: JSONArray): List<Parking> =
        (0 until array.length()).mapNotNull { index ->
            val node = array.optJSONObject(index) ?: return@mapNotNull null
            val latitude = node.optDoubleOrNull("latitude") ?: return@mapNotNull null
            val longitude = node.optDoubleOrNull("longitude") ?: return@mapNotNull null

            Parking(node.optString("name"), latitude, longitude)
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

/**
 * One preference-backed set of climb URLs, one per process.
 *
 * Every screen and every page bridge used to build its own copy and write the
 * whole set back. Two at once — a queue batch saving crags on the bridge
 * thread while a logbook sync lands its ticks — each wrote the set it had
 * read, and whichever finished last dropped the other's adds. Here there is
 * one copy, changed under one lock and replaced rather than mutated, so a
 * reader never sees it half-changed, and nothing is written when nothing
 * changed.
 */
internal class UrlStore(private val file: String, private val key: String) {

    @Volatile
    private var urls: Set<String>? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)

    fun all(context: Context): Set<String> =
        urls ?: synchronized(this) { urls ?: load(context) }

    /** Inside the lock only. */
    private fun load(context: Context): Set<String> =
        HashSet(prefs(context).getStringSet(key, emptySet()).orEmpty()).also { urls = it }

    private fun store(context: Context, next: Set<String>) {
        prefs(context).edit().putStringSet(key, next).apply()
        urls = next
    }

    /** How many were new. */
    fun add(context: Context, more: Collection<String>): Int = synchronized(this) {
        val current = urls ?: load(context)
        val fresh = more.filterTo(HashSet()) { it.isNotBlank() && it !in current }
        if (fresh.isEmpty()) return 0

        store(context, HashSet(current).apply { addAll(fresh) })
        fresh.size
    }

    /** How many were there to remove. */
    fun remove(context: Context, gone: Collection<String>): Int = synchronized(this) {
        val current = urls ?: load(context)
        val present = gone.filterTo(HashSet()) { it in current }
        if (present.isEmpty()) return 0

        store(context, HashSet(current).apply { removeAll(present) })
        present.size
    }

    fun replace(context: Context, all: Collection<String>): Int = synchronized(this) {
        val next = all.filterTo(HashSet()) { it.isNotBlank() }
        if (next != (urls ?: load(context))) store(context, next)
        next.size
    }
}

private val wishlistStore = UrlStore("wishlist", "climb_urls")
private val attemptStore = UrlStore("attempts", "climb_urls")
private val tickStore = UrlStore("ticks", "route_urls")
private val toLogStore = UrlStore("to_log", "climb_urls")

/** Climbs on UKC's wishlist, keyed by URL like the ticks. */
class Wishlist(context: Context) {

    private val app = context.applicationContext

    fun has(url: String): Boolean = wishlistStore.all(app).contains(url)

    fun isEmpty(): Boolean = wishlistStore.all(app).isEmpty()

    fun all(): Set<String> = wishlistStore.all(app)

    /** UKC owns this list, so a sync replaces it rather than adding to it. */
    fun replaceWith(urls: Collection<String>): Int = wishlistStore.replace(app, urls)
}

/** Climbs tried but not topped, so they read differently from untouched ones. */
class Attempts(context: Context) {

    private val app = context.applicationContext

    fun has(url: String): Boolean = attemptStore.all(app).contains(url)

    fun addAll(urls: Collection<String>) {
        attemptStore.add(app, urls)
    }
}

/**
 * Climbs sent at the crag and not yet in the logbook.
 *
 * The app never logs anything itself, and at the crag there is often no
 * signal to log with anyway. So the phone keeps the reader's own note — sent,
 * log it later — and the to-log list takes them to each climb's UKC page once
 * there is. A logbook sync that finds the tick clears the note.
 */
class ToLog(context: Context) {

    private val app = context.applicationContext

    fun has(url: String): Boolean = toLogStore.all(app).contains(url)

    fun all(): Set<String> = toLogStore.all(app)

    /** Flips the note, returning whether it is now set. */
    fun toggle(url: String): Boolean =
        if (has(url)) {
            toLogStore.remove(app, listOf(url))
            false
        } else {
            toLogStore.add(app, listOf(url))
            true
        }

    fun remove(url: String) {
        toLogStore.remove(app, listOf(url))
    }
}

/** Ticked climbs, keyed by climb URL so they survive re-imports of a crag. */
class Ticks(context: Context) {

    private val app = context.applicationContext

    fun has(url: String): Boolean = tickStore.all(app).contains(url)

    fun all(): Set<String> = tickStore.all(app)

    fun countIn(crag: Crag): Int {
        val ticked = all()
        return crag.buttresses.sumOf { buttress -> buttress.climbs.count { it.url in ticked } }
    }

    /**
     * The same count for a crag nobody has read yet: its climb URLs come from
     * the index rather than from parsing the crag.
     */
    fun countIn(context: Context, cragId: String): Int {
        val ticked = all()
        return CragDb.climbUrls(context, cragId).count { it in ticked }
    }

    /**
     * Folds in a logbook sync, keeping what earlier syncs already found. A
     * climb the logbook now holds no longer needs logging, so its to-log note
     * goes with it.
     */
    fun addAll(urls: Collection<String>): Int {
        if (urls.isEmpty()) return 0

        toLogStore.remove(app, urls)
        return tickStore.add(app, urls)
    }

    /**
     * Folds in the CSV export, which names climbs rather than linking them.
     * Matching is on crag plus climb name, loosened to ignore case, accents
     * and punctuation, since a logbook entry and a crag page do not always
     * agree on an apostrophe.
     *
     * Only the crags the logbook names are read. A name can stand for more
     * than one climb — a crag listing the same line twice, or two crags
     * sharing a name — so every one of them is ticked, rather than whichever
     * happened to be read last.
     */
    fun addByName(context: Context, entries: List<Pair<String, String>>): Int {
        val wanted = HashMap<String, MutableSet<String>>()
        for ((cragName, climbName) in entries) {
            wanted.getOrPut(loosen(cragName)) { HashSet() }.add(loosen(climbName))
        }

        val idsByArea = HashMap<String, MutableList<String>>()
        for ((id, area) in CragDb.cragNames(context)) {
            val key = loosen(area)
            if (key in wanted) idsByArea.getOrPut(key) { mutableListOf() }.add(id)
        }

        val found = mutableListOf<String>()

        for ((area, ids) in idsByArea) {
            val names = wanted[area] ?: continue

            for ((name, url) in CragDb.climbNamesAt(context, ids)) {
                if (loosen(name) in names) found.add(url)
            }
        }

        return addAll(found)
    }

    private fun loosen(value: String): String = java.text.Normalizer
        .normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("[^a-z0-9]"), "")
}
