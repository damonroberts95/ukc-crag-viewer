package dr.ukccrags

import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dr.ukccrags.databinding.ActivityCragListBinding
import dr.ukccrags.databinding.DialogQueueInfoBinding
import dr.ukccrags.databinding.ItemCragBinding
import dr.ukccrags.databinding.ItemFoundBinding
import dr.ukccrags.databinding.ItemSectionBinding
import java.io.File

class CragListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCragListBinding

    private val adapter = ResultAdapter()

    private var nearestFirst = false
    private var filter: String = ""
    private var type: String = ""

    /** Grade groups kept, by label. Empty means every grade. */
    private val grades = linkedSetOf<String>()

    /**
     * The grades each offered label stands for. British trad grades arrive as
     * "E1 5b", and offering every adjectival/technical pairing ran the list to
     * hundreds; the filter offers "E1" and means all of them.
     */
    private var gradeGroups: Map<String, Set<String>> = emptyMap()

    /** The library, read on arrival rather than on every keystroke. */
    private var library: List<CragCard> = emptyList()

    /** Ticked climbs per crag, counted with the library rather than per row. */
    private var tickedByCrag: Map<String, Int> = emptyMap()

    /**
     * The type/grade pairs the library contains, and nothing else.
     *
     * This used to be a list of every climb paired with its crag: with a
     * four-thousand-crag library that is a couple of hundred thousand objects
     * held for the sake of two dropdowns and a search, and it was what finally
     * ran the heap out. The dropdowns only need the distinct combinations,
     * which is a few hundred.
     */
    private var kinds: List<Triple<String, String, Double>> = emptyList()
    private var typesHeld: List<String> = emptyList()
    private var here: Location? = null

    /** True once the sort has been asked for, so a refusal only complains then. */
    private var wantedNearest = false

    /** Set while the first-run guide is up, so nothing else asks over it. */
    private var guideShowing = false

    /**
     * Bumped for every render and every reload, so an answer computed for an
     * older query or an older library is dropped rather than drawn.
     */
    private var renderGeneration = 0
    private var reloadGeneration = 0

    /** Typing waits this long for the next letter before asking the library. */
    private val debounce = Handler(Looper.getMainLooper())
    private var scrollToTop = false

    /** Held so leaving the screen can stop its ticker and close it. */
    private var queueDialog: AlertDialog? = null
    private val queueTicker = Handler(Looper.getMainLooper())

    private val askLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allowed = result.values.any { it }
        if (allowed) {
            here = Nearby.lastKnown(this)
            if (wantedNearest) enableNearest() else render()
        } else {
            setNearest(false)

            // Only worth a word if they went looking for the sort.
            if (wantedNearest) {
                Toast.makeText(this, R.string.need_location, Toast.LENGTH_LONG).show()
            }
        }

        wantedNearest = false
    }

    /**
     * An import runs in the browser screen's WebView, which may be sitting
     * behind this one. A sleeping screen throttles it, so the list holds the
     * screen awake on its behalf.
     */
    private val whileImporting: (Boolean) -> Unit = { running ->
        if (running) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCragListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root, ime = true)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.add.setOnClickListener {
            startActivity(Intent(this, BrowseActivity::class.java))
        }

        // Distances are wanted on every row, not just when sorting by them.
        if (Nearby.granted(this)) here = Nearby.lastKnown(this)

        nearestFirst = Settings.nearestFirst(this) && Nearby.granted(this)
        binding.nearest.isChecked = nearestFirst
        binding.nearest.setOnClickListener { toggleNearest() }

        binding.filter.doAfterTextChanged {
            filter = it?.toString().orEmpty().trim().lowercase()
            render(delayMs = TYPING_PAUSE_MS, toTop = true)
        }

        binding.empty.setText(R.string.library_opening)
        binding.empty.visibility = View.VISIBLE
        binding.add.visibility = View.GONE

        ImportState.watch(whileImporting)

        // Once: the import is a search by place and distance, which nobody
        // guesses from a button that says "Add crags". Location is asked for
        // after it, not on top of it — or, if the guide sent the reader
        // straight off to add crags, when they come back.
        guideShowing = Guide.showOnce(this) { addingNow ->
            guideShowing = false
            if (!addingNow) askLocationOnce()
        }

        // A tap asks how it is going: a count alone cannot say whether a long
        // queue is crawling or just long. Starting it now is a button in there;
        // pausing is the long press, and the overflow entry.
        binding.queueLine.setOnClickListener { showQueueInfo() }
        binding.queueLine.setOnLongClickListener { toggleQueue(); true }

        // The library is opened here, off the main thread, before anything
        // else touches it: after an update the first open is a database
        // upgrade, and a main-thread query getting there first would wait it
        // out. Crag files not yet in the tables are brought in too, and the
        // rest of an upgrade finishes behind the list once it is showing.
        val app = applicationContext
        Thread {
            CragStore.open(app)
            runOnUiThread { if (!isDestroyed) onLibraryReady() }

            CragStore.finishUpgrade(app)
            runOnUiThread { if (!isDestroyed) reload() }
        }.start()
    }

    /** What waited for the library: the list, the queue and the weekly sync. */
    private fun onLibraryReady() {
        reload()

        // Whatever a search left behind gets read while the list is open,
        // a batch at a time. Stopping costs a batch, not the run.
        QueueDrain.start(this, binding.root) { reload() }

        // Opening the app is the only chance the sync gets: nothing here runs
        // while the app is closed.
        AutoSync.runIfDue(this, binding.root) { added ->
            // The sync outlives this screen, so it may land after it is gone.
            if (isFinishing || isDestroyed) return@runIfDue

            Toast.makeText(
                this,
                resources.getQuantityString(R.plurals.ticks_auto, added, added),
                Toast.LENGTH_LONG,
            ).show()
            reload()
        }
    }

    /** Location is asked for once; a refusal is not re-litigated. */
    private fun askLocationOnce() {
        if (Nearby.granted(this) || askedPrefs().getBoolean(KEY_ASKED, false)) return

        askedPrefs().edit().putBoolean(KEY_ASKED, true).apply()
        askLocation.launch(Nearby.PERMISSIONS)
    }

    /** Gets the reading going now, whatever it was waiting for. */
    private fun kickQueue() {
        with(ImportQueue) { queuePaused = false }

        QueueDrain.start(this, binding.root) { reload() }
        reload()

        Toast.makeText(this, R.string.queue_kicked, Toast.LENGTH_SHORT).show()
    }

    /**
     * How the queue is getting on, kept current while it is open: how far,
     * how fast, how long left, and the queue's own lines from the log — the
     * log screen holds them too, buried under every sync and import.
     *
     * The dialog and its ticker are the screen's, so leaving the screen stops
     * the ticker rather than leaving it running once a second for good.
     */
    private fun showQueueInfo() {
        closeQueueInfo()

        val view = DialogQueueInfoBinding.inflate(layoutInflater)
        val logFile = File(filesDir, "log.txt")
        var logSeen = -1L
        var logReading = false

        fun fill() {
            val stats = QueueDrain.Stats
            val left = ImportQueue.size(this)
            val paused = with(ImportQueue) { queuePaused }
            val handled = stats.read + stats.failed + stats.empty + stats.inBatch
            val elapsed = stats.elapsedMs()

            view.status.setText(
                when {
                    paused -> R.string.queue_info_paused
                    QueueDrain.busy() -> R.string.queue_info_reading
                    else -> R.string.queue_info_waiting
                }
            )

            view.bar.max = (handled + left).coerceAtLeast(1)
            view.bar.progress = handled

            val lines = mutableListOf(
                getString(R.string.queue_info_progress, handled, handled + left, left),
            )

            // Under half a minute or a handful of crags, a rate is mostly noise.
            val perMinute = if (elapsed > 30_000 && handled >= 5) handled * 60_000.0 / elapsed else 0.0
            lines += if (perMinute > 0) {
                getString(
                    R.string.queue_info_rate,
                    String.format(java.util.Locale.UK, "%.0f", perMinute),
                    duration((left / perMinute * 60_000).toLong()),
                )
            } else {
                getString(R.string.queue_info_rate_unknown)
            }

            lines += getString(R.string.queue_info_counts, stats.read, stats.failed, stats.empty)
            lines += getString(
                R.string.queue_info_batches,
                stats.batches, duration(stats.lastBatchMs), duration(elapsed),
            )
            lines += getString(R.string.queue_info_photos, TopoCache.queued())
            if (stats.throttles > 0) {
                lines += resources.getQuantityString(
                    R.plurals.queue_info_throttle, stats.throttles, stats.throttles, stats.spacingMs,
                )
            }
            lines += getString(R.string.queue_info_open)
            view.stats.text = lines.joinToString("\n")

            // The log is up to a quarter of a megabyte. It is read only when
            // it has grown, and then off the main thread.
            val length = logFile.length()
            if (length == logSeen || logReading) return
            logSeen = length
            logReading = true

            Thread {
                val log = AppLog.read(this).lineSequence()
                    .filter { "queue:" in it }
                    .toList()
                    .takeLast(60)
                    .joinToString("\n")

                runOnUiThread {
                    logReading = false
                    if (queueDialog?.isShowing != true) return@runOnUiThread

                    // Follow the end only when the reader is already there, so
                    // scrolling back to read an older line is not yanked away.
                    val atEnd = !view.logScroll.canScrollVertically(1)
                    view.log.text = log.ifBlank { getString(R.string.queue_info_log_empty) }
                    if (atEnd) view.logScroll.post { view.logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }.start()
        }

        val tick = object : Runnable {
            override fun run() {
                fill()
                queueTicker.postDelayed(this, 1000)
            }
        }

        val paused = with(ImportQueue) { queuePaused }

        queueDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.queue_info_title)
            .setView(view.root)
            .setPositiveButton(R.string.queue_info_read_now) { _, _ -> kickQueue() }
            .setNeutralButton(if (paused) R.string.queue_resume else R.string.queue_pause) { _, _ ->
                toggleQueue()
            }
            .setNegativeButton(R.string.close, null)
            .setOnDismissListener { queueTicker.removeCallbacksAndMessages(null) }
            .show()

        tick.run()
    }

    private fun closeQueueInfo() {
        queueTicker.removeCallbacksAndMessages(null)
        queueDialog?.dismiss()
        queueDialog = null
    }

    private fun duration(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0).toInt()
        return when {
            seconds >= 3600 -> getString(R.string.duration_hm, seconds / 3600, seconds % 3600 / 60)
            seconds >= 60 -> getString(R.string.duration_ms, seconds / 60, seconds % 60)
            else -> getString(R.string.duration_s, seconds)
        }
    }

    /** Stops or starts the queue: the long press, and the overflow entry. */
    private fun toggleQueue() {
        with(ImportQueue) { queuePaused = !queuePaused }

        if (!with(ImportQueue) { queuePaused }) {
            QueueDrain.start(this, binding.root) { reload() }
        }

        reload()
    }

    /**
     * Reads the library in: rows, filters and ticked counts. This happens on
     * arrival, on the way back and after each queue batch, off the main
     * thread, rather than inside render(), which runs as the search is typed.
     */
    private fun reload() {
        if (!CragStore.ready) return

        val generation = ++reloadGeneration
        val app = applicationContext

        Thread {
            // Rows, not crags: names, counts and positions straight out of the
            // index. Nothing here reads a climb.
            val cards = CragStore.cards(app)
            val found = CragDb.kinds(app)
            val counts = CragDb.countsByCrag(app, Ticks(app).all())

            runOnUiThread {
                if (isDestroyed || generation != reloadGeneration) return@runOnUiThread

                library = cards
                kinds = found
                tickedByCrag = counts
                typesHeld = found.map { (kind, _, _) -> kind }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()

                setUpClimbFilters()
                render()
                invalidateOptionsMenu()
            }
        }.start()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.crag_list, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Only worth showing while there is a queue to talk about.
        val left = queuedCount
        val paused = with(ImportQueue) { queuePaused }

        menu.findItem(R.id.queue)?.apply {
            isVisible = left > 0
            setTitle(if (paused) R.string.queue_resume else R.string.queue_pause)
        }

        menu.findItem(R.id.queue_forget)?.isVisible = left > 0

        // The sign-in state matters enough to show on the closed menu, not only
        // inside the submenu that holds it.
        menu.findItem(R.id.logbook_group)?.setTitle(
            if (Session.signedIn(this)) R.string.menu_logbook_in
            else R.string.menu_logbook_out
        )

        Session.describeIn(this, menu)
        menu.findItem(R.id.logbook_group)?.subMenu?.let { Session.describeIn(this, it) }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Everything behind these reads the library, which is not open yet in
        // the first moments after an update.
        val needsLibrary = item.itemId in setOf(R.id.map, R.id.lists, R.id.refresh, R.id.clear)
        if (needsLibrary && !CragStore.ready) {
            Toast.makeText(this, R.string.library_not_ready, Toast.LENGTH_SHORT).show()
            return true
        }

        when (item.itemId) {
            R.id.map -> {
                startActivity(Intent(this, MapActivity::class.java))
                return true
            }
            R.id.refresh_location -> {
                refreshLocation()
                return true
            }
            R.id.lists -> {
                startActivity(Intent(this, ListsActivity::class.java))
                return true
            }
            R.id.add -> {
                startActivity(Intent(this, BrowseActivity::class.java))
                return true
            }
            R.id.queue -> {
                toggleQueue()
                return true
            }
            R.id.queue_forget -> {
                confirmForgetQueue()
                return true
            }
            R.id.refresh -> {
                refreshEverything()
                return true
            }
            R.id.sign_in -> {
                // Opens UKC's own sign-in page in the in-app browser, where
                // autofill can fill it; the app never handles the credentials.
                startActivity(
                    Intent(this, BrowseActivity::class.java)
                        .putExtra(BrowseActivity.EXTRA_URL, getString(R.string.login_url))
                        .putExtra(BrowseActivity.EXTRA_SIGN_IN, true)
                )
                return true
            }
            R.id.sync_ticks -> {
                startActivity(
                    Intent(this, BrowseActivity::class.java)
                        .putExtra(BrowseActivity.EXTRA_SYNC, true)
                )
                return true
            }
            R.id.clear -> {
                confirmClear()
                return true
            }
            R.id.browse -> {
                Maps.openUrl(this, getString(R.string.crag_index_url))
                return true
            }
            R.id.settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    /** A whole search's worth of queued crags is one tap to lose, so say how many. */
    private fun confirmForgetQueue() {
        val left = ImportQueue.size(this)
        if (left == 0) return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.queue_forget)
            .setMessage(resources.getQuantityString(R.plurals.queue_forget_confirm, left, left))
            .setPositiveButton(R.string.queue_forget_go) { _, _ ->
                ImportQueue.clear(this)
                with(ImportQueue) { queuePaused = false }
                reload()
                Toast.makeText(this, R.string.queue_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Re-reads the whole library through the queue rather than in one sitting.
     *
     * A refresh of four thousand crags is the same work as importing them, and
     * had the same problem: one long run that lost its place if anything
     * interrupted it. Queued, it survives being stopped, and it can be paused
     * when the phone is needed for something else.
     */
    private fun refreshEverything() {
        val held = library.map { Queued(it.area, it.sourceUrl) }

        if (held.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_refresh, Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.refresh_crags)
            .setMessage(
                resources.getQuantityString(R.plurals.crags_left, held.size, held.size)
            )
            .setPositiveButton(R.string.refresh_crags) { _, _ ->
                // Held crags are the point of a refresh, so nothing is skipped.
                val added = ImportQueue.add(this, held, skipHeld = false)
                with(ImportQueue) { queuePaused = false }

                QueueDrain.start(this, binding.root) { reload() }
                reload()

                Toast.makeText(
                    this,
                    resources.getQuantityString(R.plurals.queued, added, added),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Wiping the library is easy to do by accident, so name the cost first. */
    private fun confirmClear() {
        val count = library.size

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_crags)
            .setMessage(resources.getQuantityString(R.plurals.clear_crags_warning, count, count))
            .setPositiveButton(R.string.clear_crags) { _, _ ->
                // Thousands of files and a database; not on the main thread.
                val app = applicationContext
                Thread {
                    CragStore.clear(app)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        reload()
                        Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setNearest(on: Boolean) {
        nearestFirst = on
        binding.nearest.isChecked = on
        Settings.setNearestFirst(this, on)
    }

    private fun toggleNearest() {
        if (nearestFirst) {
            // Only the sort goes; the distances stay on the rows.
            setNearest(false)
            render(toTop = true)
            return
        }

        // The chip checks itself on a tap; it is only really on once there is
        // a fix to sort by.
        binding.nearest.isChecked = false

        if (Nearby.granted(this)) {
            enableNearest()
        } else {
            wantedNearest = true
            askLocation.launch(Nearby.PERMISSIONS)
        }
    }

    /**
     * Asks for a new fix. The distances on these rows come from whatever fix
     * the phone happened to be holding, which can be a town away by the time
     * the reader is standing under the crag.
     */
    private fun refreshLocation() {
        if (!Nearby.granted(this)) {
            wantedNearest = false
            askLocation.launch(Nearby.PERMISSIONS)
            return
        }

        Toast.makeText(this, R.string.locating, Toast.LENGTH_SHORT).show()

        Nearby.refresh(this) { fix ->
            if (fix == null) {
                Toast.makeText(this, R.string.no_location, Toast.LENGTH_LONG).show()
                return@refresh
            }

            here = fix
            Toast.makeText(this, R.string.location_fresh, Toast.LENGTH_SHORT).show()
            render()
        }
    }

    private fun enableNearest() {
        here = Nearby.lastKnown(this)

        if (here == null) {
            setNearest(false)
            Toast.makeText(this, R.string.no_location, Toast.LENGTH_LONG).show()
        } else {
            setNearest(true)
        }

        render(toTop = true)
    }

    override fun onDestroy() {
        ImportState.forget(whileImporting)
        debounce.removeCallbacksAndMessages(null)
        closeQueueInfo()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        here = Nearby.lastKnown(this) ?: here

        // Back from adding crags on the guide's say-so: now location can be asked.
        if (!guideShowing && Settings.guideSeen(this)) askLocationOnce()

        // Before the library is open this is the arrival itself, and the
        // opening thread does the first read.
        if (!CragStore.ready) return

        // An import may have added crags, and with them types and grades.
        reload()

        QueueDrain.start(this, binding.root) { reload() }

        // Signing in happens in the browser, so re-label the menu on the way back.
        invalidateOptionsMenu()
    }

    private fun askedPrefs() = getSharedPreferences("location", MODE_PRIVATE)

    /**
     * Type and grade come from the whole library, so the crag list can be cut
     * down to "where can I climb font 6A" without opening each crag. The grade
     * list follows the chosen type, since a font grade means nothing to a trad
     * climber and the combined list would run to hundreds.
     */
    private fun setUpClimbFilters() {
        val typeLabels = listOf(getString(R.string.all_types)) + typesHeld

        binding.type.setSimpleItems(typeLabels.toTypedArray())
        binding.type.setText(if (type.isEmpty()) typeLabels.first() else type, false)
        binding.typeBox.visibility = if (typesHeld.size < 2) View.GONE else View.VISIBLE

        binding.type.setOnItemClickListener { _, _, position, _ ->
            type = if (position == 0) "" else typeLabels[position]
            grades.clear()
            setUpGrades()
            render(toTop = true)
        }

        setUpGrades()
    }

    private fun setUpGrades() {
        // Ordered by UKC's own score, so f5 sits below f6A rather than beside
        // it; each group sits where its easiest member does.
        val groups = LinkedHashMap<String, MutableSet<String>>()
        kinds
            .filter { (kind, _, _) -> type.isEmpty() || kind.equals(type, ignoreCase = true) }
            .sortedBy { (_, _, score) -> score }
            .forEach { (_, grade, _) -> groups.getOrPut(gradeGroup(grade)) { linkedSetOf() }.add(grade) }

        gradeGroups = groups
        val offered = groups.keys.toList()

        // A type change can strand a grade that no longer exists.
        grades.retainAll(offered.toSet())

        binding.gradeBox.visibility = if (offered.size < 2) View.GONE else View.VISIBLE
        showGradeLabel()

        binding.gradeBox.setOnClickListener { pickGrades(offered) }
    }

    /** Every raw grade the chosen groups stand for, which is what the index holds. */
    private fun chosenGrades(): Set<String> =
        grades.flatMapTo(HashSet()) { gradeGroups[it].orEmpty() }

    private fun showGradeLabel() {
        binding.gradeBox.text = when {
            grades.isEmpty() -> getString(R.string.any_grade)
            grades.size == 1 -> grades.first()
            else -> resources.getQuantityString(R.plurals.grades_chosen, grades.size, grades.size)
        }
    }

    private fun pickGrades(offered: List<String>) {
        val checked = offered.map { it in grades }.toBooleanArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.grade)
            .setMultiChoiceItems(offered.toTypedArray(), checked) { _, which, on ->
                if (on) grades.add(offered[which]) else grades.remove(offered[which])
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showGradeLabel()
                render(toTop = true)
            }
            .setNeutralButton(R.string.any_grade) { _, _ ->
                grades.clear()
                showGradeLabel()
                render(toTop = true)
            }
            .show()
    }

    /** Last queue size seen, for the menu, which must not read the file itself. */
    private var queuedCount = 0

    /** What a render needs, taken on the main thread and handed to a worker. */
    private class Ask(
        val filter: String,
        val type: String,
        val grades: Set<String>,
        val nearest: Boolean,
        val here: Location?,
        val library: List<CragCard>,
        val ticked: Map<String, Int>,
    )

    private class Answer(val rows: List<Row>, val queued: Int, val paused: Boolean)

    /**
     * Redraws the list for the current search, filters and sort.
     *
     * The asking is done on a worker: a search is a full-text query, the
     * filters another, and the queue a file read, and doing all that per
     * keystroke on the main thread made typing stutter. [delayMs] lets a word
     * be typed before any of it starts; only the newest answer is drawn.
     */
    private fun render(delayMs: Long = 0, toTop: Boolean = false) {
        if (toTop) scrollToTop = true
        debounce.removeCallbacksAndMessages(null)

        if (!CragStore.ready) return

        debounce.postDelayed({ startRender() }, delayMs)
    }

    private fun startRender() {
        val generation = ++renderGeneration
        val app = applicationContext
        val ask = Ask(
            filter = filter,
            type = type,
            grades = chosenGrades(),
            nearest = nearestFirst,
            here = here,
            library = library,
            ticked = tickedByCrag,
        )

        Thread {
            val answer = answer(app, ask)

            runOnUiThread {
                if (isDestroyed || generation != renderGeneration) return@runOnUiThread
                show(answer)
            }
        }.start()
    }

    private fun answer(app: android.content.Context, ask: Ask): Answer {
        val filtered = ask.type.isNotEmpty() || ask.grades.isNotEmpty()

        // One query for the whole filter, rather than a walk per crag.
        val holding = if (filtered) CragDb.cragsHolding(app, ask.type, ask.grades) else emptySet()

        val matched = ask.library
            .filter { crag ->
                (ask.filter.isEmpty() || crag.area.lowercase().contains(ask.filter)) &&
                    (!filtered || crag.id in holding)
            }
            .map { it to it.metresFrom(ask.here) }

        // Crags with no pin can't be ranked, so they sink to the bottom.
        val crags = if (ask.nearest && ask.here != null) {
            matched.sortedWith(compareBy(nullsLast()) { it.second })
        } else {
            matched
        }

        // A search reads climb names too: half-remembering a name is no reason
        // to have to remember which crag it was at. With no search there is
        // nothing to narrow the climbs by, so the list stays the crag library.
        // Capped, with one more asked for to know whether the cap bit.
        val asked = if (ask.filter.isEmpty()) {
            emptyList()
        } else {
            CragDb.searchClimbs(app, ask.filter, ask.type, ask.grades, CLIMB_HITS + 1)
        }
        val capped = asked.size > CLIMB_HITS
        val found = asked.take(CLIMB_HITS)

        val ticks = Ticks(app)
        val rows = mutableListOf<Row>()

        if (crags.isNotEmpty()) {
            // Labels only earn their space once both kinds of hit are listed.
            if (found.isNotEmpty()) {
                rows += Row.Label(
                    app.resources.getQuantityString(R.plurals.crags_found, crags.size, crags.size)
                )
            }
            crags.forEach { (crag, metres) ->
                rows += Row.CragHit(crag, ask.ticked[crag.id] ?: 0, metres)
            }
        }

        if (found.isNotEmpty()) {
            rows += Row.Label(
                if (capped) app.getString(R.string.climbs_capped, CLIMB_HITS)
                else app.resources.getQuantityString(R.plurals.climbs, found.size, found.size)
            )
            found.forEach { rows += Row.ClimbRow(it, ticks.has(it.url)) }
        }

        return Answer(rows, ImportQueue.size(app), with(ImportQueue) { app.queuePaused })
    }

    private fun show(answer: Answer) {
        val rows = answer.rows

        // The queue line says what the shade says, so tapping through from the
        // notification lands on something that agrees with it.
        if (queuedCount != answer.queued) {
            queuedCount = answer.queued
            invalidateOptionsMenu()
        }

        binding.queueLine.visibility = if (answer.queued > 0) View.VISIBLE else View.GONE
        binding.queueLine.text = when {
            answer.queued == 0 -> ""
            answer.paused -> getString(R.string.queue_paused)
            else -> resources.getQuantityString(R.plurals.crags_left, answer.queued, answer.queued)
        }

        binding.empty.setText(if (library.isEmpty()) R.string.no_crags else R.string.no_matches)
        binding.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        // The button is only worth its screen space on an empty library; once
        // there are crags, adding more lives in the menu.
        binding.add.visibility = if (library.isEmpty()) View.VISIBLE else View.GONE

        // Diffed in, so a batch landing or a tick arriving keeps the reader's
        // place; only a new search or sort goes back to the top.
        val toTop = scrollToTop
        scrollToTop = false
        adapter.submitList(rows) { if (toTop) binding.list.scrollToPosition(0) }
    }

    /** A row of results: a section label, a crag, or a climb inside one. */
    private sealed interface Row {
        data class Label(val text: String) : Row
        data class CragHit(val crag: CragCard, val ticked: Int, val metres: Float?) : Row
        data class ClimbRow(val hit: ClimbHit, val done: Boolean) : Row
    }

    private fun bindCrag(item: ItemCragBinding, row: Row.CragHit) {
        val crag = row.crag

        item.name.text = crag.area
        item.detail.text = getString(
            R.string.crag_detail_located,
            resources.getQuantityString(R.plurals.climbs, crag.climbCount, crag.climbCount),
            resources.getQuantityString(
                R.plurals.buttresses,
                crag.buttressCount,
                crag.buttressCount,
            ),
            crag.locatedButtresses,
        )

        val ticked = resources.getQuantityString(
            R.plurals.crag_ticked, crag.climbCount, row.ticked, crag.climbCount,
        )

        item.progress.text = if (row.metres != null) {
            Units.distance(this, row.metres) + " · " + ticked
        } else {
            ticked
        }

        item.root.setOnClickListener {
            startActivity(CragActivity.intent(this, crag.id, crag.area))
        }

        item.root.setOnLongClickListener {
            cragActions(crag)
            true
        }
    }

    /**
     * The long press used to be a hidden refresh. It is now the crag's short
     * menu, so what it does is written on it.
     */
    private fun cragActions(crag: CragCard) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()

        actions += getString(R.string.open_crag) to {
            startActivity(CragActivity.intent(this, crag.id, crag.area))
        }

        if (crag.hasPin || crag.locatedButtresses > 0) {
            actions += getString(R.string.map_crag) to {
                startActivity(CragActivity.mapIntent(this, crag.id, crag.area))
            }
        }

        val parking = CragDb.parking(this, crag.id).map { Parking(it.name, it.latitude, it.longitude) }
        if (crag.hasPin || parking.isNotEmpty()) {
            actions += getString(R.string.directions) to {
                Maps.directionsTo(
                    this, crag.area, crag.latitude, crag.longitude, parking,
                    choose = Settings.asksBetween(this, crag.hasPin, parking.isNotEmpty()),
                )
            }
        }

        actions += getString(R.string.refresh_this_crag) to {
            startActivity(
                Intent(this, BrowseActivity::class.java)
                    .putExtra(BrowseActivity.EXTRA_REFRESH, true)
                    .putExtra(BrowseActivity.EXTRA_REFRESH_URL, crag.sourceUrl)
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(crag.area)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun bindClimb(item: ItemFoundBinding, row: Row.ClimbRow) {
        val hit = row.hit

        item.name.text = hit.name
        item.grade.text = hit.grade
        item.meta.text = buildString {
            append(hit.cragArea)
            if (hit.type.isNotBlank()) append(" · ").append(hit.type)
            if (hit.stars > 0) append(" · ").append("★".repeat(hit.stars))
            if (row.done) append(" · ").append(getString(R.string.ticked))
        }

        item.name.alpha = if (row.done) 0.45f else 1f

        // Opens the crag at this climb, with the climb itself open.
        item.root.setOnClickListener {
            startActivity(CragActivity.intent(this, hit.cragId, hit.cragArea, hit.url))
        }

        item.root.setOnLongClickListener(null)
    }

    private inner class ResultAdapter : ListAdapter<Row, RecyclerView.ViewHolder>(RowDiff) {

        inner class LabelHolder(val item: ItemSectionBinding) :
            RecyclerView.ViewHolder(item.root)

        inner class CragHolder(val item: ItemCragBinding) :
            RecyclerView.ViewHolder(item.root)

        inner class ClimbHolder(val item: ItemFoundBinding) :
            RecyclerView.ViewHolder(item.root)

        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is Row.Label -> TYPE_LABEL
            is Row.CragHit -> TYPE_CRAG
            is Row.ClimbRow -> TYPE_CLIMB
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)

            return when (viewType) {
                TYPE_LABEL -> LabelHolder(ItemSectionBinding.inflate(inflater, parent, false))
                TYPE_CRAG -> CragHolder(ItemCragBinding.inflate(inflater, parent, false))
                else -> ClimbHolder(ItemFoundBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = getItem(position)) {
                is Row.Label -> (holder as LabelHolder).item.label.text = row.text
                is Row.CragHit -> bindCrag((holder as CragHolder).item, row)
                is Row.ClimbRow -> bindClimb((holder as ClimbHolder).item, row)
            }
        }
    }

    private object RowDiff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(old: Row, new: Row): Boolean = when {
            old is Row.Label && new is Row.Label -> true
            old is Row.CragHit && new is Row.CragHit -> old.crag.id == new.crag.id
            old is Row.ClimbRow && new is Row.ClimbRow -> old.hit.url == new.hit.url
            else -> false
        }

        override fun areContentsTheSame(old: Row, new: Row): Boolean = old == new
    }

    private companion object {
        /** Location is asked for once; a refusal is not re-litigated. */
        const val KEY_ASKED = "asked"

        /** Most climb hits a search will list. Beyond this, refine the words. */
        const val CLIMB_HITS = 200

        /** Long enough for the next letter of a word, short enough not to feel slow. */
        const val TYPING_PAUSE_MS = 150L

        const val TYPE_LABEL = 0
        const val TYPE_CRAG = 1
        const val TYPE_CLIMB = 2

        /** A trailing British technical grade: "5b", "4c", "5a/b", "6a+". */
        private val TECH_GRADE = Regex("""\s+[1-7][abc][+-]?(?:/[1-7]?[abc][+-]?)?$""")

        /**
         * The adjectival part of a British trad grade, or the grade as it is.
         * "E1 5b" and "E1 5c" both answer to "E1"; a sport "6a" has no
         * adjectival part before it and is left alone.
         */
        fun gradeGroup(grade: String): String {
            val trimmed = grade.trim()
            val stripped = trimmed.replace(TECH_GRADE, "")
            return if (stripped != trimmed && stripped.any { it.isLetter() }) {
                stripped
            } else {
                trimmed
            }
        }
    }
}
