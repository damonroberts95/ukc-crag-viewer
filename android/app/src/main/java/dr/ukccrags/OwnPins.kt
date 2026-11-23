package dr.ukccrags

import android.content.Context
import android.util.Xml
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import org.xmlpull.v1.XmlPullParser

/** One pin from the reader's own map. */
data class OwnPin(val name: String, val latitude: Double, val longitude: Double, val note: String)

/** One folder (a My Maps layer) of pins. Layers are how such maps split an area into crags. */
data class OwnFolder(val name: String, val pins: List<OwnPin>)

/**
 * Pins from the reader's own Google My Maps, laid over UKC's crags.
 *
 * UKC places about half of all buttresses, and for a boulder field like Bovey
 * Woods that half is the difference between finding a block and walking past
 * it. Locals keep better maps than UKC does, so one is carried here and its
 * pins are matched onto the crags already in the library.
 *
 * The map does not change, so it ships as `assets/own_pins.kml`, exported
 * once from its public link — no fetching, no account, and it works with no
 * signal from the first run. The crag files under `files/crags/` are never
 * changed: the pins are applied each time a crag is read ([dress]), so a
 * build without them puts every crag back as UKC has it.
 *
 * Names on hand-made maps are short and loose — "Tilted Heart B", "Knobbly Rf
 * B", "BOTBOB B" — so matching is by words after expanding the usual
 * shorthand, forgiving a typo, and reading an acronym against a name's
 * initials. A pin that matches no buttress still goes on the map, as a
 * buttress of its own with no climbs.
 */
object OwnPins {

    @Volatile
    private var app: Context? = null

    /** What the KML holds, or null before it is first read. */
    @Volatile
    private var folders: List<OwnFolder>? = null

    @Volatile
    private var title: String = ""

    /** Which crags the folders went to, worked out for the crags near the pins. */
    private class Plan(
        val nearby: Set<String>,
        val folders: Map<String, List<OwnFolder>>,
        /** The pins each crag gets: its folders', less any a neighbour names better. */
        val pins: Map<String, List<OwnPin>>,
        val parking: Map<String, List<Parking>>,
    )

    @Volatile
    private var plan: Plan? = null

    private const val PREFS = "own_pins"
    private const val KEY_APPLIED = "applied"
    private const val KEY_TOUCHED = "touched"

    /** Raised whenever matching changes, so the next start re-derives with it. */
    private const val MATCHER = 2

    /** In `assets/`. A build without it simply has no pins. */
    private const val ASSET = "own_pins.kml"

    /** A folder with no matching name goes to the nearest crag within this. */
    private const val FOLDER_REACH_M = 2_000.0

    /** A car park serves the nearest crag within this. */
    private const val PARKING_REACH_M = 3_000.0

    /** Below this a pin and a buttress are different things. */
    private const val MATCH_FLOOR = 0.6

    /** The bar for a pin to cross to a neighbouring crag's buttress. */
    private const val AWAY_FLOOR = 0.75

