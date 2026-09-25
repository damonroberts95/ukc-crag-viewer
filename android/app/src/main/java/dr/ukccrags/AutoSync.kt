package dr.ukccrags

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

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
 *
 * Never alongside the queue: two hidden readers in one renderer is the crash
 * the browser screen already stands the queue down for. A sync that falls due
 * while the queue is reading holds it, waits for the batch in hand, runs, and
 * lets the queue carry on after.
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
     * Syncs if a week has passed, otherwise does nothing. [onAdded] runs on the
     * main thread, only when ticks actually arrived, so the screen can redraw
     * itself. [host] is only somewhere to sit until a screen is in front.
     */
    fun runIfDue(
        context: Context,
        host: android.view.ViewGroup?,
        onAdded: (Int) -> Unit,
    ) {
        if (running || !Settings.weeklySync(context)) return
        if (CragStore.count(context) == 0 || !Session.signedIn(context)) return

        val last = prefs(context).getLong(KEY_LAST, 0L)
        val since = System.currentTimeMillis() - last

        // A clock knocked backwards would otherwise park the sync in the future.
        if (last != 0L && since in 0 until EVERY_MS) return

        running = true

        // Held from here, so a queue that is reading stands down after its
        // batch, and one that is not does not start underneath the sync.
        QueueDrain.holdWhileBrowsing(true)

        if (QueueDrain.busy()) {
            AppLog.add(context, "weekly logbook sync: waiting for the queue's batch")
        }

        val app = context.applicationContext
        QueueDrain.whenIdle { run(app, host, onAdded) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun run(
        app: Context,
        host: android.view.ViewGroup?,
        onAdded: (Int) -> Unit,
    ) {
        val token = PageScript.newToken()
        val script = PageScript.load(app, token, watchKind = false)

        if (script == null) {
            running = false
            QueueDrain.holdWhileBrowsing(false)
            return
        }

        // In the window, not floating free: a WebView that was never attached
        // does not reliably finish loading a page, and this one would then wait
        // for a load that never lands. Parked, it follows the screen in front.
        val wrapper = MutableContextWrapper(app)
        val web = WebView(wrapper)
        web.alpha = 0f
        App.park(web, wrapper, host?.takeIf { it.isAttachedToWindow })

        val handler = Handler(Looper.getMainLooper())
        var added = 0
        var settled = false
        var started = false

        fun settle(synced: Boolean) {
            if (settled) return
            settled = true
            running = false

            handler.removeCallbacksAndMessages(null)
            App.unpark(web)
            web.destroy()
            QueueDrain.holdWhileBrowsing(false)

            // A failed run is not stamped, so the next opening tries again.
            if (synced) ran(app)
            if (added > 0) onAdded(added)
        }

        /** Only the tick half of the page's bridge is wanted; the rest is noise. */
        val bridge = object {
            @JavascriptInterface
            fun saveTickNames(key: String, json: String) {
                if (!PageScript.matches(token, key)) return
                added += Ticks(app).addByName(app, namedClimbs(json))
            }

            @JavascriptInterface
            fun saveTicks(key: String, json: String) {
                if (!PageScript.matches(token, key)) return
                added += Ticks(app).addAll(urls(json))
            }

            @JavascriptInterface
            fun saveWishlist(key: String, json: String) {
                if (!PageScript.matches(token, key)) return
                Wishlist(app).replaceWith(urls(json))
            }

            @JavascriptInterface
            fun saveLists(key: String, json: String, complete: Boolean) {
                if (!PageScript.matches(token, key)) return
                saveTicklists(app, json, complete)
            }

            @JavascriptInterface
            fun ticksDone(key: String, found: Int) {
                if (!PageScript.matches(token, key)) return
                AppLog.add(app, "weekly logbook sync: $found ticks, $added new")
                handler.post { settle(true) }
            }

            @JavascriptInterface
            fun ticksFailed(reason: String) {
                AppLog.add(app, "weekly logbook sync failed: $reason")
                handler.post { settle(false) }
            }

            // Called by the page while it works. Nothing here has a screen.
            @JavascriptInterface
            fun ticksProgress(found: Int) = Unit
        }

        CookieManager.getInstance().setAcceptCookie(true)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(bridge, "Android")

        web.webViewClient = object : WebViewClient() {

            /** An unclaimed renderer death would take the whole app with it. */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                AppLog.add(app, "weekly logbook sync: the browser engine died")
                handler.post { settle(false) }
                return true
            }

            /**
             * The logbook export is fetched from the page, so the sync needs a
             * real UKC page under it to share the origin and the session
             * cookies with. Once: a page can finish twice, and a Cloudflare
             * challenge finishes before the page it gives way to.
             */
            override fun onPageFinished(view: WebView?, url: String?) {
                if (settled || started) return

                web.evaluateJavascript(PageScript.CHALLENGE_CHECK) { challenge ->
                    if (settled || started || challenge == "true") return@evaluateJavascript
                    started = true

                    web.evaluateJavascript(script) {
                        web.evaluateJavascript(
                            "window.__ukcSyncTicks(\"\", ${Session.userId(app)})",
                            null,
                        )
                    }
                }
            }
        }

        handler.postDelayed({ settle(false) }, TIMEOUT_MS)
        web.loadUrl(app.getString(R.string.crag_index_url))
    }

    /**
     * Stores a ticklist sync. A complete read replaces what is here, since UKC
     * owns the lists. An incomplete one — a list page that failed, or the cap
     * reached — merges: the lists it did read are brought up to date and the
     * rest keep their last good copy, where replacing used to drop them.
     */
    fun saveTicklists(context: Context, json: String, complete: Boolean) {
        val fresh = runCatching { JSONArray(json) }.getOrNull() ?: return

        if (complete) {
            Lists.replaceWith(context, fresh.toString())
            return
        }

        val merged = LinkedHashMap<String, JSONObject>()

        for (list in Lists.load(context)) {
            merged[list.url] = JSONObject()
                .put("name", list.name)
                .put("url", list.url)
                .put("climbs", JSONArray(list.climbs))
        }

        for (i in 0 until fresh.length()) {
            val node = fresh.optJSONObject(i) ?: continue
            val url = node.optString("url")
            if (url.isNotBlank()) merged[url] = node
        }

        Lists.replaceWith(context, JSONArray(merged.values).toString())
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
