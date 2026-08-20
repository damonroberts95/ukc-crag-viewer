package dr.ukccrags

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import dr.ukccrags.databinding.ActivitySearchBinding
import dr.ukccrags.databinding.ItemFoundBinding

/**
 * One ticklist's climbs, in the list's own order.
 *
 * Searching the library at large belongs to the crag list, whose search box
 * reads crag and climb names together. This screen exists because a ticklist
 * is an order UKC chose, which no filter over the library can reproduce.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding

    private var pool: List<Pair<Crag, Climb>> = emptyList()
    private var shown: List<Pair<Crag, Climb>> = emptyList()

    private lateinit var ticks: Ticks

    /** The list being shown, or null if the intent named one the app has lost. */
    private var list: Ticklist? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        ticks = Ticks(this)

        val listUrl = intent.getStringExtra(EXTRA_LIST)
        val found = listUrl?.let { url -> Lists.load(this).firstOrNull { it.url == url } }

        list = found
        supportActionBar?.title = found?.name ?: getString(R.string.ticklists)
        pool = found?.let { Lists.climbsIn(it, CragStore.load(this)) }.orEmpty()

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.query.doAfterTextChanged { render(it?.toString().orEmpty()) }

        render("")
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
            .setMessage(getString(R.string.list_import_ask, missing.size))
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

    override fun onResume() {
        super.onResume()

        // An import may have filled in crags this list was missing.
        list?.let { current ->
            pool = Lists.climbsIn(current, CragStore.load(this))
            render(binding.query.text?.toString().orEmpty())
        }
    }

    private fun render(query: String) {
        val wanted = query.trim().lowercase()

        shown = if (wanted.isEmpty()) {
            pool
        } else {
            pool.filter { (crag, climb) ->
                "${climb.name} ${climb.grade} ${crag.area}".lowercase().contains(wanted)
            }
        }

        binding.count.text = if (shown.isEmpty()) {
            getString(R.string.no_matches)
        } else {
            resources.getQuantityString(R.plurals.climbs, shown.size, shown.size)
        }

        binding.list.adapter = FoundAdapter()
    }

    private inner class FoundAdapter : RecyclerView.Adapter<FoundAdapter.Holder>() {

        inner class Holder(val item: ItemFoundBinding) : RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemFoundBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = shown.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val (crag, climb) = shown[position]
            val done = ticks.has(climb.url)

            holder.item.name.text = climb.name
            holder.item.grade.text = climb.grade
            holder.item.meta.text = buildString {
                append(crag.area)
                if (climb.type.isNotBlank()) append(" · ").append(climb.type)
                if (climb.stars > 0) append(" · ").append("★".repeat(climb.stars))
                if (done) append(" · ").append(getString(R.string.ticked))
            }

            holder.item.name.alpha = if (done) 0.45f else 1f

            // Opens the crag with this climb already filtered for.
            holder.item.root.setOnClickListener {
                startActivity(
                    Intent(this@SearchActivity, CragActivity::class.java)
                        .putExtra(CragActivity.EXTRA_AREA, crag.area)
                        .putExtra(CragActivity.EXTRA_FIND, climb.name)
                )
            }
        }
    }

    companion object {
        /** Set to a ticklist's URL to search only that list. */
        const val EXTRA_LIST = "list"
    }
}
