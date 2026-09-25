package dr.ukccrags

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.lang.ref.WeakReference
import java.util.Collections

/**
 * Reads the queue, a batch at a time, in a WebView nobody looks at.
 *
 * This is the other half of [ImportQueue]. A region's worth of crags is no
 * longer one long run holding a screen open: batches are fetched whenever the
 * app is open, and each crag is struck off the list the moment it lands, so a
 * kill costs only the crags in hand and starting again needs no memory of where
 * it was.
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
 * the drain then sits waiting for a page load that never completes. It goes in
 * as a one-pixel view, and [App.park] carries it into whichever screen is in
 * front — left in the crag list, it was hidden and throttled whenever the
 * reader opened a crag, which is why the queue only moved with the list open.
 */
object QueueDrain {

    /** Small on purpose: a batch is the most that one page load can hold. */
    private const val BATCH = 40

    /**
     * Longest the page may go without a word. Re-armed on every report, so a
     * long batch that is getting on with it is never cut short — only a page
     * that has stopped talking.
     */
    private const val QUIET_MS = 120_000L

    /** How many times a crag may fail before it is given up on. */
    private const val RETRIES = 1

    /** How long to leave a broken connection before trying it again. */
    private const val RETRY_MS = 60_000L

    /**
     * Batches in a row that read nothing before the drain stops retrying on its
     * own. Past that it waits for the reader to come back to the app.
     */
    private const val DEAD_LIMIT = 3

    /** Photos left downloading when the next batch may start anyway. */
    private const val PHOTO_BACKLOG = 60

    private const val DELAY_MS = 250
    private const val WORKERS = 6

    /** Reasons the page gives when UKC answered and the crag itself was the problem. */
    private val PAGE_FAULTS = listOf("no climbs table", "could not be stored")

    /** One drain at a time, however many screens ask for one. */
    private var running = false

    /** Waiting to try again after something went wrong out on the network. */
    private val later = Handler(Looper.getMainLooper())

    /**
     * Everything else posted to the main thread. Kept apart from [later],
     * which is cleared on every start: a waiting sync cleared with it would
     * never run, and never release its hold on the queue.
     */
    private val main = Handler(Looper.getMainLooper())

    /**
     * Holds against reading, one per holder.
     *
     * Two WebViews reading UKC pages at once share one renderer process, and
     * loading a region's search results in one while the other grinds through
     * crag pages is what killed it — taking the app with it. The reader's own
     * browser screen wins, and so does the weekly sync; the queue can wait. A
     * count rather than a flag, so two holders cannot release each other.
     */
    private var holds = 0

    /** Asked of the running drain between batches. */
    private var stopNow = false

    /**
     * Something asked for reading and has not had it yet: a start turned away
     * by a hold, a drain that stood down for one, a retry waiting on the app
     * coming back. Releasing the last hold or a screen coming forward honours it.
     */
    private var wanted = false

    /** Not before this (uptime), so a retry keeps its minute's grace. */
    private var retryAt = 0L

    /** Batches in a row that read nothing at all. */
    private var deadBatches = 0

    private var appContext: Context? = null

    /** The latest caller's, since the one that started the run may be long gone. */
    private var onBatch: () -> Unit = {}
    private var owner: WeakReference<Activity>? = null

    /** To run once the drain has stopped, for readers that must not overlap it. */
    private val idle = mutableListOf<() -> Unit>()

    /**
     * Where the spacing got to, carried from batch to batch. Each batch used to
     * start again at the floor, so a backoff UKC had asked for lasted forty
     * crags at most.
     */
    @Volatile
    private var carried = DELAY_MS

    fun holdWhileBrowsing(hold: Boolean) {
        if (hold) {
            holds++
            stopNow = true
            return
        }

        holds = (holds - 1).coerceAtLeast(0)
        if (holds > 0) return

        // Released before the batch in hand ended: carry on as if never asked.
        stopNow = false

        if (wanted) {
            val app = appContext ?: return
            later.post { begin(app) }
        }
    }

    fun busy(): Boolean = running

    /** Runs [action] on the main thread once nothing is reading, now if nothing is. */
    fun whenIdle(action: () -> Unit) {
        if (!running) action() else idle += action
    }

