package dr.ukccrags

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/** A walking line: the points to draw, how far it is, and whether it follows paths. */
data class WalkRoute(
    val points: List<Pair<Double, Double>>,
    val metres: Double,
    val onPaths: Boolean,
    /** True when only the leg near the destination follows paths. */
    val partial: Boolean = false,
    /** True when the distance ruled out looking for paths at all. */
    val tooFar: Boolean = false,
)

/**
 * Best-effort walking directions, offline once fetched.
 *
 * OpenStreetMap's raster tiles are pictures, so there is no geometry to route
 * on. The paths themselves come from Overpass, but only when asked for and only
 * once per crag: a request per buttress, or a sweep of the whole library, would
 * be abusing a free service. What comes back is cached beside the crags, so the
 * second buttress at a crag — and every later visit, signal or not — is free.
 *
 * Where there is no path near either end, the answer is an honest straight line
 * rather than a route invented out of nothing.
 */
object Walk {

    /** Ways worth walking. Motorways are not a footpath and are left out. */
    private const val HIGHWAYS =
        "path|footway|track|bridleway|steps|cycleway|pedestrian|living_street|" +
            "service|unclassified|residential|tertiary|secondary"

    /** Metres of slack around the corridor, so a path just outside still counts. */
    private const val PADDING = 400.0

    /**
     * Beyond this the walk is not a walk, and the query would be enormous. Sat
     * at home planning a trip, the useful route is not from the sofa — the
     * caller substitutes the crag's own pin as the starting point instead.
     */
    const val MAX_SPAN_METRES = 60000.0

    /**
     * The widest corridor worth asking Overpass for. Past this the box stops
     * being a corridor and becomes a county: the response would run to many
     * megabytes for paths nowhere near either end. Longer walks are answered
     * with the paths around the destination and a straight line into them.
     */
    private const val MAX_QUERY_METRES = 12000.0

    /** Radius of the box used when the corridor is too long to fetch whole. */
    private const val LOCAL_METRES = 2500.0

    /** How far a start or finish may sit from the nearest path before giving up. */
    private const val SNAP_LIMIT_METRES = 400.0

    /** Roughly a metre, which is finer than the data and coarse enough to join ways. */
    private const val GRID = 1e-5

    private fun cacheFile(context: Context, cragId: String): File =
        File(context.filesDir, "walks").apply { mkdirs() }.resolve("$cragId.json")

