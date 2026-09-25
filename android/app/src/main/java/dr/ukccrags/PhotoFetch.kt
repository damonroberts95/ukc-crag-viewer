package dr.ukccrags

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saves one crag's photos, in a WebView nobody looks at.
 *
 * The photo lists are POSTs that need each page's own token and the session's
 * Cloudflare clearance, which only a WebView on UKC's origin holds — the same
 * reason the queue reads crags this way. The pictures themselves come down in
 * Kotlin through [PhotoCache].
 *
 * It runs while the crag's screen is open and stops when that screen goes.
 * Nothing is lost by stopping: each climb is struck off as its list lands, so
 * the next run reads only what is left.
 */
object PhotoFetch {

    private const val DELAY_MS = 250

    /** Longest the page may go without a word before the run is called dead. */
    private const val QUIET_MS = 90_000L

    interface Listener {
        /** Climbs read of those asked for, then photos still to download. */
        fun progress(read: Int, total: Int, downloading: Int)
        fun finished(saved: Int, failed: String?)
    }

    private val handler = Handler(Looper.getMainLooper())

    private var web: WebView? = null
    private var host: ViewGroup? = null
    private var listener: Listener? = null

    @Volatile
    private var stopped = false

    /** The crag being read, or null. */
    var cragId: String? = null
        private set

    fun running(): Boolean = cragId != null

    /** Climbs that UKC says have photos and that have not been read yet. */
    fun unread(context: android.content.Context, crag: Crag): List<Climb> {
        val done = PhotoCache.done(context, crag.id)
        return crag.buttresses.flatMap { it.climbs }
            .filter { it.photos > 0 && it.climbId > 0 && it.climbId !in done }
            .distinctBy { it.climbId }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun start(host: ViewGroup, crag: Crag, withClimbs: Boolean, listener: Listener) {
        if (running()) return

        val app = host.context.applicationContext
        val token = PageScript.newToken()
        val script = PageScript.load(app, token, watchKind = false) ?: return

        val done = PhotoCache.done(app, crag.id)
        var climbs = if (withClimbs) unread(app, crag) else emptyList()
        var withCrag = 0L !in done

        // Everything already read: this is a look for anything new since.
        if (!withCrag && climbs.isEmpty()) {
            withCrag = true
            if (withClimbs) {
                climbs = crag.buttresses.flatMap { it.climbs }
                    .filter { it.photos > 0 && it.climbId > 0 }
                    .distinctBy { it.climbId }
            }
        }

        val asked = JSONArray()
        for (climb in climbs) asked.put(JSONObject().put("id", climb.climbId).put("url", climb.url))

        stopped = false
        cragId = crag.id
        this.host = host
        this.listener = listener

        AppLog.add(app, "photos: ${crag.area}, crag gallery ${if (withCrag) "yes" else "no"}, " +
            "${climbs.size} climbs to read")

        val view = WebView(host.context)
        web = view
        host.addView(view, ViewGroup.LayoutParams(1, 1))
        view.alpha = 0f

        val before = PhotoCache.count(app, crag.id)
        var ready = false

        val quiet = Runnable { end(app, crag, before, "UKC went quiet") }
        fun heard() {
            handler.removeCallbacks(quiet)
            handler.postDelayed(quiet, QUIET_MS)
        }

        val bridge = object {
            @JavascriptInterface
            fun photo(key: String, climbId: Long, photoId: String, url: String, caption: String) {
                if (!PageScript.matches(token, key)) return
                // The picture is fetched with the session's cookies, so only
                // from UKC's own hosts; photo ids name files, so digits only.
                if (!PageScript.isImageUrl(url) || photoId.isEmpty() || !photoId.all { it.isDigit() }) {
                    AppLog.add(app, "photos: refused a photo link")
                    return
                }
                PhotoCache.record(app, crag.id, climbId, photoId, caption, url)
            }

            @JavascriptInterface
            fun photosClimbDone(key: String, climbId: Long) {
                if (!PageScript.matches(token, key)) return
                PhotoCache.markDone(app, crag.id, climbId)
            }

            @JavascriptInterface
            fun photosProgress(done: Int, total: Int) {
                handler.post {
                    heard()
                    this@PhotoFetch.listener?.progress(done, total, PhotoCache.queued())
                }
            }

            @JavascriptInterface
            fun photosStopped(): Boolean = stopped

            @JavascriptInterface
            fun photosFinished(key: String) {
                if (!PageScript.matches(token, key)) return
                handler.post {
                    // The lists are in, so silence from here is the downloads,
                    // not a page gone quiet: the watchdog has done its job.
                    handler.removeCallbacks(quiet)
                    whenDownloaded(app, crag, before, climbs.size)
                }
            }

            @JavascriptInterface
            fun photosFailed(reason: String) {
                handler.post { end(app, crag, before, reason) }
            }

            @JavascriptInterface
            fun throttled(spacingMs: Int) {
                AppLog.add(app, "photos: UKC pushed back, spacing now ${spacingMs}ms")
                handler.post { heard() }
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)

        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.addJavascriptInterface(bridge, "Android")

        view.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                handler.post { end(app, crag, before, "the browser engine died") }
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                handler.post { end(app, crag, before, "could not open UKC — ${error?.description}") }
            }

            /** Once, and not into a Cloudflare challenge: it gives way to the real page. */
            override fun onPageFinished(page: WebView?, url: String?) {
                if (ready) return

                view.evaluateJavascript(PageScript.CHALLENGE_CHECK) { challenge ->
                    if (ready || challenge == "true" || web !== view) return@evaluateJavascript
                    ready = true

                    heard()
                    view.evaluateJavascript(script) {
                        view.evaluateJavascript(
                            "window.__ukcSavePhotos(${JSONObject.quote(crag.sourceUrl)}, $withCrag, " +
                                "${JSONObject.quote(asked.toString())}, $DELAY_MS)",
                            null,
                        )
                    }
                }
            }
        }

        listener.progress(0, climbs.size, 0)

        // A challenge that never clears is as stuck as a page that stops talking.
        heard()
        view.loadUrl(app.getString(R.string.crag_index_url))
    }

    /** Asks the page to stop after the climbs it is reading now. */
    fun stop() {
        stopped = true
    }

    /**
     * Forgets the screen it was reporting to. The page is torn down with it,
     * since it lives in that screen's window.
     */
    fun detach() {
        stopped = true
        listener = null
        cragId = null
        handler.removeCallbacksAndMessages(null)
        tearDown()
    }

    /** The lists are in; the pictures are still arriving on their own pool. */
    private fun whenDownloaded(app: android.content.Context, crag: Crag, before: Int, total: Int) {
        tearDown()

        val left = PhotoCache.queued()
        if (left > 0) {
            listener?.progress(total, total, left)
            handler.postDelayed({ whenDownloaded(app, crag, before, total) }, 500)
            return
        }

        end(app, crag, before, null)
    }

    private fun end(app: android.content.Context, crag: Crag, before: Int, failed: String?) {
        handler.removeCallbacksAndMessages(null)
        tearDown()

        val saved = (PhotoCache.count(app, crag.id) - before).coerceAtLeast(0)
        AppLog.add(app, "photos: ${crag.area} — $saved new" + (failed?.let { ", stopped: $it" } ?: ""))

        cragId = null
        val told = listener
        listener = null
        told?.finished(saved, failed)
    }

    private fun tearDown() {
        val view = web ?: return
        web = null
        host?.removeView(view)
        host = null
        view.destroy()
    }
}