    /** A screen came forward: a retry that was waiting for one can go ahead. */
    fun appResumed(activity: Activity) {
        val app = appContext ?: return
        if (wanted && !running && holds == 0 && SystemClock.uptimeMillis() >= retryAt) {
            later.removeCallbacksAndMessages(null)
            later.post { begin(app) }
        }
    }

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
        /** The spacing now, eased back as well as raised. */
        @Volatile var spacingMs = DELAY_MS
        @Volatile var batches = 0
        @Volatile var lastBatchMs = 0L
        /** Crags done in the batch in hand, so the count moves between batches. */
        @Volatile var inBatch = 0
        /** Crags failed on every try and struck off, by name, this session. */
        val givenUp: MutableList<String> = Collections.synchronizedList(mutableListOf())

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
     * can show what arrived. The latest caller's is the one kept, and it is not
     * called once the screen that gave it has gone. [host] is only somewhere to
     * sit until a screen is in front; after that the view follows the front.
     */
    fun start(context: Context, host: android.view.ViewGroup?, onBatch: () -> Unit = {}) {
        this.onBatch = onBatch
        owner = (activityOf(host?.context) ?: activityOf(context))?.let { WeakReference(it) }

        // Asked for by a screen: whatever grace a retry was waiting out is over.
        // The count of dead batches is kept, so with no signal each opening of
        // the app costs one batch's try, not three.
        retryAt = 0L

        begin(context.applicationContext, host)
    }

    private fun activityOf(context: Context?): Activity? {
        var at = context
        while (at is ContextWrapper) {
            if (at is Activity) return at
            at = at.baseContext
        }
        return null
    }

