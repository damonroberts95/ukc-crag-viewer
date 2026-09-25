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
 *
 * The WebView has to be **in the window**, even though nobody looks at it: a
 * WebView that was never attached does not reliably finish loading a page, and
 * the drain then sits waiting for a page load that never completes. So it goes
 * in as a one-pixel view and comes out again when the reading stops.
 */
object QueueDrain {

    /** Small on purpose: a batch is the most that a kill can cost. */
    private const val BATCH = 40

    /**
     * Longest a batch may go without reporting. Forty crags at a quarter of a
     * second each is seconds, not minutes, even with a throttle hold — so this
     * is generous and still catches a wedged run.
     */
    private const val BATCH_TIMEOUT_MS = 120_000L

    /** How many times a crag may fail before it is given up on. */
    private const val RETRIES = 1

    /** How long to leave a broken connection before trying it again. */
    private const val RETRY_MS = 60_000L

    /** Photos left downloading when the next batch may start anyway. */
    private const val PHOTO_BACKLOG = 60

    private const val DELAY_MS = 250
    private const val WORKERS = 6

    /** One drain at a time, however many screens ask for one. */
    private var running = false

    /** Waiting to try again after something went wrong out on the network. */
    private val later = Handler(Looper.getMainLooper())

    /**
     * Set while the browser screen is open.
     *
     * Two WebViews reading UKC pages at once share one renderer process, and
     * loading a region's search results in one while the other grinds through
     * crag pages is what killed it — taking the app with it. The reader's own
     * screen wins; the queue can wait a minute.
     */
    private var browsing = false

    fun holdWhileBrowsing(hold: Boolean) {
        browsing = hold
        if (hold) stopNow = true
    }

    /** Asked of the running drain between batches. */
    private var stopNow = false

    fun busy(): Boolean = running

    /**
     * What this session's reading has managed, for the queue's info window.
     * Counted from when the app last started reading, not from the queue's
     * birth: a rate that averaged in the hours the app sat closed would say
     * nothing about how fast the reading is going.
     */
    object Stats {
        /** When this session's reading began, 0 if it has not. */
        @Volatile var since = 0L
        /** Time actually spent reading, finished runs only. */
        @Volatile var readingMs = 0L
        /** When the current run began, 0 when nothing is reading. */
        @Volatile var runStart = 0L
        @Volatile var read = 0
        @Volatile var failed = 0
        @Volatile var empty = 0
        @Volatile var throttles = 0
        @Volatile var spacingMs = DELAY_MS
        @Volatile var batches = 0
        @Volatile var lastBatchMs = 0L
        /** Crags done in the batch in hand, so the count moves between batches. */
        @Volatile var inBatch = 0

        /** Reading time so far, the run in hand included. */
        fun elapsedMs(now: Long = System.currentTimeMillis()): Long =
            readingMs + if (runStart > 0) now - runStart else 0L

        internal fun runStarted() {
            val now = System.currentTimeMillis()
            if (since == 0L) since = now
            runStart = now
        }

        internal fun runStopped() {
            if (runStart > 0) readingMs += System.currentTimeMillis() - runStart
            runStart = 0L
            inBatch = 0
        }
    }

    /**
     * Starts reading if there is anything to read and nothing already reading.
     * [onBatch] fires on the main thread after each batch, so a list on screen
     * can show what arrived.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun start(context: Context, host: android.view.ViewGroup?, onBatch: () -> Unit = {}) {
        later.removeCallbacksAndMessages(null)

        // Every reason for not starting is worth saying: "nothing is
        // happening" is the hardest thing to diagnose after the fact.
        with(ImportQueue) {
            if (context.queuePaused) {
                AppLog.add(context, "queue: paused, not reading")
                return
            }
        }

        if (browsing) {
            AppLog.add(context, "queue: waiting for the browser screen to close")
            return
        }

        stopNow = false

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
        Stats.runStarted()

        val handler = Handler(Looper.getMainLooper())

        // Built against the screen that hosts it, not the application, since it
        // is about to be added to that screen's window.
        val web = WebView(host?.context ?: app)

        host?.addView(web, android.view.ViewGroup.LayoutParams(1, 1))
        web.alpha = 0f

        var batch: List<Queued> = emptyList()
        var ready = false

        // The bar has to describe the whole job. Reporting each batch's own
        // progress made it fill and empty forty crags at a time, which reads as
        // a stuck or looping import rather than a steady one.
        var planned = 0
        var leftAtBatch = 0
        var batchStart = 0L

        /** Crags this batch could not read, by URL. */
        val unread = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        /**
         * Tries again shortly, rather than waiting for the app to be opened
         * afresh. A connection comes back — a VPN switched off, a tunnel
         * ending — and there is no reason to make the reader do anything about
         * it. Only while the screen that hosts it is still there; otherwise
         * the next opening will start one.
         */
        fun tryAgainLater(why: String) {
            AppLog.add(app, "queue: $why — trying again in a minute")

            later.postDelayed({
                if (host == null || host.isAttachedToWindow) start(context, host, onBatch)
            }, RETRY_MS)
        }