    /** Called from [App.onCreate], so [dress] can read the pins from anywhere. */
    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun text(context: Context): String? =
        runCatching { context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()

    private fun load(context: Context): List<OwnFolder> {
        folders?.let { return it }
        synchronized(this) {
            folders?.let { return it }
            val (name, read) = text(context)?.let { runCatching { parseKml(it) }.getOrNull() }
                ?: ("" to emptyList())
            title = name
            folders = read
            return read
        }
    }

    /**
     * The index rows were derived with whatever pins the last build carried.
     * When this build carries different ones — or none — the crags near either
     * set are re-derived, once. Off the main thread, after the library opens.
     */
    fun applyIfChanged(context: Context) {
        // The matcher's version too: a build that matches better must redo
        // the rows even when the map itself is unchanged.
        val now = text(context)?.let { "$MATCHER:${it.hashCode()}" } ?: ""
        val prefs = prefs(context)
        if (prefs.getString(KEY_APPLIED, null) == now) return

        val before = prefs.getStringSet(KEY_TOUCHED, emptySet()).orEmpty()

        runCatching { reindex(context, before) }
            .onFailure { AppLog.add(context, "own pins: could not apply: ${it.message}") }
            .onSuccess { touched ->
                prefs.edit().putString(KEY_APPLIED, now).putStringSet(KEY_TOUCHED, touched).apply()
            }
    }

    /**
     * Re-derives the index rows of every crag the pins touched before or touch
     * now, so the map and the search agree with them. Returns the ones touched
     * now, for next time.
     */
    private fun reindex(context: Context, before: Set<String>): Set<String> {
        val now = load(context)
        plan = null

        val touching = candidates(context, now).map { it.id }.toSet()

        for (id in before + touching) {
            synchronized(CragStore.writeLock) {
                CragStore.byId(context, id)?.let { CragDb.put(context, it) }
            }
        }

        report(context)
        return touching
    }

    // ---- reading KML ----

    /** The document's name, and its folders. Pins outside any folder make one named after the map. */
    internal fun parseKml(text: String): Pair<String, List<OwnFolder>> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(text.reader())

        var documentName = ""
        val folderNames = ArrayDeque<String>()
        val byFolder = LinkedHashMap<String, MutableList<OwnPin>>()

        var inPlacemark = false
        var placeName = ""
        var note = ""
        var coordinates = ""
        var isPoint = false
        var awaitingFolderName = false

        while (true) {
            when (parser.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.START_TAG -> when (parser.name) {
                    "Folder" -> { folderNames.addLast(""); awaitingFolderName = true }
                    "Placemark" -> {
                        inPlacemark = true; placeName = ""; note = ""; coordinates = ""; isPoint = false
                    }
                    "Point" -> if (inPlacemark) isPoint = true
                    "name" -> parser.nextText().trim().also { value ->
                        when {
                            inPlacemark -> placeName = value
                            awaitingFolderName && folderNames.isNotEmpty() -> {
                                folderNames.removeLast(); folderNames.addLast(value); awaitingFolderName = false
                            }
                            documentName.isEmpty() && folderNames.isEmpty() -> documentName = value
                        }
                    }
                    "description" -> if (inPlacemark) note = plain(parser.nextText())
                    "coordinates" -> if (inPlacemark && isPoint) coordinates = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "Folder" -> { folderNames.removeLastOrNull(); awaitingFolderName = false }
                    "Placemark" -> {
                        inPlacemark = false
                        val parts = coordinates.split(",")
                        val longitude = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
                        val latitude = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                        if (isPoint && latitude != null && longitude != null && placeName.isNotBlank()) {
                            val folder = folderNames.lastOrNull()?.ifBlank { null } ?: documentName
                            byFolder.getOrPut(folder) { mutableListOf() }
                                .add(OwnPin(placeName, latitude, longitude, note))
                        }
                    }
                }
            }
        }
        return documentName to byFolder.map { (name, pins) -> OwnFolder(name, pins) }
    }

    private fun plain(html: String): String =
        html.replace(Regex("(?i)<br\\s*/?>"), " ").replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").trim()

    // ---- matching onto crags ----

    private fun isParking(folder: OwnFolder) = folder.name.lowercase().contains("park")

    /** Crags with a pin within reach of any of these pins. */
    private fun candidates(context: Context, from: List<OwnFolder>): List<CragDb.Place> {
        val pins = from.flatMap { it.pins }
        if (pins.isEmpty()) return emptyList()

        val pad = 0.03
        return CragDb.placesWithin(
            context,
            pins.minOf { it.latitude } - pad, pins.maxOf { it.latitude } + pad,
            pins.minOf { it.longitude } - pad * 1.7, pins.maxOf { it.longitude } + pad * 1.7,
        )
    }

