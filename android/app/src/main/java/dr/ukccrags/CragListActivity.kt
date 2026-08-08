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

class CragListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCragListBinding
    private lateinit var ticks: Ticks

    private var nearestFirst = false
    private var filter: String = ""
    private var type: String = ""

    /** Grades kept. Empty means every grade. */
    private val grades = linkedSetOf<String>()
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

        setUpClimbFilters()

        render()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.crag_list, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.nearest)?.isChecked = nearestFirst

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
            R.id.find -> {
                startActivity(Intent(this, SearchActivity::class.java))
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
            R.id.refresh -> {
                startActivity(
                    Intent(this, BrowseActivity::class.java)
                        .putExtra(BrowseActivity.EXTRA_REFRESH, true)
                )
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
            R.id.updates -> {
                Updates.check(this)
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    /** Wiping the library is easy to do by accident, so name the cost first. */
    private fun confirmClear() {
        val count = CragStore.load(this).size

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_crags)
            .setMessage(getString(R.string.clear_crags_warning, count))
            .setPositiveButton(R.string.clear_crags) { _, _ ->
                CragStore.clear(this)
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

    override fun onResume() {
        super.onResume()

        here = Nearby.lastKnown(this) ?: here

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
        val climbs = CragStore.load(this).flatMap { it.buttresses }.flatMap { it.climbs }

        val types = climbs.map { it.type }.filter { it.isNotBlank() }.distinct().sorted()
        val typeLabels = listOf(getString(R.string.all_types)) + types

        binding.type.setSimpleItems(typeLabels.toTypedArray())
        binding.type.setText(if (type.isEmpty()) typeLabels.first() else type, false)
        binding.typeBox.visibility = if (types.size < 2) View.GONE else View.VISIBLE

        binding.type.setOnItemClickListener { _, _, position, _ ->
            type = if (position == 0) "" else typeLabels[position]
            grades.clear()
            setUpGrades(climbs)
            render()
        }

        setUpGrades(climbs)
    }

    private fun setUpGrades(climbs: List<Climb>) {
        // Ordered by UKC's own score, so f5 sits below f6A rather than beside it.
        val offered = climbs
            .filter { type.isEmpty() || it.type.equals(type, ignoreCase = true) }
            .filter { it.grade.isNotBlank() }
            .distinctBy { it.grade }
            .sortedBy { it.gradeScore }
            .map { it.grade }

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

    /** True when the crag holds a climb of the chosen type and any chosen grade. */
    private fun holdsWanted(crag: Crag): Boolean {
        if (type.isEmpty() && grades.isEmpty()) return true

        return crag.buttresses.any { buttress ->
            buttress.climbs.any { climb ->
                (type.isEmpty() || climb.type.equals(type, ignoreCase = true)) &&
                    (grades.isEmpty() || grades.any { it.equals(climb.grade, true) })
            }
        }
    }

    private fun render() {
        ticks = Ticks(this)

        val loaded = CragStore.load(this).filter { crag ->
            (filter.isEmpty() || crag.area.lowercase().contains(filter)) && holdsWanted(crag)
        }

        val crags = if (nearestFirst && here != null) {
            loaded.sortedWith(
                // Crags with no pin can't be ranked, so they sink to the bottom.
                compareBy(nullsLast()) { it.metresFrom(here) }
            )
        } else {
            loaded
        }

        binding.empty.visibility = if (crags.isEmpty()) View.VISIBLE else View.GONE

        // The button is only worth its screen space on an empty library; once
        // there are crags, adding more lives in the menu.
        binding.add.visibility =
            if (CragStore.load(this).isEmpty()) View.VISIBLE else View.GONE
        binding.list.adapter = CragAdapter(crags) { crag ->
            startActivity(
                Intent(this, CragActivity::class.java)
                    .putExtra(CragActivity.EXTRA_AREA, crag.area)
            )
        }
    }

    private inner class CragAdapter(
        private val crags: List<Crag>,
        private val onClick: (Crag) -> Unit,
    ) : RecyclerView.Adapter<CragAdapter.Holder>() {

        inner class Holder(val item: ItemCragBinding) :
            RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(
                ItemCragBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                )
            )

        override fun getItemCount(): Int = crags.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val crag = crags[position]

            holder.item.name.text = crag.area
            holder.item.detail.text = getString(
                R.string.crag_detail_located,
                resources.getQuantityString(R.plurals.climbs, crag.climbCount, crag.climbCount),
                resources.getQuantityString(
                    R.plurals.buttresses,
                    crag.buttresses.size,
                    crag.buttresses.size,
                ),
                crag.locatedButtresses,
            )

            val ticked = getString(
                R.string.crag_progress,
                ticks.countIn(crag),
                crag.climbCount,
            )

            val metres = crag.metresFrom(here)

            holder.item.progress.text = if (metres != null) {
                Units.distance(this@CragListActivity, metres) + " · " + ticked
            } else {
                ticked
            }

            holder.item.root.setOnClickListener { onClick(crag) }

            // Long press re-reads just this crag, topo photos included.
            holder.item.root.setOnLongClickListener {
                MaterialAlertDialogBuilder(this@CragListActivity)
                    .setTitle(crag.area)
                    .setMessage(R.string.refresh_this_crag)
                    .setPositiveButton(R.string.refresh_this_crag) { _, _ ->
                        startActivity(
                            Intent(this@CragListActivity, BrowseActivity::class.java)
                                .putExtra(BrowseActivity.EXTRA_REFRESH, true)
                                .putExtra(BrowseActivity.EXTRA_REFRESH_URL, crag.sourceUrl)
                        )
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }
    }
    private companion object {
        /** Location is asked for once; a refusal is not re-litigated. */
        const val KEY_ASKED = "asked"
    }

}
