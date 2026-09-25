package dr.ukccrags

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dr.ukccrags.databinding.ActivitySearchBinding
import dr.ukccrags.databinding.ItemFoundBinding
import org.json.JSONArray
import org.json.JSONObject

/**
 * One list of climbs, in the list's own order: a UKC ticklist, the wishlist,
 * or the climbs marked as sent and waiting to be logged.
 *
 * Searching the library at large belongs to the crag list, whose search box
 * reads crag and climb names together. This screen exists because each of
 * these is an order or a selection no filter over the library reproduces.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding

    private var pool: List<ClimbHit> = emptyList()

    private lateinit var ticks: Ticks
    private lateinit var toLog: ToLog

    private val adapter = FoundAdapter()

    /** The ticklist being shown, or null for the wishlist, to-log, or a lost list. */
    private var list: Ticklist? = null

    private var mode: String = MODE_LIST

    /** Bumped per load, so a slow one cannot overwrite a newer one. */
    private var loading = 0

    /**
     * The bouldering grade system rows are drawn in, read on each resume: a
     * change in Settings redraws rows whose data did not change, which a diff
     * alone would never do.
     */
    private var gradeSystem = BoulderGrades.FONT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gradeSystem = BoulderGrades.system(this)

        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root, ime = true)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        ticks = Ticks(this)
        toLog = ToLog(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LIST

        supportActionBar?.title = when (mode) {
            MODE_WISHLIST -> getString(R.string.wishlist)
            MODE_TO_LOG -> getString(R.string.to_log)
            else -> getString(R.string.ticklists)
        }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.query.doAfterTextChanged { render() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (list == null) return false

        menuInflater.inflate(R.menu.search, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.import_list) {
            confirmImport()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    /**
     * A ticklist names climbs across crags the library may not hold. Their
     * crag URLs come straight out of the climb URLs, so the missing ones can
     * be imported without searching UKC for them.
     */
    private fun confirmImport() {
        val current = list ?: return

        val missing = Lists.cragsIn(current)
            .filterNot { CragStore.has(this, cragIdIn(it.second)) }

        if (missing.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(current.name)
                .setMessage(R.string.list_all_held)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(current.name)
            .setMessage(resources.getQuantityString(R.plurals.list_import_ask, missing.size, missing.size))
            .setPositiveButton(R.string.import_list_crags) { _, _ -> startImport(missing) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun cragIdIn(url: String): String =
        Regex("-(\\d+)/?$").find(url)?.groupValues?.get(1).orEmpty()

    private fun startImport(crags: List<Pair<String, String>>) {
        val payload = JSONArray()

        for ((name, url) in crags) {
            payload.put(JSONObject().put("name", name).put("url", url))
        }

        startActivity(
            Intent(this, BrowseActivity::class.java)
                .putExtra(BrowseActivity.EXTRA_IMPORT, payload.toString())
        )
    }

    /**
     * Read again on every return: an import may have filled in crags the list
     * was missing, a sync may have ticked some, and a log may have cleared a
     * to-log note. Off the main thread, and diffed in, so the place in the
     * list is kept.
     */
    override fun onResume() {
        super.onResume()
        BoulderGrades.system(this).let {
            if (it != gradeSystem) {
                gradeSystem = it
                adapter.notifyDataSetChanged()
            }
        }
        load()
    }

    private fun load() {
        val generation = ++loading
        val listUrl = intent.getStringExtra(EXTRA_LIST)
        val app = applicationContext

        Thread {
            // The library may still be opening if this is the first screen
            // back after the process was restarted.
            if (!CragStore.ready) CragStore.open(app)

            var found: Ticklist? = null
            val held = when (mode) {
                MODE_WISHLIST -> CragDb.climbsByUrl(app, Wishlist(app).all())
                    .sortedWith(compareBy({ it.cragArea.lowercase() }, { it.name.lowercase() }))
                MODE_TO_LOG -> CragDb.climbsByUrl(app, ToLog(app).all())
                    .sortedWith(compareBy({ it.cragArea.lowercase() }, { it.name.lowercase() }))
                else -> {
                    found = listUrl?.let { url -> Lists.load(app).firstOrNull { it.url == url } }
                    found?.let { Lists.climbsIn(app, it) }.orEmpty()
                }
            }

            runOnUiThread {
                if (isDestroyed || generation != loading) return@runOnUiThread

                val firstLoad = list == null && found != null
                list = found
                pool = held

                if (mode == MODE_LIST) supportActionBar?.title = found?.name ?: getString(R.string.ticklists)
                if (firstLoad) invalidateOptionsMenu()

                render()
            }
        }.start()
    }

    private fun render() {
        val wanted = binding.query.text?.toString().orEmpty().trim().lowercase()

        val shown = if (wanted.isEmpty()) {
            pool
        } else {
            pool.filter { hit ->
                "${hit.name} ${hit.grade} ${BoulderGrades.show(hit.grade, hit.type, gradeSystem)} ${hit.cragArea}".lowercase().contains(wanted)
            }
        }

        binding.count.text = when {
            pool.isEmpty() && mode == MODE_TO_LOG -> getString(R.string.to_log_empty)
            pool.isEmpty() && mode == MODE_WISHLIST -> getString(R.string.wishlist_empty)
            shown.isEmpty() -> getString(R.string.no_matches)
            mode == MODE_TO_LOG ->
                resources.getQuantityString(R.plurals.climbs, shown.size, shown.size) +
                    " · " + getString(R.string.to_log_hint)
            else -> resources.getQuantityString(R.plurals.climbs, shown.size, shown.size)
        }

        adapter.submitKeepingPlace(shown.map { Found(it, ticks.has(it.url), toLog.has(it.url)) }, { it.hit.url })
    }

    /** A hit and the marks it is drawn with, so a change of mark redraws the row. */
    private data class Found(val hit: ClimbHit, val done: Boolean, val sent: Boolean)

    private fun openCrag(hit: ClimbHit) {
        startActivity(CragActivity.intent(this, hit.cragId, hit.cragArea, hit.url))
    }

    /** A to-log row held: open its crag, or drop the note without logging. */
    private fun toLogActions(hit: ClimbHit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(hit.name)
            .setItems(
                arrayOf(
                    getString(R.string.log_ascent),
                    getString(R.string.open_crag),
                    getString(R.string.remove_to_log),
                )
            ) { _, which ->
                when (which) {
                    0 -> ClimbDialog.openLogFlow(this, hit.url)
                    1 -> openCrag(hit)
                    else -> {
                        toLog.remove(hit.url)
                        load()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class FoundAdapter : ListAdapter<Found, FoundAdapter.Holder>(FoundDiff) {

        inner class Holder(val item: ItemFoundBinding) : RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemFoundBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (hit, done, sent) = getItem(position)

            holder.item.name.text = hit.name
            holder.item.grade.text = BoulderGrades.show(hit.grade, hit.type, gradeSystem)
            holder.item.meta.text = buildString {
                append(hit.cragArea)
                if (hit.type.isNotBlank()) append(" · ").append(hit.type)
                if (hit.stars > 0) append(" · ").append("★".repeat(hit.stars))
                if (done) append(" · ").append(getString(R.string.ticked))
                else if (sent && mode != MODE_TO_LOG) append(" · ").append(getString(R.string.sent_to_log))
            }

            holder.item.name.alpha = if (done) 0.45f else 1f

            if (mode == MODE_TO_LOG) {
                // Straight to the climb's page, which stops at its logbook
                // button: the press that logs it is the reader's own.
                holder.item.root.setOnClickListener { ClimbDialog.openLogFlow(this@SearchActivity, hit.url) }
                holder.item.root.setOnLongClickListener { toLogActions(hit); true }
            } else {
                holder.item.root.setOnClickListener { openCrag(hit) }
                holder.item.root.setOnLongClickListener(null)
            }
        }
    }

    private object FoundDiff : DiffUtil.ItemCallback<Found>() {
        override fun areItemsTheSame(old: Found, new: Found): Boolean = old.hit.url == new.hit.url
        override fun areContentsTheSame(old: Found, new: Found): Boolean = old == new
    }

    companion object {
        /** Set to a ticklist's URL to show that list. */
        const val EXTRA_LIST = "list"

        /** Which list: a ticklist (the default), the wishlist, or to-log. */
        const val EXTRA_MODE = "mode"

        const val MODE_LIST = "list"
        const val MODE_WISHLIST = "wishlist"
        const val MODE_TO_LOG = "to_log"

        fun intent(context: Context, mode: String): Intent =
            Intent(context, SearchActivity::class.java).putExtra(EXTRA_MODE, mode)
    }
}
