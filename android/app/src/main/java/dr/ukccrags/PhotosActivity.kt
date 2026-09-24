package dr.ukccrags

import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import dr.ukccrags.databinding.ActivityPhotosBinding
import dr.ukccrags.databinding.ItemPhotoBinding
import java.util.concurrent.Executors

/**
 * Saved photos, one to a screen, swiped through.
 *
 * Everything here is on disk already — this screen never goes to UKC — so it
 * works at the boulder. A climb's photos when opened from a climb, otherwise
 * everything saved for the crag.
 */
class PhotosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotosBinding
    private var cragId = ""
    private var photos: List<SavedPhoto> = emptyList()

    /** A handful decoded either side of the one on screen; photos are big. */
    private val decoded = LruCache<String, Bitmap>(6)
    private val loader = Executors.newFixedThreadPool(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPhotosBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE)
        binding.toolbar.setNavigationOnClickListener { finish() }

        cragId = intent.getStringExtra(EXTRA_CRAG_ID).orEmpty()
        val climbId = intent.getLongExtra(EXTRA_CLIMB_ID, ALL)

        photos = if (climbId == ALL) PhotoCache.allPhotos(this, cragId)
        else PhotoCache.photos(this, cragId, climbId)

        binding.empty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE

        val layout = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.pager.layoutManager = layout
        binding.pager.adapter = PhotoAdapter()
        PagerSnapHelper().attachToRecyclerView(binding.pager)

        binding.pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(view: RecyclerView, state: Int) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) showPosition(layout)
            }
        })

        showPosition(layout)
    }

    private fun showPosition(layout: LinearLayoutManager) {
        if (photos.isEmpty()) return

        val at = layout.findFirstCompletelyVisibleItemPosition().coerceAtLeast(0)
        supportActionBar?.subtitle = getString(R.string.photo_of, at + 1, photos.size)
    }

    override fun onDestroy() {
        loader.shutdownNow()
        decoded.evictAll()
        super.onDestroy()
    }

    private inner class PhotoAdapter : RecyclerView.Adapter<PhotoHolder>() {

        override fun getItemCount(): Int = photos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoHolder =
            PhotoHolder(ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: PhotoHolder, position: Int) =
            holder.bind(photos[position])
    }

    private inner class PhotoHolder(private val item: ItemPhotoBinding) :
        RecyclerView.ViewHolder(item.root) {

        fun bind(photo: SavedPhoto) {
            item.caption.text = photo.caption
            item.caption.visibility = if (photo.caption.isBlank()) View.GONE else View.VISIBLE
            item.image.tag = photo.id

            val ready = decoded.get(photo.id)
            if (ready != null) {
                item.image.setImageBitmap(ready)
                return
            }

            item.image.setImageDrawable(null)

            // Decoding a photo takes long enough to stutter a swipe, so it is
            // done off the main thread and dropped if the row has moved on.
            loader.execute {
                val bitmap = PhotoCache.load(this@PhotosActivity, cragId, photo.id) ?: return@execute

                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    decoded.put(photo.id, bitmap)
                    if (item.image.tag == photo.id) item.image.setImageBitmap(bitmap)
                }
            }
        }
    }

    companion object {
        const val EXTRA_CRAG_ID = "crag_id"
        const val EXTRA_CLIMB_ID = "climb_id"
        const val EXTRA_TITLE = "title"

        private const val ALL = -1L
    }
}
