package dr.ukccrags

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dr.ukccrags.databinding.ActivitySearchBinding
import dr.ukccrags.databinding.ItemFoundBinding

/**
 * The reader's UKC ticklists, with the wishlist and the phone's own to-log
 * list above them as two more lists.
 *
 * A list names climbs the app may not hold: only those in imported crags can
 * be opened, so each row says how many of its climbs are actually here.
 */
class ListsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding

    private val adapter = ListRowAdapter()

    /** Bumped per load, so a slow one cannot overwrite a newer one. */
    private var loading = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.ticklists)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // The search box belongs to the climb screen, not to a list of lists.
        (binding.query.parent.parent as? View)?.visibility = View.GONE

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
    }

    /** One row: a ticklist, or one of the two lists the app keeps itself. */
    private data class ListRow(
        val key: String,
        val name: String,
        val done: Int,
        val total: Int,
        val held: Int,
        val summary: String?,
        val open: Intent,
    )

    /**
     * Counted off the main thread: each list is a lookup of its climbs in the
     * library, and there can be a few dozen lists. Diffed in, so coming back
     * from a list keeps the place in this one.
     */
    override fun onResume() {
        super.onResume()

        val generation = ++loading
        val app = applicationContext

        Thread {
            if (!CragStore.ready) CragStore.open(app)

            val ticks = Ticks(app)
            val lists = Lists.load(app)
            val toLog = ToLog(app).all()
            val wished = Wishlist(app).all()

            val rows = mutableListOf<ListRow>()

            rows += ListRow(
                key = SearchActivity.MODE_TO_LOG,
                name = getString(R.string.to_log),
                done = 0,
                total = toLog.size,
                held = CragDb.climbsByUrl(app, toLog).size,
                summary = getString(R.string.to_log_summary),
                open = SearchActivity.intent(this, SearchActivity.MODE_TO_LOG),
            )

            if (wished.isNotEmpty()) {
                val held = CragDb.climbsByUrl(app, wished)
                rows += ListRow(
                    key = SearchActivity.MODE_WISHLIST,
                    name = getString(R.string.wishlist),
                    done = held.count { ticks.has(it.url) },
                    total = wished.size,
                    held = held.size,
                    summary = null,
                    open = SearchActivity.intent(this, SearchActivity.MODE_WISHLIST),
                )
            }

            for (list in lists) {
                val held = Lists.climbsIn(app, list)
                rows += ListRow(
                    key = list.url,
                    name = list.name,
                    done = held.count { ticks.has(it.url) },
                    total = list.climbs.size,
                    held = held.size,
                    summary = null,
                    open = Intent(this, SearchActivity::class.java)
                        .putExtra(SearchActivity.EXTRA_LIST, list.url),
                )
            }

            runOnUiThread {
                if (isDestroyed || generation != loading) return@runOnUiThread

                binding.count.text = if (lists.isEmpty()) {
                    getString(R.string.no_lists)
                } else {
                    resources.getQuantityString(R.plurals.lists, lists.size, lists.size)
                }

                adapter.submitList(rows)
            }
        }.start()
    }

    private inner class ListRowAdapter : ListAdapter<ListRow, ListRowAdapter.Holder>(RowDiff) {

        inner class Holder(val item: ItemFoundBinding) : RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemFoundBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = getItem(position)

            holder.item.name.text = row.name

            // To-log has nothing to count as done: its climbs leave it when logged.
            holder.item.grade.text = if (row.key == SearchActivity.MODE_TO_LOG) {
                row.total.toString()
            } else {
                getString(R.string.list_done, row.done, row.total)
            }

            holder.item.meta.text = row.summary
                ?: resources.getQuantityString(R.plurals.list_held, row.total, row.held, row.total)

            holder.item.root.setOnClickListener { startActivity(row.open) }
        }
    }

    private object RowDiff : DiffUtil.ItemCallback<ListRow>() {
        override fun areItemsTheSame(old: ListRow, new: ListRow): Boolean = old.key == new.key

        // Intents do not compare by value, so the rest of the row decides.
        override fun areContentsTheSame(old: ListRow, new: ListRow): Boolean =
            old.copy(open = new.open) == new
    }
}
