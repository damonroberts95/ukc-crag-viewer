package dr.ukccrags

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** A crag as a list or a map needs it: enough to draw a row or a pin. */
data class CragCard(
    val id: String,
    val area: String,
    val sourceUrl: String,
    val latitude: Double?,
    val longitude: Double?,
    val climbCount: Int,
    val buttressCount: Int,
    val locatedButtresses: Int,
    /** What most of its climbs are, so a pin can be coloured without reading it. */
    val dominantType: String,
    /** How many topo photos it has, so a sheet can offer them without reading it. */
    val topoCount: Int,
) {
    val hasPin: Boolean get() = latitude != null && longitude != null

    fun metresFrom(from: android.location.Location?): Float? {
        if (from == null || latitude == null || longitude == null) return null

        val there = android.location.Location("crag").apply {
            latitude = this@CragCard.latitude
            longitude = this@CragCard.longitude
        }

        return from.distanceTo(there)
    }
}

/** A buttress pin, without its crag's climbs in tow. */
data class ButtressPin(
    val cragId: String,
    val cragArea: String,
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
    val climbCount: Int,
    /** True when this is really the crag's pin, UKC having published none. */
    val approximate: Boolean = false,
)

/** A climb found by a search, with the crag it is at. */
data class ClimbHit(
    val cragId: String,
    val cragArea: String,
    val name: String,
    val grade: String,
    val type: String,
    val stars: Int,
    val url: String,
)

/**
 * The library, in a database.
 *
 * It used to be a JSON file per crag, all of them parsed into memory on first
 * use and held there. That was fine for a few hundred crags and fatal at four
 * thousand: a crag list showing names and counts was holding every climb,
 * every description and every topo line in the library, and the heap ran out.
 *
 * So the shape of the question decides what is read. A list or a map asks for
 * cards and pins, which are columns; a search asks the climbs table, which is
 * indexed; only opening one crag reads a whole crag, and that is one row. The
 * original JSON is kept in that row, so nothing that was scraped is lost and a
 * crag can still be handed to the parser whole.
 *
 * Plain SQLite, no ORM: three tables, a handful of queries, no code generation
 * and nothing new in the build.
 */
object CragDb {

    private const val NAME = "crags.db"
    private const val VERSION = 1

