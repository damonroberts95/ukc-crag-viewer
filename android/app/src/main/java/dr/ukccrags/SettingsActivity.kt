package dr.ukccrags

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dr.ukccrags.databinding.ActivitySettingsBinding
import dr.ukccrags.databinding.ItemSettingBinding
import dr.ukccrags.databinding.ItemSettingSwitchBinding
import java.io.File

/**
 * The choices worth having and the storage worth knowing about.
 *
 * Built by hand from rows rather than with the preference library: a dozen
 * rows do not justify a dependency, and the storage rows are actions with
 * confirmations rather than stored values anyway.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.settings)
        binding.toolbar.setNavigationOnClickListener { finish() }

        toggle(
            binding.directionsParking,
            R.string.settings_directions_parking,
            R.string.settings_directions_parking_summary,
            Settings.directionsToParking(this),
        ) { Settings.setDirectionsToParking(this, it) }

        toggle(
            binding.showParking,
            R.string.show_parking,
            R.string.settings_show_parking_summary,
            Settings.showParking(this),
        ) { Settings.setShowParking(this, it) }

        toggle(
            binding.weeklySync,
            R.string.settings_weekly_sync,
            R.string.settings_weekly_sync_summary,
            Settings.weeklySync(this),
        ) { Settings.setWeeklySync(this, it) }

        binding.units.title.setText(R.string.settings_units)
        binding.units.root.setOnClickListener { chooseUnits() }

        binding.clearMap.title.setText(R.string.settings_clear_map)
        binding.clearMap.root.setOnClickListener { confirmClearMap() }

        binding.clearPhotos.title.setText(R.string.settings_clear_photos)
        binding.clearPhotos.root.setOnClickListener { confirmClearPhotos() }

        // Topos come with each crag and cannot be fetched again without
        // re-reading it, so they are reported here rather than offered up.
        binding.topos.title.setText(R.string.settings_topos)
        binding.topos.root.isClickable = false

        row(binding.guide, R.string.settings_guide, R.string.settings_guide_summary) {
            Guide.show(this)
        }

        row(binding.log, R.string.debug_log, R.string.settings_log_summary) {
            startActivity(Intent(this, LogActivity::class.java))
        }

        binding.updates.title.setText(R.string.check_updates)
        binding.updates.summary.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
        binding.updates.root.setOnClickListener { Updates.check(this) }

        showStorage()
        showUnits()
    }

    private fun toggle(
        row: ItemSettingSwitchBinding,
        title: Int,
        summary: Int,
        on: Boolean,
        save: (Boolean) -> Unit,
    ) {
        row.title.setText(title)
        row.summary.setText(summary)
        row.toggle.isChecked = on
        row.root.setOnClickListener {
            row.toggle.isChecked = !row.toggle.isChecked
            save(row.toggle.isChecked)
        }
    }

    private fun row(row: ItemSettingBinding, title: Int, summary: Int, action: () -> Unit) {
        row.title.setText(title)
        row.summary.setText(summary)
        row.root.setOnClickListener { action() }
    }

    /** Sizes are a walk of the disk, so they are worked out off the main thread. */
    private fun showStorage() {
        Thread {
            val map = MapSources.cachedMegabytes(this)
            val photos = PhotoCache.bytes(this) / MB
            val topos = TopoCache.bytes(this) / MB
            val crags = CragStore.count(this)

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.clearMap.summary.text = getString(R.string.settings_clear_map_summary, map)
                binding.clearPhotos.summary.text = getString(R.string.settings_clear_photos_summary, photos)
                binding.topos.summary.text = getString(R.string.settings_topos_summary, topos, crags)
            }
        }.start()
    }

    private fun showUnits() {
        binding.units.summary.setText(
            when (Settings.units(this)) {
                Settings.UNITS_MILES -> R.string.units_miles
                Settings.UNITS_KM -> R.string.units_km
                else -> R.string.units_auto
            }
        )
    }

    private fun chooseUnits() {
        val order = listOf(Settings.UNITS_AUTO, Settings.UNITS_MILES, Settings.UNITS_KM)
        val labels = arrayOf(
            getString(R.string.units_auto),
            getString(R.string.units_miles),
            getString(R.string.units_km),
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_units)
            .setSingleChoiceItems(labels, order.indexOf(Settings.units(this))) { dialog, which ->
                Settings.setUnits(this, order[which])
                showUnits()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClearMap() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_clear_map)
            .setMessage(R.string.settings_clear_map_warning)
            .setPositiveButton(R.string.clear) { _, _ -> clearMap() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * osmdroid keeps its tiles in one SQLite file and holds it open between
     * maps. Emptying it through osmdroid first and closing it means the file
     * can then go without a map still writing into a deleted inode; deleting
     * the file is what actually gives the space back, since SQLite does not
     * shrink on its own.
     */
    private fun clearMap() {
        Thread {
            runCatching {
                org.osmdroid.tileprovider.modules.SqlTileWriter().apply {
                    purgeCache()
                    onDetach()
                }
            }

            File(filesDir, "osm/tiles").apply {
                deleteRecursively()
                mkdirs()
            }

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                Toast.makeText(this, R.string.settings_map_cleared, Toast.LENGTH_SHORT).show()
                showStorage()
            }
        }.start()
    }

    private fun confirmClearPhotos() {
        if (PhotoFetch.running()) {
            Toast.makeText(this, R.string.photos_busy, Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_clear_photos)
            .setMessage(R.string.settings_clear_photos_warning)
            .setPositiveButton(R.string.clear) { _, _ ->
                PhotoCache.clearAll(this)
                showStorage()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
