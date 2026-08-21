package dr.ukccrags

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dr.ukccrags.databinding.ActivityLogBinding

/**
 * The app's own log, on the phone.
 *
 * A release build cannot be read with `run-as`, and the runs worth reading — a
 * region-wide read that stopped after an hour — happen at a crag or on a sofa,
 * not next to a laptop. This shows what [AppLog] recorded: what started, how
 * far it got, and why it stopped.
 *
 * It refreshes while open, so a running import can be watched here, and the
 * whole thing can be copied out for a bug report.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    private val refresh = android.os.Handler(android.os.Looper.getMainLooper())
    private val again = object : Runnable {
        override fun run() {
            draw(follow = true)
            refresh.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.debug_log)
        binding.toolbar.setNavigationOnClickListener { finish() }

        draw(follow = true)
    }

    override fun onResume() {
        super.onResume()
        refresh.postDelayed(again, REFRESH_MS)
    }

    override fun onPause() {
        refresh.removeCallbacks(again)
        super.onPause()
    }

    private fun draw(follow: Boolean) {
        val text = AppLog.read(this).ifBlank { getString(R.string.log_empty) }

        // Setting the same text again is what threw the view back to the top
        // every second and a half. Nothing new, nothing touched.
        if (text == shown) return

        // Whether to follow has to be decided before the text changes, since
        // replacing it moves the scroll position under us.
        val wasAtBottom = follow && !binding.scroll.canScrollVertically(1)

        shown = text
        binding.log.text = text

        if (!wasAtBottom && !first) return

        first = false
        binding.scroll.post { binding.scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    /** What the view is already showing, so it is not rewritten needlessly. */
    private var shown: String? = null

    /** The first draw follows regardless: the newest line is the point. */
    private var first = true

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, COPY, 0, R.string.log_copy)
        menu.add(0, CLEAR, 1, R.string.log_clear)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            COPY -> {
                val clip = getSystemService(ClipboardManager::class.java)
                clip?.setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.debug_log), AppLog.read(this))
                )
                Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
                return true
            }
            CLEAR -> {
                AppLog.clear(this)
                draw(follow = true)
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }

    private companion object {
        const val COPY = 1
        const val CLEAR = 2

        /** Often enough to watch an import by, cheap enough to ignore. */
        const val REFRESH_MS = 1500L
    }
}
