package dr.ukccrags

import android.graphics.Bitmap
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dr.ukccrags.databinding.ActivityTopoBinding

/** Shows a crag's topos, with each climb's line drawn over the cached photo. */
class TopoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTopoBinding
    private lateinit var crag: Crag

    private var index = 0
    private var photo: Bitmap? = null

    /**
     * The topos this screen pages through. A filter carried over from the crag
     * screen narrows them, so paging cannot wander off to a boulder that was
     * filtered out of the list you came from.
     */
    private var topos: List<Topo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityTopoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val area = intent.getStringExtra(EXTRA_AREA).orEmpty()
        crag = CragStore.load(this).firstOrNull { it.area == area } ?: run { finish(); return }

        topos = filtered(intent.getStringExtra(EXTRA_FILTER).orEmpty())
        if (topos.isEmpty()) { finish(); return }

        index = topos.indexOfFirst { it.topoId == intent.getLongExtra(EXTRA_TOPO, -1L) }
            .coerceAtLeast(0)

        val focusClimb = intent.getStringExtra(EXTRA_CLIMB_URL)
        if (focusClimb != null) index = topoIndexFor(focusClimb).coerceAtLeast(0)

        binding.names.setOnClickListener { toggleNames() }
        binding.previous.setOnClickListener { step(-1) }
        binding.next.setOnClickListener { step(1) }

        // Tapping a line names the climb it belongs to; the view itself handles
        // pinch to zoom and drag to pan.
        binding.topo.grades = crag.buttresses
            .flatMap { it.climbs }
            .filter { it.climbId > 0L && it.grade.isNotBlank() }
            .associate { it.climbId to it.grade }

        binding.topo.onTap = { line ->
            binding.status.text = line?.let {
                getString(R.string.topo_climb, binding.topo.labelFor(it))
            } ?: describeTopo()
        }

        show(focusClimb)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.topo, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.rotate) {
            rotateLines()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    /** Shows every climb's name at once, for reading the whole boulder. */
    private fun toggleNames() {
        val on = !binding.topo.showNames

        binding.topo.showNames = on
        binding.names.isChecked = on

        // A tapped line owns the labels while it is focused, so clear it.
        if (on) binding.topo.focus(null)

        binding.status.text = describeTopo()
    }

    /**
     * UKC's line coordinates do not consistently match the orientation of the
     * photo they belong to, so the turn is the reader's call, kept per topo.
     */
    private fun rotateLines() {
        val topo = topos.getOrNull(index) ?: return

        val next = (binding.topo.quarterTurns + 1) % 4
        binding.topo.quarterTurns = next
        turnPrefs().edit().putInt(topo.topoId.toString(), next).apply()

        binding.status.text = describeTopo()
    }

    private fun turnPrefs() = getSharedPreferences("topo_turns", MODE_PRIVATE)

    /** Matched the same way the crag screen matches: buttress, or a climb on it. */
    private fun filtered(filter: String): List<Topo> {
        val wanted = filter.trim().lowercase()
        if (wanted.isEmpty()) return crag.topos

        val kept = crag.topos.filter { topo ->
            topo.buttress.lowercase().contains(wanted) ||
                topo.lines.any { it.name.lowercase().contains(wanted) }
        }

        return kept.ifEmpty { crag.topos }
    }

    /** Finds the topo carrying a given climb, matched on the id in its URL. */
    private fun topoIndexFor(climbUrl: String): Int {
        val id = Regex("-(\\d+)/?$").find(climbUrl)?.groupValues?.get(1)?.toLongOrNull()
            ?: return -1

        return topos.indexOfFirst { topo -> topo.lines.any { it.climbId == id } }
    }

    private fun step(by: Int) {
        if (topos.isEmpty()) return

        index = (index + by + topos.size) % topos.size
        show(null)
    }

    private fun show(focusClimbUrl: String?) {
        val topo = topos.getOrNull(index) ?: return

        supportActionBar?.title = topo.buttress.ifBlank { crag.area }
        supportActionBar?.subtitle =
            getString(R.string.topo_of, index + 1, topos.size)

        binding.previous.isEnabled = topos.size > 1
        binding.next.isEnabled = topos.size > 1

        photo = TopoCache.load(this, topo)
        binding.topo.quarterTurns = turnPrefs().getInt(topo.topoId.toString(), 0)
        binding.topo.show(topo, photo)
        binding.status.text = describeTopo()

        focusClimbUrl?.let { url ->
            Regex("-(\\d+)/?$").find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { id ->
                binding.topo.focus(id)
                topo.lines.firstOrNull { it.climbId == id }?.let {
                    binding.status.text =
                        getString(R.string.topo_climb, binding.topo.labelFor(it))
                }
            }
        }

        if (photo == null) binding.status.text = getString(R.string.topo_offline)
    }

    private fun describeTopo(): String {
        val topo = topos.getOrNull(index) ?: return ""
        return resources.getQuantityString(
            R.plurals.topo_lines, topo.lines.size, topo.lines.size,
        )
    }

    companion object {
        const val EXTRA_AREA = "area"
        const val EXTRA_TOPO = "topo"
        const val EXTRA_CLIMB_URL = "climb"

        /** Narrows the topos to those matching this text. */
        const val EXTRA_FILTER = "filter"
    }
}
