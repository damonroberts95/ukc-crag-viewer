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

/**
 * Reads the queue, a batch at a time, in a WebView nobody looks at.
 *
 * This is the other half of [ImportQueue]. A region's worth of crags is no
 * longer one long run holding a screen open: batches are fetched whenever the
 * app is open, each one saved and struck off the list as it lands, so stopping
 * costs at most one batch and starting again needs no memory of where it was.
 *
 * The same gentle pacing as before — the page's own worker pool, scattered
 * waits, a shared hold when UKC pushes back. What changed is only how much is
 * attempted at once.
 *
 * Not a foreground service: this runs while the app is open, and stops being
 * given time shortly after it is not. That is the trade for not holding a
 * notification the reader cannot dismiss.
 */
object QueueDrain {

    /** Small on purpose: a batch is the most that a kill can cost. */
    private const val BATCH = 40

    private const val DELAY_MS = 250
    private const val WORKERS = 6

    /** One drain at a time, however many screens ask for one. */
    private var running = false

    fun busy(): Boolean = running

    /**
     * Starts reading if there is anything to read and nothing already reading.
     * [onBatch] fires on the main thread after each batch, so a list on screen
     * can show what arrived.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun start(context: Context, onBatch: () -> Unit = {}) {
        // Every reason for not starting is worth saying: "nothing is
        // happening" is the hardest thing to diagnose after the fact.
        with(ImportQueue) {
            if (context.queuePaused) {
                AppLog.add(context, "queue: paused, not reading")
                return
            }
        }

        if (running) {
            AppLog.add(context, "queue: already reading")
            return
        }

        val waiting = ImportQueue.size(context)

        if (waiting == 0) return

        AppLog.add(context, "queue: starting, $waiting crags waiting")

        val app = context.applicationContext
        val script = runCatching {
            app.assets.open("extract.js").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return

        running = true
        ImportState.running = true

        val handler = Handler(Looper.getMainLooper())
        val web = WebView(app)

        var batch: List<Queued> = emptyList()
        var ready = false

        // The bar has to describe the whole job. Reporting each batch's own
        // progress made it fill and empty forty crags at a time, which reads as
        // a stuck or looping import rather than a steady one.
        var planned = 0
        var leftAtBatch = 0

        fun stop() {
            running = false
            ImportState.running = false
            handler.removeCallbacksAndMessages(null)
            web.destroy()
            ImportProgress.clear(app)
        }

        /**
         * Photos are queued as the pages are read and land afterwards. Starting
         * the next batch on top of them would keep a pool of downloads running
         * that nobody is waiting for.
         */
        fun whenPhotosLand(then: () -> Unit) {
            if (TopoCache.queued() <= 0) {
                then()
                return
            }

            handler.postDelayed({ whenPhotosLand(then) }, 400)
        }

        /** Hands the page the next batch, or finishes if there is none left. */
        fun feed() {
            with(ImportQueue) { if (app.queuePaused) { stop(); return } }

            batch = ImportQueue.next(app, BATCH)

            if (batch.isEmpty()) {
                val held = CragStore.load(app).size
                AppLog.add(app, "queue: finished, library holds $held crags")
                ImportProgress.done(
                    app,
                    app.getString(R.string.queue_done),
                    app.resources.getQuantityString(R.plurals.crags_found, held, held),
                )
                running = false
                ImportState.running = false
                handler.removeCallbacksAndMessages(null)
                web.destroy()
                handler.post { onBatch() }
                return
            }

            leftAtBatch = ImportQueue.size(app)
            if (planned == 0) planned = leftAtBatch

            ImportProgress.show(
                app,
                app.getString(R.string.queue_running),
                app.resources.getQuantityString(
                    R.plurals.crags_left, leftAtBatch, leftAtBatch,
                ),
                planned - leftAtBatch,
                planned,
            )

            web.evaluateJavascript(
                "window.__ukcRefreshCrags(" +
                    "${org.json.JSONObject.quote(ImportQueue.asJson(batch))}, $DELAY_MS, $WORKERS)",
                null,
            )
        }

        /** Only the parts of the page's bridge a batch can reach. */
        val bridge = object {
            @JavascriptInterface
            fun saveCrag(json: String) {
                val crag = CragStore.save(app, json) ?: return
                val climbs = crag.buttresses.flatMap { it.climbs }

                // A signed-in read states the reader's own ascents, same as an
                // import from the browser screen does.
                Ticks(app).addAll(climbs.filter { it.ticked }.map { it.url })
                Attempts(app).addAll(
                    climbs.filter { it.attempted && !it.ticked }.map { it.url }
                )
            }

            @JavascriptInterface
            fun finished(ok: Int, failed: Int) {
                AppLog.add(app, "queue: batch done, $ok read, $failed failed, " +
                    "${(ImportQueue.size(app) - batch.size).coerceAtLeast(0)} left")

                // Struck off whether or not each one worked: a crag that cannot
                // be read will not read any better on the next pass, and a
                // queue that never shrinks never ends.
                ImportQueue.drop(app, batch)
                CragStore.invalidate()

                handler.post {
                    onBatch()
                    whenPhotosLand { feed() }
                }
            }

            @JavascriptInterface
            fun cragFailed(name: String, url: String, reason: String) {
                AppLog.add(app, "queue: could not read $name — $reason")
            }

            @JavascriptInterface
            fun failed(reason: String) {
                AppLog.add(app, "queue: STOPPED — $reason")
                handler.post { stop() }
            }

            @JavascriptInterface
            fun progress(done: Int, total: Int, name: String) {
                // Where this batch has got to, counted against the whole queue.
                val left = (leftAtBatch - done).coerceAtLeast(0)

                ImportProgress.show(
                    app,
                    app.getString(R.string.queue_running),
                    app.resources.getQuantityString(R.plurals.crags_left, left, left),
                    (planned - left).coerceIn(0, planned),
                    planned,
                )
            }

            // Present so the page can call them; nothing here has a screen.
            @JavascriptInterface
            fun kind(json: String) = Unit

            @JavascriptInterface
            fun throttled(spacingMs: Int) {
                AppLog.add(app, "queue: UKC pushed back, spacing now ${spacingMs}ms")
            }

            /**
             * Topo photos sit behind signed, expiring URLs, so they have to be
             * fetched while the page is in hand. Queued crags need them as much
             * as imported ones did — a topo with no picture is no use at a crag.
             */
            @JavascriptInterface
            fun fetchTopoImage(topoId: String, url: String): Boolean {
                TopoCache.enqueue(app, topoId, url)
                return true
            }

            @JavascriptInterface
            fun saveTicks(json: String) = Unit

            @JavascriptInterface
            fun ticksDone(found: Int) = Unit

            @JavascriptInterface
            fun ticksFailed(reason: String) = Unit
        }

        CookieManager.getInstance().setAcceptCookie(true)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(bridge, "Android")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (ready) return
                ready = true

                // The pages are read from this page's own origin, with its
                // cookies, exactly as the browser screen does it.
                web.evaluateJavascript(script) { feed() }
            }
        }

        web.loadUrl(app.getString(R.string.crag_index_url))
    }
}