    private class Helper(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE crags (
                    id TEXT PRIMARY KEY,
                    area TEXT NOT NULL,
                    source_url TEXT NOT NULL,
                    latitude REAL,
                    longitude REAL,
                    climb_count INTEGER NOT NULL,
                    buttress_count INTEGER NOT NULL,
                    located INTEGER NOT NULL,
                    dominant_type TEXT NOT NULL,
                    topo_count INTEGER NOT NULL,
                    json TEXT NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE buttresses (
                    crag_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    latitude REAL,
                    longitude REAL,
                    climb_count INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE climbs (
                    url TEXT PRIMARY KEY,
                    crag_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    grade TEXT NOT NULL,
                    grade_score REAL NOT NULL,
                    type TEXT NOT NULL,
                    stars INTEGER NOT NULL
                )
                """.trimIndent()
            )

            // Sorting the list by name, drawing pins by area, and searching
            // climbs are the three things done constantly.
            db.execSQL("CREATE INDEX crags_area ON crags (area)")
            db.execSQL("CREATE INDEX buttresses_crag ON buttresses (crag_id)")
            db.execSQL("CREATE INDEX climbs_crag ON climbs (crag_id)")
            db.execSQL("CREATE INDEX climbs_name ON climbs (name)")
        }

        override fun onUpgrade(db: SQLiteDatabase, from: Int, to: Int) = Unit
    }

    private var helper: Helper? = null

    @Synchronized
    private fun db(context: Context): SQLiteDatabase {
        val existing = helper ?: Helper(context.applicationContext).also { helper = it }
        return existing.writableDatabase
    }

    // ---- writing ----

    /**
     * Stores a crag, replacing whatever was there. The JSON is kept as given:
     * the tables are an index over it, not a substitute for it.
     */
    fun put(context: Context, crag: Crag, json: String) {
        val database = db(context)

        database.beginTransaction()
        try {
            database.delete("climbs", "crag_id = ?", arrayOf(crag.id))
            database.delete("buttresses", "crag_id = ?", arrayOf(crag.id))

            database.insertWithOnConflict(
                "crags",
                null,
                ContentValues().apply {
                    put("id", crag.id)
                    put("area", crag.area)
                    put("source_url", crag.sourceUrl)
                    put("latitude", crag.latitude)
                    put("longitude", crag.longitude)
                    put("climb_count", crag.climbCount)
                    put("buttress_count", crag.buttresses.size)
                    put("located", crag.locatedButtresses)
                    put("dominant_type", crag.dominantType())
                    put("topo_count", crag.topos.size)
                    put("json", json)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )

            for (buttress in crag.buttresses) {
                database.insert(
                    "buttresses",
                    null,
                    ContentValues().apply {
                        put("crag_id", crag.id)
                        put("name", buttress.name)
                        put("latitude", buttress.latitude)
                        put("longitude", buttress.longitude)
                        put("climb_count", buttress.climbs.size)
                    },
                )

                for (climb in buttress.climbs) {
                    if (climb.url.isBlank()) continue

                    database.insertWithOnConflict(
                        "climbs",
                        null,
                        ContentValues().apply {
                            put("url", climb.url)
                            put("crag_id", crag.id)
                            put("name", climb.name)
                            put("grade", climb.grade)
                            put("grade_score", climb.gradeScore)
                            put("type", climb.type)
                            put("stars", climb.stars)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
            }

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun forget(context: Context, id: String) {
        val database = db(context)

        database.delete("climbs", "crag_id = ?", arrayOf(id))
        database.delete("buttresses", "crag_id = ?", arrayOf(id))
        database.delete("crags", "id = ?", arrayOf(id))
    }

    fun clear(context: Context) {
        val database = db(context)

        database.delete("climbs", null, null)
        database.delete("buttresses", null, null)
        database.delete("crags", null, null)
    }

    // ---- reading ----

    fun count(context: Context): Int =
        db(context).rawQuery("SELECT COUNT(*) FROM crags", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun has(context: Context, id: String): Boolean =
        db(context).rawQuery("SELECT 1 FROM crags WHERE id = ? LIMIT 1", arrayOf(id)).use {
            it.moveToFirst()
        }

    /** Every crag as a row's worth, in the order a list wants them. */
    fun cards(context: Context): List<CragCard> = db(context).rawQuery(
        """
        SELECT id, area, source_url, latitude, longitude,
               climb_count, buttress_count, located, dominant_type, topo_count
        FROM crags ORDER BY area COLLATE NOCASE
        """.trimIndent(),
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cardFrom(cursor))
        }
    }

    fun card(context: Context, area: String): CragCard? = db(context).rawQuery(
        """
        SELECT id, area, source_url, latitude, longitude,
               climb_count, buttress_count, located, dominant_type, topo_count
        FROM crags WHERE area = ? LIMIT 1
        """.trimIndent(),
        arrayOf(area),
    ).use { if (it.moveToFirst()) cardFrom(it) else null }

    /** The whole crag, parsed from the JSON kept beside its index. */
    fun full(context: Context, id: String): String? =
        db(context).rawQuery("SELECT json FROM crags WHERE id = ? LIMIT 1", arrayOf(id)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun fullByArea(context: Context, area: String): String? =
        db(context).rawQuery("SELECT json FROM crags WHERE area = ? LIMIT 1", arrayOf(area)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    /**
     * Buttress pins inside a box, for the map's detailed mode.
     *
     * The bounds go into the SQL rather than into bound parameters on purpose:
     * `rawQuery` binds every argument as text, and SQLite does not compare text
     * to a REAL column numerically — so a box passed as parameters matched
     * nothing at all, and every buttress pin vanished the moment the map zoomed
     * in far enough to want them. They are doubles, so there is nothing to
     * escape.
     *
     * The coordinates come back coalesced with the crag's own, so a buttress
     * UKC never placed still arrives somewhere.
     */
    fun pinsWithin(
        context: Context,
        south: Double,
        north: Double,
        west: Double,
        east: Double,
    ): List<ButtressPin> = db(context).rawQuery(
        """
        SELECT b.crag_id, c.area, b.name,
               COALESCE(b.latitude, c.latitude), COALESCE(b.longitude, c.longitude),
               b.climb_count, b.latitude IS NULL OR b.longitude IS NULL
        FROM buttresses b JOIN crags c ON c.id = b.crag_id
        WHERE COALESCE(b.latitude, c.latitude) BETWEEN $south AND $north
          AND COALESCE(b.longitude, c.longitude) BETWEEN $west AND $east
        """.trimIndent(),
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ButtressPin(
                        cragId = cursor.getString(0),
                        cragArea = cursor.getString(1),
                        name = cursor.getString(2),
                        latitude = if (cursor.isNull(3)) null else cursor.getDouble(3),
                        longitude = if (cursor.isNull(4)) null else cursor.getDouble(4),
                        climbCount = cursor.getInt(5),
                        approximate = cursor.getInt(6) == 1,
                    )
                )
            }
        }
    }

    /**
     * Climbs matching a query, capped. The cap is the point: a two-letter
     * search against a national library matches tens of thousands of climbs and
     * nobody scrolls past the first screen of them.
     */
    fun searchClimbs(
        context: Context,
        query: String,
        type: String,
        grades: Collection<String>,
        limit: Int,
    ): List<ClimbHit> {
        val where = StringBuilder("(cl.name LIKE ? OR c.area LIKE ? OR cl.grade LIKE ?)")
        val args = mutableListOf("%$query%", "%$query%", "%$query%")

        if (type.isNotEmpty()) {
            where.append(" AND cl.type = ? COLLATE NOCASE")
            args.add(type)
        }

        if (grades.isNotEmpty()) {
            where.append(grades.joinToString(", ", " AND cl.grade IN (", ")") { "?" })
            args.addAll(grades)
        }

        return db(context).rawQuery(
            """
            SELECT cl.crag_id, c.area, cl.name, cl.grade, cl.type, cl.stars, cl.url
            FROM climbs cl JOIN crags c ON c.id = cl.crag_id
            WHERE $where
            ORDER BY cl.stars DESC, cl.name COLLATE NOCASE
            LIMIT $limit
            """.trimIndent(),
            args.toTypedArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ClimbHit(
                            cragId = cursor.getString(0),
                            cragArea = cursor.getString(1),
                            name = cursor.getString(2),
                            grade = cursor.getString(3),
                            type = cursor.getString(4),
                            stars = cursor.getInt(5),
                            url = cursor.getString(6),
                        )
                    )
                }
            }
        }
    }

    /** Crag ids holding a climb of this type and any of these grades. */
    fun cragsHolding(context: Context, type: String, grades: Collection<String>): Set<String> {
        if (type.isEmpty() && grades.isEmpty()) return emptySet()

        val where = StringBuilder("1 = 1")
        val args = mutableListOf<String>()

        if (type.isNotEmpty()) {
            where.append(" AND type = ? COLLATE NOCASE")
            args.add(type)
        }

        if (grades.isNotEmpty()) {
            where.append(grades.joinToString(", ", " AND grade IN (", ")") { "?" })
            args.addAll(grades)
        }

        return db(context).rawQuery(
            "SELECT DISTINCT crag_id FROM climbs WHERE $where",
            args.toTypedArray(),
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    /** The distinct type, grade and score combinations the library holds. */
    fun kinds(context: Context): List<Triple<String, String, Double>> = db(context).rawQuery(
        """
        SELECT DISTINCT type, grade, grade_score FROM climbs
        WHERE grade <> '' ORDER BY grade_score
        """.trimIndent(),
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(Triple(cursor.getString(0), cursor.getString(1), cursor.getDouble(2)))
            }
        }
    }

    /** Every climb's URL against its loosened crag and climb name, for tick matching. */
    fun climbUrlsByName(context: Context): List<Triple<String, String, String>> = db(context)
        .rawQuery(
            """
            SELECT c.area, cl.name, cl.url FROM climbs cl JOIN crags c ON c.id = cl.crag_id
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }

    /** Named climbs by URL, for a ticklist's own order. */
    fun climbsByUrl(context: Context, urls: List<String>): List<ClimbHit> {
        if (urls.isEmpty()) return emptyList()

        // Asked in chunks: SQLite will not take a parameter list of any length.
        return urls.chunked(400).flatMap { chunk ->
            db(context).rawQuery(
                """
                SELECT cl.crag_id, c.area, cl.name, cl.grade, cl.type, cl.stars, cl.url
                FROM climbs cl JOIN crags c ON c.id = cl.crag_id
                WHERE cl.url IN (${chunk.joinToString(", ") { "?" }})
                """.trimIndent(),
                chunk.toTypedArray(),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            ClimbHit(
                                cragId = cursor.getString(0),
                                cragArea = cursor.getString(1),
                                name = cursor.getString(2),
                                grade = cursor.getString(3),
                                type = cursor.getString(4),
                                stars = cursor.getInt(5),
                                url = cursor.getString(6),
                            )
                        )
                    }
                }
            }
        }
    }

    /** Buttress pins for one crag. */
    fun pins(context: Context, cragId: String): List<ButtressPin> = db(context).rawQuery(
        """
        SELECT b.crag_id, c.area, b.name, b.latitude, b.longitude, b.climb_count
        FROM buttresses b JOIN crags c ON c.id = b.crag_id WHERE b.crag_id = ?
        """.trimIndent(),
        arrayOf(cragId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ButtressPin(
                        cragId = cursor.getString(0),
                        cragArea = cursor.getString(1),
                        name = cursor.getString(2),
                        latitude = if (cursor.isNull(3)) null else cursor.getDouble(3),
                        longitude = if (cursor.isNull(4)) null else cursor.getDouble(4),
                        climbCount = cursor.getInt(5),
                    )
                )
            }
        }
    }

    /** Climb URLs for one crag, so a ticked count needs no parsing. */
    fun climbUrls(context: Context, cragId: String): List<String> = db(context).rawQuery(
        "SELECT url FROM climbs WHERE crag_id = ?",
        arrayOf(cragId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    private fun cardFrom(cursor: android.database.Cursor) = CragCard(
        id = cursor.getString(0),
        area = cursor.getString(1),
        sourceUrl = cursor.getString(2),
        latitude = if (cursor.isNull(3)) null else cursor.getDouble(3),
        longitude = if (cursor.isNull(4)) null else cursor.getDouble(4),
        climbCount = cursor.getInt(5),
        buttressCount = cursor.getInt(6),
        locatedButtresses = cursor.getInt(7),
        dominantType = cursor.getString(8),
        topoCount = cursor.getInt(9),
    )

    // ---- moving in ----

    /**
     * Brings the JSON files into the database, once.
     *
     * Every crag ever imported is a file under `files/crags/`, and those files
     * stay where they are afterwards: they are the only copy of what was
     * scraped, and re-reading four thousand pages to rebuild a table nobody
     * lost would be rude to UKC. The database is derived data.
     */
    fun migrateIfNeeded(context: Context, files: File, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val stored = files.listFiles().orEmpty().filter { it.extension == "json" }
        if (stored.isEmpty()) return

        // Only the ones not already in the tables, so a part-finished migration
        // picks up where it stopped.
        val already = db(context).rawQuery("SELECT id FROM crags", null).use { cursor ->
            buildSet<String> { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

        val todo = stored.filterNot { it.nameWithoutExtension in already }
        if (todo.isEmpty()) return

        AppLog.add(context, "library: moving ${todo.size} crags into the database")

        todo.forEachIndexed { index, file ->
            runCatching {
                val json = file.readText()
                CragStore.parseJson(json)?.let { put(context, it, json) }
            }

            if (index % 100 == 0) onProgress(index, todo.size)
        }

        AppLog.add(context, "library: database holds ${count(context)} crags")
    }
}
