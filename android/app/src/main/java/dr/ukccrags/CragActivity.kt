package dr.ukccrags

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dr.ukccrags.databinding.ActivityCragBinding
import dr.ukccrags.databinding.ItemRouteBinding
import dr.ukccrags.databinding.ItemSectorBinding

/** One row in the flattened sector/route list. */
private sealed interface Row {
    data class ButtressRow(val buttress: Buttress, val shown: Int) : Row

    /**
     * [buttress] is set only when the list is sorted and the headers are gone.
     * [mark] is carried in the row so a change of tick or note redraws it.
     */
    data class ClimbRow(val climb: Climb, val buttress: String = "", val mark: Mark, val wished: Boolean) : Row
}

/** What the tick slot on a climb row shows, strongest first. */
private enum class Mark { TICKED, TO_LOG, ATTEMPTED, NONE }

/** How the climb list is ordered. UKC's own order is the default. */
private enum class Sort { UKC, NAME, GRADE, STARS }

class CragActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCragBinding
    private var crag: Crag = Crag(
        area = "",
        sourceUrl = "",
        latitude = null,
        longitude = null,
        climbCount = 0,
        buttresses = emptyList(),
    )

    /** Null until the crag has been read, so nothing acts on the empty stand-in above. */
    private var loaded = false

    /** When the crag's file was read, so coming back only re-reads a refreshed one. */
    private var readStamp = 0L

    private lateinit var ticks: Ticks
    private lateinit var attempts: Attempts
    private lateinit var wishlist: Wishlist
    private lateinit var toLog: ToLog

    private val adapter = RowAdapter()

    private var query: String = ""
    private var type: String = ""
    private var sort: Sort = Sort.UKC

    /**
     * Set when this visit came for one climb. The remembered type filter is
     * then left alone for the visit: a trad climb found by search must not
     * open hidden behind the bouldering filter set last time.
     */
    private var cameForClimb = false

    /**
     * The bouldering grade system rows are drawn in, read on each resume: a
     * change in Settings redraws rows whose data did not change, which a diff
     * alone would never do.
     */
    private var gradeSystem = BoulderGrades.FONT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gradeSystem = BoulderGrades.system(this)

        binding = ActivityCragBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root, ime = true)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        ticks = Ticks(this)
        attempts = Attempts(this)
        wishlist = Wishlist(this)
        toLog = ToLog(this)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        // The keyboard wants the room more than the topo and photo buttons do.
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { bar, insets ->
            bar.visibility =
                if (insets.isVisible(WindowInsetsCompat.Type.ime())) View.GONE else View.VISIBLE
            insets
        }

        // Typing wants the list, not the notes above it.
        binding.search.setOnFocusChangeListener { _, focused ->
            if (focused) binding.appBar.setExpanded(false, true)
        }

        cameForClimb = intent.hasExtra(EXTRA_CLIMB) || intent.hasExtra(EXTRA_FIND)

        val id = intent.getStringExtra(EXTRA_ID)
        val area = intent.getStringExtra(EXTRA_AREA)

        // Parsed off the main thread: a big crag is megabytes of JSON, and
        // opening this screen first after an update may also be what opens,
        // and so upgrades, the library.
        Thread {
            val found = id?.let { CragStore.byId(this, it) }
                ?: area?.let { CragStore.byArea(this, it) }
            val stamp = found?.let { CragStore.stamp(this, it.id) } ?: 0L

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (found == null) {
                    finish()
                    return@runOnUiThread
                }
                readStamp = stamp
                arrive(found)
            }
        }.start()
    }

    /** Everything that needs the crag, run once it has been read. */
    private fun arrive(found: Crag) {
        crag = found
        loaded = true

        supportActionBar?.title = crag.area

        binding.cragDirections.setOnClickListener {
            Maps.directionsTo(
                this, crag.area, crag.latitude, crag.longitude, crag.parking,
                choose = Settings.asksBetween(this, crag.hasPin, crag.parking.isNotEmpty()),
                buttresses = crag.buttresses,
            )
        }

        // Kept as the shortcut to choosing, for when a choice is set in Settings.
        binding.cragDirections.setOnLongClickListener {
            Maps.directionsTo(
                this, crag.area, crag.latitude, crag.longitude, crag.parking, choose = true,
                buttresses = crag.buttresses,
            )
            true
        }

        binding.photos.setOnClickListener {
            startActivity(
                Intent(this, PhotosActivity::class.java)
                    .putExtra(PhotosActivity.EXTRA_CRAG_ID, crag.id)
                    .putExtra(PhotosActivity.EXTRA_TITLE, crag.area)
            )
        }

        binding.photoBox.setOnClickListener {
            PhotoFetch.stop()
            binding.photoStatus.text = getString(R.string.photos_stopping)
        }

        binding.source.setOnClickListener { Maps.openUrl(this, crag.sourceUrl) }

        binding.topos.setOnClickListener {
            startActivity(
                Intent(this, TopoActivity::class.java)
                    .putExtra(TopoActivity.EXTRA_ID, crag.id)
                    .putExtra(TopoActivity.EXTRA_AREA, crag.area)
                    .putExtra(TopoActivity.EXTRA_FILTER, query)
            )
        }

        sort = runCatching { Sort.valueOf(sortPrefs().getString(crag.id, "").orEmpty()) }
            .getOrDefault(Sort.UKC)

        showCrag(initial = true)

        binding.search.doAfterTextChanged {
            query = it?.toString().orEmpty().trim().lowercase()
            refresh()
        }

        // Arrived from a map pin with a name to narrow to.
        intent.getStringExtra(EXTRA_FIND)?.let {
            binding.search.setText(it)
            intent.removeExtra(EXTRA_FIND)
        }

        // Arrived for one climb: bring it into view and open it, rather than
        // leaving it to be found in a list filtered down to its name.
        val wanted = intent.getStringExtra(EXTRA_CLIMB)
        intent.removeExtra(EXTRA_CLIMB)

        refresh { wanted?.let { revealClimb(it) } }
    }

    /** The parts drawn from the crag itself, on arrival and after a refresh lands. */
    private fun showCrag(initial: Boolean) {
        setUpTypeFilter(initial)
        showNotes()
        showDirections()
        showPhotos()
        invalidateOptionsMenu()
    }

    private fun revealClimb(url: String) {
        val climb = crag.buttresses.asSequence().flatMap { it.climbs }.firstOrNull { it.url == url }
            ?: return

        val position = adapter.currentList.indexOfFirst { it is Row.ClimbRow && it.climb.url == url }
        if (position >= 0) {
            binding.appBar.setExpanded(false, false)
            binding.list.post {
                (binding.list.layoutManager as LinearLayoutManager)
                    .scrollToPositionWithOffset(position, binding.list.height / 3)
            }
        }

        showActions(climb)
    }

    /**
     * Climb type, offered as the types this crag actually has and remembered
     * per crag, since what you filter for at a boulder field differs from
     * what you want at a trad cliff.
     */
    private fun setUpTypeFilter(initial: Boolean) {
        val types = crag.buttresses
            .flatMap { it.climbs }
            .map { it.type }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        if (types.size < 2) {
            binding.typeBox.visibility = View.GONE
            type = ""
            return
        }

        binding.typeBox.visibility = View.VISIBLE
        val labels = listOf(getString(R.string.all_types)) + types

        type = when {
            !initial -> type.takeIf { it in types }.orEmpty()
            cameForClimb -> ""
            else -> typePrefs().getString(crag.id, "").orEmpty().takeIf { it in types }.orEmpty()
        }

        binding.type.setSimpleItems(labels.toTypedArray())
        binding.type.setText(if (type.isEmpty()) labels.first() else type, false)

        binding.type.setOnItemClickListener { _, _, position, _ ->
            type = if (position == 0) "" else labels[position]
            typePrefs().edit().putString(crag.id, type).apply()
            refresh()
        }
    }

    private fun typePrefs() = getSharedPreferences("climb_type", MODE_PRIVATE)

    private fun sortPrefs() = getSharedPreferences("climb_sort", MODE_PRIVATE)

    /**
     * Sorting cuts across buttresses, so choosing one drops the buttress
     * headers and names the buttress on each row instead.
     */
    private fun chooseSort() {
        val order = listOf(Sort.UKC, Sort.NAME, Sort.GRADE, Sort.STARS)
        val labels = arrayOf(
            getString(R.string.sort_ukc),
            getString(R.string.sort_name),
            getString(R.string.sort_grade),
            getString(R.string.sort_stars),
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_climbs)
            .setSingleChoiceItems(labels, order.indexOf(sort)) { dialog, which ->
                sort = order[which]
                sortPrefs().edit().putString(crag.id, sort.name).apply()
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.crag, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        if (!loaded) {
            for (index in 0 until menu.size()) menu.getItem(index).isVisible = false
            return super.onPrepareOptionsMenu(menu)
        }

        menu.findItem(R.id.map)?.isVisible = crag.hasPin || crag.locatedButtresses > 0
        menu.findItem(R.id.sort)?.isVisible = true
        menu.findItem(R.id.refresh_this)?.isVisible = true
        menu.findItem(R.id.save_photos)?.apply {
            isVisible = true
            setTitle(if (PhotoFetch.cragId == crag.id) R.string.photos_stop else R.string.save_photos)
        }
        menu.findItem(R.id.delete_photos)?.isVisible =
            PhotoFetch.cragId != crag.id && PhotoCache.count(this, crag.id) > 0
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.sort) {
            chooseSort()
            return true
        }

        if (item.itemId == R.id.map) {
            startActivity(mapIntent(this, crag.id, crag.area))
            return true
        }

        if (item.itemId == R.id.save_photos) {
            if (PhotoFetch.cragId == crag.id) PhotoFetch.stop() else offerPhotos()
            return true
        }

        if (item.itemId == R.id.delete_photos) {
            confirmDeletePhotos()
            return true
        }

        if (item.itemId == R.id.refresh_this) {
            startActivity(
                Intent(this, BrowseActivity::class.java)
                    .putExtra(BrowseActivity.EXTRA_REFRESH, true)
                    .putExtra(BrowseActivity.EXTRA_REFRESH_URL, crag.sourceUrl)
            )
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    /**
     * Ticks and notes may have changed while away — a sync, a log — so the
     * rows are redrawn. The crag itself is only read again when its file has
     * changed, which is what a refresh done elsewhere looks like.
     */
    override fun onResume() {
        super.onResume()
        if (!loaded) return

        BoulderGrades.system(this).let {
            if (it != gradeSystem) {
                gradeSystem = it
                adapter.notifyDataSetChanged()
            }
        }

        refresh()

        val id = crag.id
        Thread {
            val stamp = CragStore.stamp(this, id)
            if (stamp == readStamp) return@Thread

            val fresh = CragStore.byId(this, id) ?: return@Thread

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                readStamp = stamp
                crag = fresh
                showCrag(initial = false)
                refresh()
            }
        }.start()
    }

    override fun onDestroy() {
        // The page reading photos lives in this screen's window.
        if (loaded && PhotoFetch.cragId == crag.id) PhotoFetch.detach()
        super.onDestroy()
    }

    /**
     * Says where the button goes. Parking is only known for crags read since
     * it was, so an older crag keeps pointing at its own pin until refreshed.
     */
    private fun showDirections() {
        val hasParking = crag.parking.isNotEmpty()
        val toParking = Settings.directionsToParking(this) && hasParking

        binding.cragDirections.visibility =
            if (crag.hasPin || hasParking) View.VISIBLE else View.GONE
        binding.cragDirections.setText(
            when {
                Settings.asksBetween(this, crag.hasPin, hasParking) -> R.string.directions
                toParking -> R.string.directions_to_parking
                else -> R.string.directions_to_crag
            }
        )
    }

    /** The crag's own notes, sat above the climb list where they get read. */
    private fun showNotes() {
        val notes = crag.description

        binding.notesCard.visibility = if (notes.isBlank()) View.GONE else View.VISIBLE
        binding.notes.text = notes
        binding.notesCard.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.crag_notes)
                .setMessage(notes)
                .setPositiveButton(R.string.close, null)
                .show()
                .findViewById<android.widget.TextView>(android.R.id.message)
                ?.showLinks()
        }
    }

    private fun showPhotos() {
        val saved = PhotoCache.count(this, crag.id)

        binding.photos.visibility = if (saved > 0) View.VISIBLE else View.GONE
        binding.photos.text = resources.getQuantityString(R.plurals.photos_saved_n, saved, saved)
    }

    /**
     * Saving photos is two page reads per climb, so the cost is named before
     * anything is asked of UKC. The crag's own gallery is one read and is the
     * cheap choice; a climb's photos are the useful one at a boulder.
     */
    private fun offerPhotos() {
        val climbs = crag.buttresses.flatMap { it.climbs }
            .filter { it.photos > 0 && it.climbId > 0 }
            .distinctBy { it.climbId }
        val unread = PhotoFetch.unread(this, crag)
        val photos = unread.sumOf { it.photos }

        val message = when {
            climbs.isEmpty() -> getString(R.string.save_photos_crag_only)
            unread.isEmpty() -> getString(R.string.save_photos_again)
            else -> resources.getQuantityString(
                R.plurals.save_photos_cost,
                unread.size,
                unread.size,
                photos,
                ((unread.size * 0.7) / 60).toInt().coerceAtLeast(1),
                ((photos + CRAG_GALLERY) * 0.15).toInt().coerceAtLeast(1),
            )
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_photos)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)

        if (climbs.isEmpty()) {
            dialog.setPositiveButton(R.string.save_photos_go) { _, _ -> savePhotos(false) }
        } else {
            dialog.setPositiveButton(R.string.save_photos_all) { _, _ -> savePhotos(true) }
            dialog.setNeutralButton(R.string.save_photos_gallery) { _, _ -> savePhotos(false) }
        }

        dialog.show()
    }

    private fun savePhotos(withClimbs: Boolean) {
        if (PhotoFetch.running()) {
            android.widget.Toast.makeText(this, R.string.photos_busy, android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        binding.photoBox.visibility = View.VISIBLE
        binding.photoBar.isIndeterminate = true
        binding.photoStatus.text = getString(R.string.photos_starting)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        PhotoFetch.start(binding.root as ViewGroup, crag, withClimbs, object : PhotoFetch.Listener {
            override fun progress(read: Int, total: Int, downloading: Int) {
                if (isDestroyed) return

                binding.photoStatus.text = when {
                    total > 0 && read < total ->
                        getString(R.string.photos_reading, read, total)
                    downloading > 0 ->
                        resources.getQuantityString(R.plurals.photos_left, downloading, downloading)
                    else -> getString(R.string.photos_starting)
                }

                binding.photoBar.isIndeterminate = total == 0 || read >= total
                if (total > 0 && read < total) binding.photoBar.setProgressCompat(read * 100 / total, true)

                showPhotos()
            }

            override fun finished(saved: Int, failed: String?) {
                if (isDestroyed) return

                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                binding.photoBox.visibility = View.GONE
                showPhotos()
                invalidateOptionsMenu()

                android.widget.Toast.makeText(
                    this@CragActivity,
                    if (failed == null) resources.getQuantityString(R.plurals.photos_done, saved, saved)
                    else resources.getQuantityString(R.plurals.photos_failed, saved, saved, failed),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        })

        invalidateOptionsMenu()
    }

    private fun confirmDeletePhotos() {
        val count = PhotoCache.count(this, crag.id)
        val megabytes = (PhotoCache.bytes(this, crag.id) / (1024 * 1024)).toInt()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_photos)
            .setMessage(resources.getQuantityString(R.plurals.delete_photos_warning, count, count, megabytes))
            .setPositiveButton(R.string.delete_photos) { _, _ ->
                PhotoCache.clear(this, crag.id)
                showPhotos()
                invalidateOptionsMenu()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showActions(climb: Climb) {
        ClimbDialog.show(this, crag, climb) { refresh() }
    }

    private fun matches(buttress: Buttress, climb: Climb): Boolean {
        if (type.isNotEmpty() && !climb.type.equals(type, ignoreCase = true)) return false

        if (query.isEmpty()) return true

        val haystack =
            "${climb.name} ${climb.grade} ${BoulderGrades.show(climb.grade, climb.type, gradeSystem)} ${climb.type} ${buttress.name}".lowercase()

        return haystack.contains(query)
    }

    /**
     * The topos the current filter leaves standing, matched on the buttress
     * name or on a climb drawn on the photo. Filtering the list but not the
     * topos would send you to the wrong boulder.
     */
    private fun visibleTopos(): List<Topo> {
        if (query.isEmpty()) return crag.topos

        return crag.topos.filter { topo ->
            topo.buttress.lowercase().contains(query) ||
                topo.lines.any { it.name.lowercase().contains(query) }
        }
    }

    private fun markOf(climb: Climb): Mark = when {
        ticks.has(climb.url) -> Mark.TICKED
        toLog.has(climb.url) -> Mark.TO_LOG
        attempts.has(climb.url) -> Mark.ATTEMPTED
        else -> Mark.NONE
    }

    private fun climbRow(climb: Climb, buttress: String = "") =
        Row.ClimbRow(climb, buttress, markOf(climb), wishlist.has(climb.url))

    /** [then] runs once the list on screen matches, for anything that needs a row's position. */
    private fun refresh(then: () -> Unit = {}) {
        if (!loaded) return

        val rows = mutableListOf<Row>()

        if (sort == Sort.UKC) {
            for (buttress in crag.buttresses) {
                val visible = buttress.climbs.filter { matches(buttress, it) }

                if (visible.isEmpty()) continue

                rows.add(Row.ButtressRow(buttress, visible.size))
                visible.forEach { rows.add(climbRow(it)) }
            }
        } else {
            val all = crag.buttresses.flatMap { buttress ->
                buttress.climbs.filter { matches(buttress, it) }.map { buttress.name to it }
            }

            val sorted = when (sort) {
                // Ungraded climbs carry a zero score, which would otherwise
                // pile them up at the easy end, so they go last.
                Sort.GRADE -> all.sortedWith(
                    compareBy({ it.second.gradeScore <= 0.0 }, { it.second.gradeScore })
                )
                Sort.STARS -> all.sortedWith(
                    compareByDescending<Pair<String, Climb>> { it.second.stars }
                        .thenBy { it.second.name.lowercase() }
                )
                else -> all.sortedBy { it.second.name.lowercase() }
            }

            sorted.forEach { rows.add(climbRow(it.second, it.first)) }
        }

        adapter.submitKeepingPlace(rows, {
            when (it) {
                is Row.ButtressRow -> "buttress:" + it.buttress.name
                is Row.ClimbRow -> "climb:" + it.climb.url
            }
        }) { then() }

        binding.progress.text = resources.getQuantityString(
            R.plurals.crag_progress_long,
            crag.climbCount,
            ticks.countIn(crag),
            crag.climbCount,
            resources.getQuantityString(R.plurals.buttresses, crag.buttresses.size, crag.buttresses.size),
        )

        binding.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        val topos = visibleTopos()
        binding.topos.visibility = if (topos.isEmpty()) View.GONE else View.VISIBLE
        binding.topos.text = resources.getQuantityString(R.plurals.topos_n, topos.size, topos.size)
    }

    /**
     * Diffed rather than replaced, so a tick landing or a letter typed keeps
     * the reader where they were in a crag of several hundred climbs.
     */
    private inner class RowAdapter : ListAdapter<Row, RecyclerView.ViewHolder>(RowDiff) {

        override fun getItemViewType(position: Int): Int =
            if (getItem(position) is Row.ButtressRow) TYPE_BUTTRESS else TYPE_CLIMB

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)

            return if (viewType == TYPE_BUTTRESS) {
                ButtressHolder(ItemSectorBinding.inflate(inflater, parent, false))
            } else {
                ClimbHolder(ItemRouteBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = getItem(position)) {
                is Row.ButtressRow -> (holder as ButtressHolder).bind(row)
                is Row.ClimbRow -> (holder as ClimbHolder).bind(row)
            }
        }
    }

    private object RowDiff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(old: Row, new: Row): Boolean = when {
            old is Row.ButtressRow && new is Row.ButtressRow -> old.buttress.name == new.buttress.name
            old is Row.ClimbRow && new is Row.ClimbRow -> old.climb.url == new.climb.url
            else -> false
        }

        override fun areContentsTheSame(old: Row, new: Row): Boolean = old == new
    }

    private inner class ButtressHolder(private val item: ItemSectorBinding) :
        RecyclerView.ViewHolder(item.root) {

        fun bind(row: Row.ButtressRow) {
            val buttress = row.buttress

            item.name.text = buttress.name
            item.count.text = resources.getQuantityString(
                R.plurals.climbs,
                row.shown,
                row.shown,
            )

            item.directions.visibility = if (buttress.hasPin) View.VISIBLE else View.GONE
            item.noPin.visibility = if (buttress.hasPin) View.GONE else View.VISIBLE

            item.directions.setOnClickListener {
                Maps.open(
                    this@CragActivity,
                    buttress.latitude!!,
                    buttress.longitude!!,
                    buttress.name,
                )
            }
        }
    }

    private inner class ClimbHolder(private val item: ItemRouteBinding) :
        RecyclerView.ViewHolder(item.root) {

        fun bind(row: Row.ClimbRow) {
            val climb = row.climb
            val type = climb.type.ifEmpty { "—" }

            item.name.text = climb.name
            item.grade.text = BoulderGrades.show(climb.grade, climb.type, gradeSystem)
            item.stars.text = "★".repeat(climb.stars)
            item.stars.contentDescription = if (climb.stars > 0) {
                resources.getQuantityString(R.plurals.stars, climb.stars, climb.stars)
            } else {
                null
            }
            val logs = resources.getQuantityString(R.plurals.logs, climb.logs, climb.logs)
            item.meta.text = getString(R.string.route_meta, type, logs) +
                if (row.buttress.isBlank()) "" else " · " + row.buttress

            // Ticks come from the logbook, so the row only reports them. The
            // same slot says sent-to-log or tried when there is no tick.
            when (row.mark) {
                Mark.TICKED -> mark(R.drawable.ic_ticked, R.string.ticked)
                Mark.TO_LOG -> mark(R.drawable.ic_to_log, R.string.sent_to_log)
                Mark.ATTEMPTED -> mark(R.drawable.ic_attempt, R.string.attempted)
                Mark.NONE -> {
                    item.tick.visibility = View.INVISIBLE
                    item.tick.contentDescription = null
                }
            }

            item.wish.visibility = if (row.wished) View.VISIBLE else View.GONE
            item.photos.visibility = if (climb.photos > 0) View.VISIBLE else View.GONE

            val done = row.mark == Mark.TICKED
            item.name.alpha = if (done) 0.45f else 1f
            item.meta.alpha = if (done) 0.45f else 1f

            item.root.setOnClickListener { showActions(climb) }
        }

        private fun mark(icon: Int, description: Int) {
            item.tick.setImageResource(icon)
            item.tick.contentDescription = getString(description)
            item.tick.visibility = View.VISIBLE
        }
    }

    companion object {
        /** The crag's id. Preferred: names are not unique. */
        const val EXTRA_ID = "crag_id"

        /** The crag's name, for callers that have nothing else. */
        const val EXTRA_AREA = "area"

        /** A name to filter the list to on arrival. */
        const val EXTRA_FIND = "find"

        /** A climb URL to scroll to and open on arrival. */
        const val EXTRA_CLIMB = "climb_url"

        /** Roughly what UKC shows in a crag's own gallery, for the size estimate. */
        private const val CRAG_GALLERY = 24

        private const val TYPE_BUTTRESS = 0
        private const val TYPE_CLIMB = 1

        /** Opens this crag, by id with the name as a fallback, at one climb if given. */
        fun intent(context: Context, cragId: String, area: String, climbUrl: String? = null): Intent =
            Intent(context, CragActivity::class.java)
                .putExtra(EXTRA_ID, cragId)
                .putExtra(EXTRA_AREA, area)
                .apply { if (climbUrl != null) putExtra(EXTRA_CLIMB, climbUrl) }

        /**
         * The map, for one crag. The map reads the name today; the id rides
         * along so it can move to it, names not being unique.
         */
        fun mapIntent(context: Context, cragId: String, area: String): Intent =
            Intent(context, MapActivity::class.java)
                .putExtra(MapActivity.EXTRA_AREA, area)
                .putExtra(EXTRA_ID, cragId)
    }
}