    /**
     * The reader's pins applied to [crag]: positions for the buttresses they
     * name, the rest as buttresses of their own, and their car parks. Cheap
     * for any crag nowhere near them, which is nearly all.
     */
    fun dress(crag: Crag): Crag {
        val context = app ?: return crag
        val all = load(context)
        if (all.isEmpty() || !crag.hasPin) return crag

        val pins = all.flatMap { it.pins }
        val pad = 0.03
        if (crag.latitude!! !in (pins.minOf { it.latitude } - pad)..(pins.maxOf { it.latitude } + pad) ||
            crag.longitude!! !in (pins.minOf { it.longitude } - pad * 1.7)..(pins.maxOf { it.longitude } + pad * 1.7)
        ) return crag

        val current = planFor(context, all, crag)
        val mine = current.pins[crag.id].orEmpty()
        val parks = current.parking[crag.id].orEmpty()
        if (mine.isEmpty() && parks.isEmpty()) return crag

        val (buttresses, _) = place(crag.buttresses, mine)

        val parking = crag.parking + parks.filter { park ->
            crag.parking.none { metres(it.latitude, it.longitude, park.latitude, park.longitude) < 40 }
        }

        return crag.copy(buttresses = buttresses, parking = parking)
    }

    /**
     * Worked out once for the crags near the pins, and again only when a crag
     * turns up there that it has not seen — a fresh import, say.
     */
    private fun planFor(context: Context, all: List<OwnFolder>, crag: Crag): Plan {
        plan?.let { if (crag.id in it.nearby) return it }

        synchronized(this) {
            plan?.let { if (crag.id in it.nearby) return it }

            val places = candidates(context, all).filter { it.id != crag.id } +
                CragDb.Place(crag.id, crag.area, crag.latitude!!, crag.longitude!!)

            return assign(context, all, places, crag).also { plan = it }
        }
    }

    /**
     * Folders to crags by name first: every word of the folder's name in the
     * crag's ("Lower Shaptor" → "Bovey Woods - Lower Shaptor"). A folder no
     * crag names goes to the nearest crag no folder named — which is how
     * "Shaptor Rock" finds plain "Bovey Woods", the sector UKC files under the
     * wood's own name.
     */
    private fun assign(
        context: Context,
        all: List<OwnFolder>,
        places: List<CragDb.Place>,
        reading: Crag? = null,
    ): Plan {
        val byCrag = HashMap<String, MutableList<OwnFolder>>()
        val leftover = mutableListOf<OwnFolder>()

        for (folder in all.filterNot(::isParking)) {
            val words = words(folder.name, drop = FOLDER_NOISE)
            val named = places
                .filter { place ->
                    val theirs = words(place.area, drop = FOLDER_NOISE)
                    words.isNotEmpty() && words.all { word -> theirs.any { same(word, it) } }
                }
                .minByOrNull { words(it.area, drop = FOLDER_NOISE).size }

            if (named != null) byCrag.getOrPut(named.id) { mutableListOf() }.add(folder)
            else leftover += folder
        }

        for (folder in leftover) {
            val (lat, lon) = centre(folder.pins) ?: continue
            val nearest = places
                .filter { it.id !in byCrag }
                .map { it to metres(lat, lon, it.latitude, it.longitude) }
                .filter { it.second <= FOLDER_REACH_M }
                .minByOrNull { it.second }
                ?.first ?: continue
            byCrag.getOrPut(nearest.id) { mutableListOf() }.add(folder)
        }

        val pins = reallot(context, byCrag, reading)

        // Car parks to the nearest crag that has the reader's pins, else any.
        val served = places.filter { it.id in byCrag }.ifEmpty { places }
        val parking = HashMap<String, MutableList<Parking>>()
        for (pin in all.filter(::isParking).flatMap { it.pins }) {
            val nearest = served
                .map { it to metres(pin.latitude, pin.longitude, it.latitude, it.longitude) }
                .filter { it.second <= PARKING_REACH_M }
                .minByOrNull { it.second }?.first ?: continue
            val label = if (pin.note.isBlank()) pin.name else "${pin.name} · ${pin.note}"
            parking.getOrPut(nearest.id) { mutableListOf() }.add(Parking(label, pin.latitude, pin.longitude))
        }

        return Plan(places.map { it.id }.toSet(), byCrag, pins, parking)
    }

