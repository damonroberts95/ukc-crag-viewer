package dr.ukccrags

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dr.ukccrags.databinding.ActivityCragBinding
import dr.ukccrags.databinding.DialogClimbBinding
import dr.ukccrags.databinding.ItemRouteBinding
import dr.ukccrags.databinding.ItemSectorBinding

/** One row in the flattened sector/route list. */
private sealed interface Row {
    data class ButtressRow(val buttress: Buttress, val shown: Int) : Row
    /** [buttress] is set only when the list is sorted and the headers are gone. */
    data class ClimbRow(val climb: Climb, val buttress: String = "") : Row
}

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
    private lateinit var ticks: Ticks
    private lateinit var attempts: Attempts
    private lateinit var wishlist: Wishlist

    private val adapter = RowAdapter()

    private var query: String = ""
    private var type: String = ""
    private var sort: Sort = Sort.UKC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCragBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val area = intent.getStringExtra(EXTRA_AREA)
        val found = CragStore.byArea(this, area.orEmpty())

        if (found == null) {
            finish()
            return
        }

        crag = found
        ticks = Ticks(this)
        attempts = Attempts(this)
        wishlist = Wishlist(this)

        supportActionBar?.title = crag.area

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.cragDirections.visibility = if (crag.hasPin) View.VISIBLE else View.GONE
        binding.cragDirections.setOnClickListener {
            Maps.open(this, crag.latitude!!, crag.longitude!!, crag.area)
        }

        binding.source.setOnClickListener { Maps.openUrl(this, crag.sourceUrl) }

        binding.topos.setOnClickListener {
            startActivity(
                Intent(this, TopoActivity::class.java)
                    .putExtra(TopoActivity.EXTRA_AREA, crag.area)
                    .putExtra(TopoActivity.EXTRA_FILTER, query)
            )
        }

        sort = runCatching { Sort.valueOf(sortPrefs().getString(crag.id, "").orEmpty()) }
            .getOrDefault(Sort.UKC)

        showNotes()

        binding.search.doAfterTextChanged {
            query = it?.toString().orEmpty().trim().lowercase()
            refresh()
        }

        setUpTypeFilter()

        // Arrived from a search: show that climb rather than the whole crag.
        intent.getStringExtra(EXTRA_FIND)?.let {
            binding.search.setText(it)
            intent.removeExtra(EXTRA_FIND)
        }

        refresh()
    }

    /**
     * Climb type, offered as the types this crag actually has and remembered
     * per crag, since what you filter for at a boulder field differs from
     * what you want at a trad cliff.
     */
    private fun setUpTypeFilter() {
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

        val labels = listOf(getString(R.string.all_types)) + types

        val remembered = typePrefs().getString(crag.id, "").orEmpty()
        type = if (remembered in types) remembered else ""

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
        menu.findItem(R.id.map)?.isVisible = crag.hasPin || crag.locatedButtresses > 0
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.sort) {
            chooseSort()
            return true
        }

        if (item.itemId == R.id.map) {
            startActivity(
                Intent(this, MapActivity::class.java)
                    .putExtra(MapActivity.EXTRA_AREA, crag.area)
            )
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

    /** Reloads the stored copy, so a refresh done elsewhere shows up here. */
    override fun onResume() {
        super.onResume()

        CragStore.byArea(this, crag.area)?.let {
            crag = it
            setUpTypeFilter()
            showNotes()
            invalidateOptionsMenu()
            refresh()
        }
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
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** True when this climb has a line on one of the crag's cached topos. */
    private fun onTopo(climb: Climb): Boolean {
        if (climb.climbId <= 0L) return false

        return crag.topos.any { topo -> topo.lines.any { it.climbId == climb.climbId } }
    }

    /** Tapping a climb shows what UKC says about it, then what can be done with it. */
    private fun showActions(climb: Climb) {
        val view = DialogClimbBinding.inflate(layoutInflater)

        view.detail.text = buildString {
            append(climb.grade.ifBlank { "—" })
            if (climb.type.isNotBlank()) append(" · ").append(climb.type)
            if (climb.stars > 0) append(" · ").append("★".repeat(climb.stars))
            if (climb.height > 0) append(" · ").append(getString(R.string.climb_height, climb.height))
            if (climb.pitches > 1) {
                append(" · ").append(getString(R.string.climb_pitches, climb.pitches))
            }
            if (ticks.has(climb.url)) append(" · ").append(getString(R.string.ticked))
            else if (attempts.has(climb.url)) append(" · ").append(getString(R.string.attempted))
            if (wishlist.has(climb.url)) append(" · ").append(getString(R.string.on_wishlist))
        }

        view.description.text = climb.description.ifBlank { getString(R.string.no_description) }
        view.description.alpha = if (climb.description.isBlank()) 0.6f else 1f

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(climb.name)
            .setView(view.root)
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        view.topo.visibility = if (onTopo(climb)) View.VISIBLE else View.GONE
        view.topo.setOnClickListener {
            dialog.dismiss()
            startActivity(
                Intent(this, TopoActivity::class.java)
                    .putExtra(TopoActivity.EXTRA_AREA, crag.area)
                    .putExtra(TopoActivity.EXTRA_CLIMB_URL, climb.url)
            )
        }

        // Climb photos are not cached: they open on UKC, where they live.
        view.photos.visibility = if (climb.photos > 0) View.VISIBLE else View.GONE
        view.photos.text = getString(R.string.see_photos_n, climb.photos)
        view.photos.setOnClickListener {
            dialog.dismiss()
            Maps.openUrl(this, climb.url + "#photos")
        }

        view.open.setOnClickListener {
            dialog.dismiss()
            Maps.openUrl(this, climb.url)
        }

        view.share.setOnClickListener {
            dialog.dismiss()
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, climb.name)
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "${climb.name} (${climb.grade}) — ${crag.area}\n${climb.url}",
                        )
                    },
                    getString(R.string.share_via),
                )
            )
        }

        // Opens the climb's own UKC page, which carries its "Add to Logbook"
        // button. The app never presses it without being asked.
        view.log.setOnClickListener {
            dialog.dismiss()
            startActivity(
                Intent(this, BrowseActivity::class.java)
                    .putExtra(BrowseActivity.EXTRA_URL, climb.url)
                    .putExtra(BrowseActivity.EXTRA_LOG_CLIMB, true)
            )
        }
    }

    private fun matches(buttress: Buttress, climb: Climb): Boolean {
        if (type.isNotEmpty() && !climb.type.equals(type, ignoreCase = true)) return false

        if (query.isEmpty()) return true

        val haystack =
            "${climb.name} ${climb.grade} ${climb.type} ${buttress.name}".lowercase()

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

    private fun refresh() {
        val rows = mutableListOf<Row>()

        if (sort == Sort.UKC) {
            for (buttress in crag.buttresses) {
                val visible = buttress.climbs.filter { matches(buttress, it) }

                if (visible.isEmpty()) continue

                rows.add(Row.ButtressRow(buttress, visible.size))
                visible.forEach { rows.add(Row.ClimbRow(it)) }
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

            sorted.forEach { rows.add(Row.ClimbRow(it.second, it.first)) }
        }

        adapter.submit(rows)

        binding.progress.text = getString(
            R.string.crag_progress_long,
            ticks.countIn(crag),
            crag.climbCount,
            crag.buttresses.size,
        )

        binding.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        val topos = visibleTopos()
        binding.topos.visibility = if (topos.isEmpty()) View.GONE else View.VISIBLE
        binding.topos.text = getString(R.string.topo_count, topos.size)
    }

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<Row> = emptyList()

        fun submit(next: List<Row>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.ButtressRow) TYPE_BUTTRESS else TYPE_CLIMB

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
            when (val row = rows[position]) {
                is Row.ButtressRow -> (holder as ButtressHolder).bind(row)
                is Row.ClimbRow -> (holder as ClimbHolder).bind(row.climb, row.buttress)
            }
        }
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

        fun bind(climb: Climb, buttress: String) {
            val type = climb.type.ifEmpty { "—" }

            item.name.text = climb.name
            item.grade.text = climb.grade
            item.stars.text = "★".repeat(climb.stars)
            val logs = resources.getQuantityString(R.plurals.logs, climb.logs, climb.logs)
            item.meta.text = getString(R.string.route_meta, type, logs) +
                if (buttress.isBlank()) "" else " · " + buttress

            // Ticks come from the logbook, so the row only reports them.
            val done = ticks.has(climb.url)
            item.tick.visibility = if (done) View.VISIBLE else View.INVISIBLE
            item.photos.visibility = if (climb.photos > 0) View.VISIBLE else View.GONE
            item.name.alpha = if (done) 0.45f else 1f
            item.meta.alpha = if (done) 0.45f else 1f

            item.root.setOnClickListener { showActions(climb) }
        }
    }

    companion object {
        const val EXTRA_AREA = "area"

        /** A climb name to filter to on arrival. */
        const val EXTRA_FIND = "find"

        private const val TYPE_BUTTRESS = 0
        private const val TYPE_CLIMB = 1
    }
}
