package dr.ukccrags

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dr.ukccrags.databinding.DialogClimbBinding

/**
 * What UKC says about one climb, then what can be done with it.
 *
 * Shared by the crag's list and its topos: a line tapped on a photo is the
 * same climb as the row in the list, and it used to lead nowhere.
 */
object ClimbDialog {

    /**
     * [fromTopo] hides "Show on topo", which from the topo screen would only
     * reopen what is already there. [onChanged] runs after the reader changes
     * the climb's to-log note, so the screen behind can redraw its mark.
     */
    fun show(
        activity: Activity,
        crag: Crag,
        climb: Climb,
        fromTopo: Boolean = false,
        onChanged: () -> Unit = {},
    ) {
        val view = DialogClimbBinding.inflate(activity.layoutInflater)
        val ticks = Ticks(activity)
        val attempts = Attempts(activity)
        val wishlist = Wishlist(activity)
        val toLog = ToLog(activity)
        val res = activity.resources

        view.detail.text = buildString {
            append(climb.grade.ifBlank { "—" })
            if (climb.type.isNotBlank()) append(" · ").append(climb.type)
            if (climb.stars > 0) append(" · ").append("★".repeat(climb.stars))
            if (climb.height > 0) append(" · ").append(activity.getString(R.string.climb_height, climb.height))
            if (climb.pitches > 1) {
                append(" · ").append(activity.getString(R.string.climb_pitches, climb.pitches))
            }
            when {
                ticks.has(climb.url) -> append(" · ").append(activity.getString(R.string.ticked))
                toLog.has(climb.url) -> append(" · ").append(activity.getString(R.string.sent_to_log))
                attempts.has(climb.url) -> append(" · ").append(activity.getString(R.string.attempted))
            }
            if (wishlist.has(climb.url)) append(" · ").append(activity.getString(R.string.on_wishlist))
        }

        view.description.text = climb.description.ifBlank { activity.getString(R.string.no_description) }
        view.description.alpha = if (climb.description.isBlank()) 0.6f else 1f
        view.description.showLinks()

        // Nothing here is a choice to confirm, so the one button just closes it.
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(climb.name)
            .setView(view.root)
            .setNegativeButton(R.string.close, null)
            .show()

        val onTopo = climb.climbId > 0L &&
            crag.topos.any { topo -> topo.lines.any { it.climbId == climb.climbId } }

        view.topo.visibility = if (onTopo && !fromTopo) View.VISIBLE else View.GONE
        view.topo.setOnClickListener {
            dialog.dismiss()
            activity.startActivity(
                Intent(activity, TopoActivity::class.java)
                    .putExtra(TopoActivity.EXTRA_ID, crag.id)
                    .putExtra(TopoActivity.EXTRA_AREA, crag.area)
                    .putExtra(TopoActivity.EXTRA_CLIMB_URL, climb.url)
            )
        }

        // Saved photos open here, with no signal needed; otherwise on UKC.
        val saved = if (climb.climbId > 0) PhotoCache.photos(activity, crag.id, climb.climbId) else emptyList()

        view.photos.visibility = if (climb.photos > 0 || saved.isNotEmpty()) View.VISIBLE else View.GONE
        view.photos.text = if (saved.isNotEmpty()) {
            res.getQuantityString(R.plurals.photos_saved_n, saved.size, saved.size)
        } else {
            res.getQuantityString(R.plurals.see_photos_n, climb.photos, climb.photos)
        }
        view.photos.setOnClickListener {
            dialog.dismiss()

            if (saved.isEmpty()) {
                Maps.openUrl(activity, climb.url + "#photos")
                return@setOnClickListener
            }

            activity.startActivity(
                Intent(activity, PhotosActivity::class.java)
                    .putExtra(PhotosActivity.EXTRA_CRAG_ID, crag.id)
                    .putExtra(PhotosActivity.EXTRA_CLIMB_ID, climb.climbId)
                    .putExtra(PhotosActivity.EXTRA_TITLE, climb.name)
            )
        }

        view.open.setOnClickListener {
            dialog.dismiss()
            Maps.openUrl(activity, climb.url)
        }

        view.share.setOnClickListener {
            dialog.dismiss()
            activity.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, climb.name)
                        putExtra(
                            Intent.EXTRA_TEXT,
                            activity.getString(
                                R.string.share_climb_text,
                                climb.name, climb.grade, crag.area, climb.url,
                            ),
                        )
                    },
                    activity.getString(R.string.share_via),
                )
            )
        }

        // A tick is the logbook's word, so a ticked climb needs no note.
        view.sent.visibility = if (ticks.has(climb.url)) View.GONE else View.VISIBLE
        view.sent.setText(if (toLog.has(climb.url)) R.string.unmark_sent else R.string.mark_sent)
        view.sent.setOnClickListener {
            dialog.dismiss()
            if (toLog.toggle(climb.url)) {
                Toast.makeText(activity, R.string.marked_sent, Toast.LENGTH_SHORT).show()
            }
            onChanged()
        }

        view.log.setOnClickListener {
            dialog.dismiss()
            openLogFlow(activity, climb.url)
        }
    }

    /**
     * Opens the climb's own UKC page, which carries its "Add to Logbook"
     * button. The browser screen finds that button and stops there: the
     * press that writes to the logbook is always the reader's.
     */
    fun openLogFlow(activity: Activity, climbUrl: String) {
        activity.startActivity(
            Intent(activity, BrowseActivity::class.java)
                .putExtra(BrowseActivity.EXTRA_URL, climbUrl)
                .putExtra(BrowseActivity.EXTRA_LOG_CLIMB, true)
        )
    }
}