    /**
     * A hand-made map's layers need not follow UKC's split of the wood: the
     * Rock Copse layer holds the Wilderness and Pipe boulders, which UKC
     * files under Lower Shaptor. So once each crag has matched its own
     * folders, the pins left over are offered to the buttresses its
     * neighbours left unplaced, and move to whichever names them — held to a
     * stricter bar than at home, since a neighbour's buttress is the less
     * likely meaning.
     *
     * A pin naming two things — "Bez's / Double Crack B" — is split into one
     * per name first, both at the same spot.
     */
    private fun reallot(
        context: Context,
        byCrag: Map<String, List<OwnFolder>>,
        reading: Crag?,
    ): Map<String, List<OwnPin>> {
        val pins = byCrag.mapValues { (_, folders) -> folders.flatMap { it.pins }.flatMap(::split) }

        // UKC's own buttresses, with climbs: what is being read is in hand,
        // and the rest come from their files without the pins laid on.
        val ukc = byCrag.keys.associateWith { id ->
            (if (reading?.id == id) reading else CragStore.rawById(context, id))
                ?.buttresses?.filter { it.climbs.isNotEmpty() }.orEmpty()
        }

        val leftover = mutableListOf<Pair<String, OwnPin>>()
        val open = mutableListOf<Pair<String, Buttress>>()
        for ((id, mine) in pins) {
            val made = place(ukc[id].orEmpty(), mine).second
            val usedPins = made.map { it.first }.toSet()
            val usedButtresses = made.map { it.second }.toSet()
            mine.filter { it !in usedPins }.forEach { leftover += id to it }
            ukc[id].orEmpty().filter { it !in usedButtresses }.forEach { open += id to it }
        }

        val moves = buildList {
            for ((from, pin) in leftover) for ((to, buttress) in open) {
                if (to == from) continue
                val score = score(pin.name, buttress.name)
                if (score >= AWAY_FLOOR) add(Triple(from to pin, to to buttress, score))
            }
        }.sortedByDescending { it.third }

        val result = pins.mapValues { it.value.toMutableList() }
        val moved = HashSet<OwnPin>()
        val taken = HashSet<Buttress>()
        for ((source, target, _) in moves) {
            val (from, pin) = source
            val (to, buttress) = target
            if (pin in moved || buttress in taken) continue
            moved += pin
            taken += buttress
            result[from]?.remove(pin)
            result[to]?.add(pin)
        }
        return result
    }

