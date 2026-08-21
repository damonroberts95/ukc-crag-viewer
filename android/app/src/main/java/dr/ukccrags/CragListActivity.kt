package dr.ukccrags

import android.content.Intent
import android.location.Location
import android.os.Bundle
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dr.ukccrags.databinding.ActivityCragListBinding
import dr.ukccrags.databinding.ItemCragBinding
import dr.ukccrags.databinding.ItemFoundBinding
import dr.ukccrags.databinding.ItemSectionBinding

class CragListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCragListBinding
    private lateinit var ticks: Ticks

    private var nearestFirst = false
    private var filter: String = ""
    private var type: String = ""

    /** Grades kept. Empty means every grade. */
    private val grades = linkedSetOf<String>()

    /** The library, read on arrival rather than on every keystroke. */
    private var library: List<CragCard> = emptyList()

    /**
     * The type/grade pairs the library contains, and nothing else.
     *
     * This used to be a list of every climb paired with its crag: with a
     * four-thousand-crag library that is a couple of hundred thousand objects
     * held for the sake of two dropdowns and a search, and it was what finally
     * ran the heap out. The dropdowns only need the distinct combinations,
     * which is a few hundred, and the search walks the library as a sequence
     * without building anything.
     */
    private var kinds: List<Triple<String, String, Double>> = emptyList()
    private var typesHeld: List<String> = emptyList()
    private var here: Location? = null

    /** True once the sort has been asked for, so a refusal only complains then. */
    private var wantedNearest = false

    private val askLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allowed = result.values.any { it }
        if (allowed) {
            here = Nearby.lastKnown(this)
            if (wantedNearest) enableNearest() else render()
        } else {
            nearestFirst = false
            invalidateOptionsMenu()

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
        applySystemBarInsets(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.add.setOnClickListener {
            startActivity(Intent(this, BrowseActivity::class.java))
        }

        // Distances are wanted on every row, not just when sorting by them, so
        // the fix is fetched up front and asked for once.
        if (Nearby.granted(this)) {
            here = Nearby.lastKnown(this)
        } else if (!askedPrefs().getBoolean(KEY_ASKED, false)) {
            askedPrefs().edit().putBoolean(KEY_ASKED, true).apply()
            askLocation.launch(Nearby.PERMISSIONS)
        }

        binding.filter.doAfterTextChanged {
            filter = it?.toString().orEmpty().trim().lowercase()
            render()
        }

        reload()
        setUpClimbFilters()

        render()

        ImportState.watch(whileImporting)

        binding.queueLine.setOnClickListener { toggleQueue() }

        // Crags scraped before the database existed are still JSON files. Moving
        // them in is a one-off, off the main thread, and the list fills in as it
        // goes rather than sitting empty until it finishes.
        Thread {
            CragStore.open(this)
            runOnUiThread { if (!isFinishing) refreshFromStore() }
        }.start()

        // Whatever a search left behind gets read while the list is open,
        // a batch at a time. Stopping costs a batch, not the run.
        QueueDrain.start(this, binding.root) { refreshFromStore() }

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
            render()
        }
    }

    /** Pulls the list, the filters and the queue line back into agreement. */
    private fun refreshFromStore() {
        reload()
        setUpClimbFilters()
        render()
        invalidateOptionsMenu()
    }

    /** Stops or starts the queue, from the line at the top of the list. */
    private fun toggleQueue() {
        with(ImportQueue) { queuePaused = !queuePaused }

        if (!with(ImportQueue) { queuePaused }) {
            QueueDrain.start(this, binding.root) { refreshFromStore() }
        }

        refreshFromStore()
    }

    /**
     * Reads the library in. Every crag is its own file, so this happens on
     * arrival and on the way back rather than inside render(), which runs on
     * every letter typed into the search box.
     */
    private fun reload() {
        // Rows, not crags: names, counts and positions straight out of the
        // index. Nothing here reads a climb.
        library = CragStore.cards(this)
        kinds = CragDb.kinds(this)
        typesHeld = kinds.map { (type, _, _) -> type }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.crag_list, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.nearest)?.isChecked = nearestFirst

        // Only worth showing while there is a queue to talk about.
        val left = ImportQueue.size(this)
        val paused = with(ImportQueue) { queuePaused }

        menu.findItem(R.id.queue)?.apply {
            isVisible = left > 0
            title = when {
                paused -> getString(R.string.queue_paused)
                else -> resources.getQuantityString(R.plurals.crags_left, left, left)
            }
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
        when (item.itemId) {
            R.id.nearest -> {
                toggleNearest()
                return true
            }
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
                with(ImportQueue) { queuePaused = !queuePaused }

                if (!with(ImportQueue) { queuePaused }) {
                    QueueDrain.start(this, binding.root) { refreshFromStore() }
                }

                invalidateOptionsMenu()
                return true
            }
            R.id.queue_forget -> {
                ImportQueue.clear(this)
                with(ImportQueue) { queuePaused = false }
                invalidateOptionsMenu()
                Toast.makeText(this, R.string.queue_forget, Toast.LENGTH_SHORT).show()
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
            R.id.log -> {
                startActivity(Intent(this, LogActivity::class.java))
                return true
            }
            R.id.updates -> {
                Updates.check(this)
                return true
            }
        }

        return super.onOptionsItemSelected(item)
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

                QueueDrain.start(this, binding.root) { refreshFromStore() }
                invalidateOptionsMenu()

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
            .setMessage(getString(R.string.clear_crags_warning, count))
            .setPositiveButton(R.string.clear_crags) { _, _ ->
                CragStore.clear(this)
                reload()
                setUpClimbFilters()
                render()
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleNearest() {
        if (nearestFirst) {
            // Only the sort goes; the distances stay on the rows.
            nearestFirst = false
            invalidateOptionsMenu()
            render()
            return
        }

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
            nearestFirst = false
            Toast.makeText(this, R.string.no_location, Toast.LENGTH_LONG).show()
        } else {
            nearestFirst = true
        }

        invalidateOptionsMenu()
        render()
    }

    override fun onDestroy() {
        ImportState.forget(whileImporting)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        here = Nearby.lastKnown(this) ?: here

        // An import may have added crags, and with them types and grades.
        reload()
        setUpClimbFilters()

        QueueDrain.start(this, binding.root) { refreshFromStore() }

        // Signing in happens in the browser, so re-label the menu on the way back.
        invalidateOptionsMenu()
        render()
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
            render()
        }

        setUpGrades()
    }

    private fun setUpGrades() {
        // Ordered by UKC's own score, so f5 sits below f6A rather than beside it.
        val offered = kinds
            .filter { (kind, _, _) -> type.isEmpty() || kind.equals(type, ignoreCase = true) }
            .sortedBy { (_, _, score) -> score }
            .map { (_, grade, _) -> grade }
            .distinct()

        // A type change can strand a grade that no longer exists.
        grades.retainAll(offered.toSet())

        binding.gradeBox.visibility = if (offered.size < 2) View.GONE else View.VISIBLE
        showGradeLabel()

        binding.gradeBox.setOnClickListener { pickGrades(offered) }
    }

    private fun showGradeLabel() {
        binding.gradeBox.text = when {
            grades.isEmpty() -> getString(R.string.any_grade)
            grades.size == 1 -> grades.first()
            else -> getString(R.string.grades_chosen, grades.size)
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
                render()
            }
            .setNeutralButton(R.string.any_grade) { _, _ ->
                grades.clear()
                showGradeLabel()
                render()
            }
            .show()
    }

    /** Crag ids holding a wanted climb, asked of the index once per render. */
    private var holding: Set<String> = emptySet()

    /** True when a climb passes the chosen type and grades. */
    private fun wantedClimb(climb: Climb): Boolean =
        (type.isEmpty() || climb.type.equals(type, ignoreCase = true)) &&
            (grades.isEmpty() || grades.any { it.equals(climb.grade, true) })

    /** True when the crag holds a climb of the chosen type and any chosen grade. */
    private fun holdsWanted(crag: CragCard): Boolean {
        if (type.isEmpty() && grades.isEmpty()) return true
        return crag.id in holding
    }

    private fun render() {
        ticks = Ticks(this)

        // One query for the whole filter, rather than a walk per crag.
        holding = if (type.isEmpty() && grades.isEmpty()) {
            emptySet()
        } else {
            CragDb.cragsHolding(this, type, grades)
        }

        val matched = library.filter { crag ->
            (filter.isEmpty() || crag.area.lowercase().contains(filter)) && holdsWanted(crag)
        }

        val crags = if (nearestFirst && here != null) {
            matched.sortedWith(
                // Crags with no pin can't be ranked, so they sink to the bottom.
                compareBy(nullsLast()) { it.metresFrom(here) }
            )
        } else {
            matched
        }

        // A search reads climb names too: half-remembering a name is no reason
        // to have to remember which crag it was at. With no search there is
        // nothing to narrow the climbs by, so the list stays the crag library.
        // A sequence, and capped: a two-letter query against a library this
        // size otherwise builds tens of thousands of rows nobody scrolls to.
        val found = if (filter.isEmpty()) {
            emptyList()
        } else {
            CragDb.searchClimbs(this, filter, type, grades, CLIMB_HITS)
        }

        val rows = mutableListOf<Row>()

        if (crags.isNotEmpty()) {
            // Labels only earn their space once both kinds of hit are listed.
            if (found.isNotEmpty()) {
                rows += Row.Label(
                    resources.getQuantityString(R.plurals.crags_found, crags.size, crags.size)
                )
            }
            crags.forEach { rows += Row.CragHit(it) }
        }

        if (found.isNotEmpty()) {
            rows += Row.Label(
                resources.getQuantityString(R.plurals.climbs, found.size, found.size)
            )
            found.forEach { rows += Row.ClimbRow(it) }
        }

        // The queue line says what the shade says, so tapping through from the
        // notification lands on something that agrees with it.
        val queued = ImportQueue.size(this)
        val paused = with(ImportQueue) { queuePaused }

        binding.queueLine.visibility = if (queued > 0) View.VISIBLE else View.GONE
        binding.queueLine.text = when {
            queued == 0 -> ""
            paused -> getString(R.string.queue_paused)
            else -> resources.getQuantityString(R.plurals.crags_left, queued, queued)
        }

        binding.empty.setText(if (library.isEmpty()) R.string.no_crags else R.string.no_matches)
        binding.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        // The button is only worth its screen space on an empty library; once
        // there are crags, adding more lives in the menu.
        binding.add.visibility = if (library.isEmpty()) View.VISIBLE else View.GONE
        binding.list.adapter = ResultAdapter(rows)
    }

    /** A row of results: a section label, a crag, or a climb inside one. */
    private sealed interface Row {
        data class Label(val text: String) : Row
        data class CragHit(val crag: CragCard) : Row
        data class ClimbRow(val hit: ClimbHit) : Row
    }

    private fun bindCrag(item: ItemCragBinding, crag: CragCard) {
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

        val ticked = getString(
            R.string.crag_progress, ticks.countIn(this, crag.id), crag.climbCount,
        )
        val metres = crag.metresFrom(here)

        item.progress.text = if (metres != null) {
            Units.distance(this, metres) + " · " + ticked
        } else {
            ticked
        }

        item.root.setOnClickListener {
            startActivity(
                Intent(this, CragActivity::class.java)
                    .putExtra(CragActivity.EXTRA_AREA, crag.area)
            )
        }

        // Long press re-reads just this crag, topo photos included.
        item.root.setOnLongClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(crag.area)
                .setMessage(R.string.refresh_this_crag)
                .setPositiveButton(R.string.refresh_this_crag) { _, _ ->
                    startActivity(
                        Intent(this, BrowseActivity::class.java)
                            .putExtra(BrowseActivity.EXTRA_REFRESH, true)
                            .putExtra(BrowseActivity.EXTRA_REFRESH_URL, crag.sourceUrl)
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }

    private fun bindClimb(item: ItemFoundBinding, hit: ClimbHit) {
        val done = ticks.has(hit.url)

        item.name.text = hit.name
        item.grade.text = hit.grade
        item.meta.text = buildString {
            append(hit.cragArea)
            if (hit.type.isNotBlank()) append(" · ").append(hit.type)
            if (hit.stars > 0) append(" · ").append("★".repeat(hit.stars))
            if (done) append(" · ").append(getString(R.string.ticked))
        }

        item.name.alpha = if (done) 0.45f else 1f

        // Opens the crag with this climb already filtered for.
        item.root.setOnClickListener {
            startActivity(
                Intent(this, CragActivity::class.java)
                    .putExtra(CragActivity.EXTRA_AREA, hit.cragArea)
                    .putExtra(CragActivity.EXTRA_FIND, hit.name)
            )
        }

        item.root.setOnLongClickListener(null)
    }

    private inner class ResultAdapter(private val rows: List<Row>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        inner class LabelHolder(val item: ItemSectionBinding) :
            RecyclerView.ViewHolder(item.root)

        inner class CragHolder(val item: ItemCragBinding) :
            RecyclerView.ViewHolder(item.root)

        inner class ClimbHolder(val item: ItemFoundBinding) :
            RecyclerView.ViewHolder(item.root)

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
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
            when (val row = rows[position]) {
                is Row.Label -> (holder as LabelHolder).item.label.text = row.text
                is Row.CragHit -> bindCrag((holder as CragHolder).item, row.crag)
                is Row.ClimbRow -> bindClimb((holder as ClimbHolder).item, row.hit)
            }
        }
    }

    private companion object {
        /** Location is asked for once; a refusal is not re-litigated. */
        const val KEY_ASKED = "asked"

        /** Most climb hits a search will list. Beyond this, refine the words. */
        const val CLIMB_HITS = 200

        const val TYPE_LABEL = 0
        const val TYPE_CRAG = 1
        const val TYPE_CLIMB = 2
    }

}
