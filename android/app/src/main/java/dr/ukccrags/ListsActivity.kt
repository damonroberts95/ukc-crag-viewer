package dr.ukccrags

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dr.ukccrags.databinding.ActivitySearchBinding
import dr.ukccrags.databinding.ItemFoundBinding

/**
 * The reader's UKC ticklists, plus the wishlist as one more list.
 *
 * A list names climbs the app may not hold: only those in imported crags can
 * be opened, so each row says how many of its climbs are actually here.
 */
class ListsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding

    private var lists: List<Ticklist> = emptyList()

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
    }

    override fun onResume() {
        super.onResume()

        lists = Lists.load(this)

        binding.count.text = if (lists.isEmpty()) {
            getString(R.string.no_lists)
        } else {
            resources.getQuantityString(R.plurals.lists, lists.size, lists.size)
        }

        binding.list.adapter = ListAdapter()
    }

    private inner class ListAdapter : RecyclerView.Adapter<ListAdapter.Holder>() {

        private val crags = CragStore.load(this@ListsActivity)
        private val ticks = Ticks(this@ListsActivity)

        inner class Holder(val item: ItemFoundBinding) : RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemFoundBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = lists.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val list = lists[position]
            val held = Lists.climbsIn(list, crags)
            val done = held.count { ticks.has(it.second.url) }

            holder.item.name.text = list.name
            holder.item.grade.text = "$done/${list.climbs.size}"
            holder.item.meta.text = getString(R.string.list_held, held.size, list.climbs.size)

            holder.item.root.setOnClickListener {
                startActivity(
                    Intent(this@ListsActivity, SearchActivity::class.java)
                        .putExtra(SearchActivity.EXTRA_LIST, list.url)
                )
            }
        }
    }
}
