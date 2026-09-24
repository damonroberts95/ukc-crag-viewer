package dr.ukccrags

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** A photo saved for offline, and which climb it shows. 0 is the crag's own gallery. */
data class SavedPhoto(val id: String, val climbId: Long, val caption: String)

/**
 * Crag and climb photos on disk, saved only when asked for.
 *
 * Topos come with every import because a crag is no use without them. Photos
 * are different: a big crag holds a thousand of them, and reading them costs
 * two page requests per climb. So they are saved one crag at a time, from the
 * crag's own screen, and kept apart from the library under
 * `files/photos/<crag id>/` — a refresh of the crag leaves them alone.
 *
 * Each crag's folder holds the pictures by photo id, shared between the crag
 * gallery and a climb when UKC lists one photo under both, and an index of
 * which photo belongs to which climb and which climbs have been read.
 */
object PhotoCache {

    /** Long edge kept. Enough to read a hold off, and about 150KB a photo. */
    private const val MAX_EDGE = 1280

    private fun root(context: Context): File = File(context.filesDir, "photos")

    private fun dir(context: Context, cragId: String): File = File(root(context), cragId)

    fun file(context: Context, cragId: String, photoId: String): File =
        File(dir(context, cragId), "$photoId.jpg")

    private fun indexFile(context: Context, cragId: String): File =
        File(dir(context, cragId), "index.json")

    private class Index(
        val photos: MutableList<SavedPhoto> = mutableListOf(),
        val done: MutableSet<Long> = mutableSetOf(),
    )

    /*
     * A run records hundreds of photos, so the index is held while it is being
     * written to and flushed as each climb completes, rather than rewritten per
     * photo.
     */
    private val open = HashMap<String, Index>()

    @Synchronized
    private fun index(context: Context, cragId: String): Index = open.getOrPut(cragId) {
        val file = indexFile(context, cragId)
        val index = Index()

        if (!file.exists()) return@getOrPut index

        runCatching {
            val root = JSONObject(file.readText())
            val photos = root.optJSONArray("photos") ?: JSONArray()

            for (i in 0 until photos.length()) {
                val node = photos.optJSONObject(i) ?: continue
                index.photos.add(
                    SavedPhoto(
                        id = node.optString("id"),
                        climbId = node.optLong("climb_id"),
                        caption = node.optString("caption"),
                    )
                )
            }

            val done = root.optJSONArray("done") ?: JSONArray()
            for (i in 0 until done.length()) index.done.add(done.optLong(i))
        }

        index
    }

    @Synchronized
    private fun flush(context: Context, cragId: String) {
        val index = open[cragId] ?: return

        val photos = JSONArray()
        for (photo in index.photos) {
            photos.put(
                JSONObject()
                    .put("id", photo.id)
                    .put("climb_id", photo.climbId)
                    .put("caption", photo.caption)
            )
        }

        dir(context, cragId).mkdirs()
        indexFile(context, cragId).writeText(
            JSONObject()
                .put("photos", photos)
                .put("done", JSONArray(index.done.toList()))
                .toString()
        )
    }

    /** Notes a photo against its climb and starts its download if it is not already here. */
    @Synchronized
    fun record(context: Context, cragId: String, climbId: Long, photoId: String, caption: String, url: String) {
        val index = index(context, cragId)

        if (index.photos.none { it.id == photoId && it.climbId == climbId }) {
            index.photos.add(SavedPhoto(photoId, climbId, caption))
        }

        if (!file(context, cragId, photoId).exists()) enqueue(context, cragId, photoId, url)
    }

    @Synchronized
    fun markDone(context: Context, cragId: String, climbId: Long) {
        index(context, cragId).done.add(climbId)
        flush(context, cragId)
    }

    /** Climbs whose photo list has been read, 0 standing for the crag's gallery. */
    @Synchronized
    fun done(context: Context, cragId: String): Set<Long> = index(context, cragId).done.toSet()

    /** The photos of one climb, or of the crag's gallery for 0, that are on disk. */
    @Synchronized
    fun photos(context: Context, cragId: String, climbId: Long): List<SavedPhoto> =
        index(context, cragId).photos.filter {
            it.climbId == climbId && file(context, cragId, it.id).exists()
        }

    /** Every photo on disk for this crag, crag gallery first. */
    @Synchronized
    fun allPhotos(context: Context, cragId: String): List<SavedPhoto> =
        index(context, cragId).photos
            .filter { file(context, cragId, it.id).exists() }
            .distinctBy { it.id }
            .sortedBy { if (it.climbId == 0L) 0 else 1 }

    fun count(context: Context, cragId: String): Int =
        dir(context, cragId).listFiles().orEmpty().count { it.extension == "jpg" }

    fun bytes(context: Context, cragId: String): Long =
        dir(context, cragId).listFiles().orEmpty().sumOf { it.length() }

    fun bytes(context: Context): Long =
        root(context).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    @Synchronized
    fun clear(context: Context, cragId: String) {
        open.remove(cragId)
        dir(context, cragId).deleteRecursively()
    }

    @Synchronized
    fun clearAll(context: Context) {
        open.clear()
        root(context).deleteRecursively()
    }

    // ---- downloading ----

    /* Same arrangement as the topos: never on the page's thread. */
    private val pool = Executors.newFixedThreadPool(4)
    private val pending = AtomicInteger(0)

    fun queued(): Int = pending.get()

    private fun enqueue(context: Context, cragId: String, photoId: String, url: String) {
        val app = context.applicationContext
        pending.incrementAndGet()

        pool.execute {
            try {
                val target = file(app, cragId, photoId)
                if (!target.exists()) TopoCache.saveImage(app, url, target, MAX_EDGE)
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    /** Reads a saved photo, downscaled to roughly [maxEdge] on its long side. */
    fun load(context: Context, cragId: String, photoId: String, maxEdge: Int = MAX_EDGE): Bitmap? {
        val file = file(context, cragId, photoId)
        if (!file.exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2

        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
