package dr.ukccrags

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dr.ukccrags.databinding.ActivityTopoBinding

/** Shows a crag's topos, with each climb's line drawn over the cached photo. */
class TopoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTopoBinding
    private var crag: Crag? = null

    private var index = 0
    private var photo: Bitmap? = null

    /** The line last tapped, which the status line then opens. */
    private var focusedLine: TopoLine? = null

    /** Bumped per topo shown, so a slow decode cannot land on the next one. */
    private var showing = 0

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

        val id = intent.getStringExtra(EXTRA_ID)
        val area = intent.getStringExtra(EXTRA_AREA)

        // Parsing a crag is megabytes of JSON; not on the main thread.
        Thread {
            val found = id?.let { CragStore.byId(this, it) }
                ?: area?.let { CragStore.byArea(this, it) }

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (found == null) {
                    finish()
                    return@runOnUiThread
                }
                arrive(found)
            }
        }.start()
    }

    private fun arrive(found: Crag) {
        crag = found

        topos = filtered(found, intent.getStringExtra(EXTRA_FILTER).orEmpty())
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
        binding.topo.grades = found.buttresses
            .flatMap { it.climbs }
            .filter { it.climbId > 0L && it.grade.isNotBlank() }
            .associate { it.climbId to it.grade }

        binding.topo.onTap = { line ->
            focusedLine = line
            binding.status.text = line?.let {
                getString(R.string.topo_climb, binding.topo.labelFor(it))
            } ?: describeTopo()
        }

        // A named line leads on to the climb itself: tap the name under the
        // photo, or hold the line.
        binding.status.setOnClickListener { focusedLine?.let { openClimb(it) } }
        binding.topo.onLongPress = { line ->
            focusedLine = line
            binding.status.text = getString(R.string.topo_climb, binding.topo.labelFor(line))
            openClimb(line)
        }

        invalidateOptionsMenu()
        show(focusClimb)
    }

    private fun openClimb(line: TopoLine) {
        val current = crag ?: return
        val climb = current.buttresses.asSequence()
            .flatMap { it.climbs }
            .firstOrNull { it.climbId == line.climbId && line.climbId > 0L }
            ?: return

        ClimbDialog.show(this, current, climb, fromTopo = true)
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
        if (on) {
            binding.topo.focus(null)
            focusedLine = null
        }

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
    private fun filtered(crag: Crag, filter: String): List<Topo> {
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
        val id = idInUrl(climbUrl).takeIf { it > 0L } ?: return -1
        return topos.indexOfFirst { topo -> topo.lines.any { it.climbId == id } }
    }

    private fun step(by: Int) {
        if (topos.isEmpty()) return

        index = (index + by + topos.size) % topos.size
        show(null)
    }

    private fun show(focusClimbUrl: String?) {
        val topo = topos.getOrNull(index) ?: return
        val area = crag?.area.orEmpty()

        supportActionBar?.title = topo.buttress.ifBlank { area }
        supportActionBar?.subtitle =
            getString(R.string.topo_of, index + 1, topos.size)

        binding.previous.isEnabled = topos.size > 1
        binding.next.isEnabled = topos.size > 1

        focusedLine = null
        binding.topo.quarterTurns = turnPrefs().getInt(topo.topoId.toString(), 0)

        // The lines go up at once; the photo follows when it is decoded, off
        // the main thread, so paging through a boulder field does not stutter.
        binding.topo.show(topo, null)
        binding.status.text = describeTopo()
        focus(topo, focusClimbUrl)

        val generation = ++showing
        Thread {
            val decoded = decode(topo)

            runOnUiThread {
                if (isDestroyed || generation != showing) return@runOnUiThread

                photo = decoded
                binding.topo.show(topo, decoded)
                if (decoded == null) {
                    binding.status.text = getString(R.string.topo_offline)
                } else {
                    binding.status.text = describeTopo()
                    focus(topo, focusClimbUrl)
                }
            }
        }.start()
    }

    private fun focus(topo: Topo, climbUrl: String?) {
        val id = climbUrl?.let { idInUrl(it) }?.takeIf { it > 0L } ?: return

        binding.topo.focus(id)
        topo.lines.firstOrNull { it.climbId == id }?.let {
            focusedLine = it
            binding.status.text = getString(R.string.topo_climb, binding.topo.labelFor(it))
        }
    }

    /**
     * The cached photo, cut down to the screen's longer edge. RGB_565 halves
     * the memory of a topo that carries no transparency; it can band a smooth
     * sky slightly, which on a photo of rock nobody sees.
     */
    private fun decode(topo: Topo): Bitmap? {
        val file = TopoCache.file(this, topo.topoId.toString())
        if (!file.exists()) return null

        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2

            BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                },
            )
        }.getOrNull()
    }

    private fun describeTopo(): String {
        val topo = topos.getOrNull(index) ?: return ""
        return resources.getQuantityString(
            R.plurals.topo_lines, topo.lines.size, topo.lines.size,
        )
    }

    companion object {
        /** The crag's id. Preferred: names are not unique. */
        const val EXTRA_ID = "crag_id"
        const val EXTRA_AREA = "area"
        const val EXTRA_TOPO = "topo"
        const val EXTRA_CLIMB_URL = "climb"

        /** Narrows the topos to those matching this text. */
        const val EXTRA_FILTER = "filter"

        /** Matches what the topo cache has always decoded to. */
        private const val MAX_EDGE = 2048
    }
}
