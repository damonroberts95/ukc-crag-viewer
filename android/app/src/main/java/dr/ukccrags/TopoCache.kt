package dr.ukccrags

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Topo photos on disk, so a crag can be browsed at the boulder with no signal.
 *
 * UKC serves the photos from signed, expiring URLs, so there is nothing worth
 * storing as a link: the pixels are captured during import, from inside the
 * page's own session, and written here.
 */
object TopoCache {

    private fun dir(context: Context): File =
        File(context.filesDir, "topos").apply { mkdirs() }

    fun file(context: Context, topoId: String): File = File(dir(context), "$topoId.jpg")

    fun isCached(context: Context, topoId: String): Boolean = file(context, topoId).exists()

    fun isCached(context: Context, topo: Topo): Boolean =
        isCached(context, topo.topoId.toString())

    fun cachedCount(context: Context, crag: Crag): Int =
        crag.topos.count { isCached(context, it) }

    fun clear(context: Context) {
        dir(context).listFiles().orEmpty().forEach { it.delete() }
    }

    fun bytes(context: Context): Long = dir(context).listFiles().orEmpty().sumOf { it.length() }

    /** Longest edge kept, which holds a topo to a few hundred KB. */
    private const val MAX_EDGE = 1600

    /*
     * All page script runs on one thread, so a blocking bridge call stalls
     * every import worker while a photo comes down. Downloads go to their own
     * pool instead, and the import only waits for them at the very end.
     */
    private val pool = Executors.newFixedThreadPool(4)
    private val pending = AtomicInteger(0)

    /** Photos still coming down. */
    fun queued(): Int = pending.get()

    /** Starts a download and returns at once. */
    fun enqueue(context: Context, topoId: String, url: String) {
        val app = context.applicationContext
        pending.incrementAndGet()

        pool.execute {
            try {
                download(app, topoId, url)
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    /**
     * Downloads a topo photo. The link is signed and short-lived, so this has
     * to happen while the import still holds it. Blocking; the JavaScript
     * bridge calls it off the UI thread.
     */
    fun download(
        context: Context,
        topoId: String,
        url: String,
    ): Boolean = saveImage(context, url, file(context, topoId), MAX_EDGE).also {
        if (!it) Log.w("UKC", "topo $topoId download failed")
    }

    /**
     * Fetches a picture from UKC's image host with the session's cookies and
     * writes it to [target] as a JPEG no longer than [maxEdge] on its long
     * side. Shared with [PhotoCache], whose links are signed the same way.
     * The photo is kept exactly as UKC stores it: topo line coordinates are
     * the rotated ones, and TopoView undoes that as it draws.
     */
    fun saveImage(context: Context, url: String, target: File, maxEdge: Int): Boolean = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", WebSettings.getDefaultUserAgent(context))
            setRequestProperty("Referer", "https://www.ukclimbing.com/")
            CookieManager.getInstance().getCookie(url)?.let {
                setRequestProperty("Cookie", it)
            }
        }

        val bytes = connection.inputStream.use { it.readBytes() }
        connection.disconnect()

        if (bytes.isEmpty()) return@runCatching false

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2

        val bitmap = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return@runCatching false

        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")

        partial.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        bitmap.recycle()
        partial.renameTo(target)

        target.exists()
    }.getOrElse {
        Log.w("UKC", "image download failed: $it")
        false
    }

    /** Reads a cached photo, downscaled to roughly [maxEdge] on its long side. */
    fun load(context: Context, topo: Topo, maxEdge: Int = 2048): Bitmap? {
        val file = file(context, topo.topoId.toString())
        if (!file.exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2

        return BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}
