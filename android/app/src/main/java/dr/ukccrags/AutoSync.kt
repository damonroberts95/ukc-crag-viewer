package dr.ukccrags

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray

/**
 * The weekly logbook sync.
 *
 * Ticks go stale the moment an ascent is logged on the website, so the app
 * re-reads the logbook once a week. It happens **on opening only** — there is
 * no service and no alarm, so nothing runs while the app is closed — and in a
 * WebView that is never shown, so the crag list can be read while it works.
 *
 * Like every other sync here it only reads: the logbook, the wishlist and the
 * ticklists come back, nothing goes out.
 */
object AutoSync {

    private const val EVERY_MS = 7L * 24 * 60 * 60 * 1000
    private const val KEY_LAST = "last_sync"

    /** Long enough for the CSV and the ticklist walk, which is one request each. */
    private const val TIMEOUT_MS = 180_000L

    /** One at a time, and never a second one behind a screen rotation. */
    private var running = false

    private fun prefs(context: Context) =
        context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    /** Records a sync, so the weekly one does not follow one just done by hand. */
    fun ran(context: Context) {
        prefs(context).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
    }

    /**
     * Syncs if a week has passed, otherwise does nothing. [crags] is the
     * caller's already-loaded library, which the CSV's climb names are matched
     * against. [onAdded] runs on the main thread, only when ticks actually
     * arrived, so the screen can redraw itself.
     */
    fun runIfDue(context: Context, crags: List<Crag>, onAdded: (Int) -> Unit) {
        if (running || crags.isEmpty() || !Session.signedIn(context)) return

        val last = prefs(context).getLong(KEY_LAST, 0L)
        val since = System.currentTimeMillis() - last

        // A clock knocked backwards would otherwise park the sync in the future.
        if (last != 0L && since in 0 until EVERY_MS) return

        run(context, crags, onAdded)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun run(context: Context, crags: List<Crag>, onAdded: (Int) -> Unit) {
        val app = context.applicationContext
        val script = runCatching {
            app.assets.open("extract.js").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return

        running = true

        val web = WebView(app)
        val handler = Handler(Looper.getMainLooper())
        var added = 0
        var settled = false

        fun settle(synced: Boolean) {
            if (settled) return
            settled = true
            running = false

            handler.removeCallbacksAndMessages(null)
            web.destroy()

            // A failed run is not stamped, so the next opening tries again.
            if (synced) ran(app)
            if (added > 0) onAdded(added)
        }

        /** Only the tick half of the page's bridge is wanted; the rest is noise. */
        val bridge = object {
            @JavascriptInterface
            fun saveTickNames(json: String) {
                added += Ticks(app).addByName(crags, namedClimbs(json))
            }

            @JavascriptInterface
            fun saveTicks(json: String) {
                added += Ticks(app).addAll(urls(json))
            }

            @JavascriptInterface
            fun saveWishlist(json: String) {
                Wishlist(app).replaceWith(urls(json))
            }

            @JavascriptInterface
            fun saveLists(json: String) {
                Lists.replaceWith(app, json)
            }

            @JavascriptInterface
            fun ticksDone(found: Int) {
                handler.post { settle(true) }
            }

            @JavascriptInterface
            fun ticksFailed(reason: String) {
                Log.w("UKC", "weekly sync failed: $reason")
                handler.post { settle(false) }
            }

            // Called by the page while it works, or by parts of the script this
            // sync does not use. Nothing here has a screen to report to.
            @JavascriptInterface
            fun ticksProgress(found: Int) = Unit

            @JavascriptInterface
            fun kind(json: String) = Unit

            @JavascriptInterface
            fun throttled(spacingMs: Int) = Unit

            @JavascriptInterface
            fun failed(reason: String) = Unit
        }

        CookieManager.getInstance().setAcceptCookie(true)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(bridge, "Android")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (settled) return

                // The logbook export is fetched from the page, so the sync
                // needs a real UKC page under it to share the origin and the
                // session cookies with.
                web.evaluateJavascript(script) {
                    web.evaluateJavascript(
                        "window.__ukcSyncTicks(\"\", ${Session.userId(app)})",
                        null,
                    )
                }
            }
        }

        handler.postDelayed({ settle(false) }, TIMEOUT_MS)
        web.loadUrl(app.getString(R.string.crag_index_url))
    }

    private fun urls(json: String): List<String> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /** The CSV export's rows: the crag it happened at, and the climb's name. */
    private fun namedClimbs(json: String): List<Pair<String, String>> = runCatching {
        val array = JSONArray(json)

        (0 until array.length()).mapNotNull { index ->
            val node = array.optJSONObject(index) ?: return@mapNotNull null
            node.optString("crag") to node.optString("name")
        }
    }.getOrDefault(emptyList())
}