        fun stop() {
            Stats.runStopped()
            running = false
            ImportState.running = false
            handler.removeCallbacksAndMessages(null)
            host?.removeView(web)
            web.destroy()
            ImportProgress.clear(app)
        }

        /**
         * Photos are queued as the pages are read and land afterwards. Waiting
         * for every last one before the next batch left the page reader idle
         * for most of a run, so the next batch starts while a few are still
         * coming down. A long backlog is still waited out: the links expire.
         */
        fun whenPhotosLand(then: () -> Unit) {
            if (TopoCache.queued() <= PHOTO_BACKLOG) {
                then()
                return
            }

            handler.postDelayed({ whenPhotosLand(then) }, 400)
        }

        /** Hands the page the next batch, or finishes if there is none left. */
        fun feed() {
            with(ImportQueue) { if (app.queuePaused) { stop(); return } }

            if (stopNow) {
                AppLog.add(app, "queue: standing down, the browser screen is open")
                stop()
                return
            }

            batch = ImportQueue.next(app, BATCH)

            if (batch.isEmpty()) {
                val held = CragStore.count(app)
                AppLog.add(app, "queue: finished, library holds $held crags")
                ImportProgress.done(
                    app,
                    app.getString(R.string.queue_done),
                    app.resources.getQuantityString(R.plurals.crags_found, held, held),
                )
                Stats.runStopped()
                running = false
                ImportState.running = false
                handler.removeCallbacksAndMessages(null)
                host?.removeView(web)
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

            unread.clear()
            Stats.inBatch = 0
            batchStart = System.currentTimeMillis()
            AppLog.add(app, "queue: reading a batch of ${batch.size}, $leftAtBatch left")

            // If a batch never reports back, the drain would sit "already
            // reading" for the rest of the session and every later attempt
            // would decline to start. Give it a ceiling and say so.
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({
                stop()
                tryAgainLater("batch went quiet")
            }, BATCH_TIMEOUT_MS)

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
                Stats.read += ok
                Stats.failed += failed
                Stats.batches++
                Stats.inBatch = 0
                Stats.lastBatchMs = System.currentTimeMillis() - batchStart

                AppLog.add(app, "queue: batch done, $ok read, $failed failed, " +
                    "${(ImportQueue.size(app) - batch.size).coerceAtLeast(0)} left")

                // A crag that failed gets one more go at the back of the queue:
                // most failures are a passing network fault, not a bad page.
                // After that it is struck off, since a queue that never shrinks
                // never ends.
                val again = batch.filter { it.url in unread && it.tries < RETRIES }
                    .map { it.copy(tries = it.tries + 1) }

                ImportQueue.drop(app, batch)
                ImportQueue.requeue(app, again)

                if (again.isNotEmpty()) {
                    AppLog.add(app, "queue: ${again.size} to try again later")
                }

                CragStore.invalidate()

                handler.post {
                    onBatch()
                    whenPhotosLand { feed() }
                }
            }

            /** Crags with nothing worth storing: summits, mostly. */
            @JavascriptInterface
            fun emptyCrags(count: Int) {
                Stats.empty += count
                AppLog.add(app, "queue: $count had nothing to store — summits, not climbs")
            }

            @JavascriptInterface
            fun cragFailed(name: String, url: String, reason: String) {
                AppLog.add(app, "queue: could not read $name — $reason")
                if (url.isNotBlank()) unread.add(url)
            }

            @JavascriptInterface
            fun failed(reason: String) {
                handler.post {
                    stop()
                    tryAgainLater("batch failed — $reason")
                }
            }

            @JavascriptInterface
            fun progress(done: Int, total: Int, name: String) {
                Stats.inBatch = done
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
                Stats.throttles++
                Stats.spacingMs = spacingMs
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

            /**
             * A renderer that dies takes the whole app with it unless the death
             * is claimed here. Claim it, say so, and try again later.
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                handler.post {
                    stop()
                    tryAgainLater("the browser engine died under it")
                }
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                // Only the main page matters; a missing image is not a failure.
                if (request?.isForMainFrame != true) return

                handler.post {
                    stop()
                    tryAgainLater("could not open UKC — ${error?.description}")
                }
            }

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