    /** The last caller's [onBatch], unless its screen has gone. */
    private fun deliver() {
        val screen = owner
        if (screen != null) {
            val activity = screen.get() ?: return
            if (activity.isFinishing || activity.isDestroyed) return
        }
        onBatch()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun begin(app: Context, host: android.view.ViewGroup? = null) {
        appContext = app
        later.removeCallbacksAndMessages(null)
        wanted = true

        // Every reason for not starting is worth saying: "nothing is
        // happening" is the hardest thing to diagnose after the fact.
        with(ImportQueue) {
            if (app.queuePaused) {
                AppLog.add(app, "queue: paused, not reading")
                wanted = false
                return
            }
        }

        if (holds > 0) {
            AppLog.add(app, "queue: waiting for the browser or the sync to finish")
            return
        }

        stopNow = false

        if (running) {
            AppLog.add(app, "queue: already reading")
            return
        }

        val waiting = ImportQueue.size(app)

        if (waiting == 0) {
            wanted = false
            return
        }

        val token = PageScript.newToken()
        val script = PageScript.load(app, token, watchKind = false) ?: return

        AppLog.add(app, "queue: starting, $waiting crags waiting")

        wanted = false
        running = true
        ImportState.running = true
        Stats.runStarted()

        val handler = Handler(Looper.getMainLooper())

        // Built on a context that can be pointed at whichever screen holds it,
        // so moving between screens neither breaks it nor leaks the last one.
        val wrapper = MutableContextWrapper(app)
        val web = WebView(wrapper)
        web.alpha = 0f
        App.park(web, wrapper, host)

        var batch: List<Queued> = emptyList()
        var ended = false

        // The bar has to describe the whole job. Reporting each batch's own
        // progress made it fill and empty forty crags at a time, which reads as
        // a stuck or looping import rather than a steady one.
        var planned = 0
        var leftAtBatch = 0
        var batchStart = 0L

        /** Crags this batch could not read: URL to why. */
        val unread = Collections.synchronizedMap(LinkedHashMap<String, String>())

        /**
         * Tries again shortly, rather than waiting for the app to be opened
         * afresh. A connection comes back — a VPN switched off, a tunnel
         * ending — and there is no reason to make the reader do anything about
         * it. Only with a screen of ours in front; otherwise the next screen to
         * come forward starts it.
         */
        fun tryAgainLater(why: String) {
            AppLog.add(app, "queue: $why — trying again in a minute")

            wanted = true
            retryAt = SystemClock.uptimeMillis() + RETRY_MS
            later.postDelayed({
                if (App.foreground()) begin(app)
            }, RETRY_MS)
        }

        fun ended() {
            ended = true
            Stats.runStopped()
            running = false
            ImportState.running = false
            handler.removeCallbacksAndMessages(null)
            App.unpark(web)
            web.destroy()

            val waitingOnUs = idle.toList()
            idle.clear()
            waitingOnUs.forEach { main.post(it) }
        }

        fun stop() {
            if (ended) return
            ended()
            ImportProgress.clear(app)
        }

        val quiet = Runnable {
            if (ended) return@Runnable
            stop()
            tryAgainLater("the page went quiet")
        }

        /** Safe from any thread. */
        fun heard() {
            handler.removeCallbacks(quiet)
            handler.postDelayed(quiet, QUIET_MS)
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

            heard()
            handler.postDelayed({ whenPhotosLand(then) }, 400)
        }

        /** Hands the page the next batch, or finishes if there is none left. */
        fun feed() {
            if (ended) return
            with(ImportQueue) { if (app.queuePaused) { stop(); return } }

            if (stopNow && holds > 0) {
                AppLog.add(app, "queue: standing down for the browser or the sync")
                stop()
                // Picked up again when the hold is released.
                wanted = true
                return
            }

            batch = ImportQueue.next(app, BATCH)

            if (batch.isEmpty()) {
                val held = CragStore.count(app)
                AppLog.add(app, "queue: finished, library holds $held crags")
                ended()
                ImportProgress.done(
                    app,
                    app.getString(R.string.queue_done),
                    app.resources.getQuantityString(R.plurals.crags_found, held, held),
                )
                main.post { deliver() }
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
            // would decline to start.
            heard()

            web.evaluateJavascript(
                "window.__ukcRefreshCrags(" +
                    "${org.json.JSONObject.quote(ImportQueue.asJson(batch))}, " +
                    "$DELAY_MS, $WORKERS, $carried)",
                null,
            )
        }

        /**
         * The batch is in. What was read is already struck off; what failed
         * goes round once more or is given up on — unless nothing at all was
         * read, which is the connection rather than the crags.
         */
        fun settle(ok: Int) {
            if (ended) return
            val failed = synchronized(unread) { LinkedHashMap(unread) }

            val connection = failed.values.none { why -> PAGE_FAULTS.any { why.startsWith(it) } }
            val conclusive = batch.size >= BATCH / 2 || deadBatches < DEAD_LIMIT

            if (ok == 0 && failed.isNotEmpty() && connection && conclusive) {
                deadBatches++
                stop()

                if (deadBatches >= DEAD_LIMIT) {
                    AppLog.add(app, "queue: $deadBatches batches in a row read nothing — " +
                        "leaving it until the app is next opened")
                    wanted = false
                } else {
                    tryAgainLater("nothing in the batch could be read, no signal most likely")
                }
                return
            }

            if (ok > 0) deadBatches = 0

            // A crag that failed gets one more go at the back of the queue:
            // most failures are a passing network fault, not a bad page. After
            // that it is struck off, since a queue that never shrinks never ends.
            val failing = batch.filter { it.url in failed }
            val again = failing.filter { it.tries < RETRIES }.map { it.copy(tries = it.tries + 1) }
            val lost = failing.filter { it.tries >= RETRIES }

            ImportQueue.dropAndRequeue(app, failed.keys, again)

            if (again.isNotEmpty()) AppLog.add(app, "queue: ${again.size} to try again later")

            if (lost.isNotEmpty()) {
                Stats.givenUp.addAll(lost.map { it.name })
                AppLog.add(app, "queue: gave up on ${lost.joinToString { it.name }}")
            }

            deliver()
            whenPhotosLand { feed() }
        }

        /** Only the parts of the page's bridge a batch can reach. */
        val bridge = object {
            @JavascriptInterface
            fun saveCrag(key: String, json: String): Boolean {
                if (!PageScript.matches(token, key)) return false
                heard()

                val crag = CragStore.save(app, json)
                if (crag == null) {
                    AppLog.add(app, "queue: a crag came back that could not be stored")
                    return false
                }

                val climbs = crag.buttresses.flatMap { it.climbs }

                // A signed-in read states the reader's own ascents, same as an
                // import from the browser screen does.
                Ticks(app).addAll(climbs.filter { it.ticked }.map { it.url })
                Attempts(app).addAll(
                    climbs.filter { it.attempted && !it.ticked }.map { it.url }
                )

                // Struck off now, not with the batch: a kill or a stall from
                // here on must not cost this crag being read a second time.
                ImportQueue.strike(app, crag.sourceUrl)
                return true
            }

            /** Nothing worth storing — summits, mostly — but read all the same. */
            @JavascriptInterface
            fun cragEmpty(key: String, url: String) {
                if (!PageScript.matches(token, key)) return
                heard()
                ImportQueue.strike(app, url)
            }

            @JavascriptInterface
            fun finished(key: String, ok: Int, failed: Int) {
                if (!PageScript.matches(token, key)) return

                Stats.read += ok
                Stats.failed += failed
                Stats.batches++
                Stats.inBatch = 0
                Stats.lastBatchMs = System.currentTimeMillis() - batchStart

                AppLog.add(app, "queue: batch done, $ok read, $failed failed, " +
                    "${ImportQueue.size(app)} left")

                handler.post { settle(ok) }
            }

            @JavascriptInterface
            fun emptyCrags(count: Int) {
                Stats.empty += count
                AppLog.add(app, "queue: $count had nothing to store — summits, not climbs")
            }

            @JavascriptInterface
            fun cragFailed(key: String, name: String, url: String, reason: String) {
                if (!PageScript.matches(token, key)) return
                heard()
                AppLog.add(app, "queue: could not read $name — $reason")
                if (url.isNotBlank()) unread[url] = reason
            }

            @JavascriptInterface
            fun failed(reason: String) {
                handler.post {
                    if (ended) return@post
                    stop()
                    tryAgainLater("batch failed — $reason")
                }
            }

            @JavascriptInterface
            fun progress(done: Int, total: Int, name: String) {
                heard()
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

            @JavascriptInterface
            fun throttled(spacingMs: Int) {
                heard()
                Stats.throttles++
                carried = spacingMs
                Stats.spacingMs = spacingMs
                AppLog.add(app, "queue: UKC pushed back, spacing now ${spacingMs}ms")
            }

            /** The spacing easing back after a throttle, so the next batch starts there. */
            @JavascriptInterface
            fun spacing(spacingMs: Int) {
                heard()
                carried = spacingMs.coerceIn(DELAY_MS, 8000)
                Stats.spacingMs = carried
            }

            /**
             * Topo photos sit behind signed, expiring URLs, so they have to be
             * fetched while the page is in hand. Queued crags need them as much
             * as imported ones did — a topo with no picture is no use at a crag.
             */
            @JavascriptInterface
            fun fetchTopoImage(key: String, topoId: String, url: String): Boolean {
                if (!PageScript.matches(token, key)) return false
                return TopoCache.enqueue(app, topoId, url)
            }
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
                    if (ended) return@post
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
                    if (ended) return@post
                    stop()
                    tryAgainLater("could not open UKC — ${error?.description}")
                }
            }

            /**
             * Every page that finishes and is not a Cloudflare challenge gets the
             * script and a batch. The first load is often the challenge, which
             * then moves on to the real page by itself; putting the script into
             * the challenge only and never again is what left the drain waiting
             * on nothing. A page claims itself, so a second finish of the same
             * page does not start a second batch alongside the first.
             */
            override fun onPageFinished(view: WebView?, url: String?) {
                if (ended) return

                web.evaluateJavascript(PageScript.CHALLENGE_CHECK) { challenge ->
                    if (ended) return@evaluateJavascript
                    if (challenge == "true") {
                        AppLog.add(app, "queue: Cloudflare is checking the browser, waiting")
                        return@evaluateJavascript
                    }

                    // The pages are read from this page's own origin, with its
                    // cookies, exactly as the browser screen does it.
                    web.evaluateJavascript(script) {
                        web.evaluateJavascript(PageScript.CLAIM) { claimed ->
                            if (PageScript.unquote(claimed) == "yes") feed()
                        }
                    }
                }
            }
        }

        // A load that never finishes, or a challenge that never clears, is as
        // stuck as a batch that never reports.
        heard()
        web.loadUrl(app.getString(R.string.crag_index_url))
    }
}
