package dr.ukccrags

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dr.ukccrags.databinding.ActivityBrowseBinding
import dr.ukccrags.databinding.DialogProgressBinding
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-app browser for UKC. The user signs in here (autofill included), so
 * every fetch made from this page carries their session and the Cloudflare
 * clearance the WebView has already passed.
 */
class BrowseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowseBinding
    private var script: String = ""

    /** Presented by this screen's script on every call that stores or fetches. */
    private val token = PageScript.newToken()

    /** Set once the screen is going, so late callbacks from the page do nothing. */
    private var gone = false

    /** Between onStart and onStop. */
    private var visible = false

    /** Whether this screen currently holds the queue off. */
    private var holding = false

    /** The main page failed to load: no signal, most likely. */
    private var loadFailed = false

    /**
     * Crag URL to the topo ids it had before a refresh re-read it. A refresh
     * saves over the old record rather than deleting it first, so a failed
     * read loses nothing; the photos of topos UKC has since dropped are
     * cleared once the new copy is safely stored.
     */
    private val refreshing = java.util.concurrent.ConcurrentHashMap<String, Set<Long>>()

    /** A single-crag refresh wants the newest photos, not the ones on disk. */
    @Volatile
    private var freshTopos = false
    /**
     * True while an import, refresh or sync is running.
     *
     * Carries two side effects worth having in one place: the screen is held
     * awake, since a region-wide import takes minutes and a sleeping screen
     * throttles the WebView doing the work; and the shade notification is
     * cleared the moment the work stops, however it stopped.
     */
    private var busy = false
        set(value) {
            field = value

            runOnUiThread {
                ImportState.running = value
                updateHold()

                if (value) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    ImportProgress.clear(this)
                }
            }
        }

    /**
     * Holds the queue off while this screen is in front or has work running.
     *
     * Two readers in one renderer is what killed the app, so the queue waits
     * — but only while there is a reason to. Held for the life of the screen,
     * it stayed held behind "run in background" and after the screen closed,
     * since the release came after the list had already been told no.
     */
    private fun updateHold() {
        val want = !gone && (visible || busy)
        if (want == holding) return
        holding = want
        QueueDrain.holdWhileBrowsing(want)
    }

    /** What the button says with nothing to offer yet, by why the screen was opened. */
    private fun restingLabel(): String = getString(
        when {
            intent.getBooleanExtra(EXTRA_LOG_CLIMB, false) -> R.string.log_finding
            refreshFlow -> R.string.refresh_title
            else -> R.string.browse_title
        }
    )

    /** Opened to refresh one crag, rather than to find new ones. */
    private val refreshFlow: Boolean
        get() = intent.hasExtra(EXTRA_REFRESH_URL)

    /** Written from the page's worker threads, read on the main thread. */
    private val failures = mutableListOf<String>()

    private var added = 0
    private var total = 0

    /** Set while the browser is parked on a crag page with a climb ticked. */
    private var logging = false

    /** Opened purely to sign in: no importing, and it closes once signed in. */
    private var signingIn = false

    /** True once UKC's log form has been seen on this screen. */
    private var loggedHere = false

    /** A sync nobody asked for stays out of the way. */
    private var quietSync = false

    /** Opened only to read the logbook, so there is nothing to stay for. */
    private var syncOnly = false

    /** True once this screen has seen a signed-out UKC page. */
    private var wasSignedOut = false

    private var progress: AlertDialog? = null
    private var progressBinding: DialogProgressBinding? = null

    private var pendingOrigin: String? = null
    private var pendingCallback: GeolocationPermissions.Callback? = null

    private val askShade = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* declined is fine: the on-screen progress still runs */ }

    /** Asked for once per screen, and only when a long job is starting. */
    private fun wantShade() {
        if (android.os.Build.VERSION.SDK_INT < 33) return

        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!granted) askShade.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private val askLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allowed = result.values.any { it }

        if (allowed) grantGeolocation(pendingOrigin) else pendingCallback?.invoke(
            pendingOrigin, false, false,
        )

        pendingCallback = null
        pendingOrigin = null

        if (!allowed) explainLocation()
    }

    /**
     * WebView keeps its own per-origin answer, separate from the app's
     * permission, and a refusal sticks. Clearing it means a later yes is
     * actually honoured.
     */
    private fun grantGeolocation(origin: String?) {
        origin?.let { GeolocationPermissions.getInstance().allow(it) }
        pendingCallback?.invoke(origin, true, true)
    }

    /**
     * Android stops showing the permission dialog after two refusals, so the
     * only way back is Settings. Say so rather than failing silently.
     */
    private fun explainLocation() {
        val canAsk = Nearby.PERMISSIONS.any { shouldShowRequestPermissionRationale(it) }

        if (canAsk) {
            Toast.makeText(this, R.string.need_location, Toast.LENGTH_LONG).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.need_location)
            .setMessage(R.string.location_blocked)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null),
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityBrowseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        signingIn = intent.getBooleanExtra(EXTRA_SIGN_IN, false)
        syncOnly = intent.getBooleanExtra(EXTRA_SYNC, false)

        supportActionBar?.title = getString(
            when {
                signingIn -> R.string.sign_in
                syncOnly -> R.string.sync_ticks
                intent.getBooleanExtra(EXTRA_LOG_CLIMB, false) -> R.string.log_title
                refreshFlow -> R.string.refresh_title
                else -> R.string.browse_title
            }
        )

        // Signing in or syncing: there is nothing on this screen to import.
        if (signingIn || syncOnly) binding.action.visibility = View.GONE
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Lets the import be driven and inspected from adb in debug builds.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        // While this screen is reading pages, the queue does not: see
        // updateHold, run from onStart and whenever the work starts or stops.

        script = PageScript.load(this, token, watchKind = true).orEmpty()

        // A "no" to the geolocation prompt is remembered per origin for the
        // life of the app's data, and nothing in the UI can undo it. Start
        // each session with a clean slate so the prompt can be answered again.
        GeolocationPermissions.getInstance().clearAll()

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.web, true)

        binding.web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false

            // UKC's "near me" button needs this; it is not on by default in
            // every WebView build.
            setGeolocationEnabled(true)
        }

        binding.web.addJavascriptInterface(Bridge(), "Android")

        binding.web.webChromeClient = object : WebChromeClient() {

            /**
             * Swallowed, and this matters more than it looks.
             *
             * Pages are read in sandboxed iframes with scripts disallowed, so
             * Chromium reports every blocked `<script>` on every page as a
             * console error. One crag page carries dozens; an import of a whole
             * region carries hundreds of thousands, and the default handling
             * writes each one to the log from the main thread. That is what
             * turned a large import into a black screen: the UI thread spent
             * itself on logging rather than drawing.
             *
             * Nothing is lost by dropping them. The script's own failures come
             * back through the bridge instead.
             */
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean = true

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.bar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                binding.bar.progress = newProgress
            }

            /**
             * UKC's "use my location" button needs the geolocation API, which
             * a WebView denies unless the app answers this prompt.
             */
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                pendingOrigin = origin
                pendingCallback = callback

                if (Nearby.granted(this@BrowseActivity)) {
                    grantGeolocation(origin)
                    pendingOrigin = null
                    pendingCallback = null
                    return
                }

                askLocation.launch(Nearby.PERMISSIONS)
            }
        }

        binding.web.webViewClient = object : WebViewClient() {

            /**
             * The renderer can be killed under a heavy page — a whole region of
             * search results — and an unclaimed death kills the app. Claimed
             * here: say what happened and leave, rather than vanishing.
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                AppLog.add(this@BrowseActivity, "browser: the engine died, closing the screen")

                busy = false
                hideProgress()
                Toast.makeText(this@BrowseActivity, R.string.browser_died, Toast.LENGTH_LONG).show()
                finish()
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                logging = false
                loadFailed = false
                binding.action.isEnabled = false
                binding.action.text = restingLabel()
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                // A missing image is not a missing page.
                if (request?.isForMainFrame == true) {
                    loadFailed = true
                    AppLog.add(this@BrowseActivity, "browser: could not open the page — ${error?.description}")
                }
            }

            /**
             * This screen is for UKC. The bridge is on every page it shows, so
             * a link out — an advert, a club site in a crag's notes — goes to
             * the phone's browser instead of opening here with the app's hooks
             * in it. Cloudflare's challenge pages are UKC's own gate and stay.
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
            ): Boolean {
                val uri = request?.url ?: return false
                if (!request.isForMainFrame) return false

                val scheme = uri.scheme?.lowercase()
                if (scheme == "about") return false
                if ((scheme == "https" || scheme == "http") &&
                    (PageScript.isUkc(uri) || PageScript.isChallenge(uri))
                ) {
                    return false
                }

                openOutside(uri)
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                noteLogging(url.orEmpty())
                inject()
            }
        }

        binding.action.setOnClickListener {
            if (logging) submitLog() else runImport()
        }
        binding.action.isEnabled = false

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.web.canGoBack()) binding.web.goBack() else finish()
            }
        })

        binding.web.loadUrl(intent.getStringExtra(EXTRA_URL) ?: START_URL)
    }

    private fun openOutside(uri: android.net.Uri) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            .addCategory(android.content.Intent.CATEGORY_BROWSABLE)

        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Finds the climb page's own "Add to Logbook" button. The page finishes
     * itself off in script, so the button may not exist yet; try a few times,
     * then give up quietly rather than nag.
     */
    private fun prepareLog(attempt: Int = 0) {
        if (gone || !intent.getBooleanExtra(EXTRA_LOG_CLIMB, false)) return

        // With no signal the page is Chromium's error page, which has no
        // button either — and "sign in" is the wrong thing to tell someone
        // standing under a crag with no bars.
        if (loadFailed) {
            intent.removeExtra(EXTRA_LOG_CLIMB)
            binding.action.text = getString(R.string.browse_title)
            Toast.makeText(this, R.string.log_no_signal, Toast.LENGTH_LONG).show()
            return
        }

        binding.web.evaluateJavascript("window.__ukcPrepareLog()") { raw ->
            val ready = runCatching { JSONObject(PageScript.unquote(raw)).optBoolean("ready") }
                .getOrDefault(false)

            if (ready) {
                intent.removeExtra(EXTRA_LOG_CLIMB)

                // UKC's own button is somewhere down the climbs table, so put
                // it on the app's bar instead of leaving "Import this crag".
                logging = true
                binding.action.text = getString(R.string.add_to_logbook)
                binding.action.isEnabled = true

                Toast.makeText(this, R.string.log_ready, Toast.LENGTH_LONG).show()
            } else if (attempt < LOG_TRIES) {
                binding.web.postDelayed({ prepareLog(attempt + 1) }, 500)
            } else {
                intent.removeExtra(EXTRA_LOG_CLIMB)
                binding.action.text = getString(R.string.browse_title)
                Toast.makeText(
                    this,
                    if (loadFailed) R.string.log_no_signal else R.string.log_not_ready,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * A log is saved by posting UKC's addlogs form, which then moves on
     * somewhere else. Seeing that page and then leaving it means an ascent may
     * have gone in, so the logbook is re-read — one CSV request, so it is
     * cheap enough to do on spec.
     */
    private fun noteLogging(url: String) {
        if (url.contains("addlogs.php")) {
            loggedHere = true
            return
        }

        if (!loggedHere || busy) return

        loggedHere = false
        binding.web.postDelayed({ syncTicks(quiet = true) }, 800)
    }

    /** Hands the press straight to UKC's "Add to Logbook". */
    private fun submitLog() {
        binding.web.evaluateJavascript("window.__ukcSubmitLog()") { raw ->
            val ready = runCatching { JSONObject(PageScript.unquote(raw)).optBoolean("ready") }
                .getOrDefault(false)

            if (ready) {
                logging = false
                binding.action.isEnabled = false
            } else {
                Toast.makeText(this, R.string.log_not_ready, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Imports a named set of crags handed over by another screen. */
    private fun importGiven() {
        val json = intent.getStringExtra(EXTRA_IMPORT) ?: return
        intent.removeExtra(EXTRA_IMPORT)

        if (busy) return

        wantShade()
        busy = true
        binding.action.isEnabled = false
        synchronized(failures) { failures.clear() }

        showProgress(getString(R.string.importing_crags))

        binding.web.evaluateJavascript(
            "window.__ukcRefreshCrags(${JSONObject.quote(json)}, $DELAY_MS, $WORKERS, $DELAY_MS)",
            null,
        )
    }

    /** Runs a logbook sync as soon as the opening page has settled. */
    private fun syncWhenReady() {
        if (intent.hasExtra(EXTRA_IMPORT)) {
            binding.web.postDelayed({ importGiven() }, 600)
        }

        if (intent.getBooleanExtra(EXTRA_LOG_CLIMB, false)) {
            binding.web.postDelayed({ prepareLog() }, 400)
        }

        if (intent.getBooleanExtra(EXTRA_SYNC, false)) {
            intent.removeExtra(EXTRA_SYNC)
            binding.web.postDelayed({ syncTicks() }, 600)
        }

        if (intent.getBooleanExtra(EXTRA_REFRESH, false)) {
            intent.removeExtra(EXTRA_REFRESH)
            binding.web.postDelayed({ refreshCrags() }, 600)
        }
    }

    /**
     * Re-reads one stored crag, [EXTRA_REFRESH_URL], so its grades, climbs and
     * topos are current. Refreshing the whole library goes through the queue.
     *
     * Saved over the old record, never deleted first: a read that fails —
     * no signal, a throttle — used to leave the crag gone from the library.
     */
    private fun refreshCrags() {
        if (busy) return

        val only = intent.getStringExtra(EXTRA_REFRESH_URL)
        val card = CragStore.cards(this).firstOrNull { it.sourceUrl == only }

        if (card == null) {
            Toast.makeText(this, R.string.nothing_to_refresh, Toast.LENGTH_LONG).show()
            return
        }

        wantShade()
        busy = true
        binding.action.isEnabled = false
        synchronized(failures) { failures.clear() }

        refreshing[card.sourceUrl] =
            CragStore.byId(this, card.id)?.topos?.map { it.topoId }?.toSet().orEmpty()
        freshTopos = true

        val list = JSONArray().put(JSONObject().put("name", card.area).put("url", card.sourceUrl))

        showProgress(getString(R.string.refreshing_one, card.area))

        binding.web.evaluateJavascript(
            "window.__ukcRefreshCrags(${JSONObject.quote(list.toString())}, $DELAY_MS, $WORKERS, $DELAY_MS)",
            null,
        )
    }

    /**
     * A long import or refresh happens behind the WebView, which otherwise
     * just sits there looking idle, so put a dialog in front of it.
     */
    private fun showProgress(message: String) {
        val view = DialogProgressBinding.inflate(layoutInflater)
        view.message.text = message
        view.detail.text = getString(R.string.starting)

        progressBinding = view
        progress = MaterialAlertDialogBuilder(this)
            .setView(view.root)
            .setCancelable(false)
            .setNegativeButton(R.string.run_in_background) { dialog, _ ->
                dialog.dismiss()

                // Back to the list rather than parked on the browser. This
                // screen stays alive underneath — its WebView is doing the
                // work — and the count carries on in the shade.
                startActivity(
                    android.content.Intent(this, CragListActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
            .show()
    }

    private fun hideProgress() {
        progress?.dismiss()
        progress = null
        progressBinding = null
    }

    /** Loads the extractor, then asks the page what it is so we can label the button. */
    private fun inject() {
        if (gone) return

        binding.web.evaluateJavascript(script) {
            if (gone) return@evaluateJavascript
            binding.web.evaluateJavascript("window.__ukcPageKind()") { raw ->
                if (gone) return@evaluateJavascript
                applyKind(PageScript.unquote(raw))
                noteSignedIn()
                syncWhenReady()
            }
        }
    }

    /**
     * Every UKC page carries the signed-in user id, or carries a zero when the
     * session has lapsed, so each page load is a free check on sign-in state.
     * Pages elsewhere say nothing either way and are ignored.
     */
    private fun noteSignedIn() {
        val url = binding.web.url.orEmpty()
        if (!url.contains("ukclimbing.com")) return

        // Chromium's own error page keeps UKC's address but none of its
        // markers; read as "signed out", it signed the reader out for no signal.
        if (loadFailed) return

        binding.web.evaluateJavascript("window.__ukcSignedIn()") { raw ->
            val id = runCatching { JSONObject(PageScript.unquote(raw)).optLong("userId") }.getOrDefault(0L)
            Session.saw(this, id)
            invalidateOptionsMenu()

            if (!signingIn) return@evaluateJavascript

            // Close on the sign-in landing, but only once a signed-out page has
            // been seen: opening this while already signed in should not blink.
            if (id <= 0L) {
                wasSignedOut = true
            } else if (wasSignedOut) {
                Toast.makeText(this, R.string.signed_in, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun applyKind(json: String) {
        val kind = runCatching { JSONObject(json) }.getOrNull() ?: return

        if (busy || logging) return

        when (kind.optString("kind")) {
            "crag" -> {
                binding.action.text = getString(R.string.import_crag)
                binding.action.isEnabled = true
            }
            "results" -> {
                val count = kind.optInt("count")
                binding.action.text =
                    resources.getQuantityString(R.plurals.import_n, count, count)
                binding.action.isEnabled = count > 0
            }
            else -> {
                binding.action.text = restingLabel()
                binding.action.isEnabled = false
            }
        }
    }

    /**
     * Takes the names off a search and leaves them for later.
     *
     * A region-wide search names thousands of crags. Reading them here and now
     * meant a quarter of an hour of held-open screen that lost its place if
     * anything interrupted it. This costs one request; [QueueDrain] does the
     * reading, a batch at a time, and survives being stopped.
     */
    private fun queueResults() {
        binding.web.evaluateJavascript("window.__ukcResultRows()") { raw ->
            if (gone) return@evaluateJavascript
            val json = PageScript.unquote(raw)

            val found = runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { index ->
                    val node = array.optJSONObject(index) ?: return@mapNotNull null
                    Queued(node.optString("name"), node.optString("url"))
                }
            }.getOrDefault(emptyList())

            busy = false
            hideProgress()

            if (found.isEmpty()) {
                Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }

            val added = ImportQueue.add(this, found)
            with(ImportQueue) { queuePaused = false }

            MaterialAlertDialogBuilder(this)
                .setTitle(resources.getQuantityString(R.plurals.queued, added, added))
                .setMessage(
                    getString(R.string.queued_explained, found.size - added)
                )
                .setPositiveButton(R.string.see_crags) { _, _ -> finish() }
                .setNegativeButton(R.string.keep_browsing, null)
                .show()
        }
    }

    private fun runImport() {
        if (busy) return

        wantShade()
        busy = true
        binding.action.isEnabled = false
        synchronized(failures) { failures.clear() }

        showProgress(getString(R.string.importing_crags))

        binding.web.evaluateJavascript("window.__ukcPageKind()") { raw ->
            val kind = runCatching { JSONObject(PageScript.unquote(raw)) }.getOrNull()

            if (kind?.optString("kind") == "results") {
                queueResults()
            } else {
                binding.web.evaluateJavascript("window.__ukcImportCurrent()", null)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.browse, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        Session.describeIn(this, menu)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.sign_in -> {
                // The user types their own credentials into UKC's page; the app
                // never sees them. Android autofill can fill them in.
                binding.web.loadUrl(getString(R.string.login_url))
                return true
            }
            R.id.sync_ticks -> {
                syncTicks()
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    private fun syncTicks(quiet: Boolean = false) {
        if (busy || gone) return

        busy = true
        quietSync = quiet

        // A crag page is where UKC exposes the signed-in user id.
        val crag = CragStore.cards(this).firstOrNull()?.sourceUrl.orEmpty()

        if (!quiet) showProgress(getString(R.string.sync_ticks))

        binding.web.evaluateJavascript(
            "window.__ukcSyncTicks(${JSONObject.quote(crag)}, ${Session.userId(this)})", null
        )
    }

    override fun onStart() {
        super.onStart()
        visible = true
        updateHold()
    }

    override fun onStop() {
        visible = false
        updateHold()
        super.onStop()
    }

    /**
     * The WebView goes with the screen, and so does anything it was doing. A
     * job cut short leaves no count in the shade and no hold on the queue.
     */
    override fun onDestroy() {
        val cutShort = busy
        gone = true

        hideProgress()
        busy = false
        if (holding) {
            holding = false
            QueueDrain.holdWhileBrowsing(false)
        }
        if (cutShort) {
            AppLog.add(this, "browser: closed with a job still running")
            ImportProgress.clear(this)
        }

        (binding.web.parent as? android.view.ViewGroup)?.removeView(binding.web)
        binding.web.destroy()

        super.onDestroy()
    }

    /** Called from the page. Every method hops back to the main thread. */
    private inner class Bridge {

        /** False when the crag could not be kept, so the page counts it as failed. */
        @JavascriptInterface
        fun saveCrag(key: String, json: String): Boolean {
            if (!PageScript.matches(token, key)) return false

            val crag = CragStore.save(this@BrowseActivity, json)
            if (crag == null) {
                AppLog.add(this@BrowseActivity, "import: a crag came back that could not be stored")
                return false
            }

            // Stored: now the photos of topos it no longer has can go.
            refreshing.remove(crag.sourceUrl)?.let { before ->
                TopoCache.forget(this@BrowseActivity, before - crag.topos.map { it.topoId }.toSet())
            }

            // The crag page states the reader's own ascents, so a signed-in
            // import ticks its own climbs without asking the logbook.
            val climbs = crag.buttresses.flatMap { it.climbs }

            Ticks(this@BrowseActivity).addAll(climbs.filter { it.ticked }.map { it.url })
            Attempts(this@BrowseActivity).addAll(
                climbs.filter { it.attempted && !it.ticked }.map { it.url }
            )
            return true
        }

        /** Pushed by the page's MutationObserver when AJAX swaps the content. */
        @JavascriptInterface
        fun kind(json: String) {
            runOnUiThread { applyKind(json) }
        }

        /**
         * Queues a topo photo from the link the page just handed over, and
         * returns at once: all page script runs on one thread, and a download
         * held here would stall every worker.
         */
        @JavascriptInterface
        fun fetchTopoImage(key: String, topoId: String, url: String): Boolean {
            if (!PageScript.matches(token, key)) return false
            return TopoCache.enqueue(this@BrowseActivity, topoId, url, force = freshTopos)
        }

        @JavascriptInterface
        fun ticksProgress(found: Int) {
            runOnUiThread {
                binding.action.text = getString(R.string.ticks_syncing, found)
                progressBinding?.detail?.text = getString(R.string.ticks_syncing, found)
            }
        }

        /** The CSV export names climbs; the app turns those into climb URLs. */
        @JavascriptInterface
        fun saveTickNames(key: String, json: String) {
            if (!PageScript.matches(token, key)) return
            val entries = runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { index ->
                    val node = array.optJSONObject(index) ?: return@mapNotNull null
                    node.optString("crag") to node.optString("name")
                }
            }.getOrDefault(emptyList())

            added = Ticks(this@BrowseActivity)
                .addByName(this@BrowseActivity, entries)
        }

        /** UKC's wishlist arrives as climb links, so it needs no matching. */
        @JavascriptInterface
        fun saveWishlist(key: String, json: String) {
            if (!PageScript.matches(token, key)) return
            val urls = runCatching {
                val array = JSONArray(json)
                (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
            }.getOrDefault(emptyList())

            Wishlist(this@BrowseActivity).replaceWith(urls)
        }

        /**
         * UKC's ticklists, the reader's own and any they subscribe to. Merged
         * rather than replaced unless every list was read.
         */
        @JavascriptInterface
        fun saveLists(key: String, json: String, complete: Boolean) {
            if (!PageScript.matches(token, key)) return
            AutoSync.saveTicklists(this@BrowseActivity, json, complete)
        }

        @JavascriptInterface
        fun saveTicks(key: String, json: String) {
            if (!PageScript.matches(token, key)) return
            val urls = runCatching {
                val array = org.json.JSONArray(json)
                (0 until array.length()).map { array.getString(it) }
            }.getOrDefault(emptyList())

            added = Ticks(this@BrowseActivity).addAll(urls)
            total = urls.size
        }

        @JavascriptInterface
        fun ticksDone(key: String, found: Int) {
            if (!PageScript.matches(token, key)) return
            total = found

            // Counts as this week's sync, so the weekly one does not repeat it.
            AutoSync.ran(this@BrowseActivity)

            runOnUiThread {
                if (gone) return@runOnUiThread
                busy = false
                hideProgress()

                // A background sync says nothing unless it actually found one.
                if (!quietSync || added > 0) {
                    Toast.makeText(
                        this@BrowseActivity,
                        getString(R.string.ticks_done, total, added),
                        Toast.LENGTH_LONG,
                    ).show()
                }

                quietSync = false

                // Nothing to browse: the screen only existed to run the sync.
                if (syncOnly) { finish(); return@runOnUiThread }

                inject()
            }
        }

        @JavascriptInterface
        fun ticksFailed(reason: String) {
            AppLog.add(this@BrowseActivity, "logbook sync failed: $reason")

            runOnUiThread {
                if (gone) return@runOnUiThread
                busy = false
                hideProgress()

                if (quietSync) { quietSync = false; return@runOnUiThread }
                val message =
                    if (reason == "signed out") R.string.ticks_signed_out
                    else R.string.ticks_failed
                Toast.makeText(this@BrowseActivity, message, Toast.LENGTH_LONG).show()
                inject()
            }
        }

        @JavascriptInterface
        fun throttled(spacingMs: Int) {
            runOnUiThread {
                binding.action.text = getString(R.string.backing_off, spacingMs / 1000)
            }
        }

        /** Crags with nothing worth storing: summits, mostly. */
        @JavascriptInterface
        fun emptyCrags(count: Int) {
            AppLog.add(this@BrowseActivity, "import: $count had nothing to store")
        }

        /** Records why a crag didn't import, so the run can name it at the end. */
        @JavascriptInterface
        fun cragFailed(key: String, name: String, url: String, reason: String) {
            if (!PageScript.matches(token, key)) return
            AppLog.add(this@BrowseActivity, "import: could not read $name — $reason")
            synchronized(failures) { failures += "$name — $reason" }
        }

        @JavascriptInterface
        fun progress(done: Int, total: Int, name: String) {
            // Straight from the page's own thread. The shade is the one place
            // progress must keep moving even when the main thread is busy —
            // a stalled counter is indistinguishable from a stalled import.
            ImportProgress.show(
                this@BrowseActivity,
                getString(R.string.importing_crags),
                getString(R.string.importing_named, done, total, name),
                done,
                total,
            )

            // Something to read back afterwards when a long run went wrong.
            if (done % 50 == 0) AppLog.add(this@BrowseActivity, "import: $done/$total")

            runOnUiThread {
                binding.action.text = getString(R.string.importing, done, total)

                progressBinding?.let {
                    it.bar.isIndeterminate = false
                    it.bar.max = total
                    it.bar.progress = done
                    it.detail.text = getString(R.string.importing_named, done, total, name)
                }
            }
        }

        @JavascriptInterface
        fun finished(key: String, ok: Int, failed: Int) {
            if (!PageScript.matches(token, key)) return
            runOnUiThread { if (!gone) whenPhotosLand { report(ok, failed) } }
        }

        /** The pages are read long before their photos land; wait them out. */
        private fun whenPhotosLand(then: () -> Unit) {
            if (gone) return
            val waiting = TopoCache.queued()

            if (waiting <= 0) {
                then()
                return
            }

            progressBinding?.message?.text = getString(R.string.caching_photos)
            progressBinding?.detail?.text = resources.getQuantityString(
                R.plurals.photos_left, waiting, waiting,
            )

            binding.web.postDelayed({ whenPhotosLand(then) }, 400)
        }

        private fun report(ok: Int, failed: Int) {
            run {
                if (gone) return
                busy = false
                freshTopos = false
                refreshing.clear()
                hideProgress()

                val missed = synchronized(failures) { failures.toList() }

                // Said in the shade as well: after a long import the reader is
                // often no longer looking at this screen.
                ImportProgress.done(
                    this@BrowseActivity,
                    getString(R.string.import_done, ok, failed),
                    if (missed.isEmpty()) getString(R.string.import_all_ok)
                    else getString(R.string.import_some_failed),
                )

                val body = if (missed.isEmpty()) {
                    getString(R.string.import_all_ok)
                } else {
                    getString(R.string.import_some_failed) + "\n\n" + missed.joinToString("\n")
                }

                MaterialAlertDialogBuilder(this@BrowseActivity)
                    .setTitle(getString(R.string.import_done, ok, failed))
                    .setMessage(body)
                    .setPositiveButton(R.string.see_crags) { _, _ -> finish() }
                    .setNegativeButton(R.string.keep_browsing, null)
                    .show()

                inject()

                // Ticks are matched against stored crags, so climbs that only
                // arrived just now have never been looked at. One CSV request
                // settles them.
                if (ok > 0 && Session.signedIn(this@BrowseActivity)) {
                    // Let the connection recover from the import first.
                    binding.web.postDelayed({ syncTicks(quiet = true) }, 3000)
                }
            }
        }

        @JavascriptInterface
        fun failed(reason: String) {
            AppLog.add(this@BrowseActivity, "import: stopped — $reason")
            runOnUiThread {
                if (gone) return@runOnUiThread
                busy = false
                freshTopos = false
                refreshing.clear()
                hideProgress()
                Toast.makeText(
                    this@BrowseActivity,
                    getString(R.string.import_failed),
                    Toast.LENGTH_SHORT,
                ).show()
                inject()
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_SYNC = "sync"
        const val EXTRA_REFRESH = "refresh"
        const val EXTRA_REFRESH_URL = "refreshUrl"
        const val EXTRA_LOG_CLIMB = "logClimb"
        const val EXTRA_SIGN_IN = "signIn"

        /** JSON array of {name, url} crags to import on arrival. */
        const val EXTRA_IMPORT = "import"

        /** Half-second tries while UKC's climb table renders. */
        private const val LOG_TRIES = 12

        private const val START_URL = "https://www.ukclimbing.com/logbook/crags/"
        /** Pause between a worker's pages. The script widens it if UKC pushes back. */
        private const val DELAY_MS = 250

        /** Pages read at once. Photos no longer block these, so more of them pay. */
        private const val WORKERS = 6
    }
}