    fun metresBetween(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
    ): Double {
        val earth = 6_371_000.0
        val dLat = Math.toRadians(toLat - fromLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) *
            sin(dLon / 2) * sin(dLon / 2)

        return earth * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Blocking. Call it off the main thread.
     *
     * [cragId] only names the cache; the corridor is worked out from the ends.
     */
    fun route(
        context: Context,
        cragId: String,
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
    ): WalkRoute {
        val straight = WalkRoute(
            points = listOf(fromLat to fromLon, toLat to toLon),
            metres = metresBetween(fromLat, fromLon, toLat, toLon),
            onPaths = false,
        )

        if (straight.metres > MAX_SPAN_METRES) return straight.copy(tooFar = true)

        // A long approach is fetched around the destination only, and joined to
        // with a straight line: the paths outside the last couple of kilometres
        // are somebody else's road trip.
        val local = straight.metres > MAX_QUERY_METRES

        val ways = if (local) {
            waysAround(context, cragId, toLat, toLon)
        } else {
            waysFor(context, cragId, fromLat, fromLon, toLat, toLon)
        } ?: return straight

        if (ways.isEmpty()) return straight

        if (!local) return alongWays(ways, fromLat, fromLon, toLat, toLon) ?: straight

        // Enter the path network at whatever point faces the way you came from.
        val entry = nearestPoint(ways, fromLat, fromLon) ?: return straight
        val leg = alongWays(ways, entry.first, entry.second, toLat, toLon) ?: return straight

        return WalkRoute(
            points = listOf(fromLat to fromLon) + leg.points,
            metres = metresBetween(fromLat, fromLon, entry.first, entry.second) + leg.metres,
            onPaths = true,
            partial = true,
        )
    }

    /** The point on any way closest to somewhere, for joining a long approach. */
    private fun nearestPoint(
        ways: List<List<Pair<Double, Double>>>,
        lat: Double,
        lon: Double,
    ): Pair<Double, Double>? {
        var best: Pair<Double, Double>? = null
        var bestMetres = Double.MAX_VALUE

        for (way in ways) {
            for (point in way) {
                val metres = metresBetween(lat, lon, point.first, point.second)
                if (metres < bestMetres) {
                    bestMetres = metres
                    best = point
                }
            }
        }

        return best
    }

    /** Paths within [LOCAL_METRES] of a point, cached like any other fetch. */
    private fun waysAround(
        context: Context,
        cragId: String,
        lat: Double,
        lon: Double,
    ): List<List<Pair<Double, Double>>>? {
        val padLat = LOCAL_METRES / 111_320.0
        val padLon = LOCAL_METRES / (111_320.0 * cos(Math.toRadians(lat)))

        return waysIn(context, cragId, lat - padLat, lon - padLon, lat + padLat, lon + padLon)
    }

    /**
     * The cached ways for a crag, fetching them if the corridor is not covered.
     * Null means there was nothing usable and no way to get it.
     */
    private fun waysFor(
        context: Context,
        cragId: String,
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
    ): List<List<Pair<Double, Double>>>? {
        val padLat = PADDING / 111_320.0
        val padLon = PADDING / (111_320.0 * cos(Math.toRadians((fromLat + toLat) / 2)))

        return waysIn(
            context, cragId,
            minOf(fromLat, toLat) - padLat, minOf(fromLon, toLon) - padLon,
            maxOf(fromLat, toLat) + padLat, maxOf(fromLon, toLon) + padLon,
        )
    }

    private fun waysIn(
        context: Context,
        cragId: String,
        south: Double, west: Double, north: Double, east: Double,
    ): List<List<Pair<Double, Double>>>? {
        val stored = read(cacheFile(context, cragId))

        // A cache only counts if one fetch covered the whole corridor; a walk
        // from a different direction needs its own.
        if (stored != null && stored.covers(south, west, north, east)) return stored.lines()

        val fetched = fetch(context, south, west, north, east) ?: return stored?.lines()

        // Kept beside whatever was already held, so approaching from another
        // side does not throw away a perfectly good cache. Both halves are
        // kept: the boxes, because the rectangle around two corridors is not
        // somewhere either of them fetched, and the ways, because widening the
        // bounds over only the new ways left the old corridor looking covered
        // and empty — a straight line where there had been a path.
        val box = doubleArrayOf(south, west, north, east)
        val merged = stored?.plus(box, fetched) ?: Cached(listOf(box), fetched)

        write(cacheFile(context, cragId), merged)
        return merged.lines()
    }

    /** One way from Overpass. An id of -1 was read from a cache that kept none. */
    private class Way(val id: Long, val points: List<Pair<Double, Double>>)

    /**
     * A crag's paths on disk. Each box is `[south, west, north, east]` of one
     * fetch; a corridor is covered only when a single box holds all of it.
     */
    private class Cached(
        val boxes: List<DoubleArray>,
        val ways: List<Way>,
    ) {
        fun covers(s: Double, w: Double, n: Double, e: Double): Boolean = boxes.any {
            s >= it[0] && w >= it[1] && n <= it[2] && e <= it[3]
        }

        fun lines(): List<List<Pair<Double, Double>>> = ways.map { it.points }

        /**
         * Adds one fetch. Overpass hands each way back whole, not clipped to
         * the box, so the same way arrives from every corridor that touches
         * it: ids say which. Ways from a cache too old to carry ids are matched
         * on their geometry instead, which is just as whole.
         */
        fun plus(box: DoubleArray, fetched: List<Way>): Cached {
            val ids = fetched.mapTo(HashSet()) { it.id }
            val shapes = fetched.mapTo(HashSet()) { it.points }

            val kept = ways.filter { old ->
                if (old.id >= 0) old.id !in ids else old.points !in shapes
            }

            // A box inside the new one says nothing the new one does not.
            val others = boxes.filterNot {
                it[0] >= box[0] && it[1] >= box[1] && it[2] <= box[2] && it[3] <= box[3]
            }

            return Cached(others + listOf(box), kept + fetched)
        }
    }

    /**
     * Reads either shape of cache file: the current one, with a list of boxes
     * and an id per way, or the first, with one set of bounds and bare ways.
     * Anything unreadable counts as no cache and is replaced by the next fetch.
     */
    private fun read(file: File): Cached? = runCatching {
        if (!file.exists()) return null

        val root = JSONObject(file.readText())
        val wayArray = root.getJSONArray("ways")
        val ids = root.optJSONArray("ids")?.takeIf { it.length() == wayArray.length() }

        val ways = (0 until wayArray.length()).map { index ->
            val line = wayArray.getJSONArray(index)
            val points = (0 until line.length()).map {
                val point = line.getJSONArray(it)
                point.getDouble(0) to point.getDouble(1)
            }
            Way(ids?.getLong(index) ?: -1L, points)
        }

        val boxArray = root.optJSONArray("boxes")
        val boxes = if (boxArray != null) {
            (0 until boxArray.length()).map { index ->
                val box = boxArray.getJSONArray(index)
                DoubleArray(4) { box.getDouble(it) }
            }
        } else {
            listOf(
                doubleArrayOf(
                    root.getDouble("south"), root.getDouble("west"),
                    root.getDouble("north"), root.getDouble("east"),
                )
            )
        }

        Cached(boxes, ways)
    }.getOrNull()

    private fun write(file: File, cached: Cached) {
        runCatching {
            val ways = JSONArray()
            val ids = JSONArray()

            for (way in cached.ways) {
                val line = JSONArray()
                for ((lat, lon) in way.points) {
                    line.put(JSONArray().put(lat).put(lon))
                }
                ways.put(line)
                ids.put(way.id)
            }

            val boxes = JSONArray()
            for (box in cached.boxes) {
                boxes.put(JSONArray().put(box[0]).put(box[1]).put(box[2]).put(box[3]))
            }

            file.writeText(
                JSONObject()
                    .put("boxes", boxes)
                    .put("ways", ways)
                    .put("ids", ids)
                    .toString()
            )
        }
    }

    /**
     * What this app tells Overpass it is. Overpass asks for something
     * identifiable and blocks what it cannot name. Kept in one place so it can
     * follow the app's own user agent.
     */
    @Suppress("UNUSED_PARAMETER")
    fun userAgent(context: Context): String = "UKC Crag Viewer (dr.ukccrags)"

    /** One Overpass call. `out geom` hands back the shape, so nodes need no second pass. */
    private fun fetch(
        context: Context,
        south: Double, west: Double, north: Double, east: Double,
    ): List<Way>? = runCatching {
        val box = "$south,$west,$north,$east"
        val query = """
            [out:json][timeout:25];
            way["highway"~"^($HIGHWAYS)$"]($box);
            out geom;
        """.trimIndent()

        val connection = (URL("https://overpass-api.de/api/interpreter")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 40000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", userAgent(context))
        }

        connection.outputStream.use {
            it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray())
        }

        if (connection.responseCode != 200) {
            Log.w("UKC", "overpass said ${connection.responseCode}")
            return@runCatching null
        }

        val text = connection.inputStream.use { it.readBytes().decodeToString() }
        connection.disconnect()

        val elements = JSONObject(text).optJSONArray("elements") ?: return@runCatching emptyList()

        (0 until elements.length()).mapNotNull { index ->
            val element = elements.getJSONObject(index)
            val geometry = element.optJSONArray("geometry") ?: return@mapNotNull null

            val line = (0 until geometry.length()).map {
                val node = geometry.getJSONObject(it)
                node.getDouble("lat") to node.getDouble("lon")
            }

            if (line.size < 2) null else Way(element.optLong("id", -1L), line)
        }
    }.getOrElse {
        Log.w("UKC", "overpass fetch failed: $it")
        null
    }

