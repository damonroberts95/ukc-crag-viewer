package dr.ukccrags

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
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

/** A parking spot, carrying the name of the crag it serves for its label. */
data class ParkingPin(
    val cragId: String,
    val cragArea: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
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
 * indexed; only opening one crag reads a whole crag, and that comes from its
 * file under `files/crags/`, not from here.
 *
 * Everything in here is derived from those files and can be rebuilt from them,
 * which is what an upgrade or a downgrade does rather than trying to be clever.
 *
 * Plain SQLite, no ORM: a handful of tables and queries, no code generation
 * and nothing new in the build.
 */
object CragDb {

    private const val NAME = "crags.db"

    /*
     * 2 added parking. 3 took the crag JSON out of the crags table — it was a
     * second copy of every file, and a crag past 2 MB could not be read back
     * through a cursor at all — and gave climb names a full-text index.
     */
    private const val VERSION = 3

    /** Characters read per go when salvaging an old JSON column; well under a cursor window. */
    private const val SALVAGE_CHUNK = 256 * 1024

    private class Helper(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

        private val app = context.applicationContext

        init {
            // Lists and the map read while a queue batch writes. Without WAL
            // every read waited for the writer's transaction to finish.
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) = createSchema(db)

        /*
         * Up to 3, in place, so the list keeps its crags while it happens:
         * write out any crag whose file has gone missing (the old JSON column
         * is the last copy of it), rebuild the crags table without that column,
         * index climb names, and leave a note for [CragStore.finishUpgrade] to
         * re-derive every row from the files, which also mends columns older
         * rows were written without. Only the quick part happens in here; the
         * long part runs crag by crag behind the list.
         */
        override fun onUpgrade(db: SQLiteDatabase, from: Int, to: Int) {
            AppLog.add(app, "library: upgrading the database from $from to $to")

            // Android builds SQLite with secure delete on, which would write
            // zeros over every page the dropped JSON occupied. That is the
            // whole of the old library, rewritten for nothing.
            db.rawQuery("PRAGMA secure_delete = OFF", null).use { it.moveToFirst() }

            if (from < 2) createParking(db)

            salvageJson(db)

            try {
                db.execSQL("DROP TABLE IF EXISTS crags_v3")
                db.execSQL(CREATE_CRAGS.replace("CREATE TABLE crags", "CREATE TABLE crags_v3"))
                db.execSQL(
                    """
                    INSERT INTO crags_v3 ($CRAG_COLUMNS)
                    SELECT $CRAG_COLUMNS FROM crags
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE crags")
                db.execSQL("ALTER TABLE crags_v3 RENAME TO crags")

                db.execSQL("DROP INDEX IF EXISTS climbs_name")
                createIndexes(db)
                createFts(db)

                // Search works straight away on what is there; the rebuild
                // then replaces it crag by crag.
                if (hasFts(db)) {
                    db.execSQL("INSERT INTO climbs_fts (docid, name) SELECT rowid, name FROM climbs")
                }

                markRebuild(app, rebuild = true, vacuum = true)
            } catch (e: SQLiteException) {
                // An older table than expected. The files are all written out
                // by now, so start empty and let them fill it back in.
                AppLog.add(app, "library: upgrade in place failed (${e.message}), rebuilding from files")
                dropEverything(db)
                createSchema(db)
                markRebuild(app, rebuild = false, vacuum = true)
            }
        }

        /*
         * A newer build's database, met by an older build. Everything here is
         * derived from the crag files, so starting again is safe where guessing
         * at a future schema is not.
         */
        override fun onDowngrade(db: SQLiteDatabase, from: Int, to: Int) {
            AppLog.add(app, "library: database from version $from, rebuilding it for $to")

            dropEverything(db)
            createSchema(db)

            // Empty tables: bringing the files in is the rebuild.
            markRebuild(app, rebuild = false, vacuum = true)
        }

        /** Writes out every crag the files have lost but the old column still holds. */
        private fun salvageJson(db: SQLiteDatabase) {
            val hasJson = db.rawQuery("PRAGMA table_info(crags)", null).use { cursor ->
                var found = false
                while (cursor.moveToNext()) if (cursor.getString(1) == "json") found = true
                found
            }
            if (!hasJson) return

            val dir = File(app.filesDir, "crags").apply { mkdirs() }
            val ids = db.rawQuery("SELECT id FROM crags", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }

            var written = 0

            for (id in ids) {
                val file = File(dir, "$id.json")
                if (file.exists() && file.length() > 0) continue

                // Read in slices: a whole big crag would not fit a cursor
                // window, which is the very bug this version exists to fix.
                runCatching {
                    val length = db.rawQuery(
                        "SELECT length(json) FROM crags WHERE id = ?", arrayOf(id),
                    ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

                    if (length <= 0L) return@runCatching

                    val text = StringBuilder()
                    var start = 1L

                    while (start <= length) {
                        db.rawQuery(
                            "SELECT substr(json, $start, $SALVAGE_CHUNK) FROM crags WHERE id = ?",
                            arrayOf(id),
                        ).use { if (it.moveToFirst()) text.append(it.getString(0)) }
                        start += SALVAGE_CHUNK
                    }

                    CragStore.writeFile(file, text.toString())
                    written++
                }.onFailure {
                    AppLog.add(app, "library: could not save crag $id out of the database: ${it.message}")
                }
            }

            if (written > 0) AppLog.add(app, "library: wrote $written crag files back out of the database")
        }
    }

    private val CREATE_CRAGS = """
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
            topo_count INTEGER NOT NULL
        )
    """.trimIndent()

    private const val CRAG_COLUMNS =
        "id, area, source_url, latitude, longitude, climb_count, buttress_count, " +
            "located, dominant_type, topo_count"

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(CREATE_CRAGS)

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

        db.execSQL("CREATE INDEX buttresses_crag ON buttresses (crag_id)")
        db.execSQL("CREATE INDEX climbs_crag ON climbs (crag_id)")

        createParking(db)
        createIndexes(db)
        createFts(db)
    }

    private fun createParking(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS parking (
                crag_id TEXT NOT NULL,
                name TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS parking_crag ON parking (crag_id)")
    }

    /*
     * Shaped to the questions asked. The list sorts by name without regard to
     * case, so the index does too, or SQLite sorts the lot every time. The
     * type-and-grade filter and the dropdowns built from it are answered from
     * one covering index rather than from a walk of every climb.
     */
    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS crags_area")
        db.execSQL("CREATE INDEX crags_area ON crags (area COLLATE NOCASE)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS climbs_kind " +
                "ON climbs (type COLLATE NOCASE, grade, grade_score, crag_id)"
        )
    }

    /*
     * Climb names, word by word, so a search is an index lookup rather than a
     * substring test against a hundred thousand rows on every keystroke. The
     * row id is the climb's own, which is how a hit finds its way back.
     * unicode61 folds accents; an SQLite without it still has the simple
     * tokenizer, which folds only case.
     */
    private fun createFts(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS climbs_fts")

        try {
            db.execSQL("CREATE VIRTUAL TABLE climbs_fts USING fts4(name, tokenize=unicode61)")
        } catch (_: SQLiteException) {
            runCatching { db.execSQL("CREATE VIRTUAL TABLE climbs_fts USING fts4(name)") }
        }
    }

    private fun hasFts(db: SQLiteDatabase): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE name = 'climbs_fts' LIMIT 1", null,
    ).use { it.moveToFirst() }

    /** Virtual tables first: dropping one takes its shadow tables with it. */
    private fun dropEverything(db: SQLiteDatabase) {
        fun names(where: String) = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND $where", null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

        names("sql LIKE 'CREATE VIRTUAL%'").forEach { db.execSQL("DROP TABLE IF EXISTS \"$it\"") }
        names("name NOT LIKE 'sqlite_%' AND name <> 'android_metadata'")
            .forEach { db.execSQL("DROP TABLE IF EXISTS \"$it\"") }
    }

    private var helper: Helper? = null

    /** Whether the full-text index exists, learned once per process. */
    @Volatile
    private var fts: Boolean? = null

    @Synchronized
    private fun db(context: Context): SQLiteDatabase {
        val existing = helper ?: Helper(context.applicationContext).also { helper = it }
        return existing.writableDatabase
    }

    private fun ftsReady(database: SQLiteDatabase): Boolean =
        fts ?: hasFts(database).also { fts = it }

    /**
     * Opens the database, running any upgrade. Called from [CragStore.open] on
     * a background thread, so the upgrade happens there and not inside the
     * first query some screen makes on the main thread.
     */
    fun prepare(context: Context) {
        ftsReady(db(context))
    }

    // ---- the rebuild ----

    private fun state(context: Context) =
        context.applicationContext.getSharedPreferences("crag_db", Context.MODE_PRIVATE)

    private fun markRebuild(context: Context, rebuild: Boolean, vacuum: Boolean) {
        state(context).edit()
            .putBoolean(KEY_REBUILD, rebuild)
            .remove(KEY_REBUILD_AFTER)
            .putBoolean(KEY_VACUUM, vacuum || state(context).getBoolean(KEY_VACUUM, false))
            .commit()
    }

    /**
     * Re-derives every row from the crag files, if an upgrade asked for it.
     *
     * One crag at a time, each in its own transaction, so the list and the
     * queue carry on around it; and it notes how far it got, so being killed
     * part way costs the remainder rather than the lot. The rows it replaces
     * stay readable until it reaches them.
     */
    fun rebuildIfPending(context: Context, files: File) {
        val prefs = state(context)
        if (!prefs.getBoolean(KEY_REBUILD, false)) {
            vacuumIfPending(context)
            return
        }

        val after = prefs.getString(KEY_REBUILD_AFTER, "").orEmpty()
        val todo = files.listFiles().orEmpty()
            .filter { it.extension == "json" && it.name > after }
            .sortedBy { it.name }

        AppLog.add(context, "library: re-deriving ${todo.size} crags from their files")

        var done = 0
        todo.forEachIndexed { index, file ->
            synchronized(CragStore.writeLock) {
                // Under the save lock, so the file read here is the newest: a
                // queue batch saving the same crag waits, rather than having
                // its fresh rows replaced by an older copy read just before.
                runCatching { CragStore.parseJson(file.readText()) }.getOrNull()
                    ?.let { put(context, it); done++ }
            }

            if (index % 50 == 49) prefs.edit().putString(KEY_REBUILD_AFTER, file.name).apply()
        }

        forgetOrphans(context, files)

        prefs.edit().putBoolean(KEY_REBUILD, false).remove(KEY_REBUILD_AFTER).commit()
        AppLog.add(context, "library: re-derived $done crags, database holds ${count(context)}")

        vacuumIfPending(context)
    }

    /**
     * A row whose file is gone cannot be opened any more, and would sit in the
     * list doing nothing. It goes back on the queue instead, so the next read
     * restores it from UKC.
     */
    private fun forgetOrphans(context: Context, files: File) {
        val orphans = cards(context).filterNot { File(files, "${it.id}.json").exists() }
        if (orphans.isEmpty()) return

        AppLog.add(context, "library: ${orphans.size} crags had lost their files, queued to read again")
        orphans.forEach { forget(context, it.id) }
        ImportQueue.add(context, orphans.map { Queued(it.area, it.sourceUrl) }, skipHeld = false)
    }

    /**
     * Dropping the old JSON left its pages free inside the file; SQLite only
     * gives them back to the phone on a VACUUM. Once, after the rebuild, when
     * what is left to copy is small.
     */
    private fun vacuumIfPending(context: Context) {
        val prefs = state(context)
        if (!prefs.getBoolean(KEY_VACUUM, false)) return

        // A reader holding the database open makes it fail; the note stays,
        // and the next launch tries again.
        runCatching { db(context).execSQL("VACUUM") }
            .onSuccess {
                AppLog.add(context, "library: database compacted")
                prefs.edit().putBoolean(KEY_VACUUM, false).apply()
            }
            .onFailure { AppLog.add(context, "library: could not compact the database yet: ${it.message}") }
    }

    // ---- writing ----

    /** Anything derived from the whole library is stale once a crag changes. */
    @Volatile
    private var kindsCache: List<Triple<String, String, Double>>? = null

    /** Stores a crag's index rows, replacing whatever was there. */
    fun put(context: Context, crag: Crag) {
        val database = db(context)
        val withFts = ftsReady(database)

        database.beginTransaction()
        try {
            removeRows(database, crag.id, withFts)

            for (spot in crag.parking) {
                database.insert(
                    "parking",
                    null,
                    ContentValues().apply {
                        put("crag_id", crag.id)
                        put("name", spot.name)
                        put("latitude", spot.latitude)
                        put("longitude", spot.longitude)
                    },
                )
            }

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
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )

            val insertClimb = database.compileStatement(
                "INSERT OR REPLACE INTO climbs " +
                    "(url, crag_id, name, grade, grade_score, type, stars) VALUES (?, ?, ?, ?, ?, ?, ?)"
            )

            // A URL held by another crag's row is replaced below, and its name
            // must leave the search with it.
            val dropStale = if (withFts) database.compileStatement(
                "DELETE FROM climbs_fts WHERE docid IN (SELECT rowid FROM climbs WHERE url = ?)"
            ) else null

            val insertName = if (withFts) database.compileStatement(
                "INSERT OR REPLACE INTO climbs_fts (docid, name) VALUES (?, ?)"
            ) else null

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

                    dropStale?.apply {
                        bindString(1, climb.url)
                        executeUpdateDelete()
                    }

                    insertClimb.bindString(1, climb.url)
                    insertClimb.bindString(2, crag.id)
                    insertClimb.bindString(3, climb.name)
                    insertClimb.bindString(4, climb.grade)
                    insertClimb.bindDouble(5, climb.gradeScore)
                    insertClimb.bindString(6, climb.type)
                    insertClimb.bindLong(7, climb.stars.toLong())
                    val rowId = insertClimb.executeInsert()

                    if (rowId > 0 && insertName != null) {
                        insertName.bindLong(1, rowId)
                        insertName.bindString(2, climb.name)
                        insertName.executeInsert()
                    }
                }
            }

            insertClimb.close()
            dropStale?.close()
            insertName?.close()

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
            kindsCache = null
        }
    }

    /** A crag's derived rows, its search names first while its climbs still point at them. */
    private fun removeRows(database: SQLiteDatabase, id: String, withFts: Boolean) {
        if (withFts) {
            database.execSQL(
                "DELETE FROM climbs_fts WHERE docid IN (SELECT rowid FROM climbs WHERE crag_id = ?)",
                arrayOf(id),
            )
        }
        database.delete("climbs", "crag_id = ?", arrayOf(id))
        database.delete("buttresses", "crag_id = ?", arrayOf(id))
        database.delete("parking", "crag_id = ?", arrayOf(id))
    }

    fun forget(context: Context, id: String) {
        val database = db(context)

        database.beginTransaction()
        try {
            removeRows(database, id, ftsReady(database))
            database.delete("crags", "id = ?", arrayOf(id))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
            kindsCache = null
        }
    }

    fun clear(context: Context) {
        val database = db(context)

        database.beginTransaction()
        try {
            if (ftsReady(database)) database.execSQL("DELETE FROM climbs_fts")
            database.delete("climbs", null, null)
            database.delete("buttresses", null, null)
            database.delete("parking", null, null)
            database.delete("crags", null, null)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
            kindsCache = null
        }
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
        SELECT $CRAG_COLUMNS
        FROM crags ORDER BY area COLLATE NOCASE
        """.trimIndent(),
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cardFrom(cursor))
        }
    }

    /**
     * The id of the crag with this name, for callers that only know the name.
     * Names are not unique, so an exact match wins over one differing in case.
     */
    fun idForArea(context: Context, area: String): String? = db(context).rawQuery(
        "SELECT id, area FROM crags WHERE area = ? COLLATE NOCASE",
        arrayOf(area),
    ).use { cursor ->
        var loose: String? = null
        while (cursor.moveToNext()) {
            if (cursor.getString(1) == area) return@use cursor.getString(0)
            if (loose == null) loose = cursor.getString(0)
        }
        loose
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
     * Parking inside a box, for the library map. Bounds go into the SQL for the
     * same reason as [pinsWithin]: bound as text, they would match nothing.
     */
    fun parkingWithin(
        context: Context,
        south: Double,
        north: Double,
        west: Double,
        east: Double,
    ): List<ParkingPin> = parkingWhere(
        context,
        "p.latitude BETWEEN $south AND $north AND p.longitude BETWEEN $west AND $east",
        null,
    )

    /** One crag's parking, for its directions and its own map. */
    fun parking(context: Context, cragId: String): List<ParkingPin> =
        parkingWhere(context, "p.crag_id = ?", arrayOf(cragId))

    private fun parkingWhere(
        context: Context,
        where: String,
        args: Array<String>?,
    ): List<ParkingPin> = db(context).rawQuery(
        """
        SELECT p.crag_id, c.area, p.name, p.latitude, p.longitude
        FROM parking p JOIN crags c ON c.id = p.crag_id
        WHERE $where
        """.trimIndent(),
        args,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ParkingPin(
                        cragId = cursor.getString(0),
                        cragArea = cursor.getString(1),
                        name = cursor.getString(2),
                        latitude = cursor.getDouble(3),
                        longitude = cursor.getDouble(4),
                    )
                )
            }
        }
    }

    /**
     * Climbs matching a query, capped. The cap is the point: a two-letter
     * search against a national library matches tens of thousands of climbs and
     * nobody scrolls past the first screen of them. Ask for one more than will
     * be shown to learn whether it bit.
     *
     * Names are matched word by word from the start of each word, through the
     * full-text index. A crag's name is not matched here: the crag's own row
     * in the results already answers that, and listing every climb at a crag
     * because the crag was named buried the climbs actually looked for.
     */
    fun searchClimbs(
        context: Context,
        query: String,
        type: String,
        grades: Collection<String>,
        limit: Int,
    ): List<ClimbHit> {
        val database = db(context)
        val words = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()

        val filters = StringBuilder()
        val filterArgs = mutableListOf<String>()

        if (type.isNotEmpty()) {
            filters.append(" AND cl.type = ? COLLATE NOCASE")
            filterArgs.add(type)
        }

        if (grades.isNotEmpty()) {
            filters.append(grades.joinToString(", ", " AND cl.grade IN (", ")") { "?" })
            filterArgs.addAll(grades)
        }

        // A single short word may be a grade: "E1" or "7a" is a fair thing to
        // type into a climbing app's search box.
        val gradeLike = query.trim().takeIf { it.length <= 6 && !it.contains(' ') }

        fun run(match: String, args: List<String>): List<ClimbHit> = database.rawQuery(
            """
            SELECT cl.crag_id, c.area, cl.name, cl.grade, cl.type, cl.stars, cl.url
            FROM climbs cl JOIN crags c ON c.id = cl.crag_id
            WHERE ($match)$filters
            ORDER BY cl.stars DESC, cl.name COLLATE NOCASE
            LIMIT $limit
            """.trimIndent(),
            (args + filterArgs).toTypedArray(),
        ).use { hitsFrom(it) }

        if (ftsReady(database)) {
            try {
                val match = words.joinToString(" ") { "$it*" }
                return if (gradeLike != null) {
                    run(
                        "cl.rowid IN (SELECT docid FROM climbs_fts WHERE climbs_fts MATCH ?) " +
                            "OR cl.grade LIKE ? ESCAPE '\\'",
                        listOf(match, likeEscape(gradeLike) + "%"),
                    )
                } else {
                    run("cl.rowid IN (SELECT docid FROM climbs_fts WHERE climbs_fts MATCH ?)", listOf(match))
                }
            } catch (_: SQLiteException) {
                // Falls through to the plain match below.
            }
        }

        val pattern = "%" + likeEscape(query.trim()) + "%"
        return run(
            "cl.name LIKE ? ESCAPE '\\' OR cl.grade LIKE ? ESCAPE '\\'",
            listOf(pattern, likeEscape(query.trim()) + "%"),
        )
    }

    /** So a typed % or _ is a character, not a wildcard. */
    private fun likeEscape(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun hitsFrom(cursor: android.database.Cursor): List<ClimbHit> = buildList {
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

    /**
     * The distinct type, grade and score combinations the library holds.
     *
     * Asked on every return to the list and after every queue batch, and it
     * only changes when a crag does, so it is kept until one is saved.
     */
    fun kinds(context: Context): List<Triple<String, String, Double>> {
        kindsCache?.let { return it }

        return db(context).rawQuery(
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
        }.also { kindsCache = it }
    }

    /**
     * How many of these climbs each crag holds, for the list's ticked counts.
     *
     * Asked by the ticked URLs rather than by crag: a few thousand primary-key
     * lookups once, instead of a query for every row the list binds.
     */
    fun countsByCrag(context: Context, urls: Collection<String>): Map<String, Int> {
        if (urls.isEmpty()) return emptyMap()

        val counts = HashMap<String, Int>()

        for (chunk in urls.chunked(500)) {
            db(context).rawQuery(
                "SELECT crag_id, COUNT(*) FROM climbs WHERE url IN " +
                    chunk.joinToString(", ", "(", ")") { "?" } + " GROUP BY crag_id",
                chunk.toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    counts.merge(cursor.getString(0), cursor.getInt(1), Int::plus)
                }
            }
        }

        return counts
    }

    /** Every crag's id against its name, for matching the logbook's crag names. */
    fun cragNames(context: Context): List<Pair<String, String>> = db(context)
        .rawQuery("SELECT id, area FROM crags", null)
        .use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
        }

    /** Climb names and URLs at these crags only. */
    fun climbNamesAt(context: Context, cragIds: Collection<String>): List<Pair<String, String>> =
        cragIds.toList().chunked(400).flatMap { chunk ->
            db(context).rawQuery(
                "SELECT name, url FROM climbs WHERE crag_id IN " +
                    chunk.joinToString(", ", "(", ")") { "?" },
                chunk.toTypedArray(),
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
            }
        }

    /** Named climbs by URL, for a ticklist's own order. */
    fun climbsByUrl(context: Context, urls: Collection<String>): List<ClimbHit> {
        if (urls.isEmpty()) return emptyList()

        // Asked in chunks: SQLite will not take a parameter list of any length.
        return urls.toList().chunked(400).flatMap { chunk ->
            db(context).rawQuery(
                """
                SELECT cl.crag_id, c.area, cl.name, cl.grade, cl.type, cl.stars, cl.url
                FROM climbs cl JOIN crags c ON c.id = cl.crag_id
                WHERE cl.url IN (${chunk.joinToString(", ") { "?" }})
                """.trimIndent(),
                chunk.toTypedArray(),
            ).use { hitsFrom(it) }
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
     * Brings into the tables any crag file they do not hold yet.
     *
     * Every crag ever imported is a file under `files/crags/`, and those files
     * stay where they are afterwards: they are the only copy of what was
     * scraped, and re-reading four thousand pages to rebuild a table nobody
     * lost would be rude to UKC. The database is derived data.
     *
     * A file that will not parse is noted with its size and date and left
     * alone until it changes, rather than being read and failed again on every
     * launch.
     */
    fun migrateIfNeeded(context: Context, files: File, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val stored = files.listFiles().orEmpty().filter { it.extension == "json" }
        if (stored.isEmpty()) return

        // Only the ones not already in the tables, so a part-finished migration
        // picks up where it stopped.
        val already = db(context).rawQuery("SELECT id FROM crags", null).use { cursor ->
            buildSet<String> { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

        val prefs = state(context)
        val broken = prefs.getStringSet(KEY_BROKEN, emptySet()).orEmpty()
        fun stamp(file: File) = "${file.name}:${file.length()}:${file.lastModified()}"

        val todo = stored.filterNot { it.nameWithoutExtension in already || stamp(it) in broken }
        if (todo.isEmpty()) return

        AppLog.add(context, "library: moving ${todo.size} crags into the database")

        val stillBroken = broken.toMutableSet()

        todo.forEachIndexed { index, file ->
            synchronized(CragStore.writeLock) {
                val crag = runCatching { CragStore.parseJson(file.readText()) }.getOrNull()
                if (crag != null) put(context, crag) else stillBroken.add(stamp(file))
            }

            if (index % 100 == 0) onProgress(index, todo.size)
        }

        if (stillBroken.size != broken.size) {
            AppLog.add(context, "library: ${stillBroken.size - broken.size} crag files could not be read")
            prefs.edit().putStringSet(KEY_BROKEN, stillBroken).apply()
        }

        AppLog.add(context, "library: database holds ${count(context)} crags")
    }

    private const val KEY_REBUILD = "rebuild_pending"
    private const val KEY_REBUILD_AFTER = "rebuild_after"
    private const val KEY_VACUUM = "vacuum_pending"
    private const val KEY_BROKEN = "unreadable_files"
}