    /** "Bez's / Double Crack B" → "Bez's B" and "Double Crack B", at one spot. */
    private fun split(pin: OwnPin): List<OwnPin> {
        val parts = pin.name.split(Regex("\\s*/\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return listOf(pin)

        // A trailing kind ("B", "Wl") written once applies to every part.
        val kind = Regex("\\s+(\\S+)$").find(parts.last())?.groupValues?.get(1)
            ?.takeIf { it.lowercase() in SHORT }
        return parts.mapIndexed { index, part ->
            val named = if (kind != null && index < parts.size - 1 && !part.endsWith(" $kind")) "$part $kind" else part
            pin.copy(name = named)
        }
    }

    /**
     * Each pin to at most one buttress and back, best pairs first, so "Mini B?"
     * twice cannot both land on the same block. Returns the buttresses with
     * the pins applied and any pins left over added, and the pairs made.
     */
    private fun place(
        buttresses: List<Buttress>,
        pins: List<OwnPin>,
    ): Pair<List<Buttress>, List<Triple<OwnPin, Buttress, Double>>> {
        if (pins.isEmpty()) return buttresses to emptyList()

        val pairs = buildList {
            pins.forEachIndexed { p, pin ->
                buttresses.forEachIndexed { b, buttress ->
                    val score = score(pin.name, buttress.name)
                    if (score >= MATCH_FLOOR) add(Triple(p, b, score))
                }
            }
        }.sortedByDescending { it.third }

        val usedPins = HashSet<Int>()
        val at = HashMap<Int, OwnPin>()
        val made = mutableListOf<Triple<OwnPin, Buttress, Double>>()

        for ((p, b, score) in pairs) {
            if (p in usedPins || b in at) continue
            usedPins += p
            at[b] = pins[p]
            made += Triple(pins[p], buttresses[b], score)
        }

        // The reader's pin wins over UKC's: a hand-placed boulder on a local's
        // map is the better guess of the two.
        val placed = buttresses.mapIndexed { index, buttress ->
            at[index]?.let { buttress.copy(latitude = it.latitude, longitude = it.longitude) } ?: buttress
        }

        val extra = pins.filterIndexed { p, _ -> p !in usedPins }.map {
            Buttress(name = expand(it.name), latitude = it.latitude, longitude = it.longitude, climbs = emptyList())
        }

        return (placed + extra) to made
    }

    /** What matched and what did not, per crag, into the log — the only way to see a near miss. */
    private fun report(context: Context) {
        val all = load(context)
        if (all.isEmpty()) {
            AppLog.add(context, "own pins: none")
            return
        }
        AppLog.add(context, "own pins: \"$title\", ${all.sumOf { it.pins.size }} pins in ${all.size} folders")

        val places = candidates(context, all)
        val current = assign(context, all, places)
        plan = current

        for ((id, pins) in current.pins) {
            val mine = current.folders[id].orEmpty()
            val crag = CragStore.rawById(context, id) ?: continue
            val raw = crag.copy(buttresses = crag.buttresses.filter { it.climbs.isNotEmpty() })
            val (_, made) = place(raw.buttresses, pins)
            val matchedPins = made.map { it.first }.toSet()
            val matchedButtresses = made.map { it.second.name }.toSet()

            AppLog.add(
                context,
                "own pins: ${crag.area} ← ${mine.joinToString { it.name }}: " +
                    "${made.size} of ${pins.size} pins on ${raw.buttresses.size} buttresses. " +
                    "Matched: " + made.joinToString { "${it.first.name} = ${it.second.name} (${"%.2f".format(it.third)})" } +
                    ". Pins alone: " + pins.filter { it !in matchedPins }.joinToString { it.name } +
                    ". Buttresses unplaced: " +
                    raw.buttresses.filter { it.name !in matchedButtresses }.joinToString { it.name },
            )
        }

        all.filterNot(::isParking)
            .filter { folder -> current.folders.values.none { folder in it } }
            .forEach { AppLog.add(context, "own pins: folder ${it.name} matched no crag in the library") }

        current.parking.forEach { (id, spots) ->
            val area = places.firstOrNull { it.id == id }?.area ?: id
            AppLog.add(context, "own pins: ${spots.size} car parks to $area")
        }
    }

    // ---- names ----

    /** Shorthand seen on hand-made maps, by whole word. */
    private val SHORT = mapOf(
        "b" to "boulder", "bs" to "boulders", "bldr" to "boulder",
        "wl" to "wall", "twr" to "tower", "rf" to "roof", "rm" to "room",
        "btrs" to "buttress", "btr" to "buttress", "bttrs" to "buttress",
        "clstr" to "cluster", "dble" to "double", "pncke" to "pancake",
        "ftre" to "feature", "dvn" to "devon", "sent" to "sentinel",
        "ovhg" to "overhang",
    )

    /** Words that say what a thing is, not which one. */
    private val NOISE = setOf("boulder", "boulders", "block", "the", "a", "and", "of", "area")

    private val FOLDER_NOISE = setOf("the", "and", "of", "area", "crag", "sector")

    /** "Knobbly Rf B" → "Knobbly Roof Boulder", for a pin shown under its own name. */
    internal fun expand(name: String): String =
        Regex("[A-Za-z]+").replace(name) { match ->
            val long = SHORT[match.value.lowercase()] ?: return@replace match.value
            // A lone capital is shorthand; a lone "a" or "s" in a sentence is not.
            if (match.value.length == 1 && match.value[0].isLowerCase()) match.value
            else long.replaceFirstChar { it.uppercase() }
        }

    private fun words(name: String, drop: Set<String> = NOISE): List<String> =
        name.lowercase()
            .replace("&", " and ")
            .replace("'", "").replace("’", "")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .map { SHORT[it] ?: it }
            .map { if (it.length > 3 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }
            .filter { it !in drop }

    /** Initials of every word, shorthand and all: "Back of the Back of Beyond" → "botbob". */
    private fun initials(name: String): String =
        name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            .joinToString("") { it.take(1) }

    /**
     * How alike a pin's name and a buttress's are, 0 to 1. Each half of a
     * "Long Wall - Shaptor" or "Bez's / Double Crack" is tried as well as
     * the whole, since either half may be the name UKC uses.
     */
    internal fun score(pinName: String, buttressName: String): Double {
        val theirs = words(buttressName)
        if (theirs.isEmpty()) return 0.0

        val tries = listOf(pinName) + pinName.split(Regex("\\s[-–/]\\s|\\s/|/\\s|\\(|\\)"))
            .map { it.trim() }.filter { it.isNotEmpty() }

        return tries.maxOf { attempt ->
            val ours = words(attempt.replace("?", ""))
            when {
                ours.isEmpty() -> 0.0
                ours == theirs -> 1.0
                ours.joinToString("") == theirs.joinToString("") -> 0.97
                // "Submarine" for "Sub": one word each, one the start of the other.
                ours.size == 1 && theirs.size == 1 && minOf(ours[0].length, theirs[0].length) >= 3 &&
                    (ours[0].startsWith(theirs[0]) || theirs[0].startsWith(ours[0])) -> 0.7
                ours.size == 1 && ours[0].length >= 3 &&
                    (ours[0] == initials(buttressName) ||
                        ours[0] == initials(words(buttressName, drop = setOf("boulder")).joinToString(" "))) -> 0.9
                else -> overlap(ours, theirs)
            }
        }
    }

    private fun overlap(ours: List<String>, theirs: List<String>): Double {
        val matched = ours.count { word -> theirs.any { same(word, it) } }
        if (matched == 0) return 0.0
        val dice = 2.0 * matched / (ours.size + theirs.size)
        return when {
            matched == ours.size -> 0.75 + 0.2 * dice
            // Every word of UKC's name in the pin's: "Kidney Stone" for "The Kidney".
            theirs.all { word -> ours.any { same(word, it) } } -> 0.7 + 0.2 * dice
            else -> 0.8 * dice
        }
    }

    /**
     * One typo allowed — a letter wrong, missing, extra, or two swapped
     * ("Mantle" for "Mantel") — in a word long enough to have one. Not in
     * four letters: "Lost" and "Last" are different boulders.
     */
    private fun same(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 5 || b.length < 5 || abs(a.length - b.length) > 1) return false
        return editDistance(a, b) <= 1
    }

    /** Edit distance counting a swap of neighbours as one edit. */
    private fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
        return d[a.length][b.length]
    }

    // ---- geometry ----

    private fun centre(pins: List<OwnPin>): Pair<Double, Double>? =
        if (pins.isEmpty()) null else pins.sumOf { it.latitude } / pins.size to pins.sumOf { it.longitude } / pins.size

    /** Flat-earth metres: plenty at the scale of one wood. */
    private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val y = (lat2 - lat1) * 111_320.0
        val x = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(x * x + y * y)
    }
}