    /**
     * Dijkstra over the way geometry, with straight hops from the reader to the
     * path and from the path to the buttress. Those hops are usually the last
     * few metres of open ground, which no map has a line for anyway.
     */
    private fun alongWays(
        ways: List<List<Pair<Double, Double>>>,
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
    ): WalkRoute? {
        val nodes = ArrayList<Pair<Double, Double>>()
        val index = HashMap<Long, Int>()
        val edges = ArrayList<ArrayList<Pair<Int, Double>>>()

        fun nodeAt(point: Pair<Double, Double>): Int {
            val key = (point.first / GRID).toLong() * 100_000_000L + (point.second / GRID).toLong()

            index[key]?.let { return it }

            nodes.add(point)
            edges.add(ArrayList())
            index[key] = nodes.size - 1
            return nodes.size - 1
        }

        for (way in ways) {
            var previous = nodeAt(way.first())

            for (i in 1 until way.size) {
                val current = nodeAt(way[i])
                if (current == previous) continue

                val length = metresBetween(
                    nodes[previous].first, nodes[previous].second,
                    nodes[current].first, nodes[current].second,
                )

                edges[previous].add(current to length)
                edges[current].add(previous to length)
                previous = current
            }
        }

        if (nodes.isEmpty()) return null

        fun nearest(lat: Double, lon: Double): Pair<Int, Double> {
            var best = -1
            var bestMetres = Double.MAX_VALUE

            nodes.forEachIndexed { at, point ->
                // Cheap rejection first: the full formula on every node adds up.
                if (abs(point.first - lat) > 0.01 || abs(point.second - lon) > 0.02) return@forEachIndexed

                val metres = metresBetween(lat, lon, point.first, point.second)
                if (metres < bestMetres) {
                    bestMetres = metres
                    best = at
                }
            }

            return best to bestMetres
        }

        val (start, startMetres) = nearest(fromLat, fromLon)
        val (finish, finishMetres) = nearest(toLat, toLon)

        if (start < 0 || finish < 0) return null
        if (startMetres > SNAP_LIMIT_METRES || finishMetres > SNAP_LIMIT_METRES) return null

        val distance = DoubleArray(nodes.size) { Double.MAX_VALUE }
        val cameFrom = IntArray(nodes.size) { -1 }
        val queue = java.util.PriorityQueue<Pair<Int, Double>>(compareBy { it.second })

        distance[start] = 0.0
        queue.add(start to 0.0)

        while (queue.isNotEmpty()) {
            val (at, cost) = queue.poll()!!
            if (cost > distance[at]) continue
            if (at == finish) break

            for ((next, length) in edges[at]) {
                val through = cost + length
                if (through >= distance[next]) continue

                distance[next] = through
                cameFrom[next] = at
                queue.add(next to through)
            }
        }

        if (distance[finish] == Double.MAX_VALUE) return null

        val path = ArrayList<Pair<Double, Double>>()
        var at = finish
        while (at != -1) {
            path.add(nodes[at])
            at = cameFrom[at]
        }
        path.reverse()

        val points = ArrayList<Pair<Double, Double>>()
        points.add(fromLat to fromLon)
        points.addAll(path)
        points.add(toLat to toLon)

        return WalkRoute(
            points = points,
            metres = startMetres + distance[finish] + finishMetres,
            onPaths = true,
        )
    }
}
